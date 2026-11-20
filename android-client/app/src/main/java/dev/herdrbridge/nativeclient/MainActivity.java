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
    private TextView screenTitle,homeEmpty;
    private Button disconnectButton,homeReconnect, settingsButton;
    private String currentScreen="PAIR";
    private boolean settingsOpen, keyboardVisible, targetDetailsVisible;
    private TextView inputWarning, detailHeading, detailRole;
    private LinearLayout composerControls, inputActions;
    private ScrollView headingViewport;
    private boolean compactComposer, compactHeading;
    private LinearLayout chromeMeta,recentCard,sessionBody;
    private Button recentButton;
    private TextView homeCount,otherHeading,footRole,searchEmpty;
    private Button searchButton,searchClear;
    private EditText searchQuery;
    private LinearLayout searchBar;
    private boolean searchOpen;
    private int matchingCount;
    private ImageView brandMark;
    private SessionRepository.SessionTarget lastOpened;
    private List<SessionRepository.Session> liveRows=Collections.emptyList();
    private int sessionCount=-1;
    private Button targetDetails;
    private String selectedStatus="";
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
        getWindow().getDecorView().setSystemUiVisibility(0);
        LinearLayout root=column();root.setBackgroundColor(BG);
        root.setPadding(dp(16),dp(6),dp(16),dp(8));
        if(android.os.Build.VERSION.SDK_INT>=30)Api30Insets.apply(this,root,dp(16),dp(6),dp(8),this::setKeyboardVisible);
        else {
            root.setFitsSystemWindows(true);
            root.getViewTreeObserver().addOnGlobalLayoutListener(()->{
                android.graphics.Rect visible=new android.graphics.Rect();
                root.getWindowVisibleDisplayFrame(visible);
                setKeyboardVisible(getResources().getDisplayMetrics().heightPixels-visible.height()>dp(140));
            });
        }
        root.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        LinearLayout bar=new LinearLayout(this);bar.setGravity(android.view.Gravity.CENTER_VERTICAL);root.addView(bar,new LinearLayout.LayoutParams(-1,dp(52)));
        showSessions=iconButton("返回会话",R.drawable.ic_bridge_back);showSessions.setOnClickListener(v->showHome());bar.addView(showSessions,new LinearLayout.LayoutParams(dp(48),dp(48)));
        brandMark=new ImageView(this);brandMark.setImageResource(R.drawable.ic_terminal_mark);brandMark.setScaleType(ImageView.ScaleType.FIT_CENTER);brandMark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams markSpace=new LinearLayout.LayoutParams(dp(34),dp(34));markSpace.setMarginEnd(dp(10));bar.addView(brandMark,markSpace);
        screenTitle=text("连接电脑",19,INK);screenTitle.setTypeface(null,Typeface.BOLD);screenTitle.setSingleLine(true);screenTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);bar.addView(screenTitle,new LinearLayout.LayoutParams(0,-2,1));
        searchButton=iconButton("搜索会话",R.drawable.ic_bridge_search);searchButton.setOnClickListener(v->{if(searchOpen)closeSearch();else openSearch();});bar.addView(searchButton,new LinearLayout.LayoutParams(dp(48),dp(48)));
        settingsButton=iconButton("设置",R.drawable.ic_bridge_settings);settingsButton.setOnClickListener(v->openSettings());bar.addView(settingsButton,new LinearLayout.LayoutParams(dp(48),dp(48)));
        state=text("填写服务器地址，导入邀请后完成配对。",13,MUTED);state.setPadding(dp(12),dp(6),dp(12),dp(6));state.setBackground(surface(CARD,8));state.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);state.setMaxLines(3);root.addView(state,spaced(-1,-2,8));
        state.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence text,int start,int count,int after){}
            public void onTextChanged(CharSequence text,int start,int before,int count){updateCompactDetail();}
            public void afterTextChanged(android.text.Editable text){}
        });
        chromeMeta=new LinearLayout(this);chromeMeta.setGravity(android.view.Gravity.CENTER_VERTICAL);root.addView(chromeMeta,spaced(-1,-2,0));
        accessLabel=text("端到端加密",12,MUTED);chromeMeta.addView(accessLabel,new LinearLayout.LayoutParams(0,-2,1));
        targetDetails=button("ⓘ",false);targetDetails.setTextSize(21);targetDetails.setTextColor(MUTED);targetDetails.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33E0F786),null,shape(INK,24)));targetDetails.setContentDescription("终端详情");
        targetDetails.setOnClickListener(v->{targetDetailsVisible=!targetDetailsVisible;showTargetDetails();});chromeMeta.addView(targetDetails,new LinearLayout.LayoutParams(dp(48),dp(48)));
        FrameLayout host=new FrameLayout(this);root.addView(host,new LinearLayout.LayoutParams(-1,0,1));

        setup=new ScrollView(this);setup.setFillViewport(true);setup.setClipToPadding(false);host.addView(setup,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout form=column();form.setPadding(0,dp(6),0,dp(12));setup.addView(form);
        LinearLayout fields=column();fields.setPadding(dp(14),dp(10),dp(14),dp(10));fields.setBackground(surface(CARD,16));form.addView(fields);
        fields.addView(text("服务器地址",13,INK));
        origin=entry("https://bridge.example.com");origin.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);origin.setText(getPreferences(MODE_PRIVATE).getString("origin",""));fields.addView(origin,spaced(-1,dp(48),4));
        fields.addView(text("设备邀请",13,INK),spaced(-1,-2,10));
        invitation=entry("粘贴邀请链接（不保存）");invitation.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);invitation.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING|android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);invitation.setSaveEnabled(false);fields.addView(invitation,spaced(-1,dp(48),4));
        Button qr=button("从二维码图片导入",false);decorate(qr,R.drawable.ic_bridge_qr,0);qr.setOnClickListener(v->chooseQr());fields.addView(qr,spaced(-1,dp(48),8));
        fields.addView(text("设备权限",13,INK),spaced(-1,-2,10));
        pairingRole=new Spinner(this);pairingRole.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Reader · 只读（默认）","Controller · 终端输入"}));pairingRole.setBackgroundTintList(android.content.res.ColorStateList.valueOf(TEAL));pairingRole.setSaveEnabled(false);fields.addView(pairingRole,spaced(-1,dp(48),4));
        TextView roleWarning=text("Controller 可向设备的受控窗格输入。请使用电脑生成的 Controller 邀请；只改选项不会授权。",12,MUTED);fields.addView(roleWarning,spaced(-1,-2,8));
        Button pair=button("使用 Reader 邀请配对",true);pair.setOnClickListener(v->connect(true));form.addView(pair,spaced(-1,dp(48),10));
        pairingRole.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int position,long id){pair.setText(position==1?"使用 Controller 邀请配对":"使用 Reader 邀请配对");}public void onNothingSelected(AdapterView<?> p){}});
        Button connect=button("连接已配对设备",false);connect.setOnClickListener(v->connect(false));form.addView(connect,spaced(-1,dp(48),8));
        disconnectButton=button("断开并停止自动恢复",false);disconnectButton.setTextColor(0xFFFFA9A9);disconnectButton.setOnClickListener(v->{cancelQr(true);clearRecovery();stopConnection();showPairing();state.setText("已断开；再次连接需明确操作。");});disconnectButton.setVisibility(new DeviceVault(this).hasRecord()?View.VISIBLE:View.GONE);form.addView(disconnectButton,spaced(-1,dp(48),8));
        form.addView(text("预览版 · 终端文字视图",12,MUTED),spaced(-1,-2,20));

        homeScreen=column();host.addView(homeScreen,new FrameLayout.LayoutParams(-1,-1));
        homeEmpty=text("还没有会话\n连接成功后，电脑上的会话会显示在这里。",15,MUTED);homeEmpty.setGravity(android.view.Gravity.START);homeEmpty.setPadding(0,dp(20),0,dp(24));
        searchBar=new LinearLayout(this);searchBar.setGravity(android.view.Gravity.CENTER_VERTICAL);homeScreen.addView(searchBar,spaced(-1,dp(48),6));
        searchQuery=entry("搜索会话");searchQuery.setSaveEnabled(false);
        searchQuery.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);searchQuery.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH|android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        searchQuery.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(128)});
        searchQuery.setOnEditorActionListener((v,action,event)->{if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH){hideKeyboard();return true;}return false;});
        searchBar.addView(searchQuery,new LinearLayout.LayoutParams(0,-1,1));
        searchClear=iconButton("清除搜索",R.drawable.ic_bridge_close);searchClear.setOnClickListener(v->{if(searchQuery.length()==0)closeSearch();else searchQuery.setText("");});searchBar.addView(searchClear,new LinearLayout.LayoutParams(dp(48),dp(48)));
        searchQuery.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence t,int start,int count,int after){}
            public void onTextChanged(CharSequence t,int start,int before,int count){if(searchOpen&&"HOME".equals(currentScreen))renderSessionRows();}
            public void afterTextChanged(android.text.Editable text){}
        });
        sessionList=new ScrollView(this);sessionList.setClipToPadding(false);sessionList.setPadding(0,0,0,dp(12));sessionBody=column();sessionList.addView(sessionBody);homeScreen.addView(sessionList,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout hero=new LinearLayout(this);hero.setGravity(android.view.Gravity.BOTTOM);sessionBody.addView(hero,spaced(-1,-2,6));
        TextView homeHero=text("会话",49,INK);homeHero.setTypeface(null,Typeface.BOLD);hero.addView(homeHero,new LinearLayout.LayoutParams(0,-2,1));
        homeCount=text("",15,TEAL);homeCount.setTypeface(Typeface.MONOSPACE);homeCount.setPadding(0,0,dp(2),dp(7));hero.addView(homeCount);
        sessionBody.addView(rule(),spaced(-1,dp(1),9));
        searchEmpty=text("没有匹配的会话。试试其他名称，或清除搜索。",14,MUTED);searchEmpty.setPadding(0,dp(24),0,dp(24));sessionBody.addView(searchEmpty);
        sessionBody.addView(homeEmpty);
        recentCard=column();recentCard.setVisibility(View.GONE);sessionBody.addView(recentCard,spaced(-1,-2,22));
        otherHeading=text("其他会话",12,MUTED);sessionBody.addView(otherHeading,spaced(-1,-2,24));
        sessions=column();sessionBody.addView(sessions,spaced(-1,-2,12));
        homeReconnect=button("重新连接",false);homeReconnect.setOnClickListener(v->connect(false));homeScreen.addView(homeReconnect,spaced(-1,dp(48),8));
        LinearLayout footer=new LinearLayout(this);footer.setGravity(android.view.Gravity.CENTER_VERTICAL);homeScreen.addView(footer,spaced(-1,dp(36),6));
        TextView encryption=text("加密连接",11,MUTED);footer.addView(encryption,new LinearLayout.LayoutParams(0,-2,1));footRole=text("",11,MUTED);footer.addView(footRole);

        detailScreen=column();host.addView(detailScreen,new FrameLayout.LayoutParams(-1,-1));
        // Keep the complete title outside the action toolbar. Exceptionally long
        // names can scroll without pushing the terminal and composer off screen.
        headingViewport=new ScrollView(this){
            @Override protected void onMeasure(int widthSpec,int heightSpec){
                int max=dp(keyboardVisible?74:112);
                int mode=View.MeasureSpec.getMode(heightSpec);
                if(mode!=View.MeasureSpec.UNSPECIFIED)max=Math.min(max,View.MeasureSpec.getSize(heightSpec));
                super.onMeasure(widthSpec,View.MeasureSpec.makeMeasureSpec(max,View.MeasureSpec.AT_MOST));
            }
        };
        detailHeading=text("终端",22,INK);detailHeading.setTypeface(null,Typeface.BOLD);detailHeading.setIncludeFontPadding(false);
        if(android.os.Build.VERSION.SDK_INT>=29)detailHeading.setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE);detailHeading.setAccessibilityHeading(true);detailHeading.setSaveEnabled(false);
        headingViewport.addView(detailHeading,new FrameLayout.LayoutParams(-1,-2));detailScreen.addView(headingViewport,new LinearLayout.LayoutParams(-1,-2));
        detailRole=text("Reader · 只读",12,TEAL);detailRole.setIncludeFontPadding(false);detailScreen.addView(detailRole,spaced(-1,-2,4));
        selectedLabel=text("",12,MUTED);selectedLabel.setMaxLines(2);selectedLabel.setVisibility(View.GONE);detailScreen.addView(selectedLabel,spaced(-1,-2,0));
        terminal=text("",13,INK);terminal.setTypeface(Typeface.MONOSPACE);terminal.setPadding(0,dp(16),dp(4),dp(12));terminal.setTextIsSelectable(false);terminal.setSaveEnabled(false);
        HorizontalScrollView horizontal=new HorizontalScrollView(this);horizontal.addView(terminal,new android.view.ViewGroup.LayoutParams(-2,-2));
        output=new ScrollView(this);output.setFillViewport(true);output.setBackgroundColor(TERMINAL);output.addView(horizontal);LinearLayout.LayoutParams terminalSpace=new LinearLayout.LayoutParams(-1,0,1);terminalSpace.topMargin=dp(6);detailScreen.addView(rule(),spaced(-1,dp(1),0));detailScreen.addView(output,terminalSpace);
        refresh=iconButton("刷新终端",R.drawable.ic_bridge_refresh);refresh.setEnabled(false);refresh.setOnClickListener(v->{if(connection!=null&&selected!=null&&ready){hideKeyboard();state.setText("正在读取终端…");connection.requestSnapshot(selected);updateInputControls();}});bar.addView(refresh,bar.indexOfChild(settingsButton),new LinearLayout.LayoutParams(dp(48),dp(48)));
        copy=iconButton("复制终端内容",R.drawable.ic_bridge_copy);copy.setEnabled(false);copy.setOnClickListener(v->copySnapshot());bar.addView(copy,bar.indexOfChild(settingsButton),new LinearLayout.LayoutParams(dp(48),dp(48)));
        inputPanel=column();inputPanel.setPadding(0,dp(6),0,dp(6));inputPanel.addView(rule(),new LinearLayout.LayoutParams(-1,dp(1)));inputPanel.setVisibility(View.GONE);detailScreen.addView(inputPanel,spaced(-1,-2,8));
        inputWarning=text("输入也可能触发菜单。先核对当前终端。",11,MUTED);inputPanel.addView(inputWarning);
        composerControls=column();inputPanel.addView(composerControls,new LinearLayout.LayoutParams(-1,-2));
        terminalDraft=entry("终端文字 · 默认隐藏");terminalDraft.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);terminalDraft.setSaveEnabled(false);terminalDraft.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING|android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI|android.view.inputmethod.EditorInfo.IME_ACTION_NONE);terminalDraft.setOnEditorActionListener((v,action,event)->true);terminalDraft.setOnKeyListener((v,key,event)->key==android.view.KeyEvent.KEYCODE_ENTER);terminalDraft.setMinHeight(dp(48));composerControls.addView(terminalDraft,spaced(-1,-2,6));
        terminalDraft.setTooltipText("输入可能触发菜单；先核对当前终端。");
        terminalDraft.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){updateInputControls();}public void afterTextChanged(android.text.Editable e){}});
        inputActions=new LinearLayout(this);inputActions.setBaselineAligned(false);composerControls.addView(inputActions,spaced(-1,-2,6));
        sendText=button("发送文字（不回车）",false);sendText.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x44E0F786),shape(TEAL_DARK,8),null));sendText.setTextColor(new android.content.res.ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{MUTED,TEAL}));sendText.setTextSize(12);decorate(sendText,0,R.drawable.ic_bridge_send);sendText.setEnabled(false);sendText.setOnClickListener(v->sendTerminalInput(false));inputActions.addView(sendText,new LinearLayout.LayoutParams(0,-2,1));
        sendEnter=button("发送 Enter（执行当前行）",false);sendEnter.setTextSize(12);decorate(sendEnter,R.drawable.ic_bridge_enter,0);sendEnter.setEnabled(false);sendEnter.setOnClickListener(v->sendTerminalInput(true));LinearLayout.LayoutParams enterSpace=new LinearLayout.LayoutParams(0,-2,1);enterSpace.leftMargin=dp(6);inputActions.addView(sendEnter,enterSpace);
        sendText.setSingleLine(false);sendEnter.setSingleLine(false);
        sendText.setContentDescription("发送文字（不回车）");sendEnter.setContentDescription("发送 Enter（执行当前行）");
        inputStatus=text("",11,MUTED);inputStatus.setIncludeFontPadding(false);inputStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);inputPanel.addView(inputStatus,spaced(-1,-2,4));
        acknowledgeInput=button("已核对终端，恢复输入",false);acknowledgeInput.setEnabled(false);acknowledgeInput.setOnClickListener(v->{if(connection!=null&&selected!=null&&connection.canResetInput(selected))connection.resetInputUncertainty(selected);});inputPanel.addView(acknowledgeInput,spaced(-1,dp(48),4));
        setContentView(root);showPairing();
    }
    private static final int BG=0xFF101113,CARD=0xFF171918,STROKE=0xFF333637,TERMINAL=BG,
            INK=0xFFF1F0E8,MUTED=0xFFA6A8A0,TEAL=0xFFE0F786,TEAL_DARK=0xFF252923;
    private View rule(){View view=new View(this);view.setBackgroundColor(STROKE);return view;}
    private LinearLayout column(){LinearLayout view=new LinearLayout(this);view.setOrientation(LinearLayout.VERTICAL);return view;}
    private TextView text(String value,int size,int color){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(color);view.setLineSpacing(dp(2),1);return view;}
    private android.graphics.drawable.GradientDrawable shape(int color,int radius){android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();bg.setColor(color);bg.setCornerRadius(dp(radius));return bg;}
    private android.graphics.drawable.GradientDrawable surface(int color,int radius){android.graphics.drawable.GradientDrawable bg=shape(color,radius);bg.setStroke(dp(1),STROKE);return bg;}
    private void styleButton(Button view,String label,boolean primary){
        view.setText(label);view.setTextSize(14);view.setAllCaps(false);view.setMinHeight(dp(48));view.setMinimumHeight(dp(48));
        view.setPadding(dp(8),dp(4),dp(8),dp(4));view.setElevation(0);view.setStateListAnimator(null);
        android.graphics.drawable.StateListDrawable fill=new android.graphics.drawable.StateListDrawable();
        fill.addState(new int[]{-android.R.attr.state_enabled},surface(CARD,10));
        fill.addState(new int[]{},primary?shape(TEAL,10):surface(CARD,10));
        view.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33E0F786),fill,null));
        view.setTextColor(new android.content.res.ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{0xFF97998E,primary?0xFF161B0B:INK}));
    }
    private Button button(String label,boolean primary){Button view=new Button(this);styleButton(view,label,primary);return view;}
    private android.graphics.drawable.Drawable glyph(int id,int size){android.graphics.drawable.Drawable image=getDrawable(id).mutate();image.setBounds(0,0,dp(size),dp(size));return image;}
    private void decorate(Button button,int leading,int trailing){
        button.setCompoundDrawablesRelative(leading==0?null:glyph(leading,16),null,trailing==0?null:glyph(trailing,16),null);
        button.setCompoundDrawablePadding(dp(8));
    }
    private Button iconButton(String label,int id){
        Button view=new IconButton(id);styleButton(view,label,false);view.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33E0F786),null,shape(INK,24)));view.setContentDescription(label);return view;
    }
    private final class IconButton extends Button {
        private final android.graphics.drawable.Drawable icon;
        IconButton(int id){super(MainActivity.this);icon=glyph(id,24);}
        @Override protected void onDraw(android.graphics.Canvas canvas){
            int size=dp(24), left=(getWidth()-size)/2, top=(getHeight()-size)/2;
            icon.setBounds(left,top,left+size,top+size);icon.setAlpha(isEnabled()?255:100);icon.draw(canvas);
        }
    }
    private EditText entry(String hint){EditText view=new EditText(this);view.setHint(hint);view.setTextSize(15);view.setTextColor(INK);view.setHintTextColor(MUTED);view.setSingleLine(true);view.setPadding(dp(12),dp(8),dp(12),dp(8));view.setBackground(surface(BG,10));return view;}
    @android.annotation.TargetApi(30)
    private static final class Api30Insets {
        static void apply(Activity activity,View root,int horizontal,int top,int bottom,java.util.function.Consumer<Boolean> keyboard){
            WindowManager.LayoutParams attrs=activity.getWindow().getAttributes();
            attrs.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            activity.getWindow().setAttributes(attrs);
            activity.getWindow().setDecorFitsSystemWindows(false);
            activity.getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
            activity.getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
            activity.getWindow().setNavigationBarContrastEnforced(false);
            root.setOnApplyWindowInsetsListener((view,insets)->{
                android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout());
                android.graphics.Insets ime=insets.getInsets(android.view.WindowInsets.Type.ime());
                view.setPadding(horizontal+bars.left,top+bars.top,horizontal+bars.right,bottom+Math.max(bars.bottom,ime.bottom));
                keyboard.accept(insets.isVisible(android.view.WindowInsets.Type.ime()));
                return insets;
            });
        }
    }
    private LinearLayout.LayoutParams spaced(int width,int height,int top){LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(width,height);params.topMargin=dp(top);return params;}
    private void setKeyboardVisible(boolean visible){if(keyboardVisible!=visible){keyboardVisible=visible;updateCompactDetail();}}
    private void updateCompactDetail(){
        if(state==null||accessLabel==null||targetDetails==null||selectedLabel==null||inputWarning==null)return;
        boolean detail="DETAIL".equals(currentScreen), home="HOME".equals(currentScreen), compact=detail&&keyboardVisible;
        String status=state.getText().toString();
        boolean routine=status.startsWith("填写服务器地址")||status.startsWith("已连接")||status.startsWith("安全连接")||status.startsWith("正在读取终端")||status.startsWith("终端已更新")||status.startsWith("输入已确认");
        state.setVisibility(routine?View.GONE:View.VISIBLE);
        chromeMeta.setVisibility((home||detail)&&!compact?View.VISIBLE:View.GONE);
        targetDetails.setVisibility(detail&&selected!=null?View.VISIBLE:View.GONE);
        selectedLabel.setVisibility(detail&&!compact&&targetDetailsVisible&&selected!=null?View.VISIBLE:View.GONE);
        updateDetailLayout(detail,compact);
        String access="controller".equals(verifiedRole)?"Controller · 可输入":"Reader · 只读";
        String summary=ready?(detail&&status.startsWith("正在读取终端")?"读取中":detail&&!selectedStatus.isEmpty()?selectedStatus:"电脑已连接"):"未连接";
        String line=detail?summary:ready?summary+"  ·  "+access:summary;
        android.text.SpannableString meta=new android.text.SpannableString((ready?"•  ":"")+line);
        if(ready)meta.setSpan(new android.text.style.ForegroundColorSpan(TEAL),0,1,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        accessLabel.setText(meta);
        if(home){
            screenTitle.setText("Herdr Bridge");
            updateHomeCount();
            footRole.setText(ready?("controller".equals(verifiedRole)?"可控制":"只读"):"");
        }
    }
    private void updateDetailLayout(boolean detail,boolean compact){
        if(detailHeading==null||composerControls==null)return;
        boolean controller="controller".equals(verifiedRole);
        boolean inline=compact && getResources().getConfiguration().screenWidthDp>=360
            && getResources().getConfiguration().fontScale<=1.25f;
        if(detail){
            // Compact toolbar shows access while the full title keeps the width below.
            setTextIfChanged(screenTitle,inline?(controller?"终端 · 可输入":"终端 · 只读"):"终端");
            setTextIfChanged(detailRole,controller?"Controller · 可输入":"Reader · 只读");
        }
        detailRole.setVisibility(inline?View.GONE:View.VISIBLE);
        if(compactHeading!=compact){compactHeading=compact;detailHeading.setTextSize(compact?18:22);headingViewport.requestLayout();}
        inputWarning.setVisibility(inline?View.GONE:View.VISIBLE);
        if(inline!=compactComposer){
            compactComposer=inline;
            composerControls.setOrientation(inline?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);
            composerControls.setGravity(android.view.Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams draft=inline?new LinearLayout.LayoutParams(0,-2,1):spaced(-1,-2,6);
            LinearLayout.LayoutParams actions=inline?new LinearLayout.LayoutParams(0,-2,1.35f):spaced(-1,-2,6);
            if(inline)actions.setMarginStart(dp(6));
            terminalDraft.setLayoutParams(draft);inputActions.setLayoutParams(actions);
            terminalDraft.setHint(inline?"终端文字":"终端文字 · 默认隐藏");
            sendText.setText(inline?"只发文字":"发送文字（不回车）");
            sendEnter.setText(inline?"Enter 执行":"发送 Enter（执行当前行）");
            decorate(sendText,0,inline?0:R.drawable.ic_bridge_send);
            decorate(sendEnter,inline?0:R.drawable.ic_bridge_enter,0);
        }
        if(inputPanel.getPaddingTop()!=dp(inline?2:6))inputPanel.setPadding(0,dp(inline?2:6),0,dp(inline?2:6));
        LinearLayout.LayoutParams panel=(LinearLayout.LayoutParams)inputPanel.getLayoutParams();
        int margin=dp(inline?2:8);if(panel.topMargin!=margin){panel.topMargin=margin;inputPanel.setLayoutParams(panel);}
    }
    private static void setTextIfChanged(TextView view,String text){if(!text.contentEquals(view.getText()))view.setText(text);}
    private void showTargetDetails(){
        targetDetails.setContentDescription(targetDetailsVisible?"收起终端详情":"终端详情");
        if(selected!=null)selectedLabel.setText("窗格 "+shortText(selected.paneId,32)+" · "+shortText(selected.serverSessionId,24)+" / "+selected.generation);
        updateCompactDetail();
    }
    private void hideKeyboard(){android.view.inputmethod.InputMethodManager ime=(android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);if(ime!=null)ime.hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(),0);}
    private void showScreen(String screen){
        if(!"HOME".equals(screen)&&searchOpen)closeSearch();
        currentScreen=screen;setup.setVisibility("PAIR".equals(screen)?View.VISIBLE:View.GONE);homeScreen.setVisibility("HOME".equals(screen)?View.VISIBLE:View.GONE);detailScreen.setVisibility("DETAIL".equals(screen)?View.VISIBLE:View.GONE);
        screenTitle.setText("PAIR".equals(screen)?(settingsOpen?"连接设置":"连接电脑"):"HOME".equals(screen)?"Herdr Bridge":"终端");brandMark.setVisibility("HOME".equals(screen)?View.VISIBLE:View.GONE);showSessions.setVisibility("DETAIL".equals(screen)||settingsOpen?View.VISIBLE:View.GONE);showSessions.setEnabled(!inputPending);accessLabel.setVisibility("PAIR".equals(screen)?View.GONE:View.VISIBLE);settingsButton.setVisibility("PAIR".equals(screen)?View.GONE:View.VISIBLE);
        refresh.setVisibility("DETAIL".equals(screen)?View.VISIBLE:View.GONE);copy.setVisibility("DETAIL".equals(screen)?View.VISIBLE:View.GONE);
        updateSearchChrome();
        updateScreenBack();
        updateCompactDetail();
        if(connection!=null)connection.setPassiveTarget("DETAIL".equals(screen)?selected:null);
    }
    private void showPairing(){showSetup(false);}
    private void openSettings(){showSetup(true);}
    private void showSetup(boolean settings){settingsOpen=settings;hideKeyboard();clearTerminalDraft();if(connection!=null)connection.selectTarget(null);selected=null;terminal.setText("");copy.setEnabled(false);refresh.setEnabled(false);showScreen("PAIR");}
    private void showHome(){settingsOpen=false;hideKeyboard();clearTerminalDraft();if(connection!=null)connection.selectTarget(null);selected=null;terminal.setText("");copy.setEnabled(false);refresh.setEnabled(false);sessionList.setVisibility(View.VISIBLE);showScreen("HOME");renderSessionRows();updateInputControls();}
    private void showDetail(){showScreen("DETAIL");}
    private void openSearch(){
        if(!"HOME".equals(currentScreen))return;
        searchOpen=true;updateSearchChrome();updateScreenBack();renderSessionRows();
        searchQuery.requestFocus();searchQuery.post(()->{
            if(searchOpen&&"HOME".equals(currentScreen)&&foreground){
                android.view.inputmethod.InputMethodManager ime=(android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
                if(ime!=null)ime.showSoftInput(searchQuery,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }
    private void closeSearch(){
        if(!searchOpen)return;
        searchOpen=false;hideKeyboard();searchQuery.clearFocus();searchQuery.setText("");
        updateSearchChrome();updateScreenBack();renderSessionRows();
    }
    private void updateSearchChrome(){
        boolean home="HOME".equals(currentScreen);
        searchButton.setVisibility(home?View.VISIBLE:View.GONE);
        searchButton.setContentDescription(searchOpen?"关闭搜索":"搜索会话");
        searchBar.setVisibility(home&&searchOpen?View.VISIBLE:View.GONE);
    }
    private void updateHomeCount(){
        homeCount.setText(ready&&sessionCount>=0?String.format(java.util.Locale.ROOT,"%02d",searchOpen?matchingCount:sessionCount):"");
    }
    private void navigateScreenBack(){
        if("HOME".equals(currentScreen)&&searchOpen){closeSearch();return;}
        showHome();
    }
    private boolean handlesScreenBack(){
        return "DETAIL".equals(currentScreen)||("PAIR".equals(currentScreen)&&(ready||settingsOpen))||("HOME".equals(currentScreen)&&searchOpen);
    }

    private void updateScreenBack(){
        if(android.os.Build.VERSION.SDK_INT<33)return;
        boolean enabled=foreground&&handlesScreenBack();
        if(enabled&&unregisterScreenBack==null){
            unregisterScreenBack=Api33Back.register(this,()->{
                if(foreground&&handlesScreenBack())navigateScreenBack();
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
        if(android.os.Build.VERSION.SDK_INT<33&&handlesScreenBack()){navigateScreenBack();return;}
        super.onBackPressed();
    }
    private static final String UNKNOWN_INPUT="可能已发送，未确认，请刷新终端核对后再决定是否重试。";
    private void displayMutationResult(String id,SessionRepository.MutationOutcome outcome){
                    if(!id.equals(pendingMutationId))return;
                    inputPending=false;pendingMutationId=null;
                    SessionRepository.SessionTarget acknowledgedTarget=pendingMutationTarget;pendingMutationTarget=null;
                    if(outcome!=SessionRepository.MutationOutcome.UNKNOWN)getPreferences(MODE_PRIVATE).edit().remove("input_uncertain").commit();
                    inputStatus.setText(outcome==SessionRepository.MutationOutcome.COMPLETED?"输入已确认，正在更新终端。":outcome==SessionRepository.MutationOutcome.NOT_STARTED?"本次输入未开始。请刷新并检查目标或权限后再决定。":UNKNOWN_INPUT);
                    updateInputControls();
                    if(outcome==SessionRepository.MutationOutcome.COMPLETED&&foreground&&ready&&connection!=null&&acknowledgedTarget!=null&&acknowledgedTarget.equals(selected)&&"DETAIL".equals(currentScreen)){state.setText("输入已确认，正在更新当前窗格…");connection.requestSnapshot(acknowledgedTarget);updateInputControls();}
    }
    private void displayInputUncertaintyCleared(SessionRepository.SessionTarget target){
        if(!target.equals(selected))return;
        if(getPreferences(MODE_PRIVATE).edit().remove("input_uncertain").commit()){inputStatus.setText("已核对终端；请在有效期内操作。");updateInputControls();}
    }
    private void clearTerminalDraft(){
        if(inputExpiry!=null){handler.removeCallbacks(inputExpiry);inputExpiry=null;}
        if(terminalDraft!=null)terminalDraft.setText("");
    }
    private void updateInputControls(){
        if(sendText==null||sendEnter==null||acknowledgeInput==null)return;
        updateCompactDetail();
        boolean controller="controller".equals(verifiedRole)&&ready&&selected!=null&&inputTargets.contains(selected);
        inputPanel.setVisibility(controller?View.VISIBLE:View.GONE);
        boolean uncertain=getPreferences(MODE_PRIVATE).getBoolean("input_uncertain",false);
        boolean allowed=controller&&foreground&&!inputPending&&!uncertain&&connection!=null&&connection.canSend(selected);
        boolean valid=false;try{TerminalInputGate.validateText(terminalDraft.getText().toString());valid=true;}catch(IllegalArgumentException ignored){}
        sendText.setEnabled(allowed&&valid);sendEnter.setEnabled(allowed);
        acknowledgeInput.setVisibility(uncertain&&!inputPending?View.VISIBLE:View.GONE);
        acknowledgeInput.setEnabled(controller&&foreground&&!inputPending&&uncertain&&connection!=null&&connection.canResetInput(selected));
        if(inputPending)setTextIfChanged(inputStatus,"正在等待服务端确认；不会自动重发。");
        else if(uncertain)setTextIfChanged(inputStatus,UNKNOWN_INPUT);
        else if(inputStatus.length()==0)inputStatus.setText("输入前先刷新并核对终端，有效期 3 秒。");
        if(homeReconnect!=null)homeReconnect.setVisibility(ready?View.GONE:View.VISIBLE);
        if(showSessions!=null)showSessions.setEnabled(ready&&!inputPending);
        for(int i=0;i<sessions.getChildCount();i++)sessions.getChildAt(i).setEnabled(ready&&!inputPending);
        if(recentButton!=null)recentButton.setEnabled(ready&&!inputPending);
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
        android.content.ClipData clip=android.content.ClipData.newPlainText("Bridge 终端内容",terminal.getText().toString());
        if(android.os.Build.VERSION.SDK_INT>=33){android.os.PersistableBundle extras=new android.os.PersistableBundle();extras.putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE,true);clip.getDescription().setExtras(extras);}
        android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        if(clipboard!=null){clipboard.setPrimaryClip(clip);state.setText("已复制终端内容；请只粘贴到你信任的位置。");}
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
                    if(!settingsOpen||status!=SessionRepository.State.READY)state.setText(stateText(status));
                    if(status==SessionRepository.State.READY||status==SessionRepository.State.ENROLLED){
                        if(!armRecovery(configured,status==SessionRepository.State.ENROLLED)){stopConnection();state.setText("无法验证已保存凭证；自动恢复已停止。");return;}
                    }
                    if(status==SessionRepository.State.ERROR||status==SessionRepository.State.REVOKED)clearRecovery();
                    ready=status==SessionRepository.State.READY;if(!ready)sessionCount=-1;updateScreenBack();showSessions.setEnabled(ready&&!inputPending);
                    if(!ready){homeEmpty.setText("连接正在恢复\n后台不会保持网络连接。");homeEmpty.setVisibility(View.VISIBLE);refresh.setEnabled(false);copy.setEnabled(false);terminal.setText("");clearLiveSessions();selected=null;selectedLabel.setText("未选中会话");clearTerminalDraft();inputTargets.clear();}
                    else {refresh.setEnabled(selected!=null);disconnectButton.setVisibility(View.VISIBLE);showHomeOnReadyIfAppropriate();}
                    updateInputControls();
                }); }
                public void onSessions(List<SessionRepository.Session> rows) { runOnUiThread(()->{
                    if(generation!=epoch||!ready)return;
                    displaySessions(rows);
                }); }
                public void onAccess(String role){runOnUiThread(()->{if(generation!=epoch)return;verifiedRole="controller".equals(role)?"controller":"reader";updateInputControls();});}
                public void onMutationResult(String id,SessionRepository.SessionTarget target,SessionRepository.MutationOutcome outcome){runOnUiThread(()->{
                    if(generation==epoch)displayMutationResult(id,outcome);
                });}
                public void onInputUncertaintyCleared(SessionRepository.SessionTarget target){runOnUiThread(()->{if(generation==epoch)displayInputUncertaintyCleared(target);});}
                public void onPassiveSnapshotTimeout(SessionRepository.SessionTarget target,long token){
                    runOnUiThread(()->{if(generation==epoch&&foreground&&ready&&"DETAIL".equals(currentScreen)&&target.equals(selected))state.setText("自动更新暂缓；请点刷新重试。");});
                }
                public void onPassiveSnapshot(SessionRepository.SessionTarget target,long token,String content){
                    runOnUiThread(()->{if(generation==epoch)displayPassiveSnapshot(target,content);});
                }
                public void onSnapshot(SessionRepository.SessionTarget target,String content) { runOnUiThread(()->{
                    if(generation==epoch)displaySnapshot(target,content);
                }); }
            },Math::random,count->getPreferences(MODE_PRIVATE).edit().putInt("auto_retries",count).apply());
            connection.restoreInputUncertainty(getPreferences(MODE_PRIVATE).getBoolean("input_uncertain",false));
            if(foreground)connection.foreground();
            if(pair)connection.explicitInvitation(invitationClient);else if(restoring)connection.restoreSaved(priorRetries);else connection.explicitSaved();
        } catch(Exception ignored) {clearRecovery();stopConnection();invitation.setText("");state.setText("无法连接或凭证校验失败。检查地址、邀请权限或电脑端撤销状态；不会跳过安全检查。");}
    }
    private void showHomeOnReadyIfAppropriate(){
        if(!settingsOpen&&selected==null&&!"HOME".equals(currentScreen))showHome();
    }
    private static String sessionStatus(String status){
        switch(status.toLowerCase(java.util.Locale.ROOT)){
            case "idle":return "空闲";
            case "running":case "working":return "运行中";
            case "waiting":return "等待中";
            case "error":case "failed":return "异常";
            case "done":case "completed":return "已完成";
            default:return status.isEmpty()?"状态未知":shortText(status,28).replace('\n',' ');
        }
    }
    private void displaySessions(List<SessionRepository.Session> rows){
        liveRows=new ArrayList<>(rows.subList(0,Math.min(rows.size(),256)));
        inputTargets.clear();boolean retained=false;
        for(SessionRepository.Session row:liveRows){
            if(row.target.equals(selected))retained=true;
            if(row.manualTerminalInput)inputTargets.add(row.target);
        }
        if(lastOpened!=null&&findLive(lastOpened)==null)lastOpened=null;
        sessionCount=liveRows.size();homeEmpty.setVisibility(liveRows.isEmpty()?View.VISIBLE:View.GONE);
        homeEmpty.setText("暂无会话\n在电脑上打开终端或启动会话后，这里会自动更新。");
        if(!retained){clearTerminalDraft();if(connection!=null)connection.selectTarget(null);sessionList.setVisibility(View.VISIBLE);selected=null;terminal.setText("");copy.setEnabled(false);refresh.setEnabled(false);selectedLabel.setText("未选中会话");if(!settingsOpen&&!"HOME".equals(currentScreen))showHome();else renderSessionRows();}
        else renderSessionRows();
        if(!settingsOpen&&liveRows.isEmpty())state.setText("已连接，服务器没有可显示的会话。");
        else if(!settingsOpen&&selected==null)state.setText("已连接。选择一个会话查看终端。");
        updateInputControls();
    }
    private SessionRepository.Session findLive(SessionRepository.SessionTarget target){
        if(target==null)return null;
        for(SessionRepository.Session row:liveRows)if(row.target.equals(target))return row;
        return null;
    }
    private void clearLiveSessions(){
        liveRows=Collections.emptyList();lastOpened=null;sessionCount=-1;renderSessionRows();
    }
    private void renderSessionRows(){
        sessions.removeAllViews();recentCard.removeAllViews();recentButton=null;
        String query=searchOpen?searchQuery.getText().toString().trim().toLowerCase(java.util.Locale.ROOT):"";
        SessionRepository.Session recent=ready?findLive(lastOpened):null;
        if(recent!=null&&!matchesTitle(recent,query))recent=null;
        matchingCount=0;
        recentCard.setVisibility(recent==null?View.GONE:View.VISIBLE);
        if(recent!=null){
            recentCard.addView(text("最近使用",12,MUTED));
            FrameLayout slab=new FrameLayout(this);slab.setBackgroundColor(CARD);recentCard.addView(slab,spaced(-1,dp(108),12));
            recentButton=sessionButton(recent,true);slab.addView(recentButton,new FrameLayout.LayoutParams(-1,-1));
            View accent=new View(this);accent.setBackgroundColor(TEAL);accent.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);slab.addView(accent,new FrameLayout.LayoutParams(dp(2),-1,android.view.Gravity.START));
            TextView arrow=text("→",21,BG);arrow.setGravity(android.view.Gravity.CENTER);arrow.setBackground(shape(TEAL,24));arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams arrowSpace=new FrameLayout.LayoutParams(dp(38),dp(38),android.view.Gravity.END|android.view.Gravity.CENTER_VERTICAL);arrowSpace.setMarginEnd(dp(18));slab.addView(arrow,arrowSpace);
        }
        for(SessionRepository.Session row:liveRows){
            if(!matchesTitle(row,query))continue;
            matchingCount++;
            if(recent!=null&&recent.target.equals(row.target))continue;
            sessions.addView(sessionButton(row,false),new LinearLayout.LayoutParams(-1,-2));
        }
        otherHeading.setText(recent==null?"全部会话":"其他会话");
        otherHeading.setVisibility(sessions.getChildCount()==0?View.GONE:View.VISIBLE);
        searchEmpty.setVisibility(ready&&searchOpen&&!query.isEmpty()&&!liveRows.isEmpty()&&matchingCount==0?View.VISIBLE:View.GONE);
        updateHomeCount();
    }
    private static String sessionTitle(SessionRepository.Session row){
        return shortText(row.title.isEmpty()?"未命名会话":row.title,64).replace('\n',' ');
    }
    private static boolean matchesTitle(SessionRepository.Session row,String query){
        return query.isEmpty()||sessionTitle(row).toLowerCase(java.util.Locale.ROOT).contains(query);
    }
    private Button sessionButton(SessionRepository.Session row,boolean recent){
        Button item=button("",false);item.setGravity(android.view.Gravity.START|android.view.Gravity.CENTER_VERTICAL);
        item.setPaddingRelative(dp(recent?16:0),dp(10),dp(recent?68:0),dp(10));item.setMinHeight(dp(78));item.setTextSize(recent?21:16);item.setMaxLines(3);item.setEllipsize(android.text.TextUtils.TruncateAt.END);
        String title=sessionTitle(row);
        String status=sessionStatus(row.status);
        android.text.SpannableString label=new android.text.SpannableString(title+"\n"+status);
        label.setSpan(new android.text.style.StyleSpan(Typeface.BOLD),0,title.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        label.setSpan(new android.text.style.ForegroundColorSpan(MUTED),title.length()+1,label.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        label.setSpan(new android.text.style.RelativeSizeSpan(recent?12f/21f:12f/16f),title.length()+1,label.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        item.setText(label);
        if(recent)item.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33E0F786),null,shape(INK,0)));
        else {
            decorate(item,R.drawable.ic_terminal_mark,R.drawable.ic_bridge_chevron);
            android.graphics.drawable.GradientDrawable line=shape(STROKE,0);
            android.graphics.drawable.LayerDrawable divider=new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{line});
            divider.setLayerHeight(0,dp(1));divider.setLayerGravity(0,android.view.Gravity.BOTTOM|android.view.Gravity.FILL_HORIZONTAL);
            item.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33E0F786),divider,shape(INK,0)));
        }
        item.setEnabled(ready&&!inputPending);
        item.setOnClickListener(v->{SessionRepository.Session live=findLive(row.target);if(live!=null)openSession(live);});
        return item;
    }
    private void openSession(SessionRepository.Session row){
        if(!ready||connection==null||inputPending)return;
        clearTerminalDraft();selected=row.target;lastOpened=row.target;connection.selectTarget(selected);hideKeyboard();showDetail();
        detailHeading.setText(TerminalSnapshot.render(row.title.isEmpty()?"终端":row.title));headingViewport.scrollTo(0,0);sessionList.setVisibility(View.GONE);
        terminal.setText("");copy.setEnabled(false);selectedStatus=sessionStatus(row.status);targetDetailsVisible=false;showTargetDetails();
        state.setText("正在读取终端…");refresh.setEnabled(true);updateInputControls();connection.requestSnapshot(selected);
    }
    private void displayPassiveSnapshot(SessionRepository.SessionTarget target,String content){
        if(!foreground||!ready||!"DETAIL".equals(currentScreen)||!target.equals(selected)||inputPending||getPreferences(MODE_PRIVATE).getBoolean("input_uncertain",false))return;
        // Observation only: never touch the input timer, inspection state or selected target.
        String rendered=TerminalSnapshot.render(content);
        if(!rendered.contentEquals(terminal.getText()))terminal.setText(rendered);
        copy.setEnabled(terminal.length()>0);
    }
    private void displaySnapshot(SessionRepository.SessionTarget target,String content){
        if(ready&&target.equals(selected)){terminal.setText(TerminalSnapshot.render(content));copy.setEnabled(terminal.length()>0);state.setText("终端已更新。");updateInputControls();if(inputExpiry!=null)handler.removeCallbacks(inputExpiry);inputExpiry=this::updateInputControls;handler.postDelayed(inputExpiry,connection==null?1:Math.max(1,connection.inputFreshMillis(target)));}
    }
    private void stopConnection() {epoch++;ready=false;updateScreenBack();clearTerminalDraft();inputPending=false;pendingMutationId=null;pendingMutationTarget=null;inputTargets.clear();verifiedRole="reader";if(connection!=null){connection.stop();connection=null;}if(repository!=null){repository.close();repository=null;}selected=null;if(terminal!=null)terminal.setText("");if(sessions!=null)clearLiveSessions();if(refresh!=null)refresh.setEnabled(false);if(copy!=null)copy.setEnabled(false);if(showSessions!=null)showSessions.setEnabled(false);if(selectedLabel!=null)selectedLabel.setText("未选中会话");updateInputControls();}
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
    @Override public void onStop(){foreground=false;closeSearch();updateScreenBack();clearTerminalDraft();inputPending=false;pendingMutationId=null;pendingMutationTarget=null;cancelQr(false);copy.setEnabled(false);showSessions.setEnabled(false);selectedLabel.setText("未选中会话");if(connection!=null)connection.background();ready=false;selected=null;terminal.setText("");clearLiveSessions();refresh.setEnabled(false);invitation.setText("");state.setText("后台已断开；返回后仅用已保存凭证恢复，不重试邀请。");updateInputControls();super.onStop();}
    @Override public void onDestroy(){foreground=false;updateScreenBack();cancelQr(true);qrWorker.shutdownNow();stopConnection();super.onDestroy();}
}
