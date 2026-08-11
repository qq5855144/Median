package com.xinyv.median;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.PictureInPictureParams;
import android.print.PrintManager;
import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.util.Rational;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.JsPromptResult;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebResourceError;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.security.MessageDigest;
import java.util.Iterator;

public final class MainActivity extends Activity implements McpController.UiBindings {
    private static final int FILE_CHOOSER_REQUEST = 401;
    private static final int BACKUP_EXPORT_REQUEST = 402;
    private static final int BACKUP_IMPORT_REQUEST = 403;
    private static final int WEB_PERMISSION_REQUEST = 404;
    private static final int GEOLOCATION_PERMISSION_REQUEST = 405;
    private static final int VAULT_UNLOCK_REQUEST = 406;
    private static final int FULL_BACKUP_EXPORT_REQUEST = 407;
    private static final int FULL_BACKUP_IMPORT_REQUEST = 408;
    private static final int HOME_WALLPAPER_REQUEST = 409;
    private static final int HOME_LOGO_REQUEST = 410;
    private static final String PREFS = "median_browser_v2";
    private static final String HOME_URL = "https://median.invalid/";
    private static final String HOME_TOKEN = UrlCleaner.randomToken();
    private static final int MAX_SCRIPT_BYTES = 1024 * 1024;
    private static final int MAX_REQUIRE_BYTES = 512 * 1024;
    private static final int MAX_REQUIRE_COUNT = 5;
    private static final int MAX_REQUIRE_TOTAL_BYTES = 1024 * 1024;
    private static final int MAX_RESOURCE_COUNT = 16;
    private static final int MAX_RESOURCE_BYTES = 512 * 1024;
    private static final int MAX_RESOURCE_TOTAL_BYTES = 2 * 1024 * 1024;
    private static final int MAX_TABS = 64;
    private static final byte[] EMPTY_RESPONSE = new byte[0];
    private static final String NET_RULE_UA = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36 Median/3.0";
    private static final String MODE_PERFORMANCE = "performance";
    private static final String MODE_STANDARD = "standard";
    private static final String MODE_POWER_SAVE = "power_save";
    private static final int HOME_SECTION_MAIN = 0;
    private static final int HOME_SECTION_LAYOUT = 1;
    private static final int HOME_SECTION_LOGO = 2;
    private static final int HOME_SECTION_SEARCH = 3;
    private static final int HOME_SECTION_BACKGROUND = 4;
    private static final int HOME_SECTION_SHORTCUTS = 5;
    private static final int HOME_SECTION_CODE = 6;
    private static final String PREF_COMMUNITY_NOTICE_SHOWN = "community_notice_shown_v1";
    private static final String GITHUB_URL = "https://github.com/bi-box/Median";
    private static final String TELEGRAM_URL = "https://telegram.me/MedianBeta";
    private static final String COMMUNITY_INFO =
            "项目更新、版本发布与问题反馈：\n" + GITHUB_URL +
            "\n\n官方测试频道与公告：\n" + TELEGRAM_URL;

    private static final int WHITE = Color.rgb(255, 255, 255);
    private static final int TEXT = Color.rgb(32, 33, 36);
    private static final int MUTED = Color.rgb(95, 99, 104);
    private static final int SURFACE = Color.rgb(241, 243, 244);
    private static final int BLUE = Color.rgb(26, 115, 232);

    private FrameLayout rootFrame;
    // ===== P0: AI 工具调用可视化面板 + 长任务通知 =====
    private View toolPanelView;
    private LinearLayout toolPanelList;
    private TextView toolPanelBadge;
    private boolean toolPanelExpanded;
    private final java.util.ArrayDeque<String> toolEvents = new java.util.ArrayDeque<String>();
    private int toolNotifyId = 4001;
    private LinearLayout browserChrome;
    private LinearLayout topBar;
    private LinearLayout bottomBar;
    private FrameLayout webContainer;
    private WebView webView;
    private EditText addressBar;
    private LinearLayout addressPill;
    private ProgressBar progressBar;
    private BrowserIconView backButton;
    private BrowserIconView forwardButton;
    private BrowserIconView tabButton;
    private BrowserIconView shieldButton;
    private BrowserIconView refreshButton;
    private ValueCallback<Uri[]> fileChooserCallback;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private boolean performanceSamplingActive;
    private ExecutorService scriptExecutor;
    private ExecutorService scriptNetworkExecutor;
    private WifiManager.WifiLock mediaWifiLock;
    private PowerManager.WakeLock performanceWakeLock;
    private final Runnable performanceWakeRenewal = new Runnable() {
        @Override public void run() {
            if (!activityResumed || !MODE_PERFORMANCE.equals(performanceMode)) return;
            try { if (performanceWakeLock != null && performanceWakeLock.isHeld()) performanceWakeLock.release(); }
            catch (RuntimeException ignored) {}
            acquirePerformanceWakeLock();
        }
    };
    private final AggressivePerformanceController aggressivePerformanceController =
            new AggressivePerformanceController();
    private final Runnable performanceHintStarter = new Runnable() {
        @Override public void run() {
            if (activityResumed && MODE_PERFORMANCE.equals(performanceMode)) {
                aggressivePerformanceController.start(MainActivity.this, performanceTargetNanos());
            }
        }
    };
    private boolean activityResumed;
    private boolean prewarmPending;
    private long lastMemoryTrimAt;
    private boolean deferredStartupPending;
    private boolean deferredStartupComplete;
    private final Runnable webViewPrewarmer = new Runnable() {
        @Override public void run() {
            prewarmPending = false;
            if (activityResumed) ensurePrewarmedWebView();
        }
    };
    private final Runnable deferredStartup = new Runnable() {
        @Override public void run() {
            deferredStartupPending = false;
            if (deferredStartupComplete || isFinishing() || !activityResumed) return;
            deferredStartupComplete = true;
            rebuildAdBlockRulesAsync(false);
            updateFilterSubscriptions(true);
        }
    };

    private View customView;
    private View activeOverlay;
    private View activeOverlayPanel;
    private boolean activeOverlaySheet;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private int previousOrientation;
    private int previousSystemUi;

    private final AdBlockEngine adBlock = new AdBlockEngine();
    private UserScriptStore scriptStore;
    private final MediaResourceSniffer mediaSniffer = new MediaResourceSniffer();
    private BrowserServices services;
    private BrowserDataStore dataStore;
    private HomeImageStore homeImages;
    private SiteSettingsStore siteSettingsStore;
    private DeviceProfile deviceProfile;
    private WebView spareWebView;
    private SharedPreferences prefs;
    private volatile boolean adBlockEnabled;
    private boolean desktopMode;
    private boolean nightMode;
    private boolean httpsOnly;
    private boolean restoreTabs;
    private boolean acceptThirdPartyCookies;
    private String customSearchTemplate;
    private String searchEngine;
    private volatile String performanceMode;
    private volatile boolean performanceNetworkDirect;
    private volatile Set<String> siteExceptions;
    private int blockedAtPageStart;
    private volatile boolean scriptDownloadInProgress;
    private final ConcurrentHashMap<WebView, String> scriptBridgeTokens = new ConcurrentHashMap<WebView, String>();
    private final ConcurrentHashMap<WebView, List<ScriptHandler>> scriptHandlers = new ConcurrentHashMap<WebView, List<ScriptHandler>>();
    private final Set<WebView> documentStartScriptViews = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, HttpURLConnection> scriptConnections = new ConcurrentHashMap<String, HttpURLConnection>();
    /** 脚本桥诊断环形缓冲（release 构建 logcat 不可见，通过 dspp_diag 远程读取） */
    private final java.util.List<String> bridgeDiag = java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
    private void bridgeDiagLog(String msg) {
        try {
            synchronized (bridgeDiag) {
                bridgeDiag.add("[" + java.text.SimpleDateFormat.getTimeInstance(java.text.SimpleDateFormat.MEDIUM, java.util.Locale.US).format(new java.util.Date()) + "] " + msg);
                while (bridgeDiag.size() > 120) bridgeDiag.remove(0);
            }
        } catch (RuntimeException ignored) {}
    }
    private volatile boolean filterUpdateInProgress;
    private boolean autoPictureInPicture;
    private boolean cleanTrackingParameters;
    private final AtomicBoolean cookieFlushPending = new AtomicBoolean(false);
    private boolean compatibilityDialogShowing;
    private String lastCompatibilityOfferHost = "";
    private long lastCompatibilityOfferAt;
    private boolean rendererRecoveryPending;
    // WebView request callbacks run off the UI thread. Never call WebView methods there.
    private volatile String currentPageUrl = HOME_URL;
    private volatile String currentPageHost = "";
    private final ConcurrentHashMap<WebView, String> pageHosts = new ConcurrentHashMap<WebView, String>();
    private final ConcurrentHashMap<WebView, Boolean> adBlockActiveByView = new ConcurrentHashMap<WebView, Boolean>();
    private final ConcurrentHashMap<WebView, String> mobileUserAgents = new ConcurrentHashMap<WebView, String>();
    private final ConcurrentHashMap<WebView, String> appliedSiteSettings = new ConcurrentHashMap<WebView, String>();
    private final ConcurrentHashMap<WebView, Boolean> cosmeticInjected = new ConcurrentHashMap<WebView, Boolean>();
    private final Set<WebView> unresponsiveWebViews = ConcurrentHashMap.newKeySet();
    private final Set<WebView> trustedHomeViews = ConcurrentHashMap.newKeySet();
    private final Set<WebView> customHomeViews = ConcurrentHashMap.newKeySet();

    private volatile long navigationSequence;
    private boolean pageCommitted;
    private boolean pageFinished;
    private PreparedInjection preparedInjection;
    private long injectedStartSequence = -1;
    private long injectedEndSequence = -1;
    private boolean chromeUpdatePending;
    private boolean progressUpdatePending;
    private boolean hotTrimPending;
    private int pendingProgress = 100;
    private int renderedProgress = -1;
    private String renderedAddress;
    private Boolean renderedBackEnabled;
    private Boolean renderedForwardEnabled;
    private Integer renderedTabCount;
    private Boolean renderedShieldActive;
    private PermissionRequest pendingPermissionRequest;
    private String[] pendingWebPermissionResources;
    private WebView pendingPermissionView;
    private String pendingPermissionOrigin;
    private GeolocationPermissions.Callback pendingGeolocationCallback;
    private String pendingGeolocationOrigin;
    private WebView pendingGeolocationView;
    private Runnable pendingVaultAction;
    private long vaultUnlockedUntil;

    private static final class PreparedInjection {
        final long sequence;
        final String url;
        final String startScript;
        final String endScript;

        PreparedInjection(long sequence, String url, String startScript, String endScript) {
            this.sequence = sequence;
            this.url = url;
            this.startScript = startScript;
            this.endScript = endScript;
        }
    }

    static final class BrowserTab {
        String title = "新标签页";
        String url = HOME_URL;
        Bundle state;
        WebView liveView;
        long lastActiveAt;
        boolean pinned;
    }

    private final ArrayList<BrowserTab> tabs = new ArrayList<BrowserTab>();
    private final ArrayList<BrowserTab> closedTabs = new ArrayList<BrowserTab>();
    private int currentTabIndex = 0;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Window window = getWindow();
        window.setStatusBarColor(WHITE);
        window.setNavigationBarColor(WHITE);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false);

        deviceProfile = DeviceProfile.detect(this);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        migrateLegacyLogoExample();
        homeImages = new HomeImageStore(this);
        adBlockEnabled = prefs.getBoolean("adblock", true);
        desktopMode = prefs.getBoolean("desktop", false);
        nightMode = prefs.getBoolean("night_mode", false);
        httpsOnly = prefs.getBoolean("https_only", true);
        restoreTabs = HomeOpenPolicy.restoresLast(prefs.getString("home_open_mode", ""),
                prefs.getBoolean("restore_tabs", true));
        acceptThirdPartyCookies = prefs.getBoolean("accept_third_party_cookies", false);
        customSearchTemplate = prefs.getString("custom_search_template", "");
        searchEngine = prefs.getString("search_engine", "google");
        performanceMode = prefs.getString("performance_mode", MODE_STANDARD);
        performanceNetworkDirect = prefs.getBoolean("performance_network_direct", false);
        autoPictureInPicture = prefs.getBoolean("auto_picture_in_picture", false);
        cleanTrackingParameters = prefs.getBoolean("clean_tracking_parameters", true);
        if (!MODE_PERFORMANCE.equals(performanceMode) && !MODE_POWER_SAVE.equals(performanceMode)) performanceMode = MODE_STANDARD;
        siteExceptions = new HashSet<String>(prefs.getStringSet("site_exceptions", new HashSet<String>()));
        scriptStore = new UserScriptStore(this, DeepSeekPP.isEnabled(this) ? buildDsppAssetMap() : null);
        // 启动时若 DeepSeek++ 已开启，自动用 assets 最新代码重装脚本（幂等）。
        // 根治：升级 APK 后无需手动关→开；prefs 旧数据/损坏数据会被 assets 源码覆盖修复。
        if (DeepSeekPP.isEnabled(this)) {
            try {
                DeepSeekPP.install(this, scriptStore);
            } catch (Exception ignored) {
            }
            // DeepSeek++ 工具执行依赖本机 MCP 服务（8788）：确保在线，否则 AI 调工具必然失败
            try {
                McpController mcpAuto = McpController.get();
                if (!mcpAuto.enabled(this)) mcpAuto.setEnabled(this, true);
                if (!mcpAuto.isRunning()) mcpAuto.start(this);
            } catch (Exception ignored) {
            }
        }
        // Android 16+ 本地网络保护：GM 桥（app 进程 HttpURLConnection 连 127.0.0.1:8788）
        // 必须持有 ACCESS_LOCAL_NETWORK 运行时权限，否则抛 "local network target denied"
        if (Build.VERSION.SDK_INT >= 36) {
            try {
                if (checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
                    uiHandler.postDelayed(new Runnable() {
                        @Override public void run() {
                            try {
                                if (checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
                                    requestPermissions(new String[] { Manifest.permission.ACCESS_LOCAL_NETWORK }, 410);
                                    bridgeDiagLog("ACCESS_LOCAL_NETWORK permission requested");
                                }
                            } catch (RuntimeException ignored) {}
                        }
                    }, 1200);
                } else {
                    bridgeDiagLog("ACCESS_LOCAL_NETWORK already granted");
                }
            } catch (RuntimeException ignored) {}
        }
        services = new BrowserServices(this);
        scriptExecutor = newIdleExecutor(1);
        scriptNetworkExecutor = newIdleExecutor(3);
        siteSettingsStore = new SiteSettingsStore(this);
        dataStore = new BrowserDataStore(this);
        McpController mcp = McpController.get();
        if (mcp.enabled(this)) mcp.start(this);

        Intent intent = getIntent();
        boolean externalNavigation = isExternalHttpIntent(intent);
        if (!externalNavigation && restoreTabs) {
            List<BrowserDataStore.SessionTab> saved = dataStore.restoreSession();
            for (BrowserDataStore.SessionTab item : saved) {
                BrowserTab restored = new BrowserTab();
                restored.title = item.title;
                restored.url = item.url;
                restored.pinned = item.pinned;
                tabs.add(restored);
            }
            currentTabIndex = tabs.size() == 0 ? 0 : Math.min(dataStore.restoredSessionIndex(), tabs.size() - 1);
        }
        if (tabs.size() == 0) {
            BrowserTab initial = new BrowserTab();
            if (!externalNavigation && state == null) initial.url = configuredHomeUrl();
            tabs.add(initial);
        }
        BrowserTab first = tabs.get(currentTabIndex);
        buildUi();
        configureWebView(webView);
        first.liveView = webView;
        first.lastActiveAt = SystemClock.uptimeMillis();
        pageHosts.put(webView, "");

        if (externalNavigation) {
            first.url = intent.getData().toString();
            loadInput(first.url);
        } else if (state != null && webView.restoreState(state) != null) {
            first.url = webView.getUrl() == null ? HOME_URL : webView.getUrl();
            currentPageUrl = first.url;
            currentPageHost = hostOf(first.url);
        } else if (!isHomeUrl(first.url)) {
            currentPageUrl = first.url;
            currentPageHost = hostOf(first.url);
            applySiteSettings(webView, currentPageHost);
            webView.loadUrl(first.url);
        } else {
            showHome();
        }
        updateChrome();
        scheduleWebViewPrewarm();
        scheduleDeferredStartupWork();
        showCommunityNoticeOnFirstLaunch(state);
    }

    /** Heavy rule parsing and subscription I/O must not compete with the first frame. */
    private void scheduleDeferredStartupWork() {
        if (deferredStartupComplete || deferredStartupPending) return;
        long delay = MODE_POWER_SAVE.equals(performanceMode) ? 2400L : 1200L;
        deferredStartupPending = true;
        uiHandler.postDelayed(deferredStartup, delay);
    }

    private static ExecutorService newIdleExecutor(int threads) {
        int count = Math.max(1, threads);
        int queueCapacity = count == 1 ? 32 : 96;
        ThreadPoolExecutor executor = new ThreadPoolExecutor(count, count, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(queueCapacity), new ThreadPoolExecutor.CallerRunsPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    /**
     * 2.1.5 wrote its editor example into real preferences before the user
     * pressed Save. Repair only the exact inconsistent legacy state once.
     */
    private void migrateLegacyLogoExample() {
        final String migrationKey = "home_logo_example_migrated_v216";
        if (prefs.getBoolean(migrationKey, false)) return;
        String code = LogoMarkup.clean(prefs.getString("home_logo_code", ""));
        String title = HomePageConfig.cleanTitle(prefs.getString("home_title", HomePageConfig.DEFAULT_TITLE));
        SharedPreferences.Editor editor = prefs.edit().putBoolean(migrationKey, true);
        if (LogoMarkup.LEGACY_GRADIENT_EXAMPLE.equals(code) && HomePageConfig.DEFAULT_TITLE.equals(title)) {
            editor.remove("home_logo_code");
            if ("custom".equals(prefs.getString("home_logo_style", "median")))
                editor.putString("home_logo_style", "median");
        }
        editor.apply();
    }

    private void buildUi() {
        rootFrame = new FrameLayout(this);
        rootFrame.setBackgroundColor(Color.BLACK);

        browserChrome = new LinearLayout(this);
        browserChrome.setOrientation(LinearLayout.VERTICAL);
        browserChrome.setBackgroundColor(WHITE);
        rootFrame.addView(browserChrome, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(10), dp(7), dp(10), dp(7));
        topBar.setBackgroundColor(WHITE);

        addressPill = new LinearLayout(this);
        addressPill.setOrientation(LinearLayout.HORIZONTAL);
        addressPill.setGravity(Gravity.CENTER_VERTICAL);
        addressPill.setPadding(dp(3), 0, dp(3), 0);
        addressPill.setBackground(roundRect(SURFACE, 22));

        shieldButton = iconButton(BrowserIconView.SHIELD, "保护与脚本");
        addressPill.addView(shieldButton, new LinearLayout.LayoutParams(dp(40), dp(42)));

        addressBar = new EditText(this);
        addressBar.setSingleLine(true);
        addressBar.setTextSize(15f);
        addressBar.setTextColor(TEXT);
        addressBar.setHintTextColor(Color.rgb(128, 134, 139));
        addressBar.setHint("搜索或输入网址");
        addressBar.setSelectAllOnFocus(true);
        addressBar.setImeOptions(EditorInfo.IME_ACTION_GO);
        addressBar.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addressBar.setBackgroundColor(Color.TRANSPARENT);
        addressBar.setPadding(dp(3), 0, dp(3), 0);
        addressPill.addView(addressBar, new LinearLayout.LayoutParams(0, dp(44), 1f));

        refreshButton = iconButton(BrowserIconView.RELOAD, "刷新");
        addressPill.addView(refreshButton, new LinearLayout.LayoutParams(dp(40), dp(42)));
        topBar.addView(addressPill, new LinearLayout.LayoutParams(0, dp(44), 1f));

        browserChrome.addView(topBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(BLUE));
        progressBar.setVisibility(View.GONE);
        browserChrome.addView(progressBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)));

        webContainer = new FrameLayout(this);
        webView = new WebView(this);
        styleWebView(webView);
        webContainer.addView(webView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        browserChrome.addView(webContainer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER);
        bottomBar.setPadding(dp(8), dp(2), dp(8), dp(2));
        bottomBar.setBackgroundColor(WHITE);
        bottomBar.setElevation(dp(6));

        backButton = iconButton(BrowserIconView.BACK, "后退");
        forwardButton = iconButton(BrowserIconView.FORWARD, "前进");
        BrowserIconView home = iconButton(BrowserIconView.HOME, "主页");
        tabButton = iconButton(BrowserIconView.TABS, "标签页");
        BrowserIconView menu = iconButton(BrowserIconView.MENU, "菜单");
        BrowserIconView[] bottomButtons = new BrowserIconView[] { backButton, forwardButton, home, tabButton, menu };
        for (BrowserIconView button : bottomButtons) bottomBar.addView(button, new LinearLayout.LayoutParams(0, dp(52), 1f));
        browserChrome.addView(bottomBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        setContentView(rootFrame);
        installSystemBarInsets(rootFrame);

        addressBar.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEARCH ||
                        (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    loadInput(addressBar.getText().toString());
                    hideKeyboard();
                    addressBar.clearFocus();
                    return true;
                }
                return false;
            }
        });
        addressBar.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean hasFocus) {
                Motion.focusPill(addressPill, hasFocus, reduceMotion());
                if (hasFocus) {
                    String url = currentPageUrl;
                    if (isHomeUrl(url)) addressBar.setText("");
                    else if (url != null && !url.contentEquals(addressBar.getText())) addressBar.setText(url);
                    addressBar.selectAll();
                } else {
                    updateAddressBar();
                }
            }
        });
        refreshButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (isHomeUrl(currentPageUrl)) showHome(); else webView.reload(); }
        });
        refreshButton.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { webView.stopLoading(); toast("已停止加载"); return true; }
        });
        shieldButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showProtectionPanel(); }
        });
        backButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (webView.canGoBack()) webView.goBack(); }
        });
        forwardButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (webView.canGoForward()) webView.goForward(); }
        });
        home.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openConfiguredHome(); }
        });
        home.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { newTab(); return true; }
        });
        tabButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showTabs(); }
        });
        tabButton.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { newTab(); return true; }
        });
        menu.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showMainMenu(); }
        });
        applyChromeTheme();
    }


    private void installSystemBarInsets(final View target) {
        if (target == null || Build.VERSION.SDK_INT < 30) return;
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

    private BrowserIconView iconButton(int icon, String description) {
        BrowserIconView button = new BrowserIconView(this, icon);
        button.setContentDescription(description);
        button.setBackgroundResource(selectableBorderless());
        return button;
    }

    private WebView createConfiguredWebView() {
        WebView view = new WebView(this);
        styleWebView(view);
        configureWebView(view);
        pageHosts.put(view, "");
        return view;
    }

    private void styleWebView(WebView view) {
        view.setBackgroundColor(nightMode ? Color.rgb(17, 19, 21) : WHITE);
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        view.setClickable(true);
        view.setVerticalScrollBarEnabled(false);
        view.setHorizontalScrollBarEnabled(false);
        view.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        view.setDrawingCacheEnabled(false);
        resetWebViewTransform(view);
    }

    /** WebView owns its input surface. Never animate or leave transforms on that surface. */
    private void resetWebViewTransform(WebView view) {
        if (view == null) return;
        view.animate().cancel();
        view.setAlpha(1f);
        view.setTranslationX(0f);
        view.setTranslationY(0f);
        view.setScaleX(1f);
        view.setScaleY(1f);
    }

    private void scheduleWebViewPrewarm() {
        if (prewarmPending || !activityResumed || deviceProfile == null) return;
        if (!deviceProfile.allowPrewarmedWebView(performanceMode)) return;
        long now = SystemClock.uptimeMillis();
        if (lastMemoryTrimAt > 0 && now - lastMemoryTrimAt < 5 * 60 * 1000L) return;
        long delay = MODE_PERFORMANCE.equals(performanceMode) ? 700L : 1200L;
        if (!MODE_PERFORMANCE.equals(performanceMode) && !pageCommitted && !pageFinished) delay = 1800L;
        prewarmPending = true;
        uiHandler.postDelayed(webViewPrewarmer, delay);
    }

    private void ensurePrewarmedWebView() {
        if (isFinishing() || webContainer == null || spareWebView != null || deviceProfile == null) return;
        if (!deviceProfile.allowPrewarmedWebView(performanceMode)) return;
        if (lastMemoryTrimAt > 0 && SystemClock.uptimeMillis() - lastMemoryTrimAt < 5 * 60 * 1000L) return;
        if (liveWebViewCount() >= deviceProfile.hotWebViewLimit(performanceMode)) return;
        spareWebView = createConfiguredWebView();
        spareWebView.loadUrl("about:blank");
        spareWebView.onPause();
    }

    private WebView acquireWebView() {
        WebView result = spareWebView;
        spareWebView = null;
        if (result == null) result = createConfiguredWebView();
        result.onResume();
        return result;
    }

    private int liveWebViewCount() {
        int count = spareWebView == null ? 0 : 1;
        for (BrowserTab tab : tabs) if (tab.liveView != null) count++;
        return count;
    }

    private BrowserTab tabForView(WebView view) {
        if (view == null) return null;
        for (BrowserTab tab : tabs) if (tab.liveView == view) return tab;
        return null;
    }

    private String pageHostFor(WebView view) {
        if (view == webView) return currentPageHost;
        String value = pageHosts.get(view);
        return value == null ? "" : value;
    }

    private void updateTabForView(WebView view, String url, String title) {
        BrowserTab tab = tabForView(view);
        if (tab == null) return;
        if (url != null) tab.url = url;
        if (title != null && title.length() > 0) tab.title = title;
    }


    private interface SheetHandler {
        void onItem(int index);
    }

    private void showActionSheet(String title, String subtitle, String[] items, int[] icons, final SheetHandler handler) {
        dismissOverlay();
        final int sheetSurface = nightMode ? Color.rgb(35, 38, 42) : WHITE;
        final int sheetText = nightMode ? Color.rgb(232, 234, 237) : TEXT;
        final int sheetMuted = nightMode ? Color.rgb(154, 160, 166) : MUTED;
        final FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(Color.argb(104, 0, 0, 0));
        overlay.setClickable(true);
        overlay.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dismissOverlay(); }
        });

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(roundRect(sheetSurface, 24));
        panel.setPadding(dp(10), dp(8), dp(10), dp(12));
        panel.setClickable(true);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(4), dp(4), dp(4));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextColor(sheetText);
        heading.setTextSize(19f);
        heading.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        titles.addView(heading);
        if (subtitle != null && subtitle.length() > 0) {
            TextView sub = new TextView(this);
            sub.setText(subtitle);
            sub.setTextColor(sheetMuted);
            sub.setTextSize(12.5f);
            sub.setPadding(0, dp(3), 0, 0);
            titles.addView(sub);
        }
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        BrowserIconView close = iconButton(BrowserIconView.CLOSE, "关闭");
        close.setTintColor(sheetText);
        header.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));
        panel.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(4), 0, dp(4));
        scroll.addView(list);
        int rowsHeight = 0;
        for (int i = 0; i < items.length; i++) {
            final int index = i;
            String item = items[i] == null ? "" : items[i];
            int detailBreak = item.indexOf('\n');
            String primaryText = detailBreak < 0 ? item : item.substring(0, detailBreak);
            String detailText = detailBreak < 0 ? "" : item.substring(detailBreak + 1).trim();
            int rowHeight = detailText.length() == 0 ? 54 : 68;
            rowsHeight += rowHeight;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), 0, dp(8), 0);
            row.setBackgroundResource(selectableBounded());
            row.setClickable(true);
            BrowserIconView icon = iconButton(icons != null && i < icons.length ? icons[i] : BrowserIconView.MENU, primaryText);
            icon.setClickable(false);
            icon.setTintColor(sheetText);
            row.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(rowHeight - 4)));
            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.setGravity(Gravity.CENTER_VERTICAL);
            labels.setPadding(dp(7), 0, dp(4), 0);
            TextView label = new TextView(this);
            label.setText(primaryText);
            label.setTextColor(sheetText);
            label.setTextSize(15f);
            label.setSingleLine(true);
            labels.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            if (detailText.length() > 0) {
                TextView detail = new TextView(this);
                detail.setText(detailText);
                detail.setTextColor(sheetMuted);
                detail.setTextSize(12.5f);
                detail.setSingleLine(true);
                detail.setPadding(0, dp(2), 0, 0);
                labels.addView(detail, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            row.addView(labels, new LinearLayout.LayoutParams(0, dp(rowHeight), 1f));
            TextView chevron = new TextView(this);
            chevron.setText("›");
            chevron.setTextColor(sheetMuted);
            chevron.setTextSize(27f);
            chevron.setGravity(Gravity.CENTER);
            chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(chevron, new LinearLayout.LayoutParams(dp(28), dp(rowHeight)));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    dismissOverlay();
                    handler.onItem(index);
                }
            });
            list.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(rowHeight)));
        }
        panel.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        int maxHeight = Math.round(getResources().getDisplayMetrics().heightPixels * .82f);
        int desiredHeight = dp(86 + rowsHeight);
        int panelHeight = Math.min(maxHeight, desiredHeight);
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, panelHeight, Gravity.BOTTOM);
        panelParams.setMargins(dp(8), 0, dp(8), dp(8));
        overlay.addView(panel, panelParams);
        rootFrame.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        activeOverlay = overlay;
        activeOverlayPanel = panel;
        activeOverlaySheet = true;
        Motion.showSheet(overlay, panel, reduceMotion());
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dismissOverlay(); }
        });
    }

    private void dismissOverlay() {
        if (activeOverlay == null) return;
        final View removing = activeOverlay;
        final View panel = activeOverlayPanel;
        final boolean sheet = activeOverlaySheet;
        activeOverlay = null;
        activeOverlayPanel = null;
        Motion.hideOverlay(removing, panel, sheet, reduceMotion(), new Runnable() {
            @Override public void run() {
                if (removing.getParent() instanceof ViewGroup) ((ViewGroup) removing.getParent()).removeView(removing);
            }
        });
    }

    private void installDocumentStartUserScripts(WebView target) {
        removeDocumentStartUserScripts(target);
        if (target == null || scriptStore == null || !scriptStore.hasEnabledScripts()) return;
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return;
        String token = UrlCleaner.randomToken();
        List<String> sources = scriptStore.buildDocumentStartScripts(token);
        if (sources.size() == 0) return;
        ArrayList<ScriptHandler> handlers = new ArrayList<ScriptHandler>();
        try {
            for (String source : sources) {
                handlers.add(WebViewCompat.addDocumentStartJavaScript(target, source, Collections.singleton("*")));
            }
            scriptBridgeTokens.put(target, token);
            scriptHandlers.put(target, handlers);
            documentStartScriptViews.add(target);
        } catch (RuntimeException error) {
            for (ScriptHandler handler : handlers) try { handler.remove(); } catch (RuntimeException ignored) {}
            scriptBridgeTokens.remove(target);
            scriptHandlers.remove(target);
            documentStartScriptViews.remove(target);
        }
    }

    private void removeDocumentStartUserScripts(WebView target) {
        if (target == null) return;
        List<ScriptHandler> handlers = scriptHandlers.remove(target);
        if (handlers != null) for (ScriptHandler handler : handlers) try { handler.remove(); } catch (RuntimeException ignored) {}
        cancelScriptRequests(target);
        scriptBridgeTokens.remove(target);
        documentStartScriptViews.remove(target);
    }

    private void refreshUserScriptRegistrations(boolean reloadActive) {
        ArrayList<WebView> live = new ArrayList<WebView>();
        for (BrowserTab tab : tabs) if (tab.liveView != null && !live.contains(tab.liveView)) live.add(tab.liveView);
        if (spareWebView != null && !live.contains(spareWebView)) live.add(spareWebView);
        for (WebView view : live) installDocumentStartUserScripts(view);
        if (reloadActive && webView != null && isNetworkPage(webView.getUrl())) {
            webView.reload();
            toast("用户脚本权限已更新，当前页面正在重新加载");
        }
    }

    private void cancelScriptRequests(WebView target) {
        String token = scriptBridgeTokens.get(target);
        if (token == null || token.length() == 0) return;
        String prefix = token + "|";
        for (Map.Entry<String, HttpURLConnection> entry : new ArrayList<Map.Entry<String, HttpURLConnection>>(scriptConnections.entrySet())) {
            if (!entry.getKey().startsWith(prefix)) continue;
            HttpURLConnection connection = scriptConnections.remove(entry.getKey());
            if (connection != null) try { connection.disconnect(); } catch (RuntimeException ignored) {}
        }
    }

    private void configureWebView(final WebView target) {
        WebSettings settings = target.getSettings();
        WebViewPolicy.applySecureDefaults(settings, WebSettings.LOAD_DEFAULT);
        settings.setLoadsImagesAutomatically(true);
        settings.setLoadWithOverviewMode(false);
        settings.setUseWideViewPort(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        // target=_blank and user-gesture popups are promoted to real browser tabs.
        settings.setSupportMultipleWindows(true);
        settings.setTextZoom(100);
        String mobileUserAgent = WebViewPolicy.mobileUserAgent(settings.getUserAgentString());
        mobileUserAgents.put(target, mobileUserAgent);
        if (!mobileUserAgent.equals(settings.getUserAgentString())) settings.setUserAgentString(mobileUserAgent);
        applyDesktopMode(target);
        applyPerformanceMode(target);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(target, acceptThirdPartyCookies);
        installDocumentStartUserScripts(target);

        EdgeNavigationController.attach(target, new EdgeNavigationController.Callback() {
            @Override public boolean canGoBack() { return target.canGoBack(); }
            @Override public boolean canGoForward() { return target.canGoForward(); }
            @Override public void goBack() { target.goBack(); }
            @Override public void goForward() { target.goForward(); }
        });
        target.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View ignored) { return handleWebViewLongPress(target); }
        });

        if (Build.VERSION.SDK_INT >= 29) {
            RendererWatchdog.attach(target, getMainExecutor(), new RendererWatchdog.Callback() {
                @Override public void onUnresponsive(final WebView view, final RendererWatchdog.Terminator terminateRenderer) {
                    if (view == null || !unresponsiveWebViews.add(view)) return;
                    final String stuckUrl = view.getUrl();
                    uiHandler.postDelayed(new Runnable() {
                        @Override public void run() {
                            if (!unresponsiveWebViews.remove(view) || isFinishing()) return;
                            int isolated = scriptStore == null ? 0 : scriptStore.quarantineMatching(stuckUrl, "页面渲染器持续无响应");
                            if (!terminateRenderer.terminate()) {
                                try { view.stopLoading(); } catch (RuntimeException ignored) {}
                            }
                            toast(isolated > 0 ? "已隔离 " + isolated + " 个可疑脚本并恢复页面" : "页面无响应，正在重启渲染器");
                        }
                    }, 4500L);
                }

                @Override public void onResponsive(WebView view) {
                    if (view != null) unresponsiveWebViews.remove(view);
                }
            });
        }

        target.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (view != webView) return false;
                return handleNavigation(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (view != webView) return false;
                if (request == null) return false;
                // Subframe navigation must never change the active page host or its
                // WebSettings. Doing so breaks SPA controls while scrolling still works.
                if (!request.isForMainFrame()) return false;
                String url = request.getUrl().toString();
                if (url.startsWith("https://") || url.startsWith("http://")) applySiteSettings(view, hostOf(url));
                return handleNavigation(url);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                return interceptRequest(view, url);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (request == null) return null;
                recordNetworkRequest(view, request.getUrl() == null ? "" : request.getUrl().toString(),
                        request.isForMainFrame(), request.getMethod());
                return interceptRequest(view, request);
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                McpController.get().recordRunLog("info", "page", "start " + (url == null ? "" : url));
                unresponsiveWebViews.remove(view);
                cancelScriptRequests(view);
                if (!isHomeUrl(url)) {
                    trustedHomeViews.remove(view);
                    customHomeViews.remove(view);
                }
                boolean offline = isOfflineUrl(url);
                view.getSettings().setAllowContentAccess(offline);
                view.getSettings().setBlockNetworkLoads(offline);
                if (offline) view.getSettings().setJavaScriptEnabled(false);
                else applySiteSettings(view, hostOf(url));
                updateTabForView(view, url, view.getTitle());
                cosmeticInjected.remove(view);
                if (url != null) {
                    String startedHost = hostOf(url);
                    pageHosts.put(view, startedHost);
                    adBlockActiveByView.put(view, Boolean.valueOf(isAdBlockActiveForHost(startedHost)));
                }
                if (view != webView) return;
                if (url != null) {
                    currentPageUrl = url;
                    currentPageHost = hostOf(url);
                    mediaSniffer.beginPage(url);
                    if (isAdBlockActiveForHost(currentPageHost) && adBlock.requiresEarlyCosmetic(currentPageHost)) {
                        scheduleCosmeticInjection(view, currentPageHost);
                    }
                }
                updateMediaNetworkBoost();
                blockedAtPageStart = adBlock.getBlockedCount();
                if (view == webView && refreshButton != null) {
                    refreshButton.animate().cancel();
                    refreshButton.animate().rotationBy(180f).setDuration(reduceMotion() ? 100L : 220L).start();
                }
                navigationSequence++;
                pageCommitted = false;
                pageFinished = false;
                preparedInjection = null;
                schedulePageEnhancements(url, navigationSequence);
                requestChromeUpdate();
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                super.onPageCommitVisible(view, url);
                if (view != webView) return;
                pageCommitted = true;
                injectPreparedStart(navigationSequence);
                scheduleWebViewPrewarm();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                McpController.get().recordRunLog("info", "page", "finished " + (url == null ? "" : url));
                if (isHomeUrl(url)) verifyTrustedHome(view);
                updateTabForView(view, url, view.getTitle());
                if (url != null) {
                    String finishedHost = hostOf(url);
                    pageHosts.put(view, finishedHost);
                    adBlockActiveByView.put(view, Boolean.valueOf(isAdBlockActiveForHost(finishedHost)));
                }
                if (view != webView) return;
                if (url != null) {
                    currentPageUrl = url;
                    currentPageHost = hostOf(url);
                }
                updateMediaNetworkBoost();
                pageFinished = true;
                pageCommitted = true;
                if (refreshButton != null) refreshButton.animate().rotation(0f).setDuration(120L).start();
                injectPreparedStart(navigationSequence);
                injectPreparedEnd(navigationSequence);
                injectMcpHooks(view);
                if (dataStore != null && !isHomeUrl(url)) dataStore.recordVisit(view.getTitle(), url);
                updateCurrentTab(url, view.getTitle());
                persistSession();
                requestChromeUpdate();
                scheduleWebViewPrewarm();
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                super.doUpdateVisitedHistory(view, url, isReload);
                updateTabForView(view, url, view.getTitle());
                if (url != null) pageHosts.put(view, hostOf(url));
                if (view != webView) return;
                if (url != null) {
                    currentPageUrl = url;
                    currentPageHost = hostOf(url);
                }
                updateMediaNetworkBoost();
                updateCurrentTab(url, view.getTitle());
                requestChromeUpdate();
            }

            public boolean onRenderProcessGone(final WebView view, android.webkit.RenderProcessGoneDetail detail) {
                if (!rendererRecoveryPending) {
                    rendererRecoveryPending = true;
                    uiHandler.post(new Runnable() {
                        @Override public void run() { recoverFromRendererLoss(); }
                    });
                }
                return true;
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                if (view == webView) {
                    toast("证书验证失败，已停止加载");
                    String failed = error == null ? currentPageUrl : error.getUrl();
                    if (hostOf(failed).equals(currentPageHost)) {
                        maybeOfferCompatibilityMode(currentPageUrl, "主页面证书或 HTTPS 加载失败");
                    }
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                McpController.get().recordRunLog("error", "page", "load error " + (request == null || request.getUrl() == null ? "" : request.getUrl().toString())
                        + " code=" + (error == null ? "?" : error.getErrorCode()));
                if (view == webView && request != null && request.isForMainFrame() &&
                        error != null && compatibilityRelevantError(error.getErrorCode())) {
                    String failed = request.getUrl() == null ? currentPageUrl : request.getUrl().toString();
                    maybeOfferCompatibilityMode(failed, "主页面加载失败（" + error.getErrorCode() + "）");
                }
            }

            @Override
            public void onReceivedHttpAuthRequest(WebView view, final android.webkit.HttpAuthHandler handler, String host, String realm) {
                if (view != webView || handler == null) { if (handler != null) handler.cancel(); return; }
                final EditText username = new EditText(MainActivity.this);
                username.setHint("用户名");
                username.setSingleLine(true);
                final EditText password = new EditText(MainActivity.this);
                password.setHint("密码");
                password.setSingleLine(true);
                password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                LinearLayout fields = new LinearLayout(MainActivity.this);
                fields.setOrientation(LinearLayout.VERTICAL);
                fields.setPadding(dp(18), 0, dp(18), 0);
                fields.addView(username);
                fields.addView(password);
                AlertDialog dialog = new AlertDialog.Builder(MainActivity.this).setTitle("网站身份验证")
                        .setMessage(host + (realm == null || realm.length() == 0 ? "" : " · " + realm)).setView(fields)
                        .setPositiveButton("登录", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dialog, int which) { handler.proceed(username.getText().toString(), password.getText().toString()); }
                        }).setNegativeButton("取消", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dialog, int which) { handler.cancel(); }
                        }).setOnCancelListener(new DialogInterface.OnCancelListener() {
                            @Override public void onCancel(DialogInterface dialog) { handler.cancel(); }
                        }).create();
                secureDialog(dialog);
                dialog.show();
            }
        });

        target.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage message) {
                if (message != null) {
                    try {
                        String raw = message.message();
                        if (raw != null && raw.startsWith("[MDEVT]")) {
                            handleToolEvent(raw.substring(7));
                        }
                        JSONObject entry = new JSONObject();
                        android.webkit.ConsoleMessage.MessageLevel level = message.messageLevel();
                        entry.put("type", level == android.webkit.ConsoleMessage.MessageLevel.ERROR ? "error"
                                : level == android.webkit.ConsoleMessage.MessageLevel.WARNING ? "warning" : "log");
                        entry.put("message", message.message() == null ? "" : message.message());
                        entry.put("line", message.lineNumber());
                        entry.put("source", message.sourceId() == null ? "" : message.sourceId());
                        entry.put("time", System.currentTimeMillis());
                        McpController.get().recordConsole(entry);
                    } catch (Exception ignored) { }
                }
                return super.onConsoleMessage(message);
            }
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (view == webView) scheduleProgressUpdate(newProgress);
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                updateTabForView(view, view.getUrl(), title);
                if (view == webView) requestChromeUpdate();
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, JsPromptResult result) {
                if (handleScriptBridgePrompt(view, url, message, result)) return true;
                return super.onJsPrompt(view, url, message, defaultValue, result);
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (target != webView) { callback.onCustomViewHidden(); return; }
                enterFullscreen(view, callback);
            }

            @Override
            public void onHideCustomView() {
                if (target == webView) exitFullscreen();
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                handleWebPermissionRequest(target, request);
            }

            @Override
            public void onPermissionRequestCanceled(PermissionRequest request) {
                if (request != null && request == pendingPermissionRequest) {
                    pendingPermissionRequest = null;
                    pendingWebPermissionResources = null;
                    pendingPermissionView = null;
                    pendingPermissionOrigin = null;
                }
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                handleGeolocationRequest(target, origin, callback);
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                SiteSettingsStore.SiteSettings site = siteSettingsStore.forHost(pageHostFor(view));
                if (site.popups == SiteSettingsStore.BLOCK || (!isUserGesture && site.popups != SiteSettingsStore.ALLOW) ||
                        resultMsg == null || !(resultMsg.obj instanceof WebView.WebViewTransport) || tabs.size() >= MAX_TABS) return false;
                BrowserTab tab = new BrowserTab();
                WebView popup = createConfiguredWebView();
                tab.liveView = popup;
                tab.lastActiveAt = SystemClock.uptimeMillis();
                tabs.add(tab);
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(popup);
                resultMsg.sendToTarget();
                activateTab(tabs.size() - 1);
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (view != webView) { callback.onReceiveValue(null); return true; }
                if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(null);
                fileChooserCallback = callback;
                Intent intent;
                try {
                    intent = params.createIntent();
                } catch (Exception e) {
                    intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                }
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                    return true;
                } catch (Exception e) {
                    fileChooserCallback = null;
                    toast("没有可用的文件选择器");
                    return false;
                }
            }
        });

        target.setDownloadListener(new DownloadListener() {
            @Override public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                                  String mimetype, long contentLength) {
                if (looksLikeUserScript(url)) {
                    installScriptFromUrl(url);
                    return;
                }
                enqueueDownload(target, url, userAgent, contentDisposition, mimetype, contentLength);
            }
        });

    }

    private boolean handleWebViewLongPress(final WebView source) {
        WebView.HitTestResult hit = source == null ? null : source.getHitTestResult();
        if (hit == null) return false;
        int type = hit.getType();
        final boolean image = type == WebView.HitTestResult.IMAGE_TYPE || type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE;
        boolean link = type == WebView.HitTestResult.SRC_ANCHOR_TYPE || type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE;
        final String url = hit.getExtra();
        if ((!image && !link) || url == null || (!url.startsWith("https://") && !url.startsWith("http://"))) return false;
        String[] items = new String[] { "在新标签页打开", "在后台标签页打开", "复制地址", "分享地址", image ? "下载图片" : "下载链接" };
        int[] icons = new int[] { BrowserIconView.PLUS, BrowserIconView.TABS, BrowserIconView.PLUS, BrowserIconView.SHARE, BrowserIconView.STORAGE };
        showActionSheet(image ? "图片与链接" : "链接操作", hostOf(url), items, icons, new SheetHandler() {
            @Override public void onItem(int which) {
                if (which == 0) openUrlInNewTab(url, true);
                else if (which == 1) openUrlInNewTab(url, false);
                else if (which == 2) {
                    android.content.ClipboardManager manager = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (manager != null) manager.setPrimaryClip(android.content.ClipData.newPlainText("链接", url));
                    toast("地址已复制");
                } else if (which == 3) {
                    Intent share = new Intent(Intent.ACTION_SEND);
                    share.setType("text/plain");
                    share.putExtra(Intent.EXTRA_TEXT, url);
                    try { startActivity(Intent.createChooser(share, "分享链接")); } catch (Exception e) { toast("没有可用的分享应用"); }
                } else {
                    String ua = source.getSettings().getUserAgentString();
                    enqueueDownload(source, url, ua, null, image ? "image/*" : "application/octet-stream");
                }
            }
        });
        return true;
    }

    private void openUrlInNewTab(String url, boolean foreground) {
        if (url == null || (!url.startsWith("https://") && !url.startsWith("http://"))) return;
        if (tabs.size() >= MAX_TABS) { toast("最多允许 " + MAX_TABS + " 个标签页"); return; }
        BrowserTab tab = new BrowserTab();
        tab.url = url;
        String host = hostOf(url);
        tab.title = host.length() == 0 ? "新标签页" : host;
        tabs.add(tab);
        if (foreground) activateTab(tabs.size() - 1);
        else {
            renderedTabCount = null;
            persistSession();
            requestChromeUpdate();
            toast("已在后台标签页打开");
        }
        scheduleWebViewPrewarm();
    }

    private void enqueueDownload(WebView source, String url, String userAgent,
                                 String contentDisposition, String mimetype) {
        enqueueDownload(source, url, userAgent, contentDisposition, mimetype, 0L);
    }

    private void enqueueDownload(WebView source, String url, String userAgent,
                                 String contentDisposition, String mimetype, long expectedTotalBytes) {
        enqueueDownloadAdvanced(url, userAgent, contentDisposition, mimetype, "",
                downloadContextHeaders(source, url), false, expectedTotalBytes);
    }

    private boolean enqueueDownloadAdvanced(String url, String userAgent, String contentDisposition,
                                            String mimetype, String preferredName, Map<String, String> extraHeaders) {
        return enqueueDownloadAdvanced(url, userAgent, contentDisposition, mimetype, preferredName, extraHeaders, false);
    }

    private boolean enqueueDownloadAdvanced(String url, String userAgent, String contentDisposition,
                                            String mimetype, String preferredName, Map<String, String> extraHeaders,
                                            boolean publicOnly) {
        return enqueueDownloadAdvanced(url, userAgent, contentDisposition, mimetype, preferredName,
                extraHeaders, publicOnly, 0L);
    }

    private boolean enqueueDownloadAdvanced(String url, String userAgent, String contentDisposition,
                                            String mimetype, String preferredName, Map<String, String> extraHeaders,
                                            boolean publicOnly, long expectedTotalBytes) {
        try {
            url = NetworkSecurity.parseHttpUrl(url).toString();
        } catch (Exception invalidUrl) {
            toast("只允许下载有效的 HTTP(S) 资源");
            return false;
        }
        DownloadStore.Item duplicate = services.downloads().findBlockingDuplicate(url, 15000L);
        if (duplicate != null) {
            if (DownloadStore.STATUS_FAILED.equals(duplicate.status))
                toast("相同地址刚刚失败，请在下载中心重试");
            else if (DownloadStore.STATUS_PAUSED.equals(duplicate.status))
                toast("相同任务已暂停，请在下载中心继续");
            else toast("相同资源已经在下载中");
            return false;
        }
        String filename = uniqueDownloadName(DownloadFileTypes.resolveName(
                url, contentDisposition, mimetype, "", preferredName));
        String resolvedMime = DownloadFileTypes.resolveMime(filename, mimetype, "");
        try {
            long expected = Math.max(0L, expectedTotalBytes);
            enqueueAdaptiveFallback(url, userAgent, filename, resolvedMime, extraHeaders, publicOnly, expected);
            toast("开始下载：" + filename + (expected > 0L ? " · " + humanBytes(expected) : ""));
            return true;
        } catch (Exception failure) {
            toast("下载启动失败：" + safeMessage(failure));
        }
        return false;
    }

    private void enqueueAdaptiveFallback(String url, String userAgent, String filename, String mime,
                                         Map<String, String> extraHeaders, boolean publicOnly,
                                         long expectedTotalBytes) throws Exception {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 409);
        boolean wifiOnly = prefs.getBoolean("download_wifi_only", false);
        boolean allowRoaming = prefs.getBoolean("download_allow_roaming", false);
        boolean chargingOnly = prefs.getBoolean("download_charging_only", false);
        String persistedHeaders = downloadHeadersJson(extraHeaders);
        String downloadMode = DownloadMemoryPolicy.MODE_STANDARD;
        long id = services.downloads().addAdaptive(url, filename, mime, downloadMode,
                userAgent == null ? "" : userAgent, persistedHeaders,
                wifiOnly, allowRoaming, chargingOnly, publicOnly, expectedTotalBytes);
        try {
            Intent service = new Intent(this, AdaptiveDownloadService.class);
            service.setAction(AdaptiveDownloadService.ACTION_DOWNLOAD);
            service.putExtra(AdaptiveDownloadService.EXTRA_ID, id);
            service.putExtra(AdaptiveDownloadService.EXTRA_URL, url);
            service.putExtra(AdaptiveDownloadService.EXTRA_NAME, filename);
            service.putExtra(AdaptiveDownloadService.EXTRA_MIME, mime);
            service.putExtra(AdaptiveDownloadService.EXTRA_USER_AGENT, userAgent == null ? "" : userAgent);
            String cookie = CookieManager.getInstance().getCookie(url);
            service.putExtra(AdaptiveDownloadService.EXTRA_COOKIE, cookie == null ? "" : cookie);
            service.putExtra(AdaptiveDownloadService.EXTRA_HEADERS, persistedHeaders);
            service.putExtra(AdaptiveDownloadService.EXTRA_MODE, downloadMode);
            service.putExtra(AdaptiveDownloadService.EXTRA_WIFI_ONLY, wifiOnly);
            service.putExtra(AdaptiveDownloadService.EXTRA_ALLOW_ROAMING, allowRoaming);
            service.putExtra(AdaptiveDownloadService.EXTRA_CHARGING_ONLY, chargingOnly);
            service.putExtra(AdaptiveDownloadService.EXTRA_PUBLIC_ONLY, publicOnly);
            service.putExtra(AdaptiveDownloadService.EXTRA_TOTAL_BYTES, expectedTotalBytes);
            startForegroundService(service);
        } catch (Exception error) {
            services.downloads().remove(id);
            throw error;
        }
    }

    private Map<String, String> downloadContextHeaders(WebView source, String downloadUrl) {
        HashMap<String, String> headers = new HashMap<String, String>();
        String pageUrl = source == null || source.getUrl() == null ? "" : source.getUrl();
        try {
            URL page = NetworkSecurity.parseHttpUrl(pageUrl);
            URL download = NetworkSecurity.parseHttpUrl(downloadUrl);
            if (NetworkSecurity.sameOrigin(page, download)) headers.put("Referer", page.toString());
            else if (!"https".equalsIgnoreCase(page.getProtocol()) || "https".equalsIgnoreCase(download.getProtocol())) {
                String origin = page.getProtocol() + "://" + page.getHost();
                int port = page.getPort();
                if (port >= 0 && port != page.getDefaultPort()) origin += ":" + port;
                headers.put("Referer", origin + "/");
            }
        } catch (Exception ignored) {}
        return headers;
    }

    private String uniqueDownloadName(String requested) {
        String name = DownloadFileTypes.sanitize(requested);
        if (name.length() == 0) name = "download.bin";
        HashSet<String> used = new HashSet<String>();
        for (DownloadStore.Item item : services.downloads().getAll()) used.add(item.filename.toLowerCase(Locale.US));
        if (!used.contains(name.toLowerCase(Locale.US))) return name;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i <= 999; i++) {
            String candidate = base + " (" + i + ")" + extension;
            if (!used.contains(candidate.toLowerCase(Locale.US))) return candidate;
        }
        return base + '-' + System.currentTimeMillis() + extension;
    }

    private String downloadHeadersJson(Map<String, String> headers) {
        JSONObject object = new JSONObject();
        if (headers == null) return object.toString();
        for (Map.Entry<String, String> header : headers.entrySet()) {
            String name = header.getKey() == null ? "" : header.getKey().trim();
            String value = header.getValue() == null ? "" : header.getValue();
            if (!NetworkSecurity.validHeader(name, value) || NetworkSecurity.isForbiddenRequestHeader(name) ||
                    NetworkSecurity.isCredentialHeader(name) || "user-agent".equalsIgnoreCase(name)) continue;
            try { object.put(name, value); } catch (Exception ignored) {}
        }
        return object.toString();
    }

    private String downloadModeLabel(String mode) {
        if (MODE_PERFORMANCE.equals(mode)) return "快速兼容";
        if (MODE_POWER_SAVE.equals(mode)) return "低功耗";
        return "标准";
    }

    private boolean handleScriptBridgePrompt(final WebView source, String pageUrl, String message, JsPromptResult result) {
        final String prefix = "__MEDIAN_BRIDGE__";
        if (message == null || !message.startsWith(prefix)) return false;
        String response;
        try {
            if (message.length() > 512 * 1024) throw new IllegalArgumentException("request too large");
            JSONObject request = new JSONObject(message.substring(prefix.length()));
            String token = request.optString("t", "");
            final String scriptId = request.optString("s", "");
            String action = request.optString("a", "");
            JSONObject args = request.optJSONObject("p");
            if (args == null) args = new JSONObject();
            String currentUrl = source == null ? "" : source.getUrl();
            String expectedToken = source == null ? null : scriptBridgeTokens.get(source);
            if (source == null || expectedToken == null || token.length() < 32 || !token.equals(expectedToken) ||
                    currentUrl == null || !scriptStore.isRunnable(scriptId) || !scriptStore.matchesUrl(scriptId, currentUrl)) {
                response = bridgeError("unauthorized");
            } else if (!scriptStore.allowsApi(scriptId, action)) {
                response = bridgeError("grant denied");
            } else if ("report".equals(action)) {
                scriptStore.recordExecution(scriptId, args.optDouble("ms", 0d), args.optString("e", ""));
                response = bridgeOk(true);
            } else if ("getValue".equals(action)) {
                String value = services.scriptValues().getJson(scriptId, args.optString("k", ""), args.optString("d", "null"));
                response = new JSONObject().put("ok", true).put("v", value).toString();
            } else if ("setValue".equals(action)) {
                response = bridgeOk(services.scriptValues().setJson(scriptId, args.optString("k", ""), args.optString("v", "null")));
            } else if ("deleteValue".equals(action)) {
                response = bridgeOk(services.scriptValues().delete(scriptId, args.optString("k", "")));
            } else if ("listValues".equals(action)) {
                response = new JSONObject().put("ok", true).put("v", new JSONArray(services.scriptValues().listJson(scriptId))).toString();
            } else if ("openTab".equals(action)) {
                String url = args.optString("u", "");
                boolean allowed = isHttpUrl(url) && url.length() <= 8192;
                if (allowed) openUrlInNewTab(url, args.optBoolean("active", true));
                response = bridgeOk(allowed);
            } else if ("clipboard".equals(action)) {
                android.content.ClipboardManager manager = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                String value = args.optString("v", "");
                boolean allowed = source == webView && activityResumed && manager != null && value.length() <= 1024 * 1024;
                if (allowed) manager.setPrimaryClip(android.content.ClipData.newPlainText("UserScript", value));
                response = bridgeOk(allowed);
            } else if ("notification".equals(action)) {
                String text = args.optString("text", "");
                String notice = args.optString("title", "Median") + (text.length() == 0 ? "" : "：" + text);
                toast(notice.substring(0, Math.min(240, notice.length())));
                response = bridgeOk(true);
            } else if ("download".equals(action)) {
                String url = args.optString("u", "");
                boolean allowed = isHttpUrl(url) && url.length() <= 8192 && scriptStore.canConnect(scriptId, url, currentUrl);
                Map<String, String> headers = jsonStringMap(args.optJSONObject("h"));
                boolean pageIsLocal = false;
                try { pageIsLocal = NetworkSecurity.isObviouslyLocalHost(NetworkSecurity.normalizedHost(NetworkSecurity.parseHttpUrl(currentUrl))); }
                catch (Exception ignored) {}
                response = bridgeOk(allowed && enqueueDownloadAdvanced(url, source.getSettings().getUserAgentString(), null,
                        "application/octet-stream", args.optString("n", ""), headers, !pageIsLocal));
            } else if ("xhr".equals(action)) {
                String url = args.optString("u", "");
                String callbackId = args.optString("i", "");
                boolean allowed = callbackId.matches("[A-Za-z0-9_-]{1,96}") && isHttpUrl(url) &&
                        scriptStore.canConnect(scriptId, url, currentUrl);
                bridgeDiagLog("xhr action cb=" + callbackId + " allowed=" + allowed + " url=" + url + " page=" + (currentUrl == null ? "NULL" : currentUrl) + " runnable=" + scriptStore.isRunnable(scriptId) + " match=" + scriptStore.matchesUrl(scriptId, currentUrl) + " tokenOk=" + (token.length() >= 32 && token.equals(expectedToken)));
                // Android 16+ 本地网络保护：按需兜底请求权限（onCreate 弹窗可能被跳过）
                if (allowed && Build.VERSION.SDK_INT >= 36) {
                    try {
                        if (checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
                            bridgeDiagLog("xhr blocked by LNP, requesting ACCESS_LOCAL_NETWORK cb=" + callbackId);
                            requestPermissions(new String[] { Manifest.permission.ACCESS_LOCAL_NETWORK }, 410);
                            response = bridgeError("local network permission pending");
                            break;
                        }
                    } catch (RuntimeException ignored) {}
                }
                if (allowed) startScriptRequest(source, token, scriptId, callbackId, args, currentUrl);
                response = allowed ? bridgeOk(true) : bridgeError("@connect denied");
            } else if ("xhrAbort".equals(action)) {
                HttpURLConnection connection = scriptConnections.remove(token + "|" + args.optString("i", ""));
                if (connection != null) connection.disconnect();
                response = bridgeOk(true);
            } else response = bridgeError("unknown action");
        } catch (Exception e) {
            response = bridgeError("bad request");
        }
        result.confirm(response);
        return true;
    }

    private void startScriptRequest(final WebView source, final String token, final String scriptId,
                                    final String callbackId, final JSONObject args, final String pageUrl) {
        if (scriptNetworkExecutor == null || scriptNetworkExecutor.isShutdown()) {
            bridgeDiagLog("startScriptRequest SKIPPED executor=null|shutdown cb=" + callbackId);
            try { android.util.Log.d("MedianBridge", "startScriptRequest SKIPPED executor=null|shutdown cb=" + callbackId); } catch (RuntimeException ignored) {}
            return;
        }
        bridgeDiagLog("startScriptRequest dispatch cb=" + callbackId + " url=" + args.optString("u", "") + " page=" + pageUrl);
        try { android.util.Log.d("MedianBridge", "startScriptRequest dispatch cb=" + callbackId + " url=" + args.optString("u", "") + " page=" + pageUrl); } catch (RuntimeException ignored) {}
        scriptNetworkExecutor.execute(new Runnable() {
            @Override public void run() {
                HttpURLConnection connection = null;
                String key = token + "|" + callbackId;
                try {
                    URL current = NetworkSecurity.parseHttpUrl(args.optString("u", ""));
                    URL initial = current;
                    URL page = NetworkSecurity.parseHttpUrl(pageUrl);
                    int requestedTimeout = args.optInt("to", 0);
                    int timeout = requestedTimeout <= 0 ? 20000 : Math.max(1000, Math.min(60000, requestedTimeout));
                    String method = args.optString("m", "GET").toUpperCase(Locale.US);
                    if (!method.matches("GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS")) throw new IllegalArgumentException("unsupported method");
                    byte[] requestBody = args.optString("d", "").getBytes("UTF-8");
                    if (requestBody.length > 4 * 1024 * 1024) throw new IllegalArgumentException("request body too large");
                    JSONObject headerObject = args.optJSONObject("h");
                    boolean anonymous = args.optBoolean("anon", false);
                    int status = 0;
                    for (int redirects = 0; redirects <= NetworkSecurity.MAX_REDIRECTS; redirects++) {
                        if (!scriptStore.canConnect(scriptId, current.toString(), pageUrl)) throw new IllegalArgumentException("@connect denied after redirect");
                        boolean pageIsLocal = NetworkSecurity.isLocalOrPrivateHost(NetworkSecurity.normalizedHost(page));
                        boolean targetIsLocal = NetworkSecurity.isLocalOrPrivateHost(NetworkSecurity.normalizedHost(current));
                        if (targetIsLocal && !pageIsLocal) throw new IllegalArgumentException("local network target denied");

                        connection = (HttpURLConnection) current.openConnection();
                        scriptConnections.put(key, connection);
                        connection.setInstanceFollowRedirects(false);
                        connection.setConnectTimeout(Math.min(timeout, 20000));
                        connection.setReadTimeout(timeout);
                        connection.setUseCaches(false);
                        connection.setRequestProperty("Accept-Encoding", "identity");
                        connection.setRequestMethod(method);
                        boolean sameInitialOrigin = NetworkSecurity.sameOrigin(initial, current);
                        if (headerObject != null) {
                            Iterator<String> names = headerObject.keys();
                            int count = 0;
                            while (names.hasNext() && count++ < 64) {
                                String name = names.next();
                                String value = headerObject.optString(name, "");
                                if (!NetworkSecurity.validHeader(name, value) || NetworkSecurity.isForbiddenRequestHeader(name)) continue;
                                if (!sameInitialOrigin && NetworkSecurity.isCredentialHeader(name)) continue;
                                connection.setRequestProperty(name, value);
                            }
                        }
                        if (!anonymous) {
                            String cookie = CookieManager.getInstance().getCookie(current.toString());
                            if (cookie != null && cookie.length() > 0) connection.setRequestProperty("Cookie", cookie);
                        }
                        if (requestBody.length > 0 && !"GET".equals(method) && !"HEAD".equals(method)) {
                            connection.setDoOutput(true);
                            OutputStream requestOutput = connection.getOutputStream();
                            try { requestOutput.write(requestBody); } finally { requestOutput.close(); }
                        }

                        status = connection.getResponseCode();
                        if (!anonymous) storeResponseCookies(current, connection);
                        if (!NetworkSecurity.isRedirect(status)) break;
                        if (redirects == NetworkSecurity.MAX_REDIRECTS) throw new IllegalArgumentException("too many redirects");
                        URL next = NetworkSecurity.resolveRedirect(current, connection.getHeaderField("Location"), false);
                        connection.disconnect();
                        connection = null;
                        if ((status == 301 || status == 302 || status == 303) && !"GET".equals(method) && !"HEAD".equals(method)) {
                            method = "GET";
                            requestBody = new byte[0];
                        }
                        current = next;
                    }
                    if (connection == null) throw new IllegalStateException("request failed");
                    InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                    ByteArrayOutputStream output = new ByteArrayOutputStream(32768);
                    byte[] buffer = new byte[16384];
                    int read, total = 0;
                    long declared = connection.getContentLengthLong();
                    long lastProgress = 0;
                    if (input != null) try {
                        while ((read = input.read(buffer)) != -1) {
                            total += read;
                            if (total > 8 * 1024 * 1024) throw new IllegalArgumentException("response exceeds 8 MB");
                            output.write(buffer, 0, read);
                            if (total - lastProgress >= 262144) {
                                lastProgress = total;
                                JSONObject progress = new JSONObject().put("loaded", total).put("total", Math.max(0, declared)).put("lengthComputable", declared > 0);
                                dispatchScriptEvent(source, token, scriptId, callbackId, "progress", progress);
                            }
                        }
                    } finally { input.close(); }
                    byte[] bytes = output.toByteArray();
                    String responseType = args.optString("rt", "text");
                    String contentType = connection.getContentType() == null ? "" : connection.getContentType();
                    String text = decodeResponseText(bytes, contentType);
                    boolean binary = "arraybuffer".equalsIgnoreCase(responseType) || "blob".equalsIgnoreCase(responseType);
                    JSONObject payload = new JSONObject();
                    payload.put("status", status);
                    payload.put("statusText", connection.getResponseMessage() == null ? "" : connection.getResponseMessage());
                    payload.put("finalUrl", connection.getURL().toString());
                    payload.put("responseHeaders", responseHeaders(connection));
                    payload.put("contentType", contentType);
                    payload.put("responseText", binary ? "" : text);
                    payload.put("response", binary ? android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP) : text);
                    bridgeDiagLog("HTTP ok status=" + status + " bytes=" + total + " cb=" + callbackId);
                    dispatchScriptEvent(source, token, scriptId, callbackId, "load", payload);
                } catch (java.net.SocketTimeoutException timeoutError) {
                    bridgeDiagLog("HTTP timeout cb=" + callbackId + " err=" + timeoutError);
                    dispatchScriptEvent(source, token, scriptId, callbackId, "timeout", errorPayload(timeoutError));
                } catch (Exception error) {
                    String event = scriptConnections.containsKey(key) ? "error" : "abort";
                    String em = String.valueOf(error.getMessage());
                    if (em.contains("local network")) {
                        bridgeDiagLog("HTTP LNP_BLOCKED cb=" + callbackId + " err=" + em + " (需要 ACCESS_LOCAL_NETWORK 权限)");
                        try { android.util.Log.d("MedianBridge", "HTTP LNP_BLOCKED cb=" + callbackId + " err=" + em); } catch (RuntimeException ignored) {}
                    } else {
                        bridgeDiagLog("HTTP " + event + " cb=" + callbackId + " err=" + error);
                    }
                    dispatchScriptEvent(source, token, scriptId, callbackId, event, errorPayload(error));
                } finally {
                    scriptConnections.remove(key);
                    if (connection != null) connection.disconnect();
                }
            }
        });
    }

    private JSONObject errorPayload(Exception error) {
        JSONObject payload = new JSONObject();
        try { payload.put("error", safeMessage(error)); } catch (Exception ignored) {}
        return payload;
    }

    private void dispatchScriptEvent(final WebView source, final String token, final String scriptId,
                                     final String callbackId, final String event, final JSONObject payload) {
        uiHandler.post(new Runnable() {
            private int attempts = 0;
            @Override public void run() {
                attempts++;
                if (source == null || scriptStore == null) return;
                String expected = scriptBridgeTokens.get(source);
                if (expected == null || !token.equals(expected)) {
                    bridgeDiagLog("dispatch guard FAIL expected=" + (expected != null) + " tokenEq=" + token.equals(expected) + " cb=" + callbackId + " event=" + event);
                    try { android.util.Log.d("MedianBridge", "dispatch guard FAIL expected=" + (expected != null) + " tokenEq=" + token.equals(expected) + " cb=" + callbackId + " event=" + event); } catch (RuntimeException ignored) {}
                    return;
                }
                String current = source.getUrl();
                boolean urlReady = current != null && scriptStore.matchesUrl(scriptId, current);
                // 页面导航早期 getUrl() 可能尚未就绪：延迟重试（最多约 3 秒），避免脚本侧 GM 请求永久无响应
                if (!urlReady && attempts < 12) {
                    bridgeDiagLog("dispatch retry attempts=" + attempts + " url=" + current + " event=" + event);
                    try { android.util.Log.d("MedianBridge", "dispatch retry attempts=" + attempts + " url=" + current + " event=" + event); } catch (RuntimeException ignored) {}
                    uiHandler.postDelayed(this, 250);
                    return;
                }
                if (!urlReady) {
                    bridgeDiagLog("dispatch GIVEUP attempts=" + attempts + " url=" + current + " event=" + event);
                    try { android.util.Log.d("MedianBridge", "dispatch GIVEUP attempts=" + attempts + " url=" + current + " event=" + event); } catch (RuntimeException ignored) {}
                    return;
                }
                String objectName = UserScriptStore.dispatchObjectName(token, scriptId);
                String js = "(function(){var d=window[" + JSONObject.quote(objectName) + "];if(d&&typeof d.dispatch==='function')d.dispatch(" +
                        JSONObject.quote(token) + "," + JSONObject.quote(callbackId) + "," + JSONObject.quote(event) + "," +
                        (payload == null ? "{}" : payload.toString()) + ");})();";
                try {
                    source.evaluateJavascript(js, null);
                    bridgeDiagLog("dispatch SENT event=" + event + " cb=" + callbackId + " attempts=" + attempts);
                    try { android.util.Log.d("MedianBridge", "dispatch SENT event=" + event + " cb=" + callbackId + " attempts=" + attempts); } catch (RuntimeException ignored) {}
                } catch (RuntimeException ex) {
                    bridgeDiagLog("dispatch EVAL_EXC " + ex + " event=" + event + " cb=" + callbackId);
                }
            }
        });
    }

    private void storeResponseCookies(URL url, HttpURLConnection connection) {
        Map<String, List<String>> fields = connection.getHeaderFields();
        if (fields == null) return;
        CookieManager manager = CookieManager.getInstance();
        boolean changed = false;
        for (Map.Entry<String, List<String>> field : fields.entrySet()) {
            String name = field.getKey();
            if (name == null || field.getValue() == null ||
                    !("set-cookie".equalsIgnoreCase(name) || "set-cookie2".equalsIgnoreCase(name))) continue;
            for (String value : field.getValue()) {
                if (value != null && value.length() > 0) {
                    manager.setCookie(url.toString(), value);
                    changed = true;
                }
            }
        }
        if (changed) scheduleCookieFlush();
    }

    private void scheduleCookieFlush() {
        if (!cookieFlushPending.compareAndSet(false, true)) return;
        uiHandler.postDelayed(new Runnable() {
            @Override public void run() {
                cookieFlushPending.set(false);
                try { CookieManager.getInstance().flush(); } catch (RuntimeException ignored) {}
            }
        }, 1000L);
    }

    private static String decodeResponseText(byte[] bytes, String contentType) {
        Charset charset = StandardCharsets.UTF_8;
        if (contentType != null) {
            String[] parts = contentType.split(";");
            for (String part : parts) {
                String trimmed = part.trim();
                if (!trimmed.toLowerCase(Locale.US).startsWith("charset=")) continue;
                String name = trimmed.substring(8).trim().replace("\"", "");
                try { charset = Charset.forName(name); } catch (Exception ignored) {}
                break;
            }
        }
        return new String(bytes, charset);
    }

    private static String responseHeaders(HttpURLConnection connection) {
        StringBuilder out = new StringBuilder();
        Map<String, List<String>> fields = connection.getHeaderFields();
        if (fields == null) return "";
        for (Map.Entry<String, List<String>> field : fields.entrySet()) {
            if (field.getKey() == null || field.getValue() == null) continue;
            String lower = field.getKey().toLowerCase(Locale.US);
            if (lower.equals("set-cookie") || lower.equals("set-cookie2") || lower.equals("proxy-authenticate")) continue;
            for (String value : field.getValue()) out.append(field.getKey()).append(": ").append(value).append("\r\n");
        }
        return out.toString();
    }

    private static Map<String, String> jsonStringMap(JSONObject object) {
        HashMap<String, String> result = new HashMap<String, String>();
        if (object == null) return result;
        Iterator<String> keys = object.keys();
        int count = 0;
        while (keys.hasNext() && count++ < 64) {
            String key = keys.next();
            result.put(key, object.optString(key, ""));
        }
        return result;
    }

    private static String bridgeOk(boolean ok) {
        try { return new JSONObject().put("ok", ok).toString(); }
        catch (Exception ignored) { return ok ? "{\"ok\":true}" : "{\"ok\":false}"; }
    }

    private static String bridgeError(String error) {
        try { return new JSONObject().put("ok", false).put("error", error).toString(); }
        catch (Exception ignored) { return "{\"ok\":false}"; }
    }

    private static boolean isHttpUrl(String value) {
        try {
            NetworkSecurity.parseHttpUrl(value);
            return true;
        } catch (Exception ignored) { return false; }
    }

    /** 记录网络请求到 MCP Network 缓冲（仅 http/https，供 browser_network 使用）。 */
    private void recordNetworkRequest(WebView view, String url, boolean mainFrame, String method) {
        try {
            if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) return;
            JSONObject entry = new JSONObject();
            entry.put("url", url.length() > 2000 ? url.substring(0, 2000) : url);
            entry.put("mainFrame", mainFrame);
            entry.put("method", method == null ? "GET" : method);
            entry.put("time", System.currentTimeMillis());
            McpController.get().recordNetwork(entry);
        } catch (Exception ignored) { }
    }

    private WebResourceResponse interceptRequest(WebView source, String requestUrl) {
        Uri uri = null;
        try { uri = Uri.parse(requestUrl); } catch (RuntimeException ignored) {}
        WebResourceResponse ruleResp = applyNetRule(source, uri, false);
        if (ruleResp != null) return ruleResp;
        WebResourceResponse homeAsset = interceptHomeAsset(source, uri);
        if (homeAsset != null) return homeAsset;
        String pageHost = pageHostFor(source);
        observeMediaIfLikely(source, uri, "", pageHost);
        if (MODE_PERFORMANCE.equals(performanceMode) && performanceNetworkDirect) return null;
        if (cachedAdBlockActive(source, pageHost) &&
                (uri == null ? adBlock.shouldBlock(requestUrl, pageHost) : adBlock.shouldBlock(uri, pageHost))) {
            scheduleCosmeticInjection(source, pageHost);
            return blockedResponse();
        }
        return null;
    }

    private WebResourceResponse interceptRequest(WebView source, Uri requestUri) {
        WebResourceResponse homeAsset = interceptHomeAsset(source, requestUri);
        if (homeAsset != null) return homeAsset;
        String pageHost = pageHostFor(source);
        observeMediaIfLikely(source, requestUri, "", pageHost);
        if (MODE_PERFORMANCE.equals(performanceMode) && performanceNetworkDirect) return null;
        if (cachedAdBlockActive(source, pageHost) && adBlock.shouldBlock(requestUri, pageHost)) {
            scheduleCosmeticInjection(source, pageHost);
            return blockedResponse();
        }
        return null;
    }

    private WebResourceResponse interceptRequest(WebView source, WebResourceRequest request) {
        Uri requestUri = request.getUrl();
        WebResourceResponse ruleResp = applyNetRule(source, requestUri, request.isForMainFrame());
        if (ruleResp != null) return ruleResp;
        WebResourceResponse homeAsset = interceptHomeAsset(source, requestUri);
        if (homeAsset != null) return homeAsset;
        if (customHomeViews.contains(source) && !request.isForMainFrame() && requestUri != null &&
                ("http".equalsIgnoreCase(requestUri.getScheme()) || "https".equalsIgnoreCase(requestUri.getScheme())) &&
                !"median.invalid".equalsIgnoreCase(requestUri.getHost())) return blockedResponse();
        String pageHost = pageHostFor(source);
        boolean adBlockActive = cachedAdBlockActive(source, pageHost);
        String accept = shouldReadAcceptHeader(requestUri, request.isForMainFrame(), adBlockActive) ? acceptHeader(request) : "";
        observeMediaIfLikely(source, requestUri, accept, pageHost);
        if (MODE_PERFORMANCE.equals(performanceMode) && performanceNetworkDirect) return null;
        if (adBlockActive && adBlock.shouldBlock(requestUri, pageHost, accept, request.isForMainFrame())) {
            scheduleCosmeticInjection(source, pageHost);
            return blockedResponse();
        }
        return null;
    }

    private boolean cachedAdBlockActive(WebView source, String pageHost) {
        if (source == null) return isAdBlockActiveForHost(pageHost);
        Boolean cached = adBlockActiveByView.get(source);
        if (cached != null) return cached.booleanValue();
        boolean active = isAdBlockActiveForHost(pageHost);
        adBlockActiveByView.put(source, Boolean.valueOf(active));
        return active;
    }

    private static String acceptHeader(WebResourceRequest request) {
        Map<String, String> headers = request == null ? null : request.getRequestHeaders();
        if (headers == null || headers.size() == 0) return "";
        String exact = headers.get("Accept");
        if (exact != null) return exact;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if ("Accept".equalsIgnoreCase(entry.getKey())) return entry.getValue() == null ? "" : entry.getValue();
        }
        return "";
    }

    private static boolean shouldReadAcceptHeader(Uri uri, boolean mainFrame, boolean adBlockActive) {
        if (mainFrame) return true;
        if (uri == null) return false;
        String path = uri.getPath();
        if (path == null) return true;
        String lower = path.toLowerCase(Locale.US);
        return !(lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".gif") ||
                lower.endsWith(".webp") || lower.endsWith(".svg") || lower.endsWith(".css") || lower.endsWith(".js") ||
                lower.endsWith(".woff") || lower.endsWith(".woff2") || lower.endsWith(".ttf") || lower.endsWith(".ico") ||
                lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".m3u8") || lower.endsWith(".mp3") ||
                lower.endsWith(".m4a") || lower.endsWith(".wav"));
    }

    private void observeMediaIfLikely(WebView source, Uri uri, String accept, String pageHost) {
        if (source != webView || uri == null || MODE_POWER_SAVE.equals(performanceMode)) return;
        String path = uri.getPath();
        String lower = path == null ? "" : path.toLowerCase(Locale.US);
        String mime = accept == null ? "" : accept.toLowerCase(Locale.US);
        if (mime.contains("video/") || mime.contains("audio/") || mime.contains("mpegurl") || mime.contains("dash+xml") ||
                lower.endsWith(".m3u8") || lower.endsWith(".mpd") || lower.endsWith(".mp4") || lower.endsWith(".webm") ||
                lower.endsWith(".mkv") || lower.endsWith(".mov") || lower.endsWith(".m4v") || lower.endsWith(".mp3") ||
                lower.endsWith(".m4a") || lower.endsWith(".aac") || lower.endsWith(".ogg") || lower.endsWith(".opus") ||
                lower.endsWith(".flac") || lower.endsWith(".wav")) {
            mediaSniffer.observe(uri, accept, pageHost);
        }
    }

    private WebResourceResponse interceptHomeAsset(WebView source, Uri uri) {
        if (source == null || uri == null || !trustedHomeViews.contains(source) ||
                !"https".equalsIgnoreCase(uri.getScheme()) || !"median.invalid".equalsIgnoreCase(uri.getHost())) return null;
        if ("/home-custom".equals(uri.getPath())) {
            if (!customHomeViews.contains(source)) return blockedResponse();
            String html = prefs == null ? "" : prefs.getString("home_custom_html", "");
            if (!CustomHomeHtml.valid(html)) return blockedResponse();
            byte[] data = CustomHomeHtml.document(html).getBytes(StandardCharsets.UTF_8);
            return new WebResourceResponse("text/html", "UTF-8", new ByteArrayInputStream(data));
        }
        if (homeImages == null) return blockedResponse();
        HomeImageStore.Kind kind;
        if ("/home-wallpaper".equals(uri.getPath())) kind = HomeImageStore.Kind.WALLPAPER;
        else if ("/home-logo".equals(uri.getPath())) kind = HomeImageStore.Kind.LOGO;
        else return null;
        try {
            InputStream input = homeImages.open(kind);
            return input == null ? blockedResponse() : new WebResourceResponse(homeImages.mime(kind), null, input);
        } catch (Exception ignored) {
            return blockedResponse();
        }
    }

    private void scheduleCosmeticInjection(final WebView source, final String pageHost) {
        if (source == null || pageHost == null || pageHost.length() == 0) return;
        // A page can block hundreds of resources. Claim the one injection slot before
        // assembling selectors so only the first callback performs that work.
        if (cosmeticInjected.putIfAbsent(source, Boolean.TRUE) != null) return;
        final String css = adBlock.cosmeticCssForHost(pageHost);
        final String procedural = adBlock.proceduralScriptForHost(pageHost);
        if (css.length() == 0 && procedural.length() == 0) return;
        uiHandler.post(new Runnable() {
            @Override public void run() {
                if (!pageHost.equalsIgnoreCase(pageHostFor(source))) {
                    cosmeticInjected.remove(source);
                    return;
                }
                String script = "(function f(){var p=document.head||document.documentElement;if(!p){setTimeout(f,30);return;}var s=document.getElementById('__median_adblock');if(!s&&" + (css.length() > 0 ? "true" : "false") + "){s=document.createElement('style');s.id='__median_adblock';s.textContent=" +
                        JSONObject.quote(css) + ";p.appendChild(s);}})();" + procedural;
                try { source.evaluateJavascript(script, null); } catch (RuntimeException ignored) {}
            }
        });
    }

    /** 页面加载完成后注入 MCP JS 钩子与指纹伪装脚本（主线程）。 */
    private void injectMcpHooks(final WebView view) {
        if (view == null || isHomeUrl(view.getUrl())) return;
        final String hooks = McpController.get().activeHookScript();
        final String fp = McpController.get().fingerprintScript();
        if (hooks.isEmpty() && fp.isEmpty()) return;
        final String script = fp + hooks;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            view.evaluateJavascript(script, null);
        } else {
            uiHandler.post(new Runnable() {
                @Override public void run() {
                    if (view.getUrl() != null) view.evaluateJavascript(script, null);
                }
            });
        }
    }

    /** 网络规则链：block 拦截 / redirect 重写 / inject 注入 / replace 替换。
     *  多条规则可同时命中：block 优先，redirect 独立，inject+replace 叠加变换（一次拉取）。 */
    private WebResourceResponse applyNetRule(WebView source, Uri requestUri, boolean mainFrame) {
        if (requestUri == null) return null;
        String url = requestUri.toString();
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null;
        java.util.List<McpController.NetRule> rules = McpController.get().matchNetRules(url);
        if (rules.isEmpty()) return null;
        for (McpController.NetRule rule : rules) rule.hits++;
        // 1) block：任一命中即拦截
        for (McpController.NetRule rule : rules) {
            if ("block".equals(rule.type)) {
                McpController.get().recordRunLog("info", "netrule", rule.type + " " + url);
                return blockedResponse();
            }
        }
        // 2) redirect：命中即整体重定向到目标
        for (McpController.NetRule rule : rules) {
            if ("redirect".equals(rule.type)) {
                McpController.get().recordRunLog("info", "netrule", rule.type + " " + url);
                Fetched f = fetchRemoteBytes(rule.target);
                if (f == null) return null;
                attachBody(url, f);
                return new WebResourceResponse(f.mime, f.charset, new ByteArrayInputStream(f.body));
            }
        }
        // 3) inject / replace：主框架请求叠加变换（一次拉取，按添加顺序依次应用）
        if (mainFrame) {
            Fetched g = null;
            for (McpController.NetRule rule : rules) {
                if ("inject".equals(rule.type) || "replace".equals(rule.type)) {
                    if (g == null) g = fetchRemoteBytes(url);
                    if (g == null) return null;
                    McpController.get().recordRunLog("info", "netrule", rule.type + " " + url);
                    g = applyTransform(g, rule);
                }
            }
            if (g != null) {
                attachBody(url, g);
                return new WebResourceResponse(g.mime, g.charset,
                        new ByteArrayInputStream(g.body == null ? EMPTY_RESPONSE : g.body));
            }
        }
        return null;
    }

    /** 拉取结果容器（mime/charset/body/status）。 */
    private static final class Fetched {
        final int status;
        final String mime;
        final String charset;
        final byte[] body;
        Fetched(int s, String m, String c, byte[] b) { status = s; mime = m; charset = c; body = b; }
    }

    /** 将拉取结果回填到 MCP 网络记录（响应体快照，最多 4KB）。 */
    private void attachBody(String url, Fetched f) {
        if (f == null) return;
        String snippet = null;
        if (f.body != null) {
            int len = Math.min(f.body.length, 4096);
            snippet = new String(f.body, 0, len, StandardCharsets.UTF_8);
            if (f.body.length > len) snippet += "...";
        }
        McpController.get().attachNetworkBody(url, f.status, f.mime, f.body == null ? 0 : f.body.length, snippet);
    }

    /** 用 HttpURLConnection 拉取远程内容（零依赖，IO 线程调用）。 */
    private Fetched fetchRemoteBytes(String url) {
        if (url == null || url.isEmpty()) return null;
        HttpURLConnection conn = null;
        try {
            int hops = 0;
            String cur = url;
            while (true) {
                conn = (HttpURLConnection) new URL(cur).openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("User-Agent", NET_RULE_UA);
                int code = conn.getResponseCode();
                if ((code == 301 || code == 302 || code == 303 || code == 307 || code == 308) && hops < 3) {
                    String loc = conn.getHeaderField("Location");
                    conn.disconnect();
                    conn = null;
                    if (loc == null || loc.isEmpty()) return null;
                    cur = new URL(new URL(cur), loc).toString();
                    hops++;
                    continue;
                }
                if (code < 200 || code >= 400) { conn.disconnect(); conn = null; return null; }
                break;
            }
            String contentType = conn.getContentType();
            String mime = "text/html";
            String charset = "UTF-8";
            if (contentType != null) {
                String[] parts = contentType.split(";");
                if (parts.length > 0 && !parts[0].trim().isEmpty()) mime = parts[0].trim();
                for (String p : parts) {
                    String t = p.trim().toLowerCase(Locale.ROOT);
                    if (t.startsWith("charset=")) charset = t.substring("charset=".length()).trim();
                }
            }
            java.io.InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n, total = 0, MAX = 8 * 1024 * 1024;
            while ((n = in.read(buf)) > 0 && total < MAX) { bos.write(buf, 0, n); total += n; }
            in.close();
            int status = conn.getResponseCode();
            conn.disconnect();
            conn = null;
            return new Fetched(status, mime, charset, bos.toByteArray());
        } catch (Exception e) {
            if (conn != null) try { conn.disconnect(); } catch (RuntimeException ignored) {}
            return null;
        }
    }

    /** 兼容包装：拉取并返回 WebResourceResponse。 */
    private WebResourceResponse fetchRemote(String url) {
        Fetched f = fetchRemoteBytes(url);
        if (f == null) return null;
        return new WebResourceResponse(f.mime, f.charset, new ByteArrayInputStream(f.body));
    }

    /** 对已拉取的响应应用单条变换规则：injectMode 在 </head> 前插入 target；replace 把 match/pattern 替换为 target。
     *  仅对 HTML 生效，其余类型原样返回。 */
    private Fetched applyTransform(Fetched f, McpController.NetRule rule) {
        if (f == null || f.body == null) return f;
        String mime = f.mime;
        if (mime == null || !mime.toLowerCase(Locale.ROOT).contains("html")) return f;
        try {
            java.nio.charset.Charset cs;
            try { cs = java.nio.charset.Charset.forName(f.charset); } catch (Exception e) { cs = StandardCharsets.UTF_8; }
            String html = new String(f.body, cs);
            String out;
            if ("inject".equals(rule.type)) {
                String lower = html.toLowerCase(Locale.ROOT);
                int idx = lower.lastIndexOf("</head>");
                if (idx >= 0) out = html.substring(0, idx) + rule.target + html.substring(idx);
                else out = rule.target + html;
            } else {
                String pattern = rule.match != null && !rule.match.isEmpty() ? rule.match : rule.pattern;
                if (pattern == null || pattern.isEmpty()) out = html;
                else out = html.replace(pattern, rule.target == null ? "" : rule.target);
            }
            return new Fetched(f.status, f.mime, f.charset, out.getBytes(cs));
        } catch (Exception e) {
            return f;
        }
    }

    private WebResourceResponse blockedResponse() {
        return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(EMPTY_RESPONSE));
    }

    private boolean handleNavigation(String url) {
        if (url == null) return false;
        Uri parsed;
        try { parsed = Uri.parse(url); } catch (RuntimeException ignored) { return true; }
        if ("median".equalsIgnoreCase(parsed.getScheme())) {
            if (!isHomeUrl(currentPageUrl) || webView == null || !isHomeUrl(webView.getUrl()) ||
                    !trustedHomeViews.contains(webView) || customHomeViews.contains(webView)) {
                toast("网页无权调用浏览器内部功能");
                return true;
            }
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if ("engine".equals(host)) {
                setSearchEngine(uri.getQueryParameter("name"));
            } else if ("search".equals(host)) {
                String engine = uri.getQueryParameter("engine");
                String query = uri.getQueryParameter("q");
                setSearchEngine(engine);
                loadInput(query);
            } else if ("open".equals(host)) {
                loadInput(uri.getQueryParameter("url"));
            } else if ("bookmarks".equals(host)) {
                showBookmarkList(false);
            }
            return true;
        }
        if (looksLikeUserScript(url)) {
            installScriptFromUrl(url);
            return true;
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            String cleaned = shouldCleanTracking(hostOf(url)) ? UrlCleaner.cleanTracking(url) : url;
            if (!url.equals(cleaned)) {
                webView.loadUrl(cleaned);
                toast("已移除跟踪参数");
                return true;
            }
        }
        if (url.startsWith("http://") && shouldUpgradeHttp(url)) {
            webView.loadUrl("https://" + url.substring(7));
            return true;
        }
        if (url.startsWith("http://") || url.startsWith("https://") || "about:blank".equals(url) || isOfflineUrl(url)) return false;
        if (url.startsWith("data:") || url.startsWith("about:")) { toast("已阻止网页跳转到不透明内部地址"); return true; }
        confirmExternalNavigation(url);
        return true;
    }

    private boolean isOfflineUrl(String url) {
        try {
            Uri uri = Uri.parse(url);
            String name = uri.getLastPathSegment();
            return "content".equalsIgnoreCase(uri.getScheme()) && OfflineContentProvider.AUTHORITY.equals(uri.getAuthority()) &&
                    name != null && name.matches("[A-Za-z0-9._-]+") && name.endsWith(".mht");
        } catch (RuntimeException ignored) { return false; }
    }

    private String homeOpenMode() {
        return HomeOpenPolicy.normalize(prefs.getString("home_open_mode", ""),
                prefs.getBoolean("restore_tabs", true));
    }

    private String normalizeConfiguredHomeUrl(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() == 0 || value.length() > 2048) return "";
        if (!OmniboxInput.isExplicitHttpUrl(value)) {
            if (!OmniboxInput.looksLikeWebAddress(value)) return "";
            value = OmniboxInput.withDefaultHttpsScheme(value);
        }
        try { value = NetworkSecurity.parseHttpUrl(value).toString(); }
        catch (Exception invalid) { return ""; }
        if ("median.invalid".equalsIgnoreCase(Uri.parse(value).getHost())) return "";
        if (value.startsWith("http://") && shouldUpgradeHttp(value)) value = "https://" + value.substring(7);
        try { return NetworkSecurity.parseHttpUrl(value).toString(); }
        catch (Exception invalid) { return ""; }
    }

    private String configuredHomeUrl() {
        String custom = normalizeConfiguredHomeUrl(prefs.getString("home_custom_url", ""));
        return HomeOpenPolicy.usesCustomUrl(homeOpenMode(), false, custom) ? custom : HOME_URL;
    }

    private String homeOpenBehaviorLabel() {
        String mode = homeOpenMode();
        if (HomeOpenPolicy.KEEP_LAST.equals(mode)) return "保留上一次访问的内容";
        if (HomeOpenPolicy.OPEN_CUSTOM_URL.equals(mode)) {
            String custom = normalizeConfiguredHomeUrl(prefs.getString("home_custom_url", ""));
            return custom.length() == 0 ? "自定义页面（未设置）" : "自定义页面 · " + hostOf(custom);
        }
        return "打开主页";
    }

    private void openConfiguredHome() {
        String target = configuredHomeUrl();
        if (isHomeUrl(target)) showHome();
        else {
            updateCurrentTab(target, "主页");
            loadInput(target);
        }
    }

    private void loadInput(String input) {
        if (input == null) return;
        String text = input.trim();
        if (text.length() == 0) return;
        boolean explicitHttp = OmniboxInput.isExplicitHttpUrl(text);
        if (explicitHttp) {
            try { text = NetworkSecurity.parseHttpUrl(text).toString(); }
            catch (Exception invalidUrl) { toast("网页地址无效"); return; }
        }
        if (text.startsWith("http://") && shouldUpgradeHttp(text)) text = "https://" + text.substring(7);
        if (explicitHttp || "about:blank".equals(text)) {
            trustedHomeViews.remove(webView);
            customHomeViews.remove(webView);
            if (explicitHttp && shouldCleanTracking(hostOf(text))) text = UrlCleaner.cleanTracking(text);
            currentPageUrl = text;
            currentPageHost = hostOf(text);
            applySiteSettings(webView, currentPageHost);
            webView.loadUrl(text);
        } else if (OmniboxInput.looksLikeWebAddress(text)) {
            String candidate;
            try { candidate = NetworkSecurity.parseHttpsUrl(OmniboxInput.withDefaultHttpsScheme(text)).toString(); }
            catch (Exception invalidUrl) { toast("网页地址无效"); return; }
            trustedHomeViews.remove(webView);
            customHomeViews.remove(webView);
            currentPageUrl = candidate;
            currentPageHost = hostOf(currentPageUrl);
            applySiteSettings(webView, currentPageHost);
            webView.loadUrl(currentPageUrl);
        } else {
            loadSearch(text, searchEngine);
        }
    }

    private void loadSearch(String query, String engine) {
        if (query == null || query.trim().length() == 0) return;
        try {
            String q = URLEncoder.encode(query.trim(), "UTF-8");
            String url;
            if ("baidu".equals(engine)) url = "https://www.baidu.com/s?wd=" + q;
            else if ("bing".equals(engine)) url = "https://www.bing.com/search?q=" + q;
            else if ("custom".equals(engine) && validSearchTemplate(customSearchTemplate)) url = customSearchTemplate.replace("%s", q);
            else url = "https://www.google.com/search?q=" + q;
            trustedHomeViews.remove(webView);
            customHomeViews.remove(webView);
            currentPageUrl = url;
            currentPageHost = hostOf(url);
            applySiteSettings(webView, currentPageHost);
            webView.loadUrl(url);
        } catch (Exception e) {
            toast("无法创建搜索地址");
        }
    }

    @Override
    public void showHome() {
        currentPageUrl = HOME_URL;
        trustedHomeViews.add(webView);
        HomePageConfig config = homePageConfig();
        if (config.customHtmlEnabled) customHomeViews.add(webView);
        else customHomeViews.remove(webView);
        webView.loadDataWithBaseURL(HOME_URL, HomePage.html(searchEngine, dataStore.bookmarks(), nightMode,
                HOME_TOKEN, config), "text/html", "UTF-8", HOME_URL);
        currentPageHost = "";
        updateMediaNetworkBoost();
        if (webView != null) pageHosts.put(webView, "");
        updateCurrentTab(HOME_URL, "主页");
        requestChromeUpdate();
    }

    private HomePageConfig homePageConfig() {
        String customHtml = prefs.getString("home_custom_html", "");
        boolean customHtmlEnabled = prefs.getBoolean("home_custom_html_enabled", false) &&
                CustomHomeHtml.valid(customHtml);
        boolean hasLogo = homeImages != null && homeImages.has(HomeImageStore.Kind.LOGO);
        String logoMode = prefs.getString("home_logo_mode", "");
        if (!"text".equals(logoMode) && !"image".equals(logoMode) && !"none".equals(logoMode))
            logoMode = hasLogo ? "image" : "text";
        return HomePageConfig.createPersonalized(
                prefs.getString("home_title", HomePageConfig.DEFAULT_TITLE),
                prefs.getString("home_subtitle", ""),
                prefs.getString("home_logo_style", "median"),
                prefs.getString("home_logo_code", ""),
                prefs.getInt("home_logo_letter_spacing", 0),
                prefs.getInt("home_logo_gradient_angle", 90),
                prefs.getString("home_accent", "blue"),
                prefs.getInt("home_wallpaper_dim", 28),
                prefs.getInt("home_wallpaper_blur", 0),
                prefs.getString("home_wallpaper_fit", "cover"),
                prefs.getString("home_search_style", "solid"),
                prefs.getString("home_layout", "center"),
                prefs.getString("home_tile_shape", "rounded"),
                prefs.getInt("home_shortcut_columns", 4),
                prefs.getBoolean("home_show_search", true),
                prefs.getBoolean("home_show_engines", true),
                prefs.getBoolean("home_show_shortcuts", true),
                prefs.getBoolean("home_show_corner", true),
                prefs.getBoolean("home_show_clock", false),
                customHtmlEnabled,
                homeImages != null && homeImages.has(HomeImageStore.Kind.WALLPAPER),
                hasLogo,
                prefs.getLong("home_custom_html_version", 0L),
                homeImages == null ? 0L : homeImages.version(HomeImageStore.Kind.WALLPAPER),
                homeImages == null ? 0L : homeImages.version(HomeImageStore.Kind.LOGO),
                logoMode,
                prefs.getInt("home_logo_font_size", 47),
                prefs.getInt("home_logo_font_weight", 720),
                prefs.getInt("home_logo_image_width", 132),
                prefs.getInt("home_logo_image_height", 96),
                prefs.getInt("home_logo_image_radius", 0),
                prefs.getString("home_custom_css", ""));
    }

    private void verifyTrustedHome(final WebView view) {
        if (view == null) return;
        String probe = "(function(){var m=document.querySelector('meta[name=median-home-token]');return !!m&&m.content===" +
                JSONObject.quote(HOME_TOKEN) + ";})();";
        view.evaluateJavascript(probe, new ValueCallback<String>() {
            @Override public void onReceiveValue(String value) {
                try {
                    if ("true".equals(value) && isHomeUrl(view.getUrl())) {
                        trustedHomeViews.add(view);
                        if (homePageConfig().customHtmlEnabled) customHomeViews.add(view);
                        else customHomeViews.remove(view);
                    } else {
                        trustedHomeViews.remove(view);
                        customHomeViews.remove(view);
                    }
                } catch (RuntimeException ignored) {
                    trustedHomeViews.remove(view);
                    customHomeViews.remove(view);
                }
            }
        });
    }

    private void schedulePageEnhancements(final String url, final long sequence) {
        if (url == null || isHomeUrl(url) || url.startsWith("about:") || url.startsWith("data:")) return;
        boolean directNetwork = MODE_PERFORMANCE.equals(performanceMode) && performanceNetworkDirect;
        final boolean fallbackScripts = scriptStore.hasEnabledScripts()
                && (webView == null || !documentStartScriptViews.contains(webView));
        if ((!isAdBlockActive(url) || directNetwork) && !fallbackScripts) return;
        scriptExecutor.execute(new Runnable() {
            @Override public void run() {
                if (sequence != navigationSequence) return;
                try { Process.setThreadPriority(scriptThreadPriority()); } catch (RuntimeException ignored) {}
                StringBuilder start = new StringBuilder(4096);
                // Old WebView builds do not expose an isolated document-start hook. Only scripts
                // without native grants are allowed through this compatibility path.
                String startScripts = fallbackScripts ? scriptStore.buildInjection(url, true, "") : "";
                if (startScripts.length() > 0) start.append(startScripts);
                if (sequence != navigationSequence) return;
                final PreparedInjection prepared = new PreparedInjection(sequence, url, start.toString(),
                        fallbackScripts ? scriptStore.buildInjection(url, false, "") : "");
                uiHandler.post(new Runnable() {
                    @Override public void run() {
                        if (sequence != navigationSequence || webView == null) return;
                        preparedInjection = prepared;
                        if (pageCommitted) injectPreparedStart(sequence);
                        if (pageFinished) injectPreparedEnd(sequence);
                    }
                });
            }
        });
    }

    private void injectPreparedStart(long sequence) {
        PreparedInjection prepared = preparedInjection;
        if (prepared == null || prepared.sequence != sequence || injectedStartSequence == sequence) return;
        injectedStartSequence = sequence;
        if (prepared.startScript.length() > 0) executeUserScriptPayload(prepared.startScript);
    }

    private void injectPreparedEnd(final long sequence) {
        final PreparedInjection prepared = preparedInjection;
        if (prepared == null || prepared.sequence != sequence || injectedEndSequence == sequence) return;
        injectedEndSequence = sequence;
        Runnable inject = new Runnable() {
            @Override public void run() {
                if (sequence == navigationSequence && prepared.endScript.length() > 0 && webView != null) {
                    executeUserScriptPayload(prepared.endScript);
                }
                if (sequence == navigationSequence) preparedInjection = null;
            }
        };
        if (MODE_POWER_SAVE.equals(performanceMode)) uiHandler.postDelayed(inject, 160L); else inject.run();
    }

    private void executeUserScriptPayload(String payload) {
        final WebView active = webView;
        if (active == null || payload == null || payload.length() == 0) return;
        try {
            active.evaluateJavascript(payload, new ValueCallback<String>() {
                @Override public void onReceiveValue(final String value) {
                    if (scriptExecutor == null || scriptExecutor.isShutdown()) return;
                    scriptExecutor.execute(new Runnable() {
                        @Override public void run() { scriptStore.recordExecutionResult(value); }
                    });
                }
            });
        } catch (RuntimeException ignored) {
        }
    }

    private boolean isAdBlockActive(String url) {
        return isAdBlockActiveForHost(hostOf(url));
    }

    private boolean isAdBlockActiveForHost(String host) {
        if (!adBlockEnabled) return false;
        SiteSettingsStore.SiteSettings site = siteSettingsStore == null ? null : siteSettingsStore.forHost(host);
        if (site != null && site.compatibilityMode) return false;
        return matchingSiteException(host) == null;
    }

    private String matchingSiteException(String host) {
        Set<String> exceptions = siteExceptions;
        if (host == null || host.length() == 0) return null;
        String candidate = host.toLowerCase(Locale.US);
        while (candidate.length() > 0) {
            if (exceptions.contains(candidate)) return candidate;
            int dot = candidate.indexOf('.');
            if (dot < 0) break;
            candidate = candidate.substring(dot + 1);
        }
        return null;
    }

    private void showProtectionPanel() {
        final String host = currentHost();
        final String matchedException = matchingSiteException(host);
        final boolean sitePaused = host.length() > 0 && matchedException != null;
        final boolean directNetwork = MODE_PERFORMANCE.equals(performanceMode) && performanceNetworkDirect;
        int pageBlocked = Math.max(0, adBlock.getBlockedCount() - blockedAtPageStart);
        String subtitle = host.length() == 0 ? "当前为主页" : host + " · 本页拦截 " + pageBlocked + " 项";
        String[] items = new String[] {
                "全局广告拦截：" + (adBlockEnabled ? (directNetwork ? "已暂停 · 网络直通中" : "已开启") : "已关闭"),
                host.length() == 0 ? "当前页面没有站点设置" : (sitePaused ? "恢复此网站拦截" : "暂停此网站拦截"),
                "过滤器中心 · 已启用 " + services.filters().enabledCount() + " 个订阅",
                "用户脚本 · 已启用 " + enabledScriptCount() + " 个",
                "浏览适用于当前网站的脚本",
                "自定义拦截规则"
        };
        int[] icons = new int[] { BrowserIconView.SHIELD, BrowserIconView.SHIELD, BrowserIconView.STORAGE, BrowserIconView.SCRIPT, BrowserIconView.SEARCH, BrowserIconView.STORAGE };
        showActionSheet("保护与脚本", subtitle, items, icons, new SheetHandler() {
            @Override public void onItem(int which) {
                if (which == 0) {
                    adBlockEnabled = !adBlockEnabled;
                    prefs.edit().putBoolean("adblock", adBlockEnabled).apply();
                    adBlockActiveByView.clear();
                    webView.reload();
                } else if (which == 1 && host.length() > 0) {
                    HashSet<String> updated = new HashSet<String>(siteExceptions);
                    if (matchedException != null) updated.remove(matchedException); else updated.add(host);
                    siteExceptions = updated;
                    prefs.edit().putStringSet("site_exceptions", new HashSet<String>(updated)).apply();
                    adBlockActiveByView.clear();
                    webView.reload();
                } else if (which == 2) {
                    showFilterCenter();
                } else if (which == 3) {
                    showScriptCenter();
                } else if (which == 4) {
                    String target = host.length() == 0 ? "https://greasyfork.org/zh-CN/scripts" : "https://greasyfork.org/zh-CN/scripts/by-site/" + host;
                    webView.loadUrl(target);
                } else if (which == 5) {
                    showCustomFilterRules();
                }
            }
        });
    }

    private void rebuildAdBlockRules() {
        List<String> sources = services.filters().readEnabledRuleSources();
        adBlock.updateRules(prefs.getString("custom_filter_rules", ""), sources);
    }

    private void rebuildAdBlockRulesAsync(final boolean notifyUser) {
        if (scriptExecutor == null || scriptExecutor.isShutdown()) {
            rebuildAdBlockRules();
            return;
        }
        scriptExecutor.execute(new Runnable() {
            @Override public void run() {
                try { Process.setThreadPriority(scriptThreadPriority()); } catch (RuntimeException ignored) {}
                rebuildAdBlockRules();
                final AdBlockEngine.Stats stats = adBlock.getStats();
                if (notifyUser) uiHandler.post(new Runnable() {
                    @Override public void run() {
                        toast("过滤器已编译：" + stats.networkRules + " 条网络规则、" + stats.cosmeticRules + " 条外观规则");
                        if (webView != null) webView.reload();
                    }
                });
            }
        });
    }

    private void updateFilterSubscriptions(final boolean automatic) {
        if (filterUpdateInProgress) return;
        filterUpdateInProgress = true;
        if (!automatic) toast("正在更新过滤订阅…");
        services.filters().updateEnabled(automatic, new FilterSubscriptionStore.Callback() {
            @Override public void onComplete(int updated, int unchanged, int failed, String message) {
                filterUpdateInProgress = false;
                if (updated > 0) rebuildAdBlockRulesAsync(false);
                if (!automatic) {
                    String result = "订阅更新完成：" + updated + " 个更新，" + unchanged + " 个未变化";
                    if (failed > 0) result += "，" + failed + " 个失败" + (message.length() == 0 ? "" : "（" + message + "）");
                    if (updated > 0) result += "；新外观规则在下次刷新完整生效";
                    toast(result);
                }
            }
        });
    }

    private void showFilterCenter() {
        final List<FilterSubscriptionStore.Subscription> subscriptions = services.filters().getAll();
        final AdBlockEngine.Stats stats = adBlock.getStats();
        String[] items = new String[subscriptions.size() + 4];
        int[] icons = new int[items.length];
        items[0] = filterUpdateInProgress ? "过滤订阅正在更新" : "立即更新全部已启用订阅";
        items[1] = "添加 HTTPS 过滤订阅";
        items[2] = "编辑自定义规则";
        for (int i = 0; i < subscriptions.size(); i++) {
            FilterSubscriptionStore.Subscription item = subscriptions.get(i);
            String state = item.enabled ? "已启用" : "已停用";
            String count = item.ruleCount > 0 ? " · " + item.ruleCount + " 行" : " · 尚未下载";
            items[i + 3] = item.name + "：" + state + count + (item.error.length() == 0 ? "" : " · 上次失败");
        }
        items[items.length - 1] = "过滤诊断 · " + stats.networkRules + " 条网络 / " + stats.cosmeticRules + " 条外观规则";
        for (int i = 0; i < icons.length; i++) icons[i] = i == 1 ? BrowserIconView.PLUS : (i == items.length - 1 ? BrowserIconView.INFO : BrowserIconView.SHIELD);
        showActionSheet("过滤器中心", "EasyList 兼容子集 · HTTPS 更新 · ETag 缓存 · 原子替换", items, icons, new SheetHandler() {
            @Override public void onItem(int which) {
                if (which == 0) updateFilterSubscriptions(false);
                else if (which == 1) showAddFilterSubscription();
                else if (which == 2) showCustomFilterRules();
                else if (which == subscriptions.size() + 3) showFilterDiagnostics();
                else {
                    int index = which - 3;
                    if (index >= 0 && index < subscriptions.size()) showFilterSubscriptionActions(subscriptions.get(index));
                }
            }
        });
    }

    private void showFilterSubscriptionActions(final FilterSubscriptionStore.Subscription item) {
        boolean custom = item.id.startsWith("custom-");
        String[] actions = custom
                ? new String[] { item.enabled ? "停用此订阅" : "启用此订阅", "立即更新所有已启用订阅", "删除此订阅" }
                : new String[] { item.enabled ? "停用此订阅" : "启用此订阅", "立即更新所有已启用订阅" };
        int[] icons = custom
                ? new int[] { BrowserIconView.SHIELD, BrowserIconView.RELOAD, BrowserIconView.CLOSE }
                : new int[] { BrowserIconView.SHIELD, BrowserIconView.RELOAD };
        String subtitle = item.url + (item.error.length() == 0 ? "" : "\n上次错误：" + item.error);
        showActionSheet(item.name, subtitle, actions, icons, new SheetHandler() {
            @Override public void onItem(int which) {
                if (which == 0) {
                    services.filters().setEnabled(item.id, !item.enabled);
                    rebuildAdBlockRulesAsync(true);
                } else if (which == 1) {
                    updateFilterSubscriptions(false);
                } else {
                    services.filters().remove(item.id);
                    rebuildAdBlockRulesAsync(true);
                }
            }
        });
    }

    private void showAddFilterSubscription() {
        final EditText name = new EditText(this);
        name.setHint("名称（可选）");
        name.setSingleLine(true);
        final EditText url = new EditText(this);
        url.setHint("https://example.com/filter.txt");
        url.setSingleLine(true);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), 0, dp(18), 0);
        content.addView(name);
        content.addView(url);
        new AlertDialog.Builder(this).setTitle("添加过滤订阅")
                .setMessage("仅接受 HTTPS 文本规则列表。单个订阅上限 12 MB，更新会验证内容后原子替换。")
                .setView(content).setPositiveButton("添加并更新", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        try {
                            services.filters().add(name.getText().toString(), url.getText().toString().trim());
                            updateFilterSubscriptions(false);
                        } catch (Exception e) { toast("添加失败：" + safeMessage(e)); }
                    }
                }).setNegativeButton("取消", null).show();
    }

    private void showFilterDiagnostics() {
        AdBlockEngine.Stats stats = adBlock.getStats();
        new AlertDialog.Builder(this).setTitle("过滤诊断")
                .setMessage("已编译网络规则：" + stats.networkRules +
                        "\n已编译外观规则：" + stats.cosmeticRules +
                        "\n读取源文件行数：" + stats.sourceLines +
                        "\n本次运行检查请求：" + stats.inspectedRequests +
                        "\n已拦截请求：" + stats.blockedRequests +
                        "\n例外放行请求：" + stats.allowedRequests +
                        "\n\n支持 hosts、||域名^、@@、通配符、domain=、third-party、常见资源类型和 ## / #@#。出于安全与 WebView 兼容性，不执行过滤列表中的任意脚本片段。")
                .setPositiveButton("确定", null).show();
    }

    private void showCustomFilterRules() {
        final EditText input = new EditText(this);
        input.setText(prefs.getString("custom_filter_rules", ""));
        input.setHint("每行一条，例如：\n||ads.example.com^\n@@||trusted.example.com^");
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setMinLines(8);
        input.setMaxLines(14);
        input.setHorizontallyScrolling(false);
        input.setPadding(dp(14), dp(10), dp(14), dp(10));
        new AlertDialog.Builder(this).setTitle("自定义拦截规则")
                .setMessage("支持 hosts、||域名^、@@例外、通配符、domain=、third-party、资源类型以及 ## / #@# 外观规则。自定义文本上限 256 KB。")
                .setView(input).setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        String raw = input.getText().toString();
                        if (raw.length() > 256 * 1024) { toast("规则不能超过 256 KB"); return; }
                        prefs.edit().putString("custom_filter_rules", raw).apply();
                        rebuildAdBlockRulesAsync(true);
                    }
                }).setNegativeButton("取消", null).show();
    }

    private void showMainMenu() {
        String subtitle = currentHost().length() == 0 ? modeLabel() : currentHost() + " · " + modeLabel();
        boolean bookmarked = dataStore != null && dataStore.isBookmarked(currentPageUrl);
        String[] items = new String[] {
                "新建标签页",
                "标签页工具",
                "下载中心",
                "新建独立隐私窗口",
                bookmarked ? "取消收藏当前页面" : "收藏当前页面",
                "书签、历史与迁移",
                "当前网站设置",
                "桌面网站：" + (desktopMode ? "已开启" : "已关闭"),
                "网页深色模式：" + (nightMode ? "已开启" : "已关闭"),
                "页面工具",
                "广告与隐私过滤器",
                "用户脚本",
                "密码管理器",
                "浏览器设置",
                "个性化主页",
                "性能调度",
                "存储与数据",
                "MCP与开发者工具",
                "关于 Median"
        };
        int[] icons = new int[] {
                BrowserIconView.PLUS, BrowserIconView.TABS, BrowserIconView.DOWNLOAD, BrowserIconView.SHIELD,
                BrowserIconView.PLUS, BrowserIconView.TABS, BrowserIconView.SHIELD, BrowserIconView.DESKTOP,
                BrowserIconView.INFO, BrowserIconView.MENU, BrowserIconView.SHIELD,
                BrowserIconView.SCRIPT, BrowserIconView.KEY, BrowserIconView.MENU,
                BrowserIconView.HOME, BrowserIconView.SPEED, BrowserIconView.STORAGE,
                BrowserIconView.INFO, BrowserIconView.INFO
        };
        showActionSheet("Median", subtitle, items, icons, new SheetHandler() {
            @Override public void onItem(int which) {
                switch (which) {
                    case 0: newTab(); break;
                    case 1: showTabTools(); break;
                    case 2: showDownloadCenter(); break;
                    case 3: openPrivateWindow(); break;
                    case 4: toggleCurrentBookmark(); break;
                    case 5: showBrowserLibrary(); break;
                    case 6: showSiteSettings(); break;
                    case 7:
                        desktopMode = !desktopMode;
                        prefs.edit().putBoolean("desktop", desktopMode).apply();
                        applyDesktopMode();
                        webView.reload();
                        break;
                    case 8:
                        nightMode = !nightMode;
                        prefs.edit().putBoolean("night_mode", nightMode).apply();
                        applyDarkMode();
                        if (isHomeUrl(currentPageUrl)) showHome(); else webView.reload();
                        break;
                    case 9: showPageTools(); break;
                    case 10: showFilterCenter(); break;
                    case 11: showScriptCenter(); break;
                    case 12: showPasswordMenu(); break;
                    case 13: showBrowserSettings(); break;
                    case 14: showHomeCustomization(); break;
                    case 15: showPerformancePanel(); break;
                    case 16: showStoragePanel(); break;
                    case 17: showMcpPanel(); break;
                    case 18: showAbout(); break;
                    default: break;
                }
            }
        });
    }

    /** MCP 与开发者工具面板：开关、局域网、一键复制配置、状态与重启。 */
    private void showMcpPanel() {
        final McpController ctl = McpController.get();
        boolean enabled = ctl.enabled(this);
        boolean lan = ctl.lanEnabled(this);
        String token = ctl.token();
        String localUrl = ctl.endpointUrl(false);
        String lanUrl = ctl.endpointUrl(true);
        String tokenShown = token == null ? "（未生成）"
                : (token.length() > 12 ? token.substring(0, 12) + "…" : token);
        String subtitle = enabled ? "点击连接信息自动复制" : "MCP 服务未开启";
        final String wsPath = getSharedPreferences("median_mcp_v1", MODE_PRIVATE)
                .getString("workspace_dir", "/sdcard/Download/Median/Workspace");
        String[] items = new String[] {
                "本机地址（稳定）\n" + localUrl,
                "局域网地址\n" + lanUrl + (lan ? "" : "（未开启）"),
                "Token\n" + tokenShown,
                "MCP 服务：" + (enabled ? "已开启" : "已关闭"),
                "局域网访问：" + (lan ? "已开启" : "已关闭（仅本机）"),
                "复制 MCP 配置（JSON）",
                "复制 curl 测试命令",
                "查看状态与统计",
                "重启 MCP 服务",
                "远端 MCP 服务器管理",
                "AI++ 模式：" + (DeepSeekPP.isEnabled(this) ? "已开启" : "已关闭"),
                "工作区\n" + wsPath,
                "GitHub Token 设置\n" + (getSharedPreferences("median_mcp_v1", MODE_PRIVATE)
                        .getString("github_token", "").isEmpty() ? "未配置（配置后 AI 可操作 GitHub）" : "已配置（21 个 github_* 工具可用）")
        };
        showActionSheet("MCP 连接信息", subtitle, items, null, new SheetHandler() {
            @Override public void onItem(int which) {
                switch (which) {
                    case 0:
                        copyText(localUrl, "本机地址已复制");
                        break;
                    case 1:
                        copyText(lanUrl, "局域网地址已复制");
                        break;
                    case 2:
                        if (token == null) toast("Token 未生成，请先开启 MCP 服务");
                        else copyText(token, "Token 已复制");
                        break;
                    case 3:
                        boolean next = !ctl.enabled(MainActivity.this);
                        ctl.setEnabled(MainActivity.this, next);
                        if (next) ctl.start(MainActivity.this);
                        else ctl.stop();
                        toast(next ? "MCP 服务已开启" : "MCP 服务已关闭");
                        break;
                    case 4:
                        boolean nextLan = !ctl.lanEnabled(MainActivity.this);
                        ctl.setLanEnabled(MainActivity.this, nextLan);
                        ctl.restart(MainActivity.this);
                        toast(nextLan ? "局域网访问已开启" : "已切换为仅本机访问");
                        break;
                    case 5:
                        copyMcpConfig(false);
                        break;
                    case 6:
                        copyMcpConfig(true);
                        break;
                    case 7:
                        showMcpStatus(ctl);
                        break;
                    case 8:
                        ctl.restart(MainActivity.this);
                        toast("MCP 服务已重启");
                        break;
                    case 9:
                        showRemoteMcpManager();
                        break;
                    case 10:
                        toggleDeepSeekPP();
                        break;
                    case 11:
                        showWorkspaceDialog();
                        break;
                    case 12:
                        showGithubTokenDialog();
                        break;
                    default: break;
                }
            }
        });
    }

    /** 工作区设置对话框：输入目录路径（支持自动创建）。 */
    private void showWorkspaceDialog() {
        final String current = getSharedPreferences("median_mcp_v1", MODE_PRIVATE)
                .getString("workspace_dir", "/sdcard/Download/Median/Workspace");
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setText(current);
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("设置工作区目录")
                .setMessage("AI 生成的内容可通过 fs_write_file 保存到工作区（相对路径默认基于此目录）。\n支持输入绝对路径，目录不存在将自动创建。")
                .setView(input)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String path = input.getText().toString().trim();
                        if (path.isEmpty()) { toast("路径不能为空"); return; }
                        getSharedPreferences("median_mcp_v1", MODE_PRIVATE)
                                .edit().putString("workspace_dir", path).apply();
                        toast("工作区已设置：" + path);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
    /** GitHub Token 设置对话框：配置后 AI 可通过 21 个 github_* 内置工具操作 GitHub。 */
    private void showGithubTokenDialog() {
        final String current = getSharedPreferences("median_mcp_v1", MODE_PRIVATE)
                .getString("github_token", "");
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setHint("ghp_xxx 或 github_pat_xxx");
        input.setText(current);
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("GitHub Token 设置")
                .setMessage("配置后 AI 可直接调用 github_get_me / github_create_issue / github_merge_pull_request 等 21 个工具操作 GitHub。\n创建地址：github.com/settings/tokens（勾选 repo 权限）\n留空保存 = 清除 Token")
                .setView(input)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String t = input.getText().toString().trim();
                        getSharedPreferences("median_mcp_v1", MODE_PRIVATE)
                                .edit().putString("github_token", t).apply();
                        toast(t.isEmpty() ? "GitHub Token 已清除" : "GitHub Token 已保存");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
    /** 处理注入块上报的 [MDEVT] 工具事件（JSON: {t:start|done|flow_done, n:工具名, e:附加}）。 */
    private void handleToolEvent(String jsonStr) {
        try {
            if (jsonStr == null || jsonStr.isEmpty()) return;
            JSONObject evt = new JSONObject(jsonStr);
            String t = evt.optString("t", "");
            String name = evt.optString("n", "");
            JSONObject extra = evt.optJSONObject("e");
            String line;
            if ("start".equals(t)) {
                String argHint = "";
                if (extra != null) {
                    if (extra.has("path")) {
                        String p = extra.optString("path");
                        int idx = p.lastIndexOf('/');
                        argHint = " " + (idx >= 0 ? p.substring(idx + 1) : p);
                    }
                    else if (extra.has("pattern")) argHint = " " + extra.optString("pattern");
                    else if (extra.has("dir")) {
                        String p = extra.optString("dir");
                        int idx = p.lastIndexOf('/');
                        argHint = " " + (idx >= 0 ? p.substring(idx + 1) : p);
                    }
                }
                line = "\uD83D\uDD27 " + name + argHint;
            } else if ("done".equals(t)) {
                boolean ok = extra != null && extra.optBoolean("ok", false);
                long ms = extra != null ? extra.optLong("ms", 0) : 0;
                String dur = ms >= 1000 ? String.format(java.util.Locale.US, "%.1fs", ms / 1000.0) : (ms + "ms");
                line = (ok ? "\u2705 " : "\u274C ") + name + " (" + dur + ")";
                if (!activityResumed && ms >= 5000) {
                    notifyLongTask("工具执行完成", (ok ? "✅ " : "❌ ") + name + " 耗时 " + dur);
                }
            } else {
                line = "🤖 AI 回复完成";
                if (!activityResumed) notifyLongTask("AI 已完成回复", "回答已生成，返回 Median 查看");
            }
            toolEvents.addLast(line);
            while (toolEvents.size() > 20) toolEvents.removeFirst();
            updateToolPanel();
        } catch (Exception ignored) {}
    }

    /** 长任务完成通知（仅应用在后台时）。 */
    private void notifyLongTask(String title, String text) {
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                android.app.NotificationChannel ch = new android.app.NotificationChannel(
                        "median_tasks", "AI 任务", android.app.NotificationManager.IMPORTANCE_DEFAULT);
                ch.setShowBadge(true);
                nm.createNotificationChannel(ch);
            }
            Intent intent = new Intent(this, MainActivity.class);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(this, 0, intent,
                    android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
            android.app.Notification.Builder b = android.os.Build.VERSION.SDK_INT >= 26
                    ? new android.app.Notification.Builder(this, "median_tasks")
                    : new android.app.Notification.Builder(this);
            b.setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setWhen(System.currentTimeMillis());
            nm.notify(toolNotifyId++, b.build());
        } catch (RuntimeException ignored) {}
    }

    /** 构建右上角工具面板（懒加载）。 */
    private void buildToolPanel() {
        if (toolPanelView != null) return;
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(roundedBg(0xCC222222, dp(10)));
        panel.setPadding(dp(8), dp(4), dp(8), dp(4));

        toolPanelBadge = new TextView(this);
        toolPanelBadge.setText("🔧 AI 工具");
        toolPanelBadge.setTextColor(Color.WHITE);
        toolPanelBadge.setTextSize(11.5f);
        toolPanelBadge.setMaxLines(2);
        toolPanelBadge.setEllipsize(android.text.TextUtils.TruncateAt.END);
        toolPanelBadge.setPadding(dp(2), dp(4), dp(2), dp(4));
        toolPanelBadge.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleToolPanel(); }
        });
        panel.addView(toolPanelBadge, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        toolPanelList = new LinearLayout(this);
        toolPanelList.setOrientation(LinearLayout.VERTICAL);
        toolPanelList.setVisibility(View.GONE);
        panel.addView(toolPanelList, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int panelW = Math.max(dp(200), Math.min(dp(360), getResources().getDisplayMetrics().widthPixels - dp(16)));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(panelW, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        lp.setMargins(0, dp(70), dp(8), 0);
        rootFrame.addView(panel, lp);
        toolPanelView = panel;
        toolPanelView.setVisibility(View.GONE);
    }

    private android.graphics.drawable.Drawable roundedBg(int color, int radius) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(radius);
        return gd;
    }

    private void toggleToolPanel() {
        toolPanelExpanded = !toolPanelExpanded;
        updateToolPanel();
    }

    /** 更新面板：badge 显示最新一条，展开显示最近 8 条。 */
    private void updateToolPanel() {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    if (toolPanelView == null) buildToolPanel();
                    int panelW = Math.max(dp(200), Math.min(dp(360), getResources().getDisplayMetrics().widthPixels - dp(16)));
                    if (toolPanelView.getLayoutParams() != null) {
                        toolPanelView.getLayoutParams().width = panelW;
                        toolPanelView.requestLayout();
                    }
                    if (toolEvents.isEmpty()) {
                        toolPanelView.setVisibility(View.GONE);
                        return;
                    }
                    toolPanelView.setVisibility(View.VISIBLE);
                    String latest = toolEvents.getLast();
                    toolPanelBadge.setText(toolPanelExpanded ? "🔧 AI 工具调用（点击收起）" : latest);
                    toolPanelList.removeAllViews();
                    if (toolPanelExpanded) {
                        int start = Math.max(0, toolEvents.size() - 8);
                        int idx = 0;
                        for (String line : toolEvents) {
                            if (idx++ < start) continue;
                            TextView row = new TextView(MainActivity.this);
                            row.setText(line);
                            row.setTextColor(Color.WHITE);
                            row.setTextSize(11f);
                            row.setMaxLines(2);
                            row.setEllipsize(android.text.TextUtils.TruncateAt.END);
                            row.setPadding(dp(2), dp(3), dp(2), dp(3));
                            row.setOnClickListener(new View.OnClickListener() {
                                @Override public void onClick(View v) { toolPanelExpanded = false; updateToolPanel(); }
                            });
                            toolPanelList.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                        }
                        toolPanelList.setVisibility(View.VISIBLE);
                    } else {
                        toolPanelList.setVisibility(View.GONE);
                    }
                } catch (Exception ignored) {}
            }
        });
    }

    /** 远端 MCP 服务器管理：列表 + 添加 + 单服务器操作。 */
    private void showRemoteMcpManager() {
        final McpController ctl = McpController.get();
        JSONArray servers;
        try {
            servers = ctl.remoteMcpList(this);
        } catch (Exception e) {
            toast("读取失败: " + e.getMessage());
            return;
        }
        int n = servers == null ? 0 : servers.length();
        String[] items = new String[n + 1];
        items[0] = "➕ 添加服务器";
        for (int i = 0; i < n; i++) {
            JSONObject s = servers.optJSONObject(i);
            if (s == null) continue;
            String nm = s.optString("name", "");
            String u = s.optString("url", "");
            boolean en = s.optBoolean("enabled", true);
            items[i + 1] = nm + "\n" + u + "（" + (en ? "已启用" : "已禁用") + "）";
        }
        showActionSheet("远端 MCP 服务器", "填入本机或局域网 MCP 地址，DeepSeek 自动识别并调用（可配置多个）", items, null, new SheetHandler() {
            @Override public void onItem(int which) {
                if (which == 0) { showAddRemoteServerDialog(); return; }
                try {
                    JSONObject s = ctl.remoteMcpList(MainActivity.this).optJSONObject(which - 1);
                    if (s != null) showRemoteServerActions(s.optString("name", ""));
                } catch (Exception ignored) { }
            }
        });
    }
    /** 添加远端 MCP 服务器对话框（名称 + 地址 + Token 可选）。 */
    private void showAddRemoteServerDialog() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        content.setPadding(pad * 2, pad, pad * 2, 0);
        EditText nameInput = new EditText(this);
        nameInput.setHint("服务器名称（如：电脑MCP）");
        EditText urlInput = new EditText(this);
        urlInput.setHint("MCP 地址（如 http://192.168.1.100:8788/mcp）");
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText tokenInput = new EditText(this);
        tokenInput.setHint("Token（可选，留空则无需认证）");
        content.addView(nameInput);
        content.addView(urlInput);
        content.addView(tokenInput);
        new AlertDialog.Builder(this)
                .setTitle("添加远端 MCP 服务器")
                .setView(content)
                .setPositiveButton("添加", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        String name = nameInput.getText().toString().trim();
                        String url = urlInput.getText().toString().trim();
                        String token = tokenInput.getText().toString().trim();
                        if (name.isEmpty() || url.isEmpty()) { toast("名称和地址不能为空"); return; }
                        try {
                            JSONObject r = McpController.get().remoteMcpAdd(MainActivity.this, name, url, token);
                            if (r.has("error")) toast("添加失败: " + r.optString("error"));
                            else {
                                toast("已添加 " + name + "，正在探测工具…");
                                String probe = McpController.get().remoteDiscoverForUi(name);
                                showProbeResult(name, probe);
                            }
                        } catch (Exception e) { toast("添加失败: " + e.getMessage()); }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
    /** 单服务器操作：启用/禁用、探测、删除。 */
    private void showRemoteServerActions(final String name) {
        final McpController ctl = McpController.get();
        JSONObject server = null;
        try {
            JSONArray arr = ctl.remoteMcpList(this);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s != null && name.equals(s.optString("name"))) { server = s; break; }
            }
        } catch (Exception ignored) { }
        if (server == null) { toast("服务器不存在"); return; }
        final boolean enabled = server.optBoolean("enabled", true);
        String[] items = new String[] {
                (enabled ? "禁用该服务器" : "启用该服务器"),
                "探测工具列表",
                "删除该服务器",
                "返回"
        };
        showActionSheet("服务器：" + name, server.optString("url", ""), items, null, new SheetHandler() {
            @Override public void onItem(int which) {
                try {
                    switch (which) {
                        case 0:
                            ctl.remoteMcpUpdate(MainActivity.this, name, null, null, Boolean.valueOf(!enabled));
                            toast(enabled ? "已禁用 " + name : "已启用 " + name);
                            break;
                        case 1:
                            toast("正在探测 " + name + " …");
                            String probe = ctl.remoteDiscoverForUi(name);
                            showProbeResult(name, probe);
                            break;
                        case 2:
                            JSONObject r = ctl.remoteMcpRemove(MainActivity.this, name);
                            toast(r.has("error") ? "删除失败: " + r.optString("error") : "已删除 " + name);
                            break;
                        default: break;
                    }
                } catch (Exception e) {
                    toast("操作失败: " + e.getMessage());
                }
            }
        });
    }
    /** 显示探测结果（成功列出工具数，失败显示原因）。 */
    private void showProbeResult(String name, String probeJson) {
        String summary;
        try {
            JSONObject r = new JSONObject(probeJson);
            if (r.has("error")) {
                summary = "探测失败：" + r.optString("error");
            } else {
                int count = 0;
                JSONArray results = r.optJSONArray("results");
                if (results != null && results.length() > 0) {
                    JSONObject first = results.optJSONObject(0);
                    if (first != null) {
                        JSONArray tools = first.optJSONArray("tools");
                        if (tools != null) count = tools.length();
                        if (first.has("error")) summary = "探测失败：" + first.optString("error");
                        else summary = "探测成功：发现 " + count + " 个工具";
                    } else summary = "探测完成（无结果）";
                } else {
                    summary = "探测完成（未发现工具，请确认地址正确）";
                }
            }
        } catch (Exception e) {
            summary = "探测结果解析失败：" + e.getMessage() + "\n原始：" + (probeJson.length() > 200 ? probeJson.substring(0, 200) : probeJson);
        }
        toast(name + "：" + summary);
    }
    /** 复制文本到剪贴板并提示。 */
    private void copyText(String text, String message) {
        if (text == null || text.length() == 0) return;
        ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (manager != null) {
            manager.setPrimaryClip(ClipData.newPlainText("Median MCP", text));
            toast(message);
        }
    }

    private void copyMcpConfig(boolean curl) {
        McpController ctl = McpController.get();
        if (!ctl.isRunning()) {
            ctl.start(this);
        }
        String endpoint = ctl.endpointUrl(false);
        String token = ctl.token() == null ? "" : ctl.token();
        String text;
        if (curl) {
            text = "curl -s -X POST " + endpoint + " -H \"Authorization: Bearer " + token
                    + "\" -H \"Content-Type: application/json\" "
                    + "-d '{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}'";
        } else {
            text = "{\n  \"mcpServers\": {\n    \"median\": {\n      \"url\": \"" + endpoint + "\",\n"
                    + "      \"headers\": { \"Authorization\": \"Bearer " + token + "\" }\n    }\n  }\n}";
        }
        ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (manager != null) {
            manager.setPrimaryClip(ClipData.newPlainText("Median MCP", text));
            toast(curl ? "curl 命令已复制" : "MCP 配置已复制");
        }
    }

    private void showMcpStatus(McpController ctl) {
        long consoles = ctl.counter("console");
        long networks = ctl.counter("network");
        String status = "端口：" + ctl.port() + "\n"
                + "监听：" + ctl.listenHost() + "\n"
                + "本机地址：" + ctl.endpointUrl(false) + "\n"
                + "局域网地址：" + ctl.endpointUrl(true) + "\n"
                + "Token：" + (ctl.token() == null ? "-" : ctl.token()) + "\n"
                + "Console 记录：" + consoles + " 条\n"
                + "Network 记录：" + networks + " 条\n"
                + "运行中：" + ctl.isRunning();
        new AlertDialog.Builder(this)
                .setTitle("MCP 状态")
                .setMessage(status)
                .setPositiveButton("复制 Token", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        if (manager != null && ctl.token() != null) {
                            manager.setPrimaryClip(ClipData.newPlainText("Median MCP Token", ctl.token()));
                            toast("Token 已复制");
                        }
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void openPrivateWindow() {
        if (Build.VERSION.SDK_INT < 28) {
            toast("Android 8 的系统 WebView 不支持可靠的数据目录隔离；为避免伪无痕，本机不开放隐私窗口");
            return;
        }
        try { startActivity(new Intent(this, PrivateActivity.class)); }
        catch (Exception e) { toast("无法启动隐私窗口"); }
    }

    private void toggleCurrentBookmark() {
        String url = currentPageUrl;
        if (url == null || isHomeUrl(url) || (!url.startsWith("https://") && !url.startsWith("http://"))) {
            toast("当前页面不能收藏");
            return;
        }
        boolean added = dataStore.toggleBookmark(webView.getTitle(), url);
        toast(added ? "已添加到书签" : "已取消收藏");
    }

    private void showBrowserLibrary() {
        String[] items = new String[] {
                "书签（" + dataStore.bookmarks().size() + "）",
                "最近历史记录",
                "搜索历史记录",
                "离线页面（" + services.offlinePages().getAll().size() + "）",
                "导出书签备份",
                "导入书签备份",
                "导出加密完整备份",
                "导入加密完整备份"
        };
        int[] icons = new int[] { BrowserIconView.PLUS, BrowserIconView.TABS, BrowserIconView.SEARCH,
                BrowserIconView.STORAGE, BrowserIconView.SHARE, BrowserIconView.STORAGE,
                BrowserIconView.KEY, BrowserIconView.KEY };
        showActionSheet("书签与历史", "本地保存 · 不上传云端", items, icons, new SheetHandler() {
            @Override public void onItem(int which) {
                if (which == 0) showBookmarkList(false);
                else if (which == 1) showHistoryList("");
                else if (which == 2) showHistorySearch();
                else if (which == 3) showOfflinePages();
                else if (which == 4) beginBackupExport();
                else if (which == 5) beginBackupImport();
                else if (which == 6) beginFullBackupExport();
                else beginFullBackupImport();
            }
        });
    }

    private void showBookmarkList(final boolean manage) {
        final List<BrowserDataStore.Bookmark> all = dataStore.bookmarks();
        if (all.size() == 0) {
            new AlertDialog.Builder(this).setTitle("书签").setMessage("还没有书签。在网页菜单中选择“收藏当前页面”即可添加。")
                    .setPositiveButton("知道了", null).show();
            return;
        }
        String[] names = new String[all.size()];
        for (int i = 0; i < all.size(); i++) names[i] = all.get(i).title + "\n" + hostOf(all.get(i).url);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(manage ? "点按要删除的书签" : "书签")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        final BrowserDataStore.Bookmark item = all.get(which);
                        if (!manage) loadInput(item.url);
                        else new AlertDialog.Builder(MainActivity.this).setTitle("删除书签？").setMessage(item.title)
                                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface d, int w) {
                                        dataStore.removeBookmark(item.url);
                                        if (isHomeUrl(currentPageUrl)) showHome();
                                        toast("书签已删除");
                                    }
                                }).setNegativeButton("取消", null).show();
                    }
                })
                .setNeutralButton(manage ? "返回" : "管理", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { showBookmarkList(!manage); }
                }).setNegativeButton("关闭", null).create();
        dialog.show();
    }

    private void showHistoryList(final String query) {
        final List<BrowserDataStore.HistoryItem> all = dataStore.recentHistory(160, query);
        if (all.size() == 0) {
            new AlertDialog.Builder(this).setTitle("历史记录").setMessage(query.length() == 0 ? "还没有浏览历史。" : "没有匹配的历史记录。")
                    .setPositiveButton("知道了", null).show();
            return;
        }
        String[] names = new String[all.size()];
        long now = System.currentTimeMillis();
        for (int i = 0; i < all.size(); i++) {
            BrowserDataStore.HistoryItem item = all.get(i);
            CharSequence relative = android.text.format.DateUtils.getRelativeTimeSpanString(item.visitedAt, now, android.text.format.DateUtils.MINUTE_IN_MILLIS);
            names[i] = item.title + "\n" + hostOf(item.url) + " · " + relative;
        }
        new AlertDialog.Builder(this).setTitle(query.length() == 0 ? "最近历史记录" : "历史搜索：" + query)
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { loadInput(all.get(which).url); }
                }).setNeutralButton("清空历史", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { confirmClearHistory(); }
                }).setNegativeButton("关闭", null).show();
    }

    private void showHistorySearch() {
        final EditText input = new EditText(this);
        input.setHint("标题或网址");
        input.setSingleLine(true);
        input.setPadding(dp(18), dp(8), dp(18), dp(8));
        new AlertDialog.Builder(this).setTitle("搜索历史记录").setView(input)
                .setPositiveButton("搜索", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { showHistoryList(input.getText().toString().trim()); }
                }).setNegativeButton("取消", null).show();
    }

    private void confirmClearHistory() {
        new AlertDialog.Builder(this).setTitle("清空历史记录？").setMessage("书签、密码和网站登录状态不会删除。")
                .setPositiveButton("清空", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { dataStore.clearHistory(); webView.clearHistory(); toast("历史记录已清空"); }
                }).setNegativeButton("取消", null).show();
    }

    private void beginBackupExport() {
        try {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_TITLE, "Median-bookmarks.json");
            startActivityForResult(intent, BACKUP_EXPORT_REQUEST);
        } catch (Exception e) { toast("没有可用的文件保存器"); }
    }

    private void beginBackupImport() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            startActivityForResult(intent, BACKUP_IMPORT_REQUEST);
        } catch (Exception e) { toast("没有可用的文件选择器"); }
    }

    private void beginFullBackupExport() {
        persistSession();
        try {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_TITLE, "Median-encrypted-backup.json");
            startActivityForResult(intent, FULL_BACKUP_EXPORT_REQUEST);
        } catch (Exception e) { toast("没有可用的文件保存器"); }
    }

    private void beginFullBackupImport() {
        if (filterUpdateInProgress || scriptDownloadInProgress) { toast("请等待过滤器或脚本任务完成后再恢复"); return; }
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            startActivityForResult(intent, FULL_BACKUP_IMPORT_REQUEST);
        } catch (Exception e) { toast("没有可用的文件选择器"); }
    }

    private void showSiteSettings() {
        final String host = currentHost();
        if (host.length() == 0) { toast("主页没有网站设置"); return; }
        final SiteSettingsStore.SiteSettings settings = siteSettingsStore.forHost(host);
        String[] items = new String[] {
                "兼容模式：" + (settings.compatibilityMode ? "已开启" : "关闭") + "\n放宽 Cookie/用户触发弹窗/混合内容并暂停本站过滤",
                "JavaScript：" + triStateLabel(settings.javascript, "跟随全局"),
                "图片加载：" + triStateLabel(settings.images, "跟随全局"),
                "第三方 Cookie：" + triStateLabel(settings.thirdPartyCookies, "跟随全局"),
                "弹窗：" + triStateLabel(settings.popups, "仅允许用户触发"),
                "媒体自动播放：" + triStateLabel(settings.autoplay, "需要用户操作"),
                "位置：" + triStateLabel(settings.location, "每次询问"),
                "摄像头：" + triStateLabel(settings.camera, "每次询问"),
                "麦克风：" + triStateLabel(settings.microphone, "每次询问"),
                "跟踪参数清理：" + triStateLabel(settings.trackingProtection, cleanTrackingParameters ? "跟随全局开启" : "跟随全局关闭"),
                "桌面模式：" + triStateLabel(settings.desktop, "跟随全局"),
                "深色模式：" + triStateLabel(settings.dark, "跟随全局"),
                "文字缩放：" + settings.textZoom + "%",
                "重置此网站设置",
                "清除此网站 Cookie 与存储"
        };
        int[] icons = new int[] { BrowserIconView.SPEED, BrowserIconView.SCRIPT, BrowserIconView.INFO, BrowserIconView.KEY, BrowserIconView.SHIELD,
                BrowserIconView.SPEED, BrowserIconView.SHIELD, BrowserIconView.SHIELD, BrowserIconView.SHIELD,
                BrowserIconView.SHIELD, BrowserIconView.DESKTOP, BrowserIconView.INFO, BrowserIconView.SEARCH,
                BrowserIconView.RELOAD, BrowserIconView.CLOSE };
        showActionSheet("网站设置", host + " · 更改后自动刷新", items, icons, new SheetHandler() {
            @Override public void onItem(int which) {
                if (which == 0) { toggleCompatibilityMode(host, settings); return; }
                else if (which == 1) settings.javascript = nextTriState(settings.javascript);
                else if (which == 2) settings.images = nextTriState(settings.images);
                else if (which == 3) settings.thirdPartyCookies = nextTriState(settings.thirdPartyCookies);
                else if (which == 4) settings.popups = nextTriState(settings.popups);
                else if (which == 5) settings.autoplay = nextTriState(settings.autoplay);
                else if (which == 6) settings.location = nextTriState(settings.location);
                else if (which == 7) settings.camera = nextTriState(settings.camera);
                else if (which == 8) settings.microphone = nextTriState(settings.microphone);
                else if (which == 9) settings.trackingProtection = nextTriState(settings.trackingProtection);
                else if (which == 10) settings.desktop = nextTriState(settings.desktop);
                else if (which == 11) settings.dark = nextTriState(settings.dark);
                else if (which == 12) { showTextZoomDialog(host, settings); return; }
                else if (which == 13) siteSettingsStore.clear(host);
                else { confirmClearCurrentSiteData(); return; }
                if (which != 13) siteSettingsStore.save(host, settings);
                adBlockActiveByView.clear();
                applySiteSettings(webView, host);
                webView.reload();
            }
        });
    }


    private void toggleCompatibilityMode(String host, SiteSettingsStore.SiteSettings settings) {
        if (host == null || host.length() == 0 || settings == null) return;
        if (settings.compatibilityMode) {
            settings.compatibilityMode = false;
            siteSettingsStore.save(host, settings);
            adBlockActiveByView.clear();
            applySiteSettings(webView, host);
            webView.reload();
            toast("兼容模式已关闭");
            return;
        }
        enableCompatibilityForHost(host, true);
    }

    private void enableCompatibilityForHost(String host, boolean reload) {
        if (host == null || host.length() == 0 || siteSettingsStore == null) return;
        SiteSettingsStore.SiteSettings settings = siteSettingsStore.forHost(host);
        settings.compatibilityMode = true;
        siteSettingsStore.save(host, settings);
        adBlockActiveByView.clear();
        if (webView != null) applySiteSettings(webView, host);
        if (reload && webView != null) webView.reload();
        toast("已为此网站启用兼容模式");
    }

    private static boolean compatibilityRelevantError(int errorCode) {
        return errorCode == WebViewClient.ERROR_FAILED_SSL_HANDSHAKE ||
                errorCode == WebViewClient.ERROR_REDIRECT_LOOP ||
                errorCode == WebViewClient.ERROR_UNSUPPORTED_SCHEME ||
                errorCode == WebViewClient.ERROR_UNKNOWN;
    }

    private void maybeOfferCompatibilityMode(final String failedUrl, String reason) {
        if (isFinishing() || !activityResumed || compatibilityDialogShowing ||
                failedUrl == null || failedUrl.length() == 0 || !isNetworkPage(failedUrl)) return;
        final String host = hostOf(failedUrl);
        if (host.length() == 0 || siteSettingsStore == null) return;
        SiteSettingsStore.SiteSettings existing = siteSettingsStore.forHost(host);
        if (existing.compatibilityMode) return;
        long now = SystemClock.uptimeMillis();
        if (host.equals(lastCompatibilityOfferHost) && now - lastCompatibilityOfferAt < 15000L) return;
        lastCompatibilityOfferHost = host;
        lastCompatibilityOfferAt = now;
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("启用兼容模式重试？")
                .setMessage(reason + "：" + host + "\n\n将仅对这个网站放宽第三方 Cookie、用户触发弹窗和混合内容，并暂停本站广告/跟踪过滤。")
                .setPositiveButton("兼容重试", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        enableCompatibilityForHost(host, false);
                        if (webView != null) webView.loadUrl(failedUrl);
                    }
                })
                .setNegativeButton("取消", null);
        if (failedUrl.startsWith("https://")) {
            builder.setNeutralButton("HTTP 重试（不安全）", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                    enableCompatibilityForHost(host, false);
                    if (webView != null) webView.loadUrl("http://" + failedUrl.substring(8));
                }
            });
        }
        AlertDialog dialog = builder.create();
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface dialog) { compatibilityDialogShowing = false; }
        });
        compatibilityDialogShowing = true;
        dialog.show();
    }

    private void showTextZoomDialog(final String host, final SiteSettingsStore.SiteSettings settings) {
        final int[] values = new int[] { 80, 90, 100, 110, 125, 150, 175, 200 };
        String[] labels = new String[values.length];
        int checked = 2;
        for (int i = 0; i < values.length; i++) { labels[i] = values[i] + "%"; if (values[i] == settings.textZoom) checked = i; }
        new AlertDialog.Builder(this).setTitle("文字缩放").setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
                settings.textZoom = values[which];
                siteSettingsStore.save(host, settings);
                applySiteSettings(webView, host);
                webView.reload();
                dialog.dismiss();
            }
        }).setNegativeButton("取消", null).show();
    }

    private void confirmClearCurrentSiteData() {
        final String url = currentPageUrl;
        final String host = currentHost();
        if (url == null || host.length() == 0) return;
        new AlertDialog.Builder(this).setTitle("清除此网站数据？").setMessage("将尝试删除 " + host + " 的 Cookie 和本地存储，网站可能会退出登录。")
                .setPositiveButton("清除", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        CookieManager cookies = CookieManager.getInstance();
                        String raw = cookies.getCookie(url);
                        if (raw != null) {
                            String[] pairs = raw.split(";");
                            for (String pair : pairs) {
                                int equals = pair.indexOf('=');
                                String name = (equals < 0 ? pair : pair.substring(0, equals)).trim();
                                if (name.length() > 0) {
                                    String expired = name + "=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/";
                                    cookies.setCookie(url, expired);
                                    cookies.setCookie(url, expired + "; Secure");
                                    String domain = host;
                                    while (domain.indexOf('.') > 0) {
                                        cookies.setCookie(url, expired + "; Domain=" + domain);
                                        cookies.setCookie(url, expired + "; Domain=." + domain);
                                        cookies.setCookie(url, expired + "; Secure; Domain=." + domain);
                                        domain = domain.substring(domain.indexOf('.') + 1);
                                    }
                                }
                            }
                            cookies.flush();
                        }
                        Uri uri = currentUri();
                        if (uri != null) {
                            String origin = uri.getScheme() + "://" + uri.getAuthority();
                            android.webkit.WebStorage.getInstance().deleteOrigin(origin);
                            android.webkit.GeolocationPermissions.getInstance().clear(origin);
                        }
                        webView.reload();
                        toast("此网站数据已清除");
                    }
                }).setNegativeButton("取消", null).show();
    }

    private void showPageTools() {
        int offlineCount = services.offlinePages().getAll().size();
        PageAssistant assistant = services.assistant();
        String[] items = new String[] {
                "页面内查找", "切换沉浸阅读模式", assistant.isSpeaking() ? "停止朗读" : "朗读正文",
                "页面结构与统计", "TLS 证书与 SHA-256", "保存完整离线页面", "离线页面库 · " + offlineCount + " 页",
                "分享当前页面", "复制页面地址", "复制无跟踪参数地址", "翻译为简体中文",
                "打印或另存为 PDF", "媒体中心 · 已发现 " + mediaSniffer.size() + " 项", "当前页脚本命令"
        };
        int[] icons = new int[] { BrowserIconView.SEARCH, BrowserIconView.INFO, BrowserIconView.SPEED, BrowserIconView.INFO,
                BrowserIconView.SHIELD, BrowserIconView.STORAGE, BrowserIconView.STORAGE, BrowserIconView.SHARE, BrowserIconView.PLUS,
                BrowserIconView.SHIELD, BrowserIconView.INFO, BrowserIconView.STORAGE,
                BrowserIconView.SPEED, BrowserIconView.SCRIPT };
        showActionSheet("页面工具", currentHost(), items, icons, new SheetHandler() {
            @Override public void onItem(int which) {
                if (which == 0) showFindDialog();
                else if (which == 1) toggleReaderMode();
                else if (which == 2) toggleReadAloud();
                else if (which == 3) showPageInfo();
                else if (which == 4) showTlsInfo();
                else if (which == 5) saveOfflinePage();
                else if (which == 6) showOfflinePages();
                else if (which == 7) sharePage();
                else if (which == 8) copyPageUrl();
                else if (which == 9) copyCleanPageUrl();
                else if (which == 10) translatePage();
                else if (which == 11) printPage();
                else if (which == 12) probeAndShowMediaCenter();
                else showPageScriptCommands();
            }
        });
    }

    private void toggleReaderMode() {
        if (webView == null || !isNetworkPage(currentPageUrl)) { toast("当前页面不支持阅读模式"); return; }
        services.assistant().toggleReader(webView, nightMode, new PageAssistant.Callback<String>() {
            @Override public void onResult(String value, Exception error) {
                if (error != null) toast("阅读模式失败：" + safeMessage(error));
                else if ("none".equals(value)) toast("没有识别到足够长的正文");
                else toast("on".equals(value) ? "阅读模式已开启" : "阅读模式已关闭");
            }
        });
    }

    private void toggleReadAloud() {
        PageAssistant assistant = services.assistant();
        if (assistant.isSpeaking()) { assistant.stop(); toast("朗读已停止"); return; }
        assistant.speak(webView, new PageAssistant.Callback<Boolean>() {
            @Override public void onResult(Boolean value, Exception error) {
                if (error == null && Boolean.TRUE.equals(value)) toast("开始朗读正文");
                else toast(error == null ? "无法开始朗读" : "朗读失败：" + safeMessage(error));
            }
        });
    }

    private void showPageInfo() {
        if (webView == null) return;
        services.assistant().pageInfo(webView, new PageAssistant.Callback<JSONObject>() {
            @Override public void onResult(JSONObject info, Exception error) {
                if (error != null || info == null) { toast("无法分析页面"); return; }
                new AlertDialog.Builder(MainActivity.this).setTitle("页面信息")
                        .setMessage("标题：" + info.optString("title", "") +
                                "\n语言：" + info.optString("lang", "未声明") +
                                "\n编码：" + info.optString("charset", "") +
                                "\n字符：" + info.optLong("characters", 0L) +
                                "\n词语估算：" + info.optLong("words", 0L) +
                                "\n链接：" + info.optInt("links", 0) +
                                "\n图片：" + info.optInt("images", 0) +
                                "\n表单：" + info.optInt("forms", 0) +
                                "\n脚本：" + info.optInt("scripts", 0) + "\n\n" + info.optString("url", ""))
                        .setPositiveButton("确定", null).show();
            }
        });
    }

    private void showTlsInfo() {
        final String url = currentPageUrl;
        if (url == null || !url.startsWith("https://") || scriptExecutor == null || scriptExecutor.isShutdown()) {
            toast("当前页面不是可检查的 HTTPS 页面");
            return;
        }
        toast("正在独立验证服务器证书…");
        scriptExecutor.execute(new Runnable() {
            @Override public void run() {
                TlsInspector.Result value = null;
                Exception failure = null;
                try { value = TlsInspector.inspect(url); } catch (Exception e) { failure = e; }
                final TlsInspector.Result result = value;
                final Exception error = failure;
                uiHandler.post(new Runnable() {
                    @Override public void run() {
                        if (error != null) { toast("证书检查失败：" + safeMessage(error)); return; }
                        new AlertDialog.Builder(MainActivity.this).setTitle("TLS 证书")
                                .setMessage(result.summary()).setPositiveButton("确定", null).show();
                    }
                });
            }
        });
    }

    private void saveOfflinePage() {
        if (webView == null || !isNetworkPage(currentPageUrl)) { toast("仅支持保存 HTTP(S) 页面"); return; }
        toast("正在保存完整离线页面…");
        services.offlinePages().save(webView, webView.getTitle(), currentPageUrl, new OfflinePageStore.Callback() {
            @Override public void onComplete(OfflinePageStore.Entry entry, Exception error) {
                toast(error == null ? "离线页面已保存 · " + humanBytes(entry.size) : "保存失败：" + safeMessage(error));
            }
        });
    }

    private void showOfflinePages() {
        final List<OfflinePageStore.Entry> entries = services.offlinePages().getAll();
        if (entries.size() == 0) { toast("还没有离线页面"); return; }
        String[] labels = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            OfflinePageStore.Entry entry = entries.get(i);
            labels[i] = safeTitle(entry.title, entry.url) + " · " + humanBytes(entry.size) + "\n" + hostOf(entry.url);
        }
        new AlertDialog.Builder(this).setTitle("离线页面").setItems(labels, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) { showOfflineActions(entries.get(which)); }
        }).setNegativeButton("关闭", null).show();
    }

    private void showOfflineActions(final OfflinePageStore.Entry entry) {
        String[] items = new String[] { "离线打开", "打开原网页", "分享归档文件", "删除离线页面" };
        showActionSheet(entry.title, entry.url, items, new int[] { BrowserIconView.PLUS, BrowserIconView.RELOAD,
                BrowserIconView.SHARE, BrowserIconView.CLOSE }, new SheetHandler() {
            @Override public void onItem(int which) {
                if (which == 0) {
                    webView.getSettings().setAllowContentAccess(true);
                    webView.getSettings().setBlockNetworkLoads(true);
                    webView.getSettings().setJavaScriptEnabled(false);
                    webView.loadUrl(services.offlinePages().uriFor(entry).toString());
                }
                else if (which == 1) loadInput(entry.url);
                else if (which == 2) {
                    Intent share = new Intent(Intent.ACTION_SEND);
                    share.setType("application/x-mimearchive");
                    share.putExtra(Intent.EXTRA_STREAM, services.offlinePages().uriFor(entry));
                    share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try { startActivity(Intent.createChooser(share, "分享离线页面")); }
                    catch (Exception e) { toast("没有可用的分享应用"); }
                } else {
                    services.offlinePages().remove(entry.file);
                    toast("离线页面已删除");
                }
            }
        });
    }

    private void copyCleanPageUrl() {
        String url = currentPageUrl;
        if (url == null || !isNetworkPage(url)) return;
        String cleaned = UrlCleaner.cleanTracking(url);
        android.content.ClipboardManager manager = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (manager != null) manager.setPrimaryClip(android.content.ClipData.newPlainText("无跟踪参数地址", cleaned));
        toast(url.equals(cleaned) ? "地址中没有已知跟踪参数" : "已复制清理后的地址");
    }

    private void showBrowserSettings() {
        String[] items = new String[] {
                "HTTPS 优先：" + (httpsOnly ? "已开启" : "已关闭"),
                "跟踪参数清理：" + (cleanTrackingParameters ? "已开启" : "已关闭"),
                "第三方 Cookie：" + (acceptThirdPartyCookies ? "全局允许" : "默认阻止"),
                "每次打开：" + homeOpenBehaviorLabel(),
                "默认搜索引擎：" + searchEngineLabel(),
                "配置自定义搜索引擎",
                "个性化主页",
                "清空浏览历史",
                "打开本地快速主页",
                "关于 Median"
        };
        int[] icons = new int[] { BrowserIconView.SHIELD, BrowserIconView.SHIELD, BrowserIconView.SHIELD,
                BrowserIconView.TABS, BrowserIconView.SEARCH, BrowserIconView.SEARCH, BrowserIconView.HOME,
                BrowserIconView.CLOSE, BrowserIconView.HOME, BrowserIconView.INFO };
        showActionSheet("浏览器设置", "隐私优先 · 设置仅保存在本机", items, icons, new SheetHandler() {
            @Override public void onItem(int which) {
                if (which == 0) { httpsOnly = !httpsOnly; prefs.edit().putBoolean("https_only", httpsOnly).apply(); }
                else if (which == 1) {
                    cleanTrackingParameters = !cleanTrackingParameters;
                    prefs.edit().putBoolean("clean_tracking_parameters", cleanTrackingParameters).apply();
                }
                else if (which == 2) {
                    acceptThirdPartyCookies = !acceptThirdPartyCookies;
                    prefs.edit().putBoolean("accept_third_party_cookies", acceptThirdPartyCookies).apply();
                    applyCookiePolicyToAll();
                    toast(acceptThirdPartyCookies ? "第三方 Cookie 已全局允许" : "第三方 Cookie 已默认阻止，可按网站放行");
                }
                else if (which == 3) showHomeOpenBehaviorChoice();
                else if (which == 4) showSearchEngineDialog();
                else if (which == 5) showCustomSearchDialog();
                else if (which == 6) showHomeCustomization();
                else if (which == 7) confirmClearHistory();
                else if (which == 8) showHome();
                else showAbout();
            }
        });
    }

    private void toggleDeepSeekPP() {
        final boolean enable = !DeepSeekPP.isEnabled(this);
        try {
            if (enable) {
                if (scriptStore == null) scriptStore = new UserScriptStore(this, buildDsppAssetMap());
                DeepSeekPP.install(this, scriptStore);
            } else {
                DeepSeekPP.uninstall(scriptStore);
            }
            DeepSeekPP.setEnabled(this, enable);
            toast(enable ? "AI++ 模式已开启，访问 chat.deepseek.com / chatgpt.com 生效" : "AI++ 模式已关闭");
            refreshUserScriptRegistrations(enable);
            if (isHomeUrl(currentPageUrl)) showHome();
        } catch (Exception e) {
            toast("DeepSeek++ 切换失败：" + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }
    /** 读取内置 DeepSeek++ 脚本（assets/dspp/），组装 sourceUrl→code 映射。
     * 由 UserScriptStore 加载时用 assets 最新代码覆盖 prefs 旧/损坏数据，彻底绕开大脚本存储故障。 */
    private Map<String, String> buildDsppAssetMap() {
        Map<String, String> map = new HashMap<String, String>();
        map.put("asset://median/dspp-mainworld", readDsppAsset("dspp/dspp_mainworld.js"));
        map.put("asset://median/dspp-content", readDsppAsset("dspp/dspp_content.js"));
        map.put("asset://median/gptpp-mainworld", readDsppAsset("dspp/gptpp_mainworld.js"));
        return map;
    }
    private String readDsppAsset(String path) {
        try {
            InputStream in = getAssets().open(path);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            int len = out.size();
            android.util.Log.d("MedianDspp", "readAsset " + path + " len=" + len);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            android.util.Log.d("MedianDspp", "readAsset " + path + " FAIL " + e);
            return "";
        }
    }
    private void showHomeCustomization() {
        if (!isHomeUrl(currentPageUrl)) showHome();
        final HomePageConfig value = homePageConfig();
        boolean hasCustomHtml = CustomHomeHtml.valid(prefs.getString("home_custom_html", ""));
        boolean hasCustomCss = CustomHomeCss.clean(prefs.getString("home_custom_css", "")).length() > 0;
        String[] items = new String[] {
                "页面布局\n调整整体排版、副标题、时钟与强调色",
                "每次打开\n当前：" + homeOpenBehaviorLabel(),
                "Logo\n当前：" + logoModeLabel(value),
                "搜索框\n当前：" + (!value.showSearch ? "隐藏" : ("glass".equals(value.searchStyle) ? "磨砂" : "纯色")),
                "背景\n当前：" + (value.hasWallpaper ? "自定义壁纸" : "默认纯色"),
                "快捷网站\n当前：" + (value.showShortcuts ? value.shortcutColumns + " 列" : "隐藏"),
                "自定义主页\n当前：" + (value.customHtmlEnabled ? "完整 HTML" :
                        (hasCustomCss ? "自定义 CSS" : (hasCustomHtml ? "HTML 已保存、未启用" : "未设置")))
        };
        int[] icons = new int[] {
                BrowserIconView.TABS, BrowserIconView.HOME, BrowserIconView.INFO,
                BrowserIconView.SEARCH, BrowserIconView.DESKTOP, BrowserIconView.TABS,
                BrowserIconView.SCRIPT
        };
        showActionSheet("个性化主页", "点击项目进入设置 · 修改后立即预览 · 关闭面板返回网页", items, icons, new SheetHandler() {
            @Override public void onItem(int which) {
                if (which == 0) showHomeLayoutCustomization();
                else if (which == 1) showHomeOpenBehaviorChoice(true);
                else if (which == 2) showLogoCustomization();
                else if (which == 3) showSearchCustomization();
                else if (which == 4) showBackgroundCustomization();
                else if (which == 5) showShortcutCustomization();
                else showCustomCodeCustomization();
            }
        });
    }

    private void showHomeLayoutCustomization() {
        final HomePageConfig value = homePageConfig();
        String[] items = new String[] {
                "副标题：" + settingPreview(value.subtitle, 16, "未设置"),
                "页面排列方式\n当前：" + ("compact".equals(value.layout) ? "紧凑" : "居中"),
                "主页时钟：" + (value.showClock ? "显示" : "隐藏"),
                "强调色：" + accentLabel(value.accent),
                "左上角品牌：" + (value.showCornerBrand ? "显示" : "隐藏"),
                "恢复全部默认外观"
        };
        showActionSheet("页面布局", "点击项目修改；选中后立即应用并返回此页", items,
                new int[] { BrowserIconView.INFO, BrowserIconView.TABS, BrowserIconView.INFO,
                        BrowserIconView.SPEED, BrowserIconView.TABS, BrowserIconView.CLOSE }, new SheetHandler() {
                    @Override public void onItem(int which) {
                        if (which == 0) showHomeTextSetting(false, HOME_SECTION_LAYOUT);
                        else if (which == 1) showHomeStringChoiceInSection("页面布局", "home_layout",
                                new String[] { "center", "compact" }, new String[] { "居中", "紧凑" }, value.layout, HOME_SECTION_LAYOUT);
                        else if (which == 2) toggleHomeBooleanInSection("home_show_clock", value.showClock, HOME_SECTION_LAYOUT);
                        else if (which == 3) showHomeStringChoiceInSection("主页强调色", "home_accent",
                                new String[] { "blue", "violet", "green", "orange", "rose", "teal" },
                                new String[] { "蓝色", "紫色", "绿色", "橙色", "玫红", "青色" }, value.accent, HOME_SECTION_LAYOUT);
                        else if (which == 4) toggleHomeBooleanInSection("home_show_corner", value.showCornerBrand, HOME_SECTION_LAYOUT);
                        else confirmResetHomeCustomization();
                    }
                });
    }

    private void showLogoCustomization() {
        final HomePageConfig value = homePageConfig();
        ArrayList<String> labels = new ArrayList<String>();
        ArrayList<Integer> actions = new ArrayList<Integer>();
        labels.add("Logo 类型：" + logoModeLabel(value)); actions.add(Integer.valueOf(0));
        if ("text".equals(value.logoMode)) {
            labels.add("Logo 文字：" + settingPreview(value.title, 18, "Median")); actions.add(Integer.valueOf(1));
            labels.add("文字配色：" + logoStyleLabel(value.logoStyle)); actions.add(Integer.valueOf(2));
            labels.add("文字排版：" + value.logoFontSize + " px · " + value.logoFontWeight + " · " +
                    logoLetterSpacingLabel(value.logoLetterSpacing)); actions.add(Integer.valueOf(3));
            labels.add("高级文字代码"); actions.add(Integer.valueOf(7));
        } else if ("image".equals(value.logoMode)) {
            labels.add(value.hasLogo ? "更换 Logo 图片" : "选择 Logo 图片"); actions.add(Integer.valueOf(8));
            labels.add("图片宽度：" + value.logoImageWidth + " px"); actions.add(Integer.valueOf(9));
            labels.add("图片高度：" + value.logoImageHeight + " px"); actions.add(Integer.valueOf(10));
            labels.add("图片圆角：" + value.logoImageRadius + "%"); actions.add(Integer.valueOf(11));
        }
        final int[] mapped = new int[actions.size()];
        for (int i = 0; i < mapped.length; i++) mapped[i] = actions.get(i).intValue();
        String[] items = labels.toArray(new String[labels.size()]);
        int[] icons = new int[items.length];
        for (int i = 0; i < icons.length; i++) icons[i] = i == 0 ? BrowserIconView.INFO : BrowserIconView.SPEED;
        showActionSheet("Logo", "点击项目修改；高级文字代码只有保存后才会生效", items, icons, new SheetHandler() {
            @Override public void onItem(int which) {
                int action = mapped[which];
                if (action == 0) showLogoModeChoice(value);
                else if (action == 1) showHomeTextSetting(true, HOME_SECTION_LOGO);
                else if (action == 2) showLogoStyleChoice(value, HOME_SECTION_LOGO);
                else if (action == 3) showLogoTypographyCustomization();
                else if (action == 7) showLogoCodeEditor(HOME_SECTION_LOGO);
                else if (action == 8) chooseHomeImage(HomeImageStore.Kind.LOGO);
                else if (action == 9) showHomeIntegerChoiceInSection("图片宽度", "home_logo_image_width",
                        new int[] { 64, 88, 112, 132, 160, 200, 240 },
                        new String[] { "64 px", "88 px", "112 px", "132 px", "160 px", "200 px", "240 px" }, value.logoImageWidth, HOME_SECTION_LOGO);
                else if (action == 10) showHomeIntegerChoiceInSection("图片高度", "home_logo_image_height",
                        new int[] { 48, 64, 80, 96, 112, 144, 176 },
                        new String[] { "48 px", "64 px", "80 px", "96 px", "112 px", "144 px", "176 px" }, value.logoImageHeight, HOME_SECTION_LOGO);
                else showHomeIntegerChoiceInSection("图片圆角", "home_logo_image_radius",
                        new int[] { 0, 8, 16, 24, 32, 50 },
                        new String[] { "0%", "8%", "16%", "24%", "32%", "50% 圆形" }, value.logoImageRadius, HOME_SECTION_LOGO);
            }
        });
    }

    private void showLogoTypographyCustomization() {
        final HomePageConfig value = homePageConfig();
        String[] items = new String[] {
                "字号：" + value.logoFontSize + " px",
                "字重：" + value.logoFontWeight,
                "字间距：" + logoLetterSpacingLabel(value.logoLetterSpacing),
                "渐变方向：" + logoGradientAngleLabel(value.logoGradientAngle)
        };
        showActionSheet("文字排版", "统一调整 Logo 的尺寸与间隔", items,
                new int[] { BrowserIconView.SPEED, BrowserIconView.SPEED, BrowserIconView.SPEED, BrowserIconView.SPEED },
                new SheetHandler() {
                    @Override public void onItem(int which) {
                        if (which == 0) showHomeIntegerChoiceInSection("Logo 字号", "home_logo_font_size",
                                new int[] { 28, 36, 44, 47, 52, 64, 72, 88 },
                                new String[] { "28 px", "36 px", "44 px", "47 px", "52 px", "64 px", "72 px", "88 px" }, value.logoFontSize, HOME_SECTION_LOGO);
                        else if (which == 1) showHomeIntegerChoiceInSection("Logo 字重", "home_logo_font_weight",
                                new int[] { 300, 400, 500, 600, 700, 800, 900 },
                                new String[] { "300 细", "400 常规", "500 中等", "600 半粗", "700 粗体", "800 特粗", "900 黑体" }, value.logoFontWeight, HOME_SECTION_LOGO);
                        else if (which == 2) showHomeIntegerChoiceInSection("Logo 字间距", "home_logo_letter_spacing",
                                new int[] { -3, -2, -1, 0, 1, 2, 3, 4, 6, 8, 10 },
                                new String[] { "-3 px", "-2 px", "-1 px", "标准", "+1 px", "+2 px", "+3 px", "+4 px", "+6 px", "+8 px", "+10 px" }, value.logoLetterSpacing, HOME_SECTION_LOGO);
                        else showHomeIntegerChoiceInSection("渐变方向", "home_logo_gradient_angle",
                                new int[] { 90, 135, 180, 45, 0 },
                                new String[] { "左 → 右", "左上 → 右下", "上 → 下", "左下 → 右上", "下 → 上" }, value.logoGradientAngle, HOME_SECTION_LOGO);
                    }
                });
    }

    private void showLogoModeChoice(final HomePageConfig value) {
        int checked;
        boolean defaults = "text".equals(value.logoMode) && "Median".equals(value.title) &&
                "median".equals(value.logoStyle) && value.logoFontSize == 47 &&
                value.logoFontWeight == 720 && value.logoLetterSpacing == 0;
        if ("none".equals(value.logoMode)) checked = 3;
        else if ("image".equals(value.logoMode)) checked = 2;
        else checked = defaults ? 0 : 1;
        new AlertDialog.Builder(this).setTitle("Logo 类型").setSingleChoiceItems(
                new String[] { "默认 Median", "自定义文字", "图片", "无" }, checked,
                new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        SharedPreferences.Editor editor = prefs.edit();
                        if (which == 0) {
                            editor.putString("home_logo_mode", "text").putString("home_title", "Median")
                                    .putString("home_logo_style", "median").remove("home_logo_code")
                                    .putInt("home_logo_font_size", 47).putInt("home_logo_font_weight", 720)
                                    .putInt("home_logo_letter_spacing", 0).apply();
                            homeCustomizationChanged("已恢复默认 Median Logo", HOME_SECTION_LOGO);
                        } else if (which == 1) {
                            editor.putString("home_logo_mode", "text").apply();
                            homeCustomizationChanged(null, HOME_SECTION_LOGO);
                        } else if (which == 2) {
                            editor.putString("home_logo_mode", "image").apply();
                            if (value.hasLogo) homeCustomizationChanged(null, HOME_SECTION_LOGO);
                            else {
                                if (isHomeUrl(currentPageUrl)) showHome();
                                chooseHomeImage(HomeImageStore.Kind.LOGO);
                            }
                        } else {
                            editor.putString("home_logo_mode", "none").apply();
                            homeCustomizationChanged("主页 Logo 已隐藏", HOME_SECTION_LOGO);
                        }
                    }
                }).setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { continueHomeSection(HOME_SECTION_LOGO); }
                }).show();
    }

    private void showSearchCustomization() {
        final HomePageConfig value = homePageConfig();
        String[] items = new String[] {
                "搜索框：" + (!value.showSearch ? "隐藏" : ("glass".equals(value.searchStyle) ? "磨砂" : "纯色")),
                "搜索引擎按钮：" + (value.showEngines ? "显示" : "隐藏"),
                "默认搜索引擎：" + searchEngineLabel()
        };
        showActionSheet("搜索框", "功能保持内置，CSS 只修改外观", items,
                new int[] { BrowserIconView.SEARCH, BrowserIconView.SEARCH, BrowserIconView.SEARCH }, new SheetHandler() {
                    @Override public void onItem(int which) {
                        if (which == 0) showHomeSearchChoice(value, HOME_SECTION_SEARCH);
                        else if (which == 1) toggleHomeBooleanInSection("home_show_engines", value.showEngines, HOME_SECTION_SEARCH);
                        else showSearchEngineDialog();
                    }
                });
    }

    private void showBackgroundCustomization() {
        final HomePageConfig value = homePageConfig();
        String[] items = new String[] {
                "主页壁纸：" + (value.hasWallpaper ? "已设置" : "默认纯色"),
                "壁纸遮罩：" + value.wallpaperDim + "%",
                "壁纸模糊：" + (value.wallpaperBlur == 0 ? "关闭" : value.wallpaperBlur + " px"),
                "壁纸显示：" + ("contain".equals(value.wallpaperFit) ? "完整显示" : "填充裁剪")
        };
        showActionSheet("背景", "壁纸继续独立于 CSS 与 HTML", items,
                new int[] { BrowserIconView.DESKTOP, BrowserIconView.SHIELD, BrowserIconView.SPEED, BrowserIconView.DESKTOP },
                new SheetHandler() {
                    @Override public void onItem(int which) {
                        if (which == 0) showHomeAssetActions(HomeImageStore.Kind.WALLPAPER);
                        else if (which == 1) showHomeIntegerChoiceInSection("壁纸遮罩", "home_wallpaper_dim",
                                new int[] { 0, 15, 28, 40, 55, 70 }, new String[] { "0%", "15%", "28%", "40%", "55%", "70%" }, value.wallpaperDim, HOME_SECTION_BACKGROUND);
                        else if (which == 2) showHomeIntegerChoiceInSection("壁纸模糊", "home_wallpaper_blur",
                                new int[] { 0, 3, 6, 9, 12 }, new String[] { "关闭", "3 px", "6 px", "9 px", "12 px" }, value.wallpaperBlur, HOME_SECTION_BACKGROUND);
                        else showHomeStringChoiceInSection("壁纸显示方式", "home_wallpaper_fit",
                                new String[] { "cover", "contain" }, new String[] { "填充裁剪", "完整显示" }, value.wallpaperFit, HOME_SECTION_BACKGROUND);
                    }
                });
    }

    private void showShortcutCustomization() {
        final HomePageConfig value = homePageConfig();
        String[] items = new String[] {
                "快捷网站：" + (value.showShortcuts ? "显示" : "隐藏"),
                "入口列数：" + value.shortcutColumns + " 列",
                "入口形状：" + tileShapeLabel(value.tileShape),
                "管理快捷网站"
        };
        showActionSheet("快捷网站", "显示内容来自本地书签", items,
                new int[] { BrowserIconView.TABS, BrowserIconView.TABS, BrowserIconView.TABS, BrowserIconView.INFO },
                new SheetHandler() {
                    @Override public void onItem(int which) {
                        if (which == 0) toggleHomeBooleanInSection("home_show_shortcuts", value.showShortcuts, HOME_SECTION_SHORTCUTS);
                        else if (which == 1) showHomeIntegerChoiceInSection("快捷入口列数", "home_shortcut_columns",
                                new int[] { 3, 4, 5 }, new String[] { "3 列", "4 列", "5 列" }, value.shortcutColumns, HOME_SECTION_SHORTCUTS);
                        else if (which == 2) showHomeStringChoiceInSection("快捷入口形状", "home_tile_shape",
                                new String[] { "rounded", "circle", "square" }, new String[] { "圆角方形", "圆形", "小圆角方形" }, value.tileShape, HOME_SECTION_SHORTCUTS);
                        else showBookmarkList(true);
                    }
                });
    }

    private void showCustomCodeCustomization() {
        HomePageConfig value = homePageConfig();
        boolean hasCss = value.customCss.length() > 0;
        boolean hasHtml = CustomHomeHtml.valid(prefs.getString("home_custom_html", ""));
        String[] items = new String[] {
                "自定义 CSS\n" + (hasCss ? (value.customHtmlEnabled ? "已保存，HTML 模式下暂不显示" : "已启用") : "未设置") + " · 保留内置功能（推荐）",
                "完整 HTML\n" + (value.customHtmlEnabled ? "已启用" : (hasHtml ? "已保存，未启用" : "未设置")) + " · 完全替换主页，JS 在沙箱内运行"
        };
        showActionSheet("自定义主页", "选择一种方式进入编辑或启停管理", items,
                new int[] { BrowserIconView.SPEED, BrowserIconView.SCRIPT }, new SheetHandler() {
                    @Override public void onItem(int which) {
                        if (which == 0) showCustomCssActions();
                        else showCustomHomeActions(HOME_SECTION_CODE);
                    }
                });
    }

    private void showHomeOpenBehaviorChoice() { showHomeOpenBehaviorChoice(false); }

    private void showHomeOpenBehaviorChoice(final boolean returnToCustomization) {
        final String[] modes = new String[] { HomeOpenPolicy.OPEN_HOME, HomeOpenPolicy.OPEN_CUSTOM_URL,
                HomeOpenPolicy.KEEP_LAST };
        String[] labels = new String[] {
                "打开主页\n使用内置主页或自定义 HTML 主页",
                "自定义页面\n将一个网页链接设为主页",
                "保留上一次访问的内容\n冷启动时恢复上次标签页"
        };
        int checked = 0;
        String current = homeOpenMode();
        for (int i = 0; i < modes.length; i++) if (modes[i].equals(current)) checked = i;
        new AlertDialog.Builder(this).setTitle("每次打开").setSingleChoiceItems(labels, checked,
                new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        if (HomeOpenPolicy.OPEN_CUSTOM_URL.equals(modes[which])) {
                            showCustomHomeUrlEditor(returnToCustomization);
                            return;
                        }
                        restoreTabs = HomeOpenPolicy.KEEP_LAST.equals(modes[which]);
                        prefs.edit().putString("home_open_mode", modes[which])
                                .putBoolean("restore_tabs", restoreTabs).apply();
                        toast(restoreTabs ? "下次启动将恢复上次内容" : "已设为打开主页");
                        if (returnToCustomization) continueHomeCustomization();
                    }
                }).setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        if (returnToCustomization) continueHomeCustomization();
                    }
                }).show();
    }

    private void showCustomHomeUrlEditor(final boolean returnToCustomization) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("例如：www.example.com");
        input.setText(prefs.getString("home_custom_url", ""));
        input.setSelectAllOnFocus(true);
        input.setPadding(dp(16), dp(8), dp(16), dp(8));
        final AlertDialog dialog = new AlertDialog.Builder(this).setTitle("自定义页面")
                .setMessage("输入网页地址后，主页键、冷启动和每个新建标签页都会打开它。没有协议时自动使用 HTTPS。")
                .setView(input).setPositiveButton("保存并使用", null)
                .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface value, int which) {
                        if (returnToCustomization) continueHomeCustomization();
                    }
                }).create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface ignored) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View view) {
                        String normalized = normalizeConfiguredHomeUrl(input.getText().toString());
                        if (normalized.length() == 0) { toast("请输入有效的 HTTP 或 HTTPS 网页地址"); return; }
                        boolean saved = prefs.edit().putString("home_open_mode", HomeOpenPolicy.OPEN_CUSTOM_URL)
                                .putString("home_custom_url", normalized).putBoolean("restore_tabs", false).commit();
                        if (!saved) { toast("主页设置保存失败"); return; }
                        restoreTabs = false;
                        dialog.dismiss();
                        toast("自定义页面已设为主页");
                        if (returnToCustomization) continueHomeCustomization();
                    }
                });
            }
        });
        dialog.show();
    }

    private void showCustomCssActions() {
        final String saved = CustomHomeCss.clean(prefs.getString("home_custom_css", ""));
        if (saved.length() == 0) { showCustomCssEditor(); return; }
        new AlertDialog.Builder(this).setTitle("自定义 CSS")
                .setItems(new String[] { "编辑 CSS", "清除 CSS" }, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) showCustomCssEditor();
                        else {
                            prefs.edit().remove("home_custom_css").apply();
                            homeCustomizationChanged("自定义 CSS 已清除", HOME_SECTION_CODE);
                        }
                    }
                }).setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { continueHomeSection(HOME_SECTION_CODE); }
                }).show();
    }

    private void showCustomCssEditor() {
        final EditText input = compactCodeEditor();
        String saved = CustomHomeCss.clean(prefs.getString("home_custom_css", ""));
        input.setText(saved.length() == 0 ? CustomHomeCss.EXAMPLE : saved);
        input.setHint(CustomHomeCss.EXAMPLE);
        final AlertDialog dialog = new AlertDialog.Builder(this).setTitle("自定义 CSS")
                .setMessage("推荐方案：只改变外观，搜索、时钟和快捷网站继续由 Median 负责。常用选择器：.brand、.search、.engines、.shortcuts、.tile、.corner、.wrap。最大 32 KB；禁止外部 URL 与 @import。")
                .setView(input).setPositiveButton("保存并使用", null).setNeutralButton("示例", null)
                .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface value, int which) { continueHomeSection(HOME_SECTION_CODE); }
                }).create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface ignored) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View view) { input.setText(CustomHomeCss.EXAMPLE); input.setSelection(input.length()); }
                });
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View view) {
                        String raw = input.getText().toString().trim();
                        if (raw.length() == 0) { toast("CSS 不能为空，可使用“清除 CSS”移除"); return; }
                        String error = CustomHomeCss.error(raw);
                        if (error.length() > 0) { toast(error); return; }
                        prefs.edit().putString("home_custom_css", raw)
                                .putBoolean("home_custom_html_enabled", false).apply();
                        dialog.dismiss();
                        homeCustomizationChanged("自定义 CSS 已启用", HOME_SECTION_CODE);
                    }
                });
            }
        });
        dialog.show();
    }

    private EditText compactCodeEditor() {
        EditText input = new EditText(this);
        input.setMinLines(4);
        input.setMaxLines(8);
        input.setMinHeight(dp(132));
        input.setMaxHeight(dp(238));
        input.setVerticalScrollBarEnabled(true);
        input.setHorizontallyScrolling(false);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setTypeface(android.graphics.Typeface.MONOSPACE);
        input.setTextSize(13.5f);
        input.setTextColor(nightMode ? Color.rgb(232, 234, 237) : TEXT);
        input.setHintTextColor(nightMode ? Color.rgb(154, 160, 166) : MUTED);
        input.setBackground(roundRect(nightMode ? Color.rgb(43, 46, 51) : SURFACE, 12));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE |
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setPadding(dp(14), dp(10), dp(14), dp(10));
        return input;
    }

    private void showCustomHomeActions() { showCustomHomeActions(HOME_SECTION_MAIN); }

    private void showCustomHomeActions(final int returnSection) {
        final String saved = prefs.getString("home_custom_html", "");
        if (!CustomHomeHtml.valid(saved)) { showCustomHomeEditor(returnSection); return; }
        final boolean enabled = prefs.getBoolean("home_custom_html_enabled", false);
        new AlertDialog.Builder(this).setTitle("完整 HTML 页面")
                .setItems(new String[] { "编辑 HTML", enabled ? "停用并显示默认主页" : "启用自定义主页", "删除自定义主页" },
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dialog, int which) {
                                if (which == 0) showCustomHomeEditor(returnSection);
                                else if (which == 1) {
                                    prefs.edit().putBoolean("home_custom_html_enabled", !enabled).apply();
                                    homeCustomizationChanged(enabled ? "已停用完整 HTML" : "已启用完整 HTML", returnSection);
                                } else confirmDeleteCustomHome(returnSection);
                            }
                        }).setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { continueHomeSection(returnSection); }
                }).show();
    }

    private void showCustomHomeEditor(final int returnSection) {
        final EditText input = compactCodeEditor();
        String saved = prefs.getString("home_custom_html", "");
        input.setText(CustomHomeHtml.valid(saved) ? saved : CustomHomeHtml.EXAMPLE);
        input.setHint(CustomHomeHtml.EXAMPLE);
        final AlertDialog dialog = new AlertDialog.Builder(this).setTitle("自定义主页 HTML")
                .setMessage("完整替换主页，支持 HTML、CSS 和本地 JavaScript，最大 64 KB。脚本在无同源、无联网、无内部权限的沙箱中运行；壁纸仍独立控制。")
                .setView(input).setPositiveButton("保存并启用", null).setNeutralButton("示例", null)
                .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface value, int which) { continueHomeSection(returnSection); }
                }).create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface ignored) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View view) {
                        input.setText(CustomHomeHtml.EXAMPLE);
                        input.setSelection(input.length());
                    }
                });
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View view) {
                        String raw = input.getText().toString().trim();
                        if (raw.length() == 0) { toast("HTML 不能为空"); return; }
                        if (raw.getBytes(StandardCharsets.UTF_8).length > CustomHomeHtml.MAX_LENGTH) {
                            toast("HTML 不能超过 64 KB"); return;
                        }
                        long previous = Math.max(0L, prefs.getLong("home_custom_html_version", 0L));
                        long next = previous == Long.MAX_VALUE ? 1L : previous + 1L;
                        boolean saved = prefs.edit().putString("home_custom_html", raw)
                                .putBoolean("home_custom_html_enabled", true)
                                .putLong("home_custom_html_version", next).commit();
                        if (!saved) { toast("自定义主页保存失败"); return; }
                        dialog.dismiss();
                        homeCustomizationChanged("完整 HTML 已保存并启用", returnSection);
                    }
                });
            }
        });
        dialog.show();
    }

    private void confirmDeleteCustomHome(final int returnSection) {
        new AlertDialog.Builder(this).setTitle("删除自定义主页？")
                .setMessage("HTML 代码将从本机永久删除，壁纸不会受影响。")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        prefs.edit().remove("home_custom_html").remove("home_custom_html_enabled")
                                .remove("home_custom_html_version").apply();
                        homeCustomizationChanged("完整 HTML 已删除", returnSection);
                    }
                }).setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { continueHomeSection(returnSection); }
                }).show();
    }

    private void showHomeAssetActions(final HomeImageStore.Kind kind) {
        if (homeImages == null || !homeImages.has(kind)) { chooseHomeImage(kind); return; }
        String label = kind == HomeImageStore.Kind.WALLPAPER ? "壁纸" : "Logo";
        new AlertDialog.Builder(this).setTitle("主页" + label)
                .setItems(new String[] { "更换图片", "移除自定义" + label }, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) chooseHomeImage(kind);
                        else {
                            homeImages.remove(kind);
                            if (kind == HomeImageStore.Kind.LOGO) prefs.edit().putString("home_logo_mode", "text").apply();
                            homeCustomizationChanged(kind == HomeImageStore.Kind.WALLPAPER ? "已恢复默认背景" : "已恢复文字 Logo");
                        }
                    }
                }).setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { continueHomeCustomization(); }
                }).show();
    }

    private void chooseHomeImage(HomeImageStore.Kind kind) {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(intent, kind == HomeImageStore.Kind.WALLPAPER ? HOME_WALLPAPER_REQUEST : HOME_LOGO_REQUEST);
        } catch (Exception error) {
            toast("没有可用的图片选择器");
            continueHomeCustomization();
        }
    }

    private void importHomeImage(final Uri uri, final HomeImageStore.Kind kind) {
        if (uri == null || homeImages == null || scriptExecutor == null || scriptExecutor.isShutdown()) {
            toast("无法读取所选图片");
            continueHomeCustomization();
            return;
        }
        toast(kind == HomeImageStore.Kind.WALLPAPER ? "正在优化壁纸…" : "正在优化 Logo…");
        try {
            scriptExecutor.execute(new Runnable() {
                @Override public void run() {
                    Exception failure = null;
                    try { homeImages.save(uri, kind); }
                    catch (Exception error) { failure = error; }
                    final Exception result = failure;
                    uiHandler.post(new Runnable() {
                        @Override public void run() {
                            if (result != null) {
                                toast("图片处理失败：" + safeMessage(result));
                                continueHomeCustomization();
                                return;
                            }
                            if (kind == HomeImageStore.Kind.LOGO)
                                prefs.edit().putString("home_logo_mode", "image").apply();
                            homeCustomizationChanged(kind == HomeImageStore.Kind.WALLPAPER ? "主页壁纸已更新" : "主页 Logo 已更新");
                        }
                    });
                }
            });
        } catch (RuntimeException rejected) {
            toast("图片处理队列繁忙，请稍后重试");
            continueHomeCustomization();
        }
    }

    private void showHomeTextSetting(final boolean title) { showHomeTextSetting(title, HOME_SECTION_MAIN); }

    private void showHomeTextSetting(final boolean title, final int returnSection) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(title ? homePageConfig().title : homePageConfig().subtitle);
        input.setHint(title ? "Median" : "例如：今天也要保持好奇");
        input.setSelectAllOnFocus(true);
        input.setPadding(dp(16), dp(8), dp(16), dp(8));
        new AlertDialog.Builder(this).setTitle(title ? "Logo 文字" : "主页副标题")
                .setMessage(title ? "最多 28 个字符；留空恢复 Median。" : "最多 64 个字符；留空则隐藏。")
                .setView(input).setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        String value = title ? HomePageConfig.cleanTitle(input.getText().toString()) :
                                HomePageConfig.cleanSubtitle(input.getText().toString());
                        SharedPreferences.Editor editor = prefs.edit().putString(title ? "home_title" : "home_subtitle", value);
                        if (title) {
                            editor.putString("home_logo_mode", "text");
                            if ("custom".equals(homePageConfig().logoStyle))
                                editor.putString("home_logo_style", "median").remove("home_logo_code");
                        }
                        editor.apply();
                        homeCustomizationChanged(title ? "Logo 文字已更新" : "主页副标题已更新", returnSection);
                    }
                }).setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { continueHomeSection(returnSection); }
                }).show();
    }

    private void showLogoStyleChoice(final HomePageConfig value) { showLogoStyleChoice(value, HOME_SECTION_MAIN); }

    private void showLogoStyleChoice(final HomePageConfig value, final int returnSection) {
        final String[] styles = new String[] { "median", "google", "aurora", "sunset", "ocean", "rose_gold", "custom" };
        String[] labels = new String[] { "Median 经典", "Google 官方配色", "极光渐变", "日落渐变", "海洋渐变", "玫瑰金渐变", "自定义代码" };
        int checked = 0;
        for (int i = 0; i < styles.length; i++) if (styles[i].equals(value.logoStyle)) checked = i;
        new AlertDialog.Builder(this).setTitle("文字 Logo 样式").setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
                String style = styles[which];
                dialog.dismiss();
                if ("custom".equals(style)) {
                    showLogoCodeEditor(returnSection);
                    return;
                }
                prefs.edit().putString("home_logo_style", style)
                        .putString("home_logo_mode", "text").apply();
                homeCustomizationChanged(null, returnSection);
            }
        }).setNegativeButton("取消", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) { continueHomeSection(returnSection); }
        }).show();
    }

    private void showLogoCodeEditor() { showLogoCodeEditor(HOME_SECTION_MAIN); }

    private void showLogoCodeEditor(final int returnSection) {
        final EditText input = new EditText(this);
        String saved = homePageConfig().logoCode;
        String example = LogoMarkup.gradientExample(homePageConfig().title);
        input.setText(saved.length() == 0 ? example : saved);
        input.setHint(example);
        input.setMinLines(5);
        input.setMaxLines(9);
        input.setHorizontallyScrolling(false);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setTypeface(android.graphics.Typeface.MONOSPACE);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setPadding(dp(14), dp(10), dp(14), dp(10));
        final AlertDialog dialog = new AlertDialog.Builder(this).setTitle("自定义文字 Logo")
                .setMessage("安全标记语法（不会执行 HTML/JavaScript）：\n\n" +
                        "[color=#4285F4]文字[/color]\n" +
                        "[gradient=#8B5CF6,#6366F1,#22D3EE]文字[/gradient]\n" +
                        "Med[space=4]ian\n\n" +
                        "普通空格会保留；[space=0–24] 可精确控制局部间隔。渐变支持 2–4 个颜色。")
                .setView(input).setPositiveButton("保存", null)
                .setNeutralButton("Google 示例", null)
                .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface value, int which) { continueHomeSection(returnSection); }
                }).create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface ignored) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View view) { input.setText(LogoMarkup.GOOGLE_CODE); input.setSelection(input.length()); }
                });
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View view) {
                        LogoMarkup.Result parsed = LogoMarkup.parse(input.getText().toString(), homePageConfig().logoGradientAngle);
                        if (!parsed.valid()) { toast(parsed.error); return; }
                        prefs.edit().putString("home_logo_mode", "text")
                                .putString("home_logo_style", "custom")
                                .putString("home_logo_code", LogoMarkup.clean(input.getText().toString()))
                                .putString("home_title", HomePageConfig.cleanTitle(parsed.plainText)).apply();
                        dialog.dismiss();
                        homeCustomizationChanged("自定义文字 Logo 已保存", returnSection);
                    }
                });
            }
        });
        dialog.show();
    }

    private void showHomeStringChoice(String title, final String key, final String[] values,
                                      String[] labels, String current) {
        showHomeStringChoiceInSection(title, key, values, labels, current, HOME_SECTION_MAIN);
    }

    private void showHomeStringChoiceInSection(String title, final String key, final String[] values,
                                      String[] labels, String current, final int returnSection) {
        int checked = 0;
        for (int i = 0; i < values.length; i++) if (values[i].equals(current)) checked = i;
        new AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
                prefs.edit().putString(key, values[which]).apply();
                dialog.dismiss();
                homeCustomizationChanged(null, returnSection);
            }
        }).setNegativeButton("取消", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) { continueHomeSection(returnSection); }
        }).show();
    }

    private void showHomeIntegerChoice(String title, final String key, final int[] values,
                                       String[] labels, int current) {
        showHomeIntegerChoiceInSection(title, key, values, labels, current, HOME_SECTION_MAIN);
    }

    private void showHomeIntegerChoiceInSection(String title, final String key, final int[] values,
                                       String[] labels, int current, final int returnSection) {
        int checked = 0;
        for (int i = 0; i < values.length; i++) if (values[i] == current) checked = i;
        new AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
                prefs.edit().putInt(key, values[which]).apply();
                dialog.dismiss();
                homeCustomizationChanged(null, returnSection);
            }
        }).setNegativeButton("取消", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) { continueHomeSection(returnSection); }
        }).show();
    }

    private void showHomeSearchChoice(HomePageConfig value) { showHomeSearchChoice(value, HOME_SECTION_MAIN); }

    private void showHomeSearchChoice(HomePageConfig value, final int returnSection) {
        int checked = !value.showSearch ? 2 : ("glass".equals(value.searchStyle) ? 1 : 0);
        new AlertDialog.Builder(this).setTitle("搜索框").setSingleChoiceItems(
                new String[] { "显示 · 纯色", "显示 · 磨砂", "隐藏" }, checked, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        SharedPreferences.Editor editor = prefs.edit().putBoolean("home_show_search", which != 2);
                        if (which != 2) editor.putString("home_search_style", which == 1 ? "glass" : "solid");
                        editor.apply();
                        dialog.dismiss();
                        homeCustomizationChanged(null, returnSection);
                    }
                }).setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { continueHomeSection(returnSection); }
                }).show();
    }

    private void toggleHomeBoolean(String key, boolean current) {
        toggleHomeBooleanInSection(key, current, HOME_SECTION_MAIN);
    }

    private void toggleHomeBooleanInSection(String key, boolean current, int returnSection) {
        prefs.edit().putBoolean(key, !current).apply();
        homeCustomizationChanged(null, returnSection);
    }

    private void confirmResetHomeCustomization() {
        new AlertDialog.Builder(this).setTitle("恢复默认主页？")
                .setMessage("将移除自定义壁纸、Logo、CSS 和 HTML，并恢复标题、颜色、布局及模块显示。书签和“每次打开”设置不会删除。")
                .setPositiveButton("恢复", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        SharedPreferences.Editor editor = prefs.edit();
                        String[] keys = new String[] { "home_title", "home_subtitle", "home_accent", "home_wallpaper_dim",
                                "home_logo_style", "home_logo_code", "home_logo_letter_spacing", "home_logo_gradient_angle",
                                "home_logo_mode", "home_logo_font_size", "home_logo_font_weight", "home_logo_image_width",
                                "home_logo_image_height", "home_logo_image_radius", "home_custom_css",
                                "home_wallpaper_blur", "home_wallpaper_fit", "home_search_style", "home_layout",
                                "home_tile_shape", "home_shortcut_columns", "home_show_search", "home_show_engines",
                                "home_show_shortcuts", "home_show_corner", "home_show_clock", "home_custom_html",
                                "home_custom_html_enabled", "home_custom_html_version" };
                        for (String key : keys) editor.remove(key);
                        editor.apply();
                        if (homeImages != null) homeImages.removeAll();
                        homeCustomizationChanged("已恢复默认主页");
                    }
                }).setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { continueHomeCustomization(); }
                }).show();
    }

    private void homeCustomizationChanged(String message) {
        homeCustomizationChanged(message, HOME_SECTION_MAIN);
    }

    private void homeCustomizationChanged(String message, int returnSection) {
        if (isHomeUrl(currentPageUrl)) showHome();
        if (message != null && message.length() > 0) toast(message);
        continueHomeSection(returnSection);
    }

    private void continueHomeCustomization() {
        continueHomeSection(HOME_SECTION_MAIN);
    }

    private void continueHomeSection(final int section) {
        uiHandler.postDelayed(new Runnable() {
            @Override public void run() {
                if (isFinishing() || isDestroyed()) return;
                if (section == HOME_SECTION_LAYOUT) showHomeLayoutCustomization();
                else if (section == HOME_SECTION_LOGO) showLogoCustomization();
                else if (section == HOME_SECTION_SEARCH) showSearchCustomization();
                else if (section == HOME_SECTION_BACKGROUND) showBackgroundCustomization();
                else if (section == HOME_SECTION_SHORTCUTS) showShortcutCustomization();
                else if (section == HOME_SECTION_CODE) showCustomCodeCustomization();
                else showHomeCustomization();
            }
        }, 90L);
    }

    private static String settingPreview(String value, int max, String empty) {
        String clean = value == null ? "" : value.trim();
        if (clean.length() == 0) return empty;
        int count = clean.codePointCount(0, clean.length());
        return count <= max ? clean : clean.substring(0, clean.offsetByCodePoints(0, max)) + "…";
    }

    private static String accentLabel(String value) {
        if ("violet".equals(value)) return "紫色";
        if ("green".equals(value)) return "绿色";
        if ("orange".equals(value)) return "橙色";
        if ("rose".equals(value)) return "玫红";
        if ("teal".equals(value)) return "青色";
        return "蓝色";
    }

    private static String tileShapeLabel(String value) {
        if ("circle".equals(value)) return "圆形";
        if ("square".equals(value)) return "小圆角方形";
        return "圆角方形";
    }

    private static String logoModeLabel(HomePageConfig value) {
        if (value == null || "none".equals(value.logoMode)) return "无";
        if ("image".equals(value.logoMode)) return value.hasLogo ? "图片" : "图片（未选择）";
        return "文字 · " + settingPreview(value.title, 12, "Median");
    }

    private static String logoStyleLabel(String value) {
        if ("google".equals(value)) return "Google 官方配色";
        if ("aurora".equals(value)) return "极光渐变";
        if ("sunset".equals(value)) return "日落渐变";
        if ("ocean".equals(value)) return "海洋渐变";
        if ("rose_gold".equals(value)) return "玫瑰金渐变";
        if ("custom".equals(value)) return "自定义代码";
        return "Median 经典";
    }

    private static String logoLetterSpacingLabel(int value) {
        if (value == 0) return "标准";
        return (value > 0 ? "+" : "") + value + " px";
    }

    private static String logoGradientAngleLabel(int value) {
        if (value == 135) return "左上 → 右下";
        if (value == 180) return "上 → 下";
        if (value == 45) return "左下 → 右上";
        if (value == 0) return "下 → 上";
        if (value == 90) return "左 → 右";
        return value + "°";
    }

    private void showSearchEngineDialog() {
        final String[] values = new String[] { "google", "baidu", "bing", "custom" };
        String[] labels = new String[] { "Google", "百度", "Bing", "自定义" };
        int checked = 0;
        for (int i = 0; i < values.length; i++) if (values[i].equals(searchEngine)) checked = i;
        new AlertDialog.Builder(this).setTitle("默认搜索引擎").setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
                if ("custom".equals(values[which]) && !validSearchTemplate(customSearchTemplate)) { dialog.dismiss(); showCustomSearchDialog(); return; }
                setSearchEngine(values[which]);
                dialog.dismiss();
                if (isHomeUrl(currentPageUrl)) showHome();
            }
        }).setNegativeButton("取消", null).show();
    }

    private void showCustomSearchDialog() {
        final EditText input = new EditText(this);
        input.setHint("https://example.com/search?q=%s");
        input.setText(customSearchTemplate);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setPadding(dp(16), dp(8), dp(16), dp(8));
        new AlertDialog.Builder(this).setTitle("自定义搜索引擎").setMessage("使用 %s 代表经过编码的搜索词，只允许 HTTPS 地址。")
                .setView(input).setPositiveButton("保存并使用", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        String value = input.getText().toString().trim();
                        if (!validSearchTemplate(value)) { toast("地址必须以 https:// 开头并包含 %s"); return; }
                        customSearchTemplate = value;
                        prefs.edit().putString("custom_search_template", value).apply();
                        setSearchEngine("custom");
                        if (isHomeUrl(currentPageUrl)) showHome();
                    }
                }).setNegativeButton("取消", null).show();
    }

    private void copyText(String label, String text, String confirmation) {
        android.content.ClipboardManager manager = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (manager == null) { toast("剪贴板不可用"); return; }
        manager.setPrimaryClip(android.content.ClipData.newPlainText(label == null ? "Median" : label, text == null ? "" : text));
        toast(confirmation == null || confirmation.length() == 0 ? "已复制" : confirmation);
    }

    private void copyPageUrl() {
        String url = webView == null ? null : webView.getUrl();
        if (url == null || isHomeUrl(url)) return;
        android.content.ClipboardManager manager = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (manager != null) manager.setPrimaryClip(android.content.ClipData.newPlainText("页面地址", url));
        toast("页面地址已复制");
    }

    private void translatePage() {
        String url = webView == null ? null : webView.getUrl();
        if (url == null || !url.startsWith("http")) return;
        try { loadInput("https://translate.google.com/translate?sl=auto&tl=zh-CN&u=" + URLEncoder.encode(url, "UTF-8")); }
        catch (Exception e) { toast("无法创建翻译地址"); }
    }

    private void printPage() {
        if (webView == null || isHomeUrl(webView.getUrl())) return;
        try {
            PrintManager manager = (PrintManager) getSystemService(PRINT_SERVICE);
            if (manager != null) manager.print(safeTitle(webView.getTitle(), webView.getUrl()),
                    webView.createPrintDocumentAdapter("Median page"), new android.print.PrintAttributes.Builder().build());
        } catch (Exception e) { toast("系统打印服务不可用"); }
    }

    private void showDownloadCenter() {
        try { startActivity(new Intent(this, DownloadCenterActivity.class)); }
        catch (RuntimeException error) { toast("无法打开下载中心：" + safeMessage(error)); }
    }

    private String downloadPolicySummary() {
        ArrayList<String> parts = new ArrayList<String>();
        if (prefs.getBoolean("download_wifi_only", false)) parts.add("仅非计费网络"); else parts.add("允许移动网络");
        if (prefs.getBoolean("download_allow_roaming", false)) parts.add("允许漫游");
        if (prefs.getBoolean("download_charging_only", false)) parts.add("仅充电时");
        parts.add("Median 单连接下载");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) { if (i > 0) out.append(" · "); out.append(parts.get(i)); }
        return out.toString();
    }

    private void showDownloadPolicy() {
        final String[] labels = new String[] { "仅在非计费网络下载", "允许数据漫游下载", "仅在充电时下载" };
        final boolean[] checked = new boolean[] {
                prefs.getBoolean("download_wifi_only", false),
                prefs.getBoolean("download_allow_roaming", false),
                prefs.getBoolean("download_charging_only", false)
        };
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("下载策略")
                .setMultiChoiceItems(labels, checked, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which, boolean isChecked) { checked[which] = isChecked; }
                })
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        prefs.edit().putBoolean("download_wifi_only", checked[0])
                                .putBoolean("download_allow_roaming", checked[1])
                                .putBoolean("download_charging_only", checked[2]).apply();
                        toast("下载策略已更新");
                    }
                }).setNegativeButton("取消", null).create();
        secureDialog(dialog);
        dialog.show();
    }

    private String downloadStatusSummary(long id) {
        DownloadStore.Item item = services.downloads().get(id);
        if (item != null && item.isAdaptive()) return adaptiveDownloadStatus(item);
        DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        if (manager == null) return "状态未知";
        Cursor cursor = null;
        try {
            cursor = manager.query(new DownloadManager.Query().setFilterById(id));
            if (cursor == null || !cursor.moveToFirst()) return "记录已失效";
            return downloadStatusFromCursor(cursor);
        } catch (Exception e) {
            return "状态未知";
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    private String adaptiveDownloadStatus(DownloadStore.Item item) {
        if (DownloadStore.STATUS_COMPLETED.equals(item.status)) {
            return "已完成" + (item.totalBytes > 0L ? " · " + humanBytes(item.totalBytes) : "");
        }
        if (DownloadStore.STATUS_FAILED.equals(item.status)) return "失败 · " + (item.reason.length() == 0 ? "未知原因" : item.reason);
        if (DownloadStore.STATUS_CANCELLED.equals(item.status)) return "已取消";
        if (DownloadStore.STATUS_PAUSED.equals(item.status)) return "已暂停" + (item.reason.length() == 0 ? "" : " · " + item.reason);
        if (DownloadStore.STATUS_WAITING.equals(item.status)) return item.reason.length() == 0 ? "等待中" : item.reason;
        if (DownloadStore.STATUS_PENDING.equals(item.status)) return "准备中 · " + downloadModeLabel(item.mode);
        long percent = DownloadCenterPolicy.progressPermille(item.downloadedBytes, item.totalBytes) / 10L;
        StringBuilder out = new StringBuilder("下载中");
        if (item.totalBytes > 0L) out.append(" · ").append(percent).append('%');
        if (item.bytesPerSecond > 0L) out.append(" · ").append(humanBytes(item.bytesPerSecond)).append("/s");
        out.append(" · ").append(downloadModeLabel(item.mode));
        return out.toString();
    }

    private Map<Long, String> downloadStatusSummaries(List<DownloadStore.Item> downloads, int limit) {
        HashMap<Long, String> result = new HashMap<Long, String>();
        int count = Math.min(limit, downloads == null ? 0 : downloads.size());
        if (count == 0) return result;
        ArrayList<Long> systemIds = new ArrayList<Long>();
        for (int i = 0; i < count; i++) {
            DownloadStore.Item item = downloads.get(i);
            if (item.isAdaptive()) result.put(Long.valueOf(item.id), adaptiveDownloadStatus(item));
            else systemIds.add(Long.valueOf(item.id));
        }
        if (systemIds.size() == 0) return result;
        long[] ids = new long[systemIds.size()];
        for (int i = 0; i < systemIds.size(); i++) ids[i] = systemIds.get(i).longValue();
        DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        if (manager == null) return result;
        Cursor cursor = null;
        try {
            cursor = manager.query(new DownloadManager.Query().setFilterById(ids));
            if (cursor == null) return result;
            int idColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID);
            while (cursor.moveToNext()) result.put(Long.valueOf(cursor.getLong(idColumn)), downloadStatusFromCursor(cursor));
        } catch (Exception ignored) {
        } finally { if (cursor != null) cursor.close(); }
        return result;
    }

    private String downloadStatusFromCursor(Cursor cursor) {
        int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
        long current = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
        long total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
        if (status == DownloadManager.STATUS_SUCCESSFUL) return "已完成" + (total > 0 ? " · " + humanBytes(total) : "");
        if (status == DownloadManager.STATUS_FAILED) return "失败 · " + downloadReason(cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)));
        if (status == DownloadManager.STATUS_PAUSED) return "已暂停 · " + downloadReason(cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)));
        if (status == DownloadManager.STATUS_PENDING) return "等待中";
        return "下载中" + (total > 0 ? " · " + (DownloadCenterPolicy.progressPermille(current, total) / 10) + "%" : "");
    }

    private void showDownloadActions(final DownloadStore.Item original) {
        final DownloadStore.Item latest = services.downloads().get(original.id);
        final DownloadStore.Item item = latest == null ? original : latest;
        String cancelLabel = item.isAdaptive() ? "取消下载任务" : "移除旧版系统任务";
        String[] actions = new String[] { "打开文件", "分享文件", "重新下载", "复制下载地址", "计算 SHA-256", "查看详细信息", cancelLabel, "仅忘记 Median 记录" };
        int[] icons = new int[] { BrowserIconView.PLUS, BrowserIconView.SHARE, BrowserIconView.RELOAD, BrowserIconView.PLUS,
                BrowserIconView.SHIELD, BrowserIconView.STORAGE, BrowserIconView.CLOSE, BrowserIconView.CLOSE };
        showActionSheet(item.filename, downloadStatusSummary(item.id), actions, icons, new SheetHandler() {
            @Override public void onItem(int which) {
                if (which == 0) openDownloadedFile(item, false);
                else if (which == 1) openDownloadedFile(item, true);
                else if (which == 2) enqueueDownloadAdvanced(item.url,
                        webView == null ? null : webView.getSettings().getUserAgentString(), null,
                        item.mime, item.filename, downloadContextHeaders(webView, item.url));
                else if (which == 3) copyText("下载地址", item.url, "下载地址已复制");
                else if (which == 4) calculateDownloadSha256(item);
                else if (which == 5) showDownloadDetails(item);
                else if (which == 6) {
                    if (item.isAdaptive()) {
                        Intent cancel = new Intent(MainActivity.this, AdaptiveDownloadService.class);
                        cancel.setAction(AdaptiveDownloadService.ACTION_CANCEL);
                        cancel.putExtra(AdaptiveDownloadService.EXTRA_ID, item.id);
                        try { startService(cancel); } catch (RuntimeException ignored) {}
                        toast("已请求取消下载");
                    } else {
                        DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                        if (manager != null) manager.remove(item.id);
                        services.downloads().remove(item.id);
                        toast("下载任务与系统记录已移除");
                    }
                } else {
                    services.downloads().remove(item.id);
                    toast("Median 下载记录已移除");
                }
            }
        });
    }

    private void showDownloadDetails(DownloadStore.Item source) {
        final DownloadStore.Item refreshed = services.downloads().get(source.id);
        final DownloadStore.Item item = refreshed == null ? source : refreshed;
        String time = item.createdAt <= 0 ? "未知" : new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new java.util.Date(item.createdAt));
        String engine = item.isAdaptive() ? "Median 内部下载" : "旧版系统下载记录";
        String mode = item.isAdaptive() ? "\n性能档：" + downloadModeLabel(item.mode) : "";
        String progress = item.isAdaptive() ? (item.totalBytes > 0L ?
                "\n进度：" + humanBytes(item.downloadedBytes) + " / " + humanBytes(item.totalBytes) :
                "\n进度：" + humanBytes(item.downloadedBytes) + " / 总大小未知") : "";
        String message = "状态：" + downloadStatusSummary(item.id) +
                "\n引擎：" + engine + mode + progress +
                "\n文件：" + item.filename +
                "\n类型：" + (item.mime.length() == 0 ? "未知" : item.mime) +
                "\n创建时间：" + time +
                "\n任务 ID：" + item.id +
                "\n地址：" + item.url;
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("下载详细信息").setMessage(message)
                .setPositiveButton("复制地址", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { copyText("下载地址", item.url, "下载地址已复制"); }
                }).setNegativeButton("关闭", null).create();
        secureDialog(dialog);
        dialog.show();
    }

    private void calculateDownloadSha256(final DownloadStore.Item source) {
        final DownloadStore.Item refreshed = services.downloads().get(source.id);
        final DownloadStore.Item item = refreshed == null ? source : refreshed;
        final Uri uri = downloadedUri(item);
        if (uri == null) { toast("文件尚未下载完成"); return; }
        if (scriptNetworkExecutor == null || scriptNetworkExecutor.isShutdown()) return;
        toast("正在计算 SHA-256…");
        scriptNetworkExecutor.execute(new Runnable() {
            @Override public void run() {
                InputStream input = null;
                try {
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    input = getContentResolver().openInputStream(uri);
                    if (input == null) throw new IllegalStateException("无法读取文件");
                    byte[] buffer = new byte[65536];
                    int read;
                    while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
                    byte[] bytes = digest.digest();
                    final StringBuilder hex = new StringBuilder(64);
                    for (byte value : bytes) hex.append(String.format(Locale.US, "%02x", value & 0xff));
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            copyText("SHA-256", hex.toString(), "SHA-256 已计算并复制");
                            AlertDialog dialog = new AlertDialog.Builder(MainActivity.this).setTitle("SHA-256")
                                    .setMessage(item.filename + "\n\n" + hex).setPositiveButton("确定", null).create();
                            secureDialog(dialog); dialog.show();
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() { @Override public void run() { toast("校验失败：" + safeMessage(e)); } });
                } finally { if (input != null) try { input.close(); } catch (Exception ignored) {} }
            }
        });
    }

    private Uri downloadedUri(DownloadStore.Item item) {
        if (item == null) return null;
        if (item.isAdaptive()) {
            if (!DownloadStore.STATUS_COMPLETED.equals(item.status) || item.localUri.length() == 0) return null;
            try { return Uri.parse(item.localUri); } catch (RuntimeException ignored) { return null; }
        }
        DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        return manager == null ? null : manager.getUriForDownloadedFile(item.id);
    }

    private String downloadReason(int reason) {
        if (reason == DownloadManager.PAUSED_WAITING_TO_RETRY) return "等待重试";
        if (reason == DownloadManager.PAUSED_WAITING_FOR_NETWORK) return "等待网络";
        if (reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI) return "等待非计费网络";
        if (reason == DownloadManager.PAUSED_UNKNOWN) return "未知原因";
        if (reason == DownloadManager.ERROR_CANNOT_RESUME) return "无法续传";
        if (reason == DownloadManager.ERROR_DEVICE_NOT_FOUND) return "存储不可用";
        if (reason == DownloadManager.ERROR_FILE_ALREADY_EXISTS) return "文件已存在";
        if (reason == DownloadManager.ERROR_FILE_ERROR) return "文件错误";
        if (reason == DownloadManager.ERROR_HTTP_DATA_ERROR) return "HTTP 数据错误";
        if (reason == DownloadManager.ERROR_INSUFFICIENT_SPACE) return "空间不足";
        if (reason == DownloadManager.ERROR_TOO_MANY_REDIRECTS) return "重定向过多";
        if (reason >= 400 && reason <= 599) return "HTTP " + reason;
        return "代码 " + reason;
    }

    private void openDownloadedFile(DownloadStore.Item source, boolean share) {
        DownloadStore.Item refreshed = services.downloads().get(source.id);
        DownloadStore.Item item = refreshed == null ? source : refreshed;
        try {
            Uri uri = downloadedUri(item);
            if (uri == null) { toast("文件尚未下载完成或已被移除"); return; }
            String mime = DownloadFileTypes.mimeForOpen(getContentResolver(), uri, item.filename, item.mime);
            if (!share && DownloadFileTypes.isApk(item.filename, mime) && Build.VERSION.SDK_INT >= 26 &&
                    !getPackageManager().canRequestPackageInstalls()) {
                toast("请允许 Median 安装下载的 APK，授权后再次点“打开”");
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
                return;
            }
            Intent intent;
            if (share) {
                intent = new Intent(Intent.ACTION_SEND);
                intent.setType(mime);
                intent.putExtra(Intent.EXTRA_STREAM, uri);
            } else {
                intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(uri, mime);
            }
            intent.setClipData(ClipData.newRawUri(item.filename, uri));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            toast(share ? "正在打开分享面板" : "正在打开：" + item.filename);
            startActivity(share ? Intent.createChooser(intent, "分享下载文件") : intent);
        } catch (Exception e) { toast("打开失败：没有可用应用，或文件已被移动"); }
    }

    private void probeAndShowMediaCenter() {
        if (webView == null || isHomeUrl(webView.getUrl())) { showMediaCenter(); return; }
        String probe = "(function(){var o=[],s={};function a(u,t){try{u=new URL(u,location.href).href;}catch(e){return;}if(!/^https?:/.test(u)||s[u])return;s[u]=1;o.push({url:u,mime:t||''});}" +
                "document.querySelectorAll('video,audio,source').forEach(function(e){a(e.currentSrc||e.src,e.type||'');});" +
                "try{performance.getEntriesByType('resource').forEach(function(e){if(/\\.(m3u8|mpd|mp4|webm|m4a|mp3|aac|ogg|opus|flac)(?:[?#]|$)/i.test(e.name))a(e.name,'');});}catch(e){}" +
                "return JSON.stringify(o.slice(0,80));})();";
        try {
            webView.evaluateJavascript(probe, new ValueCallback<String>() {
                @Override public void onReceiveValue(String value) {
                    try {
                        Object decoded = new JSONTokener(value == null ? "[]" : value).nextValue();
                        String json = decoded instanceof String ? (String) decoded : String.valueOf(decoded);
                        JSONArray array = new JSONArray(json);
                        for (int i = 0; i < array.length(); i++) {
                            JSONObject object = array.optJSONObject(i);
                            if (object != null) mediaSniffer.observe(object.optString("url", ""), object.optString("mime", ""), currentHost());
                        }
                    } catch (Exception ignored) {}
                    showMediaCenter();
                }
            });
        } catch (RuntimeException e) { showMediaCenter(); }
    }

    private void showMediaCenter() {
        final List<MediaResourceSniffer.Resource> resources = mediaSniffer.getAll();
        String[] items = new String[resources.size() + 2];
        int[] icons = new int[items.length];
        items[0] = "立即进入画中画";
        items[1] = "离开应用自动画中画：" + (autoPictureInPicture ? "已开启" : "已关闭");
        icons[0] = BrowserIconView.SPEED;
        icons[1] = BrowserIconView.SPEED;
        for (int i = 0; i < resources.size(); i++) {
            MediaResourceSniffer.Resource item = resources.get(i);
            String host = hostOf(item.url);
            items[i + 2] = item.kind + " · " + (host.length() == 0 ? item.url : host);
            icons[i + 2] = BrowserIconView.STORAGE;
        }
        String subtitle = resources.size() == 0 ? "暂未发现直链；DRM、blob: 与分段加密流不会被伪装为可下载文件" : "已发现 " + resources.size() + " 个候选资源 · 请选择后可播放、下载或复制";
        showActionSheet("媒体中心", subtitle, items, icons, new SheetHandler() {
            @Override public void onItem(int which) {
                if (which == 0) enterPagePictureInPicture();
                else if (which == 1) {
                    autoPictureInPicture = !autoPictureInPicture;
                    prefs.edit().putBoolean("auto_picture_in_picture", autoPictureInPicture).apply();
                    toast(autoPictureInPicture ? "离开应用时将尝试进入画中画" : "自动画中画已关闭");
                } else {
                    int index = which - 2;
                    if (index >= 0 && index < resources.size()) showMediaResourceActions(resources.get(index));
                }
            }
        });
    }

    private void showMediaResourceActions(final MediaResourceSniffer.Resource resource) {
        String[] actions = new String[] { "使用外部播放器打开", "下载资源", "在新标签页打开", "复制媒体地址", "分享媒体地址" };
        int[] icons = new int[] { BrowserIconView.SPEED, BrowserIconView.STORAGE, BrowserIconView.PLUS, BrowserIconView.PLUS, BrowserIconView.SHARE };
        showActionSheet(resource.kind, hostOf(resource.url) + " · " + resource.mime, actions, icons, new SheetHandler() {
            @Override public void onItem(int which) {
                if (which == 0) openMediaExternally(resource);
                else if (which == 1) enqueueDownload(webView, resource.url,
                        webView == null ? null : webView.getSettings().getUserAgentString(), null, resource.mime);
                else if (which == 2) openUrlInNewTab(resource.url, true);
                else if (which == 3) {
                    android.content.ClipboardManager manager = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (manager != null) manager.setPrimaryClip(android.content.ClipData.newPlainText("媒体地址", resource.url));
                    toast("媒体地址已复制");
                } else {
                    Intent share = new Intent(Intent.ACTION_SEND);
                    share.setType("text/plain");
                    share.putExtra(Intent.EXTRA_TEXT, resource.url);
                    try { startActivity(Intent.createChooser(share, "分享媒体地址")); } catch (Exception e) { toast("没有可用的分享应用"); }
                }
            }
        });
    }

    private void openMediaExternally(MediaResourceSniffer.Resource resource) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.parse(resource.url), resource.mime.length() == 0 ? "video/*" : resource.mime);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) { toast("没有兼容此外部媒体的播放器"); }
    }

    private void enterPagePictureInPicture() {
        if (webView == null || isHomeUrl(currentPageUrl)) { toast("当前页面不能进入画中画"); return; }
        try {
            PictureInPictureParams params = new PictureInPictureParams.Builder().setAspectRatio(new Rational(16, 9)).build();
            if (!enterPictureInPictureMode(params)) toast("系统未允许进入画中画");
        } catch (Exception e) { toast("画中画不可用：" + safeMessage(e)); }
    }

    private void showPageScriptCommands() {
        if (webView == null || isHomeUrl(currentPageUrl)) { toast("当前页面没有脚本命令"); return; }
        final String token = scriptBridgeTokens.get(webView);
        if (token == null || token.length() < 32) { toast("当前 WebView 不支持安全脚本命令"); return; }
        StringBuilder query = new StringBuilder("(function(){var a=[];");
        for (UserScriptStore.Script script : scriptStore.getAll()) {
            if (!script.enabled || script.quarantined || !scriptStore.matchesUrl(script.id, currentPageUrl)) continue;
            String objectName = UserScriptStore.dispatchObjectName(token, script.id);
            query.append("try{var d=window[").append(JSONObject.quote(objectName)).append("];if(d&&typeof d.menus==='function'){var m=d.menus(")
                    .append(JSONObject.quote(token)).append(");if(Array.isArray(m))m.forEach(function(x){x.scriptId=")
                    .append(JSONObject.quote(script.id)).append(";a.push(x);});}}catch(_){}");
        }
        query.append("return JSON.stringify(a);})();");
        try {
            webView.evaluateJavascript(query.toString(), new ValueCallback<String>() {
                @Override public void onReceiveValue(String value) {
                    try {
                        Object decoded = new JSONTokener(value == null ? "[]" : value).nextValue();
                        String raw = decoded instanceof String ? (String) decoded : String.valueOf(decoded);
                        final JSONArray commands = new JSONArray(raw);
                        if (commands.length() == 0) { toast("当前页面的脚本没有注册命令"); return; }
                        String[] items = new String[commands.length()];
                        int[] icons = new int[commands.length()];
                        for (int i = 0; i < commands.length(); i++) {
                            JSONObject command = commands.optJSONObject(i);
                            items[i] = (command == null ? "脚本命令" : command.optString("caption", "脚本命令")) +
                                    (command == null || command.optString("script", "").length() == 0 ? "" : " · " + command.optString("script", ""));
                            icons[i] = BrowserIconView.SCRIPT;
                        }
                        showActionSheet("脚本命令", currentHost(), items, icons, new SheetHandler() {
                            @Override public void onItem(int which) {
                                JSONObject command = commands.optJSONObject(which);
                                if (command == null || webView == null || !token.equals(scriptBridgeTokens.get(webView))) return;
                                String scriptId = command.optString("scriptId", "");
                                String id = command.optString("id", "");
                                if (!scriptStore.matchesUrl(scriptId, webView.getUrl())) return;
                                String objectName = UserScriptStore.dispatchObjectName(token, scriptId);
                                String run = "(function(){var d=window[" + JSONObject.quote(objectName) + "];if(d&&typeof d.runMenu==='function')d.runMenu(" +
                                        JSONObject.quote(token) + "," + JSONObject.quote(id) + ");})();";
                                try { webView.evaluateJavascript(run, null); } catch (RuntimeException ignored) {}
                            }
                        });
                    } catch (Exception e) { toast("无法读取当前页脚本命令"); }
                }
            });
        } catch (RuntimeException e) { toast("当前页面不支持脚本命令"); }
    }

    private String humanBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(Locale.US, "%.1f KB", bytes / 1024d);
        if (bytes < 1024L * 1024L * 1024L) return String.format(Locale.US, "%.1f MB", bytes / (1024d * 1024d));
        return String.format(Locale.US, "%.1f GB", bytes / (1024d * 1024d * 1024d));
    }

    private String triStateLabel(int value, String inherit) {
        return value == SiteSettingsStore.ALLOW ? "允许" : (value == SiteSettingsStore.BLOCK ? "阻止" : inherit);
    }

    private int nextTriState(int value) {
        if (value == SiteSettingsStore.INHERIT) return SiteSettingsStore.ALLOW;
        if (value == SiteSettingsStore.ALLOW) return SiteSettingsStore.BLOCK;
        return SiteSettingsStore.INHERIT;
    }

    private String searchEngineLabel() {
        if ("baidu".equals(searchEngine)) return "百度";
        if ("bing".equals(searchEngine)) return "Bing";
        if ("custom".equals(searchEngine)) return "自定义";
        return "Google";
    }

    private void showPerformancePanel() {
        String[] modes = new String[] {
                "性能模式 · 提高网页前台响应",
                "标准模式 · 日常稳定优先",
                "低功耗模式 · 减少网页预热和后台资源",
                "极限档网络直通：" + (performanceNetworkDirect ? "已开启 · 跳过广告扫描" : "已关闭 · 默认保留完整过滤"),
                "查看实时性能诊断"
        };
        int[] icons = new int[] { BrowserIconView.SPEED, BrowserIconView.HOME, BrowserIconView.SHIELD, BrowserIconView.SPEED, BrowserIconView.SEARCH };
        showActionSheet("性能调度", modeLabel() + " · 百分比为相对策略强度，不是 CPU 超频", modes, icons, new SheetHandler() {
            @Override public void onItem(int which) {
                if (which == 3) {
                    performanceNetworkDirect = !performanceNetworkDirect;
                    prefs.edit().putBoolean("performance_network_direct", performanceNetworkDirect).apply();
                    renderedShieldActive = null;
                    requestChromeUpdate();
                    toast(performanceNetworkDirect ? "网络直通已开启；性能模式下广告请求检查暂停" : "网络直通已关闭；已恢复完整请求拦截");
                    return;
                }
                if (which == 4) {
                    showPerformanceDiagnostics();
                    return;
                }
                setPerformanceMode(which == 0 ? MODE_PERFORMANCE : (which == 2 ? MODE_POWER_SAVE : MODE_STANDARD));
            }
        });
    }

    private void showPerformanceDiagnostics() {
        PerformanceMonitor performanceMonitor = services.performance();
        String samplingNote;
        if (!performanceSamplingActive) {
            performanceMonitor.start(this);
            performanceSamplingActive = true;
            samplingNote = "\n\n已开始慢帧采样。正常使用一段时间后再次打开此页，数据更有参考价值。";
        } else {
            samplingNote = "";
        }
        int hotLimit = deviceProfile == null ? 1 : deviceProfile.hotWebViewLimit(performanceMode);
        AdBlockEngine.Stats filterStats = adBlock.getStats();
        String details = performanceMonitor.snapshot(modeLabel()) + samplingNote +
                "\n\n" + (deviceProfile == null ? "" : deviceProfile.summary()) +
                "\nWebView 内核：" + webViewProviderSummary() +
                "\n当前网络：" + networkSummary() +
                "\n当前热 WebView：" + liveWebViewCount() + " / 自适应上限 " + hotLimit +
                "\n性能提示：" + (aggressivePerformanceController.isActive() ? "已启用" : "未启用或系统不支持") +
                "\nWi-Fi 前台锁：" + wifiLockSummary() +
                "\n请求路径：" + (MODE_PERFORMANCE.equals(performanceMode) && performanceNetworkDirect ? "Chromium 直通（跳过广告规则扫描）" : "完整请求规则扫描") +
                "\n过滤热路径：" + filterStats.networkRules + " 条编译规则 · 已检查 " + filterStats.inspectedRequests + " 个请求" +
                "\n\n共同底线：热标签页池按设备内存自适应、视频网站走兼容路径、当前页不暂停定时器。" +
                "\n\n下载策略：所有新任务均由 Median 内部单连接下载器处理，不调用 Android DownloadManager。\n" +
                DownloadMemoryPolicy.diagnostics(this, performanceMode) +
                "\n下载始终单连接；中断后仅在续传时使用 Range，服务器不支持时自动从头继续。" +
                "\n文件名、MIME 和 APK 结构会在保存前复核；公共目录失败时保留已完成数据。" +
                "\n说明：WebView 的 IMPORTANT 只是防止内核被优先回收，不代表 CPU 加速。";
        new AlertDialog.Builder(this)
                .setTitle("性能诊断")
                .setMessage(details)
                .setPositiveButton("确定", null)
                .show();
    }

    private String webViewProviderSummary() {
        try {
            PackageInfo info = WebView.getCurrentWebViewPackage();
            if (info == null) return "未知";
            String version = info.versionName == null ? "" : info.versionName;
            return info.packageName + (version.length() == 0 ? "" : " " + version);
        } catch (RuntimeException e) {
            return "读取失败";
        }
    }

    private String networkSummary() {
        try {
            ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (manager == null) return "未知";
            NetworkCapabilities caps = manager.getNetworkCapabilities(manager.getActiveNetwork());
            if (caps == null) return "未连接";
            String transport;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) transport = "VPN";
            else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transport = "Wi-Fi";
            else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transport = "蜂窝网络";
            else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transport = "以太网";
            else transport = "其他";
            int estimated = caps.getLinkDownstreamBandwidthKbps();
            return transport + (estimated > 0 ? " · 系统链路估计 " + estimated + " kbps" : "");
        } catch (RuntimeException e) {
            return "读取失败";
        }
    }

    private String wifiLockSummary() {
        boolean held = false;
        try { held = mediaWifiLock != null && mediaWifiLock.isHeld(); } catch (RuntimeException ignored) {}
        if (!held) return "未持有";
        return Build.VERSION.SDK_INT >= 34 ? "低延迟前台锁" : "高性能前台锁";
    }

    private void setPerformanceMode(String mode) {
        if (!MODE_PERFORMANCE.equals(mode) && !MODE_POWER_SAVE.equals(mode)) mode = MODE_STANDARD;
        if (mode.equals(performanceMode)) return;
        performanceMode = mode;
        prefs.edit().putString("performance_mode", performanceMode).apply();
        applyPerformanceMode();
        updateMediaNetworkBoost();
        renderedProgress = -1;
        renderedAddress = null;
        renderedBackEnabled = null;
        renderedForwardEnabled = null;
        renderedTabCount = null;
        renderedShieldActive = null;
        requestChromeUpdate();
        toast("已切换为" + modeLabel());
    }

    private String modeLabel() {
        if (MODE_PERFORMANCE.equals(performanceMode)) return "极限性能";
        if (MODE_POWER_SAVE.equals(performanceMode)) return "低功耗模式";
        return "标准模式";
    }

    private boolean reduceMotion() {
        return MODE_POWER_SAVE.equals(performanceMode);
    }

    private int scriptThreadPriority() {
        if (MODE_PERFORMANCE.equals(performanceMode)) return Process.THREAD_PRIORITY_FOREGROUND;
        if (MODE_POWER_SAVE.equals(performanceMode)) return Process.THREAD_PRIORITY_BACKGROUND;
        return Process.THREAD_PRIORITY_DEFAULT;
    }

    private int progressStep() {
        if (MODE_PERFORMANCE.equals(performanceMode)) return 3;
        if (MODE_POWER_SAVE.equals(performanceMode)) return 10;
        return 5;
    }

    private void showScriptCenter() {
        final AlertDialog dialog = new AlertDialog.Builder(this).create();
        dialog.setTitle("用户脚本");

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(8), dp(18), dp(16));
        scroll.addView(content);

        TextView note = new TextView(this);
        note.setText("脚本来自第三方，能读取和修改匹配网页。只安装你信任的脚本。刷新页面后生效。");
        note.setTextColor(MUTED);
        note.setTextSize(13f);
        note.setPadding(0, 0, 0, dp(12));
        content.addView(note);

        Button browse = actionButton("浏览 Greasy Fork");
        Button current = actionButton("查找适用于当前网站的脚本");
        Button link = actionButton("通过 .user.js 链接安装");
        Button updateAll = actionButton("检查并更新全部脚本");
        content.addView(browse);
        content.addView(current);
        content.addView(link);
        content.addView(updateAll);

        TextView installedTitle = new TextView(this);
        installedTitle.setText("已安装脚本");
        installedTitle.setTextSize(15f);
        installedTitle.setTextColor(TEXT);
        installedTitle.setPadding(0, dp(18), 0, dp(6));
        content.addView(installedTitle);

        final List<UserScriptStore.Script> scripts = scriptStore.getAll();
        if (scripts.size() == 0) {
            TextView empty = new TextView(this);
            empty.setText("还没有安装脚本。进入 Greasy Fork 后点击“安装此脚本”，Median 会接管安装。 ");
            empty.setTextColor(MUTED);
            empty.setPadding(0, dp(8), 0, dp(8));
            content.addView(empty);
        } else {
            for (final UserScriptStore.Script script : scripts) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(3), 0, dp(3));

                CheckBox enabled = new CheckBox(this);
                String subtitle = script.version.length() > 0 ? "  v" + script.version : "";
                String state = script.quarantined ? "\n已隔离 · " + script.disabledReason : "\n" + script.riskSummary;
                enabled.setText(script.name + subtitle + state);
                enabled.setTextColor(script.quarantined ? Color.rgb(176, 0, 32) : TEXT);
                enabled.setTextSize(13.5f);
                enabled.setChecked(script.enabled && !script.quarantined);
                enabled.setEnabled(!script.quarantined);
                row.addView(enabled, new LinearLayout.LayoutParams(0, dp(62), 1f));

                Button details = new Button(this);
                details.setText("详情");
                details.setAllCaps(false);
                details.setTextSize(13f);
                details.setMinWidth(0);
                details.setMinHeight(0);
                row.addView(details, new LinearLayout.LayoutParams(dp(66), dp(42)));
                content.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(66)));

                enabled.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        scriptStore.setEnabled(script.id, ((CheckBox) v).isChecked());
                        refreshUserScriptRegistrations(true);
                    }
                });
                details.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        dialog.dismiss();
                        showScriptDetails(script);
                    }
                });
            }
        }

        browse.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dialog.dismiss(); webView.loadUrl("https://greasyfork.org/zh-CN/scripts"); }
        });
        current.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dialog.dismiss();
                String host = currentHost();
                webView.loadUrl(host.length() == 0 || isHomeUrl(webView.getUrl()) ? "https://greasyfork.org/zh-CN/scripts" : "https://greasyfork.org/zh-CN/scripts/by-site/" + host);
            }
        });
        link.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dialog.dismiss(); showScriptUrlDialog(); }
        });
        updateAll.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dialog.dismiss(); updateAllUserScripts(); }
        });

        dialog.setView(scroll);
        dialog.setButton(DialogInterface.BUTTON_NEGATIVE, "关闭", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int which) { d.dismiss(); }
        });
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface d) {
                dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setTextColor(BLUE);
            }
        });
        dialog.show();
    }

    private Button actionButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setTextSize(14f);
        button.setTextColor(TEXT);
        button.setBackground(roundRect(SURFACE, 12));
        button.setPadding(dp(14), 0, dp(12), 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        params.setMargins(0, dp(4), 0, dp(4));
        button.setLayoutParams(params);
        return button;
    }

    private void showScriptUrlDialog() {
        final EditText input = new EditText(this);
        input.setHint("https://.../script.user.js");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setPadding(dp(16), dp(8), dp(16), dp(8));
        new AlertDialog.Builder(this)
                .setTitle("安装用户脚本")
                .setView(input)
                .setPositiveButton("下载并检查", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { installScriptFromUrl(input.getText().toString().trim()); }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void installScriptFromUrl(final String sourceUrl) {
        final String normalizedSourceUrl;
        try { normalizedSourceUrl = NetworkSecurity.parseHttpsUrl(sourceUrl).toString(); }
        catch (Exception invalidUrl) {
            toast("只允许从有效的 HTTPS 地址安装脚本");
            return;
        }
        if (scriptDownloadInProgress) {
            toast("已有脚本正在下载");
            return;
        }
        scriptDownloadInProgress = true;
        toast("正在读取脚本…");
        final String userAgent = webView.getSettings().getUserAgentString();
        if (scriptNetworkExecutor == null || scriptNetworkExecutor.isShutdown()) {
            scriptDownloadInProgress = false;
            toast("脚本后台服务不可用");
            return;
        }
        scriptNetworkExecutor.execute(new Runnable() {
            @Override public void run() {
                try { Process.setThreadPriority(scriptThreadPriority()); } catch (RuntimeException ignored) {}
                try {
                    final UserScriptStore.Script script = downloadAndPrepareUserScript(normalizedSourceUrl, userAgent);
                    runOnUiThread(new Runnable() {
                        @Override public void run() { showScriptInstallConfirmation(script); }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() { toast("脚本安装失败：" + safeMessage(e)); }
                    });
                } finally {
                    scriptDownloadInProgress = false;
                }
            }
        });
    }

    private UserScriptStore.Script downloadAndPrepareUserScript(String sourceUrl, String userAgent) throws Exception {
        URL parsedUrl = NetworkSecurity.parseHttpsUrl(sourceUrl);
        HttpURLConnection connection = null;
        try {
            HashMap<String, String> headers = new HashMap<String, String>();
            headers.put("User-Agent", userAgent == null ? "MedianBrowser/2.0" : userAgent);
            headers.put("Accept", "text/javascript, application/javascript, text/plain, */*");
            connection = NetworkSecurity.openPublicHttpsGetFollowingRedirects(parsedUrl, 12000, 18000, headers);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IllegalArgumentException("HTTP " + status);
            int declared = connection.getContentLength();
            if (declared > MAX_SCRIPT_BYTES) throw new IllegalArgumentException("脚本超过 1 MB");
            byte[] bytes = readBounded(connection.getInputStream(), MAX_SCRIPT_BYTES, "脚本超过 1 MB");
            String finalUrl = connection.getURL().toString();
            UserScriptStore.Script parsed = scriptStore.parseUserScript(new String(bytes, "UTF-8"), finalUrl);
            if (parsed.updateUrl.length() > 0 && !"none".equalsIgnoreCase(parsed.updateUrl))
                parsed.updateUrl = resolveHttpsUrl(finalUrl, parsed.updateUrl, "@updateURL");
            if (parsed.downloadUrl.length() > 0 && !"none".equalsIgnoreCase(parsed.downloadUrl))
                parsed.downloadUrl = resolveHttpsUrl(finalUrl, parsed.downloadUrl, "@downloadURL");
            if (parsed.requires.size() > MAX_REQUIRE_COUNT) throw new IllegalArgumentException("@require 最多允许 " + MAX_REQUIRE_COUNT + " 项");
            StringBuilder dependencies = new StringBuilder();
            int dependencyBytes = 0;
            for (String requireUrl : parsed.requires) {
                String resolved = resolveHttpsUrl(finalUrl, requireUrl, "@require");
                byte[] dependency = downloadHttpsBytes(resolved, userAgent, MAX_REQUIRE_BYTES, "单个 @require 超过 512 KB");
                dependencyBytes += dependency.length;
                if (dependencyBytes > MAX_REQUIRE_TOTAL_BYTES) throw new IllegalArgumentException("@require 总大小超过 1 MB");
                dependencies.append("\n/* @require ").append(resolved.replace("*/", "* /")).append(" */\n")
                        .append(new String(dependency, "UTF-8")).append('\n');
            }
            parsed.requireCode = dependencies.toString();
            if (parsed.resources.size() > MAX_RESOURCE_COUNT) throw new IllegalArgumentException("@resource 最多允许 " + MAX_RESOURCE_COUNT + " 项");
            int resourceBytes = 0;
            for (UserScriptStore.Script.Resource resource : parsed.resources) {
                String resolved = resolveHttpsUrl(finalUrl, resource.url, "@resource");
                byte[] data = downloadHttpsBytes(resolved, userAgent, MAX_RESOURCE_BYTES, "单个 @resource 超过 512 KB");
                resourceBytes += data.length;
                if (resourceBytes > MAX_RESOURCE_TOTAL_BYTES) throw new IllegalArgumentException("@resource 总大小超过 2 MB");
                resource.url = resolved;
                resource.mime = guessResourceMime(resolved);
                resource.base64 = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
            }
            scriptStore.refreshAnalysis(parsed);
            return parsed;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String downloadHttpsText(String sourceUrl, String userAgent, int maxBytes) throws Exception {
        return new String(downloadHttpsBytes(sourceUrl, userAgent, maxBytes, "下载内容超过大小限制"), "UTF-8");
    }

    private byte[] downloadHttpsBytes(String sourceUrl, String userAgent, int maxBytes, String sizeError) throws Exception {
        URL parsed = NetworkSecurity.parseHttpsUrl(sourceUrl);
        HttpURLConnection connection = null;
        try {
            HashMap<String, String> headers = new HashMap<String, String>();
            headers.put("User-Agent", userAgent == null ? "MedianBrowser/2.0" : userAgent);
            headers.put("Accept", "text/javascript, application/javascript, text/plain, */*");
            connection = NetworkSecurity.openPublicHttpsGetFollowingRedirects(parsed, 12000, 15000, headers);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IllegalArgumentException("依赖资源 HTTP " + status);
            int declared = connection.getContentLength();
            if (declared > maxBytes) throw new IllegalArgumentException(sizeError);
            return readBounded(connection.getInputStream(), maxBytes, sizeError);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private byte[] readBounded(InputStream input, int maxBytes, String sizeError) throws Exception {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(32768, maxBytes));
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) throw new IllegalArgumentException(sizeError);
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    private String resolveHttpsUrl(String baseUrl, String candidate, String label) throws Exception {
        String resolved = new URL(new URL(baseUrl), candidate).toString();
        if (!resolved.startsWith("https://")) throw new IllegalArgumentException(label + " 只允许 HTTPS：" + resolved);
        return resolved;
    }

    private String guessResourceMime(String url) {
        String lower = url.toLowerCase(Locale.US);
        if (lower.matches(".*\\.(css)(?:[?#].*)?$")) return "text/css";
        if (lower.matches(".*\\.(js|mjs)(?:[?#].*)?$")) return "text/javascript";
        if (lower.matches(".*\\.(json)(?:[?#].*)?$")) return "application/json";
        if (lower.matches(".*\\.(png)(?:[?#].*)?$")) return "image/png";
        if (lower.matches(".*\\.(jpe?g)(?:[?#].*)?$")) return "image/jpeg";
        if (lower.matches(".*\\.(svg)(?:[?#].*)?$")) return "image/svg+xml";
        if (lower.matches(".*\\.(webp)(?:[?#].*)?$")) return "image/webp";
        if (lower.matches(".*\\.(txt|md|html?)(?:[?#].*)?$")) return "text/plain;charset=utf-8";
        return "application/octet-stream";
    }

    private void updateAllUserScripts() {
        updateUserScripts(scriptStore.getAll());
    }

    private void updateOneUserScript(UserScriptStore.Script script) {
        ArrayList<UserScriptStore.Script> scripts = new ArrayList<UserScriptStore.Script>();
        scripts.add(script);
        updateUserScripts(scripts);
    }

    private void updateUserScripts(final List<UserScriptStore.Script> scripts) {
        if (scriptDownloadInProgress) { toast("已有脚本任务正在运行"); return; }
        if (scripts == null || scripts.size() == 0) { toast("没有可检查的脚本"); return; }
        scriptDownloadInProgress = true;
        toast("正在检查脚本更新…");
        final String userAgent = webView == null ? "MedianBrowser" : webView.getSettings().getUserAgentString();
        if (scriptNetworkExecutor == null || scriptNetworkExecutor.isShutdown()) {
            scriptDownloadInProgress = false;
            toast("脚本后台服务不可用");
            return;
        }
        scriptNetworkExecutor.execute(new Runnable() {
            @Override public void run() {
                try { Process.setThreadPriority(scriptThreadPriority()); } catch (RuntimeException ignored) {}
                int checked = 0;
                int updated = 0;
                int failed = 0;
                int skipped = 0;
                ArrayList<UserScriptStore.Script> pendingSaves = new ArrayList<UserScriptStore.Script>();
                for (UserScriptStore.Script existing : scripts) {
                    String source = preferredUpdateUrl(existing);
                    if (!source.startsWith("https://")) { skipped++; continue; }
                    try {
                        UserScriptStore.Script candidate = downloadAndPrepareUserScript(source, userAgent);
                        checked++;
                        long now = System.currentTimeMillis();
                        int versionOrder = compareVersions(candidate.version, existing.version);
                        boolean changed = !candidate.code.equals(existing.code) || !candidate.requireCode.equals(existing.requireCode) ||
                                !resourceSignature(candidate).equals(resourceSignature(existing));
                        boolean shouldUpdate = versionOrder > 0 || (versionOrder == 0 && changed);
                        if (shouldUpdate) {
                            candidate.id = existing.id;
                            candidate.installedAt = existing.installedAt == 0L ? now : existing.installedAt;
                            candidate.updatedAt = now;
                            candidate.lastUpdateCheck = now;
                            candidate.enabled = existing.enabled;
                            candidate.quarantined = false;
                            candidate.disabledReason = "";
                            if (candidate.riskScore >= 8 || candidate.riskScore > existing.riskScore + 3) {
                                candidate.enabled = false;
                                candidate.quarantined = true;
                                candidate.disabledReason = "更新后权限或风险明显增加，请检查后重新启用";
                            }
                            pendingSaves.add(candidate);
                            updated++;
                        } else {
                            existing.lastUpdateCheck = now;
                            pendingSaves.add(existing);
                        }
                    } catch (Exception ignored) {
                        failed++;
                    }
                }
                scriptStore.saveBatch(pendingSaves);
                final int finalChecked = checked;
                final int finalUpdated = updated;
                final int finalFailed = failed;
                final int finalSkipped = skipped;
                scriptDownloadInProgress = false;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        String result = "脚本更新完成：检查 " + finalChecked + " 个，更新 " + finalUpdated + " 个";
                        if (finalSkipped > 0) result += "，跳过 " + finalSkipped + " 个无更新地址脚本";
                        if (finalFailed > 0) result += "，失败 " + finalFailed + " 个";
                        toast(result);
                        if (finalUpdated > 0) refreshUserScriptRegistrations(true);
                    }
                });
            }
        });
    }

    private String preferredUpdateUrl(UserScriptStore.Script script) {
        if (script == null) return "";
        if ("none".equalsIgnoreCase(script.updateUrl) || "none".equalsIgnoreCase(script.downloadUrl)) return "";
        if (script.downloadUrl.length() > 0) return script.downloadUrl;
        if (script.sourceUrl.length() > 0) return script.sourceUrl;
        if (script.updateUrl.length() > 0) return script.updateUrl;
        return script.sourceUrl;
    }

    private int compareVersions(String left, String right) {
        String l = left == null ? "" : left.trim();
        String r = right == null ? "" : right.trim();
        int plus = l.indexOf('+'); if (plus >= 0) l = l.substring(0, plus);
        plus = r.indexOf('+'); if (plus >= 0) r = r.substring(0, plus);
        String[] lp = l.split("-", 2);
        String[] rp = r.split("-", 2);
        String[] a = lp[0].split("[._]");
        String[] b = rp[0].split("[._]");
        int count = Math.max(a.length, b.length);
        for (int i = 0; i < count; i++) {
            String av = i < a.length ? a[i] : "0";
            String bv = i < b.length ? b[i] : "0";
            int comparison = compareVersionPart(av, bv, false);
            if (comparison != 0) return comparison;
        }
        boolean aPre = lp.length > 1 && lp[1].length() > 0;
        boolean bPre = rp.length > 1 && rp[1].length() > 0;
        if (aPre != bPre) return aPre ? -1 : 1;
        if (!aPre) return 0;
        a = lp[1].split("[._-]");
        b = rp[1].split("[._-]");
        count = Math.max(a.length, b.length);
        for (int i = 0; i < count; i++) {
            if (i >= a.length) return -1;
            if (i >= b.length) return 1;
            int comparison = compareVersionPart(a[i], b[i], true);
            if (comparison != 0) return comparison;
        }
        return 0;
    }

    private int compareVersionPart(String left, String right, boolean numericBeforeText) {
        boolean leftNumber = left.matches("[0-9]+");
        boolean rightNumber = right.matches("[0-9]+");
        if (leftNumber && rightNumber) {
            try { return Long.compare(Long.parseLong(left), Long.parseLong(right)); }
            catch (NumberFormatException ignored) {
                if (left.length() != right.length()) return left.length() < right.length() ? -1 : 1;
                return left.compareTo(right);
            }
        }
        if (numericBeforeText && leftNumber != rightNumber) return leftNumber ? -1 : 1;
        return left.compareToIgnoreCase(right);
    }

    private String resourceSignature(UserScriptStore.Script script) {
        StringBuilder value = new StringBuilder();
        for (UserScriptStore.Script.Resource resource : script.resources) {
            value.append(resource.name).append(':').append(resource.url).append(':').append(UrlCleaner.stableId(resource.base64)).append(';');
        }
        return value.toString();
    }

    private void showScriptInstallConfirmation(final UserScriptStore.Script script) {
        StringBuilder scope = new StringBuilder();
        int count = Math.min(6, script.matches.size());
        for (int i = 0; i < count; i++) scope.append("\n• ").append(script.matches.get(i));
        if (script.matches.size() > count) scope.append("\n• 以及其他 ").append(script.matches.size() - count).append(" 项");
        StringBuilder grants = new StringBuilder();
        int grantCount = Math.min(6, script.grants.size());
        for (int i = 0; i < grantCount; i++) grants.append(i == 0 ? "" : "、").append(script.grants.get(i));
        if (script.grants.size() > grantCount) grants.append(" 等 ").append(script.grants.size()).append(" 项");
        String message = "版本：" + (script.version.length() == 0 ? "未知" : script.version) +
                "\n来源：" + script.sourceUrl +
                "\n运行时机：" + script.runAt + (script.noFrames ? " · 仅顶层页面" : "") +
                "\n授权声明：" + (grants.length() == 0 ? "未声明" : grants.toString()) +
                "\nHTTPS 依赖：" + script.requires.size() + " 项" +
                "\nHTTPS 资源：" + script.resources.size() + " 项" +
                "\n网络范围：" + (script.connects.size() == 0 ? "未声明" : script.connects.toString()) +
                "\n风险评估：" + script.riskSummary + "（" + script.riskScore + " 分）" +
                "\n\n可运行的网站：" + scope.toString() +
                "\n\nMedian 支持 @require、@resource、常用 GM4 API、批量更新与慢脚本隔离。高权限 API 只在支持 document-start 的 WebView 中启用，桥接令牌保存在闭包内；网络请求会逐跳校验 @connect，并阻止远程网页访问本机与私网地址。旧 WebView 会关闭高权限 API。";
        new AlertDialog.Builder(this)
                .setTitle("安装“" + script.name + "”？")
                .setMessage(message)
                .setPositiveButton(script.riskScore >= 8 ? "安装（默认禁用）" : "安装并启用", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        script.enabled = script.riskScore < 8;
                        scriptStore.save(script);
                        refreshUserScriptRegistrations(script.enabled);
                        toast(script.enabled ? "脚本已安装并启用" : "高风险脚本已安装，默认禁用");
                    }
                })
                .setNeutralButton("安装但禁用", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        script.enabled = false;
                        scriptStore.save(script);
                        refreshUserScriptRegistrations(false);
                        toast("脚本已安装，当前未启用");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showScriptDetails(final UserScriptStore.Script script) {
        StringBuilder matches = new StringBuilder();
        for (String item : script.matches) matches.append("\n• ").append(item);
        String message = "状态：" + (script.quarantined ? "已隔离" : (script.enabled ? "已启用" : "已禁用")) +
                (script.disabledReason.length() == 0 ? "" : "\n原因：" + script.disabledReason) +
                "\n版本：" + (script.version.length() == 0 ? "未知" : script.version) +
                "\n风险：" + script.riskSummary + "（" + script.riskScore + " 分）" +
                "\n上次同步执行：" + String.format(Locale.US, "%.1f ms", script.lastCostMs) +
                "\n运行时机：" + script.runAt + (script.noFrames ? " · 仅顶层页面" : "") +
                "\n声明授权：" + (script.grants.size() == 0 ? "未声明" : script.grants.toString()) +
                "\nHTTPS 依赖：" + script.requires.size() + " 项" +
                "\nHTTPS 资源：" + script.resources.size() + " 项" +
                "\n网络范围：" + (script.connects.size() == 0 ? "未声明" : script.connects.toString()) +
                "\n来源：" + (script.sourceUrl.length() == 0 ? "本地" : script.sourceUrl) +
                "\n匹配范围：" + matches.toString();
        String positive = script.quarantined ? "重新启用" : (preferredUpdateUrl(script).length() > 0 ? "检查更新" : "确定");
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(script.name)
                .setMessage(message)
                .setPositiveButton(positive, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        if (script.quarantined) {
                            scriptStore.setEnabled(script.id, true);
                            refreshUserScriptRegistrations(true);
                            toast("脚本已重新启用；若再次造成卡顿会继续隔离");
                        } else if (preferredUpdateUrl(script).length() > 0) updateOneUserScript(script);
                    }
                })
                .setNegativeButton("返回", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { showScriptCenter(); }
                });
        builder.setNeutralButton("删除", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
                scriptStore.delete(script.id);
                refreshUserScriptRegistrations(true);
                toast("脚本已删除");
            }
        });
        builder.show();
    }

    private boolean looksLikeUserScript(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.US);
        return lower.startsWith("https://") && (lower.contains("update.greasyfork.org/scripts/") || lower.endsWith(".user.js") || lower.contains(".user.js?"));
    }

    private int enabledScriptCount() {
        int count = 0;
        for (UserScriptStore.Script script : scriptStore.getAll()) if (script.enabled) count++;
        return count;
    }

    private void showTabs() {
        saveCurrentTab();
        dismissOverlay();
        final int pageBackground = nightMode ? Color.rgb(27, 29, 32) : Color.rgb(248, 249, 250);
        final int cardBackground = nightMode ? Color.rgb(38, 41, 45) : WHITE;
        final int activeCard = nightMode ? Color.rgb(32, 54, 82) : Color.rgb(232, 240, 254);
        final int pageText = nightMode ? Color.rgb(232, 234, 237) : TEXT;
        final int pageMuted = nightMode ? Color.rgb(154, 160, 166) : MUTED;
        final FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(pageBackground);
        overlay.setClickable(true);
        overlay.setFocusableInTouchMode(true);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setClickable(true);
        page.setPadding(dp(14), dp(8), dp(14), dp(12));
        overlay.addView(page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(4), 0, 0, dp(8));
        TextView title = new TextView(this);
        title.setText("标签页  " + tabs.size());
        title.setTextSize(22f);
        title.setTextColor(pageText);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(54), 1f));
        BrowserIconView add = iconButton(BrowserIconView.PLUS, "新建标签页");
        BrowserIconView closePage = iconButton(BrowserIconView.CLOSE, "返回网页");
        add.setTintColor(pageText);
        closePage.setTintColor(pageText);
        header.addView(add, new LinearLayout.LayoutParams(dp(48), dp(48)));
        header.addView(closePage, new LinearLayout.LayoutParams(dp(48), dp(48)));
        page.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));

        final BaseAdapter[] tabAdapterHolder = new BaseAdapter[1];
        final class TabRow {
            final LinearLayout root;
            final TextView title;
            final TextView url;
            final BrowserIconView close;

            TabRow() {
                root = new LinearLayout(MainActivity.this);
                root.setOrientation(LinearLayout.HORIZONTAL);
                root.setGravity(Gravity.CENTER_VERTICAL);
                root.setPadding(dp(16), dp(8), dp(7), dp(8));
                root.setElevation(dp(1));
                // Bind touch directly to the card instead of relying on ListView row dispatch.
                // Some OEM ListView implementations stop delivering item clicks when a row
                // contains an independently clickable child such as the close button.
                root.setClickable(true);
                root.setLongClickable(true);
                root.setFocusable(false);
                LinearLayout text = new LinearLayout(MainActivity.this);
                text.setOrientation(LinearLayout.VERTICAL);
                text.setGravity(Gravity.CENTER_VERTICAL);
                title = new TextView(MainActivity.this);
                title.setTextColor(pageText);
                title.setTextSize(15.5f);
                title.setSingleLine(true);
                title.setEllipsize(android.text.TextUtils.TruncateAt.END);
                url = new TextView(MainActivity.this);
                url.setTextColor(pageMuted);
                url.setTextSize(12.5f);
                url.setSingleLine(true);
                url.setEllipsize(android.text.TextUtils.TruncateAt.END);
                text.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(27)));
                text.addView(url, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(23)));
                root.addView(text, new LinearLayout.LayoutParams(0, dp(58), 1f));
                close = iconButton(BrowserIconView.CLOSE, "关闭标签页");
                close.setTintColor(pageText);
                // Keep the close affordance touchable without allowing it to steal row focus.
                close.setFocusable(false);
                close.setFocusableInTouchMode(false);
                root.addView(close, new LinearLayout.LayoutParams(dp(46), dp(46)));
            }

            void bind(final int position) {
                final BrowserTab boundTab = tabs.get(position);
                title.setText((boundTab.pinned ? "固定 · " : "") + safeTitle(boundTab.title, boundTab.url));
                String host = hostOf(boundTab.url);
                url.setText(host.length() == 0 ? "主页" : host);
                root.setBackground(roundRect(position == currentTabIndex ? activeCard : cardBackground, 18));
                root.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View view) {
                        int currentPosition = tabs.indexOf(boundTab);
                        if (currentPosition < 0) return;
                        dismissOverlay();
                        switchTab(currentPosition);
                    }
                });
                root.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override public boolean onLongClick(View view) {
                        int currentPosition = tabs.indexOf(boundTab);
                        if (currentPosition < 0) return false;
                        BrowserTab tab = tabs.get(currentPosition);
                        tab.pinned = !tab.pinned;
                        persistSession();
                        if (tabAdapterHolder[0] != null) tabAdapterHolder[0].notifyDataSetChanged();
                        toast(tab.pinned ? "标签已固定" : "已取消固定");
                        return true;
                    }
                });
                close.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View view) {
                        int currentPosition = tabs.indexOf(boundTab);
                        if (currentPosition >= 0) closeTabAt(currentPosition);
                    }
                });
            }
        }

        final BaseAdapter tabAdapter = new BaseAdapter() {
            @Override public int getCount() { return tabs.size(); }
            @Override public BrowserTab getItem(int position) { return tabs.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TabRow row;
                if (convertView == null) {
                    row = new TabRow();
                    row.root.setTag(row);
                    convertView = row.root;
                } else row = (TabRow) convertView.getTag();
                row.bind(position);
                return convertView;
            }
        };
        tabAdapterHolder[0] = tabAdapter;
        ListView list = new ListView(this);
        list.setAdapter(tabAdapter);
        list.setBackgroundColor(Color.TRANSPARENT);
        list.setDivider(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        list.setDividerHeight(dp(10));
        list.setPadding(0, dp(4), 0, dp(20));
        list.setClipToPadding(false);
        list.setVerticalScrollBarEnabled(false);
        list.setItemsCanFocus(false);
        page.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        rootFrame.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        activeOverlay = overlay;
        activeOverlayPanel = page;
        activeOverlaySheet = false;
        Motion.showPage(overlay, page, reduceMotion());
        add.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dismissOverlay(); newTab(); }
        });
        closePage.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dismissOverlay(); }
        });
    }

    private void closeTabAt(int index) {
        if (index < 0 || index >= tabs.size()) return;
        if (tabs.size() <= 1) {
            dismissOverlay();
            resetOnlyTabToHome(true);
            return;
        }
        if (index == currentTabIndex) {
            dismissOverlay();
            closeCurrentTab();
            return;
        }
        BrowserTab removed = tabs.remove(index);
        rememberClosedTab(removed);
        destroyTabView(removed, false);
        if (index < currentTabIndex) currentTabIndex--;
        renderedTabCount = null;
        persistSession();
        dismissOverlay();
        showTabs();
    }

    // ==================== MCP UiBindings 实现 ====================
    @Override public WebView currentWebView() { return webView; }
    @Override public List<?> liveTabs() { return tabs; }
    @Override public int currentTabIndex() { return currentTabIndex; }
    @Override public Object dataStore() { return dataStore; }
    @Override public boolean isPrivateMode() { return false; }
    @Override public String applySetting(String key, String value) {
        try {
            if (key == null) return "key required";
            if ("night".equals(key) || "nightMode".equals(key)) {
                nightMode = "true".equalsIgnoreCase(value) || "1".equals(value);
                prefs.edit().putBoolean("night_mode", nightMode).apply();
                applyDarkMode();
                if (isHomeUrl(currentPageUrl)) showHome(); else webView.reload();
                return null;
            }
            if ("adBlock".equals(key) || "adblock".equals(key)) {
                adBlockEnabled = "true".equalsIgnoreCase(value) || "1".equals(value);
                prefs.edit().putBoolean("adblock", adBlockEnabled).apply();
                adBlockActiveByView.clear();
                webView.reload();
                return null;
            }
            if ("performance".equals(key) || "performanceMode".equals(key)) {
                setPerformanceMode(value);
                return null;
            }
            if ("searchEngine".equals(key) || "engine".equals(key)) {
                setSearchEngine(value);
                if (isHomeUrl(currentPageUrl)) showHome();
                return null;
            }
            if ("mcpBackgroundKeepAlive".equals(key) || "mcpKeepAlive".equals(key)) {
                boolean v = "true".equalsIgnoreCase(value) || "1".equals(value);
                prefs.edit().putBoolean("mcp_background_keepalive", v).apply();
                return null;
            }
            return "unknown setting: " + key;
        } catch (Exception e) {
            return e.getMessage() == null ? e.toString() : e.getMessage();
        }
    }
    @Override public void addBookmark(String url, String title) {
        if (url == null || url.isEmpty()) return;
        String t = title == null || title.isEmpty() ? url : title;
        boolean exists = false;
        for (Object b : dataStore.bookmarks()) {
            if (b instanceof BrowserDataStore.Bookmark && url.equals(((BrowserDataStore.Bookmark) b).url)) { exists = true; break; }
        }
        if (!exists) dataStore.toggleBookmark(t, url);
    }
    @Override public void clearHistory() { dataStore.clearHistory(); }
    @Override public void newTab(String url) {
        newTab();
        if (url != null && url.length() > 0 && webView != null) {
            if (isHttpUrlOrHome(url)) webView.loadUrl(url);
        }
    }
    private static boolean isHttpUrlOrHome(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://")
                || "about:blank".equals(url) || url.startsWith("median."));
    }

    private void newTab() {
        if (tabs.size() >= MAX_TABS) { toast("最多允许 " + MAX_TABS + " 个标签页"); return; }
        BrowserTab tab = new BrowserTab();
        tab.url = configuredHomeUrl();
        tabs.add(tab);
        activateTab(tabs.size() - 1);
        if (webView != null) webView.clearHistory();
        scheduleWebViewPrewarm();
    }

    public void switchTab(int index) {
        if (index < 0 || index >= tabs.size() || index == currentTabIndex) return;
        activateTab(index);
    }

    private void activateTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        BrowserTab previous = currentTabIndex >= 0 && currentTabIndex < tabs.size() ? tabs.get(currentTabIndex) : null;
        WebView previousView = webView;
        if (previous != null && previousView != null) {
            updateTabForView(previousView, previousView.getUrl(), previousView.getTitle());
            previous.lastActiveAt = SystemClock.uptimeMillis();
            previousView.onPause();
            if (previousView.getParent() == webContainer) webContainer.removeView(previousView);
        }

        currentTabIndex = index;
        BrowserTab targetTab = tabs.get(index);
        boolean created = targetTab.liveView == null;
        if (created) targetTab.liveView = acquireWebView();
        webView = targetTab.liveView;
        if (previousView != null && previousView != webView) applyPerformanceMode(previousView);
        applyPerformanceMode(webView);
        targetTab.lastActiveAt = SystemClock.uptimeMillis();
        if (webView.getParent() instanceof ViewGroup) ((ViewGroup) webView.getParent()).removeView(webView);
        webContainer.addView(webView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        webView.onResume();
        resetWebViewTransform(webView);
        webView.requestFocus(View.FOCUS_DOWN);

        navigationSequence++;
        preparedInjection = null;
        pageCommitted = true;
        pageFinished = webView.getProgress() >= 100;
        injectedStartSequence = -1;
        injectedEndSequence = -1;

        if (created) {
            boolean restored = targetTab.state != null && webView.restoreState(targetTab.state) != null;
            targetTab.state = null;
            if (!restored) {
                if (isHomeUrl(targetTab.url)) showHome();
                else {
                    currentPageUrl = targetTab.url;
                    currentPageHost = hostOf(targetTab.url);
                    webView.loadUrl(targetTab.url);
                }
            } else {
                currentPageUrl = webView.getUrl() == null ? targetTab.url : webView.getUrl();
                currentPageHost = hostOf(currentPageUrl);
            }
        } else {
            currentPageUrl = webView.getUrl() == null ? targetTab.url : webView.getUrl();
            currentPageHost = hostOf(currentPageUrl);
        }

        pageHosts.put(webView, currentPageHost == null ? "" : currentPageHost);
        updateMediaNetworkBoost();
        scheduleHotWebViewTrim();
        requestChromeUpdate();
        scheduleWebViewPrewarm();
        persistSession();
    }

    public void closeCurrentTab() {
        if (tabs.size() <= 1) {
            resetOnlyTabToHome(true);
            return;
        }
        int closingIndex = currentTabIndex;
        BrowserTab closing = tabs.get(closingIndex);
        rememberClosedTab(closing);
        if (webView != null && webView.getParent() == webContainer) webContainer.removeView(webView);
        destroyTabView(closing, false);
        tabs.remove(closingIndex);
        int target = Math.min(closingIndex, tabs.size() - 1);
        currentTabIndex = -1;
        webView = null;
        activateTab(target);
    }

    private void resetOnlyTabToHome(boolean remember) {
        if (tabs.size() == 0) tabs.add(new BrowserTab());
        BrowserTab tab = tabs.get(0);
        if (remember) rememberClosedTab(tab);
        tab.title = "新标签页";
        final String target = configuredHomeUrl();
        tab.url = target;
        tab.state = null;
        tab.pinned = false;
        if (webView != null) {
            try { webView.stopLoading(); webView.clearHistory(); } catch (RuntimeException ignored) {}
        }
        currentTabIndex = 0;
        openConfiguredHome();
        uiHandler.postDelayed(new Runnable() {
            @Override public void run() {
                if (webView != null && (isHomeUrl(target) ? isHomeUrl(webView.getUrl()) : target.equals(webView.getUrl())))
                    webView.clearHistory();
            }
        }, 250L);
        persistSession();
    }

    private void rememberClosedTab(BrowserTab tab) {
        if (tab == null || tab.url == null || isHomeUrl(tab.url)) return;
        BrowserTab copy = new BrowserTab();
        copy.title = tab.title;
        copy.url = tab.url;
        copy.pinned = tab.pinned;
        closedTabs.add(0, copy);
        while (closedTabs.size() > 12) closedTabs.remove(closedTabs.size() - 1);
    }

    private void showTabTools() {
        String[] items = new String[] {
                "搜索标签页",
                "重新打开最近关闭的标签" + (closedTabs.size() == 0 ? " · 无记录" : " · " + closedTabs.size()),
                "复制当前标签",
                "关闭其他标签",
                "冻结后台标签释放内存",
                "关闭全部并返回主页"
        };
        int[] icons = new int[] { BrowserIconView.SEARCH, BrowserIconView.RELOAD, BrowserIconView.PLUS,
                BrowserIconView.CLOSE, BrowserIconView.SPEED, BrowserIconView.HOME };
        showActionSheet("标签页工具", tabs.size() + " 个标签", items, icons, new SheetHandler() {
            @Override public void onItem(int which) {
                if (which == 0) showTabSearch();
                else if (which == 1) reopenClosedTab();
                else if (which == 2) duplicateCurrentTab();
                else if (which == 3) closeOtherTabs();
                else if (which == 4) {
                    for (int i = 0; i < tabs.size(); i++) if (i != currentTabIndex && !tabs.get(i).pinned) freezeTab(tabs.get(i));
                    toast("后台标签已冻结");
                } else closeAllTabs();
            }
        });
    }

    private void reopenClosedTab() {
        if (closedTabs.size() == 0) { toast("没有最近关闭的标签"); return; }
        if (tabs.size() >= MAX_TABS) { toast("标签页已达到上限"); return; }
        BrowserTab closed = closedTabs.remove(0);
        BrowserTab tab = new BrowserTab();
        tab.title = closed.title;
        tab.url = closed.url;
        tab.pinned = closed.pinned;
        tabs.add(tab);
        activateTab(tabs.size() - 1);
    }

    private void duplicateCurrentTab() {
        if (currentTabIndex < 0 || currentTabIndex >= tabs.size()) return;
        if (tabs.size() >= MAX_TABS) { toast("标签页已达到上限"); return; }
        saveCurrentTab();
        BrowserTab current = tabs.get(currentTabIndex);
        BrowserTab duplicate = new BrowserTab();
        duplicate.title = current.title;
        duplicate.url = current.url;
        tabs.add(duplicate);
        activateTab(tabs.size() - 1);
    }

    private void closeOtherTabs() {
        if (tabs.size() <= 1) { toast("没有其他标签"); return; }
        BrowserTab current = tabs.get(currentTabIndex);
        for (int i = tabs.size() - 1; i >= 0; i--) {
            BrowserTab tab = tabs.get(i);
            if (tab == current || tab.pinned) continue;
            rememberClosedTab(tab);
            destroyTabView(tab, false);
            tabs.remove(i);
        }
        currentTabIndex = tabs.indexOf(current);
        renderedTabCount = null;
        persistSession();
        requestChromeUpdate();
    }

    private void closeAllTabs() {
        BrowserTab active = currentTabIndex >= 0 && currentTabIndex < tabs.size() ? tabs.get(currentTabIndex) : null;
        for (BrowserTab tab : new ArrayList<BrowserTab>(tabs)) {
            rememberClosedTab(tab);
            if (tab != active) destroyTabView(tab, false);
        }
        tabs.clear();
        if (active == null) active = new BrowserTab();
        tabs.add(active);
        currentTabIndex = 0;
        resetOnlyTabToHome(false);
    }

    private void showTabSearch() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("标题或网址");
        new AlertDialog.Builder(this).setTitle("搜索标签页").setView(input)
                .setPositiveButton("搜索", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        String query = input.getText().toString().trim().toLowerCase(Locale.US);
                        final ArrayList<Integer> matches = new ArrayList<Integer>();
                        for (int i = 0; i < tabs.size(); i++) {
                            BrowserTab tab = tabs.get(i);
                            if (query.length() == 0 || tab.title.toLowerCase(Locale.US).contains(query) || tab.url.toLowerCase(Locale.US).contains(query)) matches.add(Integer.valueOf(i));
                        }
                        if (matches.size() == 0) { toast("没有匹配的标签"); return; }
                        String[] labels = new String[matches.size()];
                        for (int i = 0; i < labels.length; i++) {
                            BrowserTab tab = tabs.get(matches.get(i).intValue());
                            labels[i] = safeTitle(tab.title, tab.url) + "\n" + tab.url;
                        }
                        new AlertDialog.Builder(MainActivity.this).setTitle("匹配标签").setItems(labels, new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dialog, int which) { activateTab(matches.get(which).intValue()); }
                        }).show();
                    }
                }).setNegativeButton("取消", null).show();
    }

    private void saveCurrentTab() {
        if (tabs.size() == 0 || currentTabIndex < 0 || currentTabIndex >= tabs.size() || webView == null) return;
        BrowserTab tab = tabs.get(currentTabIndex);
        updateTabForView(webView, webView.getUrl(), webView.getTitle());
        tab.lastActiveAt = SystemClock.uptimeMillis();
    }

    private void updateCurrentTab(String url, String title) {
        if (tabs.size() == 0 || currentTabIndex < 0 || currentTabIndex >= tabs.size()) return;
        BrowserTab tab = tabs.get(currentTabIndex);
        if (url != null) tab.url = url;
        if (title != null && title.length() > 0) tab.title = title;
    }

    private void scheduleHotWebViewTrim() {
        if (hotTrimPending || webContainer == null) return;
        hotTrimPending = true;
        webContainer.postOnAnimation(new Runnable() {
            @Override public void run() {
                hotTrimPending = false;
                enforceHotWebViewLimit();
            }
        });
    }

    private void enforceHotWebViewLimit() {
        if (deviceProfile == null) return;
        int limit = deviceProfile.hotWebViewLimit(performanceMode);
        while (liveWebViewCount() > limit) {
            if (spareWebView != null) {
                destroyWebView(spareWebView);
                spareWebView = null;
                continue;
            }
            BrowserTab oldest = null;
            for (int i = 0; i < tabs.size(); i++) {
                BrowserTab candidate = tabs.get(i);
                if (i == currentTabIndex || candidate.liveView == null) continue;
                if (oldest == null || candidate.lastActiveAt < oldest.lastActiveAt) oldest = candidate;
            }
            if (oldest == null) break;
            freezeTab(oldest);
        }
    }

    private void freezeTab(BrowserTab tab) {
        if (tab == null || tab.liveView == null || tab.liveView == webView) return;
        WebView view = tab.liveView;
        updateTabForView(view, view.getUrl(), view.getTitle());
        Bundle saved = new Bundle();
        if (view.saveState(saved) != null) tab.state = saved;
        destroyTabView(tab, true);
        trimColdTabStates();
    }

    private void trimColdTabStates() {
        int limit = deviceProfile == null ? 2 : deviceProfile.coldTabStateLimit(performanceMode);
        int count = 0;
        for (int i = 0; i < tabs.size(); i++) if (i != currentTabIndex && tabs.get(i).state != null) count++;
        while (count > limit) {
            BrowserTab oldest = null;
            for (int i = 0; i < tabs.size(); i++) {
                BrowserTab candidate = tabs.get(i);
                if (i == currentTabIndex || candidate.state == null) continue;
                if (oldest == null || candidate.lastActiveAt < oldest.lastActiveAt) oldest = candidate;
            }
            if (oldest == null) return;
            oldest.state = null;
            count--;
        }
    }

    private void destroyTabView(BrowserTab tab, boolean keepState) {
        if (tab == null || tab.liveView == null) return;
        WebView view = tab.liveView;
        tab.liveView = null;
        if (!keepState) tab.state = null;
        if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
        destroyWebView(view);
    }

    private void recoverFromRendererLoss() {
        String reloadUrl = currentPageUrl;
        if (reloadUrl == null || reloadUrl.length() == 0) reloadUrl = HOME_URL;
        if (customView != null) exitFullscreen();
        if (spareWebView != null) {
            destroyWebView(spareWebView);
            spareWebView = null;
        }
        ArrayList<WebView> affected = new ArrayList<WebView>();
        for (BrowserTab tab : tabs) {
            if (tab.liveView != null && !affected.contains(tab.liveView)) {
                updateTabForView(tab.liveView, tab.liveView.getUrl(), tab.liveView.getTitle());
                affected.add(tab.liveView);
            }
            tab.liveView = null;
            tab.state = null;
        }
        for (WebView view : affected) destroyWebView(view);

        BrowserTab active = currentTabIndex >= 0 && currentTabIndex < tabs.size() ? tabs.get(currentTabIndex) : null;
        webView = createConfiguredWebView();
        if (active != null) {
            active.liveView = webView;
            active.lastActiveAt = SystemClock.uptimeMillis();
        }
        webContainer.removeAllViews();
        webContainer.addView(webView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        currentPageUrl = reloadUrl;
        currentPageHost = hostOf(reloadUrl);
        pageHosts.put(webView, currentPageHost);
        adBlockActiveByView.put(webView, Boolean.valueOf(isAdBlockActiveForHost(currentPageHost)));
        rendererRecoveryPending = false;
        if (isHomeUrl(reloadUrl)) showHome(); else webView.loadUrl(reloadUrl);
        requestChromeUpdate();
        scheduleWebViewPrewarm();
        toast("网页渲染进程已恢复");
    }

    private void destroyWebView(WebView view) {
        if (view == null) return;
        unresponsiveWebViews.remove(view);
        removeDocumentStartUserScripts(view);
        pageHosts.remove(view);
        adBlockActiveByView.remove(view);
        mobileUserAgents.remove(view);
        appliedSiteSettings.remove(view);
        cosmeticInjected.remove(view);
        trustedHomeViews.remove(view);
        customHomeViews.remove(view);
        try { view.stopLoading(); } catch (RuntimeException ignored) {}
        try { view.onPause(); } catch (RuntimeException ignored) {}
        view.setOnTouchListener(null);
        view.setOnLongClickListener(null);
        view.setDownloadListener(null);
        if (Build.VERSION.SDK_INT >= 29) {
            RendererWatchdog.detach(view);
        }
        view.setWebChromeClient(null);
        view.setWebViewClient(null);
        view.destroy();
    }

    private String safeTitle(String title, String url) {
        String value = title == null || title.trim().length() == 0 ? url : title.trim();
        if (isHomeUrl(url)) value = "主页";
        return value.length() > 34 ? value.substring(0, 34) + "…" : value;
    }

    private void enterFullscreen(View view, WebChromeClient.CustomViewCallback callback) {
        if (customView != null) {
            callback.onCustomViewHidden();
            return;
        }
        customView = view;
        customViewCallback = callback;
        previousOrientation = getRequestedOrientation();
        previousSystemUi = getWindow().getDecorView().getSystemUiVisibility();
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        browserChrome.setVisibility(View.GONE);
        rootFrame.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        view.requestFocus(View.FOCUS_DOWN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    private void exitFullscreen() {
        if (customView == null) return;
        rootFrame.removeView(customView);
        customView = null;
        browserChrome.setVisibility(View.VISIBLE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(previousSystemUi);
        setRequestedOrientation(previousOrientation);
        if (customViewCallback != null) customViewCallback.onCustomViewHidden();
        customViewCallback = null;
        resetWebViewTransform(webView);
        if (webView != null) webView.requestFocus(View.FOCUS_DOWN);
    }

    private void showPasswordMenu() {
        String[] items = new String[] { "保存当前站点账号", "填充当前页面", "管理已保存密码", "密码安全说明" };
        new AlertDialog.Builder(this)
                .setTitle("密码管理器")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        if (which == 3) { showVaultInfo(); return; }
                        final int action = which;
                        withVaultUnlock(new Runnable() {
                            @Override public void run() {
                                if (action == 0) saveCredentialDialog();
                                else if (action == 1) chooseCredentialToFill();
                                else manageCredentials();
                            }
                        });
                    }
                })
                .show();
    }

    private void withVaultUnlock(Runnable action) {
        if (action == null) return;
        if (SystemClock.elapsedRealtime() < vaultUnlockedUntil) { action.run(); return; }
        android.app.KeyguardManager manager = (android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (manager == null || !manager.isDeviceSecure()) {
            new AlertDialog.Builder(this).setTitle("需要安全锁屏")
                    .setMessage("Median 现在把 Keystore 密钥直接绑定到系统身份验证。请先设置 PIN、图案、密码或生物识别，密码库不会提供绕过入口。")
                    .setPositiveButton("打开安全设置", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface dialog, int which) {
                            try { startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS)); }
                            catch (Exception e) { toast("无法打开系统安全设置"); }
                        }
                    }).setNegativeButton("取消", null).show();
            return;
        }
        Intent unlock = manager.createConfirmDeviceCredentialIntent("解锁 Median 密码库", "确认设备身份后继续");
        if (unlock == null) { toast("系统身份验证不可用，密码库保持锁定"); return; }
        pendingVaultAction = action;
        try { startActivityForResult(unlock, VAULT_UNLOCK_REQUEST); }
        catch (Exception e) { pendingVaultAction = null; toast("无法调用系统身份验证"); }
    }

    private boolean requireSecurePage() {
        Uri uri = currentUri();
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null || isHomeUrl(uri.toString())) {
            toast("仅允许在 HTTPS 网站保存或填充密码");
            return false;
        }
        return currentHost().length() > 0;
    }

    private void saveCredentialDialog() {
        if (!requireSecurePage()) return;
        final String expectedUrl = currentPageUrl;
        final String expectedHost = currentHost();
        final EditText username = new EditText(this);
        username.setHint("用户名或邮箱");
        username.setSingleLine(true);
        final EditText password = new EditText(this);
        password.setHint("密码");
        password.setSingleLine(true);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), 0, dp(18), 0);
        TextView host = new TextView(this);
        host.setText("站点：" + expectedHost);
        host.setPadding(0, dp(8), 0, dp(8));
        content.addView(host);
        content.addView(username);
        content.addView(password);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("保存账号")
                .setView(content)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        final String user = username.getText().toString().trim();
                        final String pass = password.getText().toString();
                        if (!UrlCleaner.sameOrigin(expectedUrl, currentPageUrl) || !expectedHost.equalsIgnoreCase(currentHost()) ||
                                !requireSecurePage()) {
                            toast("页面来源已经变化，已拒绝保存");
                            return;
                        }
                        if (user.length() == 0 || pass.length() == 0) {
                            toast("用户名和密码不能为空");
                            return;
                        }
                        if (user.length() > 512 || pass.length() > 8192) { toast("账号或密码长度超过安全限制"); return; }
                        services.passwords().saveCredential(expectedHost, user, pass, new PasswordVault.Callback<Void>() {
                            @Override public void onComplete(Void value, Exception error) {
                                if (error == null) toast("已加密保存");
                                else toast("保存失败：" + safeMessage(error));
                            }
                        });
                    }
                })
                .setNegativeButton("取消", null)
                .create();
        secureDialog(dialog);
        dialog.show();
        String probe = "(function(){function v(e){return e&&!e.disabled&&e.offsetParent!==null;}var ps=Array.from(document.querySelectorAll('input[type=password]')).filter(v),p=ps.find(function(e){return e.autocomplete!=='new-password';})||ps[0],scope=p&&p.form?p.form:document;var us=Array.from(scope.querySelectorAll('input[autocomplete=username],input[type=email],input[type=text]')).filter(v),u=us[0];return JSON.stringify([u&&u.value||'',p&&p.value||'']);})();";
        webView.evaluateJavascript(probe, new ValueCallback<String>() {
            @Override public void onReceiveValue(String value) {
                if (!UrlCleaner.sameOrigin(expectedUrl, currentPageUrl)) return;
                try {
                    Object decoded = new JSONTokener(value).nextValue();
                    JSONArray fields = new JSONArray(decoded instanceof String ? (String) decoded : String.valueOf(decoded));
                    if (username.getText().length() == 0) username.setText(fields.optString(0, ""));
                    if (password.getText().length() == 0) password.setText(fields.optString(1, ""));
                } catch (Exception ignored) {}
            }
        });
    }

    private void chooseCredentialToFill() {
        if (!requireSecurePage()) return;
        final String requestedHost = currentHost();
        services.passwords().forHost(requestedHost, new PasswordVault.Callback<List<PasswordVault.Credential>>() {
            @Override public void onComplete(final List<PasswordVault.Credential> credentials, Exception error) {
                if (error != null) {
                    toast("读取密码库失败：" + safeMessage(error));
                    return;
                }
                if (!requestedHost.equalsIgnoreCase(currentHost())) {
                    toast("页面已经变化，请重新选择账号");
                    return;
                }
                if (credentials == null || credentials.size() == 0) {
                    toast("当前站点没有已保存账号");
                    return;
                }
                String[] names = new String[credentials.size()];
                for (int i = 0; i < credentials.size(); i++) names[i] = credentials.get(i).username;
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("选择账号")
                        .setItems(names, new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dialog, int which) { fillCredential(credentials.get(which)); }
                        })
                        .show();
            }
        });
    }

    private void fillCredential(PasswordVault.Credential credential) {
        if (!requireSecurePage()) return;
        if (!currentHost().equalsIgnoreCase(credential.host)) {
            toast("站点不匹配，已拒绝填充");
            return;
        }
        String user = JSONObject.quote(credential.username);
        String pass = JSONObject.quote(credential.password);
        String js = "(function(){function visible(e){return e&&!e.disabled&&e.offsetParent!==null;}var ps=Array.from(document.querySelectorAll('input[type=password]')).filter(visible),p=ps.find(function(e){return e.autocomplete!=='new-password'&&e.autocomplete!=='one-time-code';})||ps[0],scope=p&&p.form?p.form:document,us=Array.from(scope.querySelectorAll('input[autocomplete=username],input[type=email],input[type=text]')).filter(visible),u=us.find(function(e){return e.autocomplete==='username'||e.type==='email';})||us[0];" +
                "function set(el,v){if(!el)return;var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');if(d&&d.set)d.set.call(el,v);else el.value=v;el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));}" +
                "set(u," + user + ");set(p," + pass + ");return !!p;})();";
        webView.evaluateJavascript(js, null);
        toast("已填充，请确认网站后再提交");
    }

    private void manageCredentials() {
        services.passwords().getAll(new PasswordVault.Callback<List<PasswordVault.Credential>>() {
            @Override public void onComplete(final List<PasswordVault.Credential> all, Exception error) {
                if (error != null) {
                    toast("读取密码库失败：" + safeMessage(error));
                    return;
                }
                if (all == null || all.size() == 0) {
                    toast("密码库为空");
                    return;
                }
                String[] names = new String[all.size()];
                for (int i = 0; i < all.size(); i++) names[i] = all.get(i).host + "\n" + all.get(i).username;
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("已保存密码（点按删除）")
                        .setItems(names, new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dialog, int which) { confirmDeleteCredential(all.get(which)); }
                        })
                        .setNegativeButton("关闭", null)
                        .show();
            }
        });
    }

    private void confirmDeleteCredential(final PasswordVault.Credential credential) {
        new AlertDialog.Builder(this)
                .setTitle("删除账号？")
                .setMessage(credential.host + "\n" + credential.username)
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        services.passwords().delete(credential.id, new PasswordVault.Callback<Void>() {
                            @Override public void onComplete(Void value, Exception error) {
                                if (error == null) toast("已删除");
                                else toast("删除失败：" + safeMessage(error));
                            }
                        });
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showVaultInfo() {
        new AlertDialog.Builder(this)
                .setTitle("密码安全说明")
                .setMessage("密码数据使用 AES-GCM 加密，密钥存放在 Android Keystore，并要求最近 120 秒内完成系统身份验证；旧版未绑定身份的密钥会在首次成功解锁时迁移。应用禁止系统备份。\n\n只在 HTTPS 网站手动保存和填充，不向网页开放原生 JavaScript 桥。\n\n仍未经过独立安全审计，不建议保存金融账户或主要邮箱密码。")
                .setPositiveButton("知道了", null)
                .show();
    }

    private void secureDialog(final AlertDialog dialog) {
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface d) {
                if (dialog.getWindow() != null) dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            }
        });
    }

    private void showFindDialog() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setPadding(dp(18), dp(8), dp(18), dp(8));
        new AlertDialog.Builder(this)
                .setTitle("页面内查找")
                .setView(input)
                .setPositiveButton("查找", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        webView.findAllAsync(input.getText().toString());
                        webView.showFindDialog(input.getText().toString(), false);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void sharePage() {
        String url = webView.getUrl();
        if (url == null || isHomeUrl(url)) return;
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, (webView.getTitle() == null ? "" : webView.getTitle() + "\n") + url);
        startActivity(Intent.createChooser(share, "分享页面"));
    }

    private void showStoragePanel() {
        toast("正在统计浏览数据");
        scriptExecutor.execute(new Runnable() {
            @Override public void run() {
                final StoragePolicy.Snapshot snapshot = StoragePolicy.snapshot(MainActivity.this);
                uiHandler.post(new Runnable() {
                    @Override public void run() { showStorageSnapshot(snapshot); }
                });
            }
        });
    }

    private void showStorageSnapshot(final StoragePolicy.Snapshot snapshot) {
        String subtitle = "总计 " + StoragePolicy.format(snapshot.totalBytes) +
                " · 临时缓存 " + StoragePolicy.format(snapshot.transientBytes) +
                " · 站点及设置 " + StoragePolicy.format(snapshot.siteBytes);
        String[] items = new String[] {
                "清理临时缓存（保留登录）",
                "清除 Cookie 与站点数据（会退出登录）",
                "清除全部浏览数据",
                "重新统计"
        };
        int[] icons = new int[] { BrowserIconView.RELOAD, BrowserIconView.SHIELD, BrowserIconView.CLOSE, BrowserIconView.SEARCH };
        showActionSheet("存储与数据", subtitle, items, icons, new SheetHandler() {
            @Override public void onItem(int which) {
                if (which == 0) {
                    webView.clearCache(true);
                    toast("临时缓存已清理");
                } else if (which == 1) {
                    CookieManager.getInstance().removeAllCookies(null);
                    CookieManager.getInstance().flush();
                    android.webkit.WebStorage.getInstance().deleteAllData();
                    toast("Cookie 与站点数据已清除");
                } else if (which == 2) {
                    confirmClearData();
                } else {
                    showStoragePanel();
                }
            }
        });
    }

    private void maybeTrimTransientCache() {
        if (scriptExecutor == null || scriptExecutor.isShutdown()) return;
        if (isMediaCompatibilityHost(currentPageHost)) return;
        long now = System.currentTimeMillis();
        long last = prefs.getLong("last_cache_check", 0L);
        if (now - last < 24L * 60L * 60L * 1000L) return;
        prefs.edit().putLong("last_cache_check", now).apply();
        final String mode = performanceMode;
        scriptExecutor.execute(new Runnable() {
            @Override public void run() {
                StoragePolicy.Snapshot snapshot = StoragePolicy.snapshot(MainActivity.this);
                long budget = StoragePolicy.budgetBytes(MainActivity.this, mode);
                final boolean substantiallyOverBudget = snapshot.transientBytes > budget + budget / 4L;
                if (!substantiallyOverBudget) return;
                uiHandler.post(new Runnable() {
                    @Override public void run() {
                        if (webView != null) webView.clearCache(false);
                    }
                });
            }
        });
    }

    private void confirmClearData() {
        new AlertDialog.Builder(this)
                .setTitle("清除浏览数据")
                .setMessage("将清除 Cookie、缓存、表单、HTTP 身份验证、历史、下载索引、网站权限与本地存储。下载文件、书签、离线页面、密码和用户脚本不会删除。")
                .setPositiveButton("清除", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        CookieManager.getInstance().removeAllCookies(new ValueCallback<Boolean>() {
                            @Override public void onReceiveValue(Boolean value) { CookieManager.getInstance().flush(); }
                        });
                        for (BrowserTab tab : tabs) if (tab.liveView != null) {
                            tab.liveView.clearCache(true);
                            tab.liveView.clearFormData();
                            tab.liveView.clearHistory();
                        }
                        if (dataStore != null) dataStore.clearHistory();
                        services.downloads().clear();
                        if (siteSettingsStore != null) siteSettingsStore.clearAll();
                        appliedSiteSettings.clear();
                        for (BrowserTab tab : tabs) if (tab.liveView != null) applySiteSettings(tab.liveView, pageHostFor(tab.liveView));
                        android.webkit.WebStorage.getInstance().deleteAllData();
                        android.webkit.GeolocationPermissions.getInstance().clearAll();
                        android.webkit.WebViewDatabase database = android.webkit.WebViewDatabase.getInstance(MainActivity.this);
                        database.clearHttpAuthUsernamePassword();
                        database.clearFormData();
                        toast("浏览数据已清除");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showCommunityNoticeOnFirstLaunch(Bundle state) {
        if (state != null || prefs.getBoolean(PREF_COMMUNITY_NOTICE_SHOWN, false)) return;
        prefs.edit().putBoolean(PREF_COMMUNITY_NOTICE_SHOWN, true).apply();
        uiHandler.postDelayed(new Runnable() {
            @Override public void run() {
                if (isFinishing() || (Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
                showCommunityNotice();
            }
        }, 350L);
    }

    private void showCommunityNotice() {
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("欢迎使用 Median Browser")
                .setMessage("感谢你使用 Median Browser。我们会持续改进轻量体验、隐私保护与网页兼容性。\n\n"
                        + COMMUNITY_INFO
                        + "\n\n本提示仅在首次启动时显示。之后可在“设置 → 关于 Median”中再次查看。")
                .setPositiveButton("开始使用", null)
                .create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface ignored) { enableDialogLinks(dialog); }
        });
        dialog.show();
    }

    private void enableDialogLinks(AlertDialog dialog) {
        TextView message = dialog.findViewById(android.R.id.message);
        if (message == null) return;
        android.text.util.Linkify.addLinks(message, android.text.util.Linkify.WEB_URLS);
        message.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        message.setLinksClickable(true);
    }

    private void showAbout() {
        String profile = deviceProfile == null ? "设备策略：尚未初始化" : deviceProfile.summary();
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Median Browser " + appVersionName())
                .setMessage("本版整合过滤订阅、用户脚本、Median 单连接下载、媒体中心、独立隐私进程、离线 MHTML、阅读模式、系统朗读、标签批量工具、跟踪参数清理、站点权限和三档性能调度。所有新下载均由 Median 自己处理，不调用系统下载器。\n\n密码库使用系统身份验证与 Android Keystore；应用不集成广告、分析或遥测 SDK。\n\n" + profile + "\n\nMedian 使用设备的 Android System WebView；网页兼容性、协议与媒体能力取决于系统 WebView 版本。\n\n" + COMMUNITY_INFO)
                .setPositiveButton("确定", null)
                .setNeutralButton("隐私说明", null)
                .setNegativeButton("兼容诊断", null)
                .create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface ignored) {
                enableDialogLinks(dialog);
                Button privacy = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
                if (privacy != null) privacy.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { showPrivacyNotice(); }
                });
                Button diagnostics = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
                if (diagnostics != null) diagnostics.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { showCompatibilityDiagnostics(); }
                });
            }
        });
        dialog.show();
    }

    private void showCompatibilityDiagnostics() {
        final String report = WebViewCompat.getDocumentStartDiagnosticReport()
                + "\n\n安全降级：不支持 document-start 时，Median 只运行不含原生权限的用户脚本；"
                + "跨域请求、下载、剪贴板等高权限接口保持关闭。";
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("WebView 兼容诊断")
                .setMessage(report)
                .setPositiveButton("运行注入自测", null)
                .setNeutralButton("复制", null)
                .setNegativeButton("关闭", null)
                .create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface ignored) {
                Button copy = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
                if (copy != null) copy.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        if (clipboard != null) {
                            clipboard.setPrimaryClip(ClipData.newPlainText("Median WebView diagnostics", report));
                            toast("诊断信息已复制");
                        }
                    }
                });
                Button test = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                if (test != null) test.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { runDocumentStartSelfTest(); }
                });
            }
        });
        dialog.show();
    }

    private void runDocumentStartSelfTest() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            toast("当前 System WebView 不支持安全的 document-start 注入");
            return;
        }
        final WebView probe = new WebView(this);
        final String token = UrlCleaner.randomToken();
        final ScriptHandler[] registration = new ScriptHandler[1];
        final boolean[] finished = new boolean[] { false };
        WebSettings settings = probe.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        probe.setAlpha(0f);
        rootFrame.addView(probe, new FrameLayout.LayoutParams(1, 1));

        final Runnable cleanup = new Runnable() {
            @Override public void run() {
                if (finished[0]) return;
                finished[0] = true;
                if (registration[0] != null) try { registration[0].remove(); } catch (RuntimeException ignored) {}
                if (probe.getParent() instanceof ViewGroup) ((ViewGroup) probe.getParent()).removeView(probe);
                try { probe.stopLoading(); } catch (RuntimeException ignored) {}
                probe.destroy();
            }
        };

        probe.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript("String(window.__medianCompatOrder||'missing')", new ValueCallback<String>() {
                    @Override public void onReceiveValue(String value) {
                        boolean passed = value != null && value.contains("before");
                        cleanup.run();
                        toast(passed ? "document-start 自测通过" : "document-start 自测失败，已保持安全降级");
                    }
                });
            }
        });
        try {
            registration[0] = WebViewCompat.addDocumentStartJavaScript(
                    probe, "window.__medianCompatToken=" + JSONObject.quote(token) + ";",
                    Collections.singleton("https://median-compat.invalid"));
            String html = "<!doctype html><meta charset=utf-8><script>"
                    + "window.__medianCompatOrder=(window.__medianCompatToken===" + JSONObject.quote(token)
                    + ")?'before':'after';</script>compat";
            probe.loadDataWithBaseURL("https://median-compat.invalid/", html, "text/html", "UTF-8", null);
            uiHandler.postDelayed(new Runnable() {
                @Override public void run() {
                    if (finished[0]) return;
                    cleanup.run();
                    toast("document-start 自测超时，已保持安全降级");
                }
            }, 6000L);
        } catch (RuntimeException error) {
            cleanup.run();
            toast("自测无法启动：" + safeMessage(error));
        }
    }

    private String appVersionName() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName == null ? "" : info.versionName;
        } catch (Exception ignored) { return ""; }
    }

    private void showPrivacyNotice() {
        new AlertDialog.Builder(this)
                .setTitle("隐私与数据处理")
                .setMessage("Median 默认在设备本地保存书签、历史记录、标签会话、网站设置、下载记录、用户脚本和过滤规则。应用本身不包含广告、分析或遥测 SDK，也不运营同步服务器。\n\n访问网页时，目标网站、其内容提供方、所选搜索引擎、翻译服务或过滤订阅源会接收正常网络请求。网站可按其自身政策使用 Cookie 和其他存储。\n\n摄像头、麦克风和位置仅在 HTTPS 网站主动请求、当前页面来源匹配且你授予 Android 权限后提供。密码库使用 Android Keystore 与 AES-GCM；完整备份由你设置的密码加密。\n\n用户脚本属于第三方代码。Median 会显示其匹配范围和权限，并限制原生网络范围，但你仍应只安装可信脚本。清除浏览数据或卸载应用会删除相应本地数据；导出的文件由你自行保管。完整政策见 Google Play 商店页面或项目仓库中的 PRIVACY_POLICY.md。")
                .setPositiveButton("知道了", null)
                .show();
    }

    private void setSearchEngine(String engine) {
        if (!"baidu".equals(engine) && !"bing".equals(engine) && !"custom".equals(engine)) engine = "google";
        if ("custom".equals(engine) && !validSearchTemplate(customSearchTemplate)) engine = "google";
        searchEngine = engine;
        prefs.edit().putString("search_engine", searchEngine).apply();
    }

    private void applyPerformanceMode() {
        applyUiThreadPriority();
        applyDisplayPolicy();
        updateAggressivePerformanceResources();
        for (BrowserTab tab : tabs) if (tab.liveView != null) applyPerformanceMode(tab.liveView);
        if (spareWebView != null) applyPerformanceMode(spareWebView);
        enforceHotWebViewLimit();
        if (deviceProfile != null && deviceProfile.allowPrewarmedWebView(performanceMode)) scheduleWebViewPrewarm();
    }

    private void applyUiThreadPriority() {
        int priority = MODE_PERFORMANCE.equals(performanceMode)
                ? Process.THREAD_PRIORITY_DISPLAY
                : Process.THREAD_PRIORITY_DEFAULT;
        try { Process.setThreadPriority(Process.myTid(), priority); }
        catch (RuntimeException ignored) {}
    }

    private void applyDisplayPolicy() {
        if (getWindow() == null) return;
        try {
            WindowManager.LayoutParams attributes = getWindow().getAttributes();
            attributes.preferredDisplayModeId = 0;
            attributes.preferredRefreshRate = 0f;
            if (MODE_PERFORMANCE.equals(performanceMode)) {
                Display display = getWindowManager().getDefaultDisplay();
                Display.Mode best = highestRefreshMode(display);
                attributes.preferredDisplayModeId = best.getModeId();
                attributes.preferredRefreshRate = best.getRefreshRate();
            }
            getWindow().setAttributes(attributes);
        } catch (RuntimeException ignored) {}
    }

    private Display.Mode highestRefreshMode(Display display) {
        Display.Mode current = display.getMode();
        Display.Mode best = current;
        for (Display.Mode candidate : display.getSupportedModes()) {
            if (candidate.getPhysicalWidth() == current.getPhysicalWidth() &&
                    candidate.getPhysicalHeight() == current.getPhysicalHeight() &&
                    candidate.getRefreshRate() > best.getRefreshRate()) {
                best = candidate;
            }
        }
        return best;
    }

    private long performanceTargetNanos() {
        float refreshRate = 60f;
        try {
            Display display = getWindowManager().getDefaultDisplay();
            refreshRate = Math.max(60f, highestRefreshMode(display).getRefreshRate());
        } catch (RuntimeException ignored) {}
        long frameBudget = (long) (1_000_000_000d / refreshRate);
        return Math.max(2_000_000L, frameBudget * 2L / 3L);
    }

    private void updateAggressivePerformanceResources() {
        boolean active = activityResumed && MODE_PERFORMANCE.equals(performanceMode);
        uiHandler.removeCallbacks(performanceHintStarter);
        if (active) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            acquirePerformanceWakeLock();
            if (!aggressivePerformanceController.isActive()) {
                // Let HWUI create RenderThread before assembling the hint session.
                uiHandler.postDelayed(performanceHintStarter, 250L);
            } else {
                aggressivePerformanceController.start(this, performanceTargetNanos());
            }
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            releasePerformanceWakeLock();
            aggressivePerformanceController.stop(this);
        }
    }

    private void acquirePerformanceWakeLock() {
        try {
            if (performanceWakeLock == null) {
                PowerManager manager = (PowerManager) getApplicationContext().getSystemService(Context.POWER_SERVICE);
                if (manager == null) return;
                performanceWakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Median:ExtremePerformance");
                performanceWakeLock.setReferenceCounted(false);
            }
            performanceWakeLock.acquire(10L * 60L * 1000L);
            uiHandler.removeCallbacks(performanceWakeRenewal);
            uiHandler.postDelayed(performanceWakeRenewal, 9L * 60L * 1000L);
        } catch (SecurityException ignored) {
        } catch (RuntimeException ignored) {
        }
    }

    private PowerManager.WakeLock mcpKeepAliveLock;
    private void acquireMcpKeepAliveLock() {
        try {
            if (mcpKeepAliveLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                mcpKeepAliveLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "median:mcp-keepalive");
                mcpKeepAliveLock.setReferenceCounted(false);
            }
            if (!mcpKeepAliveLock.isHeld()) mcpKeepAliveLock.acquire();
        } catch (Exception ignored) {}
    }
    private void startKeepAliveService() {
        try {
            Intent intent = new Intent(this, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
            else startService(intent);
        } catch (RuntimeException ignored) {}
    }
    private void stopKeepAliveService() {
        try { stopService(new Intent(this, KeepAliveService.class)); } catch (RuntimeException ignored) {}
    }
    private void releaseMcpKeepAliveLock() {
        try {
            if (mcpKeepAliveLock != null && mcpKeepAliveLock.isHeld()) mcpKeepAliveLock.release();
        } catch (Exception ignored) {}
    }
    private void releasePerformanceWakeLock() {
        uiHandler.removeCallbacks(performanceWakeRenewal);
        if (performanceWakeLock == null) return;
        try { if (performanceWakeLock.isHeld()) performanceWakeLock.release(); }
        catch (RuntimeException ignored) {}
    }

    private void applyPerformanceMode(WebView target) {
        if (target == null) return;
        WebSettings settings = target.getSettings();
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        boolean mediaPage = isMediaCompatibilityHost(pageHostFor(target));
        settings.setOffscreenPreRaster(!mediaPage && deviceProfile != null && deviceProfile.allowOffscreenPreRaster(performanceMode));
        boolean offline = isOfflineUrl(target.getUrl());
        settings.setAllowContentAccess(offline);
        settings.setBlockNetworkLoads(offline);
        if (offline) settings.setJavaScriptEnabled(false);
        SiteSettingsStore.SiteSettings site = siteSettingsStore == null ? new SiteSettingsStore.SiteSettings() : siteSettingsStore.forHost(pageHostFor(target));
        settings.setLoadsImagesAutomatically(site.images != SiteSettingsStore.BLOCK);
        try {
            // Only the visible renderer stays IMPORTANT. Hot background tabs remain
            // restorable but cannot compete at the same OOM/CPU scheduling class.
            boolean active = target == webView;
            target.setRendererPriorityPolicy(active ? WebView.RENDERER_PRIORITY_IMPORTANT : WebView.RENDERER_PRIORITY_BOUND, !active);
        } catch (RuntimeException ignored) {
        }
    }

    private void applyDesktopMode() {
        for (BrowserTab tab : tabs) if (tab.liveView != null) applyDesktopMode(tab.liveView);
        if (spareWebView != null) applyDesktopMode(spareWebView);
    }

    private void applyDesktopMode(WebView target) {
        if (target == null) return;
        WebSettings settings = target.getSettings();
        SiteSettingsStore.SiteSettings site = siteSettingsStore == null ? new SiteSettingsStore.SiteSettings() : siteSettingsStore.forHost(pageHostFor(target));
        boolean enabled = site.desktop == SiteSettingsStore.ALLOW || (site.desktop == SiteSettingsStore.INHERIT && desktopMode);
        String mobile = mobileUserAgents.get(target);
        if (mobile == null || mobile.length() == 0) {
            mobile = WebViewPolicy.mobileUserAgent(settings.getUserAgentString());
            mobileUserAgents.put(target, mobile);
        }
        String desiredUserAgent = mobile;
        if (enabled) {
            desiredUserAgent = desktopUserAgent(mobile);
            settings.setUseWideViewPort(true);
            settings.setLoadWithOverviewMode(true);
        } else {
            settings.setUseWideViewPort(false);
            settings.setLoadWithOverviewMode(false);
        }
        if (!desiredUserAgent.equals(settings.getUserAgentString())) settings.setUserAgentString(desiredUserAgent);
    }

    private String desktopUserAgent(String mobile) {
        String chrome = "Chrome/120.0.0.0";
        int start = mobile == null ? -1 : mobile.indexOf("Chrome/");
        if (start >= 0) {
            int end = mobile.indexOf(' ', start);
            chrome = mobile.substring(start, end < 0 ? mobile.length() : end);
        }
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " + chrome + " Safari/537.36";
    }

    private void applySiteSettings(WebView target, String host) {
        if (target == null) return;
        String normalized = host == null ? "" : host;
        pageHosts.put(target, normalized);
        if (target == webView) currentPageHost = normalized;
        adBlockActiveByView.put(target, Boolean.valueOf(isAdBlockActiveForHost(normalized)));
        applyPerformanceMode(target);
        SiteSettingsStore.SiteSettings site = siteSettingsStore == null ? new SiteSettingsStore.SiteSettings() : siteSettingsStore.forHost(normalized);
        String configKey = normalized + '|' + site.javascript + '|' + site.images + '|' +
                site.thirdPartyCookies + '|' + site.desktop + '|' + site.dark + '|' + site.popups + '|' +
                site.autoplay + '|' + site.location + '|' + site.camera + '|' + site.microphone + '|' +
                site.trackingProtection + '|' + site.compatibilityMode + '|' + site.textZoom +
                '|' + desktopMode + '|' + nightMode + '|' + acceptThirdPartyCookies;
        if (configKey.equals(appliedSiteSettings.get(target))) return;
        WebSettings settings = target.getSettings();
        settings.setMixedContentMode(site.compatibilityMode ? WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE : WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptEnabled(site.javascript != SiteSettingsStore.BLOCK);
        settings.setLoadsImagesAutomatically(site.images != SiteSettingsStore.BLOCK);
        settings.setBlockNetworkImage(site.images == SiteSettingsStore.BLOCK);
        settings.setTextZoom(site.textZoom);
        settings.setSupportMultipleWindows(site.compatibilityMode || site.popups != SiteSettingsStore.BLOCK);
        settings.setJavaScriptCanOpenWindowsAutomatically(site.popups == SiteSettingsStore.ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(site.autoplay != SiteSettingsStore.ALLOW);
        boolean allowThirdParty = site.compatibilityMode || site.thirdPartyCookies == SiteSettingsStore.ALLOW ||
                (site.thirdPartyCookies == SiteSettingsStore.INHERIT && acceptThirdPartyCookies);
        CookieManager.getInstance().setAcceptThirdPartyCookies(target, allowThirdParty);
        applyDesktopMode(target);
        applyDarkMode(target);
        appliedSiteSettings.put(target, configKey);
    }

    private boolean shouldCleanTracking(String host) {
        SiteSettingsStore.SiteSettings site = siteSettingsStore == null ? null : siteSettingsStore.forHost(host);
        if (site != null && site.compatibilityMode) return false;
        return (site != null && site.trackingProtection == SiteSettingsStore.ALLOW) ||
                ((site == null || site.trackingProtection == SiteSettingsStore.INHERIT) && cleanTrackingParameters);
    }

    private void applyCookiePolicyToAll() {
        for (BrowserTab tab : tabs) {
            if (tab.liveView == null) continue;
            appliedSiteSettings.remove(tab.liveView);
            applySiteSettings(tab.liveView, pageHostFor(tab.liveView));
        }
        if (spareWebView != null) {
            appliedSiteSettings.remove(spareWebView);
            applySiteSettings(spareWebView, pageHostFor(spareWebView));
        }
        scheduleCookieFlush();
    }

    private void applyDarkMode() {
        for (BrowserTab tab : tabs) if (tab.liveView != null) applyDarkMode(tab.liveView);
        if (spareWebView != null) applyDarkMode(spareWebView);
        applyChromeTheme();
    }

    private void applyChromeTheme() {
        if (browserChrome == null || topBar == null || bottomBar == null || addressPill == null || addressBar == null) return;
        int background = nightMode ? Color.rgb(27, 29, 32) : WHITE;
        int surface = nightMode ? Color.rgb(43, 46, 51) : SURFACE;
        int foreground = nightMode ? Color.rgb(232, 234, 237) : TEXT;
        int hint = nightMode ? Color.rgb(154, 160, 166) : Color.rgb(128, 134, 139);
        if (rootFrame != null) rootFrame.setBackgroundColor(background);
        browserChrome.setBackgroundColor(background);
        topBar.setBackgroundColor(background);
        bottomBar.setBackgroundColor(background);
        addressPill.setBackground(roundRect(surface, 22));
        addressBar.setTextColor(foreground);
        addressBar.setHintTextColor(hint);
        tintIconTree(topBar, foreground);
        tintIconTree(bottomBar, foreground);
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);
            android.view.WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                int appearance = nightMode ? 0 : (android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS |
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
                controller.setSystemBarsAppearance(appearance,
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS |
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } else {
            window.setStatusBarColor(background);
            window.setNavigationBarColor(background);
        }
        if (Build.VERSION.SDK_INT < 30) {
            int flags = 0;
            if (!nightMode) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            window.getDecorView().setSystemUiVisibility(flags);
        }
    }

    private void tintIconTree(View view, int color) {
        if (view instanceof BrowserIconView) ((BrowserIconView) view).setTintColor(color);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) tintIconTree(group.getChildAt(i), color);
        }
    }

    private void applyDarkMode(WebView target) {
        if (target == null) return;
        String host = pageHostFor(target);
        SiteSettingsStore.SiteSettings site = siteSettingsStore == null ? new SiteSettingsStore.SiteSettings() : siteSettingsStore.forHost(host);
        boolean enabled = site.dark == SiteSettingsStore.ALLOW || (site.dark == SiteSettingsStore.INHERIT && nightMode);
        if (isMediaCompatibilityHost(host)) enabled = false;
        target.setBackgroundColor(enabled ? Color.rgb(17, 19, 21) : WHITE);
        if (Build.VERSION.SDK_INT >= 33) {
            target.getSettings().setAlgorithmicDarkeningAllowed(enabled);
        } else if (Build.VERSION.SDK_INT >= 29) {
            target.getSettings().setForceDark(enabled ? WebSettings.FORCE_DARK_ON : WebSettings.FORCE_DARK_OFF);
        }
    }

    private boolean isMediaCompatibilityHost(String host) {
        if (host == null) return false;
        String value = host.toLowerCase(Locale.US);
        return value.equals("youtube.com") || value.endsWith(".youtube.com") || value.equals("youtu.be") ||
                value.equals("youtube-nocookie.com") || value.endsWith(".youtube-nocookie.com");
    }

    /** Extreme-mode-only foreground Wi-Fi policy; standard and power-save never hold this lock. */
    private void updateMediaNetworkBoost() {
        boolean mediaPage = isMediaCompatibilityHost(currentPageHost);
        boolean performancePage = MODE_PERFORMANCE.equals(performanceMode) && isNetworkPage(currentPageUrl);
        boolean shouldBoost = activityResumed && MODE_PERFORMANCE.equals(performanceMode) && webView != null &&
                (mediaPage || performancePage);
        if (!shouldBoost) {
            releaseMediaNetworkBoost();
            return;
        }
        try {
            if (mediaWifiLock == null) {
                Context app = getApplicationContext();
                WifiManager manager = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
                if (manager == null) return;
                // Android 14+ officially maps HIGH_PERF to LOW_LATENCY. Request the
                // replacement explicitly; both modes keep the foreground Wi-Fi path
                // responsive and avoid the old no-lock regression on modern devices.
                int lockMode = Build.VERSION.SDK_INT >= 34
                        ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                        : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                mediaWifiLock = manager.createWifiLock(
                        lockMode, "Median:ForegroundNetwork");
                mediaWifiLock.setReferenceCounted(false);
            }
            if (!mediaWifiLock.isHeld()) mediaWifiLock.acquire();
        } catch (SecurityException ignored) {
        } catch (RuntimeException ignored) {
        }
    }

    private void releaseMediaNetworkBoost() {
        if (mediaWifiLock == null) return;
        try { if (mediaWifiLock.isHeld()) mediaWifiLock.release(); }
        catch (RuntimeException ignored) {}
    }

    private boolean isNetworkPage(String url) {
        return url != null && !isHomeUrl(url) &&
                (url.startsWith("https://") || url.startsWith("http://"));
    }

    private boolean validSearchTemplate(String value) {
        if (value == null || !value.contains("%s") || value.length() >= 2048) return false;
        try {
            URL parsed = NetworkSecurity.parseHttpsUrl(value.replace("%s", "median-query"));
            return parsed.getRef() == null;
        } catch (Exception ignored) { return false; }
    }

    private static boolean sameSecureOrigin(String origin, String pageUrl) {
        try {
            URL expected = NetworkSecurity.parseHttpsUrl(origin);
            URL current = NetworkSecurity.parseHttpsUrl(pageUrl);
            return NetworkSecurity.sameOrigin(expected, current);
        } catch (Exception ignored) { return false; }
    }

    private boolean shouldUpgradeHttp(String url) {
        if (!httpsOnly || url == null || !url.startsWith("http://")) return false;
        String host = hostOf(url);
        SiteSettingsStore.SiteSettings site = siteSettingsStore == null ? null : siteSettingsStore.forHost(host);
        if (site != null && site.compatibilityMode) return false;
        if (host.length() == 0 || "localhost".equals(host) || host.endsWith(".local") || host.endsWith(".onion")) return false;
        return !host.matches("^(10\\.|127\\.|169\\.254\\.|192\\.168\\.|172\\.(1[6-9]|2[0-9]|3[01])\\.).*");
    }

    private void confirmExternalNavigation(final String url) {
        String scheme;
        try { scheme = Uri.parse(url).getScheme(); } catch (Exception e) { scheme = "外部应用"; }
        new AlertDialog.Builder(this).setTitle("打开外部应用？")
                .setMessage("网页请求打开 “" + (scheme == null ? "未知" : scheme) + "” 链接。仅在你信任当前网站时继续。")
                .setPositiveButton("继续", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { startExternalNavigation(url); }
                }).setNegativeButton("取消", null).show();
    }

    private void startExternalNavigation(String url) {
        try {
            Intent external;
            if (url.startsWith("intent:")) {
                Intent parsed = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                if (parsed.getData() == null) throw new IllegalArgumentException("外部地址无效");
                external = new Intent(Intent.ACTION_VIEW, parsed.getData());
            } else external = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            external.addCategory(Intent.CATEGORY_BROWSABLE);
            external.setComponent(null);
            external.setPackage(null);
            external.setSelector(null);
            int unsafeGrants = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;
            external.setFlags(external.getFlags() & ~unsafeGrants);
            external.setClipData(null);
            startActivity(external);
        } catch (Exception e) { toast("无法打开此链接"); }
    }

    private void handleWebPermissionRequest(final WebView source, final PermissionRequest request) {
        if (request == null || source == null || source != webView || request.getOrigin() == null ||
                !sameSecureOrigin(request.getOrigin().toString(), source.getUrl())) {
            if (request != null) request.deny();
            return;
        }
        if (pendingPermissionRequest != null) pendingPermissionRequest.deny();
        final ArrayList<String> webResources = new ArrayList<String>();
        final ArrayList<String> androidPermissions = new ArrayList<String>();
        StringBuilder names = new StringBuilder();
        boolean autoGrant = true;
        String originHost = request.getOrigin().getHost();
        if (originHost == null || originHost.length() == 0) { request.deny(); return; }
        final SiteSettingsStore.SiteSettings site = siteSettingsStore.forHost(originHost);
        for (String resource : request.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
                if (site.camera == SiteSettingsStore.BLOCK) continue;
                if (site.camera != SiteSettingsStore.ALLOW) autoGrant = false;
                webResources.add(resource);
                if (!androidPermissions.contains(Manifest.permission.CAMERA)) androidPermissions.add(Manifest.permission.CAMERA);
                if (names.length() > 0) names.append("、");
                names.append("摄像头");
            } else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                if (site.microphone == SiteSettingsStore.BLOCK) continue;
                if (site.microphone != SiteSettingsStore.ALLOW) autoGrant = false;
                webResources.add(resource);
                if (!androidPermissions.contains(Manifest.permission.RECORD_AUDIO)) androidPermissions.add(Manifest.permission.RECORD_AUDIO);
                if (names.length() > 0) names.append("、");
                names.append("麦克风");
            }
        }
        if (webResources.size() == 0) { request.deny(); return; }
        final String expectedOrigin = request.getOrigin().toString();
        if (autoGrant) {
            grantWebPermission(source, expectedOrigin, request, webResources, androidPermissions);
            return;
        }
        new AlertDialog.Builder(this).setTitle("允许网站使用" + names + "？")
                .setMessage((originHost == null ? "当前网站" : originHost) + "\n\n只允许你正在使用并信任的网站。此次授权会在页面关闭后失效。")
                .setPositiveButton("仅此次允许", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        grantWebPermission(source, expectedOrigin, request, webResources, androidPermissions);
                    }
                }).setNegativeButton("阻止", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { request.deny(); }
                }).setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override public void onCancel(DialogInterface dialog) { request.deny(); }
                }).show();
    }

    private void grantWebPermission(WebView source, String origin, PermissionRequest request,
                                    List<String> webResources, List<String> androidPermissions) {
        if (origin == null || source != webView || !sameSecureOrigin(origin, source.getUrl())) { request.deny(); return; }
        ArrayList<String> missing = new ArrayList<String>();
        for (String permission : androidPermissions) if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) missing.add(permission);
        if (missing.size() == 0) request.grant(webResources.toArray(new String[webResources.size()]));
        else {
            pendingPermissionRequest = request;
            pendingPermissionView = source;
            pendingPermissionOrigin = origin;
            pendingWebPermissionResources = webResources.toArray(new String[webResources.size()]);
            requestPermissions(missing.toArray(new String[missing.size()]), WEB_PERMISSION_REQUEST);
        }
    }

    private void handleGeolocationRequest(final WebView source, final String origin, final GeolocationPermissions.Callback callback) {
        if (callback == null) return;
        final String host = hostOf(origin);
        if (source == null || source != webView || !sameSecureOrigin(origin, source.getUrl())) {
            callback.invoke(origin, false, false);
            return;
        }
        SiteSettingsStore.SiteSettings site = siteSettingsStore.forHost(host);
        if (site.location == SiteSettingsStore.BLOCK) { callback.invoke(origin, false, false); return; }
        if (site.location == SiteSettingsStore.ALLOW) {
            grantGeolocation(source, host, origin, callback);
            return;
        }
        new AlertDialog.Builder(this).setTitle("允许网站获取位置？")
                .setMessage((host.length() == 0 ? "当前网站" : host) + "\n\nMedian 不会保留此授权；网站仍可能保存你提交的位置。")
                .setPositiveButton("仅此次允许", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        grantGeolocation(source, host, origin, callback);
                    }
                }).setNegativeButton("阻止", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { callback.invoke(origin, false, false); }
                }).setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override public void onCancel(DialogInterface dialog) { callback.invoke(origin, false, false); }
                }).show();
    }

    private void grantGeolocation(WebView source, String host, String origin, GeolocationPermissions.Callback callback) {
        if (source != webView || !sameSecureOrigin(origin, source.getUrl())) { callback.invoke(origin, false, false); return; }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            callback.invoke(origin, true, false);
        } else {
            pendingGeolocationCallback = callback;
            pendingGeolocationOrigin = origin;
            pendingGeolocationView = source;
            requestPermissions(new String[] { Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION },
                    GEOLOCATION_PERMISSION_REQUEST);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults.length > 0;
        for (int result : grantResults) if (result != PackageManager.PERMISSION_GRANTED) granted = false;
        if (requestCode == 410) {
            bridgeDiagLog("ACCESS_LOCAL_NETWORK result granted=" + granted + " perms=" + java.util.Arrays.toString(permissions));
        }
        if (requestCode == WEB_PERMISSION_REQUEST && pendingPermissionRequest != null) {
            if (granted && pendingWebPermissionResources != null && pendingPermissionView == webView &&
                    pendingPermissionOrigin != null && sameSecureOrigin(pendingPermissionOrigin, webView.getUrl()))
                pendingPermissionRequest.grant(pendingWebPermissionResources);
            else pendingPermissionRequest.deny();
            pendingPermissionRequest = null;
            pendingWebPermissionResources = null;
            pendingPermissionView = null;
            pendingPermissionOrigin = null;
        } else if (requestCode == GEOLOCATION_PERMISSION_REQUEST && pendingGeolocationCallback != null) {
            boolean locationGranted = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            pendingGeolocationCallback.invoke(pendingGeolocationOrigin,
                    locationGranted && pendingGeolocationView == webView &&
                            sameSecureOrigin(pendingGeolocationOrigin, webView.getUrl()), false);
            pendingGeolocationCallback = null;
            pendingGeolocationOrigin = null;
            pendingGeolocationView = null;
        }
    }

    private void updateChrome() {
        requestChromeUpdate();
    }

    private void requestChromeUpdate() {
        if (chromeUpdatePending || webView == null) return;
        chromeUpdatePending = true;
        Runnable update = new Runnable() {
            @Override public void run() {
                chromeUpdatePending = false;
                performChromeUpdate();
            }
        };
        rootFrame.postOnAnimation(update);
    }

    private void performChromeUpdate() {
        if (webView == null) return;
        updateAddressBar();
        boolean canBack = webView.canGoBack();
        boolean canForward = webView.canGoForward();
        if (renderedBackEnabled == null || renderedBackEnabled.booleanValue() != canBack) {
            renderedBackEnabled = Boolean.valueOf(canBack);
            backButton.setEnabled(canBack);
            backButton.setAlpha(canBack ? 1f : .32f);
        }
        if (renderedForwardEnabled == null || renderedForwardEnabled.booleanValue() != canForward) {
            renderedForwardEnabled = Boolean.valueOf(canForward);
            forwardButton.setEnabled(canForward);
            forwardButton.setAlpha(canForward ? 1f : .32f);
        }
        int tabCount = tabs.size();
        if (renderedTabCount == null || renderedTabCount.intValue() != tabCount) {
            renderedTabCount = Integer.valueOf(tabCount);
            tabButton.setCount(tabCount);
        }
        boolean shieldActive = isAdBlockActiveForHost(currentPageHost) &&
                !(MODE_PERFORMANCE.equals(performanceMode) && performanceNetworkDirect);
        if (renderedShieldActive == null || renderedShieldActive.booleanValue() != shieldActive) {
            renderedShieldActive = Boolean.valueOf(shieldActive);
            shieldButton.setActive(shieldActive);
        }
    }

    private void updateAddressBar() {
        if (addressBar == null || addressBar.hasFocus()) return;
        String url = currentPageUrl;
        String display = "";
        if (url != null && !isHomeUrl(url) && !"about:blank".equals(url)) {
            String host = currentPageHost;
            if (host != null && host.length() > 0) {
                Uri uri = currentUri();
                String scheme = uri == null ? "" : uri.getScheme();
                display = (scheme == null || scheme.length() == 0 ? "" : scheme.toLowerCase(Locale.US) + "://") + host;
            } else {
                display = url;
            }
        }
        if (!display.equals(renderedAddress) || !display.contentEquals(addressBar.getText())) {
            renderedAddress = display;
            addressBar.setText(display);
        }
    }

    private void scheduleProgressUpdate(int progress) {
        pendingProgress = progress;
        if (progressUpdatePending || webView == null) return;
        progressUpdatePending = true;
        Runnable update = new Runnable() {
            @Override public void run() {
                progressUpdatePending = false;
                int value = pendingProgress;
                if (value >= 100) {
                    if (renderedProgress != 100) Motion.animateProgress(progressBar, renderedProgress, 100, reduceMotion());
                    renderedProgress = 100;
                    if (progressBar.getVisibility() != View.GONE) {
                        progressBar.animate().cancel();
                        progressBar.animate().alpha(0f).setDuration(reduceMotion() ? 70L : 130L).withEndAction(new Runnable() {
                            @Override public void run() { progressBar.setVisibility(View.GONE); progressBar.setAlpha(1f); }
                        }).start();
                    }
                    return;
                }
                if (renderedProgress >= 0 && renderedProgress < 100 && Math.abs(value - renderedProgress) < progressStep()) return;
                int previous = renderedProgress;
                renderedProgress = value;
                if (progressBar.getVisibility() != View.VISIBLE) {
                    progressBar.animate().cancel();
                    progressBar.setAlpha(0f);
                    progressBar.setVisibility(View.VISIBLE);
                    progressBar.animate().alpha(1f).setDuration(100L).start();
                }
                Motion.animateProgress(progressBar, previous, value, reduceMotion());
            }
        };
        rootFrame.postOnAnimation(update);
    }

    private Uri currentUri() {
        try {
            String url = currentPageUrl;
            return url == null ? null : Uri.parse(url);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String currentHost() {
        return currentPageHost == null ? "" : currentPageHost;
    }

    private String hostOf(String url) {
        try {
            if (url == null) return "";
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if ("content".equalsIgnoreCase(uri.getScheme()) && OfflineContentProvider.AUTHORITY.equals(uri.getAuthority())) return "";
            return host == null || "median.invalid".equalsIgnoreCase(host) ? "" : host.toLowerCase(Locale.US);
        } catch (RuntimeException e) {
            return "";
        }
    }

    private boolean isHomeUrl(String url) {
        return UrlCleaner.isInternalPage(url, "median.invalid");
    }

    private GradientDrawable roundRect(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private int selectableBorderless() {
        TypedValue out = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, out, true);
        return out.resourceId;
    }

    private int selectableBounded() {
        TypedValue out = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, out, true);
        return out.resourceId;
    }

    private void hideKeyboard() {
        InputMethodManager manager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null) manager.hideSoftInputFromWindow(addressBar.getWindowToken(), 0);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private String safeMessage(Exception e) {
        if (e == null) return "未知错误";
        String message = e.getMessage();
        return message == null || message.length() == 0 ? e.getClass().getSimpleName() : message;
    }

    private void writeBackup(final Uri uri) {
        if (uri == null || scriptExecutor == null || scriptExecutor.isShutdown()) return;
        final String json = dataStore.exportJson();
        scriptExecutor.execute(new Runnable() {
            @Override public void run() {
                OutputStream output = null;
                Exception failure = null;
                try {
                    output = getContentResolver().openOutputStream(uri, "w");
                    if (output == null) throw new IllegalStateException("无法打开目标文件");
                    output.write(json.getBytes("UTF-8"));
                    output.flush();
                } catch (Exception e) { failure = e; }
                finally { if (output != null) try { output.close(); } catch (Exception ignored) {} }
                final Exception error = failure;
                uiHandler.post(new Runnable() { @Override public void run() { toast(error == null ? "书签备份已导出" : "导出失败：" + safeMessage(error)); } });
            }
        });
    }

    private void readBackup(final Uri uri) {
        if (uri == null || scriptExecutor == null || scriptExecutor.isShutdown()) return;
        toast("正在检查备份…");
        scriptExecutor.execute(new Runnable() {
            @Override public void run() {
                InputStream input = null;
                int imported = 0;
                Exception failure = null;
                try {
                    input = getContentResolver().openInputStream(uri);
                    if (input == null) throw new IllegalStateException("无法打开备份文件");
                    ByteArrayOutputStream output = new ByteArrayOutputStream(32 * 1024);
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        if (output.size() + read > 4 * 1024 * 1024) throw new IllegalArgumentException("备份超过 4 MB");
                        output.write(buffer, 0, read);
                    }
                    imported = dataStore.importJson(output.toString("UTF-8"));
                } catch (Exception e) { failure = e; }
                finally { if (input != null) try { input.close(); } catch (Exception ignored) {} }
                final int count = imported;
                final Exception error = failure;
                uiHandler.post(new Runnable() { @Override public void run() { toast(error == null ? "已导入 " + count + " 个新书签" : "导入失败：" + safeMessage(error)); } });
            }
        });
    }

    private void showFullBackupPassword(final Uri uri, final boolean exporting) {
        if (uri == null) return;
        final EditText password = new EditText(this);
        password.setHint(exporting ? "设置备份口令（至少 10 个字符）" : "输入备份口令");
        password.setSingleLine(true);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final EditText confirmation = new EditText(this);
        confirmation.setHint("再次输入备份口令");
        confirmation.setSingleLine(true);
        confirmation.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(18), 0, dp(18), 0);
        fields.addView(password);
        if (exporting) fields.addView(confirmation);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(exporting ? "加密完整备份" : "解密完整备份")
                .setMessage(exporting ? "包含书签、历史、标签、设置、脚本、过滤订阅和密码。离线页面及下载文件不包含在内。忘记口令将无法恢复。" :
                        "恢复会替换当前浏览数据、脚本、设置和密码。AES-GCM 校验失败时不会应用备份。")
                .setView(fields)
                .setPositiveButton(exporting ? "加密导出" : "解密恢复", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        String first = password.getText().toString();
                        if (first.length() < 10 || first.length() > 256) { toast("备份口令需为 10–256 个字符"); return; }
                        if (exporting && !first.equals(confirmation.getText().toString())) { toast("两次输入的口令不一致"); return; }
                        if (exporting) createFullBackup(uri, first.toCharArray());
                        else restoreFullBackup(uri, first.toCharArray());
                    }
                }).setNegativeButton("取消", null).create();
        secureDialog(dialog);
        dialog.show();
    }

    private void createFullBackup(final Uri uri, final char[] password) {
        toast("正在加密完整备份…");
        services.passwords().exportJson(new PasswordVault.Callback<String>() {
            @Override public void onComplete(final String vaultJson, Exception error) {
                if (error != null) {
                    java.util.Arrays.fill(password, '\0');
                    toast("读取密码库失败：" + safeMessage(error));
                    return;
                }
                scriptExecutor.execute(new Runnable() {
                    @Override public void run() {
                        OutputStream output = null;
                        Exception failure = null;
                        try {
                            JSONObject root = new JSONObject();
                            root.put("format", "median-portable-state");
                            root.put("version", 1);
                            root.put("createdAt", System.currentTimeMillis());
                            root.put("browser", dataStore.exportPortable());
                            root.put("sites", new JSONObject(siteSettingsStore.exportJson()));
                            root.put("scripts", new JSONArray(scriptStore.exportJson()));
                            root.put("filters", new JSONArray(services.filters().exportJson()));
                            root.put("customFilters", prefs.getString("custom_filter_rules", ""));
                            root.put("settings", settingsSnapshot());
                            root.put("passwords", new JSONArray(vaultJson == null ? "[]" : vaultJson));
                            byte[] encoded = PortableBackupCodec.encrypt(root, password);
                            output = getContentResolver().openOutputStream(uri, "w");
                            if (output == null) throw new IllegalStateException("无法打开目标文件");
                            output.write(encoded);
                            output.flush();
                        } catch (Exception e) { failure = e; }
                        finally {
                            java.util.Arrays.fill(password, '\0');
                            if (output != null) try { output.close(); } catch (Exception ignored) {}
                        }
                        final Exception result = failure;
                        uiHandler.post(new Runnable() {
                            @Override public void run() { toast(result == null ? "加密完整备份已导出" : "完整备份失败：" + safeMessage(result)); }
                        });
                    }
                });
            }
        });
    }

    @Override public JSONObject dsppDiagnostics() {
        JSONObject out = new JSONObject();
        try {
            out.put("enabled", DeepSeekPP.isEnabled(this));
            JSONArray bridge = new JSONArray();
            synchronized (bridgeDiag) {
                for (String line : bridgeDiag) bridge.put(line);
            }
            out.put("bridgeDiag", bridge);
            out.put("executorAlive", scriptNetworkExecutor != null && !scriptNetworkExecutor.isShutdown());
            out.put("bridgeTokens", scriptBridgeTokens.size());
            Map<String, String> am = buildDsppAssetMap();
            JSONObject assetLens = new JSONObject();
            for (Map.Entry<String, String> e : am.entrySet()) {
                assetLens.put(e.getKey(), e.getValue() == null ? 0 : e.getValue().length());
            }
            out.put("assetLens", assetLens);
            JSONArray scripts = new JSONArray();
            if (scriptStore != null) {
                for (UserScriptStore.Script s : scriptStore.getAll()) {
                    JSONObject j = new JSONObject();
                    j.put("id", s.id);
                    j.put("name", s.name);
                    j.put("sourceUrl", s.sourceUrl);
                    j.put("codeLen", s.code == null ? 0 : s.code.length());
                    j.put("enabled", s.enabled);
                    j.put("quarantined", s.quarantined);
                    scripts.put(j);
                }
            }
            out.put("scripts", scripts);
        } catch (Exception e) {
            try { out.put("error", String.valueOf(e)); } catch (Exception ignored) {}
        }
        return out;
    }
    @Override public JSONObject settingsSnapshot() {
        try {
        JSONObject value = new JSONObject();
        value.put("adBlock", adBlockEnabled);
        value.put("desktop", desktopMode);
        value.put("night", nightMode);
        value.put("httpsOnly", httpsOnly);
        value.put("restoreTabs", restoreTabs);
        value.put("homeOpenMode", homeOpenMode());
        value.put("homeCustomUrl", normalizeConfiguredHomeUrl(prefs.getString("home_custom_url", "")));
        value.put("thirdPartyCookies", acceptThirdPartyCookies);
        value.put("searchEngine", searchEngine);
        value.put("customSearch", customSearchTemplate);
        value.put("performanceMode", performanceMode);
        value.put("networkDirect", performanceNetworkDirect);
        value.put("autoPip", autoPictureInPicture);
        value.put("cleanTracking", cleanTrackingParameters);
        value.put("mcpBackgroundKeepAlive", prefs.getBoolean("mcp_background_keepalive", true));
        value.put("siteExceptions", new JSONArray(siteExceptions));
        HomePageConfig home = homePageConfig();
        value.put("homeTitle", home.title);
        value.put("homeSubtitle", home.subtitle);
        value.put("homeLogoStyle", home.logoStyle);
        value.put("homeLogoCode", home.logoCode);
        value.put("homeLogoMode", home.logoMode);
        value.put("homeLogoLetterSpacing", home.logoLetterSpacing);
        value.put("homeLogoGradientAngle", home.logoGradientAngle);
        value.put("homeLogoFontSize", home.logoFontSize);
        value.put("homeLogoFontWeight", home.logoFontWeight);
        value.put("homeLogoImageWidth", home.logoImageWidth);
        value.put("homeLogoImageHeight", home.logoImageHeight);
        value.put("homeLogoImageRadius", home.logoImageRadius);
        value.put("homeAccent", home.accent);
        value.put("homeWallpaperDim", home.wallpaperDim);
        value.put("homeWallpaperBlur", home.wallpaperBlur);
        value.put("homeWallpaperFit", home.wallpaperFit);
        value.put("homeSearchStyle", home.searchStyle);
        value.put("homeLayout", home.layout);
        value.put("homeTileShape", home.tileShape);
        value.put("homeShortcutColumns", home.shortcutColumns);
        value.put("homeShowSearch", home.showSearch);
        value.put("homeShowEngines", home.showEngines);
        value.put("homeShowShortcuts", home.showShortcuts);
        value.put("homeShowCorner", home.showCornerBrand);
        value.put("homeShowClock", home.showClock);
        value.put("homeCustomCss", home.customCss);
        value.put("homeCustomHtmlEnabled", home.customHtmlEnabled);
        value.put("homeCustomHtml", CustomHomeHtml.clean(prefs.getString("home_custom_html", "")));
        value.put("homeCustomHtmlVersion", home.customHtmlVersion);
        return value;
        } catch (Exception e) {
            return null;
        }
    }

    private void restoreFullBackup(final Uri uri, final char[] password) {
        if (scriptExecutor == null || scriptExecutor.isShutdown()) return;
        toast("正在验证并恢复完整备份…");
        scriptExecutor.execute(new Runnable() {
            @Override public void run() {
                InputStream input = null;
                Exception failure = null;
                String passwords = null;
                int bookmarks = 0;
                try {
                    input = getContentResolver().openInputStream(uri);
                    if (input == null) throw new IllegalStateException("无法打开备份文件");
                    byte[] encoded = readBounded(input, 20 * 1024 * 1024, "备份超过 20 MB");
                    input = null;
                    JSONObject root = PortableBackupCodec.decrypt(encoded, password);
                    if (!"median-portable-state".equals(root.optString("format")) || root.optInt("version", 0) != 1)
                        throw new IllegalArgumentException("备份内容版本不受支持");
                    JSONObject browser = root.getJSONObject("browser");
                    JSONObject sites = root.getJSONObject("sites");
                    JSONArray scripts = root.getJSONArray("scripts");
                    JSONArray filters = root.getJSONArray("filters");
                    JSONArray vault = root.getJSONArray("passwords");
                    JSONObject settings = root.getJSONObject("settings");
                    if (scripts.length() > 128 || filters.length() > 32 || vault.length() > 500)
                        throw new IllegalArgumentException("备份条目超过安全限制");
                    bookmarks = dataStore.importPortable(browser);
                    siteSettingsStore.importJson(sites.toString());
                    scriptStore.importJson(scripts.toString());
                    services.filters().importJson(filters.toString());
                    String custom = root.optString("customFilters", "");
                    if (custom.length() > 256 * 1024) throw new IllegalArgumentException("自定义规则超过限制");
                    prefs.edit().putString("custom_filter_rules", custom).commit();
                    applySettingsSnapshot(settings);
                    passwords = vault.toString();
                } catch (Exception e) { failure = e; }
                finally {
                    java.util.Arrays.fill(password, '\0');
                    if (input != null) try { input.close(); } catch (Exception ignored) {}
                }
                final Exception error = failure;
                final String vaultJson = passwords;
                final int bookmarkCount = bookmarks;
                uiHandler.post(new Runnable() {
                    @Override public void run() {
                        if (error != null) { toast("恢复失败：" + safeMessage(error)); return; }
                        services.passwords().importJson(vaultJson, new PasswordVault.Callback<Integer>() {
                            @Override public void onComplete(Integer count, Exception vaultError) {
                                if (vaultError != null) { toast("其他数据已恢复，但密码恢复失败：" + safeMessage(vaultError)); return; }
                                finishFullRestore(bookmarkCount, count == null ? 0 : count.intValue());
                            }
                        });
                    }
                });
            }
        });
    }

    private void applySettingsSnapshot(JSONObject value) throws Exception {
        String restoredMode = value.optString("performanceMode", MODE_STANDARD);
        if (!MODE_PERFORMANCE.equals(restoredMode) && !MODE_POWER_SAVE.equals(restoredMode)) restoredMode = MODE_STANDARD;
        String restoredSearch = value.optString("searchEngine", "google");
        if (!"google".equals(restoredSearch) && !"baidu".equals(restoredSearch) && !"bing".equals(restoredSearch) &&
                !"custom".equals(restoredSearch)) restoredSearch = "google";
        String restoredCustomSearch = value.optString("customSearch", "").trim();
        if (!validSearchTemplate(restoredCustomSearch)) restoredCustomSearch = "";
        if ("custom".equals(restoredSearch) && restoredCustomSearch.length() == 0) restoredSearch = "google";
        boolean legacyRestoreTabs = value.optBoolean("restoreTabs", true);
        String restoredOpenMode = HomeOpenPolicy.normalize(value.optString("homeOpenMode", ""), legacyRestoreTabs);
        String restoredHomeUrl = normalizeConfiguredHomeUrl(value.optString("homeCustomUrl", ""));
        if (HomeOpenPolicy.OPEN_CUSTOM_URL.equals(restoredOpenMode) && restoredHomeUrl.length() == 0)
            restoredOpenMode = HomeOpenPolicy.OPEN_HOME;
        String restoredCustomHtml = CustomHomeHtml.clean(value.optString("homeCustomHtml", ""));
        boolean restoredCustomHtmlEnabled = value.optBoolean("homeCustomHtmlEnabled", false) &&
                CustomHomeHtml.valid(restoredCustomHtml);
        long restoredCustomHtmlVersion = Math.max(0L, value.optLong("homeCustomHtmlVersion", 0L));
        String restoredCustomCss = CustomHomeCss.clean(value.optString("homeCustomCss", ""));
        if (!CustomHomeCss.valid(restoredCustomCss)) restoredCustomCss = "";
        String restoredLogoMode = value.optString("homeLogoMode", "text");
        if (!"text".equals(restoredLogoMode) && !"image".equals(restoredLogoMode) && !"none".equals(restoredLogoMode))
            restoredLogoMode = "text";
        boolean restoredKeepLast = HomeOpenPolicy.KEEP_LAST.equals(restoredOpenMode);
        HashSet<String> exceptions = new HashSet<String>();
        JSONArray array = value.optJSONArray("siteExceptions");
        if (array != null) for (int i = 0; i < array.length() && exceptions.size() < 500; i++) {
            String host = array.optString(i, "").trim().toLowerCase(Locale.US);
            if (host.matches("[a-z0-9._-]{1,253}")) exceptions.add(host);
        }
        HomePageConfig restoredHome = HomePageConfig.createPersonalized(
                value.optString("homeTitle", HomePageConfig.DEFAULT_TITLE),
                value.optString("homeSubtitle", ""), value.optString("homeLogoStyle", "median"),
                value.optString("homeLogoCode", ""), value.optInt("homeLogoLetterSpacing", 0),
                value.optInt("homeLogoGradientAngle", 90), value.optString("homeAccent", "blue"),
                value.optInt("homeWallpaperDim", 28), value.optInt("homeWallpaperBlur", 0),
                value.optString("homeWallpaperFit", "cover"), value.optString("homeSearchStyle", "solid"),
                value.optString("homeLayout", "center"), value.optString("homeTileShape", "rounded"),
                value.optInt("homeShortcutColumns", 4), value.optBoolean("homeShowSearch", true),
                value.optBoolean("homeShowEngines", true), value.optBoolean("homeShowShortcuts", true),
                value.optBoolean("homeShowCorner", true), value.optBoolean("homeShowClock", false),
                restoredCustomHtmlEnabled, false, false, restoredCustomHtmlVersion, 0L, 0L,
                restoredLogoMode, value.optInt("homeLogoFontSize", 47),
                value.optInt("homeLogoFontWeight", 720), value.optInt("homeLogoImageWidth", 132),
                value.optInt("homeLogoImageHeight", 96), value.optInt("homeLogoImageRadius", 0),
                restoredCustomCss);
        SharedPreferences.Editor editor = prefs.edit()
                .putBoolean("adblock", value.optBoolean("adBlock", true))
                .putBoolean("desktop", value.optBoolean("desktop", false))
                .putBoolean("night_mode", value.optBoolean("night", false))
                .putBoolean("https_only", value.optBoolean("httpsOnly", true))
                .putBoolean("restore_tabs", restoredKeepLast)
                .putString("home_open_mode", restoredOpenMode)
                .putString("home_custom_url", restoredHomeUrl)
                .putBoolean("accept_third_party_cookies", value.optBoolean("thirdPartyCookies", false))
                .putString("search_engine", restoredSearch)
                .putString("custom_search_template", restoredCustomSearch)
                .putString("performance_mode", restoredMode)
                .putBoolean("performance_network_direct", value.optBoolean("networkDirect", false))
                .putBoolean("auto_picture_in_picture", value.optBoolean("autoPip", false))
                .putBoolean("clean_tracking_parameters", value.optBoolean("cleanTracking", true))
                .putStringSet("site_exceptions", exceptions)
                .putString("home_title", restoredHome.title)
                .putString("home_subtitle", restoredHome.subtitle)
                .putString("home_logo_style", restoredHome.logoStyle)
                .putString("home_logo_code", restoredHome.logoCode)
                .putString("home_logo_mode", restoredHome.logoMode)
                .putInt("home_logo_letter_spacing", restoredHome.logoLetterSpacing)
                .putInt("home_logo_gradient_angle", restoredHome.logoGradientAngle)
                .putInt("home_logo_font_size", restoredHome.logoFontSize)
                .putInt("home_logo_font_weight", restoredHome.logoFontWeight)
                .putInt("home_logo_image_width", restoredHome.logoImageWidth)
                .putInt("home_logo_image_height", restoredHome.logoImageHeight)
                .putInt("home_logo_image_radius", restoredHome.logoImageRadius)
                .putString("home_accent", restoredHome.accent)
                .putInt("home_wallpaper_dim", restoredHome.wallpaperDim)
                .putInt("home_wallpaper_blur", restoredHome.wallpaperBlur)
                .putString("home_wallpaper_fit", restoredHome.wallpaperFit)
                .putString("home_search_style", restoredHome.searchStyle)
                .putString("home_layout", restoredHome.layout)
                .putString("home_tile_shape", restoredHome.tileShape)
                .putInt("home_shortcut_columns", restoredHome.shortcutColumns)
                .putBoolean("home_show_search", restoredHome.showSearch)
                .putBoolean("home_show_engines", restoredHome.showEngines)
                .putBoolean("home_show_shortcuts", restoredHome.showShortcuts)
                .putBoolean("home_show_corner", restoredHome.showCornerBrand)
                .putBoolean("home_show_clock", restoredHome.showClock)
                .putString("home_custom_css", restoredHome.customCss)
                .putString("home_custom_html", restoredCustomHtml)
                .putBoolean("home_custom_html_enabled", restoredCustomHtmlEnabled)
                .putLong("home_custom_html_version", restoredCustomHtmlVersion);
        if (!editor.commit()) throw new IllegalStateException("无法保存恢复设置");
        adBlockEnabled = value.optBoolean("adBlock", true);
        desktopMode = value.optBoolean("desktop", false);
        nightMode = value.optBoolean("night", false);
        httpsOnly = value.optBoolean("httpsOnly", true);
        restoreTabs = restoredKeepLast;
        acceptThirdPartyCookies = value.optBoolean("thirdPartyCookies", false);
        searchEngine = restoredSearch;
        customSearchTemplate = restoredCustomSearch;
        performanceMode = restoredMode;
        performanceNetworkDirect = value.optBoolean("networkDirect", false);
        autoPictureInPicture = value.optBoolean("autoPip", false);
        cleanTrackingParameters = value.optBoolean("cleanTracking", true);
        siteExceptions = exceptions;
    }

    private void finishFullRestore(int bookmarks, int passwords) {
        appliedSiteSettings.clear();
        refreshUserScriptRegistrations(false);
        rebuildAdBlockRulesAsync(false);
        applyChromeTheme();
        applyPerformanceMode();
        applyRestoredTabs();
        updateFilterSubscriptions(false);
        toast("完整备份已恢复：" + bookmarks + " 个书签、" + passwords + " 个密码条目");
    }

    private void applyRestoredTabs() {
        webContainer.removeAllViews();
        if (spareWebView != null) { destroyWebView(spareWebView); spareWebView = null; }
        ArrayList<WebView> oldViews = new ArrayList<WebView>();
        for (BrowserTab tab : tabs) if (tab.liveView != null && !oldViews.contains(tab.liveView)) oldViews.add(tab.liveView);
        for (WebView old : oldViews) destroyWebView(old);
        tabs.clear();
        closedTabs.clear();
        if (restoreTabs) {
            List<BrowserDataStore.SessionTab> restored = dataStore.restoreSession();
            for (BrowserDataStore.SessionTab item : restored) {
                BrowserTab tab = new BrowserTab();
                tab.title = item.title;
                tab.url = item.url;
                tab.pinned = item.pinned;
                tabs.add(tab);
            }
        }
        if (tabs.size() == 0) {
            BrowserTab home = new BrowserTab();
            home.url = configuredHomeUrl();
            tabs.add(home);
        }
        currentTabIndex = restoreTabs ? Math.min(dataStore.restoredSessionIndex(), tabs.size() - 1) : 0;
        BrowserTab active = tabs.get(currentTabIndex);
        webView = createConfiguredWebView();
        active.liveView = webView;
        active.lastActiveAt = SystemClock.uptimeMillis();
        webContainer.addView(webView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        currentPageUrl = active.url;
        currentPageHost = hostOf(active.url);
        pageHosts.put(webView, currentPageHost);
        if (isHomeUrl(active.url)) showHome(); else webView.loadUrl(active.url);
        renderedTabCount = null;
        requestChromeUpdate();
        scheduleWebViewPrewarm();
    }

    private void persistSession() {
        if (dataStore == null) return;
        saveCurrentTab();
        ArrayList<BrowserDataStore.SessionTab> snapshot = new ArrayList<BrowserDataStore.SessionTab>();
        for (BrowserTab tab : tabs) snapshot.add(new BrowserDataStore.SessionTab(tab.title, tab.url, tab.pinned));
        dataStore.saveSession(snapshot, currentTabIndex);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (isExternalHttpIntent(intent)) loadInput(intent.getData().toString());
        else if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction())) toast("已拒绝非 HTTP(S) 外部地址");
    }

    private static boolean isExternalHttpIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction()) || intent.getData() == null) return false;
        try {
            NetworkSecurity.parseHttpUrl(intent.getData().toString());
            return true;
        } catch (Exception ignored) { return false; }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (webView != null) webView.saveState(outState);
        persistSession();
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST && fileChooserCallback != null) {
            Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            fileChooserCallback.onReceiveValue(result);
            fileChooserCallback = null;
        } else if (requestCode == BACKUP_EXPORT_REQUEST && resultCode == RESULT_OK && data != null) {
            writeBackup(data.getData());
        } else if (requestCode == BACKUP_IMPORT_REQUEST && resultCode == RESULT_OK && data != null) {
            readBackup(data.getData());
        } else if (requestCode == FULL_BACKUP_EXPORT_REQUEST && resultCode == RESULT_OK && data != null) {
            final Uri uri = data.getData();
            withVaultUnlock(new Runnable() { @Override public void run() { showFullBackupPassword(uri, true); } });
        } else if (requestCode == FULL_BACKUP_IMPORT_REQUEST && resultCode == RESULT_OK && data != null) {
            final Uri uri = data.getData();
            withVaultUnlock(new Runnable() { @Override public void run() { showFullBackupPassword(uri, false); } });
        } else if (requestCode == HOME_WALLPAPER_REQUEST) {
            if (resultCode == RESULT_OK && data != null) importHomeImage(data.getData(), HomeImageStore.Kind.WALLPAPER);
            else continueHomeCustomization();
        } else if (requestCode == HOME_LOGO_REQUEST) {
            if (resultCode == RESULT_OK && data != null) importHomeImage(data.getData(), HomeImageStore.Kind.LOGO);
            else continueHomeCustomization();
        } else if (requestCode == VAULT_UNLOCK_REQUEST) {
            Runnable action = pendingVaultAction;
            pendingVaultAction = null;
            if (resultCode == RESULT_OK) {
                vaultUnlockedUntil = SystemClock.elapsedRealtime() + 120_000L;
                if (action != null) action.run();
            } else toast("密码库保持锁定");
        }
    }

    @Override
    public void onBackPressed() {
        if (activeOverlay != null) {
            dismissOverlay();
        } else if (customView != null) {
            exitFullscreen();
        } else if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else if (tabs.size() > 1) {
            closeCurrentTab();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onStop() {
        persistSession();
        if (dataStore != null) dataStore.flush();
        if (prewarmPending) {
            uiHandler.removeCallbacks(webViewPrewarmer);
            prewarmPending = false;
        }
        if (deferredStartupPending) {
            uiHandler.removeCallbacks(deferredStartup);
            deferredStartupPending = false;
        }
        if (spareWebView != null) {
            destroyWebView(spareWebView);
            spareWebView = null;
        }
        vaultUnlockedUntil = 0L;
        if (services != null) services.trimMemory();
        maybeTrimTransientCache();
        super.onStop();
    }

    @Override
    protected void onPause() {
        boolean pictureInPicture = isInPictureInPictureMode();
        activityResumed = pictureInPicture;
        if (!pictureInPicture) releaseMediaNetworkBoost();
        updateAggressivePerformanceResources();
        try { Process.setThreadPriority(Process.myTid(), Process.THREAD_PRIORITY_DEFAULT); }
        catch (RuntimeException ignored) {}
        boolean mcpKeepAlive = prefs.getBoolean("mcp_background_keepalive", true) && McpController.get().hasUi();
        if (webView != null && !pictureInPicture && !mcpKeepAlive) {
            webView.onPause();
        }
        if (mcpKeepAlive) {
            acquireMcpKeepAliveLock();
            startKeepAliveService();
        } else {
            McpController.get().detach();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        McpController.get().attach(this);
        releaseMcpKeepAliveLock();
        stopKeepAliveService();
        activityResumed = true;
        applyUiThreadPriority();
        applyDisplayPolicy();
        updateAggressivePerformanceResources();
        if (webView != null) {
            webView.onResume();
        }
        scheduleWebViewPrewarm();
        scheduleDeferredStartupWork();
        updateMediaNetworkBoost();
    }

    @Override
    protected void onUserLeaveHint() {
        if (autoPictureInPicture && webView != null && isNetworkPage(currentPageUrl) &&
                !isInPictureInPictureMode()) enterPagePictureInPicture();
        super.onUserLeaveHint();
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPictureInPictureMode, android.content.res.Configuration newConfig) {
        super.onPictureInPictureModeChanged(inPictureInPictureMode, newConfig);
        if (topBar != null) topBar.setVisibility(inPictureInPictureMode ? View.GONE : View.VISIBLE);
        if (bottomBar != null) bottomBar.setVisibility(inPictureInPictureMode ? View.GONE : View.VISIBLE);
        if (progressBar != null && inPictureInPictureMode) progressBar.setVisibility(View.GONE);
        activityResumed = inPictureInPictureMode || hasWindowFocus();
        if (webView != null) {
            if (inPictureInPictureMode) webView.onResume();
            else requestChromeUpdate();
        }
        updateAggressivePerformanceResources();
        updateMediaNetworkBoost();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        boolean runningPressure = level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW &&
                level <= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL;
        boolean realMemoryPressure = runningPressure ||
                level >= android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE;
        if (realMemoryPressure) lastMemoryTrimAt = SystemClock.uptimeMillis();
        if (prewarmPending && (realMemoryPressure || level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)) {
            uiHandler.removeCallbacks(webViewPrewarmer);
            prewarmPending = false;
        }
        if (runningPressure || level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            preparedInjection = null;
            if (services != null) services.trimMemory();
        }

        boolean releaseTabs;
        if (MODE_POWER_SAVE.equals(performanceMode)) {
            releaseTabs = runningPressure || level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN;
        } else if (MODE_PERFORMANCE.equals(performanceMode)) {
            releaseTabs = level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                    level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE;
        } else {
            releaseTabs = level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                    level >= android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE;
        }
        if (releaseTabs) {
            releaseInactiveTabStates();
            if (level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                    level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE) clearColdTabBundles();
        }
    }

    @Override
    public void onLowMemory() {
        lastMemoryTrimAt = SystemClock.uptimeMillis();
        if (prewarmPending) {
            uiHandler.removeCallbacks(webViewPrewarmer);
            prewarmPending = false;
        }
        preparedInjection = null;
        releaseInactiveTabStates();
        clearColdTabBundles();
        super.onLowMemory();
    }

    private void releaseInactiveTabStates() {
        if (spareWebView != null) {
            destroyWebView(spareWebView);
            spareWebView = null;
        }
        ArrayList<BrowserTab> inactive = new ArrayList<BrowserTab>();
        for (int i = 0; i < tabs.size(); i++) if (i != currentTabIndex && tabs.get(i).liveView != null) inactive.add(tabs.get(i));
        for (BrowserTab tab : inactive) freezeTab(tab);
    }

    private void clearColdTabBundles() {
        for (int i = 0; i < tabs.size(); i++) if (i != currentTabIndex) tabs.get(i).state = null;
    }

    @Override
    protected void onDestroy() {
        McpController.get().stop();
        releaseMcpKeepAliveLock();
        stopKeepAliveService();
        activityResumed = false;
        updateAggressivePerformanceResources();
        aggressivePerformanceController.stop(this);
        releasePerformanceWakeLock();
        performanceWakeLock = null;
        releaseMediaNetworkBoost();
        mediaWifiLock = null;
        if (performanceSamplingActive && services != null) services.performance().stop(this);
        persistSession();
        if (pendingPermissionRequest != null) pendingPermissionRequest.deny();
        if (pendingGeolocationCallback != null) pendingGeolocationCallback.invoke(pendingGeolocationOrigin, false, false);
        dismissOverlay();
        uiHandler.removeCallbacksAndMessages(null);
        if (scriptExecutor != null) scriptExecutor.shutdownNow();
        if (scriptNetworkExecutor != null) scriptNetworkExecutor.shutdownNow();
        for (HttpURLConnection connection : scriptConnections.values()) try { connection.disconnect(); } catch (RuntimeException ignored) {}
        scriptConnections.clear();
        if (services != null) services.close();
        if (dataStore != null) dataStore.close();
        if (customView != null) exitFullscreen();
        if (spareWebView != null) {
            destroyWebView(spareWebView);
            spareWebView = null;
        }
        ArrayList<WebView> views = new ArrayList<WebView>();
        for (BrowserTab tab : tabs) if (tab.liveView != null && !views.contains(tab.liveView)) views.add(tab.liveView);
        for (WebView view : views) destroyWebView(view);
        for (BrowserTab tab : tabs) tab.liveView = null;
        webView = null;
        super.onDestroy();
    }
}
