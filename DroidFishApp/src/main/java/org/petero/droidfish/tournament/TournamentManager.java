package org.petero.droidfish.tournament;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.FileInputStream;
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
    public enum TimeMode { CLOCK, MOVE_TIME, DEPTH, NODES }

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
        public final String whiteEngineId, blackEngineId;
        public Outcome outcome;
        public String pgn;
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
    private final List<Engine> engines = new ArrayList<>();
    private final List<Pairing> pairings = new ArrayList<>();

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
        // Millisecond timestamps can collide when a user creates tournaments rapidly.
        // Choose a unique persistent filename so a new event never overwrites another.
        String candidate;
        long stamp = System.currentTimeMillis();
        int suffix = 0;
        do {
            candidate = "cet_tournament_" + stamp + (suffix == 0 ? "" : "_" + suffix) + ".json";
            suffix++;
        } while (new java.io.File(this.context.getFilesDir(), candidate).exists());
        this.fileName = candidate;
        this.title = title == null ? "CET Tournament" : title; this.format = format; this.gamesPerPair = gamesPerPair;
        this.baseTimeMs = baseTimeMs; this.incrementMs = incrementMs;
        this.timeMode = timeMode == null ? TimeMode.CLOCK : timeMode; this.limitValue = Math.max(1, limitValue);
        this.openingPgn = openingPgn == null ? "" : openingPgn; this.openingPlies = openingPlies;
        this.repeatOpening = repeatOpening;
        this.openingStartIndex = Math.max(1, openingStartIndex);
        this.engines.addAll(engines);
        buildSchedule();
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
        openingStartIndex = Math.max(1, root.optInt("openingStartIndex", 1));
        timeMode = TimeMode.valueOf(root.optString("timeMode", "CLOCK")); limitValue = root.optInt("limitValue", 30);
        JSONArray ea = root.getJSONArray("engines");
        for (int i = 0; i < ea.length(); i++) { JSONObject e = ea.getJSONObject(i); engines.add(new Engine(e.getString("id"), e.optString("name"))); }
        JSONArray pa = root.getJSONArray("pairings");
        for (int i = 0; i < pa.length(); i++) {
            JSONObject p = pa.getJSONObject(i);
            pairings.add(new Pairing(p.getInt("index"), p.getInt("round"), p.getInt("gameNumber"),
                    p.getString("white"), p.getString("black"), Outcome.valueOf(p.optString("outcome", "UNFINISHED")), p.optString("pgn", "")));
        }
    }

    public static TournamentManager restore(Context context) throws IOException, JSONException {
        List<String> saved = savedTournamentFiles(context);
        if (saved.isEmpty()) throw new IOException("No saved tournament exists");
        return restore(context, saved.get(0));
    }

    public static TournamentManager restore(Context context, String fileName) throws IOException, JSONException {
        try (FileInputStream in = context.openFileInput(fileName);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, Charset.forName("UTF-8")))) {
            StringBuilder data = new StringBuilder(); String line;
            while ((line = reader.readLine()) != null) data.append(line);
            return new TournamentManager(context, new JSONObject(data.toString()), fileName);
        }
    }

    public static List<String> savedTournamentFiles(Context context) {
        List<String> files = new ArrayList<>();
        String[] names = context.getFilesDir().list();
        if (names != null) for (String name : names)
            if (name.equals("cet_tournament.json") || (name.startsWith("cet_tournament_") && name.endsWith(".json"))) files.add(name);
        Collections.sort(files, (a, b) -> Long.compare(savedTimestamp(b), savedTimestamp(a)));
        return files;
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
        if (fileName == null || !(fileName.equals("cet_tournament.json") ||
                (fileName.startsWith("cet_tournament_") && fileName.endsWith(".json"))))
            throw new IOException("Invalid saved tournament name");
        if (!context.deleteFile(fileName)) throw new IOException("Could not delete saved tournament");
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
    public synchronized List<Pairing> pairings() { return Collections.unmodifiableList(new ArrayList<>(pairings)); }
    public synchronized Pairing current() { for (Pairing p : pairings) if (!p.isComplete()) return p; return null; }
    public synchronized boolean isComplete() { return current() == null; }
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
        if (outcome == null || outcome == Outcome.UNFINISHED) throw new IllegalArgumentException("A final result is required");
        Pairing p = pairings.get(index);
        if (p.isComplete()) throw new IllegalStateException("Pairing already has a result");
        p.outcome = outcome; p.pgn = pgn == null ? "" : pgn; save();
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

    public synchronized void save() throws IOException, JSONException {
        JSONObject root = new JSONObject(); root.put("title", title); root.put("format", format.name()); root.put("gamesPerPair", gamesPerPair);
        root.put("baseTimeMs", baseTimeMs); root.put("incrementMs", incrementMs);
        root.put("timeMode", timeMode.name()); root.put("limitValue", limitValue);
        root.put("openingPgn", openingPgn); root.put("openingBinPath", openingBinPath); root.put("openingPlies", openingPlies); root.put("openingStartIndex", openingStartIndex); root.put("repeatOpening", repeatOpening);
        JSONArray ea = new JSONArray(); for (Engine e : engines) { JSONObject o = new JSONObject(); o.put("id", e.id); o.put("name", e.displayName); ea.put(o); } root.put("engines", ea);
        JSONArray pa = new JSONArray(); for (Pairing p : pairings) { JSONObject o = new JSONObject(); o.put("index", p.index); o.put("round", p.round); o.put("gameNumber", p.gameNumber); o.put("white", p.whiteEngineId); o.put("black", p.blackEngineId); o.put("outcome", p.outcome.name()); o.put("pgn", p.pgn); pa.put(o); } root.put("pairings", pa);
        try (FileOutputStream out = context.openFileOutput(fileName, Context.MODE_PRIVATE)) { out.write(root.toString().getBytes(Charset.forName("UTF-8"))); }
    }
}
