package dev.herdrbridge.nativeclient;

import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.view.View;
import android.widget.*;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.FutureTask;
import static org.junit.Assert.*;

/** Isolated emulator UI tests. No real invitation, endpoint, camera or production clipboard data. */
public class ReaderQrUiTest {
    @Before public void isolatedInstall(){
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Assume.assumeFalse(new DeviceVault(context).hasRecord());
        for(String name:new String[]{"MainActivity",MainActivity.class.getName()})
            Assume.assumeFalse(context.getSharedPreferences(name,Context.MODE_PRIVATE).getBoolean("auto_connect",false));
    }
    private static Object field(Activity a,String name){try{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(a);}catch(Exception e){throw new AssertionError(e);}}
    private static void set(Activity a,String name,Object value){try{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(a,value);}catch(Exception e){throw new AssertionError(e);}}
    private static void call(Activity a,String name,Class<?>[] types,Object...args){try{Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(a,args);}catch(Exception e){throw new AssertionError(e);}}

    @Test public void localPickerContractAndCancelledStaleNonContentResultsFailClosed(){
        for(int api:new int[]{28,33}){
            Intent intent=MainActivity.imagePickerIntent(api);
            assertEquals(api>=33?android.provider.MediaStore.ACTION_PICK_IMAGES:Intent.ACTION_OPEN_DOCUMENT,intent.getAction());
            assertEquals("image/*",intent.getType());assertTrue(intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY,false));
            assertEquals(0,intent.getFlags()&Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        }
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{
                set(a,"pickerAwaiting",true);set(a,"activeQrRequest",123);set(a,"qrOrigin","https://example.invalid");
                a.onActivityResult(122,Activity.RESULT_OK,new Intent().setData(Uri.parse("content://stale.invalid/image")));
                assertEquals(true,field(a,"pickerAwaiting"));assertNull(field(a,"qrJob"));
                a.onActivityResult(123,Activity.RESULT_CANCELED,null);
                assertEquals(false,field(a,"pickerAwaiting"));assertNull(field(a,"repository"));
                set(a,"pickerAwaiting",true);set(a,"activeQrRequest",124);
                a.onActivityResult(124,Activity.RESULT_OK,new Intent().setData(Uri.parse("https://never-open.invalid/image")));
                assertNull(field(a,"qrJob"));assertNull(field(a,"repository"));
                assertEquals("",((EditText)field(a,"invitation")).getText().toString());
            });
        }
    }

    @Test public void backgroundCancelsDecoderWorkAndClearsInvitation(){
        FutureTask<Void> job=new FutureTask<>(()->null);CancellationSignal signal=new CancellationSignal();long[] epoch={0};
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{set(a,"qrJob",job);set(a,"qrCancellation",signal);epoch[0]=(Long)field(a,"qrEpoch");((EditText)field(a,"invitation")).setText("synthetic-transient");});
            scenario.moveToState(Lifecycle.State.CREATED);scenario.moveToState(Lifecycle.State.RESUMED);
            scenario.onActivity(a->{assertTrue(job.isCancelled());assertTrue(signal.isCanceled());assertTrue((Long)field(a,"qrEpoch")>epoch[0]);assertEquals("",((EditText)field(a,"invitation")).getText().toString());assertNull(field(a,"repository"));
                FutureTask<Void> timeoutJob=new FutureTask<>(()->null);CancellationSignal timeoutSignal=new CancellationSignal();
                set(a,"qrJob",timeoutJob);set(a,"qrCancellation",timeoutSignal);
                call(a,"scheduleQrTimeout",new Class<?>[]{long.class},field(a,"qrEpoch"));
                ((Runnable)field(a,"qrTimeout")).run();
                assertTrue(timeoutJob.isCancelled());assertTrue(timeoutSignal.isCanceled());assertNull(field(a,"qrTimeout"));
                assertTrue(((TextView)field(a,"state")).getText().toString().contains("超时"));
            });
        }
    }

    @Test public void nativeReaderLayoutShowsDistinctPaneAndExplicitSensitiveCopy(){
        SessionRepository.SessionTarget target=new SessionRepository.SessionTarget("server-A","pane-A","terminal-A",1,"");
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{
                set(a,"ready",true);((ScrollView)field(a,"setup")).setVisibility(View.GONE);
                // Test-only read transport records the selection without opening a socket.
                ForegroundConnection c=new ForegroundConnection(new ForegroundConnection.Factory(){
                    public boolean hasSaved(){return true;}public SessionRepository saved(){return new SessionRepository(){public void start(Listener l){}public void close(){}public void requestSnapshot(SessionTarget t){assertEquals(target,t);}};}
                },new ForegroundConnection.Scheduler(){public void execute(Runnable r){r.run();}public void after(long d,Runnable r){}public void cancel(Runnable r){}},new SessionRepository.Listener(){public void onState(SessionRepository.State s,String x){}public void onSessions(List<SessionRepository.Session> s){}public void onSnapshot(SessionRepository.SessionTarget t,String x){}},()->0.5);
                set(a,"connection",c);
                call(a,"displaySessions",new Class<?>[]{List.class},Collections.emptyList());
                assertTrue(((TextView)field(a,"state")).getText().toString().contains("没有可显示"));
                SessionRepository.SessionTarget other=new SessionRepository.SessionTarget("server-A","pane-B","terminal-B",1,"");
                call(a,"displaySessions",new Class<?>[]{List.class},Arrays.asList(new SessionRepository.Session(target,"","idle"),new SessionRepository.Session(other,"\u001b[31m标题","running")));
                LinearLayout sessions=(LinearLayout)field(a,"sessions");assertEquals(2,sessions.getChildCount());
                assertTrue(((Button)sessions.getChildAt(0)).getText().toString().contains("pane-A"));assertTrue(((Button)sessions.getChildAt(1)).getText().toString().contains("pane-B"));
                sessions.getChildAt(0).performClick();assertEquals(View.GONE,((ScrollView)field(a,"sessionList")).getVisibility());
                assertTrue(((TextView)field(a,"state")).getText().toString().contains("正在读取"));
                assertFalse(((Button)field(a,"copy")).isEnabled());
                call(a,"displaySnapshot",new Class<?>[]{SessionRepository.SessionTarget.class,String.class},target,"\u001b[31msynthetic snapshot\u001b[0m");
                assertEquals("synthetic snapshot",((TextView)field(a,"terminal")).getText().toString());
                ((Button)field(a,"copy")).performClick();
                ClipboardManager clipboard=(ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE);
                try{ClipData clip=clipboard.getPrimaryClip();assertNotNull(clip);assertEquals("synthetic snapshot",clip.getItemAt(0).getText().toString());if(Build.VERSION.SDK_INT>=33)assertTrue(clip.getDescription().getExtras().getBoolean(ClipDescription.EXTRA_IS_SENSITIVE));}
                finally{clipboard.clearPrimaryClip();}
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(a->{View output=(View)field(a,"output");assertTrue("Reader leaves a usable terminal viewport",output.getHeight()>a.getWindow().getDecorView().getHeight()/3);});
        }
    }
    @Test public void verifiedQrResultOnlyPopulatesCurrentForegroundOrigin(){
        String configured="https://example.invalid";
        String decoded=configured+"/#gateways=wss%3A%2F%2Fexample.invalid&invite=BBBBBBBBBBBBBBBBBBBBBB&invite_expires="+(System.currentTimeMillis()+600000)+"&invite_version=1&label=Test&path_policy=relay-only&relay_id=AAAAAAAAAAAAAAAAAAAAAA&rendezvous=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&setup=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{
                EditText origin=(EditText)field(a,"origin"),invitation=(EditText)field(a,"invitation");origin.setText(configured);
                long epoch=(Long)field(a,"qrEpoch");CancellationSignal signal=new CancellationSignal();
                Class<?>[] types={long.class,String.class,CancellationSignal.class,String.class};
                call(a,"acceptQrResult",types,epoch-1,configured,signal,decoded);assertEquals("",invitation.getText().toString());
                origin.setText("https://other.invalid");call(a,"acceptQrResult",types,epoch,configured,signal,decoded);assertEquals("",invitation.getText().toString());
                origin.setText(configured);call(a,"acceptQrResult",types,epoch,configured,signal,decoded);
                assertEquals(decoded,invitation.getText().toString());assertNull(field(a,"repository"));assertNull(field(a,"connection"));
                assertFalse(a.getPreferences(Context.MODE_PRIVATE).getAll().containsValue(decoded));
            });
            scenario.moveToState(Lifecycle.State.CREATED);scenario.moveToState(Lifecycle.State.RESUMED);
            scenario.onActivity(a->assertEquals("",((EditText)field(a,"invitation")).getText().toString()));
        }
    }

}
