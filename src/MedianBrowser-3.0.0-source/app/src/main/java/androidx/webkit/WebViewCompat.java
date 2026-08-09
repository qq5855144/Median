/*
 * Copyright 2018 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0.
 */
package androidx.webkit;

import android.net.Uri;
import android.webkit.WebView;

import androidx.webkit.internal.WebViewGlueCommunicator;

import java.util.LinkedHashSet;
import java.util.Set;

/** Focused AndroidX-compatible facade for the document-start API used by Median. */
public final class WebViewCompat {
    private WebViewCompat() {}

    public static ScriptHandler addDocumentStartJavaScript(
            WebView webView, String script, Set<String> allowedOriginRules) {
        if (webView == null) throw new NullPointerException("webView");
        if (script == null) throw new NullPointerException("script");
        if (script.length() == 0) throw new IllegalArgumentException("script must not be empty");
        String[] rules = normalizeOriginRules(allowedOriginRules);
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            throw new UnsupportedOperationException(
                    "DOCUMENT_START_SCRIPT is not supported by this WebView provider");
        }
        return WebViewGlueCommunicator.addDocumentStartJavaScript(webView, script, rules);
    }

    public static String getDocumentStartDiagnosticReport() {
        return WebViewGlueCommunicator.diagnostic().compactReport();
    }

    public static void refreshWebViewProvider() {
        WebViewGlueCommunicator.invalidate();
    }

    private static String[] normalizeOriginRules(Set<String> originRules) {
        if (originRules == null || originRules.isEmpty()) {
            throw new IllegalArgumentException("allowedOriginRules must not be empty");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<String>();
        for (String rule : originRules) {
            if (rule == null) throw new IllegalArgumentException("origin rule must not be null");
            String value = rule.trim();
            if (value.length() == 0) throw new IllegalArgumentException("origin rule must not be empty");
            if ("*".equals(value)) {
                normalized.add(value);
                continue;
            }
            if (value.indexOf('/') >= 0 && !value.contains("://")) {
                throw new IllegalArgumentException("origin rule requires a scheme: " + value);
            }
            Uri uri = Uri.parse(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null || host.length() == 0
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || (uri.getPath() != null && uri.getPath().length() > 0)) {
                throw new IllegalArgumentException("invalid origin rule: " + value);
            }
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("unsupported origin scheme: " + scheme);
            }
            if (host.startsWith("*.") && host.length() <= 2) {
                throw new IllegalArgumentException("invalid wildcard host: " + value);
            }
            normalized.add(value);
        }
        return normalized.toArray(new String[0]);
    }
}
