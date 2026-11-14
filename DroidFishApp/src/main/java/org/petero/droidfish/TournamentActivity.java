package org.petero.droidfish;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.net.Uri;
import android.database.Cursor;
import android.provider.DocumentsContract;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.BitmapDrawable;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import android.app.AlertDialog;
import android.app.ProgressDialog;
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
import android.widget.ListView;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
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
import org.petero.droidfish.tournament.CupTournamentManager;
import org.petero.droidfish.tournament.TournamentRunner;
import org.json.JSONException;
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
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

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
    private TextView mirrorBlackAnalysisText, mirrorWhiteAnalysisText, mirrorWhiteClock, mirrorBlackClock,
            mirrorWhiteName, mirrorBlackName, mirrorWhiteMoveTime, mirrorBlackMoveTime,
            mirrorStatusText, mirrorHeaderText, mirrorEventTitleText, mirrorSectionTitle;
    private LinearLayout mirrorPage, mirrorBlackRow, mirrorWhiteRow, mirrorSectionHost, mirrorControlBar;
    private ScrollView mirrorScroll, mirrorBlackAnalysisScroll, mirrorWhiteAnalysisScroll;
    private View mirrorBoardFrame;
    private boolean mirrorBoardOnlyMode;
    private Button mirrorMovesTab, mirrorStandingsTab, mirrorScheduleTab, mirrorInfoTab, mirrorMovesPlayButton, mirrorPauseButton;
    private Button mirrorMoveListTab, mirrorMoveAnalysisTab, mirrorGameInfoTab;
    private Button mirrorStandingsTableTab, mirrorGraphTab, mirrorCrosstableTab;
    private int mirrorMovesSubTab = 0, mirrorStandingsSubTab = 0, mirrorGraphMetric = 0;
    private ImageView mirrorWhiteIcon, mirrorBlackIcon;
    private Button mirrorAnalysisToggle, mirrorAnalysisChooseEngine;
    private TextView mirrorAnalysisOutput;
    private DroidComputerPlayer mirrorAnalysisEngine;
    private String mirrorAnalysisEngineId = "";
    private boolean mirrorAnalysisOn;
    private int mirrorAnalysisSearchId = 61000;
    private final ArrayList<Move> mirrorAnalysisHints = new ArrayList<>();
    private String mirrorReplayPgn = "";
    private CheckBox engineIconsCheck;
    private Button engineIconFolderButton, engineIconConfigureButton;
    private Uri engineIconFolderUri;
    private final Set<String> engineIconDisabledIds = new HashSet<>();
    private final HashMap<String, Uri> engineIconUris = new HashMap<>();
    private final HashMap<String, Bitmap> engineIconBitmaps = new HashMap<>();
    private boolean engineIconFolderScanned;
    private boolean restoringSetup;
    private static final int REQUEST_ENGINE_ICON_FOLDER = 7318;
    private ListView mirrorScheduleList;
    private final ArrayList<String> mirrorScheduleRows = new ArrayList<>();
    private final ArrayList<Integer> mirrorSchedulePairingIndexes = new ArrayList<>();
    private boolean mirrorUiFlipped = false;
    private int mirrorSelectedTab = 0; // 0 = Moves, 1 = Standings, 2 = Schedule, 3 = Info
    private boolean mirrorReplayMode = false, mirrorReplayLiveSnapshot = false, mirrorAutoPlay = false;
    private int mirrorReplayPairingIndex = -1;
    private String mirrorReplayWhiteEngineId = "", mirrorReplayBlackEngineId = "";
    private final ArrayList<Position> mirrorReplayPositions = new ArrayList<>();
    private final ArrayList<String> mirrorReplayMoves = new ArrayList<>(), mirrorReplayComments = new ArrayList<>();
    private String mirrorReplayWhiteName = "White", mirrorReplayBlackName = "Black";
    private String currentWhiteEngineId = "", currentBlackEngineId = "";
    private final HashMap<String, Integer> mirrorRanks = new HashMap<>();
    private final Handler mirrorAutoPlayHandler = new Handler(Looper.getMainLooper());
    private Runnable mirrorAutoPlayRunnable;
    private int currentGameNumber = 0, currentGameTotal = 0;
    private TournamentManager activeTournamentManager;
    private String mirrorTcLabel = "—";
    private final ArrayList<Position> mirrorPositions = new ArrayList<>();
    private boolean mirrorLiveHistoryInitialized;
    private int mirrorPly = -1;
    private String lastWhiteAnalysis = "Eval: —", lastBlackAnalysis = "Eval: —";
    private long lastWhiteTimeMs, lastBlackTimeMs;
    private CheckBox showTournamentArrowsCheck;
    private CheckBox disableScreenTimeoutCheck;
    private Position latestPosition = new Position();
    private float touchDownX, touchDownY;
    private Button previousMoveButton, nextMoveButton;
    private ChessBoardPlay tournamentBoard;
    private View setupView, playView;
    private AlertDialog cachedScheduleDialog;
    private TournamentManager cachedScheduleTournament;
    private ListView cachedScheduleList;
    private ArrayAdapter<String> cachedScheduleAdapter;
    private final ArrayList<String> cachedScheduleRows = new ArrayList<>();
    private String cachedScheduleContents = "";
    private Button cachedScheduleAnomaliesButton, cachedScheduleQueueButton;
    private boolean cachedScheduleDirty = true;
    private AlertDialog cachedAnomalyDialog;
    private volatile boolean tournamentLoadInProgress;
    private TournamentManager cachedAnomalyTournament;
    private TextView cachedAnomalySummary;
    private LinearLayout cachedAnomalyRows;
    private Spinner formatSpinner, timeModeSpinner, swissSeedingSpinner, cupSeedingSpinner;
    private EditText titleInput, gamesInput, swissRoundsInput, baseInput, incrementInput, limitInput, openingPliesInput, openingStartInput, autoWinEvalInput;
    private TextView swissSetupHint, cupSetupHint, cupSeedingLabel;
    private Button swissRankEditorButton, cupRankEditorButton, cachedScheduleBracketButton;
    private final ArrayList<EngineChoice> swissRankedEngines = new ArrayList<>();
    private final ArrayList<EngineChoice> cupRankedEngines = new ArrayList<>();
    private boolean swissRankOrderConfirmed, cupRankOrderConfirmed;
    private boolean forcedPairFormat;
    private boolean repeatOpeningBeforeForcedPairFormat;
    private String gamesPerPairBeforeForcedPairFormat = "2";
    private CheckBox fastWinRuleCheck;
    private TextView openingFileText;
    private String selectedOpeningPgn = "";
    private String selectedOpeningBinPath = "";
    private String currentPgnMoves = "";
    private org.petero.droidfish.gamelogic.Move whiteHintMove, blackHintMove;
    private String selectedOpeningFileName = "No PGN file selected";
    private static final int REQUEST_OPENING_PGN = 7314;
    private static final int REQUEST_OPENING_BIN = 7316;
    private static final int REQUEST_TOURNAMENT_JSON = 7317;
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
    // Tournament-wide elapsed time is separate from Timer 1 session time.
    private long tournamentElapsedAccumulatedMs = 0L;
    private long tournamentElapsedStartNs = 0L;
    private long lastTournamentElapsedSaveNs = 0L;
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

        Button choose = new Button(this);
        choose.setText("Select tournament engines");
        root.addView(choose, fullWidth());
        selectedText = text("No engines selected"); root.addView(selectedText, fullWidth());
        choose.setOnClickListener(v -> chooseEngines());

        engineIconsCheck = new CheckBox(this);
        engineIconsCheck.setText("Show engine icons on Mirror Board");
        root.addView(engineIconsCheck, fullWidth());
        engineIconsCheck.setOnCheckedChangeListener((button, checked) -> {
            if (restoringSetup) return;
            saveSetup();
            engineIconBitmaps.clear();
            if (isMirrorShowing()) { updateMirrorRankedNames(); renderMirrorSection(); }
        });
        engineIconFolderButton = new Button(this);
        engineIconFolderButton.setText("Choose engine icon folder (PNG files)");
        root.addView(engineIconFolderButton, fullWidth());
        engineIconFolderButton.setOnClickListener(v -> chooseEngineIconFolder());
        engineIconConfigureButton = new Button(this);
        engineIconConfigureButton.setText("Enable / disable icons for engines");
        root.addView(engineIconConfigureButton, fullWidth());
        engineIconConfigureButton.setOnClickListener(v -> configureEngineIcons());

        root.addView(text("Tournament format"), fullWidth());
        formatSpinner = new Spinner(this);
        formatSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Round Robin", "Match", "Gauntlet (first engine vs rest)", "Swiss", "Cup"}));
        root.addView(formatSpinner, fullWidth());

        titleInput = field("Tournament name", "My Tournament"); root.addView(titleInput, fullWidth());
        gamesInput = field("Games per pairing", "2"); root.addView(gamesInput, fullWidth());
        swissRoundsInput = field("Swiss rounds (blank = automatic)", "");
        swissRoundsInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        swissRoundsInput.setVisibility(View.GONE);
        root.addView(swissRoundsInput, fullWidth());
        root.addView(text("Swiss initial seeding"), fullWidth());
        swissSeedingSpinner = new Spinner(this);
        swissSeedingSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Choose Swiss seeding method", "Random (independent Swiss)", "Rank engine (continuous tournament / MCEC)"}));
        swissSeedingSpinner.setVisibility(View.GONE);
        root.addView(swissSeedingSpinner, fullWidth());
        swissRankEditorButton = new Button(this);
        swissRankEditorButton.setText("Edit engine ranking (strongest to weakest)");
        swissRankEditorButton.setVisibility(View.GONE);
        root.addView(swissRankEditorButton, fullWidth());
        swissRankEditorButton.setOnClickListener(v -> showSwissRankEditor());
        swissSetupHint = text("Choose Random for an independent Swiss draw, or Rank engine for a continuous tournament. Each opponent pairing plays two games with colors reversed.");
        swissSetupHint.setVisibility(View.GONE);
        root.addView(swissSetupHint, fullWidth());
        cupSeedingLabel = text("Cup initial seeding");
        cupSeedingLabel.setVisibility(View.GONE);
        root.addView(cupSeedingLabel, fullWidth());
        cupSeedingSpinner = new Spinner(this);
        cupSeedingSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Choose Cup seeding method", "Random (independent Cup)", "Rank engine (continuous tournament / MCEC)"}));
        cupSeedingSpinner.setVisibility(View.GONE);
        root.addView(cupSeedingSpinner, fullWidth());
        cupRankEditorButton = new Button(this);
        cupRankEditorButton.setText("Edit engine ranking (strongest to weakest)");
        cupRankEditorButton.setVisibility(View.GONE);
        root.addView(cupRankEditorButton, fullWidth());
        cupRankEditorButton.setOnClickListener(v -> showCupRankEditor());
        cupSetupHint = text("Cup uses stage-specific match lengths, reversed-color game pairs, and a knockout bracket. Default field size is 32.");
        cupSetupHint.setVisibility(View.GONE);
        root.addView(cupSetupHint, fullWidth());
        cupSeedingSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { updateCupSeedingUi(); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        swissSeedingSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                updateSwissSeedingUi();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        root.addView(text("Time control mode"), fullWidth());
        timeModeSpinner = new Spinner(this);
        timeModeSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Time Clock: Time + Increment", "Time per move", "Depth per move", "Nodes per move", "Move/Minute (e.g. 40/15)"}));
        root.addView(timeModeSpinner, fullWidth());
        TextView movePeriodMinutesLabel = text("Minutes per period");
        movePeriodMinutesLabel.setVisibility(View.GONE);
        root.addView(movePeriodMinutesLabel, fullWidth());
        baseInput = field("Base time per side (minutes; 0.5 = 30 seconds)", "0.5"); root.addView(baseInput, fullWidth());
        incrementInput = field("Increment per move (seconds; 0.5 = half a second)", "0.5"); root.addView(incrementInput, fullWidth());
        TextView movePeriodMovesLabel = text("Moves per period");
        movePeriodMovesLabel.setVisibility(View.GONE);
        root.addView(movePeriodMovesLabel, fullWidth());
        limitInput = field("Time per move (seconds), depth, or nodes", "2"); root.addView(limitInput, fullWidth());
        timeModeSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                boolean clockMode = position == 0;
                boolean movePeriodMode = position == 4;
                baseInput.setVisibility(clockMode || movePeriodMode ? View.VISIBLE : View.GONE);
                incrementInput.setVisibility(clockMode ? View.VISIBLE : View.GONE);
                limitInput.setVisibility(clockMode ? View.GONE : View.VISIBLE);
                movePeriodMinutesLabel.setVisibility(movePeriodMode ? View.VISIBLE : View.GONE);
                movePeriodMovesLabel.setVisibility(movePeriodMode ? View.VISIBLE : View.GONE);
                if (clockMode) {
                    baseInput.setHint("Base time per side (minutes; 0.5 = 30 seconds)");
                    incrementInput.setHint("Increment per move (seconds; 0.5 = half a second)");
                } else if (position == 1) {
                    limitInput.setHint("Time per move in seconds (e.g. 2 or 5)");
                } else if (position == 2) {
                    limitInput.setHint("Depth per move (e.g. 20)");
                    if ("2".equals(limitInput.getText().toString())) limitInput.setText("20");
                } else if (position == 3) {
                    limitInput.setHint("Nodes per move (e.g. 100000)");
                    if ("20".equals(limitInput.getText().toString())) limitInput.setText("100000");
                } else if (movePeriodMode) {
                    baseInput.setHint("Time awarded each period in minutes (e.g. 15)");
                    limitInput.setHint("Moves in each period (e.g. 40)");
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        openingPliesInput = field("PGN opening plies (0 = full opening; positive = custom half-move limit)", "0"); root.addView(openingPliesInput, fullWidth());
        repeatOpeningCheck = new CheckBox(this); repeatOpeningCheck.setText("Repeat opening for paired color-reversal games");
        root.addView(repeatOpeningCheck, fullWidth());
        formatSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                applyTournamentFormatUi(position);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
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
        Button importTournamentButton = new Button(this);
        importTournamentButton.setText("Import tournament JSON");
        root.addView(importTournamentButton, fullWidth());
        importTournamentButton.setOnClickListener(v -> chooseTournamentJson());
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

    /** Independent mirror and read-only replay surface. It never controls the tournament runner. */
    private void buildMirrorUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF303030);

        mirrorScroll = new ScrollView(this);
        mirrorScroll.setFillViewport(false);
        mirrorScroll.setVerticalScrollBarEnabled(true);
        mirrorPage = new LinearLayout(this);
        mirrorPage.setOrientation(LinearLayout.VERTICAL);
        mirrorPage.setPadding(0, dp(8), 0, dp(8));
        mirrorPage.setBackgroundColor(0xFF303030);

        mirrorHeaderText = text("Game —/—  TC —  T 0:00:0");
        mirrorHeaderText.setTextSize(16);
        mirrorHeaderText.setTypeface(null, android.graphics.Typeface.BOLD);
        mirrorHeaderText.setSingleLine(true);
        mirrorHeaderText.setIncludeFontPadding(false);
        mirrorHeaderText.setGravity(android.view.Gravity.CENTER);
        mirrorPage.addView(mirrorHeaderText, fullWidth());

        mirrorEventTitleText = text("Tournament");
        mirrorEventTitleText.setTextSize(18);
        mirrorEventTitleText.setTypeface(null, android.graphics.Typeface.BOLD);
        mirrorEventTitleText.setGravity(android.view.Gravity.CENTER);
        mirrorPage.addView(mirrorEventTitleText, fullWidth());

        mirrorBlackAnalysisText = text("Eval: —    Depth: —    Nodes: —    NPS: —");
        mirrorBlackAnalysisText.setTextSize(18);
        mirrorBlackAnalysisText.setMinLines(5);
        mirrorBlackAnalysisText.setMaxLines(100);
        mirrorBlackAnalysisText.setMinHeight(dp(110));
        mirrorBlackAnalysisText.setGravity(android.view.Gravity.TOP | android.view.Gravity.LEFT);
        mirrorBlackAnalysisText.setIncludeFontPadding(false);
        mirrorBlackAnalysisText.setTextColor(0xFFD5D5D5);
        mirrorBlackAnalysisScroll = createMirrorAnalysisScroll(mirrorBlackAnalysisText);
        mirrorPage.addView(mirrorBlackAnalysisScroll, new LinearLayout.LayoutParams(-1, dp(110)));

        mirrorBlackClock = text("00:30.0");
        mirrorBlackClock.setTextSize(21);
        mirrorBlackClock.setTypeface(null, android.graphics.Typeface.BOLD);
        mirrorBlackName = text("BLACK");
        mirrorBlackName.setTextSize(19);
        mirrorBlackName.setTypeface(null, android.graphics.Typeface.BOLD);
        mirrorBlackName.setGravity(android.view.Gravity.CENTER);
        mirrorBlackIcon = new ImageView(this);
        mirrorBlackIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mirrorBlackIcon.setVisibility(View.GONE);
        mirrorBlackMoveTime = text("—");
        mirrorBlackMoveTime.setTextSize(17);
        mirrorBlackMoveTime.setGravity(android.view.Gravity.RIGHT);
        mirrorBlackRow = new LinearLayout(this);
        mirrorBlackRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        mirrorBlackRow.addView(mirrorBlackClock, new LinearLayout.LayoutParams(0, -2, 1));
        mirrorBlackRow.addView(mirrorBlackIcon, new LinearLayout.LayoutParams(dp(25), dp(25)));
        mirrorBlackRow.addView(mirrorBlackName, new LinearLayout.LayoutParams(0, -2, 1.4f));
        mirrorBlackRow.addView(mirrorBlackMoveTime, new LinearLayout.LayoutParams(0, -2, 1));
        mirrorPage.addView(mirrorBlackRow, fullWidth());

        FrameLayout frame = new FrameLayout(this) {
            @Override protected void onMeasure(int w, int h) {
                int width = MeasureSpec.getSize(w);
                super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY));
            }
        };
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2);
        fp.topMargin = dp(4);
        fp.bottomMargin = dp(4);
        mirrorBoardFrame = frame;
        mirrorPage.addView(frame, fp);
        mirrorBoard = new TournamentChessBoard(this);
        mirrorBoard.setClickable(false);
        mirrorBoard.setFocusable(false);
        mirrorBoard.highlightLastMove = true;
        mirrorBoard.setPosition(new Position());
        frame.addView(mirrorBoard, new FrameLayout.LayoutParams(-1, -1, android.view.Gravity.CENTER));

        mirrorWhiteClock = text("00:30.0");
        mirrorWhiteClock.setTextSize(21);
        mirrorWhiteClock.setTypeface(null, android.graphics.Typeface.BOLD);
        mirrorWhiteName = text("WHITE");
        mirrorWhiteName.setTextSize(19);
        mirrorWhiteName.setTypeface(null, android.graphics.Typeface.BOLD);
        mirrorWhiteName.setGravity(android.view.Gravity.CENTER);
        mirrorWhiteIcon = new ImageView(this);
        mirrorWhiteIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mirrorWhiteIcon.setVisibility(View.GONE);
        mirrorWhiteMoveTime = text("—");
        mirrorWhiteMoveTime.setTextSize(17);
        mirrorWhiteMoveTime.setGravity(android.view.Gravity.RIGHT);
        mirrorWhiteRow = new LinearLayout(this);
        mirrorWhiteRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        mirrorWhiteRow.addView(mirrorWhiteClock, new LinearLayout.LayoutParams(0, -2, 1));
        mirrorWhiteRow.addView(mirrorWhiteIcon, new LinearLayout.LayoutParams(dp(25), dp(25)));
        mirrorWhiteRow.addView(mirrorWhiteName, new LinearLayout.LayoutParams(0, -2, 1.4f));
        mirrorWhiteRow.addView(mirrorWhiteMoveTime, new LinearLayout.LayoutParams(0, -2, 1));
        mirrorPage.addView(mirrorWhiteRow, fullWidth());

        mirrorWhiteAnalysisText = text("Eval: —    Depth: —    Nodes: —    NPS: —");
        mirrorWhiteAnalysisText.setTextSize(18);
        mirrorWhiteAnalysisText.setMinLines(5);
        mirrorWhiteAnalysisText.setMaxLines(100);
        mirrorWhiteAnalysisText.setMinHeight(dp(110));
        mirrorWhiteAnalysisText.setGravity(android.view.Gravity.TOP | android.view.Gravity.LEFT);
        mirrorWhiteAnalysisText.setIncludeFontPadding(false);
        mirrorWhiteAnalysisText.setTextColor(0xFFD5D5D5);
        mirrorWhiteAnalysisScroll = createMirrorAnalysisScroll(mirrorWhiteAnalysisText);
        mirrorPage.addView(mirrorWhiteAnalysisScroll, new LinearLayout.LayoutParams(-1, dp(110)));

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        mirrorMovesTab = new Button(this); mirrorMovesTab.setText("MOVES");
        mirrorStandingsTab = new Button(this); mirrorStandingsTab.setText("STANDINGS");
        mirrorScheduleTab = new Button(this); mirrorScheduleTab.setText("SCHEDULE");
        mirrorInfoTab = new Button(this); mirrorInfoTab.setText("INFO");
        for (Button b : new Button[]{mirrorMovesTab, mirrorStandingsTab, mirrorScheduleTab, mirrorInfoTab})
            tabs.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
        mirrorPage.addView(tabs, fullWidth());
        mirrorMovesTab.setOnClickListener(v -> selectMirrorTab(0, true));
        mirrorStandingsTab.setOnClickListener(v -> selectMirrorTab(1, true));
        mirrorScheduleTab.setOnClickListener(v -> selectMirrorTab(2, true));
        mirrorInfoTab.setOnClickListener(v -> selectMirrorTab(3, true));

        mirrorSectionTitle = text("MOVES");
        mirrorSectionTitle.setTextSize(16);
        mirrorSectionTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        mirrorSectionTitle.setPadding(dp(10), dp(8), dp(10), dp(6));
        mirrorSectionTitle.setBackgroundColor(0xFF252525);
        mirrorPage.addView(mirrorSectionTitle, fullWidth());
        mirrorSectionHost = new LinearLayout(this);
        mirrorSectionHost.setOrientation(LinearLayout.VERTICAL);
        mirrorSectionHost.setPadding(dp(6), dp(4), dp(6), dp(4));
        mirrorSectionHost.setBackgroundColor(0xFF292929);
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int panelHeight = Math.max(dp(260), Math.min(dp(520), (int)(screenHeight * 0.48f)));
        mirrorPage.addView(mirrorSectionHost, new LinearLayout.LayoutParams(-1, panelHeight));
        mirrorScroll.addView(mirrorPage, new ScrollView.LayoutParams(-1, -2));
        root.addView(mirrorScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        // This operator bar is outside the scroll view and remains pinned to the bottom.
        LinearLayout controls = new LinearLayout(this);
        mirrorControlBar = controls;
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setBackgroundColor(0xFF202020);
        if (android.os.Build.VERSION.SDK_INT >= 21) controls.setElevation(dp(6));
        mirrorPauseButton = new Button(this);
        mirrorPauseButton.setText(runner != null && runner.isPaused() ? "PLAY" : "PAUSE");
        Button back = new Button(this); back.setText("SWITCH");
        Button menu = new Button(this); menu.setText("MENU");
        for (Button b : new Button[]{mirrorPauseButton, back, menu})
            controls.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(controls, fullWidth());
        mirrorPauseButton.setOnClickListener(v -> togglePauseResume());
        back.setOnClickListener(v -> switchTournamentAndMirror());
        // Use Android's native long-click handling so a vertical drag can still be
        // intercepted by the outer page scroll view. The mirror board is read-only.
        mirrorBoard.setLongClickable(true);
        mirrorBoard.setOnLongClickListener(view -> {
            toggleMirrorBoardOnlyMode();
            return true;
        });
        menu.setOnClickListener(v -> new AlertDialog.Builder(TournamentActivity.this)
                .setTitle("Tournament menu")
                .setItems(new String[]{"Adjudicate", "Setup", "Flip Board"}, (dialog, which) -> {
                    if (which == 0) showAdjudicationDialog();
                    else if (which == 1) setContentView(setupView);
                    else toggleTournamentFlip();
                }).show());

        mirrorView = root;
        selectMirrorTab(0, false);
    }

    private ScrollView createMirrorAnalysisScroll(TextView analysis) {
        ScrollView scroll = new MirrorSectionScrollView(this);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroll.addView(analysis, new ScrollView.LayoutParams(-1, -2));
        return scroll;
    }

    /** Long-press the mirror board to hide every control and show only the chessboard. */
    private void toggleMirrorBoardOnlyMode() {
        if (mirrorPage == null || mirrorBoardFrame == null || mirrorScroll == null) return;
        mirrorBoardOnlyMode = !mirrorBoardOnlyMode;
        if (mirrorBoardOnlyMode) {
            stopMirrorAnalysis();
            mirrorScroll.scrollTo(0, 0);
            mirrorScroll.setFillViewport(true);
            mirrorPage.setGravity(android.view.Gravity.CENTER);
            for (int i = 0; i < mirrorPage.getChildCount(); i++) {
                View child = mirrorPage.getChildAt(i);
                child.setVisibility(child == mirrorBoardFrame ? View.VISIBLE : View.GONE);
            }
            if (mirrorControlBar != null) mirrorControlBar.setVisibility(View.GONE);
        } else {
            mirrorPage.setGravity(android.view.Gravity.NO_GRAVITY);
            for (int i = 0; i < mirrorPage.getChildCount(); i++)
                mirrorPage.getChildAt(i).setVisibility(View.VISIBLE);
            if (mirrorControlBar != null) mirrorControlBar.setVisibility(View.VISIBLE);
            mirrorScroll.setFillViewport(false);
            renderMirrorSection();
        }
        mirrorPage.requestLayout();
    }

    private ArrayList<Position> currentMirrorPositions() {
        return mirrorReplayMode ? mirrorReplayPositions : mirrorPositions;
    }

    private int mirrorDisplayedPly() {
        ArrayList<Position> positions = currentMirrorPositions();
        return mirrorReplayMode ? Math.max(0, mirrorPly) : (mirrorPly < 0 ? Math.max(0, positions.size() - 1) : mirrorPly);
    }

    private void openMirrorBoard() {
        stopMirrorAutoPlay();
        mirrorReplayMode = false;
        mirrorReplayLiveSnapshot = false;
        mirrorPly = -1;
        syncMirrorFromLive();
        currentTournamentScreen = 1;
        setContentView(mirrorView);
    }

    /** Switches between the live tournament board and its independent mirror view. */
    private void switchTournamentAndMirror() {
        if (mirrorView == null || playView == null) return;
        stopMirrorAutoPlay();
        stopMirrorAnalysis();
        if (isMirrorShowing()) {
            currentTournamentScreen = 0;
            if (tournamentBoard != null && latestPosition != null) tournamentBoard.setPosition(new Position(latestPosition));
            if (tournamentBoard != null) tournamentBoard.setMoveHints(null);
            setContentView(playView);
        } else {
            mirrorReplayMode = false;
            mirrorReplayLiveSnapshot = false;
            mirrorPly = -1;
            syncMirrorFromLive();
            currentTournamentScreen = 1;
            setContentView(mirrorView);
        }
    }

    private boolean isMirrorShowing() { return currentTournamentScreen == 1; }

    private void syncMirrorFromLive() {
        if (mirrorBoard == null) return;
        if (mirrorPositions.isEmpty() || !samePosition(mirrorPositions.get(mirrorPositions.size() - 1), latestPosition))
            mirrorPositions.add(new Position(latestPosition));
        if (!mirrorReplayMode) {
            if (mirrorPly < 0) mirrorBoard.setPosition(new Position(latestPosition));
            else if (mirrorPly < mirrorPositions.size()) mirrorBoard.setPosition(new Position(mirrorPositions.get(mirrorPly)));
            if (tournamentBoard != null && mirrorBoard.flipped != tournamentBoard.flipped) {
                mirrorBoard.setFlipped(tournamentBoard.flipped);
                mirrorBoard.invalidate();
            }
            if (mirrorWhiteClock != null) {
                mirrorWhiteClock.setText(whiteClockText == null ? "—" : whiteClockText.getText());
                mirrorBlackClock.setText(blackClockText == null ? "—" : blackClockText.getText());
            }
            if (mirrorWhiteMoveTime != null) {
                mirrorWhiteMoveTime.setText(whiteMoveTimeText == null ? "—" : whiteMoveTimeText.getText());
                mirrorBlackMoveTime.setText(blackMoveTimeText == null ? "—" : blackMoveTimeText.getText());
            }
            if (mirrorBlackAnalysisText != null) mirrorBlackAnalysisText.setText(lastBlackAnalysis);
            if (mirrorWhiteAnalysisText != null) mirrorWhiteAnalysisText.setText(lastWhiteAnalysis);
            updateMirrorRankedNames();
        }
        if (mirrorPauseButton != null) mirrorPauseButton.setText(runner != null && runner.isPaused() ? "PLAY" : "PAUSE");
        if (mirrorHeaderText != null) mirrorHeaderText.setText(mirrorHeaderLabel());
        if (mirrorEventTitleText != null && activeTournamentManager != null)
            mirrorEventTitleText.setText(activeTournamentManager.title());
    }

    private boolean samePosition(Position a, Position b) {
        return a != null && b != null && a.toString().equals(b.toString());
    }

    /**
     * Opening-book moves are already in the live PGN before the first engine move,
     * but the runner's position callbacks begin at the opening position. Rebuild
     * the mirror-only history once when those two counts do not line up.
     */
    private void ensureMirrorHistoryMatchesLivePgn(Position livePosition, boolean forceRebuild) {
        ArrayList<String> parsedComments = new ArrayList<>();
        ArrayList<String> parsedMoves = parseMirrorMoves(currentPgnMoves, parsedComments);
        if (!forceRebuild && mirrorPositions.size() == parsedMoves.size() + 1 && !mirrorPositions.isEmpty() &&
                samePosition(mirrorPositions.get(mirrorPositions.size() - 1), livePosition)) return;
        if (parsedMoves.isEmpty()) {
            mirrorPositions.clear();
            mirrorPositions.add(new Position(livePosition));
            return;
        }
        try {
            // Prefer DroidFish's PGN parser (it preserves a PGN FEN start when
            // present), but do not leave the mirror history at callback-count
            // length when a partial live movetext cannot be parsed as a PGN.
            GameTree history = new GameTree(null);
            PGNOptions options = new PGNOptions();
            options.imp.comments = true; options.imp.variations = false; options.imp.nag = true;
            if (history.readPGN(currentPgnMoves, options)) {
                while (!history.currentNode.equals(history.rootNode)) history.goBack();
                ArrayList<Position> rebuilt = new ArrayList<>();
                Position cursor = new Position(history.currentPos); rebuilt.add(new Position(cursor));
                android.util.Pair<List<GameTree.Node>, Integer> moveList = history.getMoveList();
                if (moveList != null && moveList.first != null) {
                    for (GameTree.Node node : moveList.first) {
                        if (node.move == null) continue;
                        cursor.makeMove(node.move, new org.petero.droidfish.gamelogic.UndoInfo());
                        rebuilt.add(new Position(cursor));
                    }
                }
                if (rebuilt.size() == parsedMoves.size() + 1) {
                    if (!samePosition(rebuilt.get(rebuilt.size() - 1), livePosition))
                        rebuilt.set(rebuilt.size() - 1, new Position(livePosition));
                    mirrorPositions.clear(); mirrorPositions.addAll(rebuilt);
                    return;
                }
            }
        } catch (Exception ignored) { }

        // Fallback: replay the SAN list from the normal initial position. This
        // specifically keeps book moves visible even when the live PGN has no
        // tags or is still being written by the tournament worker.
        try {
            ArrayList<Position> rebuilt = new ArrayList<>();
            Position cursor = new Position();
            rebuilt.add(new Position(cursor));
            for (String san : parsedMoves) {
                Move move = TextIO.stringToMove(cursor, san);
                if (move == null) return;
                cursor.makeMove(move, new org.petero.droidfish.gamelogic.UndoInfo());
                rebuilt.add(new Position(cursor));
            }
            if (!samePosition(rebuilt.get(rebuilt.size() - 1), livePosition))
                rebuilt.set(rebuilt.size() - 1, new Position(livePosition));
            mirrorPositions.clear(); mirrorPositions.addAll(rebuilt);
        } catch (Exception ignored) { }
    }

    private void refreshMirrorRanks() {
        mirrorRanks.clear();
        if (activeTournamentManager != null) {
            List<TournamentManager.Standing> standings = activeTournamentManager.standings();
            for (int i = 0; i < standings.size(); i++) mirrorRanks.put(standings.get(i).engineId, i + 1);
        }
        updateMirrorRankedNames();
    }

    private void updateMirrorRankedNames() {
        if (mirrorWhiteName == null || mirrorBlackName == null) return;
        if (mirrorReplayMode) {
            mirrorWhiteName.setText(mirrorRankedName(mirrorReplayWhiteEngineId, mirrorReplayWhiteName));
            mirrorBlackName.setText(mirrorRankedName(mirrorReplayBlackEngineId, mirrorReplayBlackName));
            setMirrorPlayerIcon(mirrorWhiteIcon, mirrorReplayWhiteEngineId, mirrorReplayWhiteName);
            setMirrorPlayerIcon(mirrorBlackIcon, mirrorReplayBlackEngineId, mirrorReplayBlackName);
            return;
        }
        mirrorWhiteName.setText(mirrorRankedName(currentWhiteEngineId, currentWhiteName));
        mirrorBlackName.setText(mirrorRankedName(currentBlackEngineId, currentBlackName));
        setMirrorPlayerIcon(mirrorWhiteIcon, currentWhiteEngineId, currentWhiteName);
        setMirrorPlayerIcon(mirrorBlackIcon, currentBlackEngineId, currentBlackName);
    }

    private void setMirrorPlayerIcon(ImageView view, String engineId, String displayName) {
        if (view == null) return;
        Bitmap bitmap = mirrorEngineIcon(engineId, displayName);
        if (bitmap == null) { view.setImageDrawable(null); view.setVisibility(View.GONE); }
        else { view.setImageDrawable(new BitmapDrawable(getResources(), bitmap)); view.setVisibility(View.VISIBLE); }
    }

    private String mirrorRankedName(String engineId, String displayName) {
        if (engineId == null || engineId.isEmpty()) return displayName;
        Integer rank = mirrorRanks.get(engineId);
        return "#" + (rank == null ? "—" : rank) + "." + displayName;
    }

    private void selectMirrorTab(int tab, boolean scrollToSection) {
        mirrorSelectedTab = Math.max(0, Math.min(3, tab));
        if (mirrorSelectedTab != 0) { stopMirrorAutoPlay(); stopMirrorAnalysis(); }
        else if (mirrorMovesSubTab != 1) stopMirrorAnalysis();
        Button[] buttons = {mirrorMovesTab, mirrorStandingsTab, mirrorScheduleTab, mirrorInfoTab};
        String[] labels = {"MOVES", "STANDINGS", "SCHEDULE", "INFO"};
        for (int i = 0; i < buttons.length; i++) {
            if (buttons[i] == null) continue;
            buttons[i].setText(labels[i]);
            buttons[i].setTextSize(11);
            buttons[i].setPadding(0, dp(4), 0, dp(4));
            buttons[i].setAlpha(i == mirrorSelectedTab ? 1.0f : 0.72f);
        }
        renderMirrorSection();
        if (scrollToSection && mirrorScroll != null && mirrorSectionTitle != null) {
            mirrorScroll.post(() -> mirrorScroll.smoothScrollTo(0, Math.max(0, mirrorSectionTitle.getTop() - dp(110))));
        }
    }

    private void renderMirrorSection() {
        if (mirrorSectionHost == null) return;
        mirrorSectionHost.removeAllViews();
        if (mirrorReplayMode) {
            LinearLayout replayBar = new LinearLayout(this);
            replayBar.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView replayLabel = text((mirrorReplayLiveSnapshot ? "LIVE SNAPSHOT · GAME " : "SAVED PGN · GAME ") +
                    (mirrorReplayPairingIndex + 1));
            replayLabel.setTextSize(12);
            replayLabel.setTextColor(0xFFBFCBD2);
            replayBar.addView(replayLabel, new LinearLayout.LayoutParams(0, -2, 1));
            Button live = new Button(this);
            live.setText("BACK TO LIVE");
            replayBar.addView(live, new LinearLayout.LayoutParams(-2, -2));
            live.setOnClickListener(v -> returnMirrorToLive());
            mirrorSectionHost.addView(replayBar, fullWidth());
        }
        if (mirrorSelectedTab == 0) {
            mirrorSectionTitle.setText("MOVES" + (mirrorReplayMode ? " · GAME " + (mirrorReplayPairingIndex + 1) + " REPLAY" : ""));
            renderMirrorMovesSection();
        } else if (mirrorSelectedTab == 1) {
            mirrorSectionTitle.setText("STANDINGS");
            renderMirrorStandingsSection();
        } else if (mirrorSelectedTab == 2) {
            TournamentManager tournament = activeTournamentManager;
            mirrorSectionTitle.setText(tournament == null ? "SCHEDULE" : scheduleTitle(tournament));
            renderMirrorScheduleSection();
        } else {
            mirrorSectionTitle.setText("TOURNAMENT INFO");
            renderMirrorInfoSection();
        }
    }

    private void renderMirrorMovesSection() {
        if (mirrorSectionHost == null) return;
        // Reconcile the navigation history against the complete live PGN before
        // sizing the controls or rendering the move list. This keeps book moves
        // and engine moves on the same ply index even after a delayed callback.
        if (!mirrorReplayMode && latestPosition != null)
            ensureMirrorHistoryMatchesLivePgn(latestPosition, false);
        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(android.view.Gravity.CENTER);
        Button first = new Button(this); first.setText("<<");
        Button previous = new Button(this); previous.setText("<");
        mirrorMovesPlayButton = new Button(this); mirrorMovesPlayButton.setText(mirrorAutoPlay ? "⏸" : "▶️");
        Button next = new Button(this); next.setText(">");
        Button last = new Button(this); last.setText(">>");
        for (Button b : new Button[]{first, previous, mirrorMovesPlayButton, next, last})
            nav.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
        mirrorSectionHost.addView(nav, fullWidth());
        first.setOnClickListener(v -> navigateMirror(0));
        previous.setOnClickListener(v -> navigateMirror(Math.max(0, mirrorDisplayedPly() - 1)));
        next.setOnClickListener(v -> navigateMirror(Math.min(currentMirrorPositions().size() - 1, mirrorDisplayedPly() + 1)));
        last.setOnClickListener(v -> navigateMirror(Math.max(0, currentMirrorPositions().size() - 1)));
        mirrorMovesPlayButton.setOnClickListener(v -> toggleMirrorAutoPlay());

        int displayPly = mirrorDisplayedPly();
        int displayTotal = Math.max(0, currentMirrorPositions().size() - 1);
        mirrorStatusText = text(mirrorReplayMode ?
                (mirrorReplayLiveSnapshot ? "Live snapshot · ply " : "Saved PGN · ply ") + displayPly + "/" + displayTotal :
                (mirrorPly < 0 ? "Following live game" : "Live review · ply " + displayPly + "/" + displayTotal + " · select >> to return"));
        mirrorStatusText.setTextSize(12);
        mirrorStatusText.setTextColor(0xFFBDBDBD);
        mirrorStatusText.setPadding(dp(8), dp(4), dp(8), dp(4));
        mirrorSectionHost.addView(mirrorStatusText, fullWidth());

        LinearLayout subtabs = new LinearLayout(this);
        mirrorMoveListTab = mirrorSubTabButton("MOVES", 0, mirrorMovesSubTab == 0);
        mirrorMoveAnalysisTab = mirrorSubTabButton("ANALYSIS", 1, mirrorMovesSubTab == 1);
        mirrorGameInfoTab = mirrorSubTabButton("INFO", 2, mirrorMovesSubTab == 2);
        subtabs.addView(mirrorMoveListTab, new LinearLayout.LayoutParams(0, -2, 1));
        subtabs.addView(mirrorMoveAnalysisTab, new LinearLayout.LayoutParams(0, -2, 1));
        subtabs.addView(mirrorGameInfoTab, new LinearLayout.LayoutParams(0, -2, 1));
        mirrorSectionHost.addView(subtabs, fullWidth());

        if (mirrorMovesSubTab == 0) renderMirrorMoveList();
        else if (mirrorMovesSubTab == 1) renderMirrorAnalysisSection();
        else renderMirrorGameInfoSection();
    }

    private Button mirrorSubTabButton(String label, int tab, boolean selected) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(11);
        button.setPadding(dp(2), dp(3), dp(2), dp(3));
        button.setAlpha(selected ? 1.0f : 0.68f);
        button.setOnClickListener(v -> selectMirrorMovesSubTab(tab));
        return button;
    }

    private void selectMirrorMovesSubTab(int tab) {
        mirrorMovesSubTab = Math.max(0, Math.min(2, tab));
        if (mirrorMovesSubTab != 1) stopMirrorAnalysis();
        renderMirrorSection();
        if (mirrorMovesSubTab == 1 && mirrorAnalysisOn) startMirrorAnalysis();
    }

    private void renderMirrorMoveList() {
        ScrollView movesScroll = new MirrorSectionScrollView(this);
        movesScroll.setFillViewport(false);
        LinearLayout moves = new LinearLayout(this);
        moves.setOrientation(LinearLayout.VERTICAL);
        ArrayList<String> sans;
        ArrayList<String> comments;
        if (mirrorReplayMode) {
            sans = new ArrayList<>(mirrorReplayMoves);
            comments = new ArrayList<>(mirrorReplayComments);
        } else {
            comments = new ArrayList<>();
            sans = parseMirrorMoves(currentPgnMoves, comments);
        }
        // Navigation is driven by the same rebuilt PGN position history as this
        // list; never silently truncate a complete move list to callback count.
        int maxMoves = sans.size();
        if (maxMoves == 0) {
            TextView empty = text("No moves played yet.");
            empty.setPadding(dp(10), dp(14), dp(10), dp(14));
            moves.addView(empty, fullWidth());
        } else {
            for (int start = 0; start < maxMoves; start += 2) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                for (int side = 0; side < 2; side++) {
                    final int ply = start + side + 1;
                    TextView cell = text("");
                    cell.setTextSize(14);
                    cell.setPadding(dp(8), dp(8), dp(8), dp(8));
                    cell.setGravity(android.view.Gravity.TOP | android.view.Gravity.LEFT);
                    boolean selectedMove = ply == mirrorDisplayedPly();
                    cell.setBackgroundColor(selectedMove ? 0xFF465A68 : (((start / 2) & 1) == 0 ? 0xFF333333 : 0xFF2D2D2D));
                    if (ply <= maxMoves) {
                        Position beforeMove = ply - 1 < currentMirrorPositions().size()
                                ? currentMirrorPositions().get(ply - 1) : null;
                        int moveNumber = beforeMove == null ? (ply + 1) / 2 : beforeMove.fullMoveCounter;
                        boolean whiteMove = beforeMove == null ? ((ply & 1) == 1) : beforeMove.whiteMove;
                        String prefix = moveNumber + (whiteMove ? "." : "...");
                        String comment = ply - 1 < comments.size() ? comments.get(ply - 1) : "";
                        String displayComment = mirrorMoveCommentDisplay(comment);
                        android.text.SpannableString styled = new android.text.SpannableString(prefix + sans.get(ply - 1) +
                                (displayComment.isEmpty() ? "" : "\n" + displayComment));
                        int commentAt = (prefix + sans.get(ply - 1)).length();
                        if (!displayComment.isEmpty()) {
                            styled.setSpan(new android.text.style.RelativeSizeSpan(0.78f), commentAt,
                                    styled.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                            styled.setSpan(new android.text.style.ForegroundColorSpan(0xFFB8C4CC), commentAt,
                                    styled.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                        cell.setText(styled);
                        cell.setOnClickListener(v -> navigateMirror(ply));
                    }
                    row.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
                }
                moves.addView(row, fullWidth());
            }
        }
        movesScroll.addView(moves, new ScrollView.LayoutParams(-1, -2));
        mirrorSectionHost.addView(movesScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if ((!mirrorReplayMode && mirrorPly < 0) || mirrorReplayLiveSnapshot) {
            movesScroll.post(() -> movesScroll.fullScroll(View.FOCUS_DOWN));
        } else if (mirrorDisplayedPly() > 0) {
            final int selectedRow = Math.max(0, (mirrorDisplayedPly() - 1) / 2);
            movesScroll.post(() -> {
                View row = moves.getChildAt(selectedRow);
                if (row != null) movesScroll.smoothScrollTo(0, row.getTop());
            });
        }
    }

    private void renderMirrorAnalysisSection() {
        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(android.view.Gravity.CENTER_VERTICAL);
        mirrorAnalysisChooseEngine = new Button(this);
        String selectedName = mirrorAnalysisEngineId.isEmpty() ? "Choose engine" : engineDisplayName(
                activeTournamentManager == null ? new ArrayList<>() : activeTournamentManager.engines(), mirrorAnalysisEngineId);
        if (selectedName.equals(mirrorAnalysisEngineId) || selectedName.isEmpty()) {
            for (EngineChoice e : availableEngines) if (e.id.equals(mirrorAnalysisEngineId)) { selectedName = e.name; break; }
        }
        mirrorAnalysisChooseEngine.setText(selectedName);
        mirrorAnalysisToggle = new Button(this);
        mirrorAnalysisToggle.setText(mirrorAnalysisOn ? "ANALYSIS ON" : "ANALYSIS OFF");
        controls.addView(mirrorAnalysisChooseEngine, new LinearLayout.LayoutParams(0, -2, 1));
        controls.addView(mirrorAnalysisToggle, new LinearLayout.LayoutParams(0, -2, 1));
        mirrorSectionHost.addView(controls, fullWidth());
        mirrorAnalysisChooseEngine.setOnClickListener(v -> chooseMirrorAnalysisEngine());
        mirrorAnalysisToggle.setOnClickListener(v -> {
            if (!mirrorAnalysisOn && mirrorAnalysisEngineId.isEmpty()) {
                chooseMirrorAnalysisEngine();
                return;
            }
            mirrorAnalysisOn = !mirrorAnalysisOn;
            mirrorAnalysisToggle.setText(mirrorAnalysisOn ? "ANALYSIS ON" : "ANALYSIS OFF");
            if (mirrorAnalysisOn) startMirrorAnalysis(); else stopMirrorAnalysis();
        });
        mirrorAnalysisOutput = text(mirrorAnalysisOn ? "Waiting for analysis…" : "Select an engine, then turn analysis on.");
        mirrorAnalysisOutput.setTextSize(14);
        mirrorAnalysisOutput.setTextColor(0xFFE0E0E0);
        mirrorAnalysisOutput.setPadding(dp(10), dp(10), dp(10), dp(10));
        mirrorSectionHost.addView(mirrorAnalysisOutput, fullWidth());
    }

    private void chooseMirrorAnalysisEngine() {
        if (availableEngines.isEmpty()) loadEngineChoices();
        if (availableEngines.isEmpty()) { Toast.makeText(this, "No installed engines found", Toast.LENGTH_LONG).show(); return; }
        String[] labels = new String[availableEngines.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = availableEngines.get(i).name;
        new AlertDialog.Builder(this).setTitle("Choose engine to analyse this position")
                .setItems(labels, (dialog, which) -> {
                    if (which < 0 || which >= availableEngines.size()) return;
                    stopMirrorAnalysis();
                    mirrorAnalysisEngineId = availableEngines.get(which).id;
                    mirrorAnalysisOn = true;
                    renderMirrorSection();
                    startMirrorAnalysis();
                }).setNegativeButton("Cancel", null).show();
    }

    private void startMirrorAnalysis() {
        if (!mirrorAnalysisOn || mirrorAnalysisEngineId.isEmpty() || mirrorMovesSubTab != 1 || !isMirrorShowing()) return;
        stopMirrorAnalysisEngineOnly();
        ArrayList<Position> positions = currentMirrorPositions();
        if (positions.isEmpty()) return;
        int ply = Math.max(0, Math.min(mirrorDisplayedPly(), positions.size() - 1));
        Position position = new Position(positions.get(ply));
        mirrorAnalysisOutput = mirrorAnalysisOutput == null ? text("Starting analysis…") : mirrorAnalysisOutput;
        final DroidComputerPlayer analysisPlayer = new DroidComputerPlayer(new SearchListener() {
            @Override public void notifyDepth(int id, int depth) { }
            @Override public void notifyCurrMove(int id, Position pos, Move move, int moveNr) { }
            @Override public void notifyPV(int id, Position pos, ArrayList<PvInfo> infos, Move ponderMove) {
                if (infos == null || infos.isEmpty() || infos.get(0) == null) return;
                PvInfo pv = infos.get(0);
                String eval = pv.isMate ? ((pv.score >= 0 ? "+M" : "-M") + Math.abs(pv.score)) :
                        String.format(java.util.Locale.US, "%+.2f", pv.score / 100.0);
                StringBuilder line = new StringBuilder();
                ArrayList<Move> hints = new ArrayList<>();
                Position cursor = new Position(pos);
                if (pv.pv != null) for (Move move : pv.pv) {
                    if (hints.size() < 2) hints.add(move);
                    if (line.length() > 0) line.append(' ');
                    if (cursor.whiteMove) line.append(cursor.fullMoveCounter).append('.');
                    line.append(TextIO.moveToString(cursor, move, false, false));
                    cursor.makeMove(move, new org.petero.droidfish.gamelogic.UndoInfo());
                }
                String output = "Eval: " + eval + "    Depth: " + pv.depth + "    Nodes: " + compactViewerCount(pv.nodes) +
                        "    NPS: " + compactViewerCount(pv.nps) + "\n" + line;
                runOnUiThread(() -> {
                    if (!mirrorAnalysisOn || !isMirrorShowing() || mirrorMovesSubTab != 1 || mirrorAnalysisOutput == null) return;
                    mirrorAnalysisOutput.setText(output);
                    mirrorAnalysisHints.clear(); mirrorAnalysisHints.addAll(hints);
                    if (mirrorBoard != null) mirrorBoard.setMoveHints(mirrorAnalysisHints);
                });
            }
            @Override public void notifyStats(int id, long nodes, int nps, long tbHits, int hash, int time, int seldepth) { }
            @Override public void notifyBookInfo(int id, String bookInfo, ArrayList<Move> moveList, String eco, int distToEcoTree) { }
            @Override public void notifySearchResult(int id, String cmd, Move ponderMove) { }
            @Override public void notifyEngineName(String engineName) { }
            @Override public void notifyEngineInitialized() { }
            @Override public void reportEngineError(String errMsg) {
                runOnUiThread(() -> Toast.makeText(TournamentActivity.this, "Analysis engine: " + errMsg, Toast.LENGTH_LONG).show());
            }
        });
        mirrorAnalysisEngine = analysisPlayer;
        analysisPlayer.setEngineOptions(new org.petero.droidfish.EngineOptions());
        analysisPlayer.setBookOptions(new BookOptions());
        int engineId = ++mirrorAnalysisSearchId;
        analysisPlayer.queueStartEngine(engineId, mirrorAnalysisEngineId);
        uiHandler.postDelayed(() -> {
            if (mirrorAnalysisEngine != analysisPlayer || !mirrorAnalysisOn || !isMirrorShowing() || mirrorMovesSubTab != 1) return;
            try {
                analysisPlayer.queueAnalyzeRequest(DroidComputerPlayer.SearchRequest.analyzeRequest(
                        ++mirrorAnalysisSearchId, position, new ArrayList<Move>(), position, false, mirrorAnalysisEngineId, 1));
            } catch (RuntimeException ex) {
                Toast.makeText(this, "Unable to start analysis: " + ex.getMessage(), Toast.LENGTH_LONG).show();
            }
        }, 700);
    }

    private void stopMirrorAnalysisEngineOnly() {
        if (mirrorAnalysisEngine != null) {
            try { mirrorAnalysisEngine.stopSearch(); mirrorAnalysisEngine.shutdownEngine(); } catch (RuntimeException ignored) { }
            mirrorAnalysisEngine = null;
        }
        mirrorAnalysisHints.clear();
        if (mirrorBoard != null) mirrorBoard.setMoveHints(null);
    }

    private void stopMirrorAnalysis() {
        mirrorAnalysisOn = false;
        stopMirrorAnalysisEngineOnly();
        if (mirrorAnalysisToggle != null) mirrorAnalysisToggle.setText("ANALYSIS OFF");
    }

    private void renderMirrorGameInfoSection() {
        String pgn = mirrorReplayMode ? mirrorReplayPgn : currentPgnMoves;
        TournamentManager.Pairing pairing = currentMirrorPairing();
        String opening = pgnTag(pgn, "Opening");
        String variation = pgnTag(pgn, "Variation");
        String eco = pgnTag(pgn, "ECO");
        String[] detected = detectMirrorEco(pgn);
        if (opening.isEmpty() && !detected[1].isEmpty()) opening = detected[1];
        if (variation.isEmpty() && !detected[2].isEmpty()) variation = detected[2];
        if (eco.isEmpty() && !detected[0].isEmpty()) eco = detected[0];
        if (opening.isEmpty() && pairing != null) opening = pairing.openingName == null ? "" : pairing.openingName;
        // Older opening-book entries may have stored only the ECO code as a
        // label. Prefer the ECO database's human-readable classification.
        if (opening.equalsIgnoreCase(eco) && !detected[1].isEmpty()) opening = detected[1];
        StringBuilder info = new StringBuilder("CURRENT GAME\n");
        info.append("White: ").append(mirrorReplayMode ? mirrorReplayWhiteName : currentWhiteName).append('\n');
        info.append("Black: ").append(mirrorReplayMode ? mirrorReplayBlackName : currentBlackName).append('\n');
        info.append("Opening: ").append(opening.isEmpty() ? "Not identified" : opening).append('\n');
        if (!variation.isEmpty()) info.append("Variation: ").append(variation).append('\n');
        if (!eco.isEmpty()) info.append("ECO: ").append(eco).append('\n');
        String event = pgnTag(pgn, "Event");
        String site = pgnTag(pgn, "Site");
        String date = pgnTag(pgn, "Date");
        String round = pgnTag(pgn, "Round");
        String result = pgnTag(pgn, "Result");
        if (!event.isEmpty()) info.append("Event: ").append(event).append('\n');
        if (!site.isEmpty()) info.append("Site: ").append(site).append('\n');
        if (!date.isEmpty()) info.append("Date: ").append(date).append('\n');
        if (!round.isEmpty()) info.append("Round: ").append(round).append('\n');
        if (!result.isEmpty()) info.append("Result: ").append(result).append('\n');
        if (pairing != null && !pairing.openingSanLine.isEmpty()) info.append("Opening line: ").append(pairing.openingSanLine).append('\n');
        TextView body = text(info.toString());
        body.setTextSize(14); body.setTextColor(0xFFE0E0E0);
        body.setPadding(dp(10), dp(8), dp(10), dp(8));
        ScrollView scroll = new MirrorSectionScrollView(this);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        mirrorSectionHost.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
    }

    private String[] detectMirrorEco(String pgn) {
        String[] result = {"", "", ""};
        if (pgn == null || pgn.trim().isEmpty()) return result;
        try {
            GameTree tree = new GameTree(null);
            PGNOptions options = new PGNOptions(); options.imp.comments = true; options.imp.variations = false; options.imp.nag = true;
            if (tree.readPGN(pgn, options)) {
                org.petero.droidfish.book.EcoDb.Result eco = tree.getGameECO();
                result[0] = eco.eco == null ? "" : eco.eco;
                result[1] = eco.opn == null ? "" : eco.opn;
                result[2] = eco.var == null ? "" : eco.var;
            }
        } catch (Exception ignored) { }
        return result;
    }

    private TournamentManager.Pairing currentMirrorPairing() {
        if (activeTournamentManager == null) return null;
        int index = mirrorReplayMode ? mirrorReplayPairingIndex : currentGameNumber - 1;
        if (index < 0) return null;
        for (TournamentManager.Pairing pairing : activeTournamentManager.pairings()) if (pairing.index == index) return pairing;
        return null;
    }

    private String pgnTag(String pgn, String tag) {
        if (pgn == null || pgn.isEmpty()) return "";
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?m)^\\[" + java.util.regex.Pattern.quote(tag) +
                "\\s+\"([^\"]*)\"\\]").matcher(pgn);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    /** Compact, readable move annotation extracted from the saved PGN comment. */
    private String mirrorMoveCommentDisplay(String comment) {
        if (comment == null || comment.trim().isEmpty()) return "";
        String value = comment.trim();
        if (value.equalsIgnoreCase("Book") || value.equalsIgnoreCase("Book.")) return "Book.";
        String eval = findCommentValue(value, "Eval|eval");
        String depth = findCommentValue(value, "Depth|D");
        String nodes = findCommentValue(value, "Nodes|N");
        String nps = findCommentValue(value, "NPS|nps");
        String clock = findCommentValue(value, "Clock");
        String used = findCommentValue(value, "Time|TimeUsed");
        StringBuilder first = new StringBuilder();
        if (!eval.isEmpty()) first.append(eval);
        if (!depth.isEmpty()) appendMirrorValue(first, "d" + depth);
        if (!nodes.isEmpty()) appendMirrorValue(first, "n" + nodes);
        if (!nps.isEmpty()) appendMirrorValue(first, nps + "nps");
        String second = mirrorSecondsForDisplay(clock) + (used.isEmpty() ? "" :
                (clock.isEmpty() ? "" : "   ") + mirrorSecondsForDisplay(used));
        if (first.length() == 0 && second.isEmpty()) return value;
        return first.toString() + (second.isEmpty() ? "" : "\n" + second);
    }

    private void appendMirrorValue(StringBuilder builder, String value) {
        if (builder.length() > 0) builder.append("   ");
        builder.append(value);
    }

    private String mirrorSecondsForDisplay(String value) {
        // Runner comments already use the same display format as the live clock
        // (for example 25.8 or 1:25.8) and move timer (for example 1.09).
        return value == null ? "" : value.trim();
    }

    /**
     * Parse the PGN with the project's own PGN reader so book moves, move
     * numbers, comments, and saved-game replays all use the same main line.
     * A lightweight token fallback is retained for partially-written live PGNs.
     */
    private ArrayList<String> parseMirrorMoves(String pgn, ArrayList<String> commentsOut) {
        ArrayList<String> moves = new ArrayList<>();
        if (commentsOut != null) commentsOut.clear();
        if (pgn == null || pgn.trim().isEmpty()) return moves;
        try {
            GameTree tree = new GameTree(null);
            PGNOptions options = new PGNOptions();
            options.imp.comments = true;
            options.imp.variations = false;
            options.imp.nag = true;
            if (tree.readPGN(pgn, options)) {
                while (!tree.currentNode.equals(tree.rootNode)) tree.goBack();
                android.util.Pair<List<GameTree.Node>, Integer> line = tree.getMoveList();
                if (line != null && line.first != null) {
                    Position cursor = new Position(tree.currentPos);
                    for (GameTree.Node node : line.first) {
                        if (node.move == null) continue;
                        String san = org.petero.droidfish.gamelogic.TextIO.moveToString(cursor, node.move, false, false);
                        moves.add(san);
                        if (commentsOut != null) commentsOut.add(node.getPostComment());
                        cursor.makeMove(node.move, new org.petero.droidfish.gamelogic.UndoInfo());
                    }
                    return moves;
                }
            }
        } catch (Exception ignored) { }

        // Fallback for a live PGN snapshot that is temporarily incomplete while
        // the runner is adding the just-played move/comment.
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\{([^}]*)\\}|\\S+").matcher(pgn);
        while (matcher.find()) {
            if (matcher.group(1) != null) {
                if (commentsOut != null && !commentsOut.isEmpty())
                    commentsOut.set(commentsOut.size() - 1, matcher.group(1).trim());
                continue;
            }
            String token = matcher.group();
            String san = token.replaceFirst("^\\d+\\.{1,3}", "");
            if (san.isEmpty() || san.matches("1-0|0-1|1/2-1/2|\\*|\\$\\d+") ||
                    san.equals("e.p.") || san.equals("...") || san.startsWith("[")) continue;
            moves.add(san);
            if (commentsOut != null) commentsOut.add("");
        }
        return moves;
    }

    private void toggleMirrorAutoPlay() {
        if (mirrorAutoPlay) {
            stopMirrorAutoPlay();
            if (mirrorMovesPlayButton != null) mirrorMovesPlayButton.setText("▶️");
            return;
        }
        if (currentMirrorPositions().size() <= 1) return;
        mirrorAutoPlay = true;
        if (mirrorMovesPlayButton != null) mirrorMovesPlayButton.setText("⏸");
        mirrorAutoPlayRunnable = new Runnable() {
            @Override public void run() {
                if (!mirrorAutoPlay) return;
                int nextPly = mirrorDisplayedPly() + 1;
                if (nextPly >= currentMirrorPositions().size()) {
                    stopMirrorAutoPlay();
                    if (mirrorMovesPlayButton != null) mirrorMovesPlayButton.setText("▶️");
                    if (!mirrorReplayMode) { mirrorPly = -1; syncMirrorFromLive(); }
                    return;
                }
                navigateMirror(nextPly);
                mirrorAutoPlayHandler.postDelayed(this, 700L);
            }
        };
        mirrorAutoPlayHandler.postDelayed(mirrorAutoPlayRunnable, 150L);
    }

    private void stopMirrorAutoPlay() {
        mirrorAutoPlay = false;
        if (mirrorAutoPlayRunnable != null) mirrorAutoPlayHandler.removeCallbacks(mirrorAutoPlayRunnable);
        mirrorAutoPlayRunnable = null;
    }

    private void navigateMirror(int ply) {
        ArrayList<Position> positions = currentMirrorPositions();
        if (positions.isEmpty() || mirrorBoard == null) return;
        int target = Math.max(0, Math.min(ply, positions.size() - 1));
        if (!mirrorReplayMode && target >= positions.size() - 1) {
            mirrorPly = -1;
            mirrorBoard.setPosition(new Position(latestPosition));
        } else {
            mirrorPly = target;
            mirrorBoard.setPosition(new Position(positions.get(target)));
            mirrorBoard.setSelection(-1);
        }
        if (mirrorStatusText != null) mirrorStatusText.setText(mirrorReplayMode ?
                "Replay · ply " + target + "/" + (positions.size() - 1) :
                (mirrorPly < 0 ? "Following live game" : "Live review · ply " + target + "/" + (positions.size() - 1)));
        if (mirrorReplayMode) updateMirrorReplayDetails(target);
        else updateMirrorRankedNames();
        if (mirrorSelectedTab == 0) {
            renderMirrorSection();
            if (mirrorMovesSubTab == 1 && mirrorAnalysisOn) startMirrorAnalysis();
        }
    }

    private void returnMirrorToLive() {
        stopMirrorAutoPlay();
        mirrorReplayMode = false;
        mirrorReplayLiveSnapshot = false;
        mirrorReplayPgn = "";
        mirrorReplayPairingIndex = -1;
        mirrorReplayWhiteEngineId = mirrorReplayBlackEngineId = "";
        mirrorReplayPositions.clear();
        mirrorReplayMoves.clear();
        mirrorReplayComments.clear();
        mirrorPly = -1;
        syncMirrorFromLive();
        renderMirrorSection();
        if (mirrorScroll != null && mirrorSectionTitle != null)
            mirrorScroll.post(() -> mirrorScroll.smoothScrollTo(0, Math.max(0, mirrorSectionTitle.getTop() - dp(110))));
        Toast.makeText(this, "Returned to live tournament game", Toast.LENGTH_SHORT).show();
    }

    /**
     * The selected panel owns a vertical drag while it can scroll in that
     * direction. At its top/bottom edge the gesture is handed back to the
     * outer mirror page, making it easy to return to the board.
     */
    private class MirrorSectionScrollView extends ScrollView {
        private float downY;
        MirrorSectionScrollView(android.content.Context context) { super(context); }
        @Override public boolean onInterceptTouchEvent(android.view.MotionEvent event) {
            android.view.ViewParent parent = getParent();
            int action = event.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                downY = event.getY();
                if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
            } else if (action == android.view.MotionEvent.ACTION_MOVE) {
                float delta = event.getY() - downY;
                if (Math.abs(delta) > dp(3) && parent != null) {
                    int direction = delta < 0 ? 1 : -1;
                    parent.requestDisallowInterceptTouchEvent(canScrollVertically(direction));
                }
            } else if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) {
                if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
            }
            return super.onInterceptTouchEvent(event);
        }
    }

    private class MirrorSectionListView extends ListView {
        private float downY;
        MirrorSectionListView(android.content.Context context) { super(context); }
        @Override public boolean onInterceptTouchEvent(android.view.MotionEvent event) {
            android.view.ViewParent parent = getParent();
            int action = event.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                downY = event.getY();
                if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
            } else if (action == android.view.MotionEvent.ACTION_MOVE) {
                float delta = event.getY() - downY;
                if (Math.abs(delta) > dp(3) && parent != null) {
                    int direction = delta < 0 ? 1 : -1;
                    parent.requestDisallowInterceptTouchEvent(canScrollVertically(direction));
                }
            } else if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) {
                if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
            }
            return super.onInterceptTouchEvent(event);
        }
    }

    private static final class MirrorColorRecord {
        int whiteWins, blackWins, whiteDraws, blackDraws, whiteLosses, blackLosses;
    }

    private void renderMirrorStandingsSection() {
        LinearLayout subtabs = new LinearLayout(this);
        mirrorStandingsTableTab = mirrorSubTabButton("STANDINGS", 0, mirrorStandingsSubTab == 0);
        mirrorGraphTab = mirrorSubTabButton("GRAPH", 1, mirrorStandingsSubTab == 1);
        mirrorCrosstableTab = mirrorSubTabButton("CROSSTABLE", 2, mirrorStandingsSubTab == 2);
        mirrorStandingsTableTab.setOnClickListener(v -> selectMirrorStandingsSubTab(0));
        mirrorGraphTab.setOnClickListener(v -> selectMirrorStandingsSubTab(1));
        mirrorCrosstableTab.setOnClickListener(v -> selectMirrorStandingsSubTab(2));
        subtabs.addView(mirrorStandingsTableTab, new LinearLayout.LayoutParams(0, -2, 1));
        subtabs.addView(mirrorGraphTab, new LinearLayout.LayoutParams(0, -2, 1));
        subtabs.addView(mirrorCrosstableTab, new LinearLayout.LayoutParams(0, -2, 1));
        mirrorSectionHost.addView(subtabs, fullWidth());
        if (mirrorStandingsSubTab == 0) renderMirrorStandingsTable();
        else if (mirrorStandingsSubTab == 1) renderMirrorGraphSection();
        else renderMirrorCrosstableSection();
    }

    private void selectMirrorStandingsSubTab(int tab) {
        mirrorStandingsSubTab = Math.max(0, Math.min(2, tab));
        stopMirrorAnalysis();
        renderMirrorSection();
    }

    private void renderMirrorStandingsTable() {
        TournamentManager tournament = activeTournamentManager;
        if (tournament == null) {
            TextView empty = text("No tournament loaded.");
            mirrorSectionHost.addView(empty, fullWidth());
            return;
        }
        if (tournament.format() == TournamentManager.Format.CUP) {
            TextView body = text(formatCupProgress(tournament, tournament.engines()));
            body.setTextSize(15);
            body.setTextColor(0xFFE0E0E0);
            body.setPadding(dp(10), dp(8), dp(10), dp(8));
            ScrollView cupScroll = new MirrorSectionScrollView(this);
            cupScroll.addView(body, new ScrollView.LayoutParams(-1, -2));
            mirrorSectionHost.addView(cupScroll, new LinearLayout.LayoutParams(-1, -1));
            return;
        }

        List<TournamentManager.Standing> standings = tournament.standings();
        HashMap<String, MirrorColorRecord> colors = new HashMap<>();
        for (TournamentManager.Engine engine : tournament.engines()) colors.put(engine.id, new MirrorColorRecord());
        for (TournamentManager.Pairing pairing : tournament.pairings()) {
            if (!pairing.isComplete()) continue;
            MirrorColorRecord white = colors.get(pairing.whiteEngineId);
            MirrorColorRecord black = colors.get(pairing.blackEngineId);
            if (white == null || black == null) continue;
            switch (pairing.outcome) {
                case WHITE_WIN: white.whiteWins++; black.blackLosses++; break;
                case BLACK_WIN: white.whiteLosses++; black.blackWins++; break;
                case DRAW: white.whiteDraws++; black.blackDraws++; break;
                default: break;
            }
        }

        int availableWidth = Math.max(dp(300), getResources().getDisplayMetrics().widthPixels - dp(20));
        final int engineWidth = (int)(availableWidth * 0.38f);
        final int resultWidth = Math.max(dp(72), (availableWidth - engineWidth) / 3);
        LinearLayout table = new LinearLayout(this);
        table.setOrientation(LinearLayout.VERTICAL);
        table.setPadding(dp(4), dp(4), dp(4), dp(8));
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        addMirrorStandingHeaderCell(header, "Engine", engineWidth);
        addMirrorStandingHeaderCell(header, "Win", resultWidth);
        addMirrorStandingHeaderCell(header, "Draw", resultWidth);
        addMirrorStandingHeaderCell(header, "Lose", resultWidth);
        table.addView(header, new LinearLayout.LayoutParams(-2, -2));

        for (int i = 0; i < standings.size(); i++) {
            TournamentManager.Standing standing = standings.get(i);
            String name = engineDisplayName(tournament.engines(), standing.engineId);
            MirrorColorRecord color = colors.get(standing.engineId);
            if (color == null) color = new MirrorColorRecord();
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            int rowColor = (i & 1) == 0 ? 0xFF303030 : 0xFF292929;
            row.setBackgroundColor(rowColor);

            double maxScore = standing.played + (tournament.format() == TournamentManager.Format.SWISS ? 2.0 * standing.byes : 0.0);
            double scorePct = maxScore > 0 ? 100.0 * standing.points / maxScore : 0.0;
            LinearLayout engineCell = new LinearLayout(this);
            engineCell.setOrientation(LinearLayout.VERTICAL);
            engineCell.setPadding(dp(7), dp(8), dp(4), dp(8));
            TextView engineName = text((i + 1) + "." + name);
            engineName.setTextSize(13);
            engineName.setMaxLines(2);
            engineName.setEllipsize(android.text.TextUtils.TruncateAt.END);
            engineName.setTypeface(null, android.graphics.Typeface.BOLD);
            TextView score = text(String.format(java.util.Locale.US, "%.1f/%s · %.1f%%",
                    standing.points, (maxScore == Math.rint(maxScore) ? Integer.toString((int)maxScore) :
                            String.format(java.util.Locale.US, "%.1f", maxScore)), scorePct));
            score.setTextSize(10);
            score.setTextColor(0xFFB8C4CC);
            LinearLayout engineNameLine = new LinearLayout(this);
            engineNameLine.setGravity(android.view.Gravity.CENTER_VERTICAL);
            addMirrorEngineIcon(engineNameLine, standing.engineId, name, 22);
            engineNameLine.addView(engineName, new LinearLayout.LayoutParams(0, -2, 1));
            engineCell.addView(engineNameLine, fullWidth());
            engineCell.addView(score, fullWidth());
            row.addView(engineCell, new LinearLayout.LayoutParams(engineWidth, -2));

            addMirrorStandingResultCell(row, standing.wins, color.whiteWins, color.blackWins, standing.played, resultWidth, rowColor);
            addMirrorStandingResultCell(row, standing.draws, color.whiteDraws, color.blackDraws, standing.played, resultWidth, rowColor);
            addMirrorStandingResultCell(row, standing.losses, color.whiteLosses, color.blackLosses, standing.played, resultWidth, rowColor);
            table.addView(row, new LinearLayout.LayoutParams(-2, -2));
            View divider = new View(this);
            divider.setBackgroundColor(0xFF454545);
            table.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        }

        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        horizontal.setFillViewport(false);
        horizontal.setHorizontalScrollBarEnabled(true);
        horizontal.addView(table, new HorizontalScrollView.LayoutParams(-2, -2));
        ScrollView vertical = new MirrorSectionScrollView(this);
        vertical.setFillViewport(false);
        vertical.setVerticalScrollBarEnabled(true);
        vertical.addView(horizontal, new ScrollView.LayoutParams(-1, -2));
        mirrorSectionHost.addView(vertical, new LinearLayout.LayoutParams(-1, -1));
    }

    private String[] mirrorGraphMetricLabels() { return new String[]{"EVAL", "DEPTH", "NODES", "NPS", "TIME"}; }

    private void renderMirrorGraphSection() {
        LinearLayout metricTabs = new LinearLayout(this);
        String[] labels = mirrorGraphMetricLabels();
        for (int i = 0; i < labels.length; i++) {
            final int metric = i;
            Button button = new Button(this);
            button.setText(labels[i]); button.setTextSize(10); button.setPadding(0, dp(2), 0, dp(2));
            button.setAlpha(i == mirrorGraphMetric ? 1.0f : 0.65f);
            button.setOnClickListener(v -> { mirrorGraphMetric = metric; renderMirrorSection(); });
            metricTabs.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
        }
        mirrorSectionHost.addView(metricTabs, fullWidth());

        ArrayList<String> comments = new ArrayList<>();
        if (mirrorReplayMode) comments.addAll(mirrorReplayComments);
        else parseMirrorMoves(currentPgnMoves, comments);
        ArrayList<Float> values = new ArrayList<>();
        ArrayList<Integer> moveNumbers = new ArrayList<>();
        for (int i = 0; i < comments.size(); i++) {
            String raw = comments.get(i);
            String key;
            switch (mirrorGraphMetric) {
                case 1: key = "Depth|D"; break;
                case 2: key = "Nodes|N"; break;
                case 3: key = "NPS|nps"; break;
                case 4: key = "Time|TimeUsed"; break;
                default: key = "Eval|eval"; break;
            }
            double parsed = parseMirrorGraphValue(findCommentValue(raw, key), mirrorGraphMetric);
            if (Double.isFinite(parsed)) { values.add((float)parsed); moveNumbers.add(i + 1); }
        }
        String unit;
        switch (mirrorGraphMetric) {
            case 1: unit = "Depth"; break;
            case 2: unit = "Nodes"; break;
            case 3: unit = "Nodes / second"; break;
            case 4: unit = "Seconds per move"; break;
            default: unit = "Evaluation · White perspective"; break;
        }
        TextView label = text(unit + " · " + values.size() + " recorded moves");
        label.setTextSize(12); label.setTextColor(0xFFBBC6CE); label.setPadding(dp(8), dp(4), dp(8), dp(2));
        mirrorSectionHost.addView(label, fullWidth());
        if (values.isEmpty()) {
            TextView empty = text("No saved move analysis is available yet. The graph appears as annotated moves are recorded.");
            empty.setTextSize(13); empty.setPadding(dp(10), dp(14), dp(10), dp(14));
            mirrorSectionHost.addView(empty, fullWidth());
            return;
        }
        MirrorGraphView graph = new MirrorGraphView(this, values, moveNumbers, mirrorGraphMetric);
        mirrorSectionHost.addView(graph, new LinearLayout.LayoutParams(-1, dp(220)));
        TextView note = text("Source: existing PGN move comments · no extra engine searches");
        note.setTextSize(10); note.setTextColor(0xFF9AA7B0); note.setPadding(dp(8), dp(2), dp(8), dp(2));
        mirrorSectionHost.addView(note, fullWidth());
    }

    private double parseMirrorGraphValue(String value, int metric) {
        if (value == null || value.trim().isEmpty() || value.equalsIgnoreCase("n/a")) return Double.NaN;
        String v = value.trim().replace(",", "");
        if (metric == 0 && v.toUpperCase(java.util.Locale.US).contains("M")) {
            return v.startsWith("-") ? -10.0 : 10.0;
        }
        if (metric == 4 && v.contains(":")) {
            try {
                String[] parts = v.split(":");
                return Double.parseDouble(parts[0]) * 60.0 + Double.parseDouble(parts[1]);
            } catch (Exception ignored) { return Double.NaN; }
        }
        double scale = 1.0;
        char suffix = Character.toUpperCase(v.charAt(v.length() - 1));
        if (suffix == 'K') { scale = 1000.0; v = v.substring(0, v.length() - 1); }
        else if (suffix == 'M') { scale = 1000000.0; v = v.substring(0, v.length() - 1); }
        else if (suffix == 'G') { scale = 1000000000.0; v = v.substring(0, v.length() - 1); }
        try { return Double.parseDouble(v) * scale; }
        catch (NumberFormatException ignored) { return Double.NaN; }
    }

    private final class MirrorGraphView extends View {
        private final ArrayList<Float> values;
        private final ArrayList<Integer> moveNumbers;
        private final int metric;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        MirrorGraphView(android.content.Context context, ArrayList<Float> data, ArrayList<Integer> moves, int metric) {
            super(context); this.values = new ArrayList<>(data); this.moveNumbers = new ArrayList<>(moves); this.metric = metric;
            setBackgroundColor(0xFF202428);
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float left = dp(42), right = getWidth() - dp(12), top = dp(14), bottom = getHeight() - dp(28);
            if (right <= left || bottom <= top || values.isEmpty()) return;
            float min = values.get(0), max = values.get(0);
            for (float v : values) { min = Math.min(min, v); max = Math.max(max, v); }
            if (min == max) { min -= 1; max += 1; }
            float pad = (max - min) * 0.12f; min -= pad; max += pad;
            paint.setStrokeWidth(dp(1)); paint.setColor(0xFF46515A); paint.setStyle(Paint.Style.STROKE);
            for (int i = 0; i <= 4; i++) {
                float y = top + (bottom - top) * i / 4f;
                canvas.drawLine(left, y, right, y, paint);
            }
            paint.setStyle(Paint.Style.FILL); paint.setTextSize(dp(9)); paint.setColor(0xFFB7C1C8);
            canvas.drawText(formatGraphNumber(max), dp(3), top + dp(3), paint);
            canvas.drawText(formatGraphNumber((min + max) / 2f), dp(3), (top + bottom) / 2f, paint);
            canvas.drawText(formatGraphNumber(min), dp(3), bottom, paint);
            Path path = new Path();
            for (int i = 0; i < values.size(); i++) {
                float x = values.size() == 1 ? (left + right) / 2f : left + (right - left) * i / (values.size() - 1f);
                float y = bottom - (values.get(i) - min) / (max - min) * (bottom - top);
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            paint.setColor(metric == 0 ? 0xFF8DC7E8 : 0xFFB5D6A0); paint.setStrokeWidth(dp(2)); paint.setStyle(Paint.Style.STROKE);
            canvas.drawPath(path, paint); paint.setStyle(Paint.Style.FILL);
            for (int i = 0; i < values.size(); i++) {
                float x = values.size() == 1 ? (left + right) / 2f : left + (right - left) * i / (values.size() - 1f);
                float y = bottom - (values.get(i) - min) / (max - min) * (bottom - top);
                canvas.drawCircle(x, y, dp(3), paint);
            }
            paint.setTextSize(dp(9)); paint.setColor(0xFFB7C1C8);
            canvas.drawText("Move " + moveNumbers.get(0), left, getHeight() - dp(7), paint);
            String end = "Move " + moveNumbers.get(moveNumbers.size() - 1);
            float width = paint.measureText(end); canvas.drawText(end, right - width, getHeight() - dp(7), paint);
        }
        private String formatGraphNumber(float value) {
            if (Math.abs(value) >= 1000000) return String.format(java.util.Locale.US, "%.1fM", value / 1000000f);
            if (Math.abs(value) >= 1000) return String.format(java.util.Locale.US, "%.1fK", value / 1000f);
            return String.format(java.util.Locale.US, "%.2f", value);
        }
    }

    private void renderMirrorCrosstableSection() {
        TournamentManager tournament = activeTournamentManager;
        if (tournament == null) { mirrorSectionHost.addView(text("No tournament loaded."), fullWidth()); return; }
        List<TournamentManager.Engine> engines = tournament.engines();
        int nameWidth = dp(142), cellWidth = dp(76);
        LinearLayout table = new LinearLayout(this); table.setOrientation(LinearLayout.VERTICAL);
        LinearLayout header = new LinearLayout(this); header.setOrientation(LinearLayout.HORIZONTAL);
        addMirrorStandingHeaderCell(header, "Engine", nameWidth);
        for (TournamentManager.Engine engine : engines) {
            LinearLayout cell = new LinearLayout(this); cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(android.view.Gravity.CENTER); cell.setPadding(dp(2), dp(3), dp(2), dp(3));
            addMirrorEngineIcon(cell, engine.id, engine.displayName, 22);
            TextView name = text(engine.displayName); name.setTextSize(9); name.setMaxLines(2);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END); name.setGravity(android.view.Gravity.CENTER);
            cell.addView(name, new LinearLayout.LayoutParams(cellWidth - dp(4), -2));
            header.addView(cell, new LinearLayout.LayoutParams(cellWidth, -2));
        }
        addMirrorStandingHeaderCell(header, "Pts", dp(52));
        table.addView(header, new LinearLayout.LayoutParams(-2, -2));
        HashMap<String, Double> crossScores = new HashMap<>();
        HashMap<String, Integer> crossGames = new HashMap<>();
        for (TournamentManager.Pairing pairing : tournament.pairings()) {
            if (!pairing.isComplete()) continue;
            double whitePoints = pairing.outcome == TournamentManager.Outcome.WHITE_WIN ? 1.0 :
                    (pairing.outcome == TournamentManager.Outcome.DRAW ? 0.5 : 0.0);
            double blackPoints = 1.0 - whitePoints;
            String whiteKey = pairing.whiteEngineId + "\u0001" + pairing.blackEngineId;
            String blackKey = pairing.blackEngineId + "\u0001" + pairing.whiteEngineId;
            crossScores.put(whiteKey, (crossScores.containsKey(whiteKey) ? crossScores.get(whiteKey) : 0.0) + whitePoints);
            crossScores.put(blackKey, (crossScores.containsKey(blackKey) ? crossScores.get(blackKey) : 0.0) + blackPoints);
            crossGames.put(whiteKey, (crossGames.containsKey(whiteKey) ? crossGames.get(whiteKey) : 0) + 1);
            crossGames.put(blackKey, (crossGames.containsKey(blackKey) ? crossGames.get(blackKey) : 0) + 1);
        }
        for (int i = 0; i < engines.size(); i++) {
            TournamentManager.Engine engine = engines.get(i);
            LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setBackgroundColor((i & 1) == 0 ? 0xFF303030 : 0xFF292929);
            LinearLayout nameCell = new LinearLayout(this); nameCell.setGravity(android.view.Gravity.CENTER_VERTICAL);
            nameCell.setPadding(dp(4), dp(7), dp(3), dp(7));
            TextView name = text((i + 1) + ". " + engine.displayName); name.setTextSize(11); name.setMaxLines(2);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            addMirrorEngineIcon(nameCell, engine.id, engine.displayName, 22);
            nameCell.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(nameCell, new LinearLayout.LayoutParams(nameWidth, -2));
            double totalPoints = 0;
            for (int j = 0; j < engines.size(); j++) {
                String cellText = "—";
                if (i != j) {
                    String key = engine.id + "\u0001" + engines.get(j).id;
                    double points = crossScores.containsKey(key) ? crossScores.get(key) : 0.0;
                    int played = crossGames.containsKey(key) ? crossGames.get(key) : 0;
                    if (played > 0) cellText = formatCrosstableScore(points) + "/" + played;
                    totalPoints += points;
                }
                TextView score = text(cellText); score.setTextSize(11); score.setGravity(android.view.Gravity.CENTER);
                score.setPadding(dp(2), dp(10), dp(2), dp(10));
                score.setBackgroundColor(i == j ? 0xFF252525 : ((i + j) % 2 == 0 ? 0xFF303030 : 0xFF292929));
                row.addView(score, new LinearLayout.LayoutParams(cellWidth, -2));
            }
            TextView points = text(String.format(java.util.Locale.US, "%.1f", totalPoints));
            points.setTextSize(11); points.setGravity(android.view.Gravity.CENTER); points.setTypeface(null, android.graphics.Typeface.BOLD);
            row.addView(points, new LinearLayout.LayoutParams(dp(52), -2));
            table.addView(row, new LinearLayout.LayoutParams(-2, -2));
            View divider = new View(this); divider.setBackgroundColor(0xFF454545);
            table.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        }
        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        horizontal.setFillViewport(false); horizontal.setHorizontalScrollBarEnabled(true);
        horizontal.addView(table, new HorizontalScrollView.LayoutParams(-2, -2));
        ScrollView vertical = new MirrorSectionScrollView(this); vertical.setFillViewport(false);
        vertical.addView(horizontal, new ScrollView.LayoutParams(-1, -2));
        mirrorSectionHost.addView(vertical, new LinearLayout.LayoutParams(-1, -1));
    }

    private String formatCrosstableScore(double points) {
        if (points == Math.rint(points)) return Integer.toString((int)points);
        return String.format(java.util.Locale.US, "%.1f", points);
    }

    private void addMirrorStandingHeaderCell(LinearLayout row, String label, int width) {
        TextView cell = text(label);
        cell.setTextSize(12);
        cell.setTypeface(null, android.graphics.Typeface.BOLD);
        cell.setTextColor(0xFFFFFFFF);
        cell.setGravity(android.view.Gravity.CENTER);
        cell.setBackgroundColor(0xFF465A68);
        cell.setPadding(dp(4), dp(9), dp(4), dp(9));
        row.addView(cell, new LinearLayout.LayoutParams(width, -2));
    }

    private void addMirrorStandingResultCell(LinearLayout row, int total, int white, int black,
                                             int played, int width, int background) {
        double percent = played > 0 ? 100.0 * total / played : 0.0;
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(android.view.Gravity.CENTER);
        cell.setPadding(dp(2), dp(7), dp(2), dp(7));
        cell.setBackgroundColor(background);
        TextView count = text(total + " (" + white + "/" + black + ")");
        count.setTextSize(12);
        count.setGravity(android.view.Gravity.CENTER);
        TextView pct = text(String.format(java.util.Locale.US, "%.1f%%", percent));
        pct.setTextSize(10);
        pct.setTextColor(0xFFB8C4CC);
        pct.setGravity(android.view.Gravity.CENTER);
        cell.addView(count, fullWidth());
        cell.addView(pct, fullWidth());
        row.addView(cell, new LinearLayout.LayoutParams(width, -2));
    }

    private void renderMirrorScheduleSection() {
        TournamentManager tournament = activeTournamentManager;
        if (tournament == null) {
            TextView empty = text("No tournament loaded.");
            mirrorSectionHost.addView(empty, fullWidth());
            return;
        }
        mirrorScheduleRows.clear();
        mirrorSchedulePairingIndexes.clear();
        List<TournamentManager.Engine> engines = tournament.engines();
        List<TournamentManager.Pairing> pairings = new ArrayList<>(tournament.pairings());
        boolean swiss = tournament.format() == TournamentManager.Format.SWISS;
        boolean cup = tournament.format() == TournamentManager.Format.CUP;
        if (cup) {
            Collections.sort(pairings, (a, b) -> {
                int stage = Integer.compare(a.round, b.round);
                if (stage != 0) return stage;
                int match = Integer.compare(a.cupMatchNumber, b.cupMatchNumber);
                if (match != 0) return match;
                int pair = Integer.compare(a.cupPairNumber, b.cupPairNumber);
                if (pair != 0) return pair;
                return Integer.compare(a.cupGameInPair, b.cupGameInPair);
            });
        }
        int shownRound = -1, shownCupStage = -1, shownCupMatch = -1;
        List<Integer> priorityQueue = tournament.playQueue();
        for (TournamentManager.Pairing pairing : pairings) {
            if (swiss && pairing.round != shownRound) {
                shownRound = pairing.round;
                addMirrorScheduleRow("ROUND " + shownRound, -1);
            }
            if (cup && pairing.round - 1 != shownCupStage) {
                shownCupStage = pairing.round - 1;
                shownCupMatch = -1;
                addMirrorScheduleRow(CupTournamentManager.stageLabel(tournament.cupBracketSize(), shownCupStage).toUpperCase(java.util.Locale.US), -1);
            }
            if (cup && pairing.cupMatchNumber != shownCupMatch) {
                shownCupMatch = pairing.cupMatchNumber;
                TournamentManager.CupMatch match = findCupMatch(tournament, shownCupMatch);
                String a = match == null ? "" : cupMatchEngineName(tournament, match, true, engines);
                String b = match == null ? "" : cupMatchEngineName(tournament, match, false, engines);
                addMirrorScheduleRow("MATCH #" + shownCupMatch + " · " + a + " vs " + b, -1);
            }
            String white = pairing.isComplete() && !pairing.whiteNameAtPlay.isEmpty()
                    ? pairing.whiteNameAtPlay : engineDisplayName(engines, pairing.whiteEngineId);
            String black = pairing.isComplete() && !pairing.blackNameAtPlay.isEmpty()
                    ? pairing.blackNameAtPlay : engineDisplayName(engines, pairing.blackEngineId);
            int queuePosition = priorityQueue.indexOf(pairing.index) + 1;
            String state = pairing.replayPending ? " · replay queued" : (queuePosition > 0 ? " · priority #" + queuePosition : "");
            String prefix = swiss ? "R" + pairing.round + "." + pairing.gameNumber
                    : (cup ? "Pair " + pairing.cupPairNumber + " · Game " + pairing.cupGameInPair
                    : Integer.toString(pairing.index + 1));
            String row = prefix + ". " + white + " - " + black + " (" + pairingResultLabel(pairing.outcome) + ")" + state +
                    "\n" + countFullMoves(pairing.pgn) + " moves";
            addMirrorScheduleRow(row, pairing.index);
        }
        if (mirrorScheduleRows.isEmpty()) addMirrorScheduleRow("No scheduled games.", -1);

        ListView list = new MirrorSectionListView(this);
        mirrorScheduleList = list;
        list.setFastScrollEnabled(true);
        list.setFastScrollAlwaysVisible(true);
        list.setVerticalScrollBarEnabled(true);
        list.setScrollBarStyle(View.SCROLLBARS_INSIDE_INSET);
        if (android.os.Build.VERSION.SDK_INT >= 21) list.setNestedScrollingEnabled(false);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, mirrorScheduleRows) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                int pairingIndex = position < mirrorSchedulePairingIndexes.size() ? mirrorSchedulePairingIndexes.get(position) : -1;
                if (pairingIndex < 0) {
                    TextView section = new TextView(TournamentActivity.this);
                    section.setText(getItem(position)); section.setTextSize(12); section.setTypeface(null, android.graphics.Typeface.BOLD);
                    section.setTextColor(0xFFBFCBD2); section.setPadding(dp(10), dp(8), dp(10), dp(6));
                    section.setBackgroundColor(0xFF252525); return section;
                }
                TournamentManager.Pairing pairing = findMirrorPairing(activeTournamentManager, pairingIndex);
                if (pairing == null) {
                    TextView fallback = new TextView(TournamentActivity.this); fallback.setText(getItem(position));
                    fallback.setTextColor(0xFFE6E6E6); fallback.setPadding(dp(10), dp(7), dp(28), dp(7)); return fallback;
                }
                LinearLayout row = new LinearLayout(TournamentActivity.this); row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                row.setPadding(dp(5), dp(5), dp(5), dp(5));
                row.setBackgroundColor((position & 1) == 0 ? 0xFF303030 : 0xFF292929);
                String prefix = pairing.index + 1 + ". ";
                TextView number = text(prefix); number.setTextSize(11); number.setTextColor(0xFFBFCBD2);
                row.addView(number, new LinearLayout.LayoutParams(-2, -2));
                String white = pairing.isComplete() && !pairing.whiteNameAtPlay.isEmpty() ? pairing.whiteNameAtPlay : engineDisplayName(activeTournamentManager.engines(), pairing.whiteEngineId);
                String black = pairing.isComplete() && !pairing.blackNameAtPlay.isEmpty() ? pairing.blackNameAtPlay : engineDisplayName(activeTournamentManager.engines(), pairing.blackEngineId);
                addMirrorEngineIcon(row, pairing.whiteEngineId, white, 19);
                TextView whiteName = text(white); whiteName.setTextSize(11); whiteName.setMaxLines(1); whiteName.setEllipsize(android.text.TextUtils.TruncateAt.END);
                row.addView(whiteName, new LinearLayout.LayoutParams(0, -2, 1));
                TextView vs = text("–"); vs.setTextSize(10); row.addView(vs, new LinearLayout.LayoutParams(-2, -2));
                addMirrorEngineIcon(row, pairing.blackEngineId, black, 19);
                TextView blackName = text(black); blackName.setTextSize(11); blackName.setMaxLines(1); blackName.setEllipsize(android.text.TextUtils.TruncateAt.END);
                row.addView(blackName, new LinearLayout.LayoutParams(0, -2, 1));
                TextView result = text(pairingResultLabel(pairing.outcome)); result.setTextSize(10); result.setGravity(android.view.Gravity.RIGHT);
                row.addView(result, new LinearLayout.LayoutParams(-2, -2));
                return row;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= mirrorSchedulePairingIndexes.size()) return;
            int pairingIndex = mirrorSchedulePairingIndexes.get(position);
            if (pairingIndex >= 0) loadMirrorScheduledGame(tournament, pairingIndex);
        });
        mirrorSectionHost.addView(list, new LinearLayout.LayoutParams(-1, -1));
    }


    private void addMirrorScheduleRow(String row, int pairingIndex) {
        mirrorScheduleRows.add(row);
        mirrorSchedulePairingIndexes.add(pairingIndex);
    }

    private TournamentManager.Pairing findMirrorPairing(TournamentManager tournament, int pairingIndex) {
        if (tournament == null) return null;
        for (TournamentManager.Pairing pairing : tournament.pairings()) if (pairing.index == pairingIndex) return pairing;
        return null;
    }

    /** Opens a completed schedule PGN in mirror-only state; the tournament runner is not touched. */
    private void loadMirrorScheduledGame(TournamentManager tournament, int pairingIndex) {
        if (pairingIndex < 0) return;
        TournamentManager.Pairing pairing = null;
        for (TournamentManager.Pairing candidate : tournament.pairings()) {
            if (candidate.index == pairingIndex) { pairing = candidate; break; }
        }
        if (pairing == null) return;
        if (!pairing.isComplete() || pairing.pgn == null || pairing.pgn.trim().isEmpty()) {
            if (pairing.index + 1 == currentGameNumber &&
                    pairing.whiteEngineId.equals(currentWhiteEngineId) && pairing.blackEngineId.equals(currentBlackEngineId) &&
                    !mirrorPositions.isEmpty()) {
                openMirrorLiveGameSnapshot(pairing);
            } else {
                Toast.makeText(this, "This game has not produced a PGN yet.", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        final String pgn = pairing.pgn;
        final int selectedIndex = pairingIndex;
        final String selectedWhiteEngineId = pairing.whiteEngineId;
        final String selectedBlackEngineId = pairing.blackEngineId;
        new Thread(() -> {
            try {
                GameTree tree = new GameTree(null);
                PGNOptions options = new PGNOptions();
                options.imp.comments = true;
                options.imp.variations = false;
                options.imp.nag = true;
                if (!tree.readPGN(pgn, options)) throw new IOException("No valid chess game found in this PGN");
                while (!tree.currentNode.equals(tree.rootNode)) tree.goBack();
                ArrayList<Position> positions = new ArrayList<>();
                ArrayList<String> moves = new ArrayList<>();
                ArrayList<String> comments = new ArrayList<>();
                Position cursor = new Position(tree.currentPos);
                positions.add(new Position(cursor));
                android.util.Pair<List<GameTree.Node>, Integer> moveList = tree.getMoveList();
                if (moveList != null && moveList.first != null) {
                    for (GameTree.Node node : moveList.first) {
                        Move move = node.move;
                        if (move == null) continue;
                        moves.add(TextIO.moveToString(cursor, move, false, false));
                        comments.add(node.getPostComment());
                        cursor.makeMove(move, new org.petero.droidfish.gamelogic.UndoInfo());
                        positions.add(new Position(cursor));
                        if (moves.size() >= 2000) break;
                    }
                }
                String[] names = extractPgnNames(pgn);
                runOnUiThread(() -> {
                    stopMirrorAutoPlay();
                    mirrorReplayMode = true;
                    mirrorReplayLiveSnapshot = false;
                    mirrorReplayPgn = pgn;
                    mirrorReplayPairingIndex = selectedIndex;
                    mirrorReplayWhiteEngineId = selectedWhiteEngineId;
                    mirrorReplayBlackEngineId = selectedBlackEngineId;
                    mirrorReplayPositions.clear(); mirrorReplayPositions.addAll(positions);
                    mirrorReplayMoves.clear(); mirrorReplayMoves.addAll(moves);
                    mirrorReplayComments.clear(); mirrorReplayComments.addAll(comments);
                    mirrorReplayWhiteName = names[0];
                    mirrorReplayBlackName = names[1];
                    mirrorPly = 0;
                    if (mirrorReplayPositions.size() > 0) mirrorBoard.setPosition(new Position(mirrorReplayPositions.get(0)));
                    refreshMirrorRanks();
                    updateMirrorReplayDetails(0);
                    selectMirrorTab(0, true);
                    Toast.makeText(this, "Loaded Game " + (selectedIndex + 1) + " PGN · " + moves.size() + " plies", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Could not load scheduled PGN: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "mirror-schedule-pgn-loader").start();
    }

    /** Freeze the currently running game's move history for schedule review without pausing it. */
    private void openMirrorLiveGameSnapshot(TournamentManager.Pairing pairing) {
        ArrayList<String> comments = new ArrayList<>();
        ArrayList<String> moves = parseMirrorMoves(currentPgnMoves, comments);
        if (!mirrorLiveHistoryInitialized || mirrorPositions.size() != moves.size() + 1) {
            Toast.makeText(this, "The live game history is still synchronizing. Try again shortly.", Toast.LENGTH_SHORT).show();
            return;
        }
        stopMirrorAutoPlay();
        mirrorReplayMode = true;
        mirrorReplayLiveSnapshot = true;
        mirrorReplayPgn = currentPgnMoves;
        mirrorReplayPairingIndex = pairing.index;
        mirrorReplayWhiteEngineId = pairing.whiteEngineId;
        mirrorReplayBlackEngineId = pairing.blackEngineId;
        mirrorReplayPositions.clear();
        for (Position position : mirrorPositions) mirrorReplayPositions.add(new Position(position));
        mirrorReplayMoves.clear(); mirrorReplayMoves.addAll(moves);
        mirrorReplayComments.clear(); mirrorReplayComments.addAll(comments);
        mirrorReplayWhiteName = currentWhiteName;
        mirrorReplayBlackName = currentBlackName;
        mirrorPly = Math.max(0, mirrorReplayPositions.size() - 1);
        if (!mirrorReplayPositions.isEmpty()) mirrorBoard.setPosition(new Position(mirrorReplayPositions.get(mirrorPly)));
        mirrorBoard.setSelection(-1);
        refreshMirrorRanks();
        updateMirrorReplayDetails(mirrorPly);
        selectMirrorTab(0, true);
        Toast.makeText(this, "Loaded a read-only snapshot of the live game. The tournament continues.", Toast.LENGTH_SHORT).show();
    }

    private void updateMirrorReplayDetails(int ply) {
        String whiteClock = "—", blackClock = "—", whiteTime = "—", blackTime = "—";
        String whiteAnalysis = "Eval: —    Depth: —    Nodes: —    NPS: —";
        String blackAnalysis = "Eval: —    Depth: —    Nodes: —    NPS: —";
        int safePly = Math.max(0, Math.min(ply, mirrorReplayComments.size()));
        for (int i = 0; i < safePly; i++) {
            String comment = mirrorReplayComments.get(i);
            String clock = findCommentValue(comment, "Clock");
            String used = findCommentValue(comment, "Time");
            if ((i & 1) == 0) {
                if (!clock.isEmpty()) whiteClock = clock;
                if (!used.isEmpty()) whiteTime = used;
                whiteAnalysis = comment != null && comment.trim().equalsIgnoreCase("Book.") ? "Book." : analysisFromComment(comment);
            } else {
                if (!clock.isEmpty()) blackClock = clock;
                if (!used.isEmpty()) blackTime = used;
                blackAnalysis = comment != null && comment.trim().equalsIgnoreCase("Book.") ? "Book." : analysisFromComment(comment);
            }
        }
        mirrorWhiteClock.setText(whiteClock);
        mirrorBlackClock.setText(blackClock);
        mirrorWhiteMoveTime.setText(whiteTime);
        mirrorBlackMoveTime.setText(blackTime);
        mirrorWhiteAnalysisText.setText(whiteAnalysis);
        mirrorBlackAnalysisText.setText(blackAnalysis);
        updateMirrorRankedNames();
        if (mirrorStatusText != null) mirrorStatusText.setText((mirrorReplayLiveSnapshot ? "Live snapshot" : "Saved PGN") +
                " · ply " + safePly + "/" + mirrorReplayMoves.size());
    }

    private void renderMirrorInfoSection() {
        TournamentManager t = activeTournamentManager;
        if (t == null) {
            TextView empty = text("No tournament loaded.");
            mirrorSectionHost.addView(empty, fullWidth());
            return;
        }
        StringBuilder info = new StringBuilder();
        info.append(t.title()).append("\n\n");
        info.append("FORMAT\n").append(t.format().name().replace('_', ' ')).append("\n\n");
        info.append("PROGRESS\n").append(t.completedGamesCount()).append(" / ").append(t.pairings().size())
                .append(" games completed\n").append("Engines: ").append(t.engines().size()).append("\n\n");
        info.append("PAIRING SETUP\n").append("Games per pairing: ").append(t.gamesPerPair()).append("\n")
                .append("Scheduled games: ").append(t.pairings().size()).append("\n");
        info.append("TIME CONTROL\n").append(mirrorTimeControlLabel(t)).append("\n")
                .append("Engine settings: 1 thread · 16 MB hash · ponder off\n\n");
        info.append("OPENING SETUP\n");
        if (!t.openingBinPath().trim().isEmpty()) {
            String binName = selectedOpeningFileName == null ? "" : selectedOpeningFileName.trim();
            int marker = binName.indexOf(" · BIN book loaded");
            if (marker >= 0) binName = binName.substring(0, marker).trim();
            if (binName.isEmpty() || binName.equals("No PGN file selected"))
                binName = new File(t.openingBinPath()).getName();
            info.append("Opening book in use: ").append(binName).append(" (Polyglot BIN)\n");
            if (!t.openingPgn().isEmpty()) {
                String inactivePgn = pgnBookNameFromContents(t.openingPgn());
                info.append("PGN book also selected: ").append(inactivePgn)
                        .append(" (inactive while BIN is selected)\n");
            }
        } else if (!t.openingPgn().isEmpty()) {
            String pgnBook = selectedOpeningFileName == null ? "" : selectedOpeningFileName.trim();
            if (pgnBook.isEmpty() || pgnBook.equals("No PGN file selected") || pgnBook.equals("Saved PGN book loaded"))
                pgnBook = pgnBookNameFromContents(t.openingPgn());
            int marker = pgnBook.indexOf(" · PGN book loaded");
            if (marker >= 0) pgnBook = pgnBook.substring(0, marker).trim();
            info.append("Opening PGN book in use: ").append(pgnBook).append("\n");
        } else {
            info.append("Opening book: None selected\n");
        }
        info.append("Opening plies: ").append(t.openingPlies() == 0 ? "Full opening" : t.openingPlies()).append("\n");
        info.append("Starting opening number: ").append(t.openingStartIndex()).append("\n");
        info.append("Repeat opening for paired games: ").append(t.repeatOpening() ? "Yes" : "No").append("\n\n");
        if (t.format() == TournamentManager.Format.SWISS) {
            info.append("SWISS SETUP\nRounds: ").append(t.swissRoundsTotal()).append("\nSeeding: ")
                    .append(t.swissSeedingMode().name().replace('_', ' ')).append("\n\n");
        }
        if (t.format() == TournamentManager.Format.CUP) {
            info.append("CUP SETUP\nBracket size: ").append(t.cupBracketSize()).append("\nSeeding: ")
                    .append(t.cupSeedingMode().name().replace('_', ' ')).append("\nCurrent stage: ")
                    .append(t.cupCurrentStageLabel()).append("\n\n");
        }
        if (currentGameNumber > 0 && currentGameNumber <= t.pairings().size()) {
            TournamentManager.Pairing current = t.pairings().get(currentGameNumber - 1);
            if (current.openingName != null && !current.openingName.trim().isEmpty())
                info.append("Current game opening: ").append(current.openingName).append("\n\n");
        }
        info.append("ENGINE LIST\n");
        for (int i = 0; i < t.engines().size(); i++)
            info.append(i + 1).append(". ").append(t.engines().get(i).displayName).append("\n");
        info.append("\nTournament elapsed: ").append(formatElapsedSeconds(
                t == activeTournamentManager ? currentTournamentElapsedMs() : t.tournamentElapsedMs()));
        TextView body = text(info.toString());
        body.setTextSize(14);
        body.setTextColor(0xFFE0E0E0);
        body.setPadding(dp(10), dp(8), dp(10), dp(8));
        ScrollView scroll = new MirrorSectionScrollView(this);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        mirrorSectionHost.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
    }

    private String pgnBookNameFromContents(String pgn) {
        if (pgn == null || pgn.trim().isEmpty()) return "PGN opening book loaded";
        String[] tags = {"Opening", "ECO", "Event"};
        for (String tag : tags) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                    "(?m)^\\[" + tag + "\\s+\"([^\"]+)\"\\]").matcher(pgn);
            if (matcher.find()) return "PGN book · " + matcher.group(1);
        }
        return "PGN opening book loaded";
    }

    private String mirrorTimeControlLabel(TournamentManager t) {
        switch (t.timeMode()) {
            case CLOCK:
                long seconds = Math.max(0L, t.baseTimeMs() / 1000L);
                String base = seconds >= 60 ? String.format(java.util.Locale.US, "%d:%02d", seconds / 60, seconds % 60)
                        : String.format(java.util.Locale.US, "0:%02d", seconds);
                double increment = t.incrementMs() / 1000.0;
                String inc = increment == Math.rint(increment) ? String.format(java.util.Locale.US, "%.0f", increment)
                        : String.format(java.util.Locale.US, "%.1f", increment);
                return base + "+" + inc;
            case MOVE_TIME: return String.format(java.util.Locale.US, "%.1f seconds per move", t.limitValue() / 1000.0);
            case DEPTH: return "Depth " + t.limitValue() + " per move";
            case NODES: return t.limitValue() + " nodes per move";
            case MOVE_PERIOD:
                double minutes = t.baseTimeMs() / 60000.0;
                String period = minutes == Math.rint(minutes) ? String.format(java.util.Locale.US, "%.0f", minutes)
                        : String.format(java.util.Locale.US, "%.2f", minutes);
                return t.limitValue() + "/" + period + " minutes";
            default: return "Not available";
        }
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
                            freezeTournamentElapsedClock();
                            freezeEventElapsedClock();
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
            TournamentManager tournament = activeTournamentManager != null ? activeTournamentManager : TournamentManager.restore(this);
            final boolean cup = tournament.format() == TournamentManager.Format.CUP;
            final String standings = formatStandings(tournament, tournament.standings(), tournament.engines());
            new AlertDialog.Builder(this).setTitle((cup ? "Cup Progress " : "Standings ") + completedGameCount(tournament) + "/" + tournament.pairings().size())
                    .setMessage(standings)
                    .setNegativeButton("COPY", (dialog, which) -> {
                        ClipboardManager clipboard = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText(cup ? "Cup progress" : "Tournament standings", standings));
                        Toast.makeText(this, cup ? "Cup progress copied" : "Standings copied", Toast.LENGTH_SHORT).show();
                    })
                    .setNeutralButton(cup ? "BRACKET" : "EDIT RESULT", (dialog, which) -> {
                        if (cup) showCupBracketDialog(tournament); else showStandingsResultPicker(tournament);
                    })
                    .setPositiveButton("CLOSE", null).show();
        } catch (Exception e) {
            final String standings = standingsText == null ? "No results yet" : standingsText.getText().toString();
            new AlertDialog.Builder(this).setTitle("Tournament standings")
                    .setMessage(standings)
                    .setNegativeButton("COPY", (dialog, which) -> {
                        ClipboardManager clipboard = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Tournament standings", standings));
                        Toast.makeText(this, "Standings copied", Toast.LENGTH_SHORT).show();
                    })
                    .setPositiveButton("CLOSE", null).show();
        }
    }

    /** Standings are derived from game results; correct a game result to update both records consistently. */
    private void showStandingsResultPicker(TournamentManager tournament) {
        List<TournamentManager.Pairing> pairings = tournament.pairings();
        ArrayList<Integer> completed = new ArrayList<>();
        ArrayList<String> labels = new ArrayList<>();
        List<TournamentManager.Engine> engines = tournament.engines();
        for (TournamentManager.Pairing pairing : pairings) if (pairing.isComplete()) {
            completed.add(pairing.index);
            labels.add("Game " + (pairing.index + 1) + ": " + engineDisplayName(engines, pairing.whiteEngineId) +
                    " - " + engineDisplayName(engines, pairing.blackEngineId) + " (" + pairingResultLabel(pairing.outcome) + ")");
        }
        if (completed.isEmpty()) {
            Toast.makeText(this, "No completed game results to edit", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("Choose result to correct")
                .setItems(labels.toArray(new String[0]), (dialog, which) ->
                        showPairingResultEditor(tournament, completed.get(which)))
                .setNegativeButton("CANCEL", null).show();
    }

    private void showPairingResultEditor(TournamentManager tournament, int pairingIndex) {
        TournamentManager.Pairing pairing = tournament.pairings().get(pairingIndex);
        String[] results = {"White wins (1-0)", "Draw (1/2-1/2)", "Black wins (0-1)"};
        new AlertDialog.Builder(this).setTitle("Correct Game " + (pairingIndex + 1) + " result")
                .setItems(results, (dialog, which) -> {
                    TournamentManager.Outcome outcome = which == 0 ? TournamentManager.Outcome.WHITE_WIN :
                            which == 1 ? TournamentManager.Outcome.DRAW : TournamentManager.Outcome.BLACK_WIN;
                    if (outcome == pairing.outcome) {
                        Toast.makeText(this, "Result is already " + pairingResultLabel(outcome), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    new AlertDialog.Builder(this).setTitle("Confirm result correction")
                            .setMessage("Change Game " + (pairingIndex + 1) + " from " + pairingResultLabel(pairing.outcome) +
                                    " to " + pairingResultLabel(outcome) + "? Standings will be recalculated.")
                            .setNegativeButton("CANCEL", null)
                            .setPositiveButton("SAVE", (d, w) -> {
                                try {
                                    tournament.updatePairingResult(pairingIndex, outcome);
                                    invalidateCachedSchedule(tournament);
                                    String pgnSaveWarning = "";
                                    try { saveTournamentPgnAutomatically(tournament); }
                                    catch (Exception pgnError) { pgnSaveWarning = " · PGN export update failed: " + pgnError.getMessage(); }
                                    if (standingsText != null) standingsText.setText(formatStandings(tournament, tournament.standings(), tournament.engines()));
                                    refreshMirrorRanks();
                                    if (isMirrorShowing() && !mirrorReplayMode && mirrorSelectedTab == 1) renderMirrorStandingsSection();
                                    if (isMirrorShowing() && !mirrorReplayMode && mirrorSelectedTab == 2) renderMirrorScheduleSection();
                                    Toast.makeText(this, "Result corrected; standings, tournament JSON, and PGN result saved (moves unchanged)" + pgnSaveWarning, Toast.LENGTH_LONG).show();
                                    showStandingsDialog();
                                } catch (Exception e) {
                                    Toast.makeText(this, "Could not correct result: " + e.getMessage(), Toast.LENGTH_LONG).show();
                                }
                            }).show();
                }).setNegativeButton("CANCEL", null).show();
    }

    private String pairingResultLabel(TournamentManager.Outcome outcome) {
        switch (outcome) {
            case WHITE_WIN: return "1-0";
            case DRAW: return "½-½";
            case BLACK_WIN: return "0-1";
            default: return "*";
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
            if (activeTournamentManager == null) activeTournamentManager = TournamentManager.restore(this);
            TournamentManager tournament = activeTournamentManager;
            if (cachedScheduleDialog != null && cachedScheduleTournament == tournament) {
                if (cachedScheduleDirty) refreshCachedSchedule(tournament);
                cachedScheduleDialog.show();
                return;
            }
            if (cachedScheduleDialog != null) cachedScheduleDialog.dismiss();
            cachedScheduleTournament = tournament;
            cachedScheduleDirty = true;

            // One row per game makes the Android FastScroller useful even for multi-thousand-game events.
            cachedScheduleRows.clear();
            ListView scheduleList = new ListView(this);
            cachedScheduleList = scheduleList;
            scheduleList.setFastScrollEnabled(true);
            scheduleList.setFastScrollAlwaysVisible(true);
            scheduleList.setVerticalScrollBarEnabled(true);
            scheduleList.setScrollBarStyle(View.SCROLLBARS_INSIDE_INSET);
            cachedScheduleAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, cachedScheduleRows) {
                @Override public View getView(int position, View convertView, ViewGroup parent) {
                    TextView row = (TextView) super.getView(position, convertView, parent);
                    row.setTextSize(16);
                    row.setPadding(dp(12), dp(9), dp(30), dp(9));
                    row.setLineSpacing(dp(2), 1.0f);
                    row.setTextIsSelectable(true);
                    return row;
                }
            };
            scheduleList.setAdapter(cachedScheduleAdapter);
            LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            int screenHeight = getResources().getDisplayMetrics().heightPixels;
            int scheduleViewportHeight = Math.min(dp(660), Math.max(dp(440), (int)(screenHeight * 0.70f)));
            content.addView(scheduleList, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, scheduleViewportHeight));
            Button bracketButton = new Button(this);
            cachedScheduleBracketButton = bracketButton;
            bracketButton.setText("VIEW CUP BRACKET");
            bracketButton.setVisibility(tournament.format() == TournamentManager.Format.CUP ? View.VISIBLE : View.GONE);
            content.addView(bracketButton, fullWidth());
            bracketButton.setOnClickListener(v -> showCupBracketDialog(tournament));
            Button anomalies = new Button(this);
            cachedScheduleAnomaliesButton = anomalies;
            content.addView(anomalies, fullWidth());
            LinearLayout replayQueueRow = new LinearLayout(this);
            replayQueueRow.setOrientation(LinearLayout.HORIZONTAL);
            Button queueGames = new Button(this);
            cachedScheduleQueueButton = queueGames;
            queueGames.setText("PLAY QUEUE");
            Button replayGame = new Button(this);
            replayGame.setText("REPLAY GAME");
            replayQueueRow.addView(queueGames, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            replayQueueRow.addView(replayGame, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            content.addView(replayQueueRow, fullWidth());
            cachedScheduleDialog = new AlertDialog.Builder(this).setTitle(scheduleTitle(tournament))
                    .setView(content)
                    .setNegativeButton("COPY", (d, which) -> {
                        String current = cachedScheduleContents == null ? "" : cachedScheduleContents;
                        ClipboardManager clipboard = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Tournament schedule", current));
                        Toast.makeText(this, "Schedule copied", Toast.LENGTH_SHORT).show();
                    })
                    .setNeutralButton("EDIT", (d, which) -> showScheduleEditMenu(tournament))
                    .setPositiveButton("CLOSE", null).create();
            anomalies.setOnClickListener(v -> { cachedScheduleDialog.dismiss(); showAnomaliesDialog(tournament); });
            queueGames.setOnClickListener(v -> { cachedScheduleDialog.dismiss(); showPlayQueueEditor(tournament); });
            replayGame.setOnClickListener(v -> { cachedScheduleDialog.dismiss(); showReplayGamePicker(tournament); });
            refreshCachedSchedule(tournament);
            cachedScheduleDialog.show();
        } catch (Exception e) {
            Toast.makeText(this, "Could not load tournament schedule: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** Title includes completed/total progress; rows are cached until tournament state changes. */
    private String scheduleTitle(TournamentManager tournament) {
        String prefix = tournament.format() == TournamentManager.Format.CUP ? "Cup Schedule" : "Schedule";
        return prefix + " •" + tournament.completedGamesCount() + "/" + tournament.pairings().size();
    }

    private void refreshCachedSchedule(TournamentManager tournament) {
        if (cachedScheduleList == null || cachedScheduleTournament != tournament || cachedScheduleAdapter == null) {
            cachedScheduleDirty = true;
            return;
        }
        StringBuilder copy = new StringBuilder(Math.max(256, tournament.pairings().size() * 64));
        cachedScheduleRows.clear();
        List<TournamentManager.Engine> engines = tournament.engines();
        List<Integer> priorityQueue = tournament.playQueue();
        boolean swiss = tournament.format() == TournamentManager.Format.SWISS;
        boolean cup = tournament.format() == TournamentManager.Format.CUP;
        int displayedSwissRound = 0, displayedCupStage = -1, displayedCupMatch = -1;
        List<TournamentManager.Pairing> schedulePairings = new ArrayList<>(tournament.pairings());
        if (cup) {
            // Tiebreak pairs are appended to the execution queue when needed. Display
            // them beside their match rather than at the bottom of the whole schedule.
            java.util.Collections.sort(schedulePairings, (a, b) -> {
                int stageOrder = Integer.compare(a.round, b.round);
                if (stageOrder != 0) return stageOrder;
                int matchOrder = Integer.compare(a.cupMatchNumber, b.cupMatchNumber);
                if (matchOrder != 0) return matchOrder;
                int pairOrder = Integer.compare(a.cupPairNumber, b.cupPairNumber);
                if (pairOrder != 0) return pairOrder;
                return Integer.compare(a.cupGameInPair, b.cupGameInPair);
            });
        }
        for (TournamentManager.Pairing pairing : schedulePairings) {
            if (swiss && pairing.round != displayedSwissRound) {
                if (displayedSwissRound > 0) appendSwissByeRows(tournament, engines, displayedSwissRound, copy);
                displayedSwissRound = pairing.round;
                String heading = "ROUND " + displayedSwissRound;
                cachedScheduleRows.add(heading);
                if (copy.length() > 0) copy.append("\n\n");
                copy.append(heading);
            }
            if (cup && pairing.round - 1 != displayedCupStage) {
                if (displayedCupStage >= 0) appendCupByeRows(tournament, engines, displayedCupStage, copy);
                displayedCupStage = pairing.round - 1;
                displayedCupMatch = -1;
                String heading = CupTournamentManager.stageLabel(tournament.cupBracketSize(), displayedCupStage).toUpperCase(java.util.Locale.US);
                cachedScheduleRows.add("========== " + heading + " ==========");
                if (copy.length() > 0) copy.append("\n\n");
                copy.append("========== ").append(heading).append(" ==========");
            }
            if (cup && pairing.cupMatchNumber != displayedCupMatch) {
                displayedCupMatch = pairing.cupMatchNumber;
                TournamentManager.CupMatch match = findCupMatch(tournament, displayedCupMatch);
                String aName = match == null ? "" : cupMatchEngineName(tournament, match, true, engines);
                String bName = match == null ? "" : cupMatchEngineName(tournament, match, false, engines);
                String matchHeading = "MATCH #" + displayedCupMatch + " · " + aName + " vs " + bName;
                cachedScheduleRows.add(matchHeading);
                if (copy.length() > 0) copy.append("\n\n");
                copy.append(matchHeading);
            }
            String white = pairing.isComplete() && !pairing.whiteNameAtPlay.isEmpty()
                    ? pairing.whiteNameAtPlay : engineDisplayName(engines, pairing.whiteEngineId);
            String black = pairing.isComplete() && !pairing.blackNameAtPlay.isEmpty()
                    ? pairing.blackNameAtPlay : engineDisplayName(engines, pairing.blackEngineId);
            String result = pairingResultLabel(pairing.outcome);
            int endedAtMoves = countFullMoves(pairing.pgn);
            int queuePosition = priorityQueue.indexOf(pairing.index) + 1;
            String state = pairing.replayPending ? " · replay queued" : (queuePosition > 0 ? " · priority #" + queuePosition : "");
            String gamePrefix = swiss ? "R" + pairing.round + "." + pairing.gameNumber
                    : (cup ? "Pair " + pairing.cupPairNumber + " · Game " + pairing.cupGameInPair
                    : Integer.toString(pairing.index + 1));
            String row = gamePrefix + ". " + white + " - " + black + " (" + result + ")" + state
                    + "\n" + endedAtMoves + " moves";
            cachedScheduleRows.add(row);
            if (copy.length() > 0) copy.append("\n\n");
            copy.append(row);
        }
        if (cup && displayedCupStage >= 0) appendCupByeRows(tournament, engines, displayedCupStage, copy);
        if (swiss && displayedSwissRound > 0) appendSwissByeRows(tournament, engines, displayedSwissRound, copy);
        if (cachedScheduleRows.isEmpty()) {
            cachedScheduleRows.add("No scheduled games.");
            copy.append("No scheduled games.");
        }
        cachedScheduleContents = copy.toString();
        cachedScheduleAdapter.notifyDataSetChanged();
        if (cachedScheduleDialog != null) cachedScheduleDialog.setTitle(scheduleTitle(tournament));
        if (cachedScheduleBracketButton != null)
            cachedScheduleBracketButton.setVisibility(tournament.format() == TournamentManager.Format.CUP ? View.VISIBLE : View.GONE);
        cachedScheduleDirty = false;
        List<TournamentManager.Pairing> timeLosses = tournament.pendingTimeLosses();
        List<TournamentManager.Pairing> drawAnomalies = tournament.pendingDrawAnomalies();
        if (cachedScheduleAnomaliesButton != null)
            cachedScheduleAnomaliesButton.setText("ANOMALIES · " + timeLosses.size() + " TIME LOSSES · " + drawAnomalies.size() + " DRAWS");
        if (cachedScheduleQueueButton != null)
            cachedScheduleQueueButton.setText("PLAY QUEUE · " + tournament.playQueue().size());
    }


    private void appendSwissByeRows(TournamentManager tournament, List<TournamentManager.Engine> engines,
                                    int round, StringBuilder copy) {
        for (TournamentManager.SwissBye bye : tournament.swissByes()) {
            if (bye.round != round) continue;
            String row = "BYE · " + engineDisplayName(engines, bye.engineId) + " (+2 points)";
            cachedScheduleRows.add(row);
            if (copy.length() > 0) copy.append("\n\n");
            copy.append(row);
        }
    }

    private TournamentManager.CupMatch findCupMatch(TournamentManager tournament, int matchNumber) {
        for (TournamentManager.CupMatch match : tournament.cupMatches()) if (match.matchNumber == matchNumber) return match;
        return null;
    }

    private void appendCupByeRows(TournamentManager tournament, List<TournamentManager.Engine> engines,
                                  int stageIndex, StringBuilder copy) {
        for (TournamentManager.CupMatch match : tournament.cupMatches()) {
            if (match.stageIndex != stageIndex || !match.bye) continue;
            String row = "MATCH #" + match.matchNumber + " · BYE · " +
                    engineDisplayName(engines, match.winnerId) + " advances";
            cachedScheduleRows.add(row);
            if (copy.length() > 0) copy.append("\n\n");
            copy.append(row);
        }
    }

    private String cupMatchEngineName(TournamentManager tournament, TournamentManager.CupMatch match,
                                      boolean first, List<TournamentManager.Engine> engines) {
        if (match == null) return "TBD";
        String id = first ? match.engineAId : match.engineBId;
        if (id == null || id.isEmpty()) return match.bye ? "BYE" : "TBD";
        if (!match.gameIndices.isEmpty()) {
            int gameIndex = match.gameIndices.get(0);
            if (gameIndex >= 0 && gameIndex < tournament.pairings().size()) {
                TournamentManager.Pairing firstGame = tournament.pairings().get(gameIndex);
                String historical = first ? firstGame.whiteNameAtPlay : firstGame.blackNameAtPlay;
                if (historical != null && !historical.isEmpty()) return historical;
            }
        }
        return engineDisplayName(engines, id);
    }

    /** Lightweight horizontal bracket, backed by the same persisted match records as the schedule. */
    private void showCupBracketDialog(TournamentManager tournament) {
        if (tournament == null || tournament.format() != TournamentManager.Format.CUP) return;
        final int bracketHeight = Math.min(dp(660), Math.max(dp(420),
                (int)(getResources().getDisplayMetrics().heightPixels * 0.68f)));
        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        horizontal.setFillViewport(false);
        horizontal.setHorizontalScrollBarEnabled(true);
        LinearLayout stages = new LinearLayout(this);
        stages.setOrientation(LinearLayout.HORIZONTAL);
        stages.setPadding(dp(8), dp(8), dp(8), dp(8));
        List<TournamentManager.Engine> engines = tournament.engines();
        int bracketSize = tournament.cupBracketSize();
        int stageCount = CupTournamentManager.stageCount(bracketSize);
        List<TournamentManager.CupMatch> allMatches = tournament.cupMatches();
        for (int stage = 0; stage < stageCount; stage++) {
            LinearLayout column = new LinearLayout(this);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setPadding(dp(4), dp(4), dp(12), dp(4));
            TextView heading = text(CupTournamentManager.stageLabel(bracketSize, stage));
            heading.setTextSize(17);
            heading.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
            heading.setPadding(dp(4), dp(8), dp(4), dp(12));
            column.addView(heading, fullWidth());

            int expectedMatches = bracketSize >> (stage + 1);
            int matchOffset = 0;
            for (int prior = 0; prior < stage; prior++) matchOffset += bracketSize >> (prior + 1);
            int rendered = 0;
            for (TournamentManager.CupMatch match : allMatches) {
                if (match.stageIndex != stage) continue;
                rendered++;
                LinearLayout card = new LinearLayout(this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(8), dp(6), dp(8), dp(6));
                card.setBackgroundColor(0xFFE2E2E2);
                TextView number = text("#" + match.matchNumber);
                number.setTextColor(0xFF333333);
                number.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
                String aName = cupMatchEngineName(tournament, match, true, engines);
                String bName = cupMatchEngineName(tournament, match, false, engines);
                TextView a = text(aName + "   " + (match.bye ? "BYE" : String.format(java.util.Locale.US, "%.1f", match.engineAPoints)));
                TextView b = text(bName + "   " + (match.bye ? "--" : String.format(java.util.Locale.US, "%.1f", match.engineBPoints)));
                a.setTextColor(match.decided && match.winnerId.equals(match.engineAId) ? 0xFF176B2C : 0xFF222222);
                b.setTextColor(match.decided && !match.winnerId.isEmpty() && match.winnerId.equals(match.engineBId) ? 0xFF176B2C : 0xFF222222);
                String stateText;
                if (match.bye) {
                    stateText = "BYE · advances";
                } else if (match.decided) {
                    stateText = "Advances: " + engineDisplayName(engines, match.winnerId);
                    if (stage + 1 < stageCount) {
                        int nextStageMatchOffset = 0;
                        for (int prior = 0; prior <= stage; prior++) nextStageMatchOffset += bracketSize >> (prior + 1);
                        int matchPosition = 0;
                        for (TournamentManager.CupMatch candidate : allMatches) {
                            if (candidate.stageIndex == stage) {
                                if (candidate.matchNumber == match.matchNumber) break;
                                matchPosition++;
                            }
                        }
                        stateText += " · Winner to #" + (nextStageMatchOffset + matchPosition / 2 + 1);
                    }
                } else {
                    stateText = "Match in progress · " + match.pairsCompleted + "/" + match.pairsScheduled + " pairs";
                }
                TextView state = text(stateText);
                state.setTextSize(12);
                state.setTextColor(0xFF555555);
                card.addView(number, fullWidth());
                card.addView(a, fullWidth());
                card.addView(b, fullWidth());
                card.addView(state, fullWidth());
                LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(dp(250), ViewGroup.LayoutParams.WRAP_CONTENT);
                cardLp.bottomMargin = dp(10);
                column.addView(card, cardLp);
            }
            // Render future rounds as simple TBD cards so the complete bracket is visible
            // before the first stage finishes. The match numbering is fixed and persisted
            // when each stage is generated.
            for (int i = rendered; i < expectedMatches; i++) {
                int matchNumber = matchOffset + i + 1;
                LinearLayout card = new LinearLayout(this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(8), dp(6), dp(8), dp(6));
                card.setBackgroundColor(0xFFD8D8D8);
                TextView number = text("#" + matchNumber);
                number.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
                TextView a = text("TBD   --");
                TextView b = text("TBD   --");
                TextView state = text("Waiting for previous stage");
                state.setTextSize(12); state.setTextColor(0xFF666666);
                card.addView(number, fullWidth()); card.addView(a, fullWidth());
                card.addView(b, fullWidth()); card.addView(state, fullWidth());
                LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(dp(250), ViewGroup.LayoutParams.WRAP_CONTENT);
                cardLp.bottomMargin = dp(10);
                column.addView(card, cardLp);
            }

            ScrollView vertical = new ScrollView(this);
            vertical.setFillViewport(false);
            vertical.addView(column, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams columnLp = new LinearLayout.LayoutParams(dp(270), bracketHeight);
            stages.addView(vertical, columnLp);
        }
        horizontal.addView(stages, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, bracketHeight));
        // This project targets an AlertDialog.Builder API that only exposes
        // setView(View), not the newer setView(View, left, top, right, bottom)
        // overload. Keep the intended inset by applying padding to a wrapper.
        LinearLayout bracketContainer = new LinearLayout(this);
        bracketContainer.setOrientation(LinearLayout.VERTICAL);
        bracketContainer.setPadding(dp(4), dp(4), dp(4), dp(4));
        bracketContainer.addView(horizontal, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this).setTitle("Cup bracket · " + tournament.title())
                .setView(bracketContainer).setPositiveButton("CLOSE", null).show();
    }

    private void invalidateCachedSchedule(TournamentManager tournament) {
        if (cachedScheduleTournament == tournament) {
            cachedScheduleDirty = true;
            if (cachedScheduleDialog != null && cachedScheduleDialog.isShowing()) refreshCachedSchedule(tournament);
        }
    }

    /** Multi-select unfinished scheduled games in the operator's chosen execution order. */
    private void showPlayQueueEditor(TournamentManager tournament) {
        ArrayList<Integer> gameIndices = new ArrayList<>();
        ArrayList<String> labels = new ArrayList<>();
        List<TournamentManager.Engine> engines = tournament.engines();
        List<TournamentManager.Pairing> pairings = tournament.pairings();
        for (TournamentManager.Pairing pairing : pairings) {
            if (pairing.isComplete() || pairing.replayPending || pairing.index == tournament.activePairingIndex()) continue;
            String white = engineDisplayName(engines, pairing.whiteEngineId);
            String black = engineDisplayName(engines, pairing.blackEngineId);
            gameIndices.add(pairing.index);
            labels.add("Game " + (pairing.index + 1) + ": " + white + " - " + black);
        }
        if (gameIndices.isEmpty()) {
            Toast.makeText(this, "No unfinished games available for the play queue", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean[] checked = new boolean[gameIndices.size()];
        for (int i = 0; i < gameIndices.size(); i++) checked[i] = tournament.isQueued(gameIndices.get(i));
        new AlertDialog.Builder(this).setTitle("Play queue · Select games")
                .setMultiChoiceItems(labels.toArray(new String[0]), checked, (dialog, which, isChecked) -> {
                    int gameIndex = gameIndices.get(which);
                    try {
                        tournament.setQueued(gameIndex, isChecked);
                        invalidateCachedSchedule(tournament);
                        String action = isChecked ? "added to" : "removed from";
                        int queuePosition = tournament.playQueue().indexOf(gameIndex) + 1;
                        String position = isChecked ? " · queue position " + queuePosition : "";
                        Toast.makeText(this, "Game " + (gameIndex + 1) + " " + action + " priority queue" + position, Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        ((AlertDialog)dialog).getListView().setItemChecked(which, !isChecked);
                        Toast.makeText(this, "Could not update play queue: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }).setPositiveButton("DONE", (dialog, which) -> showScheduleDialog())
                .setNegativeButton("CANCEL", (dialog, which) -> showScheduleDialog()).show();
    }

    /** Select a completed schedule entry to replay with its persisted original opening. */
    private void showReplayGamePicker(TournamentManager tournament) {
        if (tournament.format() == TournamentManager.Format.CUP) {
            Toast.makeText(this, "Cup results are locked to preserve the knockout bracket. A future Cup correction tool can handle controlled match corrections.", Toast.LENGTH_LONG).show();
            return;
        }
        ArrayList<Integer> gameIndices = new ArrayList<>();
        ArrayList<String> labels = new ArrayList<>();
        List<TournamentManager.Engine> engines = tournament.engines();
        for (TournamentManager.Pairing pairing : tournament.pairings()) {
            if (!pairing.isComplete() || pairing.replayPending) continue;
            String white = engineDisplayName(engines, pairing.whiteEngineId);
            String black = engineDisplayName(engines, pairing.blackEngineId);
            String opening = pairing.openingName == null || pairing.openingName.trim().isEmpty()
                    ? "Starting position" : pairing.openingName;
            gameIndices.add(pairing.index);
            labels.add("Game " + (pairing.index + 1) + ": " + white + " - " + black +
                    " (" + pairingResultLabel(pairing.outcome) + ") · " + opening);
        }
        if (gameIndices.isEmpty()) {
            Toast.makeText(this, "No completed games available to replay", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("Choose a game to replay")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    int gameIndex = gameIndices.get(which);
                    TournamentManager.Pairing selected = tournament.pairings().get(gameIndex);
                    String opening = selected.openingName == null || selected.openingName.trim().isEmpty()
                            ? "Starting position" : selected.openingName;
                    new AlertDialog.Builder(this).setTitle("Replay Game " + (gameIndex + 1))
                            .setMessage("The replacement game will use the same engine pairing and saved opening (" + opening +"). " +
                                    "The existing result and PGN remain recoverable until the replay finishes. The new game will replace the old result and moves.")
                            .setNegativeButton("CANCEL", null)
                            .setPositiveButton("REPLAY", (confirm, action) -> {
                                try {
                                    tournament.requestReplay(gameIndex);
                                    invalidateCachedSchedule(tournament);
                                    if (runner == null) {
                                        launchTournament(tournament);
                                    } else {
                                        Toast.makeText(this, "Game " + (gameIndex + 1) + " placed at the front of the play queue. The current game will finish first.", Toast.LENGTH_LONG).show();
                                    }
                                } catch (Exception e) {
                                    Toast.makeText(this, "Could not queue replay: " + e.getMessage(), Toast.LENGTH_LONG).show();
                                }
                            }).show();
                }).setNegativeButton("CANCEL", null).show();
    }

    /** Lists pending anomalies with direct, per-game result controls. */
    private void showAnomaliesDialog(TournamentManager tournament) {
        try {
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(12), dp(8), dp(12), dp(4));
            TextView summary = text("");
            summary.setTextSize(16);
            summary.setPadding(dp(4), dp(4), dp(4), dp(12));
            root.addView(summary, fullWidth());
            LinearLayout rows = new LinearLayout(this);
            rows.setOrientation(LinearLayout.VERTICAL);
            ScrollView scroll = new ScrollView(this);
            scroll.addView(rows, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(500)));
            final AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Anomalies")
                    .setView(root).setPositiveButton("CLOSE", null).create();
            cachedAnomalyDialog = dialog;
            cachedAnomalyTournament = tournament;
            cachedAnomalySummary = summary;
            cachedAnomalyRows = rows;
            dialog.setOnDismissListener(d -> {
                if (cachedAnomalyDialog == dialog) {
                    cachedAnomalyDialog = null;
                    cachedAnomalyTournament = null;
                    cachedAnomalySummary = null;
                    cachedAnomalyRows = null;
                }
            });
            refreshAnomalyRows(tournament, summary, rows, dialog);
            dialog.show();
        } catch (Exception e) {
            Toast.makeText(this, "Could not load anomalies: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void refreshAnomalyRows(TournamentManager tournament, TextView summary, LinearLayout rows, AlertDialog dialog) {
        List<TournamentManager.Pairing> timeLosses = tournament.pendingTimeLosses();
        List<TournamentManager.Pairing> drawAnomalies = tournament.pendingDrawAnomalies();
        summary.setText(completedGameCount(tournament) + " completed games total\n" +
                timeLosses.size() + " losses on time\n" + drawAnomalies.size() + " anomalous draws");
        rows.removeAllViews();
        if (timeLosses.isEmpty() && drawAnomalies.isEmpty()) {
            TextView none = text("No pending anomalies. All flagged games have been adjudicated or confirmed.");
            none.setPadding(dp(8), dp(16), dp(8), dp(16));
            rows.addView(none, fullWidth());
            return;
        }
        if (!timeLosses.isEmpty()) {
            addAnomalySectionHeader(rows, "LOSSES ON TIME (" + timeLosses.size() + " games)");
            for (TournamentManager.Pairing pairing : timeLosses) addAnomalyGameRow(rows, tournament, pairing, summary, dialog);
        }
        if (!drawAnomalies.isEmpty()) {
            addAnomalySectionHeader(rows, "DRAW (!) (" + drawAnomalies.size() + " games)");
            for (TournamentManager.Pairing pairing : drawAnomalies) addAnomalyGameRow(rows, tournament, pairing, summary, dialog);
        }
    }

    private void addAnomalySectionHeader(LinearLayout parent, String title) {
        TextView header = text(title);
        header.setTypeface(null, android.graphics.Typeface.BOLD);
        header.setTextSize(15);
        header.setPadding(dp(8), dp(14), dp(8), dp(8));
        parent.addView(header, fullWidth());
    }

    private void addAnomalyGameRow(LinearLayout parent, TournamentManager tournament,
                                   TournamentManager.Pairing pairing, TextView summary, AlertDialog dialog) {
        List<TournamentManager.Engine> engines = tournament.engines();
        String white = engineDisplayName(engines, pairing.whiteEngineId);
        String black = engineDisplayName(engines, pairing.blackEngineId);
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setPadding(dp(8), dp(8), dp(8), dp(8));
        TextView description = text("Game " + (pairing.index + 1) + ": " + white + " - " + black +
                " (" + pairingResultLabel(pairing.outcome) + ")\n" + tournament.anomalyReason(pairing));
        description.setTextSize(15);
        item.addView(description, fullWidth());
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        String[] labels = {"WHITE WIN", "BLACK WIN", "DRAW", "CONFIRM"};
        for (int action = 0; action < labels.length; action++) {
            final int selectedAction = action;
            Button button = new Button(this);
            button.setText(labels[action]);
            button.setTextSize(10);
            if (tournament.format() == TournamentManager.Format.CUP && selectedAction != 3) button.setEnabled(false);
            button.setMinWidth(0);
            button.setPadding(dp(2), 0, dp(2), 0);
            actions.addView(button, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            button.setOnClickListener(v -> {
                if (selectedAction == 3) {
                    new AlertDialog.Builder(this).setTitle("Confirm existing result")
                            .setMessage("Keep " + pairingResultLabel(pairing.outcome) + " for Game " + (pairing.index + 1) +
                                    " and remove it from the anomaly list?")
                            .setNegativeButton("CANCEL", null)
                            .setPositiveButton("CONFIRM", (d, w) -> {
                                try {
                                    tournament.markAnomalyReviewed(pairing.index);
                                    invalidateCachedSchedule(tournament);
                                    Toast.makeText(this, "Game confirmed", Toast.LENGTH_SHORT).show();
                                    refreshAnomalyRows(tournament, summary, parent, dialog);
                                } catch (Exception e) {
                                    Toast.makeText(this, "Could not confirm game: " + e.getMessage(), Toast.LENGTH_LONG).show();
                                }
                            }).show();
                    return;
                }
                TournamentManager.Outcome outcome = selectedAction == 0 ? TournamentManager.Outcome.WHITE_WIN :
                        selectedAction == 1 ? TournamentManager.Outcome.BLACK_WIN : TournamentManager.Outcome.DRAW;
                new AlertDialog.Builder(this).setTitle("Confirm adjudication")
                        .setMessage("Set Game " + (pairing.index + 1) + " to " + pairingResultLabel(outcome) +
                                "? Standings, tournament JSON, and PGN result metadata will be updated. Moves and annotations will remain unchanged.")
                        .setNegativeButton("CANCEL", null)
                        .setPositiveButton("ADJUDICATE", (d, w) -> {
                            try {
                                tournament.updatePairingResult(pairing.index, outcome);
                                String warning = "";
                                try { saveTournamentPgnAutomatically(tournament); }
                                catch (Exception pgnError) { warning = " · PGN export update failed: " + pgnError.getMessage(); }
                                if (standingsText != null) standingsText.setText(formatStandings(tournament, tournament.standings(), tournament.engines()));
                                invalidateCachedSchedule(tournament);
                                Toast.makeText(this, "Game adjudicated" + warning, Toast.LENGTH_LONG).show();
                                refreshAnomalyRows(tournament, summary, parent, dialog);
                            } catch (Exception e) {
                                Toast.makeText(this, "Could not adjudicate game: " + e.getMessage(), Toast.LENGTH_LONG).show();
                            }
                        }).show();
            });
        }
        if (tournament.format() == TournamentManager.Format.CUP) {
            TextView locked = text("Cup match results are locked to preserve bracket advancement. Use CONFIRM to acknowledge this anomaly.");
            locked.setTextSize(12); locked.setTextColor(0xFFAAAAAA); item.addView(locked, fullWidth());
        }
        item.addView(actions, fullWidth());
        parent.addView(item, fullWidth());
        View divider = new View(this);
        divider.setBackgroundColor(0xFF666666);
        parent.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
    }

    /** Offers both pairing edits and tournament-wide engine identity corrections. */
    private void showScheduleEditMenu(TournamentManager tournament) {
        if (tournament.format() == TournamentManager.Format.CUP) {
            Toast.makeText(this, "Cup match results are locked into the bracket after a match advances. Engine updates are available at each stage checkpoint.", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("Edit tournament")
                .setItems(new String[]{"Edit scheduled game / result", "Edit tournament engine"}, (dialog, which) -> {
                    if (which == 0) showScheduleEditor(tournament);
                    else showTournamentEnginePicker(tournament);
                }).setNegativeButton("CANCEL", null).show();
    }

    private void showTournamentEnginePicker(TournamentManager tournament) {
        List<TournamentManager.Engine> engines = tournament.engines();
        if (engines.isEmpty()) {
            Toast.makeText(this, "No tournament engines are available to edit", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[engines.size()];
        for (int i = 0; i < engines.size(); i++) {
            TournamentManager.Engine engine = engines.get(i);
            labels[i] = engine.displayName + " · " + tournament.completedGamesForEngine(engine.id) + " completed";
        }
        new AlertDialog.Builder(this).setTitle("Choose tournament engine to edit")
                .setItems(labels, (dialog, which) -> {
                    if (which >= 0 && which < engines.size()) showTournamentEngineEditor(tournament, which);
                }).setNegativeButton("CANCEL", null).show();
    }

    private void showTournamentEngineEditor(TournamentManager tournament, int engineIndex) {
        List<TournamentManager.Engine> engines = tournament.engines();
        if (engineIndex < 0 || engineIndex >= engines.size()) return;
        TournamentManager.Engine engine = engines.get(engineIndex);
        loadEngineChoices();
        int completedGames = tournament.completedGamesForEngine(engine.id);
        boolean activeEngine = tournament.isEngineInActivePairing(engine.id);
        final String[] selectedExecutableId = {engine.id};

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), dp(8));
        TextView nameLabel = text("Engine name shown in standings, schedule, and PGN");
        form.addView(nameLabel, fullWidth());
        EditText nameInput = new EditText(this);
        nameInput.setSingleLine(true);
        nameInput.setText(engine.displayName);
        nameInput.setSelection(nameInput.getText().length());
        form.addView(nameInput, fullWidth());

        TextView executableLabel = text("Tournament executable");
        executableLabel.setPadding(0, dp(12), 0, dp(4));
        form.addView(executableLabel, fullWidth());
        TextView executablePath = text(engine.id);
        executablePath.setTextSize(12);
        executablePath.setTextIsSelectable(true);
        form.addView(executablePath, fullWidth());
        Button chooseExecutable = new Button(this);
        chooseExecutable.setText("Choose installed engine version");
        form.addView(chooseExecutable, fullWidth());
        boolean executableCanChange = completedGames == 0 && !activeEngine;
        chooseExecutable.setEnabled(executableCanChange && !availableEngines.isEmpty());
        chooseExecutable.setOnClickListener(v -> {
            ArrayList<EngineChoice> choices = new ArrayList<>();
            ArrayList<String> choiceLabels = new ArrayList<>();
            for (EngineChoice choice : availableEngines) {
                if (choice.id == null || choice.id.trim().isEmpty()) continue;
                choices.add(choice);
                choiceLabels.add(choice.name + "\n" + choice.id);
            }
            if (choices.isEmpty()) {
                Toast.makeText(this, "No installed replacement engines were found", Toast.LENGTH_SHORT).show();
                return;
            }
            new AlertDialog.Builder(this).setTitle("Select replacement executable")
                    .setItems(choiceLabels.toArray(new String[0]), (d, selected) -> {
                        if (selected < 0 || selected >= choices.size()) return;
                        EngineChoice choice = choices.get(selected);
                        selectedExecutableId[0] = choice.id;
                        executablePath.setText(choice.id);
                        nameInput.setText(choice.name);
                        nameInput.setSelection(nameInput.getText().length());
                    }).setNegativeButton("CANCEL", null).show();
        });

        TextView note = text(executableCanChange
                ? "You can correct the display name only, or select a newer installed executable. Replacing the executable updates every unfinished scheduled pairing for this engine."
                : (completedGames > 0
                    ? "This engine has completed games. Its display name can still be corrected, including saved PGN player tags, but its executable cannot be replaced to protect historical results."
                    : "This engine is part of the active or interrupted game. Its display name can be corrected, but its executable cannot be changed until that game is resolved."));
        note.setTextSize(13);
        note.setPadding(0, dp(8), 0, dp(4));
        form.addView(note, fullWidth());

        new AlertDialog.Builder(this).setTitle("Edit " + engine.displayName)
                .setView(form)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("SAVE", (dialog, which) -> {
                    String newName = nameInput.getText().toString().trim();
                    String newId = selectedExecutableId[0];
                    if (newName.isEmpty()) {
                        Toast.makeText(this, "Engine name cannot be empty", Toast.LENGTH_LONG).show();
                        return;
                    }
                    boolean executableChanged = !engine.id.equals(newId);
                    Runnable applyUpdate = () -> {
                        try {
                            tournament.updateTournamentEngine(engineIndex, newId, newName);
                            invalidateCachedSchedule(tournament);
                            if (standingsText != null) standingsText.setText(formatStandings(tournament, tournament.standings(), tournament.engines()));
                            String warning = "";
                            try { saveTournamentPgnAutomatically(tournament); }
                            catch (Exception pgnError) { warning = " · PGN export update failed: " + pgnError.getMessage(); }
                            Toast.makeText(this, (executableChanged
                                    ? "Tournament engine updated; all unfinished pairings now use " + newName
                                    : "Tournament engine name corrected to " + newName) + warning, Toast.LENGTH_LONG).show();
                            if (cachedScheduleDialog != null && cachedScheduleDialog.isShowing()) refreshCachedSchedule(tournament);
                        } catch (Exception e) {
                            Toast.makeText(this, "Could not update tournament engine: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    };
                    if (executableChanged) {
                        new AlertDialog.Builder(this).setTitle("Confirm engine replacement")
                                .setMessage("Replace " + engine.displayName + " with " + newName + " for every unfinished scheduled game? Completed games and their recorded results will not be reassigned.")
                                .setNegativeButton("CANCEL", null)
                                .setPositiveButton("REPLACE", (confirm, action) -> applyUpdate.run()).show();
                    } else {
                        applyUpdate.run();
                    }
                }).show();
    }

    /** Edit future pairings only; an in-flight runner owns its current pairing. */
    private void showScheduleEditor(TournamentManager tournament) {
        List<TournamentManager.Pairing> pairings = tournament.pairings();
        int lockedIndex = -1;
        if (runner != null) {
            TournamentManager.Pairing current = tournament.current();
            if (current != null) lockedIndex = current.index;
        }
        ArrayList<Integer> editable = new ArrayList<>();
        ArrayList<String> labels = new ArrayList<>();
        List<TournamentManager.Engine> engines = tournament.engines();
        for (TournamentManager.Pairing pairing : pairings) {
            String white = engineDisplayName(engines, pairing.whiteEngineId);
            String black = engineDisplayName(engines, pairing.blackEngineId);
            String gameLabel = tournament.format() == TournamentManager.Format.SWISS
                    ? "Round " + pairing.round + " · Game " + pairing.gameNumber
                    : "Game " + (pairing.index + 1);
            String label = gameLabel + ": " + white + " - " + black + " (" + pairingResultLabel(pairing.outcome) + ")";
            if (pairing.replayPending) {
                // The replacement game owns this record until it finishes.
            } else if (pairing.isComplete()) {
                editable.add(pairing.index); labels.add(label + " · correct result");
            } else if (runner == null || pairing.index > lockedIndex) {
                editable.add(pairing.index); labels.add(label + " · edit pairing");
            }
        }
        if (editable.isEmpty()) {
            Toast.makeText(this, runner == null ? "No games available to edit" : "No future pairings available to edit", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("Choose a scheduled game")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    int index = editable.get(which);
                    TournamentManager.Pairing pairing = tournament.pairings().get(index);
                    if (pairing.isComplete()) showPairingResultEditor(tournament, index);
                    else showPairingEnginesEditor(tournament, index);
                }).setNegativeButton("CANCEL", null).show();
    }

    private void showPairingEnginesEditor(TournamentManager tournament, int pairingIndex) {
        TournamentManager.Pairing pairing = tournament.pairings().get(pairingIndex);
        List<TournamentManager.Engine> engines = tournament.engines();
        ArrayList<String> names = new ArrayList<>();
        int whiteSelection = 0, blackSelection = Math.min(1, engines.size() - 1);
        for (int i = 0; i < engines.size(); i++) {
            TournamentManager.Engine engine = engines.get(i);
            names.add(engine.displayName);
            if (engine.id.equals(pairing.whiteEngineId)) whiteSelection = i;
            if (engine.id.equals(pairing.blackEngineId)) blackSelection = i;
        }
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), dp(4));
        TextView whiteLabel = text("White engine");
        Spinner whiteSpinner = new Spinner(this);
        whiteSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        whiteSpinner.setSelection(whiteSelection);
        TextView blackLabel = text("Black engine");
        Spinner blackSpinner = new Spinner(this);
        blackSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        blackSpinner.setSelection(blackSelection);
        form.addView(whiteLabel, fullWidth()); form.addView(whiteSpinner, fullWidth());
        form.addView(blackLabel, fullWidth()); form.addView(blackSpinner, fullWidth());
        String editTitle = tournament.format() == TournamentManager.Format.SWISS
                ? "Edit Round " + pairing.round + " · Game " + pairing.gameNumber
                : "Edit Game " + (pairingIndex + 1);
        new AlertDialog.Builder(this).setTitle(editTitle)
                .setView(form)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("SAVE", (dialog, which) -> {
                    int wi = whiteSpinner.getSelectedItemPosition();
                    int bi = blackSpinner.getSelectedItemPosition();
                    if (wi == bi) {
                        Toast.makeText(this, "Choose two different engines", Toast.LENGTH_LONG).show();
                        return;
                    }
                    try {
                        tournament.updatePairingEngines(pairingIndex, engines.get(wi).id, engines.get(bi).id);
                        invalidateCachedSchedule(tournament);
                        Toast.makeText(this, "Schedule updated and tournament saved", Toast.LENGTH_LONG).show();
                        showScheduleDialog();
                    } catch (Exception e) {
                        Toast.makeText(this, "Could not update schedule: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }).show();
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

    private void applyTournamentFormatUi(int formatPosition) {
        boolean swiss = formatPosition == TournamentManager.Format.SWISS.ordinal();
        boolean cup = formatPosition == TournamentManager.Format.CUP.ordinal();
        if (swissRoundsInput != null) swissRoundsInput.setVisibility(swiss ? View.VISIBLE : View.GONE);
        if (swissSeedingSpinner != null) swissSeedingSpinner.setVisibility(swiss ? View.VISIBLE : View.GONE);
        if (swissSetupHint != null) swissSetupHint.setVisibility(swiss ? View.VISIBLE : View.GONE);
        if (cupSeedingLabel != null) cupSeedingLabel.setVisibility(cup ? View.VISIBLE : View.GONE);
        if (cupSeedingSpinner != null) cupSeedingSpinner.setVisibility(cup ? View.VISIBLE : View.GONE);
        if (cupSetupHint != null) cupSetupHint.setVisibility(cup ? View.VISIBLE : View.GONE);
        updateSwissSeedingUi();
        updateCupSeedingUi();
        boolean forceTwoGames = swiss || cup;
        if (forceTwoGames != forcedPairFormat && gamesInput != null && repeatOpeningCheck != null) {
            if (forceTwoGames) {
                gamesPerPairBeforeForcedPairFormat = gamesInput.getText().toString();
                repeatOpeningBeforeForcedPairFormat = repeatOpeningCheck.isChecked();
                gamesInput.setText("2");
                repeatOpeningCheck.setChecked(true);
            } else {
                gamesInput.setText(gamesPerPairBeforeForcedPairFormat);
                repeatOpeningCheck.setChecked(repeatOpeningBeforeForcedPairFormat);
            }
            forcedPairFormat = forceTwoGames;
        }
        if (gamesInput != null) {
            gamesInput.setVisibility(cup ? View.GONE : View.VISIBLE);
            gamesInput.setEnabled(!forceTwoGames);
            gamesInput.setHint(swiss ? "Games per Swiss pairing (fixed at 2)" : "Games per pairing");
        }
        if (repeatOpeningCheck != null) {
            repeatOpeningCheck.setEnabled(!forceTwoGames);
            repeatOpeningCheck.setChecked(forceTwoGames || repeatOpeningCheck.isChecked());
            repeatOpeningCheck.setText(cup ? "Repeat opening for every color-reversed Cup game pair (required)" :
                    "Repeat opening for paired color-reversal games");
        }
    }

    private void applySwissSetupMode(boolean swiss) {
        applyTournamentFormatUi(swiss ? TournamentManager.Format.SWISS.ordinal() :
                (formatSpinner == null ? TournamentManager.Format.ROUND_ROBIN.ordinal() : formatSpinner.getSelectedItemPosition()));
    }

    private void updateSwissSeedingUi() {
        boolean swiss = formatSpinner != null &&
                formatSpinner.getSelectedItemPosition() == TournamentManager.Format.SWISS.ordinal();
        int seedingChoice = swissSeedingSpinner == null ? 0 : swissSeedingSpinner.getSelectedItemPosition();
        boolean rankMode = seedingChoice == 2;
        if (swissRankEditorButton != null) swissRankEditorButton.setVisibility(swiss && rankMode ? View.VISIBLE : View.GONE);
        if (swissSetupHint != null) {
            if (rankMode) {
                swissSetupHint.setText("Rank engines from strongest to weakest, then tap Edit engine ranking and confirm. DroidFish applies TCEC-style equal-distance group seeding for Round 1; later rounds use score and initial seed.");
            } else if (seedingChoice == 1) {
                swissSetupHint.setText("A fresh random engine order is generated for this independent Swiss. Later rounds use score-and-seed pairing.");
            } else {
                swissSetupHint.setText("Choose Random for an independent Swiss or Rank engine for a continuous tournament such as MCEC.");
            }
        }
    }

    private void updateCupSeedingUi() {
        boolean cup = formatSpinner != null && formatSpinner.getSelectedItemPosition() == TournamentManager.Format.CUP.ordinal();
        int choice = cupSeedingSpinner == null ? 0 : cupSeedingSpinner.getSelectedItemPosition();
        boolean rankMode = choice == 2;
        if (cupRankEditorButton != null) cupRankEditorButton.setVisibility(cup && rankMode ? View.VISIBLE : View.GONE);
        if (cupSetupHint != null) {
            if (rankMode) cupSetupHint.setText("Rank all selected engines from strongest to weakest. The top eight are spread across the bracket; for a partial field, the highest-ranked engines receive first-round byes. Remaining entrants are drawn into the open positions.");
            else if (choice == 1) cupSetupHint.setText("Random creates an independent, saved bracket draw. The draw is never changed when the tournament resumes.");
            else cupSetupHint.setText("Choose Random for an independent Cup or Rank engine for a continuous MCEC-style Cup.");
        }
    }

    private void syncCupRankedEngines() {
        ArrayList<EngineChoice> updated = new ArrayList<>();
        for (EngineChoice ranked : cupRankedEngines) for (EngineChoice selected : selectedEngines)
            if (ranked.id.equals(selected.id)) { updated.add(selected); break; }
        for (EngineChoice selected : selectedEngines) {
            boolean found = false;
            for (EngineChoice ranked : updated) if (ranked.id.equals(selected.id)) { found = true; break; }
            if (!found) updated.add(selected);
        }
        cupRankedEngines.clear(); cupRankedEngines.addAll(updated);
    }

    private void showCupRankEditor() {
        syncCupRankedEngines();
        if (cupRankedEngines.size() < 2) { Toast.makeText(this, "Select at least two engines first", Toast.LENGTH_LONG).show(); return; }
        final ArrayList<EngineChoice> working = new ArrayList<>(cupRankedEngines);
        final ArrayList<String> labels = new ArrayList<>();
        final ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_single_choice, labels);
        final int[] selectedIndex = {0};
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(12), dp(8), dp(12), dp(8));
        content.addView(text("Move engines into strength order. #1 is strongest. The top eight are distributed across the bracket; remaining engines fill the vacant seed positions."), fullWidth());
        ListView list = new ListView(this); list.setChoiceMode(ListView.CHOICE_MODE_SINGLE); list.setAdapter(adapter);
        content.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(340)));
        LinearLayout controls = new LinearLayout(this); controls.setOrientation(LinearLayout.HORIZONTAL);
        Button up = new Button(this); up.setText("Move up"); Button down = new Button(this); down.setText("Move down");
        controls.addView(up, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        controls.addView(down, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1)); content.addView(controls, fullWidth());
        Runnable refresh = () -> { labels.clear(); for (int i=0;i<working.size();i++) labels.add("#"+(i+1)+"  "+working.get(i).name); adapter.notifyDataSetChanged();
            if (!working.isEmpty()) { selectedIndex[0]=Math.max(0,Math.min(selectedIndex[0],working.size()-1)); list.setItemChecked(selectedIndex[0],true); list.setSelection(selectedIndex[0]); } };
        list.setOnItemClickListener((parent, view, position, id) -> selectedIndex[0]=position);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Cup engine ranking").setView(content)
                .setPositiveButton("Use ranking", null).setNegativeButton(android.R.string.cancel, null).create();
        up.setOnClickListener(v -> { int i=selectedIndex[0]; if(i>0){Collections.swap(working,i,i-1);selectedIndex[0]=i-1;refresh.run();} });
        down.setOnClickListener(v -> { int i=selectedIndex[0]; if(i>=0&&i+1<working.size()){Collections.swap(working,i,i+1);selectedIndex[0]=i+1;refresh.run();} });
        refresh.run();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            cupRankedEngines.clear(); cupRankedEngines.addAll(working); cupRankOrderConfirmed=true; saveSetup(); dialog.dismiss();
        }));
        dialog.show();
    }

    private void syncSwissRankedEngines() {
        ArrayList<EngineChoice> updated = new ArrayList<>();
        for (EngineChoice ranked : swissRankedEngines) {
            for (EngineChoice selected : selectedEngines) {
                if (ranked.id.equals(selected.id)) { updated.add(selected); break; }
            }
        }
        for (EngineChoice selected : selectedEngines) {
            boolean present = false;
            for (EngineChoice ranked : updated) if (selected.id.equals(ranked.id)) { present = true; break; }
            if (!present) updated.add(selected);
        }
        swissRankedEngines.clear();
        swissRankedEngines.addAll(updated);
    }

    private void showSwissRankEditor() {
        syncSwissRankedEngines();
        if (swissRankedEngines.size() < 2) {
            Toast.makeText(this, "Select at least two engines first", Toast.LENGTH_LONG).show();
            return;
        }
        final ArrayList<EngineChoice> working = new ArrayList<>(swissRankedEngines);
        final ArrayList<String> labels = new ArrayList<>();
        final ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_single_choice, labels);
        final int[] selectedIndex = {0};
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        content.setPadding(pad, pad, pad, pad);
        TextView instruction = text("Move engines into strength order. Rank #1 is the strongest engine. Confirming this ranking is required for Rank engine mode. It controls only initial TCEC-style group seeding; later rounds follow tournament scores.");
        content.addView(instruction, fullWidth());
        ListView list = new ListView(this);
        list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        list.setAdapter(adapter);
        content.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)));
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        Button up = new Button(this); up.setText("Move up");
        Button down = new Button(this); down.setText("Move down");
        controls.addView(up, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        controls.addView(down, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        content.addView(controls, fullWidth());
        Runnable refresh = () -> {
            labels.clear();
            for (int i = 0; i < working.size(); i++) labels.add("#" + (i + 1) + "  " + working.get(i).name);
            adapter.notifyDataSetChanged();
            if (!working.isEmpty()) {
                selectedIndex[0] = Math.max(0, Math.min(selectedIndex[0], working.size() - 1));
                list.setItemChecked(selectedIndex[0], true);
                list.setSelection(selectedIndex[0]);
            }
        };
        list.setOnItemClickListener((parent, view, position, id) -> selectedIndex[0] = position);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Swiss engine ranking")
                .setView(content)
                .setPositiveButton("Use ranking", null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        up.setOnClickListener(v -> {
            int index = selectedIndex[0];
            if (index > 0) {
                Collections.swap(working, index, index - 1);
                selectedIndex[0] = index - 1;
                refresh.run();
            }
        });
        down.setOnClickListener(v -> {
            int index = selectedIndex[0];
            if (index >= 0 && index + 1 < working.size()) {
                Collections.swap(working, index, index + 1);
                selectedIndex[0] = index + 1;
                refresh.run();
            }
        });
        refresh.run();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            swissRankedEngines.clear();
            swissRankedEngines.addAll(working);
            swissRankOrderConfirmed = true;
            saveSetup();
            dialog.dismiss();
        }));
        dialog.show();
    }

    private void chooseEngineIconFolder() {
        if (android.os.Build.VERSION.SDK_INT < 21) {
            Toast.makeText(this, "Selecting an icon folder requires Android 5.0 or newer", Toast.LENGTH_LONG).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(intent, REQUEST_ENGINE_ICON_FOLDER); }
        catch (android.content.ActivityNotFoundException ex) {
            Toast.makeText(this, "No folder picker is available on this device", Toast.LENGTH_LONG).show();
        }
    }

    private void configureEngineIcons() {
        if (selectedEngines.isEmpty()) {
            Toast.makeText(this, "Select tournament engines first", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[selectedEngines.size()];
        boolean[] checked = new boolean[selectedEngines.size()];
        Set<String> updatedDisabledIds = new HashSet<>(engineIconDisabledIds);
        for (int i = 0; i < selectedEngines.size(); i++) {
            EngineChoice engine = selectedEngines.get(i);
            labels[i] = engine.name + " · " + iconFileNameForEngine(engine.id, engine.name);
            checked[i] = !updatedDisabledIds.contains(normalizeIconKey(engine.id));
        }
        new AlertDialog.Builder(this).setTitle("Engine icons · check to enable")
                .setMultiChoiceItems(labels, checked, (dialog, which, enabled) -> {
                    String id = normalizeIconKey(selectedEngines.get(which).id);
                    if (enabled) updatedDisabledIds.remove(id); else updatedDisabledIds.add(id);
                })
                .setPositiveButton("Apply", (dialog, which) -> { engineIconDisabledIds.clear(); engineIconDisabledIds.addAll(updatedDisabledIds); saveSetup(); engineIconBitmaps.clear(); if (isMirrorShowing()) { updateMirrorRankedNames(); renderMirrorSection(); } })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private String normalizeIconKey(String value) {
        if (value == null) return "";
        String name = new File(value).getName();
        int dot = name.lastIndexOf('.'); if (dot > 0) name = name.substring(0, dot);
        return name.toLowerCase(java.util.Locale.US).replaceAll("[^a-z0-9]", "");
    }

    private String iconFileNameForEngine(String engineId, String displayName) {
        String key = normalizeEngineIconName(displayName);
        if (key.isEmpty()) key = normalizeEngineIconName(engineId);
        return (key.isEmpty() ? "default" : key) + ".png";
    }

    /** Also recognize family icons such as Stockfish.png for "Stockfish 17.1". */
    private String normalizeEngineIconName(String value) {
        if (value == null) return "";
        String name = new File(value).getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        name = name.trim().replaceFirst("(?i)(?:[\\s_-]+v?\\d+(?:[._-]\\d+)*)$", "");
        return normalizeIconKey(name);
    }

    private ArrayList<String> engineIconLookupKeys(String engineId, String displayName) {
        ArrayList<String> keys = new ArrayList<>();
        String exactId = normalizeIconKey(engineId);
        String exactName = normalizeIconKey(displayName);
        String familyId = normalizeEngineIconName(engineId);
        String familyName = normalizeEngineIconName(displayName);
        for (String key : new String[]{exactId, exactName, familyId, familyName})
            if (!key.isEmpty() && !keys.contains(key)) keys.add(key);
        return keys;
    }

    private void scanEngineIconFolder() {
        if (engineIconFolderScanned) return;
        engineIconFolderScanned = true;
        engineIconUris.clear();
        if (engineIconFolderUri == null || android.os.Build.VERSION.SDK_INT < 21) return;
        Cursor cursor = null;
        try {
            String documentId = DocumentsContract.getTreeDocumentId(engineIconFolderUri);
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(engineIconFolderUri, documentId);
            cursor = getContentResolver().query(children,
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null);
            if (cursor == null) return;
            while (cursor.moveToNext()) {
                String name = cursor.getString(1);
                if (name == null || !name.toLowerCase(java.util.Locale.US).endsWith(".png")) continue;
                String id = cursor.getString(0);
                engineIconUris.put(normalizeIconKey(name), DocumentsContract.buildDocumentUriUsingTree(engineIconFolderUri, id));
            }
        } catch (Exception ignored) { }
        finally { if (cursor != null) cursor.close(); }
    }

    private Bitmap decodeMirrorIcon(Uri uri) {
        if (uri == null) return null;
        String key = uri.toString();
        if (engineIconBitmaps.containsKey(key)) return engineIconBitmaps.get(key);
        Bitmap bitmap = null;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in != null) {
                BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
                byte[] bytes;
                try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[4096]; int n;
                    while ((n = in.read(buffer)) > 0 && out.size() < 2 * 1024 * 1024) out.write(buffer, 0, n);
                    bytes = out.toByteArray();
                }
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true; BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
                int max = Math.max(opts.outWidth, opts.outHeight); opts.inSampleSize = 1;
                while (max / opts.inSampleSize > 96) opts.inSampleSize *= 2;
                opts.inJustDecodeBounds = false;
                Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
                if (decoded != null) {
                    float scale = Math.min(64.0f / decoded.getWidth(), 64.0f / decoded.getHeight());
                    bitmap = Bitmap.createScaledBitmap(decoded, Math.max(1, Math.round(decoded.getWidth() * scale)),
                            Math.max(1, Math.round(decoded.getHeight() * scale)), true);
                }
            }
        } catch (Exception ignored) { }
        engineIconBitmaps.put(key, bitmap);
        return bitmap;
    }

    private Bitmap mirrorEngineIcon(String engineId, String displayName) {
        if (engineIconsCheck == null || !engineIconsCheck.isChecked() || engineIconDisabledIds.contains(normalizeIconKey(engineId))) return null;
        scanEngineIconFolder();
        Uri uri = null;
        for (String key : engineIconLookupKeys(engineId, displayName)) {
            uri = engineIconUris.get(key);
            if (uri != null) break;
        }
        if (uri == null) uri = engineIconUris.get("default");
        return decodeMirrorIcon(uri);
    }

    private void addMirrorEngineIcon(LinearLayout row, String engineId, String displayName, int size) {
        Bitmap bitmap = mirrorEngineIcon(engineId, displayName);
        if (bitmap == null) return;
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(new BitmapDrawable(getResources(), bitmap));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(size), dp(size));
        lp.leftMargin = dp(3); lp.rightMargin = dp(4);
        row.addView(icon, lp);
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
        int selectedFormat = formatSpinner.getSelectedItemPosition();
        String selectionTitle = selectedFormat == TournamentManager.Format.SWISS.ordinal()
                ? "Choose engines for Swiss"
                : (selectedFormat == TournamentManager.Format.CUP.ordinal()
                    ? "Choose Cup entrants (default bracket size is 32)"
                    : "Choose engines; selection order sets the gauntlet engine");
        new AlertDialog.Builder(this).setTitle(selectionTitle)
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
                    Set<String> previousIds = new HashSet<>();
                    for (EngineChoice e : selectedEngines) previousIds.add(e.id);
                    Set<String> updatedIds = new HashSet<>();
                    for (EngineChoice e : dialogSelection) updatedIds.add(e.id);
                    if (!previousIds.equals(updatedIds)) { swissRankOrderConfirmed = false; cupRankOrderConfirmed = false; }
                    selectedEngines.clear(); selectedEngines.addAll(dialogSelection);
                    syncSwissRankedEngines(); syncCupRankedEngines();
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
        e.putString("setupGames", forcedPairFormat ? gamesPerPairBeforeForcedPairFormat : gamesInput.getText().toString());
        e.putString("setupSwissRounds", swissRoundsInput.getText().toString());
        e.putInt("setupSwissSeedingMode", swissSeedingSpinner == null ? 0 : swissSeedingSpinner.getSelectedItemPosition());
        StringBuilder swissRankIds = new StringBuilder();
        for (EngineChoice c : swissRankedEngines) { if (swissRankIds.length() > 0) swissRankIds.append("\n"); swissRankIds.append(c.id); }
        e.putString("setupSwissRankIds", swissRankIds.toString());
        e.putBoolean("setupSwissRankOrderConfirmed", swissRankOrderConfirmed);
        e.putInt("setupCupSeedingMode", cupSeedingSpinner == null ? 0 : cupSeedingSpinner.getSelectedItemPosition());
        StringBuilder cupRankIds = new StringBuilder();
        for (EngineChoice c : cupRankedEngines) { if (cupRankIds.length() > 0) cupRankIds.append("\n"); cupRankIds.append(c.id); }
        e.putString("setupCupRankIds", cupRankIds.toString());
        e.putBoolean("setupCupRankOrderConfirmed", cupRankOrderConfirmed);
        e.putInt("setupFormat", formatSpinner.getSelectedItemPosition());
        e.putInt("setupTimeMode", timeModeSpinner.getSelectedItemPosition());
        e.putString("setupBase", baseInput.getText().toString());
        e.putString("setupIncrement", incrementInput.getText().toString());
        e.putString("setupLimit", limitInput.getText().toString());
        e.putString("setupPlies", openingPliesInput.getText().toString());
        e.putString("setupOpeningStart", openingStartInput.getText().toString());
        e.putBoolean("setupRepeat", forcedPairFormat ? repeatOpeningBeforeForcedPairFormat : repeatOpeningCheck.isChecked());
        e.putBoolean("setupArrows", showTournamentArrowsCheck.isChecked());
        e.putBoolean("setupEngineIconsEnabled", engineIconsCheck != null && engineIconsCheck.isChecked());
        e.putString("setupEngineIconFolder", engineIconFolderUri == null ? "" : engineIconFolderUri.toString());
        StringBuilder disabledIcons = new StringBuilder();
        for (String id : engineIconDisabledIds) { if (disabledIcons.length() > 0) disabledIcons.append("\n"); disabledIcons.append(id); }
        e.putString("setupEngineIconDisabledIds", disabledIcons.toString());
        e.putBoolean("tournamentDisableScreenTimeout", disableScreenTimeoutCheck != null && disableScreenTimeoutCheck.isChecked());
        e.putString("setupAutoWinEval", autoWinEvalInput.getText().toString());
        e.putBoolean("setupFastWin", fastWinRuleCheck.isChecked());
        e.putBoolean("setupDrawRules", drawRulesCheck != null && drawRulesCheck.isChecked());
        if (drawMoveRuleInput != null) e.putString("setupDrawMove", drawMoveRuleInput.getText().toString());
        if (ruleStartMoveInput != null) e.putString("setupRuleStartMove", ruleStartMoveInput.getText().toString());
        if (drawRepetitionInput != null) e.putString("setupDrawRep", drawRepetitionInput.getText().toString());
        if (drawMaximumInput != null) e.putString("setupDrawMax", drawMaximumInput.getText().toString());
        // Keep small setup books in preferences, but do not place multi-megabyte PGNs
        // inside the SharedPreferences XML. Saved tournaments store their own PGN copy.
        if (selectedOpeningPgn != null && selectedOpeningPgn.length() <= 256 * 1024)
            e.putString("setupOpeningPgn", selectedOpeningPgn);
        else
            e.remove("setupOpeningPgn");
        e.putString("setupOpeningName", selectedOpeningFileName);
        e.putString("setupOpeningBinPath", selectedOpeningBinPath);
        if (evalDrawScoreInput != null) e.putString("setupEvalDrawScore", evalDrawScoreInput.getText().toString());
        if (evalDrawConditionInput != null) e.putString("setupEvalDrawCondition", evalDrawConditionInput.getText().toString());
        StringBuilder ids = new StringBuilder(); for (EngineChoice c : selectedEngines) { if (ids.length()>0) ids.append("\n"); ids.append(c.id); }
        e.putString("setupEngineIds", ids.toString()); e.apply();
    }
    private void restoreSetup() {
        restoringSetup = true;
        android.content.SharedPreferences p = getPreferences(MODE_PRIVATE);
        titleInput.setText(p.getString("setupTitle", titleInput.getText().toString()));
        gamesInput.setText(p.getString("setupGames", gamesInput.getText().toString()));
        swissRoundsInput.setText(p.getString("setupSwissRounds", ""));
        if (swissSeedingSpinner != null) swissSeedingSpinner.setSelection(Math.max(0, Math.min(2, p.getInt("setupSwissSeedingMode", 0))));
        swissRankOrderConfirmed = p.getBoolean("setupSwissRankOrderConfirmed", false);
        if (cupSeedingSpinner != null) cupSeedingSpinner.setSelection(Math.max(0, Math.min(2, p.getInt("setupCupSeedingMode", 0))));
        cupRankOrderConfirmed = p.getBoolean("setupCupRankOrderConfirmed", false);
        repeatOpeningCheck.setChecked(p.getBoolean("setupRepeat", false));
        int savedFormat = p.getInt("setupFormat", 0);
        formatSpinner.setSelection(savedFormat);
        applyTournamentFormatUi(savedFormat);
        timeModeSpinner.setSelection(p.getInt("setupTimeMode", 0));
        baseInput.setText(p.getString("setupBase", baseInput.getText().toString())); incrementInput.setText(p.getString("setupIncrement", incrementInput.getText().toString()));
        limitInput.setText(p.getString("setupLimit", limitInput.getText().toString())); openingPliesInput.setText(p.getString("setupPlies", openingPliesInput.getText().toString()));
        openingStartInput.setText(p.getString("setupOpeningStart", openingStartInput.getText().toString()));
        showTournamentArrowsCheck.setChecked(p.getBoolean("setupArrows", true));
        if (engineIconsCheck != null) engineIconsCheck.setChecked(p.getBoolean("setupEngineIconsEnabled", false));
        String iconFolder = p.getString("setupEngineIconFolder", "");
        engineIconFolderUri = iconFolder.isEmpty() ? null : Uri.parse(iconFolder);
        if (engineIconFolderButton != null) engineIconFolderButton.setText(engineIconFolderUri == null ?
                "Choose engine icon folder (PNG files)" : "Icon folder selected · change folder");
        engineIconDisabledIds.clear();
        String disabledIconIds = p.getString("setupEngineIconDisabledIds", "");
        if (!disabledIconIds.isEmpty()) for (String id : disabledIconIds.split("\n")) engineIconDisabledIds.add(id);
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
        selectedEngines.clear(); String ids=p.getString("setupEngineIds", ""); if (!ids.isEmpty()) for(String id:ids.split("\n")) for(EngineChoice c:availableEngines) if(c.id.equals(id)){selectedEngines.add(c); break;}
        swissRankedEngines.clear();
        String savedSwissRankIds = p.getString("setupSwissRankIds", "");
        if (!savedSwissRankIds.isEmpty()) for (String id : savedSwissRankIds.split("\n"))
            for (EngineChoice c : selectedEngines) if (c.id.equals(id)) { swissRankedEngines.add(c); break; }
        syncSwissRankedEngines();
        cupRankedEngines.clear();
        String savedCupRankIds = p.getString("setupCupRankIds", "");
        if (!savedCupRankIds.isEmpty()) for (String id : savedCupRankIds.split("\n"))
            for (EngineChoice c : selectedEngines) if (c.id.equals(id)) { cupRankedEngines.add(c); break; }
        syncCupRankedEngines();
        updateSelectedText();
        updateSwissSeedingUi(); updateCupSeedingUi();
        restoringSetup = false;
    }
    private String mirrorHeaderLabel() {
        return "Game " + (currentGameNumber == 0 ? "—" : currentGameNumber) + "/" +
                (currentGameTotal == 0 ? "—" : currentGameTotal) +
                "  TC " + mirrorTcLabel + "  T " + formatElapsedSeconds(currentTournamentElapsedMs());
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

    private long currentTournamentElapsedMs() {
        if (tournamentElapsedStartNs == 0L) return tournamentElapsedAccumulatedMs;
        return tournamentElapsedAccumulatedMs + Math.max(0L, (System.nanoTime() - tournamentElapsedStartNs) / 1_000_000L);
    }

    private void freezeTournamentElapsedClock() {
        if (tournamentElapsedStartNs != 0L) {
            tournamentElapsedAccumulatedMs = currentTournamentElapsedMs();
            tournamentElapsedStartNs = 0L;
        }
        persistTournamentElapsedTime();
    }

    private void resumeTournamentElapsedClock() {
        if (tournamentElapsedStartNs == 0L) tournamentElapsedStartNs = System.nanoTime();
    }

    private void persistTournamentElapsedTime() {
        TournamentManager t = activeTournamentManager;
        if (t == null) return;
        t.setTournamentElapsedMs(currentTournamentElapsedMs());
        try { t.save(); } catch (Exception ignored) { }
        lastTournamentElapsedSaveNs = System.nanoTime();
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
            heatPhaseStartNs = 0L;
            // A running tournament begins a fresh play interval immediately; a paused
            // tournament starts it from togglePauseResume() when the user presses Play.
            heatLastResumeNs = runner != null && !runner.isPaused() && heatCycleEnabled
                    ? System.nanoTime() : 0L;
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
            resumeTournamentElapsedClock();
            if (lastTournamentElapsedSaveNs == 0L || now - lastTournamentElapsedSaveNs >= 5_000_000_000L) persistTournamentElapsedTime();
            if (eventLastResumeNs == 0L) resumeEventElapsedClock();
            if (heatCycleEnabled && !heatCooling && heatLastResumeNs != 0L) {
                heatPlayAccumulatedMs += Math.max(0L, (now - heatLastResumeNs) / 1_000_000L);
                heatLastResumeNs = now;
                if (heatPlayAccumulatedMs >= heatPlayDurationMs) {
                    r.pause();
                    heatCooling = true;
                    heatPhaseStartNs = now;
                    heatLastResumeNs = 0L;
                    freezeTournamentElapsedClock();
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
                resumeTournamentElapsedClock();
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
            freezeTournamentElapsedClock();
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
            TournamentManager.Format format = TournamentManager.Format.values()[formatSpinner.getSelectedItemPosition()];
            if (format == TournamentManager.Format.CUP && selectedEngines.size() > CupTournamentManager.MAX_ENGINES)
                throw new IllegalArgumentException("Cup supports up to " + CupTournamentManager.MAX_ENGINES + " selected engines");
            int games = (format == TournamentManager.Format.SWISS || format == TournamentManager.Format.CUP)
                    ? 2 : positiveInt(gamesInput, "games per pairing", 1);
            int swissRounds = 0;
            if (format == TournamentManager.Format.SWISS) {
                int maximumRounds = TournamentManager.maximumSwissRounds(selectedEngines.size());
                String requestedRounds = swissRoundsInput.getText().toString().trim();
                swissRounds = requestedRounds.isEmpty()
                        ? TournamentManager.recommendedSwissRounds(selectedEngines.size())
                        : Integer.parseInt(requestedRounds);
                if (swissRounds < 1 || swissRounds > maximumRounds)
                    throw new IllegalArgumentException("Swiss rounds must be between 1 and " + maximumRounds +
                            " for " + selectedEngines.size() + " selected engines");
            }
            int selectedSwissSeeding = swissSeedingSpinner.getSelectedItemPosition();
            if (format == TournamentManager.Format.SWISS && selectedSwissSeeding == 0)
                throw new IllegalArgumentException("Choose Random or Rank engine for Swiss seeding first");
            TournamentManager.SwissSeedingMode swissSeedingMode = format == TournamentManager.Format.SWISS
                    ? TournamentManager.SwissSeedingMode.values()[selectedSwissSeeding - 1]
                    : TournamentManager.SwissSeedingMode.RANDOM;
            ArrayList<String> swissRankOrderIds = new ArrayList<>();
            if (format == TournamentManager.Format.SWISS && swissSeedingMode == TournamentManager.SwissSeedingMode.RANK_ENGINE) {
                if (!swissRankOrderConfirmed) {
                    Toast.makeText(this, "Review and confirm the engine ranking before starting Rank engine Swiss", Toast.LENGTH_LONG).show();
                    showSwissRankEditor();
                    return;
                }
                syncSwissRankedEngines();
                if (swissRankedEngines.size() != selectedEngines.size())
                    throw new IllegalArgumentException("Rank every selected engine before starting Swiss");
                for (EngineChoice engine : swissRankedEngines) swissRankOrderIds.add(engine.id);
            } else {
                for (EngineChoice engine : selectedEngines) swissRankOrderIds.add(engine.id);
            }
            int selectedCupSeeding = cupSeedingSpinner.getSelectedItemPosition();
            if (format == TournamentManager.Format.CUP && selectedCupSeeding == 0)
                throw new IllegalArgumentException("Choose Random or Rank engine for Cup seeding first");
            TournamentManager.CupSeedingMode cupSeedingMode = format == TournamentManager.Format.CUP
                    ? TournamentManager.CupSeedingMode.values()[selectedCupSeeding - 1]
                    : TournamentManager.CupSeedingMode.RANDOM;
            ArrayList<String> cupRankOrderIds = new ArrayList<>();
            if (format == TournamentManager.Format.CUP && cupSeedingMode == TournamentManager.CupSeedingMode.RANK_ENGINE) {
                if (!cupRankOrderConfirmed) {
                    Toast.makeText(this, "Review and confirm the engine ranking before starting Rank engine Cup", Toast.LENGTH_LONG).show();
                    showCupRankEditor();
                    return;
                }
                syncCupRankedEngines();
                if (cupRankedEngines.size() != selectedEngines.size())
                    throw new IllegalArgumentException("Rank every selected engine before starting Cup");
                for (EngineChoice engine : cupRankedEngines) cupRankOrderIds.add(engine.id);
            } else {
                for (EngineChoice engine : selectedEngines) cupRankOrderIds.add(engine.id);
            }
            int timeModeIndex = timeModeSpinner.getSelectedItemPosition();
            boolean movePeriodMode = timeModeIndex == 4;
            double baseMinutes = (timeModeIndex == 0 || movePeriodMode)
                    ? positiveDouble(baseInput, movePeriodMode ? "minutes per move period" : "base time in minutes", 0.01)
                    : 0.5;
            long baseMillis = Math.round(baseMinutes * 60_000.0);
            int increment = timeModeIndex == 0 ? (int)Math.round(nonNegativeDouble(incrementInput, "increment") * 1000.0) : 0;
            int limitValue = timeModeIndex == 1 ? (int)Math.min(Integer.MAX_VALUE / 1000L, Math.round(positiveDouble(limitInput, "seconds per move", 1) * 1000.0)) :
                    (timeModeIndex == 2 ? positiveInt(limitInput, "depth per move", 1) :
                    (timeModeIndex == 3 ? positiveInt(limitInput, "nodes per move", 1) :
                    (movePeriodMode ? positiveInt(limitInput, "moves per time period", 1) : 30)));
            int openingPlies = nonNegativeInt(openingPliesInput, "opening plies");
            int openingStart = positiveInt(openingStartInput, "starting PGN opening number", 1);
            // A selected PGN is applied in full by default (0); positive values cap half-moves.
            if (openingPlies > 0 && selectedOpeningPgn.trim().isEmpty() && selectedOpeningBinPath.trim().isEmpty())
                throw new IllegalArgumentException("Select a PGN opening file before setting a custom opening-ply limit");
            if (baseMillis > Integer.MAX_VALUE) throw new IllegalArgumentException("Base time is too large");
            if (format == TournamentManager.Format.MATCH && selectedEngines.size() != 2)
                throw new IllegalArgumentException("Match format requires exactly two engines");
            ArrayList<TournamentManager.Engine> engines = new ArrayList<>();
            for (EngineChoice e : selectedEngines) engines.add(new TournamentManager.Engine(e.id, e.name));
            String tournamentName = titleInput.getText().toString().trim();
            if (tournamentName.isEmpty()) tournamentName = "My Tournament";
            saveSetup();
            TournamentManager tournament = new TournamentManager(this, tournamentName, format,
                    engines, games, baseMillis, increment, selectedOpeningPgn, openingPlies,
                    format == TournamentManager.Format.SWISS || format == TournamentManager.Format.CUP || repeatOpeningCheck.isChecked(),
                    TournamentManager.TimeMode.values()[timeModeIndex], limitValue, openingStart, swissRounds,
                    swissSeedingMode, swissRankOrderIds, cupSeedingMode, cupRankOrderIds);
            tournament.setOpeningBinPath(selectedOpeningBinPath);
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
        if (requestCode == REQUEST_ENGINE_ICON_FOLDER && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri tree = data.getData();
            try {
                int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getContentResolver().takePersistableUriPermission(tree, flags);
            } catch (SecurityException ignored) { }
            engineIconFolderUri = tree;
            engineIconUris.clear(); engineIconBitmaps.clear(); engineIconFolderScanned = false;
            if (engineIconFolderButton != null) engineIconFolderButton.setText("Icon folder selected · change folder");
            saveSetup();
            if (isMirrorShowing()) { updateMirrorRankedNames(); renderMirrorSection(); }
            Toast.makeText(this, "Engine icon folder selected. Use exact PNG names such as Stockfish.png; default.png is the fallback.", Toast.LENGTH_LONG).show();
            return;
        }
        if (requestCode == REQUEST_TOURNAMENT_JSON && resultCode == RESULT_OK && data != null && data.getData() != null) {
            importTournamentJson(data.getData());
            return;
        }
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

    private void chooseTournamentJson() {
        if (runner != null) {
            Toast.makeText(this, "Pause and stop the current tournament before importing another event", Toast.LENGTH_LONG).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/json", "application/octet-stream", "text/plain"});
        try { startActivityForResult(intent, REQUEST_TOURNAMENT_JSON); }
        catch (android.content.ActivityNotFoundException error) {
            Toast.makeText(this, "No file picker is available to import a tournament JSON", Toast.LENGTH_LONG).show();
        }
    }

    private void importTournamentJson(Uri uri) {
        final String sourceName = queryDisplayName(uri);
        new Thread(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri);
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                if (in == null) throw new IOException("Could not open selected JSON file");
                byte[] buffer = new byte[32768];
                int count;
                long total = 0;
                while ((count = in.read(buffer)) != -1) {
                    total += count;
                    if (total > 32L * 1024L * 1024L) throw new IOException("Tournament JSON is larger than 32 MB");
                    out.write(buffer, 0, count);
                }
                String json = new String(out.toByteArray(), Charset.forName("UTF-8"));
                TournamentManager imported = TournamentManager.importJson(this, json);
                runOnUiThread(() -> {
                    String mirrorWarning = imported.externalSaveError().isEmpty() ? "" : "\n" + imported.externalSaveError();
                    Toast.makeText(this, "Imported " + sourceName + " · " + imported.pairings().size() +
                            " scheduled games" + mirrorWarning, Toast.LENGTH_LONG).show();
                    showSavedTournamentActions(imported.savedFileName(), imported);
                });
            } catch (Exception error) {
                runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("Tournament import failed")
                        .setMessage(error.getMessage() == null ? error.toString() : error.getMessage())
                        .setPositiveButton("CLOSE", null).show());
            }
        }, "tournament-json-import").start();
    }

    private String queryDisplayName(Uri uri) {
        android.database.Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (RuntimeException ignored) { }
        finally { if (cursor != null) cursor.close(); }
        return "Selected file";
    }

    private static final class SavedTournamentEntry {
        final String fileName;
        final String label;
        final boolean readable;
        SavedTournamentEntry(String fileName, String label, boolean readable) {
            this.fileName = fileName;
            this.label = label;
            this.readable = readable;
        }
    }

    private void chooseSavedTournament() {
        if (runner != null) { Toast.makeText(this, "A tournament is already running", Toast.LENGTH_SHORT).show(); return; }
        if (tournamentLoadInProgress) { Toast.makeText(this, "Tournament data is still loading", Toast.LENGTH_SHORT).show(); return; }
        final List<String> files = TournamentManager.savedTournamentFiles(this);
        if (files.isEmpty()) { Toast.makeText(this, "No saved tournaments", Toast.LENGTH_SHORT).show(); return; }

        // A tournament JSON can contain several megabytes of opening PGN and
        // thousands of pairings. Never parse saved files on the UI thread, and
        // retain only menu labels here rather than every full tournament in RAM.
        tournamentLoadInProgress = true;
        final ProgressDialog progressDialog = new ProgressDialog(this);
        progressDialog.setMessage("Reading saved tournaments…");
        progressDialog.setIndeterminate(true);
        progressDialog.setCancelable(false);
        progressDialog.show();
        new Thread(() -> {
            final ArrayList<SavedTournamentEntry> entries = new ArrayList<>();
            for (String file : files) {
                try {
                    TournamentManager t = TournamentManager.restore(this, file);
                    int completed = 0;
                    for (TournamentManager.Pairing pairing : t.pairings()) if (pairing.isComplete()) completed++;
                    String label = t.title() + " · " + completed + "/" + t.pairings().size() +
                            " games · " + (t.isComplete() ? "Completed" : "Unfinished");
                    entries.add(new SavedTournamentEntry(file, label, true));
                } catch (Exception e) {
                    entries.add(new SavedTournamentEntry(file, file + " · Cannot read: " + e.getMessage(), false));
                }
            }
            runOnUiThread(() -> {
                progressDialog.dismiss();
                tournamentLoadInProgress = false;
                if (isFinishing()) return;
                String[] labels = new String[entries.size()];
                for (int i = 0; i < entries.size(); i++) labels[i] = entries.get(i).label;
                new AlertDialog.Builder(this).setTitle("Saved tournaments")
                    .setItems(labels, (dialog, which) -> {
                        SavedTournamentEntry entry = entries.get(which);
                        if (!entry.readable) {
                            Toast.makeText(this, "This saved tournament could not be read", Toast.LENGTH_LONG).show();
                        } else {
                            showSavedTournamentActions(entry.fileName);
                        }
                    })
                    .setNegativeButton("Cancel", null).show();
            });
        }, "tournament-saved-list-loader").start();
    }

    private void showSavedTournamentActions(String fileName) {
        if (tournamentLoadInProgress) return;
        tournamentLoadInProgress = true;
        final ProgressDialog progressDialog = new ProgressDialog(this);
        progressDialog.setMessage("Loading selected tournament…");
        progressDialog.setIndeterminate(true);
        progressDialog.setCancelable(false);
        progressDialog.show();
        new Thread(() -> {
            TournamentManager saved = null;
            Exception failure = null;
            try { saved = TournamentManager.restore(this, fileName); }
            catch (Exception error) { failure = error; }
            final TournamentManager loaded = saved;
            final Exception loadError = failure;
            runOnUiThread(() -> {
                progressDialog.dismiss();
                tournamentLoadInProgress = false;
                if (isFinishing()) return;
                if (loadError != null) {
                    Toast.makeText(this, "Could not open saved tournament: " + loadError.getMessage(), Toast.LENGTH_LONG).show();
                } else {
                    showSavedTournamentActions(fileName, loaded);
                }
            });
        }, "tournament-selected-loader").start();
    }

    private void showSavedTournamentActions(String fileName, TournamentManager saved) {
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
        swissRankedEngines.clear();
        for (String id : tournament.swissStrengthOrder())
            for (EngineChoice engine : selectedEngines) if (engine.id.equals(id)) { swissRankedEngines.add(engine); break; }
        swissRankOrderConfirmed = tournament.format() == TournamentManager.Format.SWISS &&
                tournament.swissSeedingMode() == TournamentManager.SwissSeedingMode.RANK_ENGINE;
        syncSwissRankedEngines();
        updateSelectedText();
        titleInput.setText(tournament.title());
        if (tournament.format() == TournamentManager.Format.SWISS) {
            swissRoundsInput.setText(String.valueOf(tournament.swissRoundsTotal()));
            swissSeedingSpinner.setSelection(tournament.swissSeedingMode().ordinal() + 1);
        }
        cupRankedEngines.clear();
        for (String id : tournament.cupStrengthOrder()) for (EngineChoice c : selectedEngines) if (c.id.equals(id)) { cupRankedEngines.add(c); break; }
        syncCupRankedEngines();
        if (tournament.format() == TournamentManager.Format.CUP) cupSeedingSpinner.setSelection(tournament.cupSeedingMode().ordinal() + 1);
        cupRankOrderConfirmed = tournament.format() == TournamentManager.Format.CUP && tournament.cupSeedingMode() == TournamentManager.CupSeedingMode.RANK_ENGINE;
        formatSpinner.setSelection(tournament.format().ordinal());
        applyTournamentFormatUi(tournament.format().ordinal());
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
        // The authoritative opening book is already stored in this tournament's JSON.
        // Do not duplicate a potentially multi-megabyte PGN into SharedPreferences.
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

    /**
     * Tournament engines must be launched with the same process environment that
     * DroidFish uses on its main board. Keep these preference keys/defaults aligned
     * with DroidFish.readPrefs() so engines which rely on their working directory,
     * hash configuration, or tablebase settings behave consistently in both places.
     */
    private EngineOptions createTournamentEngineOptions() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        EngineOptions options = new EngineOptions();
        options.hashMB = readIntPreference(prefs, "hashMB", 16);
        File externalDir = Environment.getExternalStorageDirectory();
        String separator = File.separator;
        options.unSafeHash = new File(externalDir, ENGINE_DIR + separator + ".unsafehash").exists();
        options.hints = prefs.getBoolean("tbHints", false);
        options.hintsEdit = prefs.getBoolean("tbHintsEdit", false);
        options.rootProbe = prefs.getBoolean("tbRootProbe", true);
        options.engineProbe = prefs.getBoolean("tbEngineProbe", true);

        String gtbPath = prefs.getString("gtbPath", "").trim();
        if (gtbPath.isEmpty())
            gtbPath = new File(externalDir, "DroidFish/gtb").getAbsolutePath();
        options.gtbPath = gtbPath;
        options.gtbPathNet = prefs.getString("gtbPathNet", "").trim();

        String rtbPath = prefs.getString("rtbPath", "").trim();
        if (rtbPath.isEmpty())
            rtbPath = new File(externalDir, "DroidFish/rtb").getAbsolutePath();
        options.rtbPath = rtbPath;
        options.rtbPathNet = prefs.getString("rtbPathNet", "").trim();

        File workDir = new File(externalDir, "DroidFish/uci/logs");
        if (!workDir.exists()) workDir.mkdirs();
        options.workDir = workDir.getAbsolutePath();
        // Network engines read their host/port from their own .NETE descriptor.
        // No currently selected main-board engine should leak its identity into a
        // tournament containing different engine files.
        options.networkID = "";
        return options;
    }

    private int readIntPreference(SharedPreferences prefs, String key, int defaultValue) {
        try {
            return Integer.parseInt(prefs.getString(key, Integer.toString(defaultValue)).trim());
        } catch (RuntimeException ignored) {
            return defaultValue;
        }
    }

    private void launchTournament(final TournamentManager tournament) {
        if (tournamentLoadInProgress) {
            Toast.makeText(this, "Tournament preparation is already running", Toast.LENGTH_SHORT).show();
            return;
        }
        tournamentLoadInProgress = true;
        if (startButton != null) startButton.setEnabled(false);
        if (resumeButton != null) resumeButton.setEnabled(false);
        if (stopButton != null) stopButton.setEnabled(false);
        Toast.makeText(this, "Preparing tournament schedule in background…", Toast.LENGTH_SHORT).show();

        int anomalyRuleStart = 1, anomalyMaximumMoves = 160;
        try { anomalyRuleStart = Integer.parseInt(ruleStartMoveInput.getText().toString().trim()); } catch (RuntimeException ignored) { }
        try { anomalyMaximumMoves = Integer.parseInt(drawMaximumInput.getText().toString().trim()); } catch (RuntimeException ignored) { }
        final int configuredRuleStart = anomalyRuleStart;
        final int configuredMaximumMoves = anomalyMaximumMoves;
        final boolean anomalyRulesEnabled = (fastWinRuleCheck != null && fastWinRuleCheck.isChecked()) ||
                (drawRulesCheck != null && drawRulesCheck.isChecked());
        new Thread(() -> {
            Exception failure = null;
            try {
                tournament.prepareOpeningAssignments();
                // New tournaments with an unassigned BIN book are persisted by
                // setAnomalyRuleSettingsIfMissing(); PGN/no-book preparation saves
                // when it initializes assignments. Imported events are already saved.
                tournament.setAnomalyRuleSettingsIfMissing(anomalyRulesEnabled, configuredRuleStart, configuredMaximumMoves);
            } catch (Exception error) {
                failure = error;
            }
            final Exception preparationError = failure;
            runOnUiThread(() -> {
                tournamentLoadInProgress = false;
                if (isFinishing()) return;
                if (preparationError != null) {
                    if (startButton != null) startButton.setEnabled(true);
                    if (resumeButton != null) resumeButton.setEnabled(true);
                    if (stopButton != null) stopButton.setEnabled(false);
                    Toast.makeText(this, "Could not prepare tournament: " + preparationError.getMessage(), Toast.LENGTH_LONG).show();
                    return;
                }
                if (!tournament.externalSaveError().isEmpty())
                    Toast.makeText(this, tournament.externalSaveError(), Toast.LENGTH_LONG).show();
                launchPreparedTournament(tournament);
            });
        }, "tournament-opening-preparation").start();
    }

    private void showCupStageCheckpointDialog(TournamentManager tournament) {
        if (tournament == null || !tournament.cupCheckpointPending()) return;
        String next = tournament.cupNextStageLabel();
        new AlertDialog.Builder(this)
                .setTitle(tournament.cupCurrentStageLabel() + " completed")
                .setMessage("The winners are saved and the bracket is ready for " + next + ". Would you like to update any advancing engines before the next stage? Earlier games and PGNs will keep the engine versions used when they were played.")
                .setNeutralButton("UPDATE ENGINES", (dialog, which) -> showCupEngineUpdateList(tournament, new HashSet<>()))
                .setPositiveButton("CONTINUE", (dialog, which) -> continueCupAfterCheckpoint(tournament))
                .setNegativeButton("LATER", null)
                .show();
    }

    private void showCupEngineUpdateList(TournamentManager tournament, Set<String> updatedIds) {
        if (tournament == null || !tournament.cupCheckpointPending()) return;
        List<String> advancing = tournament.cupAdvancingEngineIds();
        ArrayList<String> pendingIds = new ArrayList<>();
        ArrayList<String> labels = new ArrayList<>();
        List<TournamentManager.Engine> engines = tournament.engines();
        for (String id : advancing) {
            if (updatedIds.contains(id)) continue;
            for (TournamentManager.Engine engine : engines) if (engine.id.equals(id)) {
                pendingIds.add(id);
                labels.add(engine.displayName + " · " + engine.executableId);
                break;
            }
        }
        new AlertDialog.Builder(this).setTitle("Update engines for " + tournament.cupNextStageLabel())
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    if (which >= 0 && which < pendingIds.size()) chooseCupReplacementExecutable(tournament, pendingIds.get(which), updatedIds);
                })
                .setPositiveButton("DONE", (dialog, which) -> continueCupAfterCheckpoint(tournament))
                .setNegativeButton("BACK", (dialog, which) -> showCupStageCheckpointDialog(tournament))
                .show();
    }

    private void chooseCupReplacementExecutable(TournamentManager tournament, String participantId, Set<String> updatedIds) {
        loadEngineChoices();
        if (availableEngines.isEmpty()) {
            Toast.makeText(this, "No installed engine executables were found", Toast.LENGTH_LONG).show();
            showCupEngineUpdateList(tournament, updatedIds);
            return;
        }
        TournamentManager.Engine participant = null;
        for (TournamentManager.Engine engine : tournament.engines()) if (engine.id.equals(participantId)) { participant = engine; break; }
        if (participant == null) { showCupEngineUpdateList(tournament, updatedIds); return; }
        final TournamentManager.Engine target = participant;
        ArrayList<EngineChoice> choices = new ArrayList<>();
        ArrayList<String> labels = new ArrayList<>();
        for (EngineChoice choice : availableEngines) {
            if (choice.id.equals(target.executableId)) continue;
            boolean used = false;
            for (TournamentManager.Engine engine : tournament.engines())
                if (!engine.id.equals(target.id) && engine.executableId.equals(choice.id)) { used = true; break; }
            if (!used) { choices.add(choice); labels.add(choice.name + "\n" + choice.id); }
        }
        if (choices.isEmpty()) {
            Toast.makeText(this, "No unused replacement executable is available for " + target.displayName, Toast.LENGTH_LONG).show();
            showCupEngineUpdateList(tournament, updatedIds);
            return;
        }
        new AlertDialog.Builder(this).setTitle("Replace " + target.displayName + " for next stage")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    if (which < 0 || which >= choices.size()) { showCupEngineUpdateList(tournament, updatedIds); return; }
                    EngineChoice replacement = choices.get(which);
                    new AlertDialog.Builder(this).setTitle("Confirm Cup engine update")
                            .setMessage("Use " + replacement.name + " for " + target.displayName + " from " + tournament.cupNextStageLabel() + " onward? Previous games and PGNs remain unchanged.")
                            .setNegativeButton("CANCEL", (d, w) -> showCupEngineUpdateList(tournament, updatedIds))
                            .setPositiveButton("UPDATE", (d, w) -> {
                                try {
                                    tournament.updateCupEngineExecutable(target.id, replacement.id, replacement.name);
                                    updatedIds.add(target.id);
                                    Toast.makeText(this, target.displayName + " updated to " + replacement.name, Toast.LENGTH_SHORT).show();
                                    showCupEngineUpdateList(tournament, updatedIds);
                                } catch (Exception error) {
                                    Toast.makeText(this, "Could not update Cup engine: " + error.getMessage(), Toast.LENGTH_LONG).show();
                                    showCupEngineUpdateList(tournament, updatedIds);
                                }
                            }).show();
                })
                .setNegativeButton("BACK", (dialog, which) -> showCupEngineUpdateList(tournament, updatedIds))
                .show();
    }

    private void continueCupAfterCheckpoint(TournamentManager tournament) {
        if (tournament == null || !tournament.cupCheckpointPending()) return;
        try {
            tournament.advanceCupAfterCheckpoint();
            activeTournamentManager = tournament;
            invalidateCachedSchedule(tournament);
            launchTournament(tournament);
        } catch (Exception error) {
            Toast.makeText(this, "Could not start the next Cup stage: " + error.getMessage(), Toast.LENGTH_LONG).show();
            showCupStageCheckpointDialog(tournament);
        }
    }

    private void launchPreparedTournament(final TournamentManager tournament) {
        stopMirrorAutoPlay();
        mirrorReplayMode = false;
        mirrorReplayLiveSnapshot = false;
        mirrorReplayPairingIndex = -1;
        mirrorReplayWhiteEngineId = mirrorReplayBlackEngineId = "";
        mirrorReplayPositions.clear();
        mirrorLiveHistoryInitialized = false;
        mirrorReplayMoves.clear();
        mirrorReplayComments.clear();
        activeTournamentManager = tournament;
        refreshMirrorRanks();
        tournamentElapsedAccumulatedMs = tournament.tournamentElapsedMs();
        tournamentElapsedStartNs = System.nanoTime();
        lastTournamentElapsedSaveNs = tournamentElapsedStartNs;
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
                    mirrorTcLabel = mirrorTimeControlLabel(tournament);
                    if (mirrorHeaderText != null) mirrorHeaderText.setText(mirrorHeaderLabel());
                    statusText.setText("Game " + currentGameNumber + "/" + currentGameTotal + " " + tournament.title());
                    if (openingStartInput != null && tournament.repeatOpening()) {
                        // Paired color-reversal games use one PGN opening per pair:
                        // games 1-2 use start, games 3-4 use start+1, etc.
                        int openingNumber = tournament.openingStartIndex() + Math.max(0, pairing.index / 2);
                        openingStartInput.setText(String.valueOf(openingNumber));
                    }
                    stopMirrorAnalysis();
                    mirrorReplayPgn = "";
                    currentPgnMoves = "";
                    mirrorPositions.clear();
                    mirrorLiveHistoryInitialized = false;
                    if (!mirrorReplayMode) mirrorPly = -1;
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
                    currentWhiteEngineId = pairing.whiteEngineId;
                    currentBlackEngineId = pairing.blackEngineId;
                    updateTurnNameLabels();
                    if (mirrorEventTitleText != null) mirrorEventTitleText.setText(tournament.title());
                    refreshMirrorRanks();
                    if (!mirrorReplayMode && mirrorSelectedTab == 0 && isMirrorShowing()) renderMirrorSection();
                    else if (mirrorSelectedTab == 1 && mirrorStandingsSubTab == 1 && isMirrorShowing()) renderMirrorSection();
                    else if (mirrorSelectedTab == 2 && isMirrorShowing()) renderMirrorScheduleSection();
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
                    boolean rebuildMirrorHistory = !mirrorLiveHistoryInitialized;
                    ensureMirrorHistoryMatchesLivePgn(position, rebuildMirrorHistory);
                    mirrorLiveHistoryInitialized = true;
                    if (isMirrorShowing() && !mirrorReplayMode && mirrorPly < 0) syncMirrorFromLive();
                    if (isMirrorShowing() && !mirrorReplayMode && mirrorSelectedTab == 0) {
                        renderMirrorSection();
                        if (mirrorMovesSubTab == 1 && mirrorAnalysisOn && mirrorPly < 0 && !mirrorBoardOnlyMode) startMirrorAnalysis();
                    } else if (isMirrorShowing() && !mirrorReplayMode && mirrorSelectedTab == 1 && mirrorStandingsSubTab == 1) renderMirrorSection();
                    if (tournamentBoard != null && !isMirrorShowing()) {
                        tournamentBoard.setPosition(position);
                        tournamentBoard.setSelection(tournamentBoard.highlightLastMove && moveToHighlight != null ? moveToHighlight.to : -1);
                    }
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
                    if (!mirrorReplayMode) {
                        if (mirrorBlackAnalysisText != null) mirrorBlackAnalysisText.setText(lastBlackAnalysis);
                        if (mirrorWhiteAnalysisText != null) mirrorWhiteAnalysisText.setText(lastWhiteAnalysis);
                    }
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
                    standingsText.setText(formatStandings(tournament, tournament.standings(), tournament.engines()));
                    refreshMirrorRanks();
                    if (mirrorSelectedTab == 1 && isMirrorShowing()) renderMirrorStandingsSection();
                    if (mirrorSelectedTab == 2 && isMirrorShowing()) renderMirrorScheduleSection();
                    invalidateCachedSchedule(tournament);
                    if (cachedAnomalyDialog != null && cachedAnomalyDialog.isShowing() &&
                            cachedAnomalyTournament == tournament && cachedAnomalySummary != null && cachedAnomalyRows != null)
                        refreshAnomalyRows(tournament, cachedAnomalySummary, cachedAnomalyRows, cachedAnomalyDialog);
                    if (tournamentTitleText != null) tournamentTitleText.setText(tournament.title());
                    if (savedError != null) statusText.setText(result + " · PGN auto-save failed: " + savedError.getMessage());
                    if (!tournament.externalSaveError().isEmpty())
                        statusText.setText(statusText.getText() + " · JSON mirror: " + tournament.externalSaveError());
                });
            }
            @Override public void onTournamentFinished() {
                freezeTournamentElapsedClock();
                runOnUiThread(() -> {
                    runner = null;
                    applyTournamentKeepScreenOn();
                    if (tournament.format() == TournamentManager.Format.CUP && tournament.cupCheckpointPending()) {
                        String next = tournament.cupNextStageLabel();
                        String message = tournament.cupCurrentStageLabel() + " completed. Qualified engines: " +
                                tournament.cupAdvancingEngineIds().size() + ". Next stage: " + next + ".";
                        statusText.setText(message + " Engine update checkpoint.");
                        moveStatusText.setText("CUP CHECKPOINT · " + next.toUpperCase(java.util.Locale.US));
                        startButton.setEnabled(true); resumeButton.setEnabled(true); stopButton.setEnabled(false);
                        standingsText.setText(formatStandings(tournament, tournament.standings(), tournament.engines()));
                        invalidateCachedSchedule(tournament);
                        showCupStageCheckpointDialog(tournament);
                    } else {
                        statusText.setText("Tournament complete");
                        moveStatusText.setText("TOURNAMENT COMPLETE");
                        standingsText.setText(formatStandings(tournament, tournament.standings(), tournament.engines()));
                        startButton.setEnabled(true); resumeButton.setEnabled(true); stopButton.setEnabled(false);
                    }
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
        }, createTournamentEngineOptions(), new BookOptions(), tournament.baseTimeMs(), tournament.incrementMs(),
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
        runner.start();
        // Start both interval clocks with the runner, even when the timer settings
        // were configured before a tournament existed (the old code left the play
        // interval's resume timestamp at zero in that case).
        resetAdvancedTimerRuntime();
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
            freezeTournamentElapsedClock();
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

    private String formatStandings(TournamentManager tournament, List<TournamentManager.Standing> standings, List<TournamentManager.Engine> engines) {
        if (tournament != null && tournament.format() == TournamentManager.Format.CUP)
            return formatCupProgress(tournament, engines);
        StringBuilder out = new StringBuilder("Standings\n");
        for (int i = 0; i < standings.size(); i++) {
            TournamentManager.Standing s = standings.get(i);
            String name = s.engineId;
            for (TournamentManager.Engine e : engines) if (e.id.equals(s.engineId)) { name = e.displayName; break; }
            out.append(i + 1).append(".").append(name).append("\n    ");
            if (tournament != null && tournament.format() == TournamentManager.Format.SWISS) {
                out.append(s.points).append(" pts  ").append(s.played).append(" games  +")
                        .append(s.wins).append(" =").append(s.draws).append(" -").append(s.losses)
                        .append("  BYE:").append(s.byes)
                        .append("  SB:").append(String.format(java.util.Locale.US, "%.2f", s.sonnebornBerger));
            } else {
                out.append(s.points).append("/").append(s.played).append("  +").append(s.wins)
                        .append(" =").append(s.draws).append(" -").append(s.losses);
            }
            out.append("\n");
        }
        return out.toString();
    }

    private String formatCupProgress(TournamentManager tournament, List<TournamentManager.Engine> engines) {
        StringBuilder out = new StringBuilder("Cup bracket progress\n");
        if (tournament.cupFinished()) {
            out.append("Tournament complete\n");
            for (TournamentManager.CupMatch match : tournament.cupMatches()) {
                if (match.stageIndex == CupTournamentManager.stageCount(tournament.cupBracketSize()) - 1 && match.decided) {
                    out.append("Champion: ").append(engineDisplayName(engines, match.winnerId)).append("\n");
                    break;
                }
            }
            return out.toString();
        }
        out.append("Current stage: ").append(tournament.cupCurrentStageLabel()).append("\n");
        int stageMatches = 0, decided = 0;
        for (TournamentManager.CupMatch match : tournament.cupMatches()) {
            if (match.stageIndex != tournament.cupCurrentStage()) continue;
            stageMatches++;
            if (match.decided) decided++;
        }
        out.append("Matches decided: ").append(decided).append("/").append(stageMatches).append("\n");
        if (tournament.cupCheckpointPending()) {
            out.append("Stage completed · waiting for engine-update decision\n");
            out.append("Qualified engines:\n");
            for (String id : tournament.cupAdvancingEngineIds()) out.append("• ").append(engineDisplayName(engines, id)).append("\n");
        } else {
            out.append("Knockout results are determined by the bracket, not cumulative league points.\n");
            out.append("Open VIEW CUP BRACKET to inspect matches and advancing engines.");
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
            resumeTournamentElapsedClock();
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
            if (mirrorPauseButton != null) mirrorPauseButton.setText("PAUSE");
            moveStatusText.setText("Resuming tournament…");
        } else {
            runner.pause();
            freezeTournamentElapsedClock();
            freezeEventElapsedClock();
            heatLastResumeNs = 0L;
            // A manual pause cancels the automatic cooling wait; the next Play starts a fresh cycle.
            heatCooling = false; heatPhaseStartNs = 0L; heatPlayAccumulatedMs = 0L;
            autoPaused = false;
            playPauseButton.setText("P");
            if (mirrorPauseButton != null) mirrorPauseButton.setText("PLAY");
            moveStatusText.setText("PAUSED · Tap Play to continue");
        }
    }

    /** Rewrite the cumulative PGN after every completed game, avoiding duplicate append on resume. */
    private File saveTournamentPgnAutomatically(TournamentManager tournament) throws IOException {
        String safeName = tournament.pgnFileBaseName().trim().replaceAll("[^A-Za-z0-9._ -]", "_")
                .replaceAll("\\s+", " ").replaceAll("[ .]+$", "");
        if (safeName.isEmpty()) safeName = "Tournament";
        byte[] bytes = tournament.exportPgn().getBytes(Charset.forName("UTF-8"));
        File pgnDir = new File(new File(Environment.getExternalStorageDirectory(), "DroidFish"), "pgn");
        File publicFile;
        if (tournament.format() == TournamentManager.Format.CUP) {
            String chosenBase = tournament.cupPgnFileBaseName();
            if (chosenBase.isEmpty()) {
                String candidateBase = safeName;
                int suffix = 2;
                File appPgnDir = getExternalFilesDir("pgn");
                while (new File(pgnDir, candidateBase + ".pgn").exists() ||
                        (appPgnDir != null && new File(appPgnDir, candidateBase + ".pgn").exists())) {
                    candidateBase = safeName + " (" + suffix++ + ")";
                }
                try {
                    tournament.setCupPgnFileBaseNameIfEmpty(candidateBase);
                } catch (JSONException e) {
                    // Keep the PGN save API's IOException contract while preserving
                    // the underlying JSON persistence failure for diagnostics.
                    throw new IOException("Unable to save Cup PGN filename", e);
                }
                chosenBase = tournament.cupPgnFileBaseName();
            }
            safeName = chosenBase;
            publicFile = new File(pgnDir, safeName + ".pgn");
        } else {
            publicFile = new File(pgnDir, safeName + ".pgn");
            // Preserve an unrelated PGN with the same filename; other formats retain
            // their historical single-Event filename matching behavior.
            if (publicFile.exists()) {
                String existing = readPgnText(publicFile);
                String eventTag = "[Event \"" + tournament.title().replace("\\", "\\\\").replace("\"", "\\\"") + "\"]";
                if (!existing.contains(eventTag)) {
                    int suffix = 2;
                    File candidate;
                    do { candidate = new File(pgnDir, safeName + " (" + suffix++ + ").pgn"); }
                    while (candidate.exists() && !readPgnText(candidate).contains(eventTag));
                    publicFile = candidate;
                }
            }
        }
        try {
            File dir = publicFile.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs() && !dir.isDirectory())
                throw new IOException("Cannot create " + dir.getAbsolutePath());
            String mergedPgn = mergePgnGames(publicFile.exists() ? readPgnText(publicFile) : "", tournament.exportPgn());
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
            String mergedPgn = mergePgnGames(fallback.exists() ? readPgnText(fallback) : "", tournament.exportPgn());
            writeSynced(fallback, mergedPgn.getBytes(Charset.forName("UTF-8")));
            return fallback;
        }
    }

    private String mergePgnGames(String existing, String incoming) {
        java.util.LinkedHashMap<String, String> games = new java.util.LinkedHashMap<>();
        collectPgnGames(games, existing);
        collectPgnGames(games, incoming);
        ArrayList<String> orderedGames = new ArrayList<>(games.values());
        Collections.sort(orderedGames, (a, b) -> Long.compare(pgnRoundOrderKey(a), pgnRoundOrderKey(b)));
        StringBuilder result = new StringBuilder();
        for (String game : orderedGames) { if (result.length() > 0) result.append("\n\n"); result.append(game); }
        return result.toString();
    }

    private long pgnRoundOrderKey(String game) {
        String round = pgnTagValue(game, "Round");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d+)\\.(\\d+)$").matcher(round.trim());
        if (!m.matches()) return Long.MAX_VALUE;
        try { return Long.parseLong(m.group(1)) * 1000000L + Long.parseLong(m.group(2)); }
        catch (NumberFormatException ignored) { return Long.MAX_VALUE; }
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
            if (!game.isEmpty()) games.put(pgnGameIdentity(game), game);
        }
    }

    /** Event/Date/Round are stable across display-name corrections, so PGN merges replace rather than duplicate a game. */
    private String pgnGameIdentity(String game) {
        String event = pgnTagValue(game, "Event");
        String date = pgnTagValue(game, "Date");
        String round = pgnTagValue(game, "Round");
        if (event.isEmpty() && date.isEmpty() && round.isEmpty())
            return game.replaceAll("\\s+", " ");
        return event + "\u001f" + date + "\u001f" + round;
    }

    private String pgnTagValue(String pgn, String tag) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?m)^\\[" + tag + " \"((?:\\\\.|[^\"])*)\"\\]$").matcher(pgn);
        return matcher.find() ? matcher.group(1) : "";
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

    /** Cup PGNs can be larger than the lightweight preview limit used by other text files. */
    private String readPgnText(File file) throws IOException {
        if (file == null || !file.isFile()) return "";
        try (java.io.FileInputStream in = new java.io.FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32768]; int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return new String(out.toByteArray(), Charset.forName("UTF-8"));
        }
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
        stopMirrorAnalysis();
        saveSetup();
        if (runner != null && !runner.isPaused()) {
            runner.pause();
            freezeTournamentElapsedClock();
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
        stopMirrorAutoPlay();
        stopMirrorAnalysis();
        stopGameViewerAnalysis();
        uiHandler.removeCallbacks(clockRefresh);
        if (runner != null) {
            runner.pause();
            freezeTournamentElapsedClock();
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
