package com.xinyv.median;

import android.app.ActivityManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.PowerManager;

/**
 * Converts device memory and thermal signals into conservative settings for Median's own
 * single-connection downloader.
 */
final class DownloadMemoryPolicy {
    static final String MODE_PERFORMANCE = "performance";
    static final String MODE_STANDARD = "standard";
    static final String MODE_POWER_SAVE = "power_save";

    static final class Snapshot {
        final int memoryClassMb;
        final boolean lowRam;
        final long availableSystemMb;
        final long systemThresholdMb;
        final long heapUsedMb;
        final long heapFreeMb;
        final int cpuCores;
        final int downstreamKbps;
        final int thermalStatus;

        Snapshot(int memoryClassMb, boolean lowRam, long availableSystemMb, long systemThresholdMb,
                 long heapUsedMb, long heapFreeMb, int cpuCores, int downstreamKbps, int thermalStatus) {
            this.memoryClassMb = memoryClassMb;
            this.lowRam = lowRam;
            this.availableSystemMb = availableSystemMb;
            this.systemThresholdMb = systemThresholdMb;
            this.heapUsedMb = heapUsedMb;
            this.heapFreeMb = heapFreeMb;
            this.cpuCores = cpuCores;
            this.downstreamKbps = downstreamKbps;
            this.thermalStatus = thermalStatus;
        }
    }

    static final class Plan {
        final int bufferBytes;
        final long memoryBudgetBytes;
        final String summary;

        Plan(int bufferBytes, long memoryBudgetBytes, String summary) {
            this.bufferBytes = bufferBytes;
            this.memoryBudgetBytes = memoryBudgetBytes;
            this.summary = summary;
        }
    }

    private DownloadMemoryPolicy() {}

    static Snapshot snapshot(Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        int memoryClass = manager == null ? 128 : Math.max(64, manager.getMemoryClass());
        boolean lowRam = manager != null && manager.isLowRamDevice();
        long availableMb = 0L;
        long thresholdMb = 0L;
        if (manager != null) {
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(info);
            availableMb = info.availMem / (1024L * 1024L);
            thresholdMb = info.threshold / (1024L * 1024L);
        }
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        long free = Math.max(0L, runtime.maxMemory() - used);
        int downstream = 0;
        try {
            ConnectivityManager connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities caps = connectivity == null ? null : connectivity.getNetworkCapabilities(connectivity.getActiveNetwork());
            if (caps != null) downstream = Math.max(0, caps.getLinkDownstreamBandwidthKbps());
        } catch (RuntimeException ignored) {}
        int thermal = PowerManager.THERMAL_STATUS_NONE;
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                if (power != null) thermal = power.getCurrentThermalStatus();
            } catch (RuntimeException ignored) {}
        }
        return new Snapshot(memoryClass, lowRam, availableMb, thresholdMb,
                used / (1024L * 1024L), free / (1024L * 1024L),
                Math.max(1, Runtime.getRuntime().availableProcessors()), downstream, thermal);
    }

    static Plan plan(Context context, String mode, long totalBytes) {
        return plan(context, mode, totalBytes, 1);
    }

    static Plan plan(Context context, String mode, long totalBytes, int activeTasks) {
        Snapshot s = snapshot(context);
        int taskCount = Math.max(1, activeTasks);
        long mib = 1024L * 1024L;
        long freeHeapBytes = Math.max(8L * mib, s.heapFreeMb * mib);
        boolean memoryPressure = s.availableSystemMb > 0L &&
                s.availableSystemMb < Math.max(s.systemThresholdMb * 2L, 384L);
        boolean hot = Build.VERSION.SDK_INT >= 29 && s.thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE;

        int buffer = s.lowRam || memoryPressure ? 64 * 1024 : 128 * 1024;
        long globalBudget = Math.min(2L * mib, Math.max(512L * 1024L, freeHeapBytes / 32L));
        long budget = Math.max(512L * 1024L, globalBudget / Math.min(taskCount, 2));
        if (hot) budget = Math.min(budget, 1024L * 1024L);
        return new Plan(buffer, budget,
                "Median 单连接 · " + humanBuffer(buffer) + " 缓冲 · " + humanBytes(budget) +
                        " 预算" + (hot ? " · 高温保护" : ""));
    }

    static String diagnostics(Context context, String mode) {
        Snapshot s = snapshot(context);
        Plan p = plan(context, mode, -1L);
        return "内存档：" + (s.lowRam ? "低内存设备" : s.memoryClassMb + " MB 应用堆") +
                " · 堆已用 " + s.heapUsedMb + " MB · 可扩展约 " + s.heapFreeMb + " MB" +
                "\n系统可用：" + s.availableSystemMb + " MB · 回收阈值 " + s.systemThresholdMb + " MB" +
                "\n下载计划：" + p.summary +
                "\nCPU：" + s.cpuCores + " 核" +
                (s.downstreamKbps > 0 ? " · 链路估计 " + s.downstreamKbps + " kbps" : "") +
                (Build.VERSION.SDK_INT >= 29 ? " · 温控状态 " + thermalLabel(s.thermalStatus) : "");
    }

    private static String thermalLabel(int status) {
        if (status >= PowerManager.THERMAL_STATUS_EMERGENCY) return "紧急";
        if (status >= PowerManager.THERMAL_STATUS_CRITICAL) return "临界";
        if (status >= PowerManager.THERMAL_STATUS_SEVERE) return "严重";
        if (status >= PowerManager.THERMAL_STATUS_MODERATE) return "中等";
        if (status >= PowerManager.THERMAL_STATUS_LIGHT) return "轻微";
        return "正常";
    }

    static String humanBytes(long bytes) {
        if (bytes < 1024L * 1024L) return Math.max(1L, bytes / 1024L) + " KB";
        return Math.max(1L, bytes / (1024L * 1024L)) + " MB";
    }

    private static String humanBuffer(int bytes) {
        return bytes >= 1024 * 1024 ? (bytes / (1024 * 1024)) + " MB" : (bytes / 1024) + " KB";
    }
}
