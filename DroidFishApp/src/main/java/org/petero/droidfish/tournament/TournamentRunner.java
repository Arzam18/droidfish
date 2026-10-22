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
                               String whiteName, String blackName, String pgnMoves);
        /** Persistent per-engine analysis text; each side retains its last line after moving. */
        void onEngineAnalysis(boolean white, String analysis);
        void onReviewPosition(Position position, int ply, int totalPlies, String whiteAnalysis, String blackAnalysis, long moveTimeMs);
        void onMoveTime(boolean whiteMoved, long moveTimeMs, String openingName);
        void onGameFinished(TournamentManager.Pairing pairing, TournamentManager.Outcome outcome, String pgn);
        /** Result plus the concrete rule/termination reason for the result announcement. */
        default void onGameFinished(TournamentManager.Pairing pairing, TournamentManager.Outcome outcome,
                                    String pgn, String reason) {
            onGameFinished(pairing, outcome, pgn);
        }
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
    private final int openingStartIndex;
    private final boolean repeatOpening;
    private final double autoWinThreshold;
    private final boolean fastWinEnabled;
    private volatile double latestWhiteEvalPawns = Double.NaN;
    private volatile double latestBlackEvalPawns = Double.NaN;
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
    private long currentTurnStartedNs;
    private boolean humanAdjudication;
    private boolean drawRulesEnabled = true;
    private int drawMoveLimit = 50;
    private int drawRepetitionLimit = 3;
    private int drawMaximumFullMoves = 160;
    private int ruleStartFullMove = 1;

    /** Configure tournament-only automatic draw adjudication before start(). */
    public synchronized void configureDrawRules(boolean enabled, int moveRule, int repetitionCount, int maximumFullMoves) {
        configureDrawRules(enabled, moveRule, repetitionCount, maximumFullMoves, 1);
    }
    public synchronized void configureDrawRules(boolean enabled, int moveRule, int repetitionCount, int maximumFullMoves, int startFullMove) {
        if (game != null || pairing != null) throw new IllegalStateException("Draw rules must be configured before starting the tournament");
        drawRulesEnabled = enabled;
        drawMoveLimit = Math.max(1, Math.min(10000, moveRule));
        drawRepetitionLimit = Math.max(2, Math.min(100, repetitionCount));
        drawMaximumFullMoves = Math.max(1, Math.min(10000, maximumFullMoves));
        ruleStartFullMove = Math.max(1, Math.min(10000, startFullMove));
    }

    private boolean applyConfiguredDrawRules() {
        if (!drawRulesEnabled || game == null) return false;
        Position pos = game.currPos();
        if (pos.fullMoveCounter < ruleStartFullMove) return false;
        if (pos.halfMoveClock >= drawMoveLimit * 2) {
            game.adjudicateTournament("1/2-1/2");
            finishCurrentGame(TournamentManager.Outcome.DRAW, "50-move rule");
            return true;
        }
        if (pos.fullMoveCounter > drawMaximumFullMoves) {
            game.adjudicateTournament("1/2-1/2");
            finishCurrentGame(TournamentManager.Outcome.DRAW, "maximum-move rule");
            return true;
        }
        long hash = pos.zobristHash(); int occurrences = 0;
        for (Position historic : positionHistory) if (historic.zobristHash() == hash) occurrences++;
        if (occurrences >= drawRepetitionLimit) {
            game.adjudicateTournament("1/2-1/2");
            finishCurrentGame(TournamentManager.Outcome.DRAW, "threefold repetition");
            return true;
        }
        return false;
    }

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
        this(tournament, listener, engineOptions, bookOptions, baseTimeMs, incrementMs, openingPgn, openingPlies, repeatOpening, tournament == null ? 1 : tournament.openingStartIndex());
    }

    public TournamentRunner(TournamentManager tournament, Listener listener,
                            EngineOptions engineOptions, BookOptions bookOptions,
                            long baseTimeMs, long incrementMs,
                            String openingPgn, int openingPlies, boolean repeatOpening, int openingStartIndex) {
        this(tournament, listener, engineOptions, bookOptions, baseTimeMs, incrementMs, openingPgn, openingPlies, repeatOpening, openingStartIndex, 10.0, false);
    }

    public TournamentRunner(TournamentManager tournament, Listener listener,
                            EngineOptions engineOptions, BookOptions bookOptions,
                            long baseTimeMs, long incrementMs,
                            String openingPgn, int openingPlies, boolean repeatOpening, int openingStartIndex,
                            double autoWinThreshold, boolean fastWinEnabled) {
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
        this.openingStartIndex = Math.max(1, openingStartIndex);
        this.autoWinThreshold = autoWinThreshold > 0.0 && !Double.isInfinite(autoWinThreshold) && !Double.isNaN(autoWinThreshold) ? autoWinThreshold : 10.0;
        this.fastWinEnabled = fastWinEnabled;
        pgnOptions = new PGNOptions();
        // Tournament move comments are part of the exported record, not optional UI comments.
        pgnOptions.exp.comments = true;
        whitePlayer = new DroidComputerPlayer(new EngineListener(true));
        blackPlayer = new DroidComputerPlayer(new EngineListener(false));
        whitePlayer.setEngineOptions(this.engineOptions);
        blackPlayer.setEngineOptions(this.engineOptions);
        // A PGN opening book is authoritative for tournament starts. Do not let
        // DroidFish's own Polyglot/CTG/ECO/internal book replace the selected line.
        BookOptions tournamentBookOptions = new BookOptions(this.bookOptions);
        if (!this.openingPgn.trim().isEmpty()) {
            tournamentBookOptions.filename = "";
            tournamentBookOptions.maxLength = 0;
            tournamentBookOptions.preferMainLines = false;
        }
        whitePlayer.setBookOptions(tournamentBookOptions);
        blackPlayer.setBookOptions(tournamentBookOptions);
    }

    public void start() {
        synchronized (this) {
            if (stopped) throw new IllegalStateException("Runner has been stopped");
        }
        // Opening playback deliberately waits 500 ms per ply. Never run that loop on
        // the Android UI thread: queued board updates would otherwise all render at once.
        Thread starter = new Thread(() -> {
            synchronized (TournamentRunner.this) {
                if (stopped || finished || paused) return;
            }
            startNextGame();
        }, "Tournament-Game-Starter");
        starter.setDaemon(true);
        starter.start();
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
    public boolean isFinished() { return finished; }
    public boolean isStopped() { return stopped; }
    public synchronized long remainingMs(boolean white) {
        CetClock c=clock; if(c==null || tournament.timeMode()!=TournamentManager.TimeMode.CLOCK) return -1; return c.remainingMs(white);
    }
    public synchronized boolean whiteToMove() { return game != null && game.currPos().whiteMove; }
    public synchronized long activeMoveTimeMs() {
        if(game==null || pairing==null || paused || finished || stopped || currentTurnStartedNs==0) return -1;
        return Math.max(0,(System.nanoTime()-currentTurnStartedNs)/1_000_000L);
    }

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
        finishCurrentGame(outcome, "manual adjudication");
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
        latestWhiteEvalPawns = Double.NaN;
        latestBlackEvalPawns = Double.NaN;
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
        game.tree.setTournamentMetadata(tournament.title(), "DroidFish", pairing.round + "." + gameNumberInRound(pairing),
                eventType, (baseTimeMs / 1000) + "+" + String.format(java.util.Locale.US, "%g", incrementMs / 1000.0));
        clock = new CetClock(baseTimeMs, incrementMs);
        game.tree.setTournamentOpening(currentOpeningName);
        if (!openingPgn.trim().isEmpty()) {
            int openingIndex = (openingStartIndex - 1) + (repeatOpening ? Math.max(0, (pairing.gameNumber - 1) / 2) : pairing.index);
            String selectedOpening = PgnOpeningBook.gameAt(openingPgn, openingIndex);
            currentOpeningName = PgnOpeningBook.tagValue(selectedOpening, "Opening");
            if (currentOpeningName.isEmpty()) currentOpeningName = PgnOpeningBook.tagValue(selectedOpening, "ECO");
            if (currentOpeningName.isEmpty()) currentOpeningName = "PGN opening " + (openingIndex + 1);
            game.tree.setTournamentOpening(currentOpeningName);
            int appliedPlies = PgnOpeningBook.applyMainLine(selectedOpening, openingPlies, san -> {
                Pair<Boolean, Move> result = game.processString(san);
                boolean applied = result != null && Boolean.TRUE.equals(result.first) && result.second != null;
                if (applied) {
                    // Publish each individual ply and allow the UI to render it before
                    // applying the next opening move. Engines are started only after this loop.
                    listener.onPositionChanged(new Position(game.currPos()), game.currPos().whiteMove,
                            0, 0, whiteEngine.displayName, blackEngine.displayName, currentPgnMoves());
                    try { Thread.sleep(500); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
                }
                return applied;
            });
            if (appliedPlies == 0) {
                listener.onError("Selected PGN opening " + (openingIndex + 1) +
                        " contains no legal moves from the starting position. Check that the PGN has a valid main line.", null);
            }
        }
        if (game.getGameState() != Game.GameState.ALIVE) {
            finishCurrentGame(resultForState(game.getGameState()));
            return;
        }
        recordPosition(0);
        listener.onMoveTime(false, 0, currentOpeningName);
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

    /** PGN Round uses the sequential game number within a round (1.1 ... 1.N). */
    private String gameNumberInRound(TournamentManager.Pairing target) {
        int gameInRound = 0;
        for (TournamentManager.Pairing p : tournament.pairings()) {
            if (p.round == target.round && p.index <= target.index) gameInRound++;
        }
        return Integer.toString(Math.max(1, gameInRound));
    }

    private TournamentManager.Engine findEngine(String id) {
        for (TournamentManager.Engine engine : tournament.engines()) if (engine.id.equals(id)) return engine;
        throw new IllegalStateException("Tournament references unknown engine: " + id);
    }

    private synchronized void requestMove(boolean whiteToMove) {
        if (stopped || finished || paused || game == null || game.getGameState() != Game.GameState.ALIVE) return;
        if (tournament.timeMode() == TournamentManager.TimeMode.CLOCK && clock.isExpired()) {
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
        currentTurnStartedNs = System.nanoTime();
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
        switch (tournament.timeMode()) {
            case MOVE_TIME: request.tournamentGoCommand = "go movetime " + Math.max(1, tournament.limitValue()); break;
            case DEPTH: request.tournamentGoCommand = "go depth " + Math.max(1, tournament.limitValue()); break;
            case NODES: request.tournamentGoCommand = "go nodes " + Math.max(1, tournament.limitValue()); break;
            case CLOCK: default: break;
        }
        player.queueSearchRequest(request);
        if (tournament.timeMode() == TournamentManager.TimeMode.CLOCK) startClockWatchdog(whiteToMove, turnToken);
    }

    /** Adjudicate flag fall even if an engine hangs and never emits bestmove. */
    private void startClockWatchdog(final boolean whiteToMove, final long turnToken) {
        Thread watchdog = new Thread(() -> {
            while (true) {
                synchronized (TournamentRunner.this) {
                    if (stopped || finished || paused || turnToken != turnGeneration || game == null ||
                            game.getGameState() != Game.GameState.ALIVE || game.currPos().whiteMove != whiteToMove)
                        return;
                    if (tournament.timeMode() == TournamentManager.TimeMode.CLOCK && clock.isExpired()) {
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
        lastMoveTimeMs = tournament.timeMode() == TournamentManager.TimeMode.CLOCK ? clock.lastTurnElapsedMs() : Math.max(0, (System.nanoTime() - currentTurnStartedNs) / 1_000_000L);
        addMoveAnalysisComment(whiteEngineSide, lastMoveTimeMs);
        recordPosition(lastMoveTimeMs);
        listener.onMoveTime(!game.currPos().whiteMove, lastMoveTimeMs, currentOpeningName);
        notifyPosition();
        if (applyConfiguredDrawRules()) return;
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

    private String currentPgnMoves() {
        if (game == null || game.tree == null) return "";
        try {
            android.util.Pair<java.util.List<GameTree.Node>, Integer> list = game.tree.getMoveList();
            StringBuilder out = new StringBuilder();
            Position cursor = new Position();
            for (GameTree.Node node : list.first) {
                Move move = node.move;
                if (move == null) continue;
                if (out.length() > 0) out.append(' ');
                if (cursor.whiteMove) out.append(cursor.fullMoveCounter).append('.');
                else if (out.length() == 0) out.append(cursor.fullMoveCounter).append("...");
                out.append(org.petero.droidfish.gamelogic.TextIO.moveToString(cursor, move, false, false));
                cursor.makeMove(move, new org.petero.droidfish.gamelogic.UndoInfo());
            }
            return out.toString();
        } catch (RuntimeException ignored) { return ""; }
    }

    private String clockText(long ms) {
        long tenths = Math.max(0, ms) / 100;
        return String.format(java.util.Locale.US, "%d:%02d.%d", tenths / 600, (tenths / 10) % 60, tenths % 10);
    }

    private void addMoveAnalysisComment(boolean whiteMoved, long moveMs) {
        try {
            android.util.Pair<java.util.List<GameTree.Node>, Integer> list = game.tree.getMoveList();
            if (list == null || list.first == null || list.first.isEmpty()) return;
            GameTree.Node node = list.first.get(list.first.size() - 1);
            String analysis = whiteMoved ? latestWhiteAnalysis : latestBlackAnalysis;
            String eval = "n/a", depth = "n/a", nodes = "n/a", nps = "n/a", wdl = "";
            if (analysis != null) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                        "Eval:\\s*(.*?)\\s+Depth:\\s*(.*?)\\s+Nodes:\\s*(.*?)\\s+NPS:\\s*(\\S+)")
                        .matcher(analysis.replace('\n', ' '));
                if (m.find()) {
                    eval = m.group(1).trim(); depth = m.group(2).trim();
                    nodes = m.group(3).trim(); nps = m.group(4).trim();
                }
                java.util.regex.Matcher w = java.util.regex.Pattern.compile(
                        "W:\\s*(\\d+)%\\s+D:\\s*(\\d+)%\\s+L:\\s*(\\d+)%").matcher(analysis);
                if (w.find()) wdl = " W:" + w.group(1) + "% D:" + w.group(2) + "% L:" + w.group(3) + "%";
            }
            long remaining = clock == null ? 0 : clock.remainingMs(whiteMoved);
            String comment = "Eval: " + eval + " Depth: " + depth + " Nodes: " + nodes
                    + " NPS: " + nps + " Clock: " + clockText(remaining)
                    + " Time: " + clockText(moveMs) + wdl;
            // Do not include the engine's PV/variation in the PGN comment.
            node.setPostComment(comment);
        } catch (RuntimeException ignored) { }
    }

    private void notifyPosition() {
        if (game == null || pairing == null || whiteEngine == null || blackEngine == null) return;
        // Require fresh evaluations from both engines for every position before fast adjudication.
        latestWhiteEvalPawns = Double.NaN;
        latestBlackEvalPawns = Double.NaN;
        CetClock activeClock = clock;
        long whiteMs = activeClock == null || tournament.timeMode() != TournamentManager.TimeMode.CLOCK ? 0 : activeClock.remainingMs(true);
        long blackMs = activeClock == null || tournament.timeMode() != TournamentManager.TimeMode.CLOCK ? 0 : activeClock.remainingMs(false);
        listener.onPositionChanged(new Position(game.currPos()), game.currPos().whiteMove,
                whiteMs, blackMs, whiteEngine.displayName, blackEngine.displayName, currentPgnMoves());
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

    private void finishCurrentGame(TournamentManager.Outcome outcome) {
        finishCurrentGame(outcome, null);
    }

    private synchronized void finishCurrentGame(TournamentManager.Outcome outcome, String explicitReason) {
        if (stopped || finished || pairing == null || outcome == TournamentManager.Outcome.UNFINISHED) return;
        turnGeneration++;
        boolean wasHumanAdjudication = humanAdjudication;
        String stateReason = terminationForState(game.getGameState());
        String resultReason = explicitReason != null && !explicitReason.trim().isEmpty() ? explicitReason :
                (wasHumanAdjudication ? "automatic evaluation adjudication" : stateReason);
        game.tree.setTournamentTermination(wasHumanAdjudication ? "adjudication" : resultReason);
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
        listener.onGameFinished(completed, outcome, pgn, resultReason);
        // Do not sleep on the caller: manual adjudication can originate on the UI thread.
        // A separate continuation lets Android render the result for at least 500 ms.
        Thread advance = new Thread(() -> {
            try { Thread.sleep(500); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            synchronized (TournamentRunner.this) {
                if (stopped || finished || paused) return;
            }
            startNextGame();
        }, "Tournament-Next-Game");
        advance.setDaemon(true);
        advance.start();
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
                        boolean whiteToMove = cursor.whiteMove;
                        int moveNumber = cursor.fullMoveCounter;
                        String san = org.petero.droidfish.gamelogic.TextIO.moveToString(cursor, m, false, false);
                        if (line.length() > 0) line.append(' ');
                        if (whiteToMove) {
                            line.append(moveNumber).append('.').append(san);
                        } else {
                            // Standard PGN-style numbering: 1.e4 e5 2.Nf3 Nf6.
                            // If a variation starts with Black to move, retain 1... notation.
                            if (line.length() == 0) line.append(moveNumber).append("...");
                            line.append(san);
                        }
                        cursor.makeMove(m, new org.petero.droidfish.gamelogic.UndoInfo());
                    }
                }
                // UCI scores are from the engine's point of view. Convert both
                // centipawn and mate scores to a consistent White-positive view.
                int whitePerspectiveScore = white ? pv.score : -pv.score;
                if (!pv.isMate) {
                    double evalPawns = whitePerspectiveScore / 100.0;
                    if (white) latestWhiteEvalPawns = evalPawns; else latestBlackEvalPawns = evalPawns;
                    checkFastWinRule();
                }
                String scoreText = pv.isMate ? (whitePerspectiveScore >= 0 ? "+M" : "-M") + Math.abs(whitePerspectiveScore) :
                        String.format(java.util.Locale.US, "%+.2f", whitePerspectiveScore / 100.0);
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
        private void checkFastWinRule() {
            if (!fastWinEnabled || stopped || finished || paused || game == null || pairing == null) return;
            if (game.currPos().fullMoveCounter < ruleStartFullMove) return;
            double whiteEval = latestWhiteEvalPawns;
            double blackEval = latestBlackEvalPawns;
            if (Double.isNaN(whiteEval) || Double.isNaN(blackEval)) return;
            String result = null;
            if (whiteEval >= autoWinThreshold && blackEval >= autoWinThreshold) result = "1-0";
            else if (whiteEval <= -autoWinThreshold && blackEval <= -autoWinThreshold) result = "0-1";
            if (result == null) return;
            synchronized (TournamentRunner.this) {
                if (stopped || finished || paused || game == null || pairing == null) return;
                turnGeneration++;
                if (clock != null) clock.pause();
                whitePlayer.stopSearch();
                blackPlayer.stopSearch();
                humanAdjudication = false;
                game.adjudicateTournament(result);
                TournamentManager.Outcome outcome = "1-0".equals(result) ? TournamentManager.Outcome.WHITE_WIN : TournamentManager.Outcome.BLACK_WIN;
                finishCurrentGame(outcome, "10-evaluation fast-win rule");
            }
        }
        private String compactCount(long value) {
            if (value >= 1_000_000) return String.format(java.util.Locale.US, "%.1fM", value / 1_000_000.0);
            if (value >= 1_000) return String.format(java.util.Locale.US, "%.0fK", value / 1_000.0);
            return Long.toString(value);
        }
        /** Approximate W/D/L from the displayed centipawn score; informational only. */
        private String estimateWdl(String score) {
            try {
                String value = score.trim();
                if (value.startsWith("+M") || value.startsWith("-M"))
                    return value.startsWith("+") ? "99,1,0" : "0,1,99";
                double cp = Double.parseDouble(value) * 100.0;
                double expected = 1.0 / (1.0 + Math.exp(-cp * 0.004));
                double draw = 0.32 * Math.exp(-Math.abs(cp) / 180.0);
                double win = Math.max(0.0, expected - draw / 2.0);
                int w = (int)Math.round(win * 100.0);
                int d = (int)Math.round(draw * 100.0);
                int l = Math.max(0, 100 - w - d);
                return w + "," + d + "," + l;
            } catch (RuntimeException ex) { return "33,34,33"; }
        }
        private void publishAnalysis() {
            if (stopped || finished) return;
            String score = "—";
            String pvLine = "";
            if (lastPv.startsWith("eval ")) {
                String body = lastPv.substring(5).trim();
                int separator = body.indexOf("  ");
                score = separator >= 0 ? body.substring(0, separator).trim() : body;
                pvLine = separator >= 0 ? body.substring(separator).trim() : "";
            } else if (!lastPv.isEmpty()) {
                pvLine = lastPv;
            }
            String nodes = compactCount(lastNodes);
            String nps = compactCount(lastNps);
            String variation = pvLine == null ? "" : pvLine.trim();
            String wdl = estimateWdl(score);
            String header = "Eval: " + score + "    Depth: " + lastDepth + "    Nodes: " + nodes + "    NPS: " + nps;
            String text = header + "\nW: " + wdl.split(",")[0] + "%   D: " + wdl.split(",")[1] + "%   L: " + wdl.split(",")[2] + "%"
                    + (variation.length() == 0 ? "" : "\n" + variation);
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
                    (tournament.timeMode() != TournamentManager.TimeMode.CLOCK || activeClock.acceptMove(white, arrivalNs));
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
