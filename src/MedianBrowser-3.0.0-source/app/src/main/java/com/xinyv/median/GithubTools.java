package com.xinyv.median;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** GitHub 工具：21 个内置工具定义与执行（零依赖，Token 见 GithubApi）。返回 {"result":{...}} 与本地工具一致。 */
public class GithubTools {
    private GithubTools() { }

    private static JSONObject prop(String type, String desc) throws Exception {
        JSONObject p = new JSONObject();
        p.put("type", type);
        if (desc != null) p.put("description", desc);
        return p;
    }

    private static JSONObject tool(String name, String desc, JSONObject schema, String[] req) throws Exception {
        JSONObject t = new JSONObject();
        t.put("name", name).put("description", desc);
        JSONObject is = new JSONObject();
        is.put("type", "object");
        is.put("properties", schema == null ? new JSONObject() : schema);
        is.put("additionalProperties", false);
        if (req != null && req.length > 0) is.put("required", new JSONArray(req));
        t.put("inputSchema", is);
        return t;
    }

    /** 21 个 GitHub 工具定义。 */
    public static JSONArray toolDefinitions() throws Exception {
        JSONArray tools = new JSONArray();
        tools.put(tool("github_get_me", "获取当前 GitHub 账号信息（需已配置 Token）", new JSONObject(), null));
        tools.put(tool("github_search_repositories", "搜索仓库（如 language:java stars:>100）", new JSONObject()
                .put("q", prop("string", "搜索关键字")).put("per_page", prop("number", "数量，默认10")), new String[]{"q"}));
        tools.put(tool("github_list_repositories", "列出当前账号仓库", new JSONObject()
                .put("per_page", prop("number", "数量，默认10")), null));
        tools.put(tool("github_get_repository", "获取仓库详情", new JSONObject()
                .put("owner", prop("string", "所有者")).put("repo", prop("string", "仓库名")), new String[]{"owner", "repo"}));
        tools.put(tool("github_create_repository", "创建仓库", new JSONObject()
                .put("name", prop("string", "仓库名")).put("description", prop("string", "描述（可选）"))
                .put("private", prop("boolean", "是否私有，默认false")), new String[]{"name"}));
        tools.put(tool("github_fork_repository", "Fork 仓库", new JSONObject()
                .put("owner", prop("string", "所有者")).put("repo", prop("string", "仓库名")), new String[]{"owner", "repo"}));
        tools.put(tool("github_get_issue", "获取 Issue 详情", new JSONObject()
                .put("owner", prop("string", "所有者")).put("repo", prop("string", "仓库名")).put("number", prop("number", "Issue 编号")), new String[]{"owner", "repo", "number"}));
        tools.put(tool("github_create_issue", "创建 Issue", new JSONObject()
                .put("owner", prop("string", "所有者")).put("repo", prop("string", "仓库名"))
                .put("title", prop("string", "标题")).put("body", prop("string", "内容（可选）")), new String[]{"owner", "repo", "title"}));
        tools.put(tool("github_update_issue", "更新 Issue（标题/内容/状态）", new JSONObject()
                .put("owner", prop("string", "所有者")).put("repo", prop("string", "仓库名")).put("number", prop("number", "Issue 编号"))
                .put("title", prop("string", "新标题（可选）")).put("body", prop("string", "新内容（可选）"))
                .put("state", prop("string", "open 或 closed")), new String[]{"owner", "repo", "number"}));
        tools.put(tool("github_list_issues", "列出 Issue", new JSONObject()
                .put("owner", prop("string", "所有者")).put("repo", prop("string", "仓库名"))
                .put("state", prop("string", "open/closed/all，默认 open")), new String[]{"owner", "repo"}));
        tools.put(tool("github_search_issues", "搜索 Issue/PR", new JSONObject()
                .put("q", prop("string", "搜索关键字")), new String[]{"q"}));
        tools.put(tool("github_get_pull_request", "获取 PR 详情", new JSONObject()
                .put("owner", prop("string", "所有者")).put("repo", prop("string", "仓库名")).put("number", prop("number", "PR 编号")), new String[]{"owner", "repo", "number"}));
        tools.put(tool("github_create_pull_request", "创建 PR", new JSONObject()
                .put("owner", prop("string", "所有者")).put("repo", prop("string", "仓库名"))
                .put("title", prop("string", "标题")).put("head", prop("string", "源分支"))
                .put("base", prop("string", "目标分支")).put("body", prop("string", "描述（可选）")), new String[]{"owner", "repo", "title", "head", "base"}));
        tools.put(tool("github_update_pull_request", "更新 PR（标题/内容/状态）", new JSONObject()
                .put("owner", prop("string", "所有者")).put("repo", prop("string", "仓库名")).put("number", prop("number", "PR 编号"))
                .put("title", prop("string", "新标题（可选）")).put("body", prop("string", "新描述（可选）"))
                .put("state", prop("string", "open 或 closed")), new String[]{"owner", "repo", "number"}));
        tools.put(tool("github_list_pull_requests", "列出 PR", new JSONObject()
                .put("owner", prop("string", "所有者")).put("repo", prop("string", "仓库名"))
                .put("state", prop("string", "open/closed/all，默认 open")), new String[]{"owner", "repo"}));
        tools.put(tool("github_merge_pull_request", "合并 PR", new JSONObject()
                .put("owner", prop("string", "所有者")).put("repo", prop("string", "仓库名")).put("number", prop("number", "PR 编号"))
                .put("merge_method", prop("string", "merge/squash/rebase，可选")), new String[]{"owner", "repo", "number"}));
        tools.put(tool("github_get_file_contents", "获取仓库文件内容（base64）", new JSONObject()
                .put("owner", prop("string", "所有者")).put("repo", prop("string", "仓库名"))
                .put("path", prop("string", "文件路径")).put("ref", prop("string", "分支/提交（可选）")), new String[]{"owner", "repo", "path"}));
        tools.put(tool("github_create_or_update_file", "创建或更新文件（提交）", new JSONObject()
                .put("owner", prop("string", "所有者")).put("repo", prop("string", "仓库名"))
                .put("path", prop("string", "文件路径")).put("content", prop("string", "文件内容（UTF-8）"))
                .put("message", prop("string", "提交信息")).put("branch", prop("string", "分支（可选）")), new String[]{"owner", "repo", "path", "content", "message"}));
        tools.put(tool("github_list_commits", "列出提交记录", new JSONObject()
                .put("owner", prop("string", "所有者")).put("repo", prop("string", "仓库名"))
                .put("per_page", prop("number", "数量，默认10")), new String[]{"owner", "repo"}));
        tools.put(tool("github_list_branches", "列出分支", new JSONObject()
                .put("owner", prop("string", "所有者")).put("repo", prop("string", "仓库名")), new String[]{"owner", "repo"}));
        tools.put(tool("github_create_branch", "从指定分支创建新分支", new JSONObject()
                .put("owner", prop("string", "所有者")).put("repo", prop("string", "仓库名"))
                .put("branch", prop("string", "新分支名")).put("from", prop("string", "源分支，默认 main")), new String[]{"owner", "repo", "branch"}));
        return tools;
    }

    /** 工具执行入口。返回 {"result":{...}}。 */
    public static JSONObject call(Context ctx, String name, JSONObject a) {
        try {
            JSONObject r;
            String owner = a.optString("owner", "");
            String repo = a.optString("repo", "");
            if ("github_get_me".equals(name)) {
                r = GithubApi.request(ctx, "GET", "/user", null);
            } else if ("github_search_repositories".equals(name)) {
                Map<String, String> q = new LinkedHashMap<String, String>();
                q.put("q", a.optString("q", ""));
                q.put("per_page", a.has("per_page") ? String.valueOf(a.optInt("per_page", 10)) : "");
                JSONArray arr = GithubApi.requestArray(ctx, "GET", "/search/repositories" + GithubApi.qs(q));
                return result(trimArray(arr, new String[]{"full_name", "html_url", "description", "stargazers_count", "language", "fork"}));
            } else if ("github_list_repositories".equals(name)) {
                Map<String, String> q = new LinkedHashMap<String, String>();
                q.put("per_page", a.has("per_page") ? String.valueOf(a.optInt("per_page", 10)) : "");
                JSONArray arr = GithubApi.requestArray(ctx, "GET", "/user/repos" + GithubApi.qs(q));
                return result(trimArray(arr, new String[]{"full_name", "html_url", "private", "description", "language", "updated_at"}));
            } else if ("github_get_repository".equals(name)) {
                r = GithubApi.request(ctx, "GET", "/repos/" + GithubApi.encode(owner) + "/" + GithubApi.encode(repo), null);
            } else if ("github_create_repository".equals(name)) {
                JSONObject body = new JSONObject().put("name", a.optString("name", ""));
                if (a.has("description")) body.put("description", a.optString("description"));
                body.put("private", a.optBoolean("private", false));
                r = GithubApi.request(ctx, "POST", "/user/repos", body);
            } else if ("github_fork_repository".equals(name)) {
                r = GithubApi.request(ctx, "POST", "/repos/" + GithubApi.encode(owner) + "/" + GithubApi.encode(repo) + "/forks", null);
            } else if ("github_get_issue".equals(name)) {
                r = GithubApi.request(ctx, "GET", "/repos/" + GithubApi.encode(owner) + "/" + GithubApi.encode(repo) + "/issues/" + a.optInt("number", 0), null);
            } else if ("github_create_issue".equals(name)) {
                JSONObject body = new JSONObject().put("title", a.optString("title", ""));
                if (a.has("body")) body.put("body", a.optString("body"));
                r = GithubApi.request(ctx, "POST", "/repos/" + GithubApi.encode(owner) + "/" + GithubApi.encode(repo) + "/issues", body);
            } else if ("github_update_issue".equals(name)) {
                JSONObject body = new JSONObject();
                if (a.has("title")) body.put("title", a.optString("title"));
                if (a.has("body")) body.put("body", a.optString("body"));
                if (a.has("state")) body.put("state", a.optString("state"));
                r = GithubApi.request(ctx, "PATCH", "/repos/" + GithubApi.encode(owner) + "/" + GithubApi.encode(repo) + "/issues/" + a.optInt("number", 0), body);
            } else if ("github_list_issues".equals(name)) {
                Map<String, String> q = new LinkedHashMap<String, String>();
                q.put("state", a.optString("state", "open"));
                JSONArray arr = GithubApi.requestArray(ctx, "GET", "/repos/" + GithubApi.encode(owner) + "/" + GithubApi.encode(repo) + "/issues" + GithubApi.qs(q));
                return result(trimArray(arr, new String[]{"number", "title", "state", "html_url", "user"}));
            } else if ("github_search_issues".equals(name)) {
                Map<String, String> q = new LinkedHashMap<String, String>();
                q.put("q", a.optString("q", ""));
                JSONObject o = GithubApi.request(ctx, "GET", "/search/issues" + GithubApi.qs(q), null);
                return result(trimArray(o.optJSONArray("items"), new String[]{"number", "title", "state", "html_url", "repository_url"}));
            } else if ("github_get_pull_request".equals(name)) {
                r = GithubApi.request(ctx, "GET", "/repos/" + GithubApi.encode(owner) + "/" + GithubApi.encode(repo) + "/pulls/" + a.optInt("number", 0), null);
            } else if ("github_create_pull_request".equals(name)) {
                JSONObject body = new JSONObject()
                        .put("title", a.optString("title", "")).put("head", a.optString("head", ""))
                        .put("base", a.optString("base", ""));
                if (a.has("body")) body.put("body", a.optString("body"));
                r = GithubApi.request(ctx, "POST", "/repos/" + GithubApi.encode(owner) + "/" + GithubApi.encode(repo) + "/pulls", body);
            } else if ("github_update_pull_request".equals(name)) {
                JSONObject body = new JSONObject();
                if (a.has("title")) body.put("title", a.optString("title"));
                if (a.has("body")) body.put("body", a.optString("body"));
                if (a.has("state")) body.put("state", a.optString("state"));
                r = GithubApi.request(ctx, "PATCH", "/repos/" + GithubApi.encode(owner) + "/" + GithubApi.encode(repo) + "/pulls/" + a.optInt("number", 0), body);
            } else if ("github_list_pull_requests".equals(name)) {
                Map<String, String> q = new LinkedHashMap<String, String>();
                q.put("state", a.optString("state", "open"));
                JSONArray arr = GithubApi.requestArray(ctx, "GET", "/repos/" + GithubApi.encode(owner) + "/" + GithubApi.encode(repo) + "/pulls" + GithubApi.qs(q));
                return result(trimArray(arr, new String[]{"number", "title", "state", "html_url", "user"}));
            } else if ("github_merge_pull_request".equals(name)) {
                JSONObject body = new JSONObject();
                if (a.has("merge_method")) body.put("merge_method", a.optString("merge_method"));
                r = GithubApi.request(ctx, "PUT", "/repos/" + GithubApi.encode(owner) + "/" + GithubApi.encode(repo) + "/pulls/" + a.optInt("number", 0) + "/merge", body);
            } else if ("github_get_file_contents".equals(name)) {
                StringBuilder p = new StringBuilder("/repos/" + GithubApi.encode(owner) + "/" + GithubApi.encode(repo) + "/contents/" + GithubApi.encode(a.optString("path", "")));
                if (a.has("ref")) p.append("?ref=").append(GithubApi.encode(a.optString("ref")));
                r = GithubApi.request(ctx, "GET", p.toString(), null);
            } else if ("github_create_or_update_file".equals(name)) {
                String path = a.optString("path", "");
                String content = Base64.encodeToString(a.optString("content", "").getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                JSONObject body = new JSONObject().put("message", a.optString("message", "update via Median")).put("content", content);
                if (a.has("branch")) body.put("branch", a.optString("branch"));
                try {
                    JSONObject old = GithubApi.request(ctx, "GET", "/repos/" + GithubApi.encode(owner) + "/" + GithubApi.encode(repo) + "/contents/" + GithubApi.encode(path), null);
                    if (old.has("sha")) body.put("sha", old.optString("sha"));
                } catch (Exception ignored) { }
                r = GithubApi.request(ctx, "PUT", "/repos/" + GithubApi.encode(owner) + "/" + GithubApi.encode(repo) + "/contents/" + GithubApi.encode(path), body);
            } else if ("github_list_commits".equals(name)) {
                Map<String, String> q = new LinkedHashMap<String, String>();
                q.put("per_page", a.has("per_page") ? String.valueOf(a.optInt("per_page", 10)) : "");
                JSONArray arr = GithubApi.requestArray(ctx, "GET", "/repos/" + GithubApi.encode(owner) + "/" + GithubApi.encode(repo) + "/commits" + GithubApi.qs(q));
                JSONArray out = new JSONArray();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject c = arr.optJSONObject(i);
                    if (c == null) continue;
                    JSONObject o = new JSONObject();
                    String sha = c.optString("sha", "");
                    o.put("sha", sha.substring(0, Math.min(7, sha.length())));
                    JSONObject cm = c.optJSONObject("commit");
                    if (cm != null) {
                        o.put("message", cm.optString("message", ""));
                        JSONObject au = cm.optJSONObject("author");
                        if (au != null) o.put("author", au.optString("name", ""));
                    }
                    out.put(o);
                }
                return result(out.toString());
            } else if ("github_list_branches".equals(name)) {
                JSONArray arr = GithubApi.requestArray(ctx, "GET", "/repos/" + GithubApi.encode(owner) + "/" + GithubApi.encode(repo) + "/branches");
                JSONArray out = new JSONArray();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject b = arr.optJSONObject(i);
                    if (b == null) continue;
                    out.put(b.optString("name", ""));
                }
                return result(out.toString());
            } else if ("github_create_branch".equals(name)) {
                String from = a.optString("from", "main");
                JSONObject src = GithubApi.request(ctx, "GET", "/repos/" + GithubApi.encode(owner) + "/" + GithubApi.encode(repo) + "/git/ref/heads/" + GithubApi.encode(from), null);
                JSONObject obj = src.optJSONObject("object");
                JSONObject body = new JSONObject().put("ref", "refs/heads/" + a.optString("branch", ""))
                        .put("sha", obj == null ? "" : obj.optString("sha", ""));
                r = GithubApi.request(ctx, "POST", "/repos/" + GithubApi.encode(owner) + "/" + GithubApi.encode(repo) + "/git/refs", body);
            } else {
                return error("unknown tool: " + name);
            }
            return result(r.toString());
        } catch (Exception e) {
            return error(e.getMessage() == null ? "error" : e.getMessage());
        }
    }

    private static JSONObject result(String data) {
        try {
            return new JSONObject().put("result", new JSONObject().put("ok", true).put("data", data));
        } catch (Exception e) {
            return error("result encode failed");
        }
    }

    private static JSONObject error(String msg) {
        try {
            return new JSONObject().put("result", new JSONObject().put("ok", false).put("error", msg));
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    /** 数组精简：只保留关键字段，避免响应过大。 */
    private static String trimArray(JSONArray arr, String[] keep) {
        if (arr == null) return "[]";
        JSONArray out = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject src = arr.optJSONObject(i);
            if (src == null) { out.put(arr.opt(i)); continue; }
            JSONObject o = new JSONObject();
            for (String k : keep) {
                if (src.has(k)) {
                    try { o.put(k, src.get(k)); } catch (Exception ignored) { }
                }
            }
            out.put(o);
        }
        return out.toString();
    }
}