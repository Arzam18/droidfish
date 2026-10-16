package org.petero.droidfish;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.FrameLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.kalab.chess.enginesupport.ChessEngine;
import com.kalab.chess.enginesupport.ChessEngineResolver;
import org.petero.droidfish.book.BookOptions;
import org.petero.droidfish.engine.EngineUtil;
import org.petero.droidfish.gamelogic.Position;
import org.petero.droidfish.ChessBoardPlay;
import org.petero.droidfish.tournament.TournamentManager;
import org.petero.droidfish.tournament.TournamentRunner;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Simple native setup and live-results screen for CET-style engine tournaments. */
public final class TournamentActivity extends Activity {
    private static final String ENGINE_DIR = "DroidFish/uci";
    private final ArrayList<EngineChoice> availableEngines = new ArrayList<>();
    private final ArrayList<EngineChoice> selectedEngines = new ArrayList<>();
    private TextView selectedText, statusText, standingsText, clockText;
    private TextView whiteClockText, blackClockText, whiteNameText, blackNameText, moveStatusText;
    private ChessBoardPlay tournamentBoard;
    private View setupView, playView;
    private Spinner formatSpinner;
    private EditText gamesInput, baseInput, incrementInput, openingPliesInput, openingInput;
    private CheckBox repeatOpeningCheck;
    private Button startButton, resumeButton, stopButton, exportButton;
    private TournamentRunner runner;

    private static final class EngineChoice {
        final String id, name;
        EngineChoice(String id, String name) { this.id = id; this.name = name; }
        @Override public String toString() { return name; }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle(R.string.option_tournament);
        loadEngineChoices();
        buildUi();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        Button choose = new Button(this);
        choose.setText("Select tournament engines");
        root.addView(choose, fullWidth());
        selectedText = text("No engines selected"); root.addView(selectedText, fullWidth());
        choose.setOnClickListener(v -> chooseEngines());

        root.addView(text("Tournament format"), fullWidth());
        formatSpinner = new Spinner(this);
        formatSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Round Robin", "Match", "Gauntlet (first engine vs rest)"}));
        root.addView(formatSpinner, fullWidth());

        gamesInput = field("Games per pairing", "2"); root.addView(gamesInput, fullWidth());
        baseInput = field("Base time per side (seconds)", "30"); root.addView(baseInput, fullWidth());
        incrementInput = field("Increment per move (milliseconds)", "500"); root.addView(incrementInput, fullWidth());
        openingPliesInput = field("PGN opening plies (0 = disabled)", "0"); root.addView(openingPliesInput, fullWidth());
        repeatOpeningCheck = new CheckBox(this); repeatOpeningCheck.setText("Repeat opening for paired color-reversal games");
        root.addView(repeatOpeningCheck, fullWidth());
        openingInput = field("Opening PGN (paste one or more games)", "");
        openingInput.setMinLines(3); openingInput.setGravity(android.view.Gravity.TOP);
        root.addView(openingInput, fullWidth());

        startButton = new Button(this); startButton.setText("Start tournament"); root.addView(startButton, fullWidth());
        resumeButton = new Button(this); resumeButton.setText("Resume saved tournament"); root.addView(resumeButton, fullWidth());
        stopButton = new Button(this); stopButton.setText("Stop tournament"); stopButton.setEnabled(false); root.addView(stopButton, fullWidth());
        exportButton = new Button(this); exportButton.setText("Export completed games to PGN"); root.addView(exportButton, fullWidth());
        statusText = text("Ready"); root.addView(statusText, fullWidth());
        standingsText = text(""); root.addView(standingsText, fullWidth());
        startButton.setOnClickListener(v -> startTournament());
        resumeButton.setOnClickListener(v -> resumeTournament());
        stopButton.setOnClickListener(v -> stopTournament());
        exportButton.setOnClickListener(v -> exportTournamentPgn());
        setupView = scroll;
        buildPlayUi();
        setContentView(setupView);
    }

    /** Dedicated live-game layout: large centered board, player clocks and compact controls. */
    private void buildPlayUi() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(12), dp(8), dp(12), dp(8));
        page.setBackgroundColor(0xFF303030);

        TextView title = text("TOURNAMENT GAME");
        title.setTextSize(18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        page.addView(title, fullWidth());

        blackNameText = text("BLACK");
        blackNameText.setTextSize(14);
        page.addView(blackNameText, fullWidth());
        blackClockText = text("00:30.0");
        blackClockText.setTextSize(25);
        blackClockText.setTypeface(null, android.graphics.Typeface.BOLD);
        page.addView(blackClockText, fullWidth());

        FrameLayout boardFrame = new FrameLayout(this);
        LinearLayout.LayoutParams frameParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        frameParams.topMargin = dp(4);
        frameParams.bottomMargin = dp(4);
        page.addView(boardFrame, frameParams);
        tournamentBoard = new TournamentChessBoard(this);
        tournamentBoard.setClickable(false);
        tournamentBoard.setFocusable(false);
        tournamentBoard.highlightLastMove = true;
        tournamentBoard.setPosition(new Position());
        FrameLayout.LayoutParams boardParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.Gravity.CENTER);
        boardFrame.addView(tournamentBoard, boardParams);

        whiteNameText = text("WHITE");
        whiteNameText.setTextSize(14);
        page.addView(whiteNameText, fullWidth());
        whiteClockText = text("00:30.0");
        whiteClockText.setTextSize(25);
        whiteClockText.setTypeface(null, android.graphics.Typeface.BOLD);
        page.addView(whiteClockText, fullWidth());
        moveStatusText = text("Waiting for tournament");
        page.addView(moveStatusText, fullWidth());

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        Button stop = new Button(this); stop.setText("Stop");
        Button results = new Button(this); results.setText("Results");
        Button export = new Button(this); export.setText("Export PGN");
        Button setup = new Button(this); setup.setText("Setup");
        controls.addView(stop, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        controls.addView(results, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        controls.addView(export, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        controls.addView(setup, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        page.addView(controls, fullWidth());
        stop.setOnClickListener(v -> stopTournament());
        results.setOnClickListener(v -> showStandingsDialog());
        export.setOnClickListener(v -> exportTournamentPgn());
        setup.setOnClickListener(v -> {
            if (runner != null) {
                Toast.makeText(this, "Stop the tournament before returning to setup", Toast.LENGTH_SHORT).show();
            } else {
                setContentView(setupView);
            }
        });
        playView = page;
    }

    private void showStandingsDialog() {
        try {
            TournamentManager tournament = TournamentManager.restore(this);
            new AlertDialog.Builder(this).setTitle("Tournament standings")
                    .setMessage(formatStandings(tournament.standings(), tournament.engines()))
                    .setPositiveButton(android.R.string.ok, null).show();
        } catch (Exception e) {
            new AlertDialog.Builder(this).setTitle("Tournament standings")
                    .setMessage(standingsText == null ? "No results yet" : standingsText.getText())
                    .setPositiveButton(android.R.string.ok, null).show();
        }
    }

    private static final class TournamentChessBoard extends ChessBoardPlay {
        TournamentChessBoard(android.content.Context context) { super(context, null); }
        @Override protected int getMaxWidthPercentage() { return 100; }
        @Override protected int getMaxHeightPercentage() { return 100; }
    }

    private void loadEngineChoices() {
        availableEngines.clear();
        availableEngines.add(new EngineChoice("stockfish", getString(R.string.stockfish_engine)));
        availableEngines.add(new EngineChoice("cuckoochess", getString(R.string.cuckoochess_engine)));
        if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) return;
        String sep = File.separator;
        String base = Environment.getExternalStorageDirectory() + sep + ENGINE_DIR + sep;
        try {
            ChessEngineResolver resolver = new ChessEngineResolver(this);
            List<ChessEngine> resolved = resolver.resolveEngines();
            ArrayList<EngineChoice> oex = new ArrayList<>();
            for (ChessEngine engine : resolved) {
                if (engine.getName() != null && engine.getFileName() != null && engine.getPackageName() != null) {
                    oex.add(new EngineChoice(base + EngineUtil.openExchangeDir + sep + EngineUtil.openExchangeFileName(engine), engine.getName()));
                }
            }
            Collections.sort(oex, (a,b) -> a.name.compareToIgnoreCase(b.name));
            availableEngines.addAll(oex);
        } catch (RuntimeException ignored) { }
        try {
            String[] files = FileUtil.findFilesInDirectory(ENGINE_DIR, filename -> {
                String name = new File(filename).getName();
                return !name.equals("stockfish") && !name.equals("cuckoochess") && !name.endsWith(".ini");
            });
            for (String file : files) availableEngines.add(new EngineChoice(base + file, file));
        } catch (RuntimeException ignored) { }
    }

    private void chooseEngines() {
        if (availableEngines.isEmpty()) { Toast.makeText(this, "No engines found", Toast.LENGTH_LONG).show(); return; }
        String[] labels = new String[availableEngines.size()];
        boolean[] checked = new boolean[availableEngines.size()];
        for (int i = 0; i < availableEngines.size(); i++) {
            EngineChoice choice = availableEngines.get(i); labels[i] = choice.name;
            for (EngineChoice selected : selectedEngines) if (selected.id.equals(choice.id)) checked[i] = true;
        }
        final ArrayList<EngineChoice> dialogSelection = new ArrayList<>(selectedEngines);
        new AlertDialog.Builder(this).setTitle("Choose engines; selection order sets the gauntlet engine")
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> {
                    EngineChoice choice = availableEngines.get(which);
                    if (isChecked) {
                        boolean present = false;
                        for (EngineChoice e : dialogSelection) if (e.id.equals(choice.id)) present = true;
                        if (!present) dialogSelection.add(choice);
                    } else {
                        for (int i = dialogSelection.size() - 1; i >= 0; i--)
                            if (dialogSelection.get(i).id.equals(choice.id)) dialogSelection.remove(i);
                    }
                })
                .setPositiveButton("Apply", (dialog, which) -> {
                    selectedEngines.clear(); selectedEngines.addAll(dialogSelection);
                    updateSelectedText();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void updateSelectedText() {
        StringBuilder b = new StringBuilder();
        for (EngineChoice e : selectedEngines) { if (b.length() > 0) b.append(", "); b.append(e.name); }
        selectedText.setText(b.length() == 0 ? "No engines selected" : b.toString());
    }

    private void startTournament() {
        if (runner != null) { Toast.makeText(this, "A tournament is already running", Toast.LENGTH_SHORT).show(); return; }
        if (selectedEngines.size() < 2) { Toast.makeText(this, "Select at least two engines", Toast.LENGTH_LONG).show(); return; }
        try {
            int games = positiveInt(gamesInput, "games per pairing", 1);
            int seconds = positiveInt(baseInput, "base time", 1);
            int increment = nonNegativeInt(incrementInput, "increment");
            int openingPlies = nonNegativeInt(openingPliesInput, "opening plies");
            if (seconds > Integer.MAX_VALUE / 1000) throw new IllegalArgumentException("Base time is too large");
            TournamentManager.Format format = TournamentManager.Format.values()[formatSpinner.getSelectedItemPosition()];
            if (format == TournamentManager.Format.MATCH && selectedEngines.size() != 2)
                throw new IllegalArgumentException("Match format requires exactly two engines");
            ArrayList<TournamentManager.Engine> engines = new ArrayList<>();
            for (EngineChoice e : selectedEngines) engines.add(new TournamentManager.Engine(e.id, e.name));
            TournamentManager tournament = new TournamentManager(this, "DroidFish CET Tournament", format,
                    engines, games, seconds * 1000L, increment, openingInput.getText().toString(), openingPlies,
                    repeatOpeningCheck.isChecked());
            tournament.save();
            launchTournament(tournament);
        } catch (Exception e) {
            if (runner != null) runner.stop();
            runner = null; startButton.setEnabled(true); resumeButton.setEnabled(true); stopButton.setEnabled(false);
            Toast.makeText(this, "Could not start tournament: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void resumeTournament() {
        if (runner != null) { Toast.makeText(this, "A tournament is already running", Toast.LENGTH_SHORT).show(); return; }
        try {
            TournamentManager tournament = TournamentManager.restore(this);
            selectedEngines.clear();
            for (TournamentManager.Engine engine : tournament.engines()) selectedEngines.add(new EngineChoice(engine.id, engine.displayName));
            updateSelectedText();
            formatSpinner.setSelection(tournament.format().ordinal());
            gamesInput.setText(String.valueOf(tournament.gamesPerPair()));
            baseInput.setText(String.valueOf(Math.max(1, tournament.baseTimeMs() / 1000)));
            incrementInput.setText(String.valueOf(tournament.incrementMs()));
            openingPliesInput.setText(String.valueOf(tournament.openingPlies()));
            openingInput.setText(tournament.openingPgn());
            repeatOpeningCheck.setChecked(tournament.repeatOpening());
            launchTournament(tournament);
        } catch (Exception e) {
            Toast.makeText(this, "No saved tournament could be resumed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void launchTournament(final TournamentManager tournament) {
        runner = new TournamentRunner(tournament, new TournamentRunner.Listener() {
            @Override public void onGameStarted(TournamentManager.Pairing pairing, String white, String black) {
                runOnUiThread(() -> {
                    setContentView(playView);
                    statusText.setText("Game " + (pairing.index + 1) + ": " + white + " vs " + black);
                    moveStatusText.setText("Game " + (pairing.index + 1) + " · " + white + " vs " + black);
                    whiteNameText.setText("WHITE  ·  " + white);
                    blackNameText.setText("BLACK  ·  " + black);
                });
            }
            @Override public void onPositionChanged(Position position, boolean whiteToMove,
                                                    long whiteTimeMs, long blackTimeMs,
                                                    String whiteName, String blackName) {
                runOnUiThread(() -> {
                    tournamentBoard.setPosition(position);
                    whiteClockText.setText(formatClock(whiteTimeMs));
                    blackClockText.setText(formatClock(blackTimeMs));
                    whiteClockText.setAlpha(whiteToMove ? 1.0f : 0.72f);
                    blackClockText.setAlpha(whiteToMove ? 0.72f : 1.0f);
                    moveStatusText.setText((whiteToMove ? "White to move" : "Black to move") +
                            "  ·  " + whiteName + " vs " + blackName);
                });
            }
            @Override public void onGameFinished(TournamentManager.Pairing pairing, TournamentManager.Outcome outcome, String pgn) {
                runOnUiThread(() -> {
                    String result = "Game " + (pairing.index + 1) + " finished: " + outcome;
                    statusText.setText(result);
                    moveStatusText.setText(result);
                    standingsText.setText(formatStandings(tournament.standings(), tournament.engines()));
                });
            }
            @Override public void onTournamentFinished() {
                runOnUiThread(() -> {
                    statusText.setText("Tournament complete");
                    moveStatusText.setText("TOURNAMENT COMPLETE");
                    standingsText.setText(formatStandings(tournament.standings(), tournament.engines()));
                    runner = null; startButton.setEnabled(true); resumeButton.setEnabled(true); stopButton.setEnabled(false);
                });
            }
            @Override public void onError(String message, Throwable error) {
                runOnUiThread(() -> {
                    statusText.setText("Tournament error: " + message + (error == null ? "" : " (" + error.getMessage() + ")"));
                    if (runner != null) { runner.stop(); runner = null; }
                    startButton.setEnabled(true); resumeButton.setEnabled(true); stopButton.setEnabled(false);
                });
            }
        }, new EngineOptions(), new BookOptions(), tournament.baseTimeMs(), tournament.incrementMs(),
                tournament.openingPgn(), tournament.openingPlies(), tournament.repeatOpening());
        startButton.setEnabled(false); resumeButton.setEnabled(false); stopButton.setEnabled(true);
        setContentView(playView);
        runner.start();
    }

    private void exportTournamentPgn() {
        try {
            TournamentManager tournament = TournamentManager.restore(this);
            String pgn = tournament.exportPgn();
            if (pgn.isEmpty()) { Toast.makeText(this, "No completed PGN games to export", Toast.LENGTH_LONG).show(); return; }
            File dir = getExternalFilesDir(null);
            if (dir == null) throw new IOException("App external files directory is unavailable");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not create export directory");
            File file = new File(dir, "cet_tournament.pgn");
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(pgn.getBytes(Charset.forName("UTF-8")));
            }
            Toast.makeText(this, "PGN exported to " + file.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "PGN export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String formatStandings(List<TournamentManager.Standing> standings, List<TournamentManager.Engine> engines) {
        StringBuilder out = new StringBuilder("Standings\n");
        for (int i = 0; i < standings.size(); i++) {
            TournamentManager.Standing s = standings.get(i);
            String name = s.engineId;
            for (TournamentManager.Engine e : engines) if (e.id.equals(s.engineId)) { name = e.displayName; break; }
            out.append(i + 1).append(". ").append(name).append("  ").append(s.points)
                    .append(" pts  (+").append(s.wins).append(" =").append(s.draws).append(" -").append(s.losses).append(")\n");
        }
        return out.toString();
    }

    private void stopTournament() {
        if (runner != null) { runner.stop(); runner = null; }
        statusText.setText("Tournament stopped. Completed results are saved.");
        if (moveStatusText != null) moveStatusText.setText("TOURNAMENT STOPPED");
        startButton.setEnabled(true); resumeButton.setEnabled(true); stopButton.setEnabled(false);
    }

    @Override public void onBackPressed() { stopTournament(); super.onBackPressed(); }
    @Override protected void onDestroy() { if (runner != null) runner.stop(); super.onDestroy(); }

    private EditText field(String hint, String value) {
        EditText e = new EditText(this); e.setSingleLine(false); e.setHint(hint); e.setText(value); return e;
    }
    private TextView text(String value) { TextView t = new TextView(this); t.setText(value); t.setTextSize(16); t.setPadding(0, dp(6), 0, dp(6)); return t; }
    private LinearLayout.LayoutParams fullWidth() { return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); }
    private String formatClock(long millis) {
        long safe = Math.max(0, millis);
        long totalSeconds = safe / 1000;
        return String.format(java.util.Locale.US, "%d:%02d.%d", totalSeconds / 60,
                totalSeconds % 60, (safe % 1000) / 100);
    }

    private int dp(int value) { return (int)(value * getResources().getDisplayMetrics().density + 0.5f); }
    private int positiveInt(EditText field, String name, int minimum) {
        int v = Integer.parseInt(field.getText().toString().trim()); if (v < minimum) throw new IllegalArgumentException(name + " must be at least " + minimum); return v;
    }
    private int nonNegativeInt(EditText field, String name) {
        int v = Integer.parseInt(field.getText().toString().trim()); if (v < 0) throw new IllegalArgumentException(name + " cannot be negative"); return v;
    }
}
