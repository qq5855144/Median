package com.xinyv.median;

import android.app.Activity;
import android.os.Build;

/**
 * Foreground-only ADPF controller. Android 12+ may use these reports to adjust
 * core placement and CPU frequency for Median's UI/RenderThread frame work.
 * WebView's sandboxed renderer is intentionally not faked as an app thread.
 */
final class AggressivePerformanceController {
    private Object platformController;

    void start(Activity activity, long targetNanos) {
        if (activity == null || Build.VERSION.SDK_INT < 31) return;
        if (platformController instanceof Api31Controller) {
            ((Api31Controller) platformController).updateTarget(targetNanos);
            return;
        }
        Api31Controller controller = new Api31Controller(activity, targetNanos);
        if (controller.isActive()) platformController = controller;
    }

    void stop(Activity activity) {
        if (Build.VERSION.SDK_INT >= 31 && platformController instanceof Api31Controller) {
            ((Api31Controller) platformController).stop(activity);
        }
        platformController = null;
    }

    boolean isActive() {
        return platformController != null;
    }

    @android.annotation.TargetApi(31)
    private static final class Api31Controller {
        private final Object sessionLock = new Object();
        private android.os.PerformanceHintManager.Session session;
        private android.os.HandlerThread metricsThread;
        private android.view.Window.OnFrameMetricsAvailableListener listener;
        private long targetNanos;

        Api31Controller(Activity activity, long requestedTargetNanos) {
            targetNanos = Math.max(1_000_000L, requestedTargetNanos);
            try {
                android.os.PerformanceHintManager manager =
                        activity.getSystemService(android.os.PerformanceHintManager.class);
                if (manager == null) return;
                session = manager.createHintSession(performanceThreadIds(), targetNanos);
                if (session == null) return;
                if (Build.VERSION.SDK_INT >= 35) session.setPreferPowerEfficiency(false);

                metricsThread = new android.os.HandlerThread(
                        "median-performance-hints", android.os.Process.THREAD_PRIORITY_DEFAULT);
                metricsThread.start();
                listener = new android.view.Window.OnFrameMetricsAvailableListener() {
                    @Override public void onFrameMetricsAvailable(android.view.Window window,
                            android.view.FrameMetrics frameMetrics, int droppedCount) {
                        long duration = frameMetrics.getMetric(android.view.FrameMetrics.TOTAL_DURATION);
                        if (duration <= 0L) return;
                        synchronized (sessionLock) {
                            android.os.PerformanceHintManager.Session activeSession = session;
                            if (activeSession == null) return;
                            try { activeSession.reportActualWorkDuration(duration); }
                            catch (RuntimeException ignored) {}
                        }
                    }
                };
                activity.getWindow().addOnFrameMetricsAvailableListener(
                        listener, new android.os.Handler(metricsThread.getLooper()));
            } catch (RuntimeException e) {
                stop(activity);
            }
        }

        boolean isActive() {
            synchronized (sessionLock) {
                return session != null && listener != null;
            }
        }

        void updateTarget(long requestedTargetNanos) {
            long desired = Math.max(1_000_000L, requestedTargetNanos);
            synchronized (sessionLock) {
                if (session == null || desired == targetNanos) return;
                targetNanos = desired;
                try { session.updateTargetWorkDuration(desired); }
                catch (RuntimeException ignored) {}
            }
        }

        void stop(Activity activity) {
            if (activity != null && listener != null) {
                try { activity.getWindow().removeOnFrameMetricsAvailableListener(listener); }
                catch (RuntimeException ignored) {}
            }
            listener = null;
            android.os.PerformanceHintManager.Session closing;
            synchronized (sessionLock) {
                closing = session;
                session = null;
            }
            if (closing != null) {
                try { closing.close(); } catch (RuntimeException ignored) {}
            }
            if (metricsThread != null) metricsThread.quitSafely();
            metricsThread = null;
        }

        private static int[] performanceThreadIds() {
            int mainTid = android.os.Process.myTid();
            int renderTid = findRenderThreadTid();
            return renderTid > 0 && renderTid != mainTid
                    ? new int[] { mainTid, renderTid }
                    : new int[] { mainTid };
        }

        private static int findRenderThreadTid() {
            java.io.File taskDirectory = new java.io.File("/proc/self/task");
            java.io.File[] tasks = taskDirectory.listFiles();
            if (tasks == null) return -1;
            for (java.io.File task : tasks) {
                java.io.BufferedReader reader = null;
                try {
                    reader = new java.io.BufferedReader(new java.io.FileReader(new java.io.File(task, "comm")));
                    String name = reader.readLine();
                    if (name != null && name.startsWith("RenderThread")) {
                        return Integer.parseInt(task.getName());
                    }
                } catch (Exception ignored) {
                } finally {
                    if (reader != null) try { reader.close(); } catch (java.io.IOException ignored) {}
                }
            }
            return -1;
        }
    }
}
