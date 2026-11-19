package dev.herdrbridge.nativeclient;
import org.junit.Test;
import static org.junit.Assert.*;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.util.Arrays;
public class VaultEnvelopeTest {
 private final SecretKeySpec key=new SecretKeySpec(new byte[32],"AES");
 @Test public void roundTripAndRandomNonces() throws Exception {
  byte[] plain="synthetic credential".getBytes(java.nio.charset.StandardCharsets.UTF_8);
  byte[] a=VaultEnvelope.seal(key,"https://example.test",plain),b=VaultEnvelope.seal(key,"https://example.test",plain);
  assertFalse(Arrays.equals(a,b));assertArrayEquals(plain,VaultEnvelope.open(key,"https://example.test",a));
 }
 @Test public void tamperAuthorityKeyAndMalformedFailClosed()throws Exception {
  byte[] a=VaultEnvelope.seal(key,"https://example.test",new byte[32]);
  try{VaultEnvelope.open(key,"https://other.test",a);fail();}catch(GeneralSecurityException expected){}
  byte[] wrong=new byte[32];wrong[0]=1;
  try{VaultEnvelope.open(new SecretKeySpec(wrong,"AES"),"https://example.test",a);fail();}catch(GeneralSecurityException expected){}
  a[a.length-1]^=1;
  try{VaultEnvelope.open(key,"https://example.test",a);fail();}catch(GeneralSecurityException expected){}
  for(byte[] data:new byte[][]{new byte[0],new byte[32],new byte[VaultEnvelope.MAX_BYTES+1]}){
   try{VaultEnvelope.open(key,"https://example.test",data);fail();}catch(GeneralSecurityException expected){}
  }
 }
 @Test public void typedRecordRejectsControllerAndTrailingData()throws Exception {
  String id="AAAAAAAAAAAAAAAAAAAAAAAA";
  try{new CredentialRecord("https://example.test",id,id,1,"admin",new byte[32],"AAAAAAAAAAAAAAAAAAAAAA",new byte[32]);fail();}catch(IllegalArgumentException expected){}
  try(CredentialRecord record=new CredentialRecord("https://example.test",id,id,1,"reader",new byte[32],"AAAAAAAAAAAAAAAAAAAAAA",new byte[32])){
   byte[] encoded=record.encode();try(CredentialRecord decoded=CredentialRecord.decode(encoded)){assertEquals(record.origin,decoded.origin);}
   try{CredentialRecord.decode(Arrays.copyOf(encoded,encoded.length+1));fail();}catch(java.io.IOException expected){}
  }
 }
}
