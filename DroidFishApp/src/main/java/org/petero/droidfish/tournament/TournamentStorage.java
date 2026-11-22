package org.petero.droidfish.tournament;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Compatibility-first modular tournament snapshots.
 *
 * The existing compressed JSON remains the authoritative recovery source during
 * this migration phase. The module files and ZIP are atomically rebuilt from the
 * same committed snapshot, so damaged/missing derived files can always be recreated
 * without changing legacy tournament loading.
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

