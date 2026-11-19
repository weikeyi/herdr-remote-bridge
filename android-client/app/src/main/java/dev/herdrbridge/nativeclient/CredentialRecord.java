package dev.herdrbridge.nativeclient;

import java.io.*;
import java.util.Arrays;

/** Enrolled role-bound credential only. Never serialize invitations or root relay tokens. */
public final class CredentialRecord implements AutoCloseable {
    public final String origin, credentialId, deviceId, relayId, role;
    public final long version;
    private final byte[] secret, rendezvousKey;
    public CredentialRecord(String origin, String credentialId, String deviceId, long version,
                            String role, byte[] secret, String relayId, byte[] rendezvousKey) {
        this.origin = InvitationValidator.origin(origin);
        if ((!"reader".equals(role) && !"controller".equals(role)) || version < 1 || version > 9007199254740991L || !id(credentialId) || !id(deviceId)
                || !relayIdValid(relayId) || secret == null || secret.length != 32
                || rendezvousKey == null || rendezvousKey.length != 32) throw invalid();
        this.role=role; this.credentialId=credentialId; this.deviceId=deviceId; this.version=version; this.relayId=relayId;
        this.secret=secret.clone(); this.rendezvousKey=rendezvousKey.clone();
    }
    private static boolean relayIdValid(String value) {
        try {byte[] decoded=java.util.Base64.getUrlDecoder().decode(value);return decoded.length==16&&java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value);}
        catch(RuntimeException ignored){return false;}
    }
    private static boolean id(String s) { return s!=null && s.matches("[A-Za-z0-9_-]{16,128}"); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid device credential"); }
    public byte[] secret() { return secret.clone(); }
    public byte[] rendezvousKey() { return rendezvousKey.clone(); }
    byte[] encode() throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        DataOutputStream out=new DataOutputStream(bytes);
        out.writeInt(2); out.writeUTF(role); out.writeUTF(origin); out.writeUTF(credentialId); out.writeUTF(deviceId);
        out.writeLong(version); out.writeUTF(relayId); out.write(secret); out.write(rendezvousKey);
        out.flush(); return bytes.toByteArray();
    }
    static CredentialRecord decode(byte[] data) throws IOException {
        if(data.length>16384) throw new IOException("Credential record exceeds limit");
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(data));
        byte[] secret=new byte[32], rendezvous=new byte[32];
        try {
            int format=in.readInt(); if(format!=1 && format!=2) throw invalid();
            String role=format==1?"reader":in.readUTF();
            String origin=in.readUTF(), credential=in.readUTF(), device=in.readUTF();
            long version=in.readLong(); String relay=in.readUTF();
            in.readFully(secret); in.readFully(rendezvous);
            if(in.available()!=0) throw invalid();
            return new CredentialRecord(origin,credential,device,version,role,secret,relay,rendezvous);
        } catch (IllegalArgumentException e) { throw new IOException("Invalid credential record"); }
        finally { Arrays.fill(secret,(byte)0); Arrays.fill(rendezvous,(byte)0); }
    }
    @Override public void close() { Arrays.fill(secret,(byte)0); Arrays.fill(rendezvousKey,(byte)0); }
    @Override public String toString() { return "CredentialRecord[redacted]"; }
}
