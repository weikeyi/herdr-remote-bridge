package dev.herdrbridge.nativeclient;

import org.junit.Test;
import org.json.JSONObject;
import java.io.IOException;
import java.lang.reflect.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Synthetic protocol state tests. Never opens a WebSocket or disables TLS. */
public class NativeRelayClientTest {
    private static final String ID="AAAAAAAAAAAAAAAAAAAAAAAA", RELAY="AAAAAAAAAAAAAAAAAAAAAA", ORIGIN="https://example.test";
    private static final class Store implements CredentialStore {
        boolean reject;int writes;
        public boolean hasRecord(){return false;}
        public void save(CredentialRecord value)throws IOException{writes++;if(reject)throw new IOException("synthetic failure");}
    }
    private NativeRelayClient client(Store store,CredentialRecord record){return new NativeRelayClient(ORIGIN,RELAY,ID,1,new byte[32],new byte[32],0,record,store);}
    private JSONObject finish()throws Exception{return StrictJson.object("{\"type\":\"e2ee_server_finish\",\"version\":2,\"role\":\"reader\",\"locale\":\"en\",\"device_id\":\""+ID+"\",\"credential_id\":\""+ID+"\",\"credential_version\":1,\"credential_secret\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\"}");}
    private Object field(Object object,String name)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    private void invokeFinish(NativeRelayClient client,JSONObject message)throws Exception{Method m=NativeRelayClient.class.getDeclaredMethod("finish",JSONObject.class);m.setAccessible(true);m.invoke(client,message);}
    private void rejects(NativeRelayClient client,JSONObject message)throws Exception{
        try{invokeFinish(client,message);fail("accepted invalid finish");}catch(InvocationTargetException expected){}
        assertNotEquals("APPLICATION",field(client,"phase").toString());
    }
    @Test public void failedVaultNeverAdvancesAndSuccessWaitsForConfig()throws Exception{
        Store store=new Store();store.reject=true;
        try(NativeRelayClient client=client(store,null)){rejects(client,finish());assertEquals(1,store.writes);}
        Store good=new Store();AtomicInteger ready=new AtomicInteger();
        try(NativeRelayClient client=client(good,null)){
            Field l=NativeRelayClient.class.getDeclaredField("listener");l.setAccessible(true);l.set(client,new SessionRepository.Listener(){
                public void onState(SessionRepository.State state,String message){if(state==SessionRepository.State.READY)ready.incrementAndGet();}
                public void onSessions(List<SessionRepository.Session> sessions){}
                public void onSnapshot(SessionRepository.SessionTarget target,String content){}
            });
            invokeFinish(client,finish());assertEquals(1,good.writes);assertEquals("APPLICATION",field(client,"phase").toString());assertEquals(0,ready.get());
        }
    }
    @Test public void malformedControllerAndNumericCoercionFailClosed()throws Exception{
        for(JSONObject bad:new JSONObject[]{finish().put("role","controller"),finish().put("credential_version",1.0),finish().put("version","2"),finish().put("locale","bad_locale"),finish().put("credential_secret","bad")}){
            Store store=new Store();try(NativeRelayClient client=client(store,null)){rejects(client,bad);assertEquals(0,store.writes);}
        }
    }
    @Test public void savedIdentityCannotRotateSilently()throws Exception{
        for(JSONObject message:new JSONObject[]{finish(),finish().put("device_id","BBBBBBBBBBBBBBBBBBBBBBBB"),finish().put("credential_version",2)}){
            if(!message.getString("device_id").equals(ID)||message.getLong("credential_version")!=1)message.remove("credential_secret");
            Store store=new Store();CredentialRecord record=new CredentialRecord(ORIGIN,ID,ID,1,"reader",new byte[32],RELAY,new byte[32]);
            try(NativeRelayClient client=client(store,record)){rejects(client,message);assertEquals(0,store.writes);}
        }
    }
    @Test public void unexpectedTextOrBinaryBeforeGatewayReadyIsRejected()throws Exception{
        try(NativeRelayClient client=client(new Store(),null)){
            Method text=NativeRelayClient.class.getDeclaredMethod("receiveText",String.class);text.setAccessible(true);
            try{text.invoke(client,"{\"type\":\"ready\"}");fail();}catch(InvocationTargetException expected){}
            Method binary=NativeRelayClient.class.getDeclaredMethod("receiveBinary",byte[].class);binary.setAccessible(true);
            try{binary.invoke(client,(Object)new byte[]{1,3,0,0,0,0});fail();}catch(InvocationTargetException expected){}
            assertEquals("NEW",field(client,"phase").toString());
        }
    }
    @Test public void manualCloseErasesCredentialBytesAndTerminatesWorker()throws Exception{
        NativeRelayClient client=client(new Store(),null);
        byte[] secret=(byte[])field(client,"authSecret"), rendezvous=(byte[])field(client,"rendezvous");
        java.util.Arrays.fill(secret,(byte)7);java.util.Arrays.fill(rendezvous,(byte)9);
        client.close();assertTrue(client.awaitClosed(2000));
        assertEquals("CLOSED",field(client,"phase").toString());
        assertArrayEquals(new byte[32],secret);assertArrayEquals(new byte[32],rendezvous);
    }
    @Test public void listenerExceptionCannotSkipCloseCleanup()throws Exception{
        Store store=new Store();NativeRelayClient client=client(store,null);
        Field l=NativeRelayClient.class.getDeclaredField("listener");l.setAccessible(true);l.set(client,new SessionRepository.Listener(){
            public void onState(SessionRepository.State state,String message){throw new RuntimeException("synthetic listener failure");}
            public void onSessions(List<SessionRepository.Session> sessions){}
            public void onSnapshot(SessionRepository.SessionTarget target,String content){}
        });
        Method fail=NativeRelayClient.class.getDeclaredMethod("fail",SessionRepository.State.class);fail.setAccessible(true);fail.invoke(client,SessionRepository.State.ERROR);
        assertEquals("CLOSED",field(client,"phase").toString());assertTrue(((java.util.concurrent.ScheduledThreadPoolExecutor)field(client,"serial")).isShutdown());
    }
}
