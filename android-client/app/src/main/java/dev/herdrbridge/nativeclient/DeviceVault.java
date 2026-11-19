package dev.herdrbridge.nativeclient;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.*;
import java.security.*;
import java.util.Arrays;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/** One encrypted enrolled-device record. Missing/invalid keys never trigger reset or plaintext fallback. */
public final class DeviceVault implements CredentialStore {
    private final String alias;
    private final AtomicFile file;
    public DeviceVault(Context context) { this(context,"device-v1.enc","bridge-native-device-v1"); }
    DeviceVault(Context context,String filename,String alias) {
        if(!filename.matches("[A-Za-z0-9.-]{1,100}") || !alias.matches("[A-Za-z0-9-]{1,100}")) throw new IllegalArgumentException("Invalid vault scope");
        this.alias=alias;file=new AtomicFile(new File(context.getNoBackupFilesDir(),filename));
    }
    private SecretKey key(boolean mayCreate) throws GeneralSecurityException,IOException {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(store.containsAlias(alias)) {
            Key key=store.getKey(alias,null);
            if(!(key instanceof SecretKey)) throw new GeneralSecurityException("Invalid vault key");
            return (SecretKey)key;
        }
        if(!mayCreate) throw new GeneralSecurityException("Vault key unavailable; re-enrollment required");
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }
    public synchronized boolean hasRecord() { return file.getBaseFile().exists() || new File(file.getBaseFile()+".bak").exists(); }
    public synchronized void save(CredentialRecord record) throws IOException {
        byte[] plain=null;FileOutputStream out=null;
        try {
            if(hasRecord()) {
                try(CredentialRecord existing=load(record.origin)) {
                    if(!existing.deviceId.equals(record.deviceId)||!existing.credentialId.equals(record.credentialId)||!existing.relayId.equals(record.relayId)) throw new IOException("Existing device identity differs");
                }
            }
            plain=record.encode();byte[] sealed=VaultEnvelope.seal(key(!hasRecord()),record.origin,plain);
            out=file.startWrite();out.write(sealed);file.finishWrite(out);out=null;
        } catch (GeneralSecurityException|RuntimeException e) { throw new IOException("Cannot protect device credential"); }
        finally { if(out!=null)file.failWrite(out);if(plain!=null)Arrays.fill(plain,(byte)0); }
    }
    public synchronized CredentialRecord load(String configuredOrigin) throws IOException {
        byte[] plain=null;
        try(FileInputStream in=file.openRead();ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
            byte[] block=new byte[1024];int n;
            while((n=in.read(block))!=-1) { if(bytes.size()+n>VaultEnvelope.MAX_BYTES)throw new IOException("Vault exceeds limit");bytes.write(block,0,n); }
            plain=VaultEnvelope.open(key(false),configuredOrigin,bytes.toByteArray());
            CredentialRecord record=CredentialRecord.decode(plain);
            if(!record.origin.equals(InvitationValidator.origin(configuredOrigin))) {record.close();throw new IOException("Vault authority mismatch");}
            return record;
        } catch(GeneralSecurityException|RuntimeException e) { throw new IOException("Device vault unavailable or authentication failed"); }
        finally {if(plain!=null)Arrays.fill(plain,(byte)0);}
    }
}
