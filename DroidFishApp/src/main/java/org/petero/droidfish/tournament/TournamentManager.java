package org.petero.droidfish.tournament;

import android.content.Context;
import android.os.Environment;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Persistent tournament schedule/result model. Engine execution is owned by the controller. */
public final class TournamentManager {
    public enum Format { ROUND_ROBIN, MATCH, GAUNTLET }
    public enum Outcome { WHITE_WIN, DRAW, BLACK_WIN, UNFINISHED }
    public enum TimeMode { CLOCK, MOVE_TIME, DEPTH, NODES, MOVE_PERIOD }

    public static final class Engine {
        public final String id;
        public final String displayName;
        public Engine(String id, String displayName) {
            if (id == null || id.trim().isEmpty()) throw new IllegalArgumentException("Empty engine id");
            this.id = id; this.displayName = displayName == null || displayName.trim().isEmpty() ? id : displayName;
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
        Pairing(int index, int round, int gameNumber, String white, String black, Outcome outcome, String pgn) {
            this.index = index; this.round = round; this.gameNumber = gameNumber;
            whiteEngineId = white; blackEngineId = black; this.outcome = outcome; this.pgn = pgn;
        }
        public boolean isComplete() { return outcome != Outcome.UNFINISHED; }
    }

    public static final class Standing {
        public final String engineId;
        public int played, wins, draws, losses;
        public double points;
        Standing(String id) { engineId = id; }
    }

    private final Context context;
    private final String fileName;
    private String title;
    private Format format;
    private int gamesPerPair;
    private long baseTimeMs = 30000, incrementMs = 500;
    private TimeMode timeMode = TimeMode.CLOCK;
    private int limitValue = 30;
    private String openingPgn = "";
    private String openingBinPath = "";
    private int openingPlies;
    private int openingStartIndex = 1;
    private boolean repeatOpening;
    private long tournamentElapsedMs;
    private boolean anomalyRuleSettingsSaved;
    private boolean anomalyRulesEnabled;
    private int anomalyRuleStartFullMove = 1;
    private int anomalyMaximumFullMoves = 160;
    private static final double ANOMALY_EVAL_THRESHOLD_PAWNS = 1.50;
    private final List<Engine> engines = new ArrayList<>();
    private final List<Pairing> pairings = new ArrayList<>();
    /** Game indices in operator-selected priority order. */
    private final ArrayList<Integer> playQueue = new ArrayList<>();
    /** Scheduled game interrupted by a pause/process death; it resumes before the priority queue. */
    private int activePairingIndex = -1;
    private String externalSaveError = "";

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
        if (context == null || engines == null || engines.size() < 2) throw new IllegalArgumentException("At least two engines are required");
        if (gamesPerPair < 1) throw new IllegalArgumentException("Games per pairing must be positive");
        if (baseTimeMs < 1 || incrementMs < 0 || openingPlies < 0) throw new IllegalArgumentException("Invalid tournament settings");
        this.context = context.getApplicationContext();
        // Every new tournament receives a unique internal filename so importing or
        // creating an event can never overwrite another saved tournament.
        this.fileName = newUniqueFileName(this.context);
        this.title = title == null ? "CET Tournament" : title; this.format = format; this.gamesPerPair = gamesPerPair;
        this.baseTimeMs = baseTimeMs; this.incrementMs = incrementMs;
        this.timeMode = timeMode == null ? TimeMode.CLOCK : timeMode; this.limitValue = Math.max(1, limitValue);
        this.openingPgn = openingPgn == null ? "" : openingPgn; this.openingPlies = openingPlies;
        this.repeatOpening = repeatOpening;
        this.openingStartIndex = Math.max(1, openingStartIndex);
        this.engines.addAll(engines);
        buildSchedule();
        assignPgnOpeningsInMemory();
    }

    private TournamentManager(Context context, JSONObject root, String fileName) throws JSONException {
        this.context = context.getApplicationContext(); this.fileName = fileName == null || fileName.isEmpty() ? "cet_tournament.json" : fileName;
        title = root.optString("title", "CET Tournament"); format = Format.valueOf(root.getString("format"));
        gamesPerPair = root.optInt("gamesPerPair", 2);
        baseTimeMs = root.optLong("baseTimeMs", 30000);
        incrementMs = root.optLong("incrementMs", 500);
        openingPgn = root.optString("openingPgn", "");
        openingBinPath = root.optString("openingBinPath", "");
        openingPlies = root.optInt("openingPlies", 0);
        repeatOpening = root.optBoolean("repeatOpening", false);
        tournamentElapsedMs = Math.max(0L, root.optLong("tournamentElapsedMs", 0L));
        openingStartIndex = Math.max(1, root.optInt("openingStartIndex", 1));
        anomalyRuleSettingsSaved = root.optBoolean("anomalyRuleSettingsSaved", false);
        anomalyRulesEnabled = root.optBoolean("anomalyRulesEnabled", true);
        anomalyRuleStartFullMove = Math.max(1, root.optInt("anomalyRuleStartFullMove", 1));
        anomalyMaximumFullMoves = Math.max(1, root.optInt("anomalyMaximumFullMoves", 160));
        timeMode = TimeMode.valueOf(root.optString("timeMode", "CLOCK")); limitValue = root.optInt("limitValue", 30);
        JSONArray ea = root.getJSONArray("engines");
        for (int i = 0; i < ea.length(); i++) { JSONObject e = ea.getJSONObject(i); engines.add(new Engine(e.getString("id"), e.optString("name"))); }
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
            pairings.add(pairing);
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
            imported = new TournamentManager(context, root, newUniqueFileName(context.getApplicationContext()));
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
    }

    private static String newUniqueFileName(Context context) {
        long stamp = System.currentTimeMillis();
        int suffix = 0;
        String candidate;
        do {
            candidate = "cet_tournament_" + stamp + (suffix == 0 ? "" : "_" + suffix) + ".json";
            suffix++;
        } while (new File(context.getFilesDir(), candidate).exists() ||
                externalTournamentFile(context, candidate).exists() ||
                fileExists(appExternalTournamentFile(context, candidate)));
        return candidate;
    }

    public static TournamentManager restore(Context context) throws IOException, JSONException {
        List<String> saved = savedTournamentFiles(context);
        if (saved.isEmpty()) throw new IOException("No saved tournament exists");
        return restore(context, saved.get(0));
    }

    public static TournamentManager restore(Context context, String fileName) throws IOException, JSONException {
        if (!isTournamentFileName(fileName)) throw new IOException("Invalid saved tournament name");
        File internal = new File(context.getFilesDir(), fileName);
        if (!internal.isFile()) {
            File external = findExternalTournamentFile(context, fileName);
            if (external == null || !external.isFile()) throw new IOException("Saved tournament file was not found");
            copyToInternal(context, external, fileName);
        }
        try (FileInputStream in = context.openFileInput(fileName);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, Charset.forName("UTF-8")))) {
            StringBuilder data = new StringBuilder(); String line;
            while ((line = reader.readLine()) != null) data.append(line);
            return new TournamentManager(context, new JSONObject(data.toString()), fileName);
        }
    }

    /** Internal app storage remains authoritative; public JSON copies are recovery/portability mirrors. */
    public static List<String> savedTournamentFiles(Context context) {
        java.util.LinkedHashSet<String> found = new java.util.LinkedHashSet<>();
        addTournamentFiles(found, context.getFilesDir());
        addTournamentFiles(found, externalTournamentDirectory(context));
        addTournamentFiles(found, appExternalTournamentDirectory(context));
        List<String> files = new ArrayList<>(found);
        Collections.sort(files, (a, b) -> Long.compare(savedTimestamp(b), savedTimestamp(a)));
        return files;
    }

    private static void addTournamentFiles(java.util.Set<String> names, File dir) {
        if (dir == null || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) if (file.isFile() && isTournamentFileName(file.getName())) names.add(file.getName());
    }

    private static boolean isTournamentFileName(String name) {
        return name != null && (name.equals("cet_tournament.json") ||
                (name.startsWith("cet_tournament_") && name.endsWith(".json") && name.indexOf('/') < 0 && name.indexOf('\\') < 0));
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

    private static boolean fileExists(File file) { return file != null && file.exists(); }

    private static File findExternalTournamentFile(Context context, String name) {
        File shared = externalTournamentFile(context, name);
        File app = appExternalTournamentFile(context, name);
        boolean sharedExists = shared.isFile();
        boolean appExists = app != null && app.isFile();
        // Prefer the app-specific copy on equal timestamps because it is written
        // immediately after a failed public-storage write on coarse-timestamp filesystems.
        if (sharedExists && appExists) return shared.lastModified() > app.lastModified() ? shared : app;
        if (sharedExists) return shared;
        return appExists ? app : null;
    }

    private static void copyToInternal(Context context, File source, String name) throws IOException {
        File temp = new File(context.getFilesDir(), name + ".importing");
        try (FileInputStream in = new FileInputStream(source); FileOutputStream out = context.openFileOutput(temp.getName(), Context.MODE_PRIVATE)) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
            out.getFD().sync();
        }
        File target = new File(context.getFilesDir(), name);
        if (target.exists() && !target.delete()) { temp.delete(); throw new IOException("Could not replace internal tournament file"); }
        if (!temp.renameTo(target)) { temp.delete(); throw new IOException("Could not import tournament file"); }
    }

    private static long savedTimestamp(String name) {
        try {
            String value = name.substring("cet_tournament_".length(), name.length() - 5);
            int suffix = value.indexOf('_');
            if (suffix >= 0) value = value.substring(0, suffix);
            return Long.parseLong(value);
        } catch (RuntimeException e) { return 0L; }
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
        } catch (SecurityException e) {
            throw new IOException("Could not delete external tournament JSON copy", e);
        }
        if (!deleted) throw new IOException("Could not delete saved tournament");
    }

    public synchronized String savedFileName() { return fileName; }

    private void buildSchedule() {
        pairings.clear(); int index = 0;
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

    public synchronized List<Engine> engines() { return Collections.unmodifiableList(new ArrayList<>(engines)); }
    public synchronized String title() { return title; }
    public synchronized Format format() { return format; }
    public synchronized int gamesPerPair() { return gamesPerPair; }
    public synchronized long baseTimeMs() { return baseTimeMs; }
    public synchronized long incrementMs() { return incrementMs; }
    public synchronized TimeMode timeMode() { return timeMode; }
    public synchronized int limitValue() { return limitValue; }
    public synchronized String openingPgn() { return openingPgn; }
    public synchronized String openingBinPath() { return openingBinPath; }
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
    public synchronized void setActivePairing(int index) throws IOException, JSONException {
        Pairing p = pairings.get(index);
        if (p.isComplete() && !p.replayPending) throw new IllegalStateException("Only a pending game can become active");
        activePairingIndex = index;
        save();
    }
    public synchronized void setQueued(int index, boolean queued) throws IOException, JSONException {
        Pairing p = pairings.get(index);
        if (queued) {
            if (p.isComplete() && !p.replayPending) throw new IllegalStateException("Only unfinished games can be queued; use Replay for completed games");
            if (!playQueue.contains(index)) playQueue.add(index);
        } else {
            playQueue.remove(Integer.valueOf(index));
        }
        save();
    }
    /** Queue a replacement play at the head while keeping the old result recoverable until replacement finishes. */
    public synchronized void requestReplay(int index) throws IOException, JSONException {
        Pairing p = pairings.get(index);
        if (!p.isComplete()) throw new IllegalStateException("Only completed games can be replayed");
        if (p.replayPending) throw new IllegalStateException("This game is already waiting to be replayed");
        p.replayPending = true;
        playQueue.remove(Integer.valueOf(index));
        playQueue.add(0, index);
        save();
    }
    public synchronized Pairing current() {
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
    public synchronized boolean isComplete() { return current() == null; }

    /** Initialize stable assignments for every PGN opening (or empty assignments when no book is selected). */
    public synchronized void prepareOpeningAssignments() throws IOException, JSONException {
        boolean changed = false;
        for (Pairing p : pairings) {
            if (p.openingAssigned) continue;
            if (!openingPgn.trim().isEmpty()) {
                assignPgnOpening(p);
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
        if (changed) save();
    }

    private void assignPgnOpeningsInMemory() {
        if (openingPgn.trim().isEmpty()) return;
        for (Pairing p : pairings) if (!p.openingAssigned) assignPgnOpening(p);
    }

    private void assignPgnOpening(Pairing p) {
        int zeroBased = Math.max(0, openingStartIndex - 1) + (repeatOpening ? Math.max(0, p.index / 2) : p.index);
        String selected = PgnOpeningBook.gameAt(openingPgn, zeroBased);
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
    public synchronized void setPairingOpeningIfMissing(int index, List<String> sanMoves, String name) throws IOException, JSONException {
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
            }
        }
        save();
    }

    /** Original PGN date is reused when a scheduled game is replayed, replacing the same record. */
    public synchronized String ensurePairingPgnDate(int index) throws IOException, JSONException {
        Pairing p = pairings.get(index);
        if (p.pgnDate == null || !p.pgnDate.matches("\\d{4}\\.\\d{2}\\.\\d{2}")) {
            p.pgnDate = pgnTagValue(p.pgn, "Date");
            if (!p.pgnDate.matches("\\d{4}\\.\\d{2}\\.\\d{2}")) {
                java.util.Calendar now = java.util.Calendar.getInstance();
                p.pgnDate = String.format(java.util.Locale.US, "%04d.%02d.%02d", now.get(java.util.Calendar.YEAR),
                        now.get(java.util.Calendar.MONTH) + 1, now.get(java.util.Calendar.DAY_OF_MONTH));
            }
            save();
        }
        return p.pgnDate;
    }

    private String pgnTagValue(String pgn, String name) {
        if (pgn == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?m)^\\[" + java.util.regex.Pattern.quote(name) + "\\s+\"((?:\\\\.|[^\"])*?)\"\\]").matcher(pgn);
        return m.find() ? m.group(1).replace("\\\\\"", "\"").replace("\\\\\\\\", "\\\\") : "";
    }
    /** All completed game PGNs in schedule order, separated for concatenated-PGN readers. */
    public synchronized String exportPgn() {
        StringBuilder out = new StringBuilder();
        for (Pairing p : pairings) {
            if (p.isComplete() && p.pgn != null && !p.pgn.trim().isEmpty()) {
                if (out.length() > 0) out.append("\n\n");
                out.append(p.pgn.trim());
            }
        }
        return out.toString();
    }
    public synchronized void recordResult(int index, Outcome outcome, String pgn) throws IOException, JSONException {
        recordResult(index, outcome, pgn, Double.NaN, Double.NaN);
    }
    public synchronized void recordResult(int index, Outcome outcome, String pgn,
                                          double whiteEvalPawns, double blackEvalPawns) throws IOException, JSONException {
        if (outcome == null || outcome == Outcome.UNFINISHED) throw new IllegalArgumentException("A final result is required");
        Pairing p = pairings.get(index);
        if (p.isComplete() && !p.replayPending) throw new IllegalStateException("Pairing already has a result");
        p.outcome = outcome; p.pgn = pgn == null ? "" : pgn;
        p.whiteEvalPawns = Double.isFinite(whiteEvalPawns) ? whiteEvalPawns : Double.NaN;
        p.blackEvalPawns = Double.isFinite(blackEvalPawns) ? blackEvalPawns : Double.NaN;
        p.replayPending = false;
        p.anomalyReviewed = false;
        playQueue.remove(Integer.valueOf(index));
        if (activePairingIndex == index) activePairingIndex = -1;
        if (p.pgnDate == null || p.pgnDate.isEmpty()) p.pgnDate = pgnTagValue(p.pgn, "Date");
        save();
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
    public synchronized void markAnomalyReviewed(int index) throws IOException, JSONException {
        Pairing p = pairings.get(index);
        if (!p.isComplete() || p.replayPending) throw new IllegalStateException("An unfinished or queued-replay game cannot be confirmed");
        p.anomalyReviewed = true;
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
        p.whiteEngineId = whiteId; p.blackEngineId = blackId;
        save();
    }

    /** Correct a recorded result; standings are always recalculated from the game records. */
    public synchronized void updatePairingResult(int index, Outcome outcome) throws IOException, JSONException {
        if (outcome == null || outcome == Outcome.UNFINISHED) throw new IllegalArgumentException("Choose a final result");
        Pairing p = pairings.get(index);
        if (!p.isComplete() || p.replayPending) throw new IllegalStateException("An unfinished or queued-replay game cannot have its result corrected");
        p.outcome = outcome;
        p.pgn = replacePgnResult(p.pgn, outcome);
        p.anomalyReviewed = true;
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
        Collections.sort(table, (a,b) -> Double.compare(b.points, a.points));
        return table;
    }
    private Standing find(List<Standing> list, String id) { for (Standing s : list) if (s.engineId.equals(id)) return s; throw new IllegalStateException("Unknown engine " + id); }

    public synchronized String externalSaveError() { return externalSaveError; }

    public synchronized void save() throws IOException, JSONException {
        JSONObject root = new JSONObject(); root.put("title", title); root.put("format", format.name()); root.put("gamesPerPair", gamesPerPair);
        root.put("baseTimeMs", baseTimeMs); root.put("incrementMs", incrementMs);
        root.put("timeMode", timeMode.name()); root.put("limitValue", limitValue);
        root.put("openingPgn", openingPgn); root.put("openingBinPath", openingBinPath); root.put("openingPlies", openingPlies); root.put("openingStartIndex", openingStartIndex); root.put("repeatOpening", repeatOpening); root.put("tournamentElapsedMs", tournamentElapsedMs);
        root.put("anomalyRuleSettingsSaved", anomalyRuleSettingsSaved); root.put("anomalyRulesEnabled", anomalyRulesEnabled);
        root.put("anomalyRuleStartFullMove", anomalyRuleStartFullMove); root.put("anomalyMaximumFullMoves", anomalyMaximumFullMoves);
        JSONArray ea = new JSONArray(); for (Engine e : engines) { JSONObject o = new JSONObject(); o.put("id", e.id); o.put("name", e.displayName); ea.put(o); } root.put("engines", ea);
        JSONArray pa = new JSONArray(); for (Pairing p : pairings) { JSONObject o = new JSONObject(); o.put("index", p.index); o.put("round", p.round); o.put("gameNumber", p.gameNumber); o.put("white", p.whiteEngineId); o.put("black", p.blackEngineId); o.put("outcome", p.outcome.name()); o.put("pgn", p.pgn); o.put("whiteEvalPawns", Double.isFinite(p.whiteEvalPawns) ? p.whiteEvalPawns : JSONObject.NULL); o.put("blackEvalPawns", Double.isFinite(p.blackEvalPawns) ? p.blackEvalPawns : JSONObject.NULL); o.put("anomalyReviewed", p.anomalyReviewed); o.put("replayPending", p.replayPending); o.put("openingAssigned", p.openingAssigned); o.put("openingIndex", p.openingIndex); o.put("openingName", p.openingName); o.put("openingSanLine", p.openingSanLine); o.put("pgnDate", p.pgnDate); pa.put(o); } root.put("pairings", pa);
        JSONArray queue = new JSONArray(); for (Integer index : playQueue) queue.put(index); root.put("playQueue", queue);
        root.put("activePairingIndex", activePairingIndex);
        byte[] bytes = root.toString().getBytes(Charset.forName("UTF-8"));
        // The private copy remains the authoritative recovery copy and is always written first.
        try (FileOutputStream out = context.openFileOutput(fileName, Context.MODE_PRIVATE)) {
            out.write(bytes); out.flush(); out.getFD().sync();
        }
        // Mirror the exact same snapshot to shared DroidFish storage after every save(),
        // including each completed game and the periodic elapsed-time checkpoint.
        externalSaveError = "";
        try {
            writeAtomic(externalTournamentFile(context, fileName), bytes);
            // If an earlier save used the app-specific fallback, refresh that copy too
            // so a later restore can never select a stale mirror because of timestamp ties.
            File fallback = appExternalTournamentFile(context, fileName);
            if (fallback != null && fallback.isFile()) {
                try { writeAtomic(fallback, bytes); }
                catch (IOException | SecurityException staleFallbackError) {
                    try { fallback.delete(); } catch (SecurityException ignored) { }
                }
            }
        } catch (IOException | SecurityException sharedError) {
            try {
                File fallback = appExternalTournamentFile(context, fileName);
                if (fallback == null) throw new IOException("App external storage is unavailable");
                writeAtomic(fallback, bytes);
                externalSaveError = "Shared DroidFish/tournament folder unavailable; backup saved in " + fallback.getAbsolutePath();
            } catch (IOException | SecurityException fallbackError) {
                externalSaveError = "Could not mirror tournament JSON to shared or app external storage: " + fallbackError.getMessage();
            }
        }
    }

    private static void writeAtomic(File target, byte[] bytes) throws IOException {
        File dir = target.getParentFile();
        if (dir == null || (!dir.exists() && !dir.mkdirs() && !dir.isDirectory()))
            throw new IOException("Cannot create tournament folder");
        File temp = new File(dir, target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp, false)) {
            out.write(bytes); out.flush(); out.getFD().sync();
        }
        if (!temp.renameTo(target)) {
            // Some filesystems do not replace an existing destination via renameTo.
            File backup = new File(dir, target.getName() + ".bak");
            if (backup.exists() && !backup.delete()) { temp.delete(); throw new IOException("Cannot replace tournament backup"); }
            if (target.exists() && !target.renameTo(backup)) { temp.delete(); throw new IOException("Cannot rotate previous tournament JSON"); }
            if (!temp.renameTo(target)) {
                if (backup.exists()) backup.renameTo(target);
                temp.delete(); throw new IOException("Cannot publish tournament JSON");
            }
            if (backup.exists()) backup.delete();
        }
    }
}
