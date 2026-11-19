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
    private TextView state, terminal, selectedLabel;
    private ScrollView setup, sessionList, output;
    private Button showSessions;
    private Button copy;
    private final java.util.concurrent.ThreadPoolExecutor qrWorker=new java.util.concurrent.ThreadPoolExecutor(1,1,0L,java.util.concurrent.TimeUnit.MILLISECONDS,new java.util.concurrent.ArrayBlockingQueue<>(1));
    private java.util.concurrent.Future<?> qrJob;
    private Runnable qrTimeout;
    private android.os.CancellationSignal qrCancellation;
    private long qrEpoch;
    private boolean pickerAwaiting;
    private String qrOrigin;
    private android.net.Uri pendingQrUri;
    private int nextQrRequest=901, activeQrRequest;
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
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(12),dp(8),dp(12),dp(8));
        root.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        TextView title=new TextView(this);title.setText("Bridge Reader · 只读预览");title.setTextSize(21);root.addView(title);
        TextView notice=new TextView(this);notice.setText("内部测试版，未完成真实手机验收。仅显示文字快照，不是完整终端；不能输入命令。");notice.setTextSize(12);notice.setMaxLines(2);root.addView(notice);
        Button settings=new Button(this);settings.setText("展开 / 收起连接与配对设置");settings.setOnClickListener(v->setup.setVisibility(setup.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE));root.addView(settings);
        setup=new ScrollView(this);LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);setup.addView(form);root.addView(setup,new LinearLayout.LayoutParams(-1,dp(220)));
        origin=new EditText(this);origin.setHint("自有 HTTPS 根地址");origin.setSingleLine(true);origin.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        origin.setText(getPreferences(MODE_PRIVATE).getString("origin",""));form.addView(origin);
        invitation=new EditText(this);invitation.setHint("粘贴一次性 Reader 邀请（不保存）");invitation.setSingleLine(true);
        invitation.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        invitation.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING|android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        invitation.setSaveEnabled(false);form.addView(invitation);
        Button qr=new Button(this);qr.setText("从二维码图片导入");qr.setOnClickListener(v->chooseQr());form.addView(qr);
        Button connect=new Button(this);connect.setText("连接已配对设备");connect.setOnClickListener(v->connect(false));form.addView(connect);
        Button pair=new Button(this);pair.setText("使用 Reader 邀请配对");pair.setOnClickListener(v->connect(true));form.addView(pair);
        Button disconnect=new Button(this);disconnect.setText("断开并停止自动恢复");disconnect.setOnClickListener(v->{cancelQr(true);clearRecovery();stopConnection();state.setText("已断开；再次连接需明确操作。");});form.addView(disconnect);
        state=new TextView(this);state.setText("尚未连接。请先填写服务器地址，再连接或配对。");state.setTextSize(14);state.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);root.addView(state);
        sessions=new LinearLayout(this);sessions.setOrientation(LinearLayout.VERTICAL);
        showSessions=new Button(this);showSessions.setText("选择会话 / 返回会话列表");showSessions.setEnabled(false);showSessions.setOnClickListener(v->sessionList.setVisibility(View.VISIBLE));root.addView(showSessions);
        sessionList=new ScrollView(this);sessionList.addView(sessions);root.addView(sessionList,new LinearLayout.LayoutParams(-1,dp(110)));
        selectedLabel=new TextView(this);selectedLabel.setText("未选中会话");selectedLabel.setTextSize(12);root.addView(selectedLabel);
        LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);root.addView(actions);
        refresh=new Button(this);refresh.setText("刷新选中会话快照");refresh.setEnabled(false);refresh.setOnClickListener(v->{if(connection!=null&&selected!=null&&ready){state.setText("正在读取选中会话的新快照…");connection.requestSnapshot(selected);}});actions.addView(refresh,new LinearLayout.LayoutParams(0,-2,1));
        copy=new Button(this);copy.setText("复制当前快照");copy.setEnabled(false);copy.setOnClickListener(v->copySnapshot());actions.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
        terminal=new TextView(this);terminal.setTypeface(Typeface.MONOSPACE);terminal.setTextSize(13);terminal.setPadding(dp(8),dp(8),dp(8),dp(8));terminal.setTextIsSelectable(false);terminal.setSaveEnabled(false);
        HorizontalScrollView horizontal=new HorizontalScrollView(this);horizontal.addView(terminal,new android.view.ViewGroup.LayoutParams(-2,-2));
        output=new ScrollView(this);output.setFillViewport(true);output.addView(horizontal);root.addView(output,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private static String shortText(String value,int max){String text=TerminalSnapshot.render(value);return text.length()<=max?text:text.substring(0,max)+"…";}
    private static String stateText(SessionRepository.State status){
        switch(status){
            case READY:return "安全连接已建立，正在加载会话…";
            case CONNECTING:return "正在连接或恢复，请稍候…";
            case ENROLLED:return "凭证已安全保存，正在确认配对…";
            case DISCONNECTED:return "连接中断；前台将按有限次数恢复，后台不会联网。";
            case REVOKED:return "设备授权已撤销，自动恢复已停止。请在电脑重新配对。";
            default:return "连接或安全校验失败，自动恢复已停止。检查服务器、网络或电脑授权后再连接。";
        }
    }
    private void copySnapshot(){
        if(!foreground||!ready||selected==null||terminal.length()==0)return;
        android.content.ClipData clip=android.content.ClipData.newPlainText("Bridge 只读快照",terminal.getText().toString());
        if(android.os.Build.VERSION.SDK_INT>=33){android.os.PersistableBundle extras=new android.os.PersistableBundle();extras.putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE,true);clip.getDescription().setExtras(extras);}
        android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        if(clipboard!=null){clipboard.setPrimaryClip(clip);state.setText("已复制当前文字快照；请只粘贴到你信任的位置。");}
    }
    private void cancelQr(boolean clearPicker){
        qrEpoch++;
        if(qrTimeout!=null){handler.removeCallbacks(qrTimeout);qrTimeout=null;}
        if(qrCancellation!=null){qrCancellation.cancel();qrCancellation=null;}
        if(qrJob!=null){qrJob.cancel(true);qrJob=null;qrWorker.purge();}
        pendingQrUri=null;
        if(clearPicker){pickerAwaiting=false;activeQrRequest=0;}
        if(!pickerAwaiting)qrOrigin=null;
    }
    static android.content.Intent imagePickerIntent(int api){
        android.content.Intent intent=api>=33?new android.content.Intent(android.provider.MediaStore.ACTION_PICK_IMAGES):new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).addCategory(android.content.Intent.CATEGORY_OPENABLE);
        return intent.setType("image/*").putExtra(android.content.Intent.EXTRA_LOCAL_ONLY,true).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }
    private void chooseQr(){
        cancelQr(true);invitation.setText("");
        try{
            qrOrigin=InvitationValidator.origin(origin.getText().toString().trim());
            android.content.Intent intent=imagePickerIntent(android.os.Build.VERSION.SDK_INT);
            pickerAwaiting=true;activeQrRequest=nextQrRequest++;if(nextQrRequest>65000)nextQrRequest=901;
            try{startActivityForResult(intent,activeQrRequest);}
            catch(android.content.ActivityNotFoundException unavailable){startActivityForResult(imagePickerIntent(28),activeQrRequest);}
        }catch(Exception ignored){cancelQr(true);state.setText("无法打开本地图片选择器。请先填写有效 HTTPS 地址，也可以直接粘贴邀请。");}
    }
    @Override protected void onActivityResult(int request,int result,android.content.Intent data){
        super.onActivityResult(request,result,data);
        if(!pickerAwaiting||request!=activeQrRequest)return;
        pickerAwaiting=false;activeQrRequest=0;
        android.net.Uri uri=data==null?null:data.getData();
        if(result!=RESULT_OK||uri==null||!"content".equals(uri.getScheme())){cancelQr(true);state.setText("未导入二维码。可以重新选择本地图片或粘贴邀请。");return;}
        if(foreground)decodeQr(uri);else pendingQrUri=uri;
    }
    private void decodeQr(android.net.Uri uri){
        final long generation=qrEpoch;
        final String configured=qrOrigin;
        if(configured==null||!foreground)return;
        final android.os.CancellationSignal cancellation=new android.os.CancellationSignal();qrCancellation=cancellation;
        state.setText("正在本机识别二维码图片…");
        scheduleQrTimeout(generation);
        try{qrJob=qrWorker.submit(()->{
            try{
                String decoded=QrImageDecoder.decode(getApplicationContext(),uri,cancellation);
                InvitationValidator.invitation(decoded,configured,System.currentTimeMillis());
                handler.post(()->acceptQrResult(generation,configured,cancellation,decoded));
            }catch(Exception ignored){handler.post(()->{if(generation==qrEpoch&&foreground&&!cancellation.isCanceled()){finishQr();state.setText("无法读取有效邀请二维码。请选清晰的本地图片，或直接粘贴邀请。");}});}
        });}catch(java.util.concurrent.RejectedExecutionException ignored){cancelQr(true);state.setText("图片识别仍在结束，请稍后重试或粘贴邀请。");}
    }
    private void scheduleQrTimeout(long generation){
        qrTimeout=()->{if(generation==qrEpoch&&foreground){cancelQr(true);state.setText("图片识别超时，请重选清晰的本地图片或直接粘贴邀请。");}};
        handler.postDelayed(qrTimeout,15000);
    }
    private void finishQr(){if(qrTimeout!=null){handler.removeCallbacks(qrTimeout);qrTimeout=null;}qrJob=null;qrCancellation=null;qrOrigin=null;}
    private void acceptQrResult(long generation,String configured,android.os.CancellationSignal cancellation,String decoded){
        if(generation!=qrEpoch||!foreground||cancellation.isCanceled())return;
        finishQr();
        try{
            if(!configured.equals(InvitationValidator.origin(origin.getText().toString().trim())))throw new IllegalArgumentException();
            InvitationValidator.invitation(decoded,configured,System.currentTimeMillis());
        }catch(Exception ignored){state.setText("服务器地址已变化或邀请已失效，请重新选择邀请图片。");return;}
        invitation.setText(decoded);setup.setVisibility(View.VISIBLE);state.setText("已识别并验证邀请。请点击“使用 Reader 邀请配对”；尚未提交此邀请。");
    }
    private void connect(boolean pair) {connect(pair,false);}
    private void connect(boolean pair,boolean restoring) {
        cancelQr(true);
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
                    state.setText(stateText(status));
                    if(status==SessionRepository.State.READY||status==SessionRepository.State.ENROLLED){
                        if(!armRecovery(configured,status==SessionRepository.State.ENROLLED)){stopConnection();state.setText("无法验证已保存凭证；自动恢复已停止。");return;}
                    }
                    if(status==SessionRepository.State.ERROR||status==SessionRepository.State.REVOKED)clearRecovery();
                    ready=status==SessionRepository.State.READY;showSessions.setEnabled(ready);
                    if(!ready){refresh.setEnabled(false);copy.setEnabled(false);terminal.setText("");sessions.removeAllViews();selected=null;selectedLabel.setText("未选中会话");}
                    else {refresh.setEnabled(selected!=null);setup.setVisibility(View.GONE);if(selected==null)sessionList.setVisibility(View.VISIBLE);}
                }); }
                public void onSessions(List<SessionRepository.Session> rows) { runOnUiThread(()->{
                    if(generation!=epoch||!ready)return;
                    displaySessions(rows);
                }); }
                public void onSnapshot(SessionRepository.SessionTarget target,String content) { runOnUiThread(()->{
                    if(generation==epoch)displaySnapshot(target,content);
                }); }
            },Math::random,count->getPreferences(MODE_PRIVATE).edit().putInt("auto_retries",count).apply());
            if(foreground)connection.foreground();
            if(pair)connection.explicitInvitation(invitationClient);else if(restoring)connection.restoreSaved(priorRetries);else connection.explicitSaved();
        } catch(Exception ignored) {clearRecovery();stopConnection();invitation.setText("");state.setText("无法连接或凭证校验失败。检查地址、Reader 邀请或电脑端撤销状态；不会跳过安全检查。");}
    }
    private void displaySessions(List<SessionRepository.Session> rows){
                    sessions.removeAllViews();boolean retained=false;
                    int count=0;
                    for(SessionRepository.Session row:rows) {
                        if(++count>256)break;
                        if(row.target.equals(selected))retained=true;
                        Button item=new Button(MainActivity.this);
                        String label=shortText(row.title.isEmpty()?"未命名会话":row.title,80)+" · "+shortText(row.status,30)+"\n窗格 "+shortText(row.target.paneId,50)+" · 终端 "+shortText(row.target.terminalId,50);
                        item.setText(label);item.setAllCaps(false);
                        item.setOnClickListener(v->{if(!ready||connection==null)return;selected=row.target;sessionList.setVisibility(View.GONE);terminal.setText("");copy.setEnabled(false);selectedLabel.setText("服务器 "+shortText(selected.serverSessionId,40)+" / 窗格 "+shortText(selected.paneId,50)+" / 代次 "+selected.generation);state.setText("正在读取选中会话的新快照…");refresh.setEnabled(true);connection.requestSnapshot(selected);});sessions.addView(item);
                    }
                    if(!retained){sessionList.setVisibility(View.VISIBLE);selected=null;terminal.setText("");copy.setEnabled(false);refresh.setEnabled(false);selectedLabel.setText("未选中会话");}
                    if(rows.isEmpty())state.setText("已连接，服务器没有可显示的会话。");else if(selected==null)state.setText("已连接："+Math.min(rows.size(),256)+" 个会话。请选择一个读取快照。");
    }
    private void displaySnapshot(SessionRepository.SessionTarget target,String content){
        if(ready&&target.equals(selected)){terminal.setText(TerminalSnapshot.render(content));copy.setEnabled(terminal.length()>0);state.setText("快照已更新。内容不会自动发送到剪贴板。");}
    }
    private void stopConnection() {epoch++;ready=false;if(connection!=null){connection.stop();connection=null;}if(repository!=null){repository.close();repository=null;}selected=null;if(terminal!=null)terminal.setText("");if(sessions!=null)sessions.removeAllViews();if(refresh!=null)refresh.setEnabled(false);if(copy!=null)copy.setEnabled(false);if(showSessions!=null)showSessions.setEnabled(false);if(selectedLabel!=null)selectedLabel.setText("未选中会话");}
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
        if(pendingQrUri!=null){android.net.Uri uri=pendingQrUri;pendingQrUri=null;decodeQr(uri);}
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
    @Override public void onStop(){foreground=false;cancelQr(false);copy.setEnabled(false);showSessions.setEnabled(false);selectedLabel.setText("未选中会话");if(connection!=null)connection.background();ready=false;selected=null;terminal.setText("");sessions.removeAllViews();refresh.setEnabled(false);invitation.setText("");state.setText("后台已断开；返回后仅用已保存凭证恢复，不重试邀请。");super.onStop();}
    @Override public void onDestroy(){cancelQr(true);qrWorker.shutdownNow();stopConnection();super.onDestroy();}
}
