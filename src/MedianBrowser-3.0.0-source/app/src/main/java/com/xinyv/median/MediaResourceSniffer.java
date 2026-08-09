package com.xinyv.median;

import android.net.Uri;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** Bounded, metadata-only media URL index populated from requests and DOM media elements. */
final class MediaResourceSniffer {
    static final class Resource {
        String url;
        String mime;
        String kind;
        String pageHost;
        long seenAt;
    }

    private static final int MAX_ITEMS = 80;
    private final LinkedHashMap<String, Resource> items = new LinkedHashMap<String, Resource>();
    private String pageUrl = "";

    synchronized void beginPage(String url) {
        String next = value(url);
        if (next.equals(pageUrl)) return;
        pageUrl = next;
        items.clear();
    }

    void observe(Uri uri, String declaredMime, String pageHost) {
        if (uri == null) return;
        String rawPath = value(uri.getPath());
        String rawMime = value(declaredMime);
        if (!looksLikeMedia(rawPath, rawMime)) return;
        String path = rawPath.toLowerCase(Locale.US);
        String mime = rawMime.toLowerCase(Locale.US);
        String kind = kindOf(path, mime);
        if (kind.length() == 0) return;
        addMedia(uri.toString(), path, mime, kind, pageHost);
    }

    void observe(String url, String mime, String pageHost) {
        if (url == null || url.length() > 8192 || (!url.startsWith("https://") && !url.startsWith("http://"))) return;
        String lower = url.toLowerCase(Locale.US);
        String normalizedMime = value(mime).toLowerCase(Locale.US);
        String kind = kindOf(lower, normalizedMime);
        if (kind.length() == 0) return;
        addMedia(url, lower, normalizedMime, kind, pageHost);
    }

    synchronized List<Resource> getAll() {
        ArrayList<Resource> result = new ArrayList<Resource>();
        for (Resource source : items.values()) result.add(copy(source));
        return result;
    }

    synchronized int size() { return items.size(); }

    private void addMedia(String url, String normalizedPath, String mime, String kind, String pageHost) {
        synchronized (this) {
            Resource existing = items.remove(url);
            Resource item = existing == null ? new Resource() : existing;
            item.url = url;
            item.kind = kind;
            item.mime = normalizedMime(normalizedPath, mime, kind);
            item.pageHost = value(pageHost);
            item.seenAt = System.currentTimeMillis();
            items.put(url, item);
            while (items.size() > MAX_ITEMS) {
                String first = items.keySet().iterator().next();
                items.remove(first);
            }
        }
    }

    private static String kindOf(String url, String mime) {
        if (mime.contains("application/vnd.apple.mpegurl") || mime.contains("application/x-mpegurl") || hasExtension(url, ".m3u8")) return "HLS 流";
        if (mime.contains("application/dash+xml") || hasExtension(url, ".mpd")) return "DASH 流";
        if (mime.contains("video/") || hasAny(url, ".mp4", ".webm", ".mkv", ".mov", ".m4v")) return "视频";
        if (mime.contains("audio/") || hasAny(url, ".mp3", ".m4a", ".aac", ".ogg", ".opus", ".flac", ".wav")) return "音频";
        return "";
    }

    private static boolean looksLikeMedia(String path, String mime) {
        if (containsIgnoreCase(mime, "video/") || containsIgnoreCase(mime, "audio/") ||
                containsIgnoreCase(mime, "mpegurl") || containsIgnoreCase(mime, "dash+xml")) return true;
        return hasExtensionIgnoreCase(path, ".m3u8") || hasExtensionIgnoreCase(path, ".mpd") ||
                hasExtensionIgnoreCase(path, ".mp4") || hasExtensionIgnoreCase(path, ".webm") ||
                hasExtensionIgnoreCase(path, ".mkv") || hasExtensionIgnoreCase(path, ".mov") ||
                hasExtensionIgnoreCase(path, ".m4v") || hasExtensionIgnoreCase(path, ".mp3") ||
                hasExtensionIgnoreCase(path, ".m4a") || hasExtensionIgnoreCase(path, ".aac") ||
                hasExtensionIgnoreCase(path, ".ogg") || hasExtensionIgnoreCase(path, ".opus") ||
                hasExtensionIgnoreCase(path, ".flac") || hasExtensionIgnoreCase(path, ".wav");
    }

    private static boolean hasExtensionIgnoreCase(String value, String extension) {
        int end = value.length();
        if (end >= extension.length() && value.regionMatches(true, end - extension.length(), extension, 0, extension.length())) return true;
        String nested = extension + "/";
        for (int i = 0; i <= value.length() - nested.length(); i++) {
            if (value.regionMatches(true, i, nested, 0, nested.length())) return true;
        }
        return false;
    }

    private static boolean containsIgnoreCase(String value, String needle) {
        for (int i = 0; i <= value.length() - needle.length(); i++) {
            if (value.regionMatches(true, i, needle, 0, needle.length())) return true;
        }
        return false;
    }

    private static String normalizedMime(String url, String mime, String kind) {
        int comma = mime.indexOf(',');
        if (comma >= 0) mime = mime.substring(0, comma);
        if (mime.startsWith("video/") || mime.startsWith("audio/") || mime.startsWith("application/")) return mime;
        if ("HLS 流".equals(kind)) return "application/vnd.apple.mpegurl";
        if ("DASH 流".equals(kind)) return "application/dash+xml";
        if (hasExtension(url, ".mp3")) return "audio/mpeg";
        if (hasExtension(url, ".m4a")) return "audio/mp4";
        if (hasExtension(url, ".webm")) return "video/webm";
        return "视频".equals(kind) ? "video/*" : "audio/*";
    }

    private static boolean hasAny(String url, String... extensions) {
        for (String extension : extensions) if (hasExtension(url, extension)) return true;
        return false;
    }

    private static boolean hasExtension(String url, String extension) {
        int query = url.indexOf('?');
        String path = query < 0 ? url : url.substring(0, query);
        return path.endsWith(extension) || path.contains(extension + "/");
    }

    private static Resource copy(Resource source) {
        Resource item = new Resource();
        item.url = source.url;
        item.mime = source.mime;
        item.kind = source.kind;
        item.pageHost = source.pageHost;
        item.seenAt = source.seenAt;
        return item;
    }

    private static String value(String value) { return value == null ? "" : value; }
}
