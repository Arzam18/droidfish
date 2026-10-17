package org.petero.droidfish;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextWatcher;
import android.text.Editable;
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
    private TextView selectedText, statusText, standingsText, clockText, tournamentTitleText;
    private TextView whiteClockText, blackClockText, whiteNameText, blackNameText, moveStatusText;
    private TextView whiteAnalysisText, blackAnalysisText, whiteMoveTimeText, blackMoveTimeText, openingText;
    private CheckBox showTournamentArrowsCheck;
    private Position latestPosition = new Position();
    private float touchDownX, touchDownY;
    private Button previousMoveButton, nextMoveButton;
    private ChessBoardPlay tournamentBoard;
    private View setupView, playView;
    private Spinner formatSpinner, timeModeSpinner;
    private EditText titleInput, gamesInput, baseInput, incrementInput, limitInput, openingPliesInput, openingStartInput;
    private TextView openingFileText;
    private String selectedOpeningPgn = "";
    private String currentPgnMoves = "";
    private org.petero.droidfish.gamelogic.Move whiteHintMove, blackHintMove;
    private String selectedOpeningFileName = "No PGN file selected";
    private static final int REQUEST_OPENING_PGN = 7314;
    private CheckBox repeatOpeningCheck;
    private Button startButton, resumeButton, stopButton, playPauseButton;
    private boolean autoPaused;
    private TournamentRunner runner;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable clockRefresh = new Runnable() { public void run() { refreshLiveClocks(); uiHandler.postDelayed(this, 100); } };

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
        restoreSetup();
        uiHandler.post(clockRefresh);
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

        titleInput = field("Tournament name", "My Tournament"); root.addView(titleInput, fullWidth());
        gamesInput = field("Games per pairing", "2"); root.addView(gamesInput, fullWidth());
        root.addView(text("Time control mode"), fullWidth());
        timeModeSpinner = new Spinner(this);
        timeModeSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Time Clock: Time + Increment", "Time per move", "Depth per move", "Nodes per move"}));
        root.addView(timeModeSpinner, fullWidth());
        baseInput = field("Base time per side (minutes)", "0.5"); root.addView(baseInput, fullWidth());
        incrementInput = field("Increment per move (seconds; e.g. 1 for +1)", "0.5"); root.addView(incrementInput, fullWidth());
        limitInput = field("Time per move (seconds), depth, or nodes", "2"); root.addView(limitInput, fullWidth());
        timeModeSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                boolean clockMode = position == 0;
                baseInput.setVisibility(clockMode ? View.VISIBLE : View.GONE);
                incrementInput.setVisibility(clockMode ? View.VISIBLE : View.GONE);
                limitInput.setVisibility(clockMode ? View.GONE : View.VISIBLE);
                if (position == 1) limitInput.setHint("Time per move in seconds (e.g. 2 or 5)");
                else if (position == 2) { limitInput.setHint("Depth per move (e.g. 20)"); if ("2".equals(limitInput.getText().toString())) limitInput.setText("20"); }
                else if (position == 3) { limitInput.setHint("Nodes per move (e.g. 100000)"); if ("20".equals(limitInput.getText().toString())) limitInput.setText("100000"); }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        openingPliesInput = field("PGN opening plies (0 = full opening; positive = custom half-move limit)", "0"); root.addView(openingPliesInput, fullWidth());
        repeatOpeningCheck = new CheckBox(this); repeatOpeningCheck.setText("Repeat opening for paired color-reversal games");
        root.addView(repeatOpeningCheck, fullWidth());
        Button chooseOpeningPgn = new Button(this);
        chooseOpeningPgn.setText("Select opening PGN file");
        root.addView(chooseOpeningPgn, fullWidth());
        openingFileText = text(selectedOpeningFileName);
        root.addView(openingFileText, fullWidth());
        chooseOpeningPgn.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/x-chess-pgn");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/x-chess-pgn", "application/octet-stream", "text/plain", "*/*"});
            startActivityForResult(intent, REQUEST_OPENING_PGN);
        });
        openingStartInput = field("Params Opening PGN — start from game number (1 = first opening)", "1");
        root.addView(openingStartInput, fullWidth());
        showTournamentArrowsCheck = new CheckBox(this);
        showTournamentArrowsCheck.setText("Show engine thinking arrows on board");
        showTournamentArrowsCheck.setChecked(getPreferences(MODE_PRIVATE).getBoolean("tournamentThinkingArrows", true));
        showTournamentArrowsCheck.setOnCheckedChangeListener((button, checked) -> getPreferences(MODE_PRIVATE).edit().putBoolean("tournamentThinkingArrows", checked).apply());
        root.addView(showTournamentArrowsCheck, fullWidth());

        startButton = new Button(this); startButton.setText("Start tournament"); root.addView(startButton, fullWidth());
        resumeButton = new Button(this); resumeButton.setText("Resume saved tournament"); root.addView(resumeButton, fullWidth());
        stopButton = new Button(this); stopButton.setText("Go to tournament board"); stopButton.setEnabled(false); root.addView(stopButton, fullWidth());
        statusText = text("Ready"); root.addView(statusText, fullWidth());
        standingsText = text(""); root.addView(standingsText, fullWidth());
        startButton.setOnClickListener(v -> startTournament());
        resumeButton.setOnClickListener(v -> chooseSavedTournament());
        stopButton.setOnClickListener(v -> setContentView(playView));
        scroll.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                touchDownX = event.getX(); touchDownY = event.getY(); return false;
            }
            if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                float dx = event.getX() - touchDownX;
                float dy = event.getY() - touchDownY;
                if (Math.abs(dx) > dp(80) && Math.abs(dx) > Math.abs(dy) * 1.25f) {
                    if (dx > 0) {
                        setContentView(playView);
                        return true;
                    }
                }
                return false;
            }
            return false;
        });
        setupView = scroll;
        buildPlayUi();
        setContentView(setupView);
    }

    /** Dedicated live-game layout: large centered board, player clocks and compact controls. */
    private void buildPlayUi() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(0, dp(8), 0, dp(8));
        page.setBackgroundColor(0xFF303030);

        tournamentTitleText = text("MY TOURNAMENT");
        tournamentTitleText.setTextSize(18);
        tournamentTitleText.setTypeface(null, android.graphics.Typeface.BOLD);
        tournamentTitleText.setGravity(android.view.Gravity.CENTER);
        page.addView(tournamentTitleText, fullWidth());

        // Upper engine analysis panel, kept above its clock/name row.
        blackAnalysisText = text("Eval: —    Depth: —    Nodes: —    NPS: —");
        blackAnalysisText.setTextSize(18);
        blackAnalysisText.setLines(6);
        blackAnalysisText.setMaxLines(6);
        blackAnalysisText.setHeight(dp(126));
        blackAnalysisText.setGravity(android.view.Gravity.TOP | android.view.Gravity.LEFT);
        blackAnalysisText.setIncludeFontPadding(false);
        blackAnalysisText.setTextColor(0xFFD5D5D5);
        page.addView(blackAnalysisText, fullWidth());

        LinearLayout blackClockRow = new LinearLayout(this);
        blackClockRow.setOrientation(LinearLayout.HORIZONTAL);
        blackClockRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        blackClockText = text("00:30.0"); blackClockText.setTextSize(21); blackClockText.setTypeface(null, android.graphics.Typeface.BOLD);
        blackNameText = text("BLACK"); blackNameText.setTextSize(19); blackNameText.setTypeface(null, android.graphics.Typeface.BOLD); blackNameText.setGravity(android.view.Gravity.CENTER);
        blackMoveTimeText = text("—"); blackMoveTimeText.setTextSize(17); blackMoveTimeText.setGravity(android.view.Gravity.RIGHT);
        blackClockRow.addView(blackClockText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        blackClockRow.addView(blackNameText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f));
        blackClockRow.addView(blackMoveTimeText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        page.addView(blackClockRow, fullWidth());

        FrameLayout boardFrame = new FrameLayout(this) {
            @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int width = MeasureSpec.getSize(widthMeasureSpec);
                int size = width;
                super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY));
            }
        };
        LinearLayout.LayoutParams frameParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
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
        boardFrame.setOnTouchListener((v, event) -> handleBoardSwipe(event));
        tournamentBoard.setOnTouchListener((v, event) -> handleBoardSwipe(event));

        // Move-navigation buttons are temporarily hidden as requested.
        LinearLayout whiteClockRow = new LinearLayout(this);
        whiteClockRow.setOrientation(LinearLayout.HORIZONTAL);
        whiteClockRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        whiteClockText = text("00:30.0"); whiteClockText.setTextSize(21); whiteClockText.setTypeface(null, android.graphics.Typeface.BOLD);
        whiteNameText = text("WHITE"); whiteNameText.setTextSize(19); whiteNameText.setTypeface(null, android.graphics.Typeface.BOLD); whiteNameText.setGravity(android.view.Gravity.CENTER);
        whiteMoveTimeText = text("—"); whiteMoveTimeText.setTextSize(17); whiteMoveTimeText.setGravity(android.view.Gravity.RIGHT);
        whiteClockRow.addView(whiteClockText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        whiteClockRow.addView(whiteNameText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f));
        whiteClockRow.addView(whiteMoveTimeText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        page.addView(whiteClockRow, fullWidth());

        whiteAnalysisText = text("Eval: —    Depth: —    Nodes: —    NPS: —");
        whiteAnalysisText.setTextSize(18);
        whiteAnalysisText.setLines(6);
        whiteAnalysisText.setMaxLines(6);
        whiteAnalysisText.setHeight(dp(126));
        whiteAnalysisText.setGravity(android.view.Gravity.TOP | android.view.Gravity.LEFT);
        whiteAnalysisText.setIncludeFontPadding(false);
        whiteAnalysisText.setTextColor(0xFFD5D5D5);
        page.addView(whiteAnalysisText, fullWidth());
        openingText = text("Opening: Starting position");
        openingText.setTextSize(16);
        page.addView(openingText, fullWidth());
        moveStatusText = text("PGN: —");
        page.addView(moveStatusText, fullWidth());


        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        playPauseButton = new Button(this); playPauseButton.setText("P");
        Button results = new Button(this); results.setText("R");
        Button menu = new Button(this); menu.setText("M");
        Button schedule = new Button(this); schedule.setText("Sch");

        controls.addView(playPauseButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        controls.addView(results, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        controls.addView(menu, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        controls.addView(schedule, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        menu.setOnClickListener(v -> new AlertDialog.Builder(TournamentActivity.this)
                .setTitle("Tournament menu")
                .setItems(new String[]{"Adjudicate", "Setup", "Flip Board", "Moves"}, (dialog, which) -> {
                    if (which == 0) showAdjudicationDialog();
                    else if (which == 1) setContentView(setupView);
                    else if (which == 2) { if (tournamentBoard != null) tournamentBoard.setFlipped(!tournamentBoard.flipped); }
                    else showMovesDialog();
                }).show());
        schedule.setOnClickListener(v -> showScheduleDialog());
        page.addView(controls, fullWidth());
        playPauseButton.setOnClickListener(v -> togglePauseResume());
        results.setOnClickListener(v -> showStandingsDialog());
        ScrollView playScroll = new ScrollView(this);
        playScroll.setFillViewport(false);
        playScroll.setVerticalScrollBarEnabled(true);
        playScroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        playView = playScroll;
    }

    private boolean handleBoardSwipe(android.view.MotionEvent event) {
        switch (event.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                touchDownX = event.getX(); touchDownY = event.getY();
                return true;
            case android.view.MotionEvent.ACTION_UP:
                float dx = event.getX() - touchDownX;
                float dy = event.getY() - touchDownY;
                if (Math.abs(dx) > dp(80) && Math.abs(dx) > Math.abs(dy) * 1.25f) {
                    if (dx < 0) {
                        setContentView(setupView); // swipe left: tournament setup
                    } else {
                        // Bring the existing main DroidFish Activity forward, leaving this
                        // Activity underneath it so the current game and engine state survive.
                        if (runner != null && !runner.isPaused()) {
                            runner.pause();
                            autoPaused = true;
                            if (playPauseButton != null) playPauseButton.setText("P");
                            if (moveStatusText != null) moveStatusText.setText("PAUSED · Return to tournament to continue");
                        }
                        Intent main = new Intent(TournamentActivity.this, DroidFish.class);
                        main.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                        startActivity(main);
                    }
                    return true;
                }
                return true;
            default:
                return true;
        }
    }

    private void showStandingsDialog() {
        try {
            TournamentManager tournament = TournamentManager.restore(this);
            final String standings = formatStandings(tournament.standings(), tournament.engines());
            new AlertDialog.Builder(this).setTitle("Standings " + completedGameCount(tournament) + "/" + tournament.pairings().size())
                    .setMessage(standings)
                    .setNegativeButton("COPY", (dialog, which) -> {
                        ClipboardManager clipboard = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Tournament standings", standings));
                        Toast.makeText(this, "Standings copied", Toast.LENGTH_SHORT).show();
                    })
                    .setPositiveButton("OK", null).show();
        } catch (Exception e) {
            final String standings = standingsText == null ? "No results yet" : standingsText.getText().toString();
            new AlertDialog.Builder(this).setTitle("Tournament standings")
                    .setMessage(standings)
                    .setNegativeButton("COPY", (dialog, which) -> {
                        ClipboardManager clipboard = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Tournament standings", standings));
                        Toast.makeText(this, "Standings copied", Toast.LENGTH_SHORT).show();
                    })
                    .setPositiveButton("OK", null).show();
        }
    }

    private void showMovesDialog() {
        String moves = currentPgnMoves == null || currentPgnMoves.isEmpty() ? "No moves played yet." : currentPgnMoves;
        TextView body = text(moves); body.setTextIsSelectable(true); body.setTextSize(16); body.setPadding(dp(12), dp(8), dp(12), dp(8));
        ScrollView sc = new ScrollView(this); sc.addView(body);
        new AlertDialog.Builder(this).setTitle("Current game moves").setView(sc)
                .setPositiveButton("TOURNAMENT", (d, w) -> setContentView(playView)).setNegativeButton("CLOSE", null).show();
    }

    private void showScheduleDialog() {
        try {
            TournamentManager tournament = TournamentManager.restore(this);
            List<TournamentManager.Engine> engines = tournament.engines();
            StringBuilder out = new StringBuilder();
            for (TournamentManager.Pairing pairing : tournament.pairings()) {
                String white = engineDisplayName(engines, pairing.whiteEngineId);
                String black = engineDisplayName(engines, pairing.blackEngineId);
                String result;
                switch (pairing.outcome) {
                    case WHITE_WIN: result = "1-0"; break;
                    case DRAW: result = "½"; break;
                    case BLACK_WIN: result = "0-1"; break;
                    default: result = "*"; break;
                }
                out.append(pairing.index + 1).append('.').append(white)
                   .append("   (").append(result).append(")   ").append(black).append('\n');
            }
            if (out.length() == 0) out.append("No scheduled games.");
            TextView text = text(out.toString());
            text.setTextIsSelectable(true);
            text.setTextSize(14);
            text.setPadding(dp(12), dp(8), dp(12), dp(8));
            ScrollView scroll = new ScrollView(this);
            scroll.setFillViewport(false);
            scroll.addView(text, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            new AlertDialog.Builder(this).setTitle("Schedule · " + tournament.pairings().size() + " games")
                    .setView(scroll)
                    .setNegativeButton("COPY", (dialog, which) -> {
                        ClipboardManager clipboard = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Tournament schedule", out.toString()));
                        Toast.makeText(this, "Schedule copied", Toast.LENGTH_SHORT).show();
                    })
                    .setPositiveButton("CLOSE", null).show();
        } catch (Exception e) {
            Toast.makeText(this, "Could not load tournament schedule: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String engineDisplayName(List<TournamentManager.Engine> engines, String id) {
        for (TournamentManager.Engine engine : engines) if (engine.id.equals(id)) return engine.displayName;
        return id;
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


    private void saveSetup() {
        if (titleInput == null) return;
        android.content.SharedPreferences.Editor e = getPreferences(MODE_PRIVATE).edit();
        e.putString("setupTitle", titleInput.getText().toString());
        e.putString("setupGames", gamesInput.getText().toString());
        e.putInt("setupFormat", formatSpinner.getSelectedItemPosition());
        e.putInt("setupTimeMode", timeModeSpinner.getSelectedItemPosition());
        e.putString("setupBase", baseInput.getText().toString());
        e.putString("setupIncrement", incrementInput.getText().toString());
        e.putString("setupLimit", limitInput.getText().toString());
        e.putString("setupPlies", openingPliesInput.getText().toString());
        e.putString("setupOpeningStart", openingStartInput.getText().toString());
        e.putBoolean("setupRepeat", repeatOpeningCheck.isChecked());
        e.putBoolean("setupArrows", showTournamentArrowsCheck.isChecked());
        e.putString("setupOpeningPgn", selectedOpeningPgn);
        e.putString("setupOpeningName", selectedOpeningFileName);
        StringBuilder ids = new StringBuilder(); for (EngineChoice c : selectedEngines) { if (ids.length()>0) ids.append("\n"); ids.append(c.id); }
        e.putString("setupEngineIds", ids.toString()); e.apply();
    }
    private void restoreSetup() {
        android.content.SharedPreferences p = getPreferences(MODE_PRIVATE);
        titleInput.setText(p.getString("setupTitle", titleInput.getText().toString()));
        gamesInput.setText(p.getString("setupGames", gamesInput.getText().toString()));
        formatSpinner.setSelection(p.getInt("setupFormat", 0)); timeModeSpinner.setSelection(p.getInt("setupTimeMode", 0));
        baseInput.setText(p.getString("setupBase", baseInput.getText().toString())); incrementInput.setText(p.getString("setupIncrement", incrementInput.getText().toString()));
        limitInput.setText(p.getString("setupLimit", limitInput.getText().toString())); openingPliesInput.setText(p.getString("setupPlies", openingPliesInput.getText().toString()));
        openingStartInput.setText(p.getString("setupOpeningStart", openingStartInput.getText().toString())); repeatOpeningCheck.setChecked(p.getBoolean("setupRepeat", false));
        showTournamentArrowsCheck.setChecked(p.getBoolean("setupArrows", true)); selectedOpeningPgn=p.getString("setupOpeningPgn", ""); selectedOpeningFileName=p.getString("setupOpeningName", selectedOpeningPgn.isEmpty()?"No PGN file selected":"Saved PGN book loaded"); openingFileText.setText(selectedOpeningFileName);
        selectedEngines.clear(); String ids=p.getString("setupEngineIds", ""); if (!ids.isEmpty()) for(String id:ids.split("\n")) for(EngineChoice c:availableEngines) if(c.id.equals(id)){selectedEngines.add(c); break;} updateSelectedText();
    }
    private void refreshLiveClocks() {
        TournamentRunner r=runner; if(r==null || playView==null) return;
        if (r.isPaused()) return;
        long w=r.remainingMs(true), b=r.remainingMs(false);
        if(whiteClockText!=null && w>=0) whiteClockText.setText(formatClock(w));
        if(blackClockText!=null && b>=0) blackClockText.setText(formatClock(b));
        long elapsed=r.activeMoveTimeMs();
        if(elapsed>=0) { boolean white=r.whiteToMove(); TextView t=white?whiteMoveTimeText:blackMoveTimeText; if(t!=null)t.setText(formatClockDuration(elapsed)); }
    }

    private void startTournament() {
        if (runner != null) {
            // The tournament's schedule and completed PGNs are already persisted. Stop the
            // current runner before creating a separate saved tournament; its unfinished
            // pairing remains unfinished and will be replayed when resumed.
            try { runner.stop(); } catch (RuntimeException ignored) { }
            runner = null;
        }
        if (selectedEngines.size() < 2) { Toast.makeText(this, "Select at least two engines", Toast.LENGTH_LONG).show(); return; }
        try {
            int games = positiveInt(gamesInput, "games per pairing", 1);
            int timeModeIndex = timeModeSpinner.getSelectedItemPosition();
            double baseMinutes = timeModeIndex == 0 ? positiveDouble(baseInput, "base time in minutes", 0.01) : 0.5;
            long baseMillis = Math.round(baseMinutes * 60_000.0);
            int increment = timeModeIndex == 0 ? (int)Math.round(nonNegativeDouble(incrementInput, "increment") * 1000.0) : 0;
            int limitValue = timeModeIndex == 1 ? (int)Math.min(Integer.MAX_VALUE / 1000L, Math.round(positiveDouble(limitInput, "seconds per move", 1) * 1000.0)) :
                    (timeModeIndex == 2 ? positiveInt(limitInput, "depth per move", 1) :
                    (timeModeIndex == 3 ? positiveInt(limitInput, "nodes per move", 1) : 30));
            int openingPlies = nonNegativeInt(openingPliesInput, "opening plies");
            int openingStart = positiveInt(openingStartInput, "starting PGN opening number", 1);
            // A selected PGN is applied in full by default (0); positive values cap half-moves.
            if (openingPlies > 0 && selectedOpeningPgn.trim().isEmpty())
                throw new IllegalArgumentException("Select a PGN opening file before setting a custom opening-ply limit");
            if (baseMillis > Integer.MAX_VALUE * 1000L) throw new IllegalArgumentException("Base time is too large");
            TournamentManager.Format format = TournamentManager.Format.values()[formatSpinner.getSelectedItemPosition()];
            if (format == TournamentManager.Format.MATCH && selectedEngines.size() != 2)
                throw new IllegalArgumentException("Match format requires exactly two engines");
            ArrayList<TournamentManager.Engine> engines = new ArrayList<>();
            for (EngineChoice e : selectedEngines) engines.add(new TournamentManager.Engine(e.id, e.name));
            String tournamentName = titleInput.getText().toString().trim();
            if (tournamentName.isEmpty()) tournamentName = "My Tournament";
            saveSetup();
            TournamentManager tournament = new TournamentManager(this, tournamentName, format,
                    engines, games, baseMillis, increment, selectedOpeningPgn, openingPlies,
                    repeatOpeningCheck.isChecked(), TournamentManager.TimeMode.values()[timeModeIndex], limitValue, openingStart);
            tournament.save();
            launchTournament(tournament);
        } catch (Exception e) {
            if (runner != null) runner.stop();
            runner = null; startButton.setEnabled(true); resumeButton.setEnabled(true); stopButton.setEnabled(runner != null);
            Toast.makeText(this, "Could not start tournament: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_OPENING_PGN || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) { }
        new Thread(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                if (in == null) throw new IOException("Could not open selected PGN file");
                byte[] buffer = new byte[32768]; int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                String pgn = new String(out.toByteArray(), Charset.forName("UTF-8"));
                if (pgn.trim().isEmpty()) throw new IOException("The selected PGN file is empty");
                final String name = queryDisplayName(uri);
                runOnUiThread(() -> {
                    selectedOpeningPgn = pgn;
                    selectedOpeningFileName = name + " · PGN book loaded (contents are not displayed)";
                    openingFileText.setText(selectedOpeningFileName);
                    Toast.makeText(this, "PGN book selected. Choose the starting game number below.", Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Could not read PGN: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "PGN-book-loader").start();
    }

    private String queryDisplayName(Uri uri) {
        android.database.Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (RuntimeException ignored) { }
        finally { if (cursor != null) cursor.close(); }
        return "Selected PGN file";
    }

    private void chooseSavedTournament() {
        if (runner != null) { Toast.makeText(this, "A tournament is already running", Toast.LENGTH_SHORT).show(); return; }
        List<String> files = TournamentManager.savedTournamentFiles(this);
        if (files.isEmpty()) { Toast.makeText(this, "No saved tournaments", Toast.LENGTH_SHORT).show(); return; }
        ArrayList<String> labels = new ArrayList<>();
        for (String file : files) {
            try {
                TournamentManager t = TournamentManager.restore(this, file);
                int completed = 0;
                for (TournamentManager.Pairing pairing : t.pairings()) if (pairing.isComplete()) completed++;
                labels.add(t.title() + " · " + completed + "/" + t.pairings().size() + " games · " + (t.isComplete() ? "Completed" : "Unfinished"));
            } catch (Exception e) { labels.add(file + " · Cannot read"); }
        }
        new AlertDialog.Builder(this).setTitle("Saved tournaments")
            .setItems(labels.toArray(new String[0]), (dialog, which) -> showSavedTournamentActions(files.get(which)))
            .setNegativeButton("Cancel", null).show();
    }

    private void showSavedTournamentActions(String fileName) {
        final TournamentManager saved;
        try { saved = TournamentManager.restore(this, fileName); }
        catch (Exception e) { Toast.makeText(this, "Could not open saved tournament: " + e.getMessage(), Toast.LENGTH_LONG).show(); return; }
        String[] actions = saved.isComplete() ? new String[]{"Load setup / review", "Delete tournament"} : new String[]{"Continue tournament", "Delete tournament"};
        new AlertDialog.Builder(this).setTitle(saved.title()).setItems(actions, (dialog, which) -> {
            if (which == 0) {
                loadTournamentSetup(saved);
                if (!saved.isComplete()) launchTournament(saved);
                else Toast.makeText(this, "Completed tournament loaded into setup; its saved results remain available.", Toast.LENGTH_LONG).show();
            } else {
                new AlertDialog.Builder(this).setTitle("Delete saved tournament?").setMessage(saved.title() + " will be permanently deleted.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Delete", (d, w) -> {
                        try { TournamentManager.deleteSavedTournament(this, fileName); Toast.makeText(this, "Tournament deleted", Toast.LENGTH_SHORT).show(); }
                        catch (Exception e) { Toast.makeText(this, "Delete failed: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
                    }).show();
            }
        }).setNegativeButton("Cancel", null).show();
    }

    private void loadTournamentSetup(TournamentManager tournament) {
        selectedEngines.clear();
        for (TournamentManager.Engine engine : tournament.engines()) selectedEngines.add(new EngineChoice(engine.id, engine.displayName));
        updateSelectedText();
        titleInput.setText(tournament.title());
        formatSpinner.setSelection(tournament.format().ordinal());
        gamesInput.setText(String.valueOf(tournament.gamesPerPair()));
        baseInput.setText(String.valueOf(Math.max(0.01, tournament.baseTimeMs() / 60000.0)));
        incrementInput.setText(String.valueOf(tournament.incrementMs() / 1000.0));
        timeModeSpinner.setSelection(tournament.timeMode().ordinal());
        limitInput.setText(tournament.timeMode() == TournamentManager.TimeMode.MOVE_TIME ? String.valueOf(tournament.limitValue() / 1000.0) : String.valueOf(tournament.limitValue()));
        openingPliesInput.setText(String.valueOf(tournament.openingPlies()));
        selectedOpeningPgn = tournament.openingPgn();
        selectedOpeningFileName = selectedOpeningPgn.isEmpty() ? "No PGN file selected" : "Saved PGN book loaded";
        if (openingFileText != null) openingFileText.setText(selectedOpeningFileName);
        openingStartInput.setText(String.valueOf(tournament.openingStartIndex()));
        repeatOpeningCheck.setChecked(tournament.repeatOpening());
        saveSetup();
    }

    private void resumeTournament() {
        chooseSavedTournament();
    }

    private void launchTournament(final TournamentManager tournament) {
        runner = new TournamentRunner(tournament, new TournamentRunner.Listener() {
            @Override public void onGameStarted(TournamentManager.Pairing pairing, String white, String black) {
                runOnUiThread(() -> {
                    setContentView(playView);
                    if (tournamentTitleText != null) tournamentTitleText.setText(tournament.title());
                    if (playPauseButton != null) playPauseButton.setText(runner != null && runner.isPaused() ? "Play" : "Pause");
                    statusText.setText("Game " + (pairing.index + 1) + "/" + tournament.pairings().size() + ": " + white + " vs " + black);
                    if (openingStartInput != null && tournament.repeatOpening()) openingStartInput.setText(String.valueOf(tournament.openingStartIndex() + Math.max(0, (pairing.gameNumber - 1) / 2)));
                    currentPgnMoves = "";
                    whiteHintMove = null; blackHintMove = null;
                    moveStatusText.setText("PGN: —");
                    if (openingText != null) openingText.setText("Opening: Starting position");
                    if (whiteMoveTimeText != null) whiteMoveTimeText.setText("—");
                    if (blackMoveTimeText != null) blackMoveTimeText.setText("—");
                    whiteNameText.setText(white);
                    blackNameText.setText(black);
                });
            }
            @Override public void onPositionChanged(Position position, boolean whiteToMove,
                                                    long whiteTimeMs, long blackTimeMs,
                                                    String whiteName, String blackName, String pgnMoves) {
                runOnUiThread(() -> {
                    latestPosition = new Position(position);
                    currentPgnMoves = pgnMoves == null ? "" : pgnMoves;
                    tournamentBoard.setPosition(position);
                    whiteClockText.setText(formatClock(whiteTimeMs));
                    blackClockText.setText(formatClock(blackTimeMs));
                    whiteClockText.setAlpha(whiteToMove ? 1.0f : 0.72f);
                    blackClockText.setAlpha(whiteToMove ? 0.72f : 1.0f);
                    moveStatusText.setText("PGN: " + (currentPgnMoves.isEmpty() ? "—" : currentPgnMoves));
                });
            }
            @Override public void onEngineAnalysis(boolean white, String analysis) {
                runOnUiThread(() -> {
                    TextView target = white ? whiteAnalysisText : blackAnalysisText;
                    if (target != null) {
                        android.text.SpannableString styled = new android.text.SpannableString(analysis);
                        int variationStart = analysis.indexOf('\n');
                        if (variationStart >= 0 && variationStart + 1 < analysis.length()) {
                            styled.setSpan(new android.text.style.RelativeSizeSpan(0.75f), variationStart + 1,
                                    analysis.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                        target.setText(styled);
                    }
                    if (tournamentBoard != null && showTournamentArrowsCheck != null && showTournamentArrowsCheck.isChecked()
                            && android.preference.PreferenceManager.getDefaultSharedPreferences(TournamentActivity.this).getString("thinkingArrows", "4").equals("0") == false) {
                        try {
                            int variationStart = analysis.indexOf('\n');
                            if (variationStart >= 0 && variationStart + 1 < analysis.length()) {
                                String pvLine = analysis.substring(variationStart + 1).trim();
                                int firstSpace = pvLine.indexOf(' ');
                                if (firstSpace >= 0 && firstSpace + 1 < pvLine.length()) pvLine = pvLine.substring(firstSpace + 1).trim();
                                int end = pvLine.indexOf(' '); if (end >= 0) pvLine = pvLine.substring(0, end);
                                org.petero.droidfish.gamelogic.Move move = org.petero.droidfish.gamelogic.TextIO.stringToMove(new Position(latestPosition), pvLine);
                                if (move != null) {
                                    if (white) whiteHintMove = move; else blackHintMove = move;
                                    java.util.ArrayList<org.petero.droidfish.gamelogic.Move> hints = new java.util.ArrayList<>();
                                    if (whiteHintMove != null) hints.add(whiteHintMove);
                                    if (blackHintMove != null) hints.add(blackHintMove);
                                    tournamentBoard.setMoveHints(hints);
                                }
                            }
                        } catch (RuntimeException ignored) { }
                    } else if (tournamentBoard != null) { whiteHintMove = null; blackHintMove = null; tournamentBoard.setMoveHints(null); }
                });
            }
            @Override public void onReviewPosition(Position position, int ply, int totalPlies,
                                                   String whiteAnalysis, String blackAnalysis, long moveTimeMs) {
                runOnUiThread(() -> {
                    latestPosition = new Position(position);
                    tournamentBoard.setPosition(position);
                    tournamentBoard.setMoveHints(null);
                    whiteAnalysisText.setText(whiteAnalysis);
                    blackAnalysisText.setText(blackAnalysis);
                    moveStatusText.setText("PAUSED · Reviewing ply " + ply + "/" + totalPlies + " · Next/Previous to navigate");
                    String used = ply == 0 ? "—" : formatClockDuration(moveTimeMs);
                    if (ply > 0 && (ply % 2 == 1)) whiteMoveTimeText.setText(used); else if (ply > 0) blackMoveTimeText.setText(used);
                });
            }
            @Override public void onMoveTime(boolean whiteMoved, long moveTimeMs, String openingName) {
                runOnUiThread(() -> {
                    TextView target = whiteMoved ? whiteMoveTimeText : blackMoveTimeText;
                    if (target != null) target.setText(moveTimeMs <= 0 ? "—" : formatClockDuration(moveTimeMs));
                    if (openingText != null) openingText.setText("Opening: " + (openingName == null || openingName.isEmpty() ? "Starting position" : openingName));
                });
            }
            @Override public void onGameFinished(TournamentManager.Pairing pairing, TournamentManager.Outcome outcome, String pgn) {
                // Persist the cumulative PGN immediately on the callback thread, not only in a
                // queued UI update: the Activity may be backgrounded or destroyed right now.
                Exception autoSaveError = null;
                try { saveTournamentPgnAutomatically(tournament); }
                catch (Exception e) { autoSaveError = e; }
                final Exception savedError = autoSaveError;
                runOnUiThread(() -> {
                    String result = "Game " + (pairing.index + 1) + " finished: " + outcome;
                    statusText.setText(result);
                    moveStatusText.setText(result);
                    standingsText.setText(formatStandings(tournament.standings(), tournament.engines()));
                    if (tournamentTitleText != null) tournamentTitleText.setText(tournament.title());
                    if (savedError != null) statusText.setText(result + " · PGN auto-save failed: " + savedError.getMessage());
                });
            }
            @Override public void onTournamentFinished() {
                runOnUiThread(() -> {
                    statusText.setText("Tournament complete");
                    moveStatusText.setText("TOURNAMENT COMPLETE");
                    standingsText.setText(formatStandings(tournament.standings(), tournament.engines()));
                    runner = null; startButton.setEnabled(true); resumeButton.setEnabled(true); stopButton.setEnabled(runner != null);
                });
            }
            @Override public void onError(String message, Throwable error) {
                runOnUiThread(() -> {
                    statusText.setText("Tournament error: " + message + (error == null ? "" : " (" + error.getMessage() + ")"));
                    if (runner != null) { runner.stop(); runner = null; }
                    startButton.setEnabled(true); resumeButton.setEnabled(true); stopButton.setEnabled(runner != null);
                });
            }
        }, new EngineOptions(), new BookOptions(), tournament.baseTimeMs(), tournament.incrementMs(),
                tournament.openingPgn(), tournament.openingPlies(), tournament.repeatOpening(), tournament.openingStartIndex());
        startButton.setEnabled(false); resumeButton.setEnabled(false); stopButton.setEnabled(true);
        if (playPauseButton != null) playPauseButton.setText("P");
        if (tournamentTitleText != null) tournamentTitleText.setText(tournament.title());
        setContentView(playView);
        runner.start();
    }

    private void exportTournamentPgn() {
        try {
            TournamentManager tournament = TournamentManager.restore(this);
            String pgn = tournament.exportPgn();
            if (pgn.isEmpty()) { Toast.makeText(this, "No completed PGN games to export", Toast.LENGTH_LONG).show(); return; }
            File file = saveTournamentPgnAutomatically(tournament);
            Toast.makeText(this, "PGN saved to " + file.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "PGN export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private int completedGameCount(TournamentManager tournament) {
        int count = 0;
        for (TournamentManager.Pairing pairing : tournament.pairings()) {
            if (pairing.isComplete()) count++;
        }
        return count;
    }

    private String formatStandings(List<TournamentManager.Standing> standings, List<TournamentManager.Engine> engines) {
        StringBuilder out = new StringBuilder("Standings\n");
        for (int i = 0; i < standings.size(); i++) {
            TournamentManager.Standing s = standings.get(i);
            String name = s.engineId;
            for (TournamentManager.Engine e : engines) if (e.id.equals(s.engineId)) { name = e.displayName; break; }
            out.append(i + 1).append(".").append(name).append("\n    ").append(s.points)
                    .append("/").append(s.played).append("  +").append(s.wins)
                    .append(" =").append(s.draws).append(" -").append(s.losses).append("\n");
        }
        return out.toString();
    }

    private void showAdjudicationDialog() {
        if (runner == null || !runner.isPaused()) {
            Toast.makeText(this, "Pause the tournament before adjudicating the current game", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("Adjudicate current game")
                .setItems(new String[]{"White wins (1-0)", "Draw (1/2-1/2)", "Black wins (0-1)"}, (dialog, which) -> {
                    String result = which == 0 ? "1-0" : which == 1 ? "1/2-1/2" : "0-1";
                    new AlertDialog.Builder(this).setTitle("Confirm adjudication")
                            .setMessage("Record result " + result + " and finish this game?")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Adjudicate", (d, w) -> {
                                if (runner != null) runner.adjudicate(result);
                            }).show();
                }).setNegativeButton("Cancel", null).show();
    }

    private String formatClockDuration(long millis) {
        long safe = Math.max(0, millis);
        return String.format(java.util.Locale.US, "%d:%02d.%d", (safe / 60000), (safe / 1000) % 60, (safe % 1000) / 100);
    }

    private String formatDuration(long millis) {
        long safe = Math.max(0, millis);
        return String.format(java.util.Locale.US, "%d.%03ds", safe / 1000, safe % 1000);
    }

    private void togglePauseResume() {
        if (runner == null) {
            Toast.makeText(this, "No active tournament. Use Resume Saved Tournament.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (runner.isPaused()) {
            runner.resume();
            autoPaused = false;
            playPauseButton.setText("P");
            moveStatusText.setText("Resuming tournament…");
        } else {
            runner.pause();
            autoPaused = false;
            playPauseButton.setText("P");
            moveStatusText.setText("PAUSED · Tap Play to continue");
        }
    }

    /** Rewrite the cumulative PGN after every completed game, avoiding duplicate append on resume. */
    private File saveTournamentPgnAutomatically(TournamentManager tournament) throws IOException {
        String safeName = tournament.title().trim().replaceAll("[^A-Za-z0-9._ -]", "_")
                .replaceAll("\\s+", " ").replaceAll("[ .]+$", "");
        if (safeName.isEmpty()) safeName = "Tournament";
        byte[] bytes = tournament.exportPgn().getBytes(Charset.forName("UTF-8"));
        File pgnDir = new File(new File(Environment.getExternalStorageDirectory(), "DroidFish"), "pgn");
        File publicFile = new File(pgnDir, safeName + ".pgn");
        // Preserve an unrelated PGN with the same filename. Reuse this tournament's file
        // only when it already carries this exact Event tag; otherwise choose a free suffix.
        if (publicFile.exists()) {
            String existing = readSmallText(publicFile);
            String eventTag = "[Event \"" + tournament.title().replace("\\", "\\\\").replace("\"", "\\\"") + "\"]";
            if (!existing.contains(eventTag)) {
                int suffix = 2;
                File candidate;
                do { candidate = new File(pgnDir, safeName + " (" + suffix++ + ").pgn"); }
                while (candidate.exists() && !readSmallText(candidate).contains(eventTag));
                publicFile = candidate;
            }
        }
        try {
            File dir = publicFile.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs() && !dir.isDirectory())
                throw new IOException("Cannot create " + dir.getAbsolutePath());
            String mergedPgn = mergePgnGames(publicFile.exists() ? readSmallText(publicFile) : "", tournament.exportPgn());
            writeSynced(publicFile, mergedPgn.getBytes(Charset.forName("UTF-8")));
            return publicFile;
        } catch (IOException | SecurityException publicPathError) {
            // Android may deny legacy shared-storage writes. Keep auto-save working in the
            // app-specific external folder rather than losing the completed tournament PGN.
            File appDir = getExternalFilesDir("pgn");
            if (appDir == null) throw new IOException("Cannot access DroidFish/pgn or app PGN folder", publicPathError);
            if (!appDir.exists() && !appDir.mkdirs() && !appDir.isDirectory())
                throw new IOException("Cannot create app PGN folder", publicPathError);
            File fallback = new File(appDir, safeName + ".pgn");
            String mergedPgn = mergePgnGames(fallback.exists() ? readSmallText(fallback) : "", tournament.exportPgn());
            writeSynced(fallback, mergedPgn.getBytes(Charset.forName("UTF-8")));
            return fallback;
        }
    }

    private String mergePgnGames(String existing, String incoming) {
        java.util.LinkedHashMap<String, String> games = new java.util.LinkedHashMap<>();
        collectPgnGames(games, existing);
        collectPgnGames(games, incoming);
        StringBuilder result = new StringBuilder();
        for (String game : games.values()) { if (result.length() > 0) result.append("\n\n"); result.append(game); }
        return result.toString();
    }

    private void collectPgnGames(java.util.LinkedHashMap<String, String> games, String source) {
        if (source == null || source.trim().isEmpty()) return;
        String pgn = source.replace("\r\n", "\n").trim();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?m)(?=^\\[Event\\s)").matcher(pgn);
        java.util.ArrayList<Integer> starts = new java.util.ArrayList<>();
        while (matcher.find()) starts.add(matcher.start());
        if (starts.isEmpty()) { games.putIfAbsent(pgn.replaceAll("\\s+", " "), pgn); return; }
        for (int i = 0; i < starts.size(); i++) {
            String game = pgn.substring(starts.get(i), i + 1 < starts.size() ? starts.get(i + 1) : pgn.length()).trim();
            if (!game.isEmpty()) games.putIfAbsent(game.replaceAll("\\s+", " "), game);
        }
    }

    private String readSmallText(File file) {
        try {
            byte[] data = new byte[(int)Math.min(file.length(), 2 * 1024 * 1024)];
            try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                int offset = 0; while (offset < data.length) { int n = in.read(data, offset, data.length - offset); if (n < 0) break; offset += n; }
                return new String(data, 0, offset, Charset.forName("UTF-8"));
            }
        } catch (IOException ignored) { return ""; }
    }

    private void writeSynced(File file, byte[] bytes) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            out.write(bytes);
            out.flush();
            out.getFD().sync();
        }
    }

    private void stopTournament() {
        if (runner != null) { runner.stop(); runner = null; }
        statusText.setText("Tournament stopped. Completed results are saved.");
        if (moveStatusText != null) moveStatusText.setText("TOURNAMENT STOPPED");
        startButton.setEnabled(true); resumeButton.setEnabled(true); stopButton.setEnabled(runner != null);
    }

    @Override public void onBackPressed() {
        if (runner != null) {
            if (!runner.isPaused()) runner.pause();
            autoPaused = true;
            if (playPauseButton != null) playPauseButton.setText("P");
            Intent main = new Intent(this, DroidFish.class);
            main.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(main);
            Toast.makeText(this, "Tournament paused. Return to this tournament to continue.", Toast.LENGTH_SHORT).show();
            return;
        }
        super.onBackPressed();
    }

    @Override protected void onPause() {
        saveSetup();
        if (runner != null && !runner.isPaused()) {
            runner.pause();
            autoPaused = true;
        }
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (runner != null && autoPaused && runner.isPaused()) {
            runner.resume();
            autoPaused = false;
            if (playPauseButton != null) playPauseButton.setText("P");
        }
    }

    @Override protected void onDestroy() {
        uiHandler.removeCallbacks(clockRefresh);
        if (runner != null) {
            runner.pause();
            runner = null;
        }
        super.onDestroy();
    }

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
    private double positiveDouble(EditText field, String name, double minimum) {
        double v = Double.parseDouble(field.getText().toString().trim());
        if (Double.isNaN(v) || Double.isInfinite(v) || v < minimum) throw new IllegalArgumentException(name + " must be at least " + minimum);
        return v;
    }
    private double nonNegativeDouble(EditText field, String name) {
        double v = Double.parseDouble(field.getText().toString().trim());
        if (Double.isNaN(v) || Double.isInfinite(v) || v < 0) throw new IllegalArgumentException(name + " cannot be negative");
        return v;
    }
}
