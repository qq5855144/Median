#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
command -v node >/dev/null 2>&1 || { echo 'node is required for userscript syntax checks' >&2; exit 1; }
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/src/android/content" "$TMP/src/org/json" "$TMP/src/com/xinyv/median" "$TMP/classes"
cat > "$TMP/src/android/content/Context.java" <<'JAVA'
package android.content;
public abstract class Context {
    public static final int MODE_PRIVATE = 0;
    public abstract SharedPreferences getSharedPreferences(String name, int mode);
}
JAVA
cat > "$TMP/src/android/content/SharedPreferences.java" <<'JAVA'
package android.content;
public interface SharedPreferences {
    String getString(String key, String defValue);
    Editor edit();
    interface Editor {
        Editor putString(String key, String value);
        void apply();
        boolean commit();
    }
}
JAVA
cat > "$TMP/src/org/json/JSONException.java" <<'JAVA'
package org.json;
public class JSONException extends Exception {
    public JSONException() { super(); }
    public JSONException(String message) { super(message); }
}
JAVA
cat > "$TMP/src/org/json/JSONArray.java" <<'JAVA'
package org.json;
import java.util.Collection;
public class JSONArray {
    public JSONArray() {}
    public JSONArray(String raw) throws JSONException {}
    public JSONArray(Collection<?> values) {}
    public int length() { return 0; }
    public JSONObject optJSONObject(int index) { return null; }
    public String optString(int index) { return ""; }
    public JSONArray put(Object value) { return this; }
    @Override public String toString() { return "[]"; }
}
JAVA
cat > "$TMP/src/org/json/JSONObject.java" <<'JAVA'
package org.json;
public class JSONObject {
    public JSONObject() {}
    public JSONObject(String raw) throws JSONException {}
    public JSONObject put(String key, Object value) throws JSONException { return this; }
    public String optString(String key, String fallback) { return fallback; }
    public boolean optBoolean(String key, boolean fallback) { return fallback; }
    public double optDouble(String key, double fallback) { return fallback; }
    public int optInt(String key, int fallback) { return fallback; }
    public long optLong(String key, long fallback) { return fallback; }
    public JSONArray optJSONArray(String key) { return null; }
    @Override public String toString() { return "{}"; }
}
JAVA
cat > "$TMP/src/org/json/JSONTokener.java" <<'JAVA'
package org.json;
public class JSONTokener {
    public JSONTokener(String raw) {}
    public Object nextValue() throws JSONException { return null; }
}
JAVA
cat > "$TMP/src/com/xinyv/median/UrlCleaner.java" <<'JAVA'
package com.xinyv.median;
final class UrlCleaner {
    static String stableId(String value) { return Integer.toUnsignedString(value == null ? 0 : value.hashCode(), 36); }
}
JAVA
cat > "$TMP/src/com/xinyv/median/UserScriptGeneratedJsSelfTest.java" <<'JAVA'
package com.xinyv.median;
import android.content.Context;
import android.content.SharedPreferences;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class UserScriptGeneratedJsSelfTest {
    private static final class MemoryPreferences implements SharedPreferences, SharedPreferences.Editor {
        public String getString(String key, String fallback) { return fallback; }
        public Editor edit() { return this; }
        public Editor putString(String key, String value) { return this; }
        public void apply() {}
        public boolean commit() { return true; }
    }
    private static final class MemoryContext extends Context {
        private final MemoryPreferences preferences = new MemoryPreferences();
        public SharedPreferences getSharedPreferences(String name, int mode) { return preferences; }
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        UserScriptStore store = new UserScriptStore(new MemoryContext());
        UserScriptStore.Script script = new UserScriptStore.Script();
        script.id = "syntax-test";
        script.name = "Generated JS syntax test";
        script.version = "1.0";
        script.namespace = "median.test";
        script.description = "Exercises every compatibility API";
        script.author = "Median";
        script.homepage = "https://example.com/";
        script.runAt = "document-start";
        script.code = "GM_registerMenuCommand('Test', function(){ GM_setValue('x', 1); });\n" +
                "GM_xmlhttpRequest({url:'https://example.com/data',responseType:'arraybuffer',onload:function(r){console.log(r.status);}});";
        script.requireCode = "const requiredValue = 1;";
        script.enabled = true;
        script.matches.add("https://example.com/*");
        script.compiledMatches.add(Pattern.compile("^https://example\\.com/.*$"));
        script.grants.add("GM_registerMenuCommand");
        script.grants.add("GM_setValue");
        script.grants.add("GM_xmlhttpRequest");
        script.grants.add("GM_download");
        script.connects.add("example.com");
        UserScriptStore.Script.Resource resource = new UserScriptStore.Script.Resource();
        resource.name = "sample";
        resource.url = "https://example.com/sample.txt";
        resource.mime = "text/plain";
        resource.base64 = "aGVsbG8=";
        script.resources.add(resource);

        Field field = UserScriptStore.class.getDeclaredField("cache");
        field.setAccessible(true);
        ((ArrayList<UserScriptStore.Script>) field.get(store)).add(script);
        if (!store.allowsApi(script.id, "xhr") || !store.allowsApi(script.id, "download"))
            throw new AssertionError("declared grants were not recognized");
        script.grants.add("GM_notDownloadButContainsTheWord");
        script.grants.remove("GM_download");
        if (store.allowsApi(script.id, "download"))
            throw new AssertionError("substring grant accidentally authorized download");
        script.grants.add("GM_download");
        List<String> generated = store.buildDocumentStartScripts("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        if (generated.size() != 1) throw new AssertionError("expected one generated userscript");
        String payload = generated.get(0);
        if (!payload.contains("location.hostname||'').toLowerCase()==='median.invalid'"))
            throw new AssertionError("internal home-page guard missing");
        if (!payload.contains("if(window.top!==window.self)return;"))
            throw new AssertionError("native-grant scripts must be top-frame only");
        if (store.matchesUrl(script.id, "https://median.invalid/"))
            throw new AssertionError("internal home page must not match user scripts");
        System.out.print(payload);
    }
}
JAVA

javac --release 17 -d "$TMP/classes" \
  "$TMP/src/android/content/Context.java" \
  "$TMP/src/android/content/SharedPreferences.java" \
  "$TMP/src/org/json/JSONException.java" \
  "$TMP/src/org/json/JSONArray.java" \
  "$TMP/src/org/json/JSONObject.java" \
  "$TMP/src/org/json/JSONTokener.java" \
  "$TMP/src/com/xinyv/median/UrlCleaner.java" \
  app/src/main/java/com/xinyv/median/NetworkSecurity.java \
  app/src/main/java/com/xinyv/median/UserScriptStore.java \
  "$TMP/src/com/xinyv/median/UserScriptGeneratedJsSelfTest.java"
java -cp "$TMP/classes" com.xinyv.median.UserScriptGeneratedJsSelfTest > "$TMP/generated-userscript.js"
node --check "$TMP/generated-userscript.js" >/dev/null
echo 'Generated userscript JavaScript syntax passed'
