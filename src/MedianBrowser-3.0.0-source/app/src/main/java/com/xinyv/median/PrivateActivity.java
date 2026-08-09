package com.xinyv.median;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebViewDatabase;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.net.URLEncoder;
import java.util.Collections;

/**
 * Isolated-process private browser for Android 9+. Its WebView profile never shares the normal
 * cookie/database directory and is erased when the task closes.
 */
public final class PrivateActivity extends Activity {
    private static final int FILE_CHOOSER_REQUEST = 501;
    private static final String HOME = "https://median-private.invalid/";
    private static final String HOME_TOKEN = UrlCleaner.randomToken();
    private static final byte[] EMPTY = new byte[0];
    private static boolean privateDataDirectoryConfigured;
    private final AdBlockEngine adBlock = new AdBlockEngine();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private FilterSubscriptionStore filterStore;
    private Thread filterCompileThread;
    private WebView webView;
    private EditText address;
    private ProgressBar progress;
    private String pageHost = "";
    private boolean trustedHome;
    private boolean privateProfileReady;
    private ValueCallback<Uri[]> fileChooserCallback;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (android.os.Build.VERSION.SDK_INT < 28) { finish(); return; }
        if (!ensurePrivateDataDirectory()) {
            Toast.makeText(this, "无法建立隔离的 WebView 数据目录，隐私窗口已关闭", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        filterStore = new FilterSubscriptionStore(this);
        filterCompileThread = new Thread(new Runnable() {
            @Override public void run() {
                try { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); } catch (RuntimeException ignored) {}
                adBlock.updateRules(getSharedPreferences("median_browser_v2", MODE_PRIVATE).getString("custom_filter_rules", ""),
                        filterStore.readEnabledRuleSources());
            }
        }, "median-private-filter-compile");
        filterCompileThread.start();
        getWindow().setStatusBarColor(Color.rgb(30, 32, 36));
        getWindow().setNavigationBarColor(Color.rgb(30, 32, 36));
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (android.os.Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        buildUi();
        configure();
        CookieManager.getInstance().removeAllCookies(new ValueCallback<Boolean>() {
            @Override public void onReceiveValue(Boolean value) {
                completePrivateProfileReset();
            }
        });
        // Some vendor WebView builds fail to deliver the cookie callback. Never leave
        // the private window on a permanent blank page because of that provider bug.
        handler.postDelayed(new Runnable() {
            @Override public void run() { completePrivateProfileReset(); }
        }, 1500L);
    }

    private static synchronized boolean ensurePrivateDataDirectory() {
        if (privateDataDirectoryConfigured) return true;
        if (android.os.Build.VERSION.SDK_INT < 28) {
            // setDataDirectorySuffix requires API 28; on 26/27 fall back to the
            // default profile (private isolation is best-effort there).
            privateDataDirectoryConfigured = true;
            return true;
        }
        try {
            WebView.setDataDirectorySuffix("median_private");
            privateDataDirectoryConfigured = true;
            return true;
        } catch (RuntimeException error) {
            return false;
        }
    }

    private void completePrivateProfileReset() {
        if (privateProfileReady || isFinishing() || webView == null) return;
        privateProfileReady = true;
        CookieManager.getInstance().flush();
        clearPrivateProfileMetadata();
        webView.clearCache(true);
        showHome();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(18, 20, 23));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), dp(7), dp(8), dp(7));
        TextView mask = button("◉", "隐私浏览");
        address = new EditText(this);
        address.setSingleLine(true);
        address.setHint("隐私搜索或输入网址");
        address.setHintTextColor(Color.rgb(150, 155, 162));
        address.setTextColor(Color.WHITE);
        address.setTextSize(15f);
        address.setBackgroundColor(Color.rgb(43, 46, 51));
        address.setPadding(dp(15), 0, dp(15), 0);
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        TextView close = button("×", "关闭隐私浏览");
        top.addView(mask, new LinearLayout.LayoutParams(dp(42), dp(44)));
        top.addView(address, new LinearLayout.LayoutParams(0, dp(44), 1f));
        top.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));
        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.rgb(138, 180, 248)));
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(18, 20, 23));
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.setClickable(true);
        webView.setVerticalScrollBarEnabled(false);
        root.addView(webView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER);
        TextView back = button("‹", "后退");
        TextView forward = button("›", "前进");
        TextView home = button("⌂", "隐私主页");
        TextView reload = button("↻", "刷新");
        TextView info = button("⋯", "隐私说明");
        TextView[] actions = new TextView[] { back, forward, home, reload, info };
        for (TextView action : actions) bottom.addView(action, new LinearLayout.LayoutParams(0, dp(52), 1f));
        root.addView(bottom, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        setContentView(root);
        installSystemBarInsets(root);

        address.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_GO || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    loadInput(address.getText().toString());
                    address.clearFocus();
                    return true;
                }
                return false;
            }
        });
        close.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { finishAndRemoveTask(); } });
        back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { if (webView.canGoBack()) webView.goBack(); } });
        forward.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { if (webView.canGoForward()) webView.goForward(); } });
        home.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { showHome(); } });
        reload.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { webView.reload(); } });
        info.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                new AlertDialog.Builder(PrivateActivity.this).setTitle("独立隐私会话")
                        .setMessage("此窗口运行在独立进程和独立 WebView 数据目录中，不记录 Median 历史、书签或标签会话；仍会读取普通窗口已下载的过滤订阅，但不运行持久用户脚本。关闭后会清除 Cookie、缓存及网站存储。下载已禁用，避免系统下载管理器留下记录。运营商、网站和网络管理者仍可能看到访问活动。")
                        .setPositiveButton("知道了", null).show();
            }
        });
    }

    private void installSystemBarInsets(final View target) {
        if (target == null || android.os.Build.VERSION.SDK_INT < 30) return;
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        android.view.WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) controller.setSystemBarsAppearance(0,
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS |
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        target.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public android.view.WindowInsets onApplyWindowInsets(View view, android.view.WindowInsets insets) {
                android.graphics.Insets bars = insets.getInsets(
                        android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            }
        });
        target.requestApplyInsets();
    }

    private TextView button(String text, String description) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.rgb(220, 223, 228));
        view.setTextSize(25f);
        view.setGravity(Gravity.CENTER);
        view.setContentDescription(description);
        return view;
    }

    private void configure() {
        WebSettings settings = webView.getSettings();
        WebViewPolicy.applySecureDefaults(settings, WebSettings.LOAD_NO_CACHE);
        settings.setSupportMultipleWindows(false);
        String mobileUa = WebViewPolicy.mobileUserAgent(settings.getUserAgentString());
        if (!mobileUa.equals(settings.getUserAgentString())) settings.setUserAgentString(mobileUa);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);

        EdgeNavigationController.attach(webView, new EdgeNavigationController.Callback() {
            @Override public boolean canGoBack() { return webView.canGoBack(); }
            @Override public boolean canGoForward() { return webView.canGoForward(); }
            @Override public void goBack() { webView.goBack(); }
            @Override public void goForward() { webView.goForward(); }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return request != null && request.isForMainFrame() && handle(request.getUrl().toString());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) { return handle(url); }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                if (!isHome(url)) trustedHome = false;
                pageHost = host(url);
                address.setText(isHome(url) ? "" : url);
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String accept = request == null ? "" : request.getRequestHeaders().get("Accept");
                return request != null && adBlock.shouldBlock(request.getUrl(), pageHost, accept, request.isForMainFrame())
                        ? new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(EMPTY)) : null;
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                return adBlock.shouldBlock(url, pageHost)
                        ? new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(EMPTY)) : null;
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                Toast.makeText(PrivateActivity.this, "证书验证失败，已停止加载", Toast.LENGTH_SHORT).show();
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (isHome(url)) verifyTrustedHome(view);
                String css = adBlock.cosmeticCssForHost(pageHost);
                String procedural = adBlock.proceduralScriptForHost(pageHost);
                if (css.length() > 0 || procedural.length() > 0) view.evaluateJavascript("(function f(){var p=document.head||document.documentElement;if(!p){setTimeout(f,30);return;}if(" + (css.length() > 0 ? "true" : "false") + "){var s=document.createElement('style');s.textContent=" + JSONObject.quote(css) + ";p.appendChild(s);}})();" + procedural, null);
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int value) {
                progress.setVisibility(value < 100 ? View.VISIBLE : View.GONE);
                progress.setProgress(value);
            }
            @Override public void onPermissionRequest(android.webkit.PermissionRequest request) { if (request != null) request.deny(); }
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(null);
                fileChooserCallback = callback;
                try { startActivityForResult(params.createIntent(), FILE_CHOOSER_REQUEST); return true; }
                catch (Exception e) { fileChooserCallback = null; Toast.makeText(PrivateActivity.this, "文件选择器不可用", Toast.LENGTH_SHORT).show(); return false; }
            }
        });
        webView.setDownloadListener(new android.webkit.DownloadListener() {
            @Override public void onDownloadStart(String url, String userAgent, String disposition, String mime, long size) {
                Toast.makeText(PrivateActivity.this, "隐私窗口不写入系统下载记录", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private boolean handle(String url) {
        if (url == null) return false;
        Uri parsed;
        try { parsed = Uri.parse(url); } catch (RuntimeException ignored) { return true; }
        if ("median".equalsIgnoreCase(parsed.getScheme())) {
            if (!trustedHome || !isHome(webView == null ? null : webView.getUrl())) {
                Toast.makeText(this, "网页无权调用浏览器内部功能", Toast.LENGTH_SHORT).show();
                return true;
            }
            String action = parsed.getHost();
            if ("search".equals(action)) loadInput(parsed.getQueryParameter("q"), parsed.getQueryParameter("engine"));
            else if ("bookmarks".equals(action))
                Toast.makeText(this, "隐私窗口不读取或保存书签", Toast.LENGTH_SHORT).show();
            return true;
        }
        String scheme = parsed.getScheme();
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            try { NetworkSecurity.parseHttpUrl(url); return false; }
            catch (Exception invalid) {
                Toast.makeText(this, "已阻止无效或含凭据的网址", Toast.LENGTH_SHORT).show();
                return true;
            }
        }
        if ("about:blank".equals(url)) return false;
        if (url.startsWith("data:") || url.startsWith("about:")) {
            Toast.makeText(this, "已阻止网页跳转到不透明内部地址", Toast.LENGTH_SHORT).show();
            return true;
        }
        confirmExternal(url);
        return true;
    }

    private void loadInput(String input) {
        loadInput(input, "google");
    }

    private void loadInput(String input, String engine) {
        String value = input == null ? "" : input.trim();
        if (value.length() == 0) return;
        trustedHome = false;
        if (OmniboxInput.isExplicitHttpUrl(value)) {
            try { webView.loadUrl(UrlCleaner.cleanTracking(NetworkSecurity.parseHttpUrl(value).toString())); }
            catch (Exception invalid) { Toast.makeText(this, "网址无效或包含不安全的凭据", Toast.LENGTH_SHORT).show(); }
        } else if (OmniboxInput.looksLikeWebAddress(value)) {
            try { webView.loadUrl(UrlCleaner.cleanTracking(NetworkSecurity.parseHttpsUrl(OmniboxInput.withDefaultHttpsScheme(value)).toString())); }
            catch (Exception invalid) { search(value, engine); }
        } else search(value, engine);
    }

    private void search(String query, String engine) {
        try {
            String encoded = URLEncoder.encode(query == null ? "" : query, "UTF-8");
            if ("baidu".equals(engine)) webView.loadUrl("https://www.baidu.com/s?wd=" + encoded);
            else if ("bing".equals(engine)) webView.loadUrl("https://www.bing.com/search?q=" + encoded);
            else webView.loadUrl("https://www.google.com/search?q=" + encoded);
        } catch (Exception ignored) {}
    }

    private void showHome() {
        pageHost = "";
        trustedHome = true;
        webView.loadDataWithBaseURL(HOME, HomePage.html("google", Collections.<BrowserDataStore.Bookmark>emptyList(), true, HOME_TOKEN), "text/html", "UTF-8", HOME);
    }

    private void verifyTrustedHome(final WebView view) {
        view.evaluateJavascript("(function(){var m=document.querySelector('meta[name=median-home-token]');return !!m&&m.content===" +
                JSONObject.quote(HOME_TOKEN) + ";})();", new ValueCallback<String>() {
            @Override public void onReceiveValue(String value) {
                try {
                    trustedHome = "true".equals(value) && webView == view && isHome(view.getUrl());
                } catch (RuntimeException ignored) {
                    trustedHome = false;
                }
            }
        });
    }

    private void confirmExternal(final String url) {
        new AlertDialog.Builder(this).setTitle("离开隐私窗口？")
                .setMessage("外部应用可能记录这次操作。仅在你信任当前网站时继续。")
                .setPositiveButton("继续", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface dialog, int which) {
                        try {
                            Intent intent;
                            if (url.startsWith("intent:")) {
                                Intent parsed = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                                if (parsed.getData() == null) throw new IllegalArgumentException("外部地址无效");
                                intent = new Intent(Intent.ACTION_VIEW, parsed.getData());
                            } else intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                            intent.addCategory(Intent.CATEGORY_BROWSABLE);
                            intent.setComponent(null);
                            intent.setPackage(null);
                            intent.setSelector(null);
                            int unsafeGrants = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;
                            intent.setFlags(intent.getFlags() & ~unsafeGrants);
                            intent.setClipData(null);
                            startActivity(intent);
                        } catch (Exception ignored) { Toast.makeText(PrivateActivity.this, "无法打开链接", Toast.LENGTH_SHORT).show(); }
                    }
                }).setNegativeButton("取消", null).show();
    }

    private static boolean isHome(String url) { return UrlCleaner.isInternalPage(url, "median-private.invalid"); }

    private static String host(String url) {
        try { String value = Uri.parse(url).getHost(); return value == null ? "" : value; }
        catch (Exception ignored) { return ""; }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST && fileChooserCallback != null) {
            fileChooserCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileChooserCallback = null;
        }
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack(); else super.onBackPressed();
    }

    @Override protected void onPause() {
        if (webView != null) try { webView.onPause(); } catch (RuntimeException ignored) {}
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (webView != null) try { webView.onResume(); } catch (RuntimeException ignored) {}
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (filterCompileThread != null) filterCompileThread.interrupt();
        filterCompileThread = null;
        if (fileChooserCallback != null) { fileChooserCallback.onReceiveValue(null); fileChooserCallback = null; }
        if (webView != null) {
            WebView closing = webView;
            webView = null;
            try { closing.stopLoading(); closing.onPause(); closing.clearHistory(); closing.clearCache(true); } catch (RuntimeException ignored) {}
            closing.setWebChromeClient(null);
            closing.setWebViewClient(null);
            if (closing.getParent() instanceof ViewGroup) ((ViewGroup) closing.getParent()).removeView(closing);
            closing.destroy();
        }
        CookieManager.getInstance().removeAllCookies(null);
        CookieManager.getInstance().flush();
        clearPrivateProfileMetadata();
        if (filterStore != null) filterStore.close();
        super.onDestroy();
    }

    private void clearPrivateProfileMetadata() {
        WebStorage.getInstance().deleteAllData();
        GeolocationPermissions.getInstance().clearAll();
        try {
            WebViewDatabase database = WebViewDatabase.getInstance(this);
            database.clearHttpAuthUsernamePassword();
            database.clearFormData();
        } catch (RuntimeException ignored) {}
    }
}
