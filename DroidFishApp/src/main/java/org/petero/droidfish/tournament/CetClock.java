package org.petero.droidfish.tournament;

/** Monotonic, per-side clock for CET-style engine matches. Times are milliseconds. */
public final class CetClock {
    private final long[] remaining = new long[2]; // 0 white, 1 black
    private final long incrementMs;
    private final int movesPerPeriod;
    private final long periodMs;
    private final int[] movesPlayed = new int[2];
    private int activeSide = -1;
    private long turnStartedNs;
    private boolean running;
    private long lastTurnElapsedMs;

    public CetClock(long baseMs, long incrementMs) {
        this(baseMs, baseMs, incrementMs, 0, 0);
    }

    /** Repeating move-period control: each side receives periodMs after every movesPerPeriod moves. */
    public CetClock(long baseMs, long periodMs, int movesPerPeriod) {
        this(baseMs, baseMs, 0, movesPerPeriod, periodMs);
    }

    public CetClock(long whiteBaseMs, long blackBaseMs, long incrementMs) {
        this(whiteBaseMs, blackBaseMs, incrementMs, 0, 0);
    }

    private CetClock(long whiteBaseMs, long blackBaseMs, long incrementMs,
                     int movesPerPeriod, long periodMs) {
        if (whiteBaseMs < 0 || blackBaseMs < 0 || incrementMs < 0 || movesPerPeriod < 0 || periodMs < 0)
            throw new IllegalArgumentException("Clock values must be non-negative");
        if ((movesPerPeriod == 0) != (periodMs == 0))
            throw new IllegalArgumentException("Move-period count and time must both be positive");
        remaining[0] = whiteBaseMs;
        remaining[1] = blackBaseMs;
        this.incrementMs = incrementMs;
        this.movesPerPeriod = movesPerPeriod;
        this.periodMs = periodMs;
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

    /** Seed the move counters from a pre-played opening position before clocks start. */
    public synchronized void setMovesPlayed(int whiteMoves, int blackMoves) {
        if (running || movesPerPeriod <= 0) return;
        movesPlayed[0] = Math.max(0, whiteMoves);
        movesPlayed[1] = Math.max(0, blackMoves);
        // If a tournament starts from a pre-played opening, account for any time
        // controls already crossed by that position without charging thinking time.
        for (int side = 0; side < 2; side++) {
            long completedPeriods = movesPlayed[side] / movesPerPeriod;
            if (completedPeriods > 0) {
                long bonus = completedPeriods > Long.MAX_VALUE / periodMs
                        ? Long.MAX_VALUE : completedPeriods * periodMs;
                remaining[side] = remaining[side] > Long.MAX_VALUE - bonus
                        ? Long.MAX_VALUE : remaining[side] + bonus;
            }
        }
    }

    public long incrementMs() { return incrementMs; }

    /** UCI movestogo value for the selected side in a repeating move-period control. */
    public synchronized int movesToNextPeriod(boolean white) {
        if (movesPerPeriod <= 0) return 0;
        int played = movesPlayed[white ? 0 : 1];
        int remainder = played % movesPerPeriod;
        return remainder == 0 ? movesPerPeriod : movesPerPeriod - remainder;
    }

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
        if (movesPerPeriod > 0) {
            // Move counts are per side: 40/15 grants the next 15-minute block
            // after that side completes move 40, 80, 120, and so on.
            movesPlayed[activeSide]++;
            if (movesPlayed[activeSide] % movesPerPeriod == 0)
                remaining[activeSide] += periodMs;
        } else {
            remaining[activeSide] += incrementMs;
        }
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
