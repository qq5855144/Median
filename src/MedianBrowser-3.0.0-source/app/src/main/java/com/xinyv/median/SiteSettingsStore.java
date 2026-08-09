package com.xinyv.median;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/** Compact per-origin preferences. A copy is returned so request threads never share mutable state. */
final class SiteSettingsStore {
    static final int INHERIT = 0;
    static final int ALLOW = 1;
    static final int BLOCK = 2;

    static final class SiteSettings {
        int javascript = INHERIT;
        int images = INHERIT;
        int thirdPartyCookies = INHERIT;
        int desktop = INHERIT;
        int dark = INHERIT;
        int popups = INHERIT;
        int autoplay = INHERIT;
        int location = INHERIT;
        int camera = INHERIT;
        int microphone = INHERIT;
        int trackingProtection = INHERIT;
        boolean compatibilityMode;
        int textZoom = 100;

        boolean isDefault() {
            return javascript == INHERIT && images == INHERIT && thirdPartyCookies == INHERIT &&
                    desktop == INHERIT && dark == INHERIT && popups == INHERIT && autoplay == INHERIT &&
                    location == INHERIT && camera == INHERIT && microphone == INHERIT &&
                    trackingProtection == INHERIT && !compatibilityMode && textZoom == 100;
        }

        SiteSettings copy() {
            SiteSettings result = new SiteSettings();
            result.javascript = javascript;
            result.images = images;
            result.thirdPartyCookies = thirdPartyCookies;
            result.desktop = desktop;
            result.dark = dark;
            result.popups = popups;
            result.autoplay = autoplay;
            result.location = location;
            result.camera = camera;
            result.microphone = microphone;
            result.trackingProtection = trackingProtection;
            result.compatibilityMode = compatibilityMode;
            result.textZoom = textZoom;
            return result;
        }
    }

    private static final String PREFS = "median_sites_v1";
    private static final String KEY = "sites";
    private static final int MAX_SITES = 2000;
    private final SharedPreferences prefs;
    private final HashMap<String, SiteSettings> cache = new HashMap<String, SiteSettings>();

    SiteSettingsStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    synchronized SiteSettings forHost(String host) {
        SiteSettings value = cache.get(normalize(host));
        return value == null ? new SiteSettings() : value.copy();
    }

    synchronized void save(String host, SiteSettings settings) {
        String key = normalize(host);
        if (!validHost(key) || settings == null) return;
        if (!cache.containsKey(key) && cache.size() >= MAX_SITES) return;
        if (settings.isDefault()) cache.remove(key); else cache.put(key, settings.copy());
        persist();
    }

    synchronized void clear(String host) {
        cache.remove(normalize(host));
        persist();
    }

    synchronized void clearAll() {
        cache.clear();
        persist();
    }

    synchronized int configuredSiteCount() { return cache.size(); }

    synchronized String exportJson() { return prefs.getString(KEY, "{}"); }

    synchronized void importJson(String raw) throws Exception {
        if (raw == null || raw.length() > 1024 * 1024) throw new IllegalArgumentException("网站设置超过限制");
        new JSONObject(raw);
        prefs.edit().putString(KEY, raw).commit();
        load();
    }

    private void load() {
        cache.clear();
        try {
            JSONObject root = new JSONObject(prefs.getString(KEY, "{}"));
            Iterator<String> names = root.keys();
            while (names.hasNext() && cache.size() < MAX_SITES) {
                String rawHost = names.next();
                String host = normalize(rawHost);
                JSONObject value = root.optJSONObject(rawHost);
                if (!validHost(host) || value == null) continue;
                SiteSettings settings = new SiteSettings();
                settings.javascript = clamp(value.optInt("javascript", INHERIT), INHERIT, BLOCK);
                settings.images = clamp(value.optInt("images", INHERIT), INHERIT, BLOCK);
                settings.thirdPartyCookies = clamp(value.optInt("thirdPartyCookies", INHERIT), INHERIT, BLOCK);
                settings.desktop = clamp(value.optInt("desktop", INHERIT), INHERIT, BLOCK);
                settings.dark = clamp(value.optInt("dark", INHERIT), INHERIT, BLOCK);
                settings.popups = clamp(value.optInt("popups", INHERIT), INHERIT, BLOCK);
                settings.autoplay = clamp(value.optInt("autoplay", INHERIT), INHERIT, BLOCK);
                settings.location = clamp(value.optInt("location", INHERIT), INHERIT, BLOCK);
                settings.camera = clamp(value.optInt("camera", INHERIT), INHERIT, BLOCK);
                settings.microphone = clamp(value.optInt("microphone", INHERIT), INHERIT, BLOCK);
                settings.trackingProtection = clamp(value.optInt("trackingProtection", INHERIT), INHERIT, BLOCK);
                settings.compatibilityMode = value.optBoolean("compatibilityMode", false);
                settings.textZoom = clamp(value.optInt("textZoom", 100), 50, 200);
                if (!settings.isDefault()) cache.put(host, settings);
            }
        } catch (Exception ignored) { cache.clear(); }
    }

    private void persist() {
        JSONObject root = new JSONObject();
        try {
            for (Map.Entry<String, SiteSettings> entry : cache.entrySet()) {
                SiteSettings settings = entry.getValue();
                JSONObject value = new JSONObject();
                value.put("javascript", settings.javascript);
                value.put("images", settings.images);
                value.put("thirdPartyCookies", settings.thirdPartyCookies);
                value.put("desktop", settings.desktop);
                value.put("dark", settings.dark);
                value.put("popups", settings.popups);
                value.put("autoplay", settings.autoplay);
                value.put("location", settings.location);
                value.put("camera", settings.camera);
                value.put("microphone", settings.microphone);
                value.put("trackingProtection", settings.trackingProtection);
                value.put("compatibilityMode", settings.compatibilityMode);
                value.put("textZoom", settings.textZoom);
                root.put(entry.getKey(), value);
            }
        } catch (Exception ignored) {}
        prefs.edit().putString(KEY, root.toString()).apply();
    }

    private static String normalize(String host) {
        if (host == null) return "";
        String value = host.trim().toLowerCase(Locale.US);
        while (value.endsWith(".")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private static boolean validHost(String host) {
        return host != null && host.length() > 0 && host.length() <= 253 && host.matches("[a-z0-9._:-]+");
    }

    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}
