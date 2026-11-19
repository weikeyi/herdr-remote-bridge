package dev.herdrbridge.protocol;

import java.io.*;
import java.lang.reflect.Field;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import javax.crypto.Cipher;

public final class E2eeTest {
    private static final Properties V = new Properties();
    private static int checks;
    interface Checked { void run() throws Exception; }
    static void equal(Object a, Object b) {
        checks++;
        if (!Objects.equals(a,b)) throw new AssertionError("Mismatch");
    }
    static void bytes(byte[] a, byte[] b) { checks++; if (!Arrays.equals(a,b)) throw new AssertionError("Byte mismatch"); }
    static void rejects(Checked task) throws Exception {
        checks++;
        try { task.run(); } catch (GeneralSecurityException | IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected rejection");
    }
    static String text(String key) {
        return new String(Base64.getUrlDecoder().decode(V.getProperty(key)), StandardCharsets.UTF_8);
    }
    static byte[] raw(String key) { return Base64.getUrlDecoder().decode(text(key)); }
    static byte[] auth() { return E2ee.utf8(text("relay_key")); }
    static KeyPair pair() throws Exception {
        PrivateKey privateKey = KeyFactory.getInstance("EC").generatePrivate(
                new ECPrivateKeySpec(new BigInteger(1, raw("client.private_key")), E2ee.curve()));
        return new KeyPair(E2ee.importPublic(raw("client.public_key")), privateKey);
    }
    static E2ee.Handshake handshake() throws Exception {
        return new E2ee.Handshake("credential","credential-test",7,auth(),"en",pair(),raw("client.nonce"));
    }
    static E2ee.Session accept(E2ee.Handshake h) throws Exception {
        return h.acceptServerHello("e2ee_server_hello",2,text("server.nonce"),text("server.public_key"),text("server.proof"));
    }
    static E2ee.Session session() { return new E2ee.Session(raw("c2s_key"),raw("s2c_key")); }
    static byte[] serverFrame(long sequence, byte[] plain) throws Exception {
        byte[] c=E2ee.crypt(Cipher.ENCRYPT_MODE,raw("s2c_key"),"s2c",sequence,plain);
        return ByteBuffer.allocate(10+c.length).put((byte)2).put((byte)0).putLong(sequence).put(c).array();
    }
    public static void main(String[] args) throws Exception {
        try (Reader reader=Files.newBufferedReader(Paths.get(args[0]))) { V.load(reader); }
        E2ee.Handshake h=handshake();
        equal(h.hello.proof,text("client.proof"));
        equal(h.hello.publicKey,text("client.public_key"));
        byte[] transcript=E2ee.concat(E2ee.binding("credential","credential-test",7),
                raw("client.nonce"),raw("client.public_key"),raw("server.nonce"),raw("server.public_key"));
        bytes(transcript,raw("transcript"));
        bytes(E2ee.hmac(auth(),E2ee.utf8("herdr-e2ee-v2 server\0"),transcript),raw("server.proof"));
        bytes(E2ee.hmac(auth(),E2ee.utf8("herdr-e2ee-v2 key\0"),transcript),raw("key_salt"));
        bytes(E2ee.hkdf(raw("shared_secret"),raw("key_salt"),E2ee.utf8("herdr-e2ee-v2 c2s")),raw("c2s_key"));
        bytes(E2ee.hkdf(raw("shared_secret"),raw("key_salt"),E2ee.utf8("herdr-e2ee-v2 s2c")),raw("s2c_key"));
        E2ee.Session s=accept(h);
        bytes(s.encrypt(text("records.client_finish.plaintext")),raw("records.client_finish.binary_frame"));
        bytes(s.encrypt(text("records.c2s.plaintext")),raw("records.c2s.binary_frame"));
        equal(s.decrypt(raw("records.s2c.binary_frame")),text("records.s2c.plaintext"));
        rejects(()->s.decrypt(raw("records.s2c.binary_frame")));
        rejects(()->s.encrypt("closed after replay"));
        rejects(()->accept(h));

        for (String bad:new String[]{text("server.proof")+"=", "bad", E2ee.encode(new byte[32])}) {
            E2ee.Handshake b=handshake();
            rejects(()->b.acceptServerHello("e2ee_server_hello",2,text("server.nonce"),text("server.public_key"),bad));
            rejects(()->accept(b));
        }
        rejects(()->handshake().acceptServerHello("wrong",2,text("server.nonce"),text("server.public_key"),text("server.proof")));
        rejects(()->E2ee.importPublic(new byte[65]));
        byte[] offCurve=raw("server.public_key");offCurve[64]^=1;
        rejects(()->E2ee.importPublic(offCurve));
        byte[] outOfField=new byte[65];Arrays.fill(outOfField,(byte)255);outOfField[0]=4;
        rejects(()->E2ee.importPublic(outOfField));
        for(String kind:new String[]{"", "admin"}) rejects(()->E2ee.binding(kind,"id",1));
        rejects(()->E2ee.binding("credential","bad\0id",1));
        rejects(()->E2ee.binding("credential","\ud800",1));
        rejects(()->E2ee.binding("credential","id",0));
        rejects(()->E2ee.binding("credential","id",E2ee.MAX_SEQUENCE+1));
        rejects(()->E2ee.decode("AA=",1));
        rejects(()->E2ee.decode("AB",1)); // nonzero unused base64 bits
        bytes(E2ee.decode("AA",1),new byte[]{0});

        byte[] valid=raw("records.s2c.binary_frame");
        for (int index:new int[]{0,1,2,9,10,valid.length-1}) {
            byte[] bad=valid.clone();bad[index]^=1;
            E2ee.Session b=session();rejects(()->b.decrypt(bad));rejects(()->b.decrypt(valid));
        }
        for (int length:new int[]{0,1,9,10,25}) rejects(()->session().decrypt(new byte[length]));
        rejects(()->session().decrypt(serverFrame(1,E2ee.utf8("gap"))));
        E2ee.Session unicode=session();
        equal(unicode.decrypt(serverFrame(0,E2ee.utf8("中文 😀"))),"中文 😀");
        E2ee.Session invalid=session();
        rejects(()->invalid.decrypt(serverFrame(0,new byte[]{(byte)0xc0,(byte)0x80})));
        rejects(()->invalid.decrypt(valid));
        rejects(()->session().encrypt("\ud800"));
        rejects(()->session().decrypt(new byte[E2ee.MAX_PLAINTEXT_BYTES+27]));
        E2ee.Session limit=session();
        Field send=E2ee.Session.class.getDeclaredField("sendSequence");send.setAccessible(true);
        send.setLong(limit,E2ee.MAX_SEQUENCE);
        equal(ByteBuffer.wrap(limit.encrypt("last"),2,8).getLong(),E2ee.MAX_SEQUENCE);
        rejects(()->limit.encrypt("exhausted"));
        byte[] unsafe=valid.clone();ByteBuffer.wrap(unsafe,2,8).putLong(E2ee.MAX_SEQUENCE+1);
        rejects(()->session().decrypt(unsafe));
        E2ee.Session closed=session();closed.close();closed.close();rejects(()->closed.decrypt(valid));

        E2ee.Handshake random1=E2ee.start("invitation","id",1,E2ee.encode(auth()),"zh-CN");
        E2ee.Handshake random2=E2ee.start("invitation","id",1,E2ee.encode(auth()),"zh-CN");
        checks++; if(random1.hello.nonce.equals(random2.hello.nonce) ||
                random1.hello.publicKey.equals(random2.hello.publicKey))throw new AssertionError("Ephemeral reuse");
        random1.close();random2.close();
        System.out.println("PASS "+checks+" cryptographic fixture/negative checks");
    }
}
