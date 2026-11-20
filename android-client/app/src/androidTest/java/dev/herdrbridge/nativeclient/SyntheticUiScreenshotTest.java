package dev.herdrbridge.nativeclient;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.view.View;
import android.view.KeyEvent;
import android.os.SystemClock;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Synthetic-only screenshots of the real Activity. No socket, credential or terminal. */
public class SyntheticUiScreenshotTest {
    private static final SessionRepository.SessionTarget TARGET = new SessionRepository.SessionTarget("primary","synthetic-pane","synthetic-terminal",1,"synthetic-agent");
    private static Object get(Activity a,String name){try{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(a);}catch(Exception e){throw new AssertionError(e);}}
    private static void set(Activity a,String name,Object value){try{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(a,value);}catch(Exception e){throw new AssertionError(e);}}
    private static void call(Activity a,String name,Class<?>[] types,Object...args){try{Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(a,args);}catch(Exception e){throw new AssertionError(e);}}
    @Before public void requireCleanSyntheticInstall(){
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertFalse("Never capture an enrolled installation",new DeviceVault(c).hasRecord());
        for(String n:new String[]{"MainActivity",MainActivity.class.getName()}){
            assertFalse(c.getSharedPreferences(n,0).getBoolean("auto_connect",false));
            String origin=c.getSharedPreferences(n,0).getString("origin","");
            assertTrue("Never capture real configuration",origin.isEmpty()||origin.equals("https://example.invalid"));
        }
    }
    private static final class Fake implements SessionRepository {
        Listener listener; int snapshots;
        public void start(Listener listener){this.listener=listener;}
        public void close(){}
        public void requestSnapshot(SessionTarget target){assertEquals(TARGET,target);snapshots++;}
        public String sendTerminalText(SessionTarget target,String value){throw new AssertionError("Screenshot test must never write");}
        public String sendEnter(SessionTarget target){throw new AssertionError("Screenshot test must never send Enter");}
    }
    private static Fake install(MainActivity a,String role){
        ForegroundConnection old=(ForegroundConnection)get(a,"connection");if(old!=null)old.stop();
        Fake fake=new Fake();
        ForegroundConnection connection=new ForegroundConnection(new ForegroundConnection.Factory(){public boolean hasSaved(){return true;}public SessionRepository saved(){return fake;}},new ForegroundConnection.Scheduler(){public void execute(Runnable r){r.run();}public void after(long delay,Runnable r){}public void cancel(Runnable r){}},new SessionRepository.Listener(){
            public void onAccess(String value){set(a,"verifiedRole",value);}
            public void onState(SessionRepository.State state,String ignored){set(a,"ready",state==SessionRepository.State.READY);call(a,"updateInputControls",new Class<?>[]{});}
            public void onSessions(List<SessionRepository.Session> rows){call(a,"displaySessions",new Class<?>[]{List.class},rows);}
            public void onSnapshot(SessionRepository.SessionTarget target,String text){call(a,"displaySnapshot",new Class<?>[]{SessionRepository.SessionTarget.class,String.class},target,text);}
        },()->0.5);
        set(a,"connection",connection);connection.foreground();connection.explicitSaved();
        fake.listener.onAccess(role);fake.listener.onState(SessionRepository.State.READY,"");
        fake.listener.onSessions(Arrays.asList(new SessionRepository.Session(TARGET,"示例项目 · 构建检查","working",true),new SessionRepository.Session(new SessionRepository.SessionTarget("primary","synthetic-pane-2","synthetic-terminal-2",1,""),"文档预览 · 待处理","idle",true)));
        return fake;
    }
    private static void snapshot(Fake fake){fake.listener.onSnapshot(TARGET,"$ npm run check\n\n✓ TypeScript 检查通过\n✓ 单元测试通过\n\n示例输出：这是合成测试数据，不连接真实终端。\n等待下一步操作…");}
    private static void capture(String name)throws Exception{
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        Thread.sleep(400);
        Bitmap bitmap=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull("UiAutomation screenshot unavailable",bitmap);
        try{
            File dir=new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null),"synthetic-ui");
            assertTrue(dir.isDirectory()||dir.mkdirs());
            try(FileOutputStream out=new FileOutputStream(new File(dir,name+".png"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}
        }finally{bitmap.recycle();}
    }
    private static boolean imeVisible(Activity activity){
        Rect frame=new Rect();
        activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(frame);
        android.util.DisplayMetrics screen=new android.util.DisplayMetrics();
        activity.getWindowManager().getDefaultDisplay().getRealMetrics(screen);
        return screen.heightPixels-frame.bottom>180*activity.getResources().getDisplayMetrics().density;
    }
    private static void assertFullyVisibleAboveIme(Activity activity,String name,int minimumDp){
        View control=(View)get(activity,name);
        Rect visible=new Rect(),clipped=new Rect();
        activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(visible);
        assertTrue(name+" must be visible",control.isShown()&&control.getGlobalVisibleRect(clipped));
        assertEquals(name+" horizontal clipping",control.getWidth(),clipped.width());
        assertEquals(name+" vertical clipping",control.getHeight(),clipped.height());
        int minimum=Math.round(minimumDp*activity.getResources().getDisplayMetrics().density);
        assertTrue(name+" hit area too small",control.getWidth()>=minimum&&control.getHeight()>=minimum);
        int[] position=new int[2];control.getLocationOnScreen(position);
        assertTrue(name+" outside visible screen above the IME",visible.contains(position[0],position[1],position[0]+control.getWidth(),position[1]+control.getHeight()));
    }
    private static void systemBack(){
        long now=SystemClock.uptimeMillis();
        android.app.UiAutomation ui=InstrumentationRegistry.getInstrumentation().getUiAutomation();
        assertTrue("System Back down was not injected",ui.injectInputEvent(new KeyEvent(now,now,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK,0),true));
        assertTrue("System Back up was not injected",ui.injectInputEvent(new KeyEvent(now,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BACK,0),true));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
    private static boolean awaitIme(ActivityScenario<MainActivity> scenario,boolean visible)throws InterruptedException{
        for(int attempt=0;attempt<20;attempt++){
            AtomicReference<Boolean> shown=new AtomicReference<>();
            scenario.onActivity(a->shown.set(imeVisible(a)));
            if(shown.get()==visible)return true;
            Thread.sleep(150);
        }
        return false;
    }
    @Test public void syntheticScreensAndControllerComposer()throws Exception{
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            AtomicReference<Fake> fixture=new AtomicReference<>();
            try{
                scenario.onActivity(a->{
                    assertTrue((a.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0);
                    // ONLY this synthetic instrumentation clears protection, never production code.
                    a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                    ((EditText)get(a,"origin")).setText("");((EditText)get(a,"invitation")).setText("");
                    assertTrue(((View)get(a,"setup")).isShown());
                });
                capture("01-pairing-empty");
                scenario.onActivity(a->{fixture.set(install(a,"reader"));call(a,"showHome",new Class<?>[]{});assertEquals(2,((LinearLayout)get(a,"sessions")).getChildCount());assertTrue(((View)get(a,"sessions")).isShown());});
                capture("02-session-home");
                scenario.onActivity(a->{((LinearLayout)get(a,"sessions")).getChildAt(0).performClick();snapshot(fixture.get());assertTrue(((View)get(a,"terminal")).isShown());assertEquals(View.GONE,((View)get(a,"inputPanel")).getVisibility());});
                capture("03-reader-detail");
                scenario.onActivity(a->{fixture.set(install(a,"controller"));((LinearLayout)get(a,"sessions")).getChildAt(0).performClick();snapshot(fixture.get());assertTrue(((View)get(a,"inputPanel")).isShown());assertTrue(((Button)get(a,"sendEnter")).isEnabled());assertEquals("",((EditText)get(a,"terminalDraft")).getText().toString());});
                capture("04-controller-composer");
                scenario.onActivity(a->{EditText draft=(EditText)get(a,"terminalDraft");draft.requestFocus();((InputMethodManager)a.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(draft,InputMethodManager.SHOW_IMPLICIT);});
                // API29 has no WindowInsets.Type.ime; verify the actual app-visible
                // frame shrank by a keyboard-sized amount, never label a hidden IME.
                assertTrue("Actual IME must be visible for keyboard screenshot",awaitIme(scenario,true));
                scenario.onActivity(a->{
                    assertFullyVisibleAboveIme(a,"terminalDraft",0);
                    assertFullyVisibleAboveIme(a,"sendText",44);
                    assertFullyVisibleAboveIme(a,"sendEnter",44);
                });
                capture("05-controller-ime-visible");
                systemBack();
                assertTrue("First system Back must dismiss IME",awaitIme(scenario,false));
                scenario.onActivity(a->assertEquals("DETAIL",get(a,"currentScreen")));
                systemBack();
                scenario.onActivity(a->{
                    assertEquals("HOME",get(a,"currentScreen"));
                    assertTrue(((View)get(a,"sessions")).isShown());
                    assertFalse(((View)get(a,"terminal")).isShown());
                    assertEquals("",((EditText)get(a,"terminalDraft")).getText().toString());
                });
            }finally{scenario.onActivity(a->{a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);assertTrue((a.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0);});}
        }
    }
}
