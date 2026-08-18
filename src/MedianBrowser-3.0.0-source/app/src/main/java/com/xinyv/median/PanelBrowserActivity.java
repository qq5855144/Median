package com.xinyv.median;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 小窗浏览器：以浮动小窗承载 WebView，供 AI 工具调用时访问目标网页，
 * 不占用/不影响主窗口当前标签页（如 Kimi 对话页）。
 */
public final class PanelBrowserActivity extends Activity {

    public static final String EXTRA_URL = "panel_url";
    private WebView webView;
    private TextView titleView;
    private String initialUrl = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        if (intent != null && intent.getStringExtra(EXTRA_URL) != null) {
            initialUrl = intent.getStringExtra(EXTRA_URL);
        }
        configureWindow();
        buildUi();
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            try {
                getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                        android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                        new android.window.OnBackInvokedCallback() {
                            @Override public void onBackInvoked() {
                                if (webView != null && webView.canGoBack()) webView.goBack();
                                else finish();
                            }
                        });
            } catch (Exception ignored) { }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getStringExtra(EXTRA_URL) != null) {
            String u = intent.getStringExtra(EXTRA_URL);
            initialUrl = u;
            if (webView != null && !u.isEmpty()) webView.loadUrl(u);
        }
    }

    private void configureWindow() {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window w = getWindow();
        if (w != null) {
            w.setGravity(Gravity.CENTER);
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = 0.35f;
            DisplayMetrics dm = getResources().getDisplayMetrics();
            lp.width = (int) (dm.widthPixels * 0.93f);
            lp.height = (int) (dm.heightPixels * 0.86f);
            w.setAttributes(lp);
        }
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable() {
            {
                setColor(Color.WHITE);
                setCornerRadius(dp(14));
            }
        });
        root.setClipToOutline(true);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        // 顶部栏：标题 + 关闭
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(6), dp(8), dp(6));
        bar.setBackgroundColor(0xFFF3F4F6);
        titleView = new TextView(this);
        titleView.setTextSize(13);
        titleView.setTextColor(0xFF1F2329);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleView.setText("小窗浏览");
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bar.addView(titleView, tp);
        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextSize(18);
        close.setTextColor(0xFF4B5563);
        close.setGravity(Gravity.CENTER);
        close.setPadding(dp(10), dp(2), dp(10), dp(4));
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        bar.addView(close, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));

        // WebView
        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                view.loadUrl(url);
                return true;
            }
            @Override public void onPageFinished(WebView view, String url) {
                try {
                    String t = view.getTitle();
                    if (t != null && !t.isEmpty()) titleView.setText(t.length() > 60 ? t.substring(0, 60) : t);
                    else titleView.setText(url.length() > 60 ? url.substring(0, 60) : url);
                } catch (Exception ignored) { }
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onReceivedTitle(WebView view, String t) {
                try {
                    if (t != null && !t.isEmpty()) titleView.setText(t.length() > 60 ? t.substring(0, 60) : t);
                } catch (Exception ignored) { }
            }
            @Override public void onCloseWindow(WebView window) {
                finish();
            }
        });
        col.addView(webView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        if (initialUrl != null && !initialUrl.isEmpty()) {
            webView.loadUrl(initialUrl);
        }
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            try {
                webView.stopLoading();
                webView.setWebViewClient(null);
                webView.setWebChromeClient(null);
                ((ViewGroup) webView.getParent()).removeView(webView);
                webView.destroy();
            } catch (Exception ignored) { }
            webView = null;
        }
        super.onDestroy();
    }
}
