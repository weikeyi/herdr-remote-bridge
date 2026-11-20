package dev.herdrbridge.nativeclient;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import java.util.*;

/** Native role-gated client. No WebView, script execution or automatic input replay. */
public final class MainActivity extends Activity {
    private EditText origin, invitation, terminalDraft;
    private Spinner pairingRole;
    private LinearLayout inputPanel,homeScreen,detailScreen;
    private TextView screenTitle,homeSummary,homeEmpty;
    private Button disconnectButton,homeReconnect;
    private String currentScreen="PAIR";
    private Runnable unregisterScreenBack;
    private SessionRepository.SessionTarget pendingMutationTarget;
    private TextView inputStatus, accessLabel;
    private Button sendText, sendEnter, acknowledgeInput;
    private String verifiedRole="reader", pendingMutationId;
    private boolean inputPending;
    private final Set<SessionRepository.SessionTarget> inputTargets=new HashSet<>();
    private Runnable inputExpiry;
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
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LinearLayout root=column();root.setFitsSystemWindows(true);root.setBackgroundColor(BG);root.setPadding(dp(16),dp(8),dp(16),dp(8));
        root.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        LinearLayout bar=new LinearLayout(this);bar.setGravity(android.view.Gravity.CENTER_VERTICAL);root.addView(bar,new LinearLayout.LayoutParams(-1,dp(52)));
        showSessions=button("‹ 会话",false);showSessions.setOnClickListener(v->showHome());bar.addView(showSessions,new LinearLayout.LayoutParams(dp(84),dp(44)));
        screenTitle=text("连接电脑",22,INK);screenTitle.setTypeface(null,Typeface.BOLD);bar.addView(screenTitle,new LinearLayout.LayoutParams(0,-2,1));
        Button settings=button("设置",false);settings.setOnClickListener(v->showPairing());bar.addView(settings,new LinearLayout.LayoutParams(dp(70),dp(44)));
        state=text("填写服务器地址，导入邀请后完成配对。",13,MUTED);state.setPadding(dp(12),dp(10),dp(12),dp(10));state.setBackground(shape(0xFFE9EFF8,12));state.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);state.setMaxLines(3);root.addView(state,spaced(-1,-2,8));
        accessLabel=text("端到端加密 · 默认只读",12,MUTED);root.addView(accessLabel,spaced(-1,-2,8));
        FrameLayout host=new FrameLayout(this);root.addView(host,new LinearLayout.LayoutParams(-1,0,1));

        setup=new ScrollView(this);setup.setFillViewport(true);setup.setClipToPadding(false);host.addView(setup,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout form=column();form.setPadding(dp(4),dp(12),dp(4),dp(16));setup.addView(form);
        ImageView mark=new ImageView(this);mark.setImageResource(dev.herdrbridge.nativeclient.R.drawable.ic_bridge_reader);form.addView(mark,new LinearLayout.LayoutParams(dp(48),dp(48)));
        TextView welcome=text("你的终端，随身可达",24,INK);welcome.setTypeface(null,Typeface.BOLD);form.addView(welcome,spaced(-1,-2,16));
        TextView intro=text("通过自己的服务器连接电脑。邀请仅在本次配对中使用，不会保存在输入框。",14,MUTED);form.addView(intro,spaced(-1,-2,8));
        form.addView(text("服务器地址",13,INK),spaced(-1,-2,24));
        origin=entry("https://bridge.example.com");origin.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);origin.setText(getPreferences(MODE_PRIVATE).getString("origin",""));form.addView(origin,spaced(-1,dp(52),8));
        form.addView(text("设备邀请",13,INK),spaced(-1,-2,20));
        invitation=entry("粘贴邀请链接（不保存）");invitation.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);invitation.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING|android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);invitation.setSaveEnabled(false);form.addView(invitation,spaced(-1,dp(52),8));
        Button qr=button("从二维码图片导入",false);qr.setOnClickListener(v->chooseQr());form.addView(qr,spaced(-1,dp(48),8));
        form.addView(text("设备权限",13,INK),spaced(-1,-2,20));
        pairingRole=new Spinner(this);pairingRole.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Reader · 只读（默认）","Controller · 终端输入"}));pairingRole.setBackground(shape(0xFFFFFFFF,12));pairingRole.setSaveEnabled(false);form.addView(pairingRole,spaced(-1,dp(48),8));
        TextView roleWarning=text("Controller 是设备级授权，不限于一个窗格。需另用电脑生成的 Controller 邀请；选择此项不会提升权限。",12,MUTED);form.addView(roleWarning,spaced(-1,-2,8));
        Button pair=button("使用 Reader 邀请配对",true);pair.setOnClickListener(v->connect(true));form.addView(pair,spaced(-1,dp(52),20));
        pairingRole.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int position,long id){pair.setText(position==1?"使用 Controller 邀请配对":"使用 Reader 邀请配对");}public void onNothingSelected(AdapterView<?> p){}});
        Button connect=button("连接已配对设备",false);connect.setOnClickListener(v->connect(false));form.addView(connect,spaced(-1,dp(48),8));
        disconnectButton=button("断开并停止自动恢复",false);disconnectButton.setTextColor(0xFF9D3443);disconnectButton.setOnClickListener(v->{cancelQr(true);clearRecovery();stopConnection();showPairing();state.setText("已断开；再次连接需明确操作。");});disconnectButton.setVisibility(new DeviceVault(this).hasRecord()?View.VISIBLE:View.GONE);form.addView(disconnectButton,spaced(-1,dp(48),8));
        form.addView(text("预览版本 · 原生文字快照，不是完整 VT 终端。",12,MUTED),spaced(-1,-2,20));

        homeScreen=column();host.addView(homeScreen,new FrameLayout.LayoutParams(-1,-1));
        homeSummary=text("电脑会话",18,INK);homeSummary.setTypeface(null,Typeface.BOLD);homeScreen.addView(homeSummary,spaced(-1,-2,12));
        homeEmpty=text("还没有会话\n连接成功后，电脑上的会话会显示在这里。",15,MUTED);homeEmpty.setGravity(android.view.Gravity.CENTER);homeEmpty.setPadding(dp(20),dp(40),dp(20),dp(40));homeScreen.addView(homeEmpty,spaced(-1,-2,16));
        sessionList=new ScrollView(this);sessionList.setClipToPadding(false);sessionList.setPadding(0,dp(8),0,dp(16));sessions=column();sessionList.addView(sessions);homeScreen.addView(sessionList,new LinearLayout.LayoutParams(-1,0,1));
        homeReconnect=button("重新连接",false);homeReconnect.setOnClickListener(v->connect(false));homeScreen.addView(homeReconnect,spaced(-1,dp(48),8));

        detailScreen=column();host.addView(detailScreen,new FrameLayout.LayoutParams(-1,-1));
        selectedLabel=text("未选中会话",12,MUTED);selectedLabel.setMaxLines(2);detailScreen.addView(selectedLabel,spaced(-1,-2,4));
        terminal=text("",13,0xFFE2E8F0);terminal.setTypeface(Typeface.MONOSPACE);terminal.setPadding(dp(14),dp(14),dp(14),dp(14));terminal.setTextIsSelectable(false);terminal.setSaveEnabled(false);
        HorizontalScrollView horizontal=new HorizontalScrollView(this);horizontal.addView(terminal,new android.view.ViewGroup.LayoutParams(-2,-2));
        output=new ScrollView(this);output.setFillViewport(true);output.setBackground(shape(0xFF101B2C,16));output.setClipToOutline(true);output.addView(horizontal);LinearLayout.LayoutParams terminalSpace=new LinearLayout.LayoutParams(-1,0,1);terminalSpace.topMargin=dp(12);detailScreen.addView(output,terminalSpace);
        LinearLayout actions=new LinearLayout(this);detailScreen.addView(actions,spaced(-1,dp(52),8));
        refresh=button("刷新选中会话快照",false);refresh.setEnabled(false);refresh.setOnClickListener(v->{if(connection!=null&&selected!=null&&ready){hideKeyboard();state.setText("正在读取选中会话的新快照…");connection.requestSnapshot(selected);updateInputControls();}});actions.addView(refresh,new LinearLayout.LayoutParams(0,-1,1));
        copy=button("复制当前快照",false);copy.setEnabled(false);copy.setOnClickListener(v->copySnapshot());LinearLayout.LayoutParams copySpace=new LinearLayout.LayoutParams(0,-1,1);copySpace.leftMargin=dp(8);actions.addView(copy,copySpace);
        inputPanel=column();inputPanel.setPadding(dp(12),dp(8),dp(12),dp(8));inputPanel.setBackground(shape(0xFFFFFFFF,14));inputPanel.setVisibility(View.GONE);detailScreen.addView(inputPanel,spaced(-1,-2,8));
        TextView inputWarning=text("输入可能触发菜单，不一定是 shell。先核对目标和新快照；文字不附带回车。",11,MUTED);inputPanel.addView(inputWarning);
        terminalDraft=entry("终端文字 · 默认隐藏");terminalDraft.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);terminalDraft.setSaveEnabled(false);terminalDraft.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING|android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI|android.view.inputmethod.EditorInfo.IME_ACTION_NONE);terminalDraft.setOnEditorActionListener((v,action,event)->true);terminalDraft.setOnKeyListener((v,key,event)->key==android.view.KeyEvent.KEYCODE_ENTER);inputPanel.addView(terminalDraft,spaced(-1,dp(46),6));
        terminalDraft.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){updateInputControls();}public void afterTextChanged(android.text.Editable e){}});
        LinearLayout inputActions=new LinearLayout(this);inputPanel.addView(inputActions,spaced(-1,dp(48),6));
        sendText=button("发送文字（不回车）",true);sendText.setTextSize(12);sendText.setEnabled(false);sendText.setOnClickListener(v->sendTerminalInput(false));inputActions.addView(sendText,new LinearLayout.LayoutParams(0,-1,1));
        sendEnter=button("发送 Enter（执行当前行）",false);sendEnter.setTextSize(12);sendEnter.setEnabled(false);sendEnter.setOnClickListener(v->sendTerminalInput(true));LinearLayout.LayoutParams enterSpace=new LinearLayout.LayoutParams(0,-1,1);enterSpace.leftMargin=dp(6);inputActions.addView(sendEnter,enterSpace);
        inputStatus=text("",11,MUTED);inputPanel.addView(inputStatus,spaced(-1,-2,4));
        acknowledgeInput=button("已核对新快照，恢复输入",false);acknowledgeInput.setEnabled(false);acknowledgeInput.setOnClickListener(v->{if(connection!=null&&selected!=null&&connection.canResetInput(selected))connection.resetInputUncertainty(selected);});inputPanel.addView(acknowledgeInput,spaced(-1,dp(48),4));
        setContentView(root);showPairing();
    }
    private static final int BG=0xFFF4F7FB,INK=0xFF16243A,MUTED=0xFF60718A,BLUE=0xFF245DCE;
    private LinearLayout column(){LinearLayout view=new LinearLayout(this);view.setOrientation(LinearLayout.VERTICAL);return view;}
    private TextView text(String value,int size,int color){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(color);view.setLineSpacing(dp(2),1);return view;}
    private android.graphics.drawable.GradientDrawable shape(int color,int radius){android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();bg.setColor(color);bg.setCornerRadius(dp(radius));return bg;}
    private Button button(String label,boolean primary){Button view=new Button(this);view.setText(label);view.setTextSize(14);view.setAllCaps(false);view.setMinHeight(dp(44));view.setMinimumHeight(dp(44));view.setPadding(dp(10),dp(4),dp(10),dp(4));view.setElevation(0);view.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x223B82F6),shape(primary?BLUE:0xFFFFFFFF,12),null));view.setTextColor(new android.content.res.ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{0xFF94A3B8,primary?0xFFFFFFFF:BLUE}));return view;}
    private EditText entry(String hint){EditText view=new EditText(this);view.setHint(hint);view.setTextSize(15);view.setTextColor(INK);view.setHintTextColor(0xFF8391A7);view.setSingleLine(true);view.setPadding(dp(12),dp(8),dp(12),dp(8));view.setBackground(shape(0xFFFFFFFF,12));return view;}
    private LinearLayout.LayoutParams spaced(int width,int height,int top){LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(width,height);params.topMargin=dp(top);return params;}
    private void hideKeyboard(){android.view.inputmethod.InputMethodManager ime=(android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);if(ime!=null)ime.hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(),0);}
    private void showScreen(String screen){
        currentScreen=screen;setup.setVisibility("PAIR".equals(screen)?View.VISIBLE:View.GONE);homeScreen.setVisibility("HOME".equals(screen)?View.VISIBLE:View.GONE);detailScreen.setVisibility("DETAIL".equals(screen)?View.VISIBLE:View.GONE);
        screenTitle.setText("PAIR".equals(screen)?"连接电脑":"HOME".equals(screen)?"会话":"终端");showSessions.setVisibility("DETAIL".equals(screen)?View.VISIBLE:View.GONE);showSessions.setEnabled(!inputPending);accessLabel.setVisibility("PAIR".equals(screen)?View.GONE:View.VISIBLE);
        updateScreenBack();
    }
    private void showPairing(){hideKeyboard();clearTerminalDraft();if(connection!=null)connection.selectTarget(null);selected=null;terminal.setText("");copy.setEnabled(false);refresh.setEnabled(false);showScreen("PAIR");}
    private void showHome(){hideKeyboard();clearTerminalDraft();if(connection!=null)connection.selectTarget(null);selected=null;terminal.setText("");copy.setEnabled(false);refresh.setEnabled(false);sessionList.setVisibility(View.VISIBLE);showScreen("HOME");updateInputControls();}
    private void showDetail(){showScreen("DETAIL");}
    private boolean handlesScreenBack(){
        return "DETAIL".equals(currentScreen)||("PAIR".equals(currentScreen)&&ready);
    }

    private void updateScreenBack(){
        if(android.os.Build.VERSION.SDK_INT<33)return;
        boolean enabled=foreground&&handlesScreenBack();
        if(enabled&&unregisterScreenBack==null){
            unregisterScreenBack=Api33Back.register(this,()->{
                if(foreground&&handlesScreenBack())showHome();
            });
        }else if(!enabled&&unregisterScreenBack!=null){
            unregisterScreenBack.run();
            unregisterScreenBack=null;
        }
    }

    @android.annotation.TargetApi(33)
    private static final class Api33Back {
        static Runnable register(Activity activity,Runnable action){
            android.window.OnBackInvokedDispatcher dispatcher=activity.getOnBackInvokedDispatcher();
            android.window.OnBackInvokedCallback callback=action::run;
            // Leave the IME's own back handling ahead of our screen navigation.
            dispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,callback);
            return ()->dispatcher.unregisterOnBackInvokedCallback(callback);
        }
    }

    // API 33+ gestures use the scoped platform dispatcher above; only API 28–32 need this fallback.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override @SuppressWarnings("deprecation")
    public void onBackPressed(){
        if(android.os.Build.VERSION.SDK_INT<33&&handlesScreenBack()){showHome();return;}
        super.onBackPressed();
    }
    private static final String UNKNOWN_INPUT="可能已发送，未确认，请刷新终端核对后再决定是否重试。";
    private void displayMutationResult(String id,SessionRepository.MutationOutcome outcome){
                    if(!id.equals(pendingMutationId))return;
                    inputPending=false;pendingMutationId=null;
                    SessionRepository.SessionTarget acknowledgedTarget=pendingMutationTarget;pendingMutationTarget=null;
                    if(outcome!=SessionRepository.MutationOutcome.UNKNOWN)getPreferences(MODE_PRIVATE).edit().remove("input_uncertain").commit();
                    inputStatus.setText(outcome==SessionRepository.MutationOutcome.COMPLETED?"服务端已确认输入。请刷新快照核对结果。":outcome==SessionRepository.MutationOutcome.NOT_STARTED?"本次输入未开始。请刷新并检查目标或权限后再决定。":UNKNOWN_INPUT);
                    updateInputControls();
                    if(outcome==SessionRepository.MutationOutcome.COMPLETED&&foreground&&ready&&connection!=null&&acknowledgedTarget!=null&&acknowledgedTarget.equals(selected)&&"DETAIL".equals(currentScreen)){state.setText("输入已确认，正在更新当前窗格…");connection.requestSnapshot(acknowledgedTarget);updateInputControls();}
    }
    private void displayInputUncertaintyCleared(SessionRepository.SessionTarget target){
        if(!target.equals(selected))return;
        if(getPreferences(MODE_PRIVATE).edit().remove("input_uncertain").commit()){inputStatus.setText("已核对快照；仅在快照仍新鲜时允许输入。");updateInputControls();}
    }
    private void clearTerminalDraft(){
        if(inputExpiry!=null){handler.removeCallbacks(inputExpiry);inputExpiry=null;}
        if(terminalDraft!=null)terminalDraft.setText("");
    }
    private void updateInputControls(){
        if(sendText==null||sendEnter==null||acknowledgeInput==null)return;
        boolean controller="controller".equals(verifiedRole)&&ready&&selected!=null&&inputTargets.contains(selected);
        inputPanel.setVisibility(controller?View.VISIBLE:View.GONE);
        boolean uncertain=getPreferences(MODE_PRIVATE).getBoolean("input_uncertain",false);
        boolean allowed=controller&&foreground&&!inputPending&&!uncertain&&connection!=null&&connection.canSend(selected);
        boolean valid=false;try{TerminalInputGate.validateText(terminalDraft.getText().toString());valid=true;}catch(IllegalArgumentException ignored){}
        sendText.setEnabled(allowed&&valid);sendEnter.setEnabled(allowed);
        acknowledgeInput.setVisibility(uncertain&&!inputPending?View.VISIBLE:View.GONE);
        acknowledgeInput.setEnabled(controller&&foreground&&!inputPending&&uncertain&&connection!=null&&connection.canResetInput(selected));
        if(inputPending)inputStatus.setText("正在等待服务端确认；不会自动重发。");
        else if(uncertain)inputStatus.setText(UNKNOWN_INPUT);
        else if(inputStatus.length()==0)inputStatus.setText("每次输入前先刷新并核对快照；有效期 3 秒。");
        if(homeReconnect!=null)homeReconnect.setVisibility(ready?View.GONE:View.VISIBLE);
        if(showSessions!=null)showSessions.setEnabled(ready&&!inputPending);
        for(int i=0;i<sessions.getChildCount();i++)sessions.getChildAt(i).setEnabled(ready&&!inputPending);
    }
    private void sendTerminalInput(boolean enter){
        if(!foreground||!ready||selected==null||connection==null||inputPending||getPreferences(MODE_PRIVATE).getBoolean("input_uncertain",false)||!connection.canSend(selected))return;
        String text=terminalDraft.getText().toString();
        if(!enter)try{TerminalInputGate.validateText(text);}catch(IllegalArgumentException ignored){inputStatus.setText("仅允许不含换行或控制字符的单行文字，最多 4096 UTF-8 字节。");return;}
        // Durable non-secret uncertainty marker precedes dispatch; never store the draft or target.
        if(!getPreferences(MODE_PRIVATE).edit().putBoolean("input_uncertain",true).commit()){inputStatus.setText("无法保存安全状态，未发送输入。");return;}
        inputPending=true;pendingMutationTarget=selected;
        try{pendingMutationId=enter?connection.sendEnter(selected):connection.sendTerminalText(selected,text);}
        catch(RuntimeException ignored){inputPending=false;pendingMutationId=null;connection.restoreInputUncertainty(true);inputStatus.setText(UNKNOWN_INPUT);}
        clearTerminalDraft();updateInputControls();
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
        invitation.setText(decoded);showScreen("PAIR");state.setText("已识别并验证邀请。请核对所选权限，再点击配对按钮；尚未提交此邀请。");
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
                invitationClient=NativeRelayClient.forInvitation(configured,transientInvite,vault,pairingRole.getSelectedItemPosition()==1?"controller":"reader");
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
                    ready=status==SessionRepository.State.READY;updateScreenBack();showSessions.setEnabled(ready&&!inputPending);
                    if(!ready){homeEmpty.setText("连接正在恢复\n后台不会保持网络连接。");homeEmpty.setVisibility(View.VISIBLE);refresh.setEnabled(false);copy.setEnabled(false);terminal.setText("");sessions.removeAllViews();selected=null;selectedLabel.setText("未选中会话");clearTerminalDraft();inputTargets.clear();}
                    else {refresh.setEnabled(selected!=null);disconnectButton.setVisibility(View.VISIBLE);if(selected==null&&!"HOME".equals(currentScreen))showHome();}
                    updateInputControls();
                }); }
                public void onSessions(List<SessionRepository.Session> rows) { runOnUiThread(()->{
                    if(generation!=epoch||!ready)return;
                    displaySessions(rows);
                }); }
                public void onAccess(String role){runOnUiThread(()->{if(generation!=epoch)return;verifiedRole="controller".equals(role)?"controller":"reader";accessLabel.setText("权限："+("controller".equals(verifiedRole)?"Controller（设备级，服务端已验证）":"Reader（只读）"));updateInputControls();});}
                public void onMutationResult(String id,SessionRepository.SessionTarget target,SessionRepository.MutationOutcome outcome){runOnUiThread(()->{
                    if(generation==epoch)displayMutationResult(id,outcome);
                });}
                public void onInputUncertaintyCleared(SessionRepository.SessionTarget target){runOnUiThread(()->{if(generation==epoch)displayInputUncertaintyCleared(target);});}
                public void onSnapshot(SessionRepository.SessionTarget target,String content) { runOnUiThread(()->{
                    if(generation==epoch)displaySnapshot(target,content);
                }); }
            },Math::random,count->getPreferences(MODE_PRIVATE).edit().putInt("auto_retries",count).apply());
            connection.restoreInputUncertainty(getPreferences(MODE_PRIVATE).getBoolean("input_uncertain",false));
            if(foreground)connection.foreground();
            if(pair)connection.explicitInvitation(invitationClient);else if(restoring)connection.restoreSaved(priorRetries);else connection.explicitSaved();
        } catch(Exception ignored) {clearRecovery();stopConnection();invitation.setText("");state.setText("无法连接或凭证校验失败。检查地址、邀请权限或电脑端撤销状态；不会跳过安全检查。");}
    }
    private void displaySessions(List<SessionRepository.Session> rows){
                    sessions.removeAllViews();inputTargets.clear();boolean retained=false;
                    int count=0;
                    for(SessionRepository.Session row:rows) {
                        if(++count>256)break;
                        if(row.target.equals(selected))retained=true;if(row.manualTerminalInput)inputTargets.add(row.target);
                        Button item=button("",false);item.setGravity(android.view.Gravity.START|android.view.Gravity.CENTER_VERTICAL);item.setTextColor(INK);item.setPadding(dp(16),dp(16),dp(16),dp(16));item.setMinHeight(dp(96));
                        String label=shortText(row.title.isEmpty()?"未命名会话":row.title,80)+" · "+shortText(row.status,30)+"   ›\n窗格 "+shortText(row.target.paneId,50)+" · 终端 "+shortText(row.target.terminalId,50);
                        item.setText(label);item.setAllCaps(false);item.setEnabled(!inputPending);
                        item.setOnClickListener(v->{if(!ready||connection==null||inputPending)return;clearTerminalDraft();selected=row.target;connection.selectTarget(selected);hideKeyboard();showDetail();screenTitle.setText(shortText(row.title.isEmpty()?"终端":row.title,24));sessionList.setVisibility(View.GONE);terminal.setText("");copy.setEnabled(false);selectedLabel.setText("服务器 "+shortText(selected.serverSessionId,40)+" / 窗格 "+shortText(selected.paneId,50)+" / 代次 "+selected.generation);state.setText("正在读取选中会话的新快照…");refresh.setEnabled(true);updateInputControls();connection.requestSnapshot(selected);});sessions.addView(item,spaced(-1,-2,10));
                    }
                    homeSummary.setText("电脑会话 · "+Math.min(rows.size(),256));homeEmpty.setVisibility(rows.isEmpty()?View.VISIBLE:View.GONE);homeEmpty.setText("暂无会话\n在电脑上打开终端或启动会话后，这里会自动更新。");
                    if(!retained){clearTerminalDraft();if(connection!=null)connection.selectTarget(null);sessionList.setVisibility(View.VISIBLE);selected=null;terminal.setText("");copy.setEnabled(false);refresh.setEnabled(false);selectedLabel.setText("未选中会话");showHome();}
                    if(rows.isEmpty())state.setText("已连接，服务器没有可显示的会话。");else if(selected==null)state.setText("已连接："+Math.min(rows.size(),256)+" 个会话。请选择一个读取快照。");
        updateInputControls();
    }
    private void displaySnapshot(SessionRepository.SessionTarget target,String content){
        if(ready&&target.equals(selected)){terminal.setText(TerminalSnapshot.render(content));copy.setEnabled(terminal.length()>0);state.setText("快照已更新。内容不会自动发送到剪贴板。");updateInputControls();if(inputExpiry!=null)handler.removeCallbacks(inputExpiry);inputExpiry=this::updateInputControls;handler.postDelayed(inputExpiry,connection==null?1:Math.max(1,connection.inputFreshMillis(target)));}
    }
    private void stopConnection() {epoch++;ready=false;updateScreenBack();clearTerminalDraft();inputPending=false;pendingMutationId=null;pendingMutationTarget=null;inputTargets.clear();verifiedRole="reader";if(connection!=null){connection.stop();connection=null;}if(repository!=null){repository.close();repository=null;}selected=null;if(terminal!=null)terminal.setText("");if(sessions!=null)sessions.removeAllViews();if(refresh!=null)refresh.setEnabled(false);if(copy!=null)copy.setEnabled(false);if(showSessions!=null)showSessions.setEnabled(false);if(selectedLabel!=null)selectedLabel.setText("未选中会话");updateInputControls();}
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
        super.onStart();foreground=true;updateScreenBack();
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
    @Override public void onStop(){foreground=false;updateScreenBack();clearTerminalDraft();inputPending=false;pendingMutationId=null;pendingMutationTarget=null;cancelQr(false);copy.setEnabled(false);showSessions.setEnabled(false);selectedLabel.setText("未选中会话");if(connection!=null)connection.background();ready=false;selected=null;terminal.setText("");sessions.removeAllViews();refresh.setEnabled(false);invitation.setText("");state.setText("后台已断开；返回后仅用已保存凭证恢复，不重试邀请。");updateInputControls();super.onStop();}
    @Override public void onDestroy(){foreground=false;updateScreenBack();cancelQr(true);qrWorker.shutdownNow();stopConnection();super.onDestroy();}
}
