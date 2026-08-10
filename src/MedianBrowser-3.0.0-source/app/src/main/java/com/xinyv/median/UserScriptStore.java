package com.xinyv.median;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

final class UserScriptStore {
    static final class Script {
        static final class Resource {
            String name = "";
            String url = "";
            String mime = "application/octet-stream";
            String base64 = "";
        }

        String id = "";
        String name = "未命名脚本";
        String version = "";
        String namespace = "";
        String description = "";
        String author = "";
        String homepage = "";
        String sourceUrl = "";
        String updateUrl = "";
        String downloadUrl = "";
        String runAt = "document-end";
        String code = "";
        String requireCode = "";
        boolean noFrames;
        boolean enabled = true;
        boolean quarantined;
        String disabledReason = "";
        int riskScore;
        String riskSummary = "低风险";
        double lastCostMs;
        int slowStrikes;
        long installedAt;
        long updatedAt;
        long lastUpdateCheck;
        final ArrayList<String> matches = new ArrayList<String>();
        final ArrayList<String> excludes = new ArrayList<String>();
        final ArrayList<String> grants = new ArrayList<String>();
        final ArrayList<String> requires = new ArrayList<String>();
        final ArrayList<String> connects = new ArrayList<String>();
        final ArrayList<Resource> resources = new ArrayList<Resource>();
        final ArrayList<Pattern> compiledMatches = new ArrayList<Pattern>();
        final ArrayList<Pattern> compiledExcludes = new ArrayList<Pattern>();
    }

    private static final String PREFS = "median_scripts_v2";
    private static final String KEY = "scripts";
    private final SharedPreferences prefs;
    private final Context appContext;
    private final ArrayList<Script> cache = new ArrayList<Script>();
    private final LinkedHashMap<String, List<Script>> matchCache = new LinkedHashMap<String, List<Script>>(32, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, List<Script>> eldest) { return size() > 32; }
    };
    UserScriptStore(Context context) {
        appContext = context.getApplicationContext();
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        loadCache();
    }

    synchronized List<Script> getAll() {
        return new ArrayList<Script>(cache);
    }

    synchronized boolean hasEnabledScripts() {
        for (Script script : cache) {
            if (script.enabled && !script.quarantined && script.code.length() > 0) return true;
        }
        return false;
    }

    synchronized Script getById(String id) {
        for (Script script : cache) if (script.id.equals(id)) return script;
        return null;
    }

    synchronized void save(Script script) {
        if (script == null || script.code == null || script.code.length() > 1024 * 1024) throw new IllegalArgumentException("脚本超过 1 MB");
        prepare(script);
        boolean replaced = false;
        for (int i = 0; i < cache.size(); i++) {
            if (cache.get(i).id.equals(script.id)) {
                cache.set(i, script);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            if (cache.size() >= 128) throw new IllegalStateException("最多安装 128 个脚本");
            cache.add(script);
        }
        persist();
    }

    synchronized void saveBatch(List<Script> scripts) {
        if (scripts == null) return;
        for (Script script : scripts) {
            if (script == null || script.code == null || script.code.length() > 1024 * 1024) continue;
            prepare(script);
            boolean replaced = false;
            for (int i = 0; i < cache.size(); i++) {
                if (cache.get(i).id.equals(script.id)) {
                    cache.set(i, script);
                    replaced = true;
                    break;
                }
            }
            if (!replaced && cache.size() < 128) cache.add(script);
        }
        persist();
    }

    void refreshAnalysis(Script script) {
        if (script != null) prepare(script);
    }

    synchronized String exportJson() { return prefs.getString(KEY, "[]"); }

    synchronized int importJson(String raw) throws Exception {
        if (raw == null || raw.length() > 12 * 1024 * 1024) throw new IllegalArgumentException("脚本备份超过 12 MB");
        JSONArray array = new JSONArray(raw);
        if (array.length() > 128) throw new IllegalArgumentException("脚本数量超过 128 个");
        prefs.edit().putString(KEY, raw).commit();
        loadCache();
        return cache.size();
    }

    synchronized void setEnabled(String id, boolean enabled) {
        Script script = getById(id);
        if (script != null) {
            script.enabled = enabled;
            if (enabled) {
                script.quarantined = false;
                script.disabledReason = "";
                script.slowStrikes = 0;
            }
            persist();
        }
    }

    synchronized void delete(String id) {
        for (int i = cache.size() - 1; i >= 0; i--) {
            if (cache.get(i).id.equals(id)) cache.remove(i);
        }
        persist();
    }

    synchronized List<Script> matching(String url) {
        if (!isEligiblePageUrl(url)) return Collections.emptyList();
        List<Script> remembered = matchCache.get(url);
        if (remembered != null) return remembered;
        ArrayList<Script> result = new ArrayList<Script>();
        for (Script script : cache) {
            if (!script.enabled || script.quarantined || script.code.length() == 0) continue;
            boolean included = script.matches.size() == 0;
            for (Pattern pattern : script.compiledMatches) {
                if (pattern.matcher(url).find()) {
                    included = true;
                    break;
                }
            }
            if (!included) continue;
            boolean excluded = false;
            for (Pattern pattern : script.compiledExcludes) {
                if (pattern.matcher(url).find()) {
                    excluded = true;
                    break;
                }
            }
            if (!excluded) result.add(script);
        }
        List<Script> stable = Collections.unmodifiableList(result);
        matchCache.put(url, stable);
        return stable;
    }

    Script parseUserScript(String source, String sourceUrl) throws IllegalArgumentException {
        if (source == null || source.length() == 0) throw new IllegalArgumentException("脚本内容为空");
        int start = source.indexOf("==UserScript==");
        int end = source.indexOf("==/UserScript==");
        if (start < 0 || end <= start) throw new IllegalArgumentException("不是有效的 UserScript");

        Script script = new Script();
        script.sourceUrl = sourceUrl == null ? "" : sourceUrl;
        script.installedAt = System.currentTimeMillis();
        script.updatedAt = script.installedAt;
        script.code = source;
        script.id = stableId(script.sourceUrl.length() > 0 ? script.sourceUrl : String.valueOf(System.currentTimeMillis()));

        String fallbackName = "";
        String localizedName = "";
        String[] lines = source.substring(start, end).split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            int at = trimmed.indexOf('@');
            if (at < 0) continue;
            String meta = trimmed.substring(at + 1).trim();
            int space = meta.indexOf(' ');
            if (space < 0) space = meta.indexOf('\t');
            String key = space < 0 ? meta : meta.substring(0, space).trim();
            String value = space < 0 ? "" : meta.substring(space + 1).trim();
            if (key.equals("name")) fallbackName = value;
            else if (key.equals("name:zh-CN") || key.equals("name:zh-Hans")) localizedName = value;
            else if (key.equals("version")) script.version = value;
            else if (key.equals("namespace")) script.namespace = value;
            else if (key.equals("description") || key.equals("description:zh-CN") || key.equals("description:zh-Hans")) script.description = value;
            else if (key.equals("author")) script.author = value;
            else if (key.equals("homepage") || key.equals("homepageURL") || key.equals("website")) script.homepage = value;
            else if (key.equals("match") || key.equals("include")) addUnique(script.matches, value);
            else if (key.equals("exclude") || key.equals("exclude-match")) addUnique(script.excludes, value);
            else if (key.equals("run-at")) script.runAt = value;
            else if (key.equals("grant")) addUnique(script.grants, value);
            else if (key.equals("require")) addUnique(script.requires, value);
            else if (key.equals("connect")) addUnique(script.connects, value);
            else if (key.equals("resource")) parseResource(value, script.resources);
            else if (key.equals("noframes")) script.noFrames = true;
            else if (key.equals("downloadURL")) script.downloadUrl = value;
            else if (key.equals("updateURL")) script.updateUrl = value;
        }
        script.name = localizedName.length() > 0 ? localizedName : (fallbackName.length() > 0 ? fallbackName : "未命名脚本");
        if (script.matches.size() == 0) script.matches.add("*://*/*");
        prepare(script);
        if (script.riskScore >= 8) script.enabled = false;
        return script;
    }

    /** Returns one self-contained payload. The final expression is a JSON timing report. */
    String buildInjection(String url, boolean documentStart, String bridgeToken) {
        List<Script> scripts = matching(url);
        if (scripts.size() == 0) return "";
        StringBuilder out = new StringBuilder(8192);
        out.append("(function(){var __mr=[];window.__medianInstalled=window.__medianInstalled||{};");
        boolean included = false;
        for (Script script : scripts) {
            if ((bridgeToken == null || bridgeToken.length() == 0) && hasNativeGrants(script)) continue;
            boolean isStart = "document-start".equalsIgnoreCase(script.runAt);
            if (isStart != documentStart) continue;
            included = true;
            String key = jsQuote(script.id);
            out.append("if(!window.__medianInstalled[").append(key).append("]){window.__medianInstalled[").append(key).append("]=1;");
            out.append("var __mreport=function(){},__mt=(window.performance&&performance.now)?performance.now():Date.now(),__me='';try{(function(){\n");
            if (script.noFrames || hasNativeGrants(script)) out.append("if(window.top!==window.self)return;\n");
            appendCompatibilityApi(out, script, bridgeToken, dispatchObjectName(bridgeToken, script.id));
            if (script.requireCode != null && script.requireCode.length() > 0) {
                out.append("\n/* Median resolved @require */\n").append(script.requireCode).append("\n");
            }
            if ("document-idle".equalsIgnoreCase(script.runAt)) {
                out.append("\nsetTimeout(function(){try{(function(){\n").append(script.code)
                        .append("\n}).call(window);}catch(e){console.error('Median userscript idle ")
                        .append(escapeForSingle(script.name)).append("',e);}},0);\n");
            } else {
                out.append("\n/* Median userscript */\n").append(script.code).append("\n");
            }
            out.append("}).call(window);}catch(e){__me=String(e&&e.message||e);console.error('Median userscript ")
                    .append(escapeForSingle(script.name)).append("',e);}var __md=((window.performance&&performance.now)?performance.now():Date.now())-__mt;");
            out.append("__mr.push({id:").append(key).append(",ms:Math.round(__md*10)/10,error:__me});}");
        }
        out.append("return JSON.stringify(__mr);})();");
        return included ? out.toString() : "";
    }

    /**
     * Creates one document-start registration per enabled script. Each registration captures the
     * native prompt before page JavaScript runs, keeps its capability token in a closure, and uses
     * a non-writable random dispatcher for asynchronous replies.
     */
    synchronized List<String> buildDocumentStartScripts(String bridgeToken) {
        ArrayList<String> result = new ArrayList<String>();
        if (bridgeToken == null || bridgeToken.length() < 32) return result;
        for (Script script : cache) {
            if (!script.enabled || script.quarantined || script.code.length() == 0) continue;
            StringBuilder out = new StringBuilder(Math.max(4096, script.code.length() + script.requireCode.length() + 2048));
            out.append("(function(){'use strict';if((location.protocol!=='http:'&&location.protocol!=='https:')||String(location.hostname||'').toLowerCase()==='median.invalid')return;var __mu=String(location.href||'');if(!(")
                    .append(jsPatternTest(script.compiledMatches, "__mu"))
                    .append(")||(").append(jsPatternTest(script.compiledExcludes, "__mu"))
                    .append("))return;var __mreport=function(){},__medianRun=function(){var __mt=(window.performance&&performance.now)?performance.now():Date.now(),__me='';try{(function(){\n");
            if (script.noFrames || hasNativeGrants(script)) out.append("if(window.top!==window.self)return;\n");
            appendCompatibilityApi(out, script, bridgeToken, dispatchObjectName(bridgeToken, script.id));
            if (script.requireCode != null && script.requireCode.length() > 0) {
                out.append("\n/* Median resolved @require */\n").append(script.requireCode).append("\n");
            }
            out.append("\n/* Median userscript */\n").append(script.code)
                    .append("\n}).call(window);}catch(e){__me=String(e&&e.message||e);try{console.error('Median userscript ")
                    .append(escapeForSingle(script.name)).append("',e);}catch(_){}}var __md=((window.performance&&performance.now)?performance.now():Date.now())-__mt;")
                    .append("try{if(typeof __mreport==='function')__mreport(Math.round(__md*10)/10,__me);}catch(_){};};");
            if ("document-start".equalsIgnoreCase(script.runAt)) {
                out.append("__medianRun();");
            } else if ("document-idle".equalsIgnoreCase(script.runAt)) {
                out.append("var __medianIdle=function(){setTimeout(__medianRun,0);};if(document.readyState==='complete')__medianIdle();else window.addEventListener('load',__medianIdle,{once:true});");
            } else {
                out.append("if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',__medianRun,{once:true});else __medianRun();");
            }
            out.append("})();");
            result.add(out.toString());
        }
        return result;
    }

    synchronized boolean matchesUrl(String scriptId, String url) {
        Script script = getById(scriptId);
        if (script == null || !script.enabled || script.quarantined || !isEligiblePageUrl(url)) return false;
        boolean included = false;
        for (Pattern pattern : script.compiledMatches) if (pattern.matcher(url).find()) { included = true; break; }
        if (!included) return false;
        for (Pattern pattern : script.compiledExcludes) if (pattern.matcher(url).find()) return false;
        return true;
    }

    static String dispatchObjectName(String bridgeToken, String scriptId) {
        return "__medianDispatch_" + UrlCleaner.stableId((bridgeToken == null ? "" : bridgeToken) + "|" + (scriptId == null ? "" : scriptId));
    }

    private static boolean hasNativeGrants(Script script) {
        if (script == null || script.grants.size() == 0) return false;
        for (String grant : script.grants) if (grant != null && grant.trim().length() > 0 && !"none".equalsIgnoreCase(grant.trim())) return true;
        return false;
    }

    private static boolean isEligiblePageUrl(String url) {
        try {
            java.net.URL parsed = NetworkSecurity.parseHttpUrl(url);
            return !"median.invalid".equals(NetworkSecurity.normalizedHost(parsed));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String jsPatternTest(List<Pattern> patterns, String valueExpression) {
        if (patterns == null || patterns.size() == 0) return "false";
        StringBuilder out = new StringBuilder();
        for (Pattern pattern : patterns) {
            if (out.length() > 0) out.append("||");
            String flags = (pattern.flags() & Pattern.CASE_INSENSITIVE) != 0 ? "i" : "";
            out.append("(new RegExp(").append(jsQuote(pattern.pattern())).append(',').append(jsQuote(flags))
                    .append(").test(").append(valueExpression).append("))");
        }
        return out.toString();
    }

    synchronized boolean canConnect(String scriptId, String targetUrl, String pageUrl) {
        Script script = getById(scriptId);
        if (script == null || !script.enabled || script.quarantined || !matchesUrl(scriptId, pageUrl)) return false;
        try {
            java.net.URL target = NetworkSecurity.parseHttpUrl(targetUrl);
            java.net.URL page = NetworkSecurity.parseHttpUrl(pageUrl);
            String targetHost = NetworkSecurity.normalizedHost(target);
            String pageHost = NetworkSecurity.normalizedHost(page);
            boolean localTarget = NetworkSecurity.isObviouslyLocalHost(targetHost);
            if (script.connects.size() == 0) return NetworkSecurity.sameOrigin(target, page);
            for (String raw : script.connects) {
                String value = raw == null ? "" : raw.trim().toLowerCase(Locale.US);
                if ("*".equals(value)) {
                    if (!localTarget) return true; // Wildcards never silently grant native access to local networks.
                    continue;
                }
                if ("self".equals(value) && NetworkSecurity.sameOrigin(target, page)) return true;
                if (value.contains("://")) {
                    try {
                        java.net.URL allowed = NetworkSecurity.parseHttpUrl(value.endsWith("/") ? value : value + "/");
                        if (NetworkSecurity.sameOrigin(target, allowed)) return true;
                    } catch (Exception ignored) {}
                    continue;
                }
                if (value.startsWith("*.")) {
                    String suffix = value.substring(2);
                    if (!localTarget && targetHost.endsWith("." + suffix)) return true;
                } else if (targetHost.equals(value)) {
                    return !localTarget || value.equals(pageHost) || NetworkSecurity.isObviouslyLocalHost(value);
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    synchronized boolean isRunnable(String scriptId) {
        Script script = getById(scriptId);
        return script != null && script.enabled && !script.quarantined;
    }

    synchronized boolean allowsApi(String scriptId, String action) {
        Script script = getById(scriptId);
        if (script == null || !script.enabled || script.quarantined) return false;
        if ("report".equals(action)) return true;
        if (script.grants.size() == 0) return false; // Missing @grant is treated as least privilege.
        for (String raw : script.grants) {
            String grant = normalizeGrant(raw);
            if ("none".equals(grant)) return false;
            if ((action.equals("getValue") && "gm_getvalue".equals(grant)) ||
                    (action.equals("setValue") && "gm_setvalue".equals(grant)) ||
                    (action.equals("deleteValue") && "gm_deletevalue".equals(grant)) ||
                    (action.equals("listValues") && "gm_listvalues".equals(grant)) ||
                    (action.equals("openTab") && "gm_openintab".equals(grant)) ||
                    (action.equals("clipboard") && "gm_setclipboard".equals(grant)) ||
                    (action.equals("notification") && "gm_notification".equals(grant)) ||
                    (action.equals("download") && "gm_download".equals(grant)) ||
                    ((action.equals("xhr") || action.equals("xhrAbort")) && "gm_xmlhttprequest".equals(grant))) return true;
        }
        return false;
    }

    private static String normalizeGrant(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.US).replace('.', '_');
        while (value.contains("__")) value = value.replace("__", "_");
        return value;
    }

    synchronized void recordExecutionResult(String raw) {
        if (raw == null || raw.length() == 0 || "null".equals(raw)) return;
        boolean changed = false;
        try {
            Object decoded = new JSONTokener(raw).nextValue();
            String json = decoded instanceof String ? (String) decoded : String.valueOf(decoded);
            JSONArray results = new JSONArray(json);
            for (int i = 0; i < results.length(); i++) {
                JSONObject item = results.optJSONObject(i);
                if (item == null) continue;
                Script script = getById(item.optString("id", ""));
                if (script == null) continue;
                double ms = item.optDouble("ms", 0d);
                String error = item.optString("error", "");
                script.lastCostMs = ms;
                if (ms >= 45d || error.length() > 0) {
                    script.slowStrikes++;
                    changed = true;
                } else if (script.slowStrikes > 0) {
                    script.slowStrikes--;
                    changed = true;
                }
                if (ms >= 250d || script.slowStrikes >= 3) {
                    script.quarantined = true;
                    script.enabled = false;
                    script.disabledReason = ms >= 250d ? "单次同步执行超过 250ms" : "连续造成慢执行或错误";
                    changed = true;
                }
            }
        } catch (Exception ignored) {
        }
        if (changed) persist();
    }

    synchronized void recordExecution(String scriptId, double ms, String error) {
        Script script = getById(scriptId);
        if (script == null) return;
        ms = Math.max(0d, Math.min(60000d, ms));
        error = error == null ? "" : error;
        script.lastCostMs = ms;
        boolean changed = false;
        if (ms >= 45d || error.length() > 0) {
            script.slowStrikes++;
            changed = true;
        } else if (script.slowStrikes > 0) {
            script.slowStrikes--;
            changed = true;
        }
        if (ms >= 250d || script.slowStrikes >= 3) {
            script.quarantined = true;
            script.enabled = false;
            script.disabledReason = ms >= 250d ? "单次同步执行超过 250ms" : "连续造成慢执行或错误";
            changed = true;
        }
        if (changed) persist();
    }

    synchronized int quarantineMatching(String url, String reason) {
        int count = 0;
        for (Script script : matching(url)) {
            script.quarantined = true;
            script.enabled = false;
            script.disabledReason = reason == null ? "页面渲染器无响应" : reason;
            count++;
        }
        if (count > 0) persist();
        return count;
    }

    private void loadCache() {
        cache.clear();
        matchCache.clear();
        String raw = prefs.getString(KEY, null);
        if (raw == null) {
            // One-time import from the previous schema.
            raw = prefs.getString("scripts", "[]");
        }
        try {
            JSONArray array = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < array.length() && cache.size() < 128; i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) continue;
                Script script = new Script();
                script.id = object.optString("id", String.valueOf(i));
                script.name = object.optString("name", "未命名脚本");
                script.version = object.optString("version", "");
                script.namespace = object.optString("namespace", "");
                script.description = object.optString("description", "");
                script.author = object.optString("author", "");
                script.homepage = object.optString("homepage", "");
                script.sourceUrl = object.optString("sourceUrl", "");
                script.updateUrl = object.optString("updateUrl", "");
                script.downloadUrl = object.optString("downloadUrl", "");
                script.runAt = object.optString("runAt", "document-end");
                script.code = decompressFromStore(object.optString("code", ""));
                String assetCode = readDsppAsset(script.sourceUrl);
                if (assetCode != null && assetCode.length() > 0) script.code = assetCode;
                script.requireCode = object.optString("requireCode", "");
                if (script.code.length() > 1024 * 1024 || script.requireCode.length() > 1024 * 1024) continue;
                script.noFrames = object.optBoolean("noFrames", false);
                script.enabled = object.optBoolean("enabled", true);
                script.quarantined = object.optBoolean("quarantined", false);
                script.disabledReason = object.optString("disabledReason", "");
                script.lastCostMs = object.optDouble("lastCostMs", 0d);
                script.slowStrikes = object.optInt("slowStrikes", 0);
                script.installedAt = object.optLong("installedAt", 0L);
                script.updatedAt = object.optLong("updatedAt", script.installedAt);
                script.lastUpdateCheck = object.optLong("lastUpdateCheck", 0L);
                copyArray(object.optJSONArray("matches"), script.matches);
                copyArray(object.optJSONArray("excludes"), script.excludes);
                copyArray(object.optJSONArray("grants"), script.grants);
                copyArray(object.optJSONArray("requires"), script.requires);
                copyArray(object.optJSONArray("connects"), script.connects);
                copyResources(object.optJSONArray("resources"), script.resources);
                if (script.matches.size() == 0) script.matches.add("*://*/*");
                prepare(script);
                cache.add(script);
            }
        } catch (JSONException ignored) {
            cache.clear();
        }
    }

    private void persist() {
        matchCache.clear();
        JSONArray array = new JSONArray();
        for (Script script : cache) {
            JSONObject object = new JSONObject();
            try {
                object.put("id", script.id);
                object.put("name", script.name);
                object.put("version", script.version);
                object.put("namespace", script.namespace);
                object.put("description", script.description);
                object.put("author", script.author);
                object.put("homepage", script.homepage);
                object.put("sourceUrl", script.sourceUrl);
                object.put("updateUrl", script.updateUrl);
                object.put("downloadUrl", script.downloadUrl);
                object.put("runAt", script.runAt);
                object.put("code", compressForStore(script.code));
                object.put("requireCode", script.requireCode);
                object.put("noFrames", script.noFrames);
                object.put("enabled", script.enabled);
                object.put("quarantined", script.quarantined);
                object.put("disabledReason", script.disabledReason);
                object.put("lastCostMs", script.lastCostMs);
                object.put("slowStrikes", script.slowStrikes);
                object.put("installedAt", script.installedAt);
                object.put("updatedAt", script.updatedAt);
                object.put("lastUpdateCheck", script.lastUpdateCheck);
                object.put("matches", new JSONArray(script.matches));
                object.put("excludes", new JSONArray(script.excludes));
                object.put("grants", new JSONArray(script.grants));
                object.put("requires", new JSONArray(script.requires));
                object.put("connects", new JSONArray(script.connects));
                JSONArray resources = new JSONArray();
                for (Script.Resource resource : script.resources) {
                    JSONObject value = new JSONObject();
                    value.put("name", resource.name);
                    value.put("url", resource.url);
                    value.put("mime", resource.mime);
                    value.put("base64", resource.base64);
                    resources.put(value);
                }
                object.put("resources", resources);
                array.put(object);
            } catch (JSONException ignored) {
            }
        }
        prefs.edit().putString(KEY, array.toString()).apply();
    }

    private static void prepare(Script script) {
        preparePatterns(script);
        analyzeRisk(script);
    }

    private static void analyzeRisk(Script script) {
        String combined = (script.requireCode == null ? "" : script.requireCode) + "\n" + (script.code == null ? "" : script.code);
        String lower = combined.toLowerCase(Locale.US);
        int score = 0;
        ArrayList<String> reasons = new ArrayList<String>();
        if (script.matches.contains("*://*/*") || script.matches.contains("http*://*/*")) { score += 2; reasons.add("全站运行"); }
        if (script.requires.size() > 0) { score += Math.min(4, script.requires.size() * 2); reasons.add("外部依赖"); }
        if (script.resources.size() > 0) { score += Math.min(2, script.resources.size()); reasons.add("外部资源"); }
        if (script.connects.contains("*")) { score += 2; reasons.add("任意网络域名"); }
        for (String grant : script.grants) {
            if (!"none".equalsIgnoreCase(grant)) score += 1;
            if (grant.toLowerCase(Locale.US).contains("xmlhttprequest") || grant.toLowerCase(Locale.US).contains("download")) score += 1;
        }
        if (lower.contains("unsafewindow")) { score += 2; reasons.add("网页全局访问"); }
        if (lower.contains("eval(") || lower.contains("new function")) { score += 3; reasons.add("动态代码"); }
        if (lower.contains("while(true)") || lower.contains("while (true)") || lower.contains("for(;;)")) { score += 5; reasons.add("疑似无限循环"); }
        if (lower.contains("setinterval(")) { score += 1; reasons.add("高频定时器"); }
        if (lower.contains("mutationobserver")) { score += 1; reasons.add("持续 DOM 监听"); }
        if (lower.contains("xmlhttprequest") || lower.contains("fetch(") || lower.contains("websocket")) { score += 2; reasons.add("网络访问"); }
        if (lower.contains("document.cookie") || lower.contains("localstorage")) { score += 1; reasons.add("网站数据访问"); }
        if (lower.contains("navigator.clipboard") || lower.contains("execcommand('copy") || lower.contains("execcommand(\"copy")) { score += 1; reasons.add("剪贴板访问"); }
        if (lower.contains("sendbeacon") || lower.contains("rtcpeerconnection")) { score += 2; reasons.add("后台通信"); }
        if (script.code != null && script.code.length() > 262144) { score += 2; reasons.add("大型脚本"); }
        script.riskScore = score;
        if (score >= 8) script.riskSummary = "高风险 · " + join(reasons);
        else if (score >= 4) script.riskSummary = "中风险 · " + join(reasons);
        else if (score > 0) script.riskSummary = "低风险 · " + join(reasons);
        else script.riskSummary = "低风险 · 未发现明显危险特征";
    }

    private static String join(List<String> values) {
        if (values.size() == 0) return "需人工检查";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.size() && i < 4; i++) {
            if (i > 0) out.append("、");
            out.append(values.get(i));
        }
        return out.toString();
    }

    private static void copyArray(JSONArray array, List<String> target) {
        if (array == null) return;
        for (int i = 0; i < array.length() && target.size() < 512; i++) addUnique(target, array.optString(i));
    }

    private static void parseResource(String value, List<Script.Resource> target) {
        if (value == null) return;
        String trimmed = value.trim();
        int split = -1;
        for (int i = 0; i < trimmed.length(); i++) {
            if (Character.isWhitespace(trimmed.charAt(i))) { split = i; break; }
        }
        if (split <= 0) return;
        String name = trimmed.substring(0, split).trim();
        String url = trimmed.substring(split + 1).trim();
        if (name.length() == 0 || url.length() == 0) return;
        for (Script.Resource existing : target) if (existing.name.equals(name)) return;
        Script.Resource resource = new Script.Resource();
        resource.name = name;
        resource.url = url;
        target.add(resource);
    }

    private static void copyResources(JSONArray array, List<Script.Resource> target) {
        if (array == null) return;
        for (int i = 0; i < array.length() && target.size() < 16; i++) {
            JSONObject object = array.optJSONObject(i);
            if (object == null) continue;
            Script.Resource resource = new Script.Resource();
            resource.name = object.optString("name", "");
            resource.url = object.optString("url", "");
            resource.mime = object.optString("mime", "application/octet-stream");
            resource.base64 = object.optString("base64", "");
            if (resource.name.length() > 0) target.add(resource);
        }
    }

    private static void addUnique(List<String> list, String value) {
        if (value == null) return;
        String v = value.trim();
        if (v.length() > 0 && !list.contains(v)) list.add(v);
    }

    private static void preparePatterns(Script script) {
        script.compiledMatches.clear();
        script.compiledExcludes.clear();
        for (String value : script.matches) {
            Pattern pattern = compilePattern(value);
            if (pattern != null) script.compiledMatches.add(pattern);
        }
        for (String value : script.excludes) {
            Pattern pattern = compilePattern(value);
            if (pattern != null) script.compiledExcludes.add(pattern);
        }
    }

    private static Pattern compilePattern(String pattern) {
        if (pattern == null || pattern.length() == 0 || pattern.equals("*")) return Pattern.compile(".*");
        String p = pattern.trim();
        if (p.length() > 1024) return null;
        if ("<all_urls>".equalsIgnoreCase(p)) return Pattern.compile("^(?:https?|file)://.*$", Pattern.CASE_INSENSITIVE);
        if (p.startsWith("/") && p.endsWith("/") && p.length() > 2) {
            try { return Pattern.compile(p.substring(1, p.length() - 1)); }
            catch (RuntimeException ignored) { return null; }
        }
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c == '*') regex.append(".*");
            else if ("\\.[]{}()+-^$|?".indexOf(c) >= 0) regex.append('\\').append(c);
            else regex.append(c);
        }
        regex.append('$');
        try { return Pattern.compile(regex.toString()); }
        catch (RuntimeException ignored) { return null; }
    }

    private static String stableId(String value) {
        return "script-" + UrlCleaner.stableId(value);
    }

    private static void appendCompatibilityApi(StringBuilder out, Script script, String bridgeToken, String dispatcherName) {
        String id = jsQuote(script.id);
        String name = jsQuote(script.name);
        String version = jsQuote(script.version);
        String namespace = jsQuote(script.namespace);
        String description = jsQuote(script.description);
        String author = jsQuote(script.author);
        String homepage = jsQuote(script.homepage);
        String token = jsQuote(bridgeToken == null ? "" : bridgeToken);
        boolean nativeGrants = hasNativeGrants(script) && bridgeToken != null && bridgeToken.length() >= 32;
        out.append("var __msid=").append(id).append(",__mbt=").append(token)
                .append(",__mp=(typeof window.prompt==='function'?window.prompt.bind(window):null);");
        out.append("var __mraw=function(a,p){try{if(!__mp)return null;var r=__mp('__MEDIAN_BRIDGE__'+JSON.stringify({t:__mbt,s:__msid,a:a,p:p||{}}),'');return r?JSON.parse(r):null;}catch(e){return null;}};");
        out.append("__mreport=function(ms,e){try{__mraw('report',{ms:Number(ms)||0,e:String(e||'').slice(0,500)});}catch(_){}};");
        if (!nativeGrants) {
            out.append("var GM_info={script:{name:").append(name).append(",version:").append(version)
                    .append(",namespace:").append(namespace).append(",description:").append(description)
                    .append(",author:").append(author).append(",homepage:").append(homepage)
                    .append("},scriptHandler:'Median',version:'1.4.0'};");
            return;
        }
        out.append("var unsafeWindow=window,__mk=__msid+':',__mcall=__mraw,__mcb=Object.create(null),__mmenu=Object.create(null),__mmc=1;");
        out.append("var GM_info={script:{name:").append(name).append(",version:").append(version)
                .append(",namespace:").append(namespace).append(",description:").append(description)
                .append(",author:").append(author).append(",homepage:").append(homepage)
                .append("},scriptHandler:'Median',version:'1.4.0'};");
        out.append("var __mdecode=function(p,rt){if(!p)return p;rt=String(rt||'').toLowerCase();try{if((rt==='arraybuffer'||rt==='blob')&&typeof p.response==='string'){var b=atob(p.response),a=new Uint8Array(b.length);for(var j=0;j<b.length;j++)a[j]=b.charCodeAt(j);p.response=rt==='blob'&&typeof Blob==='function'?new Blob([a],{type:String(p.contentType||'application/octet-stream')}):a.buffer;}else if(rt==='json'&&typeof p.response==='string'){p.response=p.response.length?JSON.parse(p.response):null;}}catch(_){if(rt==='json')p.response=null;}return p;};");
        out.append("var __mdispatch=function(t,i,e,p){var x=__mcb[i];if(!x||t!==__mbt)return;p=__mdecode(p,x.o.responseType);if(e==='progress'){if(x.o.onprogress)x.o.onprogress(p);return;}try{if(p&&typeof p==='object')p.readyState=4;if(x.o.onreadystatechange)x.o.onreadystatechange(p);if(e==='load'&&x.o.onload)x.o.onload(p);else if(e==='error'&&x.o.onerror)x.o.onerror(p);else if(e==='timeout'&&x.o.ontimeout)x.o.ontimeout(p);else if(e==='abort'&&x.o.onabort)x.o.onabort(p);}finally{if(x.o.onloadend)x.o.onloadend(p);delete __mcb[i];}};");
        out.append("var __mlist=function(t){if(t!==__mbt)return[];return Object.keys(__mmenu).map(function(i){var x=__mmenu[i];return{id:i,caption:x.c,script:").append(name).append("};});};");
        out.append("var __mrun=function(t,i){if(t!==__mbt||!__mmenu[i])return false;try{__mmenu[i].f();return true;}catch(e){try{console.error('Median script command',e);}catch(_){}return false;}};");
        out.append("try{Object.defineProperty(window,").append(jsQuote(dispatcherName)).append(",{value:Object.freeze({dispatch:__mdispatch,menus:__mlist,runMenu:__mrun}),writable:false,configurable:false,enumerable:false});}catch(_){try{Object.defineProperty(window,").append(jsQuote(dispatcherName)).append(",{value:Object.freeze({dispatch:__mdispatch,menus:__mlist,runMenu:__mrun}),writable:false,configurable:false});}catch(__){}};");
        out.append("var __mres={");
        for (int i = 0; i < script.resources.size(); i++) {
            Script.Resource resource = script.resources.get(i);
            if (i > 0) out.append(',');
            out.append(jsQuote(resource.name)).append(":{b:").append(jsQuote(resource.base64))
                    .append(",m:").append(jsQuote(resource.mime)).append(",u:").append(jsQuote(resource.url)).append("}");
        }
        out.append("};");
        out.append("var GM_log=function(){try{console.log.apply(console,arguments);}catch(e){}};");
        out.append("var GM_addStyle=function(c){var s=document.createElement('style');s.textContent=String(c);(document.head||document.documentElement).appendChild(s);return s;};");
        out.append("var GM_getValue=function(k,d){var r=__mcall('getValue',{k:String(k),d:JSON.stringify(d)});if(r&&r.ok){try{return JSON.parse(r.v);}catch(e){}}try{var v=localStorage.getItem(__mk+k);return v===null?d:JSON.parse(v);}catch(e){return d;}};");
        out.append("var __mvl={},__mvli=1,__mvd=function(k,o,n,r){Object.keys(__mvl).forEach(function(i){var x=__mvl[i];if(x.k===k)try{x.f(k,o,n,!!r);}catch(e){}});};");
        out.append("var GM_setValue=function(k,v){var o=GM_getValue(k,undefined),j=JSON.stringify(v),r=__mcall('setValue',{k:String(k),v:j});if(!(r&&r.ok))try{localStorage.setItem(__mk+k,j);}catch(e){}__mvd(String(k),o,v,false);};");
        out.append("var GM_deleteValue=function(k){var o=GM_getValue(k,undefined),r=__mcall('deleteValue',{k:String(k)});if(!(r&&r.ok))try{localStorage.removeItem(__mk+k);}catch(e){}__mvd(String(k),o,undefined,false);};");
        out.append("var GM_listValues=function(){var r=__mcall('listValues',{});if(r&&r.ok)return r.v||[];var a=[];try{for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i);if(k&&k.indexOf(__mk)===0)a.push(k.slice(__mk.length));}}catch(e){}return a;};");
        out.append("var GM_addValueChangeListener=function(k,f){var i=__mvli++;__mvl[i]={k:String(k),f:f};return i;},GM_removeValueChangeListener=function(i){delete __mvl[i];};");
        out.append("var GM_addElement=function(a,b,c){var p=document.documentElement,t=a,x=b;if(a&&a.nodeType){p=a;t=b;x=c;}var e=document.createElement(t);x=x||{};Object.keys(x).forEach(function(k){if(k==='textContent')e.textContent=x[k];else e.setAttribute(k,x[k]);});p.appendChild(e);return e;};");
        out.append("var GM_openInTab=function(u,o){var r=__mcall('openTab',{u:String(u),active:!(o&&o.active===false)});return{close:function(){},closed:!(r&&r.ok)};};");
        out.append("var GM_setClipboard=function(v){var r=__mcall('clipboard',{v:String(v)});if(r&&r.ok)return;v=String(v);var t=document.createElement('textarea');t.value=v;document.documentElement.appendChild(t);t.select();try{document.execCommand('copy');}finally{t.remove();}};");
        out.append("var GM_notification=function(o){o=typeof o==='string'?{text:o}:(o||{});var r=__mcall('notification',{title:String(o.title||'Median'),text:String(o.text||'')});if(!(r&&r.ok))GM_log(o.text||'');};");
        out.append("var GM_registerMenuCommand=function(c,f){if(typeof f!=='function')return'';var i=").append(id).append("+'-'+(__mmc++);__mmenu[i]={c:String(c).slice(0,160),f:f};return i;};var GM_unregisterMenuCommand=function(i){delete __mmenu[String(i)];};");
        out.append("var GM_getResourceURL=function(n){var r=__mres[n];return r&&r.b?'data:'+(r.m||'application/octet-stream')+';base64,'+r.b:(r?r.u:undefined);};");
        out.append("var GM_getResourceText=function(n){var r=__mres[n];if(!r||!r.b)return undefined;try{var s=atob(r.b),a=new Uint8Array(s.length);for(var i=0;i<s.length;i++)a[i]=s.charCodeAt(i);return new TextDecoder('utf-8').decode(a);}catch(e){return undefined;}};");
        out.append("var GM_download=function(o,n){if(typeof o==='string')o={url:o,name:n};o=o||{};var r=__mcall('download',{u:String(o.url||''),n:String(o.name||''),h:o.headers||{}});if(r&&r.ok){if(o.onload)setTimeout(function(){o.onload({});},0);return{abort:function(){}};}if(o.onerror)setTimeout(function(){o.onerror({error:'download rejected'});},0);return{abort:function(){}};};");
        out.append("var GM_xmlhttpRequest=function(o){o=o||{};var i='x'+Date.now().toString(36)+Math.random().toString(36).slice(2),q={i:i,u:String(o.url||''),m:String(o.method||'GET'),h:o.headers||{},d:o.data==null?'':String(o.data),rt:String(o.responseType||'text'),to:Number(o.timeout||0),anon:!!o.anonymous};__mcb[i]={o:o};try{var st={readyState:1,finalUrl:q.u};if(o.onloadstart)o.onloadstart(st);if(o.onreadystatechange)o.onreadystatechange(st);}catch(_){}var r=__mcall('xhr',q);if(!(r&&r.ok)){delete __mcb[i];setTimeout(function(){var e={error:(r&&r.error)||'request rejected',readyState:4};try{if(o.onreadystatechange)o.onreadystatechange(e);if(o.onerror)o.onerror(e);}finally{if(o.onloadend)o.onloadend(e);}},0);}return{abort:function(){if(!__mcb[i])return;__mcall('xhrAbort',{i:i});}};};");
        out.append("var GM={info:GM_info,log:GM_log,addStyle:GM_addStyle,addElement:GM_addElement,getValue:function(k,d){return Promise.resolve(GM_getValue(k,d));},setValue:function(k,v){GM_setValue(k,v);return Promise.resolve();},deleteValue:function(k){GM_deleteValue(k);return Promise.resolve();},listValues:function(){return Promise.resolve(GM_listValues());},addValueChangeListener:GM_addValueChangeListener,removeValueChangeListener:GM_removeValueChangeListener,openInTab:GM_openInTab,setClipboard:function(v){GM_setClipboard(v);return Promise.resolve();},notification:function(o){GM_notification(o);return Promise.resolve();},registerMenuCommand:GM_registerMenuCommand,unregisterMenuCommand:GM_unregisterMenuCommand,getResourceText:function(n){return Promise.resolve(GM_getResourceText(n));},getResourceUrl:function(n){return Promise.resolve(GM_getResourceURL(n));},download:GM_download,xmlHttpRequest:GM_xmlhttpRequest};\n");
    }

    private static String jsQuote(String value) {
        String input = value == null ? "" : value;
        StringBuilder out = new StringBuilder(input.length() + 2);
        out.append('"');
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c == '\b') out.append("\\b");
            else if (c == '\f') out.append("\\f");
            else if (c == '\n') out.append("\\n");
            else if (c == '\r') out.append("\\r");
            else if (c == '\t') out.append("\\t");
            else if (c < 0x20 || c == '\u2028' || c == '\u2029') {
                String hex = Integer.toHexString(c);
                out.append("\\u");
                for (int pad = hex.length(); pad < 4; pad++) out.append('0');
                out.append(hex);
            } else out.append(c);
        }
        return out.append('"').toString();
    }

    private static String escapeForSingle(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ").replace("\r", " ");
    }
    /** 大脚本压缩存储（GZIP+Base64 加前缀），避免超大字符串写入 SharedPreferences 时损坏/截断。 */
    private static final int COMPRESS_THRESHOLD = 64 * 1024;

    /** 内置 DeepSeek++ 脚本直接从 assets 读取（绕开 prefs 大脚本存储损坏问题，升级 APK 即自动生效）。 */
    private String readDsppAsset(String sourceUrl) {
        if (sourceUrl == null || appContext == null) return null;
        String assetPath = null;
        if ("asset://median/dspp-mainworld".equals(sourceUrl)) assetPath = "dspp/dspp_mainworld.js";
        else if ("asset://median/dspp-content".equals(sourceUrl)) assetPath = "dspp/dspp_content.js";
        if (assetPath == null) return null;
        try {
            InputStream in = appContext.getAssets().open(assetPath);
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return new String(out.toByteArray(), "UTF-8");
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return null;
        }
    }


    private static String compressForStore(String code) {
        if (code == null || code.length() == 0) return code == null ? "" : code;
        if (code.length() <= COMPRESS_THRESHOLD) return code;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            GZIPOutputStream gz = new GZIPOutputStream(bos);
            gz.write(code.getBytes("UTF-8"));
            gz.close();
            return "gz1:" + java.util.Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (Exception e) {
            return code;
        }
    }

    private static String decompressFromStore(String stored) {
        if (stored == null || stored.length() == 0) return "";
        if (!stored.startsWith("gz1:")) return stored;
        try {
            byte[] data = java.util.Base64.getDecoder().decode(stored.substring(4));
            GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(data));
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = gz.read(buf)) > 0) bos.write(buf, 0, n);
            gz.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
