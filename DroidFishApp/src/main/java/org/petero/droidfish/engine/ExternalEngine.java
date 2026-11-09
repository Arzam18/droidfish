/*
    DroidFish - An Android chess program.
    Copyright (C) 2011-2014  Peter Österlund, peterosterlund2@gmail.com

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package org.petero.droidfish.engine;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.channels.FileChannel;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.petero.droidfish.DroidFishApp;
import org.petero.droidfish.EngineOptions;
import org.petero.droidfish.R;
import android.content.Context;

/** Engine running as a process started from an external resource. */
public class ExternalEngine extends UCIEngineBase {
    /* Each engine process needs its own executable path. Android may reject replacing
       an executable while another process is still running it (ETXTBSY). */
    private static final AtomicLong ENGINE_INSTANCE_ID = new AtomicLong();
    private static final Object INSTANCE_DIR_LOCK = new Object();
    private static final Set<String> ACTIVE_INSTANCE_DIRS = new HashSet<>();
    private static final int STDERR_TAIL_LIMIT = 4096;
    protected final Context context;

    private File engineFileName;
    private File engineWorkDir;
    private File engineInstanceDir;
    private final Report report;
    private Process engineProc;
    private Thread startupThread;
    private Thread exitThread;
    private Thread stdInThread;
    private Thread stdErrThread;
    private final LocalPipe inLines;
    private final StringBuilder stderrTail = new StringBuilder();
    private final AtomicBoolean errorReported = new AtomicBoolean(false);
    private volatile boolean startedOk;
    private volatile boolean isRunning;
    private volatile boolean shuttingDown;

    public ExternalEngine(String engine, String workDir, Report report) {
        context = DroidFishApp.getContext();
        this.report = report;
        engineFileName = new File(engine);
        engineWorkDir = new File(workDir == null ? "" : workDir);
        engineProc = null;
        startupThread = null;
        exitThread = null;
        stdInThread = null;
        stdErrThread = null;
        inLines = new LocalPipe();
        startedOk = false;
        isRunning = false;
    }

    protected String internalSFPath() {
        return context.getFilesDir().getAbsolutePath() + "/internal_sf";
    }

    @Override
    protected void startProcess() {
        try {
            // Each live process gets a private executable path. Register it before
            // pruning leftovers so concurrent White/Black startup cannot delete it.
            File engineRoot = new File(context.getFilesDir(), "engine");
            if (!engineRoot.exists() && !engineRoot.mkdirs())
                throw new IOException("Could not create engine directory: " + engineRoot);
            engineInstanceDir = new File(engineRoot, "instance_" + android.os.Process.myPid() + "_" + ENGINE_INSTANCE_ID.incrementAndGet());
            synchronized (INSTANCE_DIR_LOCK) {
                if (!engineInstanceDir.exists() && !engineInstanceDir.mkdirs())
                    throw new IOException("Could not create private engine directory: " + engineInstanceDir);
                ACTIVE_INSTANCE_DIRS.add(engineInstanceDir.getAbsolutePath());
                cleanStaleEngineDirsLocked(engineRoot);
            }

            String exePath = copyFile(engineFileName, engineInstanceDir);
            chmod(exePath);
            cleanUpExeDir(engineInstanceDir, exePath);
            ProcessBuilder pb = new ProcessBuilder(exePath);
            if (engineWorkDir.canRead() && engineWorkDir.isDirectory())
                pb.directory(engineWorkDir);
            synchronized (EngineUtil.nativeLock) {
                engineProc = pb.start();
            }
            final Process startedProcess = engineProc;
            reNice();

            startupThread = new Thread(() -> {
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    return;
                }
                if (!shuttingDown && startedOk && isRunning && !isUCI)
                    reportProcessError(context.getString(R.string.uci_protocol_error));
            }, "Engine-Startup-Watchdog");

            // Drain both output streams before starting the exit watcher. In particular,
            // stderr must be consumed even when a native engine exits during startup.
            stdInThread = new Thread(() -> {
                Process ep = startedProcess;
                InputStream is = ep.getInputStream();
                InputStreamReader isr = new InputStreamReader(is);
                BufferedReader br = new BufferedReader(isr, 8192);
                String line;
                try {
                    boolean first = true;
                    while ((line = br.readLine()) != null) {
                        if (Thread.currentThread().isInterrupted())
                            return;
                        synchronized (inLines) {
                            inLines.addLine(line);
                            if (first) {
                                startedOk = true;
                                isRunning = true;
                                first = false;
                            }
                        }
                    }
                } catch (IOException ignore) {
                }
                inLines.close();
            }, "Engine-Stdout");

            stdErrThread = new Thread(() -> {
                Process ep = startedProcess;
                try (BufferedReader br = new BufferedReader(new InputStreamReader(ep.getErrorStream()), 2048)) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (Thread.currentThread().isInterrupted())
                            return;
                        appendStderr(line);
                    }
                } catch (IOException ignore) {
                }
            }, "Engine-Stderr");

            exitThread = new Thread(() -> {
                try {
                    Process ep = startedProcess;
                    int exitCode = ep.waitFor();
                    isRunning = false;
                    Thread stderrReader = stdErrThread;
                    if (stderrReader != null) {
                        try { stderrReader.join(500); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    }
                    Thread startupWatchdog = startupThread;
                    if (startupWatchdog != null)
                        startupWatchdog.interrupt();
                    if (!shuttingDown) {
                        String message = startedOk
                                ? context.getString(R.string.engine_terminated)
                                : context.getString(R.string.failed_to_start_engine);
                        message += " (exit code " + exitCode + ")";
                        message += "\nExecutable: " + engineFileName.getAbsolutePath();
                        if (engineWorkDir != null && engineWorkDir.getPath().length() > 0)
                            message += "\nWorking directory: " + engineWorkDir.getAbsolutePath();
                        String stderr = getStderrTail();
                        if (!stderr.isEmpty())
                            message += "\nEngine stderr:\n" + stderr;
                        reportProcessError(message);
                    }
                } catch (InterruptedException ignore) {
                    // Explicit shutdown interrupts this watcher; it must not report an error.
                }
            }, "Engine-Exit-Watcher");

            startupThread.start();
            stdInThread.start();
            stdErrThread.start();
            exitThread.start();
        } catch (IOException | SecurityException ex) {
            String message = "Could not start engine '" + engineFileName.getAbsolutePath() + "': " + ex.getMessage();
            if (engineWorkDir != null && engineWorkDir.getPath().length() > 0)
                message += "\nWorking directory: " + engineWorkDir.getAbsolutePath();
            reportProcessError(message);
            Process ep = engineProc;
            if (ep != null) {
                try { ep.destroy(); } catch (RuntimeException ignore) { }
                waitForProcessExit(ep, 1000);
                engineProc = null;
            }
            inLines.close();
            releaseEngineInstanceDir();
        }
    }

    private void reportProcessError(String message) {
        if (!shuttingDown && errorReported.compareAndSet(false, true))
            report.reportError(message == null ? "Engine process failed" : message);
    }

    private void appendStderr(String line) {
        if (line == null || line.isEmpty()) return;
        synchronized (stderrTail) {
            stderrTail.append(line).append('\n');
            if (stderrTail.length() > STDERR_TAIL_LIMIT)
                stderrTail.delete(0, stderrTail.length() - STDERR_TAIL_LIMIT);
        }
    }

    private String getStderrTail() {
        synchronized (stderrTail) {
            return stderrTail.toString().trim();
        }
    }

    /** Remove old private executable directories left by previous app/process runs. */
    private static void cleanStaleEngineDirsLocked(File engineRoot) {
        File[] entries = engineRoot.listFiles();
        if (entries == null) return;
        for (File entry : entries) {
            if (!entry.isDirectory() || !entry.getName().startsWith("instance_")) continue;
            if (!ACTIVE_INSTANCE_DIRS.contains(entry.getAbsolutePath()))
                deleteRecursively(entry);
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }

    /** Release this process' private copy only after its child process has stopped. */
    private void releaseEngineInstanceDir() {
        File dir = engineInstanceDir;
        if (dir == null) return;
        synchronized (INSTANCE_DIR_LOCK) {
            ACTIVE_INSTANCE_DIRS.remove(dir.getAbsolutePath());
            deleteRecursively(dir);
        }
        engineInstanceDir = null;
    }

    /** Try to lower the engine process priority. */
    private void reNice() {
        try {
            java.lang.reflect.Field f = engineProc.getClass().getDeclaredField("pid");
            f.setAccessible(true);
            int pid = f.getInt(engineProc);
            EngineUtil.reNice(pid, 10);
        } catch (Throwable ignore) {
        }
    }

    /** Remove all files except exePath from exeDir. */
    private void cleanUpExeDir(File exeDir, String exePath) {
        try {
            exePath = new File(exePath).getCanonicalPath();
            File[] files = exeDir.listFiles();
            if (files == null)
                return;
            for (File f : files) {
                if (!f.getCanonicalPath().equals(exePath) && !keepExeDirFile(f))
                    f.delete();
            }
        } catch (IOException ignore) {
        }
    }

    private boolean keepExeDirFile(File f) {
        return InternalStockFish.keepExeDirFile(f);
    }

    private int hashMB = -1;
    private String gaviotaTbPath = "";
    private String syzygyPath = "";
    private boolean optionsInitialized = false;

    @Override
    public void initOptions(EngineOptions engineOptions) {
        super.initOptions(engineOptions);
        hashMB = getHashMB(engineOptions);
        setOption("Hash", hashMB);
        syzygyPath = engineOptions.getEngineRtbPath(false);
        setOption("SyzygyPath", syzygyPath);
        gaviotaTbPath = engineOptions.getEngineGtbPath(false);
        setOption("GaviotaTbPath", gaviotaTbPath);
        optionsInitialized = true;
    }

    @Override
    protected File getOptionsFile() {
        return new File(engineFileName.getAbsolutePath() + ".ini");
    }

    /** Reduce too large hash sizes. */
    private static int getHashMB(EngineOptions engineOptions) {
        int hashMB = engineOptions.hashMB;
        if (hashMB > 16 && !engineOptions.unSafeHash) {
            int maxMem = (int)(Runtime.getRuntime().maxMemory() / (1024*1024));
            if (maxMem < 16)
                maxMem = 16;
            if (hashMB > maxMem)
                hashMB = maxMem;
        }
        return hashMB;
    }

    @Override
    public boolean optionsOk(EngineOptions engineOptions) {
        if (!optionsInitialized)
            return true;
        if (hashMB != getHashMB(engineOptions))
            return false;
        if (hasOption("gaviotatbpath") && !gaviotaTbPath.equals(engineOptions.getEngineGtbPath(false)))
            return false;
        if (hasOption("syzygypath") && !syzygyPath.equals(engineOptions.getEngineRtbPath(false)))
            return false;
        return true;
    }

    @Override
    public String readLineFromEngine(int timeoutMillis) {
        String ret = inLines.readLine(timeoutMillis);
        if (ret == null)
            return null;
        if (ret.length() > 0) {
//            System.out.printf("Engine -> GUI: %s\n", ret);
        }
        return ret;
    }

    // XXX Writes should be handled by separate thread.
    @Override
    public void writeLineToEngine(String data) {
//        System.out.printf("GUI -> Engine: %s\n", data);
        data += "\n";
        try {
            Process ep = engineProc;
            if (ep != null) {
                ep.getOutputStream().write(data.getBytes());
                ep.getOutputStream().flush();
            }
        } catch (IOException ignore) {
        }
    }

    @Override
    public void shutDown() {
        Process ep = engineProc;
        boolean processAlreadyExited = false;
        if (ep != null) {
            try {
                ep.exitValue();
                processAlreadyExited = true;
            } catch (IllegalThreadStateException stillRunning) {
                // A live process is being shut down intentionally.
            }
        }
        // A monitor thread can call shutDown() in response to stdout EOF. In that
        // case the process has already exited unexpectedly, and the exit watcher
        // must be allowed to publish its exit code and captured stderr diagnostics.
        if (ep == null || !processAlreadyExited) {
            shuttingDown = true;
            if (exitThread != null)
                exitThread.interrupt();
        }
        if (startupThread != null)
            startupThread.interrupt();
        super.shutDown();
        if (ep != null) {
            waitForProcessExit(ep, 250);
            try {
                ep.exitValue();
            } catch (IllegalThreadStateException stillRunning) {
                ep.destroy();
                waitForProcessExit(ep, 1000);
            }
            if (!processAlreadyExited) {
                try {
                    ep.getInputStream().close();
                    ep.getErrorStream().close();
                    ep.getOutputStream().close();
                } catch (IOException ignore) { }
            }
        }
        if (processAlreadyExited && stdErrThread != null) {
            try { stdErrThread.join(600); }
            catch (InterruptedException ignore) { Thread.currentThread().interrupt(); }
        }
        engineProc = null;
        if (stdInThread != null)
            stdInThread.interrupt();
        if (stdErrThread != null)
            stdErrThread.interrupt();
        inLines.close();
        releaseEngineInstanceDir();
    }

    private void waitForProcessExit(Process process, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                process.exitValue();
                return;
            } catch (IllegalThreadStateException stillRunning) {
                try { Thread.sleep(10); }
                catch (InterruptedException ignore) { }
            }
        }
    }

    protected String copyFile(File from, File exeDir) throws IOException {
        File to = new File(exeDir, "engine.exe");
        new File(internalSFPath()).delete();
        if (to.exists() && (from.length() == to.length()) && (from.lastModified() == to.lastModified()))
            return to.getAbsolutePath();
        try (FileInputStream fis = new FileInputStream(from);
             FileChannel inFC = fis.getChannel();
             FileOutputStream fos = new FileOutputStream(to);
             FileChannel outFC = fos.getChannel()) {
            long cnt = outFC.transferFrom(inFC, 0, inFC.size());
            if (cnt < inFC.size())
                throw new IOException("File copy failed");
        } finally {
            to.setLastModified(from.lastModified());
        }
        return to.getAbsolutePath();
    }

    private void chmod(String exePath) throws IOException {
        if (!EngineUtil.chmod(exePath))
            throw new IOException("chmod failed");
    }
}
