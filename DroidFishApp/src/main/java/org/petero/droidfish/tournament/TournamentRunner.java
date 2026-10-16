package org.petero.droidfish.tournament;

import android.util.Pair;
import org.petero.droidfish.EngineOptions;
import org.petero.droidfish.PGNOptions;
import org.petero.droidfish.book.BookOptions;
import org.petero.droidfish.engine.DroidComputerPlayer;
import org.petero.droidfish.gamelogic.Game;
import org.petero.droidfish.gamelogic.GameTree;
import org.petero.droidfish.gamelogic.Move;
import org.petero.droidfish.gamelogic.PgnToken;
import org.petero.droidfish.gamelogic.Position;
import org.petero.droidfish.gamelogic.SearchListener;
import org.petero.droidfish.gamelogic.TimeControlData;

import java.io.IOException;
import org.json.JSONException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Runs a saved tournament with two independent UCI engine processes.
 * Calls to Listener are made from the engine callback thread; marshal UI updates to the main thread.
 */
public final class TournamentRunner {
    public interface Listener {
        void onGameStarted(TournamentManager.Pairing pairing, String whiteName, String blackName);
        /** Current tournament position, copied so the UI can safely render it. */
        void onPositionChanged(Position position, boolean whiteToMove, long whiteTimeMs, long blackTimeMs,
                               String whiteName, String blackName);
        /** Persistent per-engine analysis text; each side retains its last line after moving. */
        void onEngineAnalysis(boolean white, String analysis);
        void onReviewPosition(Position position, int ply, int totalPlies, String whiteAnalysis, String blackAnalysis, long moveTimeMs);
        void onMoveTime(long moveTimeMs, String openingName);
        void onGameFinished(TournamentManager.Pairing pairing, TournamentManager.Outcome outcome, String pgn);
        void onTournamentFinished();
        void onError(String message, Throwable error);
    }

    private final TournamentManager tournament;
    private final Listener listener;
    private final EngineOptions engineOptions;
    private final BookOptions bookOptions;
    private final PGNOptions pgnOptions;
    private final long baseTimeMs, incrementMs;
    private final String openingPgn;
    private final int openingPlies;
    private final boolean repeatOpening;
    private final DroidComputerPlayer whitePlayer, blackPlayer;
    private Game game;
    private volatile CetClock clock;
    private TournamentManager.Pairing pairing;
    private TournamentManager.Engine whiteEngine, blackEngine;
    private int whiteSearchId, blackSearchId;
    private volatile int activeWhiteId, activeBlackId;
    private long turnGeneration;
    private volatile boolean stopped, finished, paused;
    private final ArrayList<Position> positionHistory = new ArrayList<>();
    private final ArrayList<String> whiteAnalysisHistory = new ArrayList<>();
    private final ArrayList<String> blackAnalysisHistory = new ArrayList<>();
    private final ArrayList<Long> moveTimeHistory = new ArrayList<>();
    private int reviewPly = -1;
    private String currentOpeningName = "Starting position";
    private String latestWhiteAnalysis = "WHITE analysis waiting…";
    private String latestBlackAnalysis = "BLACK analysis waiting…";
    private long lastMoveTimeMs;
    private boolean humanAdjudication;

    public TournamentRunner(TournamentManager tournament, Listener listener,
                            EngineOptions engineOptions, BookOptions bookOptions,
                            long baseTimeMs, long incrementMs,
                            String openingPgn, int openingPlies) {
        this(tournament, listener, engineOptions, bookOptions, baseTimeMs, incrementMs, openingPgn, openingPlies, false);
    }

    public TournamentRunner(TournamentManager tournament, Listener listener,
                            EngineOptions engineOptions, BookOptions bookOptions,
                            long baseTimeMs, long incrementMs,
                            String openingPgn, int openingPlies, boolean repeatOpening) {
        if (tournament == null || listener == null) throw new IllegalArgumentException("Tournament and listener are required");
        if (baseTimeMs < 1 || baseTimeMs > Integer.MAX_VALUE || incrementMs < 0 || incrementMs > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Time values are outside supported UCI millisecond range");
        this.tournament = tournament;
        this.listener = listener;
        this.engineOptions = engineOptions == null ? new EngineOptions() : engineOptions;
        this.bookOptions = bookOptions == null ? new BookOptions() : bookOptions;
        this.baseTimeMs = baseTimeMs;
        this.incrementMs = incrementMs;
        this.openingPgn = openingPgn == null ? "" : openingPgn;
        this.openingPlies = Math.max(0, openingPlies);
        this.repeatOpening = repeatOpening;
        pgnOptions = new PGNOptions();
        whitePlayer = new DroidComputerPlayer(new EngineListener(true));
        blackPlayer = new DroidComputerPlayer(new EngineListener(false));
        whitePlayer.setEngineOptions(this.engineOptions);
        blackPlayer.setEngineOptions(this.engineOptions);
        whitePlayer.setBookOptions(this.bookOptions);
        blackPlayer.setBookOptions(this.bookOptions);
    }

    public synchronized void start() {
        if (stopped) throw new IllegalStateException("Runner has been stopped");
        startNextGame();
    }

    /** Pause without discarding the current game, position, or completed schedule. */
    public synchronized void pause() {
        if (stopped || finished || paused) return;
        paused = true;
        turnGeneration++;
        if (clock != null) clock.pause();
        // Keep both UCI processes loaded. Stop only their current searches; pause/resume
        // should not pay the engine startup and NNUE-loading cost again.
        whitePlayer.stopSearch();
        blackPlayer.stopSearch();
    }

    /** Resume the same in-memory game position using the already-loaded UCI processes. */
    public synchronized void resume() {
        if (stopped || finished || !paused) return;
        paused = false;
        if (game == null || pairing == null) {
            startNextGame();
            return;
        }
        // The engines were deliberately not unloaded on pause. Re-queue their configured
        // engine IDs (a no-op when the correct process is already loaded) and continue from
        // the exact same Game position.
        activeWhiteId = ++whiteSearchId;
        activeBlackId = ++blackSearchId;
        whitePlayer.queueStartEngine(activeWhiteId, whiteEngine.id);
        blackPlayer.queueStartEngine(activeBlackId, blackEngine.id);
        listener.onGameStarted(pairing, whiteEngine.displayName, blackEngine.displayName);
        notifyPosition();
        requestMove(game.currPos().whiteMove);
    }

    public synchronized boolean isPaused() { return paused; }

    public synchronized void stop() {
        stopped = true;
        paused = false;
        turnGeneration++;
        if (clock != null) clock.pause();
        whitePlayer.shutdownEngine();
        blackPlayer.shutdownEngine();
    }

    /** Navigate completed plies while paused; -1 means return to live position. */
    public synchronized void reviewPreviousMove() {
        if (!paused || game == null || positionHistory.isEmpty()) return;
        if (reviewPly < 0) reviewPly = Math.max(0, positionHistory.size() - 2);
        else reviewPly = Math.max(0, reviewPly - 1);
        publishReviewPosition();
    }

    public synchronized void reviewNextMove() {
        if (!paused || game == null || positionHistory.isEmpty()) return;
        if (reviewPly < 0) return;
        if (reviewPly >= positionHistory.size() - 1) reviewPly = -1;
        else reviewPly++;
        publishReviewPosition();
    }

    public synchronized void adjudicate(String result) {
        if (stopped || finished || paused == false || game == null || pairing == null) return;
        if (!("1-0".equals(result) || "0-1".equals(result) || "1/2-1/2".equals(result))) return;
        turnGeneration++;
        clock.pause();
        whitePlayer.stopSearch();
        blackPlayer.stopSearch();
        humanAdjudication = true;
        game.adjudicateTournament(result);
        TournamentManager.Outcome outcome = "1-0".equals(result) ? TournamentManager.Outcome.WHITE_WIN :
                "0-1".equals(result) ? TournamentManager.Outcome.BLACK_WIN : TournamentManager.Outcome.DRAW;
        finishCurrentGame(outcome);
    }

    private void publishReviewPosition() {
        int index = reviewPly < 0 ? positionHistory.size() - 1 : reviewPly;
        if (index < 0 || index >= positionHistory.size()) return;
        listener.onReviewPosition(new Position(positionHistory.get(index)), index, positionHistory.size() - 1,
                whiteAnalysisHistory.get(index), blackAnalysisHistory.get(index), moveTimeHistory.get(index));
    }

    private void recordPosition(long moveTimeMs) {
        positionHistory.add(new Position(game.currPos()));
        whiteAnalysisHistory.add(latestWhiteAnalysis);
        blackAnalysisHistory.add(latestBlackAnalysis);
        moveTimeHistory.add(Math.max(0, moveTimeMs));
        reviewPly = -1;
    }

    private void startNextGame() {
        if (stopped || finished || paused) return;
        pairing = tournament.current();
        if (pairing == null) {
            finished = true;
            whitePlayer.shutdownEngine();
            blackPlayer.shutdownEngine();
            listener.onTournamentFinished();
            return;
        }
        whiteEngine = findEngine(pairing.whiteEngineId);
        blackEngine = findEngine(pairing.blackEngineId);
        TimeControlData tc = new TimeControlData();
        tc.setTimeControl((int)baseTimeMs, 0, (int)incrementMs);
        game = new Game(new EmptyPgnReceiver(), tc);
        game.tree.setPlayerNames(whiteEngine.displayName, blackEngine.displayName);
        positionHistory.clear(); whiteAnalysisHistory.clear(); blackAnalysisHistory.clear(); moveTimeHistory.clear();
        reviewPly = -1; lastMoveTimeMs = 0; humanAdjudication = false;
        latestWhiteAnalysis = "WHITE analysis waiting…"; latestBlackAnalysis = "BLACK analysis waiting…";
        currentOpeningName = "Starting position";
        String eventType = tournament.format().name().toLowerCase(java.util.Locale.US).replace('_', ' ');
        game.tree.setTournamentMetadata(tournament.title(), "DroidFish", pairing.round + "." + pairing.gameNumber,
                eventType, (baseTimeMs / 1000) + "+" + String.format(java.util.Locale.US, "%g", incrementMs / 1000.0));
        clock = new CetClock(baseTimeMs, incrementMs);
        game.tree.setTournamentOpening(currentOpeningName);
        if (openingPlies > 0 && !openingPgn.isEmpty()) {
            int openingIndex = repeatOpening ? Math.max(0, (pairing.gameNumber - 1) / 2) : pairing.index;
            String selectedOpening = PgnOpeningBook.gameAt(openingPgn, openingIndex);
            currentOpeningName = PgnOpeningBook.tagValue(selectedOpening, "Opening");
            if (currentOpeningName.isEmpty()) currentOpeningName = PgnOpeningBook.tagValue(selectedOpening, "ECO");
            if (currentOpeningName.isEmpty()) currentOpeningName = "PGN opening " + (openingIndex + 1);
            game.tree.setTournamentOpening(currentOpeningName);
            PgnOpeningBook.applyMainLine(selectedOpening, openingPlies, san -> {
                Pair<Boolean, Move> result = game.processString(san);
                return result != null && Boolean.TRUE.equals(result.first) && result.second != null;
            });
        }
        if (game.getGameState() != Game.GameState.ALIVE) {
            finishCurrentGame(resultForState(game.getGameState()));
            return;
        }
        recordPosition(0);
        listener.onMoveTime(0, currentOpeningName);
        whitePlayer.setEngineOptions(engineOptions);
        blackPlayer.setEngineOptions(engineOptions);
        whitePlayer.uciNewGame();
        blackPlayer.uciNewGame();
        activeWhiteId = ++whiteSearchId;
        activeBlackId = ++blackSearchId;
        whitePlayer.queueStartEngine(activeWhiteId, whiteEngine.id);
        blackPlayer.queueStartEngine(activeBlackId, blackEngine.id);
        listener.onGameStarted(pairing, whiteEngine.displayName, blackEngine.displayName);
        notifyPosition();
        requestMove(game.currPos().whiteMove);
    }

    private TournamentManager.Engine findEngine(String id) {
        for (TournamentManager.Engine engine : tournament.engines()) if (engine.id.equals(id)) return engine;
        throw new IllegalStateException("Tournament references unknown engine: " + id);
    }

    private synchronized void requestMove(boolean whiteToMove) {
        if (stopped || finished || paused || game == null || game.getGameState() != Game.GameState.ALIVE) return;
        if (clock.isExpired()) {
            game.markTimeout(whiteToMove);
            finishCurrentGame(resultForState(game.getGameState()));
            return;
        }
        Pair<Position, ArrayList<Move>> history = game.getUCIHistory();
        // Snapshot before starting the turn: DroidComputerPlayer subtracts the engine-start
        // delay from the moving side's UCI clock, matching the independently running watchdog.
        int wTime = (int)Math.max(1, Math.min(Integer.MAX_VALUE, clock.remainingMs(true)));
        int bTime = (int)Math.max(1, Math.min(Integer.MAX_VALUE, clock.remainingMs(false)));
        int inc = (int)Math.min(Integer.MAX_VALUE, clock.incrementMs());
        clock.startTurn(whiteToMove);
        final long turnToken = ++turnGeneration;
        int id;
        DroidComputerPlayer player;
        if (whiteToMove) {
            id = ++whiteSearchId;
            activeWhiteId = id;
            player = whitePlayer;
        } else {
            id = ++blackSearchId;
            activeBlackId = id;
            player = blackPlayer;
        }
        DroidComputerPlayer.SearchRequest request = DroidComputerPlayer.SearchRequest.cetSearchRequest(
                id, System.currentTimeMillis(), history.first, history.second,
                new Position(game.currPos()), game.haveDrawOffer(), wTime, bTime, inc, inc,
                whiteToMove ? whiteEngine.id : blackEngine.id);
        player.queueSearchRequest(request);
        startClockWatchdog(whiteToMove, turnToken);
    }

    /** Adjudicate flag fall even if an engine hangs and never emits bestmove. */
    private void startClockWatchdog(final boolean whiteToMove, final long turnToken) {
        Thread watchdog = new Thread(() -> {
            while (true) {
                synchronized (TournamentRunner.this) {
                    if (stopped || finished || paused || turnToken != turnGeneration || game == null ||
                            game.getGameState() != Game.GameState.ALIVE || game.currPos().whiteMove != whiteToMove)
                        return;
                    if (clock.isExpired()) {
                        game.markTimeout(whiteToMove);
                        // A non-responsive engine may never answer stop; kill it so the next
                        // pairing can start a clean process instead of inheriting a stuck search.
                        (whiteToMove ? whitePlayer : blackPlayer).shutdownEngine();
                        turnGeneration++;
                        finishCurrentGame(resultForState(game.getGameState()));
                        return;
                    }
                }
                try { Thread.sleep(5); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
        }, "CET-Clock-Watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private synchronized void onSearchResult(boolean whiteEngineSide, int id, String cmd,
                                             boolean acceptedAtArrival) {
        if (stopped || finished || paused || game == null) return;
        if (whiteEngineSide ? id != activeWhiteId : id != activeBlackId) return;
        boolean whiteToMove = game.currPos().whiteMove;
        if (whiteToMove != whiteEngineSide) return;
        if (!acceptedAtArrival) {
            game.markTimeout(whiteToMove);
            turnGeneration++;
            finishCurrentGame(resultForState(game.getGameState()));
            return;
        }
        turnGeneration++; // Invalidate this turn's watchdog before changing the board.
        Pair<Boolean, Move> result = game.processString(cmd);
        if (result == null || !Boolean.TRUE.equals(result.first)) {
            // Illegal engine output is a loss for the engine that produced it.
            game.processString("resign");
            finishCurrentGame(resultForState(game.getGameState()));
            return;
        }
        lastMoveTimeMs = clock.lastTurnElapsedMs();
        recordPosition(lastMoveTimeMs);
        listener.onMoveTime(lastMoveTimeMs, currentOpeningName);
        notifyPosition();
        if (game.getGameState() != Game.GameState.ALIVE) {
            finishCurrentGame(resultForState(game.getGameState()));
            return;
        }
        if (result.second == null) {
            listener.onError("Engine returned a non-move command without ending the game: " + cmd, null);
            stop();
            return;
        }
        requestMove(game.currPos().whiteMove);
    }

    private void notifyPosition() {
        if (game == null || pairing == null || whiteEngine == null || blackEngine == null) return;
        CetClock activeClock = clock;
        long whiteMs = activeClock == null ? 0 : activeClock.remainingMs(true);
        long blackMs = activeClock == null ? 0 : activeClock.remainingMs(false);
        listener.onPositionChanged(new Position(game.currPos()), game.currPos().whiteMove,
                whiteMs, blackMs, whiteEngine.displayName, blackEngine.displayName);
    }

    private String terminationForState(Game.GameState state) {
        switch (state) {
            case TIMEOUT_WHITE: case TIMEOUT_BLACK: return "time forfeit";
            case WHITE_MATE: case BLACK_MATE: return "checkmate";
            case RESIGN_WHITE: case RESIGN_BLACK: return "resignation";
            case WHITE_STALEMATE: case BLACK_STALEMATE: return "stalemate";
            case DRAW_REP: return "threefold repetition";
            case DRAW_50: return "50-move rule";
            case DRAW_NO_MATE: return "insufficient mating material";
            case DRAW_AGREE: return "draw agreement";
            default: return "adjudication";
        }
    }

    private TournamentManager.Outcome resultForState(Game.GameState state) {
        switch (state) {
            case WHITE_MATE:
            case RESIGN_BLACK:
            case TIMEOUT_BLACK: return TournamentManager.Outcome.WHITE_WIN;
            case BLACK_MATE:
            case RESIGN_WHITE:
            case TIMEOUT_WHITE: return TournamentManager.Outcome.BLACK_WIN;
            case WHITE_STALEMATE:
            case BLACK_STALEMATE:
            case DRAW_REP:
            case DRAW_50:
            case DRAW_NO_MATE:
            case DRAW_AGREE: return TournamentManager.Outcome.DRAW;
            default: return TournamentManager.Outcome.UNFINISHED;
        }
    }

    private synchronized void finishCurrentGame(TournamentManager.Outcome outcome) {
        if (stopped || finished || pairing == null || outcome == TournamentManager.Outcome.UNFINISHED) return;
        turnGeneration++;
        game.tree.setTournamentTermination(humanAdjudication ? "adjudication" : terminationForState(game.getGameState()));
        humanAdjudication = false;
        String pgn = game.tree.toPGN(pgnOptions);
        try {
            tournament.recordResult(pairing.index, outcome, pgn);
        } catch (IOException | JSONException e) {
            listener.onError("Could not save tournament result", e);
            stop();
            return;
        }
        TournamentManager.Pairing completed = pairing;
        pairing = null;
        listener.onGameFinished(completed, outcome, pgn);
        startNextGame();
    }

    private final class EngineListener implements SearchListener {
        private final boolean white;
        EngineListener(boolean white) { this.white = white; }
        private volatile int lastDepth;
        private volatile long lastNodes;
        private volatile int lastNps;
        private volatile int lastTimeMs;
        private volatile String lastPv = "";
        @Override public void notifyDepth(int id, int depth) {
            lastDepth = depth;
            publishAnalysis();
        }
        @Override public void notifyCurrMove(int id, Position pos, Move move, int moveNr) { }
        @Override public void notifyPV(int id, Position pos, ArrayList<PvInfo> pvInfo, Move ponderMove) {
            if (pvInfo != null && !pvInfo.isEmpty()) {
                PvInfo pv = pvInfo.get(0);
                StringBuilder line = new StringBuilder();
                if (pv.pv != null) {
                    Position cursor = new Position(pos);
                    for (Move m : pv.pv) {
                        String san = org.petero.droidfish.gamelogic.TextIO.moveToString(cursor, m, false, false);
                        if (line.length() > 0) line.append(' ');
                        line.append(san);
                        cursor.makeMove(m, new org.petero.droidfish.gamelogic.UndoInfo());
                    }
                }
                String scoreText = pv.isMate ? (pv.score >= 0 ? "M" : "-M") + Math.abs(pv.score) :
                        String.format(java.util.Locale.US, "%+.2f", pv.score / 100.0);
                lastPv = "eval " + scoreText + (line.length() == 0 ? "" : "  " + line);
                lastDepth = pv.depth;
                lastNodes = pv.nodes;
                lastNps = pv.nps;
                lastTimeMs = pv.time;
                publishAnalysis();
            }
        }
        @Override public void notifyStats(int id, long nodes, int nps, long tbHits, int hash, int time, int seldepth) {
            lastNodes = nodes; lastNps = nps; lastTimeMs = time;
            publishAnalysis();
        }
        @Override public void notifyBookInfo(int id, String bookInfo, ArrayList<Move> moveList, String eco, int distToEcoTree) {
            if (bookInfo != null && !bookInfo.isEmpty()) {
                lastPv = "Book: " + bookInfo;
                publishAnalysis();
            }
        }
        private void publishAnalysis() {
            if (stopped || finished) return;
            String side = white ? "WHITE" : "BLACK";
            String text = side + "  d" + lastDepth + "  " + String.format(java.util.Locale.US, "%.2f", lastTimeMs / 1000.0) + "s  " +
                    (lastNps >= 1000 ? String.format(java.util.Locale.US, "%.1fkn/s", lastNps / 1000.0) : lastNps + "n/s") +
                    "  " + lastNodes + " nodes" + (lastPv.isEmpty() ? "" : "\n" + lastPv);
            if (white) latestWhiteAnalysis = text; else latestBlackAnalysis = text;
            if (!positionHistory.isEmpty() && reviewPly < 0) {
                int last = positionHistory.size() - 1;
                if (white) whiteAnalysisHistory.set(last, text); else blackAnalysisHistory.set(last, text);
            }
            listener.onEngineAnalysis(white, text);
        }
        @Override public void notifySearchResult(int id, String cmd, Move ponder) {
            // Stop the clock at bestmove arrival, not when the deferred board update runs.
            long arrivalNs = System.nanoTime();
            int activeId = white ? activeWhiteId : activeBlackId;
            CetClock activeClock = clock;
            boolean acceptedAtArrival = !stopped && !finished && !paused && id == activeId && activeClock != null &&
                    activeClock.acceptMove(white, arrivalNs);
            // DroidComputerPlayer invokes listeners before it returns its state to IDLE.
            // Defer board/result handling so old-search cleanup cannot overwrite a new search.
            DroidComputerPlayer player = white ? whitePlayer : blackPlayer;
            Thread worker = new Thread(() -> {
                try {
                    while (player.computerBusy()) Thread.sleep(2);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                onSearchResult(white, id, cmd, acceptedAtArrival);
            }, "CET-Tournament-Result");
            worker.start();
        }
        @Override public void notifyEngineName(String engineName) { }
        @Override public void reportEngineError(String errMsg) { listener.onError(errMsg, null); }
        @Override public void notifyEngineInitialized() {
            // UCI option discovery clears pending options, so apply the tournament baseline
            // after each newly loaded engine has reported its supported options.
            Map<String, String> options = new HashMap<>();
            options.put("Threads", "1");
            options.put("Hash", "16");
            options.put("Ponder", "false");
            (white ? whitePlayer : blackPlayer).setEngineUCIOptions(options);
        }
    }

    private static final class EmptyPgnReceiver implements PgnToken.PgnTokenReceiver {
        @Override public boolean isUpToDate() { return true; }
        @Override public void clear() { }
        @Override public void processToken(GameTree.Node node, int type, String token) { }
        @Override public void setCurrent(GameTree.Node node) { }
    }
}
