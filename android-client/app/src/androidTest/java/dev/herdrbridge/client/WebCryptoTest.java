package dev.herdrbridge.client;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.security.NetworkSecurityPolicy;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebSettings;
import android.webkit.CookieManager;
import android.webkit.WebViewClient;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real WebView API smoke tests; fixture responses never contact a server. */
@RunWith(AndroidJUnit4.class)
public final class WebCryptoTest {
    private static final String URL = "https://bridge.invalid/";
    private WebView web;
    private void ui(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }
    private void openFixture() throws Exception {
        CountDownLatch loaded = new CountDownLatch(1);
        ui(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            web = new WebView(context);
            MainActivity.configureWebView(web);
            assertTrue(web.getSettings().getJavaScriptEnabled());
            assertTrue(web.getSettings().getDomStorageEnabled());
            assertFalse(web.getSettings().getAllowFileAccess());
            assertFalse(web.getSettings().getAllowContentAccess());
            assertFalse(web.getSettings().getAllowFileAccessFromFileURLs());
            assertFalse(web.getSettings().getAllowUniversalAccessFromFileURLs());
            assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, web.getSettings().getMixedContentMode());
            assertFalse(web.getSettings().getJavaScriptCanOpenWindowsAutomatically());
            assertTrue(web.getSettings().getSafeBrowsingEnabled());
            assertFalse(CookieManager.getInstance().acceptThirdPartyCookies(web));
            web.setWebViewClient(new WebViewClient() {
                @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    boolean fixture = URL.equals(request.getUrl().toString());
                    String html = "<!doctype html><meta charset=utf-8><title>Crypto fixture</title>";
                    return new WebResourceResponse("text/html", "UTF-8", fixture ? 200 : 403,
                        fixture ? "OK" : "Blocked", Collections.singletonMap("Cache-Control", "no-store"),
                        new ByteArrayInputStream((fixture ? html : "").getBytes(StandardCharsets.UTF_8)));
                }
                @Override public void onPageFinished(WebView view, String url) {
                    if (URL.equals(url)) loaded.countDown();
                }
            });
            web.loadUrl(URL);
        });
        assertTrue("HTTPS fixture did not load", loaded.await(20, TimeUnit.SECONDS));
    }
    private String evaluate(String script) throws Exception {
        AtomicReference<String> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        ui(() -> web.evaluateJavascript(script, value -> { result.set(value); done.countDown(); }));
        assertTrue("JavaScript callback timed out", done.await(10, TimeUnit.SECONDS));
        return result.get();
    }
    private void destroy() {
        ui(() -> { if (web != null) { web.stopLoading(); web.destroy(); web = null; } });
    }
    @Test public void secureContextCryptoAndStorageSurviveWebViewRecreation() throws Exception {
        try {
            openFixture();
            evaluate("localStorage.removeItem('bridge_crypto_test'); window.cryptoResult='pending';"
                + "(async()=>{try{"
                + "if(!isSecureContext||!crypto.subtle)throw Error('secure context');"
                + "const s=crypto.subtle, data=new TextEncoder().encode('synthetic-fixture-only');"
                + "const a=await s.generateKey({name:'ECDH',namedCurve:'P-256'},true,['deriveBits']);"
                + "const b=await s.generateKey({name:'ECDH',namedCurve:'P-256'},true,['deriveBits']);"
                + "const raw=await s.exportKey('raw',b.publicKey);"
                + "const pub=await s.importKey('raw',raw,{name:'ECDH',namedCurve:'P-256'},false,[]);"
                + "const bits=await s.deriveBits({name:'ECDH',public:pub},a.privateKey,256);"
                + "const material=await s.importKey('raw',bits,'HKDF',false,['deriveKey','deriveBits']);"
                + "const params={name:'HKDF',hash:'SHA-256',salt:new Uint8Array(32),info:data};"
                + "const derived=await s.deriveBits(params,material,256);if(derived.byteLength!==32)throw Error('HKDF');"
                + "const aes=await s.deriveKey(params,material,{name:'AES-GCM',length:256},false,['encrypt','decrypt']);"
                + "const iv=crypto.getRandomValues(new Uint8Array(12));"
                + "const cipher=await s.encrypt({name:'AES-GCM',iv},aes,data);"
                + "const plain=await s.decrypt({name:'AES-GCM',iv},aes,cipher);"
                + "if(new TextDecoder().decode(plain)!=='synthetic-fixture-only')throw Error('AES');"
                + "const h=await s.importKey('raw',derived,{name:'HMAC',hash:'SHA-256'},false,['sign','verify']);"
                + "const sig=await s.sign('HMAC',h,data);if(!await s.verify('HMAC',h,sig,data))throw Error('HMAC');"
                + "localStorage.setItem('bridge_crypto_test','synthetic-fixture-only');window.cryptoResult='ok';"
                + "}catch(e){window.cryptoResult='failed:'+e.name;}})();");
            String result = "\"pending\"";
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while ("\"pending\"".equals(result) && System.nanoTime() < deadline) {
                Thread.sleep(100);
                result = evaluate("window.cryptoResult");
            }
            assertEquals("Web Crypto algorithms must work in the actual WebView provider", "\"ok\"", result);
            destroy();
            openFixture();
            assertEquals("\"synthetic-fixture-only\"", evaluate("localStorage.getItem('bridge_crypto_test')"));
            evaluate("localStorage.removeItem('bridge_crypto_test')");
        } finally { destroy(); }
    }
    @Test public void manifestDisablesCleartextAndBackupAndRequestsOnlyInternet() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS);
        assertArrayEquals(new String[] { "android.permission.INTERNET" }, info.requestedPermissions);
        assertEquals(0, info.applicationInfo.flags & ApplicationInfo.FLAG_ALLOW_BACKUP);
        assertEquals(0, info.applicationInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE);
        assertFalse(NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("bridge.invalid"));
    }
}
