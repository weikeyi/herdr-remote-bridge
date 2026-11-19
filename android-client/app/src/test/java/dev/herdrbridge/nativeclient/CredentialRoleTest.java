package dev.herdrbridge.nativeclient;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
public class CredentialRoleTest {
    private static final String ID="AAAAAAAAAAAAAAAAAAAAAAAA", RELAY="AAAAAAAAAAAAAAAAAAAAAA";
    @Test public void versionTwoPreservesExactRole()throws Exception {
        for(String role:new String[]{"reader","controller"})try(CredentialRecord r=new CredentialRecord("https://example.test",ID,ID,1,role,new byte[32],RELAY,new byte[32]);CredentialRecord decoded=CredentialRecord.decode(r.encode())){assertEquals(role,decoded.role);}
        assertThrows(IllegalArgumentException.class,()->new CredentialRecord("https://example.test",ID,ID,1,"admin",new byte[32],RELAY,new byte[32]));
    }
    @Test public void legacyVersionOneIsAlwaysReader()throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        out.writeInt(1);out.writeUTF("https://example.test");out.writeUTF(ID);out.writeUTF(ID);out.writeLong(1);out.writeUTF(RELAY);out.write(new byte[64]);
        try(CredentialRecord decoded=CredentialRecord.decode(bytes.toByteArray())){assertEquals("reader",decoded.role);}
    }
}
