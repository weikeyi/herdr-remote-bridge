package dev.herdrbridge.client;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.http.SslError;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.ByteArrayInputStream;

/** Preview shell for a user-trusted hosted PWA, not a sandbox for hostile scripts. */
public final class MainActivity extends Activity {
    private WebView web;
    private EditText originInput, inviteInput;
    private TextView status;
    private LinearLayout layout, controls;
    private android.widget.ScrollView configPanel;
    private boolean cryptoReady;
    private int pageGeneration;
    private volatile String origin = "";
    private boolean clearing;
    private boolean invitationPending;
    private SharedPreferences preferences;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        WebView.setWebContentsDebuggingEnabled(false);
        preferences = getSharedPreferences("bridge", MODE_PRIVATE);
        origin = preferences.getString("origin", "");
        layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(12 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        layout.setOnApplyWindowInsetsListener((view, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets edges = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.ime());
                view.setPadding(padding + edges.left, padding + edges.top, padding + edges.right, padding + edges.bottom);
            } else {
                view.setPadding(padding + insets.getSystemWindowInsetLeft(), padding + insets.getSystemWindowInsetTop(),
                    padding + insets.getSystemWindowInsetRight(), padding + insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        Button toggle = new Button(this);
        toggle.setText("连接设置");
        toggle.setOnClickListener(view -> configPanel.setVisibility(configPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        layout.addView(toggle);
        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        configPanel = new android.widget.ScrollView(this);
        configPanel.setFillViewport(false);
        configPanel.addView(controls);
        layout.addView(configPanel, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView notice = new TextView(this);
        notice.setText("预览版：仅打开你信任的 HTTPS Bridge。服务器脚本仍是信任边界；并非原生终端。邀请只用于本次配对，会话凭据由网页存储。不要使用不受信任的服务器。");
        controls.addView(notice);
        originInput = input("https://你的域名或IP:9443", false);
        originInput.setText(origin);
        button("保存服务地址", () -> configure());
        inviteInput = input("在此粘贴完整的一次性邀请链接", true);
        button("使用邀请配对", () -> pair());
        button("打开已有会话", () -> {
            if (!clearing && !origin.isEmpty()) { web.loadUrl(origin + "/"); collapseControls(); }
        });
        button("清除本机会话及服务地址", () -> new AlertDialog.Builder(this)
            .setMessage("将关闭应用并由Android删除全部应用数据、网页会话和服务地址。不会撤销服务器上的设备；需要在电脑端单独撤销。")
            .setNegativeButton("取消", null).setPositiveButton("清除", (dialog, which) -> clearSession()).show());
        status = new TextView(this);
        controls.addView(status);
        createWebView();
        setContentView(layout);
        layout.requestApplyInsets();
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::onBackPressed);
        }
    }
    private EditText input(String hint, boolean secret) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setSingleLine(true);
        field.setSaveEnabled(false);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_URI));
        controls.addView(field);
        return field;
    }
    private void button(String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(view -> action.run());
        controls.addView(button);
    }
    private void configure() {
        if (clearing) return;
        try {
            String next = InvitationValidator.origin(originInput.getText().toString().trim());
            if (!origin.isEmpty() && !next.equals(origin)) {
                status.setText("更换服务地址前，请先清除本机会话。");
                return;
            }
            origin = next;
            originInput.setText(origin);
            preferences.edit().putString("origin", origin).apply();
            status.setText("正在检查WebCrypto，请检查完成后再粘贴邀请。TLS证书必须受系统信任。");
            web.loadUrl(origin + "/");
        } catch (IllegalArgumentException e) { status.setText("地址无效：只允许 HTTPS 根地址，不允许用户信息、查询或片段。"); }
    }
    private void pair() {
        if (!cryptoReady) {
            inviteInput.setText("");
            status.setText("请先保存地址并等待WebCrypto检查成功，再重新粘贴邀请。邀请尚未使用。");
            return;
        }
        String value = inviteInput.getText().toString();
        inviteInput.setText("");
        if (clearing || origin.isEmpty()) { status.setText("请先保存服务地址。"); return; }
        try {
            String url = InvitationValidator.invitation(value, origin, System.currentTimeMillis());
            invitationPending = true;
            web.loadUrl(url);
            collapseControls();
            status.setText("正在配对。邀请未另存；请在网页中确认结果。");
        } catch (IllegalArgumentException e) { status.setText("邀请无效、已过期或与服务地址不一致，请重新生成专用邀请。"); }
    }
    @SuppressWarnings("deprecation")
    private void createWebView() {
        web = new WebView(this);
        web.setSaveEnabled(false);
        web.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        configureWebView(web);
        web.setDownloadListener((url, agent, disposition, mime, size) -> status.setText("预览版不支持下载。"));
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(android.webkit.ConsoleMessage message) { return true; }
            @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }
            @Override public void onGeolocationPermissionsShowPrompt(String url, android.webkit.GeolocationPermissions.Callback callback) {
                callback.invoke(url, false, false);
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !InvitationValidator.sameOrigin(request.getUrl().toString(), origin);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return !InvitationValidator.sameOrigin(url, origin);
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (InvitationValidator.sameOrigin(request.getUrl().toString(), origin)) return null;
                return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", java.util.Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                cryptoReady = false;
                configPanel.setVisibility(View.VISIBLE);
                status.setText("TLS证书验证失败，已拒绝连接。不会绕过证书错误。");
            }
            @Override public void onPageStarted(WebView view, String url, Bitmap icon) {
                pageGeneration++;
                cryptoReady = false;
                if (!"about:blank".equals(url) && !InvitationValidator.sameOrigin(url, origin)) view.stopLoading();
            }
            @Override public void doUpdateVisitedHistory(WebView view, String url, boolean reload) {
                forgetInvitationHistory(view, url);
            }
            @Override public void onPageFinished(WebView view, String url) {
                forgetInvitationHistory(view, url);
                if (InvitationValidator.sameOrigin(url, origin) && url.indexOf('#') < 0) probeCrypto(view, pageGeneration);
            }
        });
        layout.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
    }
    @SuppressWarnings("deprecation")
    static void configureWebView(WebView web) {
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(true); // No onCreateWindow implementation: popup rejected.
        settings.setGeolocationEnabled(false);
        settings.setSafeBrowsingEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
    }
    private void collapseControls() {
        inviteInput.clearFocus();
        android.view.inputmethod.InputMethodManager input = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (input != null) input.hideSoftInputFromWindow(inviteInput.getWindowToken(), 0);
        configPanel.setVisibility(View.GONE);
        web.requestFocus();
    }
    // Legacy entry point for API 28-32. API 33+ registers the platform gesture
    // callback above and reuses this action; neither path traverses WebView history.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() {
        // Never navigate backward into an invitation-bearing history entry.
        if (configPanel.getVisibility() == View.GONE) configPanel.setVisibility(View.VISIBLE);
        else finish();
    }
    private void probeCrypto(WebView view, int generation) {
        view.evaluateJavascript("window.__bridgeCryptoCheck='pending'; (async()=>{try {"
            + "if(!isSecureContext||!crypto.subtle)throw 0;const s=crypto.subtle;"
            + "const p=await s.generateKey({name:'ECDH',namedCurve:'P-256'},false,['deriveBits']);"
            + "await s.deriveBits({name:'ECDH',public:p.publicKey},p.privateKey,256);"
            + "const k=await s.importKey('raw',new Uint8Array(32),'HKDF',false,['deriveKey']);"
            + "const a=await s.deriveKey({name:'HKDF',hash:'SHA-256',salt:new Uint8Array(32),info:new Uint8Array(1)},k,{name:'AES-GCM',length:256},false,['encrypt','decrypt']);"
            + "const iv=new Uint8Array(12);const c=await s.encrypt({name:'AES-GCM',iv},a,new Uint8Array([1]));"
            + "await s.decrypt({name:'AES-GCM',iv},a,c);"
            + "const h=await s.importKey('raw',new Uint8Array(32),{name:'HMAC',hash:'SHA-256'},false,['sign']);"
            + "await s.sign('HMAC',h,new Uint8Array(1));window.__bridgeCryptoCheck='ok';"
            + "}catch(e){window.__bridgeCryptoCheck='failed';}})();", ignored -> pollCrypto(view, generation, 0));
    }
    private void pollCrypto(WebView view, int generation, int attempt) {
        if (generation != pageGeneration || clearing || isFinishing() || isDestroyed()) return;
        view.evaluateJavascript("window.__bridgeCryptoCheck", result -> {
            if (generation != pageGeneration || clearing || isFinishing() || isDestroyed()) return;
            if ("\"ok\"".equals(result)) {
                cryptoReady = true;
                status.setText("WebCrypto检查通过，可粘贴邀请。尚不代表真实连接验收。");
            } else if (attempt < 40 && "\"pending\"".equals(result)) {
                view.postDelayed(() -> pollCrypto(view, generation, attempt + 1), 250);
            } else {
                cryptoReady = false;
                status.setText("WebCrypto检查失败，请更新Android System WebView后重试。邀请未使用。");
                configPanel.setVisibility(View.VISIBLE);
            }
        });
    }
    private void forgetInvitationHistory(WebView view, String url) {
        if (invitationPending && InvitationValidator.sameOrigin(url, origin) && url.indexOf('#') < 0) {
            view.clearHistory();
            invitationPending = false;
        }
    }
    private void clearSession() {
        if (clearing) return;
        clearing = true;
        inviteInput.setText("");
        web.stopLoading();
        web.clearHistory();
        web.clearCache(true);
        web.clearFormData();
        layout.removeView(web);
        web.destroy();
        // Android terminates this process on success and removes the complete app
        // profile, including WebView service workers. Partial WebStorage deletion
        // cannot safely authorize an origin switch.
        android.app.ActivityManager manager = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (manager == null || !manager.clearApplicationUserData()) {
            createWebView();
            clearing = false;
            status.setText("系统未能清除全部应用数据。服务地址保持锁定，请在Android设置中清除本应用存储空间。");
        }
    }
    @Override protected void onPause() {
        inviteInput.setText("");
        if (!clearing) web.onPause();
        CookieManager.getInstance().flush();
        super.onPause();
    }
    @Override protected void onResume() { super.onResume(); if (web != null && !clearing) web.onResume(); }
    @Override protected void onDestroy() {
        if (web != null && !clearing) { layout.removeView(web); web.destroy(); }
        super.onDestroy();
    }
}
