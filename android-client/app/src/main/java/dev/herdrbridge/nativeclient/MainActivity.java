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
        state=new TextView(this);state.setText("尚未连接；不会显示示例会话。");root.addView(state);
        refresh=new Button(this);refresh.setText("刷新选中会话快照");refresh.setEnabled(false);refresh.setOnClickListener(v->{if(repository!=null&&selected!=null&&ready)repository.requestSnapshot(selected);});root.addView(refresh);
        sessions=new LinearLayout(this);sessions.setOrientation(LinearLayout.VERTICAL);
        ScrollView list=new ScrollView(this);list.addView(sessions);root.addView(list,new LinearLayout.LayoutParams(-1,180));
        terminal=new TextView(this);terminal.setTypeface(Typeface.MONOSPACE);terminal.setTextIsSelectable(false);terminal.setSaveEnabled(false);
        ScrollView output=new ScrollView(this);output.addView(terminal);root.addView(output,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
    }
    private void connect(boolean pair) {
        stopConnection();
        try {
            String configured=InvitationValidator.origin(origin.getText().toString().trim());
            DeviceVault vault=new DeviceVault(this);
            if(pair) {
                if(vault.hasRecord()) { state.setText("已有凭证。先连接；更换设备或服务器请先在电脑撤销，再通过系统清除本应用数据。");invitation.setText("");return; }
                String transientInvite=invitation.getText().toString();invitation.setText("");
                InvitationValidator.invitation(transientInvite,configured,System.currentTimeMillis());
                repository=NativeRelayClient.forInvitation(configured,transientInvite,vault);
            } else {
                try(CredentialRecord record=vault.load(configured)) { repository=NativeRelayClient.forSaved(record,vault); }
            }
            getPreferences(MODE_PRIVATE).edit().putString("origin",configured).apply(); // Non-secret origin only.
            final long generation=++epoch;
            repository.start(new SessionRepository.Listener() {
                public void onState(SessionRepository.State status,String safeMessage) { runOnUiThread(()->{
                    if(generation!=epoch)return;
                    // Deliberately do not render exception/network strings that may contain secrets.
                    state.setText("连接状态："+status.name());
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
                        item.setOnClickListener(v->{if(!ready||repository==null)return;selected=row.target;terminal.setText("");refresh.setEnabled(true);repository.requestSnapshot(selected);});sessions.addView(item);
                    }
                    if(!retained){selected=null;terminal.setText("");refresh.setEnabled(false);}
                    if(rows.isEmpty())state.setText("已连接，服务器没有可显示的会话。");
                }); }
                public void onSnapshot(SessionRepository.SessionTarget target,String content) { runOnUiThread(()->{
                    if(generation==epoch&&ready&&target.equals(selected))terminal.setText(TerminalSnapshot.render(content));
                }); }
            });
        } catch(Exception ignored) {stopConnection();invitation.setText("");state.setText("无法连接或凭证校验失败。检查地址、Reader 邀请或电脑端撤销状态；不会跳过安全检查。");}
    }
    private void stopConnection() {epoch++;ready=false;if(repository!=null){repository.close();repository=null;}selected=null;if(terminal!=null)terminal.setText("");if(sessions!=null)sessions.removeAllViews();if(refresh!=null)refresh.setEnabled(false);}
    @Override public void onStop(){stopConnection();invitation.setText("");state.setText("已暂停连接；返回后请显式连接。");super.onStop();}
}
