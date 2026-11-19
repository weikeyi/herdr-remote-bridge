package dev.herdrbridge.nativeclient;

import java.util.List;
import java.util.function.DoubleSupplier;

/** UI-thread lifecycle owner. Only saved credentials may ever be retried.
 * Retry budget is five attempts per outage, reset only by authenticated saved READY or an explicit action.
 */
final class ForegroundConnection {
    interface Factory {
        boolean hasSaved();
        SessionRepository saved() throws Exception;
    }
    interface Scheduler {
        void execute(Runnable work);
        void after(long delayMillis, Runnable work);
        void cancel(Runnable work);
    }
    private final Factory factory;
    private final Scheduler scheduler;
    private final SessionRepository.Listener view;
    private final DoubleSupplier random;
    private final java.util.function.IntConsumer saveRetries;
    private SessionRepository current;
    private Runnable retry;
    private long epoch;
    private boolean foreground, authorized, invitationAttempt, ready;
    private int retries;
    ForegroundConnection(Factory factory, Scheduler scheduler, SessionRepository.Listener view, DoubleSupplier random) {
        this(factory,scheduler,view,random,count->{});
    }
    ForegroundConnection(Factory factory,Scheduler scheduler,SessionRepository.Listener view,DoubleSupplier random,java.util.function.IntConsumer saveRetries){
        this.factory=factory;this.scheduler=scheduler;this.view=view;this.random=random;this.saveRetries=saveRetries;
    }
    void foreground() {
        foreground=true;
        if(authorized&&current==null&&retry==null){
            if(factory.hasSaved())recover(false);
            else{authorized=false;view.onState(SessionRepository.State.DISCONNECTED,"");}
        }
    }
    void background() {foreground=false;stopAttempt();view.onState(SessionRepository.State.DISCONNECTED,"");}
    void stop() {authorized=false;background();}
    void restoreSaved(int count) {authorized=true;retries=Math.max(0,Math.min(5,count));stopAttempt();if(foreground)recover(false);}
    void explicitSaved() {authorized=true;retries=0;saveRetries.accept(retries);stopAttempt();if(foreground)attempt(null);}
    void explicitInvitation(SessionRepository invitation) {
        authorized=true;retries=0;saveRetries.accept(retries);stopAttempt();
        if(foreground)attempt(invitation);else{invitation.close();authorized=false;}
    }
    void requestSnapshot(SessionRepository.SessionTarget target) {if(foreground&&ready&&current!=null)current.requestSnapshot(target);}
    private void stopAttempt() {
        epoch++;ready=false;
        if(retry!=null){scheduler.cancel(retry);retry=null;}
        if(current!=null){current.close();current=null;}
    }
    private void attempt(SessionRepository invitation) {
        if(!foreground||!authorized){if(invitation!=null)invitation.close();return;}
        stopAttempt();final long generation=epoch;
        invitationAttempt=invitation!=null;
        view.onState(SessionRepository.State.CONNECTING,"");
        try {
            current=invitation!=null?invitation:factory.saved();
            current.start(new SessionRepository.Listener(){
                public void onState(SessionRepository.State state,String ignored){scheduler.execute(()->{if(generation==epoch&&foreground)state(state);});}
                public void onSessions(List<SessionRepository.Session> sessions){scheduler.execute(()->{if(generation==epoch&&foreground&&ready)view.onSessions(sessions);});}
                public void onSnapshot(SessionRepository.SessionTarget target,String text){scheduler.execute(()->{if(generation==epoch&&foreground&&ready)view.onSnapshot(target,text);});}
            });
        } catch(Exception ignored){stopAttempt();authorized=false;view.onState(SessionRepository.State.ERROR,"");}
    }
    private void state(SessionRepository.State state) {
        if(state==SessionRepository.State.ENROLLED&&invitationAttempt){
            // Vault write already succeeded. Persist non-secret resume intent before confirmation.
            view.onState(SessionRepository.State.ENROLLED,"");
            // Confirm pending invitation using fresh credential auth.
            stopAttempt();if(factory.hasSaved())attempt(null);else fatal(SessionRepository.State.ERROR);return;
        }
        if(state==SessionRepository.State.ERROR||state==SessionRepository.State.REVOKED){fatal(state);return;}
        if(state==SessionRepository.State.DISCONNECTED){
            stopAttempt();view.onState(state,"");recover(true);return;
        }
        // An invitation socket never provides stable UI data, even if config arrives quickly.
        ready=state==SessionRepository.State.READY&&!invitationAttempt;
        if(ready){retries=0;saveRetries.accept(retries);}
        view.onState(ready?SessionRepository.State.READY:SessionRepository.State.CONNECTING,"");
    }
    private void recover(boolean delayed) {
        if(!factory.hasSaved()||retries>=5){fatal(SessionRepository.State.ERROR);return;}
        long base=1000L<<retries++;saveRetries.accept(retries);
        if(!delayed){attempt(null);return;}
        long delay=base+(long)(base*0.25*Math.max(0,Math.min(1,random.getAsDouble())));
        final long generation=epoch;
        retry=()->{if(generation==epoch&&foreground&&authorized){retry=null;attempt(null);}};
        scheduler.after(delay,retry);
    }
    private void fatal(SessionRepository.State state){stopAttempt();authorized=false;view.onState(state,"");}
}
