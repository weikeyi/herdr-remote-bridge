package dev.herdrbridge.protocol;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.*;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Binary herdr-e2ee-v2 primitives. JSON/enrollment validation belongs to the caller. */
public final class E2ee {
    public static final String SUBPROTOCOL = "herdr-e2ee-v2";
    public static final long MAX_SEQUENCE = (1L << 53) - 1;
    // Local resource ceiling, not a claim about the upstream transport limit.
    public static final int MAX_PLAINTEXT_BYTES = 4 * 1024 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();
    private E2ee() {}

    public static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    public static byte[] decode(String value, int expectedSize) {
        if (value == null || value.length() > ((long) expectedSize * 4 + 2) / 3 ||
                !value.matches("[A-Za-z0-9_-]*")) throw new IllegalArgumentException("Invalid base64url");
        byte[] bytes = Base64.getUrlDecoder().decode(value);
        if (bytes.length != expectedSize || !encode(bytes).equals(value))
            throw new IllegalArgumentException("Invalid canonical base64url or length");
        return bytes;
    }

    public static final class Hello {
        public final String kind, id, locale, nonce, publicKey, proof;
        public final long authVersion;
        private Hello(String kind, String id, long version, String locale, byte[] nonce, byte[] key, byte[] proof) {
            this.kind = kind; this.id = id; this.authVersion = version; this.locale = locale;
            this.nonce = encode(nonce); this.publicKey = encode(key); this.proof = encode(proof);
        }
    }

    /** Fresh nonce and fresh P-256 keypair for every connection; never persist this object. */
    public static Handshake start(String kind, String id, long version, String secret, String locale)
            throws GeneralSecurityException {
        byte[] key = decode(secret, 32);
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
            byte[] nonce = new byte[32]; RANDOM.nextBytes(nonce);
            return new Handshake(kind, id, version, key, locale, generator.generateKeyPair(), nonce);
        } finally { Arrays.fill(key, (byte) 0); }
    }

    public static final class Handshake implements AutoCloseable {
        public final Hello hello;
        private byte[] auth, binding, nonce, publicBytes;
        private PrivateKey privateKey;
        private boolean consumed;

        // Package-private injection exists only for deterministic parity tests.
        Handshake(String kind, String id, long version, byte[] auth, String locale,
                  KeyPair pair, byte[] nonce) throws GeneralSecurityException {
            binding = binding(kind, id, version);
            if (auth.length != 32 || nonce.length != 32) throw new IllegalArgumentException("Invalid secret or nonce");
            this.auth = auth.clone(); this.nonce = nonce.clone();
            this.privateKey = pair.getPrivate(); this.publicBytes = rawPublic(pair.getPublic());
            String safeLocale = locale != null && locale.matches("[A-Za-z0-9-]{1,32}") ? locale : "en";
            hello = new Hello(kind, id, version, safeLocale, this.nonce, publicBytes,
                    hmac(this.auth, utf8("herdr-e2ee-v2 client\0"), binding, this.nonce, publicBytes));
        }

        /**
         * Returns an authenticated channel candidate, NOT completed enrollment.
         * Caller must first send the encrypted client finish, decrypt and strictly
         * validate server finish, then expose the channel to application messages.
         * Any failure consumes this handshake. Never reuse it on reconnect.
         */
        public synchronized Session acceptServerHello(String type, int version, String nonce,
                                                       String publicKey, String proof)
                throws GeneralSecurityException {
            if (consumed) throw new GeneralSecurityException("Handshake already consumed");
            consumed = true;
            byte[] shared = null, salt = null, send = null, receive = null;
            try {
                if (!"e2ee_server_hello".equals(type) || version != 2)
                    throw new GeneralSecurityException("Unsupported server hello");
                byte[] serverNonce = decode(nonce, 32), serverPublic = decode(publicKey, 65);
                byte[] transcript = concat(binding, this.nonce, publicBytes, serverNonce, serverPublic);
                byte[] expected = hmac(auth, utf8("herdr-e2ee-v2 server\0"), transcript);
                if (!MessageDigest.isEqual(decode(proof, 32), expected))
                    throw new GeneralSecurityException("Server proof mismatch");
                KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
                agreement.init(privateKey);
                agreement.doPhase(importPublic(serverPublic), true);
                shared = agreement.generateSecret();
                if (shared.length != 32) throw new GeneralSecurityException("Invalid ECDH output");
                salt = hmac(auth, utf8("herdr-e2ee-v2 key\0"), transcript);
                send = hkdf(shared, salt, utf8("herdr-e2ee-v2 c2s"));
                receive = hkdf(shared, salt, utf8("herdr-e2ee-v2 s2c"));
                return new Session(send, receive);
            } catch (IllegalArgumentException ex) {
                throw new GeneralSecurityException("Invalid handshake field", ex);
            } finally {
                wipe(shared); wipe(salt); wipe(send); wipe(receive); close();
            }
        }

        @Override public synchronized void close() {
            consumed = true; wipe(auth); auth = null; privateKey = null;
        }
    }

    public static final class Session implements AutoCloseable {
        private byte[] sendKey, receiveKey;
        private long sendSequence, receiveSequence;
        private boolean closed;
        Session(byte[] send, byte[] receive) {
            if (send.length != 32 || receive.length != 32) throw new IllegalArgumentException("AES-256 keys required");
            sendKey = send.clone(); receiveKey = receive.clone();
        }
        public synchronized byte[] encrypt(String plaintext) throws GeneralSecurityException {
            checkOpen();
            try {
                byte[] bytes = strictEncode(plaintext);
                if (bytes.length > MAX_PLAINTEXT_BYTES) throw new GeneralSecurityException("Message too large");
                checkSequence(sendSequence);
                byte[] sealed = crypt(Cipher.ENCRYPT_MODE, sendKey, "c2s", sendSequence, bytes);
                byte[] frame = ByteBuffer.allocate(10 + sealed.length).put((byte) 2).put((byte) 0)
                        .putLong(sendSequence).put(sealed).array();
                sendSequence++;
                return frame;
            } catch (GeneralSecurityException | RuntimeException ex) { close(); throw ex; }
        }
        public synchronized String decrypt(byte[] frame) throws GeneralSecurityException {
            checkOpen();
            try {
                if (frame == null || frame.length < 26 || frame.length > MAX_PLAINTEXT_BYTES + 26 ||
                        frame[0] != 2 || frame[1] != 0)
                    throw new GeneralSecurityException("Invalid binary frame");
                long seq = ByteBuffer.wrap(frame, 2, 8).getLong();
                checkSequence(seq);
                if (seq != receiveSequence) throw new GeneralSecurityException("Unexpected sequence");
                byte[] plaintext = crypt(Cipher.DECRYPT_MODE, receiveKey, "s2c", seq,
                        Arrays.copyOfRange(frame, 10, frame.length));
                String result = strictDecode(plaintext);
                receiveSequence++;
                return result;
            } catch (GeneralSecurityException | RuntimeException ex) { close(); throw ex; }
        }
        private void checkOpen() throws GeneralSecurityException {
            if (closed) throw new GeneralSecurityException("Session closed");
        }
        @Override public synchronized void close() {
            closed = true; wipe(sendKey); wipe(receiveKey); sendKey = receiveKey = null;
        }
    }

    static byte[] binding(String kind, String id, long version) {
        if (!("credential".equals(kind) || "invitation".equals(kind)) || id == null ||
                id.isEmpty() || id.length() > 256 || id.matches("(?s).*[\\x00-\\x1f\\x7f].*") ||
                version < 1 || version > MAX_SEQUENCE)
            throw new IllegalArgumentException("Invalid authentication selector");
        // Reject malformed UTF-16 rather than silently replacing signed identity bytes.
        try { return strictEncode("herdr-e2ee-v2 auth\0" + kind + "\0" + id + "\0" + version + "\0"); }
        catch (GeneralSecurityException ex) { throw new IllegalArgumentException("Invalid identity Unicode", ex); }
    }

    static ECParameterSpec curve() throws GeneralSecurityException {
        AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
        params.init(new ECGenParameterSpec("secp256r1"));
        return params.getParameterSpec(ECParameterSpec.class);
    }
    static PublicKey importPublic(byte[] raw) throws GeneralSecurityException {
        if (raw.length != 65 || raw[0] != 4) throw new GeneralSecurityException("Expected uncompressed P-256 key");
        ECParameterSpec spec = curve();
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(raw, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(raw, 33, 65));
        BigInteger p = ((ECFieldFp) spec.getCurve().getField()).getP();
        if (x.compareTo(p) >= 0 || y.compareTo(p) >= 0 ||
                !y.multiply(y).mod(p).equals(x.multiply(x).multiply(x)
                        .add(spec.getCurve().getA().multiply(x)).add(spec.getCurve().getB()).mod(p)))
            throw new GeneralSecurityException("Point is not on P-256");
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), spec));
    }
    static byte[] rawPublic(PublicKey publicKey) throws GeneralSecurityException {
        if (!(publicKey instanceof ECPublicKey)) throw new GeneralSecurityException("EC public key required");
        ECPublicKey key = (ECPublicKey) publicKey;
        if (!key.getParams().getOrder().equals(curve().getOrder())) throw new GeneralSecurityException("P-256 required");
        byte[] raw = new byte[65]; raw[0] = 4;
        coordinate(key.getW().getAffineX(), raw, 1); coordinate(key.getW().getAffineY(), raw, 33);
        importPublic(raw);
        return raw;
    }
    private static void coordinate(BigInteger value, byte[] out, int offset) {
        byte[] bytes = value.toByteArray();
        int from = bytes.length == 33 && bytes[0] == 0 ? 1 : 0;
        if (value.signum() < 0 || bytes.length - from > 32) throw new IllegalArgumentException("Invalid coordinate");
        System.arraycopy(bytes, from, out, offset + 32 - (bytes.length - from), bytes.length - from);
    }
    static byte[] hmac(byte[] key, byte[]... parts) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
        for (byte[] part : parts) mac.update(part);
        return mac.doFinal();
    }
    static byte[] hkdf(byte[] input, byte[] salt, byte[] info) throws GeneralSecurityException {
        byte[] prk = hmac(salt, input);
        try { return hmac(prk, info, new byte[] {1}); }
        finally { wipe(prk); }
    }
    static byte[] crypt(int mode, byte[] key, String direction, long sequence, byte[] data)
            throws GeneralSecurityException {
        checkSequence(sequence);
        if (!"c2s".equals(direction) && !"s2c".equals(direction)) throw new GeneralSecurityException("Invalid direction");
        byte[] nonce = ByteBuffer.allocate(12).putInt(0).putLong(sequence).array();
        byte[] aad = concat(utf8("herdr-e2ee-v2 " + direction + "\0"), ByteBuffer.allocate(8).putLong(sequence).array());
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(data);
    }
    static byte[] concat(byte[]... parts) {
        int size = 0; for (byte[] p : parts) size = Math.addExact(size, p.length);
        ByteBuffer b = ByteBuffer.allocate(size); for (byte[] p : parts) b.put(p); return b.array();
    }
    static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    static byte[] strictEncode(String value) throws GeneralSecurityException {
        try {
            ByteBuffer b = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value));
            byte[] result = new byte[b.remaining()]; b.get(result); return result;
        } catch (CharacterCodingException ex) { throw new GeneralSecurityException("Invalid Unicode", ex); }
    }
    static String strictDecode(byte[] value) throws GeneralSecurityException {
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value)).toString(); }
        catch (CharacterCodingException ex) { throw new GeneralSecurityException("Invalid UTF-8", ex); }
    }
    static void checkSequence(long seq) throws GeneralSecurityException {
        if (seq < 0 || seq > MAX_SEQUENCE) throw new GeneralSecurityException("Sequence exhausted or invalid");
    }
    static void wipe(byte[] value) { if (value != null) Arrays.fill(value, (byte) 0); }
}
