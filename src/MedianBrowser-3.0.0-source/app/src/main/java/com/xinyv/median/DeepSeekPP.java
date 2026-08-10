package com.xinyv.median;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * DeepSeek++ 移动端适配（方案 C 深度集成）。
 *
 * 原理：把 DeepSeek++（WXT/MV3 浏览器扩展）的 content scripts 转为 Median
 * 用户脚本预置进 assets，通过本类一键安装/卸载到 UserScriptStore：
 *   - dspp_mainworld.js : 主世界拦截器（fetch 拦截、请求增强、流式工具调用解析）
 *   - dspp_content.js   : 侧边栏与工具卡片 UI
 * 两者头部均带 chrome.* -> localStorage 的移动端 shim（无 background 时降级）。
 */
final class DeepSeekPP {

    static final String PREFS = "median_dspp";
    static final String PREFS_KEY = "deepseek_pp_enabled";
    private static final String[] ASSET_NAMES = {"dspp_mainworld.js", "dspp_content.js"};
    private static final String[] SOURCE_URLS = {"asset://median/dspp-mainworld", "asset://median/dspp-content"};

    private DeepSeekPP() {}

    static boolean isEnabled(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return p.getBoolean(PREFS_KEY, false);
    }

    static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(PREFS_KEY, enabled).apply();
    }

    /** 从 assets 安装两个内置脚本（可重复调用，幂等）。返回是否全部成功。 */
    static boolean install(Context context, UserScriptStore store) throws Exception {
        if (context == null || store == null) return false;
        List<UserScriptStore.Script> scripts = new ArrayList<UserScriptStore.Script>();
        for (int i = 0; i < ASSET_NAMES.length; i++) {
            String code = readAsset(context, "dspp/" + ASSET_NAMES[i]);
            if (code == null || code.length() == 0) continue;
            UserScriptStore.Script script = store.parseUserScript(code, SOURCE_URLS[i]);
            script.enabled = true;
            scripts.add(script);
        }
        if (scripts.size() == 0) return false;
        store.saveBatch(scripts);
        for (UserScriptStore.Script script : scripts) store.setEnabled(script.id, true);
        return true;
    }

    /** 卸载全部内置脚本（按 sourceUrl 匹配，幂等）。 */
    static void uninstall(UserScriptStore store) {
        if (store == null) return;
        for (String sourceUrl : SOURCE_URLS) {
            for (UserScriptStore.Script script : store.getAll()) {
                if (sourceUrl.equals(script.sourceUrl)) {
                    store.delete(script.id);
                    break;
                }
            }
        }
    }

    private static String readAsset(Context context, String name) {
        try {
            InputStream in = context.getAssets().open(name);
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            } finally {
                in.close();
            }
        } catch (Exception ignored) {
            return null;
        }
    }
}
