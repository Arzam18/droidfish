package org.petero.droidfish.tournament;

import android.content.Context;
import android.os.Build;
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
        String previous = readSmallFile(new File(diagnosticsDir(), "session.state"));
        if (previous != null && previous.startsWith("RUNNING|")) {
            enqueue(new Record("RECOVERY", "unclean_previous_process",
                    "Previous process ended while tournament was marked RUNNING; this indicates interruption, not a proven cause. Previous state=" + previous,
                    null, true, null), true);
        } else if (previous != null && previous.startsWith("PAUSED|")) {
            enqueue(new Record("RECOVERY", "previous_session_was_paused", previous, null, true, null), true);
        }
        enqueue(new Record("APP", "diagnostics_initialized",
                "sdk=" + Build.VERSION.SDK_INT + " device=" + Build.MANUFACTURER + "/" + Build.MODEL + " pid=" + Process.myPid(),
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
        // Ask the writer to flush recent records, but never hold the UI for long.
        CountDownLatch barrier = new CountDownLatch(1);
        enqueue(new Record("", "", "", null, true, barrier), true);
        try { barrier.await(750L, TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        File dir = new File(app.getFilesDir(), "tournament-diagnostics");
        File previous = new File(dir, "diagnostics.previous.log");
        File current = new File(dir, "diagnostics.log");
        StringBuilder out = new StringBuilder();
        appendFileToString(previous, out);
        appendFileToString(current, out);
        return out.length() == 0 ? "No tournament diagnostic events have been recorded yet." : out.toString();
    }

    public static String getLogPath(Context context) {
        Context app = context == null ? appContext : context.getApplicationContext();
        return app == null ? "unavailable" : new File(new File(app.getFilesDir(), "tournament-diagnostics"), "diagnostics.log").getAbsolutePath();
    }

    private static File diagnosticsDir() {
        Context context = appContext;
        return context == null ? null : new File(context.getFilesDir(), "tournament-diagnostics");
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
                    if (record != null && record.barrier != null && record.category.isEmpty()) {
                        if (stream != null) { stream.flush(); stream.getFD().sync(); }
                        lastSyncNs = System.nanoTime();
                        record.barrier.countDown();
                        continue;
                    }
                    if (record != null && record.sessionState != null)
                        writeSmallFile(new File(dir, "session.state"), record.sessionState);
                    if (record != null && !record.category.isEmpty()) {
                        File log = new File(dir, "diagnostics.log");
                        if (stream == null) stream = new FileOutputStream(log, true);
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
                    File log = new File(dir, "diagnostics.log");
                    if (stream != null && log.length() > MAX_LOG_BYTES) {
                        stream.flush(); stream.getFD().sync(); stream.close(); stream = null;
                        File previous = new File(dir, "diagnostics.previous.log");
                        if (previous.exists() && !previous.delete()) android.util.Log.w(TAG, "Could not delete previous diagnostic log");
                        if (log.exists() && !log.renameTo(previous)) android.util.Log.w(TAG, "Could not rotate diagnostic log");
                    }
                } catch (Exception error) {
                    android.util.Log.e(TAG, "Unable to persist tournament diagnostic", error);
                    if (stream != null) { try { stream.close(); } catch (Exception ignored) { } stream = null; }
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
