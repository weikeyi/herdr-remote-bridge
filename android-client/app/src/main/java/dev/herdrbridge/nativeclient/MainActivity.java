package dev.herdrbridge.nativeclient;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import java.util.*;

/** Internal native Reader milestone. No WebView, web execution or command-entry controls. */
public final class MainActivity extends Activity {
    private EditText origin, invitation;
    private TextView state, terminal;
    private LinearLayout sessions;
    private Button refresh;
    private SessionRepository repository;
    private ForegroundConnection connection;
    private final android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean foreground, restoreChecked;
    private SessionRepository.SessionTarget selected;
    private long epoch;
    private boolean ready;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(20,20,20,20);
        root.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        TextView title=new TextView(this);title.setText("Bridge Native · 内部开发里程碑");title.setTextSize(21);root.addView(title);
        TextView notice=new TextView(this);notice.setText("尚未完成真实设备端到端验收，不是可交付发行版。原生只读界面；终端是去除 ANSI 的文字快照，不是完整 VT 屏幕。");root.addView(notice);
        origin=new EditText(this);origin.setHint("自有 HTTPS 根地址");origin.setSingleLine(true);origin.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        origin.setText(getPreferences(MODE_PRIVATE).getString("origin",""));root.addView(origin);
        invitation=new EditText(this);invitation.setHint("一次性 Reader 邀请（不保存）");invitation.setSingleLine(true);
        invitation.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        invitation.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING|android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        invitation.setSaveEnabled(false);root.addView(invitation);
        Button connect=new Button(this);connect.setText("连接已配对设备");connect.setOnClickListener(v->connect(false));root.addView(connect);
        Button pair=new Button(this);pair.setText("使用 Reader 邀请配对");pair.setOnClickListener(v->connect(true));root.addView(pair);
        Button disconnect=new Button(this);disconnect.setText("断开并停止自动恢复");disconnect.setOnClickListener(v->{clearRecovery();stopConnection();state.setText("已断开；再次连接需明确操作。");});root.addView(disconnect);
        state=new TextView(this);state.setText("尚未连接；不会显示示例会话。");root.addView(state);
        refresh=new Button(this);refresh.setText("刷新选中会话快照");refresh.setEnabled(false);refresh.setOnClickListener(v->{if(connection!=null&&selected!=null&&ready)connection.requestSnapshot(selected);});root.addView(refresh);
        sessions=new LinearLayout(this);sessions.setOrientation(LinearLayout.VERTICAL);
        ScrollView list=new ScrollView(this);list.addView(sessions);root.addView(list,new LinearLayout.LayoutParams(-1,180));
        terminal=new TextView(this);terminal.setTypeface(Typeface.MONOSPACE);terminal.setTextIsSelectable(false);terminal.setSaveEnabled(false);
        ScrollView output=new ScrollView(this);output.addView(terminal);root.addView(output,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
    }
    private void connect(boolean pair) {connect(pair,false);}
    private void connect(boolean pair,boolean restoring) {
        int priorRetries=restoring?getPreferences(MODE_PRIVATE).getInt("auto_retries",0):0;
        if(!restoring)clearRecovery();stopConnection();
        try {
            String configured=InvitationValidator.origin(origin.getText().toString().trim());
            DeviceVault vault=new DeviceVault(this);
            SessionRepository invitationClient=null;
            if(pair) {
                if(vault.hasRecord()) { state.setText("已有凭证。先连接；更换设备或服务器请先在电脑撤销，再通过系统清除本应用数据。");invitation.setText("");return; }
                String transientInvite=invitation.getText().toString();invitation.setText("");
                InvitationValidator.invitation(transientInvite,configured,System.currentTimeMillis());
                invitationClient=NativeRelayClient.forInvitation(configured,transientInvite,vault);
            } else {
                try(CredentialRecord record=vault.load(configured)) { if(restoring&&!matchesRecoveryIdentity(record))throw new IllegalStateException("Saved identity changed"); }
            }
            getPreferences(MODE_PRIVATE).edit().putString("origin",configured).apply();
            if(!pair&&!armRecovery(configured,false))throw new IllegalStateException("Cannot arm recovery"); // Non-secret origin only.
            final long generation=++epoch;
            connection=new ForegroundConnection(new ForegroundConnection.Factory(){
                public boolean hasSaved(){return vault.hasRecord();}
                public SessionRepository saved()throws Exception{
                    try(CredentialRecord record=vault.load(configured)){repository=NativeRelayClient.forSaved(record,vault);return repository;}
                }
            },new ForegroundConnection.Scheduler(){
                public void execute(Runnable work){handler.post(work);}
                public void after(long delay,Runnable work){handler.postDelayed(work,delay);}
                public void cancel(Runnable work){handler.removeCallbacks(work);}
            },new SessionRepository.Listener() {
                public void onState(SessionRepository.State status,String safeMessage) { runOnUiThread(()->{
                    if(generation!=epoch)return;
                    // Deliberately do not render exception/network strings that may contain secrets.
                    state.setText("连接状态："+status.name());
                    if(status==SessionRepository.State.READY||status==SessionRepository.State.ENROLLED){
                        if(!armRecovery(configured,status==SessionRepository.State.ENROLLED)){stopConnection();state.setText("无法验证已保存凭证；自动恢复已停止。");return;}
                    }
                    if(status==SessionRepository.State.ERROR||status==SessionRepository.State.REVOKED)clearRecovery();
                    ready=status==SessionRepository.State.READY;
                    if(!ready){refresh.setEnabled(false);terminal.setText("");sessions.removeAllViews();selected=null;}
                    else refresh.setEnabled(selected!=null);
                }); }
                public void onSessions(List<SessionRepository.Session> rows) { runOnUiThread(()->{
                    if(generation!=epoch||!ready)return;
                    sessions.removeAllViews();boolean retained=false;
                    int count=0;
                    for(SessionRepository.Session row:rows) {
                        if(++count>256)break;
                        if(row.target.equals(selected))retained=true;
                        Button item=new Button(MainActivity.this);
                        String label=TerminalSnapshot.render(row.title+" · "+row.status);
                        item.setText(label.substring(0,Math.min(160,label.length())));
                        item.setOnClickListener(v->{if(!ready||connection==null)return;selected=row.target;terminal.setText("");refresh.setEnabled(true);connection.requestSnapshot(selected);});sessions.addView(item);
                    }
                    if(!retained){selected=null;terminal.setText("");refresh.setEnabled(false);}
                    if(rows.isEmpty())state.setText("已连接，服务器没有可显示的会话。");
                }); }
                public void onSnapshot(SessionRepository.SessionTarget target,String content) { runOnUiThread(()->{
                    if(generation==epoch&&ready&&target.equals(selected))terminal.setText(TerminalSnapshot.render(content));
                }); }
            },Math::random,count->getPreferences(MODE_PRIVATE).edit().putInt("auto_retries",count).apply());
            if(foreground)connection.foreground();
            if(pair)connection.explicitInvitation(invitationClient);else if(restoring)connection.restoreSaved(priorRetries);else connection.explicitSaved();
        } catch(Exception ignored) {clearRecovery();stopConnection();invitation.setText("");state.setText("无法连接或凭证校验失败。检查地址、Reader 邀请或电脑端撤销状态；不会跳过安全检查。");}
    }
    private void stopConnection() {epoch++;ready=false;if(connection!=null){connection.stop();connection=null;}if(repository!=null){repository.close();repository=null;}selected=null;if(terminal!=null)terminal.setText("");if(sessions!=null)sessions.removeAllViews();if(refresh!=null)refresh.setEnabled(false);}
    private boolean armRecovery(String configured,boolean confirming){
        try(CredentialRecord record=new DeviceVault(this).load(configured)){
            getPreferences(MODE_PRIVATE).edit().putBoolean("auto_connect",true).putString("auto_origin",configured)
                .putString("auto_device",record.deviceId).putString("auto_credential",record.credentialId)
                .putBoolean("auto_confirming",confirming).apply();return true;
        }catch(Exception ignored){clearRecovery();return false;}
    }
    private boolean matchesRecoveryIdentity(CredentialRecord record){
        android.content.SharedPreferences prefs=getPreferences(MODE_PRIVATE);
        return record.deviceId.equals(prefs.getString("auto_device",null))&&record.credentialId.equals(prefs.getString("auto_credential",null));
    }
    private void clearRecovery(){getPreferences(MODE_PRIVATE).edit().remove("auto_connect").remove("auto_origin").remove("auto_retries").remove("auto_device").remove("auto_credential").remove("auto_confirming").apply();}
    @Override public void onStart(){
        super.onStart();foreground=true;
        if(connection!=null)connection.foreground();
        else if(!restoreChecked){
            restoreChecked=true;
            android.content.SharedPreferences prefs=getPreferences(MODE_PRIVATE);
            String configured=prefs.getString("origin","");
            if(prefs.getBoolean("auto_connect",false)){
                if(configured.equals(prefs.getString("auto_origin",null))){origin.setText(configured);connect(false,true);}
                else clearRecovery();
            }
        }
    }
    @Override public void onStop(){foreground=false;if(connection!=null)connection.background();ready=false;selected=null;terminal.setText("");sessions.removeAllViews();refresh.setEnabled(false);invitation.setText("");state.setText("后台已断开；返回后仅用已保存凭证恢复，不重试邀请。");super.onStop();}
    @Override public void onDestroy(){stopConnection();super.onDestroy();}
}
