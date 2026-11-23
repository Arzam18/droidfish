package org.petero.droidfish.tournament;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.os.Process;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Bounded asynchronous tournament diagnostics. Engine and UI threads only enqueue
 * records; a dedicated low-priority writer performs file I/O and periodic fsync.
 * The last durable checkpoint is therefore useful after process death without
 * forcing an fsync for every engine/worker event.
 */
public final class TournamentDiagnostics {
    private static final String TAG = "CET-Diagnostics";
    private static final long MAX_LOG_BYTES = 2L * 1024L * 1024L;
    private static final long SYNC_INTERVAL_NS = 1_000_000_000L;
    private static final int QUEUE_CAPACITY = 2048;
    private static final Object START_LOCK = new Object();
    private static final ArrayBlockingQueue<Record> QUEUE = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private static volatile Context appContext;
    private static volatile String currentPhase = "idle";
    private static volatile String tournamentState = "idle";
    private static volatile Thread writerThread;
    private static volatile long droppedRecords;
    private static volatile File resolvedDiagnosticsDirectory;
    private static volatile boolean usingSharedStorage;

    private TournamentDiagnostics() { }

    private static final class Record {
        final String category, event, detail, sessionState;
        final boolean forceSync;
        final CountDownLatch barrier;
        Record(String category, String event, String detail, String sessionState,
               boolean forceSync, CountDownLatch barrier) {
            this.category = category; this.event = event; this.detail = detail;
            this.sessionState = sessionState; this.forceSync = forceSync; this.barrier = barrier;
        }
    }

    public static void initialize(Context context) {
        if (context == null) return;
        appContext = context.getApplicationContext();
        ensureWriter();
        File activeDir = diagnosticsDir(true);
        String previous = readSmallFile(new File(activeDir, "session.state"));
        if (previous == null) {
            File legacyState = new File(new File(appContext.getFilesDir(), "tournament-diagnostics"), "session.state");
            previous = readSmallFile(legacyState);
        }
        if (previous != null && previous.startsWith("RUNNING|")) {
            enqueue(new Record("RECOVERY", "unclean_previous_process",
                    "Previous process ended while tournament was marked RUNNING; this indicates interruption, not a proven cause. Previous state=" + previous,
                    null, true, null), true);
        } else if (previous != null && previous.startsWith("PAUSED|")) {
            enqueue(new Record("RECOVERY", "previous_session_was_paused", previous, null, true, null), true);
        }
        enqueue(new Record("APP", "diagnostics_initialized",
                "sdk=" + Build.VERSION.SDK_INT + " device=" + Build.MANUFACTURER + "/" + Build.MODEL +
                        " pid=" + Process.myPid() + " logPath=" + new File(activeDir, "error_log.txt").getAbsolutePath() +
                        " sharedStorage=" + usingSharedStorage,
                "IDLE|" + System.currentTimeMillis() + "|startup", true, null), true);
    }

    private static void ensureWriter() {
        if (writerThread != null) return;
        synchronized (START_LOCK) {
            if (writerThread != null) return;
            Thread thread = new Thread(TournamentDiagnostics::writerLoop, "CET-Diagnostic-Writer");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            writerThread = thread;
            thread.start();
        }
    }

    public static void setPhase(String phase, String detail) {
        currentPhase = phase == null ? "unknown" : phase;
        record("PHASE", currentPhase, detail);
    }

    public static String currentPhase() { return currentPhase; }

    public static void setTournamentState(String state, String detail) {
        String safe = state == null ? "unknown" : state.toUpperCase(java.util.Locale.US);
        tournamentState = safe.toLowerCase(java.util.Locale.US);
        enqueue(new Record("TOURNAMENT", safe, detail,
                safe + "|" + System.currentTimeMillis() + "|" + clean(detail), true, null), true);
    }

    public static void record(String category, String event, String detail) {
        enqueue(new Record(category, event, detail, null, false, null), false);
    }

    public static void recordError(String category, String event, Throwable error) {
        StringBuilder detail = new StringBuilder();
        if (error != null) {
            detail.append(error.getClass().getName()).append(": ").append(error.getMessage());
            for (StackTraceElement element : error.getStackTrace()) detail.append("\n    at ").append(element);
        }
        enqueue(new Record(category, event, detail.toString(), null, true, null), true);
    }

    /** Called by the independent watchdog when the main/UI thread stops pumping events. */
    public static void recordUiStall(long stalledMs, Thread mainThread) {
        StringBuilder report = new StringBuilder(4096);
        report.append("stalledMs=").append(stalledMs).append(" phase=").append(currentPhase)
                .append(" tournamentState=").append(tournamentState).append("\n");
        if (mainThread != null) {
            report.append("MAIN THREAD ").append(mainThread.getName()).append(" state=").append(mainThread.getState()).append('\n');
            for (StackTraceElement element : mainThread.getStackTrace()) report.append("  at ").append(element).append('\n');
        }
        report.append("THREAD SNAPSHOTS\n");
        int threadCount = 0;
        for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
            if (threadCount++ >= 32) { report.append("  ... thread list truncated\n"); break; }
            Thread thread = entry.getKey();
            report.append("THREAD ").append(thread.getName()).append(" state=").append(thread.getState()).append('\n');
            StackTraceElement[] stack = entry.getValue();
            int limit = Math.min(stack == null ? 0 : stack.length, 24);
            for (int i = 0; i < limit; i++) report.append("  at ").append(stack[i]).append('\n');
        }
        enqueue(new Record("WATCHDOG", "ui_stall_snapshot", report.toString(), null, true, null), true);
    }

    public static String readLog(Context context) {
        Context app = context == null ? appContext : context.getApplicationContext();
        if (app == null) return "Diagnostics are not initialized.";
        // Refresh shared-storage access first, then place a barrier behind it so
        // the writer closes/reopens its stream at the selected public/private path.
        File activeDir = diagnosticsDir(true);
        CountDownLatch barrier = new CountDownLatch(1);
        enqueue(new Record("", "", "", null, true, barrier), true);
        try { barrier.await(750L, TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }

        StringBuilder out = new StringBuilder();
        File privateDir = new File(app.getFilesDir(), "tournament-diagnostics");
        // Keep both older private formats visible after changing storage locations.
        appendFileToString(new File(privateDir, "diagnostics.previous.log"), out);
        appendFileToString(new File(privateDir, "diagnostics.log"), out);
        if (!activeDir.equals(privateDir)) {
            appendFileToString(new File(privateDir, "error_log_previous.txt"), out);
            appendFileToString(new File(privateDir, "error_log.txt"), out);
        }
        appendFileToString(new File(activeDir, "error_log_previous.txt"), out);
        appendFileToString(new File(activeDir, "error_log.txt"), out);
        return out.length() == 0 ? "No tournament diagnostic events have been recorded yet. Check shared-storage permission and relaunch DroidFish." : out.toString();
    }

    public static String getLogPath(Context context) {
        Context app = context == null ? appContext : context.getApplicationContext();
        if (app == null) return "unavailable";
        File directory = diagnosticsDir(true);
        return directory == null ? "unavailable" : new File(directory, "error_log.txt").getAbsolutePath();
    }

    private static File diagnosticsDir() { return diagnosticsDir(false); }

    /** Re-check shared-storage access when the app initializes or the user opens the log viewer. */
    private static File diagnosticsDir(boolean refresh) {
        File cached = resolvedDiagnosticsDirectory;
        if (!refresh && cached != null) return cached;
        Context context = appContext;
        if (context == null) return null;
        synchronized (START_LOCK) {
            cached = resolvedDiagnosticsDirectory;
            if (!refresh && cached != null) return cached;

            // Android 10+ uses scoped storage for newer targets. This application
            // still targets API 28 and already requests WRITE_EXTERNAL_STORAGE,
            // so use the conventional shared DroidFish folder when permission is granted.
            boolean permissionGranted = Build.VERSION.SDK_INT < 23 ||
                    context.checkCallingOrSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                            PackageManager.PERMISSION_GRANTED;
            if (permissionGranted && Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
                File externalRoot = Environment.getExternalStorageDirectory();
                File publicDir = new File(new File(externalRoot, "DroidFish"), "error_log");
                try {
                    if ((publicDir.isDirectory() || publicDir.mkdirs()) && publicDir.isDirectory()) {
                        // Verify actual create/write access rather than relying on canWrite().
                        File probe = new File(publicDir, ".write_check_" + Process.myPid());
                        try (FileOutputStream out = new FileOutputStream(probe, false)) {
                            out.write(1);
                            out.flush();
                        }
                        //noinspection ResultOfMethodCallIgnored
                        probe.delete();
                        usingSharedStorage = true;
                        resolvedDiagnosticsDirectory = publicDir;
                        return publicDir;
                    }
                } catch (Exception denied) {
                    android.util.Log.w(TAG, "Shared diagnostic directory unavailable; using private fallback", denied);
                }
            }
            File privateDir = new File(context.getFilesDir(), "tournament-diagnostics");
            usingSharedStorage = false;
            resolvedDiagnosticsDirectory = privateDir;
            return privateDir;
        }
    }

    private static void enqueue(Record record, boolean important) {
        if (appContext == null) return;
        ensureWriter();
        if (QUEUE.offer(record)) return;
        droppedRecords++;
        if (important) {
            // Preserve critical failure/watchdog records by evicting one oldest item.
            QUEUE.poll();
            if (!QUEUE.offer(record)) android.util.Log.e(TAG, "Diagnostic queue full; critical record could not be queued");
        }
    }

    private static void writerLoop() {
        FileOutputStream stream = null;
        File streamFile = null;
        long lastSyncNs = System.nanoTime();
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Record record;
                try { record = QUEUE.poll(500L, TimeUnit.MILLISECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                try {
                    File dir = diagnosticsDir();
                    if (dir == null) continue;
                    if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory()) continue;
                    File desiredLog = new File(dir, "error_log.txt");
                    if (stream != null && streamFile != null && !desiredLog.equals(streamFile)) {
                        stream.flush(); stream.getFD().sync(); stream.close();
                        stream = null; streamFile = null;
                    }
                    if (record != null && record.barrier != null && record.category.isEmpty()) {
                        if (stream != null) { stream.flush(); stream.getFD().sync(); }
                        lastSyncNs = System.nanoTime();
                        record.barrier.countDown();
                        continue;
                    }
                    if (record != null && record.sessionState != null)
                        writeSmallFile(new File(dir, "session.state"), record.sessionState);
                    if (record != null && !record.category.isEmpty()) {
                        File log = desiredLog;
                        if (stream == null) {
                            stream = new FileOutputStream(log, true);
                            streamFile = log;
                        }
                        String line = System.currentTimeMillis() + "\t" + System.nanoTime() + "\tpid=" + Process.myPid() +
                                "\ttid=" + Thread.currentThread().getId() + "\t" + clean(record.category) + "\t" + clean(record.event) +
                                "\tphase=" + clean(currentPhase) + "\t" + clean(record.detail) + "\tdropped=" + droppedRecords + "\n";
                        stream.write(line.getBytes(Charset.forName("UTF-8")));
                    }
                    long now = System.nanoTime();
                    if (stream != null && (record != null && record.forceSync || now - lastSyncNs >= SYNC_INTERVAL_NS)) {
                        stream.flush();
                        stream.getFD().sync();
                        lastSyncNs = now;
                    }
                    File log = new File(dir, "error_log.txt");
                    if (stream != null && log.length() > MAX_LOG_BYTES) {
                        stream.flush(); stream.getFD().sync(); stream.close(); stream = null; streamFile = null;
                        File previous = new File(dir, "error_log_previous.txt");
                        if (previous.exists() && !previous.delete()) android.util.Log.w(TAG, "Could not delete previous diagnostic log");
                        if (log.exists() && !log.renameTo(previous)) android.util.Log.w(TAG, "Could not rotate diagnostic log");
                    }
                } catch (Exception error) {
                    android.util.Log.e(TAG, "Unable to persist tournament diagnostic", error);
                    if (stream != null) { try { stream.close(); } catch (Exception ignored) { } stream = null; streamFile = null; }
                }
            }
        } finally {
            if (stream != null) {
                try { stream.flush(); stream.getFD().sync(); stream.close(); }
                catch (Exception error) { android.util.Log.e(TAG, "Unable to flush diagnostic log", error); }
            }
        }
    }

    private static String clean(String value) {
        if (value == null) return "";
        return value.replace('\r', ' ').replace('\n', '|').replace('\t', ' ').trim();
    }

    private static String readSmallFile(File file) {
        if (file == null || !file.isFile()) return null;
        StringBuilder out = new StringBuilder();
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[1024]; int n;
            while ((n = in.read(buffer)) > 0 && out.length() < 4096) out.append(new String(buffer, 0, n, Charset.forName("UTF-8")));
        } catch (Exception ignored) { }
        return out.toString().trim();
    }

    private static void writeSmallFile(File file, String value) {
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp, false)) {
            out.write((value == null ? "" : value).getBytes(Charset.forName("UTF-8")));
            out.flush(); out.getFD().sync();
        } catch (Exception error) {
            android.util.Log.e(TAG, "Unable to write session state", error);
            return;
        }
        if (file.exists() && !file.delete()) android.util.Log.w(TAG, "Could not replace session state");
        if (!temp.renameTo(file)) android.util.Log.w(TAG, "Could not publish session state");
    }

    private static void appendFileToString(File file, StringBuilder out) {
        if (!file.isFile()) return;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (out.length() > 512 * 1024) { out.append("\n[Display truncated; log file is larger than 512 KiB]\n"); break; }
                out.append(line).append('\n');
            }
        } catch (Exception error) {
            out.append("Unable to read ").append(file.getName()).append(": ").append(error.getMessage()).append('\n');
        }
    }
}
