package org.petero.droidfish.tournament;

import android.content.Context;
import android.os.Build;
import android.os.Process;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.Map;

/**
 * Small, durable tournament diagnostics journal. Writes are intentionally limited
 * to lifecycle milestones and incidents, never to high-frequency engine info lines.
 */
public final class TournamentDiagnostics {
    private static final String TAG = "CET-Diagnostics";
    private static final long MAX_LOG_BYTES = 2L * 1024L * 1024L;
    private static final Object LOCK = new Object();
    private static volatile Context appContext;
    private static volatile String currentPhase = "idle";
    private static volatile String tournamentState = "idle";

    private TournamentDiagnostics() { }

    public static void initialize(Context context) {
        if (context == null) return;
        appContext = context.getApplicationContext();
        synchronized (LOCK) {
            File dir = diagnosticsDir();
            if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory()) return;
            String previous = readSmallFile(new File(dir, "session.state"));
            if (previous != null && previous.startsWith("RUNNING|")) {
                appendLocked("RECOVERY", "unclean_previous_process",
                        "The previous process ended while a tournament was marked RUNNING. This indicates interruption, not a proven cause. Previous state=" + previous);
            } else if (previous != null && previous.startsWith("PAUSED|")) {
                appendLocked("RECOVERY", "previous_session_was_paused", previous);
            }
            writeSmallFile(new File(dir, "session.state"), "IDLE|" + System.currentTimeMillis() + "|startup");
            appendLocked("APP", "diagnostics_initialized", "sdk=" + Build.VERSION.SDK_INT + " device=" + Build.MANUFACTURER + "/" + Build.MODEL + " pid=" + Process.myPid());
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
        synchronized (LOCK) {
            File dir = diagnosticsDir();
            if (dir == null || (!dir.exists() && !dir.mkdirs() && !dir.isDirectory())) return;
            writeSmallFile(new File(dir, "session.state"), safe + "|" + System.currentTimeMillis() + "|" + clean(detail));
            appendLocked("TOURNAMENT", safe, detail);
        }
    }

    public static void record(String category, String event, String detail) {
        synchronized (LOCK) {
            File dir = diagnosticsDir();
            if (dir == null || (!dir.exists() && !dir.mkdirs() && !dir.isDirectory())) return;
            appendLocked(category, event, detail);
        }
    }

    public static void recordError(String category, String event, Throwable error) {
        StringBuilder detail = new StringBuilder();
        if (error != null) {
            detail.append(error.getClass().getName()).append(": ").append(error.getMessage());
            for (StackTraceElement element : error.getStackTrace()) detail.append("\n    at ").append(element);
        }
        record(category, event, detail.toString());
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
        record("WATCHDOG", "ui_stall_snapshot", report.toString());
    }

    public static String readLog(Context context) {
        Context app = context == null ? appContext : context.getApplicationContext();
        if (app == null) return "Diagnostics are not initialized.";
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

    private static void appendLocked(String category, String event, String detail) {
        File dir = diagnosticsDir();
        if (dir == null) return;
        File log = new File(dir, "diagnostics.log");
        try {
            if (log.exists() && log.length() > MAX_LOG_BYTES) {
                File previous = new File(dir, "diagnostics.previous.log");
                if (previous.exists() && !previous.delete()) previous.deleteOnExit();
                if (!log.renameTo(previous)) {
                    // Fall back to truncation only after keeping the prior file where possible.
                    try (FileOutputStream truncate = new FileOutputStream(log, false)) { truncate.write(new byte[0]); truncate.getFD().sync(); }
                }
            }
            String line = System.currentTimeMillis() + "\t" + System.nanoTime() + "\tpid=" + Process.myPid() +
                    "\ttid=" + Thread.currentThread().getId() + "\t" + clean(category) + "\t" + clean(event) +
                    "\tphase=" + clean(currentPhase) + "\t" + clean(detail) + "\n";
            try (FileOutputStream out = new FileOutputStream(log, true)) {
                out.write(line.getBytes(Charset.forName("UTF-8")));
                out.flush();
                out.getFD().sync();
            }
        } catch (IOException | SecurityException error) {
            android.util.Log.e(TAG, "Unable to persist tournament diagnostic", error);
        }
    }

    private static String clean(String value) {
        if (value == null) return "";
        return value.replace('\r', ' ').replace('\n', '|').replace('\t', ' ').trim();
    }

    private static String readSmallFile(File file) {
        if (!file.isFile()) return null;
        StringBuilder out = new StringBuilder();
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[1024]; int n;
            while ((n = in.read(buffer)) > 0 && out.length() < 4096) out.append(new String(buffer, 0, n, Charset.forName("UTF-8")));
        } catch (IOException ignored) { }
        return out.toString().trim();
    }

    private static void writeSmallFile(File file, String value) {
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            out.write((value == null ? "" : value).getBytes(Charset.forName("UTF-8")));
            out.flush(); out.getFD().sync();
        } catch (IOException | SecurityException error) {
            android.util.Log.e(TAG, "Unable to write session state", error);
        }
    }

    private static void appendFileToString(File file, StringBuilder out) {
        if (!file.isFile()) return;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (out.length() > 512 * 1024) { out.append("\n[Display truncated; log file is larger than 512 KiB]\n"); break; }
                out.append(line).append('\n');
            }
        } catch (IOException error) {
            out.append("Unable to read ").append(file.getName()).append(": ").append(error.getMessage()).append('\n');
        }
    }
}
