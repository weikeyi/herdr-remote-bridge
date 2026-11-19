package dev.herdrbridge.nativeclient;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class VaultEnvelope {
    static final int MAX_BYTES=16432;
    private static byte[] aad(String origin) { return ("BridgeNativeVault-v1\u0000"+InvitationValidator.origin(origin)).getBytes(StandardCharsets.UTF_8); }
    static byte[] seal(SecretKey key,String origin,byte[] plain) throws GeneralSecurityException {
        if(plain.length==0||plain.length>16384) throw new GeneralSecurityException("Invalid credential length");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,key); cipher.updateAAD(aad(origin));
        byte[] iv=cipher.getIV();
        if(iv.length!=12) throw new GeneralSecurityException("Invalid vault nonce");
        byte[] sealed=cipher.doFinal(plain);
        return ByteBuffer.allocate(4+12+sealed.length).putInt(1).put(iv).put(sealed).array();
    }
    static byte[] open(SecretKey key,String origin,byte[] data) throws GeneralSecurityException {
        if(data.length<33||data.length>MAX_BYTES) throw new GeneralSecurityException("Invalid vault envelope");
        ByteBuffer buffer=ByteBuffer.wrap(data);
        if(buffer.getInt()!=1) throw new GeneralSecurityException("Unsupported vault envelope");
        byte[] iv=new byte[12];buffer.get(iv);byte[] sealed=new byte[buffer.remaining()];buffer.get(sealed);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,iv));cipher.updateAAD(aad(origin));
        return cipher.doFinal(sealed);
    }
}
