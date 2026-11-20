package dev.herdrbridge.nativeclient;
import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.Assert.*;

/** UI/controller integration with test-only repositories; never opens a network or types into a real terminal. */
public class TerminalInputUiTest {
 @Before public void isolatedInstall(){Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();Assume.assumeFalse(new DeviceVault(c).hasRecord());for(String n:new String[]{"MainActivity",MainActivity.class.getName()})Assume.assumeFalse(c.getSharedPreferences(n,0).getBoolean("auto_connect",false));}
 private static Object get(Activity a,String n){try{Field f=MainActivity.class.getDeclaredField(n);f.setAccessible(true);return f.get(a);}catch(Exception e){throw new AssertionError(e);}}
 private static void set(Activity a,String n,Object v){try{Field f=MainActivity.class.getDeclaredField(n);f.setAccessible(true);f.set(a,v);}catch(Exception e){throw new AssertionError(e);}}
 private static void call(Activity a,String n,Class<?>[] types,Object...args){try{Method m=MainActivity.class.getDeclaredMethod(n,types);m.setAccessible(true);m.invoke(a,args);}catch(Exception e){throw new AssertionError(e);}}
 private static final SessionRepository.SessionTarget TARGET=new SessionRepository.SessionTarget("primary","pane","terminal",1,"");
 private static class Fake implements SessionRepository {
  Listener listener;int textSends,enters,reads;String text,id;boolean closed;
  public void start(Listener l){listener=l;}
  public void close(){closed=true;}
  public void requestSnapshot(SessionTarget target){assertEquals(TARGET,target);reads++;}
  public String sendTerminalText(SessionTarget target,String value){assertEquals(TARGET,target);text=value;textSends++;return id="text-"+textSends;}
  public String sendEnter(SessionTarget target){assertEquals(TARGET,target);enters++;return id="enter-"+enters;}
  public void resetInputUncertainty(SessionTarget target){listener.onInputUncertaintyCleared(target);}
 }
 private static Fake install(MainActivity a,String role){
  Fake fake=new Fake();
  ForegroundConnection connection=new ForegroundConnection(new ForegroundConnection.Factory(){public boolean hasSaved(){return true;}public SessionRepository saved(){return fake;}},new ForegroundConnection.Scheduler(){public void execute(Runnable r){r.run();}public void after(long delay,Runnable r){}public void cancel(Runnable r){}},new SessionRepository.Listener(){
   public void onAccess(String value){set(a,"verifiedRole",value);}
   public void onState(SessionRepository.State state,String ignored){set(a,"ready",state==SessionRepository.State.READY);call(a,"updateInputControls",new Class<?>[]{});}
   public void onSessions(List<SessionRepository.Session> rows){call(a,"displaySessions",new Class<?>[]{List.class},rows);}
   public void onSnapshot(SessionRepository.SessionTarget target,String text){call(a,"displaySnapshot",new Class<?>[]{SessionRepository.SessionTarget.class,String.class},target,text);}
   public void onMutationResult(String id,SessionRepository.SessionTarget target,SessionRepository.MutationOutcome outcome){call(a,"displayMutationResult",new Class<?>[]{String.class,SessionRepository.MutationOutcome.class},id,outcome);}
   public void onInputUncertaintyCleared(SessionRepository.SessionTarget target){call(a,"displayInputUncertaintyCleared",new Class<?>[]{SessionRepository.SessionTarget.class},target);}
  },()->0.5);
  set(a,"connection",connection);connection.restoreInputUncertainty(a.getPreferences(0).getBoolean("input_uncertain",false));connection.foreground();connection.explicitSaved();
  fake.listener.onAccess(role);fake.listener.onState(SessionRepository.State.READY,"");fake.listener.onSessions(Collections.singletonList(new SessionRepository.Session(TARGET,"test","idle",true)));
  ((LinearLayout)get(a,"sessions")).getChildAt(0).performClick();return fake;
 }
 @Test public void readerCannotWriteAndControllerRequiresFreshSnapshotAndExplicitButtons(){
  try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
   scenario.onActivity(a->{
    Fake reader=install(a,"reader");reader.listener.onSnapshot(TARGET,"snapshot");assertEquals(View.GONE,((View)get(a,"inputPanel")).getVisibility());assertFalse(((Button)get(a,"sendEnter")).isEnabled());assertEquals(0,reader.enters);
    ((ForegroundConnection)get(a,"connection")).stop();Fake controller=install(a,"controller");
    assertFalse(((Button)get(a,"sendEnter")).isEnabled());controller.listener.onSnapshot(TARGET,"fresh snapshot");assertTrue(((Button)get(a,"sendEnter")).isEnabled());
    EditText draft=(EditText)get(a,"terminalDraft");assertFalse(draft.isSaveEnabled());assertNotNull(draft.getTransformationMethod());
    draft.setText("bad\nline");assertFalse(((Button)get(a,"sendText")).isEnabled());draft.setText("echo test");draft.onEditorAction(EditorInfo.IME_ACTION_DONE);assertEquals(0,controller.textSends);assertEquals(0,controller.enters);
    ((Button)get(a,"sendText")).performClick();assertEquals("echo test",controller.text);assertEquals(1,controller.textSends);assertEquals(0,controller.enters);assertEquals("",draft.getText().toString());assertTrue(a.getPreferences(0).getBoolean("input_uncertain",false));
    ((Button)get(a,"sendText")).performClick();((Button)get(a,"sendEnter")).performClick();assertEquals(1,controller.textSends);assertEquals(0,controller.enters);
    controller.listener.onMutationResult(controller.id,TARGET,SessionRepository.MutationOutcome.NOT_STARTED);assertFalse(a.getPreferences(0).getBoolean("input_uncertain",false));assertFalse(((Button)get(a,"sendEnter")).isEnabled());
    assertTrue(((Button)get(a,"refresh")).performClick()); // NOT_STARTED does not implicitly refresh.
    controller.listener.onSnapshot(TARGET,"fresh before explicit second attempt");draft.setText("synthetic text");((Button)get(a,"sendText")).performClick();int readsBefore=controller.reads;
    controller.listener.onMutationResult(controller.id,TARGET,SessionRepository.MutationOutcome.COMPLETED);
    assertEquals(readsBefore+1,controller.reads);assertEquals(2,controller.textSends);assertEquals(0,controller.enters);assertFalse(((Button)get(a,"sendEnter")).isEnabled());
    controller.listener.onSnapshot(TARGET,"fresh post-text snapshot");assertTrue(((Button)get(a,"sendEnter")).isEnabled());
   });
  }
 }
 @Test public void unknownPersistsAcrossRecreationUntilFreshExplicitInspection(){
  try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
   scenario.onActivity(a->{Fake fake=install(a,"controller");fake.listener.onSnapshot(TARGET,"fresh");((Button)get(a,"sendEnter")).performClick();assertEquals(1,fake.enters);fake.listener.onState(SessionRepository.State.DISCONNECTED,"");assertTrue(a.getPreferences(0).getBoolean("input_uncertain",false));});
   scenario.recreate();
   scenario.onActivity(a->{
    assertTrue(a.getPreferences(0).getBoolean("input_uncertain",false));Fake fresh=install(a,"controller");assertFalse(((Button)get(a,"acknowledgeInput")).isEnabled());fresh.listener.onSnapshot(TARGET,"inspect current terminal");assertFalse(((Button)get(a,"sendEnter")).isEnabled());assertTrue(((Button)get(a,"acknowledgeInput")).isEnabled());assertEquals(0,fresh.enters);
    ((Button)get(a,"acknowledgeInput")).performClick();assertFalse(a.getPreferences(0).getBoolean("input_uncertain",false));assertTrue(((Button)get(a,"sendEnter")).isEnabled());assertEquals(0,fresh.enters);
    ((Button)get(a,"sendEnter")).performClick();assertEquals(1,fresh.enters);int beforeReads=fresh.reads;fresh.listener.onMutationResult(fresh.id,TARGET,SessionRepository.MutationOutcome.COMPLETED);assertFalse(a.getPreferences(0).getBoolean("input_uncertain",false));
    assertEquals(beforeReads+1,fresh.reads);assertEquals(1,fresh.enters);assertEquals(0,fresh.textSends);assertFalse(((Button)get(a,"sendEnter")).isEnabled());
    fresh.listener.onSnapshot(TARGET,"fresh correlated post-delivery snapshot");assertTrue(((Button)get(a,"sendEnter")).isEnabled());
   });
  }
 }
}
