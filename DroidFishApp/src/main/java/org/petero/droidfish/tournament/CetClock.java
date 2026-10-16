package org.petero.droidfish.tournament;

/** Monotonic, per-side clock for CET-style engine matches. Times are milliseconds. */
public final class CetClock {
    private final long[] remaining = new long[2]; // 0 white, 1 black
    private final long incrementMs;
    private int activeSide = -1;
    private long turnStartedNs;
    private boolean running;
    private long lastTurnElapsedMs;

    public CetClock(long baseMs, long incrementMs) {
        this(baseMs, baseMs, incrementMs);
    }

    public CetClock(long whiteBaseMs, long blackBaseMs, long incrementMs) {
        if (whiteBaseMs < 0 || blackBaseMs < 0 || incrementMs < 0)
            throw new IllegalArgumentException("Clock values must be non-negative");
        remaining[0] = whiteBaseMs;
        remaining[1] = blackBaseMs;
        this.incrementMs = incrementMs;
    }

    public synchronized void startTurn(boolean whiteToMove) {
        int requestedSide = whiteToMove ? 0 : 1;
        if (running && activeSide == requestedSide) return;
        if (running) settle(System.nanoTime());
        activeSide = requestedSide;
        turnStartedNs = System.nanoTime();
        running = true;
    }

    /** Freeze the active side clock without adding an increment. */
    public synchronized void pause() {
        if (running) settle(System.nanoTime());
    }

    public synchronized boolean isRunning() { return running; }

    public long incrementMs() { return incrementMs; }

    /** Duration spent thinking on the most recently completed turn, in milliseconds. */
    public synchronized long lastTurnElapsedMs() { return lastTurnElapsedMs; }

    /** Accepts a move and adds the side's increment only if it arrived in time. */
    public synchronized boolean acceptMove(boolean whiteMover) {
        return acceptMove(whiteMover, System.nanoTime());
    }

    /** Adjudicate using the monotonic timestamp when bestmove actually arrived. */
    public synchronized boolean acceptMove(boolean whiteMover, long arrivalNs) {
        if (!running || activeSide != (whiteMover ? 0 : 1)) return false;
        long elapsed = Math.max(0, (arrivalNs - turnStartedNs) / 1_000_000L);
        lastTurnElapsedMs = elapsed;
        remaining[activeSide] = Math.max(0, remaining[activeSide] - elapsed);
        running = false;
        if (remaining[activeSide] <= 0) return false;
        remaining[activeSide] += incrementMs;
        return true;
    }

    public synchronized boolean isExpired() {
        return isExpired(System.nanoTime());
    }

    public synchronized boolean isExpired(long nowNs) {
        if (!running || activeSide < 0) return false;
        long elapsed = Math.max(0, (nowNs - turnStartedNs) / 1_000_000L);
        return elapsed >= remaining[activeSide];
    }

    public synchronized long remainingMs(boolean white) {
        int side = white ? 0 : 1;
        long value = remaining[side];
        if (running && activeSide == side) {
            long elapsed = Math.max(0, (System.nanoTime() - turnStartedNs) / 1_000_000L);
            value = Math.max(0, value - elapsed);
        }
        return value;
    }

    /** CET-style UCI command, including both sides' current clocks and increments. */
    public synchronized String uciGo() {
        return String.format(java.util.Locale.US, "go wtime %d btime %d winc %d binc %d",
                remainingMs(true), remainingMs(false), incrementMs, incrementMs);
    }

    private void settle(long nowNs) {
        if (!running || activeSide < 0) return;
        long elapsed = Math.max(0, (nowNs - turnStartedNs) / 1_000_000L);
        remaining[activeSide] = Math.max(0, remaining[activeSide] - elapsed);
        running = false;
    }
}
