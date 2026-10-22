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
import org.petero.droidfish.gamelogic.GameTree;
import org.petero.droidfish.gamelogic.ChessParseError;
import org.petero.droidfish.gamelogic.TextIO;
import org.petero.droidfish.ChessBoardPlay;
import org.petero.droidfish.tournament.TournamentManager;
import org.petero.droidfish.tournament.TournamentRunner;
import org.petero.droidfish.engine.DroidComputerPlayer;
import org.petero.droidfish.gamelogic.SearchListener;
import org.petero.droidfish.gamelogic.SearchListener.PvInfo;
import org.petero.droidfish.gamelogic.Move;

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
    private LinearLayout playPage, blackClockRow, whiteClockRow;
    private boolean tournamentUiFlipped = false;
    private boolean currentWhiteToMove = true;
    private String currentWhiteName = "WHITE", currentBlackName = "BLACK";
    private TextView whiteAnalysisText, blackAnalysisText, whiteMoveTimeText, blackMoveTimeText, openingText;
    private View mirrorView;
    private int currentTournamentScreen = 0; // 0 = live board, 1 = mirror board
    private ChessBoardPlay mirrorBoard;
    private TextView mirrorAnalysisText, mirrorWhiteClock, mirrorBlackClock, mirrorWhiteName, mirrorBlackName,
            mirrorWhiteMoveTime, mirrorBlackMoveTime, mirrorMovesText, mirrorStatusText, mirrorHeaderText;
    private LinearLayout mirrorPage, mirrorBlackRow, mirrorWhiteRow;
    private boolean mirrorUiFlipped = false;
    private int currentGameNumber = 0, currentGameTotal = 0;
    private TournamentManager activeTournamentManager;
    private String mirrorTcLabel = "—";
    private Button mirrorAnalysisButton;
    private final ArrayList<Position> mirrorPositions = new ArrayList<>();
    private int mirrorPly = -1;
    private boolean mirrorAnalysisOn = false;
    private boolean mirrorAnalysisWhite = true;
    private boolean mirrorNavigationAllowed = false;
    private String lastWhiteAnalysis = "Eval: —", lastBlackAnalysis = "Eval: —";
    private long lastWhiteTimeMs, lastBlackTimeMs;
    private CheckBox showTournamentArrowsCheck;
    private CheckBox disableScreenTimeoutCheck;
    private Position latestPosition = new Position();
    private float touchDownX, touchDownY;
    private Button previousMoveButton, nextMoveButton;
    private ChessBoardPlay tournamentBoard;
    private View setupView, playView;
    private Spinner formatSpinner, timeModeSpinner;
    private EditText titleInput, gamesInput, baseInput, incrementInput, limitInput, openingPliesInput, openingStartInput, autoWinEvalInput;
    private CheckBox fastWinRuleCheck;
    private TextView openingFileText;
    private String selectedOpeningPgn = "";
    private String selectedOpeningBinPath = "";
    private String currentPgnMoves = "";
    private org.petero.droidfish.gamelogic.Move whiteHintMove, blackHintMove;
    private String selectedOpeningFileName = "No PGN file selected";
    private static final int REQUEST_OPENING_PGN = 7314;
    private static final int REQUEST_OPENING_BIN = 7316;
    private static final int REQUEST_GAME_VIEWER_PGN = 7315;
    private View gameViewerView;
    private ChessBoardPlay gameViewerBoard;
    private TextView gvWhiteClock, gvBlackClock, gvWhiteName, gvBlackName, gvWhiteTime, gvBlackTime;
    private TextView gvWhiteAnalysis, gvBlackAnalysis, gvMoves, gvStatus;
    private Button gvAnalysisButton;
    private EditText gvMoveInput;
    private GameTree gameViewerTree;
    private final ArrayList<Position> gameViewerPositions = new ArrayList<>();
    private final ArrayList<String> gameViewerMoves = new ArrayList<>();
    private final ArrayList<String> gameViewerComments = new ArrayList<>();
    private int gameViewerPly = 0;
    private boolean gameViewerAnalysisOn = false;
    private boolean gameViewerSelectedWhite = true;
    private String gameViewerPgn = "";
    private DroidComputerPlayer gameViewerEngine;
    private String gameViewerEngineId = "";
    private int gameViewerSearchId = 50000;
    private final ArrayList<Move> gameViewerHints = new ArrayList<>();
    private CheckBox drawRulesCheck;
    private EditText drawMoveRuleInput, drawRepetitionInput, drawMaximumInput, ruleStartMoveInput, evalDrawScoreInput, evalDrawConditionInput;

    private CheckBox repeatOpeningCheck;
    private Button startButton, resumeButton, stopButton, playPauseButton;
    private boolean autoPaused;
    private boolean activityVisible = true;
    private TournamentRunner runner;
    private EditText eventTimerMinutesInput, heatPauseMinutesInput, heatPlayMinutesInput;
    private long eventStartElapsedNs = 0L;
    private long eventAccumulatedMs = 0L;
    private long eventLastResumeNs = 0L;
    private boolean eventTimerHomeTriggered = false;
    private boolean heatCycleEnabled = false;
    private boolean heatCooling = false;
    private long heatPhaseStartNs = 0L;
    private long heatPlayAccumulatedMs = 0L;
    private long heatLastResumeNs = 0L;
    private long heatPauseDurationMs = 5 * 60_000L;
    private long heatPlayDurationMs = 2 * 60_000L;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable clockRefresh = new Runnable() { public void run() { refreshLiveClocks(); tickAutomaticTimers(); uiHandler.postDelayed(this, 250); } };

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
        restoreAdvancedTimerSettings();
        uiHandler.post(clockRefresh);
    }

    private void applyTournamentKeepScreenOn() {
        boolean enabled = getPreferences(MODE_PRIVATE).getBoolean("tournamentDisableScreenTimeout", false);
        if (enabled && runner != null) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        Button openGameViewer = new Button(this);
        openGameViewer.setText("Open Game Viewer (PGN / analysis)");
        root.addView(openGameViewer, fullWidth());
        openGameViewer.setOnClickListener(v -> openGameViewerPicker());

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
        baseInput = field("Base time per side (minutes; 0.5 = 30 seconds)", "0.5"); root.addView(baseInput, fullWidth());
        incrementInput = field("Increment per move (seconds; 0.5 = half a second)", "0.5"); root.addView(incrementInput, fullWidth());
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
        Button chooseOpeningBin = new Button(this); chooseOpeningBin.setText("Select Polyglot BIN opening book"); root.addView(chooseOpeningBin, fullWidth());
        chooseOpeningBin.setOnClickListener(v -> { Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("*/*"); intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/octet-stream", "application/x-chess-book", "*/*"}); startActivityForResult(intent, REQUEST_OPENING_BIN); });
        openingStartInput = field("Params Opening PGN — start from game number (1 = first opening)", "1");
        root.addView(openingStartInput, fullWidth());
        showTournamentArrowsCheck = new CheckBox(this);
        showTournamentArrowsCheck.setText("Show engine thinking arrows on board");
        showTournamentArrowsCheck.setChecked(getPreferences(MODE_PRIVATE).getBoolean("tournamentThinkingArrows", true));
        showTournamentArrowsCheck.setOnCheckedChangeListener((button, checked) -> getPreferences(MODE_PRIVATE).edit().putBoolean("tournamentThinkingArrows", checked).apply());
        root.addView(showTournamentArrowsCheck, fullWidth());
        disableScreenTimeoutCheck = new CheckBox(this);
        disableScreenTimeoutCheck.setText("Disable Screen Timeout during tournament");
        disableScreenTimeoutCheck.setChecked(getPreferences(MODE_PRIVATE).getBoolean("tournamentDisableScreenTimeout", false));
        disableScreenTimeoutCheck.setOnCheckedChangeListener((button, checked) -> {
            getPreferences(MODE_PRIVATE).edit().putBoolean("tournamentDisableScreenTimeout", checked).apply();
            applyTournamentKeepScreenOn();
        });
        root.addView(disableScreenTimeoutCheck, fullWidth());
        autoWinEvalInput = field("Auto win evaluation threshold (pawns; 10 = ±10.00)", "10");
        root.addView(autoWinEvalInput, fullWidth());
        fastWinRuleCheck = new CheckBox(this);
        fastWinRuleCheck.setText("Enable Fast Win Rule (both engines must agree on the winning side)");
        fastWinRuleCheck.setChecked(true);
        root.addView(fastWinRuleCheck, fullWidth());
        drawRulesCheck = new CheckBox(this); drawRulesCheck.setText("Enable tournament draw rules"); drawRulesCheck.setChecked(true); root.addView(drawRulesCheck, fullWidth());
        drawMoveRuleInput = field("Move rule (full moves; default 50)", "50"); root.addView(drawMoveRuleInput, fullWidth());
        ruleStartMoveInput = field("Start checking fast-win and draw rules at full move (default 1)", "1"); root.addView(ruleStartMoveInput, fullWidth());
        drawRepetitionInput = field("Repetition count (default 3)", "3"); root.addView(drawRepetitionInput, fullWidth());
        drawMaximumInput = field("Maximum full moves (default 160)", "160"); root.addView(drawMaximumInput, fullWidth());
        evalDrawScoreInput = field("Evaluation draw Score (pawns; 0.12 means ±0.12)", "0.12"); root.addView(evalDrawScoreInput, fullWidth());
        evalDrawConditionInput = field("Evaluation draw Condition (consecutive full moves)", "6"); root.addView(evalDrawConditionInput, fullWidth());
        drawRulesCheck.setOnCheckedChangeListener((button, checked) -> { drawMoveRuleInput.setEnabled(checked); drawRepetitionInput.setEnabled(checked); drawMaximumInput.setEnabled(checked); });

        TextView timerHeading = text("Advanced Control Mode — saved independently of tournament setup");
        timerHeading.setTypeface(null, android.graphics.Typeface.BOLD); root.addView(timerHeading, fullWidth());
        eventTimerMinutesInput = field("Timer 1: pause and go Home after N minutes (0 = off)", "0"); root.addView(eventTimerMinutesInput, fullWidth());
        heatPlayMinutesInput = field("Timer 2 — Play interval in minutes (0 = off; example 2)", "0"); root.addView(heatPlayMinutesInput, fullWidth());
        heatPauseMinutesInput = field("Timer 2 — Pause/cooling interval in minutes (example 5)", "5"); root.addView(heatPauseMinutesInput, fullWidth());
        Button applyTimers = new Button(this); applyTimers.setText("Apply Advanced Timers"); root.addView(applyTimers, fullWidth());
        applyTimers.setOnClickListener(v -> applyAdvancedTimers(true));

        startButton = new Button(this); startButton.setText("Start tournament"); root.addView(startButton, fullWidth());
        resumeButton = new Button(this); resumeButton.setText("Resume saved tournament"); root.addView(resumeButton, fullWidth());
        stopButton = new Button(this); stopButton.setText("Stop and Save Tournament"); stopButton.setEnabled(false); root.addView(stopButton, fullWidth());
        Button goToTournamentBoardButton = new Button(this);
        goToTournamentBoardButton.setText("Go to Tournament Board");
        goToTournamentBoardButton.setEnabled(true);
        root.addView(goToTournamentBoardButton, fullWidth());
        goToTournamentBoardButton.setOnClickListener(v -> {
            if (playView != null) {
                currentTournamentScreen = 0;
                if (tournamentBoard != null && latestPosition != null) tournamentBoard.setPosition(new Position(latestPosition));
                if (tournamentBoard != null) tournamentBoard.setMoveHints(null);
                setContentView(playView);
            }
        });
        statusText = text("Ready"); root.addView(statusText, fullWidth());
        standingsText = text(""); root.addView(standingsText, fullWidth());
        startButton.setOnClickListener(v -> startTournament());
        resumeButton.setOnClickListener(v -> chooseSavedTournament());
        stopButton.setOnClickListener(v -> stopAndSaveTournament());
        // Horizontal swipes no longer navigate between setup and tournament screens.
        scroll.setOnTouchListener((v, event) -> false);
        setupView = scroll;
        buildPlayUi();
        setContentView(setupView);
    }

    /** Dedicated live-game layout: large centered board, player clocks and compact controls. */
    private void buildPlayUi() {
        LinearLayout page = new LinearLayout(this);
        playPage = page;
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
        blackAnalysisText.setLines(7);
        blackAnalysisText.setMaxLines(7);
        blackAnalysisText.setHeight(dp(147));
        blackAnalysisText.setGravity(android.view.Gravity.TOP | android.view.Gravity.LEFT);
        blackAnalysisText.setIncludeFontPadding(false);
        blackAnalysisText.setTextColor(0xFFD5D5D5);
        page.addView(blackAnalysisText, fullWidth());

        blackClockRow = new LinearLayout(this);
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
        boardFrame.setOnTouchListener((v, event) -> false);
        tournamentBoard.setOnTouchListener((v, event) -> false);

        // Move-navigation buttons are temporarily hidden as requested.
        whiteClockRow = new LinearLayout(this);
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
        whiteAnalysisText.setLines(7);
        whiteAnalysisText.setMaxLines(7);
        whiteAnalysisText.setHeight(dp(147));
        whiteAnalysisText.setGravity(android.view.Gravity.TOP | android.view.Gravity.LEFT);
        whiteAnalysisText.setIncludeFontPadding(false);
        whiteAnalysisText.setTextColor(0xFFD5D5D5);
        page.addView(whiteAnalysisText, fullWidth());
        openingText = text("Opening: Starting position");
        openingText.setTextSize(16);
        page.addView(openingText, fullWidth());
        moveStatusText = text("PGN: —");
        moveStatusText.setVisibility(View.GONE); // PGN move list is shown only on the mirror board.


        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        playPauseButton = new Button(this); playPauseButton.setText("P");
        Button results = new Button(this); results.setText("R");
        Button menu = new Button(this); menu.setText("M");
        Button schedule = new Button(this); schedule.setText("Switch");

        controls.addView(playPauseButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        controls.addView(results, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        controls.addView(menu, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        controls.addView(schedule, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        menu.setOnClickListener(v -> new AlertDialog.Builder(TournamentActivity.this)
                .setTitle("Tournament menu")
                .setItems(new String[]{"Adjudicate", "Setup", "Flip Board", "Moves", "Schedule"}, (dialog, which) -> {
                    if (which == 0) showAdjudicationDialog();
                    else if (which == 1) setContentView(setupView);
                    else if (which == 2) toggleTournamentFlip();
                    else if (which == 3) showMovesDialog();
                    else showScheduleDialog();
                }).show());
        schedule.setOnClickListener(v -> switchTournamentAndMirror());
        page.addView(controls, fullWidth());
        playPauseButton.setOnClickListener(v -> togglePauseResume());
        results.setOnClickListener(v -> showStandingsDialog());
        ScrollView playScroll = new ScrollView(this);
        playScroll.setFillViewport(false);
        playScroll.setVerticalScrollBarEnabled(true);
        playScroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        playView = playScroll;
        buildMirrorUi();
        buildGameViewerUi();
    }

    /** Independent, read-only mirror of the live tournament board. Navigation never changes the live board. */
    private void buildMirrorUi() {
        LinearLayout page = new LinearLayout(this);
        mirrorPage = page;
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(0, dp(8), 0, dp(8));
        page.setBackgroundColor(0xFF303030);
        mirrorHeaderText = text("Game —/—  TC —  T 0:00:0"); mirrorHeaderText.setTextSize(18); mirrorHeaderText.setTypeface(null, android.graphics.Typeface.BOLD); mirrorHeaderText.setSingleLine(true); mirrorHeaderText.setIncludeFontPadding(false);
        mirrorHeaderText.setGravity(android.view.Gravity.CENTER); page.addView(mirrorHeaderText, fullWidth());
        mirrorAnalysisText = text("Select an engine with Analysis");
        mirrorAnalysisText.setTextSize(18); mirrorAnalysisText.setLines(7); mirrorAnalysisText.setMaxLines(7);
        mirrorAnalysisText.setHeight(dp(147)); mirrorAnalysisText.setGravity(android.view.Gravity.TOP | android.view.Gravity.LEFT);
        mirrorAnalysisText.setIncludeFontPadding(false); mirrorAnalysisText.setTextColor(0xFFD5D5D5);
        page.addView(mirrorAnalysisText, fullWidth());
        mirrorBlackClock = text("00:00.0"); mirrorBlackClock.setTextSize(20);
        mirrorBlackName = text("BLACK"); mirrorBlackName.setTextSize(18); mirrorBlackName.setGravity(android.view.Gravity.CENTER);
        mirrorBlackMoveTime = text("—"); mirrorBlackMoveTime.setGravity(android.view.Gravity.RIGHT);
        LinearLayout black = new LinearLayout(this); mirrorBlackRow = black; black.setGravity(android.view.Gravity.CENTER_VERTICAL);
        black.addView(mirrorBlackClock, new LinearLayout.LayoutParams(0, -2, 1));
        black.addView(mirrorBlackName, new LinearLayout.LayoutParams(0, -2, 1.4f));
        black.addView(mirrorBlackMoveTime, new LinearLayout.LayoutParams(0, -2, 1)); page.addView(black, fullWidth());
        FrameLayout frame = new FrameLayout(this) {
            @Override protected void onMeasure(int w, int h) { int width=MeasureSpec.getSize(w); super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)); }
        };
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2); fp.topMargin=dp(4); fp.bottomMargin=dp(4); page.addView(frame, fp);
        mirrorBoard = new TournamentChessBoard(this); mirrorBoard.setClickable(false); mirrorBoard.setFocusable(false); mirrorBoard.highlightLastMove=true;
        mirrorBoard.setPosition(new Position()); frame.addView(mirrorBoard, new FrameLayout.LayoutParams(-1,-1,android.view.Gravity.CENTER));
        mirrorWhiteClock = text("00:00.0"); mirrorWhiteClock.setTextSize(20);
        mirrorWhiteName = text("WHITE"); mirrorWhiteName.setTextSize(18); mirrorWhiteName.setGravity(android.view.Gravity.CENTER);
        mirrorWhiteMoveTime = text("—"); mirrorWhiteMoveTime.setGravity(android.view.Gravity.RIGHT);
        LinearLayout white = new LinearLayout(this); mirrorWhiteRow = white; white.setGravity(android.view.Gravity.CENTER_VERTICAL);
        white.addView(mirrorWhiteClock, new LinearLayout.LayoutParams(0,-2,1)); white.addView(mirrorWhiteName,new LinearLayout.LayoutParams(0,-2,1.4f));
        white.addView(mirrorWhiteMoveTime,new LinearLayout.LayoutParams(0,-2,1)); page.addView(white,fullWidth());
        LinearLayout nav = new LinearLayout(this); nav.setGravity(android.view.Gravity.CENTER);
        Button first = new Button(this); first.setText("<<"); Button prev = new Button(this); prev.setText("<");
        mirrorAnalysisButton = new Button(this); mirrorAnalysisButton.setText("⏯");
        Button next = new Button(this); next.setText(">"); Button last = new Button(this); last.setText(">>");
        for (Button b : new Button[]{first,prev,mirrorAnalysisButton,next,last}) nav.addView(b,new LinearLayout.LayoutParams(0,-2,1));
        page.addView(nav,fullWidth());
        mirrorMovesText=text("No moves played yet."); mirrorMovesText.setTextSize(16); page.addView(mirrorMovesText,fullWidth());
        mirrorStatusText=text("Mirror follows the live tournament."); page.addView(mirrorStatusText,fullWidth());
        LinearLayout mirrorControls = new LinearLayout(this);
        mirrorControls.setOrientation(LinearLayout.HORIZONTAL);
        Button mirrorPause = new Button(this); mirrorPause.setText("PAUSE / PLAY");
        Button mirrorResults = new Button(this); mirrorResults.setText("RESULTS");
        Button mirrorMenu = new Button(this); mirrorMenu.setText("MENU");
        Button back=new Button(this); back.setText("SWITCH");
        for (Button b : new Button[]{mirrorPause, mirrorResults, mirrorMenu, back})
            mirrorControls.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
        page.addView(mirrorControls, fullWidth());
        mirrorPause.setOnClickListener(v -> togglePauseResume());
        mirrorResults.setOnClickListener(v -> showStandingsDialog());
        mirrorMenu.setOnClickListener(v -> new AlertDialog.Builder(TournamentActivity.this)
                .setTitle("Tournament menu")
                .setItems(new String[]{"Adjudicate", "Setup", "Flip Board", "Schedule", "Moves"}, (dialog, which) -> {
                    if (which == 0) showAdjudicationDialog();
                    else if (which == 1) setContentView(setupView);
                    else if (which == 2) toggleTournamentFlip();
                    else if (which == 3) showScheduleDialog();
                    else showMovesDialog();
                }).show());
        back.setOnClickListener(v->switchTournamentAndMirror());
        first.setOnClickListener(v->navigateMirror(0)); prev.setOnClickListener(v->navigateMirror(Math.max(0, mirrorDisplayedPly()-1)));
        next.setOnClickListener(v->navigateMirror(Math.min(mirrorPositions.size()-1, mirrorDisplayedPly()+1)));
        last.setOnClickListener(v->navigateMirror(Math.max(0,mirrorPositions.size()-1)));
        // Short tap toggles the selected engine's recorded/live tournament analysis.
        // Long press chooses which engine's analysis the panel displays.
        mirrorAnalysisButton.setOnClickListener(v->toggleMirrorAnalysis());
        mirrorAnalysisButton.setOnLongClickListener(v->{ selectMirrorAnalysisEngine(); return true; });
        ScrollView sc=new ScrollView(this); sc.addView(page,new ScrollView.LayoutParams(-1,-2)); mirrorView=sc;
    }

    private int mirrorDisplayedPly() { return mirrorPly < 0 ? Math.max(0, mirrorPositions.size()-1) : mirrorPly; }
    private void openMirrorBoard() { syncMirrorFromLive(); currentTournamentScreen = 1; setContentView(mirrorView); }

    /** Switches between the live tournament board and its independent mirror view. */
    private void switchTournamentAndMirror() {
        if (mirrorView == null || playView == null) return;
        if (isMirrorShowing()) {
            currentTournamentScreen = 0;
            if (tournamentBoard != null && latestPosition != null) tournamentBoard.setPosition(new Position(latestPosition));
            if (tournamentBoard != null) tournamentBoard.setMoveHints(null);
            setContentView(playView);
        } else {
            syncMirrorFromLive();
            currentTournamentScreen = 1;
            setContentView(mirrorView);
        }
    }

    private boolean isMirrorShowing() { return currentTournamentScreen == 1; }
    private void syncMirrorFromLive() {
        if (mirrorBoard == null) return;
        if (mirrorPositions.isEmpty() || !samePosition(mirrorPositions.get(mirrorPositions.size()-1), latestPosition)) mirrorPositions.add(new Position(latestPosition));
        int idx = mirrorPly < 0 ? mirrorPositions.size()-1 : Math.min(mirrorPly,mirrorPositions.size()-1);
        if (idx >= 0) mirrorBoard.setPosition(new Position(mirrorPositions.get(idx)));
        if (tournamentBoard != null && mirrorBoard.flipped != tournamentBoard.flipped) { mirrorBoard.setFlipped(tournamentBoard.flipped); mirrorBoard.invalidate(); }
        if (mirrorMovesText != null) updateMirrorMoveLinks();
        if (mirrorWhiteName != null) { mirrorWhiteName.setText(currentWhiteName); mirrorBlackName.setText(currentBlackName); }
        if (mirrorHeaderText != null) mirrorHeaderText.setText(mirrorHeaderLabel());
        if (mirrorWhiteClock != null) { mirrorWhiteClock.setText(whiteClockText == null ? "—" : whiteClockText.getText()); mirrorBlackClock.setText(blackClockText == null ? "—" : blackClockText.getText()); }
        if (mirrorWhiteMoveTime != null) { mirrorWhiteMoveTime.setText(whiteMoveTimeText == null ? "—" : whiteMoveTimeText.getText()); mirrorBlackMoveTime.setText(blackMoveTimeText == null ? "—" : blackMoveTimeText.getText()); }
        if (mirrorAnalysisOn && mirrorAnalysisText != null) mirrorAnalysisText.setText(mirrorAnalysisWhite ? lastWhiteAnalysis : lastBlackAnalysis);
    }
    private boolean samePosition(Position a, Position b) { return a != null && b != null && a.toString().equals(b.toString()); }
    /** Makes SAN tokens in the recorded PGN clickable, including moves from the opening line. */
    private void updateMirrorMoveLinks() {
        if (mirrorMovesText == null) return;
        String moves = currentPgnMoves == null ? "" : currentPgnMoves.trim();
        if (moves.isEmpty()) { mirrorMovesText.setText("No moves played yet."); return; }
        android.text.SpannableString styled = new android.text.SpannableString(moves);
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\d+\\.{1,3}|[^\\s]+\\s*").matcher(moves);
        int ply = 0;
        while (matcher.find()) {
            String token = matcher.group().trim();
            if (token.isEmpty() || token.matches("1-0|0-1|1/2-1/2|\\*|\\{.*\\}")) continue;
            if (token.matches("\\d+\\.{1,3}")) {
                int moveNo;
                try { moveNo = Integer.parseInt(token.replaceAll("\\.", "")); } catch (RuntimeException ex) { continue; }
                int target = Math.max(0, Math.min(mirrorPositions.size()-1, (moveNo-1)*2 + (token.endsWith("...") ? 1 : 0)));
                styled.setSpan(new android.text.style.ClickableSpan() {
                    @Override public void onClick(View widget) { navigateMirror(target); }
                    @Override public void updateDrawState(android.text.TextPaint ds) { ds.setUnderlineText(true); ds.setColor(0xFF80CBC4); }
                }, matcher.start(), matcher.end(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                continue;
            }
            if (token.startsWith("{") || token.startsWith(";") || token.startsWith("(") || token.matches("\\d+\\.")) continue;
            final int target = Math.max(0, Math.min(mirrorPositions.size()-1, ++ply));
            styled.setSpan(new android.text.style.ClickableSpan() {
                @Override public void onClick(View widget) { navigateMirror(target); }
                @Override public void updateDrawState(android.text.TextPaint ds) { ds.setUnderlineText(true); ds.setColor(0xFF80CBC4); }
            }, matcher.start(), matcher.end(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        mirrorMovesText.setText(styled);
        mirrorMovesText.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        mirrorMovesText.setHighlightColor(android.graphics.Color.TRANSPARENT);
    }

    private void navigateMirror(int ply) {
        if (runner != null && !runner.isPaused()) {
            new AlertDialog.Builder(this).setTitle("Tournament is active:")
                .setMessage("Pause the tournament before navigating the mirror board?")
                .setPositiveButton("Pause", (d,w)->{ runner.pause(); mirrorNavigationAllowed=true; if(playPauseButton!=null)playPauseButton.setText("P"); navigateMirror(ply); })
                .setNegativeButton("Close", (d,w)->{}).show(); return;
        }
        if (mirrorPositions.isEmpty()) return;
        mirrorPly=Math.max(0,Math.min(ply,mirrorPositions.size()-1)); mirrorBoard.setPosition(new Position(mirrorPositions.get(mirrorPly)));
        if (mirrorStatusText!=null) mirrorStatusText.setText("Mirror review · ply " + mirrorPly + "/" + (mirrorPositions.size()-1));
    }
    private void toggleMirrorAnalysis() {
        mirrorAnalysisOn = !mirrorAnalysisOn;
        mirrorAnalysisButton.setText(mirrorAnalysisOn ? "⏯ ON" : "⏯ OFF");
        if (!mirrorAnalysisOn) {
            mirrorAnalysisText.setText("Analysis off");
        } else {
            mirrorAnalysisText.setText(mirrorAnalysisWhite ? lastWhiteAnalysis : lastBlackAnalysis);
        }
    }

    private void selectMirrorAnalysisEngine() {
        new AlertDialog.Builder(this).setTitle("Select analysis engine")
            .setItems(new String[]{currentWhiteName, currentBlackName}, (d, which) -> {
                mirrorAnalysisWhite = which == 0;
                if (mirrorAnalysisOn && mirrorAnalysisText != null)
                    mirrorAnalysisText.setText(mirrorAnalysisWhite ? lastWhiteAnalysis : lastBlackAnalysis);
                Toast.makeText(this, "Selected " + (mirrorAnalysisWhite ? currentWhiteName : currentBlackName) + " analysis. Tap ⏯ to toggle.", Toast.LENGTH_SHORT).show();
            }).setNegativeButton("Cancel", null).show();
    }

    private void buildGameViewerUi() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(0, dp(8), 0, dp(8));
        page.setBackgroundColor(0xFF303030);
        TextView title = text("GAME VIEWER"); title.setTextSize(18); title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(android.view.Gravity.CENTER); page.addView(title, fullWidth());
        Button open = new Button(this); open.setText("Open PGN file"); page.addView(open, fullWidth());
        open.setOnClickListener(v -> openGameViewerPicker());
        gvWhiteClock=text("---"); gvWhiteClock.setTextSize(20);
        gvWhiteName=text("WHITE"); gvWhiteName.setTextSize(18); gvWhiteName.setGravity(android.view.Gravity.CENTER);
        gvWhiteTime=text("---"); gvWhiteTime.setGravity(android.view.Gravity.RIGHT);
        LinearLayout top = new LinearLayout(this); top.setGravity(android.view.Gravity.CENTER_VERTICAL);
        top.addView(gvWhiteClock,new LinearLayout.LayoutParams(0,-2,1)); top.addView(gvWhiteName,new LinearLayout.LayoutParams(0,-2,1.4f)); top.addView(gvWhiteTime,new LinearLayout.LayoutParams(0,-2,1)); page.addView(top,fullWidth());
        FrameLayout frame = new FrameLayout(this) {
            @Override protected void onMeasure(int w,int h) { int width=MeasureSpec.getSize(w); super.onMeasure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY)); }
        };
        LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(-1,-2); fp.topMargin=dp(4); fp.bottomMargin=dp(4); page.addView(frame,fp);
        gameViewerBoard=new TournamentChessBoard(this); gameViewerBoard.setClickable(false); gameViewerBoard.setFocusable(false); gameViewerBoard.highlightLastMove=true; gameViewerBoard.setPosition(new Position());
        frame.addView(gameViewerBoard,new FrameLayout.LayoutParams(-1,-1,android.view.Gravity.CENTER));
        gvBlackClock=text("---"); gvBlackClock.setTextSize(20);
        gvBlackName=text("BLACK"); gvBlackName.setTextSize(18); gvBlackName.setGravity(android.view.Gravity.CENTER);
        gvBlackTime=text("---"); gvBlackTime.setGravity(android.view.Gravity.RIGHT);
        LinearLayout bottom=new LinearLayout(this); bottom.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bottom.addView(gvBlackClock,new LinearLayout.LayoutParams(0,-2,1)); bottom.addView(gvBlackName,new LinearLayout.LayoutParams(0,-2,1.4f)); bottom.addView(gvBlackTime,new LinearLayout.LayoutParams(0,-2,1)); page.addView(bottom,fullWidth());
        LinearLayout nav=new LinearLayout(this); nav.setGravity(android.view.Gravity.CENTER);
        Button first=new Button(this); first.setText("<<"); Button prev=new Button(this); prev.setText("<"); gvAnalysisButton=new Button(this); gvAnalysisButton.setText("⏯"); Button next=new Button(this); next.setText(">"); Button last=new Button(this); last.setText(">>");
        for(Button b:new Button[]{first,prev,gvAnalysisButton,next,last}) nav.addView(b,new LinearLayout.LayoutParams(0,-2,1)); page.addView(nav,fullWidth());
        first.setOnClickListener(v->showGameViewerPly(0)); prev.setOnClickListener(v->showGameViewerPly(Math.max(0,gameViewerPly-1))); next.setOnClickListener(v->showGameViewerPly(Math.min(gameViewerPositions.size()-1,gameViewerPly+1))); last.setOnClickListener(v->showGameViewerPly(Math.max(0,gameViewerPositions.size()-1)));
        gvAnalysisButton.setOnClickListener(v->{ if (gameViewerEngineId.isEmpty()) { Toast.makeText(this, "Long-press ⏯ to select an engine first", Toast.LENGTH_SHORT).show(); return; } gameViewerAnalysisOn=!gameViewerAnalysisOn; gvAnalysisButton.setText(gameViewerAnalysisOn?"⏯ ON":"⏯ OFF"); if (gameViewerAnalysisOn) startGameViewerAnalysis(); else stopGameViewerAnalysis(); renderGameViewerPly(); });
        gvAnalysisButton.setOnLongClickListener(v->{ selectGameViewerAnalysisEngine(); return true; });
        gvWhiteAnalysis=text("White Eval: ---  Depth: ---  Nodes: ---  NPS: ---"); gvWhiteAnalysis.setTextSize(16); gvWhiteAnalysis.setLines(2); gvWhiteAnalysis.setMaxLines(2); page.addView(gvWhiteAnalysis,fullWidth());
        gvBlackAnalysis=text("Black Eval: ---  Depth: ---  Nodes: ---  NPS: ---"); gvBlackAnalysis.setTextSize(16); gvBlackAnalysis.setLines(2); gvBlackAnalysis.setMaxLines(2); page.addView(gvBlackAnalysis,fullWidth());
        gvMoves=text("No PGN loaded."); gvMoves.setTextSize(16); page.addView(gvMoves,fullWidth());
        gvStatus=text("Open a PGN to review recorded analysis."); page.addView(gvStatus,fullWidth());
        Button fresh=new Button(this); fresh.setText("New empty game"); page.addView(fresh,fullWidth()); fresh.setOnClickListener(v -> startEmptyGameViewer());
        Button openBottom=new Button(this); openBottom.setText("Open a PGN"); page.addView(openBottom,fullWidth()); openBottom.setOnClickListener(v->openGameViewerPicker());
        Button back=new Button(this); back.setText("Go to Setup"); page.addView(back,fullWidth()); back.setOnClickListener(v->{ stopGameViewerAnalysis(); setContentView(setupView); });
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(false); scroll.addView(page,new ScrollView.LayoutParams(-1,-2)); gameViewerView=scroll;
    }

    private void applyGameViewerManualMove() {
        if (gameViewerPositions.isEmpty()) startEmptyGameViewer();
        Position pos = new Position(gameViewerPositions.get(gameViewerPly));
        String text = gvMoveInput == null ? "" : gvMoveInput.getText().toString().trim();
        if (text.isEmpty()) return;
        Move move = TextIO.stringToMove(pos, text);
        if (move == null) { Toast.makeText(this, "Illegal move: " + text, Toast.LENGTH_SHORT).show(); return; }
        String san = TextIO.moveToString(pos, move, false, false);
        pos.makeMove(move, new org.petero.droidfish.gamelogic.UndoInfo());
        while (gameViewerPositions.size() > gameViewerPly + 1) gameViewerPositions.remove(gameViewerPositions.size()-1);
        while (gameViewerMoves.size() > gameViewerPly) gameViewerMoves.remove(gameViewerMoves.size()-1);
        while (gameViewerComments.size() > gameViewerPly) gameViewerComments.remove(gameViewerComments.size()-1);
        gameViewerPositions.add(pos); gameViewerMoves.add(san); gameViewerComments.add(""); gameViewerPly++;
        if (gvMoveInput != null) gvMoveInput.setText(""); renderGameViewerPly();
        if (gameViewerAnalysisOn && !gameViewerEngineId.isEmpty()) startGameViewerAnalysis();
    }

    private void openGameViewerPicker() {
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("application/x-chess-pgn");
        try { startActivityForResult(intent,REQUEST_GAME_VIEWER_PGN); }
        catch (android.content.ActivityNotFoundException ex) { intent.setType("*/*"); startActivityForResult(intent,REQUEST_GAME_VIEWER_PGN); }
    }

    private void loadGameViewerPgn(Uri uri) {
        new Thread(() -> {
            try {
                StringBuilder sb=new StringBuilder();
                try(InputStream in=getContentResolver().openInputStream(uri)) { if(in==null) throw new IOException("Could not open PGN"); byte[] buf=new byte[8192]; int n; while((n=in.read(buf))>0) sb.append(new String(buf,0,n,java.nio.charset.StandardCharsets.UTF_8)); }
                String pgn=sb.toString();
                GameTree tree=new GameTree(null); org.petero.droidfish.PGNOptions options=new org.petero.droidfish.PGNOptions(); options.imp.comments=true; options.imp.variations=false; options.imp.nag=true;
                if(!tree.readPGN(pgn,options)) throw new IOException("No valid chess game found in this PGN");
                while(!tree.currentNode.equals(tree.rootNode)) tree.goBack();
                ArrayList<String> moves=extractViewerMoves(pgn);
                ArrayList<Position> positions=new ArrayList<>(); positions.add(new Position(tree.currentPos));
                for(int i=0;i<moves.size() && i<2000;i++) { tree.goForward(-1); positions.add(new Position(tree.currentPos)); }
                ArrayList<String> comments=extractViewerComments(pgn);
                while(moves.size()>positions.size()-1) moves.remove(moves.size()-1);
                while(moves.size()<positions.size()-1) moves.add("?");
                while(comments.size()<positions.size()-1) comments.add("");
                final GameTree finalTree=tree; final ArrayList<Position> finalPositions=positions; final ArrayList<String> finalComments=comments; final ArrayList<String> finalMoves=moves; final String finalPgn=pgn;
                runOnUiThread(() -> {
                    gameViewerTree=finalTree; gameViewerPositions.clear(); gameViewerPositions.addAll(finalPositions); gameViewerComments.clear(); gameViewerComments.addAll(finalComments); gameViewerMoves.clear(); gameViewerMoves.addAll(finalMoves); gameViewerPgn=finalPgn; gameViewerPly=0;
                    setContentView(gameViewerView); renderGameViewerPly(); if(gameViewerAnalysisOn && !gameViewerEngineId.isEmpty()) startGameViewerAnalysis();
                    Toast.makeText(this,"PGN loaded · "+Math.max(0,gameViewerPositions.size()-1)+" plies",Toast.LENGTH_SHORT).show();
                });
            } catch(Exception e) { runOnUiThread(() -> Toast.makeText(this,"Could not load PGN: "+e.getMessage(),Toast.LENGTH_LONG).show()); }
        },"game-viewer-pgn-loader").start();
    }

    private ArrayList<String> extractViewerComments(String pgn) {
        String main=pgn.replaceAll("(?m)^\\s*\\[[^\\]]*\\]\\s*$", " ");
        StringBuilder clean=new StringBuilder(); int depth=0; boolean inComment=false;
        for(int i=0;i<main.length();i++) { char ch=main.charAt(i); if(ch=='{' && depth==0) inComment=true; if(ch=='(') depth++; if(depth==0) clean.append(ch); if(ch==')' && depth>0) depth--; if(ch=='}' && inComment) inComment=false; }
        ArrayList<String> out=new ArrayList<>(); java.util.regex.Matcher m=java.util.regex.Pattern.compile("\\{([^}]*)\\}").matcher(clean.toString());
        while(m.find()) out.add(m.group(1).trim()); return out;
    }
    private ArrayList<String> extractViewerMoves(String pgn) {
        String body=pgn.replaceAll("(?m)^\\s*\\[[^\\]]*\\]\\s*$", " "); body=body.replaceAll("\\{[^}]*\\}|;[^\\r\\n]*", " ").replaceAll("\\([^)]*\\)", " ");
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?<![A-Za-z0-9])(?:\\d+\\.(?:\\.\\.)?\\s*)?([KQRBN]?[a-h]?[1-8]?x?[a-h][1-8](?:=[QRBN])?[+#]?|O-O-O[+#]?|O-O[+#]?)(?![A-Za-z0-9])").matcher(body);
        ArrayList<String> out=new ArrayList<>(); while(m.find()) out.add(m.group(1)); return out;
    }

    private void showGameViewerPly(int ply) {
        if(gameViewerPositions.isEmpty()) return; gameViewerPly=Math.max(0,Math.min(ply,gameViewerPositions.size()-1)); renderGameViewerPly(); if(gameViewerAnalysisOn && !gameViewerEngineId.isEmpty()) startGameViewerAnalysis();
    }
    private void renderGameViewerPly() {
        if(gameViewerPositions.isEmpty()) { gameViewerPositions.add(new Position()); gameViewerPly=0; }
        gameViewerBoard.setPosition(new Position(gameViewerPositions.get(gameViewerPly)));
        String[] names=extractPgnNames(gameViewerPgn); gvWhiteName.setText(names[0]); gvBlackName.setText(names[1]);
        String whiteComment="", blackComment="";
        String whiteClock="", blackClock="", whiteUsed="", blackUsed="";
        for(int ply=1; ply<=gameViewerPly && ply<=gameViewerComments.size(); ply++) {
            String c=gameViewerComments.get(ply-1);
            if((ply & 1)==1) {
                if(!c.isEmpty()) whiteComment=c;
                String clock=findCommentValue(c,"WhiteClock|WClock|ClockW|Clock");
                String used=findCommentValue(c,"WhiteTime|WTime|TimeWhite|T|Time|TimeUsed");
                if(!clock.isEmpty()) whiteClock=clock; if(!used.isEmpty()) whiteUsed=used;
            } else {
                if(!c.isEmpty()) blackComment=c;
                String clock=findCommentValue(c,"BlackClock|BClock|ClockB|Clock");
                String used=findCommentValue(c,"BlackTime|BTime|TimeBlack|T|Time|TimeUsed");
                if(!clock.isEmpty()) blackClock=clock; if(!used.isEmpty()) blackUsed=used;
            }
        }
        gvWhiteClock.setText(whiteClock.isEmpty()?"---":whiteClock); gvBlackClock.setText(blackClock.isEmpty()?"---":blackClock);
        gvWhiteTime.setText(whiteUsed.isEmpty()?"---":whiteUsed); gvBlackTime.setText(blackUsed.isEmpty()?"---":blackUsed);
        String whiteAnalysis=analysisFromComment(whiteComment), blackAnalysis=analysisFromComment(blackComment);
        // When live analysis is off, keep the PGN's recorded per-move analysis visible.
        gvWhiteAnalysis.setText("White "+whiteAnalysis);
        gvBlackAnalysis.setText("Black "+blackAnalysis);
        StringBuilder moves=new StringBuilder();
        for(int i=0;i<gameViewerMoves.size() && i<gameViewerPly;i++) { if(i%2==0) { if(moves.length()>0)moves.append(' '); moves.append((i/2+1)).append('.'); } else moves.append(' '); moves.append(gameViewerMoves.get(i)); }
        gvMoves.setText(moves.length()==0?"No moves yet.":moves.toString()); gvStatus.setText("Ply "+gameViewerPly+" / "+(gameViewerPositions.size()-1));
    }
    private String analysisFromComment(String c) {
        if(c==null||c.isEmpty()) return "Eval: ---  Depth: ---  Nodes: ---  NPS: ---";
        String eval=findCommentValue(c,"Eval|eval"); String depth=findCommentValue(c,"D|Depth"); String nodes=findCommentValue(c,"N|Nodes"); String nps=findCommentValue(c,"NPS|nps");
        return "Eval: "+dash(eval)+"  Depth: "+dash(depth)+"  Nodes: "+dash(nodes)+"  NPS: "+dash(nps);
    }
    private String[] extractPgnNames(String pgn) {
        String white="White", black="Black"; java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?m)^\\[White\\s+\"([^\"]*)\"\\]").matcher(pgn); if(m.find())white=m.group(1);
        m=java.util.regex.Pattern.compile("(?m)^\\[Black\\s+\"([^\"]*)\"\\]").matcher(pgn); if(m.find())black=m.group(1); return new String[]{white,black};
    }
    private String findAnalysis(int ply, boolean white) {
        if(ply<=0) return "Eval: ---  Depth: ---  Nodes: ---  NPS: ---";
        String c=ply-1<gameViewerComments.size()?gameViewerComments.get(ply-1):"";
        String eval=findCommentValue(c,"Eval|eval"); String depth=findCommentValue(c,"D|Depth"); String nodes=findCommentValue(c,"N|Nodes"); String nps=findCommentValue(c,"NPS|nps");
        return "Eval: "+dash(eval)+"  Depth: "+dash(depth)+"  Nodes: "+dash(nodes)+"  NPS: "+dash(nps);
    }
    private String dash(String v) { return v==null||v.isEmpty()?"---":v; }
    private String findCommentValue(String comment,String keys) {
        if(comment==null) return ""; String[] ks=keys.split("\\|");
        for(String k:ks) { java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?i)(?:^|[\\s,;])"+java.util.regex.Pattern.quote(k)+"\\s*[:=]\\s*([^\\s,;]+)").matcher(comment); if(m.find()) return m.group(1); }
        return "";
    }
    private void selectGameViewerAnalysisEngine() {
        if (availableEngines.isEmpty()) loadEngineChoices();
        String[] labels = new String[availableEngines.size()]; for (int i=0;i<labels.length;i++) labels[i]=availableEngines.get(i).name;
        new AlertDialog.Builder(this).setTitle("Select engine for live analysis")
            .setItems(labels,(d,w)->{ if(w>=0 && w<availableEngines.size()) { stopGameViewerAnalysis(); gameViewerEngineId=availableEngines.get(w).id; gameViewerSelectedWhite=true; Toast.makeText(this,"Selected "+availableEngines.get(w).name,Toast.LENGTH_SHORT).show(); if(gameViewerAnalysisOn) startGameViewerAnalysis(); } })
            .setNegativeButton("Cancel",null).show();
    }

    private void startEmptyGameViewer() {
        stopGameViewerAnalysis(); gameViewerPgn=""; gameViewerTree=new GameTree(null); gameViewerPositions.clear(); gameViewerPositions.add(new Position()); gameViewerMoves.clear(); gameViewerComments.clear(); gameViewerPly=0;
        setContentView(gameViewerView); renderGameViewerPly();
    }

    private void stopGameViewerAnalysis() {
        if (gameViewerEngine != null) { try { gameViewerEngine.stopSearch(); gameViewerEngine.shutdownEngine(); } catch (RuntimeException ignored) {} gameViewerEngine=null; }
        if (gameViewerBoard != null) gameViewerBoard.setMoveHints(null); gameViewerHints.clear();
    }

    private void startGameViewerAnalysis() {
        if (!gameViewerAnalysisOn || gameViewerEngineId.isEmpty() || gameViewerPositions.isEmpty()) return;
        stopGameViewerEngineOnly();
        gameViewerEngine = new DroidComputerPlayer(new SearchListener() {
            @Override public void notifyDepth(int id,int depth) { }
            @Override public void notifyCurrMove(int id,Position pos,Move m,int moveNr) { }
            @Override public void notifyStats(int id,long nodes,int nps,long tbHits,int hash,int time,int seldepth) { }
            @Override public void notifyBookInfo(int id,String bookInfo,ArrayList<Move> moveList,String eco,int distToEcoTree) { }
            @Override public void notifyEngineName(String name) { runOnUiThread(()->gvBlackName.setText(name)); }
            @Override public void reportEngineError(String errMsg) { runOnUiThread(()->Toast.makeText(TournamentActivity.this,"Analysis engine: "+errMsg,Toast.LENGTH_LONG).show()); }
            @Override public void notifyEngineInitialized() { }
            @Override public void notifySearchResult(int id,String cmd,Move ponder) { }
            @Override public void notifyPV(int id,Position pos,ArrayList<PvInfo> infos,Move ponderMove) {
                if (infos==null || infos.isEmpty() || infos.get(0)==null) return; PvInfo pv=infos.get(0);
                String eval=pv.isMate?((pv.score>=0?"+M":"-M")+Math.abs(pv.score)) : String.format(java.util.Locale.US,"%+.2f",pv.score/100.0);
                StringBuilder variation=new StringBuilder(); Position cursor=new Position(pos); ArrayList<Move> hints=new ArrayList<>();
                if(pv.pv!=null) for(Move m:pv.pv) { if(hints.size()<2) hints.add(m); String san=TextIO.moveToString(cursor,m,false,false); if(variation.length()>0)variation.append(' '); if(cursor.whiteMove)variation.append(cursor.fullMoveCounter).append('.'); variation.append(san); cursor.makeMove(m,new org.petero.droidfish.gamelogic.UndoInfo()); }
                String output="Eval: "+eval+"    Depth: "+pv.depth+"    Nodes: "+compactViewerCount(pv.nodes)+"    NPS: "+compactViewerCount(pv.nps)+"\n"+variation;
                runOnUiThread(()->{ if(!gameViewerAnalysisOn)return; gvWhiteAnalysis.setText(output); gvBlackAnalysis.setText("Black Eval: ---  Depth: ---  Nodes: ---  NPS: ---"); gameViewerHints.clear(); gameViewerHints.addAll(hints); gameViewerBoard.setMoveHints(gameViewerHints); });
            }
        });
        gameViewerEngine.setEngineOptions(new org.petero.droidfish.EngineOptions()); gameViewerEngine.setBookOptions(new BookOptions());
        int id=++gameViewerSearchId; gameViewerEngine.queueStartEngine(id,gameViewerEngineId);
        Position pos=new Position(gameViewerPositions.get(gameViewerPly));
        uiHandler.postDelayed(()->{ if(gameViewerEngine==null||!gameViewerAnalysisOn)return; try { gameViewerEngine.queueAnalyzeRequest(DroidComputerPlayer.SearchRequest.analyzeRequest(++gameViewerSearchId,pos,new ArrayList<Move>(),pos,false,gameViewerEngineId,1)); } catch(RuntimeException ex) { Toast.makeText(this,"Unable to start analysis: "+ex.getMessage(),Toast.LENGTH_LONG).show(); } },700);
    }
    private void stopGameViewerEngineOnly() { if(gameViewerEngine!=null) { try { gameViewerEngine.stopSearch(); gameViewerEngine.shutdownEngine(); } catch(RuntimeException ignored){} gameViewerEngine=null; } }
    private String compactViewerCount(long value) { if(value>=1_000_000)return String.format(java.util.Locale.US,"%.1fM",value/1_000_000.0); if(value>=1_000)return String.format(java.util.Locale.US,"%.0fK",value/1_000.0); return Long.toString(value); }

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
                .setPositiveButton("TOURNAMENT", (d, w) -> { currentTournamentScreen = 0; setContentView(playView); }).setNegativeButton("CLOSE", null).show();
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
                    case DRAW: result = "½-½"; break;
                    case BLACK_WIN: result = "0-1"; break;
                    default: result = "*"; break;
                }
                int endedAtMoves = countFullMoves(pairing.pgn);
                out.append(pairing.index + 1).append('.').append(white).append("  -  ").append(black)
                   .append("  (").append(result).append(") ").append(endedAtMoves).append(" moves").append('\n');
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
        e.putBoolean("tournamentDisableScreenTimeout", disableScreenTimeoutCheck != null && disableScreenTimeoutCheck.isChecked());
        e.putString("setupAutoWinEval", autoWinEvalInput.getText().toString());
        e.putBoolean("setupFastWin", fastWinRuleCheck.isChecked());
        e.putBoolean("setupDrawRules", drawRulesCheck != null && drawRulesCheck.isChecked());
        if (drawMoveRuleInput != null) e.putString("setupDrawMove", drawMoveRuleInput.getText().toString());
        if (ruleStartMoveInput != null) e.putString("setupRuleStartMove", ruleStartMoveInput.getText().toString());
        if (drawRepetitionInput != null) e.putString("setupDrawRep", drawRepetitionInput.getText().toString());
        if (drawMaximumInput != null) e.putString("setupDrawMax", drawMaximumInput.getText().toString());
        e.putString("setupOpeningPgn", selectedOpeningPgn);
        e.putString("setupOpeningName", selectedOpeningFileName);
        e.putString("setupOpeningBinPath", selectedOpeningBinPath);
        if (evalDrawScoreInput != null) e.putString("setupEvalDrawScore", evalDrawScoreInput.getText().toString());
        if (evalDrawConditionInput != null) e.putString("setupEvalDrawCondition", evalDrawConditionInput.getText().toString());
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
        showTournamentArrowsCheck.setChecked(p.getBoolean("setupArrows", true));
        if (disableScreenTimeoutCheck != null) disableScreenTimeoutCheck.setChecked(p.getBoolean("tournamentDisableScreenTimeout", false));
        autoWinEvalInput.setText(p.getString("setupAutoWinEval", "10"));
        fastWinRuleCheck.setChecked(p.getBoolean("setupFastWin", true));
        if (drawRulesCheck != null) drawRulesCheck.setChecked(p.getBoolean("setupDrawRules", true));
        if (drawMoveRuleInput != null) drawMoveRuleInput.setText(p.getString("setupDrawMove", "50"));
        if (ruleStartMoveInput != null) ruleStartMoveInput.setText(p.getString("setupRuleStartMove", "1"));
        if (drawRepetitionInput != null) drawRepetitionInput.setText(p.getString("setupDrawRep", "3"));
        if (drawMaximumInput != null) drawMaximumInput.setText(p.getString("setupDrawMax", "160"));
        if (evalDrawScoreInput != null) evalDrawScoreInput.setText(p.getString("setupEvalDrawScore", "0.12"));
        if (evalDrawConditionInput != null) evalDrawConditionInput.setText(p.getString("setupEvalDrawCondition", "6"));
        selectedOpeningBinPath = p.getString("setupOpeningBinPath", "");
        selectedOpeningPgn=p.getString("setupOpeningPgn", ""); selectedOpeningFileName=p.getString("setupOpeningName", selectedOpeningPgn.isEmpty()?"No PGN file selected":"Saved PGN book loaded"); openingFileText.setText(selectedOpeningFileName);
        selectedEngines.clear(); String ids=p.getString("setupEngineIds", ""); if (!ids.isEmpty()) for(String id:ids.split("\n")) for(EngineChoice c:availableEngines) if(c.id.equals(id)){selectedEngines.add(c); break;} updateSelectedText();
    }
    private String mirrorHeaderLabel() {
        return "Game " + (currentGameNumber == 0 ? "—" : currentGameNumber) + "/" +
                (currentGameTotal == 0 ? "—" : currentGameTotal) +
                "  TC " + mirrorTcLabel + "  T " + formatElapsedSeconds(currentEventElapsedMs());
    }

    private String formatElapsedTenths(long ms) {
        long safe = Math.max(0, ms);
        return String.format(java.util.Locale.US, "%02d:%02d:%d", safe / 3_600_000L,
                (safe / 60_000L) % 60L, (safe / 1_000L) % 60L) + "." + ((safe % 1000L) / 100L);
    }

    private String formatElapsedSeconds(long ms) {
        long seconds = Math.max(0L, ms) / 1000L;
        return String.format(java.util.Locale.US, "%d:%02d:%d", seconds / 3600L,
                (seconds / 60L) % 60L, seconds % 60L);
    }

    private long currentEventElapsedMs() {
        if (eventStartElapsedNs == 0L) return eventAccumulatedMs;
        return eventAccumulatedMs + Math.max(0L, (System.nanoTime() - eventStartElapsedNs) / 1_000_000L);
    }

    private void restoreAdvancedTimerSettings() {
        android.content.SharedPreferences p = getPreferences(MODE_PRIVATE);
        if (eventTimerMinutesInput != null) eventTimerMinutesInput.setText(p.getString("advancedEventTimerMinutes", "0"));
        if (heatPlayMinutesInput != null) heatPlayMinutesInput.setText(p.getString("advancedHeatPlayMinutes", "0"));
        if (heatPauseMinutesInput != null) heatPauseMinutesInput.setText(p.getString("advancedHeatPauseMinutes", "5"));
    }

    private void applyAdvancedTimers(boolean showToast) {
        try {
            int eventMins = Integer.parseInt(eventTimerMinutesInput.getText().toString().trim());
            int playMins = Integer.parseInt(heatPlayMinutesInput.getText().toString().trim());
            int pauseMins = Integer.parseInt(heatPauseMinutesInput.getText().toString().trim());
            if (eventMins < 0 || playMins < 0 || pauseMins < 0) throw new IllegalArgumentException("Timer values must be 0 or greater.");
            if (playMins > 0 && pauseMins == 0) throw new IllegalArgumentException("Set a pause interval greater than 0 when Timer 2 is enabled.");
            if (eventMins > 525600 || playMins > 525600 || pauseMins > 525600) throw new IllegalArgumentException("Timer values are too large.");
            getPreferences(MODE_PRIVATE).edit().putString("advancedEventTimerMinutes", String.valueOf(eventMins))
                    .putString("advancedHeatPlayMinutes", String.valueOf(playMins)).putString("advancedHeatPauseMinutes", String.valueOf(pauseMins)).apply();
            heatPlayDurationMs = playMins * 60_000L;
            heatPauseDurationMs = pauseMins * 60_000L;
            heatCycleEnabled = playMins > 0;
            heatCooling = false;
            heatPlayAccumulatedMs = 0L;
            heatLastResumeNs = runner != null && !runner.isPaused() && heatCycleEnabled ? System.nanoTime() : 0L;
            heatPhaseStartNs = 0L;
            if (showToast) Toast.makeText(this, "Advanced timers saved. They apply to the active tournament.", Toast.LENGTH_SHORT).show();
        } catch (RuntimeException ex) {
            Toast.makeText(this, "Invalid timer settings: " + ex.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void resetAdvancedTimerRuntime() {
        applyAdvancedTimers(false);
        eventAccumulatedMs = 0L;
        eventStartElapsedNs = System.nanoTime();
        eventLastResumeNs = eventStartElapsedNs;
        eventTimerHomeTriggered = false;
        heatCycleEnabled = heatPlayDurationMs > 0L;
        heatCooling = false;
        heatPlayAccumulatedMs = 0L;
        heatLastResumeNs = heatCycleEnabled ? System.nanoTime() : 0L;
        heatPhaseStartNs = 0L;
    }

    // Active-play elapsed clock: pauses do not consume Timer 1; resume continues from saved elapsed time.
    private void freezeEventElapsedClock() {
        if (eventStartElapsedNs != 0L) {
            eventAccumulatedMs += Math.max(0L, (System.nanoTime() - eventStartElapsedNs) / 1_000_000L);
            eventStartElapsedNs = 0L;
        }
    }
    private void resumeEventElapsedClock() {
        if (eventStartElapsedNs == 0L) eventStartElapsedNs = System.nanoTime();
        eventLastResumeNs = eventStartElapsedNs;
    }

    private void tickAutomaticTimers() {
        TournamentRunner r = runner;
        if (r == null || r.isFinished() || r.isStopped()) return;
        long now = System.nanoTime();
        if (!r.isPaused()) {
            if (eventLastResumeNs == 0L) resumeEventElapsedClock();
            if (heatCycleEnabled && !heatCooling && heatLastResumeNs != 0L) {
                heatPlayAccumulatedMs += Math.max(0L, (now - heatLastResumeNs) / 1_000_000L);
                heatLastResumeNs = now;
                if (heatPlayAccumulatedMs >= heatPlayDurationMs) {
                    r.pause();
                    heatCooling = true;
                    heatPhaseStartNs = now;
                    heatLastResumeNs = 0L;
                    freezeEventElapsedClock();
                    if (playPauseButton != null) playPauseButton.setText("Play");
                    if (moveStatusText != null) moveStatusText.setText("AUTO PAUSE · Cooling interval");
                }
            }
        } else if (activityVisible && heatCycleEnabled && heatCooling && heatPhaseStartNs != 0L && !eventTimerHomeTriggered) {
            if ((now - heatPhaseStartNs) / 1_000_000L >= heatPauseDurationMs) {
                heatCooling = false;
                heatPhaseStartNs = 0L;
                heatPlayAccumulatedMs = 0L;
                heatLastResumeNs = now;
                resumeEventElapsedClock();
                r.resume();
                if (playPauseButton != null) playPauseButton.setText("P");
                if (moveStatusText != null) moveStatusText.setText("AUTO PLAY · Tournament resumed");
            }
        }
        if (mirrorHeaderText != null && isMirrorShowing()) mirrorHeaderText.setText(mirrorHeaderLabel());
        int eventMinutes = 0;
        try { eventMinutes = Integer.parseInt(eventTimerMinutesInput.getText().toString().trim()); } catch (RuntimeException ignored) { }
        if (eventMinutes > 0 && !eventTimerHomeTriggered && currentEventElapsedMs() >= eventMinutes * 60_000L) {
            eventTimerHomeTriggered = true;
            freezeEventElapsedClock();
            heatCycleEnabled = false;
            if (!r.isPaused()) r.pause();
            autoPaused = false;
            if (playPauseButton != null) playPauseButton.setText("Play");
            if (moveStatusText != null) moveStatusText.setText("TIMER 1 · Tournament paused; returning Home");
            Intent main = new Intent(this, DroidFish.class);
            main.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(main);
            Toast.makeText(this, "Timer 1 finished. Tournament paused; return to resume.", Toast.LENGTH_LONG).show();
        }
    }

    private void refreshLiveClocks() {
        TournamentRunner r=runner; if(r==null || playView==null) return;
        if (r.isPaused()) return;
        long w=r.remainingMs(true), b=r.remainingMs(false);
        if(whiteClockText!=null && w>=0) whiteClockText.setText(formatClock(w));
        if(blackClockText!=null && b>=0) blackClockText.setText(formatClock(b));
        long elapsed=r.activeMoveTimeMs();
        if(elapsed>=0) { boolean white=r.whiteToMove(); TextView t=white?whiteMoveTimeText:blackMoveTimeText; if(t!=null)t.setText(formatClockDuration(elapsed)); }
        if (mirrorView != null && mirrorView.getParent() != null) syncMirrorFromLive();
    }

    private void startTournament() {
        // Never tear down a live runner and immediately launch another pair of engines.
        // That raced engine shutdown in the past and could return the app to the main board.
        // Finish/stop the current tournament first, then start a new saved event.
        if (runner != null) {
            Toast.makeText(this, "A tournament is already active. Stop or finish it before starting a new tournament.", Toast.LENGTH_LONG).show();
            return;
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
            if (openingPlies > 0 && selectedOpeningPgn.trim().isEmpty() && selectedOpeningBinPath.trim().isEmpty())
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
            tournament.setOpeningBinPath(selectedOpeningBinPath);
            tournament.save();
            launchTournament(tournament);
        } catch (Exception e) {
            TournamentRunner failedRunner = runner;
            runner = null;
            if (failedRunner != null) {
                try { failedRunner.stop(); } catch (RuntimeException ignored) { }
            }
            if (startButton != null) startButton.setEnabled(true);
            if (resumeButton != null) resumeButton.setEnabled(true);
            if (stopButton != null) stopButton.setEnabled(false);
            // A failed start must return to setup instead of leaving a half-initialized board.
            if (setupView != null) setContentView(setupView);
            Toast.makeText(this, "Could not start tournament: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_GAME_VIEWER_PGN && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri viewerUri = data.getData();
            try { getContentResolver().takePersistableUriPermission(viewerUri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (SecurityException ignored) { }
            loadGameViewerPgn(viewerUri); return;
        }
        if (requestCode == REQUEST_OPENING_BIN && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri binUri = data.getData();
            new Thread(() -> {
                try (InputStream in = getContentResolver().openInputStream(binUri)) {
                    if (in == null) throw new IOException("Could not open selected BIN book");
                    File target = new File(getFilesDir(), "tournament_opening_book_" + System.currentTimeMillis() + ".bin");
                    try (FileOutputStream out = new FileOutputStream(target, false)) { byte[] buffer = new byte[32768]; int n; while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n); }
                    if (target.length() < 16 || target.length() % 16 != 0) throw new IOException("Invalid Polyglot BIN file size");
                    String name = queryDisplayName(binUri);
                    runOnUiThread(() -> { selectedOpeningBinPath = target.getAbsolutePath(); selectedOpeningFileName = name + " · BIN book loaded (takes priority over PGN while selected)"; openingFileText.setText(selectedOpeningFileName); saveSetup(); Toast.makeText(this, "Polyglot BIN book selected", Toast.LENGTH_LONG).show(); });
                } catch (Exception e) { runOnUiThread(() -> Toast.makeText(this, "Could not read BIN book: " + e.getMessage(), Toast.LENGTH_LONG).show()); }
            }, "BIN-book-loader").start();
            return;
        }
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
                    selectedOpeningBinPath = "";
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

    private void updateTurnNameLabels() {
        if (whiteNameText != null) whiteNameText.setText(currentWhiteName);
        if (blackNameText != null) blackNameText.setText(currentBlackName);
    }

    /** Flip player panels together with the board, preserving each side's data. */
    private void toggleTournamentFlip() {
        if (tournamentBoard != null) tournamentBoard.setFlipped(!tournamentBoard.flipped);
        if (mirrorBoard != null) { mirrorBoard.setFlipped(!mirrorBoard.flipped); mirrorBoard.invalidate(); }
        tournamentUiFlipped = !tournamentUiFlipped;
        mirrorUiFlipped = !mirrorUiFlipped;
        if (mirrorPage != null && mirrorBlackRow != null && mirrorWhiteRow != null) {
            mirrorPage.removeView(mirrorBlackRow);
            mirrorPage.removeView(mirrorWhiteRow);
            int boardIndex = mirrorPage.indexOfChild((View) mirrorBoard.getParent());
            if (mirrorUiFlipped) {
                mirrorPage.addView(mirrorWhiteRow, Math.max(0, boardIndex));
                mirrorPage.addView(mirrorBlackRow, Math.min(mirrorPage.getChildCount(), boardIndex + 2));
            } else {
                mirrorPage.addView(mirrorBlackRow, Math.max(0, boardIndex));
                mirrorPage.addView(mirrorWhiteRow, Math.min(mirrorPage.getChildCount(), boardIndex + 2));
            }
        }
        if (playPage == null || blackClockRow == null || whiteClockRow == null ||
                blackAnalysisText == null || whiteAnalysisText == null || tournamentBoard == null || tournamentBoard.getParent() == null) return;
        View boardFrame = (View) tournamentBoard.getParent();
        playPage.removeView(blackAnalysisText);
        playPage.removeView(blackClockRow);
        playPage.removeView(whiteClockRow);
        playPage.removeView(whiteAnalysisText);
        playPage.removeView(boardFrame);
        if (tournamentUiFlipped) {
            playPage.addView(whiteAnalysisText, 1);
            playPage.addView(whiteClockRow, 2);
            playPage.addView(boardFrame, 3);
            playPage.addView(blackClockRow, 4);
            playPage.addView(blackAnalysisText, 5);
        } else {
            playPage.addView(blackAnalysisText, 1);
            playPage.addView(blackClockRow, 2);
            playPage.addView(boardFrame, 3);
            playPage.addView(whiteClockRow, 4);
            playPage.addView(whiteAnalysisText, 5);
        }
    }

    private void resumeTournament() {
        chooseSavedTournament();
    }

    private void launchTournament(final TournamentManager tournament) {
        activeTournamentManager = tournament;
        double autoWinThreshold = 10.0;
        try { autoWinThreshold = Double.parseDouble(autoWinEvalInput.getText().toString().trim()); }
        catch (RuntimeException ignored) { autoWinThreshold = 10.0; }
        if (!(autoWinThreshold > 0.0) || Double.isNaN(autoWinThreshold) || Double.isInfinite(autoWinThreshold)) autoWinThreshold = 10.0;
        final double configuredAutoWinThreshold = autoWinThreshold;
        final boolean fastWinEnabled = fastWinRuleCheck != null && fastWinRuleCheck.isChecked();
        runner = new TournamentRunner(tournament, new TournamentRunner.Listener() {
            @Override public void onGameStarted(TournamentManager.Pairing pairing, String white, String black) {
                runOnUiThread(() -> {
                    // Keep the user's chosen board. A new game must not force Mirror Board
                    // back to the live board; Switch is the only board-selection control.
                    if (!isMirrorShowing()) setContentView(playView);
                    if (tournamentTitleText != null) tournamentTitleText.setText(tournament.title());
                    if (playPauseButton != null) playPauseButton.setText(runner != null && runner.isPaused() ? "Play" : "Pause");
                    currentGameNumber = pairing.index + 1; currentGameTotal = tournament.pairings().size();
                    double incrementSeconds = tournament.incrementMs() / 1000.0;
                    String incrementLabel = (incrementSeconds == Math.rint(incrementSeconds))
                            ? String.format(java.util.Locale.US, "%.0f", incrementSeconds)
                            : String.format(java.util.Locale.US, "%.1f", incrementSeconds);
                    // Display the base time as an actual clock duration, not decimal minutes.
                    // Example: 0.5 minutes -> 0:30; 1.7 minutes -> 1:42.
                    long baseSeconds = Math.max(0L, Math.round(tournament.baseTimeMs() / 1000.0));
                    String baseLabel;
                    if (baseSeconds >= 60) {
                        baseLabel = String.format(java.util.Locale.US, "%d:%02d", baseSeconds / 60, baseSeconds % 60);
                    } else {
                        baseLabel = String.format(java.util.Locale.US, "0:%02d", baseSeconds);
                    }
                    mirrorTcLabel = String.format(java.util.Locale.US, "%s+%s", baseLabel, incrementLabel);
                    if (mirrorHeaderText != null) mirrorHeaderText.setText(mirrorHeaderLabel());
                    statusText.setText("Game " + currentGameNumber + "/" + currentGameTotal + " " + tournament.title());
                    if (openingStartInput != null && tournament.repeatOpening()) openingStartInput.setText(String.valueOf(tournament.openingStartIndex() + Math.max(0, (pairing.gameNumber - 1) / 2)));
                    currentPgnMoves = "";
                    mirrorPositions.clear(); mirrorPly = -1; mirrorNavigationAllowed = false;
                    mirrorPositions.add(new Position(latestPosition));
                    whiteHintMove = null; blackHintMove = null;
                    if (tournamentBoard != null) { tournamentBoard.setMoveHints(null); tournamentBoard.setSelection(-1); }
                    if (mirrorBoard != null) mirrorBoard.setSelection(-1);
                    moveStatusText.setText("PGN: —");
                    if (openingText != null) openingText.setText("Opening: Starting position");
                    if (whiteMoveTimeText != null) whiteMoveTimeText.setText("—");
                    if (blackMoveTimeText != null) blackMoveTimeText.setText("—");
                    currentWhiteName = white;
                    currentBlackName = black;
                    updateTurnNameLabels();
                });
            }
            @Override public void onPositionChanged(Position position, boolean whiteToMove,
                                                    long whiteTimeMs, long blackTimeMs,
                                                    String whiteName, String blackName, String pgnMoves) {
                onPositionChanged(position, whiteToMove, whiteTimeMs, blackTimeMs, whiteName, blackName, pgnMoves, null);
            }
            @Override public void onPositionChanged(Position position, boolean whiteToMove,
                                                    long whiteTimeMs, long blackTimeMs,
                                                    String whiteName, String blackName, String pgnMoves,
                                                    org.petero.droidfish.gamelogic.Move lastMove) {
                final org.petero.droidfish.gamelogic.Move moveToHighlight = lastMove;
                runOnUiThread(() -> {
                    latestPosition = new Position(position);
                    whiteHintMove = null;
                    blackHintMove = null;
                    if (tournamentBoard != null) tournamentBoard.setMoveHints(null);
                    currentWhiteToMove = whiteToMove;
                    updateTurnNameLabels();
                    currentPgnMoves = pgnMoves == null ? "" : pgnMoves;
                    if (mirrorPositions.isEmpty() || !samePosition(mirrorPositions.get(mirrorPositions.size()-1), position)) mirrorPositions.add(new Position(position));
                    if (isMirrorShowing() && mirrorPly < 0) syncMirrorFromLive();
                    if (tournamentBoard != null && !isMirrorShowing()) {
                        tournamentBoard.setPosition(position);
                        tournamentBoard.setSelection(tournamentBoard.highlightLastMove && moveToHighlight != null ? moveToHighlight.to : -1);
                    }
                    if (mirrorMovesText != null) updateMirrorMoveLinks();
                    if (mirrorBoard != null && isMirrorShowing() && mirrorPly < 0) {
                        mirrorBoard.setPosition(position);
                        mirrorBoard.setSelection(mirrorBoard.highlightLastMove && moveToHighlight != null ? moveToHighlight.to : -1);
                    }
                    whiteClockText.setText(formatClock(whiteTimeMs));
                    blackClockText.setText(formatClock(blackTimeMs));
                    whiteClockText.setAlpha(whiteToMove ? 1.0f : 0.72f);
                    blackClockText.setAlpha(whiteToMove ? 0.72f : 1.0f);
                    moveStatusText.setText("PGN: " + (currentPgnMoves.isEmpty() ? "—" : currentPgnMoves));
                });
            }
            @Override public void onEngineAnalysis(boolean white, String analysis) {
                runOnUiThread(() -> {
                    TextView target = isMirrorShowing() ? null : (white ? whiteAnalysisText : blackAnalysisText);
                    if (white) lastWhiteAnalysis = analysis; else lastBlackAnalysis = analysis;
                    if (isMirrorShowing() && mirrorAnalysisOn && mirrorAnalysisText != null) mirrorAnalysisText.setText(mirrorAnalysisWhite ? lastWhiteAnalysis : lastBlackAnalysis);
                    if (target != null) {
                        android.text.SpannableString styled = new android.text.SpannableString(analysis);
                        int variationStart = analysis.indexOf('\n');
                        if (variationStart >= 0) variationStart = analysis.indexOf('\n', variationStart + 1);
                        if (variationStart >= 0 && variationStart + 1 < analysis.length()) {
                            styled.setSpan(new android.text.style.RelativeSizeSpan(0.75f), variationStart + 1,
                                    analysis.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                        target.setText(styled);
                    }
                    if (!isMirrorShowing() && tournamentBoard != null && showTournamentArrowsCheck != null && showTournamentArrowsCheck.isChecked()
                            && android.preference.PreferenceManager.getDefaultSharedPreferences(TournamentActivity.this).getString("thinkingArrows", "4").equals("0") == false) {
                        try {
                            int variationStart = analysis.indexOf('\n');
                            if (variationStart >= 0) variationStart = analysis.indexOf('\n', variationStart + 1);
                            if (variationStart >= 0 && variationStart + 1 < analysis.length()) {
                                String pvLine = analysis.substring(variationStart + 1).trim();
                                // PVs are SAN and may start with "60...Rg2" or "61.Rh5".
                                pvLine = pvLine.replaceFirst("^\\d+\\.{1,3}", "");
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
                    } else if (!isMirrorShowing() && tournamentBoard != null) { whiteHintMove = null; blackHintMove = null; tournamentBoard.setMoveHints(null); }
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
                onGameFinished(pairing, outcome, pgn, "adjudication");
            }
            @Override public void onGameFinished(TournamentManager.Pairing pairing, TournamentManager.Outcome outcome, String pgn, String reason) {
                // Persist the cumulative PGN immediately on the callback thread, not only in a
                // queued UI update: the Activity may be backgrounded or destroyed right now.
                Exception autoSaveError = null;
                try { saveTournamentPgnAutomatically(tournament); }
                catch (Exception e) { autoSaveError = e; }
                final Exception savedError = autoSaveError;
                runOnUiThread(() -> {
                    String resultText;
                    switch (outcome) {
                        case WHITE_WIN: resultText = "WHITE WINS"; break;
                        case BLACK_WIN: resultText = "BLACK WINS"; break;
                        case DRAW: resultText = "DRAW"; break;
                        default: resultText = "GAME FINISHED"; break;
                    }
                    String reasonText = reason == null || reason.trim().isEmpty() ? "Game result" : reason.trim();
                    if (!reasonText.isEmpty()) reasonText = Character.toUpperCase(reasonText.charAt(0)) + reasonText.substring(1);
                    int progress = pairing.index + 1;
                    int total = tournament.pairings().size();
                    String result = "Game " + progress + "/" + total + " " + resultText + ": " + reasonText;
                    statusText.setText(result);
                    moveStatusText.setText(result);
                    // The normal game-start and position callbacks update these labels
                    // immediately after the 500 ms inter-game pause. Announce the result
                    // independently so it is actually visible to the user.
                    android.widget.Toast.makeText(TournamentActivity.this, result,
                            android.widget.Toast.LENGTH_LONG).show();
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
                    runner = null; applyTournamentKeepScreenOn(); startButton.setEnabled(true); resumeButton.setEnabled(true); stopButton.setEnabled(runner != null);
                });
            }
            @Override public void onError(String message, Throwable error) {
                runOnUiThread(() -> {
                    statusText.setText("Tournament error: " + message + (error == null ? "" : " (" + error.getMessage() + ")"));
                    if (runner != null) { runner.stop(); runner = null; }
        applyTournamentKeepScreenOn();
                    applyTournamentKeepScreenOn();
                    startButton.setEnabled(true); resumeButton.setEnabled(true); stopButton.setEnabled(runner != null);
                });
            }
        }, new EngineOptions(), new BookOptions(), tournament.baseTimeMs(), tournament.incrementMs(),
                tournament.openingPgn(), tournament.openingPlies(), tournament.repeatOpening(), tournament.openingStartIndex(),
                configuredAutoWinThreshold, fastWinEnabled);
        startButton.setEnabled(false); resumeButton.setEnabled(false); stopButton.setEnabled(true);
        if (playPauseButton != null) playPauseButton.setText("P");
        if (tournamentTitleText != null) tournamentTitleText.setText(tournament.title());
        setContentView(playView);
        int moveRule = 50, repetitionRule = 3, maximumMoves = 160, ruleStartMove = 1;
        try { moveRule = Integer.parseInt(drawMoveRuleInput.getText().toString().trim()); } catch (RuntimeException ignored) { }
        try { repetitionRule = Integer.parseInt(drawRepetitionInput.getText().toString().trim()); } catch (RuntimeException ignored) { }
        try { maximumMoves = Integer.parseInt(drawMaximumInput.getText().toString().trim()); } catch (RuntimeException ignored) { }
        try { ruleStartMove = Integer.parseInt(ruleStartMoveInput.getText().toString().trim()); } catch (RuntimeException ignored) { }
        runner.configureDrawRules(drawRulesCheck.isChecked(), moveRule, repetitionRule, maximumMoves, ruleStartMove);
        double evalDrawScore = 0.12; int evalDrawCondition = 6;
        try { evalDrawScore = Double.parseDouble(evalDrawScoreInput.getText().toString().trim()); } catch (RuntimeException ignored) { }
        try { evalDrawCondition = Integer.parseInt(evalDrawConditionInput.getText().toString().trim()); } catch (RuntimeException ignored) { }
        runner.configureEvaluationDrawRule(evalDrawScore, evalDrawCondition);
        saveSetup();
        runner.start();
        applyTournamentKeepScreenOn();
    }

    private int countFullMoves(String pgn) {
        if (pgn == null || pgn.trim().isEmpty()) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:^|\\s)(\\d+)\\.").matcher(pgn);
        int max = 0; while (m.find()) try { max = Math.max(max, Integer.parseInt(m.group(1))); } catch (NumberFormatException ignored) { }
        return max;
    }

    private void stopAndSaveTournament() {
        if (runner == null) { setContentView(playView); return; }
        TournamentRunner oldRunner = runner;
        try {
            TournamentManager activeTournament = activeTournamentManager;
            if (activeTournament == null) activeTournament = TournamentManager.restore(this);
            oldRunner.pause();
            activeTournament.save();
            saveTournamentPgnAutomatically(activeTournament);
            oldRunner.stop();
            runner = null;
            startButton.setEnabled(true);
            resumeButton.setEnabled(true);
            stopButton.setEnabled(false);
            statusText.setText("Tournament stopped and saved. Use Resume Saved Tournament to continue.");
            Toast.makeText(this, "Tournament saved", Toast.LENGTH_LONG).show();
            setContentView(setupView);
        } catch (Exception e) {
            Toast.makeText(this, "Could not save tournament: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
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
            // Timer 1 is a repeating play-session limit: each manual Play starts a fresh interval.
            eventAccumulatedMs = 0L;
            eventStartElapsedNs = System.nanoTime();
            eventLastResumeNs = eventStartElapsedNs;
            eventTimerHomeTriggered = false;
            if (heatCooling) { heatCooling = false; heatPhaseStartNs = 0L; heatPlayAccumulatedMs = 0L; }
            resumeEventElapsedClock();
            if (heatPlayDurationMs > 0L) heatCycleEnabled = true;
            if (heatCycleEnabled) heatLastResumeNs = System.nanoTime();
            runner.resume();
            autoPaused = false;
            playPauseButton.setText("P");
            moveStatusText.setText("Resuming tournament…");
        } else {
            runner.pause();
            freezeEventElapsedClock();
            heatLastResumeNs = 0L;
            // A manual pause cancels the automatic cooling wait; the next Play starts a fresh cycle.
            heatCooling = false; heatPhaseStartNs = 0L; heatPlayAccumulatedMs = 0L;
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
        heatCycleEnabled = false; heatCooling = false; heatLastResumeNs = 0L; freezeEventElapsedClock();
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
        activityVisible = false;
        saveSetup();
        if (runner != null && !runner.isPaused()) {
            runner.pause();
            freezeEventElapsedClock();
            heatLastResumeNs = 0L;
            autoPaused = true;
            if (playPauseButton != null) playPauseButton.setText("Play");
        }
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        activityVisible = true;
        // Do not auto-resume when returning from Home/Recents. The user explicitly taps Play.
        if (runner != null && runner.isPaused() && playPauseButton != null) playPauseButton.setText("Play");
    }

    @Override protected void onDestroy() {
        stopGameViewerAnalysis();
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
