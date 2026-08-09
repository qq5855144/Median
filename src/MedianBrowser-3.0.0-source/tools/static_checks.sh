#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

python3 tools/java_syntax_sanity.py
python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET
for p in Path('app/src/main').rglob('*.xml'):
    ET.parse(p)
print('Android XML parse passed')
PY

if rg -n 'addJavascriptInterface|MIXED_CONTENT_ALWAYS_ALLOW|setAllowFileAccess\(true\)|setAllowUniversalAccessFromFileURLs\(true\)|setInstanceFollowRedirects\(true\)' app/src/main; then
  echo 'Unsafe WebView or redirect surface found.' >&2
  exit 1
fi
if rg -n 'median-debug|STOREPASS:-android|versionName=.1\.3\.|Median Browser 1\.2' --glob '!CHANGELOG.md' --glob '!UPGRADE_NOTES.md' --glob '!tools/static_checks.sh' .; then
  echo 'Stale release/debug metadata found.' >&2
  exit 1
fi
rg -q "medianVersionCode = 73" app/build.gradle
rg -q "medianVersionName = '2.1.9'" app/build.gradle
rg -q "applicationId 'com.xinyv.median.compat'" app/build.gradle
rg -q 'targetSdk 36' app/build.gradle
if rg -n '^[[:space:]]*implementation[[:space:]]' app/build.gradle; then
  echo 'Unexpected production runtime dependency found; the focused WebKit slice must remain self-contained.' >&2
  exit 1
fi
rg -Fq 'WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)' app/src/main/java/com/xinyv/median/MainActivity.java
rg -Fq 'WebViewCompat.addDocumentStartJavaScript' app/src/main/java/com/xinyv/median/MainActivity.java
rg -q 'DOCUMENT_START_SCRIPT:1' app/src/main/java/androidx/webkit/WebViewFeature.java
rg -q 'org.chromium.support_lib_glue.SupportLibReflectionUtil' app/src/main/java/androidx/webkit/internal/WebViewGlueCommunicator.java
rg -q 'class WebViewCompat' app/src/main/java/androidx/webkit/WebViewCompat.java
rg -q 'class WebViewFeature' app/src/main/java/androidx/webkit/WebViewFeature.java
rg -q 'interface WebViewProviderFactoryBoundaryInterface' app/src/main/java/org/chromium/support_lib_boundary/WebViewProviderFactoryBoundaryInterface.java
rg -q 'android:allowBackup="false"' app/src/main/AndroidManifest.xml
rg -q 'android:exported="false"' app/src/main/AndroidManifest.xml
rg -q 'android.permission.REQUEST_INSTALL_PACKAGES' app/src/main/AndroidManifest.xml
rg -Fq 'android:authorities="${applicationId}.downloads"' app/src/main/AndroidManifest.xml
rg -q 'EXPECTED_SHA256=20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78' gradlew
rg -Fq 'BuildConfig.APPLICATION_ID + ".offline"' app/src/main/java/com/xinyv/median/OfflineContentProvider.java
rg -Fq 'android:taskAffinity="${applicationId}.private"' app/src/main/AndroidManifest.xml
rg -Fq 'NetworkSecurity.isCredentialHeader(name)' app/src/main/java/com/xinyv/median/MainActivity.java
rg -Fq 'NetworkSecurity.isCredentialHeader(name)' app/src/main/java/com/xinyv/median/AdaptiveDownloadService.java
rg -Fq 'parseContentRange(connection.getHeaderField("Content-Range"))' app/src/main/java/com/xinyv/median/AdaptiveDownloadService.java
rg -Fq 'sameSecureOrigin(pendingPermissionOrigin, webView.getUrl())' app/src/main/java/com/xinyv/median/MainActivity.java
rg -Fq "String(location.hostname||'').toLowerCase()==='median.invalid'" app/src/main/java/com/xinyv/median/UserScriptStore.java
rg -Fq 'return "performance".equals(mode) && !lowRam' app/src/main/java/com/xinyv/median/DeviceProfile.java
rg -Fq 'coldTabStateLimit(performanceMode)' app/src/main/java/com/xinyv/median/MainActivity.java
rg -Fq 'WebViewPolicy.applySecureDefaults(settings, WebSettings.LOAD_DEFAULT)' app/src/main/java/com/xinyv/median/MainActivity.java
rg -Fq 'WebViewPolicy.applySecureDefaults(settings, WebSettings.LOAD_NO_CACHE)' app/src/main/java/com/xinyv/median/PrivateActivity.java
rg -Fq 'final BaseAdapter tabAdapter' app/src/main/java/com/xinyv/median/MainActivity.java
rg -Fq 'if (activityResumed) ensurePrewarmedWebView()' app/src/main/java/com/xinyv/median/MainActivity.java
rg -Fq 'if (!changed) return' app/src/main/java/com/xinyv/median/BrowserDataStore.java
rg -Fq 'if (!dirty) return' app/src/main/java/com/xinyv/median/BrowserDataStore.java
rg -Fq 'bookmarkSnapshot = new ArrayList<Bookmark>(bookmarks)' app/src/main/java/com/xinyv/median/BrowserDataStore.java
rg -Fq 'sameNormalizedUrl(item.url, normalized)' app/src/main/java/com/xinyv/median/BrowserDataStore.java
rg -Fq 'io.postDelayed(writer, WRITE_RETRY_MS)' app/src/main/java/com/xinyv/median/BrowserDataStore.java
rg -Fq 'matchCache.put(url, stable)' app/src/main/java/com/xinyv/median/UserScriptStore.java
rg -Fq 'HashMap<String, JSONObject> cache' app/src/main/java/com/xinyv/median/ScriptValueStore.java
rg -Fq 'OmniboxInput.looksLikeWebAddress(text)' app/src/main/java/com/xinyv/median/MainActivity.java
rg -Fq 'OmniboxInput.looksLikeWebAddress(value)' app/src/main/java/com/xinyv/median/PrivateActivity.java
rg -Fq 'row.setBackgroundResource(selectableBounded())' app/src/main/java/com/xinyv/median/MainActivity.java
rg -Fq 'resolveAttribute(android.R.attr.selectableItemBackground, out, true)' app/src/main/java/com/xinyv/median/MainActivity.java
if rg -n 'row\.setBackgroundResource\(selectableBorderless\(\)\)' app/src/main/java/com/xinyv/median; then
  echo 'Full-width rows must use a bounded ripple.' >&2
  exit 1
fi
rg -Fq 'showHomeCustomization()' app/src/main/java/com/xinyv/median/MainActivity.java
rg -Fq 'interceptHomeAsset(source, requestUri)' app/src/main/java/com/xinyv/median/MainActivity.java
rg -Fq 'HomeOpenPolicy.restoresLast' app/src/main/java/com/xinyv/median/MainActivity.java
rg -Fq 'tab.url = configuredHomeUrl()' app/src/main/java/com/xinyv/median/MainActivity.java
rg -Fq '"/home-custom".equals(uri.getPath())' app/src/main/java/com/xinyv/median/MainActivity.java
rg -Fq "sandbox='allow-scripts allow-forms allow-popups allow-popups-to-escape-sandbox allow-top-navigation-by-user-activation'" app/src/main/java/com/xinyv/median/HomePage.java
rg -Fq "connect-src 'none'" app/src/main/java/com/xinyv/median/CustomHomeHtml.java
rg -Fq 'customHomeViews.contains(source) && !request.isForMainFrame()' app/src/main/java/com/xinyv/median/MainActivity.java
if rg -n 'allow-same-origin' app/src/main/java/com/xinyv/median/HomePage.java; then
  echo 'Custom homepage scripts must remain isolated from the trusted home origin.' >&2
  exit 1
fi
rg -Fq 'WALLPAPER_MAX_DIMENSION = 2048' app/src/main/java/com/xinyv/median/HomeImageStore.java
rg -Fq 'LogoMarkup.renderPreset(options.logoStyle' app/src/main/java/com/xinyv/median/HomePage.java
rg -Fq 'MAX_VISIBLE_CODE_POINTS = 48' app/src/main/java/com/xinyv/median/LogoMarkup.java
rg -Fq 'options.logoLetterSpacing' app/src/main/java/com/xinyv/median/HomePage.java
rg -Fq 'home_logo_gradient_angle' app/src/main/java/com/xinyv/median/MainActivity.java
rg -Fq "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src 'self' data:" app/src/main/java/com/xinyv/median/HomePage.java
rg -Fq 'CustomHomeCss.error(raw)' app/src/main/java/com/xinyv/median/MainActivity.java
rg -Fq "class='logo-space'" app/src/main/java/com/xinyv/median/LogoMarkup.java
if rg -n 'data:image/|Base64.*home.wallpaper|home.wallpaper.*Base64' app/src/main/java/com/xinyv/median; then
  echo 'Home images must be streamed locally instead of embedded into every HTML page.' >&2
  exit 1
fi
rg -Fq 'if (rule.thirdParty || rule.firstParty)' app/src/main/java/com/xinyv/median/AdBlockEngine.java
rg -Fq 'RULE_MATCH_SCRATCH' app/src/main/java/com/xinyv/median/AdBlockEngine.java
rg -Fq 'seen.add(selector)' app/src/main/java/com/xinyv/median/AdBlockEngine.java
if rg -n 'target\.contains\(selector\)' app/src/main/java/com/xinyv/median/AdBlockEngine.java; then
  echo 'Cosmetic selector de-duplication must remain hash-based.' >&2
  exit 1
fi
rg -Fq 'updateInFlight.compareAndSet(false, true)' app/src/main/java/com/xinyv/median/FilterSubscriptionStore.java
rg -Fq 'manualUpdatePending.set(true)' app/src/main/java/com/xinyv/median/FilterSubscriptionStore.java
rg -Fq 'collectUpdateTargets(runAutomatic)' app/src/main/java/com/xinyv/median/FilterSubscriptionStore.java
rg -Fq 'cosmeticInjected.putIfAbsent(source, Boolean.TRUE)' app/src/main/java/com/xinyv/median/MainActivity.java
rg -Fq 'services = new BrowserServices(this)' app/src/main/java/com/xinyv/median/MainActivity.java
rg -Fq 'DownloadFileTypes.correctCompletedApk' app/src/main/java/com/xinyv/median/AdaptiveDownloadService.java
rg -Fq 'DownloadFileTypes.mimeForOpen' app/src/main/java/com/xinyv/median/DownloadCenterActivity.java
rg -Fq 'class DownloadRetryPolicy' app/src/main/java/com/xinyv/median/DownloadRetryPolicy.java
rg -Fq 'findBlockingDuplicate(url, 15000L)' app/src/main/java/com/xinyv/median/MainActivity.java
rg -Fq 'CookieManager.getInstance().getCookie(current.toString())' app/src/main/java/com/xinyv/median/AdaptiveDownloadService.java
rg -Fq 'storeResponseCookies(current, connection)' app/src/main/java/com/xinyv/median/AdaptiveDownloadService.java
rg -Fq 'control.attach(connection)' app/src/main/java/com/xinyv/median/AdaptiveDownloadService.java
rg -Fq 'TASK_EXECUTOR.allowCoreThreadTimeOut(true)' app/src/main/java/com/xinyv/median/AdaptiveDownloadService.java
rg -Fq 'new LinkedBlockingQueue<Runnable>(48)' app/src/main/java/com/xinyv/median/AdaptiveDownloadService.java
rg -Fq 'return START_REDELIVER_INTENT' app/src/main/java/com/xinyv/median/AdaptiveDownloadService.java
rg -Fq 'private static final ArrayList<Item> items' app/src/main/java/com/xinyv/median/DownloadStore.java
rg -Fq 'TELEMETRY_WRITE_INTERVAL_MS = 5000L' app/src/main/java/com/xinyv/median/DownloadStore.java
rg -Fq 'ensurePrivateDataDirectory()' app/src/main/java/com/xinyv/median/PrivateActivity.java
if [ "$(rg -c 'loadLocked\(' app/src/main/java/com/xinyv/median/DownloadStore.java)" -ne 2 ]; then
  echo 'DownloadStore must not reparse the full JSON index on every operation.' >&2
  exit 1
fi
rg -Fq 'AdaptiveDownloadService.isTaskScheduled(item.id)' app/src/main/java/com/xinyv/median/DownloadCenterActivity.java
rg -Fq 'extends BaseAdapter' app/src/main/java/com/xinyv/median/DownloadCenterActivity.java
rg -Fq 'store.removeAll(ids)' app/src/main/java/com/xinyv/median/DownloadCenterActivity.java
rg -Fq 'DownloadCenterPolicy.canResume(status)' app/src/main/java/com/xinyv/median/DownloadCenterActivity.java
rg -Fq 'enqueueDownload(target, url, userAgent, contentDisposition, mimetype, contentLength)' app/src/main/java/com/xinyv/median/MainActivity.java
rg -Fq 'EXTRA_TOTAL_BYTES' app/src/main/java/com/xinyv/median/AdaptiveDownloadService.java
rg -Fq 'DownloadCenterPolicy.resolvedTotal(item.totalBytes, totalBytes)' app/src/main/java/com/xinyv/median/DownloadStore.java
rg -Fq 'DownloadCenterPolicy.progressPermille(current, total)' app/src/main/java/com/xinyv/median/DownloadCenterActivity.java
rg -Fq 'responseTotal(connection, cursor, contentRange)' app/src/main/java/com/xinyv/median/AdaptiveDownloadService.java
rg -Fq 'services.downloads().addAdaptive' app/src/main/java/com/xinyv/median/MainActivity.java
rg -Fq 'String downloadMode = DownloadMemoryPolicy.MODE_STANDARD' app/src/main/java/com/xinyv/median/MainActivity.java
rg -Fq 'DownloadState.create(task)' app/src/main/java/com/xinyv/median/AdaptiveDownloadService.java
rg -Fq 'connection = open(task, task.url, range' app/src/main/java/com/xinyv/median/AdaptiveDownloadService.java
if rg -n 'private Probe probe|bytes=0-0' app/src/main/java/com/xinyv/median/AdaptiveDownloadService.java; then
  echo 'Downloads must not spend a separate one-byte probe request before the real transfer.' >&2
  exit 1
fi
rg -Fq 'private static final int TASK_THREADS = Math.max(1, Math.min(2' app/src/main/java/com/xinyv/median/AdaptiveDownloadService.java
rg -Fq 'publishToAppDownloads' app/src/main/java/com/xinyv/median/AdaptiveDownloadService.java
rg -Fq 'DownloadContentProvider.uriFor' app/src/main/java/com/xinyv/median/AdaptiveDownloadService.java
if rg -n 'new DownloadManager\.Request|enqueueSystemDownload|addSystem\(' app/src/main/java/com/xinyv/median; then
  echo 'New downloads must not use Android DownloadManager.' >&2
  exit 1
fi
if rg -n 'videoplayback|getPlayerResponse|streamingData|isGoogleVideoUrl|youtubeQueryRange|networkObserved|YouTube 完整视频|YouTube 媒体轨|SABR|UMP' app/src/main/java/com/xinyv/median; then
  echo 'YouTube-specific sniffing/downloading code must remain removed in the classic build.' >&2
  exit 1
fi
if rg -n 'new (FilterSubscriptionStore|DownloadStore|OfflinePageStore|PageAssistant|PasswordVault|PerformanceMonitor)\(' app/src/main/java/com/xinyv/median/MainActivity.java; then
  echo 'Optional browser services must remain off the startup path.' >&2
  exit 1
fi
if rg -n 'new Thread\(' app/src/main/java/com/xinyv/median/MainActivity.java; then
  echo 'MainActivity background work must use bounded, lifecycle-owned executors.' >&2
  exit 1
fi
if rg -n 'STOREPASS.*android|KEYPASS.*android|median-debug\.p12|keytool.*-storepass android' --glob '!CHANGELOG.md' --glob '!UPGRADE_NOTES.md' --glob '!tools/static_checks.sh' .; then
  echo 'Weak or auto-generated release signing material found.' >&2
  exit 1
fi
bash -n build.sh tools/build_500kb_apk.sh tools/verify.sh tools/verify_release_tag.sh tools/tests/userscript_js_syntax_test.sh
sh -n gradlew

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
javac --release 17 -d "$TMP" \
  app/src/main/java/com/xinyv/median/NetworkSecurity.java \
  tools/tests/NetworkSecuritySelfTest.java
java -cp "$TMP" com.xinyv.median.NetworkSecuritySelfTest
javac --release 17 -d "$TMP" \
  app/src/main/java/com/xinyv/median/DownloadRetryPolicy.java \
  tools/tests/DownloadRetryPolicySelfTest.java
java -cp "$TMP" com.xinyv.median.DownloadRetryPolicySelfTest
javac --release 17 -d "$TMP" \
  app/src/main/java/com/xinyv/median/OmniboxInput.java \
  tools/tests/OmniboxInputSelfTest.java
java -cp "$TMP" com.xinyv.median.OmniboxInputSelfTest
javac --release 17 -d "$TMP" \
  app/src/main/java/com/xinyv/median/HomeOpenPolicy.java \
  tools/tests/HomeOpenPolicySelfTest.java
java -cp "$TMP" com.xinyv.median.HomeOpenPolicySelfTest
javac --release 17 -d "$TMP" \
  app/src/main/java/com/xinyv/median/CustomHomeCss.java \
  tools/tests/CustomHomeCssSelfTest.java
java -cp "$TMP" com.xinyv.median.CustomHomeCssSelfTest
javac --release 17 -d "$TMP" \
  app/src/main/java/com/xinyv/median/CustomHomeHtml.java \
  tools/tests/CustomHomeHtmlSelfTest.java
java -cp "$TMP" com.xinyv.median.CustomHomeHtmlSelfTest
javac --release 17 -d "$TMP" \
  app/src/main/java/com/xinyv/median/LogoMarkup.java \
  app/src/main/java/com/xinyv/median/CustomHomeCss.java \
  app/src/main/java/com/xinyv/median/HomePageConfig.java \
  tools/tests/HomePageConfigSelfTest.java \
  tools/tests/LogoMarkupSelfTest.java
java -cp "$TMP" com.xinyv.median.HomePageConfigSelfTest
java -cp "$TMP" com.xinyv.median.LogoMarkupSelfTest
tools/tests/userscript_js_syntax_test.sh

if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  git diff --check
fi
echo 'Static checks passed.'
