package com.xinyv.median;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** GitHub REST API 调用（HttpURLConnection，零第三方依赖）。Token 存 median_mcp_v1/github_token。 */
public class GithubApi {
    private static final String API = "https://api.github.com";
    private static final int TIMEOUT = 20000;

    private GithubApi() { }

    static String token(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences("median_mcp_v1", Context.MODE_PRIVATE);
        return prefs.getString("github_token", "");
    }

    /** 通用请求：GET/POST/PATCH/PUT/DELETE，返回 JSON。失败抛异常。 */
    static JSONObject request(Context ctx, String method, String path, JSONObject body) throws Exception {
        String token = token(ctx);
        if (token.isEmpty()) throw new Exception("未配置 GitHub Token：MCP 面板 → GitHub Token 设置");
        HttpURLConnection conn = (HttpURLConnection) new URL(API + path).openConnection();
        try {
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);
            conn.setRequestMethod(method);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("User-Agent", "Median-GithubMcp/1.0");
            if (body != null) {
                byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                OutputStream os = conn.getOutputStream();
                os.write(data);
                os.flush();
                os.close();
            }
            int code = conn.getResponseCode();
            String resp = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code >= 200 && code < 300) {
                if (resp == null || resp.isEmpty()) return new JSONObject().put("ok", true);
                return new JSONObject(resp);
            }
            throw new Exception("GitHub API " + code + ": " + brief(resp));
        } finally {
            conn.disconnect();
        }
    }

    /** GET 列表请求：返回 JSONArray（部分接口返回数组）。 */
    static JSONArray requestArray(Context ctx, String method, String path) throws Exception {
        String token = token(ctx);
        if (token.isEmpty()) throw new Exception("未配置 GitHub Token：MCP 面板 → GitHub Token 设置");
        HttpURLConnection conn = (HttpURLConnection) new URL(API + path).openConnection();
        try {
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);
            conn.setRequestMethod(method);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("User-Agent", "Median-GithubMcp/1.0");
            int code = conn.getResponseCode();
            String resp = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code >= 200 && code < 300) {
                return new JSONArray(resp);
            }
            throw new Exception("GitHub API " + code + ": " + brief(resp));
        } finally {
            conn.disconnect();
        }
    }

    /** URL 编码查询参数。 */
    static String qs(Map<String, String> params) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) continue;
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), "UTF-8")).append('=')
              .append(URLEncoder.encode(e.getValue(), "UTF-8"));
        }
        return sb.length() > 0 ? "?" + sb : "";
    }

    static String encode(String s) throws Exception {
        return s == null ? "" : URLEncoder.encode(s, "UTF-8").replace("+", "%20");
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String brief(String s) {
        if (s == null || s.isEmpty()) return "empty response";
        String t = s.replace("\n", " ").trim();
        return t.length() > 200 ? t.substring(0, 200) : t;
    }
}