package dev.herdrbridge.nativeclient;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.view.View;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.InputDevice;
import android.view.accessibility.AccessibilityWindowInfo;
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
    private static final SessionRepository.SessionTarget OTHER = new SessionRepository.SessionTarget("primary","synthetic-pane-2","synthetic-terminal-2",1,"");
    private static final String ATLAS_TITLE="Atlas Web · 构建检查";
    private static final String LONG_TITLE="城市基础地理信息数据库增量更新与高分辨率正射影像质量抽检作业";
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
        inventory(fake,ATLAS_TITLE);
        return fake;
    }
    private static void inventory(Fake fake,String title){
        fake.listener.onSessions(Arrays.asList(
                new SessionRepository.Session(TARGET,title,"working",true),
                new SessionRepository.Session(OTHER,"地图文档 · 预览服务","idle",true)));
    }
    private static void snapshot(Fake fake){
        fake.listener.onSnapshot(TARGET,
                "C:\\work\\atlas-web> npm run check\n"
                +"\n> atlas-web@0.8.4 check\n> tsc --noEmit && vitest run\n\n"
                +" ✓ src/map/layers.test.ts       (12 tests)\n"
                +" ✓ src/map/selection.test.ts   ( 8 tests)\n"
                +" ✓ src/import/geojson.test.ts  (16 tests)\n\n"
                +" Test Files  3 passed (3)\n"
                +"      Tests  36 passed (36)\n"
                +"   Duration  1.43s\n\n"
                +"C:\\work\\atlas-web> ");
    }
    private static View recent(Activity activity){return (View)get(activity,"recentButton");}
    private static boolean hasRecent(Activity activity){View card=recent(activity);return card!=null&&card.isShown();}
    private static void openAtlas(Activity activity){
        if(hasRecent(activity)){
            assertTrue(((TextView)recent(activity)).getText().toString().contains("Atlas Web"));
            assertTrue(recent(activity).performClick());
            return;
        }
        LinearLayout rows=(LinearLayout)get(activity,"sessions");
        for(int i=0;i<rows.getChildCount();i++){
            View row=rows.getChildAt(i);
            if(row instanceof TextView&&((TextView)row).getText().toString().contains("Atlas Web")){
                assertTrue(row.performClick());return;
            }
        }
        fail("Missing Atlas Web in synthetic inventory");
    }
    private static void capture(ActivityScenario<MainActivity> scenario,String name)throws Exception{
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        Thread.sleep(400);
        Rect appFrame=new Rect();
        scenario.onActivity(a->a.getWindow().getDecorView().getWindowVisibleDisplayFrame(appFrame));
        Bitmap screenshot=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull("UiAutomation screenshot unavailable",screenshot);
        Bitmap bitmap=screenshot.getConfig()==Bitmap.Config.HARDWARE?screenshot.copy(Bitmap.Config.ARGB_8888,false):screenshot;
        if(bitmap!=screenshot)screenshot.recycle();
        assertNotNull("Screenshot pixels unavailable",bitmap);
        try{
            File dir=new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null),"synthetic-ui");
            assertTrue(dir.isDirectory()||dir.mkdirs());
            try(FileOutputStream out=new FileOutputStream(new File(dir,name+".png"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}
            // Check actual pixels from the app viewport, never the system keyboard
            // or status/navigation bars. Preserve PNG on failure for inspection.
            assertTrue("Screen bounds outside screenshot",appFrame.intersect(0,0,bitmap.getWidth(),bitmap.getHeight()));
            Set<Integer> colors=new HashSet<>();int dark=0, total=0;
            for(int y=appFrame.top;y<appFrame.bottom;y+=3){
                for(int x=appFrame.left;x<appFrame.right;x+=3){
                    int pixel=bitmap.getPixel(x,y);colors.add(pixel&0x00F0F0F0);
                    if(android.graphics.Color.luminance(pixel)<0.18f)dark++;
                    total++;
                }
            }
            assertTrue(name+" is blank or screenshot protection was not lifted",colors.size()>=8);
            assertTrue(name+" should render a predominantly dark app surface",total>0&&dark/(double)total>=0.65);
        }finally{bitmap.recycle();}
    }
    private static void assertIconAction(Activity activity,String field,String expectedDescription){
        View view=(View)get(activity,field);
        assertTrue(field+" must be visible and actionable",view.isShown()&&view.isClickable());
        assertEquals(field+" must have an exact accessible action label",expectedDescription,
                view.getContentDescription()==null?null:view.getContentDescription().toString());
    }
    private static Rect visibleFrame(Activity activity){
        Rect frame=new Rect();
        activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(frame);
        return frame;
    }
    private static final class ImeState {
        boolean appFocused, editorFocused, editorServed, nativeVisible;
        int aboveKeyboardBottom, missingDp, imeWindows;
        boolean visible(){return (nativeVisible||imeWindows>0)&&missingDp>=64;}
        boolean hidden(){return !nativeVisible&&imeWindows==0&&missingDp<32;}
        @Override public String toString(){return "windowFocus="+appFocused+" editorFocus="+editorFocused
                +" served="+editorServed+" nativeIme="+nativeVisible+" imeWindows="+imeWindows
                +" frameBottom="+aboveKeyboardBottom+" missingDp="+missingDp;}
    }
    @android.annotation.TargetApi(30)
    private static final class ModernIme {
        static boolean visible(View decor){
            android.view.WindowInsets insets=decor.getRootWindowInsets();
            return insets!=null&&insets.isVisible(android.view.WindowInsets.Type.ime());
        }
    }
    private static ImeState probeIme(ActivityScenario<MainActivity> scenario,String editor,int initialBottom){
        ImeState result=new ImeState();
        scenario.onActivity(a->{
            EditText draft=(EditText)get(a,editor);
            result.appFocused=a.hasWindowFocus();
            result.editorFocused=draft.hasFocus();
            result.editorServed=((InputMethodManager)a.getSystemService(Context.INPUT_METHOD_SERVICE)).isActive(draft);
            result.aboveKeyboardBottom=visibleFrame(a).bottom;
            result.missingDp=Math.round((initialBottom-result.aboveKeyboardBottom)/a.getResources().getDisplayMetrics().density);
            if(android.os.Build.VERSION.SDK_INT>=30)result.nativeVisible=ModernIme.visible(a.getWindow().getDecorView());
        });
        for(AccessibilityWindowInfo window:InstrumentationRegistry.getInstrumentation().getUiAutomation().getWindows()){
            try{if(window.getType()==AccessibilityWindowInfo.TYPE_INPUT_METHOD)result.imeWindows++;}
            finally{window.recycle();}
        }
        return result;
    }
    private static void tapEditor(ActivityScenario<MainActivity> scenario,String editor){
        int[] xy=new int[2];
        scenario.onActivity(a->{
            View draft=(View)get(a,editor);
            assertTrue("Activity must own window focus before input tap",a.hasWindowFocus());
            assertFullyVisibleAboveIme(a,editor,0);
            draft.getLocationOnScreen(xy);xy[0]+=draft.getWidth()/2;xy[1]+=draft.getHeight()/2;
        });
        long now=SystemClock.uptimeMillis();
        android.app.UiAutomation ui=InstrumentationRegistry.getInstrumentation().getUiAutomation();
        for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP}){
            MotionEvent event=MotionEvent.obtain(now,SystemClock.uptimeMillis(),action,xy[0],xy[1],0);
            try{event.setSource(InputDevice.SOURCE_TOUCHSCREEN);assertTrue("Draft tap injection failed",ui.injectInputEvent(event,true));}
            finally{event.recycle();}
        }
    }
    private static void requestImeForServedEditor(ActivityScenario<MainActivity> scenario,String editor){
        scenario.onActivity(a->{
            EditText draft=(EditText)get(a,editor);
            InputMethodManager manager=(InputMethodManager)a.getSystemService(Context.INPUT_METHOD_SERVICE);
            if(!a.hasWindowFocus()||!draft.hasFocus()||!manager.isActive(draft))return;
            boolean requested=manager.showSoftInput(draft,InputMethodManager.SHOW_IMPLICIT,
                    new android.os.ResultReceiver(new android.os.Handler(android.os.Looper.getMainLooper())){
                        @Override protected void onReceiveResult(int code,android.os.Bundle data){android.util.Log.i("BridgeSyntheticIme","show result="+code);}
                    });
            android.util.Log.i("BridgeSyntheticIme","show request accepted="+requested);
        });
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
    private static void assertTerminalSpaceAboveIme(Activity activity){
        View output=(View)get(activity,"output");
        Rect visibleFrame=new Rect(), exposed=new Rect();
        activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(visibleFrame);
        assertTrue("Terminal viewport must remain visible with IME open",output.isShown()&&output.getGlobalVisibleRect(exposed));
        int dp96=Math.round(96*activity.getResources().getDisplayMetrics().density);
        int minHeight=Math.max(dp96,visibleFrame.height()/4);
        assertTrue("Terminal viewport too short above keyboard: "+exposed.height()+"px, required "+minHeight+"px",exposed.height()>=minHeight);
        int[] where=new int[2];output.getLocationOnScreen(where);
        assertTrue("Terminal viewport must lie above keyboard",where[1]+output.getHeight()<=visibleFrame.bottom);
        float density=activity.getResources().getDisplayMetrics().density;
        int heightDp=Math.round(exposed.height()/density);
        int frameBottomDp=Math.round(visibleFrame.bottom/density);
        int baselineComparable=heightDp+552-frameBottomDp;
        android.util.Log.i("BridgeSyntheticIme","terminalHeightDp="+heightDp+" frameBottomDp="+frameBottomDp
                +" baselineComparableDp="+baselineComparable+" baseline61b84c7Dp=330");
        Rect frame=visibleFrame(activity);
        if(Math.abs(frame.width()/density-400)<=2&&Math.abs(activity.getResources().getConfiguration().fontScale-1f)<.01f){
            assertTrue("Terminal gain below one baseline line (17dp): normalized height "+baselineComparable+"dp",baselineComparable>=347);
        }

    }
    private static void systemBack(){
        long now=SystemClock.uptimeMillis();
        android.app.UiAutomation ui=InstrumentationRegistry.getInstrumentation().getUiAutomation();
        assertTrue("System Back down was not injected",ui.injectInputEvent(new KeyEvent(now,now,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK,0),true));
        assertTrue("System Back up was not injected",ui.injectInputEvent(new KeyEvent(now,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BACK,0),true));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
    private static void awaitIme(ActivityScenario<MainActivity> scenario,String editor,int initialBottom,boolean visible)throws InterruptedException{
        long start=SystemClock.uptimeMillis(),deadline=start+15000;
        boolean retried=false;int consecutive=0;
        ImeState state;
        do{
            state=probeIme(scenario,editor,initialBottom);
            boolean matches=visible?state.visible()&&state.appFocused&&state.editorFocused:state.hidden();
            if(matches){
                if(++consecutive>=2){android.util.Log.i("BridgeSyntheticIme",(visible?"visible: ":"hidden: ")+state);return;}
            }else consecutive=0;
            if(visible&&!retried&&SystemClock.uptimeMillis()-start>=3000&&state.editorServed){
                retried=true;requestImeForServedEditor(scenario,editor);
            }
            Thread.sleep(180);
        }while(SystemClock.uptimeMillis()<deadline);
        android.util.Log.i("BridgeSyntheticIme","timeout: "+state);
        fail("Actual IME did not become "+(visible?"visible":"hidden")+": "+state);
    }
    private static void exerciseSearch(ActivityScenario<MainActivity> scenario,Fake fake)throws Exception{
        android.app.UiAutomation automation=InstrumentationRegistry.getInstrumentation().getUiAutomation();
        android.accessibilityservice.AccessibilityServiceInfo info=automation.getServiceInfo();
        int flags=info.flags;info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        automation.setServiceInfo(info);
        try{
            int bottom=probeIme(scenario,"searchQuery",0).aboveKeyboardBottom;
            scenario.onActivity(a->{
                assertIconAction(a,"searchButton","搜索会话");
                assertTrue(((View)get(a,"searchButton")).performClick());
                assertEquals(true,get(a,"searchOpen"));
            });
            if(!probeIme(scenario,"searchQuery",bottom).visible())tapEditor(scenario,"searchQuery");
            awaitIme(scenario,"searchQuery",bottom,true);
            scenario.onActivity(a->{
                EditText query=(EditText)get(a,"searchQuery");
                LinearLayout rows=(LinearLayout)get(a,"sessions");
                query.setText("aTLaS");
                assertTrue(hasRecent(a));assertEquals(0,rows.getChildCount());
                query.setText("无匹配的示例关键字");
                assertFalse(hasRecent(a));assertEquals(0,rows.getChildCount());
                assertTrue(((View)get(a,"searchEmpty")).isShown());
                assertTrue(((View)get(a,"searchClear")).performClick());
                assertEquals("",query.getText().toString());
                assertTrue(hasRecent(a));assertEquals(1,rows.getChildCount());
                query.setText("地图");
                assertFalse(hasRecent(a));assertEquals(1,rows.getChildCount());
                assertTrue(((TextView)rows.getChildAt(0)).getText().toString().contains("地图文档"));
                // An authoritative live rename refreshes the filtered view, without
                // closing search or resetting the query.
                query.setText("Atlas");
                inventory(fake,"绘图工程 · 构建检查");
                assertEquals("Atlas",query.getText().toString());
                assertEquals(true,get(a,"searchOpen"));
                assertFalse(hasRecent(a));assertTrue(((View)get(a,"searchEmpty")).isShown());
                inventory(fake,ATLAS_TITLE);
                assertTrue(hasRecent(a));
                query.setText("地图");
            });
            capture(scenario,"06-home-search");
            assertTrue("Search IME disappeared during screenshot",probeIme(scenario,"searchQuery",bottom).visible());
            systemBack();awaitIme(scenario,"searchQuery",bottom,false);
            scenario.onActivity(a->{assertEquals("HOME",get(a,"currentScreen"));assertEquals(true,get(a,"searchOpen"));});
            systemBack();
            scenario.onActivity(a->{
                assertEquals("HOME",get(a,"currentScreen"));assertEquals(false,get(a,"searchOpen"));
                assertEquals("",((EditText)get(a,"searchQuery")).getText().toString());
                assertTrue(hasRecent(a));assertEquals(1,((LinearLayout)get(a,"sessions")).getChildCount());
            });
        }finally{info.flags=flags;automation.setServiceInfo(info);}
    }
    private static void assertLongHeading(Activity activity){
        TextView heading=(TextView)get(activity,"detailHeading");
        assertEquals(LONG_TITLE,heading.getText().toString());
        assertFullyVisibleAboveIme(activity,"detailHeading",0);
        android.text.Layout layout=heading.getLayout();
        assertNotNull("Long title must have a real text layout",layout);
        assertTrue("Long title must wrap rather than shrink away",layout.getLineCount()>=2);
        for(int i=0;i<layout.getLineCount();i++)assertEquals("Long title is visually ellipsized",0,layout.getEllipsisCount(i));
        assertEquals("Long title layout dropped trailing characters",LONG_TITLE.length(),layout.getLineEnd(layout.getLineCount()-1));
        assertTrue("Long title must fit vertically",layout.getHeight()<=heading.getHeight()-heading.getCompoundPaddingTop()-heading.getCompoundPaddingBottom());
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
                String profile=InstrumentationRegistry.getArguments().getString("bridge_visual_profile","");
                if("narrow".equals(profile)){
                    scenario.onActivity(a->{
                        android.content.res.Configuration config=a.getResources().getConfiguration();
                        assertTrue("Expected a 360dp viewport: "+config.screenWidthDp,config.screenWidthDp>=355&&config.screenWidthDp<=365);
                        assertEquals("Expected changed font scale",1.20f,config.fontScale,.02f);
                        fixture.set(install(a,"controller"));
                        inventory(fixture.get(),LONG_TITLE);
                        assertTrue(((LinearLayout)get(a,"sessions")).getChildAt(0).performClick());
                        assertEquals(TARGET,get(a,"selected"));snapshot(fixture.get());
                    });
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                    scenario.onActivity(SyntheticUiScreenshotTest::assertLongHeading);
                    capture(scenario,"08-long-cjk-360-font120");
                    return;
                }
                assertEquals("Unexpected synthetic profile","",profile);
                capture(scenario,"01-pairing-empty");
                scenario.onActivity(a->{
                    fixture.set(install(a,"controller"));
                    call(a,"showHome",new Class<?>[]{});
                    assertFalse("First-use home cannot invent a recent session",hasRecent(a));
                    assertEquals(2,((LinearLayout)get(a,"sessions")).getChildCount());
                    openAtlas(a);
                    snapshot(fixture.get());
                    assertEquals(TARGET,get(a,"selected"));
                });
                systemBack();
                scenario.onActivity(a->{
                    assertEquals("HOME",get(a,"currentScreen"));
                    assertTrue("Returning from Atlas creates a real recent entry",hasRecent(a));
                    LinearLayout rows=(LinearLayout)get(a,"sessions");
                    assertEquals("The recent entry may not also appear in the other sessions list",1,rows.getChildCount());
                    assertTrue(((TextView)rows.getChildAt(0)).getText().toString().contains("地图文档"));
                    assertIconAction(a,"settingsButton","设置");
                    assertEquals("Herdr Bridge",((TextView)get(a,"screenTitle")).getText().toString());
                    assertEquals("02",((TextView)get(a,"homeCount")).getText().toString());
                    assertEquals("可控制",((TextView)get(a,"footRole")).getText().toString());
                    assertTrue(((TextView)get(a,"accessLabel")).getText().toString().contains("Controller"));
                });
                capture(scenario,"02-session-home");
                exerciseSearch(scenario,fixture.get());
                scenario.onActivity(a->{
                    fixture.set(install(a,"reader"));
                    assertTrue(((TextView)get(a,"accessLabel")).getText().toString().contains("Reader"));
                    openAtlas(a);
                    snapshot(fixture.get());
                    assertTrue(((View)get(a,"terminal")).isShown());
                    assertEquals(View.GONE,((View)get(a,"inputPanel")).getVisibility());
                    assertIconAction(a,"showSessions","返回会话");
                    assertIconAction(a,"refresh","刷新终端");
                    assertIconAction(a,"copy","复制终端内容");
                });
                capture(scenario,"03-reader-detail");
                systemBack();
                scenario.onActivity(a->assertEquals("HOME",get(a,"currentScreen")));
                scenario.onActivity(a->{
                    fixture.set(install(a,"controller"));openAtlas(a);snapshot(fixture.get());
                    assertTrue("Controller composer is shown",((View)get(a,"inputPanel")).isShown());
                    TextView role=(TextView)get(a,"detailRole");
                    assertTrue("Controller role is visible in the detail layout",role.isShown());
                    assertTrue("Controller role is explicitly labeled",role.getText().toString().contains("Controller"));
                    assertTrue("Authorized fresh snapshot enables the separate Enter action",((View)get(a,"sendEnter")).isEnabled());
                    assertEquals("",((EditText)get(a,"terminalDraft")).getText().toString());
                });
                capture(scenario,"04-controller-composer");
                android.app.UiAutomation automation=InstrumentationRegistry.getInstrumentation().getUiAutomation();
                android.accessibilityservice.AccessibilityServiceInfo original=automation.getServiceInfo();
                int originalFlags=original.flags;
                original.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
                automation.setServiceInfo(original);
                try{
                    int beforeIme=probeIme(scenario,"terminalDraft",0).aboveKeyboardBottom;
                    ImeState baseline=probeIme(scenario,"terminalDraft",beforeIme);
                    assertTrue("Expected no keyboard before tapping editor: "+baseline,baseline.hidden());
                    tapEditor(scenario,"terminalDraft");
                    awaitIme(scenario,"terminalDraft",beforeIme,true);
                    scenario.onActivity(a->{
                        assertFullyVisibleAboveIme(a,"terminalDraft",0);
                        assertFullyVisibleAboveIme(a,"sendText",44);
                        assertFullyVisibleAboveIme(a,"sendEnter",44);
                        assertIconAction(a,"sendText","发送文字（不回车）");
                        assertIconAction(a,"sendEnter","发送 Enter（执行当前行）");
                        assertEquals("Standard IME composer must be inline",LinearLayout.HORIZONTAL,((LinearLayout)get(a,"composerControls")).getOrientation());
                        assertTerminalSpaceAboveIme(a);
                    });
                    capture(scenario,"05-controller-ime-visible");
                    ImeState capturedIme=probeIme(scenario,"terminalDraft",beforeIme);
                    assertTrue("IME disappeared during screenshot: "+capturedIme,capturedIme.visible());
                    systemBack();
                    awaitIme(scenario,"terminalDraft",beforeIme,false);
                    scenario.onActivity(a->assertEquals("DETAIL",get(a,"currentScreen")));
                    systemBack();
                    scenario.onActivity(a->{
                        assertEquals("HOME",get(a,"currentScreen"));
                        assertTrue(((View)get(a,"sessions")).isShown());
                        assertFalse(((View)get(a,"terminal")).isShown());
                        assertEquals("",((EditText)get(a,"terminalDraft")).getText().toString());
                    });
                    scenario.onActivity(a->{
                        inventory(fixture.get(),LONG_TITLE);
                        assertTrue(hasRecent(a));
                        assertTrue(recent(a).performClick());
                        assertEquals(TARGET,get(a,"selected"));snapshot(fixture.get());
                    });
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                    scenario.onActivity(SyntheticUiScreenshotTest::assertLongHeading);
                    capture(scenario,"07-long-cjk-title");
                }finally{
                    original.flags=originalFlags;
                    automation.setServiceInfo(original);
                }
            }finally{scenario.onActivity(a->{a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);assertTrue((a.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0);});}
        }
    }
}
