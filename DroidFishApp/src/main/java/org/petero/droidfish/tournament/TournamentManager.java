package org.petero.droidfish.tournament;

import android.content.Context;
import android.os.Environment;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.BufferedOutputStream;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.Random;

/** Persistent tournament schedule/result model. Engine execution is owned by the controller. */
public final class TournamentManager {
    // Preserve existing format ordinals; new formats are appended.
    public enum Format { ROUND_ROBIN, MATCH, GAUNTLET, SWISS, CUP }
    /** Initial Swiss seed assignment: reproducibly random or strength-ranked/group-seeded. */
    public enum SwissSeedingMode { RANDOM, RANK_ENGINE }
    public enum CupSeedingMode { RANDOM, RANK_ENGINE }
    public enum Outcome { WHITE_WIN, DRAW, BLACK_WIN, UNFINISHED }
    public enum TimeMode { CLOCK, MOVE_TIME, DEPTH, NODES, MOVE_PERIOD }
    /** Automatic starting-position family when no custom FEN or PGN/BIN opening is selected. */
    public enum StartPositionMode { STANDARD, CHESS960, DFRC, CHESS324 }

    public static final class Engine {
        /** Stable tournament participant identity. Pairings and historical PGNs reference this ID. */
        public final String id;
        public final String displayName;
        /** Installed executable may change at a Cup stage checkpoint without changing participant identity. */
        public final String executableId;
        public Engine(String id, String displayName) { this(id, displayName, id); }
        public Engine(String id, String displayName, String executableId) {
            if (id == null || id.trim().isEmpty()) throw new IllegalArgumentException("Empty engine id");
            if (executableId == null || executableId.trim().isEmpty()) throw new IllegalArgumentException("Empty engine executable");
            this.id = id;
            this.displayName = displayName == null || displayName.trim().isEmpty() ? id : displayName;
            this.executableId = executableId;
        }
    }

    public static final class Pairing {
        public final int index, round, gameNumber;
        public String whiteEngineId, blackEngineId;
        public Outcome outcome;
        public String pgn;
        /** Last independent engine evaluations in White's perspective, when available. */
        public double whiteEvalPawns = Double.NaN, blackEvalPawns = Double.NaN;
        /** True after the operator has adjudicated or explicitly confirmed this anomaly. */
        public boolean anomalyReviewed;
        /** A replay keeps the old official result until the replacement game finishes. */
        public boolean replayPending;
        /** Immutable opening assignment for this scheduled game, persisted for exact replay. */
        public boolean openingAssigned;
        public int openingIndex;
        public String openingName = "";
        public String openingSanLine = "";
        /** Original PGN date is retained so replay replaces the same archived record. */
        public String pgnDate = "";
        /** Elo update order, assigned when this result is first recorded (or a replay replaces it). */
        public long ratingSequence;
        /** Cup match metadata; zero means this is not a Cup game. */
        public int cupMatchNumber, cupPairNumber, cupGameInPair;
        /** Names actually used for this completed game, preserving version history in the schedule. */
        public String whiteNameAtPlay = "", blackNameAtPlay = "";
        Pairing(int index, int round, int gameNumber, String white, String black, Outcome outcome, String pgn) {
            this.index = index; this.round = round; this.gameNumber = gameNumber;
            whiteEngineId = white; blackEngineId = black; this.outcome = outcome; this.pgn = pgn;
        }
        public boolean isComplete() { return outcome != Outcome.UNFINISHED; }
    }

    public static final class Standing {
        public final String engineId;
        public int played, wins, draws, losses, byes;
        public double points, sonnebornBerger;
        Standing(String id) { engineId = id; }
    }

    /** A Swiss bye is a pairing-level win; it has no PGN game or opponent. */
    public static final class SwissBye {
        public final int round;
        public final String engineId;
        SwissBye(int round, String engineId) { this.round = round; this.engineId = engineId; }
    }

    /** A Swiss pairing is a two-game mini-match with reversed colors. */
    private static final class SwissMatch {
        final int round, firstGameIndex, secondGameIndex;
        String firstEngineId, secondEngineId;
        SwissMatch(int round, String firstEngineId, String secondEngineId,
                   int firstGameIndex, int secondGameIndex) {
            this.round = round;
            this.firstEngineId = firstEngineId;
            this.secondEngineId = secondEngineId;
            this.firstGameIndex = firstGameIndex;
            this.secondGameIndex = secondGameIndex;
        }
    }


    /** Persistent Cup match state. Game indexes point into the shared tournament schedule. */
    public static final class CupMatch {
        public final int stageIndex, matchNumber, regularPairLimit;
        public final String engineAId, engineBId;
        public String winnerId = "";
        public boolean decided, bye;
        public int pairsScheduled, pairsCompleted;
        public double engineAPoints, engineBPoints;
        public final List<Integer> gameIndices = new ArrayList<>();
        CupMatch(int stageIndex, int matchNumber, int regularPairLimit, String a, String b) {
            this.stageIndex = stageIndex; this.matchNumber = matchNumber; this.regularPairLimit = regularPairLimit;
            this.engineAId = a == null ? "" : a; this.engineBId = b == null ? "" : b;
        }
    }

    private final Context context;
    private final String fileName;
    private String title;
    private Format format;
    private int gamesPerPair;
    private int swissRoundsTotal;
    private int swissCurrentRound;
    private SwissSeedingMode swissSeedingMode = SwissSeedingMode.RANDOM;
    private long swissRandomSeed;
    /** Original strongest-to-weakest order, before TCEC-style group seeding. */
    private final List<String> swissStrengthOrder = new ArrayList<>();
    private CupSeedingMode cupSeedingMode = CupSeedingMode.RANDOM;
    private long cupRandomSeed;
    private int cupBracketSize, cupCurrentStage;
    private boolean cupCheckpointPending, cupFinished;
    private final List<String> cupStrengthOrder = new ArrayList<>();
    private final List<String> cupSeedSlots = new ArrayList<>();
    private final List<CupMatch> cupMatches = new ArrayList<>();
    private int cupNextMatchNumber = 1, cupNextGameNumber = 1;
    /** Stable base filename for the one cumulative Cup PGN across every stage. */
    private String cupPgnFileBaseName = "";
    private long baseTimeMs = 30000, incrementMs = 500;
    private TimeMode timeMode = TimeMode.CLOCK;
    private int limitValue = 30;
    private String openingPgn = "";
    private String openingPgnFileName = "";
    private String openingBinPath = "";
    private int openingPlies;
    private int openingStartIndex = 1;
    private boolean repeatOpening;
    private StartPositionMode startPositionMode = StartPositionMode.STANDARD;
    private int chess960StartId = 1;
    private long startingPositionSeed = new java.security.SecureRandom().nextLong();
    private String openingFen = "";
    private long tournamentElapsedMs;
    private boolean anomalyRuleSettingsSaved;
    private boolean anomalyRulesEnabled;
    private int anomalyRuleStartFullMove = 1;
    private int anomalyMaximumFullMoves = 160;
    private static final double ANOMALY_EVAL_THRESHOLD_PAWNS = 1.50;
    private final List<Engine> engines = new ArrayList<>();
    private final List<Pairing> pairings = new ArrayList<>();
    // Elo is opt-in per tournament. Older saved tournaments therefore remain unrated.
    private boolean ratingEnabled;
    private boolean ratingShowDecimals;
    // Only a manager created for a brand-new event may receive initial Elo settings.
    // Managers reconstructed from saved JSON are permanently locked to their saved setting.
    private boolean ratingConfigurationAllowed = true;
    private double ratingKFactor = 20.0;
    private long nextRatingSequence = 1L;
    private final Map<String, Double> initialRatings = new HashMap<>();
    private final List<SwissBye> swissByes = new ArrayList<>();
    private final List<SwissMatch> swissMatches = new ArrayList<>();
    /** Game indices in operator-selected priority order. */
    private final ArrayList<Integer> playQueue = new ArrayList<>();
    /** Scheduled game interrupted by a pause/process death; it resumes before the priority queue. */
    private int activePairingIndex = -1;
    private volatile String externalSaveError = "";
    // Snapshot under the manager monitor, but perform gzip/fsync/shared-storage IO
    // under a separate lock so standings and clock readers never wait on disk.
    private final Object saveIoLock = new Object();
    private volatile long latestSaveRevision;
    // Prevent two concurrent imports/creates from selecting the same not-yet-saved name.
    private static final Set<String> RESERVED_TOURNAMENT_FILE_NAMES = new HashSet<>();

    public TournamentManager(Context context, String title, Format format, List<Engine> engines, int gamesPerPair) {
        this(context, title, format, engines, gamesPerPair, 30000, 500, "", 0, false);
    }

    public TournamentManager(Context context, String title, Format format, List<Engine> engines,
                             int gamesPerPair, long baseTimeMs, long incrementMs,
                             String openingPgn, int openingPlies) {
        this(context, title, format, engines, gamesPerPair, baseTimeMs, incrementMs, openingPgn, openingPlies, false);
    }

    public TournamentManager(Context context, String title, Format format, List<Engine> engines,
                             int gamesPerPair, long baseTimeMs, long incrementMs,
                             String openingPgn, int openingPlies, boolean repeatOpening) {
        this(context, title, format, engines, gamesPerPair, baseTimeMs, incrementMs, openingPgn, openingPlies, repeatOpening, TimeMode.CLOCK, 30);
    }

    public TournamentManager(Context context, String title, Format format, List<Engine> engines,
                             int gamesPerPair, long baseTimeMs, long incrementMs,
                             String openingPgn, int openingPlies, boolean repeatOpening, TimeMode timeMode, int limitValue) {
        this(context, title, format, engines, gamesPerPair, baseTimeMs, incrementMs, openingPgn, openingPlies, repeatOpening, timeMode, limitValue, 1);
    }

    public TournamentManager(Context context, String title, Format format, List<Engine> engines,
                             int gamesPerPair, long baseTimeMs, long incrementMs, String openingPgn, int openingPlies,
                             boolean repeatOpening, TimeMode timeMode, int limitValue, int openingStartIndex) {
        this(context, title, format, engines, gamesPerPair, baseTimeMs, incrementMs, openingPgn, openingPlies,
                repeatOpening, timeMode, limitValue, openingStartIndex, 0);
    }

    public TournamentManager(Context context, String title, Format format, List<Engine> engines,
                             int gamesPerPair, long baseTimeMs, long incrementMs, String openingPgn, int openingPlies,
                             boolean repeatOpening, TimeMode timeMode, int limitValue, int openingStartIndex,
                             int swissRounds) {
        this(context, title, format, engines, gamesPerPair, baseTimeMs, incrementMs, openingPgn, openingPlies,
                repeatOpening, timeMode, limitValue, openingStartIndex, swissRounds,
                SwissSeedingMode.RANK_ENGINE, engineIdsInOrder(engines));
    }

    public TournamentManager(Context context, String title, Format format, List<Engine> engines,
                             int gamesPerPair, long baseTimeMs, long incrementMs, String openingPgn, int openingPlies,
                             boolean repeatOpening, TimeMode timeMode, int limitValue, int openingStartIndex,
                             int swissRounds, SwissSeedingMode seedingMode, List<String> rankOrderIds) {
        this(context, title, format, engines, gamesPerPair, baseTimeMs, incrementMs, openingPgn, openingPlies,
                repeatOpening, timeMode, limitValue, openingStartIndex, swissRounds, seedingMode, rankOrderIds,
                CupSeedingMode.RANDOM, engineIdsInOrder(engines));
    }

    public TournamentManager(Context context, String title, Format format, List<Engine> engines,
                             int gamesPerPair, long baseTimeMs, long incrementMs, String openingPgn, int openingPlies,
                             boolean repeatOpening, TimeMode timeMode, int limitValue, int openingStartIndex,
                             int swissRounds, SwissSeedingMode seedingMode, List<String> rankOrderIds,
                             CupSeedingMode cupMode, List<String> cupRankOrderIds) {
        if (context == null || engines == null || engines.size() < 2) throw new IllegalArgumentException("At least two engines are required");
        if (format == null) throw new IllegalArgumentException("Tournament format is required");
        if (gamesPerPair < 1) throw new IllegalArgumentException("Games per pairing must be positive");
        if (baseTimeMs < 1 || incrementMs < 0 || openingPlies < 0) throw new IllegalArgumentException("Invalid tournament settings");
        this.context = context.getApplicationContext();
        this.title = title == null ? "CET Tournament" : title;
        // Each event gets a title-based, collision-safe compressed filename.
        this.fileName = newUniqueFileName(this.context, this.title);
        this.format = format;
        this.gamesPerPair = (format == Format.SWISS || format == Format.CUP) ? 2 : gamesPerPair;
        if (format == Format.SWISS) {
            int maximumRounds = maximumSwissRounds(engines.size());
            this.swissRoundsTotal = swissRounds > 0 ? swissRounds : recommendedSwissRounds(engines.size());
            if (this.swissRoundsTotal < 1 || this.swissRoundsTotal > maximumRounds)
                throw new IllegalArgumentException("Swiss rounds must be between 1 and " + maximumRounds +
                        " for this engine count");
        }
        this.baseTimeMs = baseTimeMs; this.incrementMs = incrementMs;
        this.timeMode = timeMode == null ? TimeMode.CLOCK : timeMode; this.limitValue = Math.max(1, limitValue);
        this.openingPgn = openingPgn == null ? "" : openingPgn; this.openingPlies = openingPlies;
        this.repeatOpening = format == Format.SWISS || format == Format.CUP || repeatOpening;
        this.openingStartIndex = Math.max(1, openingStartIndex);
        if (format == Format.SWISS) {
            this.swissSeedingMode = seedingMode == null ? SwissSeedingMode.RANDOM : seedingMode;
            if (this.swissSeedingMode == SwissSeedingMode.RANDOM) {
                // Persist the random seed and resulting order for reproducible resume/import.
                this.swissRandomSeed = new Random().nextLong();
                ArrayList<Engine> shuffled = new ArrayList<>(engines);
                Collections.shuffle(shuffled, new Random(this.swissRandomSeed));
                this.engines.addAll(shuffled);
                for (Engine engine : shuffled) swissStrengthOrder.add(engine.id);
            } else {
                ArrayList<Engine> strengthOrder = orderEnginesByIds(engines, rankOrderIds);
                for (Engine engine : strengthOrder) swissStrengthOrder.add(engine.id);
                this.engines.addAll(createEqualDistanceSeedOrder(strengthOrder));
            }
        } else if (format == Format.CUP) {
            this.engines.addAll(engines);
            this.cupSeedingMode = cupMode == null ? CupSeedingMode.RANDOM : cupMode;
            this.cupBracketSize = CupTournamentManager.bracketSizeFor(engines.size());
            this.cupRandomSeed = new Random().nextLong();
            ArrayList<String> selectedIds = new ArrayList<>();
            for (Engine engine : engines) selectedIds.add(engine.id);
            List<String> rankOrder = cupRankOrderIds == null ? selectedIds : cupRankOrderIds;
            if (this.cupSeedingMode == CupSeedingMode.RANK_ENGINE) {
                if (rankOrder.size() != engines.size())
                    throw new IllegalArgumentException("Rank Engine Cup requires a ranking for every selected engine");
                this.cupStrengthOrder.addAll(orderEngineIds(selectedIds, rankOrder));
            } else {
                this.cupStrengthOrder.addAll(selectedIds);
            }
            this.cupSeedSlots.addAll(CupTournamentManager.createSeedSlots(selectedIds, rankOrder,
                    this.cupSeedingMode == CupSeedingMode.RANK_ENGINE ? CupTournamentManager.SeedingMode.RANK_ENGINE : CupTournamentManager.SeedingMode.RANDOM,
                    this.cupRandomSeed, cupBracketSize));
            this.cupCurrentStage = 0;
            generateCupInitialStage();
        } else {
            this.engines.addAll(engines);
        }
        if (format != Format.CUP) buildSchedule();
        // Opening assignment is deferred to prepareOpeningAssignments(), which is
        // invoked by the activity on a worker thread. Avoid parsing a large PGN
        // book while the setup screen is running on the Android UI thread.
    }

    private static List<String> engineIdsInOrder(List<Engine> engines) {
        ArrayList<String> ids = new ArrayList<>();
        if (engines != null) for (Engine engine : engines) ids.add(engine.id);
        return ids;
    }

    private static List<String> orderEngineIds(List<String> selectedIds, List<String> orderIds) {
        if (orderIds == null || orderIds.size() != selectedIds.size())
            throw new IllegalArgumentException("Ranking must include every selected engine exactly once");
        Set<String> selected = new HashSet<>(selectedIds);
        Set<String> seen = new HashSet<>();
        ArrayList<String> ordered = new ArrayList<>();
        for (String id : orderIds) {
            if (id == null || !selected.contains(id) || !seen.add(id))
                throw new IllegalArgumentException("Ranking contains an unknown or duplicated engine");
            ordered.add(id);
        }
        return ordered;
    }

    private static ArrayList<Engine> orderEnginesByIds(List<Engine> engines, List<String> orderIds) {
        if (orderIds == null || orderIds.size() != engines.size())
            throw new IllegalArgumentException("Rank Engine mode requires a rank for every selected engine");
        Map<String, Engine> byId = new HashMap<>();
        for (Engine engine : engines) byId.put(engine.id, engine);
        ArrayList<Engine> ordered = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String id : orderIds) {
            Engine engine = byId.get(id);
            if (engine == null || !seen.add(id))
                throw new IllegalArgumentException("Swiss engine ranking contains a missing or duplicated engine");
            ordered.add(engine);
        }
        if (ordered.size() != engines.size()) throw new IllegalArgumentException("Swiss engine ranking is incomplete");
        return ordered;
    }

    /** TCEC-style group seeding: strength groups are interleaved to assign seeds. */
    private static ArrayList<Engine> createEqualDistanceSeedOrder(List<Engine> strengthOrder) {
        int count = strengthOrder.size();
        if (count < 2) return new ArrayList<>(strengthOrder);
        int groupCount = Math.max(2, (int)Math.round(count / 6.5));
        if ((groupCount & 1) != 0) groupCount++;
        if (groupCount > count) groupCount = (count & 1) == 0 ? count : count - 1;
        groupCount = Math.max(2, groupCount);
        ArrayList<ArrayList<Engine>> groups = new ArrayList<>();
        int next = 0;
        for (int group = 0; group < groupCount; group++) {
            int remaining = count - next;
            int groupsRemaining = groupCount - group;
            int groupSize = (remaining + groupsRemaining - 1) / groupsRemaining;
            ArrayList<Engine> members = new ArrayList<>();
            for (int i = 0; i < groupSize && next < count; i++) members.add(strengthOrder.get(next++));
            groups.add(members);
        }
        ArrayList<Engine> seeded = new ArrayList<>(count);
        for (int row = 0; seeded.size() < count; row++) {
            for (ArrayList<Engine> group : groups) if (row < group.size()) seeded.add(group.get(row));
        }
        return seeded;
    }

    private static boolean containsEngineId(List<Engine> engines, String engineId) {
        for (Engine engine : engines) if (engine.id.equals(engineId)) return true;
        return false;
    }

    private TournamentManager(Context context, JSONObject root, String fileName) throws JSONException {
        ratingConfigurationAllowed = false;
        this.context = context.getApplicationContext(); this.fileName = fileName == null || fileName.isEmpty() ? "cet_tournament.json" : fileName;
        title = root.optString("title", "CET Tournament"); format = Format.valueOf(root.getString("format"));
        gamesPerPair = root.optInt("gamesPerPair", 2);
        swissRoundsTotal = Math.max(0, root.optInt("swissRoundsTotal", 0));
        swissCurrentRound = Math.max(0, root.optInt("swissCurrentRound", 0));
        String savedSeedingMode = root.optString("swissSeedingMode", "RANK_ENGINE");
        try { swissSeedingMode = SwissSeedingMode.valueOf(savedSeedingMode); }
        catch (IllegalArgumentException invalidMode) { swissSeedingMode = SwissSeedingMode.RANK_ENGINE; }
        swissRandomSeed = root.optLong("swissRandomSeed", 0L);
        baseTimeMs = root.optLong("baseTimeMs", 30000);
        incrementMs = root.optLong("incrementMs", 500);
        openingPgn = root.optString("openingPgn", "");
        openingPgnFileName = root.optString("openingPgnFileName", "");
        openingBinPath = root.optString("openingBinPath", "");
        openingPlies = root.optInt("openingPlies", 0);
        repeatOpening = root.optBoolean("repeatOpening", false);
        tournamentElapsedMs = Math.max(0L, root.optLong("tournamentElapsedMs", 0L));
        openingStartIndex = Math.max(1, root.optInt("openingStartIndex", 1));
        String savedPositionMode = root.optString("startPositionMode", root.optBoolean("chess960Enabled", false) ? "CHESS960" : "STANDARD");
        try { startPositionMode = StartPositionMode.valueOf(savedPositionMode); }
        catch (IllegalArgumentException invalidMode) { startPositionMode = root.optBoolean("chess960Enabled", false) ? StartPositionMode.CHESS960 : StartPositionMode.STANDARD; }
        chess960StartId = Math.max(1, Math.min(960, root.optInt("chess960StartId", 1)));
        startingPositionSeed = root.optLong("startingPositionSeed", startingPositionSeed);
        openingFen = root.optString("openingFen", "");
        anomalyRuleSettingsSaved = root.optBoolean("anomalyRuleSettingsSaved", false);
        anomalyRulesEnabled = root.optBoolean("anomalyRulesEnabled", true);
        anomalyRuleStartFullMove = Math.max(1, root.optInt("anomalyRuleStartFullMove", 1));
        anomalyMaximumFullMoves = Math.max(1, root.optInt("anomalyMaximumFullMoves", 160));
        timeMode = TimeMode.valueOf(root.optString("timeMode", "CLOCK")); limitValue = root.optInt("limitValue", 30);
        JSONArray ea = root.getJSONArray("engines");
        for (int i = 0; i < ea.length(); i++) { JSONObject e = ea.getJSONObject(i); String id = e.getString("id"); engines.add(new Engine(id, e.optString("name"), e.optString("executableId", id))); }
        ratingEnabled = root.optBoolean("ratingEnabled", false);
        ratingShowDecimals = ratingEnabled && root.optBoolean("ratingShowDecimals", false);
        double savedK = root.optDouble("ratingKFactor", 20.0);
        ratingKFactor = Double.isFinite(savedK) && savedK > 0.0 && savedK <= 100.0 ? savedK : 20.0;
        JSONArray initialRatingArray = root.optJSONArray("initialRatings");
        boolean completeInitialRatings = initialRatingArray != null;
        if (initialRatingArray != null) {
            for (int i = 0; i < initialRatingArray.length(); i++) {
                JSONObject rating = initialRatingArray.optJSONObject(i);
                if (rating == null) { completeInitialRatings = false; continue; }
                String engineId = rating.optString("engineId", "");
                double value = rating.optDouble("rating", Double.NaN);
                if (engineId.isEmpty() || !Double.isFinite(value) || value < 0.0 || value > 10000.0 ||
                        !containsEngineId(engines, engineId) || initialRatings.containsKey(engineId)) {
                    completeInitialRatings = false;
                    continue;
                }
                initialRatings.put(engineId, value);
            }
        }
        if (ratingEnabled && (!completeInitialRatings || initialRatings.size() != engines.size())) {
            ratingEnabled = false;
            ratingShowDecimals = false;
            initialRatings.clear();
        }
        JSONArray strengthArray = root.optJSONArray("swissStrengthOrder");
        if (strengthArray != null) {
            for (int i = 0; i < strengthArray.length(); i++) swissStrengthOrder.add(strengthArray.optString(i, ""));
        } else {
            // Legacy Swiss files already contain their seed order in the engines array.
            for (Engine engine : engines) swissStrengthOrder.add(engine.id);
        }
        JSONArray pa = root.getJSONArray("pairings");
        for (int i = 0; i < pa.length(); i++) {
            JSONObject p = pa.getJSONObject(i);
            Pairing pairing = new Pairing(p.optInt("index", i), p.optInt("round", 1), p.optInt("gameNumber", i + 1),
                    p.optString("white", ""), p.optString("black", ""), Outcome.valueOf(p.optString("outcome", "UNFINISHED")), p.optString("pgn", ""));
            pairing.whiteEvalPawns = p.optDouble("whiteEvalPawns", Double.NaN);
            pairing.blackEvalPawns = p.optDouble("blackEvalPawns", Double.NaN);
            pairing.anomalyReviewed = p.optBoolean("anomalyReviewed", false);
            pairing.replayPending = p.optBoolean("replayPending", false);
            pairing.openingAssigned = p.optBoolean("openingAssigned", false);
            pairing.openingIndex = p.optInt("openingIndex", 0);
            pairing.openingName = p.optString("openingName", "");
            pairing.openingSanLine = p.optString("openingSanLine", "");
            pairing.pgnDate = p.optString("pgnDate", pgnTagValue(pairing.pgn, "Date"));
            pairing.ratingSequence = Math.max(0L, p.optLong("ratingSequence", 0L));
            nextRatingSequence = Math.max(nextRatingSequence, pairing.ratingSequence + 1L);
            pairing.cupMatchNumber = p.optInt("cupMatchNumber", 0);
            pairing.cupPairNumber = p.optInt("cupPairNumber", 0);
            pairing.cupGameInPair = p.optInt("cupGameInPair", 0);
            pairing.whiteNameAtPlay = p.optString("whiteNameAtPlay", "");
            pairing.blackNameAtPlay = p.optString("blackNameAtPlay", "");
            pairings.add(pairing);
        }
        JSONArray byeArray = root.optJSONArray("swissByes");
        if (byeArray != null) for (int i = 0; i < byeArray.length(); i++) {
            JSONObject b = byeArray.optJSONObject(i);
            if (b != null) swissByes.add(new SwissBye(b.optInt("round", 0), b.optString("engine", "")));
        }
        JSONArray matchArray = root.optJSONArray("swissMatches");
        if (matchArray != null) for (int i = 0; i < matchArray.length(); i++) {
            JSONObject m = matchArray.optJSONObject(i);
            if (m != null) swissMatches.add(new SwissMatch(m.optInt("round", 0),
                    m.optString("first", ""), m.optString("second", ""),
                    m.optInt("firstGame", -1), m.optInt("secondGame", -1)));
        }
        String savedCupMode = root.optString("cupSeedingMode", "RANDOM");
        try { cupSeedingMode = CupSeedingMode.valueOf(savedCupMode); }
        catch (IllegalArgumentException invalidCupMode) { cupSeedingMode = CupSeedingMode.RANDOM; }
        cupRandomSeed = root.optLong("cupRandomSeed", 0L);
        cupBracketSize = root.optInt("cupBracketSize", 0);
        cupCurrentStage = Math.max(0, root.optInt("cupCurrentStage", 0));
        cupCheckpointPending = root.optBoolean("cupCheckpointPending", false);
        cupFinished = root.optBoolean("cupFinished", false);
        JSONArray cupStrength = root.optJSONArray("cupStrengthOrder");
        if (cupStrength != null) for (int i = 0; i < cupStrength.length(); i++) cupStrengthOrder.add(cupStrength.optString(i, ""));
        JSONArray cupSlots = root.optJSONArray("cupSeedSlots");
        if (cupSlots != null) for (int i = 0; i < cupSlots.length(); i++) cupSeedSlots.add(cupSlots.isNull(i) ? "" : cupSlots.optString(i, ""));
        cupNextMatchNumber = Math.max(1, root.optInt("cupNextMatchNumber", 1));
        cupNextGameNumber = Math.max(1, root.optInt("cupNextGameNumber", 1));
        cupPgnFileBaseName = root.optString("cupPgnFileBaseName", "");
        JSONArray cupMatchArray = root.optJSONArray("cupMatches");
        if (cupMatchArray != null) for (int i = 0; i < cupMatchArray.length(); i++) {
            JSONObject o = cupMatchArray.optJSONObject(i);
            if (o == null) continue;
            CupMatch m = new CupMatch(o.optInt("stage", 0), o.optInt("number", 0), o.optInt("regularPairLimit", 0),
                    o.optString("engineA", ""), o.optString("engineB", ""));
            m.winnerId = o.optString("winner", ""); m.decided = o.optBoolean("decided", false); m.bye = o.optBoolean("bye", false);
            m.pairsScheduled = o.optInt("pairsScheduled", 0); m.pairsCompleted = o.optInt("pairsCompleted", 0);
            m.engineAPoints = o.optDouble("engineAPoints", 0.0); m.engineBPoints = o.optDouble("engineBPoints", 0.0);
            JSONArray games = o.optJSONArray("games");
            if (games != null) for (int j = 0; j < games.length(); j++) m.gameIndices.add(games.optInt(j, -1));
            cupMatches.add(m);
        }
        if (format == Format.CUP) {
            gamesPerPair = 2; repeatOpening = true;
            if (cupBracketSize < 2) cupBracketSize = CupTournamentManager.bracketSizeFor(engines.size());
            if (cupMatches.isEmpty()) throw new JSONException("Cup tournament has no bracket match history");
            if (cupFinished) cupCheckpointPending = false;
        }
        if (format == Format.SWISS) {
            gamesPerPair = 2;
            if (swissRoundsTotal < 1) swissRoundsTotal = recommendedSwissRounds(engines.size());
            if (swissCurrentRound < 1 && !swissMatches.isEmpty()) {
                for (SwissMatch match : swissMatches) swissCurrentRound = Math.max(swissCurrentRound, match.round);
            }
        }
        nextRatingSequence = Math.max(nextRatingSequence, root.optLong("nextRatingSequence", 1L));
        if (ratingEnabled) {
            // An enabled rating history must have one unique sequence for every recorded game.
            HashSet<Long> usedRatingSequences = new HashSet<>();
            for (Pairing pairing : pairings) {
                if (pairing.isComplete() && (pairing.ratingSequence <= 0L ||
                        pairing.ratingSequence == Long.MAX_VALUE || !usedRatingSequences.add(pairing.ratingSequence))) {
                    ratingEnabled = false;
                    ratingShowDecimals = false;
                    initialRatings.clear();
                    break;
                }
            }
        }
        activePairingIndex = root.optInt("activePairingIndex", -1);
        if (activePairingIndex < 0 || activePairingIndex >= pairings.size() ||
                (pairings.get(activePairingIndex).isComplete() && !pairings.get(activePairingIndex).replayPending))
            activePairingIndex = -1;
        JSONArray queue = root.optJSONArray("playQueue");
        if (queue != null) {
            for (int i = 0; i < queue.length(); i++) {
                int index = queue.optInt(i, -1);
                if (index >= 0 && index < pairings.size() && !playQueue.contains(index)) {
                    Pairing queued = pairings.get(index);
                    if (queued.replayPending || !queued.isComplete()) playQueue.add(index);
                }
            }
        }
    }


    /** Import a compatible tournament JSON document under a new private filename.
     * The source filename is deliberately ignored: arbitrary .json names are accepted,
     * and the imported event becomes visible in savedTournamentFiles().
     */
    public static TournamentManager importJson(Context context, String json) throws IOException, JSONException {
        if (context == null) throw new IOException("Application context is unavailable");
        if (json == null || json.trim().isEmpty()) throw new IOException("The selected JSON file is empty");
        if (!json.isEmpty() && json.charAt(0) == '\uFEFF') json = json.substring(1);
        JSONObject root = new JSONObject(json);
        TournamentManager imported;
        try {
            imported = new TournamentManager(context, root,
                    newUniqueFileName(context.getApplicationContext(), root.optString("title", "Tournament")));
            imported.validateImportedTournament();
        } catch (RuntimeException | JSONException invalid) {
            throw new IOException("Unsupported or invalid tournament JSON: " + invalid.getMessage(), invalid);
        }
        imported.save();
        return imported;
    }

    private void validateImportedTournament() throws IOException {
        if (engines.size() < 2) throw new IOException("A tournament must contain at least two engines");
        if (pairings.isEmpty()) throw new IOException("The tournament schedule is empty");
        if (gamesPerPair < 1 || limitValue < 1) throw new IOException("Invalid tournament settings");
        java.util.HashSet<String> engineIds = new java.util.HashSet<>();
        for (Engine engine : engines) {
            if (!engineIds.add(engine.id)) throw new IOException("Duplicate engine ID: " + engine.id);
        }
        java.util.HashSet<Integer> indices = new java.util.HashSet<>();
        for (int i = 0; i < pairings.size(); i++) {
            Pairing pairing = pairings.get(i);
            if (pairing.index != i || !indices.add(pairing.index))
                throw new IOException("Schedule game indexes are missing, duplicated, or out of order at game " + (i + 1));
            if (pairing.whiteEngineId == null || pairing.blackEngineId == null ||
                    !engineIds.contains(pairing.whiteEngineId) || !engineIds.contains(pairing.blackEngineId))
                throw new IOException("Game " + (i + 1) + " refers to an engine not listed in the tournament");
            if (pairing.whiteEngineId.equals(pairing.blackEngineId))
                throw new IOException("Game " + (i + 1) + " has the same engine on both sides");
            if (pairing.outcome == null) throw new IOException("Game " + (i + 1) + " has an invalid result");
        }
        if (format == Format.SWISS) validateImportedSwissData(engineIds);
        if (format == Format.CUP) validateImportedCupData(engineIds);
    }

    private void validateImportedSwissData(Set<String> engineIds) throws IOException {
        int maximumRounds = maximumSwissRounds(engines.size());
        if (swissRoundsTotal < 1 || swissRoundsTotal > maximumRounds ||
                swissCurrentRound < 1 || swissCurrentRound > swissRoundsTotal)
            throw new IOException("Invalid Swiss round settings in imported tournament");
        if (swissMatches.isEmpty()) throw new IOException("Imported Swiss tournament has no pairing history");
        if (swissStrengthOrder.size() != engines.size() ||
                !new HashSet<>(swissStrengthOrder).equals(engineIds) ||
                new HashSet<>(swissStrengthOrder).size() != engines.size())
            throw new IOException("Imported Swiss seeding order is incomplete or contains duplicate engines");
        Set<Integer> coveredGames = new HashSet<>();
        Set<String> opponentPairs = new HashSet<>();
        Set<String> roundParticipants = new HashSet<>();
        for (SwissMatch match : swissMatches) {
            if (match.round < 1 || match.round > swissCurrentRound ||
                    !engineIds.contains(match.firstEngineId) || !engineIds.contains(match.secondEngineId) ||
                    match.firstEngineId.equals(match.secondEngineId) ||
                    match.firstGameIndex < 0 || match.secondGameIndex < 0 ||
                    match.firstGameIndex >= pairings.size() || match.secondGameIndex >= pairings.size() ||
                    match.firstGameIndex == match.secondGameIndex ||
                    !coveredGames.add(match.firstGameIndex) || !coveredGames.add(match.secondGameIndex))
                throw new IOException("Imported Swiss pairing metadata is invalid");
            Pairing first = pairings.get(match.firstGameIndex);
            Pairing second = pairings.get(match.secondGameIndex);
            if (first.round != match.round || second.round != match.round ||
                    !first.blackEngineId.equals(match.firstEngineId) ||
                    !first.whiteEngineId.equals(match.secondEngineId) ||
                    !second.whiteEngineId.equals(match.firstEngineId) ||
                    !second.blackEngineId.equals(match.secondEngineId))
                throw new IOException("Imported Swiss color-reversal data does not match the schedule");
            String low = match.firstEngineId.compareTo(match.secondEngineId) < 0 ? match.firstEngineId : match.secondEngineId;
            String high = match.firstEngineId.compareTo(match.secondEngineId) < 0 ? match.secondEngineId : match.firstEngineId;
            if (!opponentPairs.add(low.length() + ":" + low + high.length() + ":" + high))
                throw new IOException("Imported Swiss tournament contains a repeated opponent pairing");
            for (String id : new String[]{match.firstEngineId, match.secondEngineId}) {
                if (!roundParticipants.add(match.round + ":" + id))
                    throw new IOException("An engine appears twice in one imported Swiss round");
            }
        }
        if (coveredGames.size() != pairings.size())
            throw new IOException("Imported Swiss tournament has games without pairing metadata");
        Set<Integer> byeRounds = new HashSet<>();
        Set<String> byeEngines = new HashSet<>();
        for (SwissBye bye : swissByes) {
            if (bye.round < 1 || bye.round > swissCurrentRound || !engineIds.contains(bye.engineId) ||
                    !byeRounds.add(bye.round) || !byeEngines.add(bye.engineId) ||
                    roundParticipants.contains(bye.round + ":" + bye.engineId))
                throw new IOException("Imported Swiss bye data is invalid");
        }
    }

    private void validateImportedCupData(Set<String> engineIds) throws IOException {
        int stages = CupTournamentManager.stageCount(cupBracketSize);
        if (cupBracketSize != CupTournamentManager.bracketSizeFor(engines.size()) ||
                cupSeedSlots.size() != cupBracketSize || cupCurrentStage < 0 ||
                cupCurrentStage >= stages || cupMatches.isEmpty())
            throw new IOException("Imported Cup bracket settings are invalid");

        Set<String> slotIds = new HashSet<>();
        for (String id : cupSeedSlots) if (id != null && !id.isEmpty()) {
            if (!engineIds.contains(id) || !slotIds.add(id))
                throw new IOException("Cup seed slots contain an unknown or duplicated engine");
        }
        if (slotIds.size() != engines.size())
            throw new IOException("Cup seed slots do not contain every selected engine");
        if (cupStrengthOrder.size() != engines.size() || !new HashSet<>(cupStrengthOrder).equals(engineIds) ||
                new HashSet<>(cupStrengthOrder).size() != engines.size())
            throw new IOException("Cup ranking/draw metadata is incomplete");

        Map<Integer, CupMatch> matchesByNumber = new HashMap<>();
        ArrayList<ArrayList<CupMatch>> stagesOfMatches = new ArrayList<>();
        for (int stage = 0; stage < stages; stage++) stagesOfMatches.add(new ArrayList<>());
        Set<Integer> coveredGames = new HashSet<>();
        for (CupMatch match : cupMatches) {
            if (match.matchNumber < 1 || matchesByNumber.put(match.matchNumber, match) != null ||
                    match.stageIndex < 0 || match.stageIndex > cupCurrentStage ||
                    match.regularPairLimit != CupTournamentManager.regularPairsPerMatch(cupBracketSize, match.stageIndex))
                throw new IOException("Cup match metadata is invalid");
            if (!match.engineAId.isEmpty() && !engineIds.contains(match.engineAId))
                throw new IOException("Cup match references an unknown engine");
            if (!match.engineBId.isEmpty() && !engineIds.contains(match.engineBId))
                throw new IOException("Cup match references an unknown engine");
            if (match.engineAId.isEmpty() && match.engineBId.isEmpty())
                throw new IOException("Cup bracket contains an empty match");
            if (match.engineAId.equals(match.engineBId) && !match.engineAId.isEmpty())
                throw new IOException("Cup match contains the same engine twice");
            boolean hasBye = match.engineAId.isEmpty() || match.engineBId.isEmpty();
            if (match.bye != hasBye || (hasBye && (!match.decided || match.winnerId.isEmpty() ||
                    !match.winnerId.equals(match.engineAId.isEmpty() ? match.engineBId : match.engineAId))))
                throw new IOException("Cup bye data is inconsistent");
            if (match.decided && !hasBye && (!match.winnerId.equals(match.engineAId) && !match.winnerId.equals(match.engineBId)))
                throw new IOException("Cup match winner is not one of its participants");
            if (!match.decided && !match.winnerId.isEmpty())
                throw new IOException("An unfinished Cup match already has a winner");
            if (match.pairsScheduled < 0 || match.pairsCompleted < 0 || match.pairsCompleted > match.pairsScheduled ||
                    match.gameIndices.size() != match.pairsScheduled * 2 || (hasBye && match.pairsScheduled != 0) ||
                    (!hasBye && match.pairsScheduled < 1))
                throw new IOException("Cup match pair counts are invalid");

            boolean[] firstDone = new boolean[match.pairsScheduled];
            boolean[] secondDone = new boolean[match.pairsScheduled];
            for (Integer index : match.gameIndices) {
                if (index == null || index < 0 || index >= pairings.size() || !coveredGames.add(index))
                    throw new IOException("Cup match contains an invalid or duplicated game reference");
                Pairing pairing = pairings.get(index);
                if (pairing.cupMatchNumber != match.matchNumber || pairing.round != match.stageIndex + 1 ||
                        pairing.cupPairNumber < 1 || pairing.cupPairNumber > match.pairsScheduled ||
                        (pairing.cupGameInPair != 1 && pairing.cupGameInPair != 2))
                    throw new IOException("Cup game metadata does not match its bracket match");
                int pairIndex = pairing.cupPairNumber - 1;
                if (pairing.cupGameInPair == 1) firstDone[pairIndex] = pairing.isComplete();
                else secondDone[pairIndex] = pairing.isComplete();
                if (pairing.cupGameInPair == 1) {
                    if (!pairing.whiteEngineId.equals(match.engineAId) || !pairing.blackEngineId.equals(match.engineBId))
                        throw new IOException("Cup first game does not use the expected colors");
                } else if (!pairing.whiteEngineId.equals(match.engineBId) || !pairing.blackEngineId.equals(match.engineAId)) {
                    throw new IOException("Cup second game does not reverse the first game's colors");
                }
            }
            int contiguousCompleted = 0;
            for (int pair = 0; pair < match.pairsScheduled; pair++) {
                // Each scheduled color-reversed pair must have exactly two records.
                int game1Count = 0, game2Count = 0;
                for (Integer index : match.gameIndices) {
                    Pairing game = pairings.get(index);
                    if (game.cupPairNumber == pair + 1) {
                        if (game.cupGameInPair == 1) game1Count++;
                        if (game.cupGameInPair == 2) game2Count++;
                    }
                }
                if (game1Count != 1 || game2Count != 1)
                    throw new IOException("Cup color-reversal pair is incomplete");
                if (firstDone[pair] && secondDone[pair]) {
                    if (contiguousCompleted == pair) contiguousCompleted++;
                }
            }
            if (contiguousCompleted != match.pairsCompleted || (match.decided &&
                    (match.pairsCompleted != match.pairsScheduled || (!hasBye &&
                    Math.abs(match.engineAPoints - match.engineBPoints) < 0.000001))))
                throw new IOException("Cup match completion state is inconsistent");
            if (!hasBye) {
                double aPoints = 0.0, bPoints = 0.0;
                for (Integer index : match.gameIndices) {
                    Pairing game = pairings.get(index);
                    if (!game.isComplete()) continue;
                    aPoints += cupGamePoints(match.engineAId, game);
                    bPoints += cupGamePoints(match.engineBId, game);
                }
                if (Math.abs(aPoints - match.engineAPoints) > 0.000001 ||
                        Math.abs(bPoints - match.engineBPoints) > 0.000001)
                    throw new IOException("Cup match totals do not match its game results");
                if (match.decided && !match.winnerId.equals(aPoints > bPoints ? match.engineAId : match.engineBId))
                    throw new IOException("Cup match winner does not match its score");
            }
            stagesOfMatches.get(match.stageIndex).add(match);
        }
        if (coveredGames.size() != pairings.size())
            throw new IOException("Cup schedule contains a game without match metadata");

        int expectedMatchesSoFar = 0;
        for (int stage = 0; stage <= cupCurrentStage; stage++) {
            ArrayList<CupMatch> stageMatches = stagesOfMatches.get(stage);
            int expectedCount = cupBracketSize >> (stage + 1);
            if (stageMatches.size() != expectedCount)
                throw new IOException("Cup bracket has an incomplete stage " + (stage + 1));
            Collections.sort(stageMatches, (a, b) -> Integer.compare(a.matchNumber, b.matchNumber));
            for (int i = 0; i < stageMatches.size(); i++) {
                CupMatch match = stageMatches.get(i);
                if (match.matchNumber != expectedMatchesSoFar + i + 1)
                    throw new IOException("Cup match numbers are not in bracket order");
                if (stage == 0) {
                    List<Integer> order = CupTournamentManager.firstRoundSeedOrder(cupBracketSize);
                    String expectedA = cupSeedSlots.get(order.get(i * 2) - 1);
                    String expectedB = cupSeedSlots.get(order.get(i * 2 + 1) - 1);
                    if (!match.engineAId.equals(expectedA == null ? "" : expectedA) ||
                            !match.engineBId.equals(expectedB == null ? "" : expectedB))
                        throw new IOException("Cup first-round pairings do not match the saved draw");
                } else {
                    ArrayList<CupMatch> previous = stagesOfMatches.get(stage - 1);
                    if (previous.size() != stageMatches.size() * 2)
                        throw new IOException("Cup previous stage has an invalid match count");
                    CupMatch first = previous.get(i * 2), second = previous.get(i * 2 + 1);
                    if (!first.decided || !second.decided ||
                            !match.engineAId.equals(first.winnerId) || !match.engineBId.equals(second.winnerId))
                        throw new IOException("Cup advancement does not match the previous stage winners");
                }
                if (stage < cupCurrentStage && !match.decided)
                    throw new IOException("A previous Cup stage contains an unfinished match");
            }
            expectedMatchesSoFar += expectedCount;
        }
        if (cupNextMatchNumber != cupMatches.size() + 1)
            throw new IOException("Cup next-match counter is inconsistent");
        boolean currentStageDecided = isCupStageDecided(cupCurrentStage);
        if (cupCheckpointPending && (cupCurrentStage >= stages - 1 || !currentStageDecided || cupFinished))
            throw new IOException("Cup checkpoint is marked pending at an invalid stage");
        if (cupFinished && (cupCurrentStage != stages - 1 || !currentStageDecided || cupCheckpointPending))
            throw new IOException("Cup completion state is inconsistent");
        if (!cupFinished && !cupCheckpointPending && currentStageDecided)
            throw new IOException("Cup stage is complete but its transition state was not saved");
    }

    private static String safeTournamentFileStem(String title) {
        String value = title == null ? "" : title.trim();
        value = value.replaceAll("[^A-Za-z0-9._-]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^[._-]+|[._-]+$", "");
        if (value.isEmpty()) value = "Tournament";
        if (value.length() > 64) value = value.substring(0, 64).replaceAll("[._-]+$", "");
        return value.isEmpty() ? "Tournament" : value;
    }

    private static synchronized String newUniqueFileName(Context context, String title) {
        String stem = safeTournamentFileStem(title);
        for (int sequence = 1; sequence < Integer.MAX_VALUE; sequence++) {
            String candidate = stem + "_" + String.format(Locale.US, "%04d", sequence) + ".json.gz";
            if (!RESERVED_TOURNAMENT_FILE_NAMES.contains(candidate) && !tournamentFileExists(context, candidate)) {
                RESERVED_TOURNAMENT_FILE_NAMES.add(candidate);
                return candidate;
            }
        }
        throw new IllegalStateException("No free filename is available for this tournament name");
    }

    private static boolean tournamentFileExists(Context context, String candidate) {
        File[] dirs = {context.getFilesDir(), externalTournamentDirectory(context), appExternalTournamentDirectory(context)};
        for (File dir : dirs) {
            if (dir == null) continue;
            File[] entries = dir.listFiles();
            if (entries == null) continue;
            for (File entry : entries) {
                String existing = entry.getName();
                if (existing.equalsIgnoreCase(candidate) ||
                        existing.regionMatches(true, 0, candidate + ".bak", 0, (candidate + ".bak").length()) ||
                        existing.regionMatches(true, 0, candidate + ".restore", 0, (candidate + ".restore").length()) ||
                        existing.equalsIgnoreCase(candidate + ".tmp") ||
                        existing.equalsIgnoreCase(candidate + ".repair.tmp")) return true;
            }
        }
        return false;
    }

    public static TournamentManager restore(Context context) throws IOException, JSONException {
        List<String> saved = savedTournamentFiles(context);
        if (saved.isEmpty()) throw new IOException("No saved tournament exists");
        return restore(context, saved.get(0));
    }

    public static TournamentManager restore(Context context, String fileName) throws IOException, JSONException {
        final long restoreStartedNs = System.nanoTime();
        TournamentDiagnostics.record("RESTORE", "begin", "file=" + fileName);
        TournamentDiagnostics.setPhase("tournament_restore", "file=" + fileName);
        if (!isTournamentFileName(fileName)) throw new IOException("Invalid saved tournament name");
        File internal = new File(context.getFilesDir(), fileName);
        File shared = externalTournamentFile(context, fileName);
        File app = appExternalTournamentFile(context, fileName);
        ArrayList<File> candidates = new ArrayList<>();
        candidates.add(internal);
        addSaveBackups(candidates, internal);
        addRepairBackups(candidates, internal);
        candidates.add(shared);
        addSaveBackups(candidates, shared);
        addRepairBackups(candidates, shared);
        if (app != null) {
            candidates.add(app);
            addSaveBackups(candidates, app);
            addRepairBackups(candidates, app);
        }

        JSONObject root = null;
        File validSource = null;
        Exception lastError = null;
        for (File candidate : candidates) {
            if (candidate == null || !candidate.isFile()) continue;
            TournamentDiagnostics.record("RESTORE", "candidate_read_begin", "path=" + candidate.getAbsolutePath());
            try {
                root = readTournamentRoot(candidate);
                validSource = candidate;
                TournamentDiagnostics.record("RESTORE", "candidate_read_valid", "path=" + candidate.getAbsolutePath());
                break;
            } catch (Exception invalidCopy) {
                lastError = invalidCopy;
                TournamentDiagnostics.recordError("RESTORE", "candidate_read_invalid path=" + candidate.getAbsolutePath(), invalidCopy);
            }
        }
        if (root == null || validSource == null) {
            IOException restoreError = new IOException("No valid private or backup copy of the saved tournament could be read" +
                    (lastError == null ? "" : ": " + lastError.getMessage()), lastError);
            TournamentDiagnostics.recordError("RESTORE", "all_snapshot_candidates_invalid file=" + fileName, restoreError);
            throw restoreError;
        }

        // Repair a missing/corrupt private copy only after a complete backup has
        // been parsed successfully. The original candidate is never truncated.
        if (!validSource.equals(internal)) {
            TournamentDiagnostics.record("RESTORE", "repair_private_snapshot_begin", "source=" + validSource.getAbsolutePath());
            copyFileAtomic(validSource, internal);
            TournamentDiagnostics.record("RESTORE", "repair_private_snapshot_complete", "target=" + internal.getAbsolutePath());
        }

        boolean legacyPlainJson = !isGzipFile(validSource);
        if (legacyPlainJson && fileName.endsWith(".json")) {
            // Migrate only after the old JSON parsed successfully. Publish the new
            // compressed private copy first; retain the old copies as .legacy.bak.
            String compressedName = newUniqueFileName(context.getApplicationContext(), root.optString("title", "Tournament"));
            TournamentManager migrated = new TournamentManager(context, root, compressedName);
            migrated.save();
            TournamentDiagnostics.record("RESTORE", "legacy_json_migrated", "from=" + fileName + " to=" + compressedName);
            // The new private snapshot is committed. Rename every legacy primary
            // and sidecar to a .legacy archive (never delete it), including old
            // external mirrors. This prevents a stale legacy backup from being
            // rediscovered and migrated into a second event on the next list scan.
            archiveLegacyCopies(context, fileName);
            return migrated;
        }
        TournamentManager restored = new TournamentManager(context, root, fileName);
        TournamentDiagnostics.record("RESTORE", "complete", "file=" + fileName + " elapsedMs=" +
                ((System.nanoTime() - restoreStartedNs) / 1_000_000L) + " source=" + validSource.getAbsolutePath());
        TournamentDiagnostics.setPhase("tournament_restored", "file=" + fileName);
        return restored;
    }

    private static void addSaveBackups(List<File> candidates, File target) {
        File parent = target.getParentFile();
        if (parent == null || !parent.isDirectory()) return;
        String prefix = target.getName() + ".bak";
        File[] files = parent.listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName();
            if (file.isFile() && name.startsWith(prefix)) candidates.add(file);
        }
    }

    private static void addRepairBackups(List<File> candidates, File target) {
        File parent = target.getParentFile();
        if (parent == null || !parent.isDirectory()) return;
        String prefix = target.getName() + ".restore";
        File[] files = parent.listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName();
            if (file.isFile() && name.startsWith(prefix) && name.endsWith(".bak")) candidates.add(file);
        }
    }

    private static JSONObject readTournamentRoot(File file) throws IOException, JSONException {
        try (FileInputStream in = new FileInputStream(file)) {
            return new JSONObject(readTournamentJson(in, 128L * 1024L * 1024L));
        }
    }

    /** Read a plain JSON or gzip-compressed tournament file by inspecting its stream header. */
    public static String readTournamentJson(InputStream source) throws IOException {
        return readTournamentJson(source, 32L * 1024L * 1024L);
    }

    private static String readTournamentJson(InputStream source, long maximumDecodedBytes) throws IOException {
        BufferedInputStream buffered = new BufferedInputStream(source);
        buffered.mark(2);
        int first = buffered.read();
        int second = buffered.read();
        buffered.reset();
        InputStream decoded = first == 0x1f && second == 0x8b ? new GZIPInputStream(buffered) : buffered;
        try (InputStream in = decoded; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32768];
            long total = 0;
            int count;
            while ((count = in.read(buffer)) != -1) {
                total += count;
                if (total > maximumDecodedBytes)
                    throw new IOException("Decompressed tournament JSON exceeds the allowed size");
                out.write(buffer, 0, count);
            }
            String json = new String(out.toByteArray(), Charset.forName("UTF-8"));
            return !json.isEmpty() && json.charAt(0) == '\ufeff' ? json.substring(1) : json;
        }
    }

    private static boolean isGzipFile(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            return in.read() == 0x1f && in.read() == 0x8b;
        }
    }

    private static void archiveLegacyCopies(Context context, String fileName) {
        File[] dirs = {context.getFilesDir(), externalTournamentDirectory(context), appExternalTournamentDirectory(context)};
        for (File dir : dirs) {
            if (dir == null || !dir.isDirectory()) continue;
            File[] entries = dir.listFiles();
            if (entries == null) continue;
            for (File copy : entries) {
                String name = copy.getName();
                boolean legacyCopy = name.equals(fileName) || name.startsWith(fileName + ".bak") ||
                        name.startsWith(fileName + ".restore") || name.equals(fileName + ".tmp") ||
                        name.equals(fileName + ".repair.tmp");
                if (!legacyCopy || !copy.isFile()) continue;
                File archive = new File(copy.getAbsolutePath() + ".legacy.bak");
                int suffix = 1;
                while (archive.exists()) archive = new File(copy.getAbsolutePath() + ".legacy_" + suffix++ + ".bak");
                // Rename on the same volume. Never delete a legacy or recovery copy.
                // A permission/rename failure must not invalidate the already-committed
                // compressed snapshot; leave the original visible for recovery.
                try { copy.renameTo(archive); } catch (SecurityException ignored) { }
            }
        }
    }

    /** Internal app storage remains authoritative; public compressed copies are portability mirrors. */
    public static List<String> savedTournamentFiles(Context context) {
        java.util.LinkedHashSet<String> found = new java.util.LinkedHashSet<>();
        addTournamentFiles(found, context.getFilesDir());
        addTournamentFiles(found, externalTournamentDirectory(context));
        addTournamentFiles(found, appExternalTournamentDirectory(context));
        List<String> files = new ArrayList<>(found);
        Collections.sort(files, (a, b) -> Long.compare(savedModified(context, b), savedModified(context, a)));
        return files;
    }

    private static void addTournamentFiles(java.util.Set<String> names, File dir) {
        if (dir == null || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (!file.isFile()) continue;
            String name = file.getName();
            if (isTournamentFileName(name)) names.add(name);
            else {
                String recoveredName = tournamentNameFromBackup(name);
                if (recoveredName != null) names.add(recoveredName);
            }
        }
    }

    /** Return the primary tournament filename represented by a recoverable sidecar. */
    private static String tournamentNameFromBackup(String sidecarName) {
        String base = sidecarName.replaceFirst("\\.restore(?:_[0-9]+)?\\.bak$", "");
        if (!base.equals(sidecarName) && isTournamentFileName(base)) return base;
        base = sidecarName.replaceFirst("\\.bak(?:\\.[0-9]+)?$", "");
        if (!base.equals(sidecarName) && isTournamentFileName(base)) return base;
        return null;
    }

    private static boolean isTournamentFileName(String name) {
        if (name == null || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) return false;
        return name.equals("cet_tournament.json") || name.equals("Tournament.json") ||
                (name.startsWith("cet_tournament_") && name.endsWith(".json")) ||
                name.matches("[A-Za-z0-9._-]{1,64}_[0-9]{4,}\\.json\\.gz");
    }

    private static File externalTournamentDirectory(Context context) {
        return new File(new File(Environment.getExternalStorageDirectory(), "DroidFish"), "tournament");
    }

    private static File appExternalTournamentDirectory(Context context) {
        File root = context.getExternalFilesDir(null);
        return root == null ? null : new File(root, "tournament");
    }

    private static File externalTournamentFile(Context context, String name) {
        return new File(externalTournamentDirectory(context), name);
    }

    private static File appExternalTournamentFile(Context context, String name) {
        File dir = appExternalTournamentDirectory(context);
        return dir == null ? null : new File(dir, name);
    }

    private static long savedModified(Context context, String name) {
        long modified = 0L;
        File[] dirs = {context.getFilesDir(), externalTournamentDirectory(context), appExternalTournamentDirectory(context)};
        for (File dir : dirs) {
            if (dir == null || !dir.isDirectory()) continue;
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File file : files) {
                if (!file.isFile()) continue;
                if (file.getName().equals(name) || name.equals(tournamentNameFromBackup(file.getName())))
                    modified = Math.max(modified, file.lastModified());
            }
        }
        return modified;
    }

    private static void copyFileAtomic(File source, File target) throws IOException {
        File dir = target.getParentFile();
        if (dir == null || (!dir.exists() && !dir.mkdirs() && !dir.isDirectory()))
            throw new IOException("Cannot create private tournament folder");
        File temp = new File(dir, target.getName() + ".repair.tmp");
        try (FileInputStream in = new FileInputStream(source); FileOutputStream out = new FileOutputStream(temp, false)) {
            byte[] buffer = new byte[32768];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            out.flush();
            out.getFD().sync();
        }
        // Do not use the normal .bak rotation here: source may itself be target.bak.
        // Keeping a separate recovery suffix prevents deleting the source backup.
        File recovery = new File(target.getAbsolutePath() + ".restore.bak");
        int suffix = 1;
        while (recovery.exists()) recovery = new File(target.getAbsolutePath() + ".restore_" + suffix++ + ".bak");
        boolean movedTarget = false;
        if (target.exists()) {
            if (!target.renameTo(recovery)) {
                temp.delete();
                throw new IOException("Cannot preserve previous private tournament copy");
            }
            movedTarget = true;
        }
        if (!temp.renameTo(target)) {
            if (movedTarget && !recovery.renameTo(target))
                throw new IOException("Cannot publish repaired tournament copy or restore its previous copy");
            temp.delete();
            throw new IOException("Cannot publish repaired private tournament copy");
        }
    }

    private static void publishTempFile(File temp, File target) throws IOException {
        // First attempt the filesystem's atomic same-directory replacement.
        if (temp.renameTo(target)) return;

        // On filesystems that refuse replacement, preserve the previous snapshot
        // under a collision-safe backup name. Never delete an existing backup.
        File backup = new File(target.getAbsolutePath() + ".bak");
        int suffix = 1;
        while (backup.exists()) backup = new File(target.getAbsolutePath() + ".bak." + suffix++);
        boolean movedTarget = false;
        if (target.exists()) {
            if (!target.renameTo(backup)) {
                temp.delete();
                throw new IOException("Cannot preserve previous tournament snapshot");
            }
            movedTarget = true;
        }
        if (!temp.renameTo(target)) {
            if (movedTarget && !backup.renameTo(target))
                throw new IOException("Cannot publish new tournament snapshot or restore previous snapshot");
            temp.delete();
            throw new IOException("Cannot publish tournament file");
        }
        // Keep the old complete snapshot as a recovery point. It is ignored by
        // the normal saved-file list and read only if the primary copy is invalid.
    }

    public static void deleteSavedTournament(Context context, String fileName) throws IOException {
        if (!isTournamentFileName(fileName)) throw new IOException("Invalid saved tournament name");
        boolean deleted = context.deleteFile(fileName);
        File shared = externalTournamentFile(context, fileName);
        File app = appExternalTournamentFile(context, fileName);
        try {
            if (shared.exists()) {
                if (!shared.delete()) throw new IOException("Could not delete shared JSON copy: " + shared.getAbsolutePath());
                deleted = true;
            }
            if (app != null && app.exists()) {
                if (!app.delete()) throw new IOException("Could not delete external backup: " + app.getAbsolutePath());
                deleted = true;
            }
            deleted |= deleteTournamentSidecars(context.getFilesDir(), fileName);
            deleted |= deleteTournamentSidecars(externalTournamentDirectory(context), fileName);
            deleted |= deleteTournamentSidecars(appExternalTournamentDirectory(context), fileName);
        } catch (SecurityException e) {
            throw new IOException("Could not delete external tournament JSON copy", e);
        }
        if (!deleted) throw new IOException("Could not delete saved tournament");
    }

    private static boolean deleteTournamentSidecars(File directory, String fileName) throws IOException {
        if (directory == null || !directory.isDirectory()) return false;
        File[] files = directory.listFiles();
        if (files == null) return false;
        boolean deleted = false;
        for (File file : files) {
            String name = file.getName();
            boolean sidecar = name.startsWith(fileName + ".bak") ||
                    name.startsWith(fileName + ".restore") ||
                    name.equals(fileName + ".tmp") || name.equals(fileName + ".repair.tmp");
            if (sidecar && file.isFile()) {
                if (!file.delete()) throw new IOException("Could not delete tournament recovery file: " + file.getAbsolutePath());
                deleted = true;
            }
        }
        return deleted;
    }

    public synchronized String savedFileName() { return fileName; }

    private void generateCupInitialStage() {
        cupMatches.clear(); pairings.clear(); cupNextMatchNumber = 1; cupNextGameNumber = 1;
        List<Integer> order = CupTournamentManager.firstRoundSeedOrder(cupBracketSize);
        for (int i = 0; i < order.size(); i += 2) {
            String a = cupSeedSlots.get(order.get(i) - 1);
            String b = cupSeedSlots.get(order.get(i + 1) - 1);
            CupMatch match = new CupMatch(0, cupNextMatchNumber++,
                    CupTournamentManager.regularPairsPerMatch(cupBracketSize, 0), a, b);
            cupMatches.add(match);
            if (a == null || a.isEmpty() || b == null || b.isEmpty()) {
                match.bye = true; match.decided = true; match.winnerId = a == null || a.isEmpty() ? b : a;
            } else {
                appendCupPairGames(match);
            }
        }
        updateCupStageCompletion();
    }

    private void appendCupPairGames(CupMatch match) {
        if (match == null || match.decided || match.engineAId.isEmpty() || match.engineBId.isEmpty())
            throw new IllegalStateException("Cannot add games to an already decided Cup match");
        int pairNo = ++match.pairsScheduled;
        int stageRound = match.stageIndex + 1;
        int first = pairings.size();
        Pairing aWhite = new Pairing(first, stageRound, cupNextGameNumber++, match.engineAId, match.engineBId, Outcome.UNFINISHED, "");
        aWhite.cupMatchNumber = match.matchNumber; aWhite.cupPairNumber = pairNo; aWhite.cupGameInPair = 1;
        Pairing bWhite = new Pairing(first + 1, stageRound, cupNextGameNumber++, match.engineBId, match.engineAId, Outcome.UNFINISHED, "");
        bWhite.cupMatchNumber = match.matchNumber; bWhite.cupPairNumber = pairNo; bWhite.cupGameInPair = 2;
        pairings.add(aWhite); pairings.add(bWhite);
        match.gameIndices.add(first); match.gameIndices.add(first + 1);
    }

    private CupMatch findCupMatch(int matchNumber) {
        for (CupMatch match : cupMatches) if (match.matchNumber == matchNumber) return match;
        return null;
    }

    private static double cupGamePoints(String engineId, Pairing game) {
        boolean white = engineId.equals(game.whiteEngineId);
        boolean black = engineId.equals(game.blackEngineId);
        if (!white && !black) return 0.0;
        if (game.outcome == Outcome.DRAW) return 0.5;
        if ((white && game.outcome == Outcome.WHITE_WIN) || (black && game.outcome == Outcome.BLACK_WIN)) return 1.0;
        return 0.0;
    }

    private void evaluateCupMatch(CupMatch match) {
        if (match == null || match.decided || match.bye) return;
        int completePairs = 0;
        for (int pairNo = 1; pairNo <= match.pairsScheduled; pairNo++) {
            boolean firstDone = false, secondDone = false;
            for (Integer index : match.gameIndices) {
                Pairing game = pairings.get(index);
                if (game.cupPairNumber == pairNo) {
                    if (game.cupGameInPair == 1) firstDone = game.isComplete();
                    if (game.cupGameInPair == 2) secondDone = game.isComplete();
                }
            }
            if (firstDone && secondDone) completePairs++;
            else break;
        }
        match.pairsCompleted = completePairs;
        double a = 0.0, b = 0.0;
        for (Integer index : match.gameIndices) {
            Pairing game = pairings.get(index);
            if (!game.isComplete()) continue;
            a += cupGamePoints(match.engineAId, game);
            b += cupGamePoints(match.engineBId, game);
        }
        match.engineAPoints = a; match.engineBPoints = b;
        if (completePairs != match.pairsScheduled) return;
        double margin = Math.abs(a - b);
        int regularRemaining = Math.max(0, match.regularPairLimit - completePairs);
        if (margin > (2.0 * regularRemaining) || (regularRemaining == 0 && margin > 0.000001)) {
            match.decided = true;
            match.winnerId = a > b ? match.engineAId : match.engineBId;
        } else {
            // Continue regular pairs, or use additional two-game reversed-color pairs as tiebreaks.
            appendCupPairGames(match);
        }
        updateCupStageCompletion();
    }

    private boolean isCupStageDecided(int stageIndex) {
        boolean found = false;
        for (CupMatch match : cupMatches) if (match.stageIndex == stageIndex) {
            found = true;
            if (!match.decided) return false;
        }
        return found;
    }

    private void updateCupStageCompletion() {
        if (format != Format.CUP || cupFinished || cupCheckpointPending || !isCupStageDecided(cupCurrentStage)) return;
        int stages = CupTournamentManager.stageCount(cupBracketSize);
        if (cupCurrentStage >= stages - 1) {
            cupFinished = true;
            cupCheckpointPending = false;
        } else {
            cupCheckpointPending = true;
        }
    }

    /** Continue from the completed-stage checkpoint, optionally after replacing survivor executables. */
    public synchronized void advanceCupAfterCheckpoint() throws IOException, JSONException {
        if (format != Format.CUP || !cupCheckpointPending) throw new IllegalStateException("Cup is not waiting at a stage checkpoint");
        List<CupMatch> previous = new ArrayList<>();
        for (CupMatch match : cupMatches) if (match.stageIndex == cupCurrentStage) previous.add(match);
        if (previous.size() < 2 || (previous.size() & 1) != 0) throw new IllegalStateException("Cup bracket stage cannot advance");
        cupCheckpointPending = false;
        cupCurrentStage++;
        cupNextGameNumber = 1;
        for (int i = 0; i < previous.size(); i += 2) {
            String a = previous.get(i).winnerId;
            String b = previous.get(i + 1).winnerId;
            CupMatch next = new CupMatch(cupCurrentStage, cupNextMatchNumber++,
                    CupTournamentManager.regularPairsPerMatch(cupBracketSize, cupCurrentStage), a, b);
            cupMatches.add(next);
            if (a == null || a.isEmpty() || b == null || b.isEmpty()) {
                next.bye = true; next.decided = true; next.winnerId = a == null || a.isEmpty() ? b : a;
            } else {
                appendCupPairGames(next);
            }
        }
        updateCupStageCompletion();
        save();
    }

    public synchronized boolean cupCheckpointPending() { return format == Format.CUP && cupCheckpointPending; }
    public synchronized boolean cupFinished() { return format == Format.CUP && cupFinished; }
    public synchronized int cupCurrentStage() { return cupCurrentStage; }
    public synchronized int cupBracketSize() { return cupBracketSize; }
    public synchronized CupSeedingMode cupSeedingMode() { return cupSeedingMode; }
    public synchronized List<String> cupStrengthOrder() { return Collections.unmodifiableList(new ArrayList<>(cupStrengthOrder)); }
    public synchronized List<String> cupSeedSlots() { return Collections.unmodifiableList(new ArrayList<>(cupSeedSlots)); }
    public synchronized List<CupMatch> cupMatches() { return Collections.unmodifiableList(new ArrayList<>(cupMatches)); }
    public synchronized String cupCurrentStageLabel() {
        return format == Format.CUP && cupBracketSize >= 2 ? CupTournamentManager.stageLabel(cupBracketSize, cupCurrentStage) : "";
    }
    public synchronized String cupNextStageLabel() {
        return format == Format.CUP && cupCurrentStage + 1 < CupTournamentManager.stageCount(cupBracketSize)
                ? CupTournamentManager.stageLabel(cupBracketSize, cupCurrentStage + 1) : "";
    }
    public synchronized List<String> cupAdvancingEngineIds() {
        ArrayList<String> ids = new ArrayList<>();
        for (CupMatch match : cupMatches) if (match.stageIndex == cupCurrentStage && match.decided && !match.winnerId.isEmpty() && !ids.contains(match.winnerId)) ids.add(match.winnerId);
        return Collections.unmodifiableList(ids);
    }
    public synchronized String pgnEventName(Pairing pairing) {
        if (format != Format.CUP || pairing == null) return title;
        String base = title.toLowerCase(java.util.Locale.US).endsWith(" cup") ? title : title + " Cup";
        return base + " " + CupTournamentManager.stageLabel(cupBracketSize, Math.max(0, Math.min(cupCurrentStageFor(pairing), CupTournamentManager.stageCount(cupBracketSize)-1))).replace("Round ", "Round ");
    }
    private int cupCurrentStageFor(Pairing pairing) { return Math.max(0, pairing.round - 1); }
    public synchronized String pgnFileBaseName() {
        if (format != Format.CUP) return title;
        if (!cupPgnFileBaseName.isEmpty()) return cupPgnFileBaseName;
        return title.toLowerCase(java.util.Locale.US).endsWith(" cup") ? title : title + " Cup";
    }

    public synchronized String cupPgnFileBaseName() { return cupPgnFileBaseName; }

    /** Assign once so separate Cup tournaments with the same title never merge into one PGN. */
    public synchronized void setCupPgnFileBaseNameIfEmpty(String baseName) throws IOException, JSONException {
        if (format != Format.CUP) throw new IllegalStateException("Only Cup tournaments use a cumulative Cup PGN filename");
        if (!cupPgnFileBaseName.isEmpty()) return;
        if (baseName == null || baseName.trim().isEmpty() || baseName.contains("/") || baseName.contains("\\") || baseName.equals(".") || baseName.equals(".."))
            throw new IllegalArgumentException("Invalid Cup PGN filename");
        cupPgnFileBaseName = baseName.trim();
        save();
    }

    /** Replace a survivor's installed executable without changing its stable Cup identity or old PGNs. */
    public synchronized void updateCupEngineExecutable(String engineId, String executableId, String displayName)
            throws IOException, JSONException {
        if (format != Format.CUP || !cupCheckpointPending) throw new IllegalStateException("Cup engines can only be updated between stages");
        if (engineId == null || executableId == null || executableId.trim().isEmpty() || displayName == null || displayName.trim().isEmpty())
            throw new IllegalArgumentException("Choose a valid replacement engine");
        if (!cupAdvancingEngineIds().contains(engineId)) throw new IllegalArgumentException("Only engines advancing to the next stage can be updated");
        for (Engine engine : engines) if (!engine.id.equals(engineId) && engine.executableId.equals(executableId))
            throw new IllegalArgumentException("That executable is already assigned to another Cup participant");
        for (int i = 0; i < engines.size(); i++) if (engines.get(i).id.equals(engineId)) {
            engines.set(i, new Engine(engines.get(i).id, displayName.trim(), executableId.trim()));
            save(); return;
        }
        throw new IllegalArgumentException("Cup participant was not found");
    }

    private void buildSchedule() {
        pairings.clear(); int index = 0;
        if (format == Format.SWISS) {
            swissByes.clear();
            swissMatches.clear();
            swissCurrentRound = 0;
            generateSwissRound();
            return;
        }
        if (format == Format.MATCH) {
            for (int g = 0; g < gamesPerPair; g++) {
                boolean flip = (g & 1) != 0;
                pairings.add(new Pairing(index++, g + 1, g + 1,
                        (flip ? engines.get(1) : engines.get(0)).id,
                        (flip ? engines.get(0) : engines.get(1)).id, Outcome.UNFINISHED, ""));
            }
            return;
        }
        if (format == Format.GAUNTLET) {
            Engine gauntlet = engines.get(0);
            for (int e = 1; e < engines.size(); e++) {
                for (int g = 0; g < gamesPerPair; g++) pairings.add(new Pairing(index++, e, g + 1,
                        (g % 2 == 0) ? gauntlet.id : engines.get(e).id,
                        (g % 2 == 0) ? engines.get(e).id : gauntlet.id, Outcome.UNFINISHED, ""));
            }
            return;
        }
        int n = engines.size();
        List<Engine> ring = new ArrayList<>(engines);
        if ((n & 1) != 0) ring.add(null);
        int count = ring.size();
        for (int round = 0; round < count - 1; round++) {
            for (int i = 0; i < count / 2; i++) {
                Engine a = ring.get(i), b = ring.get(count - 1 - i);
                if (a != null && b != null) {
                    for (int g = 0; g < gamesPerPair; g++) {
                        boolean flip = ((round + g) & 1) != 0;
                        pairings.add(new Pairing(index++, round + 1, g + 1,
                                (flip ? b : a).id, (flip ? a : b).id, Outcome.UNFINISHED, ""));
                    }
                }
            }
            Engine last = ring.remove(count - 1); ring.add(1, last);
        }
    }

    /**
     * Practical Swiss round ceiling for this mobile pairing implementation.
     * It follows the field-size bands suggested for engine events rather than
     * promising a full round-robin's theoretical maximum, which can leave a
     * score-driven Swiss sequence with no legal matching near the end.
     */
    public static int maximumSwissRounds(int engineCount) {
        if (engineCount < 2) return 0;
        int theoretical = (engineCount & 1) == 0 ? engineCount - 1 : engineCount;
        int practical;
        if (engineCount <= 8) practical = 3;
        else if (engineCount <= 16) practical = 5;
        else if (engineCount <= 32) practical = 7;
        else if (engineCount <= 50) practical = 9;
        else if (engineCount <= 85) practical = 11;
        else practical = 15;
        return Math.min(theoretical, practical);
    }

    /** Blank setup input uses the practical recommended maximum for the selected field. */
    public static int recommendedSwissRounds(int engineCount) {
        return maximumSwissRounds(engineCount);
    }

    public synchronized int swissRoundsTotal() { return swissRoundsTotal; }
    public synchronized int swissCurrentRound() { return swissCurrentRound; }
    public synchronized SwissSeedingMode swissSeedingMode() { return swissSeedingMode; }
    public synchronized List<String> swissStrengthOrder() {
        return Collections.unmodifiableList(new ArrayList<>(swissStrengthOrder));
    }
    public synchronized List<SwissBye> swissByes() {
        return Collections.unmodifiableList(new ArrayList<>(swissByes));
    }

    private int swissByeCount(String engineId) {
        int count = 0;
        for (SwissBye bye : swissByes) if (engineId.equals(bye.engineId)) count++;
        return count;
    }

    private double swissPoints(String engineId) {
        double points = 2.0 * swissByeCount(engineId);
        for (Pairing p : pairings) {
            if (!p.isComplete()) continue;
            boolean white = engineId.equals(p.whiteEngineId);
            boolean black = engineId.equals(p.blackEngineId);
            if (!white && !black) continue;
            if (p.outcome == Outcome.DRAW) points += 0.5;
            else if ((white && p.outcome == Outcome.WHITE_WIN) || (black && p.outcome == Outcome.BLACK_WIN)) points += 1.0;
        }
        return points;
    }

    private boolean swissHasMet(String firstId, String secondId) {
        for (SwissMatch match : swissMatches) {
            if ((firstId.equals(match.firstEngineId) && secondId.equals(match.secondEngineId)) ||
                    (firstId.equals(match.secondEngineId) && secondId.equals(match.firstEngineId))) return true;
        }
        return false;
    }

    /**
     * Generate one deterministic Monrad-style Swiss round. Engines are ranked by
     * points descending and original selection seed ascending. The first unpaired
     * engine is matched to the highest-ranked opponent that still permits a complete
     * no-rematch pairing of the remaining pool (look-ahead/backtracking).
     */
    private void generateSwissRound() {
        if (format != Format.SWISS || swissCurrentRound >= swissRoundsTotal) return;
        final int round = swissCurrentRound + 1;
        ArrayList<Engine> order = new ArrayList<>(engines);
        Collections.sort(order, (a, b) -> {
            int score = Double.compare(swissPoints(b.id), swissPoints(a.id));
            if (score != 0) return score;
            return Integer.compare(seedOf(a.id), seedOf(b.id));
        });

        Engine byeReceiver = null;
        if ((order.size() & 1) != 0) {
            // TCEC's ordering gives the bye to the lowest-ranked engine among
            // those with the fewest previous byes.
            for (Engine engine : order) {
                if (byeReceiver == null || swissByeCount(engine.id) < swissByeCount(byeReceiver.id) ||
                        (swissByeCount(engine.id) == swissByeCount(byeReceiver.id) &&
                                (swissPoints(engine.id) < swissPoints(byeReceiver.id) ||
                                        (swissPoints(engine.id) == swissPoints(byeReceiver.id) &&
                                                seedOf(engine.id) > seedOf(byeReceiver.id))))) {
                    byeReceiver = engine;
                }
            }
            order.remove(byeReceiver);
        }

        ArrayList<SwissEnginePair> selected = new ArrayList<>();
        if (!findSwissPairings(order, selected, new HashSet<String>())) {
            throw new IllegalStateException("No complete Swiss pairing can be generated without a rematch. " +
                    "Reduce the round count or change the selected engines.");
        }

        int nextIndex = pairings.size();
        int gameNumber = 1;
        for (SwissEnginePair pair : selected) {
            // The first-of-pair receives Black in game 1 and White in game 2,
            // matching the color-reversal convention used by TCEC Swiss.
            int firstGame = nextIndex;
            pairings.add(new Pairing(nextIndex++, round, gameNumber++, pair.second.id, pair.first.id,
                    Outcome.UNFINISHED, ""));
            int secondGame = nextIndex;
            pairings.add(new Pairing(nextIndex++, round, gameNumber++, pair.first.id, pair.second.id,
                    Outcome.UNFINISHED, ""));
            swissMatches.add(new SwissMatch(round, pair.first.id, pair.second.id, firstGame, secondGame));
        }
        if (byeReceiver != null) swissByes.add(new SwissBye(round, byeReceiver.id));
        swissCurrentRound = round;
    }

    private static final class SwissEnginePair {
        final Engine first, second;
        SwissEnginePair(Engine first, Engine second) { this.first = first; this.second = second; }
    }

    private int seedOf(String engineId) {
        for (int i = 0; i < engines.size(); i++) if (engineId.equals(engines.get(i).id)) return i + 1;
        return Integer.MAX_VALUE;
    }

    private boolean findSwissPairings(ArrayList<Engine> remaining, ArrayList<SwissEnginePair> result,
                                      Set<String> failedStates) {
        if (remaining.isEmpty()) return true;
        StringBuilder stateBuilder = new StringBuilder();
        for (Engine engine : remaining) stateBuilder.append(engine.id.length()).append(':').append(engine.id).append(';');
        String state = stateBuilder.toString();
        if (failedStates.contains(state)) return false;

        Engine first = remaining.remove(0);
        for (int i = 0; i < remaining.size(); i++) {
            Engine second = remaining.get(i);
            if (swissHasMet(first.id, second.id)) continue;
            remaining.remove(i);
            if (findSwissPairings(remaining, result, failedStates)) {
                result.add(0, new SwissEnginePair(first, second));
                remaining.add(i, second);
                remaining.add(0, first);
                return true;
            }
            remaining.add(i, second);
        }
        remaining.add(0, first);
        failedStates.add(state);
        return false;
    }

    private boolean swissRoundComplete(int round) {
        boolean foundMatch = false;
        for (SwissMatch match : swissMatches) {
            if (match.round != round) continue;
            foundMatch = true;
            if (match.firstGameIndex < 0 || match.firstGameIndex >= pairings.size() ||
                    match.secondGameIndex < 0 || match.secondGameIndex >= pairings.size()) return false;
            if (!pairings.get(match.firstGameIndex).isComplete() || !pairings.get(match.secondGameIndex).isComplete()) return false;
        }
        return foundMatch;
    }

    public synchronized List<Engine> engines() { return Collections.unmodifiableList(new ArrayList<>(engines)); }

    /** Number of tournament pairings with a recorded result. */
    public synchronized int completedGamesCount() {
        int count = 0;
        for (Pairing pairing : pairings) if (pairing.isComplete()) count++;
        return count;
    }

    /** Number of completed tournament games involving an engine. */
    public synchronized int completedGamesForEngine(String engineId) {
        int count = 0;
        for (Pairing pairing : pairings) {
            if (pairing.isComplete() && (engineId.equals(pairing.whiteEngineId) || engineId.equals(pairing.blackEngineId))) count++;
        }
        return count;
    }

    /** True when the interrupted/active pairing currently references this engine. */
    public synchronized boolean isEngineInActivePairing(String engineId) {
        if (activePairingIndex < 0 || activePairingIndex >= pairings.size()) return false;
        Pairing pairing = pairings.get(activePairingIndex);
        return engineId.equals(pairing.whiteEngineId) || engineId.equals(pairing.blackEngineId);
    }

    /**
     * Rename an engine, and optionally point it at a replacement installed executable.
     * A display-name-only correction is safe after games have been played and updates
     * historical PGN player tags. Changing the executable identity is restricted to
     * engines with no completed games and no currently active/interrupted pairing.
     * All remaining scheduled references are changed atomically in the saved JSON.
     */
    public synchronized void updateTournamentEngine(int engineIndex, String newId, String newDisplayName)
            throws IOException, JSONException {
        if (format == Format.CUP)
            throw new IllegalStateException("Cup engines can only be updated at a stage checkpoint");
        if (engineIndex < 0 || engineIndex >= engines.size()) throw new IllegalArgumentException("Unknown tournament engine");
        if (newId == null || newId.trim().isEmpty()) throw new IllegalArgumentException("Choose a valid installed engine");
        if (newDisplayName == null || newDisplayName.trim().isEmpty()) throw new IllegalArgumentException("Engine display name cannot be empty");
        newId = newId.trim();
        newDisplayName = newDisplayName.trim();
        Engine old = engines.get(engineIndex);
        boolean executableChanged = !old.id.equals(newId);
        boolean nameChanged = !old.displayName.equals(newDisplayName);

        if (executableChanged) {
            for (Engine other : engines) {
                if (!other.id.equals(old.id) && other.id.equals(newId))
                    throw new IllegalArgumentException("That executable is already assigned to another tournament engine");
            }
            if (completedGamesForEngine(old.id) > 0)
                throw new IllegalStateException("This engine already has completed games. You can correct its display name, but cannot replace its executable identity without mixing historical results.");
            if (isEngineInActivePairing(old.id))
                throw new IllegalStateException("This engine is part of the active or interrupted game. Resume or finish that game before changing its executable.");
        }

        if (nameChanged) {
            for (Pairing pairing : pairings) {
                if (pairing.pgn == null || pairing.pgn.trim().isEmpty()) continue;
                if (old.id.equals(pairing.whiteEngineId)) pairing.pgn = replacePgnTag(pairing.pgn, "White", newDisplayName);
                if (old.id.equals(pairing.blackEngineId)) pairing.pgn = replacePgnTag(pairing.pgn, "Black", newDisplayName);
            }
        }
        if (executableChanged) {
            for (Pairing pairing : pairings) {
                if (old.id.equals(pairing.whiteEngineId)) pairing.whiteEngineId = newId;
                if (old.id.equals(pairing.blackEngineId)) pairing.blackEngineId = newId;
            }
            if (format == Format.SWISS) {
                for (int i = 0; i < swissStrengthOrder.size(); i++)
                    if (old.id.equals(swissStrengthOrder.get(i))) swissStrengthOrder.set(i, newId);
                for (SwissMatch match : swissMatches) {
                    if (old.id.equals(match.firstEngineId)) match.firstEngineId = newId;
                    if (old.id.equals(match.secondEngineId)) match.secondEngineId = newId;
                }
                for (int i = 0; i < swissByes.size(); i++) {
                    SwissBye bye = swissByes.get(i);
                    if (old.id.equals(bye.engineId)) swissByes.set(i, new SwissBye(bye.round, newId));
                }
            }
        }
        engines.set(engineIndex, new Engine(newId, newDisplayName, newId));
        save();
    }

    public synchronized String title() { return title; }
    public synchronized Format format() { return format; }
    public synchronized int gamesPerPair() { return gamesPerPair; }
    public synchronized long baseTimeMs() { return baseTimeMs; }
    public synchronized long incrementMs() { return incrementMs; }
    public synchronized TimeMode timeMode() { return timeMode; }
    public synchronized int limitValue() { return limitValue; }
    /**
     * Stable sequence index for generated DFRC/Chess324 starts. Reversed-color
     * games in the same mini-match deliberately share one start position.
     */
    public synchronized int positionPairSequenceIndex(Pairing target) {
        if (target == null) return 0;
        String targetGroup = positionSequenceGroupKey(target);
        int targetOffset = positionSequenceOffset(target);
        Set<String> seenGroups = new HashSet<>();
        int sequenceBase = 0;
        for (Pairing pairing : pairings) {
            String group = positionSequenceGroupKey(pairing);
            if (!seenGroups.add(group)) continue;
            if (group.equals(targetGroup)) return sequenceBase + targetOffset;
            sequenceBase += positionSequencePairsPerGroup(pairing);
        }
        // Defensive fallback for a stale/non-scheduled pairing reference.
        return Math.max(0, target.index / 2);
    }

    private String positionSequenceGroupKey(Pairing pairing) {
        String first = pairing.whiteEngineId.compareTo(pairing.blackEngineId) <= 0
                ? pairing.whiteEngineId : pairing.blackEngineId;
        String second = pairing.whiteEngineId.compareTo(pairing.blackEngineId) <= 0
                ? pairing.blackEngineId : pairing.whiteEngineId;
        if (format == Format.CUP) {
            if (pairing.cupMatchNumber > 0 && pairing.cupPairNumber > 0)
                return "C:" + pairing.cupMatchNumber + ":" + pairing.cupPairNumber;
            return "C:legacy:" + (pairing.index / 2);
        }
        String players = first + "|" + second;
        switch (format) {
            case MATCH:
                return "M:" + players;
            case GAUNTLET:
                return "G:" + pairing.round + ":" + players;
            case SWISS:
                return "S:" + pairing.round + ":" + players;
            case ROUND_ROBIN:
            default:
                return "R:" + pairing.round + ":" + players;
        }
    }

    private int positionSequenceOffset(Pairing pairing) {
        if (format == Format.MATCH || format == Format.GAUNTLET || format == Format.ROUND_ROBIN)
            return Math.max(0, (pairing.gameNumber - 1) / 2);
        return 0;
    }

    private int positionSequencePairsPerGroup(Pairing pairing) {
        if (format == Format.MATCH || format == Format.GAUNTLET || format == Format.ROUND_ROBIN)
            return Math.max(1, (gamesPerPair + 1) / 2);
        return 1;
    }

    public synchronized String openingPgn() { return openingPgn; }
    public synchronized String openingPgnFileName() { return openingPgnFileName; }
    public synchronized void setOpeningPgnFileName(String name) { openingPgnFileName = name == null ? "" : name.trim(); }
    public synchronized String openingBinPath() { return openingBinPath; }
    public synchronized StartPositionMode startPositionMode() { return startPositionMode; }
    public synchronized int chess960StartId() { return chess960StartId; }
    public synchronized long startingPositionSeed() { return startingPositionSeed; }
    public synchronized String openingFen() { return openingFen; }
    /** Backwards-compatible accessor for imported pre-DFRC tournament records. */
    public synchronized boolean chess960Enabled() { return startPositionMode == StartPositionMode.CHESS960; }
    public synchronized void setStartingPositionSettings(StartPositionMode mode, int chess960Start, long seed, String fen) {
        startPositionMode = mode == null ? StartPositionMode.STANDARD : mode;
        chess960StartId = Math.max(1, Math.min(960, chess960Start));
        startingPositionSeed = seed;
        openingFen = fen == null ? "" : fen.trim();
    }
    public synchronized void setChess960Settings(boolean enabled, int startId) {
        startPositionMode = enabled ? StartPositionMode.CHESS960 : StartPositionMode.STANDARD;
        chess960StartId = Math.max(1, Math.min(960, startId));
    }
    public synchronized void setOpeningBinPath(String path) { openingBinPath = path == null ? "" : path; }
    public synchronized int openingPlies() { return openingPlies; }
    public synchronized int openingStartIndex() { return openingStartIndex; }
    public synchronized boolean repeatOpening() { return repeatOpening; }
    /** Total active-play duration accumulated across pauses and app restarts. */
    public synchronized long tournamentElapsedMs() { return tournamentElapsedMs; }
    public synchronized void setTournamentElapsedMs(long elapsedMs) { tournamentElapsedMs = Math.max(tournamentElapsedMs, Math.max(0L, elapsedMs)); }
    public synchronized boolean hasAnomalyRuleSettings() { return anomalyRuleSettingsSaved; }
    /** Save the automatic-rule start move once per tournament; older JSON receives setup defaults on resume. */
    public synchronized void setAnomalyRuleSettingsIfMissing(boolean enabled, int startFullMove, int maximumFullMoves) throws IOException, JSONException {
        if (anomalyRuleSettingsSaved) return;
        anomalyRulesEnabled = enabled;
        anomalyRuleStartFullMove = Math.max(1, Math.min(10000, startFullMove));
        anomalyMaximumFullMoves = Math.max(1, Math.min(10000, maximumFullMoves));
        anomalyRuleSettingsSaved = true;
        save();
    }
    public synchronized int anomalyRuleStartFullMove() { return anomalyRuleStartFullMove; }
    public synchronized List<Pairing> pairings() { return Collections.unmodifiableList(new ArrayList<>(pairings)); }
    /** Priority queue is returned in the exact order selected by the operator. */
    public synchronized List<Integer> playQueue() { return Collections.unmodifiableList(new ArrayList<>(playQueue)); }
    public synchronized boolean isQueued(int index) { return playQueue.contains(index); }
    public synchronized int activePairingIndex() { return activePairingIndex; }
    public void setActivePairing(int index) throws IOException, JSONException {
        synchronized (this) {
            Pairing p = pairings.get(index);
            if (p.isComplete() && !p.replayPending) throw new IllegalStateException("Only a pending game can become active");
            activePairingIndex = index;
        }
        save();
    }
    public void setQueued(int index, boolean queued) throws IOException, JSONException {
        synchronized (this) {
            Pairing p = pairings.get(index);
            if (queued) {
                if (p.isComplete() && !p.replayPending) throw new IllegalStateException("Only unfinished games can be queued; use Replay for completed games");
                if (!playQueue.contains(index)) playQueue.add(index);
            } else {
                playQueue.remove(Integer.valueOf(index));
            }
        }
        save();
    }
    /** Queue a replacement play at the head while keeping the old result recoverable until replacement finishes. */
    public void requestReplay(int index) throws IOException, JSONException {
        synchronized (this) {
            if (format == Format.CUP)
                throw new IllegalStateException("Cup games cannot be replayed after they enter the bracket result history");
            Pairing p = pairings.get(index);
            if (!p.isComplete()) throw new IllegalStateException("Only completed games can be replayed");
            if (p.replayPending) throw new IllegalStateException("This game is already waiting to be replayed");
            p.replayPending = true;
            playQueue.remove(Integer.valueOf(index));
            playQueue.add(0, index);
        }
        save();
    }
    public synchronized Pairing current() {
        if (format == Format.CUP && (cupCheckpointPending || cupFinished)) return null;
        if (activePairingIndex >= 0 && activePairingIndex < pairings.size()) {
            Pairing active = pairings.get(activePairingIndex);
            if (active.replayPending || !active.isComplete()) return active;
        }
        for (Integer index : playQueue) {
            Pairing p = pairings.get(index);
            if (p.replayPending || !p.isComplete()) return p;
        }
        for (Pairing p : pairings) if (p.replayPending || !p.isComplete()) return p;
        return null;
    }
    public synchronized boolean isComplete() { return format == Format.CUP ? cupFinished : current() == null; }

    /** Initialize stable assignments for every PGN opening (or empty assignments when no book is selected). */
    public void prepareOpeningAssignments() throws IOException, JSONException {
        boolean changed = false;
        synchronized (this) {
        // Splitting this 3+ MB string separately for every scheduled game used to
        // rescan the entire opening book thousands of times (O(games * book size)).
        // Split once, then do O(1) indexed lookups for all pairings.
        final List<String> pgnGames = openingPgn.trim().isEmpty()
                ? Collections.emptyList() : PgnOpeningBook.splitGames(openingPgn);
        for (Pairing p : pairings) {
            if (p.openingAssigned) continue;
            if (!pgnGames.isEmpty()) {
                assignPgnOpening(p, pgnGames);
                changed = true;
            } else if (openingBinPath.trim().isEmpty()) {
                p.openingAssigned = true;
                p.openingName = "Starting position";
                changed = true;
            } else if (p.isComplete() && p.pgn != null && !p.pgn.trim().isEmpty()) {
                // Migrate old BIN-book games: book plies precede the first per-move Eval comment.
                String prefix = p.pgn;
                int evalComment = prefix.indexOf("{Eval:");
                if (evalComment >= 0) prefix = prefix.substring(0, evalComment);
                List<String> inferred = PgnOpeningBook.mainLineSan(prefix);
                if (openingPlies > 0 && inferred.size() > openingPlies)
                    inferred = new ArrayList<>(inferred.subList(0, openingPlies));
                p.openingSanLine = joinSan(inferred);
                p.openingName = "Polyglot BIN opening";
                p.openingAssigned = true;
                changed = true;
            } else if (repeatOpening) {
                int partnerIndex = (p.index / 2) * 2;
                int otherIndex = partnerIndex == p.index ? partnerIndex + 1 : partnerIndex;
                if (otherIndex >= 0 && otherIndex < pairings.size()) {
                    Pairing partner = pairings.get(otherIndex);
                    if (partner.openingAssigned) {
                        p.openingSanLine = partner.openingSanLine;
                        p.openingName = partner.openingName;
                        p.openingIndex = partner.openingIndex;
                        p.openingAssigned = true;
                        changed = true;
                    }
                }
            }
        }
        }
        if (changed) save();
    }

    private void assignPgnOpeningsInMemory() {
        if (openingPgn.trim().isEmpty()) return;
        List<String> pgnGames = PgnOpeningBook.splitGames(openingPgn);
        for (Pairing p : pairings) if (!p.openingAssigned) assignPgnOpening(p, pgnGames);
    }

    private void assignPgnOpening(Pairing p, List<String> pgnGames) {
        if (pgnGames == null || pgnGames.isEmpty()) return;
        int zeroBased = Math.max(0, openingStartIndex - 1) + (repeatOpening ? Math.max(0, p.index / 2) : p.index);
        int selectedIndex = zeroBased % pgnGames.size();
        if (selectedIndex < 0) selectedIndex += pgnGames.size();
        String selected = pgnGames.get(selectedIndex);
        List<String> moves = PgnOpeningBook.mainLineSan(selected);
        if (openingPlies > 0 && moves.size() > openingPlies)
            moves = new ArrayList<>(moves.subList(0, openingPlies));
        p.openingSanLine = joinSan(moves);
        p.openingName = PgnOpeningBook.tagValue(selected, "Opening");
        if (p.openingName.isEmpty()) p.openingName = PgnOpeningBook.tagValue(selected, "ECO");
        if (p.openingName.isEmpty()) p.openingName = "PGN opening " + (zeroBased + 1);
        p.openingIndex = zeroBased + 1;
        p.openingAssigned = true;
    }

    private static String joinSan(List<String> moves) {
        StringBuilder line = new StringBuilder();
        for (String move : moves) { if (line.length() > 0) line.append(' '); line.append(move); }
        return line.toString();
    }

    /** Store the actual BIN line chosen the first time a pairing runs; never redraw it on replay. */
    public void setPairingOpeningIfMissing(int index, List<String> sanMoves, String name) throws IOException, JSONException {
        boolean changed = false;
        synchronized (this) {
            Pairing p = pairings.get(index);
            if (p.openingAssigned) return;
            String line = joinSan(sanMoves == null ? Collections.emptyList() : sanMoves);
            String label = name == null || name.trim().isEmpty() ? "Starting position" : name.trim();
            // The repeat-opening option pairs adjacent color-reversal games. Whichever
            // one is played first establishes the same immutable BIN line for both.
            int first = repeatOpening ? (index / 2) * 2 : index;
            int lastExclusive = repeatOpening ? Math.min(pairings.size(), first + 2) : index + 1;
            if (repeatOpening) {
                for (int target = first; target < lastExclusive; target++) {
                    Pairing partner = pairings.get(target);
                    if (partner.openingAssigned) {
                        line = partner.openingSanLine;
                        label = partner.openingName;
                        break;
                    }
                }
            }
            for (int target = first; target < lastExclusive; target++) {
                Pairing paired = pairings.get(target);
                if (!paired.openingAssigned) {
                    paired.openingSanLine = line;
                    paired.openingName = label;
                    paired.openingIndex = 0;
                    paired.openingAssigned = true;
                    changed = true;
                }
            }
        }
        if (changed) save();
    }

    /** Original PGN date is reused when a scheduled game is replayed, replacing the same record. */
    public String ensurePairingPgnDate(int index) throws IOException, JSONException {
        String result;
        boolean changed = false;
        synchronized (this) {
            Pairing p = pairings.get(index);
            if (p.pgnDate == null || !p.pgnDate.matches("\\d{4}\\.\\d{2}\\.\\d{2}")) {
                p.pgnDate = pgnTagValue(p.pgn, "Date");
                if (!p.pgnDate.matches("\\d{4}\\.\\d{2}\\.\\d{2}")) {
                    java.util.Calendar now = java.util.Calendar.getInstance();
                    p.pgnDate = String.format(java.util.Locale.US, "%04d.%02d.%02d", now.get(java.util.Calendar.YEAR),
                            now.get(java.util.Calendar.MONTH) + 1, now.get(java.util.Calendar.DAY_OF_MONTH));
                }
                changed = true;
            }
            result = p.pgnDate;
        }
        if (changed) save();
        return result;
    }

    private String pgnTagValue(String pgn, String name) {
        if (pgn == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?m)^\\[" + java.util.regex.Pattern.quote(name) + "\\s+\"((?:\\\\.|[^\"])*?)\"\\]").matcher(pgn);
        return m.find() ? m.group(1).replace("\\\\\"", "\"").replace("\\\\\\\\", "\\\\") : "";
    }
    /** All completed game PGNs in schedule order, separated for concatenated-PGN readers. */
    public String exportPgn() {
        final ArrayList<String> completedGames = new ArrayList<>();
        synchronized (this) {
            // Copy immutable PGN strings only. Concatenating potentially thousands of
            // complete games happens after releasing the manager monitor so UI readers
            // never wait for a large StringBuilder operation.
            for (Pairing p : pairings) {
                if (p.isComplete() && p.pgn != null && !p.pgn.isEmpty())
                    completedGames.add(p.pgn);
            }
        }
        StringBuilder out = new StringBuilder();
        for (String pgn : completedGames) {
            if (out.length() > 0) out.append("\n\n");
            out.append(pgn.trim());
        }
        return out.toString();
    }
    private Engine findEngineOrNull(String id) {
        for (Engine engine : engines) if (engine.id.equals(id)) return engine;
        return null;
    }

    public void recordResult(int index, Outcome outcome, String pgn) throws IOException, JSONException {
        recordResult(index, outcome, pgn, Double.NaN, Double.NaN);
    }

    public void recordResult(int index, Outcome outcome, String pgn,
                             double whiteEvalPawns, double blackEvalPawns) throws IOException, JSONException {
        if (outcome == null || outcome == Outcome.UNFINISHED) throw new IllegalArgumentException("A final result is required");
        RuntimeException pairingError = null;
        Format resultFormat;
        synchronized (this) {
            Pairing p = pairings.get(index);
            if (p.isComplete() && !p.replayPending) throw new IllegalStateException("Pairing already has a result");
            boolean replacingReplay = p.replayPending;
            if (ratingEnabled && (!p.isComplete() || replacingReplay)) p.ratingSequence = nextRatingSequence++;
            p.outcome = outcome; p.pgn = pgn == null ? "" : pgn;
            p.whiteEvalPawns = Double.isFinite(whiteEvalPawns) ? whiteEvalPawns : Double.NaN;
            p.blackEvalPawns = Double.isFinite(blackEvalPawns) ? blackEvalPawns : Double.NaN;
            p.replayPending = false;
            p.anomalyReviewed = false;
            playQueue.remove(Integer.valueOf(index));
            if (activePairingIndex == index) activePairingIndex = -1;
            if (p.pgnDate == null || p.pgnDate.isEmpty()) p.pgnDate = pgnTagValue(p.pgn, "Date");
            Engine whiteEngine = findEngineOrNull(p.whiteEngineId), blackEngine = findEngineOrNull(p.blackEngineId);
            if (whiteEngine != null) p.whiteNameAtPlay = whiteEngine.displayName;
            if (blackEngine != null) p.blackNameAtPlay = blackEngine.displayName;
            resultFormat = format;
            if (format == Format.CUP) {
                try { evaluateCupMatch(findCupMatch(p.cupMatchNumber)); }
                catch (RuntimeException error) { pairingError = error; }
            } else if (format == Format.SWISS && p.round == swissCurrentRound &&
                    swissCurrentRound < swissRoundsTotal && swissRoundComplete(swissCurrentRound)) {
                try { generateSwissRound(); }
                catch (RuntimeException error) { pairingError = error; }
            }
        }
        // Tournament reads (standings, schedules and clocks) remain responsive while
        // compressed persistence and external-storage mirroring are in progress.
        save();
        if (pairingError != null) {
            String operation = resultFormat == Format.CUP ? "Cup match progression" : "Swiss round pairing";
            throw new IllegalStateException("The game result was saved, but " + operation + " failed: " +
                    pairingError.getMessage(), pairingError);
        }
    }

    /** Unreviewed games that ended because one side ran out of time. */
    public synchronized List<Pairing> pendingTimeLosses() {
        ArrayList<Pairing> result = new ArrayList<>();
        for (Pairing p : pairings) if (p.isComplete() && !p.replayPending && !p.anomalyReviewed && isTimeForfeit(p.pgn)) result.add(p);
        return result;
    }

    /** Unreviewed drawn games whose rule/evaluation/move-count context merits manual review. */
    public synchronized List<Pairing> pendingDrawAnomalies() {
        ArrayList<Pairing> result = new ArrayList<>();
        for (Pairing p : pairings) if (p.isComplete() && !p.replayPending && p.outcome == Outcome.DRAW && !p.anomalyReviewed && drawAnomalyReason(p) != null) result.add(p);
        return result;
    }

    public synchronized String anomalyReason(Pairing p) {
        if (p == null || !p.isComplete()) return "";
        if (isTimeForfeit(p.pgn)) return "Loss on time";
        String reason = drawAnomalyReason(p);
        return reason == null ? "" : reason;
    }

    /** Mark a flagged game as reviewed without changing its result or PGN. */
    public void markAnomalyReviewed(int index) throws IOException, JSONException {
        synchronized (this) {
            Pairing p = pairings.get(index);
            if (!p.isComplete() || p.replayPending) throw new IllegalStateException("An unfinished or queued-replay game cannot be confirmed");
            p.anomalyReviewed = true;
        }
        save();
    }

    private boolean isTimeForfeit(String pgn) {
        String termination = pgnTag(pgn, "Termination").toLowerCase(java.util.Locale.US);
        return termination.contains("time forfeit") || termination.contains("timeout") || termination.contains("lost on time");
    }

    private String drawAnomalyReason(Pairing p) {
        if (p == null || p.outcome != Outcome.DRAW) return null;
        String termination = pgnTag(p.pgn, "Termination").toLowerCase(java.util.Locale.US);
        int fullMoves = pgnFullMoveCount(p.pgn);
        boolean beforeRuleStart = anomalyRulesEnabled && anomalyRuleSettingsSaved &&
                p.pgn != null && !p.pgn.trim().isEmpty() && fullMoves < anomalyRuleStartFullMove;
        boolean fiftyMoveDraw = termination.contains("50-move rule") || termination.contains("fifty-move");
        boolean maximumMoveDraw = termination.contains("maximum-move rule") || termination.contains("move limit") ||
                (termination.isEmpty() && anomalyRulesEnabled && fullMoves >= anomalyMaximumFullMoves && anomalyMaximumFullMoves > 0);
        double whiteEval = p.whiteEvalPawns, blackEval = p.blackEvalPawns;
        if (!Double.isFinite(whiteEval) || !Double.isFinite(blackEval)) {
            double[] fromPgn = readFinalEngineEvals(p.pgn);
            if (!Double.isFinite(whiteEval)) whiteEval = fromPgn[0];
            if (!Double.isFinite(blackEval)) blackEval = fromPgn[1];
        }
        boolean hasEval = Double.isFinite(whiteEval) || Double.isFinite(blackEval);
        boolean hasBoth = Double.isFinite(whiteEval) && Double.isFinite(blackEval);
        double strongest = Math.max(Double.isFinite(whiteEval) ? Math.abs(whiteEval) : 0.0,
                Double.isFinite(blackEval) ? Math.abs(blackEval) : 0.0);
        boolean evalDisagreement = strongest >= ANOMALY_EVAL_THRESHOLD_PAWNS ||
                (hasBoth && Math.abs(whiteEval - blackEval) >= 1.0);

        if (beforeRuleStart) return "Draw before automatic rules begin (configured start: full move " + anomalyRuleStartFullMove + ")" + evalSummary(whiteEval, blackEval);
        if (fiftyMoveDraw && (evalDisagreement || !hasEval))
            return "50-move-rule draw" + (evalDisagreement ? " despite engine evaluation" : " · engine evaluation unavailable") + evalSummary(whiteEval, blackEval);
        if (maximumMoveDraw && (evalDisagreement || !hasEval))
            return "Maximum-move-limit draw" + (evalDisagreement ? " despite engine evaluation" : " · engine evaluation unavailable") + evalSummary(whiteEval, blackEval);
        if (evalDisagreement) return "Draw despite engine evaluation" + evalSummary(whiteEval, blackEval);
        return null;
    }

    private String evalSummary(double whiteEval, double blackEval) {
        if (!Double.isFinite(whiteEval) && !Double.isFinite(blackEval)) return "";
        String white = Double.isFinite(whiteEval) ? String.format(java.util.Locale.US, "%+.2f", whiteEval) : "n/a";
        String black = Double.isFinite(blackEval) ? String.format(java.util.Locale.US, "%+.2f", blackEval) : "n/a";
        return " (White engine " + white + ", Black engine " + black + ")";
    }

    private String pgnTag(String pgn, String name) {
        if (pgn == null) return "";
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?m)^\\[" + java.util.regex.Pattern.quote(name) + "\\s+\"((?:\\\\.|[^\"])*)\"\\]").matcher(pgn);
        return matcher.find() ? matcher.group(1) : "";
    }

    private int pgnFullMoveCount(String pgn) {
        if (pgn == null) return 0;
        String movetext = pgn.replaceAll("(?m)^\\[[^\\r\\n]*\\][\\r\\n]*", " ")
                .replaceAll("\\{[^}]*\\}", " ");
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?:^|\\s)(\\d+)\\.{1,3}").matcher(movetext);
        int max = 0;
        while (matcher.find()) try { max = Math.max(max, Integer.parseInt(matcher.group(1))); } catch (NumberFormatException ignored) { }
        return max;
    }

    private double[] readFinalEngineEvals(String pgn) {
        double white = Double.NaN, black = Double.NaN;
        if (pgn == null) return new double[]{white, black};
        java.util.regex.Matcher comments = java.util.regex.Pattern.compile("\\{([^}]*)\\}").matcher(pgn);
        int ply = 0;
        while (comments.find()) {
            int commentPly = ply++;
            java.util.regex.Matcher eval = java.util.regex.Pattern.compile("Eval:\\s*([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)|[+-]M\\d+)").matcher(comments.group(1));
            if (!eval.find()) continue;
            String value = eval.group(1);
            double parsed;
            try {
                if (value.toUpperCase(java.util.Locale.US).contains("M")) parsed = value.startsWith("-") ? -99.0 : 99.0;
                else parsed = Double.parseDouble(value);
            } catch (NumberFormatException ignored) { continue; }
            if ((commentPly & 1) == 0) white = parsed; else black = parsed;
        }
        return new double[]{white, black};
    }

    /** Change the participants of an unfinished scheduled game without touching recorded games. */
    public synchronized void updatePairingEngines(int index, String whiteId, String blackId) throws IOException, JSONException {
        Pairing p = pairings.get(index);
        if (p.isComplete() || p.replayPending) throw new IllegalStateException("Completed or queued-replay games cannot be rescheduled");
        if (whiteId == null || blackId == null || whiteId.equals(blackId))
            throw new IllegalArgumentException("Choose two different engines");
        requireEngine(whiteId); requireEngine(blackId);
        if (format == Format.SWISS) {
            SwissMatch match = findSwissMatchForGame(index);
            if (match == null) throw new IllegalStateException("Swiss pairing metadata is missing for this game");
            int otherIndex = match.firstGameIndex == index ? match.secondGameIndex : match.firstGameIndex;
            Pairing other = pairings.get(otherIndex);
            if (other.isComplete() || other.replayPending)
                throw new IllegalStateException("Both games in a Swiss color-reversal match must be unfinished to edit the pairing");
            for (SwissMatch existing : swissMatches) {
                if (existing == match) continue;
                boolean sameOpponentPair =
                        (whiteId.equals(existing.firstEngineId) && blackId.equals(existing.secondEngineId)) ||
                        (whiteId.equals(existing.secondEngineId) && blackId.equals(existing.firstEngineId));
                if (sameOpponentPair)
                    throw new IllegalArgumentException("These engines have already been paired in another Swiss round");
                if (existing.round == match.round &&
                        (whiteId.equals(existing.firstEngineId) || whiteId.equals(existing.secondEngineId) ||
                                blackId.equals(existing.firstEngineId) || blackId.equals(existing.secondEngineId)))
                    throw new IllegalArgumentException("An engine cannot appear in two pairings in the same Swiss round");
            }
            p.whiteEngineId = whiteId;
            p.blackEngineId = blackId;
            other.whiteEngineId = blackId;
            other.blackEngineId = whiteId;
            // In the first scheduled game the stored first engine is Black;
            // in the reversed-color game it is White.
            match.firstEngineId = match.firstGameIndex == index ? blackId : whiteId;
            match.secondEngineId = match.firstGameIndex == index ? whiteId : blackId;
        } else {
            p.whiteEngineId = whiteId; p.blackEngineId = blackId;
        }
        save();
    }

    private SwissMatch findSwissMatchForGame(int index) {
        for (SwissMatch match : swissMatches)
            if (match.firstGameIndex == index || match.secondGameIndex == index) return match;
        return null;
    }

    /** Correct a recorded result; standings are always recalculated from the game records. */
    public void updatePairingResult(int index, Outcome outcome) throws IOException, JSONException {
        synchronized (this) {
            if (format == Format.CUP)
                throw new IllegalStateException("Cup game results are locked to preserve match advancement and bracket history");
            if (outcome == null || outcome == Outcome.UNFINISHED) throw new IllegalArgumentException("Choose a final result");
            Pairing p = pairings.get(index);
            if (!p.isComplete() || p.replayPending) throw new IllegalStateException("An unfinished or queued-replay game cannot have its result corrected");
            p.outcome = outcome;
            p.pgn = replacePgnResult(p.pgn, outcome);
            p.anomalyReviewed = true;
        }
        save();
    }

    private void requireEngine(String id) {
        for (Engine engine : engines) if (engine.id.equals(id)) return;
        throw new IllegalArgumentException("Unknown engine: " + id);
    }

    private String replacePgnResult(String pgn, Outcome outcome) {
        if (pgn == null || pgn.trim().isEmpty()) return pgn == null ? "" : pgn;
        String token = outcome == Outcome.WHITE_WIN ? "1-0" : outcome == Outcome.BLACK_WIN ? "0-1" : "1/2-1/2";

        // A manual readjudication changes the official result and its reason, but
        // deliberately leaves the complete movetext (moves, comments, and NAGs) intact.
        String updated = replacePgnTag(pgn, "Result", token);
        updated = replacePgnTag(updated, "Termination", "adjudication");

        // Replace only the final PGN game-termination marker, not any move/comment text.
        java.util.regex.Matcher tail = java.util.regex.Pattern
                .compile("(?s)(\\s)(1-0|0-1|1/2-1/2|\\*)(\\s*)$")
                .matcher(updated);
        if (tail.find()) {
            updated = updated.substring(0, tail.start(2)) + token + updated.substring(tail.end(2));
        } else {
            // Tournament PGNs normally have a marker. If an older/incomplete PGN
            // lacks one, append the official marker after its existing movetext.
            int end = updated.length();
            while (end > 0 && Character.isWhitespace(updated.charAt(end - 1))) end--;
            String trailing = updated.substring(end);
            updated = updated.substring(0, end) + (end > 0 ? " " : "") + token + trailing;
        }
        return updated;
    }

    /** Replace one PGN tag, or insert it in the header without touching movetext. */
    private String replacePgnTag(String pgn, String name, String value) {
        String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"");
        String replacement = "[" + name + " \"" + escaped + "\"]";
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "(?m)^\\[" + java.util.regex.Pattern.quote(name) + "\\s+\"(?:\\\\.|[^\"])*\"\\][ \t]*\\r?$");
        java.util.regex.Matcher matcher = pattern.matcher(pgn);
        if (matcher.find()) {
            return pgn.substring(0, matcher.start()) + replacement + pgn.substring(matcher.end());
        }

        // Insert before the blank line separating PGN tags from the movetext.
        int separator = pgn.indexOf("\n\n");
        if (separator >= 0) return pgn.substring(0, separator) + "\n" + replacement + pgn.substring(separator);
        int crlfSeparator = pgn.indexOf("\r\n\r\n");
        if (crlfSeparator >= 0) return pgn.substring(0, crlfSeparator) + "\r\n" + replacement + pgn.substring(crlfSeparator);
        if (pgn.startsWith("[")) return pgn + (pgn.endsWith("\n") ? "" : "\n") + replacement;
        return replacement + "\n\n" + pgn;
    }

    public synchronized List<Standing> standings() {
        List<Standing> table = new ArrayList<>();
        for (Engine e : engines) table.add(new Standing(e.id));
        for (Pairing p : pairings) {
            if (!p.isComplete()) continue;
            Standing w = find(table, p.whiteEngineId), b = find(table, p.blackEngineId);
            w.played++; b.played++;
            switch (p.outcome) {
                case WHITE_WIN: w.wins++; b.losses++; w.points += 1; break;
                case BLACK_WIN: b.wins++; w.losses++; b.points += 1; break;
                case DRAW: w.draws++; b.draws++; w.points += .5; b.points += .5; break;
                default: break;
            }
        }
        if (format == Format.SWISS) {
            for (SwissBye bye : swissByes) {
                Standing standing = find(table, bye.engineId);
                standing.points += 2.0;
                standing.byes++;
            }
            for (SwissMatch match : swissMatches) {
                if (match.firstGameIndex < 0 || match.secondGameIndex < 0 ||
                        match.firstGameIndex >= pairings.size() || match.secondGameIndex >= pairings.size()) continue;
                Pairing firstGame = pairings.get(match.firstGameIndex);
                Pairing secondGame = pairings.get(match.secondGameIndex);
                if (!firstGame.isComplete() || !secondGame.isComplete()) continue;
                double firstPoints = gamePointsFor(match.firstEngineId, firstGame) +
                        gamePointsFor(match.firstEngineId, secondGame);
                double secondPoints = 2.0 - firstPoints;
                Standing first = find(table, match.firstEngineId);
                Standing second = find(table, match.secondEngineId);
                first.sonnebornBerger += second.points * (firstPoints / 2.0);
                second.sonnebornBerger += first.points * (secondPoints / 2.0);
            }
            Collections.sort(table, (a, b) -> {
                int score = Double.compare(b.points, a.points);
                if (score != 0) return score;
                int byes = Integer.compare(a.byes, b.byes);
                if (byes != 0) return byes;
                int sb = Double.compare(b.sonnebornBerger, a.sonnebornBerger);
                if (sb != 0) return sb;
                int wins = Integer.compare(b.wins, a.wins);
                if (wins != 0) return wins;
                return Integer.compare(seedOf(a.engineId), seedOf(b.engineId));
            });
            // Apply direct encounter as a mini-table within each group still tied
            // on the preceding tiebreakers. This avoids a non-transitive
            // pairwise head-to-head comparator when three or more engines tie.
            int groupStart = 0;
            while (groupStart < table.size()) {
                int groupEnd = groupStart + 1;
                Standing first = table.get(groupStart);
                while (groupEnd < table.size() && sameSwissPrimaryTie(first, table.get(groupEnd))) groupEnd++;
                if (groupEnd - groupStart > 1) {
                    Set<String> tiedIds = new HashSet<>();
                    for (int i = groupStart; i < groupEnd; i++) tiedIds.add(table.get(i).engineId);
                    final Map<String, Double> directScores = new HashMap<>();
                    for (String id : tiedIds) directScores.put(id, directEncounterPointsAgainstGroup(id, tiedIds));
                    Collections.sort(table.subList(groupStart, groupEnd), (a, b) -> {
                        int direct = Double.compare(directScores.get(b.engineId), directScores.get(a.engineId));
                        return direct != 0 ? direct : Integer.compare(seedOf(a.engineId), seedOf(b.engineId));
                    });
                }
                groupStart = groupEnd;
            }
        } else {
            Collections.sort(table, (a,b) -> Double.compare(b.points, a.points));
        }
        return table;
    }

    private double gamePointsFor(String engineId, Pairing pairing) {
        boolean white = engineId.equals(pairing.whiteEngineId);
        boolean black = engineId.equals(pairing.blackEngineId);
        if (pairing.outcome == Outcome.DRAW) return white || black ? 0.5 : 0.0;
        if ((white && pairing.outcome == Outcome.WHITE_WIN) ||
                (black && pairing.outcome == Outcome.BLACK_WIN)) return 1.0;
        return 0.0;
    }

    private boolean sameSwissPrimaryTie(Standing a, Standing b) {
        return Double.compare(a.points, b.points) == 0 && a.byes == b.byes &&
                Double.compare(a.sonnebornBerger, b.sonnebornBerger) == 0 && a.wins == b.wins;
    }

    private double directEncounterPointsAgainstGroup(String engineId, Set<String> tiedIds) {
        double points = 0.0;
        for (SwissMatch match : swissMatches) {
            String opponentId = engineId.equals(match.firstEngineId) ? match.secondEngineId :
                    engineId.equals(match.secondEngineId) ? match.firstEngineId : null;
            if (opponentId == null || !tiedIds.contains(opponentId)) continue;
            if (match.firstGameIndex < 0 || match.secondGameIndex < 0 ||
                    match.firstGameIndex >= pairings.size() || match.secondGameIndex >= pairings.size()) continue;
            Pairing first = pairings.get(match.firstGameIndex);
            Pairing second = pairings.get(match.secondGameIndex);
            if (!first.isComplete() || !second.isComplete()) continue;
            points += gamePointsFor(engineId, first) + gamePointsFor(engineId, second);
        }
        return points;
    }
    private Standing find(List<Standing> list, String id) { for (Standing s : list) if (s.engineId.equals(id)) return s; throw new IllegalStateException("Unknown engine " + id); }

    /** True only when Elo was explicitly enabled when this tournament was created. */
    public synchronized boolean ratingEnabled() { return ratingEnabled; }
    public synchronized boolean ratingShowDecimals() { return ratingShowDecimals; }
    public synchronized double ratingKFactor() { return ratingKFactor; }
    public void setRatingShowDecimals(boolean show) throws IOException, JSONException {
        synchronized (this) {
            if (!ratingEnabled) throw new IllegalStateException("Elo rating is disabled for this tournament");
            if (ratingShowDecimals == show) return;
            ratingShowDecimals = show;
        }
        save();
    }
    public synchronized Map<String, Double> initialRatings() {
        return Collections.unmodifiableMap(new HashMap<>(initialRatings));
    }

    /**
     * Configure Elo for a newly-created tournament before its first game.
     * The creation-only guard prevents changing the Elo policy of any saved/legacy event.
     */
    public synchronized void setRatingSettings(boolean enabled, Map<String, Double> startingRatings,
                                               boolean showDecimals) {
        if (!ratingConfigurationAllowed)
            throw new IllegalStateException("Elo settings are fixed when a tournament is created and cannot be changed on a saved tournament");
        if (completedGamesCount() != 0)
            throw new IllegalStateException("Elo settings can only be configured before the first completed game");
        if (!enabled) {
            ratingConfigurationAllowed = false;
            ratingEnabled = false;
            ratingShowDecimals = false;
            initialRatings.clear();
            return;
        }
        if (startingRatings == null || startingRatings.size() != engines.size())
            throw new IllegalArgumentException("An initial Elo rating is required for every selected engine");
        HashMap<String, Double> validated = new HashMap<>();
        for (Engine engine : engines) {
            Double value = startingRatings.get(engine.id);
            if (value == null || !Double.isFinite(value) || value < 0.0 || value > 10000.0)
                throw new IllegalArgumentException("Invalid initial Elo rating for " + engine.displayName + " (use 0–10000)");
            validated.put(engine.id, value);
        }
        ratingConfigurationAllowed = false;
        initialRatings.clear();
        initialRatings.putAll(validated);
        ratingEnabled = true;
        ratingShowDecimals = showDecimals;
        ratingKFactor = 20.0;
        nextRatingSequence = 1L;
        for (Pairing pairing : pairings) pairing.ratingSequence = 0L;
    }

    /** Current Elo values recalculated in actual result-completion order. */
    public synchronized Map<String, Double> ratings() {
        if (!ratingEnabled) return Collections.emptyMap();
        HashMap<String, Double> current = new HashMap<>(initialRatings);
        ArrayList<Pairing> ordered = new ArrayList<>();
        for (Pairing pairing : pairings)
            if (pairing.isComplete() && pairing.ratingSequence > 0L) ordered.add(pairing);
        Collections.sort(ordered, (a, b) -> {
            int sequence = Long.compare(a.ratingSequence, b.ratingSequence);
            return sequence != 0 ? sequence : Integer.compare(a.index, b.index);
        });
        for (Pairing pairing : ordered) {
            Double whiteRating = current.get(pairing.whiteEngineId);
            Double blackRating = current.get(pairing.blackEngineId);
            if (whiteRating == null || blackRating == null) continue;
            double whiteActual = pairing.outcome == Outcome.WHITE_WIN ? 1.0 :
                    pairing.outcome == Outcome.DRAW ? 0.5 : 0.0;
            double blackActual = 1.0 - whiteActual;
            double expectedWhite = 1.0 / (1.0 + Math.pow(10.0, (blackRating - whiteRating) / 400.0));
            double expectedBlack = 1.0 - expectedWhite;
            current.put(pairing.whiteEngineId, whiteRating + ratingKFactor * (whiteActual - expectedWhite));
            current.put(pairing.blackEngineId, blackRating + ratingKFactor * (blackActual - expectedBlack));
        }
        return Collections.unmodifiableMap(current);
    }

    public synchronized String externalSaveError() { return externalSaveError; }

    public void save() throws IOException, JSONException {
        final JSONObject root = new JSONObject();
        final long revision;
        synchronized (this) {
            root.put("title", title); root.put("format", format.name()); root.put("gamesPerPair", gamesPerPair);
            root.put("baseTimeMs", baseTimeMs); root.put("incrementMs", incrementMs);
            root.put("timeMode", timeMode.name()); root.put("limitValue", limitValue);
            // Omit all rating metadata when disabled. Legacy/current tournaments therefore
            // retain their old schema and load as unrated without a rating migration.
            if (ratingEnabled) {
                root.put("ratingEnabled", true); root.put("ratingShowDecimals", ratingShowDecimals);
                root.put("ratingKFactor", ratingKFactor); root.put("nextRatingSequence", nextRatingSequence);
                JSONArray initialRatingArray = new JSONArray();
                for (Engine engine : engines) {
                    JSONObject rating = new JSONObject(); rating.put("engineId", engine.id);
                    rating.put("rating", initialRatings.get(engine.id)); initialRatingArray.put(rating);
                }
                root.put("initialRatings", initialRatingArray);
            }
            root.put("openingPgn", openingPgn); root.put("openingPgnFileName", openingPgnFileName); root.put("openingBinPath", openingBinPath); root.put("openingPlies", openingPlies); root.put("openingStartIndex", openingStartIndex); root.put("repeatOpening", repeatOpening); root.put("tournamentElapsedMs", tournamentElapsedMs);
            root.put("startPositionMode", startPositionMode.name()); root.put("chess960Enabled", startPositionMode == StartPositionMode.CHESS960);
            root.put("chess960StartId", chess960StartId); root.put("startingPositionSeed", startingPositionSeed); root.put("openingFen", openingFen);
            root.put("anomalyRuleSettingsSaved", anomalyRuleSettingsSaved); root.put("anomalyRulesEnabled", anomalyRulesEnabled);
            root.put("anomalyRuleStartFullMove", anomalyRuleStartFullMove); root.put("anomalyMaximumFullMoves", anomalyMaximumFullMoves);
            JSONArray ea = new JSONArray(); for (Engine e : engines) { JSONObject o = new JSONObject(); o.put("id", e.id); o.put("name", e.displayName); o.put("executableId", e.executableId); ea.put(o); } root.put("engines", ea);
            JSONArray pa = new JSONArray(); for (Pairing p : pairings) { JSONObject o = new JSONObject(); o.put("index", p.index); o.put("round", p.round); o.put("gameNumber", p.gameNumber); o.put("white", p.whiteEngineId); o.put("black", p.blackEngineId); o.put("outcome", p.outcome.name()); o.put("pgn", p.pgn); o.put("whiteEvalPawns", Double.isFinite(p.whiteEvalPawns) ? p.whiteEvalPawns : JSONObject.NULL); o.put("blackEvalPawns", Double.isFinite(p.blackEvalPawns) ? p.blackEvalPawns : JSONObject.NULL); o.put("anomalyReviewed", p.anomalyReviewed); o.put("replayPending", p.replayPending); o.put("openingAssigned", p.openingAssigned); o.put("openingIndex", p.openingIndex); o.put("openingName", p.openingName); o.put("openingSanLine", p.openingSanLine); o.put("pgnDate", p.pgnDate); o.put("cupMatchNumber", p.cupMatchNumber); o.put("cupPairNumber", p.cupPairNumber); o.put("cupGameInPair", p.cupGameInPair); if (ratingEnabled) o.put("ratingSequence", p.ratingSequence); o.put("whiteNameAtPlay", p.whiteNameAtPlay); o.put("blackNameAtPlay", p.blackNameAtPlay); pa.put(o); } root.put("pairings", pa);
            if (format == Format.SWISS) {
                root.put("swissRoundsTotal", swissRoundsTotal);
                root.put("swissCurrentRound", swissCurrentRound);
                root.put("swissSeedingMode", swissSeedingMode.name());
                root.put("swissRandomSeed", swissRandomSeed);
                JSONArray strengthOrder = new JSONArray();
                for (String engineId : swissStrengthOrder) strengthOrder.put(engineId);
                root.put("swissStrengthOrder", strengthOrder);
                JSONArray byes = new JSONArray();
                for (SwissBye bye : swissByes) {
                    JSONObject entry = new JSONObject(); entry.put("round", bye.round); entry.put("engine", bye.engineId); byes.put(entry);
                }
                root.put("swissByes", byes);
                JSONArray matches = new JSONArray();
                for (SwissMatch match : swissMatches) {
                    JSONObject entry = new JSONObject(); entry.put("round", match.round);
                    entry.put("first", match.firstEngineId); entry.put("second", match.secondEngineId);
                    entry.put("firstGame", match.firstGameIndex); entry.put("secondGame", match.secondGameIndex);
                    matches.put(entry);
                }
                root.put("swissMatches", matches);
            }
            if (format == Format.CUP) {
                root.put("cupSeedingMode", cupSeedingMode.name()); root.put("cupRandomSeed", cupRandomSeed);
                root.put("cupBracketSize", cupBracketSize); root.put("cupCurrentStage", cupCurrentStage);
                root.put("cupCheckpointPending", cupCheckpointPending); root.put("cupFinished", cupFinished);
                root.put("cupNextMatchNumber", cupNextMatchNumber); root.put("cupNextGameNumber", cupNextGameNumber);
                root.put("cupPgnFileBaseName", cupPgnFileBaseName);
                JSONArray strength = new JSONArray(); for (String id : cupStrengthOrder) strength.put(id); root.put("cupStrengthOrder", strength);
                JSONArray slots = new JSONArray(); for (String id : cupSeedSlots) { if (id == null || id.isEmpty()) slots.put(JSONObject.NULL); else slots.put(id); } root.put("cupSeedSlots", slots);
                JSONArray matches = new JSONArray();
                for (CupMatch match : cupMatches) {
                    JSONObject o = new JSONObject(); o.put("stage", match.stageIndex); o.put("number", match.matchNumber);
                    o.put("regularPairLimit", match.regularPairLimit); o.put("engineA", match.engineAId); o.put("engineB", match.engineBId);
                    o.put("winner", match.winnerId); o.put("decided", match.decided); o.put("bye", match.bye);
                    o.put("pairsScheduled", match.pairsScheduled); o.put("pairsCompleted", match.pairsCompleted);
                    o.put("engineAPoints", match.engineAPoints); o.put("engineBPoints", match.engineBPoints);
                    JSONArray games = new JSONArray(); for (Integer index : match.gameIndices) games.put(index); o.put("games", games);
                    matches.put(o);
                }
                root.put("cupMatches", matches);
            }
            JSONArray queue = new JSONArray(); for (Integer index : playQueue) queue.put(index); root.put("playQueue", queue);
            root.put("activePairingIndex", activePairingIndex);
            revision = ++latestSaveRevision;
        }
        // JSONObject serialization walks the entire schedule and every stored PGN.
        // Keep this expensive operation outside synchronized(this) so a save for a
        // large tournament cannot block board/clock/schedule readers on the UI thread.
        final byte[] bytes = root.toString().getBytes(Charset.forName("UTF-8"));
        synchronized (saveIoLock) {
            // A newer in-memory snapshot supersedes this one. If it has not yet
            // written, its caller will acquire saveIoLock next and persist it.
            if (revision < latestSaveRevision) return;
        // Commit a complete, CRC-verified gzip snapshot atomically to private storage first.
        long primarySaveStartedNs = System.nanoTime();
        try {
            writeAtomicCompressed(new File(context.getFilesDir(), fileName), bytes);
        } catch (IOException primarySaveError) {
            TournamentDiagnostics.recordError("SAVE", "primary_snapshot_commit_failed file=" + fileName, primarySaveError);
            throw primarySaveError;
        }
        TournamentDiagnostics.record("SAVE", "primary_snapshot_committed", "file=" + fileName + " bytes=" +
                bytes.length + " elapsedMs=" + ((System.nanoTime() - primarySaveStartedNs) / 1_000_000L));
        // Stage 2 modular mirroring is intentionally paused during stability recovery.
        // The verified legacy compressed JSON remains the sole authoritative snapshot.
        synchronized (TournamentManager.class) { RESERVED_TOURNAMENT_FILE_NAMES.remove(fileName); }
        // Mirror the exact same snapshot to shared DroidFish storage after every save(),
        // including each completed game and the periodic elapsed-time checkpoint.
        externalSaveError = "";
        try {
            writeAtomicCompressed(externalTournamentFile(context, fileName), bytes);
            // If an earlier save used the app-specific fallback, refresh that copy too
            // so a later restore can never select a stale mirror because of timestamp ties.
            File fallback = appExternalTournamentFile(context, fileName);
            if (fallback != null && fallback.isFile()) {
                try { writeAtomicCompressed(fallback, bytes); }
                catch (IOException | SecurityException staleFallbackError) {
                    try { fallback.delete(); } catch (SecurityException ignored) { }
                }
            }
        } catch (IOException | SecurityException sharedError) {
            TournamentDiagnostics.recordError("SAVE", "shared_snapshot_mirror_failed file=" + fileName, sharedError);
            try {
                File fallback = appExternalTournamentFile(context, fileName);
                if (fallback == null) throw new IOException("App external storage is unavailable");
                writeAtomicCompressed(fallback, bytes);
                externalSaveError = "Shared DroidFish/tournament folder unavailable; backup saved in " + fallback.getAbsolutePath();
            } catch (IOException | SecurityException fallbackError) {
                externalSaveError = "Could not mirror tournament JSON to shared or app external storage: " + fallbackError.getMessage();
                TournamentDiagnostics.recordError("SAVE", "all_snapshot_mirrors_failed file=" + fileName, fallbackError);
            }
        }
        }
    }

    private static void writeAtomicCompressed(File target, byte[] bytes) throws IOException {
        File dir = target.getParentFile();
        if (dir == null || (!dir.exists() && !dir.mkdirs() && !dir.isDirectory()))
            throw new IOException("Cannot create tournament folder");
        File temp = new File(dir, target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp, false)) {
            GZIPOutputStream gzip = new GZIPOutputStream(new BufferedOutputStream(out));
            gzip.write(bytes);
            gzip.finish();
            gzip.flush();
            out.getFD().sync();
            gzip.close();
        }
        // Read the entire temporary gzip stream before publication. This checks the
        // gzip trailer/CRC and confirms that every serialized JSON byte survived.
        long decoded = 0;
        try (GZIPInputStream verify = new GZIPInputStream(new BufferedInputStream(new FileInputStream(temp)))) {
            byte[] buffer = new byte[32768];
            int count;
            while ((count = verify.read(buffer)) != -1) decoded += count;
        } catch (IOException invalidGzip) {
            temp.delete();
            throw new IOException("Compressed tournament snapshot failed verification", invalidGzip);
        }
        if (decoded != bytes.length) {
            temp.delete();
            throw new IOException("Compressed tournament snapshot has an incomplete payload");
        }
        publishTempFile(temp, target);
    }

}
