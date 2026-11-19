package dev.herdrbridge.nativeclient;
import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.io.*;
import java.security.KeyStore;
import java.util.UUID;
import static org.junit.Assert.*;
public class DeviceVaultTest {
 @Test public void roundTripWrongAuthorityCorruptionAndLostKeyFailClosed()throws Exception {
  Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
  String id=UUID.randomUUID().toString(),alias="bridge-native-test-"+id,name="test-"+id+".enc";
  File path=new File(context.getNoBackupFilesDir(),name);
  DeviceVault vault=new DeviceVault(context,name,alias);
  try(CredentialRecord record=new CredentialRecord("https://example.test","AAAAAAAAAAAAAAAAAAAAAAAA","BBBBBBBBBBBBBBBBBBBBBBBB",1,"reader",new byte[32],"AAAAAAAAAAAAAAAAAAAAAA",new byte[32])) {
   vault.save(record);assertTrue(vault.hasRecord());
   try(CredentialRecord loaded=vault.load(record.origin)){assertEquals(record.deviceId,loaded.deviceId);}
   try{vault.load("https://other.test");fail();}catch(IOException expected){}
   KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);store.deleteEntry(alias);
   try{vault.load(record.origin);fail();}catch(IOException expected){}
   try{vault.save(record);fail();}catch(IOException expected){}
   assertFalse(store.containsAlias(alias));
   try(FileOutputStream out=new FileOutputStream(path)){out.write(new byte[]{1,2,3});}
   try{vault.load(record.origin);fail();}catch(IOException expected){}
  } finally {
   new android.util.AtomicFile(path).delete();
   KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);store.deleteEntry(alias);
  }
 }
}
