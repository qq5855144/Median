#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
BUILD_TOOLS_VERSION="${BUILD_TOOLS_VERSION:-35.0.0}"
BUILD_TOOLS="$SDK_ROOT/build-tools/$BUILD_TOOLS_VERSION"
ANDROID_JAR="$SDK_ROOT/platforms/android-36/android.jar"
OUT="${OUT_DIR:-$ROOT/out/500kb}"
APPLICATION_ID="${APPLICATION_ID:-com.xinyv.median.compat}"
VERSION_CODE="${VERSION_CODE:-73}"
VERSION_NAME="${VERSION_NAME:-2.1.9}"

: "${SDK_ROOT:?Set ANDROID_SDK_ROOT or ANDROID_HOME}"
for tool in aapt2 zipalign apksigner; do
  [ -x "$BUILD_TOOLS/$tool" ] || { echo "Missing $BUILD_TOOLS/$tool" >&2; exit 2; }
done
[ -f "$BUILD_TOOLS/lib/d8.jar" ] || { echo "Missing R8/D8 jar" >&2; exit 2; }
[ -f "$ANDROID_JAR" ] || { echo "Missing $ANDROID_JAR" >&2; exit 2; }
: "${MEDIAN_KEYSTORE:?Set MEDIAN_KEYSTORE}"
: "${MEDIAN_STOREPASS:?Set MEDIAN_STOREPASS}"
: "${MEDIAN_KEY_ALIAS:?Set MEDIAN_KEY_ALIAS}"
MEDIAN_KEYPASS="${MEDIAN_KEYPASS:-$MEDIAN_STOREPASS}"

rm -rf "$OUT"
mkdir -p "$OUT"/{compiled,resgen,generated,classes,r8}

python3 - "$ROOT/app/src/main/AndroidManifest.xml" "$OUT/AndroidManifest.xml" "$APPLICATION_ID" <<'PY'
import re, sys
src, dst, app_id = sys.argv[1:]
text = open(src, encoding='utf-8').read()
text = text.replace('${applicationId}', app_id)
text = re.sub(r'<manifest\b', '<manifest package="%s"' % app_id, text, count=1)
text = re.sub(r'android:name="\.(\w+)', r'android:name="com.xinyv.median.\1', text)
open(dst, 'w', encoding='utf-8').write(text)
PY

"$BUILD_TOOLS/aapt2" compile --dir "$ROOT/app/src/main/res" -o "$OUT/compiled/resources.zip"
"$BUILD_TOOLS/aapt2" link \
  -I "$ANDROID_JAR" \
  --manifest "$OUT/AndroidManifest.xml" \
  --java "$OUT/resgen" \
  --proguard "$OUT/aapt-proguard.pro" \
  --min-sdk-version 26 \
  --target-sdk-version 36 \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  --auto-add-overlay \
  --enable-sparse-encoding \
  -o "$OUT/base-unsigned.apk" \
  "$OUT/compiled/resources.zip"

mkdir -p "$OUT/generated/com/xinyv/median"
cat > "$OUT/generated/com/xinyv/median/BuildConfig.java" <<JAVA
package com.xinyv.median;
public final class BuildConfig {
  public static final boolean DEBUG = false;
  public static final String APPLICATION_ID = "$APPLICATION_ID";
  public static final int VERSION_CODE = $VERSION_CODE;
  public static final String VERSION_NAME = "$VERSION_NAME";
  private BuildConfig() {}
}
JAVA

mapfile -t SOURCES < <(find "$ROOT/app/src/main/java" "$OUT/resgen" "$OUT/generated" -name '*.java' -type f | sort)
javac -encoding UTF-8 -source 17 -target 17 \
  -classpath "$ANDROID_JAR" \
  -d "$OUT/classes" \
  "${SOURCES[@]}"

mkdir -p "$OUT/test-classes"
javac --release 17 -classpath "$ANDROID_JAR:$OUT/classes" -d "$OUT/test-classes" \
  "$ROOT/tools/tests/DownloadCenterPolicySelfTest.java" \
  "$ROOT/tools/tests/DownloadRetryPolicySelfTest.java" \
  "$ROOT/tools/tests/DownloadFileTypesSelfTest.java" \
  "$ROOT/tools/tests/OmniboxInputSelfTest.java" \
  "$ROOT/tools/tests/HomeOpenPolicySelfTest.java" \
  "$ROOT/tools/tests/CustomHomeCssSelfTest.java" \
  "$ROOT/tools/tests/CustomHomeHtmlSelfTest.java" \
  "$ROOT/tools/tests/HomePageConfigSelfTest.java" \
  "$ROOT/tools/tests/LogoMarkupSelfTest.java" \
  "$ROOT/tools/tests/HomePageHtmlSelfTest.java"
java -cp "$ANDROID_JAR:$OUT/classes:$OUT/test-classes" com.xinyv.median.DownloadCenterPolicySelfTest
java -cp "$ANDROID_JAR:$OUT/classes:$OUT/test-classes" com.xinyv.median.DownloadRetryPolicySelfTest
java -cp "$ANDROID_JAR:$OUT/classes:$OUT/test-classes" com.xinyv.median.OmniboxInputSelfTest
java -cp "$ANDROID_JAR:$OUT/classes:$OUT/test-classes" com.xinyv.median.HomeOpenPolicySelfTest
java -cp "$ANDROID_JAR:$OUT/classes:$OUT/test-classes" com.xinyv.median.CustomHomeCssSelfTest
java -cp "$ANDROID_JAR:$OUT/classes:$OUT/test-classes" com.xinyv.median.CustomHomeHtmlSelfTest
java -cp "$ANDROID_JAR:$OUT/classes:$OUT/test-classes" com.xinyv.median.HomePageConfigSelfTest
java -cp "$ANDROID_JAR:$OUT/classes:$OUT/test-classes" com.xinyv.median.LogoMarkupSelfTest
java -cp "$ANDROID_JAR:$OUT/classes:$OUT/test-classes" com.xinyv.median.HomePageHtmlSelfTest "$OUT/home-page.js"
node --check "$OUT/home-page.js"
jar --create --file "$OUT/program.jar" -C "$OUT/classes" .

cat > "$OUT/r8.pro" <<PRO
-dontwarn org.chromium.**
-dontwarn java.lang.invoke.**

-keep,allowoptimization class com.xinyv.median.MainActivity { public <init>(); }
-keep,allowoptimization class com.xinyv.median.PrivateActivity { public <init>(); }
-keep,allowoptimization class com.xinyv.median.DownloadCenterActivity { public <init>(); }
-keep,allowoptimization class com.xinyv.median.AdaptiveDownloadService { public <init>(); }
-keep,allowoptimization class com.xinyv.median.OfflineContentProvider { public <init>(); }
-keep,allowoptimization class com.xinyv.median.DownloadContentProvider { public <init>(); }

-keep,allowoptimization interface org.chromium.support_lib_boundary.** { *; }
-keepnames interface org.chromium.support_lib_boundary.**
-keep,allowoptimization class org.chromium.support_lib_boundary.util.BoundaryInterfaceReflectionUtil { *; }

-keep,allowoptimization class androidx.webkit.WebViewCompat { public *; }
-keep,allowoptimization class androidx.webkit.WebViewFeature { public *; }
-keep,allowoptimization interface androidx.webkit.ScriptHandler { *; }

-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
-renamesourcefileattribute SourceFile
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod
PRO
cat "$OUT/aapt-proguard.pro" >> "$OUT/r8.pro"

java -Xmx3g -cp "$BUILD_TOOLS/lib/d8.jar" com.android.tools.r8.R8 \
  --release \
  --min-api 26 \
  --lib "$ANDROID_JAR" \
  --output "$OUT/r8" \
  --pg-conf "$OUT/r8.pro" \
  --pg-map-output "$OUT/mapping.txt" \
  "$OUT/program.jar"

cp "$OUT/base-unsigned.apk" "$OUT/stage.apk"
(cd "$OUT/r8" && zip -q -9 "$OUT/stage.apk" classes.dex)
"$BUILD_TOOLS/zipalign" -f -p 4 "$OUT/stage.apk" "$OUT/aligned.apk"
FINAL="$OUT/MedianBrowser-$VERSION_NAME.apk"
"$BUILD_TOOLS/apksigner" sign \
  --ks "$MEDIAN_KEYSTORE" \
  --ks-pass "pass:$MEDIAN_STOREPASS" \
  --ks-key-alias "$MEDIAN_KEY_ALIAS" \
  --key-pass "pass:$MEDIAN_KEYPASS" \
  --v1-signing-enabled false \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  --out "$FINAL" \
  "$OUT/aligned.apk"
"$BUILD_TOOLS/apksigner" verify --verbose --print-certs "$FINAL" > "$OUT/signature.txt"
"$BUILD_TOOLS/aapt" dump badging "$FINAL" > "$OUT/badging.txt"
unzip -t "$FINAL" > "$OUT/unzip-test.txt"
java -cp "$ANDROID_JAR:$OUT/classes:$OUT/test-classes" com.xinyv.median.DownloadFileTypesSelfTest "$FINAL"
sha256sum "$FINAL" > "$FINAL.sha256"

SIZE=$(stat -c %s "$FINAL")
echo "APK: $FINAL"
echo "Size: $SIZE bytes"
if [ "$SIZE" -gt 512000 ]; then
  echo "500 KiB target: FAIL ($SIZE bytes)" >&2
  exit 3
else
  echo "500 KiB target: PASS ($((512000 - SIZE)) bytes remaining)"
fi
