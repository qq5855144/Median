package com.xinyv.median;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentCallbacks2;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.webkit.CookieManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Resumable, range-aware foreground downloader. Segment cursors are persisted so a
 * manual pause, process restart or transient failure does not discard completed data.
 */
public final class AdaptiveDownloadService extends Service {
    static final String ACTION_DOWNLOAD = "com.xinyv.median.action.ADAPTIVE_DOWNLOAD";
    static final String ACTION_PAUSE = "com.xinyv.median.action.PAUSE_ADAPTIVE_DOWNLOAD";
    static final String ACTION_RESUME = "com.xinyv.median.action.RESUME_ADAPTIVE_DOWNLOAD";
    static final String ACTION_CANCEL = "com.xinyv.median.action.CANCEL_ADAPTIVE_DOWNLOAD";
    static final String EXTRA_ID = "id";
    static final String EXTRA_URL = "url";
    static final String EXTRA_NAME = "name";
    static final String EXTRA_MIME = "mime";
    static final String EXTRA_USER_AGENT = "user_agent";
    static final String EXTRA_COOKIE = "cookie";
    static final String EXTRA_HEADERS = "headers";
    static final String EXTRA_MODE = "mode";
    static final String EXTRA_WIFI_ONLY = "wifi_only";
    static final String EXTRA_ALLOW_ROAMING = "allow_roaming";
    static final String EXTRA_CHARGING_ONLY = "charging_only";
    static final String EXTRA_PUBLIC_ONLY = "public_only";
    static final String EXTRA_TOTAL_BYTES = "total_bytes";

    private static final String CHANNEL_ID = "median_adaptive_downloads_v2";
    private static final int FOREGROUND_ID = 7101;
    private static final int TASK_THREADS = Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors()));
    private static final ThreadPoolExecutor TASK_EXECUTOR = new ThreadPoolExecutor(TASK_THREADS, TASK_THREADS,
            30L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(48));
    private static final ConcurrentHashMap<Long, Control> CONTROLS = new ConcurrentHashMap<Long, Control>();

    private final AtomicInteger activeTasks = new AtomicInteger();
    private NotificationManager notificationManager;
    private DownloadStore store;

    static { TASK_EXECUTOR.allowCoreThreadTimeOut(true); }

    static boolean isTaskScheduled(long id) { return CONTROLS.containsKey(Long.valueOf(id)); }

    @Override public void onCreate() {
        super.onCreate();
        store = new DownloadStore(this);
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && notificationManager != null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "专业下载", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Median 单连接下载、断点续传与任务控制");
            channel.setShowBadge(false);
            notificationManager.createNotificationChannel(channel);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, final int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        final long id = intent.getLongExtra(EXTRA_ID, 0L);

        if (ACTION_PAUSE.equals(action)) {
            Control control = CONTROLS.get(Long.valueOf(id));
            if (control != null) {
                control.pause("已手动暂停");
                markPaused(id, "已手动暂停");
            }
            else {
                markPaused(id, "已手动暂停");
                stopSelfResult(startId);
            }
            return START_NOT_STICKY;
        }
        if (ACTION_CANCEL.equals(action)) {
            Control control = CONTROLS.get(Long.valueOf(id));
            if (control != null) {
                control.cancel();
                DownloadStore.Item item = store.get(id);
                if (item != null) store.updateAdaptive(id, DownloadStore.STATUS_CANCELLED, "正在取消",
                        item.downloadedBytes, item.totalBytes, 0L, null);
            }
            else {
                deletePartialFiles(id);
                DownloadStore.Item item = store.get(id);
                long total = item == null ? 0L : item.totalBytes;
                store.updateAdaptive(id, DownloadStore.STATUS_CANCELLED, "已取消", 0L, total, 0L, null);
                if (item != null) notifyTask(Task.fromStore(this, item), "已取消", 0, 0L, total, false, false);
                stopSelfResult(startId);
            }
            return START_NOT_STICKY;
        }
        if (!ACTION_DOWNLOAD.equals(action) && !ACTION_RESUME.equals(action)) return START_NOT_STICKY;

        final Task task = ACTION_RESUME.equals(action) ? taskFromStore(id) : Task.fromIntent(intent);
        if (task == null || task.id >= 0L || task.url.length() == 0) {
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        try {
            startForegroundCompat(buildNotification(task, "正在准备下载", 0, 0L,
                    task.expectedTotalBytes, true, false));
        } catch (RuntimeException error) {
            store.updateAdaptive(task.id, DownloadStore.STATUS_FAILED,
                    "系统不允许启动后台下载", 0L, 0L, 0L, null);
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        final Control control = new Control();
        if (CONTROLS.putIfAbsent(Long.valueOf(task.id), control) != null) return START_NOT_STICKY;
        activeTasks.incrementAndGet();
        try {
            TASK_EXECUTOR.execute(new Runnable() {
                @Override public void run() {
                    runTask(task, control);
                    if (activeTasks.decrementAndGet() <= 0) {
                        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
                        else stopForeground(true);
                        stopSelf();
                    }
                }
            });
        } catch (RejectedExecutionException overloaded) {
            CONTROLS.remove(Long.valueOf(task.id));
            int remaining = activeTasks.decrementAndGet();
            store.updateAdaptive(task.id, DownloadStore.STATUS_FAILED,
                    "下载队列已满，请稍后重试", 0L, 0L, 0L, null);
            notifyTask(task, "下载队列已满，请稍后重试", 0, 0L, 0L, false, true);
            if (remaining <= 0) {
                if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
                else stopForeground(true);
                stopSelfResult(startId);
            }
            return START_NOT_STICKY;
        }
        // If Android kills the foreground service mid-transfer, redeliver the original
        // task intent. The persisted cursor/validator state resumes without duplicating bytes.
        return START_REDELIVER_INTENT;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            for (Control control : CONTROLS.values()) control.pause("系统内存紧张，已安全暂停");
        }
    }

    @Override public void onLowMemory() {
        super.onLowMemory();
        for (Control control : CONTROLS.values()) control.pause("系统内存不足，已安全暂停");
    }

    /** Android 15+ limits dataSync foreground services to a shared six-hour budget. */
    @Override public void onTimeout(int startId, int fgsType) {
        for (Control control : CONTROLS.values()) {
            control.pause("系统已达到后台下载时限，请返回应用后继续");
        }
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        stopSelf(startId);
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(FOREGROUND_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else startForeground(FOREGROUND_ID, notification);
    }

    private Task taskFromStore(long id) {
        DownloadStore.Item item = store.get(id);
        if (item == null || !item.isAdaptive()) return null;
        return Task.fromStore(this, item);
    }

    private void markPaused(long id, String reason) {
        DownloadStore.Item item = store.get(id);
        if (item == null || !item.isAdaptive()) return;
        store.updateAdaptiveTelemetry(id, DownloadStore.STATUS_PAUSED, reason,
                item.downloadedBytes, item.totalBytes, 0L, null, item.segmentCount,
                item.bufferBytes, item.memoryBudgetBytes, item.rangeSupported, item.etaSeconds, item.retryCount);
        notifyTask(Task.fromStore(this, item), reason, percent(item.downloadedBytes, item.totalBytes),
                item.downloadedBytes, item.totalBytes, false, true);
    }

    private void runTask(Task task, Control control) {
        File temp = partialFile(task.id);
        File stateFile = stateFile(task.id);
        Locks locks = null;
        boolean completed = false;
        boolean cancelled = false;
        try {
            waitForConstraints(task, control);
            control.check();

            DownloadState state = DownloadState.load(stateFile);
            boolean incompatible = state != null && (!task.url.equals(state.originalUrl) ||
                    state.segments.size() == 0 || state.downloaded() > temp.length() ||
                    (state.segments.size() > 1 && state.downloaded() < state.totalBytes));
            if (state == null || incompatible || !temp.exists()) {
                deleteQuietly(temp);
                deleteQuietly(stateFile);
                state = DownloadState.create(task);
                preparePartialFile(temp, state);
                state.persist(stateFile, true);
            }

            DownloadMemoryPolicy.Plan memoryPlan = DownloadMemoryPolicy.plan(this, task.mode, state.totalBytes, activeTasks.get());
            Profile profile = Profile.from(task.mode, memoryPlan);
            setCurrentThreadPriority(profile.threadPriority);
            locks = acquireLocks(profile);

            DownloadStore.Item knownItem = store.get(task.id);
            long knownTotal = state.totalBytes > 0L ? state.totalBytes : Math.max(task.expectedTotalBytes,
                    knownItem == null ? 0L : knownItem.totalBytes);
            ProgressTracker tracker = new ProgressTracker(task, profile, control, knownTotal, state.downloaded());
            tracker.setStrategy(state.segments.size(), state.rangeSupported);
            tracker.reportState(DownloadStore.STATUS_DOWNLOADING, tracker.strategy, state, null, true);

            boolean dataComplete = state.totalBytes > 0L && state.downloaded() == state.totalBytes;
            if (!dataComplete) downloadSingle(task, profile, state, temp, stateFile, control, tracker);
            control.check();
            DownloadFileTypes.Metadata completedMetadata =
                    new DownloadFileTypes.Metadata(task.filename, task.mime);
            if (DownloadFileTypes.correctCompletedApk(temp, completedMetadata)) {
                task.filename = completedMetadata.filename;
                task.mime = completedMetadata.mime;
                store.updateMetadata(task.id, task.filename, task.mime);
            }
            Uri uri = publish(task, temp, control);
            tracker.forceReport(DownloadStore.STATUS_COMPLETED, "已完成", state, uri.toString());
            notifyTask(task, "已完成", 100, tracker.downloaded.get(), tracker.totalBytes, false, false);
            completed = true;
        } catch (Paused paused) {
            DownloadState state = DownloadState.load(stateFile);
            DownloadStore.Item current = store.get(task.id);
            long downloaded = state == null ? (current == null ? 0L : current.downloadedBytes) : state.downloaded();
            long total = DownloadCenterPolicy.resolvedTotal(current == null ? 0L : current.totalBytes,
                    state == null ? 0L : state.totalBytes);
            int segments = state == null ? (current == null ? 0 : current.segmentCount) : state.segments.size();
            boolean ranged = state != null ? state.rangeSupported : current != null && current.rangeSupported;
            if (state != null) state.persist(stateFile, true);
            DownloadMemoryPolicy.Plan plan = DownloadMemoryPolicy.plan(this, task.mode, total);
            store.updateAdaptiveTelemetry(task.id, DownloadStore.STATUS_PAUSED, paused.reason,
                    downloaded, total, 0L, null, segments, plan.bufferBytes, plan.memoryBudgetBytes,
                    ranged, 0L, current == null ? 0 : current.retryCount);
            notifyTask(task, paused.reason, percent(downloaded, total), downloaded, total, false, true);
        } catch (Cancelled ignored) {
            cancelled = true;
            DownloadStore.Item current = store.get(task.id);
            long total = current == null ? 0L : current.totalBytes;
            store.updateAdaptive(task.id, DownloadStore.STATUS_CANCELLED, "已取消", 0L, total, 0L, null);
            notifyTask(task, "已取消", 0, 0L, total, false, false);
        } catch (Throwable error) {
            String reason = safeMessage(error);
            DownloadState state = DownloadState.load(stateFile);
            if (state != null) state.persist(stateFile, true);
            DownloadStore.Item current = store.get(task.id);
            long downloaded = state == null ? (current == null ? 0L : current.downloadedBytes) : state.downloaded();
            long total = DownloadCenterPolicy.resolvedTotal(current == null ? 0L : current.totalBytes,
                    state == null ? 0L : state.totalBytes);
            int retries = current == null ? 1 : current.retryCount + 1;
            DownloadMemoryPolicy.Plan plan = DownloadMemoryPolicy.plan(this, task.mode, total);
            store.updateAdaptiveTelemetry(task.id, DownloadStore.STATUS_FAILED, reason, downloaded, total, 0L,
                    null, state == null ? 0 : state.segments.size(), plan.bufferBytes, plan.memoryBudgetBytes,
                    state != null && state.rangeSupported, 0L, retries);
            notifyTask(task, "失败 · " + reason, percent(downloaded, total), downloaded, total, false, true);
        } finally {
            CONTROLS.remove(Long.valueOf(task.id));
            if (locks != null) locks.release();
            if (completed || cancelled) {
                deleteQuietly(temp);
                deleteQuietly(stateFile);
            }
        }
    }

    private void waitForConstraints(Task task, Control control) throws Cancelled, Paused {
        while (true) {
            control.check();
            String reason = constraintReason(task);
            if (reason.length() == 0) return;
            DownloadStore.Item current = store.get(task.id);
            long downloaded = current == null ? 0L : current.downloadedBytes;
            long total = current == null ? 0L : current.totalBytes;
            store.updateAdaptive(task.id, DownloadStore.STATUS_WAITING, reason, downloaded, total, 0L, null);
            notifyTask(task, reason, percent(downloaded, total), downloaded, total, true, false);
            for (int i = 0; i < 10; i++) {
                control.check();
                SystemClock.sleep(1000L);
            }
        }
    }

    private String constraintReason(Task task) {
        try {
            ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (manager == null) return "等待网络";
            Network network = manager.getActiveNetwork();
            NetworkCapabilities caps = manager.getNetworkCapabilities(network);
            if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return "等待网络";
            if (task.wifiOnly && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return "等待非计费网络";
            if (!task.allowRoaming) {
                if (Build.VERSION.SDK_INT >= 28) {
                    if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)) return "等待非漫游网络";
                } else {
                    NetworkInfo info = manager.getActiveNetworkInfo();
                    if (info != null && info.isRoaming()) return "等待非漫游网络";
                }
            }
            if (task.chargingOnly) {
                BatteryManager battery = (BatteryManager) getSystemService(BATTERY_SERVICE);
                if (battery == null || !battery.isCharging()) return "等待充电";
            }
            return "";
        } catch (RuntimeException error) { return "等待网络"; }
    }

    private void applyResponseMetadata(Task task, HttpURLConnection connection) {
        task.filename = DownloadFileTypes.resolveName(connection.getURL().toString(),
                value(connection.getHeaderField("Content-Disposition")), task.mime,
                value(connection.getContentType()), task.filename);
        task.mime = DownloadFileTypes.resolveMime(task.filename, task.mime,
                value(connection.getContentType()));
        store.updateMetadata(task.id, task.filename, task.mime);
    }

    private void preparePartialFile(File temp, DownloadState state) throws Exception {
        File parent = temp.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IllegalStateException("无法创建临时下载目录");
        RandomAccessFile allocator = new RandomAccessFile(temp, "rw");
        try {
            if (state.totalBytes > 0L) allocator.setLength(state.totalBytes);
            else allocator.setLength(0L);
        } finally { allocator.close(); }
    }

    private void downloadSingle(Task task, Profile profile, DownloadState state, File temp, File stateFile,
                                Control control, ProgressTracker tracker) throws Exception {
        Segment segment = state.segments.get(0);
        Exception last = null;
        for (int attempt = 0; attempt <= profile.retries; attempt++) {
            control.check();
            HttpURLConnection connection = null;
            InputStream input = null;
            RandomAccessFile output = null;
            try {
                long cursor = segment.cursor;
                String range = cursor > 0L ? "bytes=" + cursor + "-" : null;
                connection = open(task, task.url, range, profile.connectTimeoutMs, profile.readTimeoutMs, control);
                int code = connection.getResponseCode();
                boolean restartFull = false;
                if (range != null && code == HttpURLConnection.HTTP_PARTIAL) {
                    long[] contentRange = parseContentRange(connection.getHeaderField("Content-Range"));
                    if (contentRange == null || contentRange[0] != cursor ||
                            (state.totalBytes > 0L && contentRange[2] != state.totalBytes) ||
                            validatorChanged(state.etag, connection.getHeaderField("ETag")) ||
                            validatorChanged(state.lastModified, connection.getHeaderField("Last-Modified")))
                        restartFull = true;
                }
                if (range != null && code != HttpURLConnection.HTTP_PARTIAL && code != HttpURLConnection.HTTP_OK) {
                    if (DownloadRetryPolicy.isRetryableHttp(code)) throw new HttpFailure(code);
                    restartFull = true;
                }
                if (restartFull) {
                    if (code >= 400) closeQuietly(connection.getErrorStream());
                    else closeQuietly(connection.getInputStream());
                    control.detach(connection);
                    connection.disconnect();
                    connection = open(task, task.url, null, profile.connectTimeoutMs, profile.readTimeoutMs, control);
                    code = connection.getResponseCode();
                }
                if (range != null && (restartFull || code == HttpURLConnection.HTTP_OK)) {
                    segment.cursor = 0L;
                    tracker.reset(0L);
                    cursor = 0L;
                }
                if (code < 200 || code >= 300) throw new HttpFailure(code);
                long[] contentRange = parseContentRange(connection.getHeaderField("Content-Range"));
                long responseTotal = responseTotal(connection, cursor, contentRange);
                if (responseTotal > 0L) {
                    state.totalBytes = responseTotal;
                    segment.end = state.totalBytes - 1L;
                    tracker.totalBytes = state.totalBytes;
                }
                state.resolvedUrl = connection.getURL().toString();
                state.etag = value(connection.getHeaderField("ETag"));
                state.lastModified = value(connection.getHeaderField("Last-Modified"));
                state.rangeSupported = code == HttpURLConnection.HTTP_PARTIAL ||
                        value(connection.getHeaderField("Accept-Ranges")).toLowerCase(Locale.US).contains("bytes");
                applyResponseMetadata(task, connection);
                state.persist(stateFile, true);
                tracker.reportState(DownloadStore.STATUS_DOWNLOADING, tracker.strategy, state, null, true);
                input = connection.getInputStream();
                output = new RandomAccessFile(temp, "rw");
                if (cursor == 0L) output.setLength(state.totalBytes > 0L ? state.totalBytes : 0L);
                else if (state.totalBytes > 0L && output.length() < state.totalBytes) output.setLength(state.totalBytes);
                output.seek(cursor);
                byte[] buffer = new byte[profile.bufferBytes];
                int read;
                while (true) {
                    long remaining = state.totalBytes > 0L ? state.totalBytes - segment.cursor : buffer.length;
                    if (state.totalBytes > 0L && remaining <= 0L) break;
                    int request = (int) Math.min((long) buffer.length, Math.max(1L, remaining));
                    read = input.read(buffer, 0, request);
                    if (read == -1) break;
                    control.check();
                    output.write(buffer, 0, read);
                    state.advance(0, read);
                    tracker.add(read, state, stateFile);
                }
                if (state.totalBytes <= 0L) {
                    state.totalBytes = state.downloaded();
                    segment.end = Math.max(0L, state.totalBytes - 1L);
                    tracker.totalBytes = state.totalBytes;
                }
                if (state.totalBytes > 0L && state.downloaded() != state.totalBytes)
                    throw new EOFException("下载数据不完整");
                state.persist(stateFile, true);
                return;
            } catch (Paused paused) { throw paused;
            } catch (Cancelled cancelled) { throw cancelled;
            } catch (Exception error) {
                last = error;
                control.check();
                tracker.retryCount.incrementAndGet();
                if (attempt >= profile.retries || !isRetryable(error)) throw error;
                SystemClock.sleep(400L * (attempt + 1L));
            } finally {
                closeQuietly(input);
                closeQuietly(output);
                if (connection != null) {
                    control.detach(connection);
                    connection.disconnect();
                }
            }
        }
        if (last != null) throw last;
    }

    private Uri publish(Task task, File temp, Control control) throws Exception {
        control.check();
        if (Build.VERSION.SDK_INT < 29) return publishToAppDownloads(task, temp, control);
        try {
            return publishToMediaStore(task, temp, control);
        } catch (Paused paused) { throw paused;
        } catch (Cancelled cancelled) { throw cancelled;
        } catch (Exception mediaStoreFailure) {
            return publishToAppDownloads(task, temp, control);
        }
    }

    private Uri publishToMediaStore(Task task, File temp, Control control) throws Exception {
        if (Build.VERSION.SDK_INT < 29) throw new IllegalStateException("MediaStore.Downloads requires API 29");
        ContentResolver resolver = getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, task.filename);
        values.put(MediaStore.MediaColumns.MIME_TYPE, task.mime.length() == 0 ? "application/octet-stream" : task.mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Median");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IllegalStateException("无法创建下载文件");
        InputStream input = null;
        OutputStream output = null;
        try {
            input = new BufferedInputStream(new FileInputStream(temp), 256 * 1024);
            OutputStream rawOutput = resolver.openOutputStream(uri, "w");
            if (rawOutput == null) throw new IllegalStateException("无法写入下载目录");
            output = new BufferedOutputStream(rawOutput, 256 * 1024);
            byte[] buffer = new byte[256 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                control.check();
                output.write(buffer, 0, read);
            }
            output.flush();
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            values.put(MediaStore.MediaColumns.SIZE, temp.length());
            resolver.update(uri, values, null, null);
            return uri;
        } catch (Throwable error) {
            resolver.delete(uri, null, null);
            if (error instanceof Paused) throw (Paused) error;
            if (error instanceof Cancelled) throw (Cancelled) error;
            if (error instanceof Exception) throw (Exception) error;
            throw new IllegalStateException(safeMessage(error));
        } finally {
            closeQuietly(input);
            closeQuietly(output);
        }
    }

    private Uri publishToAppDownloads(Task task, File temp, Control control) throws Exception {
        control.check();
        File root = DownloadContentProvider.root(this);
        if (!root.isDirectory() && !root.mkdirs()) throw new IllegalStateException("无法创建应用下载目录");
        File target = uniquePublishedFile(root, task.filename);
        if (temp.renameTo(target)) return DownloadContentProvider.uriFor(target);
        InputStream input = null;
        OutputStream output = null;
        try {
            input = new BufferedInputStream(new FileInputStream(temp), 256 * 1024);
            output = new BufferedOutputStream(new FileOutputStream(target, false), 256 * 1024);
            byte[] buffer = new byte[256 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                control.check();
                output.write(buffer, 0, read);
            }
            output.flush();
            return DownloadContentProvider.uriFor(target);
        } catch (Throwable error) {
            deleteQuietly(target);
            if (error instanceof Paused) throw (Paused) error;
            if (error instanceof Cancelled) throw (Cancelled) error;
            if (error instanceof Exception) throw (Exception) error;
            throw new IllegalStateException(safeMessage(error));
        } finally {
            closeQuietly(input);
            closeQuietly(output);
        }
    }

    private File uniquePublishedFile(File root, String requestedName) {
        String name = DownloadFileTypes.sanitize(requestedName);
        if (name.length() == 0) name = "download.bin";
        File candidate = new File(root, name);
        if (!candidate.exists()) return candidate;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i <= 999; i++) {
            candidate = new File(root, base + " (" + i + ")" + extension);
            if (!candidate.exists()) return candidate;
        }
        return new File(root, base + '-' + System.currentTimeMillis() + extension);
    }

    private HttpURLConnection open(Task task, String url, String range, int connectTimeout, int readTimeout,
                                   Control control) throws Exception {
        URL initial = NetworkSecurity.parseHttpUrl(url);
        URL original = NetworkSecurity.parseHttpUrl(task.url);
        URL current = initial;
        for (int redirects = 0; redirects <= NetworkSecurity.MAX_REDIRECTS; redirects++) {
            if (task.publicOnly && NetworkSecurity.isLocalOrPrivateHost(NetworkSecurity.normalizedHost(current)))
                throw new IllegalArgumentException("本地或私有网络下载目标已拒绝");
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            control.attach(connection);
            control.check();
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(connectTimeout);
                connection.setReadTimeout(readTimeout);
                connection.setUseCaches(false);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept-Encoding", "identity");
                if (task.userAgent.length() > 0) connection.setRequestProperty("User-Agent", task.userAgent);
                if (range != null) connection.setRequestProperty("Range", range);
                boolean sameOrigin = NetworkSecurity.sameOrigin(original, current);
                String cookie = "";
                try { cookie = value(CookieManager.getInstance().getCookie(current.toString())); }
                catch (RuntimeException ignored) {}
                if (sameOrigin && cookie.length() == 0) cookie = task.cookie;
                if (cookie.length() > 0) connection.setRequestProperty("Cookie", cookie);
                for (Map.Entry<String, String> header : task.headers.entrySet()) {
                    if (!NetworkSecurity.validHeader(header.getKey(), header.getValue()) ||
                            NetworkSecurity.isForbiddenRequestHeader(header.getKey())) continue;
                    if (!sameOrigin && NetworkSecurity.isCredentialHeader(header.getKey())) continue;
                    connection.setRequestProperty(header.getKey(), header.getValue());
                }
                int status = connection.getResponseCode();
                storeResponseCookies(current, connection);
                if (!NetworkSecurity.isRedirect(status)) return connection;
                if (redirects == NetworkSecurity.MAX_REDIRECTS)
                    throw new IllegalStateException("重定向次数过多");
                URL next = NetworkSecurity.resolveRedirect(current, connection.getHeaderField("Location"), false);
                control.detach(connection);
                connection.disconnect();
                current = next;
            } catch (Exception error) {
                control.detach(connection);
                connection.disconnect();
                throw error;
            }
        }
        throw new IllegalStateException("重定向次数过多");
    }

    private static void storeResponseCookies(URL url, HttpURLConnection connection) {
        if (url == null || connection == null) return;
        try {
            CookieManager manager = CookieManager.getInstance();
            for (Map.Entry<String, List<String>> header : connection.getHeaderFields().entrySet()) {
                String name = header.getKey();
                if (name == null || !("set-cookie".equalsIgnoreCase(name) ||
                        "set-cookie2".equalsIgnoreCase(name)) || header.getValue() == null) continue;
                for (String cookie : header.getValue())
                    if (cookie != null && cookie.length() > 0) manager.setCookie(url.toString(), cookie);
            }
        } catch (RuntimeException ignored) {}
    }

    private File partialDirectory() {
        File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) dir = new File(getCacheDir(), "downloads");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static String safeTaskId(long id) { return id == Long.MIN_VALUE ? "min" : Long.toString(Math.abs(id)); }
    private File partialFile(long id) { return new File(partialDirectory(), "median-" + safeTaskId(id) + ".part"); }
    private File stateFile(long id) { return new File(partialDirectory(), "median-" + safeTaskId(id) + ".state"); }

    private void deletePartialFiles(long id) {
        deleteQuietly(partialFile(id));
        deleteQuietly(stateFile(id));
    }

    private Locks acquireLocks(Profile profile) {
        Locks locks = new Locks();
        try {
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            if (power != null) {
                locks.wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Median:AdaptiveDownload");
                locks.wakeLock.setReferenceCounted(false);
                locks.wakeLock.acquire();
            }
        } catch (RuntimeException ignored) {}
        return locks;
    }

    private void notifyTask(Task task, String text, int percent, long downloaded, long total,
                            boolean ongoing, boolean resumable) {
        if (notificationManager == null || task == null) return;
        notificationManager.notify(notificationId(task.id),
                buildNotification(task, text, percent, downloaded, total, ongoing, resumable));
    }

    private Notification buildNotification(Task task, String text, int percent, long downloaded, long total,
                                           boolean ongoing, boolean resumable) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        Intent open = new Intent(this, DownloadCenterActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(this, notificationId(task.id), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        builder.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(task.filename)
                .setContentText(text)
                .setContentIntent(content)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setVisibility(Notification.VISIBILITY_PRIVATE);
        if (total > 0L) builder.setProgress(100, Math.max(0, Math.min(100, percent)), false);
        else if (ongoing) builder.setProgress(0, 0, true);
        if (ongoing) {
            builder.addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause, "暂停",
                    serviceAction(task.id, ACTION_PAUSE, 1)).build());
            builder.addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "取消",
                    serviceAction(task.id, ACTION_CANCEL, 2)).build());
        } else if (resumable) {
            builder.addAction(new Notification.Action.Builder(android.R.drawable.ic_media_play, "继续",
                    serviceAction(task.id, ACTION_RESUME, 3)).build());
            builder.addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "取消",
                    serviceAction(task.id, ACTION_CANCEL, 4)).build());
            builder.setAutoCancel(false);
        } else builder.setAutoCancel(true);
        return builder.build();
    }

    private PendingIntent serviceAction(long id, String action, int salt) {
        Intent intent = new Intent(this, AdaptiveDownloadService.class);
        intent.setAction(action);
        intent.putExtra(EXTRA_ID, id);
        return PendingIntent.getService(this, notificationId(id) * 10 + salt, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static int notificationId(long id) { return 7200 + (int) Math.abs(id % 100000L); }
    private static int percent(long downloaded, long total) {
        return DownloadCenterPolicy.progressPermille(downloaded, total) / 10;
    }

    private static long responseTotal(HttpURLConnection connection, long cursor, long[] contentRange) {
        if (contentRange != null && contentRange[2] > 0L) return contentRange[2];
        long content = connection == null ? -1L : connection.getContentLengthLong();
        if (content > 0L && cursor >= 0L && cursor <= Long.MAX_VALUE - content) return cursor + content;
        String[] totalHeaders = new String[] {
                "X-File-Size", "X-Content-Length", "X-Original-Content-Length",
                "X-Goog-Stored-Content-Length"
        };
        for (String header : totalHeaders) {
            long value = positiveLong(connection == null ? null : connection.getHeaderField(header));
            if (value >= cursor && value > 0L) return value;
        }
        return 0L;
    }

    private static long positiveLong(String value) {
        if (value == null) return 0L;
        try { return Math.max(0L, Long.parseLong(value.trim())); }
        catch (NumberFormatException ignored) { return 0L; }
    }

    private static long[] parseContentRange(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        if (!normalized.regionMatches(true, 0, "bytes ", 0, 6)) return null;
        int dash = normalized.indexOf('-', 6);
        int slash = normalized.indexOf('/', dash + 1);
        if (dash <= 6 || slash <= dash + 1 || slash + 1 >= normalized.length()) return null;
        try {
            long start = Long.parseLong(normalized.substring(6, dash).trim());
            long end = Long.parseLong(normalized.substring(dash + 1, slash).trim());
            long total = Long.parseLong(normalized.substring(slash + 1).trim());
            if (start < 0L || end < start || total <= end) return null;
            return new long[] { start, end, total };
        } catch (NumberFormatException ignored) { return null; }
    }

    private static void setCurrentThreadPriority(int priority) {
        try { Process.setThreadPriority(Process.myTid(), priority); }
        catch (RuntimeException ignored) {}
    }

    private static String safeMessage(Throwable error) {
        if (error == null) return "未知错误";
        String message = error.getMessage();
        if (message == null || message.trim().length() == 0) message = error.getClass().getSimpleName();
        if (message.length() > 140) message = message.substring(0, 140);
        return message;
    }

    private static boolean isRetryable(Exception error) {
        return !(error instanceof HttpFailure) ||
                DownloadRetryPolicy.isRetryableHttp(((HttpFailure) error).status);
    }

    private static boolean validatorChanged(String stored, String received) {
        String left = value(stored);
        String right = value(received);
        return left.length() > 0 && right.length() > 0 && !left.equals(right);
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable != null) try { closeable.close(); } catch (Exception ignored) {}
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists()) try { file.delete(); } catch (RuntimeException ignored) {}
    }

    private static String value(String value) { return value == null ? "" : value; }

    private final class ProgressTracker {
        private static final long REPORT_INTERVAL_MS = 750L;
        final Task task;
        final Profile profile;
        final Control control;
        final AtomicLong downloaded = new AtomicLong();
        final AtomicInteger retryCount = new AtomicInteger();
        volatile long totalBytes;
        volatile String strategy = "";
        private long lastReportAt = SystemClock.elapsedRealtime();
        private long lastReportBytes;
        private final AtomicLong nextReportAt = new AtomicLong(lastReportAt + REPORT_INTERVAL_MS);
        private final AtomicLong nextStatePersistAt = new AtomicLong(lastReportAt + 2000L);

        ProgressTracker(Task task, Profile profile, Control control, long totalBytes, long initialDownloaded) {
            this.task = task;
            this.profile = profile;
            this.control = control;
            this.totalBytes = Math.max(0L, totalBytes);
            this.downloaded.set(Math.max(0L, initialDownloaded));
            this.lastReportBytes = this.downloaded.get();
        }

        void setStrategy(int segments, boolean ranged) {
            strategy = "Median 单连接";
        }

        void reset(long bytes) {
            downloaded.set(Math.max(0L, bytes));
            lastReportBytes = downloaded.get();
            lastReportAt = SystemClock.elapsedRealtime();
        }

        void add(int count, DownloadState state, File stateFile) throws Cancelled, Paused {
            control.check();
            downloaded.addAndGet(count);
            long now = SystemClock.elapsedRealtime();
            long persistAt = nextStatePersistAt.get();
            if (now >= persistAt && nextStatePersistAt.compareAndSet(persistAt, now + 2000L)) {
                state.persist(stateFile, false);
            }
            long reportAt = nextReportAt.get();
            if (now >= reportAt && nextReportAt.compareAndSet(reportAt, now + REPORT_INTERVAL_MS)) {
                reportState(DownloadStore.STATUS_DOWNLOADING, strategy, state, null, false);
            }
        }

        synchronized void reportState(String status, String reason, DownloadState state, String uri, boolean force) {
            long now = SystemClock.elapsedRealtime();
            if (!force && now - lastReportAt < REPORT_INTERVAL_MS) return;
            long current = downloaded.get();
            long elapsed = Math.max(1L, now - lastReportAt);
            long speed = DownloadStore.STATUS_DOWNLOADING.equals(status) ?
                    Math.max(0L, (current - lastReportBytes) * 1000L / elapsed) : 0L;
            long eta = speed > 0L && totalBytes > current ? (totalBytes - current) / speed : 0L;
            store.updateAdaptiveTelemetry(task.id, status, reason, current, totalBytes, speed, uri,
                    state.segments.size(), profile.bufferBytes, profile.memoryBudgetBytes,
                    state.rangeSupported, eta, retryCount.get());
            if (DownloadStore.STATUS_DOWNLOADING.equals(status)) {
                int pct = percent(current, totalBytes);
                String progressText = totalBytes > 0L ? pct + "%" :
                        (current > 0L ? "已下载 " + humanBytes(current) + " · 大小未知" : "正在获取大小");
                notifyTask(task, "下载中 · " + progressText + " · " + humanSpeed(speed), pct,
                        current, totalBytes, true, false);
            }
            lastReportAt = now;
            lastReportBytes = current;
            nextReportAt.set(now + REPORT_INTERVAL_MS);
        }

        void forceReport(String status, String reason, DownloadState state, String uri) {
            reportState(status, reason, state, uri, true);
        }
    }

    private static String humanSpeed(long bytesPerSecond) {
        if (bytesPerSecond < 1024L) return bytesPerSecond + " B/s";
        if (bytesPerSecond < 1024L * 1024L) return String.format(Locale.US, "%.1f KB/s", bytesPerSecond / 1024d);
        return String.format(Locale.US, "%.1f MB/s", bytesPerSecond / (1024d * 1024d));
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024L) return Math.max(0L, bytes) + " B";
        if (bytes < 1024L * 1024L) return String.format(Locale.US, "%.1f KB", bytes / 1024d);
        if (bytes < 1024L * 1024L * 1024L) return String.format(Locale.US, "%.1f MB", bytes / (1024d * 1024d));
        return String.format(Locale.US, "%.2f GB", bytes / (1024d * 1024d * 1024d));
    }

    private static final class Task {
        long id;
        String url;
        String filename;
        String mime;
        String userAgent;
        String cookie;
        String mode;
        boolean wifiOnly;
        boolean allowRoaming;
        boolean chargingOnly;
        boolean publicOnly;
        long expectedTotalBytes;
        final Map<String, String> headers = new HashMap<String, String>();

        static Task fromIntent(Intent intent) {
            Task task = new Task();
            task.id = intent.getLongExtra(EXTRA_ID, 0L);
            task.url = value(intent.getStringExtra(EXTRA_URL));
            task.filename = value(intent.getStringExtra(EXTRA_NAME));
            task.mime = value(intent.getStringExtra(EXTRA_MIME));
            task.userAgent = value(intent.getStringExtra(EXTRA_USER_AGENT));
            task.cookie = value(intent.getStringExtra(EXTRA_COOKIE));
            task.mode = value(intent.getStringExtra(EXTRA_MODE));
            task.wifiOnly = intent.getBooleanExtra(EXTRA_WIFI_ONLY, false);
            task.allowRoaming = intent.getBooleanExtra(EXTRA_ALLOW_ROAMING, false);
            task.chargingOnly = intent.getBooleanExtra(EXTRA_CHARGING_ONLY, false);
            task.publicOnly = intent.getBooleanExtra(EXTRA_PUBLIC_ONLY, false);
            task.expectedTotalBytes = Math.max(0L, intent.getLongExtra(EXTRA_TOTAL_BYTES, 0L));
            parseHeaders(value(intent.getStringExtra(EXTRA_HEADERS)), task.headers);
            return task;
        }

        static Task fromStore(Context context, DownloadStore.Item item) {
            Task task = new Task();
            task.id = item.id;
            task.url = value(item.url);
            task.filename = value(item.filename);
            task.mime = value(item.mime);
            task.userAgent = value(item.userAgent);
            try {
                String cookie = CookieManager.getInstance().getCookie(task.url);
                task.cookie = value(cookie);
            } catch (RuntimeException ignored) { task.cookie = ""; }
            task.mode = value(item.mode);
            task.wifiOnly = item.wifiOnly;
            task.allowRoaming = item.allowRoaming;
            task.chargingOnly = item.chargingOnly;
            task.publicOnly = item.publicOnly;
            task.expectedTotalBytes = Math.max(0L, item.totalBytes);
            parseHeaders(item.headersJson, task.headers);
            return task;
        }

        private static void parseHeaders(String json, Map<String, String> target) {
            try {
                JSONObject object = new JSONObject(value(json));
                Iterator<String> keys = object.keys();
                while (keys.hasNext()) {
                    String name = keys.next();
                    String headerValue = object.optString(name, "");
                    if (!NetworkSecurity.validHeader(name, headerValue) || NetworkSecurity.isForbiddenRequestHeader(name) ||
                            NetworkSecurity.isCredentialHeader(name) || "user-agent".equalsIgnoreCase(name)) continue;
                    target.put(name, headerValue);
                }
            } catch (Exception ignored) {}
        }
    }

    private static final class Profile {
        final int bufferBytes;
        final long memoryBudgetBytes;
        final int threadPriority;
        final int retries;
        final int connectTimeoutMs;
        final int readTimeoutMs;

        Profile(int bufferBytes, long memoryBudgetBytes, int threadPriority, int retries,
                int connectTimeoutMs, int readTimeoutMs) {
            this.bufferBytes = bufferBytes;
            this.memoryBudgetBytes = memoryBudgetBytes;
            this.threadPriority = threadPriority;
            this.retries = retries;
            this.connectTimeoutMs = connectTimeoutMs;
            this.readTimeoutMs = readTimeoutMs;
        }

        static Profile from(String mode, DownloadMemoryPolicy.Plan plan) {
            return new Profile(plan.bufferBytes, plan.memoryBudgetBytes,
                    Process.THREAD_PRIORITY_BACKGROUND, 4, 20000, 45000);
        }
    }

    private static final class Control {
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final AtomicBoolean paused = new AtomicBoolean(false);
        volatile String pauseReason = "已暂停";
        volatile HttpURLConnection connection;
        void cancel() { cancelled.set(true); disconnect(); }
        void pause(String reason) { pauseReason = value(reason); paused.set(true); disconnect(); }
        void attach(HttpURLConnection value) { connection = value; }
        void detach(HttpURLConnection value) { if (connection == value) connection = null; }
        void disconnect() {
            HttpURLConnection active = connection;
            if (active != null) try { active.disconnect(); } catch (RuntimeException ignored) {}
        }
        void check() throws Cancelled, Paused {
            if (cancelled.get()) throw new Cancelled();
            if (paused.get()) throw new Paused(pauseReason);
            if (Thread.currentThread().isInterrupted()) {
                if (paused.get()) throw new Paused(pauseReason);
                throw new Cancelled();
            }
        }
    }

    private static final class Cancelled extends Exception { private static final long serialVersionUID = 1L; }
    private static final class HttpFailure extends Exception {
        private static final long serialVersionUID = 1L;
        final int status;
        HttpFailure(int status) {
            super(DownloadRetryPolicy.messageForHttp(status));
            this.status = status;
        }
    }
    private static final class Paused extends Exception {
        private static final long serialVersionUID = 1L;
        final String reason;
        Paused(String reason) { this.reason = value(reason).length() == 0 ? "已暂停" : value(reason); }
    }

    private static final class Locks {
        PowerManager.WakeLock wakeLock;
        void release() {
            try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (RuntimeException ignored) {}
        }
    }

    private static final class Segment {
        long start;
        long end;
        volatile long cursor;
    }

    private static final class DownloadState {
        String originalUrl;
        String resolvedUrl;
        String etag;
        String lastModified;
        long totalBytes;
        boolean rangeSupported;
        final ArrayList<Segment> segments = new ArrayList<Segment>();
        private long lastPersistAt;

        static DownloadState create(Task task) {
            DownloadState state = new DownloadState();
            state.originalUrl = task.url;
            state.resolvedUrl = task.url;
            state.etag = "";
            state.lastModified = "";
            state.totalBytes = 0L;
            state.rangeSupported = false;
            Segment segment = new Segment();
            segment.start = 0L;
            segment.end = Long.MAX_VALUE;
            segment.cursor = 0L;
            state.segments.add(segment);
            return state;
        }

        long downloaded() {
            long total = 0L;
            for (Segment segment : segments) total += Math.max(0L, segment.cursor - segment.start);
            return total;
        }

        void advance(int index, int count) { segments.get(index).cursor += Math.max(0, count); }

        synchronized void persist(File file, boolean force) {
            long now = SystemClock.elapsedRealtime();
            if (!force && now - lastPersistAt < 2000L) return;
            FileOutputStream output = null;
            try {
                JSONObject object = new JSONObject();
                object.put("version", 1);
                object.put("originalUrl", originalUrl);
                object.put("resolvedUrl", resolvedUrl);
                object.put("etag", etag);
                object.put("lastModified", lastModified);
                object.put("totalBytes", totalBytes);
                object.put("rangeSupported", rangeSupported);
                JSONArray array = new JSONArray();
                for (Segment segment : segments) {
                    JSONObject item = new JSONObject();
                    item.put("start", segment.start);
                    item.put("end", segment.end);
                    item.put("cursor", segment.cursor);
                    array.put(item);
                }
                object.put("segments", array);
                File temp = new File(file.getAbsolutePath() + ".tmp");
                output = new FileOutputStream(temp, false);
                output.write(object.toString().getBytes("UTF-8"));
                if (force) output.getFD().sync();
                output.close();
                output = null;
                if (file.exists() && !file.delete()) return;
                if (!temp.renameTo(file)) temp.delete();
                lastPersistAt = now;
            } catch (Exception ignored) {
            } finally { closeQuietly(output); }
        }

        static DownloadState load(File file) {
            if (file == null || !file.isFile() || file.length() <= 0L || file.length() > 512L * 1024L) return null;
            FileInputStream input = null;
            try {
                input = new FileInputStream(file);
                byte[] data = new byte[(int) file.length()];
                int offset = 0;
                while (offset < data.length) {
                    int read = input.read(data, offset, data.length - offset);
                    if (read < 0) break;
                    offset += read;
                }
                JSONObject object = new JSONObject(new String(data, 0, offset, "UTF-8"));
                if (object.optInt("version", 0) != 1) return null;
                DownloadState state = new DownloadState();
                state.originalUrl = object.optString("originalUrl", "");
                state.resolvedUrl = object.optString("resolvedUrl", state.originalUrl);
                state.etag = object.optString("etag", "");
                state.lastModified = object.optString("lastModified", "");
                state.totalBytes = object.optLong("totalBytes", 0L);
                state.rangeSupported = object.optBoolean("rangeSupported", false);
                JSONArray array = object.optJSONArray("segments");
                if (array == null) return null;
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.optJSONObject(i);
                    if (item == null) continue;
                    Segment segment = new Segment();
                    segment.start = item.optLong("start", 0L);
                    segment.end = item.optLong("end", -1L);
                    segment.cursor = Math.max(segment.start, item.optLong("cursor", segment.start));
                    if (segment.end != Long.MAX_VALUE) segment.cursor = Math.min(segment.end + 1L, segment.cursor);
                    if (segment.end >= segment.start || segment.end == Long.MAX_VALUE) state.segments.add(segment);
                }
                return state.segments.size() == 0 ? null : state;
            } catch (Exception ignored) { return null;
            } finally { closeQuietly(input); }
        }
    }
}
