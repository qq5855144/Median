/*
 * Copyright 2018 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0.
 */
package androidx.webkit.internal;

import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Looper;
import android.webkit.WebView;

import androidx.webkit.ScriptHandler;

import org.chromium.support_lib_boundary.ScriptHandlerBoundaryInterface;
import org.chromium.support_lib_boundary.WebViewProviderBoundaryInterface;
import org.chromium.support_lib_boundary.WebViewProviderFactoryBoundaryInterface;
import org.chromium.support_lib_boundary.util.BoundaryInterfaceReflectionUtil;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Focused AndroidX WebKit glue with explicit diagnostics and provider refresh handling.
 *
 * This class deliberately exposes only the document-start surface Median uses, while following
 * the same Chromium support-library boundary used by AndroidX WebKit. Failure is always closed:
 * native userscript capabilities are not installed when the provider cannot be verified.
 */
public final class WebViewGlueCommunicator {
    private static final String GLUE_CLASS =
            "org.chromium.support_lib_glue.SupportLibReflectionUtil";
    private static final String GLUE_METHOD = "createWebViewProviderFactory";
    private static final String FEATURE_DOCUMENT_START = "DOCUMENT_START_SCRIPT:1";
    private static volatile Snapshot snapshot;
    private static volatile Diagnostic lastDiagnostic = Diagnostic.notLoaded();

    private WebViewGlueCommunicator() {}

    public static boolean isFeatureSupported(String feature) {
        Snapshot current = getSnapshot();
        boolean supported = current.factory != null
                && BoundaryInterfaceReflectionUtil.containsFeature(current.features, feature);
        if (!supported && FEATURE_DOCUMENT_START.equals(feature)
                && current.factory != null
                && current.features.contains("DOCUMENT_START_SCRIPT")) {
            // A small number of vendor WebViews shipped the pre-versioned feature name.
            supported = true;
            lastDiagnostic = current.diagnostic.withCompatibilityAlias(true);
        }
        return supported;
    }

    public static ScriptHandler addDocumentStartJavaScript(
            WebView webView, String script, String[] allowedOriginRules) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("WebView document-start registration must run on the UI thread");
        }
        Snapshot current = getSnapshot();
        if (current.factory == null) {
            throw new UnsupportedOperationException("WebView support-library glue unavailable: "
                    + current.diagnostic.reasonCode);
        }
        try {
            InvocationHandler providerHandler = current.factory.createWebView(webView);
            WebViewProviderBoundaryInterface provider =
                    BoundaryInterfaceReflectionUtil.castToSuppLibClass(
                            WebViewProviderBoundaryInterface.class, providerHandler);
            if (provider == null) {
                updateFailure(current, "provider-null", null);
                throw new UnsupportedOperationException("WebView provider unavailable");
            }
            InvocationHandler scriptHandler = provider.addDocumentStartJavaScript(
                    script, allowedOriginRules.clone());
            ScriptHandlerBoundaryInterface boundary =
                    BoundaryInterfaceReflectionUtil.castToSuppLibClass(
                            ScriptHandlerBoundaryInterface.class, scriptHandler);
            if (boundary == null) {
                updateFailure(current, "script-handler-null", null);
                throw new UnsupportedOperationException("Document-start script handle unavailable");
            }
            lastDiagnostic = current.diagnostic.withRegistrationSuccess();
            return new ScriptHandlerImpl(boundary);
        } catch (RuntimeException error) {
            updateFailure(current, "provider-call-" + simpleName(error), error);
            throw error;
        } catch (LinkageError error) {
            updateFailure(current, "provider-linkage-" + simpleName(error), error);
            throw new UnsupportedOperationException("WebView provider linkage failed", error);
        }
    }

    public static Diagnostic diagnostic() {
        getSnapshot();
        return lastDiagnostic;
    }

    public static void invalidate() {
        synchronized (WebViewGlueCommunicator.class) {
            snapshot = null;
            lastDiagnostic = Diagnostic.notLoaded();
        }
    }

    private static Snapshot getSnapshot() {
        Identity identity = currentWebViewIdentity();
        Snapshot current = snapshot;
        if (current != null && current.identity.equals(identity)) return current;
        synchronized (WebViewGlueCommunicator.class) {
            current = snapshot;
            if (current == null || !current.identity.equals(identity)) {
                snapshot = current = load(identity);
                lastDiagnostic = current.diagnostic;
            }
        }
        return current;
    }

    private static Snapshot load(Identity identity) {
        long started = android.os.SystemClock.elapsedRealtimeNanos();
        try {
            ClassLoader loader = getWebViewClassLoader();
            if (loader == null) return Snapshot.unsupported(identity, "classloader-null", started, null);
            Class<?> glue = Class.forName(GLUE_CLASS, false, loader);
            Method method = glue.getDeclaredMethod(GLUE_METHOD);
            method.setAccessible(true);
            Object result = method.invoke(null);
            if (!(result instanceof InvocationHandler)) {
                return Snapshot.unsupported(identity, "factory-handler-invalid", started, null);
            }
            WebViewProviderFactoryBoundaryInterface factory =
                    BoundaryInterfaceReflectionUtil.castToSuppLibClass(
                            WebViewProviderFactoryBoundaryInterface.class,
                            (InvocationHandler) result);
            if (factory == null) return Snapshot.unsupported(identity, "factory-null", started, null);
            String[] advertised = factory.getSupportedFeatures();
            Set<String> features = advertised == null
                    ? Collections.<String>emptySet()
                    : Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(advertised)));
            Diagnostic diagnostic = Diagnostic.loaded(identity, features,
                    elapsedMicros(started), "ok", "");
            return new Snapshot(identity, factory, features, diagnostic);
        } catch (ClassNotFoundException error) {
            return Snapshot.unsupported(identity, "glue-class-missing", started, error);
        } catch (NoSuchMethodException error) {
            return Snapshot.unsupported(identity, "glue-method-missing", started, error);
        } catch (IllegalAccessException error) {
            return Snapshot.unsupported(identity, "glue-access-denied", started, error);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            return Snapshot.unsupported(identity, "glue-invocation-" + simpleName(cause), started, cause);
        } catch (RuntimeException error) {
            return Snapshot.unsupported(identity, "glue-runtime-" + simpleName(error), started, error);
        } catch (LinkageError error) {
            return Snapshot.unsupported(identity, "glue-linkage-" + simpleName(error), started, error);
        }
    }

    private static void updateFailure(Snapshot current, String reason, Throwable error) {
        String detail = error == null ? "" : safeDetail(error);
        lastDiagnostic = current.diagnostic.withFailure(reason, detail);
    }

    private static ClassLoader getWebViewClassLoader()
            throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ClassLoader loader = WebView.getWebViewClassLoader();
            if (loader != null) return loader;
        }
        Method getFactory = WebView.class.getDeclaredMethod("getFactory");
        getFactory.setAccessible(true);
        Object provider = getFactory.invoke(null);
        if (provider == null || provider.getClass().getClassLoader() == null) {
            throw new IllegalStateException("WebView provider unavailable");
        }
        return provider.getClass().getClassLoader();
    }

    private static Identity currentWebViewIdentity() {
        try {
            PackageInfo info = WebView.getCurrentWebViewPackage();
            if (info == null) return new Identity("unknown", 0L, "");
            long version = Build.VERSION.SDK_INT >= 28
                    ? info.getLongVersionCode() : info.versionCode;
            String versionName = info.versionName == null ? "" : info.versionName;
            return new Identity(info.packageName == null ? "unknown" : info.packageName,
                    version, versionName);
        } catch (RuntimeException error) {
            return new Identity("unknown", 0L, "");
        }
    }

    private static long elapsedMicros(long startedNanos) {
        return Math.max(0L, (android.os.SystemClock.elapsedRealtimeNanos() - startedNanos) / 1000L);
    }

    private static String simpleName(Throwable error) {
        String name = error == null ? "unknown" : error.getClass().getSimpleName();
        return name == null || name.length() == 0 ? "unknown" : name.toLowerCase(Locale.US);
    }

    private static String safeDetail(Throwable error) {
        String text = error.getMessage();
        if (text == null) return simpleName(error);
        text = text.replace('\n', ' ').replace('\r', ' ').trim();
        return text.length() > 160 ? text.substring(0, 160) : text;
    }

    private static final class Identity {
        final String packageName;
        final long versionCode;
        final String versionName;

        Identity(String packageName, long versionCode, String versionName) {
            this.packageName = packageName;
            this.versionCode = versionCode;
            this.versionName = versionName;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Identity)) return false;
            Identity value = (Identity) other;
            return versionCode == value.versionCode
                    && packageName.equals(value.packageName)
                    && versionName.equals(value.versionName);
        }

        @Override public int hashCode() {
            int result = packageName.hashCode();
            result = 31 * result + (int) (versionCode ^ (versionCode >>> 32));
            return 31 * result + versionName.hashCode();
        }
    }

    private static final class Snapshot {
        final Identity identity;
        final WebViewProviderFactoryBoundaryInterface factory;
        final Set<String> features;
        final Diagnostic diagnostic;

        Snapshot(Identity identity,
                 WebViewProviderFactoryBoundaryInterface factory,
                 Set<String> features,
                 Diagnostic diagnostic) {
            this.identity = identity;
            this.factory = factory;
            this.features = features;
            this.diagnostic = diagnostic;
        }

        static Snapshot unsupported(Identity identity, String reason, long started, Throwable error) {
            Diagnostic diagnostic = Diagnostic.loaded(identity, Collections.<String>emptySet(),
                    elapsedMicros(started), reason, error == null ? "" : safeDetail(error));
            return new Snapshot(identity, null, Collections.<String>emptySet(), diagnostic);
        }
    }

    public static final class Diagnostic {
        public final String packageName;
        public final String versionName;
        public final long versionCode;
        public final boolean glueLoaded;
        public final boolean documentStartAdvertised;
        public final boolean compatibilityAliasUsed;
        public final boolean registrationSucceeded;
        public final int advertisedFeatureCount;
        public final long loadMicros;
        public final String reasonCode;
        public final String detail;

        private Diagnostic(String packageName, String versionName, long versionCode,
                           boolean glueLoaded, boolean documentStartAdvertised,
                           boolean compatibilityAliasUsed, boolean registrationSucceeded,
                           int advertisedFeatureCount, long loadMicros,
                           String reasonCode, String detail) {
            this.packageName = packageName;
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.glueLoaded = glueLoaded;
            this.documentStartAdvertised = documentStartAdvertised;
            this.compatibilityAliasUsed = compatibilityAliasUsed;
            this.registrationSucceeded = registrationSucceeded;
            this.advertisedFeatureCount = advertisedFeatureCount;
            this.loadMicros = loadMicros;
            this.reasonCode = reasonCode;
            this.detail = detail;
        }

        static Diagnostic notLoaded() {
            return new Diagnostic("unknown", "", 0L, false, false,
                    false, false, 0, 0L, "not-loaded", "");
        }

        static Diagnostic loaded(Identity identity, Set<String> features, long loadMicros,
                                 String reason, String detail) {
            boolean exact = features.contains(FEATURE_DOCUMENT_START);
            boolean alias = !exact && features.contains("DOCUMENT_START_SCRIPT");
            return new Diagnostic(identity.packageName, identity.versionName, identity.versionCode,
                    "ok".equals(reason), exact || alias, alias, false,
                    features.size(), loadMicros, reason, detail);
        }

        Diagnostic withCompatibilityAlias(boolean used) {
            return new Diagnostic(packageName, versionName, versionCode, glueLoaded,
                    documentStartAdvertised, used, registrationSucceeded,
                    advertisedFeatureCount, loadMicros, reasonCode, detail);
        }

        Diagnostic withRegistrationSuccess() {
            return new Diagnostic(packageName, versionName, versionCode, glueLoaded,
                    documentStartAdvertised, compatibilityAliasUsed, true,
                    advertisedFeatureCount, loadMicros, "ok", "");
        }

        Diagnostic withFailure(String reason, String failureDetail) {
            return new Diagnostic(packageName, versionName, versionCode, glueLoaded,
                    documentStartAdvertised, compatibilityAliasUsed, false,
                    advertisedFeatureCount, loadMicros, reason, failureDetail);
        }

        public String compactReport() {
            String provider = packageName + (versionName.length() == 0 ? "" : " " + versionName)
                    + " (" + versionCode + ")";
            return "System WebView: " + provider
                    + "\nGlue: " + (glueLoaded ? "可用" : "不可用")
                    + "\nDocument-start: " + (documentStartAdvertised ? "支持" : "不支持")
                    + (compatibilityAliasUsed ? "（兼容别名）" : "")
                    + "\n注册验证: " + (registrationSucceeded ? "已成功" : "尚未成功注册")
                    + "\n特征数量: " + advertisedFeatureCount
                    + "\n加载耗时: " + loadMicros + " µs"
                    + "\n状态: " + reasonCode
                    + (detail.length() == 0 ? "" : "\n详情: " + detail);
        }
    }

    private static final class ScriptHandlerImpl implements ScriptHandler {
        private ScriptHandlerBoundaryInterface boundary;

        ScriptHandlerImpl(ScriptHandlerBoundaryInterface boundary) {
            this.boundary = boundary;
        }

        @Override public synchronized void remove() {
            ScriptHandlerBoundaryInterface current = boundary;
            if (current == null) return;
            boundary = null;
            try {
                current.remove();
            } catch (RuntimeException ignored) {
                // Removal is idempotent from Median's perspective. The provider may have restarted.
            }
        }
    }
}
