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
    @Test public void controllerRequiresExpectedAuthenticatedRole()throws Exception {
        Store store=new Store();try(NativeRelayClient c=client(store,null)) {
            Field expected=NativeRelayClient.class.getDeclaredField("expectedRole");expected.setAccessible(true);expected.set(c,"controller");
            rejects(c,finish());assertEquals(0,store.writes);
            invokeFinish(c,finish().put("role","controller"));assertEquals(1,store.writes);
            assertEquals("controller",((CredentialRecord)field(c,"saved")).role);
        }
    }
    @Test public void mutationAcknowledgmentsBindIdActionPaneAndCurrentTarget()throws Exception {
        for(String wrong:new String[]{"none","request_id","action","pane_id","generation"})try(NativeRelayClient c=client(new Store(),null)) {
            NativeSessionProtocol p=(NativeSessionProtocol)field(c,"protocol");
            p.acceptConfig(new JSONObject().put("type","push_config").put("protocol",3).put("inventory",new JSONObject().put("state","ready")).put("capabilities",new org.json.JSONArray().put("manual_terminal_input")));
            JSONObject target=new JSONObject().put("server_session_id","primary").put("pane_id","pane-1").put("terminal_id","term-1").put("generation",0).put("agent_session_id","");
            SessionRepository.SessionTarget t=p.acceptAgents(new JSONObject().put("type","agents").put("agents",new org.json.JSONArray().put(target))).get(0).target;
            Class<?> type=Class.forName("dev.herdrbridge.nativeclient.NativeRelayClient$Mutation");java.lang.reflect.Constructor<?> ctor=type.getDeclaredConstructors()[0];ctor.setAccessible(true);
            Field pending=NativeRelayClient.class.getDeclaredField("pendingMutation");pending.setAccessible(true);pending.set(c,ctor.newInstance("mutation-1","send_keys",t));
            JSONObject ack=new JSONObject().put("request_id","mutation-1").put("action","send_keys").put("pane_id","pane-1").put("ok",true).put("phase","completed");
            if(wrong.equals("generation"))p.acceptAgentUpdate(target.put("type","agent_update").put("generation",1));
            else if(!wrong.equals("none"))ack.put(wrong,"wrong");
            Method accept=NativeRelayClient.class.getDeclaredMethod("acceptMutationResult",JSONObject.class);accept.setAccessible(true);accept.invoke(c,ack);
            if(wrong.equals("request_id"))assertNotNull(field(c,"pendingMutation"));
            else {assertNull(field(c,"pendingMutation"));assertEquals(!wrong.equals("none"),field(c,"inputUncertain"));}
        }
    }
    @Test public void passiveTimeoutPausesPollingWithoutClosingAndDropsLateReply()throws Exception {
        try(NativeRelayClient c=client(new Store(),null)) {
            NativeSessionProtocol p=(NativeSessionProtocol)field(c,"protocol");
            p.acceptConfig(new JSONObject().put("type","push_config").put("protocol",3).put("inventory",new JSONObject().put("state","ready")));
            JSONObject target=new JSONObject().put("server_session_id","primary").put("pane_id","pane-1").put("terminal_id","term-1").put("generation",0).put("agent_session_id","");
            SessionRepository.SessionTarget t=p.acceptAgents(new JSONObject().put("type","agents").put("agents",new org.json.JSONArray().put(target))).get(0).target;
            p.readPanePassive(t,"passive-0001");
            Field pending=NativeRelayClient.class.getDeclaredField("pendingTarget");pending.setAccessible(true);pending.set(c,t);
            Field passive=NativeRelayClient.class.getDeclaredField("pendingPassive");passive.setAccessible(true);passive.setBoolean(c,true);
            Method timeout=NativeRelayClient.class.getDeclaredMethod("readTimedOut");timeout.setAccessible(true);timeout.invoke(c);
            assertTrue((Boolean)field(c,"passivePaused"));assertFalse(((java.util.concurrent.atomic.AtomicBoolean)field(c,"closed")).get());assertFalse(p.hasPendingRead());
            assertNull(p.acceptPaneContent(new JSONObject().put("type","pane_content").put("request_id","passive-0001")));assertFalse(p.inputReady(t));
            pending.set(c,t);timeout.invoke(c);assertFalse(((java.util.concurrent.atomic.AtomicBoolean)field(c,"closed")).get());
        }
    }
}
