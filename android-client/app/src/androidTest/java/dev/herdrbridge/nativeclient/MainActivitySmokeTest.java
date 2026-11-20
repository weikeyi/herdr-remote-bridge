package dev.herdrbridge.nativeclient;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import org.junit.Test;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import static org.junit.Assert.*;

/** Actual native Activity lifecycle smoke tests, with invalid inputs only.
 * No endpoint, socket, real invitation, credential deletion, or production data is used.
 */
public class MainActivitySmokeTest {
    @org.junit.Before public void requireIsolatedUnenrolledTestInstall() {
        Context context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getTargetContext();
        org.junit.Assume.assumeFalse("Never launch over a real enrolled installation",new DeviceVault(context).hasRecord());
        for(String name:new String[]{"MainActivity",MainActivity.class.getName()}) {
            org.junit.Assume.assumeFalse("Never auto-connect preexisting user state",context.getSharedPreferences(name,Context.MODE_PRIVATE).getBoolean("auto_connect",false));
        }
    }
    private static <T> T field(Activity activity, String name, Class<T> type) {
        try {
            Field field = MainActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(activity));
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static Button button(View view, String text) {
        if (view instanceof Button && text.contentEquals(((Button) view).getText())) return (Button) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button found = button(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void assertNativeTree(View view) {
        // Check superclasses too, so a WebView subclass cannot evade this assertion.
        for (Class<?> type = view.getClass(); type != null; type = type.getSuperclass()) {
            assertNotEquals("android.webkit.WebView", type.getName());
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) assertNativeTree(group.getChildAt(i));
        }
    }

    private static void assertDisconnected(MainActivity activity) {
        // Invalid inputs must fail before constructing a transport, not merely before READY.
        assertNull(field(activity, "repository", SessionRepository.class));
        assertEquals(0, field(activity, "sessions", LinearLayout.class).getChildCount());
        assertEquals("", field(activity, "terminal", TextView.class).getText().toString());
        assertFalse(field(activity, "refresh", Button.class).isEnabled());
    }

    @Test public void nativeInitialUiAndInvalidActionsNeverCreateConnection() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                Map<String, ?> preferences = new HashMap<>(activity.getPreferences(Context.MODE_PRIVATE).getAll());
                boolean hadRecord = new DeviceVault(activity).hasRecord();
                View root = activity.getWindow().getDecorView();
                assertNativeTree(root);
                assertTrue((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
                assertDisconnected(activity);
                EditText origin = field(activity, "origin", EditText.class);
                EditText invitation = field(activity, "invitation", EditText.class);
                assertFalse(invitation.isSaveEnabled());
                assertFalse(field(activity, "terminal", TextView.class).isSaveEnabled());
                Button pair = button(root, "使用 Reader 邀请配对");
                Button connect = button(root, "连接已配对设备");
                assertNotNull(pair);
                assertNotNull(connect);
                for (String invalid : new String[]{"", "not-an-origin", "http://invalid.invalid"}) {
                    origin.setText(invalid);
                    for (int repeat = 0; repeat < 3; repeat++) {
                        invitation.setText("synthetic-invalid-not-a-credential");
                        assertTrue(pair.performClick());
                        assertDisconnected(activity);
                        assertEquals("", invitation.getText().toString());
                        assertTrue(field(activity, "state", TextView.class).getText().toString().startsWith("无法连接"));
                        invitation.setText("synthetic-invalid-not-a-credential");
                        assertTrue(connect.performClick());
                        assertDisconnected(activity);
                        assertEquals("", invitation.getText().toString());
                    }
                }
                assertEquals(preferences, activity.getPreferences(Context.MODE_PRIVATE).getAll());
                assertEquals(hadRecord, new DeviceVault(activity).hasRecord());
            });
        }
    }

    @Test public void backgroundAndRecreationClearTransientInvitationWithoutAutoConnect() {
        String transientText = "synthetic-ui-only-" + UUID.randomUUID();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                field(activity, "origin", EditText.class).setText("");
                field(activity, "invitation", EditText.class).setText(transientText);
                assertDisconnected(activity);
            });
            // CREATED sends the real Activity through onPause/onStop; RESUMED returns it.
            scenario.moveToState(Lifecycle.State.CREATED);
            scenario.moveToState(Lifecycle.State.RESUMED);
            scenario.onActivity(activity -> {
                assertDisconnected(activity);
                assertEquals("", field(activity, "invitation", EditText.class).getText().toString());
                assertTrue(field(activity, "state", TextView.class).getText().toString().contains("后台已断开"));
                assertFalse(activity.getPreferences(Context.MODE_PRIVATE).getAll().containsValue(transientText));
                field(activity, "invitation", EditText.class).setText(transientText);
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertDisconnected(activity);
                assertEquals("", field(activity, "invitation", EditText.class).getText().toString());
                assertFalse(activity.getPreferences(Context.MODE_PRIVATE).getAll().containsValue(transientText));
                assertNativeTree(activity.getWindow().getDecorView());
            });
        }
    }
    private static final class FakeRepository implements SessionRepository {
        boolean closed;
        public void start(Listener listener) { listener.onState(State.READY, ""); }
        public void requestSnapshot(SessionTarget target) { throw new AssertionError("No automatic snapshot replay"); }
        public void close() { closed=true; }
    }

    @Test public void foregroundRecoveryClosesOldClientAndDisconnectStopsRecovery() {
        java.util.List<FakeRepository> opened=new java.util.ArrayList<>();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
                ForegroundConnection controller=new ForegroundConnection(new ForegroundConnection.Factory(){
                    public boolean hasSaved(){return true;}
                    public SessionRepository saved(){FakeRepository r=new FakeRepository();opened.add(r);return r;}
                },new ForegroundConnection.Scheduler(){
                    public void execute(Runnable work){handler.post(work);}
                    public void after(long delay,Runnable work){handler.postDelayed(work,delay);}
                    public void cancel(Runnable work){handler.removeCallbacks(work);}
                },new SessionRepository.Listener(){
                    public void onState(SessionRepository.State state,String message){}
                    public void onSessions(java.util.List<SessionRepository.Session> sessions){}
                    public void onSnapshot(SessionRepository.SessionTarget target,String text){throw new AssertionError("Unexpected snapshot");}
                },()->0.5);
                try {Field f=MainActivity.class.getDeclaredField("connection");f.setAccessible(true);f.set(activity,controller);}
                catch(ReflectiveOperationException error){throw new AssertionError(error);}
                controller.foreground();controller.explicitSaved();
                activity.getPreferences(Context.MODE_PRIVATE).edit().putBoolean("auto_connect",true).putString("auto_origin","https://example.invalid").commit();
                assertEquals(1,opened.size());
                field(activity,"invitation",EditText.class).setText("synthetic-transient-not-retried");
                field(activity,"terminal",TextView.class).setText("synthetic old output");
                field(activity,"sessions",LinearLayout.class).addView(new TextView(activity));
            });
            scenario.moveToState(Lifecycle.State.CREATED);
            scenario.moveToState(Lifecycle.State.RESUMED);
            scenario.onActivity(activity -> {
                assertTrue(opened.get(0).closed);
                assertEquals(2,opened.size());
                assertNotSame(opened.get(0),opened.get(1));
                assertTrue(activity.getPreferences(Context.MODE_PRIVATE).getBoolean("auto_connect",false));
                assertEquals("",field(activity,"invitation",EditText.class).getText().toString());
                assertEquals("",field(activity,"terminal",TextView.class).getText().toString());
                assertEquals(0,field(activity,"sessions",LinearLayout.class).getChildCount());
                Button stop=button(activity.getWindow().getDecorView(),"断开并停止自动恢复");
                assertNotNull(stop);stop.performClick();assertTrue(opened.get(1).closed);
                assertFalse(activity.getPreferences(Context.MODE_PRIVATE).getBoolean("auto_connect",false));
            });
            scenario.moveToState(Lifecycle.State.CREATED);
            scenario.moveToState(Lifecycle.State.RESUMED);
            scenario.onActivity(activity -> assertEquals(2,opened.size()));
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertEquals(2,opened.size());assertDisconnected(activity);
                assertFalse(activity.getPreferences(Context.MODE_PRIVATE).getBoolean("auto_connect",false));
            });
        }
    }

    @Test public void restoredAuthorizationRequiresExactOriginAndValidSavedVault() {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> activity.getPreferences(Context.MODE_PRIVATE).edit()
                .putString("origin","https://example.invalid").putString("auto_origin","https://other.invalid")
                .putBoolean("auto_connect",true).commit());
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertDisconnected(activity);
                assertFalse(activity.getPreferences(Context.MODE_PRIVATE).getBoolean("auto_connect",false));
                activity.getPreferences(Context.MODE_PRIVATE).edit().putString("auto_origin","https://example.invalid")
                    .putBoolean("auto_connect",true).commit();
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                // Matching origin alone cannot open a network: missing vault fails before client factory.
                assertDisconnected(activity);
                assertFalse(activity.getPreferences(Context.MODE_PRIVATE).getBoolean("auto_connect",false));
                activity.getPreferences(Context.MODE_PRIVATE).edit().remove("origin").commit();
            });
        }
    }


    @Test public void connectedSettingsStaysOpenOnReadyAndInventoryUpdates() {
        try (ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity->{
                try {
                    Field ready=MainActivity.class.getDeclaredField("ready");
                    ready.setAccessible(true);
                    ready.setBoolean(activity,true);
                    Method land=MainActivity.class.getDeclaredMethod("showHomeOnReadyIfAppropriate");
                    land.setAccessible(true);
                    Method refresh=MainActivity.class.getDeclaredMethod("displaySessions",java.util.List.class);
                    refresh.setAccessible(true);
                    // READY on initial pairing still goes to the home page.
                    land.invoke(activity);
                    assertEquals("HOME",field(activity,"currentScreen",String.class));
                    Button settings=button(activity.getWindow().getDecorView(),"设置");
                    assertNotNull(settings);
                    assertTrue(settings.performClick());
                    EditText origin=field(activity,"origin",EditText.class);
                    origin.setText("https://example.invalid");
                    TextView state=field(activity,"state",TextView.class);
                    state.setText("synthetic settings feedback");
                    land.invoke(activity); // repeated READY
                    SessionRepository.SessionTarget target=new SessionRepository.SessionTarget("primary","pane-settings","terminal-settings",1,"");
                    refresh.invoke(activity,java.util.Collections.singletonList(new SessionRepository.Session(target,"synthetic","idle",false)));
                    refresh.invoke(activity,java.util.Collections.emptyList());
                    refresh.invoke(activity,java.util.Collections.singletonList(new SessionRepository.Session(target,"synthetic","idle",false)));
                    assertEquals("PAIR",field(activity,"currentScreen",String.class));
                    assertTrue(origin.isShown());
                    assertEquals("https://example.invalid",origin.getText().toString());
                    assertEquals("synthetic settings feedback",state.getText().toString());
                    Button back=field(activity,"showSessions",Button.class);
                    assertTrue(back.isShown());
                    assertTrue(back.performClick());
                    assertEquals("HOME",field(activity,"currentScreen",String.class));
                    assertEquals(1,field(activity,"sessions",ViewGroup.class).getChildCount());
                } catch (ReflectiveOperationException error) {
                    throw new AssertionError(error);
                }
            });
        }
    }
}
