package dev.herdrbridge.nativeclient;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.JSONObject;

/** Actual NativeRelayClient over loopback WSS; no trust-manager or hostname bypass. */
public final class NativeWssIntegration {
    static final String ROLE = System.getenv().getOrDefault("HARNESS_ROLE", "reader");
    static void require(boolean value,String reason){if(!value)throw new AssertionError(reason);}
    static final class MemoryStore implements CredentialStore,AutoCloseable {
        private CredentialRecord record;
        private int writes;
        public synchronized boolean hasRecord(){return record!=null;}
        public synchronized void save(CredentialRecord value)throws java.io.IOException {
            require(record==null,"Unexpected credential replacement");
            record=CredentialRecord.decode(value.encode());writes++;
        }
        synchronized CredentialRecord load()throws java.io.IOException{
            require(record!=null,"Credential absent");return CredentialRecord.decode(record.encode());
        }
        public synchronized void close(){if(record!=null){record.close();record=null;}}
    }
    static SessionRepository.Listener listener(java.util.function.Consumer<SessionRepository.State> state) {
        return new SessionRepository.Listener(){
            public void onSessions(List<SessionRepository.Session> sessions){}
            public void onSnapshot(SessionRepository.SessionTarget target,String content){}
            public void onState(SessionRepository.State value,String ignored){state.accept(value);}
        };
    }
    static void rejectedCertificate(String origin,String invitation,String caseName)throws Exception{
        try(MemoryStore store=new MemoryStore()){
            NativeRelayClient client=NativeRelayClient.forInvitation(origin,invitation,store);
            CountDownLatch terminal=new CountDownLatch(1);AtomicBoolean accepted=new AtomicBoolean();
            AtomicReference<SessionRepository.State> failed=new AtomicReference<>();
            client.start(listener(state->{
                if(state==SessionRepository.State.READY||state==SessionRepository.State.ENROLLED)accepted.set(true);
                if(state==SessionRepository.State.ERROR||state==SessionRepository.State.DISCONNECTED){failed.set(state);terminal.countDown();}
            }));
            try{require(terminal.await(20,TimeUnit.SECONDS),"Untrusted certificate did not fail promptly");
                require(failed.get()==SessionRepository.State.ERROR&&!accepted.get()&&!store.hasRecord(),"TLS rejection was not a security error, or enrolled");}
            finally{client.close();require(client.awaitClosed(5000),"Rejected connection leaked worker");}
            System.out.println("PASS default OkHttp TLS rejected "+caseName+" before enrollment");
        }
    }
    static void enrollment(JSONObject cfg,MemoryStore store)throws Exception{
        NativeRelayClient client=NativeRelayClient.forInvitation(cfg.getString("origin"),cfg.getString("invitation"),store,ROLE);
        CountDownLatch enrolled=new CountDownLatch(1);AtomicReference<String> failure=new AtomicReference<>();
        client.start(listener(state->{
            if(state==SessionRepository.State.ENROLLED)enrolled.countDown();
            if(state==SessionRepository.State.ERROR||state==SessionRepository.State.DISCONNECTED){failure.set(state.name());enrolled.countDown();}
        }));
        try{require(enrolled.await(20,TimeUnit.SECONDS),"Enrollment timeout");
            require(failure.get()==null&&store.hasRecord()&&store.writes==1,"Enrollment/credential-save failure");}
        finally{client.close();require(client.awaitClosed(5000),"Enrollment worker not closed");}
        System.out.println("PASS actual invitation parsing, gateway proof, E2EE finish and scoped credential save");
    }
    static void snapshotRound(MemoryStore store,int round)throws Exception{
        CredentialRecord copy=store.load();NativeRelayClient client;
        try{client=NativeRelayClient.forSaved(copy,store);}finally{copy.close();}
        CountDownLatch done=new CountDownLatch(1);AtomicReference<String> failure=new AtomicReference<>();
        AtomicBoolean requested=new AtomicBoolean(),ready=new AtomicBoolean(),snapshot=new AtomicBoolean();
        client.start(new SessionRepository.Listener(){
            public void onState(SessionRepository.State state,String ignored){
                if(state==SessionRepository.State.READY)ready.set(true);
                if(state==SessionRepository.State.ENROLLED)failure.set("Credential re-enrolled");
                if(state==SessionRepository.State.ERROR||state==SessionRepository.State.DISCONNECTED){failure.set(state.name());done.countDown();}
            }
            public void onSessions(List<SessionRepository.Session> sessions){
                if(sessions.size()!=1){failure.set("Wrong agent list");done.countDown();return;}
                if(requested.compareAndSet(false,true))client.requestSnapshot(sessions.get(0).target);
            }
            public void onSnapshot(SessionRepository.SessionTarget target,String content){
                if(!"synthetic-pane".equals(target.paneId)||!content.startsWith("synthetic WSS terminal 中文\n")||
                        !content.contains("界界界\nsynthetic-counts:text="+ (ROLE.equals("controller")?1:0) +",enter="+(ROLE.equals("controller")?1:0)+"\n"))failure.set("Wrong/reassembled snapshot");
                snapshot.set(true);done.countDown();
            }
        });
        try{require(done.await(25,TimeUnit.SECONDS),"Snapshot timeout");
            require(failure.get()==null&&ready.get()&&requested.get()&&snapshot.get()&&store.writes==1,
                    "Authenticated snapshot failed: "+failure.get());}
        finally{client.close();require(client.awaitClosed(5000),"Snapshot worker not closed");}
        System.out.println("PASS credential connection "+round+": actual native agents, exact-target snapshot, 300KB chunk reassembly, cleanup");
    }
    static void inputRound(MemoryStore store)throws Exception {
        CredentialRecord copy=store.load(); NativeRelayClient client;
        try{client=NativeRelayClient.forSaved(copy,store);}finally{copy.close();}
        CountDownLatch done=new CountDownLatch(1); AtomicReference<String> failure=new AtomicReference<>();
        AtomicReference<SessionRepository.SessionTarget> currentTarget=new AtomicReference<>();
        AtomicInteger step=new AtomicInteger(); AtomicBoolean requested=new AtomicBoolean();
        client.start(new SessionRepository.Listener(){
            public void onState(SessionRepository.State state,String ignored){
                if(state==SessionRepository.State.ERROR||state==SessionRepository.State.DISCONNECTED){failure.set(state.name());done.countDown();}
            }
            public void onSessions(List<SessionRepository.Session> rows){
                if(rows.size()!=1){failure.set("Expected one target");done.countDown();return;}
                if(requested.compareAndSet(false,true))client.requestSnapshot(rows.get(0).target);
            }
            public void onSnapshot(SessionRepository.SessionTarget target,String content){
                int current=step.get();
                if(current==0){currentTarget.set(target);step.set(-1);client.sendTerminalText(new SessionRepository.SessionTarget(target.serverSessionId,target.paneId,target.terminalId,target.generation+1,target.agentSessionId),"synthetic stale target");}
                else if(current==2){step.set(3);client.sendEnter(target);}
                else if(current==4){
                    if(!content.endsWith("synthetic-counts:text=1,enter=1\n"))failure.set("Wrong synthetic dispatch counts");
                    done.countDown();
                }
            }
            public void onMutationResult(String id,SessionRepository.SessionTarget target,SessionRepository.MutationOutcome outcome){
                if(step.get()==-1){
                    if(outcome!=SessionRepository.MutationOutcome.NOT_STARTED){failure.set("Stale target not refused");done.countDown();return;}
                    step.set(1);client.sendTerminalText(currentTarget.get(),"synthetic harmless text");return;
                }
                if(!ROLE.equals("controller")){
                    if(outcome!=SessionRepository.MutationOutcome.NOT_STARTED)failure.set("Reader input was not refused");
                    done.countDown();return;
                }
                if(outcome!=SessionRepository.MutationOutcome.COMPLETED){failure.set("Controller input not completed");done.countDown();return;}
                int current=step.get();
                if(current==1){step.set(2);client.requestSnapshot(target);}
                else if(current==3){step.set(4);client.requestSnapshot(target);}
                else{failure.set("Unexpected duplicate completion");done.countDown();}
            }
        });
        try{require(done.await(25,TimeUnit.SECONDS),"Input test timeout");require(failure.get()==null,"Input test failed: "+failure.get());}
        finally{client.close();require(client.awaitClosed(5000),"Input worker not closed");}
        System.out.println("PASS scoped native "+ROLE+" input boundary; synthetic Core counters only");
    }
    public static void main(String[] args)throws Exception{
        JSONObject cfg=new JSONObject(Files.readString(Paths.get(args[0]),StandardCharsets.UTF_8));
        // JVM trustStore was set at launch and contains ONLY the test CA. Production
        // NativeRelayClient constructs its unchanged default OkHttp TLS stack.
        rejectedCertificate(cfg.getString("bad_origin"),cfg.getString("bad_invitation"),"untrusted certificate");
        rejectedCertificate(cfg.getString("wrong_host_origin"),
                cfg.getString("invitation").replace("127.0.0.1","localhost"),"certificate hostname mismatch");
        try(MemoryStore store=new MemoryStore()){
            enrollment(cfg,store);
            inputRound(store);
            snapshotRound(store,1);
            snapshotRound(store,2);
        }
        System.out.println("PASS REAL WSS NativeRelayClient pairing/list/snapshot/fresh credential reconnect; synthetic service only");
    }
}
