package org.petero.droidfish.tournament;

import android.content.Context;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Versioned modular storage for active tournaments and immutable completed archives.
 * Legacy compressed JSON remains a restore/migration fallback for events created by
 * earlier app versions. A committed generation is authoritative for new saves.
 */
final class TournamentStorage {
    private static final int SCHEMA_VERSION = 1;
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final ExecutorService STORAGE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "tournament-modular-storage");
        thread.setDaemon(true);
        // Modular snapshots are a background mirror; never compete aggressively
        // with the chess-engine workers on a mobile device.
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private static final Map<String, Long> LATEST_QUEUED_REVISIONS = new HashMap<>();
    private static final String[] SETTINGS_KEYS = {
            "title", "format", "gamesPerPair", "baseTimeMs", "incrementMs", "timeMode", "limitValue",
            "ratingEnabled", "ratingShowDecimals", "ratingKFactor", "nextRatingSequence", "initialRatings",
            "openingPgn", "openingPgnFileName", "openingBinPath", "openingPlies", "openingStartIndex",
            "repeatOpening", "startPositionMode", "chess960Enabled", "chess960StartId", "startingPositionSeed",
            "openingFen", "tournamentElapsedMs", "anomalyRuleSettingsSaved", "anomalyRulesEnabled",
            "anomalyRuleStartFullMove", "anomalyMaximumFullMoves", "engines", "swissRoundsTotal",
            "swissCurrentRound", "swissSeedingMode", "swissRandomSeed", "swissStrengthOrder", "swissByes",
            "swissMatches", "cupSeedingMode", "cupRandomSeed", "cupBracketSize", "cupCurrentStage",
            "cupCheckpointPending", "cupFinished", "cupNextMatchNumber", "cupNextGameNumber",
            "cupPgnFileBaseName", "cupStrengthOrder", "cupSeedSlots", "cupMatches"
    };
    private static final String[] SCHEDULE_PAIR_KEYS = {
            "index", "round", "gameNumber", "white", "black", "openingAssigned", "openingIndex",
            "openingName", "openingSanLine", "pgnDate", "cupMatchNumber", "cupPairNumber", "cupGameInPair",
            "whiteNameAtPlay", "blackNameAtPlay"
    };

    private TournamentStorage() { }

    static void enqueue(Context context, String sourceFileName, JSONObject sourceRoot, long revision,
                        JSONArray standingsSnapshot) {
        if (context == null || sourceRoot == null) return;
        final Context app = context.getApplicationContext();
        // These objects are newly created immutable snapshots by TournamentManager.save().
        // Clone/serialize them on the storage worker, not on the UI or result-finalizer thread.
        final JSONObject rootSnapshot = sourceRoot;
        final JSONArray standingsSnapshotRef = standingsSnapshot;
        final String key = safeBaseName(sourceFileName);
        synchronized (LATEST_QUEUED_REVISIONS) {
            Long latest = LATEST_QUEUED_REVISIONS.get(key);
            if (latest == null || revision > latest) LATEST_QUEUED_REVISIONS.put(key, revision);
        }
        STORAGE_EXECUTOR.execute(() -> {
            synchronized (key.intern()) {
                synchronized (LATEST_QUEUED_REVISIONS) {
                    Long latest = LATEST_QUEUED_REVISIONS.get(key);
                    if (latest != null && revision < latest) return;
                }
                try {
                    JSONObject immutableSnapshot = new JSONObject(rootSnapshot.toString());
                    JSONArray immutableStandings = standingsSnapshotRef == null ? new JSONArray() :
                            new JSONArray(standingsSnapshotRef.toString());
                    sync(app, sourceFileName, immutableSnapshot, Math.max(revision, System.currentTimeMillis()), immutableStandings);
                } catch (Exception error) {
                    TournamentDiagnostics.recordError("STORAGE", "modular_snapshot_write_failed", error);
                }
            }
        });
    }

    static void sync(Context context, String sourceFileName, JSONObject sourceRoot, JSONArray standingsSnapshot) throws IOException, JSONException {
        sync(context, sourceFileName, sourceRoot, System.currentTimeMillis(), standingsSnapshot);
    }

    private static void sync(Context context, String sourceFileName, JSONObject sourceRoot, long generation,
                             JSONArray standingsSnapshot) throws IOException, JSONException {
        if (context == null || sourceRoot == null) return;
        File directory = tournamentDirectory(context, sourceFileName);
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory())
            throw new IOException("Cannot create modular tournament directory: " + directory);
        String sourceSignature = signature(sourceRoot);

        JSONObject settings = new JSONObject();
        for (String key : SETTINGS_KEYS) if (sourceRoot.has(key)) settings.put(key, sourceRoot.get(key));
        JSONObject tournament = envelope(sourceFileName, generation, sourceSignature);
        tournament.put("settings", settings);

        JSONArray sourcePairings = sourceRoot.optJSONArray("pairings");
        JSONArray schedulePairings = new JSONArray();
        JSONArray completedResults = new JSONArray();
        JSONArray graphPoints = new JSONArray();
        Map<String, JSONObject> headToHead = new HashMap<>();

        if (sourcePairings != null) {
            for (int i = 0; i < sourcePairings.length(); i++) {
                JSONObject pairing = sourcePairings.optJSONObject(i);
                if (pairing == null) continue;
                JSONObject scheduled = new JSONObject();
                for (String key : SCHEDULE_PAIR_KEYS) if (pairing.has(key)) scheduled.put(key, pairing.get(key));
                schedulePairings.put(scheduled);

                String outcome = pairing.optString("outcome", "UNFINISHED");
                String white = pairing.optString("white", "");
                String black = pairing.optString("black", "");
                if ("UNFINISHED".equals(outcome) || white.isEmpty() || black.isEmpty()) continue;
                JSONObject result = new JSONObject();
                copyIfPresent(pairing, result, "index"); copyIfPresent(pairing, result, "round");
                copyIfPresent(pairing, result, "gameNumber"); copyIfPresent(pairing, result, "white");
                copyIfPresent(pairing, result, "black"); copyIfPresent(pairing, result, "outcome");
                copyIfPresent(pairing, result, "pgn"); copyIfPresent(pairing, result, "whiteEvalPawns");
                copyIfPresent(pairing, result, "blackEvalPawns"); copyIfPresent(pairing, result, "whiteNameAtPlay");
                copyIfPresent(pairing, result, "blackNameAtPlay"); copyIfPresent(pairing, result, "anomalyReviewed");
                completedResults.put(result);

                double whiteScore = "WHITE_WIN".equals(outcome) ? 1.0 : ("DRAW".equals(outcome) ? 0.5 : 0.0);
                double blackScore = 1.0 - whiteScore;

                JSONObject point = new JSONObject();
                point.put("gameIndex", pairing.optInt("index", i)); point.put("round", pairing.optInt("round", 1));
                point.put("white", white); point.put("black", black); point.put("outcome", outcome);
                point.put("whiteScore", whiteScore); point.put("blackScore", blackScore);
                copyIfPresent(pairing, point, "whiteEvalPawns"); copyIfPresent(pairing, point, "blackEvalPawns");
                graphPoints.put(point);

                String first = white.compareTo(black) <= 0 ? white : black;
                String second = white.compareTo(black) <= 0 ? black : white;
                String pairKey = first + "\u0000" + second;
                JSONObject match = headToHead.get(pairKey);
                if (match == null) {
                    match = new JSONObject(); match.put("engineA", first); match.put("engineB", second);
                    match.put("scoreA", 0.0); match.put("scoreB", 0.0); match.put("games", new JSONArray());
                    headToHead.put(pairKey, match);
                }
                boolean whiteIsA = first.equals(white);
                match.put("scoreA", match.optDouble("scoreA", 0.0) + (whiteIsA ? whiteScore : blackScore));
                match.put("scoreB", match.optDouble("scoreB", 0.0) + (whiteIsA ? blackScore : whiteScore));
                JSONArray games = match.optJSONArray("games");
                JSONObject gameRef = new JSONObject(); gameRef.put("gameIndex", pairing.optInt("index", i));
                gameRef.put("round", pairing.optInt("round", 1)); gameRef.put("outcome", outcome); games.put(gameRef);
            }
        }

        JSONObject schedule = envelope(sourceFileName, generation, sourceSignature);
        schedule.put("pairings", schedulePairings);
        schedule.put("playQueue", sourceRoot.optJSONArray("playQueue") == null ? new JSONArray() : sourceRoot.optJSONArray("playQueue"));
        schedule.put("activePairingIndex", sourceRoot.optInt("activePairingIndex", -1));

        JSONObject resultsHeader = envelope(sourceFileName, generation, sourceSignature);
        resultsHeader.put("recordType", "snapshot_header");

        JSONArray standingRows = standingsSnapshot == null ? new JSONArray() : new JSONArray(standingsSnapshot.toString());
        JSONObject standingsJson = envelope(sourceFileName, generation, sourceSignature);
        standingsJson.put("standings", standingRows);
        standingsJson.put("swissByes", sourceRoot.optJSONArray("swissByes") == null ? new JSONArray() : sourceRoot.optJSONArray("swissByes"));

        JSONArray matchRows = new JSONArray();
        for (JSONObject match : headToHead.values()) matchRows.put(match);
        JSONObject crosstable = envelope(sourceFileName, generation, sourceSignature);
        crosstable.put("matches", matchRows);

        JSONObject graphs = envelope(sourceFileName, generation, sourceSignature);
        graphs.put("points", graphPoints);

        writeJson(new File(directory, "tournament.json"), tournament);
        writeJson(new File(directory, "schedule.json"), schedule);
        writeResultsJsonl(new File(directory, "results.jsonl"), resultsHeader, completedResults);
        writeJson(new File(directory, "standings.json"), standingsJson);
        writeJson(new File(directory, "crosstable.json"), crosstable);
        writeJson(new File(directory, "graphs.json"), graphs);
        writeBundle(directory, safeBaseName(sourceFileName) + ".zip");
    }

    /** Validate/rebuild derived modules on a worker; never inflate or rewrite bundles on the UI thread. */
    static void rebuildIfMissing(Context context, String sourceFileName, JSONObject sourceRoot, JSONArray standingsSnapshot) {
        if (context == null || sourceRoot == null) return;
        final Context app = context.getApplicationContext();
        // The restored root/standings are no longer mutated after TournamentManager
        // construction. Clone and validate them on the background storage executor.
        final JSONObject rootSnapshot = sourceRoot;
        final JSONArray standingsSnapshotRef = standingsSnapshot;
        final String key = safeBaseName(sourceFileName);
        STORAGE_EXECUTOR.execute(() -> {
            synchronized (key.intern()) {
                try {
                    JSONObject immutableRoot = new JSONObject(rootSnapshot.toString());
                    JSONArray immutableStandings = standingsSnapshotRef == null ? new JSONArray() :
                            new JSONArray(standingsSnapshotRef.toString());
                    File directory = tournamentDirectory(app, sourceFileName);
                    String[] required = {"tournament.json", "schedule.json", "results.jsonl", "standings.json", "crosstable.json", "graphs.json", key + ".zip"};
                    boolean missing = !directory.isDirectory();
                    if (!missing) for (String name : required) if (!new File(directory, name).isFile()) { missing = true; break; }
                    if (missing) sync(app, sourceFileName, immutableRoot, System.currentTimeMillis(), immutableStandings);
                    else validateOrRebuild(app, sourceFileName, immutableRoot, immutableStandings, directory);
                } catch (Exception error) {
                    TournamentDiagnostics.recordError("STORAGE", "modular_snapshot_rebuild_failed", error);
                }
            }
        });
    }

    private static void validateOrRebuild(Context context, String sourceFileName, JSONObject root,
                                          JSONArray standingsSnapshot, File directory) {
        try {
            JSONObject module = readJson(new File(directory, "tournament.json"));
            JSONObject schedule = readJson(new File(directory, "schedule.json"));
            JSONObject standings = readJson(new File(directory, "standings.json"));
            JSONObject crosstable = readJson(new File(directory, "crosstable.json"));
            JSONObject graphs = readJson(new File(directory, "graphs.json"));
            long generation = module.optLong("sourceGeneration", -1);
            String sourceSignature = signature(root);
            if (generation < 0 || module.optInt("schemaVersion", -1) != SCHEMA_VERSION ||
                    schedule.optInt("schemaVersion", -1) != SCHEMA_VERSION ||
                    standings.optInt("schemaVersion", -1) != SCHEMA_VERSION ||
                    crosstable.optInt("schemaVersion", -1) != SCHEMA_VERSION ||
                    graphs.optInt("schemaVersion", -1) != SCHEMA_VERSION ||
                    !sourceSignature.equals(module.optString("sourceSignature", "")) ||
                    !sourceSignature.equals(schedule.optString("sourceSignature", "")) ||
                    !sourceSignature.equals(standings.optString("sourceSignature", "")) ||
                    !sourceSignature.equals(crosstable.optString("sourceSignature", "")) ||
                    !sourceSignature.equals(graphs.optString("sourceSignature", "")) ||
                    schedule.optLong("sourceGeneration", -2) != generation ||
                    standings.optLong("sourceGeneration", -3) != generation ||
                    crosstable.optLong("sourceGeneration", -4) != generation ||
                    graphs.optLong("sourceGeneration", -5) != generation) {
                sync(context, sourceFileName, root, System.currentTimeMillis(), standingsSnapshot);
                return;
            }
            validateResultsJsonl(new File(directory, "results.jsonl"), sourceSignature, generation);
            // A bundle is valid only when its entries are readable and belong to this exact snapshot.
            File zip = new File(directory, safeBaseName(sourceFileName) + ".zip");
            try (ZipFile archive = new ZipFile(zip)) {
                String[] names = {"tournament.json", "schedule.json", "results.jsonl", "standings.json", "crosstable.json", "graphs.json"};
                for (String name : names) {
                    ZipEntry entry = archive.getEntry(name);
                    if (entry == null) throw new IOException("Missing ZIP entry " + name);
                    try (java.io.InputStream in = archive.getInputStream(entry)) {
                        if ("results.jsonl".equals(name)) {
                            validateResultsJsonl(in, sourceSignature, generation);
                        } else {
                            try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                                byte[] buffer = new byte[8192]; int read;
                                while ((read = in.read(buffer)) != -1) bytes.write(buffer, 0, read);
                                JSONObject zippedModule = new JSONObject(new String(bytes.toByteArray(), UTF8));
                                if (zippedModule.optInt("schemaVersion", -1) != SCHEMA_VERSION ||
                                        zippedModule.optLong("sourceGeneration", -1) != generation ||
                                        !sourceSignature.equals(zippedModule.optString("sourceSignature", "")))
                                    throw new IOException("Stale ZIP module " + name);
                            }
                        }
                    }
                }
            }
        } catch (Exception damaged) {
            TournamentDiagnostics.recordError("STORAGE", "modular_snapshot_damaged_rebuilding", damaged);
            try { sync(context, sourceFileName, root, System.currentTimeMillis(), standingsSnapshot); }
            catch (Exception rebuildError) { TournamentDiagnostics.recordError("STORAGE", "modular_snapshot_rebuild_failed", rebuildError); }
        }
    }


    // Stage 2 authoritative storage: each active tournament owns a versioned
    // directory; completed tournaments are immutable ZIP archives.
    private static final String ACTIVE_FOLDER = "active";
    private static final String COMPLETED_FOLDER = "completed";

    static boolean hasActive(Context context, String fileName) {
        File active = activeTournamentDirectory(context, fileName);
        return new File(active, "current.json").isFile() ||
                new File(active, "current.json.previous.bak").isFile();
    }

    static boolean hasCompleted(Context context, String fileName) {
        return new File(completedTournamentDirectory(context, fileName), safeBaseName(fileName) + ".zip").isFile();
    }

    static boolean tournamentExists(Context context, String fileName) {
        return hasActive(context, fileName) || hasCompleted(context, fileName);
    }

    static List<String> activeTournamentFiles(Context context) {
        ArrayList<String> names = new ArrayList<>();
        File root = new File(tournamentsRoot(context), ACTIVE_FOLDER);
        File[] entries = root.listFiles();
        if (entries != null) for (File entry : entries) {
            if (!entry.isDirectory() || (!new File(entry, "current.json").isFile() &&
                    !new File(entry, "current.json.previous.bak").isFile())) continue;
            try {
                JSONObject pointer = readCurrentPointer(entry);
                String fileName = pointer.optString("sourceFile", "");
                if (!fileName.isEmpty()) names.add(fileName);
            } catch (Exception error) {
                TournamentDiagnostics.recordError("STORAGE", "active_manifest_invalid " + entry.getName(), error);
            }
        }
        Collections.sort(names, (a, b) -> Long.compare(
                activeModified(context, b), activeModified(context, a)));
        return names;
    }

    static List<String> completedTournamentFiles(Context context) {
        ArrayList<String> names = new ArrayList<>();
        File root = new File(tournamentsRoot(context), COMPLETED_FOLDER);
        File[] entries = root.listFiles();
        if (entries != null) for (File entry : entries) {
            if (!entry.isFile() || !entry.getName().endsWith(".zip")) continue;
            String base = entry.getName().substring(0, entry.getName().length() - 4);
            names.add(base + ".json.gz");
        }
        Collections.sort(names, (a, b) -> Long.compare(
                new File(completedTournamentDirectory(context, b), safeBaseName(b) + ".zip").lastModified(),
                new File(completedTournamentDirectory(context, a), safeBaseName(a) + ".zip").lastModified()));
        return names;
    }

    private static long activeModified(Context context, String fileName) {
        File directory = activeTournamentDirectory(context, fileName);
        File pointer = new File(directory, "current.json");
        if (!pointer.isFile()) pointer = new File(directory, "current.json.previous.bak");
        return pointer.isFile() ? pointer.lastModified() : directory.lastModified();
    }

    /**
     * Commit one immutable generation. The current.json pointer is the single
     * publication point, so a crash during module writes leaves the previous
     * generation readable. Keep the current and one previous generation.
     */
    static void saveActive(Context context, String sourceFileName, JSONObject sourceRoot,
                           JSONArray standingsSnapshot, Map<Integer, String> pgnContents,
                           Set<Integer> changedPgnIndexes) throws IOException, JSONException {
        if (context == null || sourceRoot == null) throw new IOException("Tournament storage is unavailable");
        final long commitStartedNs = System.nanoTime();
        File active = activeTournamentDirectory(context, sourceFileName);
        if (!active.exists() && !active.mkdirs() && !active.isDirectory())
            throw new IOException("Cannot create active tournament directory: " + active);
        long signatureStartedNs = System.nanoTime();
        String sourceSignature = signature(sourceRoot);
        long signatureBuildMs = (System.nanoTime() - signatureStartedNs) / 1_000_000L;
        try {
            // Only skip publication when the primary pointer itself is valid.
            // If recovery is currently relying on the backup pointer, let the
            // normal commit path repair current.json rather than preserving a
            // potentially damaged primary pointer indefinitely.
            File primaryPointer = new File(active, "current.json");
            if (!primaryPointer.isFile()) throw new IOException("Missing primary pointer");
            JSONObject committed = readJson(primaryPointer);
            if ((changedPgnIndexes == null || changedPgnIndexes.isEmpty()) &&
                    sourceSignature.equals(committed.optString("sourceSignature", ""))) {
                String primaryGenerationName = committed.optString("generationDirectory", "");
                if (primaryGenerationName.isEmpty() || primaryGenerationName.contains("/") ||
                        primaryGenerationName.contains("\\"))
                    throw new IOException("Invalid primary tournament generation pointer");
                File primaryGeneration = new File(active, primaryGenerationName);
                validateGeneration(primaryGeneration, committed.optString("sourceSignature", ""),
                        committed.optLong("sourceGeneration", -1L));
                // Existing PGN files were validated when the previous generation was
                // published. A metadata-only no-op must not stat every historical PGN.
                String committedName = primaryGenerationName;
                if (!committedName.isEmpty() && !committedName.contains("/") && !committedName.contains("\\")) {
                    JSONObject storedStandings = readJson(new File(new File(active, committedName), "standings.json"));
                    JSONArray storedRows = storedStandings.optJSONArray("standings");
                    JSONArray requestedRows = standingsSnapshot == null ? new JSONArray() : standingsSnapshot;
                    if (storedRows != null && storedRows.toString().equals(requestedRows.toString())) {
                        TournamentDiagnostics.record("STORAGE", "unchanged_generation_skipped",
                                "tournament=" + safeBaseName(sourceFileName) + " generation=" +
                                        committed.optLong("sourceGeneration", -1L));
                        return;
                    }
                }
            }
        } catch (Exception invalidCommittedSnapshot) {
            // Missing or damaged current/backup pointers are deliberately rebuilt
            // by the normal validated generation-publication path below.
        }
        long generation = currentGeneration(active) + 1L;
        String generationName = String.format(java.util.Locale.US, "generation-%08d", generation);
        long generationBuildStartedNs = System.nanoTime();
        File staging = new File(active, "." + generationName + ".tmp");
        File publishedGeneration = new File(active, generationName);
        deleteRecursively(staging);
        if (!staging.mkdirs()) throw new IOException("Cannot create tournament generation directory");

        JSONObject tournament = envelope(sourceFileName, generation, sourceSignature);
        tournament.put("tournamentId", safeBaseName(sourceFileName));
        JSONObject settings = new JSONObject();
        for (String key : SETTINGS_KEYS) if (sourceRoot.has(key)) settings.put(key, sourceRoot.get(key));
        tournament.put("settings", settings);

        JSONArray sourcePairings = sourceRoot.optJSONArray("pairings");
        JSONArray schedulePairings = new JSONArray();
        JSONArray resultRecords = new JSONArray();
        JSONArray graphPoints = new JSONArray();
        Map<String, JSONObject> headToHead = new HashMap<>();
        Map<Integer, String> previousPgnFiles = readActivePgnReferences(active);
        File pgnDirectory = new File(active, "pgn");
        if (!pgnDirectory.mkdirs() && !pgnDirectory.isDirectory())
            throw new IOException("Cannot create active tournament PGN directory");

        if (sourcePairings != null) for (int i = 0; i < sourcePairings.length(); i++) {
            JSONObject pairing = sourcePairings.optJSONObject(i);
            if (pairing == null) throw new IOException("Invalid pairing record at index " + i);
            JSONObject scheduled = new JSONObject(pairing.toString());
            scheduled.remove("pgn");
            int pairingIndex = pairing.optInt("index", i);
            String pgn = pgnContents == null ? "" : pgnContents.get(pairingIndex);
            if (pgn == null) pgn = "";
            String pgnFile = "";
            if (!pgn.isEmpty()) {
                String previousFile = previousPgnFiles.get(pairingIndex);
                boolean changed = changedPgnIndexes == null || changedPgnIndexes.contains(pairingIndex);
                if (!changed && previousFile != null && !previousFile.isEmpty()) {
                    pgnFile = previousFile;
                } else {
                    byte[] pgnBytes = pgn.getBytes(UTF8);
                    String pgnName = contentAddressedPgnName(pairingIndex, pgnBytes);
                    pgnFile = "pgn/" + pgnName;
                    writeRawFileIfAbsent(new File(pgnDirectory, pgnName), pgnBytes);
                }
            }
            scheduled.put("pgnFile", pgnFile);
            scheduled.remove("outcome");
            scheduled.remove("whiteEvalPawns");
            scheduled.remove("blackEvalPawns");
            scheduled.remove("anomalyReviewed");
            scheduled.remove("ratingSequence");
            schedulePairings.put(scheduled);

            String outcome = pairing.optString("outcome", "UNFINISHED");
            String white = pairing.optString("white", "");
            String black = pairing.optString("black", "");
            if ("UNFINISHED".equals(outcome) || white.isEmpty() || black.isEmpty()) continue;

            JSONObject result = new JSONObject();
            copyIfPresent(pairing, result, "index");
            copyIfPresent(pairing, result, "outcome");
            copyIfPresent(pairing, result, "whiteEvalPawns");
            copyIfPresent(pairing, result, "blackEvalPawns");
            copyIfPresent(pairing, result, "anomalyReviewed");
            copyIfPresent(pairing, result, "ratingSequence");
            result.put("pgnFile", pgnFile);
            resultRecords.put(result);

            double whiteScore = "WHITE_WIN".equals(outcome) ? 1.0 : ("DRAW".equals(outcome) ? 0.5 : 0.0);
            double blackScore = 1.0 - whiteScore;
            JSONObject graph = new JSONObject();
            graph.put("gameIndex", pairing.optInt("index", i));
            graph.put("round", pairing.optInt("round", 1));
            graph.put("white", white); graph.put("black", black); graph.put("outcome", outcome);
            graph.put("whiteScore", whiteScore); graph.put("blackScore", blackScore);
            copyIfPresent(pairing, graph, "whiteEvalPawns");
            copyIfPresent(pairing, graph, "blackEvalPawns");
            graphPoints.put(graph);

            String first = white.compareTo(black) <= 0 ? white : black;
            String second = white.compareTo(black) <= 0 ? black : white;
            String pairKey = first + "\u0000" + second;
            JSONObject match = headToHead.get(pairKey);
            if (match == null) {
                match = new JSONObject(); match.put("engineA", first); match.put("engineB", second);
                match.put("scoreA", 0.0); match.put("scoreB", 0.0); match.put("games", new JSONArray());
                headToHead.put(pairKey, match);
            }
            boolean whiteIsA = first.equals(white);
            match.put("scoreA", match.optDouble("scoreA", 0.0) + (whiteIsA ? whiteScore : blackScore));
            match.put("scoreB", match.optDouble("scoreB", 0.0) + (whiteIsA ? blackScore : whiteScore));
            JSONObject gameRef = new JSONObject(); gameRef.put("gameIndex", pairing.optInt("index", i));
            gameRef.put("round", pairing.optInt("round", 1)); gameRef.put("outcome", outcome);
            match.getJSONArray("games").put(gameRef);
        }

        JSONObject schedule = envelope(sourceFileName, generation, sourceSignature);
        schedule.put("pairings", schedulePairings);
        schedule.put("playQueue", sourceRoot.optJSONArray("playQueue") == null ? new JSONArray() : sourceRoot.optJSONArray("playQueue"));
        schedule.put("activePairingIndex", sourceRoot.optInt("activePairingIndex", -1));
        JSONObject resultsHeader = envelope(sourceFileName, generation, sourceSignature);
        resultsHeader.put("recordType", "snapshot_header");
        JSONObject standings = envelope(sourceFileName, generation, sourceSignature);
        standings.put("standings", standingsSnapshot == null ? new JSONArray() : new JSONArray(standingsSnapshot.toString()));
        standings.put("swissByes", sourceRoot.optJSONArray("swissByes") == null ? new JSONArray() : sourceRoot.optJSONArray("swissByes"));
        JSONArray matches = new JSONArray();
        for (JSONObject match : headToHead.values()) matches.put(match);
        JSONObject crosstable = envelope(sourceFileName, generation, sourceSignature);
        crosstable.put("matches", matches);
        JSONObject graphs = envelope(sourceFileName, generation, sourceSignature);
        graphs.put("points", graphPoints);
        long generationBuildMs = (System.nanoTime() - generationBuildStartedNs) / 1_000_000L;

        long moduleWriteStartedNs = System.nanoTime();
        writeJson(new File(staging, "tournament.json"), tournament);
        writeJson(new File(staging, "schedule.json"), schedule);
        writeResultsJsonl(new File(staging, "results.jsonl"), resultsHeader, resultRecords);
        writeJson(new File(staging, "standings.json"), standings);
        writeJson(new File(staging, "crosstable.json"), crosstable);
        writeJson(new File(staging, "graphs.json"), graphs);
        validateGeneration(staging, sourceSignature, generation);
        validatePgnReferencesForCommit(staging, active, previousPgnFiles, changedPgnIndexes);
        long moduleWriteAndValidationMs = (System.nanoTime() - moduleWriteStartedNs) / 1_000_000L;
        if (publishedGeneration.exists()) deleteRecursively(publishedGeneration);
        if (!staging.renameTo(publishedGeneration)) throw new IOException("Cannot publish tournament generation");

        JSONObject pointer = envelope(sourceFileName, generation, sourceSignature);
        pointer.put("sourceFile", sourceFileName);
        pointer.put("generationDirectory", generationName);
        long pointerPublishStartedNs = System.nanoTime();
        writeJson(new File(active, "current.json"), pointer);
        long pointerPublishMs = (System.nanoTime() - pointerPublishStartedNs) / 1_000_000L;
        long pruneStartedNs = System.nanoTime();
        pruneGenerations(active, generationName);
        prunePgnFiles(active);
        long pruneMs = (System.nanoTime() - pruneStartedNs) / 1_000_000L;
        TournamentDiagnostics.record("STORAGE", "active_generation_commit_timing",
                "tournament=" + safeBaseName(sourceFileName) + " generation=" + generation +
                        " totalMs=" + ((System.nanoTime() - commitStartedNs) / 1_000_000L) +
                        " signatureMs=" + signatureBuildMs + " generationBuildMs=" + generationBuildMs +
                        " moduleWriteValidateMs=" + moduleWriteAndValidationMs +
                        " pointerPublishMs=" + pointerPublishMs + " pruneMs=" + pruneMs);
    }

    private static boolean isCompletedGeneration(File generation) throws IOException, JSONException {
        JSONObject schedule = readJson(new File(generation, "schedule.json"));
        JSONArray pairings = schedule.optJSONArray("pairings");
        if (pairings == null || pairings.length() == 0) return false;
        HashSet<Integer> completed = new HashSet<>();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                new FileInputStream(new File(generation, "results.jsonl")), UTF8))) {
            String line = reader.readLine();
            if (line == null) return false;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                JSONObject result = new JSONObject(line);
                if (!"UNFINISHED".equals(result.optString("outcome", "UNFINISHED")))
                    completed.add(result.optInt("index", -1));
            }
        }
        for (int i = 0; i < pairings.length(); i++) {
            JSONObject pairing = pairings.optJSONObject(i);
            if (pairing == null || pairing.optBoolean("replayPending", false) ||
                    !completed.contains(pairing.optInt("index", i))) return false;
        }
        JSONObject tournament = readJson(new File(generation, "tournament.json"));
        JSONObject settings = tournament.optJSONObject("settings");
        return settings == null || !"CUP".equals(settings.optString("format", "")) ||
                settings.optBoolean("cupFinished", false);
    }

    private static Map<Integer, String> readActivePgnReferences(File active) {
        HashMap<Integer, String> references = new HashMap<>();
        File[] candidates = {new File(active, "current.json"),
                new File(active, "current.json.previous.bak")};
        Exception lastError = null;
        for (File pointerFile : candidates) {
            if (!pointerFile.isFile()) continue;
            try {
                JSONObject pointer = readJson(pointerFile);
                String generationName = pointer.optString("generationDirectory", "");
                if (!generationName.matches("generation-[0-9]{8}"))
                    throw new IOException("Invalid active tournament generation pointer");
                JSONObject schedule = readJson(new File(new File(active, generationName), "schedule.json"));
                JSONArray pairings = schedule.optJSONArray("pairings");
                if (pairings == null) throw new IOException("Active schedule module has no pairings");
                for (int i = 0; i < pairings.length(); i++) {
                    JSONObject pairing = pairings.optJSONObject(i);
                    if (pairing == null) continue;
                    String pgnFile = pairing.optString("pgnFile", "");
                    if (!pgnFile.isEmpty()) {
                        validatePgnRelativePath(pgnFile);
                        references.put(pairing.optInt("index", i), pgnFile);
                    }
                }
                return references;
            } catch (Exception invalidPriorSchedule) {
                lastError = invalidPriorSchedule;
                references.clear();
            }
        }
        if (lastError != null)
            TournamentDiagnostics.recordError("STORAGE", "prior_pgn_reference_cache_unavailable", lastError);
        return references;
    }

    static boolean isManuallyKeptActive(Context context, String sourceFileName) {
        return new File(activeTournamentDirectory(context, sourceFileName), ".keep-active").isFile();
    }

    /** Restore a validated completed archive to the active directory without deleting the
     * archive until the extracted generation and its PGN references are fully validated. */
    static void restoreCompletedToActive(Context context, String sourceFileName) throws IOException, JSONException {
        File active = activeTournamentDirectory(context, sourceFileName);
        if (hasActive(context, sourceFileName)) throw new IOException("Tournament is already active");
        File archive = new File(completedTournamentDirectory(context, sourceFileName), safeBaseName(sourceFileName) + ".zip");
        if (!archive.isFile()) throw new IOException("Completed tournament archive was not found");
        File parent = active.getParentFile();
        if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory())
            throw new IOException("Cannot create active tournament folder");
        File staging = new File(parent, "." + active.getName() + ".restore-" + Long.toHexString(System.nanoTime()));
        deleteRecursively(staging);
        if (!staging.mkdirs()) throw new IOException("Cannot create active tournament restore staging folder");
        try {
            try (ZipFile zip = new ZipFile(archive)) {
                java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (name == null || name.isEmpty() || name.startsWith("/") || name.contains("..") || name.contains("\\"))
                        throw new IOException("Unsafe completed archive entry");
                    boolean allowedRoot = "tournament.json".equals(name) || "schedule.json".equals(name) ||
                            "results.jsonl".equals(name) || "standings.json".equals(name) ||
                            "crosstable.json".equals(name) || "graphs.json".equals(name);
                    if (!allowedRoot && !name.startsWith("pgn/")) throw new IOException("Unexpected archive entry: " + name);
                    if (name.startsWith("pgn/") && (name.endsWith("/") || name.length() <= 4))
                        throw new IOException("Invalid PGN archive entry");
                    File outputFile = new File(staging, name);
                    if (!outputFile.getCanonicalPath().startsWith(staging.getCanonicalPath() + File.separator))
                        throw new IOException("Archive entry escaped active staging folder");
                    if (entry.isDirectory()) {
                        if (!outputFile.mkdirs() && !outputFile.isDirectory()) throw new IOException("Cannot create archive folder");
                    } else {
                        File outputParent = outputFile.getParentFile();
                        if (!outputParent.exists() && !outputParent.mkdirs() && !outputParent.isDirectory())
                            throw new IOException("Cannot create archive entry folder");
                        try (InputStream input = zip.getInputStream(entry); FileOutputStream output = new FileOutputStream(outputFile)) {
                            byte[] buffer = new byte[16384]; int count;
                            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                            output.flush(); output.getFD().sync();
                        }
                    }
                }
            }
            JSONObject metadata = readJson(new File(staging, "tournament.json"));
            long generation = metadata.optLong("sourceGeneration", -1L);
            String signature = metadata.optString("sourceSignature", "");
            validateGeneration(staging, signature, generation);
            validatePgnReferences(staging, staging);
            JSONObject pointer = envelope(sourceFileName, generation, signature);
            pointer.put("sourceFile", sourceFileName);
            pointer.put("generationDirectory", String.format(java.util.Locale.US, "generation-%08d", generation));
            // Archive entries are the generation modules themselves. Move them into the
            // immutable generation subfolder expected by active storage.
            File generationFolder = new File(staging, pointer.getString("generationDirectory"));
            if (!generationFolder.mkdir()) throw new IOException("Cannot create restored generation folder");
            String[] moduleNames = {"tournament.json", "schedule.json", "results.jsonl", "standings.json", "crosstable.json", "graphs.json"};
            for (String moduleName : moduleNames) {
                File module = new File(staging, moduleName);
                if (!module.renameTo(new File(generationFolder, moduleName)))
                    throw new IOException("Cannot stage restored module " + moduleName);
            }
            validateGeneration(generationFolder, signature, generation);
            validatePgnReferences(generationFolder, staging);
            writeJson(new File(staging, "current.json"), pointer);
            // A finished event remains classified in Active only because the user
            // explicitly requested that move. An unfinished event may resume and
            // should still auto-archive normally when it eventually completes.
            if (isCompletedGeneration(generationFolder))
                writeRawFile(new File(staging, ".keep-active"), new byte[]{'1'});
            if (active.exists()) throw new IOException("Active tournament destination already exists");
            if (!staging.renameTo(active)) throw new IOException("Cannot publish restored active tournament");
            // Hide the completed ZIP from the archive picker only after the active
            // generation is safely published. Keep a recovery copy until the rename succeeds.
            File hiddenArchive = new File(archive.getParentFile(), archive.getName() + ".restored.bak");
            if (hiddenArchive.exists() && !hiddenArchive.delete()) {
                deleteRecursively(active);
                throw new IOException("Could not clear an earlier archive-restore backup");
            }
            if (!archive.renameTo(hiddenArchive)) {
                deleteRecursively(active);
                throw new IOException("Could not retire completed archive after active restore");
            }
            if (hiddenArchive.exists() && !hiddenArchive.delete())
                TournamentDiagnostics.record("STORAGE", "restored_archive_backup_retained", "file=" + sourceFileName);
            TournamentDiagnostics.record("STORAGE", "completed_archive_restored_to_active", "file=" + sourceFileName);
        } catch (IOException | JSONException error) {
            deleteRecursively(staging);
            throw error;
        }
    }

    private static JSONObject readCurrentPointer(File active) throws IOException, JSONException {
        File[] candidates = {new File(active, "current.json"),
                new File(active, "current.json.previous.bak")};
        Exception lastError = null;
        for (File candidate : candidates) {
            if (!candidate.isFile()) continue;
            try {
                JSONObject pointer = readJson(candidate);
                String generationName = pointer.optString("generationDirectory", "");
                if (generationName.isEmpty() || generationName.contains("/") || generationName.contains("\\"))
                    throw new IOException("Invalid active tournament generation pointer");
                File generation = new File(active, generationName);
                validateGeneration(generation, pointer.optString("sourceSignature", ""),
                        pointer.optLong("sourceGeneration", -1L));
                validatePgnReferences(generation, active);
                return pointer;
            } catch (Exception error) {
                lastError = error;
            }
        }
        if (lastError instanceof JSONException) throw (JSONException) lastError;
        if (lastError instanceof IOException) throw (IOException) lastError;
        throw new IOException("No valid committed tournament generation pointer", lastError);
    }

    private static long currentGeneration(File active) {
        File[] candidates = {new File(active, "current.json"),
                new File(active, "current.json.previous.bak")};
        for (File pointerFile : candidates) {
            if (!pointerFile.isFile()) continue;
            try {
                JSONObject pointer = readJson(pointerFile);
                String generationName = pointer.optString("generationDirectory", "");
                if (!generationName.matches("generation-[0-9]{8}") ||
                        !new File(active, generationName).isDirectory()) continue;
                long generation = pointer.optLong("sourceGeneration", -1L);
                if (generation >= 0L) return generation;
            } catch (Exception ignored) { /* Try the previous committed pointer. */ }
        }
        return 0L;
    }

    static void archiveCompleted(Context context, String sourceFileName) throws IOException, JSONException {
        File active = activeTournamentDirectory(context, sourceFileName);
        if (!hasActive(context, sourceFileName))
            throw new IOException("Cannot archive a tournament without a committed active generation");
        JSONObject pointer = readCurrentPointer(active);
        String generationName = pointer.optString("generationDirectory", "");
        if (generationName.isEmpty() || generationName.contains("/") || generationName.contains("\\"))
            throw new IOException("Invalid active tournament generation pointer");
        File generation = new File(active, generationName);
        validateGeneration(generation, pointer.optString("sourceSignature", ""), pointer.optLong("sourceGeneration", -1L));
        validatePgnReferences(generation, active);

        File completed = new File(tournamentsRoot(context), COMPLETED_FOLDER);
        if (!completed.exists() && !completed.mkdirs() && !completed.isDirectory())
            throw new IOException("Cannot create completed tournament archive directory");
        String archiveName = safeBaseName(sourceFileName) + ".zip";
        File target = new File(completed, archiveName);
        File temp = new File(completed, archiveName + ".tmp");
        if (temp.exists() && !temp.delete()) throw new IOException("Cannot clear previous archive temporary file");
        try (FileOutputStream output = new FileOutputStream(temp, false); ZipOutputStream zip = new ZipOutputStream(output)) {
            addDirectoryToZip(generation, generation, zip);
            addReferencedPgnFilesToZip(generation, new File(active, "pgn"), zip);
            zip.finish(); zip.flush(); output.getFD().sync();
        }
        validateCompletedArchive(temp, pointer.optString("sourceSignature", ""), pointer.optLong("sourceGeneration", -1L));
        publishAtomic(temp, target);
        // The ZIP is now fully verified and atomically published. Only now can the
        // active modular directory be removed.
        deleteRecursively(active);
    }

    static JSONObject readActiveRoot(Context context, String sourceFileName) throws IOException, JSONException {
        File active = activeTournamentDirectory(context, sourceFileName);
        JSONObject pointer = readCurrentPointer(active);
        String generationName = pointer.optString("generationDirectory", "");
        if (generationName.isEmpty() || generationName.contains("/") || generationName.contains("\\"))
            throw new IOException("Invalid active tournament generation pointer");
        File generation = new File(active, generationName);
        validateGeneration(generation, pointer.optString("sourceSignature", ""), pointer.optLong("sourceGeneration", -1L));
        validatePgnReferences(generation, active);
        return assembleRoot(generation, active);
    }

    static JSONObject readCompletedRoot(Context context, String sourceFileName) throws IOException, JSONException {
        File archive = new File(completedTournamentDirectory(context, sourceFileName), safeBaseName(sourceFileName) + ".zip");
        File temp = new File(context.getCacheDir(), "tournament-archive-restore-" +
                safeBaseName(sourceFileName) + "-" + Long.toHexString(System.nanoTime()));
        deleteRecursively(temp);
        if (!temp.mkdirs()) throw new IOException("Cannot create temporary completed-tournament restore directory");
        try {
            try (ZipFile zip = new ZipFile(archive)) {
                java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (name.startsWith("/") || name.contains("..") || name.contains("\\"))
                        throw new IOException("Unsafe completed archive entry");
                    File outputFile = new File(temp, name);
                    if (!outputFile.getCanonicalPath().startsWith(temp.getCanonicalPath() + File.separator))
                        throw new IOException("Completed archive path escaped restore directory");
                    if (entry.isDirectory()) {
                        if (!outputFile.mkdirs() && !outputFile.isDirectory()) throw new IOException("Cannot create archive directory");
                    } else {
                        File parent = outputFile.getParentFile();
                        if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory()) throw new IOException("Cannot create archive entry folder");
                        try (java.io.InputStream input = zip.getInputStream(entry); FileOutputStream output = new FileOutputStream(outputFile)) {
                            byte[] buffer = new byte[16384]; int count;
                            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                            output.flush(); output.getFD().sync();
                        }
                    }
                }
            }
            JSONObject metadata = readJson(new File(temp, "tournament.json"));
            validateGeneration(temp, metadata.optString("sourceSignature", ""), metadata.optLong("sourceGeneration", -1L));
            validatePgnReferences(temp, temp);
            return assembleRoot(temp, temp);
        } finally {
            deleteRecursively(temp);
        }
    }

    private static JSONObject assembleRoot(File generation, File pgnRoot) throws IOException, JSONException {
        JSONObject metadata = readJson(new File(generation, "tournament.json"));
        JSONObject settings = metadata.optJSONObject("settings");
        if (settings == null) throw new IOException("Tournament settings module is missing");
        JSONObject root = new JSONObject(settings.toString());
        JSONObject schedule = readJson(new File(generation, "schedule.json"));
        JSONArray pairings = schedule.optJSONArray("pairings");
        if (pairings == null) throw new IOException("Tournament schedule module is missing pairings");
        JSONArray restoredPairings = new JSONArray();
        Map<Integer, JSONObject> results = new HashMap<>();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                new FileInputStream(new File(generation, "results.jsonl")), UTF8))) {
            String line = reader.readLine();
            if (line == null || !"snapshot_header".equals(new JSONObject(line).optString("recordType", "")))
                throw new IOException("Tournament results module is invalid");
            while ((line = reader.readLine()) != null) if (!line.trim().isEmpty()) {
                JSONObject result = new JSONObject(line);
                results.put(result.optInt("index", -1), result);
            }
        }
        for (int i = 0; i < pairings.length(); i++) {
            JSONObject savedPair = pairings.getJSONObject(i);
            JSONObject pair = new JSONObject(savedPair.toString());
            String scheduledPgnFile = savedPair.optString("pgnFile", "");
            pair.remove("pgnFile");
            pair.put("outcome", "UNFINISHED");
            pair.put("pgn", "");
            pair.put("whiteEvalPawns", JSONObject.NULL);
            pair.put("blackEvalPawns", JSONObject.NULL);
            pair.put("anomalyReviewed", false);
            pair.put("ratingSequence", 0L);
            JSONObject result = results.get(pair.optInt("index", i));
            String pgnFile = scheduledPgnFile;
            if (result != null) {
                copyIfPresent(result, pair, "outcome");
                copyIfPresent(result, pair, "whiteEvalPawns");
                copyIfPresent(result, pair, "blackEvalPawns");
                copyIfPresent(result, pair, "anomalyReviewed");
                copyIfPresent(result, pair, "ratingSequence");
                String resultPgnFile = result.optString("pgnFile", "");
                if (!resultPgnFile.isEmpty()) pgnFile = resultPgnFile;
            }
            if (!pgnFile.isEmpty()) {
                validatePgnRelativePath(pgnFile);
                File pgn = new File(pgnRoot, pgnFile);
                if (!pgn.getCanonicalPath().startsWith(new File(pgnRoot, "pgn").getCanonicalPath() + File.separator))
                    throw new IOException("Tournament PGN path escaped its storage folder");
                pair.put("pgn", readText(pgn));
            }
            restoredPairings.put(pair);
        }
        root.put("pairings", restoredPairings);
        root.put("playQueue", schedule.optJSONArray("playQueue") == null ? new JSONArray() : schedule.optJSONArray("playQueue"));
        root.put("activePairingIndex", schedule.optInt("activePairingIndex", -1));
        return root;
    }

    private static String contentAddressedPgnName(int gameIndex, byte[] bytes) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(bytes, 0, bytes.length);
        return String.format(java.util.Locale.US, "game-%06d-%08x.pgn", gameIndex, crc.getValue());
    }

    private static void writeRawFileIfAbsent(File file, byte[] bytes) throws IOException {
        if (file.isFile()) {
            if (file.length() != bytes.length) throw new IOException("PGN content-address collision: " + file.getName());
            try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStream existing = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = input.read(buffer)) != -1) existing.write(buffer, 0, n);
                byte[] current = existing.toByteArray();
                if (!java.util.Arrays.equals(current, bytes))
                    throw new IOException("PGN content-address collision: " + file.getName());
            }
            return;
        }
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        writeRawFile(temp, bytes);
        if (!temp.renameTo(file)) {
            if (file.isFile()) {
                temp.delete();
                writeRawFileIfAbsent(file, bytes);
                return;
            }
            throw new IOException("Cannot atomically publish PGN file: " + file.getName());
        }
    }

    private static void validatePgnReferences(File generation, File pgnRoot) throws IOException, JSONException {
        JSONObject schedule = readJson(new File(generation, "schedule.json"));
        JSONArray pairings = schedule.optJSONArray("pairings");
        if (pairings == null) throw new IOException("Tournament schedule module is missing pairings");
        File pgnDirectory = new File(pgnRoot, "pgn");
        String pgnCanonical = pgnDirectory.getCanonicalPath() + File.separator;
        for (int i = 0; i < pairings.length(); i++) {
            JSONObject pair = pairings.optJSONObject(i);
            if (pair == null) throw new IOException("Tournament schedule contains an invalid pairing");
            String pgnFile = pair.optString("pgnFile", "");
            if (pgnFile.isEmpty()) continue;
            validatePgnRelativePath(pgnFile);
            File pgn = new File(pgnRoot, pgnFile);
            if (!pgn.getCanonicalPath().startsWith(pgnCanonical) || !pgn.isFile())
                throw new IOException("Tournament PGN is missing or outside its storage folder: " + pgnFile);
        }
    }

    /** Validate newly-written PGNs at commit time while reusing already-verified
     * references for immutable historical games. Full validation still runs on restore/archive. */
    private static void validatePgnReferencesForCommit(File generation, File pgnRoot,
                                                        Map<Integer, String> previousPgnFiles,
                                                        Set<Integer> changedPgnIndexes) throws IOException, JSONException {
        JSONObject schedule = readJson(new File(generation, "schedule.json"));
        JSONArray pairings = schedule.optJSONArray("pairings");
        if (pairings == null) throw new IOException("Tournament schedule module is missing pairings");
        File pgnDirectory = new File(pgnRoot, "pgn");
        String pgnCanonical = pgnDirectory.getCanonicalPath() + File.separator;
        for (int i = 0; i < pairings.length(); i++) {
            JSONObject pair = pairings.optJSONObject(i);
            if (pair == null) throw new IOException("Tournament schedule contains an invalid pairing");
            String pgnFile = pair.optString("pgnFile", "");
            if (pgnFile.isEmpty()) continue;
            validatePgnRelativePath(pgnFile);
            int index = pair.optInt("index", i);
            String previous = previousPgnFiles == null ? null : previousPgnFiles.get(index);
            boolean alreadyVerified = previous != null && previous.equals(pgnFile) &&
                    changedPgnIndexes != null && !changedPgnIndexes.contains(index);
            if (alreadyVerified) continue;
            File pgn = new File(pgnRoot, pgnFile);
            if (!pgn.getCanonicalPath().startsWith(pgnCanonical) || !pgn.isFile())
                throw new IOException("New tournament PGN is missing or outside its storage folder: " + pgnFile);
        }
    }

    private static void prunePgnFiles(File active) {
        HashSet<String> referenced = new HashSet<>();
        File[] generations = active.listFiles();
        if (generations != null) for (File generation : generations) {
            if (!generation.isDirectory() || !generation.getName().matches("generation-[0-9]{8}")) continue;
            try {
                JSONObject schedule = readJson(new File(generation, "schedule.json"));
                JSONArray pairings = schedule.optJSONArray("pairings");
                if (pairings == null) continue;
                for (int i = 0; i < pairings.length(); i++) {
                    JSONObject pair = pairings.optJSONObject(i);
                    if (pair != null) {
                        String path = pair.optString("pgnFile", "");
                        if (!path.isEmpty()) {
                            validatePgnRelativePath(path);
                            referenced.add(path.substring("pgn/".length()));
                        }
                    }
                }
            } catch (Exception error) {
                TournamentDiagnostics.recordError("STORAGE", "pgn_prune_schedule_read_failed", error);
                return; // Preserve every PGN if a retained generation cannot be inspected.
            }
        }
        File pgnDirectory = new File(active, "pgn");
        File[] pgnFiles = pgnDirectory.listFiles();
        if (pgnFiles == null) return;
        for (File pgn : pgnFiles) {
            if (pgn.isFile() && pgn.getName().endsWith(".pgn") && !referenced.contains(pgn.getName()))
                deleteRecursively(pgn);
        }
    }

    private static void addReferencedPgnFilesToZip(File generation, File activeDirectory, ZipOutputStream zip) throws IOException, JSONException {
        JSONObject schedule = readJson(new File(generation, "schedule.json"));
        JSONArray pairings = schedule.optJSONArray("pairings");
        if (pairings == null) throw new IOException("Tournament schedule module is missing pairings");
        HashSet<String> added = new HashSet<>();
        byte[] buffer = new byte[16384];
        for (int i = 0; i < pairings.length(); i++) {
            JSONObject pair = pairings.optJSONObject(i);
            if (pair == null) throw new IOException("Tournament schedule contains an invalid pairing");
            String pgnFile = pair.optString("pgnFile", "");
            if (pgnFile.isEmpty() || !added.add(pgnFile)) continue;
            validatePgnRelativePath(pgnFile);
            File pgn = new File(activeDirectory, pgnFile);
            if (!pgn.isFile()) throw new IOException("Cannot archive missing PGN: " + pgnFile);
            zip.putNextEntry(new ZipEntry(pgnFile));
            try (FileInputStream input = new FileInputStream(pgn)) {
                int count;
                while ((count = input.read(buffer)) != -1) zip.write(buffer, 0, count);
            }
            zip.closeEntry();
        }
    }

    private static void validateGeneration(File generation, String expectedSignature, long expectedGeneration)
            throws IOException, JSONException {
        String[] modules = {"tournament.json", "schedule.json", "standings.json", "crosstable.json", "graphs.json"};
        for (String name : modules) {
            JSONObject module = readJson(new File(generation, name));
            if (module.optInt("schemaVersion", -1) != SCHEMA_VERSION ||
                    module.optLong("sourceGeneration", -1) != expectedGeneration ||
                    !expectedSignature.equals(module.optString("sourceSignature", "")))
                throw new IOException("Invalid or mixed tournament generation module: " + name);
        }
        validateResultsJsonl(new File(generation, "results.jsonl"), expectedSignature, expectedGeneration);
    }

    private static void validateCompletedArchive(File archiveFile, String signature, long generation)
            throws IOException, JSONException {
        try (ZipFile zip = new ZipFile(archiveFile)) {
            String[] required = {"tournament.json", "schedule.json", "results.jsonl", "standings.json", "crosstable.json", "graphs.json"};
            for (String name : required) {
                ZipEntry entry = zip.getEntry(name);
                if (entry == null) throw new IOException("Completed archive is missing " + name);
                try (java.io.InputStream input = zip.getInputStream(entry)) {
                    if ("results.jsonl".equals(name)) validateResultsJsonl(input, signature, generation);
                    else {
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        byte[] buffer = new byte[8192]; int n;
                        while ((n = input.read(buffer)) != -1) bytes.write(buffer, 0, n);
                        JSONObject module = new JSONObject(new String(bytes.toByteArray(), UTF8));
                        if (module.optInt("schemaVersion", -1) != SCHEMA_VERSION ||
                                module.optLong("sourceGeneration", -1) != generation ||
                                !signature.equals(module.optString("sourceSignature", "")))
                            throw new IOException("Completed archive contains a stale module: " + name);
                    }
                }
            }
            ZipEntry scheduleEntry = zip.getEntry("schedule.json");
            JSONObject scheduleModule;
            try (java.io.InputStream input = zip.getInputStream(scheduleEntry); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = input.read(buffer)) != -1) bytes.write(buffer, 0, n);
                scheduleModule = new JSONObject(new String(bytes.toByteArray(), UTF8));
            }
            JSONArray scheduledPairings = scheduleModule.optJSONArray("pairings");
            if (scheduledPairings == null) throw new IOException("Completed archive schedule has no pairings");
            for (int i = 0; i < scheduledPairings.length(); i++) {
                JSONObject pair = scheduledPairings.optJSONObject(i);
                if (pair == null) throw new IOException("Completed archive contains an invalid pairing");
                String pgnFile = pair.optString("pgnFile", "");
                if (pgnFile.isEmpty()) continue;
                validatePgnRelativePath(pgnFile);
                ZipEntry pgnEntry = zip.getEntry(pgnFile);
                if (pgnEntry == null || pgnEntry.isDirectory())
                    throw new IOException("Completed archive is missing PGN entry: " + pgnFile);
                try (java.io.InputStream pgnInput = zip.getInputStream(pgnEntry)) {
                    byte[] buffer = new byte[8192];
                    while (pgnInput.read(buffer) != -1) { }
                }
            }
            ZipEntry resultsEntry = zip.getEntry("results.jsonl");
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                    zip.getInputStream(resultsEntry), UTF8))) {
                String line = reader.readLine(); // validated snapshot header
                while ((line = reader.readLine()) != null) {
                    if (line.trim().isEmpty()) continue;
                    JSONObject result = new JSONObject(line);
                    String pgnFile = result.optString("pgnFile", "");
                    if (pgnFile.isEmpty()) continue;
                    validatePgnRelativePath(pgnFile);
                    ZipEntry pgnEntry = zip.getEntry(pgnFile);
                    if (pgnEntry == null || pgnEntry.isDirectory())
                        throw new IOException("Completed archive is missing PGN entry: " + pgnFile);
                    try (java.io.InputStream pgnInput = zip.getInputStream(pgnEntry)) {
                        byte[] buffer = new byte[8192];
                        while (pgnInput.read(buffer) != -1) { }
                    }
                }
            }
        }
    }

    private static void validatePgnRelativePath(String path) throws IOException {
        if (path == null || !path.startsWith("pgn/game-") || !path.endsWith(".pgn") ||
                path.contains("..") || path.contains("\\") || path.startsWith("/"))
            throw new IOException("Invalid PGN path in tournament result record");
    }

    private static void addDirectoryToZip(File root, File current, ZipOutputStream zip) throws IOException {
        File[] children = current.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) addDirectoryToZip(root, child, zip);
            else {
                String name = root.toURI().relativize(child.toURI()).getPath();
                zip.putNextEntry(new ZipEntry(name));
                try (FileInputStream input = new FileInputStream(child)) {
                    byte[] buffer = new byte[16384]; int count;
                    while ((count = input.read(buffer)) != -1) zip.write(buffer, 0, count);
                }
                zip.closeEntry();
            }
        }
    }

    private static void writeRawFile(File file, byte[] bytes) throws IOException {
        File parent = file.getParentFile();
        if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory()) throw new IOException("Cannot create " + parent);
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(bytes); output.flush(); output.getFD().sync();
        }
    }

    private static String readText(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), UTF8);
        }
    }

    private static void pruneGenerations(File active, String currentName) {
        File[] entries = active.listFiles();
        if (entries == null) return;
        ArrayList<File> generations = new ArrayList<>();
        for (File entry : entries) if (entry.isDirectory() && entry.getName().matches("generation-[0-9]{8}")) generations.add(entry);
        Collections.sort(generations, (a, b) -> b.getName().compareTo(a.getName()));
        String previousName = null;
        File previousPointer = new File(active, "current.json.previous.bak");
        if (previousPointer.isFile()) {
            try {
                JSONObject pointer = readJson(previousPointer);
                String candidate = pointer.optString("generationDirectory", "");
                if (!candidate.isEmpty() && !candidate.contains("/") && !candidate.contains("\\")) {
                    File previousGeneration = new File(active, candidate);
                    validateGeneration(previousGeneration, pointer.optString("sourceSignature", ""),
                            pointer.optLong("sourceGeneration", -1L));
                    previousName = candidate;
                }
            } catch (Exception invalidPrevious) {
                TournamentDiagnostics.recordError("STORAGE", "previous_generation_pointer_invalid", invalidPrevious);
            }
        }
        if (previousName == null) {
            for (File generation : generations) {
                if (!generation.getName().equals(currentName)) {
                    previousName = generation.getName();
                    break;
                }
            }
        }
        for (File generation : generations) {
            if (generation.getName().equals(currentName) || generation.getName().equals(previousName)) continue;
            deleteRecursively(generation);
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        try { file.delete(); } catch (SecurityException ignored) { }
    }

    static void deleteTournament(Context context, String fileName) throws IOException {
        File active = activeTournamentDirectory(context, fileName);
        File archive = new File(completedTournamentDirectory(context, fileName), safeBaseName(fileName) + ".zip");
        File archiveBackup = new File(archive.getAbsolutePath() + ".previous.bak");
        File restoredArchiveBackup = new File(archive.getAbsolutePath() + ".restored.bak");
        File archiveTemp = new File(archive.getAbsolutePath() + ".tmp");
        boolean found = active.exists() || archive.exists() || archiveBackup.exists() ||
                restoredArchiveBackup.exists() || archiveTemp.exists();
        if (!found) throw new IOException("No modular tournament files were found");
        boolean failed = false;
        if (active.exists()) { deleteRecursively(active); failed |= active.exists(); }
        if (archive.exists() && !archive.delete()) failed = true;
        if (archiveBackup.exists() && !archiveBackup.delete()) failed = true;
        if (restoredArchiveBackup.exists() && !restoredArchiveBackup.delete()) failed = true;
        if (archiveTemp.exists() && !archiveTemp.delete()) failed = true;
        if (failed) throw new IOException("Could not remove all modular tournament files");
    }

    private static File tournamentsRoot(Context context) {
        File privateRoot = new File(context.getFilesDir(), "tournaments");
        File sharedRoot = new File(new File(Environment.getExternalStorageDirectory(), "DroidFish"), "tournaments");
        // Continue using a previously-created modular store in place. For a new
        // installation, prefer the ordinary shared DroidFish directory so the
        // active folders and completed ZIP files are accessible outside the app.
        if (hasActiveOrCompletedFolder(privateRoot)) return privateRoot;
        if (hasActiveOrCompletedFolder(sharedRoot)) return sharedRoot;
        if ((!sharedRoot.exists() && sharedRoot.mkdirs()) || sharedRoot.isDirectory() && sharedRoot.canWrite())
            return sharedRoot;
        return privateRoot;
    }

    private static boolean hasActiveOrCompletedFolder(File root) {
        return new File(root, ACTIVE_FOLDER).isDirectory() || new File(root, COMPLETED_FOLDER).isDirectory();
    }

    static boolean usesSharedStorage(Context context) {
        File sharedRoot = new File(new File(Environment.getExternalStorageDirectory(), "DroidFish"), "tournaments");
        return tournamentsRoot(context).equals(sharedRoot);
    }

    private static File activeTournamentDirectory(Context context, String fileName) {
        return new File(new File(tournamentsRoot(context), ACTIVE_FOLDER), safeBaseName(fileName));
    }

    private static File completedTournamentDirectory(Context context, String fileName) {
        return new File(tournamentsRoot(context), COMPLETED_FOLDER);
    }

    private static String signature(JSONObject root) throws JSONException {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        byte[] bytes = root.toString().getBytes(UTF8);
        crc.update(bytes, 0, bytes.length);
        return Long.toHexString(crc.getValue());
    }

    private static void validateResultsJsonl(File file) throws IOException, JSONException {
        try (FileInputStream input = new FileInputStream(file)) {
            validateResultsJsonl(input, null, -1);
        }
    }

    private static void validateResultsJsonl(File file, String expectedSignature, long expectedGeneration)
            throws IOException, JSONException {
        try (FileInputStream input = new FileInputStream(file)) {
            validateResultsJsonl(input, expectedSignature, expectedGeneration);
        }
    }

    private static void validateResultsJsonl(java.io.InputStream input, String expectedSignature, long expectedGeneration)
            throws IOException, JSONException {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(input, UTF8))) {
            String line = reader.readLine();
            if (line == null) throw new IOException("Invalid results.jsonl header");
            JSONObject header = new JSONObject(line);
            if (!"snapshot_header".equals(header.optString("recordType", "")))
                throw new IOException("Invalid results.jsonl header");
            if (expectedSignature != null &&
                    (!expectedSignature.equals(header.optString("sourceSignature", "")) ||
                            header.optLong("sourceGeneration", -1) != expectedGeneration))
                throw new IOException("Stale results.jsonl snapshot");
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) new JSONObject(line);
            }
        }
    }

    private static JSONObject envelope(String fileName, long generation, String sourceSignature) throws JSONException {
        JSONObject object = new JSONObject();
        object.put("schemaVersion", SCHEMA_VERSION);
        object.put("sourceGeneration", generation);
        object.put("sourceFile", fileName);
        object.put("sourceSignature", sourceSignature);
        object.put("generatedAt", System.currentTimeMillis());
        return object;
    }

    private static void copyIfPresent(JSONObject source, JSONObject target, String key) throws JSONException {
        if (source.has(key)) target.put(key, source.get(key));
    }

    private static void writeResultsJsonl(File target, JSONObject header, JSONArray results) throws IOException, JSONException {
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp, false)) {
            out.write((header.toString() + "\n").getBytes(UTF8));
            for (int i = 0; i < results.length(); i++)
                out.write((results.getJSONObject(i).toString() + "\n").getBytes(UTF8));
            out.flush(); out.getFD().sync();
        }
        validateResultsJsonl(temp);
        publishAtomic(temp, target);
    }

    private static void writeJson(File target, JSONObject value) throws IOException, JSONException {
        writeText(target, value.toString() + "\n");
    }

    private static void writeText(File target, String value) throws IOException {
        byte[] bytes = value.getBytes(UTF8);
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp, false)) {
            out.write(bytes); out.flush(); out.getFD().sync();
        }
        // Validate JSON modules and byte-for-byte contents before publishing.
        if (target.getName().endsWith(".json")) {
            try (FileInputStream in = new FileInputStream(temp); ByteArrayOutputStream check = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int n; while ((n = in.read(buffer)) != -1) check.write(buffer, 0, n);
                new JSONObject(new String(check.toByteArray(), UTF8));
            } catch (JSONException invalid) { throw new IOException("Generated module JSON did not validate: " + target.getName(), invalid); }
        }
        publishAtomic(temp, target);
    }

    private static void writeBundle(File directory, String archiveName) throws IOException {
        File target = new File(directory, archiveName);
        File temp = new File(directory, archiveName + ".tmp");
        String[] names = {"tournament.json", "schedule.json", "results.jsonl", "standings.json", "crosstable.json", "graphs.json"};
        try (FileOutputStream file = new FileOutputStream(temp, false); ZipOutputStream zip = new ZipOutputStream(file)) {
            byte[] buffer = new byte[16384];
            for (String name : names) {
                File source = new File(directory, name);
                ZipEntry entry = new ZipEntry(name); zip.putNextEntry(entry);
                try (FileInputStream in = new FileInputStream(source)) {
                    int n; while ((n = in.read(buffer)) != -1) zip.write(buffer, 0, n);
                }
                zip.closeEntry();
            }
            zip.finish(); zip.flush(); file.getFD().sync();
        }
        try (ZipFile validation = new ZipFile(temp)) {
            for (String name : names) {
                ZipEntry entry = validation.getEntry(name);
                if (entry == null) throw new IOException("Generated ZIP omitted " + name);
                java.io.InputStream in = validation.getInputStream(entry);
                byte[] buffer = new byte[8192]; while (in.read(buffer) != -1) { } in.close();
            }
        }
        publishAtomic(temp, target);
    }

    private static void publishAtomic(File temp, File target) throws IOException {
        File backup = new File(target.getParentFile(), target.getName() + ".previous.bak");
        if (target.exists()) {
            if (backup.exists() && !backup.delete()) throw new IOException("Could not rotate previous " + target.getName());
            if (!target.renameTo(backup)) throw new IOException("Could not preserve previous " + target.getName());
        }
        if (!temp.renameTo(target)) {
            if (backup.exists()) backup.renameTo(target);
            throw new IOException("Could not publish " + target.getName());
        }
        // Keep one verified prior module for recovery; a subsequent write rotates it.
    }

    private static JSONObject readJson(File file) throws IOException, JSONException {
        try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n; while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return new JSONObject(new String(out.toByteArray(), UTF8));
        }
    }

    private static File tournamentDirectory(Context context, String fileName) {
        return new File(new File(context.getFilesDir(), "tournaments"), safeBaseName(fileName));
    }

    private static String safeBaseName(String fileName) {
        String base = fileName == null ? "tournament" : fileName.replaceFirst("\\.json(?:\\.gz)?$", "");
        base = base.replaceAll("[^A-Za-z0-9._-]", "_");
        if (base.isEmpty()) base = "tournament";
        return base;
    }

}

