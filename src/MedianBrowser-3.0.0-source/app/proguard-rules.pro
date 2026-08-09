# System WebView reflects over these exact support-library protocol names.
-keep,allowoptimization interface org.chromium.support_lib_boundary.** { *; }
-keepnames interface org.chromium.support_lib_boundary.**
-keep,allowoptimization class org.chromium.support_lib_boundary.util.BoundaryInterfaceReflectionUtil { *; }

# Keep the focused AndroidX-compatible public facade stable for diagnostics.
-keep,allowoptimization class androidx.webkit.WebViewCompat { public *; }
-keep,allowoptimization class androidx.webkit.WebViewFeature { public *; }
-keep,allowoptimization interface androidx.webkit.ScriptHandler { *; }

# WebView invokes methods annotated with JavascriptInterface reflectively.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-keepattributes Signature,RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault
-dontwarn org.chromium.**
