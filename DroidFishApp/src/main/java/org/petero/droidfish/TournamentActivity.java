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
import android.graphics.drawable.BitmapDrawable;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextWatcher;
import android.text.Editable;
import android.os.Environment;
import android.view.View;
import android.view.GestureDetector;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
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
import org.petero.droidfish.tournament.TournamentDiagnostics;
import org.json.JSONException;
import org.petero.droidfish.engine.DroidComputerPlayer;
import org.petero.droidfish.engine.UCIOptions;
import org.petero.droidfish.activities.EditOptions;
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
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Simple native setup and live-results screen for CET-style engine tournaments. */
public final class TournamentActivity extends Activity {
    private static final String ENGINE_DIR = "DroidFish/uci";
    private final ArrayList<EngineChoice> availableEngines = new ArrayList<>();
    private final ArrayList<EngineChoice> selectedEngines = new ArrayList<>();
    private TextView selectedText, selectedCountText, gameEstimateText, statusText, standingsText, clockText, tournamentTitleText;
    private TextView whiteClockText, blackClockText, whiteNameText, blackNameText, moveStatusText;
    private LinearLayout playPage, blackClockRow, whiteClockRow;
    private Dialog expandedMainDialog;
    private TournamentChessBoard expandedMainBoard;
    private TextView expandedWhiteName, expandedBlackName, expandedWhiteAnalysis, expandedBlackAnalysis,
            expandedWhiteClock, expandedBlackClock, expandedWhiteMoveTime, expandedBlackMoveTime,
            expandedWhiteEngineTitle, expandedBlackEngineTitle, expandedWhiteTiming, expandedBlackTiming,
            expandedWhiteTimingMove, expandedBlackTimingMove;
    private LinearLayout expandedWhiteTimingGroup, expandedBlackTimingGroup;
    private Button expandedFlipBoardButton, expandedPlayPauseButton;
    private HorizontalScrollView expandedWhitePv, expandedBlackPv;
    private ScrollView expandedMainScroll;
    private ImageView expandedWhiteLogo, expandedBlackLogo;
    private View expandedBlackAnalysisPanel, expandedWhiteAnalysisPanel, expandedMovesContainer;
    private LinearLayout expandedMovesRows, expandedBlackPlayerRow, expandedWhitePlayerRow;
    private LinearLayout expandedRoot, expandedPanelsRow, expandedBlackInfo, expandedWhiteInfo;
    private FrameLayout expandedBoardFrame;
    private boolean expandedWhitePanelBelowBoard, expandedPanelsSwapped;
    private boolean expandedBottomPanelWhite = true;
    private boolean expandedBlackLogoAfterInfo, expandedWhiteLogoAfterInfo;
    private String expandedMovesSignature = "";
    private boolean tournamentUiFlipped = false;
    private boolean currentWhiteToMove = true;
    private String currentWhiteName = "WHITE", currentBlackName = "BLACK";
    private String currentBoardWhiteScore = "", currentBoardBlackScore = "";
    private TextView whiteAnalysisText, blackAnalysisText, whiteAnalysisPvText, blackAnalysisPvText,
            whiteWdlText, blackWdlText, whiteMoveTimeText, blackMoveTimeText, openingText,
            mainWhiteEngineTitle, mainBlackEngineTitle;
    private LinearLayout mainWhiteAnalysisScroll, mainBlackAnalysisScroll;
    // null preserves the normal side-based placement; a side value makes that engine the shared top output.
    // null = the normal separated Black-top / White-bottom layout; otherwise one shared output panel.
    private Boolean mainBoardSharedOutputTop;
    private boolean mainBoardSharedActiveWhite = true;
    private View mirrorView;
    private int currentTournamentScreen = 0; // 0 = live board, 1 = mirror board
    private int expandedMainReturnScreen = 0;
    private boolean boardSelectionActive;
    private boolean activityLeaving;
    private int tournamentArrowCount = 1;
    /** Number of consecutive main-PV plies to show as external-analysis arrows. */
    private int mirrorAnalysisArrowPlyCount = 1;
    private TournamentChessBoard mirrorBoard;
    private TextView mirrorBlackAnalysisText, mirrorWhiteAnalysisText, mirrorBlackPvText, mirrorWhitePvText,
            mirrorWhiteClock, mirrorBlackClock, mirrorWhiteName, mirrorBlackName,
            mirrorWhiteMoveTime, mirrorBlackMoveTime, mirrorStatusText, mirrorHeaderText,
            mirrorLastMoveText, mirrorSectionTitle;
    private LinearLayout mirrorPage, mirrorBlackRow, mirrorWhiteRow, mirrorSectionHost, mirrorControlBar,
            mirrorPrimaryControls, mirrorNavigationControls;
    private final HashMap<Integer, LinearLayout> mirrorSectionCache = new HashMap<>();
    private final HashMap<Integer, LinearLayout> mirrorMovesSubCache = new HashMap<>();
    private final HashMap<Integer, LinearLayout> mirrorStandingsSubCache = new HashMap<>();
    private ScrollView mirrorScroll;
    private LinearLayout mirrorBlackAnalysisScroll, mirrorWhiteAnalysisScroll;
    private ScrollView mirrorBlackPvScroll, mirrorWhitePvScroll;
    private LinearLayout mirrorBlackOutputPanel, mirrorWhiteOutputPanel;
    private LinearLayout mirrorBlackPlayerGroup, mirrorWhitePlayerGroup;
    private MirrorEvaluationBar mirrorBlackEvaluationBar, mirrorWhiteEvaluationBar, mirrorFocusEvaluationBar;
    private View mirrorBoardFrame, mirrorGraphScrollSpacer;
    private HorizontalScrollView mirrorMoveStripScroll, mirrorShortcutAnalysisScroll;
    private ScrollView mirrorFocusAnalysisPvScroll, mirrorFocusMovesScroll;
    private LinearLayout mirrorMoveStrip, mirrorBoardShortcuts, mirrorFocusAnalysisContainer, mirrorFocusAnalysisRow, mirrorFocusEngineTitleRow;
    private TextView mirrorShortcutAnalysis, mirrorFocusAnalysisText, mirrorFocusAnalysisPvText, mirrorFocusMovesText, mirrorFocusEngineTitle;
    private boolean mirrorBoardOnlyMode, mirrorNavigationMode, mirrorNavigationShowLastMove;
    private boolean mirrorEngineOutputHidden, mirrorPlayerPanelsCompact = false;
    private boolean mirrorFocusAnalysisWhite = true;
    private boolean mirrorBlackIconAfterInfo, mirrorWhiteIconAfterInfo, mirrorFocusIconAfterInfo;
    private boolean mirrorBlackInfoAfterVariation = true, mirrorWhiteInfoAfterVariation = true, mirrorFocusInfoAfterVariation = true;
    private boolean mirrorBranchMode;
    private int mirrorBranchBasePly, mirrorBranchOriginalPly;
    private ArrayList<Position> mirrorBranchBasePositions = new ArrayList<>();
    private ArrayList<String> mirrorBranchBaseMoves = new ArrayList<>();
    private MirrorBranchNode mirrorBranchRoot, mirrorBranchCurrent;
    private String mirrorCurrentLastMoveLabel = "Starting position";
    private Move mirrorNavigatedLastMove;
    private Button mirrorMovesTab, mirrorStandingsTab, mirrorScheduleTab, mirrorInfoTab, mirrorPauseButton;
    private Button mirrorNavFirstButton, mirrorNavPreviousButton, mirrorNavPlayButton, mirrorNavNextButton, mirrorNavLastButton;
    private Button mirrorMoveListTab, mirrorMoveAnalysisTab, mirrorGameInfoTab;
    private Button mirrorStandingsTableTab, mirrorGraphTab, mirrorCrosstableTab;
    private int mirrorMovesSubTab = 0, mirrorStandingsSubTab = 0, mirrorGraphMetric = 0;
    private ImageView mirrorWhiteIcon, mirrorBlackIcon, mirrorFocusWhiteIcon, mirrorFocusBlackIcon, mirrorFocusEngineIcon;
    private Button mirrorAnalysisToggle, mirrorAnalysisChooseEngine, mirrorAnalysisEngineToggle;
    private LinearLayout mirrorAnalysisOutput;
    private TextView mirrorAnalysisEngineName;
    private String mirrorAnalysisCoreOutput = "";
    private String mirrorAnalysisDepthOutput = "";
    private boolean mirrorAnalysisDepthsVisible = true;
    private volatile int mirrorAnalysisVariationCount = 1;
    private DroidComputerPlayer mirrorAnalysisEngine;
    private String mirrorAnalysisEngineId = "";
    private boolean mirrorAnalysisOn, mirrorAnalysisEngineActive = true;
    private String mirrorLatestAnalysisOutput = "Select an engine, then turn analysis on.";
    private int mirrorAnalysisSearchId = 61000;
    // These are updated for every requested position. The engine listener lives
    // for the lifetime of the selected engine, so it must not capture the first
    // position's ply or accept callbacks from an obsolete search.
    private volatile int mirrorAnalysisTargetPly = -1;
    private volatile int mirrorAnalysisRequestId = -1;
    private long mirrorAnalysisGeneration;
    private final ArrayList<Move> mirrorAnalysisHints = new ArrayList<>();
    private final HashMap<Integer, ArrayList<Move>> mirrorAnalysisHintsByPly = new HashMap<>();
    /** Tournament-only MultiPV first moves; never applied to the Mirror Board. */
    private final ArrayList<Move> tournamentVariationHints = new ArrayList<>();
    private long tournamentVariationPositionHash = Long.MIN_VALUE;
    private final java.util.TreeMap<Integer, String> mirrorAnalysisPvByDepth = new java.util.TreeMap<>();
    private String mirrorReplayPgn = "";
    private CheckBox engineIconsCheck;
    private CheckBox ratingEnabledCheck;
    private LinearLayout ratingInitialContainer;
    private final HashMap<String, EditText> ratingInitialInputs = new HashMap<>();
    private final HashMap<String, String> ratingSetupValues = new HashMap<>();
    private Button engineIconFolderButton, engineIconConfigureButton;
    private Uri engineIconFolderUri;
    private final Set<String> engineIconDisabledIds = new HashSet<>();
    private final HashMap<String, Uri> engineIconUris = new HashMap<>();
    private final HashMap<String, Bitmap> engineIconBitmaps = new HashMap<>();
    private boolean engineIconFolderScanned;
    private boolean restoringSetup;
    private boolean loadingTournamentSetup;
    private static final int REQUEST_ENGINE_ICON_FOLDER = 7318;
    private ListView mirrorScheduleList;
    private ScrollView mirrorMovesListScroll;
    // Reused selected move cell: live external PV updates its comment in place rather
    // than rebuilding the complete move-list hierarchy for every engine callback.
    private TextView mirrorSelectedMoveCommentCell;
    private int mirrorSelectedMoveCommentPly = -1;
    private String mirrorSelectedMoveCommentPrefix = "", mirrorSelectedMoveCommentSan = "", mirrorSelectedMoveCommentStored = "";
    private boolean mirrorSelectedMoveCommentWhite;
    private int mirrorMovesListScrollY;
    private boolean mirrorMovesListWasAtBottom = true;
    private boolean mirrorMovesListHasSavedPosition;
    private int mirrorScheduleFirstVisiblePosition;
    private int mirrorScheduleFirstVisibleTop;
    private boolean mirrorScheduleHasSavedScroll;
    private final ArrayList<String> mirrorScheduleRows = new ArrayList<>();
    private final ArrayList<Integer> mirrorSchedulePairingIndexes = new ArrayList<>();
    private TournamentManager scheduleMoveCountCacheOwner;
    private final HashMap<Integer, ScheduleMoveCountCacheEntry> scheduleMoveCountCache = new HashMap<>();
    private boolean scheduleMoveCountsReady, scheduleMoveCountsBuilding;
    private volatile long scheduleMoveCountBuildGeneration;
    private static final class ScheduleMoveCountTask {
        final int pairingIndex;
        final String pgnReference;
        ScheduleMoveCountTask(int pairingIndex, String pgnReference) {
            this.pairingIndex = pairingIndex;
            this.pgnReference = pgnReference;
        }
    }
    private static final class ScheduleMoveCountCacheEntry {
        final String pgnReference;
        final int fullMoves;
        ScheduleMoveCountCacheEntry(String pgnReference, int fullMoves) {
            this.pgnReference = pgnReference;
            this.fullMoves = fullMoves;
        }
    }
    private final Handler mirrorScheduleClickHandler = new Handler(Looper.getMainLooper());
    private Runnable mirrorSchedulePendingClick;
    private int mirrorSchedulePendingPosition = -1;
    private boolean mirrorUiFlipped = false;
    private int mirrorSelectedTab = 0; // 0 = Moves, 1 = Standings, 2 = Schedule, 3 = Info
    private boolean mirrorReplayMode = false, mirrorReplayLiveSnapshot = false, mirrorAutoPlay = false;
    private volatile int mirrorNavigationCommentLoadGeneration;
    private volatile boolean mirrorNavigationCommentsLoading;
    private String mirrorNavigationWhiteName = "White", mirrorNavigationBlackName = "Black";
    private String mirrorNavigationWhiteEngineId = "", mirrorNavigationBlackEngineId = "";
    private int mirrorReplayPairingIndex = -1;
    private String mirrorReplayWhiteEngineId = "", mirrorReplayBlackEngineId = "";
    private final ArrayList<Position> mirrorReplayPositions = new ArrayList<>();
    private final ArrayList<String> mirrorReplayMoves = new ArrayList<>(), mirrorReplayComments = new ArrayList<>();
    private String mirrorReplayWhiteName = "White", mirrorReplayBlackName = "Black";
    private String currentWhiteEngineId = "", currentBlackEngineId = "";
    private final HashMap<String, Integer> mirrorRanks = new HashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, String> mirrorReportedEngineNames = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int REQUEST_TOURNAMENT_ENGINE_OPTIONS = 7320;
    private boolean pendingAnalysisEngineOptions;
    private boolean pendingTournamentOptionsWhite;
    private String pendingTournamentOptionsEngineId = "";
    private final Handler mirrorAutoPlayHandler = new Handler(Looper.getMainLooper());
    private Runnable mirrorAutoPlayRunnable;
    private int currentGameNumber = 0, currentGameTotal = 0;
    private boolean mirrorHeaderShowsTitle;
    private TournamentManager activeTournamentManager;
    /** True while a saved tournament is being browsed without a live runner. */
    private boolean savedTournamentReviewMode;
    private String mirrorTcLabel = "—";
    private final ArrayList<Position> mirrorPositions = new ArrayList<>();
    private final ArrayList<String> mirrorLiveSanMoves = new ArrayList<>();
    private final ArrayList<String> mirrorLiveComments = new ArrayList<>();
    private final ArrayList<String> mirrorLiveAnalysisByPly = new ArrayList<>();
    private boolean mirrorLiveHistoryInitialized;
    private boolean mirrorLiveHistoryRebuildPending;
    private long mirrorLiveHistoryRebuildGeneration;
    private long mirrorDeferredSectionRefreshGeneration;
    private Runnable mirrorDeferredSectionRefreshRunnable;
    private Runnable cachedScheduleRefreshRunnable;
    private int mirrorFocusMovesLastSelectedPly = Integer.MIN_VALUE;
    private int mirrorPly = -1;
    private String lastWhiteAnalysis = "—", lastBlackAnalysis = "—";
    private long lastWhiteTimeMs, lastBlackTimeMs, mirrorLastMoveTimeMs;
    private CheckBox showTournamentArrowsCheck;
    private CheckBox disableScreenTimeoutCheck;
    private Position latestPosition = new Position();
    private float touchDownX, touchDownY;
    private Button previousMoveButton, nextMoveButton;
    private TournamentChessBoard tournamentBoard;
    private View setupView, playView;
    private AlertDialog cachedScheduleDialog;
    private TournamentManager cachedScheduleTournament;
    private ListView cachedScheduleList;
    private ArrayAdapter<String> cachedScheduleAdapter;
    private final ArrayList<String> cachedScheduleRows = new ArrayList<>();
    private final ArrayList<Integer> cachedSchedulePairingIndexes = new ArrayList<>();
    private final HashMap<Integer, TournamentManager.Pairing> cachedSchedulePairingsByIndex = new HashMap<>();
    private final HashMap<Integer, int[]> cachedScheduleNameRangesByIndex = new HashMap<>();
    private String cachedScheduleContents = "";
    private Button cachedScheduleAnomaliesButton, cachedScheduleQueueButton;
    private boolean cachedScheduleDirty = true;
    private AlertDialog cachedAnomalyDialog;
    private volatile boolean tournamentLoadInProgress;
    // A failed legacy import retains its source files. Avoid blocking each later
    // picker opening with the same bulk migration attempt during this process.
    private static volatile boolean legacyMigrationAttemptedThisProcess = false;
    private volatile boolean tournamentReviewPreparationInProgress;
    private TournamentManager cachedAnomalyTournament;
    private TextView cachedAnomalySummary;
    private LinearLayout cachedAnomalyRows;
    private Spinner formatSpinner, timeModeSpinner, swissSeedingSpinner, cupSeedingSpinner;
    private EditText titleInput, gamesInput, swissRoundsInput, baseInput, incrementInput, limitInput, openingPliesInput, openingStartInput, autoWinEvalInput, pgnPlaybackIntervalInput, chess960StartInput, variantSeedInput, openingFenInput;
    private Spinner startPositionModeSpinner;
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
    private Button startButton, resumeButton, completedButton, stopButton, playPauseButton;
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
    private static final AtomicReference<TournamentManager> PENDING_ELAPSED_SAVE = new AtomicReference<>();
    private static final AtomicBoolean ELAPSED_SAVE_WORKER_ACTIVE = new AtomicBoolean(false);
    private static final ExecutorService TOURNAMENT_SAVE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "DroidFish-Tournament-Save");
        thread.setDaemon(true);
        return thread;
    });
    private static final ExecutorService TOURNAMENT_LIFECYCLE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "DroidFish-Tournament-Lifecycle");
        thread.setDaemon(true);
        return thread;
    });
    private static final ExecutorService MIRROR_PREPARE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "DroidFish-Mirror-Prepare");
        thread.setDaemon(true);
        return thread;
    });
    private static final ExecutorService TOURNAMENT_EXPORT_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "DroidFish-Tournament-Export");
        thread.setDaemon(true);
        return thread;
    });
    private static final ExecutorService MIRROR_HISTORY_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "DroidFish-Mirror-History");
        thread.setDaemon(true);
        return thread;
    });
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
    private Runnable mirrorLiveSectionRefreshRunnable;
    private Runnable mirrorGraphRefreshRunnable;
    private Runnable expandedMovesRefreshRunnable;
    private final AtomicReference<String> pendingWhiteEngineAnalysisUi = new AtomicReference<>();
    private final AtomicReference<String> pendingBlackEngineAnalysisUi = new AtomicReference<>();
    private final AtomicBoolean engineAnalysisUiUpdatePosted = new AtomicBoolean(false);

    /** A compact, independent White/Black evaluation balance indicator for Mirror Board. */
    private final class MirrorEvaluationBar extends View {
        private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float whiteShare = 0.5f;
        private String scoreLabel = "—";
        private android.animation.ValueAnimator shareAnimator;
        private static final float MAX_BAR_PAWNS = 8.0f;

        MirrorEvaluationBar(android.content.Context context) {
            super(context);
            setContentDescription("Engine evaluation balance");
        }

        void setEngineAnalysis(String analysis, boolean whiteEngine) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                    "(?i)\\bEval\\s*:\\s*([+-]?(?:M\\d+|\\d+(?:\\.\\d+)?))")
                    .matcher(analysis == null ? "" : analysis);
            if (!matcher.find()) {
                // A real new-game/book state returns the bar to center. A transient
                // missing field must not make a retained evaluation jump to zero.
                if (analysis != null && (analysis.trim().equalsIgnoreCase("Book.") ||
                        analysis.startsWith("WHITE analysis waiting") || analysis.startsWith("BLACK analysis waiting"))) {
                    scoreLabel = "—";
                    animateShareTo(0.5f);
                }
                setContentDescription((whiteEngine ? "White" : "Black") + " engine evaluation " + scoreLabel);
                return;
            }
            String token = matcher.group(1).toUpperCase(java.util.Locale.US);
            float target;
            if (token.indexOf('M') >= 0) {
                // Mate distance does not change the bar: any winning mate is the
                // full positive endpoint and any losing mate is the full negative endpoint.
                target = token.startsWith("-") ? 0.0f : 1.0f;
            } else {
                try {
                    double score = Double.parseDouble(token);
                    score = Math.max(-MAX_BAR_PAWNS, Math.min(MAX_BAR_PAWNS, score));
                    // Two pawns move the split by one of the board's eight files.
                    // Thus ±8 reaches an endpoint and 0.00 stays exactly centered.
                    target = (float)(0.5 + score / (2.0 * MAX_BAR_PAWNS));
                } catch (NumberFormatException ignored) {
                    return;
                }
            }
            scoreLabel = token;
            setContentDescription((whiteEngine ? "White" : "Black") + " engine evaluation " + scoreLabel);
            animateShareTo(target);
        }

        private void animateShareTo(float target) {
            target = Math.max(0.0f, Math.min(1.0f, target));
            if (Math.abs(target - whiteShare) < 0.002f) {
                whiteShare = target;
                invalidate();
                return;
            }
            if (shareAnimator != null) shareAnimator.cancel();
            shareAnimator = android.animation.ValueAnimator.ofFloat(whiteShare, target);
            shareAnimator.setDuration(260L);
            shareAnimator.setInterpolator(new android.view.animation.DecelerateInterpolator());
            shareAnimator.addUpdateListener(animation -> {
                whiteShare = (Float)animation.getAnimatedValue();
                invalidate();
            });
            shareAnimator.start();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth();
            float height = getHeight();
            if (width <= 0 || height <= 0) return;
            float radius = height / 2.0f;
            android.graphics.RectF track = new android.graphics.RectF(0, 0, width, height);
            barPaint.setColor(0xFF555B60);
            canvas.drawRoundRect(track, radius, radius, barPaint);
            float split = width * whiteShare;
            android.graphics.RectF whitePart = new android.graphics.RectF(0, 0, split, height);
            android.graphics.RectF blackPart = new android.graphics.RectF(split, 0, width, height);
            barPaint.setColor(0xFFF0F0F0);
            canvas.drawRoundRect(whitePart, radius, radius, barPaint);
            barPaint.setColor(0xFF17191C);
            canvas.drawRoundRect(blackPart, radius, radius, barPaint);
            barPaint.setColor(0xFF8B9298);
            canvas.drawRect(width * 0.5f - dp(1), 0, width * 0.5f + dp(1), height, barPaint);
        }
    }
    private long lastClockRefreshNs;
    private volatile long lastUiHeartbeatNs = System.nanoTime();
    private volatile long lastUiStallLogNs;
    private Thread uiStallWatchdog;
    private final Runnable clockRefresh = new Runnable() {
        @Override public void run() {
            long now = System.nanoTime();
            if (lastClockRefreshNs != 0L) {
                long gapMs = (now - lastClockRefreshNs) / 1_000_000L;
                if (gapMs >= 750L)
                    android.util.Log.w("CET-UiLag", "Clock refresh delayed by " + gapMs + " ms");
            }
            lastClockRefreshNs = now;
            lastUiHeartbeatNs = now;
            if (activityVisible) {
                refreshLiveClocks();
                updateExpandedBoardControls();
            }
            // Tournament engine workers and durable result commits continue while
            // another app is foregrounded. Poll advanced timers at a lower rate in
            // the background; avoid hidden-view refreshes competing for CPU.
            tickAutomaticTimers();
            uiHandler.postDelayed(this, activityVisible ? 100L : 1000L);
        }
    };

    private static final class EngineChoice {
        final String id, name;
        EngineChoice(String id, String name) { this.id = id; this.name = name; }
        @Override public String toString() { return name; }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TournamentDiagnostics.initialize(this);
        TournamentDiagnostics.record("ACTIVITY", "tournament_activity_on_create", "savedInstanceState=" + (state != null));
        setTitle(R.string.option_tournament);
        latestPosition = standardStartPosition();
        SharedPreferences boardPrefs = getPreferences(MODE_PRIVATE);
        tournamentUiFlipped = boardPrefs.getBoolean("tournamentUiFlipped", false);
        mirrorUiFlipped = tournamentUiFlipped;
        tournamentArrowCount = clampArrowCount(boardPrefs.getInt("tournamentArrowCount", 1));
        mirrorAnalysisVariationCount = clampArrowCount(boardPrefs.getInt("mirrorAnalysisVariationCount", 1));
        mirrorAnalysisArrowPlyCount = clampArrowCount(boardPrefs.getInt("mirrorAnalysisArrowPlyCount", 1));
        restoreMirrorPanelLayoutPreferences(boardPrefs);
        loadEngineChoices();
        buildUi();
        restoreSetup();
        restoreAdvancedTimerSettings();
        uiHandler.post(clockRefresh);
        startUiStallWatchdog();
    }

    private static int clampArrowCount(int count) {
        return Math.max(1, Math.min(ColorTheme.MAX_ARROWS, count));
    }

    private void saveBoardSelectionState(int selectedBoard) {
        int safeBoard = Math.max(0, Math.min(2, selectedBoard));
        SharedPreferences.Editor editor = getPreferences(MODE_PRIVATE).edit();
        editor.putInt("tournamentLastBoardSelection", safeBoard)
                .putBoolean("tournamentNewBoardDoubleTap", expandedWhitePanelBelowBoard);
        if (safeBoard == 2) editor.putInt("tournamentNewBoardReturnScreen", expandedMainReturnScreen);
        // This is a small preference record; commit synchronously so a force-close
        // immediately after leaving the board cannot lose the selected board/mode.
        editor.commit();
    }

    private void saveExpandedPanelLayoutPreferences() {
        SharedPreferences.Editor editor = getPreferences(MODE_PRIVATE).edit();
        // Normal and double-tap layouts own independent icon placement settings.
        if (expandedWhitePanelBelowBoard) {
            editor.putBoolean("tournamentExpandedBlackLogoAfterInfoDoubleTap", expandedBlackLogoAfterInfo)
                    .putBoolean("tournamentExpandedWhiteLogoAfterInfoDoubleTap", expandedWhiteLogoAfterInfo);
        } else {
            editor.putBoolean("tournamentExpandedBlackLogoAfterInfoNormal", expandedBlackLogoAfterInfo)
                    .putBoolean("tournamentExpandedWhiteLogoAfterInfoNormal", expandedWhiteLogoAfterInfo);
        }
        editor.putBoolean("tournamentExpandedPanelsSwapped", expandedPanelsSwapped)
                .putBoolean("tournamentExpandedBottomPanelWhite", expandedBottomPanelWhite)
                .putBoolean("tournamentUiFlipped", tournamentUiFlipped)
                .commit();
    }

    /** Restore the last selected tournament board when returning from another Activity. */
    private void restoreBoardSelectionState() {
        if (!boardSelectionActive || playView == null || mirrorView == null) return;
        SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        int selected = Math.max(0, Math.min(2, prefs.getInt("tournamentLastBoardSelection", currentTournamentScreen)));
        if (selected == 2) {
            expandedMainReturnScreen = Math.max(0, Math.min(1, prefs.getInt("tournamentNewBoardReturnScreen", 0)));
            currentTournamentScreen = expandedMainReturnScreen;
            if (expandedMainDialog == null || !expandedMainDialog.isShowing()) showExpandedMainBoard();
        } else if (selected == 1) {
            currentTournamentScreen = 1;
            setContentView(mirrorView);
        } else {
            currentTournamentScreen = 0;
            setContentView(playView);
        }
    }

    /** Log the main-thread stack if the UI heartbeat stalls long enough to risk an Android ANR. */
    private void startUiStallWatchdog() {
        if (uiStallWatchdog != null) return;
        uiStallWatchdog = new Thread(() -> {
            long lastHeartbeatCheckpointNs = 0L;
            while (!Thread.currentThread().isInterrupted()) {
                try { Thread.sleep(1000L); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                long now = System.nanoTime();
                if (now - lastHeartbeatCheckpointNs >= 10_000_000_000L) {
                    lastHeartbeatCheckpointNs = now;
                    TournamentDiagnostics.record("WATCHDOG", "heartbeat_checkpoint", "mainHeartbeatAgeMs=" +
                            ((now - lastUiHeartbeatNs) / 1_000_000L) + " phase=" + TournamentDiagnostics.currentPhase());
                }
                long heartbeat = lastUiHeartbeatNs;
                long stalledMs = (now - heartbeat) / 1_000_000L;
                if (stalledMs < 2500L || now - lastUiStallLogNs < 10_000_000_000L) continue;
                lastUiStallLogNs = now;
                Thread mainThread = Looper.getMainLooper().getThread();
                TournamentDiagnostics.recordUiStall(stalledMs, mainThread);
                StringBuilder stack = new StringBuilder();
                for (StackTraceElement element : mainThread.getStackTrace())
                    stack.append("\n  at ").append(element);
                android.util.Log.e("CET-ANR", "Main thread heartbeat stalled for " + stalledMs +
                        " ms; current main-thread stack:" + stack);
            }
        }, "CET-UI-Stall-Watchdog");
        uiStallWatchdog.setDaemon(true);
        uiStallWatchdog.start();
    }

    private void showTournamentDiagnostics() {
        final String log = TournamentDiagnostics.readLog(this);
        TextView contents = new TextView(this);
        contents.setText(log);
        contents.setTextIsSelectable(true);
        contents.setTextSize(11);
        contents.setTypeface(android.graphics.Typeface.MONOSPACE);
        contents.setPadding(dp(12), dp(10), dp(12), dp(10));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(contents, new ScrollView.LayoutParams(-1, -2));
        new AlertDialog.Builder(this)
                .setTitle("Tournament freeze diagnostics")
                .setMessage("Log path: " + TournamentDiagnostics.getLogPath(this) +
                        "\nPreferred shared folder: /DroidFish/error_log/\nIf shared storage is unavailable, DroidFish uses its private fallback. The log is checkpointed and retained after process termination.")
                .setView(scroll)
                .setPositiveButton("Copy log", (dialog, which) -> {
                    ClipboardManager clipboard = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                    if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Tournament diagnostics", log));
                    Toast.makeText(this, "Diagnostic log copied", Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("Close", null).show();
    }

    private Position standardStartPosition() {
        try { return TextIO.readFEN(TextIO.startPosFEN); }
        catch (ChessParseError e) { return new Position(); }
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

        Button diagnosticsButton = new Button(this);
        diagnosticsButton.setText("Show / Copy Freeze Diagnostic Log");
        root.addView(diagnosticsButton, fullWidth());
        diagnosticsButton.setOnClickListener(v -> showTournamentDiagnostics());

        Button choose = new Button(this);
        choose.setText("Select tournament engines");
        root.addView(choose, fullWidth());
        selectedCountText = text("0 engines selected");
        selectedCountText.setTextColor(0xFFBFCBD2);
        root.addView(selectedCountText, fullWidth());
        selectedText = text("No engines selected"); root.addView(selectedText, fullWidth());
        choose.setOnClickListener(v -> chooseEngines());

        ratingEnabledCheck = new CheckBox(this);
        ratingEnabledCheck.setText("Enable Elo rating for this tournament");
        root.addView(ratingEnabledCheck, fullWidth());
        ratingInitialContainer = new LinearLayout(this);
        ratingInitialContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(ratingInitialContainer, fullWidth());
        ratingEnabledCheck.setOnCheckedChangeListener((button, checked) -> {
            updateRatingSetupUi();
            if (!restoringSetup && !loadingTournamentSetup) saveSetup();
        });
        updateRatingSetupUi();

        engineIconsCheck = new CheckBox(this);
        engineIconsCheck.setText("Show engine icons on Mirror Board and engine lists");
        root.addView(engineIconsCheck, fullWidth());
        engineIconsCheck.setOnCheckedChangeListener((button, checked) -> {
            if (restoringSetup) return;
            saveSetup();
            engineIconBitmaps.clear();
            invalidateAllMirrorSectionCaches(); clearMirrorSubCaches();
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
        gameEstimateText = text("Estimated games: Select at least two engines");
        gameEstimateText.setTextColor(0xFFBFCBD2);
        root.addView(gameEstimateText, fullWidth());
        gamesInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { updateEstimatedGames(); }
            @Override public void afterTextChanged(Editable e) { }
        });
        swissRoundsInput = field("Swiss rounds (blank = automatic)", "");
        swissRoundsInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        swissRoundsInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { updateEstimatedGames(); }
            @Override public void afterTextChanged(Editable e) { }
        });
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
        pgnPlaybackIntervalInput = field("PGN playback speed — seconds per move (default 1.0)", "1.0");
        pgnPlaybackIntervalInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        root.addView(pgnPlaybackIntervalInput, fullWidth());
        repeatOpeningCheck = new CheckBox(this); repeatOpeningCheck.setText("Repeat opening for paired color-reversal games");
        root.addView(repeatOpeningCheck, fullWidth());
        root.addView(text("Tournament starting-position mode"), fullWidth());
        startPositionModeSpinner = new Spinner(this);
        startPositionModeSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Standard chess", "Chess960", "DFRC — Double Fischer Random Chess", "Chess324 — Kaufman 324"}));
        root.addView(startPositionModeSpinner, fullWidth());
        chess960StartInput = field("Starting Chess960 position number (1–960; wraps from 960 to 1)", "1");
        chess960StartInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        root.addView(chess960StartInput, fullWidth());
        variantSeedInput = field("DFRC / Chess324 seed (blank = generate and save automatically)", "");
        variantSeedInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        root.addView(variantSeedInput, fullWidth());
        openingFenInput = field("Optional starting FEN (when supplied, overrides PGN/BIN and the selected mode)", "");
        openingFenInput.setMinLines(2);
        openingFenInput.setMaxLines(3);
        openingFenInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        root.addView(openingFenInput, fullWidth());
        startPositionModeSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                updateStartingPositionUi();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        formatSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                applyTournamentFormatUi(position);
                updateEstimatedGames();
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
        showTournamentArrowsCheck.setOnCheckedChangeListener((button, checked) -> {
            getPreferences(MODE_PRIVATE).edit().putBoolean("tournamentThinkingArrows", checked).commit();
            applyTournamentVariationHints();
        });
        root.addView(showTournamentArrowsCheck, fullWidth());
        disableScreenTimeoutCheck = new CheckBox(this);
        disableScreenTimeoutCheck.setText("Disable Screen Timeout during tournament");
        disableScreenTimeoutCheck.setChecked(getPreferences(MODE_PRIVATE).getBoolean("tournamentDisableScreenTimeout", false));
        disableScreenTimeoutCheck.setOnCheckedChangeListener((button, checked) -> {
            getPreferences(MODE_PRIVATE).edit().putBoolean("tournamentDisableScreenTimeout", checked).commit();
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
        resumeButton = new Button(this); resumeButton.setText("Active tournaments · Resume / View"); root.addView(resumeButton, fullWidth());
        completedButton = new Button(this); completedButton.setText("Archived tournaments · View only"); root.addView(completedButton, fullWidth());
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
                boardSelectionActive = true;
                saveBoardSelectionState(0);
                boolean noActiveGame = runner == null || runner.isFinished() || runner.isStopped();
                Position displayPosition = noActiveGame ? standardStartPosition() : latestPosition;
                if (displayPosition == null) displayPosition = standardStartPosition();
                if (tournamentBoard != null) {
                    tournamentBoard.setPosition(new Position(displayPosition));
                    Move last = noActiveGame ? null : moveAtPly(mirrorPositions, Math.max(0, mirrorPositions.size() - 1));
                    tournamentBoard.setLastMove(last);
                    tournamentBoard.setMoveHints(null);
                }
                setContentView(playView);
            }
        });
        statusText = text("Ready"); root.addView(statusText, fullWidth());
        standingsText = text(""); root.addView(standingsText, fullWidth());
        startButton.setOnClickListener(v -> startTournament());
        resumeButton.setOnClickListener(v -> chooseSavedTournament());
        completedButton.setOnClickListener(v -> chooseCompletedTournaments());
        stopButton.setOnClickListener(v -> stopAndSaveTournament());
        // Horizontal swipes no longer navigate between setup and tournament screens.
        scroll.setOnTouchListener((v, event) -> false);
        setupView = scroll;
        buildPlayUi();
        setContentView(setupView);
    }

    /** Open the expanded Main Board without changing the compact Main/Mirror engine outputs. */
    private void showExpandedMainBoard() {
        if (expandedMainDialog != null && expandedMainDialog.isShowing()) return;
        // Retain the user's normal/double-tap New Board layout across Activity pauses.
        expandedMainReturnScreen = currentTournamentScreen;
        SharedPreferences boardPrefs = getPreferences(MODE_PRIVATE);
        expandedWhitePanelBelowBoard = boardPrefs.getBoolean("tournamentNewBoardDoubleTap", false);
        tournamentUiFlipped = boardPrefs.getBoolean("tournamentUiFlipped", tournamentUiFlipped);
        expandedPanelsSwapped = boardPrefs.getBoolean("tournamentExpandedPanelsSwapped", false);
        expandedBottomPanelWhite = boardPrefs.getBoolean("tournamentExpandedBottomPanelWhite", !tournamentUiFlipped);
        // Avoid carrying the temporary double-tap icon order into normal New Board.
        // Legacy shared keys are only a fallback for existing double-tap users.
        restoreExpandedPanelLogoPreferences(boardPrefs);
        Dialog dialog = new Dialog(this);
        expandedMainDialog = dialog;

        ScrollView pageScroll = new ScrollView(this);
        expandedMainScroll = pageScroll;
        pageScroll.setFillViewport(true);
        pageScroll.setVerticalScrollBarEnabled(false);
        LinearLayout root = new LinearLayout(this);
        expandedRoot = root;
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, dp(4), 0, dp(4));
        root.setBackgroundColor(0xFF202020);
        pageScroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        // The expanded board has two independent output cards side-by-side.
        // Each card keeps the existing output styling and puts its engine icon/name first.
        LinearLayout enginePanelsRow = new LinearLayout(this);
        expandedPanelsRow = enginePanelsRow;
        enginePanelsRow.setOrientation(LinearLayout.HORIZONTAL);
        enginePanelsRow.setGravity(android.view.Gravity.TOP);
        expandedBlackAnalysisPanel = createExpandedAnalysisPanel(false);
        expandedWhiteAnalysisPanel = createExpandedAnalysisPanel(true);
        // expandedBottomPanelWhite was restored from preferences above; do not reset it here.
        LinearLayout.LayoutParams blackPanelParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        blackPanelParams.rightMargin = dp(2);
        LinearLayout.LayoutParams whitePanelParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        whitePanelParams.leftMargin = dp(2);
        enginePanelsRow.addView(expandedBlackAnalysisPanel, blackPanelParams);
        enginePanelsRow.addView(expandedWhiteAnalysisPanel, whitePanelParams);
        root.addView(enginePanelsRow, fullWidth());
        expandedBlackPlayerRow = createExpandedPlayerRow(false);
        expandedWhitePlayerRow = createExpandedPlayerRow(true);
        // Player rows are inserted around the board by applyExpandedPlayerRowOrder().

        // Measure the board from the full content width, not a percentage of screen
        // height. It therefore uses the remaining screen width instead of being
        // needlessly reduced on tall portrait displays.
        FrameLayout boardFrame = new FrameLayout(this) {
            @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int width = MeasureSpec.getSize(widthMeasureSpec);
                int size = Math.max(0, width);
                super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY));
            }
        };
        expandedBoardFrame = boardFrame;
        expandedMainBoard = new TournamentChessBoard(this);
        expandedMainBoard.setFlipped(tournamentBoard != null && tournamentBoard.flipped);
        expandedMainBoard.highlightLastMove = true;
        expandedMainBoard.fillLastMoveUnderPieces = true;
        expandedMainBoard.setPosition(new Position(latestPosition == null ? standardStartPosition() : latestPosition));
        expandedMainBoard.setLastMove(moveAtPly(mirrorPositions, Math.max(0, mirrorPositions.size() - 1)));
        expandedMainBoard.setLongClickable(true);
        expandedMainBoard.setOnLongClickListener(v -> { toggleExpandedBoardFocus(); return true; });
        expandedMainBoard.setOnTouchListener((v, event) -> false);
        boardFrame.addView(expandedMainBoard, new FrameLayout.LayoutParams(-1, -1, android.view.Gravity.CENTER));
        LinearLayout.LayoutParams boardParams = new LinearLayout.LayoutParams(-1, -2);
        boardParams.topMargin = dp(5);
        boardParams.bottomMargin = dp(5);
        root.addView(boardFrame, boardParams);
        // In normal mode the player rows remain attached to the board. In stacked
        // double-tap mode their clock/time values are shown inside the engine panels.
        applyExpandedPlayerRowOrder(!expandedWhitePanelBelowBoard);

        expandedMovesContainer = new LinearLayout(this);
        LinearLayout movesSection = (LinearLayout)expandedMovesContainer;
        movesSection.setOrientation(LinearLayout.VERTICAL);
        movesSection.setPadding(dp(5), dp(5), dp(5), dp(8));
        LinearLayout movesToolbar = new LinearLayout(this);
        movesToolbar.setOrientation(LinearLayout.HORIZONTAL);
        movesToolbar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView movesTitle = text("MOVES");
        movesTitle.setTextSize(14);
        movesTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        movesTitle.setTextColor(0xFFBFCBD2);
        movesTitle.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.LEFT);
        movesTitle.setPadding(dp(5), dp(5), dp(5), dp(5));
        movesToolbar.addView(movesTitle, new LinearLayout.LayoutParams(0, dp(44), 1.0f));
        expandedFlipBoardButton = new Button(this);
        expandedFlipBoardButton.setText("🔁");
        expandedFlipBoardButton.setTextSize(19);
        expandedFlipBoardButton.setAllCaps(false);
        expandedFlipBoardButton.setMinHeight(0);
        expandedFlipBoardButton.setMinimumHeight(dp(40));
        expandedFlipBoardButton.setPadding(dp(5), 0, dp(5), 0);
        movesToolbar.addView(expandedFlipBoardButton, new LinearLayout.LayoutParams(dp(48), dp(44)));
        expandedFlipBoardButton.setOnClickListener(v -> toggleTournamentFlip());
        expandedPlayPauseButton = new Button(this);
        expandedPlayPauseButton.setText("▶️");
        expandedPlayPauseButton.setTextSize(19);
        expandedPlayPauseButton.setAllCaps(false);
        expandedPlayPauseButton.setMinHeight(0);
        expandedPlayPauseButton.setMinimumHeight(dp(40));
        expandedPlayPauseButton.setPadding(dp(5), 0, dp(5), 0);
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(dp(52), dp(44));
        playParams.leftMargin = dp(3);
        movesToolbar.addView(expandedPlayPauseButton, playParams);
        expandedPlayPauseButton.setOnClickListener(v -> togglePauseResume());
        movesSection.addView(movesToolbar, fullWidth());
        updateExpandedBoardControls();
        expandedMovesRows = new LinearLayout(this);
        expandedMovesRows.setOrientation(LinearLayout.VERTICAL);
        ScrollView movesScroll = createIndependentVerticalScrollView();
        movesScroll.setFillViewport(false);
        movesScroll.setVerticalScrollBarEnabled(true);
        movesScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        movesScroll.addView(expandedMovesRows, new ScrollView.LayoutParams(-1, -2));
        movesSection.addView(movesScroll, new LinearLayout.LayoutParams(-1, dp(180)));
        root.addView(expandedMovesContainer, fullWidth());
        applyExpandedPanelRowOrder();

        dialog.setContentView(pageScroll);
        dialog.setOnDismissListener(d -> {
            if (expandedMovesRefreshRunnable != null) {
                uiHandler.removeCallbacks(expandedMovesRefreshRunnable);
                expandedMovesRefreshRunnable = null;
            }
            expandedMainDialog = null; expandedMainScroll = null; expandedMainBoard = null;
            expandedBlackPlayerRow = expandedWhitePlayerRow = null;
            expandedWhiteName = expandedBlackName = expandedWhiteAnalysis = expandedBlackAnalysis = null;
            expandedWhiteEngineTitle = expandedBlackEngineTitle = null;
            expandedWhitePv = expandedBlackPv = null;
            expandedWhiteClock = expandedBlackClock = expandedWhiteMoveTime = expandedBlackMoveTime = null;
            expandedWhiteTiming = expandedBlackTiming = null;
            expandedWhiteTimingMove = expandedBlackTimingMove = null;
            expandedWhiteTimingGroup = expandedBlackTimingGroup = null;
            expandedFlipBoardButton = expandedPlayPauseButton = null;
            expandedBlackLogo = expandedWhiteLogo = null;
            expandedBlackAnalysisPanel = expandedWhiteAnalysisPanel = expandedMovesContainer = null;
            expandedRoot = expandedPanelsRow = expandedBlackInfo = expandedWhiteInfo = null;
            expandedBoardFrame = null;
            // Preserve panel swap and bottom-side selection. Resetting them here made
            // the restored New Board silently change layout after visiting Main Board.
            // Android may dismiss a Dialog while the Activity is pausing or being
            // destroyed. onPause has already saved selection=New Board in that case;
            // do not overwrite it with the dialog's return screen. A user dismissal
            // while the Activity remains active should return to the parent board.
            if (!activityLeaving && !isFinishing())
                saveBoardSelectionState(expandedMainReturnScreen);
            saveExpandedPanelLayoutPreferences();
            expandedMovesRows = null; expandedMovesSignature = "";
        });
        dialog.show();
        saveBoardSelectionState(2);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.black);
        }
        // Re-apply the persisted logo/info order after rebuilding the dialog views.
        // Without this call, newly-created panels kept the factory order (logo above the name).
        applyExpandedAnalysisPanelLayouts();
        updateExpandedMainBoard();
        updateExpandedClockDisplay();
    }

    private View createExpandedAnalysisPanel(boolean white) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(9), dp(5), dp(9), dp(5));
        panel.setBackgroundColor(white ? 0xFF34302B : 0xFF29323B);
        panel.setGravity(android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL);
        ImageView logo = createExpandedEngineLogo(white);
        if (white) expandedWhiteLogo = logo; else expandedBlackLogo = logo;
        TextView label = text((white ? "White: " : "Black: ") + (white ? currentWhiteName : currentBlackName));
        label.setTextSize(16);
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        label.setTextColor(white ? 0xFFFFD9A8 : 0xFFB9D9F4);
        label.setGravity(android.view.Gravity.CENTER);
        if (white) expandedWhiteEngineTitle = label; else expandedBlackEngineTitle = label;

        // In stacked/double-tap mode, put the game clock and last move time in
        // a right-aligned column beside the engine metrics, matching the
        // Onslaught-vs-Lizard reference. The normal player rows remain unchanged.
        LinearLayout timingGroup = new LinearLayout(this);
        timingGroup.setOrientation(LinearLayout.VERTICAL);
        timingGroup.setGravity(android.view.Gravity.RIGHT | android.view.Gravity.CENTER_VERTICAL);
        timingGroup.setPadding(dp(4), 0, dp(2), 0);
        timingGroup.setVisibility(View.GONE);
        TextView timingClock = text("—");
        timingClock.setTextSize(21);
        timingClock.setTypeface(null, android.graphics.Typeface.BOLD);
        timingClock.setTextColor(0xFFE0E5E8);
        timingClock.setGravity(android.view.Gravity.RIGHT | android.view.Gravity.CENTER_VERTICAL);
        timingClock.setIncludeFontPadding(false);
        timingClock.setSingleLine(true);
        TextView timingMove = text("—");
        timingMove.setTextSize(15);
        timingMove.setTextColor(0xFFC4C9CD);
        timingMove.setGravity(android.view.Gravity.RIGHT | android.view.Gravity.CENTER_VERTICAL);
        timingMove.setIncludeFontPadding(false);
        timingMove.setSingleLine(true);
        timingGroup.addView(timingClock, new LinearLayout.LayoutParams(-2, -2));
        timingGroup.addView(timingMove, new LinearLayout.LayoutParams(-2, -2));
        if (white) {
            expandedWhiteTiming = timingClock;
            expandedWhiteTimingMove = timingMove;
            expandedWhiteTimingGroup = timingGroup;
        } else {
            expandedBlackTiming = timingClock;
            expandedBlackTimingMove = timingMove;
            expandedBlackTimingGroup = timingGroup;
        }

        TextView metrics = text(formatEngineMetrics(white ? lastWhiteAnalysis : lastBlackAnalysis));
        metrics.setTextSize(18);
        metrics.setTextColor(0xFFFFFFFF);
        metrics.setGravity(android.view.Gravity.TOP | android.view.Gravity.LEFT);
        metrics.setMaxLines(4);
        metrics.setIncludeFontPadding(false);
        metrics.setLineSpacing(dp(1), 1.0f);
        if (white) expandedWhiteAnalysis = metrics; else expandedBlackAnalysis = metrics;

        LinearLayout metricsRow = new LinearLayout(this);
        metricsRow.setOrientation(LinearLayout.HORIZONTAL);
        metricsRow.setGravity(android.view.Gravity.TOP | android.view.Gravity.CENTER_VERTICAL);
        metricsRow.addView(metrics, new LinearLayout.LayoutParams(0, -2, 1.0f));
        metricsRow.addView(timingGroup, new LinearLayout.LayoutParams(-2, -2));

        HorizontalScrollView pv = createSingleLinePv(white ? lastWhiteAnalysis : lastBlackAnalysis);
        pv.setBackgroundColor(white ? 0xFF40382F : 0xFF354450);
        if (white) expandedWhitePv = pv; else expandedBlackPv = pv;

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.addView(label, fullWidth());
        info.addView(metricsRow, fullWidth());
        info.addView(pv, new LinearLayout.LayoutParams(-1, dp(38)));
        if (white) expandedWhiteInfo = info; else expandedBlackInfo = info;

        panel.addView(logo, new LinearLayout.LayoutParams(dp(76), dp(66)));
        panel.addView(info, fullWidth());
        attachExpandedAnalysisPanelGestures(panel, white);
        attachExpandedAnalysisPanelGestures(logo, white);
        attachExpandedAnalysisPanelGestures(label, white);
        attachExpandedAnalysisPanelGestures(metrics, white);
        attachExpandedAnalysisPanelGestures(pv, white);
        if (pv.getChildAt(0) != null) attachExpandedAnalysisPanelGestures(pv.getChildAt(0), white);
        return panel;
    }

    private void attachExpandedAnalysisPanelGestures(View target, boolean white) {
        if (target == null) return;
        GestureDetector detector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(android.view.MotionEvent event) { return true; }
            @Override public boolean onDoubleTap(android.view.MotionEvent event) {
                toggleExpandedWhitePanelPlacement();
                return true;
            }
        });
        target.setClickable(true);
        target.setLongClickable(true);
        target.setOnTouchListener((view, event) -> {
            detector.onTouchEvent(event);
            return false;
        });
        target.setOnLongClickListener(v -> {
            showExpandedAnalysisLongPressMenu(white);
            return true;
        });
    }

    private void showExpandedAnalysisLongPressMenu(boolean white) {
        String engineName = white ? currentWhiteName : currentBlackName;
        String analysis = white ? lastWhiteAnalysis : lastBlackAnalysis;
        ArrayList<String> actions = new ArrayList<>();
        actions.add("Copy engine output");
        actions.add("Switch icon / information position");
        if (!expandedWhitePanelBelowBoard) actions.add("Swap White / Black panel positions");
        new AlertDialog.Builder(this).setTitle((white ? "White: " : "Black: ") + engineName)
                .setItems(actions.toArray(new String[0]), (dialog, which) -> {
                    if (which == 0) copyTournamentEngineOutput(white, analysis);
                    else if (which == 1) {
                        if (white) expandedWhiteLogoAfterInfo = !expandedWhiteLogoAfterInfo;
                        else expandedBlackLogoAfterInfo = !expandedBlackLogoAfterInfo;
                        saveExpandedPanelLayoutPreferences();
                        applyExpandedAnalysisPanelLayouts();
                    } else if (which == 2 && !expandedWhitePanelBelowBoard) {
                        expandedPanelsSwapped = !expandedPanelsSwapped;
                        saveExpandedPanelLayoutPreferences();
                        applyExpandedPanelRowOrder();
                    }
                }).show();
    }

    private void restoreExpandedPanelLogoPreferences(SharedPreferences prefs) {
        if (expandedWhitePanelBelowBoard) {
            expandedBlackLogoAfterInfo = prefs.getBoolean("tournamentExpandedBlackLogoAfterInfoDoubleTap",
                    prefs.getBoolean("tournamentExpandedBlackLogoAfterInfo", true));
            expandedWhiteLogoAfterInfo = prefs.getBoolean("tournamentExpandedWhiteLogoAfterInfoDoubleTap",
                    prefs.getBoolean("tournamentExpandedWhiteLogoAfterInfo", true));
        } else {
            // Normal New Board defaults to the engine icon above its information.
            expandedBlackLogoAfterInfo = prefs.getBoolean("tournamentExpandedBlackLogoAfterInfoNormal", false);
            expandedWhiteLogoAfterInfo = prefs.getBoolean("tournamentExpandedWhiteLogoAfterInfoNormal", false);
        }
    }

    private void toggleExpandedWhitePanelPlacement() {
        if (expandedRoot == null || expandedPanelsRow == null || expandedMovesContainer == null ||
                expandedBoardFrame == null) return;
        // Save the layout being left, then restore the destination mode's own
        // icon order. This prevents normal New Board's top-icon layout from
        // overwriting the separately saved double-tap arrangement (and vice versa).
        saveExpandedPanelLayoutPreferences();
        expandedWhitePanelBelowBoard = !expandedWhitePanelBelowBoard;
        saveBoardSelectionState(2);
        if (expandedWhitePanelBelowBoard) expandedBottomPanelWhite = !tournamentUiFlipped;
        restoreExpandedPanelLogoPreferences(getPreferences(MODE_PRIVATE));
        saveExpandedPanelLayoutPreferences();
        applyExpandedAnalysisPanelLayouts();
    }

    private void applyExpandedAnalysisPanelLayouts() {
        if (expandedPanelsRow == null || expandedWhiteAnalysisPanel == null || expandedBlackAnalysisPanel == null) return;
        configureExpandedAnalysisPanel((LinearLayout)expandedBlackAnalysisPanel, expandedBlackLogo,
                expandedBlackInfo, expandedBlackPv, expandedWhitePanelBelowBoard, expandedBlackLogoAfterInfo);
        configureExpandedAnalysisPanel((LinearLayout)expandedWhiteAnalysisPanel, expandedWhiteLogo,
                expandedWhiteInfo, expandedWhitePv, expandedWhitePanelBelowBoard, expandedWhiteLogoAfterInfo);
        // Normal layout keeps the player rows attached to the board. The
        // stacked double-tap layout moves their clock/time into the engine cards.
        boolean stackedDoubleTapMode = expandedWhitePanelBelowBoard;
        if (expandedWhiteTimingGroup != null) expandedWhiteTimingGroup.setVisibility(stackedDoubleTapMode ? View.VISIBLE : View.GONE);
        if (expandedBlackTimingGroup != null) expandedBlackTimingGroup.setVisibility(stackedDoubleTapMode ? View.VISIBLE : View.GONE);
        applyExpandedPlayerRowOrder(!stackedDoubleTapMode);
        applyExpandedPanelRowOrder();
        expandedRoot.requestLayout();
    }

    private void configureExpandedAnalysisPanel(LinearLayout panel, ImageView logo, LinearLayout info,
                                                HorizontalScrollView variation, boolean horizontal, boolean logoAfterInfo) {
        if (panel == null || logo == null || info == null || variation == null) return;
        if (panel.getParent() instanceof ViewGroup) ((ViewGroup)panel.getParent()).removeView(panel);
        panel.removeAllViews();
        if (info.getParent() instanceof ViewGroup) ((ViewGroup)info.getParent()).removeView(info);
        if (logo.getParent() instanceof ViewGroup) ((ViewGroup)logo.getParent()).removeView(logo);
        if (variation.getParent() instanceof ViewGroup && variation.getParent() != info)
            ((ViewGroup)variation.getParent()).removeView(variation);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(android.view.Gravity.TOP);

        TextView engineTitle = info.getChildAt(0) instanceof TextView ? (TextView)info.getChildAt(0) : null;
        LinearLayout metricsRow = null;
        for (int i = 1; i < info.getChildCount(); i++) {
            View child = info.getChildAt(i);
            if (child instanceof LinearLayout) { metricsRow = (LinearLayout)child; break; }
        }
        if (horizontal) {
            // Expanded/double-tap layout: engine info and clock occupy a stable
            // left block; the large icon stays beside it and PV keeps the full row.
            if (variation.getParent() == info) info.removeView(variation);
            if (engineTitle != null) engineTitle.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.LEFT);
            LinearLayout contentRow = new LinearLayout(this);
            contentRow.setOrientation(LinearLayout.HORIZONTAL);
            contentRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
            LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(126), dp(148));
            logoParams.gravity = android.view.Gravity.CENTER_VERTICAL;
            if (logoAfterInfo) {
                contentRow.addView(info, infoParams);
                logoParams.leftMargin = dp(6);
                contentRow.addView(logo, logoParams);
            } else {
                logoParams.rightMargin = dp(6);
                contentRow.addView(logo, logoParams);
                contentRow.addView(info, infoParams);
            }
            panel.addView(contentRow, fullWidth());
            panel.addView(variation, new LinearLayout.LayoutParams(-1, dp(38)));
        } else {
            if (variation.getParent() != info)
                info.addView(variation, new LinearLayout.LayoutParams(-1, dp(38)));
            if (engineTitle != null) engineTitle.setGravity(android.view.Gravity.CENTER);
            LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(76), dp(66));
            logoParams.gravity = android.view.Gravity.CENTER;
            if (logoAfterInfo) {
                panel.addView(info, fullWidth());
                panel.addView(logo, logoParams);
            } else {
                panel.addView(logo, logoParams);
                panel.addView(info, fullWidth());
            }
        }
    }

    /** Keep player rows attached to the correctly oriented side of the board. */
    private void applyExpandedPlayerRowOrder(boolean showRows) {
        if (expandedRoot == null || expandedBoardFrame == null ||
                expandedBlackPlayerRow == null || expandedWhitePlayerRow == null) return;
        if (expandedBlackPlayerRow.getParent() instanceof ViewGroup)
            ((ViewGroup)expandedBlackPlayerRow.getParent()).removeView(expandedBlackPlayerRow);
        if (expandedWhitePlayerRow.getParent() instanceof ViewGroup)
            ((ViewGroup)expandedWhitePlayerRow.getParent()).removeView(expandedWhitePlayerRow);
        if (!showRows) return;
        int boardIndex = expandedRoot.indexOfChild(expandedBoardFrame);
        if (boardIndex < 0) return;
        View topRow = tournamentUiFlipped ? expandedWhitePlayerRow : expandedBlackPlayerRow;
        View bottomRow = tournamentUiFlipped ? expandedBlackPlayerRow : expandedWhitePlayerRow;
        expandedRoot.addView(topRow, boardIndex, fullWidth());
        int currentBoardIndex = expandedRoot.indexOfChild(expandedBoardFrame);
        expandedRoot.addView(bottomRow, currentBoardIndex + 1, fullWidth());
    }

    /** Place each output with its engine side; in double-tap mode one panel is above and one below the board. */
    private void applyExpandedPanelRowOrder() {
        if (expandedRoot == null || expandedPanelsRow == null || expandedBlackAnalysisPanel == null ||
                expandedWhiteAnalysisPanel == null || expandedBoardFrame == null || expandedMovesContainer == null) return;
        if (expandedBlackAnalysisPanel.getParent() instanceof ViewGroup)
            ((ViewGroup)expandedBlackAnalysisPanel.getParent()).removeView(expandedBlackAnalysisPanel);
        if (expandedWhiteAnalysisPanel.getParent() instanceof ViewGroup)
            ((ViewGroup)expandedWhiteAnalysisPanel.getParent()).removeView(expandedWhiteAnalysisPanel);
        expandedPanelsRow.removeAllViews();
        if (expandedWhitePanelBelowBoard) {
            View topPanel = expandedBottomPanelWhite ? expandedBlackAnalysisPanel : expandedWhiteAnalysisPanel;
            View bottomPanel = expandedBottomPanelWhite ? expandedWhiteAnalysisPanel : expandedBlackAnalysisPanel;
            expandedPanelsRow.addView(topPanel, fullWidth());
            int movesIndex = expandedRoot.indexOfChild(expandedMovesContainer);
            if (movesIndex < 0) movesIndex = expandedRoot.getChildCount();
            expandedRoot.addView(bottomPanel, movesIndex, new LinearLayout.LayoutParams(-1, -2));
            expandedRoot.requestLayout();
            return;
        }
        View left = expandedPanelsSwapped ? expandedWhiteAnalysisPanel : expandedBlackAnalysisPanel;
        View right = expandedPanelsSwapped ? expandedBlackAnalysisPanel : expandedWhiteAnalysisPanel;
        LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        leftParams.rightMargin = dp(2);
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(0, -2, 1.0f);
        rightParams.leftMargin = dp(2);
        expandedPanelsRow.addView(left, leftParams);
        expandedPanelsRow.addView(right, rightParams);
        expandedRoot.requestLayout();
    }

    private void updateExpandedBoardControls() {
        if (expandedPlayPauseButton == null) return;
        boolean running = runner != null && !runner.isFinished() && !runner.isStopped() && !runner.isPaused();
        expandedPlayPauseButton.setText(running ? "⏸️" : "▶️");
    }

    private void copyTournamentEngineOutput(boolean white, String analysis) {
        copyNamedTournamentEngineOutput(white, white ? currentWhiteName : currentBlackName, analysis);
    }

    private void copyNamedTournamentEngineOutput(boolean white, String engineName, String analysis) {
        String role = white ? "White" : "Black";
        String copied = role + ": " + (engineName == null ? "Engine" : engineName) + "\n" +
                (analysis == null || analysis.trim().isEmpty() ? "No engine output available." : analysis.trim());
        ClipboardManager clipboard = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Engine output", copied));
        Toast.makeText(this, "Engine output copied", Toast.LENGTH_SHORT).show();
    }

    private LinearLayout createExpandedPlayerRow(boolean white) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(3), dp(8), dp(3));
        TextView clock = text(white ? (whiteClockText == null ? "—" : whiteClockText.getText().toString())
                : (blackClockText == null ? "—" : blackClockText.getText().toString()));
        clock.setTextSize(21);
        clock.setTypeface(null, android.graphics.Typeface.BOLD);
        clock.setGravity(android.view.Gravity.LEFT | android.view.Gravity.CENTER_VERTICAL);
        TextView name = text(white ? currentWhiteName : currentBlackName);
        name.setTextSize(19);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        name.setGravity(android.view.Gravity.CENTER);
        TextView moveTime = text(white ? (whiteMoveTimeText == null ? "—" : whiteMoveTimeText.getText().toString())
                : (blackMoveTimeText == null ? "—" : blackMoveTimeText.getText().toString()));
        moveTime.setTextSize(15);
        moveTime.setGravity(android.view.Gravity.RIGHT | android.view.Gravity.CENTER_VERTICAL);
        if (white) {
            expandedWhiteClock = clock; expandedWhiteName = name; expandedWhiteMoveTime = moveTime;
        } else {
            expandedBlackClock = clock; expandedBlackName = name; expandedBlackMoveTime = moveTime;
        }
        row.addView(clock, new LinearLayout.LayoutParams(0, -2, 1.0f));
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1.7f));
        row.addView(moveTime, new LinearLayout.LayoutParams(0, -2, 1.0f));
        return row;
    }

    private ImageView createExpandedEngineLogo(boolean white) {
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        String id = white ? currentWhiteEngineId : currentBlackEngineId;
        String name = white ? currentWhiteName : currentBlackName;
        // Use the same configured engine-icon lookup as the Mirror Board. Never
        // manufacture a giant first-letter tile (the old C/D placeholders).
        Bitmap bitmap = mirrorEngineIcon(id, name);
        if (bitmap != null) image.setImageDrawable(new BitmapDrawable(getResources(), bitmap));
        else image.setVisibility(View.GONE);
        image.setContentDescription((name == null ? "Engine" : name) + " icon");
        return image;
    }

    private HorizontalScrollView createSingleLinePv(String analysis) {
        TextView pv = text(extractPrincipalVariationLine(analysis));
        pv.setTextSize(17);
        pv.setTextColor(0xFFE0E0E0);
        pv.setSingleLine(true);
        pv.setHorizontallyScrolling(true);
        pv.setIncludeFontPadding(true);
        pv.setPadding(dp(4), dp(2), dp(4), dp(3));
        pv.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.LEFT);
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(false);
        scroll.addView(pv, new HorizontalScrollView.LayoutParams(-2, dp(34)));
        return scroll;
    }

    private String extractPrincipalVariationLine(String analysis) {
        if (analysis == null || analysis.trim().isEmpty()) return "—";
        String[] lines = analysis.split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.regionMatches(true, 0, "Engine WDL:", 0, 11)) continue;
            line = line.replaceFirst("(?i)^PV\\s*\\d*\\s*", "");
            line = line.replaceFirst("^\\d+\\.{1,3}\\s*", "");
            if (line.matches("(?i).*(?:[a-h][1-8]|O-O).*")) return line;
        }
        return "—";
    }

    private String formatExpandedPrincipalVariationLine(String analysis, boolean white) {
        String variation = extractPrincipalVariationLine(analysis);
        if (variation.equals("—") || variation.equals("Book.")) return variation;
        int moveNumber = latestPosition == null ? 1 : Math.max(1, latestPosition.fullMoveCounter);
        return moveNumber + (white ? "." : "...") + variation;
    }

    private String formatEngineMetrics(String analysis) {
        // Keep each label and its value as one unbreakable unit. In the narrow
        // side-by-side New Board panels Android could otherwise wrap only the
        // Depth value onto the next visual line even when the row had room.
        final String labelValueSpace = "\u00A0";
        return "Eval:" + labelValueSpace + analysisField(analysis, "Eval") +
                "\nDepth:" + labelValueSpace + combinedDepthDisplay(analysis) +
                "\nNPS:" + labelValueSpace + analysisField(analysis, "NPS") +
                "\nNodes:" + labelValueSpace + analysisField(analysis, "Nodes");
    }

    private void toggleExpandedBoardFocus() {
        if (expandedBlackAnalysisPanel == null || expandedWhiteAnalysisPanel == null || expandedMovesContainer == null) return;
        boolean enteringFocus = expandedBlackAnalysisPanel.getVisibility() == View.VISIBLE;
        int visibility = enteringFocus ? View.GONE : View.VISIBLE;
        expandedBlackAnalysisPanel.setVisibility(visibility);
        expandedWhiteAnalysisPanel.setVisibility(visibility);
        expandedMovesContainer.setVisibility(visibility);
        // Preserve the active layout: normal mode keeps player rows by the board,
        // while stacked double-tap mode keeps clock/time inside the engine cards.
        applyExpandedPlayerRowOrder(!expandedWhitePanelBelowBoard);
        Toast.makeText(this, enteringFocus ? "Board focus · long-press to restore engine panels" : "Engine panels restored", Toast.LENGTH_SHORT).show();
    }

    private void updateExpandedEngineMetrics() {
        if (expandedWhiteAnalysis != null) expandedWhiteAnalysis.setText(formatEngineMetrics(lastWhiteAnalysis));
        if (expandedBlackAnalysis != null) expandedBlackAnalysis.setText(formatEngineMetrics(lastBlackAnalysis));
        if (expandedWhitePv != null && expandedWhitePv.getChildCount() > 0 && expandedWhitePv.getChildAt(0) instanceof TextView)
            ((TextView) expandedWhitePv.getChildAt(0)).setText(formatExpandedPrincipalVariationLine(lastWhiteAnalysis, true));
        if (expandedBlackPv != null && expandedBlackPv.getChildCount() > 0 && expandedBlackPv.getChildAt(0) instanceof TextView)
            ((TextView) expandedBlackPv.getChildAt(0)).setText(formatExpandedPrincipalVariationLine(lastBlackAnalysis, false));
    }

    private void applyTournamentVariationHints() {
        ArrayList<Move> visibleHints = new ArrayList<>();
        boolean livePosition = !mirrorReplayMode && !mirrorBranchMode && mirrorPly < 0;
        boolean arrowsEnabled = showTournamentArrowsCheck == null || showTournamentArrowsCheck.isChecked();
        // Preserve DroidFish's existing global thinking-arrows switch too.
        String globalArrowSetting = android.preference.PreferenceManager
                .getDefaultSharedPreferences(this).getString("thinkingArrows", "4");
        arrowsEnabled = arrowsEnabled && !"0".equals(globalArrowSetting);
        if (arrowsEnabled && livePosition && latestPosition != null &&
                tournamentVariationPositionHash == latestPosition.zobristHash()) {
            int limit = clampArrowCount(tournamentArrowCount);
            for (Move hint : tournamentVariationHints) {
                if (hint == null) continue;
                visibleHints.add(hint);
                if (visibleHints.size() >= limit) break;
            }
        }
        List<Move> hintsOrNull = visibleHints.isEmpty() ? null : visibleHints;
        if (tournamentBoard != null) tournamentBoard.setMoveHints(hintsOrNull);
        if (expandedMainBoard != null) expandedMainBoard.setMoveHints(hintsOrNull);
    }

    private void updateExpandedMoveHints() {
        if (expandedMainBoard == null) return;
        applyTournamentVariationHints();
    }

    private void updateExpandedMainBoard() {
        updateExpandedMainBoard(false);
    }

    private void updateExpandedMainBoard(boolean deferMoveListRefresh) {
        if (expandedMainDialog == null || !expandedMainDialog.isShowing()) return;
        if (expandedWhiteName != null) expandedWhiteName.setText(appendResultScore(currentWhiteName, currentBoardWhiteScore));
        if (expandedBlackName != null) expandedBlackName.setText(appendResultScore(currentBlackName, currentBoardBlackScore));
        if (expandedWhiteEngineTitle != null) expandedWhiteEngineTitle.setText("White: " + currentWhiteName);
        if (expandedBlackEngineTitle != null) expandedBlackEngineTitle.setText("Black: " + currentBlackName);
        if (expandedWhiteName != null) expandedWhiteName.setTextColor(currentWhiteToMove ? 0xFFFFFFFF : 0xFF9A9A9A);
        if (expandedBlackName != null) expandedBlackName.setTextColor(currentWhiteToMove ? 0xFF9A9A9A : 0xFFFFFFFF);
        updateExpandedEngineMetrics();
        updateExpandedClockDisplay();
        if (expandedBlackLogo != null) updateExpandedEngineLogo(expandedBlackLogo, false);
        if (expandedWhiteLogo != null) updateExpandedEngineLogo(expandedWhiteLogo, true);
        if (expandedMainBoard != null) {
            boolean useSelectedHistory = mirrorReplayMode || mirrorBranchMode || mirrorPly >= 0;
            ArrayList<Position> positions = useSelectedHistory ? currentMirrorPositions() : null;
            int selectedPly = positions == null || positions.isEmpty() ? 0 :
                    Math.max(0, Math.min(mirrorDisplayedPly(), positions.size() - 1));
            Position displayPosition = positions != null && !positions.isEmpty()
                    ? positions.get(selectedPly) : latestPosition;
            if (displayPosition != null) {
                expandedMainBoard.setPosition(new Position(displayPosition));
                expandedMainBoard.setLastMove(positions != null
                        ? moveAtPly(positions, selectedPly)
                        : moveAtPly(mirrorPositions, Math.max(0, mirrorPositions.size() - 1)));
            }
            updateExpandedMoveHints();
        }
        if (deferMoveListRefresh) scheduleExpandedMoveListRefreshAfterMove();
        else refreshExpandedMoveList();
    }

    private void updateExpandedEngineLogo(ImageView image, boolean white) {
        if (image == null) return;
        Bitmap bitmap = mirrorEngineIcon(white ? currentWhiteEngineId : currentBlackEngineId,
                white ? currentWhiteName : currentBlackName);
        if (bitmap == null) {
            image.setImageDrawable(null);
            image.setVisibility(View.GONE);
        } else {
            image.setImageDrawable(new BitmapDrawable(getResources(), bitmap));
            image.setVisibility(View.VISIBLE);
        }
    }

    /** One animation setting is shared by the main, expanded, Mirror and review boards. */
    private int moveAnimationDurationMs() {
        android.content.SharedPreferences preferences = getPreferences(MODE_PRIVATE);
        int duration = preferences.getInt("tournamentMoveAnimationMs", 320);
        // 180 ms was the old default, not a deliberate user selection in older builds.
        // Upgrade that default so a move remains visible across several display frames.
        if (duration == 180) {
            duration = 320;
            preferences.edit().putInt("tournamentMoveAnimationMs", duration).apply();
        }
        return Math.max(0, Math.min(2000, duration));
    }

    /** Rebuild the expanded move list after the active board animation completes. */
    private void scheduleExpandedMoveListRefreshAfterMove() {
        if (expandedMovesRefreshRunnable != null)
            uiHandler.removeCallbacks(expandedMovesRefreshRunnable);
        Runnable refresh = () -> {
            expandedMovesRefreshRunnable = null;
            if (expandedMainDialog != null && expandedMainDialog.isShowing())
                refreshExpandedMoveList();
        };
        expandedMovesRefreshRunnable = refresh;
        int animationMs = moveAnimationDurationMs();
        if (animationMs <= 0) {
            expandedMovesRefreshRunnable = null;
            refresh.run();
        } else {
            uiHandler.postDelayed(refresh, animationMs + 16L);
        }
    }

    /** Render the same numbered two-column move/comment rows used by Mirror → Moves. */
    private void refreshExpandedMoveList() {
        if (expandedMovesRows == null) return;
        ArrayList<String> comments = new ArrayList<>();
        ArrayList<String> sans = currentMirrorSanMoves(comments);
        int displayPly = mirrorDisplayedPly();
        String lastComment = comments.isEmpty() ? "" : comments.get(comments.size() - 1);
        String signature = sans.size() + ":" + displayPly + ":" + lastComment.hashCode() + ":" +
                (mirrorReplayMode ? mirrorReplayPairingIndex : -1) + ":" + (mirrorBranchMode ? mirrorBranchBasePly : -1);
        if (signature.equals(expandedMovesSignature)) return;
        expandedMovesSignature = signature;
        expandedMovesRows.removeAllViews();
        if (sans.isEmpty()) {
            TextView empty = text("No moves played yet.");
            empty.setTextColor(0xFFBFCBD2);
            empty.setPadding(dp(10), dp(14), dp(10), dp(14));
            expandedMovesRows.addView(empty, fullWidth());
            return;
        }
        ArrayList<Position> positions = currentMirrorPositions();
        for (int firstPly = 0; firstPly < sans.size(); firstPly += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int side = 0; side < 2; side++) {
                final int ply = firstPly + side + 1;
                TextView cell = text("");
                cell.setTextSize(14);
                cell.setTextColor(0xFFE2E8EC);
                cell.setPadding(dp(8), dp(8), dp(8), dp(8));
                cell.setGravity(android.view.Gravity.TOP | android.view.Gravity.LEFT);
                boolean selectedMove = ply == displayPly;
                cell.setBackgroundColor(selectedMove ? 0xFF465A68 : (((firstPly / 2) & 1) == 0 ? 0xFF333333 : 0xFF2D2D2D));
                if (ply <= sans.size()) {
                    Position beforeMove = ply - 1 < positions.size() ? positions.get(ply - 1) : null;
                    int moveNumber = beforeMove == null ? (ply + 1) / 2 : beforeMove.fullMoveCounter;
                    boolean whiteMove = beforeMove == null ? ((ply & 1) == 1) : beforeMove.whiteMove;
                    boolean showMoveNumber = true;
                    if (!whiteMove && ply > 1 && ply - 2 < positions.size()) {
                        Position previousMoveStart = positions.get(ply - 2);
                        showMoveNumber = !(previousMoveStart.whiteMove && previousMoveStart.fullMoveCounter == moveNumber);
                    }
                    // Match Mirror → Moves exactly: the number is immediately
                    // followed by SAN (e.g. 1.e4 | e5) in two aligned columns.
                    String prefix = !showMoveNumber ? "" : (moveNumber + (whiteMove ? "." : "..."));
                    String comment = ply - 1 < comments.size() ? comments.get(ply - 1) : "";
                    String displayComment = mirrorDisplayedMoveComment(comment, whiteMove, ply);
                    android.text.SpannableString styled = new android.text.SpannableString(prefix + sans.get(ply - 1) +
                            (displayComment.isEmpty() ? "" : "\n" + displayComment));
                    int commentAt = (prefix + sans.get(ply - 1)).length();
                    if (!displayComment.isEmpty()) {
                        styled.setSpan(new android.text.style.RelativeSizeSpan(0.78f), commentAt, styled.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        styled.setSpan(new android.text.style.ForegroundColorSpan(0xFFB8C4CC), commentAt, styled.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    cell.setText(styled);
                    cell.setOnClickListener(v -> {
                        navigateMirror(ply);
                        updateExpandedMainBoard();
                    });
                }
                row.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
            }
            expandedMovesRows.addView(row, fullWidth());
        }
    }

    private void updateExpandedClockDisplay() {
        if (expandedMainDialog == null || !expandedMainDialog.isShowing()) return;
        CharSequence whiteClock = whiteClockText == null ? "—" : whiteClockText.getText();
        CharSequence blackClock = blackClockText == null ? "—" : blackClockText.getText();
        CharSequence whiteMove = whiteMoveTimeText == null ? "—" : whiteMoveTimeText.getText();
        CharSequence blackMove = blackMoveTimeText == null ? "—" : blackMoveTimeText.getText();
        if (expandedWhiteClock != null) {
            expandedWhiteClock.setText(whiteClock);
            expandedWhiteClock.setTextColor(currentWhiteToMove ? 0xFFFFFFFF : 0xFF9A9A9A);
        }
        if (expandedBlackClock != null) {
            expandedBlackClock.setText(blackClock);
            expandedBlackClock.setTextColor(currentWhiteToMove ? 0xFF9A9A9A : 0xFFFFFFFF);
        }
        if (expandedWhiteMoveTime != null) expandedWhiteMoveTime.setText(whiteMove);
        if (expandedBlackMoveTime != null) expandedBlackMoveTime.setText(blackMove);
        if (expandedWhiteTiming != null) expandedWhiteTiming.setText(whiteClock);
        if (expandedBlackTiming != null) expandedBlackTiming.setText(blackClock);
        if (expandedWhiteTimingMove != null) expandedWhiteTimingMove.setText(whiteMove);
        if (expandedBlackTimingMove != null) expandedBlackTimingMove.setText(blackMove);
        if (expandedWhiteTiming != null) expandedWhiteTiming.setTextColor(currentWhiteToMove ? 0xFFFFFFFF : 0xFF9A9A9A);
        if (expandedBlackTiming != null) expandedBlackTiming.setTextColor(currentWhiteToMove ? 0xFF9A9A9A : 0xFFFFFFFF);
        updateExpandedBoardControls();
    }

    /** Tournament Main Board only: metrics, native WDL, then a scrollable PV viewport. */
    private LinearLayout createMainAnalysisPanel(TextView metrics, TextView wdl, TextView variation) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(3), dp(2), dp(3), dp(2));
        metrics.setTextSize(17);
        metrics.setTypeface(null, android.graphics.Typeface.BOLD);
        metrics.setTextColor(0xFFFFFFFF);
        metrics.setSingleLine(true);
        metrics.setHorizontallyScrolling(true);
        metrics.setIncludeFontPadding(true);
        metrics.setPadding(0, dp(1), 0, dp(2));
        metrics.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.LEFT);
        HorizontalScrollView metricsScroll = new HorizontalScrollView(this);
        metricsScroll.setFillViewport(false);
        metricsScroll.setHorizontalScrollBarEnabled(false);
        metricsScroll.addView(metrics, new HorizontalScrollView.LayoutParams(-2, -1));
        panel.addView(metricsScroll, new LinearLayout.LayoutParams(-1, dp(31)));
        wdl.setTextSize(12);
        wdl.setTypeface(null, android.graphics.Typeface.BOLD);
        wdl.setTextColor(0xFFD9E2E8);
        wdl.setSingleLine(true);
        wdl.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.LEFT);
        wdl.setPadding(0, 0, 0, 0);
        wdl.setVisibility(View.GONE);
        panel.addView(wdl, new LinearLayout.LayoutParams(-1, dp(17)));
        variation.setTextSize(13.5f);
        variation.setTextColor(0xFFF0F0F0);
        variation.setSingleLine(false);
        variation.setMaxLines(1000);
        variation.setEllipsize(null);
        variation.setIncludeFontPadding(true);
        variation.setPadding(0, dp(1), dp(2), dp(3));
        variation.setGravity(android.view.Gravity.TOP | android.view.Gravity.LEFT);
        ScrollView variationViewport = createIndependentVerticalScrollView();
        variationViewport.setFillViewport(false);
        variationViewport.setVerticalScrollBarEnabled(false);
        variationViewport.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        variationViewport.addView(variation, new ScrollView.LayoutParams(-1, -2));
        panel.addView(variationViewport, new LinearLayout.LayoutParams(-1, dp(48)));
        return panel;
    }

    private TextView createMainOutputTitle(boolean white) {
        TextView title = text((white ? "White: " : "Black: ") + (white ? currentWhiteName : currentBlackName));
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(white ? 0xFFFFD9A8 : 0xFFB9D9F4);
        title.setSingleLine(true);
        title.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.LEFT);
        title.setVisibility(View.GONE);
        return title;
    }

    private void attachMainBoardOutputGestures(View panel, TextView metrics, TextView variation, boolean white) {
        attachMainBoardOutputGesture(panel, white);
        attachMainBoardOutputGesture(metrics, white);
        attachMainBoardOutputGesture(variation, white);
    }

    private void attachMainBoardOutputGesture(View target, boolean white) {
        if (target == null) return;
        GestureDetector detector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(android.view.MotionEvent event) { return true; }
            @Override public boolean onDoubleTap(android.view.MotionEvent event) {
                if (mainBoardSharedOutputTop != null) {
                    mainBoardSharedOutputTop = null;
                } else {
                    // Use the tapped panel's current physical side, including when the board is flipped.
                    mainBoardSharedOutputTop = (white == tournamentUiFlipped);
                    mainBoardSharedActiveWhite = currentWhiteToMove;
                    String activeAnalysis = mainBoardSharedActiveWhite ? lastWhiteAnalysis : lastBlackAnalysis;
                    setMainBoardEngineOutput(blackAnalysisText, blackAnalysisPvText, activeAnalysis);
                }
                applyMainBoardAnalysisPositions();
                return true;
            }
        });
        target.setClickable(true);
        target.setLongClickable(true);
        target.setOnTouchListener((view, event) -> {
            detector.onTouchEvent(event);
            return false; // Preserve the metrics/PV scroll containers.
        });
        target.setOnLongClickListener(v -> {
            showMainBoardOutputLongPressMenu(white);
            return true;
        });
    }

    private void showMainBoardOutputLongPressMenu(boolean white) {
        boolean outputWhite = mainBoardSharedOutputTop != null ? mainBoardSharedActiveWhite : white;
        String engineName = outputWhite ? currentWhiteName : currentBlackName;
        String analysis = outputWhite ? lastWhiteAnalysis : lastBlackAnalysis;
        new AlertDialog.Builder(this).setTitle((outputWhite ? "White: " : "Black: ") + engineName)
                .setItems(new String[]{"Copy engine output", "Switch output position"}, (dialog, which) -> {
                    if (which == 0) copyTournamentEngineOutput(outputWhite, analysis);
                    else {
                        mainBoardSharedOutputTop = mainBoardSharedOutputTop == null
                                ? !(white == tournamentUiFlipped) : !mainBoardSharedOutputTop;
                        applyMainBoardAnalysisPositions();
                    }
                }).show();
    }

    private void updateMainOutputTitles() {
        boolean shared = mainBoardSharedOutputTop != null;
        if (mainWhiteEngineTitle != null) {
            mainWhiteEngineTitle.setText("White: " + currentWhiteName);
            mainWhiteEngineTitle.setVisibility(View.GONE);
        }
        if (mainBlackEngineTitle != null) {
            boolean outputWhite = mainBoardSharedActiveWhite;
            mainBlackEngineTitle.setText((outputWhite ? "White: " : "Black: ") +
                    (outputWhite ? currentWhiteName : currentBlackName));
            mainBlackEngineTitle.setTextColor(outputWhite ? 0xFFFFD9A8 : 0xFFB9D9F4);
            mainBlackEngineTitle.setVisibility(shared ? View.VISIBLE : View.GONE);
        }
        setMainOutputVariationHeight(mainWhiteAnalysisScroll, dp(48));
        setMainOutputVariationHeight(mainBlackAnalysisScroll, shared ? dp(60) : dp(48));
        setMainOutputPanelHeight(mainBlackAnalysisScroll, shared ? dp(138) : dp(108));
        setMainOutputPanelHeight(mainWhiteAnalysisScroll, dp(108));
    }

    private void setMainOutputPanelHeight(LinearLayout panel, int height) {
        if (panel == null || panel.getLayoutParams() == null) return;
        ViewGroup.LayoutParams lp = panel.getLayoutParams();
        if (lp.height != height) {
            lp.height = height;
            panel.setLayoutParams(lp);
        }
    }

    private void setMainOutputVariationHeight(LinearLayout panel, int height) {
        if (panel == null || panel.getChildCount() < 4) return;
        // Engine title is inserted at index 0, followed by metrics, WDL and PV.
        View variationViewport = panel.getChildAt(3);
        ViewGroup.LayoutParams lp = variationViewport.getLayoutParams();
        if (lp != null && lp.height != height) {
            lp.height = height;
            variationViewport.setLayoutParams(lp);
        }
    }

    /** Reorders only the output panels and player rows; the chessboard view is never changed. */
    private void applyMainBoardAnalysisPositions() {
        updateMainOutputTitles();
        refreshTournamentMainWdlFromCache();
        if (playPage == null || blackClockRow == null || whiteClockRow == null ||
                mainBlackAnalysisScroll == null || mainWhiteAnalysisScroll == null ||
                tournamentBoard == null || !(tournamentBoard.getParent() instanceof View)) return;
        View boardFrame = (View)tournamentBoard.getParent();
        if (boardFrame.getParent() != playPage) return;
        View topPlayer = tournamentUiFlipped ? whiteClockRow : blackClockRow;
        View bottomPlayer = tournamentUiFlipped ? blackClockRow : whiteClockRow;

        playPage.removeView(mainBlackAnalysisScroll);
        playPage.removeView(mainWhiteAnalysisScroll);
        playPage.removeView(blackClockRow);
        playPage.removeView(whiteClockRow);
        playPage.removeView(boardFrame);

        int index = Math.min(1, playPage.getChildCount());
        if (mainBoardSharedOutputTop != null) {
            // Reuse one output panel for whichever engine is currently thinking.
            mainWhiteAnalysisScroll.setVisibility(View.GONE);
            mainBlackAnalysisScroll.setVisibility(View.VISIBLE);
            LinearLayout.LayoutParams sharedParams = new LinearLayout.LayoutParams(-1, dp(138));
            if (mainBoardSharedOutputTop) {
                playPage.addView(mainBlackAnalysisScroll, index++, sharedParams);
                playPage.addView(topPlayer, index++);
                playPage.addView(boardFrame, index++);
                playPage.addView(bottomPlayer, index);
            } else {
                playPage.addView(topPlayer, index++);
                playPage.addView(boardFrame, index++);
                playPage.addView(bottomPlayer, index++);
                playPage.addView(mainBlackAnalysisScroll, index, sharedParams);
            }
        } else {
            mainBlackAnalysisScroll.setVisibility(View.VISIBLE);
            mainWhiteAnalysisScroll.setVisibility(View.VISIBLE);
            // The reused shared panel may have displayed either side. Restore each
            // side's own latest analysis before returning to the normal two-panel layout.
            setMainBoardEngineOutput(blackAnalysisText, blackAnalysisPvText, lastBlackAnalysis);
            setMainBoardEngineOutput(whiteAnalysisText, whiteAnalysisPvText, lastWhiteAnalysis);
            setMainOutputPanelHeight(mainBlackAnalysisScroll, dp(108));
            setMainOutputPanelHeight(mainWhiteAnalysisScroll, dp(108));
            boolean topWhite = tournamentUiFlipped;
            View topOutput = topWhite ? mainWhiteAnalysisScroll : mainBlackAnalysisScroll;
            View bottomOutput = topWhite ? mainBlackAnalysisScroll : mainWhiteAnalysisScroll;
            playPage.addView(topOutput, index++);
            playPage.addView(topPlayer, index++);
            playPage.addView(boardFrame, index++);
            playPage.addView(bottomPlayer, index++);
            playPage.addView(bottomOutput, index);
        }
        playPage.requestLayout();
    }

    private void updateStartingPositionUi() {
        if (startPositionModeSpinner == null) return;
        int mode = startPositionModeSpinner.getSelectedItemPosition();
        if (chess960StartInput != null) chess960StartInput.setVisibility(mode == TournamentManager.StartPositionMode.CHESS960.ordinal() ? View.VISIBLE : View.GONE);
        if (variantSeedInput != null) variantSeedInput.setVisibility(
                mode == TournamentManager.StartPositionMode.DFRC.ordinal() || mode == TournamentManager.StartPositionMode.CHESS324.ordinal()
                        ? View.VISIBLE : View.GONE);
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
        blackAnalysisPvText = text("—");
        blackWdlText = text("");
        mainBlackAnalysisScroll = createMainAnalysisPanel(blackAnalysisText, blackWdlText, blackAnalysisPvText);
        mainBlackEngineTitle = createMainOutputTitle(false);
        mainBlackAnalysisScroll.addView(mainBlackEngineTitle, 0, fullWidth());
        page.addView(mainBlackAnalysisScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(108)));
        attachMainBoardOutputGestures(mainBlackAnalysisScroll, blackAnalysisText, blackAnalysisPvText, false);

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

        int mainBoardSize = getResources().getDisplayMetrics().widthPixels;
        FrameLayout boardFrame = new FrameLayout(this) {
            @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int width = MeasureSpec.getSize(widthMeasureSpec);
                int height = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY
                        ? MeasureSpec.getSize(heightMeasureSpec) : width;
                int size = Math.min(width, height);
                super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY));
            }
        };
        LinearLayout.LayoutParams frameParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, mainBoardSize);
        frameParams.topMargin = dp(4);
        frameParams.bottomMargin = dp(4);
        page.addView(boardFrame, frameParams);
        tournamentBoard = new TournamentChessBoard(this);
        tournamentBoard.setClickable(false);
        tournamentBoard.setFocusable(false);
        tournamentBoard.highlightLastMove = true;
        tournamentBoard.fillLastMoveUnderPieces = true;
        tournamentBoard.setPosition(standardStartPosition());
        FrameLayout.LayoutParams boardParams = new FrameLayout.LayoutParams(
                mainBoardSize, mainBoardSize, android.view.Gravity.CENTER);
        boardFrame.addView(tournamentBoard, boardParams);
        boardFrame.setOnTouchListener((v, event) -> false);
        tournamentBoard.setLongClickable(true);
        tournamentBoard.setOnLongClickListener(v -> { showExpandedMainBoard(); return true; });
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
        whiteAnalysisPvText = text("—");
        whiteWdlText = text("");
        mainWhiteAnalysisScroll = createMainAnalysisPanel(whiteAnalysisText, whiteWdlText, whiteAnalysisPvText);
        mainWhiteEngineTitle = createMainOutputTitle(true);
        mainWhiteAnalysisScroll.addView(mainWhiteEngineTitle, 0, fullWidth());
        page.addView(mainWhiteAnalysisScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(108)));
        attachMainBoardOutputGestures(mainWhiteAnalysisScroll, whiteAnalysisText, whiteAnalysisPvText, true);
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

        menu.setOnClickListener(v -> {
            if (savedTournamentReviewMode) {
                new AlertDialog.Builder(TournamentActivity.this).setTitle("Saved tournament review")
                        .setItems(new String[]{"Tournament list", "See Rating", "Move animation speed", "Flip Board", "Tournament arrows (" + tournamentArrowCount + ")"}, (dialog, which) -> {
                            if (which == 0) chooseSavedTournament();
                            else if (which == 1) showRatingsDialog();
                            else if (which == 2) showTournamentMoveAnimationDialog();
                            else if (which == 3) toggleTournamentFlip();
                            else showTournamentArrowCountDialog();
                        }).show();
            } else {
                new AlertDialog.Builder(TournamentActivity.this).setTitle("Tournament menu")
                        .setItems(new String[]{"Adjudicate", "Setup", "Flip Board", "Schedule", "See Rating", "Move animation speed", "Tournament arrows (" + tournamentArrowCount + ")"}, (dialog, which) -> {
                            if (which == 0) showAdjudicationDialog();
                            else if (which == 1) { boardSelectionActive = false; setContentView(setupView); }
                            else if (which == 2) toggleTournamentFlip();
                            else if (which == 3) showScheduleDialog();
                            else if (which == 4) showRatingsDialog();
                            else if (which == 5) showTournamentMoveAnimationDialog();
                            else showTournamentArrowCountDialog();
                        }).show();
            }
        });
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
        applyMainBoardAnalysisPositions();
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
        mirrorHeaderText.setTextSize(18);
        mirrorHeaderText.setTypeface(null, android.graphics.Typeface.BOLD);
        mirrorHeaderText.setPadding(dp(8), dp(8), dp(8), dp(8));
        mirrorHeaderText.setSingleLine(true);
        mirrorHeaderText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mirrorHeaderText.setIncludeFontPadding(false);
        mirrorHeaderText.setGravity(android.view.Gravity.CENTER);
        mirrorHeaderText.setOnClickListener(v -> {
            mirrorHeaderShowsTitle = !mirrorHeaderShowsTitle;
            mirrorHeaderText.setText(mirrorHeaderLabel());
        });
        mirrorPage.addView(mirrorHeaderText, fullWidth());

        mirrorFocusAnalysisContainer = new LinearLayout(this);
        mirrorFocusAnalysisContainer.setOrientation(LinearLayout.VERTICAL);
        mirrorFocusAnalysisContainer.setPadding(dp(8), dp(5), dp(8), dp(5));
        mirrorFocusAnalysisContainer.setBackgroundColor(0xFF252525);
        mirrorFocusEngineTitleRow = new LinearLayout(this);
        mirrorFocusEngineTitleRow.setOrientation(LinearLayout.HORIZONTAL);
        mirrorFocusEngineTitleRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        mirrorFocusEngineIcon = new ImageView(this);
        mirrorFocusEngineIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mirrorFocusEngineIcon.setVisibility(View.GONE);
        mirrorFocusEngineTitle = text("White: Engine");
        mirrorFocusEngineTitle.setTextSize(13);
        mirrorFocusEngineTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        mirrorFocusEngineTitle.setTextColor(0xFFE2E8EC);
        mirrorFocusEngineTitle.setSingleLine(true);
        mirrorFocusEngineTitle.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.LEFT);
        mirrorFocusEngineTitle.setPadding(0, 0, 0, 0);
        mirrorFocusEngineTitleRow.addView(mirrorFocusEngineTitle, new LinearLayout.LayoutParams(0, -1, 1.0f));
        attachMirrorFocusOutputLongPress(mirrorFocusEngineTitleRow);
        mirrorFocusEvaluationBar = new MirrorEvaluationBar(this);
        mirrorFocusEvaluationBar.setEngineAnalysis(null, true);
        mirrorFocusAnalysisContainer.addView(mirrorFocusEvaluationBar, new LinearLayout.LayoutParams(-1, dp(8)));
        mirrorFocusAnalysisContainer.addView(mirrorFocusEngineTitleRow, new LinearLayout.LayoutParams(-1, dp(24)));
        mirrorFocusAnalysisText = text(formatMirrorMetrics(null));
        mirrorFocusAnalysisText.setTextSize(12);
        mirrorFocusAnalysisText.setTextColor(0xFFE2E8EC);
        mirrorFocusAnalysisText.setGravity(android.view.Gravity.TOP | android.view.Gravity.LEFT);
        mirrorFocusAnalysisText.setIncludeFontPadding(false);
        mirrorFocusAnalysisText.setSingleLine(false);
        mirrorFocusAnalysisText.setMinLines(4);
        mirrorFocusAnalysisText.setMaxLines(4);
        attachMirrorFocusOutputLongPress(mirrorFocusAnalysisText);
        mirrorFocusAnalysisPvText = createMirrorVariationText();
        mirrorFocusAnalysisPvText.setTextSize(12);
        mirrorFocusAnalysisPvText.setTextColor(0xFFE2E8EC);
        mirrorFocusAnalysisPvText.setIncludeFontPadding(false);
        mirrorFocusAnalysisPvText.setPadding(dp(5), 0, dp(3), dp(2));
        attachMirrorFocusOutputLongPress(mirrorFocusAnalysisPvText);
        mirrorFocusAnalysisPvScroll = createMirrorVariationScroll(mirrorFocusAnalysisPvText);
        mirrorFocusAnalysisRow = createMirrorAnalysisRow(mirrorFocusAnalysisText, mirrorFocusAnalysisPvScroll,
                mirrorFocusEngineIcon, mirrorFocusIconAfterInfo, mirrorFocusInfoAfterVariation);
        mirrorFocusAnalysisContainer.addView(mirrorFocusAnalysisRow,
                new LinearLayout.LayoutParams(-1, dp(64)));
        mirrorFocusAnalysisContainer.setVisibility(View.GONE);
        mirrorPage.addView(mirrorFocusAnalysisContainer, fullWidth());

        // Compact move history (left) and quick engine analysis (right), placed
        // immediately below the tournament title and above the player outputs.
        mirrorBoardShortcuts = new LinearLayout(this);
        mirrorBoardShortcuts.setOrientation(LinearLayout.HORIZONTAL);
        mirrorBoardShortcuts.setGravity(android.view.Gravity.CENTER_VERTICAL);
        mirrorBoardShortcuts.setPadding(dp(4), dp(3), dp(4), dp(3));
        mirrorMoveStripScroll = new HorizontalScrollView(this);
        mirrorMoveStripScroll.setHorizontalScrollBarEnabled(false);
        mirrorMoveStripScroll.setFillViewport(false);
        mirrorMoveStrip = new LinearLayout(this);
        mirrorMoveStrip.setOrientation(LinearLayout.HORIZONTAL);
        mirrorMoveStripScroll.addView(mirrorMoveStrip, new HorizontalScrollView.LayoutParams(-2, dp(38)));
        mirrorBoardShortcuts.addView(mirrorMoveStripScroll, new LinearLayout.LayoutParams(0, dp(40), 1));
        View shortcutDivider = new View(this);
        shortcutDivider.setBackgroundColor(0xFF59636A);
        LinearLayout.LayoutParams dividerLp = new LinearLayout.LayoutParams(dp(1), dp(30));
        dividerLp.leftMargin = dp(3); dividerLp.rightMargin = dp(3);
        mirrorBoardShortcuts.addView(shortcutDivider, dividerLp);
        mirrorShortcutAnalysisScroll = new HorizontalScrollView(this);
        mirrorShortcutAnalysisScroll.setHorizontalScrollBarEnabled(false);
        mirrorShortcutAnalysisScroll.setFillViewport(false);
        mirrorShortcutAnalysisScroll.setClickable(true);
        mirrorShortcutAnalysis = text("Tap to analyse · hold to choose engine");
        mirrorShortcutAnalysis.setTextSize(13);
        mirrorShortcutAnalysis.setSingleLine(true);
        mirrorShortcutAnalysis.setMaxLines(1);
        mirrorShortcutAnalysis.setHorizontallyScrolling(true);
        mirrorShortcutAnalysis.setTextColor(0xFFE2E8EC);
        mirrorShortcutAnalysis.setGravity(android.view.Gravity.CENTER_VERTICAL);
        mirrorShortcutAnalysis.setPadding(dp(4), 0, dp(8), 0);
        mirrorShortcutAnalysisScroll.addView(mirrorShortcutAnalysis, new HorizontalScrollView.LayoutParams(-2, dp(38)));
        mirrorBoardShortcuts.addView(mirrorShortcutAnalysisScroll, new LinearLayout.LayoutParams(0, dp(40), 1));
        mirrorPage.addView(mirrorBoardShortcuts, fullWidth());
        installMirrorShortcutGestures();

        mirrorBlackAnalysisText = text(formatMirrorMetrics(null));
        mirrorBlackAnalysisText.setTextSize(13.5f);
        mirrorBlackAnalysisText.setMinLines(4);
        mirrorBlackAnalysisText.setMaxLines(4);
        mirrorBlackAnalysisText.setMinHeight(0);
        mirrorBlackAnalysisText.setIncludeFontPadding(false);
        mirrorBlackAnalysisText.setGravity(android.view.Gravity.TOP | android.view.Gravity.LEFT);
        mirrorBlackAnalysisText.setTextColor(0xFFF2F4F6);
        mirrorBlackAnalysisText.setLineSpacing(0, 1.0f);
        mirrorBlackPvText = createMirrorVariationText();
        mirrorBlackPvScroll = createMirrorVariationScroll(mirrorBlackPvText);
        mirrorBlackOutputPanel = new LinearLayout(this);
        mirrorBlackOutputPanel.setOrientation(LinearLayout.VERTICAL);
        mirrorBlackOutputPanel.setPadding(dp(8), dp(4), dp(8), dp(4));
        mirrorBlackOutputPanel.setBackgroundColor(0xFF29323B);
        mirrorBlackEvaluationBar = new MirrorEvaluationBar(this);
        mirrorBlackEvaluationBar.setEngineAnalysis(lastBlackAnalysis, false);
        mirrorBlackIcon = new ImageView(this);
        mirrorBlackIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mirrorBlackIcon.setVisibility(View.GONE);
        mirrorBlackAnalysisScroll = createMirrorAnalysisRow(mirrorBlackAnalysisText, mirrorBlackPvScroll,
                mirrorBlackIcon, mirrorBlackIconAfterInfo, mirrorBlackInfoAfterVariation);
        mirrorBlackOutputPanel.addView(mirrorBlackEvaluationBar, new LinearLayout.LayoutParams(-1, dp(8)));
        mirrorBlackOutputPanel.addView(mirrorBlackAnalysisScroll, new LinearLayout.LayoutParams(-1, dp(64)));
        mirrorPage.addView(mirrorBlackOutputPanel, new LinearLayout.LayoutParams(-1, dp(90)));
        attachMirrorOutputLongPress(mirrorBlackAnalysisText, false);
        attachMirrorOutputLongPress(mirrorBlackPvText, false);
        attachMirrorOutputLongPress(mirrorBlackIcon, false);
        attachMirrorOutputLongPress(mirrorBlackOutputPanel, false);

        mirrorBlackClock = text("00:30.0");
        mirrorBlackClock.setTextSize(21);
        mirrorBlackClock.setTypeface(null, android.graphics.Typeface.BOLD);
        mirrorBlackName = text("BLACK");
        mirrorBlackName.setTextSize(19);
        mirrorBlackName.setTypeface(null, android.graphics.Typeface.BOLD);
        mirrorBlackName.setGravity(android.view.Gravity.CENTER);
        mirrorBlackMoveTime = text("—");
        mirrorBlackMoveTime.setTextSize(17);
        mirrorBlackMoveTime.setGravity(android.view.Gravity.RIGHT);
        mirrorBlackRow = new LinearLayout(this);
        mirrorBlackRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        mirrorBlackRow.addView(mirrorBlackClock, new LinearLayout.LayoutParams(0, -2, 1));
        mirrorBlackRow.addView(mirrorBlackName, new LinearLayout.LayoutParams(0, -2, 1.4f));
        mirrorBlackRow.addView(mirrorBlackMoveTime, new LinearLayout.LayoutParams(0, -2, 1));
        mirrorFocusBlackIcon = new ImageView(this);
        mirrorFocusBlackIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mirrorFocusBlackIcon.setVisibility(View.GONE);
        LinearLayout.LayoutParams focusBlackIconParams = new LinearLayout.LayoutParams(dp(64), dp(64));
        focusBlackIconParams.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        mirrorPage.addView(mirrorFocusBlackIcon, focusBlackIconParams);
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
        mirrorBoard.fillLastMoveUnderPieces = true;
        mirrorBoard.setClickable(false);
        mirrorBoard.setFocusable(false);
        mirrorBoard.highlightLastMove = true;
        mirrorBoard.setPosition(standardStartPosition());
        frame.addView(mirrorBoard, new FrameLayout.LayoutParams(-1, -1, android.view.Gravity.CENTER));

        mirrorFocusMovesText = text("No moves yet.");
        mirrorFocusMovesText.setTextSize(14);
        mirrorFocusMovesText.setTextColor(0xFFE2E8EC);
        mirrorFocusMovesText.setPadding(dp(8), dp(5), dp(8), dp(5));
        mirrorFocusMovesText.setGravity(android.view.Gravity.TOP | android.view.Gravity.LEFT);
        mirrorFocusMovesText.setIncludeFontPadding(false);
        mirrorFocusMovesText.setSingleLine(false);
        mirrorFocusMovesText.setMaxLines(1000);
        mirrorFocusMovesScroll = createIndependentVerticalScrollView();
        mirrorFocusMovesScroll.setVisibility(View.GONE);
        mirrorFocusMovesScroll.setFillViewport(false);
        mirrorFocusMovesScroll.setVerticalScrollBarEnabled(true);
        mirrorFocusMovesScroll.addView(mirrorFocusMovesText, new ScrollView.LayoutParams(-1, -2));
        mirrorPage.addView(mirrorFocusMovesScroll, new LinearLayout.LayoutParams(-1, dp(116)));


        mirrorWhiteClock = text("00:30.0");
        mirrorWhiteClock.setTextSize(21);
        mirrorWhiteClock.setTypeface(null, android.graphics.Typeface.BOLD);
        mirrorWhiteName = text("WHITE");
        mirrorWhiteName.setTextSize(19);
        mirrorWhiteName.setTypeface(null, android.graphics.Typeface.BOLD);
        mirrorWhiteName.setGravity(android.view.Gravity.CENTER);
        mirrorWhiteMoveTime = text("—");
        mirrorWhiteMoveTime.setTextSize(17);
        mirrorWhiteMoveTime.setGravity(android.view.Gravity.RIGHT);
        mirrorWhiteRow = new LinearLayout(this);
        mirrorWhiteRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        mirrorWhiteRow.addView(mirrorWhiteClock, new LinearLayout.LayoutParams(0, -2, 1));
        mirrorWhiteRow.addView(mirrorWhiteName, new LinearLayout.LayoutParams(0, -2, 1.4f));
        mirrorWhiteRow.addView(mirrorWhiteMoveTime, new LinearLayout.LayoutParams(0, -2, 1));
        mirrorPage.addView(mirrorWhiteRow, fullWidth());
        mirrorFocusWhiteIcon = new ImageView(this);
        mirrorFocusWhiteIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mirrorFocusWhiteIcon.setVisibility(View.GONE);
        LinearLayout.LayoutParams focusWhiteIconParams = new LinearLayout.LayoutParams(dp(64), dp(64));
        focusWhiteIconParams.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        mirrorPage.addView(mirrorFocusWhiteIcon, focusWhiteIconParams);

        mirrorWhiteAnalysisText = text(formatMirrorMetrics(null));
        mirrorWhiteAnalysisText.setTextSize(13.5f);
        mirrorWhiteAnalysisText.setMinLines(4);
        mirrorWhiteAnalysisText.setMaxLines(4);
        mirrorWhiteAnalysisText.setMinHeight(0);
        mirrorWhiteAnalysisText.setIncludeFontPadding(false);
        mirrorWhiteAnalysisText.setGravity(android.view.Gravity.TOP | android.view.Gravity.LEFT);
        mirrorWhiteAnalysisText.setTextColor(0xFFF2F4F6);
        mirrorWhiteAnalysisText.setLineSpacing(0, 1.0f);
        mirrorWhitePvText = createMirrorVariationText();
        mirrorWhitePvScroll = createMirrorVariationScroll(mirrorWhitePvText);
        mirrorWhiteOutputPanel = new LinearLayout(this);
        mirrorWhiteOutputPanel.setOrientation(LinearLayout.VERTICAL);
        mirrorWhiteOutputPanel.setPadding(dp(8), dp(4), dp(8), dp(4));
        mirrorWhiteOutputPanel.setBackgroundColor(0xFF34302B);
        mirrorWhiteEvaluationBar = new MirrorEvaluationBar(this);
        mirrorWhiteEvaluationBar.setEngineAnalysis(lastWhiteAnalysis, true);
        mirrorWhiteIcon = new ImageView(this);
        mirrorWhiteIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mirrorWhiteIcon.setVisibility(View.GONE);
        mirrorWhiteAnalysisScroll = createMirrorAnalysisRow(mirrorWhiteAnalysisText, mirrorWhitePvScroll,
                mirrorWhiteIcon, mirrorWhiteIconAfterInfo, mirrorWhiteInfoAfterVariation);
        mirrorWhiteOutputPanel.addView(mirrorWhiteEvaluationBar, new LinearLayout.LayoutParams(-1, dp(8)));
        mirrorWhiteOutputPanel.addView(mirrorWhiteAnalysisScroll, new LinearLayout.LayoutParams(-1, dp(64)));
        mirrorPage.addView(mirrorWhiteOutputPanel, new LinearLayout.LayoutParams(-1, dp(90)));
        attachMirrorOutputLongPress(mirrorWhiteAnalysisText, true);
        attachMirrorOutputLongPress(mirrorWhitePvText, true);
        attachMirrorOutputLongPress(mirrorWhiteIcon, true);
        attachMirrorOutputLongPress(mirrorWhiteOutputPanel, true);

        createMirrorPlayerGroups();
        installMirrorPlayerOutputGestures();
        applyMirrorPlayerPanelSizes();

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
        mirrorSectionTitle.setVisibility(View.GONE);
        mirrorPage.addView(mirrorSectionTitle, fullWidth());
        mirrorSectionHost = new LinearLayout(this);
        mirrorSectionHost.setOrientation(LinearLayout.VERTICAL);
        mirrorSectionHost.setPadding(dp(6), dp(4), dp(6), dp(4));
        mirrorSectionHost.setBackgroundColor(0xFF292929);
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int panelHeight = Math.max(dp(260), Math.min(dp(520), (int)(screenHeight * 0.48f)));
        mirrorPage.addView(mirrorSectionHost, new LinearLayout.LayoutParams(-1, panelHeight));
        mirrorGraphScrollSpacer = new View(this);
        mirrorGraphScrollSpacer.setVisibility(View.GONE);
        mirrorPage.addView(mirrorGraphScrollSpacer, new LinearLayout.LayoutParams(-1, 0));
        mirrorScroll.addView(mirrorPage, new ScrollView.LayoutParams(-1, -2));
        root.addView(mirrorScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        // The pinned control bar has two mutually exclusive modes. Double-tapping
        // the board switches between tournament controls and move navigation.
        mirrorControlBar = new LinearLayout(this);
        mirrorControlBar.setOrientation(LinearLayout.HORIZONTAL);
        mirrorControlBar.setBackgroundColor(0xFF202020);
        if (android.os.Build.VERSION.SDK_INT >= 21) mirrorControlBar.setElevation(dp(6));

        mirrorPrimaryControls = new LinearLayout(this);
        mirrorPrimaryControls.setOrientation(LinearLayout.HORIZONTAL);
        mirrorPrimaryControls.setVisibility(View.VISIBLE);
        mirrorPauseButton = new Button(this);
        mirrorPauseButton.setText(runner != null && runner.isPaused() ? "PLAY" : "PAUSE");
        Button back = new Button(this); back.setText("SWITCH");
        Button menu = new Button(this); menu.setText("MENU");
        for (Button b : new Button[]{mirrorPauseButton, back, menu})
            mirrorPrimaryControls.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
        mirrorPauseButton.setOnClickListener(v -> togglePauseResume());
        back.setOnClickListener(v -> switchTournamentAndMirror());
        menu.setOnClickListener(v -> {
            if (savedTournamentReviewMode) {
                new AlertDialog.Builder(TournamentActivity.this).setTitle("Saved tournament review")
                        .setItems(new String[]{"Tournament list", "See Rating", "Move animation speed", "Flip Board", "External PV arrows (" + mirrorAnalysisArrowPlyCount + " plies)"}, (dialog, which) -> {
                            if (which == 0) chooseSavedTournament();
                            else if (which == 1) showRatingsDialog();
                            else if (which == 2) showTournamentMoveAnimationDialog();
                            else if (which == 3) toggleTournamentFlip();
                            else showMirrorExternalArrowCountDialog();
                        }).show();
            } else {
                new AlertDialog.Builder(TournamentActivity.this).setTitle("Tournament menu")
                        .setItems(new String[]{"Adjudicate", "Temporary variation", "Setup", "Flip Board", "Move animation speed", "External PV arrows (" + mirrorAnalysisArrowPlyCount + " plies)"}, (dialog, which) -> {
                            if (which == 0) showAdjudicationDialog();
                            else if (which == 1) toggleMirrorBranchMode();
                            else if (which == 2) { boardSelectionActive = false; setContentView(setupView); }
                            else if (which == 3) toggleTournamentFlip();
                            else if (which == 4) showTournamentMoveAnimationDialog();
                            else showMirrorExternalArrowCountDialog();
                        }).show();
            }
        });
        mirrorControlBar.addView(mirrorPrimaryControls, new LinearLayout.LayoutParams(-1, -2));

        mirrorNavigationControls = new LinearLayout(this);
        mirrorNavigationControls.setOrientation(LinearLayout.HORIZONTAL);
        mirrorNavigationControls.setVisibility(View.GONE);
        mirrorNavFirstButton = new Button(this); mirrorNavFirstButton.setText("⏮");
        mirrorNavPreviousButton = new Button(this); mirrorNavPreviousButton.setText("◀");
        mirrorNavPlayButton = new Button(this); mirrorNavPlayButton.setText(mirrorAutoPlay ? "⏸" : "▶");
        mirrorNavNextButton = new Button(this); mirrorNavNextButton.setText("▶");
        mirrorNavLastButton = new Button(this); mirrorNavLastButton.setText("⏭");
        Button[] navigationButtons = {mirrorNavFirstButton, mirrorNavPreviousButton, mirrorNavPlayButton,
                mirrorNavNextButton, mirrorNavLastButton};
        for (int i = 0; i < navigationButtons.length; i++) {
            Button b = navigationButtons[i];
            b.setTextSize(i == 2 ? 13 : 17);
            b.setAllCaps(false);
            b.setPadding(dp(2), dp(3), dp(2), dp(3));
            mirrorNavigationControls.addView(b, new LinearLayout.LayoutParams(0, -2, i == 2 ? 1.65f : 0.84f));
        }
        mirrorNavFirstButton.setOnClickListener(v -> navigateMirror(0));
        mirrorNavPreviousButton.setOnClickListener(v -> navigateMirror(Math.max(0, mirrorDisplayedPly() - 1)));
        GestureDetector navPlayGesture = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(android.view.MotionEvent event) { return true; }
            @Override public boolean onSingleTapConfirmed(android.view.MotionEvent event) {
                if (mirrorNavigationShowLastMove) {
                    int lastPly = Math.max(0, currentMirrorPositions().size() - 1);
                    navigateMirror(lastPly);
                } else toggleMirrorAutoPlay();
                return true;
            }
            @Override public boolean onDoubleTap(android.view.MotionEvent event) {
                mirrorNavigationShowLastMove = !mirrorNavigationShowLastMove;
                updateMirrorNavigationButtons();
                return true;
            }
        });
        mirrorNavPlayButton.setOnTouchListener((view, event) -> navPlayGesture.onTouchEvent(event));
        mirrorNavNextButton.setOnClickListener(v -> navigateMirror(Math.min(currentMirrorPositions().size() - 1, mirrorDisplayedPly() + 1)));
        mirrorNavLastButton.setOnClickListener(v -> navigateMirror(Math.max(0, currentMirrorPositions().size() - 1)));
        mirrorControlBar.addView(mirrorNavigationControls, new LinearLayout.LayoutParams(-1, -2));
        root.addView(mirrorControlBar, fullWidth());
        updateMirrorNavigationButtons();

        GestureDetector boardGestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(android.view.MotionEvent event) { return true; }
            @Override public boolean onSingleTapConfirmed(android.view.MotionEvent event) {
                // Temporary-variation moves wait for single-tap confirmation so
                // a double-tap remains exclusively the navigation-control gesture.
                if (mirrorBranchMode) {
                    int square = mirrorBoard.eventToSquare(event);
                    if (square >= 0) handleMirrorBranchSquareTap(square);
                    return true;
                }
                return false;
            }
            @Override public boolean onDoubleTap(android.view.MotionEvent event) {
                toggleMirrorNavigationMode();
                return true;
            }
            @Override public void onLongPress(android.view.MotionEvent event) {
                toggleMirrorBoardOnlyMode();
            }
        });
        mirrorBoard.setClickable(true);
        mirrorBoard.setLongClickable(true);
        mirrorBoard.setOnTouchListener((view, event) -> {
            boardGestureDetector.onTouchEvent(event);
            // Keep the gesture detector's full down/move/up stream on the board.
            return true;
        });
        mirrorView = root;
        selectMirrorTab(0, false);
    }

    private void installMirrorShortcutGestures() {
        if (mirrorShortcutAnalysisScroll == null) return;
        GestureDetector gesture = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(android.view.MotionEvent event) { return true; }
            @Override public boolean onSingleTapConfirmed(android.view.MotionEvent event) {
                if (mirrorAnalysisEngineId.isEmpty()) {
                    Toast.makeText(TournamentActivity.this, "Long-press to choose an analysis engine", Toast.LENGTH_SHORT).show();
                    return true;
                }
                mirrorAnalysisOn = !mirrorAnalysisOn;
                if (mirrorAnalysisOn && mirrorAnalysisEngineActive) startMirrorAnalysis();
                else if (!mirrorAnalysisOn) stopMirrorAnalysis();
                if (mirrorNavigationMode || mirrorEngineOutputHidden) updateMirrorFocusPanels();
                refreshSelectedMirrorMoveComment();
                updateMirrorShortcutAnalysisState();
                return true;
            }
            @Override public boolean onDoubleTap(android.view.MotionEvent event) {
                mirrorAnalysisEngineActive = !mirrorAnalysisEngineActive;
                if (!mirrorAnalysisEngineActive) stopMirrorAnalysisEngineOnly();
                else if (mirrorAnalysisOn) startMirrorAnalysis();
                if (mirrorNavigationMode || mirrorEngineOutputHidden) updateMirrorFocusPanels();
                refreshSelectedMirrorMoveComment();
                updateMirrorShortcutAnalysisState();
                return true;
            }
            @Override public void onLongPress(android.view.MotionEvent event) { chooseMirrorAnalysisEngine(); }
        });
        mirrorShortcutAnalysisScroll.setOnTouchListener((view, event) -> {
            // Observe taps without consuming the event stream: HorizontalScrollView
            // must still receive MOVE events to provide independent left/right scroll.
            gesture.onTouchEvent(event);
            return false;
        });
        updateMirrorShortcutAnalysisState();
    }

    private void updateMirrorShortcutAnalysisState() {
        if (mirrorShortcutAnalysis == null) return;
        if (!mirrorAnalysisEngineActive) {
            mirrorShortcutAnalysis.setText("ENGINE OFF · double-tap to enable");
        } else if (!mirrorAnalysisOn) {
            mirrorShortcutAnalysis.setText("Tap to analyse · hold to choose engine");
        } else if (mirrorLatestAnalysisOutput == null || mirrorLatestAnalysisOutput.startsWith("Select an engine") ||
                mirrorLatestAnalysisOutput.startsWith("Starting analysis")) {
            mirrorShortcutAnalysis.setText("ANALYSING…");
        }
    }

    private void setMirrorShortcutAnalysis(String text) {
        if (mirrorShortcutAnalysis == null) return;
        int oldContentWidth = mirrorShortcutAnalysis.getWidth();
        int oldScroll = mirrorShortcutAnalysisScroll.getScrollX();
        int viewportWidth = mirrorShortcutAnalysisScroll.getWidth();
        boolean wasPinnedToRight = oldContentWidth > viewportWidth + dp(8) &&
                oldScroll + viewportWidth >= oldContentWidth - dp(8);
        mirrorShortcutAnalysis.setText(text == null || text.trim().isEmpty() ? "ANALYSING…" : text);
        mirrorShortcutAnalysisScroll.post(() -> {
            // Preserve a user's deliberate leftward inspection of the PV. Only
            // follow new engine output when the view was already pinned to its end.
            if (wasPinnedToRight) mirrorShortcutAnalysisScroll.fullScroll(View.FOCUS_RIGHT);
        });
    }

    private void updateMirrorMoveStrip() {
        if (mirrorMoveStrip == null) return;
        mirrorMoveStrip.removeAllViews();
        ArrayList<Position> positions = currentMirrorPositions();
        ArrayList<String> sans = currentMirrorSanMoves(null);
        int selectedPly = mirrorDisplayedPly();
        for (int i = 0; i < sans.size(); i++) {
            final int ply = i + 1;
            Position before = i < positions.size() ? positions.get(i) : null;
            int moveNumber = before == null ? (ply + 1) / 2 : before.fullMoveCounter;
            boolean whiteMove = before == null ? ((ply & 1) == 1) : before.whiteMove;
            boolean followsWhiteInSameFullMove = !whiteMove && i > 0 && i - 1 < positions.size() &&
                    positions.get(i - 1).whiteMove && positions.get(i - 1).fullMoveCounter == moveNumber;
            TextView move = text((whiteMove ? moveNumber + "." : (followsWhiteInSameFullMove ? "" : moveNumber + "...")) + sans.get(i));
            move.setTextSize(14);
            move.setIncludeFontPadding(false);
            move.setSingleLine(true);
            move.setGravity(android.view.Gravity.CENTER);
            move.setPadding(dp(5), dp(4), dp(5), dp(4));
            move.setTextColor(ply == selectedPly ? 0xFFFFFFFF : 0xFFCAD3D8);
            move.setBackgroundColor(ply == selectedPly ? 0xFF496B50 : 0xFF303030);
            move.setOnClickListener(v -> navigateMirror(ply));
            mirrorMoveStrip.addView(move, new LinearLayout.LayoutParams(-2, dp(34)));
        }
        if (sans.isEmpty()) {
            TextView empty = text("—"); empty.setTextSize(12); empty.setPadding(dp(6), dp(4), dp(6), dp(4));
            mirrorMoveStrip.addView(empty, new LinearLayout.LayoutParams(-2, dp(34)));
        }
        if (selectedPly > 0 && selectedPly <= mirrorMoveStrip.getChildCount()) {
            View selected = mirrorMoveStrip.getChildAt(selectedPly - 1);
            if (selected != null) mirrorMoveStripScroll.post(() -> {
                int x = selected.getLeft();
                int right = selected.getRight();
                int visibleLeft = mirrorMoveStripScroll.getScrollX();
                int visibleRight = visibleLeft + mirrorMoveStripScroll.getWidth();
                if (x < visibleLeft) mirrorMoveStripScroll.smoothScrollTo(x, 0);
                else if (right > visibleRight) mirrorMoveStripScroll.smoothScrollTo(right - mirrorMoveStripScroll.getWidth(), 0);
            });
        } else if (!sans.isEmpty()) {
            mirrorMoveStripScroll.post(() -> mirrorMoveStripScroll.fullScroll(View.FOCUS_RIGHT));
        }
        if (mirrorEngineOutputHidden) updateMirrorFocusPanels();
    }

    private void attachMirrorFocusOutputLongPress(View target) {
        if (target == null) return;
        target.setLongClickable(true);
        target.setOnLongClickListener(v -> {
            if (mirrorBoardOnlyMode) return true;
            showMirrorFocusedOutputLongPressMenu();
            return true;
        });
    }

    private void showMirrorFocusedOutputLongPressMenu() {
        boolean white = mirrorFocusAnalysisWhite;
        String engineName;
        String analysis;
        String engineId;
        boolean analysisEngine = false;
        boolean externalAnalysisActive = mirrorAnalysisOn && mirrorAnalysisEngineActive &&
                !mirrorAnalysisEngineId.isEmpty() && mirrorLatestAnalysisOutput != null &&
                !mirrorLatestAnalysisOutput.startsWith("Select an engine");
        if (externalAnalysisActive && (mirrorNavigationMode || mirrorBranchMode || !mirrorReplayMode)) {
            engineName = mirrorAnalysisEngineNameForId(mirrorAnalysisEngineId);
            analysis = mirrorLatestAnalysisOutput;
            engineId = mirrorAnalysisEngineId;
            analysisEngine = true;
        } else if (mirrorNavigationMode) {
            engineName = white ? mirrorNavigationWhiteName : mirrorNavigationBlackName;
            engineId = white ? mirrorNavigationWhiteEngineId : mirrorNavigationBlackEngineId;
            analysis = analysisFromComment(selectedMirrorMoveComment());
        } else if (mirrorReplayMode) {
            engineName = white ? mirrorReplayWhiteName : mirrorReplayBlackName;
            engineId = white ? mirrorReplayWhiteEngineId : mirrorReplayBlackEngineId;
            analysis = white ? lastWhiteAnalysis : lastBlackAnalysis;
        } else {
            engineName = white ? currentWhiteName : currentBlackName;
            engineId = white ? currentWhiteEngineId : currentBlackEngineId;
            analysis = white ? lastWhiteAnalysis : lastBlackAnalysis;
        }
        // Capture immutable snapshots for the dialog callback. The source values are
        // assembled from several mode branches above and must not be captured directly.
        final boolean selectedWhite = white;
        final boolean selectedAnalysisEngine = analysisEngine;
        final String selectedEngineId = engineId;
        final String selectedEngineName = engineName;
        final String selectedAnalysis = analysis;
        new AlertDialog.Builder(this).setTitle(selectedEngineName == null ? "Engine output" : selectedEngineName)
                .setItems(new String[]{"Engine option", "Copy engine output", "Switch Engine Output / Moves positions",
                        "Swap icon / engine info position", "Swap icon + engine / variation"}, (dialog, which) -> {
                    if (which == 0) openEngineOptions(selectedEngineId, selectedAnalysisEngine,
                            selectedAnalysisEngine ? null : selectedWhite);
                    else if (which == 1) copyNamedTournamentEngineOutput(selectedWhite, selectedEngineName, selectedAnalysis);
                    else if (which == 2) toggleMirrorFocusOutputMovesPositions();
                    else if (which == 3) toggleMirrorPanelIconInfo(selectedWhite, true);
                    else if (which == 4) toggleMirrorPanelInfoVariation(selectedWhite, true);
                }).show();
    }

    private String selectedMirrorMoveComment() {
        ArrayList<Position> positions = currentMirrorPositions();
        int selected = positions.isEmpty() ? 0 : Math.max(0, Math.min(mirrorDisplayedPly(), positions.size() - 1));
        ArrayList<String> comments = mirrorReplayMode ? mirrorReplayComments : mirrorLiveComments;
        return selected > 0 && selected - 1 < comments.size() ? comments.get(selected - 1) : "";
    }

    private void attachMirrorOutputLongPress(View target, boolean white) {
        if (target == null) return;
        target.setLongClickable(true);
        target.setOnLongClickListener(v -> {
            showMirrorPlayerOutputLongPressMenu(white);
            return true;
        });
    }

    private void showMirrorPlayerOutputLongPressMenu(boolean white) {
        String engineName = white ? currentWhiteName : currentBlackName;
        String analysis = white ? lastWhiteAnalysis : lastBlackAnalysis;
        String engineId = white ? currentWhiteEngineId : currentBlackEngineId;
        new AlertDialog.Builder(this).setTitle((white ? "White: " : "Black: ") + engineName)
                .setItems(new String[]{"Engine option", "Copy engine output", "Switch Engine Output / Moves positions",
                        "Swap icon / engine info position", "Swap icon + engine / variation"}, (dialog, which) -> {
                    if (which == 0) openEngineOptions(engineId, false, white);
                    else if (which == 1) copyTournamentEngineOutput(white, analysis);
                    else if (which == 2) toggleMirrorFocusOutputMovesPositions();
                    else if (which == 3) toggleMirrorPanelIconInfo(white, false);
                    else if (which == 4) toggleMirrorPanelInfoVariation(white, false);
                }).show();
    }

    private void toggleMirrorFocusOutputMovesPositions() {
        if (mirrorPage == null || mirrorFocusAnalysisContainer == null || mirrorFocusMovesScroll == null) return;
        if (mirrorBoardOnlyMode) return; // Keep Mirror Focus Mode's board-only composition untouched.
        int analysisIndex = mirrorPage.indexOfChild(mirrorFocusAnalysisContainer);
        int movesIndex = mirrorPage.indexOfChild(mirrorFocusMovesScroll);
        if (analysisIndex < 0 || movesIndex < 0 || analysisIndex == movesIndex) return;
        View analysis = mirrorFocusAnalysisContainer;
        View moves = mirrorFocusMovesScroll;
        ViewGroup.LayoutParams analysisParams = analysis.getLayoutParams();
        ViewGroup.LayoutParams movesParams = moves.getLayoutParams();
        int low = Math.min(analysisIndex, movesIndex);
        int high = Math.max(analysisIndex, movesIndex);
        View lowView = analysisIndex < movesIndex ? analysis : moves;
        View highView = analysisIndex < movesIndex ? moves : analysis;
        ViewGroup.LayoutParams lowParams = analysisIndex < movesIndex ? analysisParams : movesParams;
        ViewGroup.LayoutParams highParams = analysisIndex < movesIndex ? movesParams : analysisParams;
        mirrorPage.removeView(highView);
        mirrorPage.removeView(lowView);
        mirrorPage.addView(highView, low, highParams);
        mirrorPage.addView(lowView, high, lowParams);
        mirrorPage.requestLayout();
    }

    private void installMirrorPlayerOutputGestures() {
        attachMirrorOutputGesture(mirrorBlackAnalysisText);
        attachMirrorOutputGesture(mirrorBlackPvText);
        attachMirrorOutputGesture(mirrorBlackIcon);
        attachMirrorOutputGesture(mirrorWhiteAnalysisText);
        attachMirrorOutputGesture(mirrorWhitePvText);
        attachMirrorOutputGesture(mirrorWhiteIcon);
        attachMirrorOutputGesture(mirrorFocusAnalysisText);
        attachMirrorOutputGesture(mirrorFocusAnalysisPvText);
        attachMirrorOutputGesture(mirrorFocusEngineTitleRow);
        // Double-tap on the Moves region still returns to the regular output view,
        // but a single tap there must not resize engine-output panels.
        attachMirrorOutputGesture(mirrorFocusMovesText, false);
    }

    private void attachMirrorOutputGesture(View target) {
        attachMirrorOutputGesture(target, true);
    }

    private void attachMirrorOutputGesture(View target, boolean singleTapResizesOutput) {
        if (target == null) return;
        GestureDetector detector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(android.view.MotionEvent event) { return true; }
            @Override public boolean onSingleTapConfirmed(android.view.MotionEvent event) {
                if (!singleTapResizesOutput) return false;
                toggleMirrorPlayerPanelsCompact();
                return true;
            }
            @Override public boolean onDoubleTap(android.view.MotionEvent event) {
                toggleMirrorEngineOutputMode();
                return true;
            }
        });
        target.setClickable(true);
        target.setLongClickable(true);
        target.setOnTouchListener((view, event) -> {
            detector.onTouchEvent(event);
            return false; // Preserve scroll handling and allow the output's long-press menu.
        });
    }

    private void restoreMirrorPanelLayoutPreferences(SharedPreferences prefs) {
        // Apply the requested existing layout preset once, even for users whose
        // older saved preference values were different. Subsequent user changes
        // are preserved independently for normal and double-tap output rows.
        final int layoutPresetVersion = 3;
        if (prefs.getInt("mirrorPanelLayoutPresetVersion", 0) < layoutPresetVersion) {
            mirrorPlayerPanelsCompact = false;
            // Final default: enable both requested swaps in normal and double-tap
            // Mirror Board. Keep the large engine panel size.
            mirrorBlackIconAfterInfo = true;
            mirrorWhiteIconAfterInfo = true;
            mirrorFocusIconAfterInfo = true;
            mirrorBlackInfoAfterVariation = true;
            mirrorWhiteInfoAfterVariation = true;
            mirrorFocusInfoAfterVariation = true;
            prefs.edit()
                    .putBoolean("mirrorPlayerPanelsCompact", false)
                    .putBoolean("mirrorBlackIconAfterInfo", true)
                    .putBoolean("mirrorWhiteIconAfterInfo", true)
                    .putBoolean("mirrorFocusIconAfterInfo", true)
                    .putBoolean("mirrorBlackInfoAfterVariation", true)
                    .putBoolean("mirrorWhiteInfoAfterVariation", true)
                    .putBoolean("mirrorFocusInfoAfterVariation", true)
                    .putInt("mirrorPanelLayoutPresetVersion", layoutPresetVersion)
                    .apply();
            return;
        }
        mirrorPlayerPanelsCompact = prefs.getBoolean("mirrorPlayerPanelsCompact", false);
        mirrorBlackIconAfterInfo = prefs.getBoolean("mirrorBlackIconAfterInfo", true);
        mirrorWhiteIconAfterInfo = prefs.getBoolean("mirrorWhiteIconAfterInfo", true);
        mirrorFocusIconAfterInfo = prefs.getBoolean("mirrorFocusIconAfterInfo", true);
        mirrorBlackInfoAfterVariation = prefs.getBoolean("mirrorBlackInfoAfterVariation", false);
        mirrorWhiteInfoAfterVariation = prefs.getBoolean("mirrorWhiteInfoAfterVariation", false);
        mirrorFocusInfoAfterVariation = prefs.getBoolean("mirrorFocusInfoAfterVariation", false);
    }

    private void saveMirrorPanelLayoutPreferences() {
        getPreferences(MODE_PRIVATE).edit()
                .putBoolean("mirrorPlayerPanelsCompact", mirrorPlayerPanelsCompact)
                .putBoolean("mirrorBlackIconAfterInfo", mirrorBlackIconAfterInfo)
                .putBoolean("mirrorWhiteIconAfterInfo", mirrorWhiteIconAfterInfo)
                .putBoolean("mirrorFocusIconAfterInfo", mirrorFocusIconAfterInfo)
                .putBoolean("mirrorBlackInfoAfterVariation", mirrorBlackInfoAfterVariation)
                .putBoolean("mirrorWhiteInfoAfterVariation", mirrorWhiteInfoAfterVariation)
                .putBoolean("mirrorFocusInfoAfterVariation", mirrorFocusInfoAfterVariation)
                .apply();
    }

    private void toggleMirrorPlayerPanelsCompact() {
        mirrorPlayerPanelsCompact = !mirrorPlayerPanelsCompact;
        saveMirrorPanelLayoutPreferences();
        applyMirrorPlayerPanelSizes();
    }

    private void applyMirrorPlayerPanelSizes() {
        // The engine name is already in the player row. Use the panel height only
        // for the four metrics lines, scrollable PV, evaluation bar, and padding.
        int analysisHeight = mirrorPlayerPanelsCompact ? dp(64) : dp(80);
        int playerHeight = analysisHeight + dp(16); // 8dp eval bar + 8dp vertical padding
        int titleHeight = mirrorPlayerPanelsCompact ? dp(16) : dp(24);
        float metricTextSize = mirrorPlayerPanelsCompact ? 12.0f : 16.0f;
        float variationTextSize = mirrorPlayerPanelsCompact ? 12.0f : 14.0f;
        if (mirrorBlackOutputPanel != null && mirrorBlackOutputPanel.getLayoutParams() != null) {
            ViewGroup.LayoutParams lp = mirrorBlackOutputPanel.getLayoutParams(); lp.height = playerHeight;
            mirrorBlackOutputPanel.setLayoutParams(lp);
        }
        if (mirrorWhiteOutputPanel != null && mirrorWhiteOutputPanel.getLayoutParams() != null) {
            ViewGroup.LayoutParams lp = mirrorWhiteOutputPanel.getLayoutParams(); lp.height = playerHeight;
            mirrorWhiteOutputPanel.setLayoutParams(lp);
        }
        if (mirrorBlackAnalysisText != null) {
            mirrorBlackAnalysisText.setTextSize(metricTextSize);
            mirrorBlackAnalysisText.setLineSpacing(0, 1.0f);
            mirrorBlackAnalysisText.setIncludeFontPadding(false);
        }
        if (mirrorWhiteAnalysisText != null) {
            mirrorWhiteAnalysisText.setTextSize(metricTextSize);
            mirrorWhiteAnalysisText.setLineSpacing(0, 1.0f);
            mirrorWhiteAnalysisText.setIncludeFontPadding(false);
        }
        if (mirrorBlackPvText != null) mirrorBlackPvText.setTextSize(variationTextSize);
        if (mirrorWhitePvText != null) mirrorWhitePvText.setTextSize(variationTextSize);
        if (mirrorBlackAnalysisScroll != null && mirrorBlackAnalysisScroll.getLayoutParams() != null) {
            ViewGroup.LayoutParams lp = mirrorBlackAnalysisScroll.getLayoutParams(); lp.height = analysisHeight;
            mirrorBlackAnalysisScroll.setLayoutParams(lp);
        }
        if (mirrorWhiteAnalysisScroll != null && mirrorWhiteAnalysisScroll.getLayoutParams() != null) {
            ViewGroup.LayoutParams lp = mirrorWhiteAnalysisScroll.getLayoutParams(); lp.height = analysisHeight;
            mirrorWhiteAnalysisScroll.setLayoutParams(lp);
        }
        if (mirrorBlackIcon != null && mirrorBlackIcon.getLayoutParams() != null) {
            ViewGroup.LayoutParams lp = mirrorBlackIcon.getLayoutParams();
            lp.width = mirrorPlayerPanelsCompact ? dp(64) : dp(80);
            lp.height = -1;
            mirrorBlackIcon.setLayoutParams(lp);
        }
        if (mirrorWhiteIcon != null && mirrorWhiteIcon.getLayoutParams() != null) {
            ViewGroup.LayoutParams lp = mirrorWhiteIcon.getLayoutParams();
            lp.width = mirrorPlayerPanelsCompact ? dp(64) : dp(80);
            lp.height = -1;
            mirrorWhiteIcon.setLayoutParams(lp);
        }

        // Double-tap focus mode retains its engine name and uses the same fixed
        // metrics/PV row geometry as the normal analysis output.
        int focusMovesHeight = mirrorFocusMovesText == null ? dp(116) :
                (mirrorFocusMovesText.getLineHeight() * 6 + mirrorFocusMovesText.getPaddingTop() +
                        mirrorFocusMovesText.getPaddingBottom()); // Exactly six visible lines; remainder scrolls.
        if (mirrorFocusEngineTitleRow != null && mirrorFocusEngineTitleRow.getLayoutParams() != null) {
            ViewGroup.LayoutParams lp = mirrorFocusEngineTitleRow.getLayoutParams(); lp.height = titleHeight;
            mirrorFocusEngineTitleRow.setLayoutParams(lp);
        }
        if (mirrorFocusAnalysisRow != null && mirrorFocusAnalysisRow.getLayoutParams() != null) {
            ViewGroup.LayoutParams lp = mirrorFocusAnalysisRow.getLayoutParams(); lp.height = analysisHeight;
            mirrorFocusAnalysisRow.setLayoutParams(lp);
        }
        if (mirrorFocusAnalysisText != null) {
            mirrorFocusAnalysisText.setTextSize(metricTextSize);
            mirrorFocusAnalysisText.setLineSpacing(0, 1.0f);
            mirrorFocusAnalysisText.setIncludeFontPadding(false);
        }
        if (mirrorFocusAnalysisPvText != null) {
            mirrorFocusAnalysisPvText.setTextSize(variationTextSize);
            mirrorFocusAnalysisPvText.setIncludeFontPadding(false);
        }
        if (mirrorFocusEngineTitle != null) mirrorFocusEngineTitle.setTextSize(mirrorPlayerPanelsCompact ? 13 : 16);
        if (mirrorFocusEngineIcon != null && mirrorFocusEngineIcon.getLayoutParams() != null) {
            ViewGroup.LayoutParams lp = mirrorFocusEngineIcon.getLayoutParams();
            lp.width = mirrorPlayerPanelsCompact ? dp(64) : dp(80);
            lp.height = -1;
            mirrorFocusEngineIcon.setLayoutParams(lp);
        }
        if (mirrorFocusMovesScroll != null && mirrorFocusMovesScroll.getLayoutParams() != null) {
            ViewGroup.LayoutParams lp = mirrorFocusMovesScroll.getLayoutParams(); lp.height = focusMovesHeight;
            mirrorFocusMovesScroll.setLayoutParams(lp);
        }
    }

    private boolean mirrorNavigationOutputWhiteSide() {
        ArrayList<Position> positions = currentMirrorPositions();
        if (positions.isEmpty()) return true;
        int selected = Math.max(0, Math.min(mirrorDisplayedPly(), positions.size() - 1));
        return selected > 0 ? positions.get(selected - 1).whiteMove : positions.get(0).whiteMove;
    }

    private void applyMirrorEngineOutputVisibility() {
        // Double-tap review borrows the real White/Black engine output panel. It never
        // displays the separate focus-analysis container at the same time.
        boolean navigationWhite = mirrorNavigationMode && mirrorNavigationOutputWhiteSide();
        int shortcutsVisibility = mirrorEngineOutputHidden || mirrorNavigationMode ? View.GONE : View.VISIBLE;
        if (mirrorBoardShortcuts != null) mirrorBoardShortcuts.setVisibility(shortcutsVisibility);
        // Player rows remain anchored to the board in every mode.
        if (mirrorBlackRow != null) mirrorBlackRow.setVisibility(View.VISIBLE);
        if (mirrorWhiteRow != null) mirrorWhiteRow.setVisibility(View.VISIBLE);
        if (mirrorNavigationMode) {
            // Double-tap mode is deliberately navigation-only. Do not reuse either
            // engine output panel or create/show the focus-analysis substitute here.
            if (mirrorWhiteOutputPanel != null) mirrorWhiteOutputPanel.setVisibility(View.GONE);
            if (mirrorBlackOutputPanel != null) mirrorBlackOutputPanel.setVisibility(View.GONE);
            if (mirrorFocusAnalysisContainer != null) mirrorFocusAnalysisContainer.setVisibility(View.GONE);
        } else {
            int playerVisibility = mirrorEngineOutputHidden ? View.GONE : View.VISIBLE;
            if (mirrorBlackOutputPanel != null) mirrorBlackOutputPanel.setVisibility(playerVisibility);
            if (mirrorWhiteOutputPanel != null) mirrorWhiteOutputPanel.setVisibility(playerVisibility);
            if (mirrorFocusAnalysisContainer != null) mirrorFocusAnalysisContainer.setVisibility(
                    mirrorEngineOutputHidden ? View.VISIBLE : View.GONE);
            if (!mirrorEngineOutputHidden) {
                updateMirrorEngineOutputDisplays(lastWhiteAnalysis, lastBlackAnalysis);
                updateMirrorEvaluationBars(lastWhiteAnalysis, lastBlackAnalysis);
            }
        }
        if (mirrorFocusMovesScroll != null) mirrorFocusMovesScroll.setVisibility(mirrorEngineOutputHidden && !mirrorNavigationMode ? View.VISIBLE : View.GONE);
    }

    private void toggleMirrorEngineOutputMode() {
        mirrorEngineOutputHidden = !mirrorEngineOutputHidden;
        applyMirrorEngineOutputVisibility();
        if (mirrorEngineOutputHidden) updateMirrorFocusPanels();
        applyMirrorPlayerPanelSizes();
        mirrorPage.requestLayout();
    }

    private boolean isMirrorExternalAnalysisActive() {
        return mirrorAnalysisOn && mirrorAnalysisEngineActive &&
                mirrorAnalysisEngineId != null && !mirrorAnalysisEngineId.isEmpty();
    }

    /** External analysis replaces only the selected move's displayed comment; PGN data is untouched. */
    private String mirrorDisplayedMoveComment(String storedComment, boolean whiteMove, int ply) {
        if (ply == mirrorDisplayedPly() && isMirrorExternalAnalysisActive()) {
            String output = mirrorAnalysisCoreOutput == null ? "" : mirrorAnalysisCoreOutput.trim();
            return output.isEmpty() ? "External analysis running…" : output;
        }
        return mirrorMoveCommentDisplay(storedComment, whiteMove);
    }

    private void updateMirrorFocusPanels() {
        if (mirrorFocusAnalysisText == null || mirrorFocusMovesText == null) return;
        if (mirrorNavigationMode) {
            if (mirrorWhiteOutputPanel != null) mirrorWhiteOutputPanel.setVisibility(View.GONE);
            if (mirrorBlackOutputPanel != null) mirrorBlackOutputPanel.setVisibility(View.GONE);
            if (mirrorFocusAnalysisContainer != null) mirrorFocusAnalysisContainer.setVisibility(View.GONE);
            return;
        }
        ArrayList<Position> positions = currentMirrorPositions();
        int selected = positions.isEmpty() ? 0 : Math.max(0, Math.min(mirrorDisplayedPly(), positions.size() - 1));
        boolean whiteToMove = positions.isEmpty() || positions.get(selected).whiteMove;
        boolean outputWhite = mirrorNavigationMode && selected > 0
                ? positions.get(selected - 1).whiteMove : whiteToMove;
        mirrorFocusAnalysisWhite = outputWhite;
        String engineName;
        String analysis;
        String focusEngineId;
        boolean useExternalAnalysis = false;
        ArrayList<String> savedComments = mirrorReplayMode ? mirrorReplayComments : mirrorLiveComments;
        if (mirrorNavigationMode) {
            if (isMirrorExternalAnalysisActive()) {
                // The existing focused output panel is shared by replayed game comments
                // and the optional external engine. External output has display priority
                // only while its toggle is enabled; saved PGN comments remain unchanged.
                useExternalAnalysis = true;
                engineName = mirrorAnalysisEngineNameForId(mirrorAnalysisEngineId);
                focusEngineId = mirrorAnalysisEngineId;
                analysis = mirrorAnalysisCoreOutput == null ? "" : mirrorAnalysisCoreOutput;
            } else {
                engineName = outputWhite ? mirrorNavigationWhiteName : mirrorNavigationBlackName;
                focusEngineId = outputWhite ? mirrorNavigationWhiteEngineId : mirrorNavigationBlackEngineId;
                String savedComment = selected > 0 && selected - 1 < savedComments.size()
                        ? savedComments.get(selected - 1) : "";
                analysis = mirrorNavigationCommentsLoading ? "" : analysisFromComment(savedComment);
                if (mirrorNavigationCommentsLoading || savedComment == null || savedComment.trim().isEmpty() ||
                        (!savedComment.equalsIgnoreCase("Book.") && !hasSavedAnalysisFields(savedComment)))
                    analysis = "";
            }
            String whiteClock = "—", blackClock = "—", whiteMoveTime = "—", blackMoveTime = "—";
            for (int i = 0; i < selected && i < savedComments.size() && i < positions.size(); i++) {
                String moveComment = savedComments.get(i);
                boolean moverIsWhite = positions.get(i).whiteMove;
                String moveClock = findCommentValue(moveComment, "Clock|clk");
                String moveTime = findCommentValue(moveComment, "MoveTime|Time|TimeUsed|t");
                if (moverIsWhite) {
                    if (!moveClock.isEmpty()) whiteClock = moveClock;
                    if (!moveTime.isEmpty()) whiteMoveTime = moveTime;
                } else {
                    if (!moveClock.isEmpty()) blackClock = moveClock;
                    if (!moveTime.isEmpty()) blackMoveTime = moveTime;
                }
            }
            if (mirrorWhiteClock != null) mirrorWhiteClock.setText(whiteClock);
            if (mirrorBlackClock != null) mirrorBlackClock.setText(blackClock);
            if (mirrorWhiteMoveTime != null) mirrorWhiteMoveTime.setText(whiteMoveTime);
            if (mirrorBlackMoveTime != null) mirrorBlackMoveTime.setText(blackMoveTime);
        } else {
            boolean externalAnalysisActive = isMirrorExternalAnalysisActive() &&
                    mirrorLatestAnalysisOutput != null && !mirrorLatestAnalysisOutput.startsWith("Select an engine");
            useExternalAnalysis = mirrorBranchMode || externalAnalysisActive;
            if (useExternalAnalysis) {
                engineName = mirrorAnalysisEngineNameForId(mirrorAnalysisEngineId);
                // The board-side panel follows only the newest main (PV 1)
                // analysis. The Moves analysis tab keeps its separate depth
                // history and MultiPV list in mirrorLatestAnalysisOutput.
                analysis = mirrorAnalysisCoreOutput;
                focusEngineId = mirrorAnalysisEngineId;
            } else if (mirrorReplayMode) {
                engineName = whiteToMove ? mirrorReplayWhiteName : mirrorReplayBlackName;
                analysis = whiteToMove ? lastWhiteAnalysis : lastBlackAnalysis;
                focusEngineId = whiteToMove ? mirrorReplayWhiteEngineId : mirrorReplayBlackEngineId;
            } else {
                engineName = whiteToMove ? currentWhiteName : currentBlackName;
                analysis = whiteToMove ? lastWhiteAnalysis : lastBlackAnalysis;
                focusEngineId = whiteToMove ? currentWhiteEngineId : currentBlackEngineId;
            }
        }
        String iconLookupName = engineName;
        engineName = splitMirrorEngineNameVersion(engineName)[0];
        if (analysis == null) analysis = "";
        String focusTitle = useExternalAnalysis ? "Analysis: " + engineName : (outputWhite ? "White: " : "Black: ") + engineName;
        if (mirrorFocusEngineTitle != null) mirrorFocusEngineTitle.setText(focusTitle);
        if (mirrorFocusEngineIcon != null) setMirrorPlayerIcon(mirrorFocusEngineIcon, focusEngineId, iconLookupName);
        if (mirrorFocusEvaluationBar != null) mirrorFocusEvaluationBar.setEngineAnalysis(analysis, outputWhite);
        mirrorFocusAnalysisText.setText(analysis.isEmpty()
                ? (useExternalAnalysis ? "External analysis running…" : "")
                : formatMirrorMetrics(analysis));
        if (mirrorFocusAnalysisPvText != null) {
            String boardVariation = analysis.isEmpty() ? "" :
                    (useExternalAnalysis ? extractLatestMainMirrorVariation(analysis) : extractMirrorMoveVariation(analysis));
            mirrorFocusAnalysisPvText.setText(boardVariation);
        }
        if (mirrorNavigationMode) {
            // Double-tap review exposes only the move navigation controls. Analysis
            // remains available as a temporary selected-move comment when enabled,
            // but no engine information panel is created or made visible.
            if (mirrorWhiteOutputPanel != null) mirrorWhiteOutputPanel.setVisibility(View.GONE);
            if (mirrorBlackOutputPanel != null) mirrorBlackOutputPanel.setVisibility(View.GONE);
            if (mirrorFocusAnalysisContainer != null) mirrorFocusAnalysisContainer.setVisibility(View.GONE);
        }

        ArrayList<String> sans = currentMirrorSanMoves(null);
        ArrayList<Position> history = currentMirrorPositions();
        int selectedPly = mirrorDisplayedPly();
        int previousSelectedPly = mirrorFocusMovesLastSelectedPly;
        int oldScrollY = mirrorFocusMovesScroll == null ? 0 : mirrorFocusMovesScroll.getScrollY();
        View oldChild = mirrorFocusMovesScroll == null ? null : mirrorFocusMovesScroll.getChildAt(0);
        int oldMaxScroll = oldChild == null ? 0 : Math.max(0, oldChild.getHeight() - mirrorFocusMovesScroll.getHeight());
        boolean wasFollowingLatest = oldMaxScroll == 0 || oldScrollY >= oldMaxScroll - dp(20);
        android.text.SpannableStringBuilder moves = new android.text.SpannableStringBuilder();
        int selectedStart = -1, selectedEnd = -1;
        for (int i = 0; i < sans.size(); i++) {
            if (moves.length() > 0) moves.append("  ");
            int moveStart = moves.length();
            Position before = i < history.size() ? history.get(i) : null;
            int moveNumber = before == null ? (i / 2) + 1 : before.fullMoveCounter;
            boolean whiteMove = before == null ? ((i & 1) == 0) : before.whiteMove;
            boolean followsWhiteInSameFullMove = !whiteMove && i > 0 && i - 1 < history.size() &&
                    history.get(i - 1).whiteMove && history.get(i - 1).fullMoveCounter == moveNumber;
            if (whiteMove || !followsWhiteInSameFullMove) moves.append(String.valueOf(moveNumber)).append(whiteMove ? "." : "...");
            moves.append(sans.get(i));
            int moveEnd = moves.length();
            if (i + 1 == selectedPly) {
                selectedStart = moveStart;
                selectedEnd = moveEnd;
            }
        }
        if (moves.length() == 0) moves.append("No moves yet.");
        else if (selectedStart >= 0 && selectedEnd > selectedStart) {
            moves.setSpan(new android.text.style.BackgroundColorSpan(0xFF496B50), selectedStart, selectedEnd,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            moves.setSpan(new android.text.style.ForegroundColorSpan(0xFFFFFFFF), selectedStart, selectedEnd,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            moves.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), selectedStart, selectedEnd,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        mirrorFocusMovesText.setText(moves);
        mirrorFocusMovesLastSelectedPly = selectedPly;
        final int preserveScrollY = oldScrollY;
        final boolean reviewingMoveSelection = mirrorNavigationMode || mirrorReplayMode || mirrorBranchMode || mirrorPly >= 0;
        final boolean followLatest = wasFollowingLatest && !reviewingMoveSelection;
        final boolean moveSelectionChanged = previousSelectedPly != Integer.MIN_VALUE &&
                previousSelectedPly != selectedPly && reviewingMoveSelection;
        final int highlightedOffset = selectedStart;
        if (mirrorFocusMovesScroll != null) mirrorFocusMovesScroll.post(() -> {
            View child = mirrorFocusMovesScroll.getChildAt(0);
            if (child == null) return;
            int maxScroll = Math.max(0, child.getHeight() - mirrorFocusMovesScroll.getHeight());
            if (followLatest) {
                mirrorFocusMovesScroll.scrollTo(0, maxScroll);
            } else if (moveSelectionChanged && highlightedOffset >= 0) {
                android.text.Layout layout = mirrorFocusMovesText.getLayout();
                if (layout != null) {
                    int line = layout.getLineForOffset(Math.min(highlightedOffset, moves.length()));
                    int lineTop = layout.getLineTop(line);
                    int lineBottom = layout.getLineBottom(line);
                    int viewportTop = mirrorFocusMovesScroll.getScrollY();
                    int viewportBottom = viewportTop + mirrorFocusMovesScroll.getHeight();
                    if (lineTop < viewportTop) mirrorFocusMovesScroll.scrollTo(0, lineTop);
                    else if (lineBottom > viewportBottom)
                        mirrorFocusMovesScroll.scrollTo(0, Math.min(maxScroll, lineBottom - mirrorFocusMovesScroll.getHeight()));
                }
            } else {
                mirrorFocusMovesScroll.scrollTo(0, Math.min(preserveScrollY, maxScroll));
            }
        });
    }

    /** Return only the current principal (PV 1) line for the board-side display. */
    private String extractLatestMainMirrorVariation(String analysis) {
        if (analysis == null || analysis.trim().isEmpty()) return "—";
        java.util.regex.Matcher mainPv = java.util.regex.Pattern.compile(
                "(?im)^\\s*PV\\s*1\\s+(.*)$").matcher(analysis);
        if (mainPv.find()) {
            String variation = mainPv.group(1).trim();
            return variation.isEmpty() ? "—" : variation;
        }
        // Never fall back to the complete output here: it may contain the
        // optional depth-history section used only by the Moves analysis tab.
        // If there is no current PV 1 line, leave the board variation blank.
        return "—";
    }

    private boolean hasSavedAnalysisFields(String comment) {
        return comment != null && (!findCommentValue(comment, "Eval|eval").isEmpty() ||
                !findCommentValue(comment, "Depth|D").isEmpty() ||
                !findCommentValue(comment, "Nodes|N").isEmpty() ||
                !findCommentValue(comment, "NPS|nps").isEmpty());
    }

    private TextView createMirrorVariationText() {
        TextView variation = text("—");
        variation.setTextSize(13.0f);
        variation.setTextColor(0xFFF0F2F4);
        variation.setGravity(android.view.Gravity.TOP | android.view.Gravity.LEFT);
        variation.setIncludeFontPadding(true);
        variation.setSingleLine(false);
        variation.setMaxLines(1000);
        variation.setHorizontallyScrolling(false);
        if (android.os.Build.VERSION.SDK_INT >= 23)
            variation.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE);
        variation.setPadding(dp(5), dp(1), dp(3), dp(4));
        return variation;
    }

    /** A bounded inner vertical scroller should not hand its gesture to the page scroller. */
    private ScrollView createIndependentVerticalScrollView() {
        return new ScrollView(this) {
            @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
                if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN && getParent() != null &&
                        (canScrollVertically(-1) || canScrollVertically(1)))
                    getParent().requestDisallowInterceptTouchEvent(true);
                else if ((event.getActionMasked() == android.view.MotionEvent.ACTION_UP ||
                        event.getActionMasked() == android.view.MotionEvent.ACTION_CANCEL) && getParent() != null)
                    getParent().requestDisallowInterceptTouchEvent(false);
                return super.dispatchTouchEvent(event);
            }
        };
    }

    private ScrollView createMirrorVariationScroll(TextView variation) {
        ScrollView scroll = createIndependentVerticalScrollView();
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroll.addView(variation, new ScrollView.LayoutParams(-1, -2));
        return scroll;
    }

    /** Mirror Board: four fixed metric lines and a separately scrollable PV. */
    private LinearLayout createMirrorAnalysisRow(TextView metrics, ScrollView variation) {
        return createMirrorAnalysisRow(metrics, variation, null);
    }

    /** Keep the engine icon inside the metrics/info column, not inside PV width. */
    private LinearLayout createMirrorAnalysisRow(TextView metrics, ScrollView variation, ImageView icon) {
        return createMirrorAnalysisRow(metrics, variation, icon, false, true);
    }

    private LinearLayout createMirrorAnalysisRow(TextView metrics, ScrollView variation, ImageView icon,
                                                  boolean iconAfterInfo, boolean infoAfterVariation) {
        LinearLayout row = new LinearLayout(this);
        configureMirrorAnalysisRow(row, metrics, variation, icon, iconAfterInfo, infoAfterVariation);
        return row;
    }

    private void removeViewFromParent(View view) {
        if (view != null && view.getParent() instanceof ViewGroup)
            ((ViewGroup)view.getParent()).removeView(view);
    }

    /** Rebuild only the arrangement of a panel; analysis data and child instances are retained. */
    private void configureMirrorAnalysisRow(LinearLayout row, TextView metrics, ScrollView variation,
                                            ImageView icon, boolean iconAfterInfo, boolean infoAfterVariation) {
        if (row == null || metrics == null || variation == null) return;
        removeViewFromParent(metrics);
        removeViewFromParent(variation);
        removeViewFromParent(icon);
        row.removeAllViews();
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.TOP);
        metrics.setSingleLine(false);
        metrics.setMinLines(4);
        metrics.setMaxLines(4);
        metrics.setEllipsize(null);
        metrics.setGravity(android.view.Gravity.TOP | android.view.Gravity.LEFT);
        metrics.setIncludeFontPadding(false);
        metrics.setLineSpacing(0, 1.0f);

        LinearLayout infoArea = new LinearLayout(this);
        infoArea.setOrientation(LinearLayout.HORIZONTAL);
        infoArea.setGravity(android.view.Gravity.TOP | android.view.Gravity.CENTER_VERTICAL);
        if (icon != null) {
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
                    dp(mirrorPlayerPanelsCompact ? 64 : 80), -1);
            iconParams.gravity = android.view.Gravity.CENTER_VERTICAL;
            if (iconAfterInfo) {
                iconParams.leftMargin = dp(3);
                infoArea.addView(metrics, new LinearLayout.LayoutParams(0, -1, 1.0f));
                infoArea.addView(icon, iconParams);
            } else {
                iconParams.rightMargin = dp(3);
                infoArea.addView(icon, iconParams);
                infoArea.addView(metrics, new LinearLayout.LayoutParams(0, -1, 1.0f));
            }
        } else {
            infoArea.addView(metrics, new LinearLayout.LayoutParams(-1, -1));
        }

        // The evaluation bar's 0.00 marker is the visual boundary: each side gets
        // exactly half the panel width, so neither output nor PV can invade the other.
        // Keep both halves shrinkable: long engine metrics or PV text must not
        // override the evaluation bar midpoint during the horizontal measure pass.
        infoArea.setMinimumWidth(0);
        variation.setMinimumWidth(0);
        variation.setClipToPadding(true);
        float infoWeight = 1.0f;
        float variationWeight = 1.0f;
        View first = infoAfterVariation ? (View)variation : infoArea;
        View second = infoAfterVariation ? (View)infoArea : variation;
        float firstWeight = infoAfterVariation ? variationWeight : infoWeight;
        float secondWeight = infoAfterVariation ? infoWeight : variationWeight;
        row.addView(first, new LinearLayout.LayoutParams(0, -1, firstWeight));
        row.addView(second, new LinearLayout.LayoutParams(0, -1, secondWeight));
    }

    private void toggleMirrorPanelIconInfo(boolean white, boolean focused) {
        if (focused) {
            mirrorFocusIconAfterInfo = !mirrorFocusIconAfterInfo;
            configureMirrorAnalysisRow(mirrorFocusAnalysisRow, mirrorFocusAnalysisText,
                    mirrorFocusAnalysisPvScroll, mirrorFocusEngineIcon,
                    mirrorFocusIconAfterInfo, mirrorFocusInfoAfterVariation);
        } else if (white) {
            mirrorWhiteIconAfterInfo = !mirrorWhiteIconAfterInfo;
            configureMirrorAnalysisRow(mirrorWhiteAnalysisScroll, mirrorWhiteAnalysisText,
                    mirrorWhitePvScroll, mirrorWhiteIcon,
                    mirrorWhiteIconAfterInfo, mirrorWhiteInfoAfterVariation);
        } else {
            mirrorBlackIconAfterInfo = !mirrorBlackIconAfterInfo;
            configureMirrorAnalysisRow(mirrorBlackAnalysisScroll, mirrorBlackAnalysisText,
                    mirrorBlackPvScroll, mirrorBlackIcon,
                    mirrorBlackIconAfterInfo, mirrorBlackInfoAfterVariation);
        }
        saveMirrorPanelLayoutPreferences();
        applyMirrorPlayerPanelSizes();
    }

    private void toggleMirrorPanelInfoVariation(boolean white, boolean focused) {
        if (focused) {
            mirrorFocusInfoAfterVariation = !mirrorFocusInfoAfterVariation;
            configureMirrorAnalysisRow(mirrorFocusAnalysisRow, mirrorFocusAnalysisText,
                    mirrorFocusAnalysisPvScroll, mirrorFocusEngineIcon,
                    mirrorFocusIconAfterInfo, mirrorFocusInfoAfterVariation);
        } else if (white) {
            mirrorWhiteInfoAfterVariation = !mirrorWhiteInfoAfterVariation;
            configureMirrorAnalysisRow(mirrorWhiteAnalysisScroll, mirrorWhiteAnalysisText,
                    mirrorWhitePvScroll, mirrorWhiteIcon,
                    mirrorWhiteIconAfterInfo, mirrorWhiteInfoAfterVariation);
        } else {
            mirrorBlackInfoAfterVariation = !mirrorBlackInfoAfterVariation;
            configureMirrorAnalysisRow(mirrorBlackAnalysisScroll, mirrorBlackAnalysisText,
                    mirrorBlackPvScroll, mirrorBlackIcon,
                    mirrorBlackIconAfterInfo, mirrorBlackInfoAfterVariation);
        }
        saveMirrorPanelLayoutPreferences();
        applyMirrorPlayerPanelSizes();
    }

    private void updateMirrorEvaluationBars(String whiteAnalysis, String blackAnalysis) {
        if (mirrorWhiteEvaluationBar != null) mirrorWhiteEvaluationBar.setEngineAnalysis(whiteAnalysis, true);
        if (mirrorBlackEvaluationBar != null) mirrorBlackEvaluationBar.setEngineAnalysis(blackAnalysis, false);
    }

    /** Long-press the mirror board to focus on the board while keeping both player clocks and names visible. */
    private void toggleMirrorBoardOnlyMode() {
        if (mirrorPage == null || mirrorBoardFrame == null || mirrorScroll == null) return;
        mirrorBoardOnlyMode = !mirrorBoardOnlyMode;
        if (mirrorBoardOnlyMode) {
            mirrorScroll.scrollTo(0, 0);
            mirrorScroll.setFillViewport(true);
            mirrorPage.setGravity(android.view.Gravity.CENTER);
            for (int i = 0; i < mirrorPage.getChildCount(); i++) {
                View child = mirrorPage.getChildAt(i);
                child.setVisibility((child == mirrorBoardFrame || child == mirrorBlackPlayerGroup ||
                        child == mirrorWhitePlayerGroup) ? View.VISIBLE : View.GONE);
            }
            if (mirrorBlackOutputPanel != null) mirrorBlackOutputPanel.setVisibility(View.GONE);
            if (mirrorWhiteOutputPanel != null) mirrorWhiteOutputPanel.setVisibility(View.GONE);
            if (mirrorBlackRow != null) mirrorBlackRow.setVisibility(View.VISIBLE);
            if (mirrorWhiteRow != null) mirrorWhiteRow.setVisibility(View.VISIBLE);
            if (mirrorBlackIcon != null) mirrorBlackIcon.setVisibility(View.GONE);
            if (mirrorWhiteIcon != null) mirrorWhiteIcon.setVisibility(View.GONE);
            if (mirrorFocusBlackIcon != null) setMirrorPlayerIcon(mirrorFocusBlackIcon, currentBlackEngineId, currentBlackName);
            if (mirrorFocusWhiteIcon != null) setMirrorPlayerIcon(mirrorFocusWhiteIcon, currentWhiteEngineId, currentWhiteName);
            if (mirrorControlBar != null) mirrorControlBar.setVisibility(View.GONE);
        } else {
            mirrorPage.setGravity(android.view.Gravity.NO_GRAVITY);
            for (int i = 0; i < mirrorPage.getChildCount(); i++)
                mirrorPage.getChildAt(i).setVisibility(View.VISIBLE);
            if (mirrorFocusBlackIcon != null) mirrorFocusBlackIcon.setVisibility(View.GONE);
            if (mirrorFocusWhiteIcon != null) mirrorFocusWhiteIcon.setVisibility(View.GONE);
            if (mirrorBlackIcon != null) setMirrorPlayerIcon(mirrorBlackIcon, currentBlackEngineId, currentBlackName);
            if (mirrorWhiteIcon != null) setMirrorPlayerIcon(mirrorWhiteIcon, currentWhiteEngineId, currentWhiteName);
            applyMirrorEngineOutputVisibility();
            if (mirrorControlBar != null) mirrorControlBar.setVisibility(View.VISIBLE);
            if (mirrorPrimaryControls != null) mirrorPrimaryControls.setVisibility(mirrorNavigationMode ? View.GONE : View.VISIBLE);
            if (mirrorNavigationControls != null) mirrorNavigationControls.setVisibility(mirrorNavigationMode ? View.VISIBLE : View.GONE);
            mirrorScroll.setFillViewport(false);
            applyMirrorPlayerPanelSizes();
            renderMirrorSection();
            updateMirrorGraphScrollSpacer();
        }
        mirrorPage.requestLayout();
    }

    private void refreshMirrorBranchPanels() {
        invalidateMirrorMovesSubCache(0);
        // A graph opened during branch mode must not survive after the temporary
        // branch is discarded; rebuild it from whichever move history is active.
        invalidateMirrorStandingsSubCache(1);
        if (!isMirrorShowing()) return;
        if (mirrorSelectedTab == 0) {
            invalidateMirrorSectionCache(0);
            renderMirrorSection();
        } else if (mirrorSelectedTab == 1 && mirrorStandingsSubTab == 1) {
            invalidateMirrorSectionCache(1);
            renderMirrorSection();
        }
    }

    private ArrayList<MirrorBranchNode> mirrorBranchPath() {
        ArrayList<MirrorBranchNode> path = new ArrayList<>();
        for (MirrorBranchNode n = mirrorBranchCurrent; n != null && n != mirrorBranchRoot; n = n.parent) path.add(n);
        Collections.reverse(path);
        return path;
    }

    private ArrayList<Position> mirrorBranchPositions() {
        ArrayList<Position> out = new ArrayList<>(mirrorBranchBasePositions);
        for (MirrorBranchNode node : mirrorBranchPath()) out.add(new Position(node.position));
        return out;
    }

    private void toggleMirrorBranchMode() {
        if (mirrorBoard == null || mirrorBoardOnlyMode) return;
        if (!mirrorBranchMode) {
            stopMirrorAutoPlay();
            if (runner != null && !runner.isFinished() && !runner.isPaused()) togglePauseResume();
            ArrayList<Position> originalPositions = mirrorReplayMode ? mirrorReplayPositions : mirrorPositions;
            if (originalPositions.isEmpty()) return;
            mirrorBranchOriginalPly = mirrorPly;
            mirrorBranchBasePly = Math.max(0, Math.min(mirrorDisplayedPly(), originalPositions.size() - 1));
            mirrorBranchBasePositions = new ArrayList<>();
            for (int i = 0; i <= mirrorBranchBasePly; i++) mirrorBranchBasePositions.add(new Position(originalPositions.get(i)));
            ArrayList<String> originalMoves = mirrorReplayMode ? new ArrayList<>(mirrorReplayMoves) : currentMirrorSanMoves(null);
            mirrorBranchBaseMoves = new ArrayList<>();
            for (int i = 0; i < Math.min(mirrorBranchBasePly, originalMoves.size()); i++) mirrorBranchBaseMoves.add(originalMoves.get(i));
            mirrorBranchRoot = new MirrorBranchNode(originalPositions.get(mirrorBranchBasePly), null, "", null);
            mirrorBranchCurrent = mirrorBranchRoot;
            mirrorBranchMode = true;
            // A non-negative ply prevents a trailing live-runner callback from
            // replacing this isolated branch board after the pause request.
            mirrorPly = mirrorBranchBasePly;
            mirrorNavigationMode = true;
            mirrorBoard.setClickable(true);
            mirrorBoard.oneTouchMoves = false;
            mirrorPrimaryControls.setVisibility(View.GONE);
            mirrorNavigationControls.setVisibility(View.VISIBLE);
            mirrorBoard.setPosition(new Position(mirrorBranchRoot.position));
            mirrorBoard.setSelection(-1);
            mirrorBoard.setBranchMoves(new ArrayList<>());
            mirrorBoard.setLastMove(mirrorBranchBasePly > 0 ? moveAtPly(mirrorBranchBasePositions, mirrorBranchBasePly) : null);
            mirrorAnalysisHints.clear();
            mirrorBoard.setMoveHints(null);
            if (mirrorAnalysisOn && mirrorAnalysisEngineActive) startMirrorAnalysis();
            Toast.makeText(this, "Tournament paused · temporary variation · use MENU to exit", Toast.LENGTH_SHORT).show();
        } else {
            // Exit discards the in-memory tree and restores exactly the selected
            // original live/replay position. The tournament remains paused.
            stopMirrorAutoPlay();
            mirrorBranchMode = false;
            mirrorNavigationMode = false;
            mirrorBoard.setClickable(false);
            mirrorBoard.oneTouchMoves = false;
            mirrorBoard.setSelection(-1);
            mirrorBoard.setBranchMoves(null);
            mirrorBranchRoot = mirrorBranchCurrent = null;
            mirrorBranchBasePositions.clear();
            mirrorBranchBaseMoves.clear();
            mirrorAnalysisHints.clear();
            mirrorAnalysisHintsByPly.clear();
            synchronized (mirrorAnalysisPvByDepth) { mirrorAnalysisPvByDepth.clear(); }
            mirrorAnalysisOn = false;
            stopMirrorAnalysis();
            setMirrorAnalysisOutput("Analysis off");
            setMirrorShortcutAnalysis("Analysis off · tap to analyse");
            mirrorPly = mirrorBranchOriginalPly;
            Position restore;
            if (mirrorReplayMode) restore = mirrorReplayPositions.get(Math.max(0, Math.min(mirrorPly, mirrorReplayPositions.size() - 1)));
            else restore = mirrorPly < 0 ? latestPosition : mirrorPositions.get(Math.max(0, Math.min(mirrorPly, mirrorPositions.size() - 1)));
            mirrorBoard.setPosition(new Position(restore));
            mirrorBoard.setMoveHints(null);
            mirrorPrimaryControls.setVisibility(View.VISIBLE);
            mirrorNavigationControls.setVisibility(View.GONE);
            updateMirrorLastMove(mirrorPly < 0 ? Math.max(0, mirrorPositions.size() - 1) : mirrorPly, null);
            updateMirrorShortcutAnalysisState();
        }
        updateMirrorMoveStrip();
        updateMirrorNavigationButtons();
        refreshMirrorBranchPanels();
    }

    private void handleMirrorBranchSquareTap(int square) {
        if (!mirrorBranchMode || mirrorBoard == null) return;
        Position before = new Position(mirrorBoard.pos);
        Move move = mirrorBoard.mousePressed(square);
        if (move == null) { mirrorBoard.invalidate(); return; }
        Move legal = null;
        for (Move candidate : new org.petero.droidfish.gamelogic.MoveGen().legalMoves(before)) {
            if (candidate.from == move.from && candidate.to == move.to) {
                if (legal == null || candidate.promoteTo == org.petero.droidfish.gamelogic.Piece.WQUEEN ||
                        candidate.promoteTo == org.petero.droidfish.gamelogic.Piece.BQUEEN) legal = candidate;
                if (candidate.promoteTo == org.petero.droidfish.gamelogic.Piece.WQUEEN ||
                        candidate.promoteTo == org.petero.droidfish.gamelogic.Piece.BQUEEN) break;
            }
        }
        if (legal == null) { mirrorBoard.setSelection(-1); return; }
        String san;
        try { san = TextIO.moveToString(before, legal, false, false); }
        catch (RuntimeException ex) { san = "?"; }
        MirrorBranchNode child = null;
        for (MirrorBranchNode candidate : mirrorBranchCurrent.children) {
            if (candidate.move != null && candidate.move.from == legal.from && candidate.move.to == legal.to &&
                    candidate.move.promoteTo == legal.promoteTo) { child = candidate; break; }
        }
        if (child == null) {
            Position after = new Position(before);
            after.makeMove(legal, new org.petero.droidfish.gamelogic.UndoInfo());
            child = new MirrorBranchNode(after, legal, san, mirrorBranchCurrent);
            mirrorBranchCurrent.children.add(child);
        }
        mirrorBranchCurrent.selectedChild = mirrorBranchCurrent.children.indexOf(child);
        mirrorBranchCurrent = child;
        mirrorBoard.setAnimMove(before, legal, true);
        mirrorBoard.setPosition(new Position(child.position));
        mirrorBoard.setSelection(-1);
        mirrorBoard.setBranchMoves(branchMovesOnActivePath());
        mirrorBoard.setLastMove(legal);
        mirrorAnalysisHints.clear();
        mirrorBoard.setMoveHints(null);
        int selectedPly = mirrorBranchBasePly + mirrorBranchPath().size();
        mirrorPly = selectedPly;
        updateMirrorLastMove(selectedPly, legal);
        if (mirrorAnalysisOn && mirrorAnalysisEngineActive) startMirrorAnalysis();
        updateMirrorMoveStrip();
        updateMirrorNavigationButtons();
        refreshMirrorBranchPanels();
    }

    private ArrayList<Move> branchMovesOnActivePath() {
        ArrayList<Move> moves = new ArrayList<>();
        for (MirrorBranchNode node : mirrorBranchPath()) if (node.move != null) moves.add(node.move);
        return moves;
    }

    private void toggleMirrorNavigationMode() {
        if (mirrorControlBar == null || mirrorBoardOnlyMode) return;
        mirrorNavigationMode = !mirrorNavigationMode;
        if (mirrorNavigationMode) {
            stopMirrorAutoPlay();
            if (runner != null && !runner.isPaused() && !runner.isFinished()) togglePauseResume();
            loadMirrorNavigationComments();
        } else {
            mirrorNavigationCommentLoadGeneration++;
            mirrorNavigationCommentsLoading = false;
        }
        if (mirrorPrimaryControls != null)
            mirrorPrimaryControls.setVisibility(mirrorNavigationMode ? View.GONE : View.VISIBLE);
        if (mirrorNavigationControls != null)
            mirrorNavigationControls.setVisibility(mirrorNavigationMode ? View.VISIBLE : View.GONE);
        applyMirrorEngineOutputVisibility();
        if (mirrorNavigationMode) {
            updateMirrorFocusPanels();
            if (mirrorAnalysisOn && mirrorAnalysisEngineActive) startMirrorAnalysis();
        } else {
            updateMirrorEngineOutputDisplays(lastWhiteAnalysis, lastBlackAnalysis);
            updateMirrorEvaluationBars(lastWhiteAnalysis, lastBlackAnalysis);
            updateMirrorRankedNames();
        }
        updateMirrorNavigationButtons();
    }

    /** Re-read the actual PGN move comments when review navigation is entered. */
    private void loadMirrorNavigationComments() {
        final int generation = ++mirrorNavigationCommentLoadGeneration;
        mirrorNavigationCommentsLoading = true;
        final boolean replay = mirrorReplayMode;
        mirrorNavigationWhiteName = replay ? mirrorReplayWhiteName : currentWhiteName;
        mirrorNavigationBlackName = replay ? mirrorReplayBlackName : currentBlackName;
        final String replayPgn = mirrorReplayPgn;
        final String fallbackPgn = currentPgnMoves;
        final TournamentRunner liveRunner = runner;
        final String whiteId = replay ? mirrorReplayWhiteEngineId : currentWhiteEngineId;
        final String blackId = replay ? mirrorReplayBlackEngineId : currentBlackEngineId;
        Thread loader = new Thread(() -> {
            String pgn = replay ? replayPgn : fallbackPgn;
            if (!replay && liveRunner != null) {
                try {
                    String latest = liveRunner.currentPgn();
                    if (latest != null && !latest.trim().isEmpty()) pgn = latest;
                } catch (RuntimeException ignored) { }
            }
            ArrayList<String> comments = new ArrayList<>();
            ArrayList<String> moves = parseMirrorMoves(pgn, comments);
            String[] names = extractPgnNames(pgn == null ? "" : pgn);
            final String loadedPgn = pgn == null ? "" : pgn;
            runOnUiThread(() -> {
                if (!mirrorNavigationMode || generation != mirrorNavigationCommentLoadGeneration) return;
                mirrorNavigationCommentsLoading = false;
                if (replay) {
                    if (moves.size() + 1 == mirrorReplayPositions.size()) {
                        mirrorReplayMoves.clear();
                        mirrorReplayMoves.addAll(moves);
                        mirrorReplayComments.clear();
                        mirrorReplayComments.addAll(comments);
                    } else {
                        android.util.Log.w("MirrorReview", "PGN reread did not match replay positions; retained parsed review data");
                    }
                    mirrorNavigationWhiteName = names[0].equals("White") ? mirrorReplayWhiteName : names[0];
                    mirrorNavigationBlackName = names[1].equals("Black") ? mirrorReplayBlackName : names[1];
                } else {
                    if (moves.size() + 1 == mirrorPositions.size()) {
                        mirrorLiveSanMoves.clear();
                        mirrorLiveSanMoves.addAll(moves);
                        mirrorLiveComments.clear();
                        mirrorLiveComments.addAll(comments);
                        currentPgnMoves = loadedPgn;
                    } else {
                        mirrorLiveComments.clear();
                        android.util.Log.w("MirrorReview", "PGN reread did not match live positions; review analysis left blank rather than using transient output");
                    }
                    mirrorNavigationWhiteName = names[0].equals("White") ? currentWhiteName : names[0];
                    mirrorNavigationBlackName = names[1].equals("Black") ? currentBlackName : names[1];
                }
                mirrorNavigationWhiteEngineId = whiteId;
                mirrorNavigationBlackEngineId = blackId;
                updateMirrorFocusPanels();
                updateMirrorNavigationButtons();
            });
        }, "mirror-pgn-comment-loader");
        loader.setDaemon(true);
        loader.start();
    }

    private void updateMirrorNavigationButtons() {
        if (mirrorNavFirstButton == null) return;
        ArrayList<Position> positions = currentMirrorPositions();
        int current = mirrorDisplayedPly();
        int last = Math.max(0, positions.size() - 1);
        int first = mirrorBranchMode ? mirrorBranchBasePly : 0;
        mirrorNavFirstButton.setEnabled(current > first && last > first);
        mirrorNavPreviousButton.setEnabled(current > first && last > first);
        mirrorNavNextButton.setEnabled(current < last);
        mirrorNavLastButton.setEnabled(current < last);
        mirrorNavPlayButton.setEnabled(true);
        if (mirrorNavigationShowLastMove) {
            String lastLabel = mirrorCurrentLastMoveLabel == null ? "Starting position" : mirrorCurrentLastMoveLabel;
            mirrorNavPlayButton.setText("LIVE · " + lastLabel);
            mirrorNavPlayButton.setTextSize(11);
        } else {
            mirrorNavPlayButton.setText(mirrorAutoPlay ? "STOP" : "PLAY");
            mirrorNavPlayButton.setTextSize(13);
        }
        mirrorNavFirstButton.setAlpha(mirrorNavFirstButton.isEnabled() ? 1.0f : 0.42f);
        mirrorNavPreviousButton.setAlpha(mirrorNavPreviousButton.isEnabled() ? 1.0f : 0.42f);
        mirrorNavNextButton.setAlpha(mirrorNavNextButton.isEnabled() ? 1.0f : 0.42f);
        mirrorNavLastButton.setAlpha(mirrorNavLastButton.isEnabled() ? 1.0f : 0.42f);
        mirrorNavPlayButton.setAlpha(mirrorNavPlayButton.isEnabled() ? 1.0f : 0.42f);
    }

    private ArrayList<Position> currentMirrorPositions() {
        if (mirrorBranchMode) return mirrorBranchPositions();
        return mirrorReplayMode ? mirrorReplayPositions : mirrorPositions;
    }

    private int mirrorDisplayedPly() {
        if (mirrorBranchMode) return mirrorBranchBasePly + mirrorBranchPath().size();
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
        boardSelectionActive = true;
        saveBoardSelectionState(1);
        setContentView(mirrorView);
    }

    /** Switches between the live tournament board and its independent mirror view. */
    private void switchTournamentAndMirror() {
        if (mirrorView == null || playView == null) return;
        if (!isMirrorShowing() && runner != null && !runner.isFinished() && !runner.isPaused()) {
            new AlertDialog.Builder(this)
                    .setTitle("Tournament is running")
                    .setMessage("You can pause the tournament before reviewing moves, standings, schedule, or game information, or enter Mirror Board while both engines continue searching.")
                    .setPositiveButton("PAUSE", (dialog, which) -> {
                        if (runner != null && !runner.isPaused() && !runner.isFinished()) togglePauseResume();
                        switchTournamentAndMirrorNow();
                    })
                    .setNeutralButton("GO ANYWAY", (dialog, which) -> switchTournamentAndMirrorNow())
                    .setNegativeButton("CLOSE", (dialog, which) -> dialog.dismiss())
                    .show();
            return;
        }
        switchTournamentAndMirrorNow();
    }

    private void switchTournamentAndMirrorNow() {
        stopMirrorAutoPlay();
        if (mirrorAnalysisEngine != null) { try { mirrorAnalysisEngine.stopSearch(); } catch (RuntimeException ignored) { } }
        if (isMirrorShowing()) {
            currentTournamentScreen = 0;
            boardSelectionActive = true;
            saveBoardSelectionState(0);
            Position displayPosition = latestPosition == null ? standardStartPosition() : latestPosition;
            if (tournamentBoard != null) {
                tournamentBoard.setPosition(new Position(displayPosition));
                tournamentBoard.setLastMove((runner == null || runner.isFinished() || runner.isStopped()) ? null :
                        moveAtPly(mirrorPositions, Math.max(0, mirrorPositions.size() - 1)));
                applyTournamentVariationHints();
                tournamentBoard.setSelection(-1);
                tournamentBoard.requestLayout();
                tournamentBoard.invalidate();
            }
            setContentView(playView);
        } else {
            mirrorReplayMode = false;
            mirrorReplayLiveSnapshot = false;
            mirrorPly = -1;
            syncMirrorFromLive();
            currentTournamentScreen = 1;
            boardSelectionActive = true;
            saveBoardSelectionState(1);
            setContentView(mirrorView);
        }
    }

    private boolean isMirrorShowing() { return currentTournamentScreen == 1; }

    private void syncMirrorFromLive() {
        if (mirrorBoard == null) return;
        if (mirrorPositions.isEmpty() || !samePosition(mirrorPositions.get(mirrorPositions.size() - 1), latestPosition))
            mirrorPositions.add(new Position(latestPosition == null ? standardStartPosition() : latestPosition));
        if (!mirrorReplayMode) {
            if (mirrorPly < 0) mirrorBoard.setPosition(new Position(latestPosition));
            else if (mirrorPly < mirrorPositions.size()) mirrorBoard.setPosition(new Position(mirrorPositions.get(mirrorPly)));
            updateMirrorLastMove(mirrorDisplayedPly(), mirrorNavigatedLastMove, !(isMirrorShowing() && mirrorPly < 0));
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
        }
        // Engine metrics, evaluation bars, rank labels and the tournament header
        // change on their own callbacks/results. Rewriting those view trees for every
        // ply steals vsync time from the moving pieces on slower phones.
        if (isMirrorShowing() && !mirrorReplayMode && mirrorSelectedTab == 0)
            updateMirrorMoveStrip();
        if (mirrorNavigationMode || mirrorSelectedTab == 0) updateMirrorNavigationButtons();
    }

    private boolean samePosition(Position a, Position b) {
        return a != null && b != null && a.toString().equals(b.toString());
    }

    /**
     * Opening-book moves are already in the live PGN before the first engine move,
     * but the runner's position callbacks begin at the opening position. Rebuild
     * the mirror-only history once when those two counts do not line up.
     */
    private void ensureMirrorHistoryMatchesLivePgn(Position livePosition, boolean forceRebuild,
                                                   Move lastMove, Position previousPosition) {
        // Normal engine turns are incremental: the runner already supplies the exact
        // move and prior position. Avoid reparsing the complete PGN on every ply. This
        // matters on single-core phones, where repeated PGN parsing competed with search.
        if (!forceRebuild && mirrorLiveHistoryInitialized && !mirrorPositions.isEmpty()) {
            Position tail = mirrorPositions.get(mirrorPositions.size() - 1);
            if (samePosition(tail, livePosition)) {
                if (runner != null && !runner.isFinished()) {
                    TournamentRunner.AnalysisHistorySnapshot snapshot = runner.getAnalysisHistorySnapshot();
                    if (snapshot.completedPlies > mirrorLiveSanMoves.size() ||
                            mirrorPositions.size() != mirrorLiveSanMoves.size() + 1) {
                        android.util.Log.w("TournamentHistory", "Live board position is current but move-list count is behind; reconciling PGN");
                        scheduleMirrorLiveHistoryRebuild();
                    }
                }
                return;
            }
            if (lastMove != null && previousPosition != null && samePosition(tail, previousPosition)) {
                try {
                    Position expected = new Position(tail);
                    expected.makeMove(lastMove, new org.petero.droidfish.gamelogic.UndoInfo());
                    if (samePosition(expected, livePosition)) {
                        mirrorPositions.add(new Position(livePosition));
                        while (mirrorLiveComments.size() < mirrorLiveSanMoves.size()) mirrorLiveComments.add("");
                        return;
                    }
                } catch (RuntimeException ignored) { }
            }
            // A dropped/out-of-order callback must not leave the move list stale
            // indefinitely. Reconcile once from the runner's authoritative PGN on a
            // background thread; the normal successful move path remains incremental.
            android.util.Log.w("TournamentHistory", "Live move sequence mismatch; scheduling PGN reconciliation");
            scheduleMirrorLiveHistoryRebuild();
            return;
        }

        // Rebuild only at game start or after a full PGN refresh. The PGN reader
        // and legal-SAN fallback are compared so a partial parser result cannot
        // silently truncate a saved game at its opening moves.
        try {
            MirrorPgnHistory parsed = readMirrorPgnHistory(currentPgnMoves);
            if (parsed.moves.isEmpty()) {
                if (forceRebuild) {
                    mirrorPositions.clear();
                    mirrorPositions.add(new Position(livePosition == null ? standardStartPosition() : livePosition));
                    mirrorLiveSanMoves.clear();
                    mirrorLiveComments.clear();
                }
                return;
            }
            if (livePosition != null && !parsed.positions.isEmpty() &&
                    !samePosition(parsed.positions.get(parsed.positions.size() - 1), livePosition)) {
                if (lastMove != null && previousPosition != null &&
                        samePosition(parsed.positions.get(parsed.positions.size() - 1), previousPosition)) {
                    Position appended = new Position(previousPosition);
                    appended.makeMove(lastMove, new org.petero.droidfish.gamelogic.UndoInfo());
                    if (samePosition(appended, livePosition)) {
                        parsed.moves.add(TextIO.moveToString(previousPosition, lastMove, false, false));
                        parsed.comments.add("");
                        parsed.positions.add(new Position(livePosition));
                    }
                }
            }
            if (livePosition != null && !parsed.positions.isEmpty() &&
                    !samePosition(parsed.positions.get(parsed.positions.size() - 1), livePosition)) {
                android.util.Log.w("TournamentHistory", "PGN history snapshot does not yet reach live position; retrying asynchronously");
                scheduleMirrorLiveHistoryRebuild();
                return;
            }
            mirrorPositions.clear(); mirrorPositions.addAll(parsed.positions);
            mirrorLiveSanMoves.clear(); mirrorLiveSanMoves.addAll(parsed.moves);
            mirrorLiveComments.clear(); mirrorLiveComments.addAll(parsed.comments);
        } catch (Exception parseError) {
            android.util.Log.e("TournamentHistory", "Could not rebuild complete PGN history", parseError);
            // Last-resort partial-live-PGN recovery from the standard start. A
            // saved custom-FEN game still uses the authoritative GameTree path.
            MirrorPgnHistory fallback = parseMirrorPgnMovetext(currentPgnMoves, standardStartPosition());
            if (fallback != null && !fallback.moves.isEmpty() && livePosition != null &&
                    samePosition(fallback.positions.get(fallback.positions.size() - 1), livePosition)) {
                mirrorPositions.clear(); mirrorPositions.addAll(fallback.positions);
                mirrorLiveSanMoves.clear(); mirrorLiveSanMoves.addAll(fallback.moves);
                mirrorLiveComments.clear(); mirrorLiveComments.addAll(fallback.comments);
            } else if (runner != null && !runner.isFinished()) {
                scheduleMirrorLiveHistoryRebuild();
            }
        }
    }

    private void scheduleMirrorLiveHistoryRebuild() {
        scheduleMirrorLiveHistoryRebuild(0);
    }

    private void scheduleMirrorLiveHistoryRebuild(final int retryCount) {
        final TournamentRunner source = runner;
        if (source == null || source.isFinished() || source.isStopped() ||
                mirrorReplayMode || mirrorLiveHistoryRebuildPending) return;
        mirrorLiveHistoryRebuildPending = true;
        final long generation = ++mirrorLiveHistoryRebuildGeneration;
        new Thread(() -> {
            MirrorPgnHistory parsed = null;
            String error = null;
            try {
                String pgn = source.currentPgn();
                parsed = readMirrorPgnHistory(pgn);
            } catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            final MirrorPgnHistory snapshot = parsed;
            final String parseError = error;
            runOnUiThread(() -> {
                if (generation != mirrorLiveHistoryRebuildGeneration) return;
                mirrorLiveHistoryRebuildPending = false;
                if (runner != source || mirrorReplayMode || source.isStopped()) return;
                if (snapshot == null || snapshot.moves.isEmpty()) {
                    android.util.Log.w("TournamentHistory", "Live PGN reconciliation failed: " + parseError);
                    return;
                }
                Position live = latestPosition;
                if (live != null && !snapshot.positions.isEmpty() &&
                        !samePosition(snapshot.positions.get(snapshot.positions.size() - 1), live)) {
                    // The runner advanced while the snapshot was being parsed. Get a
                    // fresh authoritative snapshot a few times, but never spin forever
                    // parsing a continually-growing PGN on a low-power device.
                    if (retryCount < 3)
                        uiHandler.postDelayed(() -> scheduleMirrorLiveHistoryRebuild(retryCount + 1), 120L);
                    else
                        android.util.Log.w("TournamentHistory", "PGN reconciliation deferred after 3 moving-target retries; the next position callback will retry");
                    return;
                }
                if (snapshot.moves.size() < mirrorLiveSanMoves.size()) {
                    android.util.Log.w("TournamentHistory", "Rejected shorter live PGN reconciliation snapshot");
                    return;
                }
                mirrorPositions.clear(); mirrorPositions.addAll(snapshot.positions);
                mirrorLiveSanMoves.clear(); mirrorLiveSanMoves.addAll(snapshot.moves);
                mirrorLiveComments.clear(); mirrorLiveComments.addAll(snapshot.comments);
                mirrorLiveHistoryInitialized = true;
                invalidateMirrorMovesSubCache(0);
                invalidateMirrorSectionCache(0);
                invalidateMirrorSectionCache(1);
                if (isMirrorShowing() && !mirrorReplayMode) syncMirrorFromLive();
                if (isMirrorShowing() && (mirrorSelectedTab == 0 ||
                        (mirrorSelectedTab == 1 && mirrorStandingsSubTab == 1))) renderMirrorSection();
                android.util.Log.i("TournamentHistory", "Reconciled live PGN history: " + snapshot.moves.size() + " plies");
            });
        }, "mirror-live-history-rebuild").start();
    }

    /** Parse a completed game's authoritative PGN off the UI thread. A new game
     * invalidates the generation token so a late result cannot overwrite its history. */
    private void scheduleCompletedMirrorHistoryRebuild(final String pgn,
                                                       final Position completedPosition,
                                                       final int completedGameIndex) {
        if (pgn == null || pgn.isEmpty() || completedPosition == null) return;
        mirrorLiveHistoryRebuildPending = true;
        final long generation = ++mirrorLiveHistoryRebuildGeneration;
        final long startedNs = System.nanoTime();
        MIRROR_HISTORY_EXECUTOR.execute(() -> {
            MirrorPgnHistory parsed = null;
            Exception failure = null;
            try { parsed = readMirrorPgnHistory(pgn); }
            catch (Exception error) { failure = error; }
            final MirrorPgnHistory snapshot = parsed;
            final Exception parseFailure = failure;
            runOnUiThread(() -> {
                if (generation != mirrorLiveHistoryRebuildGeneration) return;
                mirrorLiveHistoryRebuildPending = false;
                if (isFinishing() || isDestroyed() || mirrorReplayMode || currentPgnMoves != pgn ||
                        latestPosition == null || !samePosition(latestPosition, completedPosition)) return;
                if (snapshot == null || snapshot.moves.isEmpty() || snapshot.positions.isEmpty() ||
                        !samePosition(snapshot.positions.get(snapshot.positions.size() - 1), completedPosition)) {
                    TournamentDiagnostics.record("HISTORY", "completed_pgn_history_rebuild_rejected",
                            "game=" + completedGameIndex + " elapsedMs=" +
                                    ((System.nanoTime() - startedNs) / 1_000_000L) +
                                    " reason=" + (parseFailure == null ? "position mismatch or empty history" : parseFailure.getClass().getSimpleName()));
                    return;
                }
                mirrorPositions.clear(); mirrorPositions.addAll(snapshot.positions);
                mirrorLiveSanMoves.clear(); mirrorLiveSanMoves.addAll(snapshot.moves);
                mirrorLiveComments.clear(); mirrorLiveComments.addAll(snapshot.comments);
                mirrorLiveHistoryInitialized = true;
                invalidateMirrorMovesSubCache(0);
                invalidateMirrorSectionCache(0);
                if (isMirrorShowing() && !mirrorReplayMode) {
                    syncMirrorFromLive();
                    if (mirrorSelectedTab == 0 || (mirrorSelectedTab == 1 && mirrorStandingsSubTab == 1))
                        scheduleMirrorSectionRefreshAfterMove(false);
                }
                TournamentDiagnostics.record("HISTORY", "completed_pgn_history_rebuilt_background",
                        "game=" + completedGameIndex + " plies=" + snapshot.moves.size() +
                                " elapsedMs=" + ((System.nanoTime() - startedNs) / 1_000_000L));
            });
        });
    }

    /** Defer expensive Mirror section reconstruction until the engine transition has
     * already been dispatched. Replaced tasks coalesce rapid result/start updates. */
    private void scheduleDeferredMirrorSectionRefresh(TournamentManager expectedTournament, long delayMs) {
        if (mirrorDeferredSectionRefreshRunnable != null)
            uiHandler.removeCallbacks(mirrorDeferredSectionRefreshRunnable);
        final long generation = ++mirrorDeferredSectionRefreshGeneration;
        final int expectedTab = mirrorSelectedTab;
        Runnable refresh = () -> {
            mirrorDeferredSectionRefreshRunnable = null;
            if (generation != mirrorDeferredSectionRefreshGeneration || isFinishing() || isDestroyed() ||
                    activeTournamentManager != expectedTournament || !isMirrorShowing() || mirrorSelectedTab != expectedTab) return;
            long startedNs = System.nanoTime();
            renderMirrorSection();
            long elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L;
            TournamentDiagnostics.record("UI", "deferred_mirror_section_refresh",
                    "tab=" + expectedTab + " elapsedMs=" + elapsedMs);
            if (elapsedMs >= 100L)
                android.util.Log.w("CET-TournamentUI", "Deferred Mirror tab " + expectedTab + " refresh took " + elapsedMs + "ms");
        };
        mirrorDeferredSectionRefreshRunnable = refresh;
        uiHandler.postDelayed(refresh, Math.max(0L, delayMs));
    }

    private void refreshMirrorRanks() {
        refreshMirrorRanks(activeTournamentManager == null ? java.util.Collections.emptyList() : activeTournamentManager.standings());
    }

    private void refreshMirrorRanks(List<TournamentManager.Standing> standings) {
        mirrorRanks.clear();
        if (standings != null) {
            for (int i = 0; i < standings.size(); i++) mirrorRanks.put(standings.get(i).engineId, i + 1);
        }
        updateMirrorRankedNames();
    }

    private void updateMirrorRankedNames() {
        if (mirrorWhiteName == null || mirrorBlackName == null) return;
        if (mirrorReplayMode) {
            String resultTag = mirrorReplayLiveSnapshot ? "*" : extractPgnResult(mirrorReplayPgn);
            mirrorWhiteName.setText(appendResultScore(mirrorRankedName(mirrorReplayWhiteEngineId, mirrorReplayWhiteName), resultScore(resultTag, true)));
            mirrorBlackName.setText(appendResultScore(mirrorRankedName(mirrorReplayBlackEngineId, mirrorReplayBlackName), resultScore(resultTag, false)));
            setMirrorPlayerIcon(mirrorWhiteIcon, mirrorReplayWhiteEngineId, mirrorReplayWhiteName);
            setMirrorPlayerIcon(mirrorBlackIcon, mirrorReplayBlackEngineId, mirrorReplayBlackName);
            if (mirrorFocusWhiteIcon != null) setMirrorPlayerIcon(mirrorFocusWhiteIcon, mirrorReplayWhiteEngineId, mirrorReplayWhiteName);
            if (mirrorFocusBlackIcon != null) setMirrorPlayerIcon(mirrorFocusBlackIcon, mirrorReplayBlackEngineId, mirrorReplayBlackName);
            if (mirrorEngineOutputHidden) updateMirrorFocusPanels();
            return;
        }
        mirrorWhiteName.setText(appendResultScore(mirrorRankedName(currentWhiteEngineId, currentWhiteName), currentBoardWhiteScore));
        mirrorBlackName.setText(appendResultScore(mirrorRankedName(currentBlackEngineId, currentBlackName), currentBoardBlackScore));
        mirrorWhiteName.setTextColor(currentWhiteToMove ? 0xFFFFFFFF : 0xFF9A9A9A);
        mirrorBlackName.setTextColor(currentWhiteToMove ? 0xFF9A9A9A : 0xFFFFFFFF);
        if (mirrorWhiteClock != null) mirrorWhiteClock.setAlpha(currentWhiteToMove ? 1.0f : 0.58f);
        if (mirrorBlackClock != null) mirrorBlackClock.setAlpha(currentWhiteToMove ? 0.58f : 1.0f);
        setMirrorPlayerIcon(mirrorWhiteIcon, currentWhiteEngineId, currentWhiteName);
        setMirrorPlayerIcon(mirrorBlackIcon, currentBlackEngineId, currentBlackName);
        if (mirrorFocusWhiteIcon != null) setMirrorPlayerIcon(mirrorFocusWhiteIcon, currentWhiteEngineId, currentWhiteName);
        if (mirrorFocusBlackIcon != null) setMirrorPlayerIcon(mirrorFocusBlackIcon, currentBlackEngineId, currentBlackName);
        if (mirrorEngineOutputHidden) updateMirrorFocusPanels();
    }

    private void setMirrorPlayerIcon(ImageView view, String engineId, String displayName) {
        if (view == null) return;
        Bitmap bitmap = mirrorEngineIcon(engineId, displayName);
        if (bitmap == null) { view.setImageDrawable(null); view.setVisibility(View.GONE); return; }
        view.setImageDrawable(new BitmapDrawable(getResources(), bitmap));
        if (view == mirrorFocusEngineIcon) {
            // The shared analysis icon belongs to the double-tap engine-output
            // panel, not to the separate board-only focus icons.
            view.setVisibility((mirrorEngineOutputHidden || mirrorNavigationMode) ? View.VISIBLE : View.GONE);
        } else {
            boolean focusIcon = view == mirrorFocusBlackIcon || view == mirrorFocusWhiteIcon;
            view.setVisibility(focusIcon == mirrorBoardOnlyMode ? View.VISIBLE : View.GONE);
        }
    }

    private String mirrorRankedName(String engineId, String displayName) {
        String nameOnly = splitMirrorEngineNameVersion(displayName)[0];
        if (engineId == null || engineId.isEmpty()) return nameOnly;
        Integer rank = mirrorRanks.get(engineId);
        return "#" + (rank == null ? "—" : rank) + "." + nameOnly;
    }

    private void selectMirrorTab(int tab, boolean scrollToSection) {
        int previousTab = mirrorSelectedTab;
        mirrorSelectedTab = Math.max(0, Math.min(3, tab));
        if (mirrorSelectedTab == 2 && previousTab != 2) mirrorScheduleHasSavedScroll = false;
        if (mirrorSelectedTab != 0) stopMirrorAutoPlay();
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
        updateMirrorGraphScrollSpacer();
        if (scrollToSection && mirrorScroll != null && mirrorSectionTitle != null) {
            mirrorScroll.post(() -> mirrorScroll.smoothScrollTo(0, Math.max(0, mirrorSectionHost.getTop() - dp(110))));
        }
    }

    private void updateMirrorGraphScrollSpacer() {
        if (mirrorGraphScrollSpacer == null || mirrorSectionHost == null) return;
        boolean graphSelected = mirrorSelectedTab == 1 && mirrorStandingsSubTab == 1;
        if (!graphSelected) {
            mirrorGraphScrollSpacer.setVisibility(View.GONE);
            return;
        }
        // The graph now expands naturally inside the outer Mirror Board scroll view.
        // A viewport-sized spacer made the chart appear stranded below the controls.
        mirrorGraphScrollSpacer.setVisibility(View.GONE);
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) mirrorSectionHost.getLayoutParams();
        if (params.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            mirrorSectionHost.setLayoutParams(params);
        }
    }

    private void renderMirrorSection() {
        if (mirrorSectionHost == null) return;
        LinearLayout parentHost = mirrorSectionHost;
        // Capture scroll state before detaching a section that is about to be rebuilt.
        // This prevents a live move/result update from flashing the list back to row 1.
        if (mirrorSelectedTab == 0 && mirrorMovesListScroll != null && mirrorMovesListScroll.getParent() != null) {
            mirrorMovesListScrollY = mirrorMovesListScroll.getScrollY();
            View child = mirrorMovesListScroll.getChildAt(0);
            int contentHeight = child == null ? 0 : child.getHeight();
            int viewportHeight = mirrorMovesListScroll.getHeight();
            mirrorMovesListWasAtBottom = contentHeight - (mirrorMovesListScrollY + viewportHeight) <= dp(32);
            mirrorMovesListHasSavedPosition = true;
        } else if (mirrorSelectedTab == 2 && mirrorScheduleList != null && mirrorScheduleList.getParent() != null) {
            mirrorScheduleFirstVisiblePosition = mirrorScheduleList.getFirstVisiblePosition();
            View firstChild = mirrorScheduleList.getChildAt(0);
            mirrorScheduleFirstVisibleTop = firstChild == null ? 0 : firstChild.getTop();
            mirrorScheduleHasSavedScroll = true;
        }
        boolean graphSelected = mirrorSelectedTab == 1 && mirrorStandingsSubTab == 1;
        LinearLayout.LayoutParams hostParams = (LinearLayout.LayoutParams) parentHost.getLayoutParams();
        int normalPanelHeight = Math.max(dp(260), Math.min(dp(520), (int)(getResources().getDisplayMetrics().heightPixels * 0.48f)));
        int desiredHeight = graphSelected ? ViewGroup.LayoutParams.WRAP_CONTENT : normalPanelHeight;
        if (hostParams.height != desiredHeight) { hostParams.height = desiredHeight; parentHost.setLayoutParams(hostParams); }
        parentHost.removeAllViews();
        LinearLayout cached = mirrorSectionCache.get(mirrorSelectedTab);
        if (cached != null) {
            if (cached.getParent() instanceof ViewGroup) ((ViewGroup) cached.getParent()).removeView(cached);
            parentHost.addView(cached, new LinearLayout.LayoutParams(-1, graphSelected ? -2 : -1));
            if (mirrorSelectedTab == 2 && mirrorScheduleList != null) scrollMirrorScheduleToCurrentGame();
            return;
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        mirrorSectionHost = content;
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
        if (mirrorSelectedTab == 0) renderMirrorMovesSection();
        else if (mirrorSelectedTab == 1) renderMirrorStandingsSection();
        else if (mirrorSelectedTab == 2) renderMirrorScheduleSection();
        else renderMirrorInfoSection();
        mirrorSectionHost = parentHost;
        mirrorSectionCache.put(mirrorSelectedTab, content);
        parentHost.addView(content, new LinearLayout.LayoutParams(-1, graphSelected ? -2 : -1));
    }

    private void invalidateMirrorSectionCache(int tab) {
        mirrorSectionCache.remove(tab);
    }

    /** Coalesce Mirror move-list rendering until the active piece animation has ended. */
    private void scheduleMirrorSectionRefreshAfterMove(boolean liveMoveOnly) {
        if (mirrorLiveSectionRefreshRunnable != null)
            uiHandler.removeCallbacks(mirrorLiveSectionRefreshRunnable);
        Runnable refresh = () -> {
            mirrorLiveSectionRefreshRunnable = null;
            if (!isMirrorShowing()) return;
            if (liveMoveOnly && (mirrorReplayMode || mirrorPly >= 0)) return;
            updateMirrorMoveStrip();
            if (mirrorSelectedTab == 0 ||
                    (mirrorSelectedTab == 1 && mirrorStandingsSubTab == 1))
                renderMirrorSection();
        };
        mirrorLiveSectionRefreshRunnable = refresh;
        int animationMs = moveAnimationDurationMs();
        if (animationMs <= 0) {
            mirrorLiveSectionRefreshRunnable = null;
            refresh.run();
        } else {
            uiHandler.postDelayed(refresh, animationMs + 16L);
        }
    }

    private void invalidateAllMirrorSectionCaches() {
        mirrorSectionCache.clear();
    }

    private void invalidateMirrorMovesSubCache(int subTab) { mirrorMovesSubCache.remove(subTab); }
    private void invalidateMirrorStandingsSubCache(int subTab) { mirrorStandingsSubCache.remove(subTab); }
    private void clearMirrorSubCaches() { mirrorMovesSubCache.clear(); mirrorStandingsSubCache.clear(); }

    private void addCachedMirrorSubpanel(HashMap<Integer, LinearLayout> cache, int key, Runnable builder) {
        LinearLayout parent = mirrorSectionHost;
        LinearLayout panel = cache.get(key);
        if (panel == null) {
            panel = new LinearLayout(this);
            panel.setOrientation(LinearLayout.VERTICAL);
            mirrorSectionHost = panel;
            builder.run();
            mirrorSectionHost = parent;
            cache.put(key, panel);
        }
        if (panel.getParent() instanceof ViewGroup) ((ViewGroup) panel.getParent()).removeView(panel);
        boolean naturalHeightPanel = mirrorSelectedTab == 1 && mirrorStandingsSubTab == 1;
        parent.addView(panel, new LinearLayout.LayoutParams(-1,
                naturalHeightPanel ? ViewGroup.LayoutParams.WRAP_CONTENT : 0,
                naturalHeightPanel ? 0 : 1));
    }

    private void renderMirrorMovesSection() {
        if (mirrorSectionHost == null) return;
        // Reconcile the navigation history against the complete live PGN before
        // sizing the controls or rendering the move list. This keeps book moves
        // and engine moves on the same ply index even after a delayed callback.
        if (!mirrorReplayMode && latestPosition != null)
            ensureMirrorHistoryMatchesLivePgn(latestPosition, false, null, null);
        int displayPly = mirrorDisplayedPly();
        int displayTotal = Math.max(0, currentMirrorPositions().size() - 1);
        mirrorStatusText = text(mirrorReplayMode ?
                (mirrorReplayLiveSnapshot ? "Live snapshot · ply " : "Saved PGN · ply ") + displayPly + "/" + displayTotal :
                (mirrorPly < 0 ? "Following live game" : "Live review · ply " + displayPly + "/" + displayTotal + " · double-tap board for controls"));
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

        final int selectedSubTab = mirrorMovesSubTab;
        addCachedMirrorSubpanel(mirrorMovesSubCache, selectedSubTab, () -> {
            if (selectedSubTab == 0) renderMirrorMoveList();
            else if (selectedSubTab == 1) renderMirrorAnalysisSection();
            else renderMirrorGameInfoSection();
        });
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
        invalidateMirrorSectionCache(0);
        renderMirrorSection();
        if (mirrorAnalysisOn && mirrorAnalysisEngineActive) startMirrorAnalysis();
    }

    private void renderMirrorMoveList() {
        mirrorSelectedMoveCommentCell = null;
        mirrorSelectedMoveCommentPly = -1;
        ScrollView movesScroll = new MirrorSectionScrollView(this);
        mirrorMovesListScroll = movesScroll;
        movesScroll.setFillViewport(false);
        LinearLayout moves = new LinearLayout(this);
        moves.setOrientation(LinearLayout.VERTICAL);
        ArrayList<String> sans;
        ArrayList<String> comments;
        comments = new ArrayList<>();
        sans = currentMirrorSanMoves(comments);
        // Snapshot the position history once. currentMirrorPositions() returns a
        // defensive copy, so calling it inside each cell would make this renderer
        // quadratic in move count on long games.
        ArrayList<Position> displayPositions = currentMirrorPositions();
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
                        Position beforeMove = ply - 1 < displayPositions.size()
                                ? displayPositions.get(ply - 1) : null;
                        int moveNumber = beforeMove == null ? (ply + 1) / 2 : beforeMove.fullMoveCounter;
                        boolean whiteMove = beforeMove == null ? ((ply & 1) == 1) : beforeMove.whiteMove;
                        boolean showMoveNumber = true;
                        if (!whiteMove && ply > 1 && ply - 2 < displayPositions.size()) {
                            Position previousMoveStart = displayPositions.get(ply - 2);
                            // Standard PGN omits n... when Black immediately follows White's n. move.
                            showMoveNumber = !(previousMoveStart.whiteMove &&
                                    previousMoveStart.fullMoveCounter == moveNumber);
                        }
                        String prefix = !showMoveNumber ? "" : (moveNumber + (whiteMove ? "." : "..."));
                        String comment = ply - 1 < comments.size() ? comments.get(ply - 1) : "";
                        if (selectedMove) {
                            mirrorSelectedMoveCommentCell = cell;
                            mirrorSelectedMoveCommentPly = ply;
                            mirrorSelectedMoveCommentPrefix = prefix;
                            mirrorSelectedMoveCommentSan = sans.get(ply - 1);
                            mirrorSelectedMoveCommentStored = comment;
                            mirrorSelectedMoveCommentWhite = whiteMove;
                        }
                        setMirrorMoveCommentCellText(cell, prefix, sans.get(ply - 1),
                                mirrorDisplayedMoveComment(comment, whiteMove, ply));
                        cell.setOnClickListener(v -> navigateMirror(ply));
                    }
                    row.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
                }
                moves.addView(row, fullWidth());
            }
        }
        movesScroll.addView(moves, new ScrollView.LayoutParams(-1, -2));
        mirrorSectionHost.addView(movesScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        final boolean followLive = (!mirrorReplayMode && mirrorPly < 0) || mirrorReplayLiveSnapshot;
        final boolean restoreBottom = !mirrorMovesListHasSavedPosition || mirrorMovesListWasAtBottom;
        final int savedY = mirrorMovesListScrollY;
        movesScroll.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                if (movesScroll.getViewTreeObserver().isAlive())
                    movesScroll.getViewTreeObserver().removeOnPreDrawListener(this);
                View contentView = movesScroll.getChildAt(0);
                ViewGroup content = contentView instanceof ViewGroup ? (ViewGroup) contentView : null;
                int maxY = content == null ? 0 : Math.max(0, content.getHeight() - movesScroll.getHeight());
                int targetY;
                if (mirrorReplayMode && content != null) {
                    int selectedPly = mirrorDisplayedPly();
                    int selectedRowIndex = selectedPly <= 0 ? 0 : (selectedPly - 1) / 2;
                    View selectedRow = content.getChildAt(selectedRowIndex);
                    if (selectedRow != null) {
                        int rowTop = selectedRow.getTop();
                        targetY = Math.max(0, Math.min(maxY,
                                rowTop - Math.max(dp(24), movesScroll.getHeight() / 3)));
                    } else {
                        targetY = Math.max(0, Math.min(savedY, maxY));
                    }
                } else {
                    targetY = followLive && restoreBottom ? maxY : Math.max(0, Math.min(savedY, maxY));
                }
                movesScroll.scrollTo(0, targetY);
                mirrorMovesListScrollY = targetY;
                mirrorMovesListWasAtBottom = maxY - targetY <= dp(32);
                mirrorMovesListHasSavedPosition = true;
                return true;
            }
        });
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
        mirrorAnalysisEngineToggle = new Button(this);
        mirrorAnalysisEngineToggle.setText(mirrorAnalysisEngineActive ? "ENGINE ENABLED" : "ENGINE DISABLED");
        mirrorAnalysisToggle = new Button(this);
        mirrorAnalysisToggle.setText(mirrorAnalysisOn ? "ANALYSIS ON" : "ANALYSIS OFF");
        controls.addView(mirrorAnalysisChooseEngine, new LinearLayout.LayoutParams(0, -2, 1));
        controls.addView(mirrorAnalysisEngineToggle, new LinearLayout.LayoutParams(0, -2, 1));
        controls.addView(mirrorAnalysisToggle, new LinearLayout.LayoutParams(0, -2, 1));
        mirrorSectionHost.addView(controls, fullWidth());
        mirrorAnalysisChooseEngine.setOnClickListener(v -> chooseMirrorAnalysisEngine());
        mirrorAnalysisEngineToggle.setOnClickListener(v -> {
            mirrorAnalysisEngineActive = !mirrorAnalysisEngineActive;
            if (!mirrorAnalysisEngineActive) stopMirrorAnalysisEngineOnly();
            mirrorAnalysisEngineToggle.setText(mirrorAnalysisEngineActive ? "ENGINE ENABLED" : "ENGINE DISABLED");
            if (mirrorAnalysisEngineActive && mirrorAnalysisOn) startMirrorAnalysis();
            if (mirrorNavigationMode || mirrorEngineOutputHidden) updateMirrorFocusPanels();
            refreshSelectedMirrorMoveComment();
        });
        mirrorAnalysisToggle.setOnClickListener(v -> {
            if (!mirrorAnalysisOn && mirrorAnalysisEngineId.isEmpty()) {
                chooseMirrorAnalysisEngine();
                return;
            }
            mirrorAnalysisOn = !mirrorAnalysisOn;
            mirrorAnalysisToggle.setText(mirrorAnalysisOn ? "ANALYSIS ON" : "ANALYSIS OFF");
            if (mirrorAnalysisOn) startMirrorAnalysis(); else stopMirrorAnalysis();
            if (mirrorNavigationMode || mirrorEngineOutputHidden) updateMirrorFocusPanels();
            refreshSelectedMirrorMoveComment();
        });
        mirrorAnalysisEngineName = text(mirrorAnalysisEngineId.isEmpty() ? "ANALYSIS ENGINE" :
                engineDisplayName(activeTournamentManager == null ? new ArrayList<>() : activeTournamentManager.engines(), mirrorAnalysisEngineId));
        if (mirrorAnalysisEngineName.getText().toString().equals(mirrorAnalysisEngineId) || mirrorAnalysisEngineName.length() == 0) {
            for (EngineChoice choice : availableEngines) if (choice.id.equals(mirrorAnalysisEngineId)) {
                mirrorAnalysisEngineName.setText(choice.name); break;
            }
        }
        mirrorAnalysisEngineName.setTextSize(15);
        mirrorAnalysisEngineName.setTypeface(null, android.graphics.Typeface.BOLD);
        mirrorAnalysisEngineName.setTextColor(0xFFFFFFFF);
        mirrorAnalysisEngineName.setPadding(dp(10), dp(9), dp(10), dp(4));
        mirrorSectionHost.addView(mirrorAnalysisEngineName, fullWidth());
        mirrorAnalysisOutput = new LinearLayout(this);
        mirrorAnalysisOutput.setOrientation(LinearLayout.VERTICAL);
        mirrorAnalysisOutput.setPadding(dp(8), dp(6), dp(8), dp(8));
        populateMirrorAnalysisOutput(mirrorLatestAnalysisOutput);
        ScrollView outputScroll = new MirrorSectionScrollView(this);
        outputScroll.setFillViewport(false);
        outputScroll.setVerticalScrollBarEnabled(true);
        outputScroll.addView(mirrorAnalysisOutput, new ScrollView.LayoutParams(-1, -2));
        mirrorSectionHost.addView(outputScroll, new LinearLayout.LayoutParams(-1, dp(230)));
        mirrorAnalysisOutput.setOnLongClickListener(v -> {
            showMirrorAnalysisLongPressMenu(true, mirrorAnalysisEngineId, mirrorLatestAnalysisOutput, null);
            return true;
        });
    }

    /**
     * Render each UCI variation as its own one-line horizontal scroller.
     * A normal wrapping TextView lets a long PV steal the next depth row's
     * vertical space, which made adjacent depth lines appear to collide.
     */
    private void populateMirrorAnalysisOutput(String output) {
        if (mirrorAnalysisOutput == null) return;
        mirrorAnalysisOutput.removeAllViews();
        String value = output == null ? "" : output.trim();
        if (value.isEmpty()) value = "Waiting for analysis…";
        String[] lines = value.split("\\r?\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i == 3 && lines.length > 3) {
                View spacer = new View(this);
                mirrorAnalysisOutput.addView(spacer, new LinearLayout.LayoutParams(-1, dp(5)));
            }
            HorizontalScrollView rowScroll = new HorizontalScrollView(this);
            rowScroll.setFillViewport(false);
            rowScroll.setHorizontalScrollBarEnabled(false);
            rowScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);

            TextView rowText = text(lines[i].isEmpty() ? " " : lines[i]);
            // The Moves → Analysis section is intended to be readable at phone size.
            // Keep metrics most prominent and retain a slightly smaller depth/PV hierarchy.
            rowText.setTextSize(i == 0 ? 17 : 15.5f);
            rowText.setTextColor(i == 0 ? 0xFFF0F0F0 : 0xFFD8DEE2);
            rowText.setSingleLine(true);
            rowText.setMaxLines(1);
            rowText.setHorizontallyScrolling(true);
            rowText.setEllipsize(null);
            rowText.setIncludeFontPadding(false);
            rowText.setGravity(android.view.Gravity.CENTER_VERTICAL);
            rowText.setPadding(dp(3), dp(3), dp(10), dp(3));
            rowScroll.addView(rowText, new HorizontalScrollView.LayoutParams(-2, dp(30)));
            mirrorAnalysisOutput.addView(rowScroll, new LinearLayout.LayoutParams(-1, dp(31)));
            attachMirrorAnalysisOutputLongPress(rowScroll);
            attachMirrorAnalysisOutputLongPress(rowText);
        }
    }

    private void attachMirrorAnalysisOutputLongPress(View target) {
        if (target == null) return;
        target.setLongClickable(true);
        target.setOnLongClickListener(v -> {
            showMirrorAnalysisLongPressMenu(true, mirrorAnalysisEngineId, mirrorLatestAnalysisOutput, null);
            return true;
        });
    }

    private void setMirrorAnalysisOutput(String output) {
        mirrorLatestAnalysisOutput = output == null ? "" : output;
        populateMirrorAnalysisOutput(mirrorLatestAnalysisOutput);
        if (mirrorEngineOutputHidden) updateMirrorFocusPanels();
    }

    private void chooseMirrorAnalysisEngine() {
        if (availableEngines.isEmpty()) loadEngineChoices();
        if (availableEngines.isEmpty()) { Toast.makeText(this, "No installed engines found", Toast.LENGTH_LONG).show(); return; }
        CharSequence[] labels = new CharSequence[availableEngines.size()];
        for (int i = 0; i < labels.length; i++) {
            // Keep the picker responsive: engineLabelWithIcon() can query the
            // document tree and decode PNG files synchronously on the UI thread.
            labels[i] = availableEngines.get(i).name;
        }
        new AlertDialog.Builder(this).setTitle("Choose engine to analyse this position")
                .setItems(labels, (dialog, which) -> {
                    if (which < 0 || which >= availableEngines.size()) return;
                    stopMirrorAnalysisEngineOnly();
                    EngineChoice selectedEngine = availableEngines.get(which);
                    mirrorAnalysisEngineId = selectedEngine.id;
                    mirrorAnalysisCoreOutput = "";
                    mirrorAnalysisDepthOutput = "";
                    mirrorAnalysisEngineActive = true;
                    mirrorAnalysisOn = true;
                    // Update the visible selection labels in place rather than
                    // rebuilding the cached Moves section just to change its name.
                    if (mirrorAnalysisChooseEngine != null) mirrorAnalysisChooseEngine.setText(selectedEngine.name);
                    if (mirrorAnalysisEngineName != null) mirrorAnalysisEngineName.setText(selectedEngine.name);
                    startMirrorAnalysis();
                }).setNegativeButton("Cancel", null).show();
    }

    private void startMirrorAnalysis() {
        if (!mirrorAnalysisOn || !mirrorAnalysisEngineActive || mirrorAnalysisEngineId.isEmpty()) return;
        final long analysisGeneration = ++mirrorAnalysisGeneration;
        synchronized (mirrorAnalysisPvByDepth) { mirrorAnalysisPvByDepth.clear(); }
        mirrorAnalysisCoreOutput = "";
        mirrorAnalysisDepthOutput = "";
        if (mirrorAnalysisEngine != null) { try { mirrorAnalysisEngine.stopSearch(); } catch (RuntimeException ignored) { } }
        ArrayList<Position> positions = currentMirrorPositions();
        if (positions.isEmpty()) {
            mirrorAnalysisTargetPly = -1;
            mirrorAnalysisRequestId = -1;
            return;
        }
        int ply = Math.max(0, Math.min(mirrorDisplayedPly(), positions.size() - 1));
        final int analysisPlyForSearch = ply;
        mirrorAnalysisTargetPly = analysisPlyForSearch;
        mirrorAnalysisRequestId = -1;
        Position position = new Position(positions.get(ply));
        final String analysisEngineIdForSearch = mirrorAnalysisEngineId;
        final int requestedVariationCount = Math.max(1,
                Math.min(ColorTheme.MAX_ARROWS, mirrorAnalysisVariationCount));
        setMirrorAnalysisOutput("Starting analysis…");
        setMirrorShortcutAnalysis("ANALYSING…");
        if (mirrorNavigationMode || mirrorEngineOutputHidden) updateMirrorFocusPanels();
        refreshSelectedMirrorMoveComment();
        final DroidComputerPlayer analysisPlayer = mirrorAnalysisEngine != null ? mirrorAnalysisEngine : new DroidComputerPlayer(new SearchListener() {
            @Override public void notifyDepth(int id, int depth) { }
            @Override public void notifyCurrMove(int id, Position pos, Move move, int moveNr) { }
            @Override public void notifyPV(int id, Position pos, ArrayList<PvInfo> infos, Move ponderMove) {
                // The listener is reused after navigation. Only the active
                // request ID and its mutable target ply are authoritative.
                if (id != mirrorAnalysisRequestId) return;
                final int targetPly = mirrorAnalysisTargetPly;
                if (infos == null || infos.isEmpty() || infos.get(0) == null) return;
                PvInfo pv = infos.get(0);
                int whitePerspectiveScore = (pos != null && pos.whiteMove) ? pv.score : -pv.score;
                String eval = pv.isMate ? ((whitePerspectiveScore >= 0 ? "+M" : "-M") + Math.abs(whitePerspectiveScore)) :
                        String.format(java.util.Locale.US, "%+.2f", whitePerspectiveScore / 100.0);
                ArrayList<Move> hints = new ArrayList<>();
                StringBuilder variationLines = new StringBuilder();
                // This listener is reused by later analysis requests. Read the
                // currently selected count here instead of capturing the count
                // from the first request, so the Variation menu updates PV rows
                // and the corresponding board arrows on the same engine.
                int requestedVariations = Math.max(1,
                        Math.min(ColorTheme.MAX_ARROWS, mirrorAnalysisVariationCount));
                if (requestedVariations <= 1) {
                    if (pv.pv != null) {
                        int plies = Math.min(ColorTheme.MAX_ARROWS, pv.pv.size());
                        for (int plyIndex = 0; plyIndex < plies; plyIndex++) {
                            Move move = pv.pv.get(plyIndex);
                            if (move == null) break;
                            hints.add(move);
                        }
                    }
                    String variation = mirrorPvVariation(pos, pv);
                    if (!variation.isEmpty()) variationLines.append(variation);
                } else {
                    // Additional engine variations are opt-in through the separate
                    // Variation menu. Show one first-move arrow for each returned PV.
                    for (int pvIndex = 0; pvIndex < infos.size() && pvIndex < requestedVariations; pvIndex++) {
                        PvInfo variationPv = infos.get(pvIndex);
                        if (variationPv == null || variationPv.pv == null || variationPv.pv.isEmpty()) break;
                        String variation = mirrorPvVariation(pos, variationPv);
                        if (variation.isEmpty()) break;
                        hints.add(variationPv.pv.get(0));
                        if (variationLines.length() > 0) variationLines.append('\n');
                        variationLines.append("PV ").append(pvIndex + 1).append("  ").append(variation);
                    }
                }
                String depthLines;
                synchronized (mirrorAnalysisPvByDepth) {
                    // The depth history remains the principal (PV 1) line. Secondary
                    // MultiPV lines are listed separately above and receive their own arrows.
                    for (int pvIndex = 0; pvIndex < infos.size(); pvIndex++) {
                        PvInfo depthPv = infos.get(pvIndex);
                        if (pvIndex != 0 || depthPv == null || depthPv.depth <= 0) continue;
                        String variation = mirrorPvVariation(pos, depthPv);
                        if (!variation.isEmpty()) mirrorAnalysisPvByDepth.put(depthPv.depth, variation);
                    }
                    StringBuilder depthText = new StringBuilder();
                    for (java.util.Map.Entry<Integer, String> entry : mirrorAnalysisPvByDepth.entrySet()) {
                        if (depthText.length() > 0) depthText.append('\n');
                        depthText.append('d').append(entry.getKey()).append("  ").append(entry.getValue());
                    }
                    depthLines = depthText.toString();
                }
                final String mainVariation = mirrorPvVariation(pos, pv);
                final String compactOutput = "[" + pv.depth + "] " + eval + " " +
                        compactMirrorShortcutCount(pv.nps) + "nps" +
                        (mainVariation.isEmpty() ? "" : " " + mainVariation);
                String engineWdl = pv.hasWdl ? "\nEngine WDL: W " + String.format(java.util.Locale.US, "%.1f", pv.wdlWin / 10.0) +
                        "%   D " + String.format(java.util.Locale.US, "%.1f", pv.wdlDraw / 10.0) +
                        "%   L " + String.format(java.util.Locale.US, "%.1f", pv.wdlLoss / 10.0) + "%" : "";
                final String coreOutput = "Eval: " + eval + "   Depth: " + pv.depth + "/" + pv.seldepth +
                        "   NPS: " + compactViewerCount(pv.nps) + "   Nodes: " + compactViewerCount(pv.nodes) +
                        engineWdl + (variationLines.length() == 0 ? "" : "\n" + variationLines);
                runOnUiThread(() -> {
                    if (!mirrorAnalysisOn || !mirrorAnalysisEngineActive ||
                            !analysisEngineIdForSearch.equals(mirrorAnalysisEngineId) ||
                            id != mirrorAnalysisRequestId || targetPly != mirrorAnalysisTargetPly ||
                            targetPly != mirrorDisplayedPly()) return;
                    mirrorAnalysisCoreOutput = coreOutput;
                    mirrorAnalysisDepthOutput = depthLines;
                    setMirrorAnalysisOutput(composeMirrorAnalysisOutput());
                    setMirrorShortcutAnalysis(compactOutput);
                    if (mirrorAnalysisEngineName != null) mirrorAnalysisEngineName.setText(mirrorAnalysisEngineNameForId(analysisEngineIdForSearch));
                    if (mirrorNavigationMode || mirrorEngineOutputHidden) updateMirrorFocusPanels();
                    refreshSelectedMirrorMoveComment();
                    ArrayList<Move> savedHints = new ArrayList<>(hints);
                    mirrorAnalysisHintsByPly.put(targetPly, savedHints);
                    mirrorAnalysisHints.clear();
                    mirrorAnalysisHints.addAll(savedHints);
                    applyMirrorBoardHighlights(mirrorNavigatedLastMove);
                });
            }
            @Override public void notifyStats(int id, long nodes, int nps, long tbHits, int hash, int time, int seldepth) { }
            @Override public void notifyBookInfo(int id, String bookInfo, ArrayList<Move> moveList, String eco, int distToEcoTree) { }
            @Override public void notifySearchResult(int id, String cmd, Move ponderMove) { }
            @Override public void notifyEngineName(String engineName) {
                if (engineName == null || engineName.trim().isEmpty()) return;
                mirrorReportedEngineNames.put(analysisEngineIdForSearch, engineName.trim());
                runOnUiThread(() -> {
                    if (mirrorAnalysisEngineName != null && analysisEngineIdForSearch.equals(mirrorAnalysisEngineId))
                        mirrorAnalysisEngineName.setText(engineName.trim());
                });
            }
            @Override public void notifyEngineInitialized() { }
            @Override public void reportEngineError(String errMsg) {
                runOnUiThread(() -> Toast.makeText(TournamentActivity.this, "Analysis engine: " + errMsg, Toast.LENGTH_LONG).show());
            }
        });
        boolean startEngine = mirrorAnalysisEngine == null;
        if (startEngine) {
            mirrorAnalysisEngine = analysisPlayer;
            analysisPlayer.setEngineOptions(new org.petero.droidfish.EngineOptions());
            analysisPlayer.setBookOptions(new BookOptions());
            analysisPlayer.queueStartEngine(++mirrorAnalysisSearchId, mirrorAnalysisEngineId);
        }
        final DroidComputerPlayer activePlayer = analysisPlayer;
        uiHandler.postDelayed(() -> {
            if (analysisGeneration != mirrorAnalysisGeneration ||
                    mirrorAnalysisEngine != activePlayer || !mirrorAnalysisOn || !mirrorAnalysisEngineActive ||
                    analysisPlyForSearch != mirrorAnalysisTargetPly || analysisPlyForSearch != mirrorDisplayedPly()) return;
            try {
                int requestId = ++mirrorAnalysisSearchId;
                mirrorAnalysisRequestId = requestId;
                activePlayer.queueAnalyzeRequest(DroidComputerPlayer.SearchRequest.analyzeRequest(
                        requestId, position, new ArrayList<Move>(), position, false,
                        analysisEngineIdForSearch, requestedVariationCount));
            } catch (RuntimeException ex) {
                Toast.makeText(this, "Unable to start analysis: " + ex.getMessage(), Toast.LENGTH_LONG).show();
            }
        }, startEngine ? 700 : 80);
    }

    private void showMirrorAnalysisLongPressMenu(boolean analysisEngine, String engineId, String output) {
        showMirrorAnalysisLongPressMenu(analysisEngine, engineId, output, null);
    }

    private String composeMirrorAnalysisOutput() {
        String core = mirrorAnalysisCoreOutput == null ? "" : mirrorAnalysisCoreOutput;
        String depths = mirrorAnalysisDepthOutput == null ? "" : mirrorAnalysisDepthOutput;
        if (mirrorAnalysisDepthsVisible && !depths.isEmpty()) return core + "\n" + depths;
        return core;
    }

    private void showMirrorAnalysisLongPressMenu(boolean analysisEngine, String engineId, String output, Boolean whiteSide) {
        String engineName = displayNameForEngineAction(engineId, whiteSide);
        String fullOutput = (engineName == null || engineName.trim().isEmpty() ? "Engine" : engineName) + "\n" +
                (output == null || output.trim().isEmpty() ? "No engine output available." : output);
        String[] actions = new String[]{"Engine option", "Copy engine output",
                mirrorAnalysisDepthsVisible ? "Hide depths" : "Show depths",
                "Variation (" + mirrorAnalysisVariationCount + ")"};
        new AlertDialog.Builder(this).setTitle(engineName == null ? "Engine output" : engineName)
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) openEngineOptions(engineId, analysisEngine, whiteSide);
                    else if (which == 1) {
                        ClipboardManager clipboard = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Engine output", fullOutput));
                        Toast.makeText(this, "Engine output copied", Toast.LENGTH_SHORT).show();
                    } else if (which == 2) {
                        mirrorAnalysisDepthsVisible = !mirrorAnalysisDepthsVisible;
                        setMirrorAnalysisOutput(composeMirrorAnalysisOutput());
                    } else if (which == 3) showMirrorAnalysisVariationDialog();
                }).show();
    }

    private void showMirrorAnalysisVariationDialog() {
        if (mirrorAnalysisEngine == null) {
            Toast.makeText(this, "Start analysis before adding variations", Toast.LENGTH_SHORT).show();
            return;
        }
        int engineLimit = Math.max(1, mirrorAnalysisEngine.getMaxPV());
        int maxVariations = Math.min(ColorTheme.MAX_ARROWS, engineLimit);
        if (maxVariations <= 1) {
            Toast.makeText(this, "This engine supports only one analysis variation", Toast.LENGTH_LONG).show();
            return;
        }
        CharSequence[] choices = new CharSequence[maxVariations];
        for (int i = 0; i < maxVariations; i++)
            choices[i] = (i + 1) + (i == 0 ? " variation" : " variations");
        new AlertDialog.Builder(this).setTitle("Analysis variations")
                .setSingleChoiceItems(choices, Math.min(mirrorAnalysisVariationCount - 1, maxVariations - 1),
                        (dialog, which) -> {
                            int requested = which + 1;
                            if (requested != mirrorAnalysisVariationCount) {
                                mirrorAnalysisVariationCount = requested;
                                getPreferences(MODE_PRIVATE).edit().putInt("mirrorAnalysisVariationCount", requested).commit();
                                dialog.dismiss();
                                if (mirrorAnalysisOn && mirrorAnalysisEngineActive) startMirrorAnalysis();
                            } else {
                                dialog.dismiss();
                            }
                        })
                .setNegativeButton("Cancel", null).show();
    }

    private void showTournamentArrowCountDialog() {
        CharSequence[] choices = new CharSequence[ColorTheme.MAX_ARROWS];
        for (int i = 0; i < choices.length; i++)
            choices[i] = (i + 1) + (i == 0 ? " arrow · main-PV ply" : " arrows · main-PV plies");
        new AlertDialog.Builder(this).setTitle("Tournament engine variation arrows")
                .setSingleChoiceItems(choices, tournamentArrowCount - 1, (dialog, which) -> {
                    int requested = clampArrowCount(which + 1);
                    tournamentArrowCount = requested;
                    getPreferences(MODE_PRIVATE).edit().putInt("tournamentArrowCount", requested).commit();
                    if (runner != null) runner.setTournamentArrowCount(requested);
                    applyTournamentVariationHints();
                    dialog.dismiss();
                    Toast.makeText(this, "Tournament arrows: " + requested + " consecutive plies of the main variation", Toast.LENGTH_SHORT).show();
                }).setNegativeButton("Cancel", null).show();
    }

    private void showMirrorExternalArrowCountDialog() {
        CharSequence[] choices = new CharSequence[ColorTheme.MAX_ARROWS];
        for (int i = 0; i < choices.length; i++)
            choices[i] = (i + 1) + (i == 0 ? " arrow · main-PV ply" : " arrows · main-PV plies");
        new AlertDialog.Builder(this).setTitle("Mirror external-analysis arrows (PV plies)")
                .setSingleChoiceItems(choices, mirrorAnalysisArrowPlyCount - 1, (dialog, which) -> {
                    int requested = clampArrowCount(which + 1);
                    mirrorAnalysisArrowPlyCount = requested;
                    getPreferences(MODE_PRIVATE).edit().putInt("mirrorAnalysisArrowPlyCount", requested).commit();
                    dialog.dismiss();
                    // Re-render the cached current PV. Arrow count does not change MultiPV
                    // and must not restart or deepen the analysis search.
                    applyMirrorBoardHighlights(mirrorNavigatedLastMove);
                    Toast.makeText(this, "External-analysis main-PV arrows: " + requested, Toast.LENGTH_SHORT).show();
                }).setNegativeButton("Cancel", null).show();
    }

    private String displayNameForEngineAction(String engineId, Boolean whiteSide) {
        if (whiteSide != null) {
            String name = whiteSide ? currentWhiteName : currentBlackName;
            if (name != null && !name.trim().isEmpty()) return name;
        }
        return mirrorAnalysisEngineNameForId(engineId);
    }

    private String mirrorAnalysisEngineNameForId(String engineId) {
        if (engineId == null || engineId.isEmpty()) return "Engine";
        // The UI selection's display name is authoritative for panels and menus.
        // UCI id/name values are fallbacks only and must not replace the selected label.
        if (activeTournamentManager != null) {
            for (TournamentManager.Engine engine : activeTournamentManager.engines())
                if (engineId.equals(engine.id) && engine.displayName != null && !engine.displayName.trim().isEmpty())
                    return engine.displayName;
        }
        for (EngineChoice choice : availableEngines)
            if (engineId.equals(choice.id) && choice.name != null && !choice.name.trim().isEmpty()) return choice.name;
        String reported = mirrorReportedEngineNames.get(engineId);
        if (reported != null && !reported.trim().isEmpty()) return reported;
        return new File(engineId).getName();
    }

    private void openEngineOptions(String engineId, boolean analysisEngine, Boolean whiteSide) {
        if (engineId == null || engineId.trim().isEmpty()) {
            Toast.makeText(this, "Engine identity is not available", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!analysisEngine && runner == null) {
            Toast.makeText(this, "No active tournament engine is available", Toast.LENGTH_SHORT).show();
            return;
        }
        if (analysisEngine && mirrorAnalysisEngine != null) {
            try { mirrorAnalysisEngine.stopSearch(); } catch (RuntimeException ignored) { }
        }
        if (!analysisEngine && runner != null && !runner.isPaused() && !runner.isFinished()) {
            new AlertDialog.Builder(this)
                    .setTitle("Pause before editing options?")
                    .setMessage("The engine must be paused before its UCI options can be changed.")
                    .setPositiveButton("PAUSE & EDIT", (dialog, which) -> {
                        if (runner != null && !runner.isPaused() && !runner.isFinished()) togglePauseResume();
                        launchEngineOptionsEditor(engineId, analysisEngine, whiteSide);
                    })
                    .setNegativeButton("CANCEL", null).show();
            return;
        }
        launchEngineOptionsEditor(engineId, analysisEngine, whiteSide);
    }

    private void launchEngineOptionsEditor(String engineId, boolean analysisEngine, Boolean whiteSide) {
        UCIOptions options;
        if (analysisEngine) options = mirrorAnalysisEngine == null ? null : mirrorAnalysisEngine.getUCIOptions();
        else options = runner == null || whiteSide == null ? null :
                runner.getUciOptionsForTournamentEngine(whiteSide, engineId);
        if (options == null) {
            Toast.makeText(this, "Engine options are not currently available. Check that this engine has started successfully.", Toast.LENGTH_LONG).show();
            return;
        }
        EngineOptions engineOptions = createTournamentEngineOptions();
        Intent intent = new Intent(this, EditOptions.class);
        intent.putExtra("org.petero.droidfish.ucioptions", options);
        intent.putExtra("org.petero.droidfish.enginename", displayNameForEngineAction(engineId, whiteSide));
        intent.putExtra("org.petero.droidfish.workDir", engineOptions.workDir);
        intent.putExtra("org.petero.droidfish.localEngine", engineOptions.networkID == null || engineOptions.networkID.isEmpty());
        pendingAnalysisEngineOptions = analysisEngine;
        pendingTournamentOptionsEngineId = analysisEngine ? "" : engineId;
        pendingTournamentOptionsWhite = !analysisEngine && Boolean.TRUE.equals(whiteSide);
        startActivityForResult(intent, REQUEST_TOURNAMENT_ENGINE_OPTIONS);
    }

    /** Convert one iterative-deepening PV to a readable SAN variation. */
    private String mirrorPvVariation(Position start, PvInfo pv) {
        if (start == null || pv == null || pv.pv == null || pv.pv.isEmpty()) return "";
        StringBuilder variation = new StringBuilder();
        Position cursor = new Position(start);
        for (Move move : pv.pv) {
            if (variation.length() > 0) variation.append(' ');
            if (cursor.whiteMove) variation.append(cursor.fullMoveCounter).append('.');
            else if (variation.length() == 0) variation.append(cursor.fullMoveCounter).append("...");
            variation.append(TextIO.moveToString(cursor, move, false, false));
            cursor.makeMove(move, new org.petero.droidfish.gamelogic.UndoInfo());
        }
        return variation.toString();
    }

    private void stopMirrorAnalysisEngineOnly() {
        mirrorAnalysisGeneration++;
        mirrorAnalysisTargetPly = -1;
        mirrorAnalysisRequestId = -1;
        if (mirrorAnalysisEngine != null) {
            try { mirrorAnalysisEngine.stopSearch(); mirrorAnalysisEngine.shutdownEngine(); } catch (RuntimeException ignored) { }
            mirrorAnalysisEngine = null;
        }
        mirrorAnalysisHints.clear();
        mirrorAnalysisHintsByPly.clear();
        if (mirrorBoard != null) mirrorBoard.setMoveHints(null);
    }

    private void stopMirrorAnalysis() {
        mirrorAnalysisOn = false;
        mirrorAnalysisGeneration++;
        mirrorAnalysisTargetPly = -1;
        mirrorAnalysisRequestId = -1;
        if (mirrorAnalysisEngine != null) { try { mirrorAnalysisEngine.stopSearch(); } catch (RuntimeException ignored) { } }
        if (mirrorAnalysisToggle != null) mirrorAnalysisToggle.setText("ANALYSIS OFF");
        if (!mirrorBranchMode) updateMirrorShortcutAnalysisState();
    }

    private void renderMirrorGameInfoSection() {
        String pgn = mirrorReplayMode ? mirrorReplayPgn : currentPgnMoves;
        TournamentManager.Pairing pairing = currentMirrorPairing();
        String white = pgnTag(pgn, "White");
        String black = pgnTag(pgn, "Black");
        if (white.isEmpty()) white = mirrorReplayMode ? mirrorReplayWhiteName : currentWhiteName;
        if (black.isEmpty()) black = mirrorReplayMode ? mirrorReplayBlackName : currentBlackName;
        String opening = pgnTag(pgn, "Opening");
        String variation = pgnTag(pgn, "Variation");
        String eco = pgnTag(pgn, "ECO");
        String[] detected = detectMirrorEco(pgn);
        if (opening.isEmpty() && !detected[1].isEmpty()) opening = detected[1];
        if (variation.isEmpty() && !detected[2].isEmpty()) variation = detected[2];
        if (eco.isEmpty() && !detected[0].isEmpty()) eco = detected[0];
        if (opening.isEmpty() && pairing != null && pairing.openingName != null) opening = pairing.openingName;
        if (opening.equalsIgnoreCase(eco) && !detected[1].isEmpty()) opening = detected[1];

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        TextView heading = text("PGN ENGINE METADATA");
        heading.setTextSize(13); heading.setTypeface(null, android.graphics.Typeface.BOLD);
        heading.setTextColor(0xFFBFCBD2); heading.setPadding(dp(10), dp(12), dp(10), dp(4));
        content.addView(heading, fullWidth());
        addMirrorPgnMetadataRow(content, "White", white);
        addMirrorPgnMetadataRow(content, "Black", black);
        String date = pgnTag(pgn, "Date");
        if (date.isEmpty() && pairing != null) date = pairing.pgnDate;
        String round = pgnTag(pgn, "Round");
        if (round.isEmpty() && pairing != null) round = Integer.toString(pairing.round);
        String termination = pgnTag(pgn, "Termination");
        addMirrorPgnMetadataRow(content, "Date", date);
        addMirrorPgnMetadataRow(content, "Round", round);
        if (pairing != null && pairing.isComplete()) addMirrorPgnMetadataRow(content, "Termination", termination);
        addMirrorPgnMetadataRow(content, "Opening", opening.isEmpty() ? "Not identified" : opening);
        if (!variation.isEmpty()) addMirrorPgnMetadataRow(content, "Variation", variation);
        if (!eco.isEmpty()) addMirrorPgnMetadataRow(content, "ECO", eco);
        ScrollView scroll = new MirrorSectionScrollView(this);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        mirrorSectionHost.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
    }

    private void addMirrorPgnMetadataRow(LinearLayout parent, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        TextView key = text(label);
        key.setTextSize(14); key.setTypeface(null, android.graphics.Typeface.BOLD);
        key.setTextColor(0xFFBFCBD2);
        row.addView(key, new LinearLayout.LayoutParams(dp(92), -2));
        TextView data = text(value == null || value.trim().isEmpty() ? "—" : value);
        data.setTextSize(16); data.setTypeface(null, android.graphics.Typeface.BOLD);
        data.setTextColor(0xFFFFFFFF); data.setMaxLines(3);
        row.addView(data, new LinearLayout.LayoutParams(0, -2, 1));
        parent.addView(row, fullWidth());
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

    private void setMirrorMoveCommentCellText(TextView cell, String prefix, String san, String displayComment) {
        if (cell == null) return;
        String moveText = (prefix == null ? "" : prefix) + (san == null ? "" : san);
        android.text.SpannableString styled = new android.text.SpannableString(moveText +
                (displayComment == null || displayComment.isEmpty() ? "" : "\n" + displayComment));
        int commentAt = moveText.length();
        if (displayComment != null && !displayComment.isEmpty()) {
            styled.setSpan(new android.text.style.RelativeSizeSpan(0.78f), commentAt,
                    styled.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            styled.setSpan(new android.text.style.ForegroundColorSpan(0xFFB8C4CC), commentAt,
                    styled.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        cell.setText(styled);
    }

    private void refreshSelectedMirrorMoveComment() {
        if (mirrorSelectedMoveCommentCell == null || mirrorSelectedMoveCommentCell.getParent() == null ||
                mirrorSelectedMoveCommentPly != mirrorDisplayedPly()) return;
        setMirrorMoveCommentCellText(mirrorSelectedMoveCommentCell, mirrorSelectedMoveCommentPrefix,
                mirrorSelectedMoveCommentSan,
                mirrorDisplayedMoveComment(mirrorSelectedMoveCommentStored,
                        mirrorSelectedMoveCommentWhite, mirrorSelectedMoveCommentPly));
        // The expanded board uses the same saved-comment/external-analysis precedence.
        if (expandedMainDialog != null && expandedMainDialog.isShowing()) {
            expandedMovesSignature = "";
            refreshExpandedMoveList();
        }
    }

    /** Compact, readable move annotation extracted from the saved PGN comment. */
    private String mirrorMoveCommentDisplay(String comment, boolean whiteMove) {
        if (comment == null || comment.trim().isEmpty()) return "";
        String value = comment.trim();
        boolean bookComment = value.equalsIgnoreCase("Book") || value.equalsIgnoreCase("Book.") ||
                value.regionMatches(true, 0, "Book. ", 0, 6);
        if (value.equalsIgnoreCase("Book") || value.equalsIgnoreCase("Book.")) return "Book.";
        String eval = findCommentValue(value, "Eval|eval");
        String depth = findCommentValue(value, "Depth|D");
        String selDepth = findCommentValue(value, "SelDepth|SDepth|SD");
        String nodes = findCommentValue(value, "Nodes|N");
        String nps = findCommentValue(value, "NPS|nps");
        String clock = findCommentValue(value, "Clock");
        String used = findCommentValue(value, "Time|TimeUsed");
        StringBuilder first = new StringBuilder();
        if (bookComment) first.append("Book.");
        if (!eval.isEmpty()) first.append(eval);
        if (!depth.isEmpty()) {
            String depthDisplay = depth;
            if (depthDisplay.indexOf('/') < 0 && !selDepth.isEmpty()) depthDisplay += "/" + selDepth;
            appendMirrorValue(first, "d" + depthDisplay);
        }
        if (!nodes.isEmpty()) appendMirrorValue(first, "n" + nodes);
        if (!nps.isEmpty()) appendMirrorValue(first, nps + "nps");
        if (!used.isEmpty()) appendMirrorValue(first, "t:" + mirrorSecondsForDisplay(used));
        String second = clock.isEmpty() ? "" : "clk:" + mirrorSecondsForDisplay(clock);
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
     * PGN [%clk] annotations are parsed by GameTree into Node.remainingTime,
     * not retained in the ordinary post-comment string. Re-attach a display-only
     * Clock field so the Mirror and expanded move lists can show the same comment
     * after a saved PGN has been loaded.
     */
    private String moveCommentWithClock(GameTree.Node node) {
        if (node == null) return "";
        String comment = node.getPostComment();
        int remainingMs = node.getRemainingTimeMs();
        if (remainingMs == Integer.MIN_VALUE || remainingMs < 0 ||
                !findCommentValue(comment, "Clock|clk").isEmpty())
            return comment;
        String clock = "Clock: " + formatPgnClock(remainingMs);
        return comment == null || comment.trim().isEmpty() ? clock : comment.trim() + " " + clock;
    }

    private String formatPgnClock(long remainingMs) {
        long seconds = Math.max(0L, (remainingMs + 999L) / 1000L);
        long hours = seconds / 3600L;
        long minutes = (seconds / 60L) % 60L;
        long secs = seconds % 60L;
        return String.format(java.util.Locale.US, "%02d:%02d:%02d", hours, minutes, secs);
    }

    /**
     * Parse the PGN with the project's own PGN reader so book moves, move
     * numbers, comments, and saved-game replays all use the same main line.
     * A lightweight token fallback is retained for partially-written live PGNs.
     */
    private static final class MirrorPgnHistory {
        final ArrayList<Position> positions = new ArrayList<>();
        final ArrayList<String> moves = new ArrayList<>();
        final ArrayList<String> comments = new ArrayList<>();
    }

    /**
     * Read the complete main line from PGN. If the PGN reader returns a shorter
     * line than a legal SAN replay of the same movetext, keep the longer legal line.
     * This specifically avoids accepting a partial opening-only line from old games.
     */
    private MirrorPgnHistory readMirrorPgnHistory(String pgn) throws IOException {
        if (pgn == null || pgn.trim().isEmpty()) throw new IOException("PGN is empty");
        GameTree tree = new GameTree(null);
        PGNOptions options = new PGNOptions();
        options.imp.comments = true;
        options.imp.variations = false;
        options.imp.nag = true;
        boolean parsedByTree = false;
        try {
            parsedByTree = tree.readPGN(pgn, options);
        } catch (ChessParseError parseError) {
            // Treat a checked PGN parse failure like a false parse result and
            // let the legal SAN fallback attempt to recover the complete line.
            parsedByTree = false;
        }
        if (!parsedByTree) {
            Position fallbackRoot = standardStartPosition();
            java.util.regex.Matcher fenTag = java.util.regex.Pattern.compile(
                    "(?im)^\\s*\\[FEN\\s+\"([^\"]+)\"\\]\\s*$").matcher(pgn);
            if (fenTag.find()) {
                try {
                    fallbackRoot = TextIO.readFEN(fenTag.group(1));
                } catch (ChessParseError | RuntimeException invalidFen) {
                    throw new IOException("PGN contains an invalid FEN", invalidFen);
                }
            }
            MirrorPgnHistory fallback = parseMirrorPgnMovetext(pgn, fallbackRoot);
            if (fallback != null && !fallback.moves.isEmpty()) return fallback;
            throw new IOException("No valid chess game found in PGN");
        }
        while (!tree.currentNode.equals(tree.rootNode)) tree.goBack();
        Position rootPosition = new Position(tree.currentPos);

        MirrorPgnHistory treeHistory = new MirrorPgnHistory();
        Position cursor = new Position(rootPosition);
        treeHistory.positions.add(new Position(cursor));
        android.util.Pair<List<GameTree.Node>, Integer> moveList = tree.getMoveList();
        if (moveList != null && moveList.first != null) {
            for (GameTree.Node node : moveList.first) {
                if (node.move == null) continue;
                treeHistory.moves.add(TextIO.moveToString(cursor, node.move, false, false));
                treeHistory.comments.add(moveCommentWithClock(node));
                cursor.makeMove(node.move, new org.petero.droidfish.gamelogic.UndoInfo());
                treeHistory.positions.add(new Position(cursor));
                if (treeHistory.moves.size() >= 20000) break;
            }
        }

        MirrorPgnHistory sanHistory = parseMirrorPgnMovetext(pgn, rootPosition);
        if (sanHistory != null && sanHistory.moves.size() > treeHistory.moves.size()) {
            // Keep comments already parsed by GameTree for the common prefix when
            // the legal SAN fallback recovers a longer main line.
            int common = Math.min(treeHistory.comments.size(), sanHistory.comments.size());
            for (int i = 0; i < common; i++) {
                String fallbackComment = sanHistory.comments.get(i);
                if (fallbackComment == null || fallbackComment.trim().isEmpty())
                    sanHistory.comments.set(i, treeHistory.comments.get(i));
            }
            return sanHistory;
        }
        return treeHistory;
    }

    /** Remove PGN line comments without modifying semicolons inside brace comments. */
    private String stripMirrorPgnLineComments(String pgn) {
        StringBuilder clean = new StringBuilder(pgn.length());
        boolean inBraceComment = false;
        boolean inLineComment = false;
        boolean lineStart = true;
        for (int i = 0; i < pgn.length(); i++) {
            char ch = pgn.charAt(i);
            if (inLineComment) {
                if (ch == '\n' || ch == '\r') {
                    inLineComment = false;
                    clean.append(ch);
                    lineStart = true;
                }
                continue;
            }
            if (!inBraceComment && ch == ';') {
                inLineComment = true;
                continue;
            }
            if (!inBraceComment && lineStart && ch == '%') {
                inLineComment = true;
                continue;
            }
            clean.append(ch);
            if (ch == '{' && !inBraceComment) inBraceComment = true;
            else if (ch == '}' && inBraceComment) inBraceComment = false;
            if (ch == '\n' || ch == '\r') lineStart = true;
            else if (!Character.isWhitespace(ch)) lineStart = false;
        }
        return clean.toString();
    }

    /** Parse and legally replay the PGN main-line SAN as a recovery path. */
    private MirrorPgnHistory parseMirrorPgnMovetext(String pgn, Position startPosition) {
        if (pgn == null || startPosition == null) return null;
        MirrorPgnHistory history = new MirrorPgnHistory();
        Position cursor = new Position(startPosition);
        history.positions.add(new Position(cursor));
        String moveText = stripMirrorPgnLineComments(pgn)
                .replaceAll("(?m)^\\s*\\[[^\\r\\n]*\\]\\s*$", " ");
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\{([^}]*)\\}|\\(|\\)|\\S+").matcher(moveText);
        int variationDepth = 0;
        while (matcher.find()) {
            if (matcher.group(1) != null) {
                if (variationDepth == 0 && !history.comments.isEmpty())
                    history.comments.set(history.comments.size() - 1, matcher.group(1).trim());
                continue;
            }
            String token = matcher.group();
            if ("(".equals(token)) { variationDepth++; continue; }
            if (")".equals(token)) { variationDepth = Math.max(0, variationDepth - 1); continue; }
            if (variationDepth > 0) continue;
            if (token.startsWith("[") || token.startsWith("$") || token.startsWith("%")) continue;
            String san = token.replaceFirst("^\\d+\\.{1,3}", "")
                    .replaceAll("\\$\\d+$", "").replaceAll("[!?]+$", "");
            if (san.isEmpty() || san.equals("...") || san.equalsIgnoreCase("e.p.") ||
                    san.matches("1-0|0-1|1/2-1/2|\\*")) continue;
            Move move;
            try { move = TextIO.stringToMove(cursor, san); }
            catch (RuntimeException invalidSan) { break; }
            if (move == null) break;
            history.moves.add(TextIO.moveToString(cursor, move, false, false));
            history.comments.add("");
            try {
                cursor.makeMove(move, new org.petero.droidfish.gamelogic.UndoInfo());
            } catch (RuntimeException invalidMove) {
                history.moves.remove(history.moves.size() - 1);
                history.comments.remove(history.comments.size() - 1);
                break;
            }
            history.positions.add(new Position(cursor));
            if (history.moves.size() >= 20000) break;
        }
        return history;
    }

    private ArrayList<String> parseMirrorMoves(String pgn, ArrayList<String> commentsOut) {
        if (commentsOut != null) commentsOut.clear();
        ArrayList<String> moves = new ArrayList<>();
        try {
            MirrorPgnHistory history = readMirrorPgnHistory(pgn);
            moves.addAll(history.moves);
            if (commentsOut != null) commentsOut.addAll(history.comments);
        } catch (Exception ignored) {
            // Preserve the old partial-PGN recovery behavior for a live snapshot
            // that was captured while the runner was updating the current node.
            MirrorPgnHistory fallback = parseMirrorPgnMovetext(pgn, standardStartPosition());
            if (fallback != null) {
                moves.addAll(fallback.moves);
                if (commentsOut != null) commentsOut.addAll(fallback.comments);
            }
        }
        return moves;
    }

    private ArrayList<String> currentMirrorSanMoves(ArrayList<String> commentsOut) {
        if (mirrorBranchMode) {
            if (commentsOut != null) commentsOut.clear();
            ArrayList<String> combined = new ArrayList<>(mirrorBranchBaseMoves);
            combined.addAll(branchSansOnActivePath());
            if (commentsOut != null) {
                ArrayList<String> sourceComments;
                if (mirrorReplayMode) {
                    sourceComments = new ArrayList<>(mirrorReplayComments);
                } else {
                    sourceComments = new ArrayList<>(mirrorLiveComments);
                }
                for (int i = 0; i < mirrorBranchBaseMoves.size(); i++) {
                    String comment = i < sourceComments.size() ? sourceComments.get(i) : "";
                    // Preserve saved PGN analysis for the original part of the
                    // line; temporary branch moves intentionally have no PGN comments.
                    if ((comment == null || comment.trim().isEmpty()) && !mirrorReplayMode && !mirrorNavigationMode &&
                            i + 1 < mirrorLiveAnalysisByPly.size())
                        comment = mirrorLiveAnalysisByPly.get(i + 1);
                    commentsOut.add(comment == null ? "" : comment);
                }
                for (int i = 0; i < branchSansOnActivePath().size(); i++) commentsOut.add("");
            }
            return combined;
        }
        if (mirrorReplayMode) {
            if (commentsOut != null) { commentsOut.clear(); commentsOut.addAll(mirrorReplayComments); }
            return new ArrayList<>(mirrorReplayMoves);
        }
        if (mirrorLiveHistoryInitialized && mirrorLiveSanMoves.size() + 1 == mirrorPositions.size()) {
            if (commentsOut != null) {
                commentsOut.clear();
                for (int i = 0; i < mirrorLiveSanMoves.size(); i++) {
                    String comment = i < mirrorLiveComments.size() ? mirrorLiveComments.get(i) : "";
                    // Live analysis snapshots are keyed by resulting-position ply.
                    if ((comment == null || comment.trim().isEmpty()) && !mirrorNavigationMode &&
                            i + 1 < mirrorLiveAnalysisByPly.size())
                        comment = mirrorLiveAnalysisByPly.get(i + 1);
                    commentsOut.add(comment == null ? "" : comment);
                }
            }
            return new ArrayList<>(mirrorLiveSanMoves);
        }
        ArrayList<String> parsedComments = new ArrayList<>();
        ArrayList<String> parsed = parseMirrorMoves(currentPgnMoves, parsedComments);
        if (!parsed.isEmpty()) {
            if (commentsOut != null) {
                commentsOut.clear();
                for (int i = 0; i < parsed.size(); i++) {
                    String comment = i < parsedComments.size() ? parsedComments.get(i) : "";
                    if ((comment == null || comment.trim().isEmpty()) && !mirrorNavigationMode &&
                            i + 1 < mirrorLiveAnalysisByPly.size())
                        comment = mirrorLiveAnalysisByPly.get(i + 1);
                    commentsOut.add(comment == null ? "" : comment);
                }
            }
            return parsed;
        }
        if (commentsOut != null) commentsOut.clear();
        return new ArrayList<>(mirrorLiveSanMoves);
    }

    private ArrayList<String> branchSansOnActivePath() {
        ArrayList<String> sans = new ArrayList<>();
        for (MirrorBranchNode node : mirrorBranchPath()) sans.add(node.san);
        return sans;
    }

    private long mirrorReplayIntervalMs() {
        try {
            double seconds = Double.parseDouble(pgnPlaybackIntervalInput == null ? "1.0" :
                    pgnPlaybackIntervalInput.getText().toString().trim());
            if (!Double.isFinite(seconds)) seconds = 1.0;
            seconds = Math.max(0.1, Math.min(60.0, seconds));
            return Math.max(100L, Math.round(seconds * 1000.0));
        } catch (RuntimeException ignored) { return 1000L; }
    }

    private void toggleMirrorAutoPlay() {
        if (mirrorAutoPlay) {
            stopMirrorAutoPlay();
            updateMirrorNavigationButtons();
            return;
        }
        if (currentMirrorPositions().size() <= 1) return;
        mirrorAutoPlay = true;
        updateMirrorNavigationButtons();
        mirrorAutoPlayRunnable = new Runnable() {
            @Override public void run() {
                if (!mirrorAutoPlay) return;
                int nextPly = mirrorDisplayedPly() + 1;
                if (nextPly >= currentMirrorPositions().size()) {
                    stopMirrorAutoPlay();
                    if (!mirrorReplayMode) { mirrorPly = -1; syncMirrorFromLive(); }
                    updateMirrorNavigationButtons();
                    return;
                }
                navigateMirror(nextPly);
                mirrorAutoPlayHandler.postDelayed(this, mirrorReplayIntervalMs());
            }
        };
        mirrorAutoPlayHandler.postDelayed(mirrorAutoPlayRunnable, mirrorReplayIntervalMs());
    }

    private void stopMirrorAutoPlay() {
        mirrorAutoPlay = false;
        if (mirrorAutoPlayRunnable != null) mirrorAutoPlayHandler.removeCallbacks(mirrorAutoPlayRunnable);
        mirrorAutoPlayRunnable = null;
        updateMirrorNavigationButtons();
    }

    private void navigateMirror(int ply) {
        ArrayList<Position> positions = currentMirrorPositions();
        if (positions.isEmpty() || mirrorBoard == null) return;
        int minimum = mirrorBranchMode ? mirrorBranchBasePly : 0;
        int target = Math.max(minimum, Math.min(ply, positions.size() - 1));
        if (mirrorBranchMode) {
            int depth = target - mirrorBranchBasePly;
            mirrorBranchCurrent = mirrorBranchRoot;
            for (int i = 0; i < depth; i++) {
                if (mirrorBranchCurrent.children.isEmpty()) break;
                int childIndex = Math.max(0, Math.min(mirrorBranchCurrent.selectedChild, mirrorBranchCurrent.children.size() - 1));
                mirrorBranchCurrent = mirrorBranchCurrent.children.get(childIndex);
            }
            positions = mirrorBranchPositions();
            mirrorBoard.setPosition(new Position(positions.get(Math.min(target, positions.size() - 1))));
            mirrorBoard.setBranchMoves(branchMovesOnActivePath());
            mirrorPly = target;
            mirrorAnalysisHints.clear();
            mirrorBoard.setMoveHints(null);
            Move branchMove = mirrorBranchCurrent == mirrorBranchRoot ? null : mirrorBranchCurrent.move;
            updateMirrorLastMove(target, branchMove);
            updateMirrorMoveStrip();
            if (mirrorNavigationMode) {
                applyMirrorEngineOutputVisibility();
                updateMirrorFocusPanels();
            }
            if (mirrorAnalysisOn && mirrorAnalysisEngineActive) startMirrorAnalysis();
            updateMirrorNavigationButtons();
            return;
        }
        int oldPly = Math.max(0, mirrorPly);
        animateGameStep(mirrorBoard, positions, oldPly, target);
        if (!mirrorReplayMode && target >= positions.size() - 1) {
            mirrorPly = -1;
            mirrorBoard.setPosition(new Position(latestPosition));
        } else {
            mirrorPly = target;
            mirrorBoard.setPosition(new Position(positions.get(target)));
        }
        mirrorAnalysisHints.clear();
        if (!mirrorReplayMode) {
            ArrayList<Move> cachedHints = mirrorAnalysisHintsByPly.get(target);
            if (cachedHints != null) mirrorAnalysisHints.addAll(cachedHints);
        }
        if (mirrorBoard != null) {
            ArrayList<Move> visibleHints = visibleMirrorAnalysisHints();
            mirrorBoard.setMoveHints(visibleHints.isEmpty() ? null : visibleHints);
        }
        updateMirrorLastMove(target, null, false);
        if (mirrorStatusText != null) mirrorStatusText.setText(mirrorReplayMode ?
                "Replay · ply " + target + "/" + (positions.size() - 1) :
                (mirrorPly < 0 ? "Following live game" : "Live review · ply " + target + "/" + (positions.size() - 1)));
        if (mirrorReplayMode) updateMirrorReplayDetails(target);
        else updateMirrorRankedNames();
        if (mirrorNavigationMode) {
            applyMirrorEngineOutputVisibility();
            updateMirrorFocusPanels();
        }
        updateMirrorNavigationButtons();
        if (mirrorSelectedTab == 0) {
            invalidateMirrorMovesSubCache(0);
            invalidateMirrorSectionCache(0);
        }
        if ((mirrorNavigationMode || mirrorSelectedTab == 0) && mirrorAnalysisOn && mirrorAnalysisEngineActive)
            startMirrorAnalysis();
        scheduleMirrorSectionRefreshAfterMove(false);
    }

    /** Display the move which led to the selected position and keep it visible on the board. */
    private void updateMirrorLastMove(int ply, Move suppliedMove) {
        updateMirrorLastMove(ply, suppliedMove, true);
    }

    private void updateMirrorLastMove(int ply, Move suppliedMove, boolean updateMoveStrip) {
        ArrayList<Position> positions = currentMirrorPositions();
        Move lastMove = null;
        String moveLabel = "Starting position";
        if (ply > 0 && ply < positions.size()) {
            Position before = positions.get(ply - 1);
            Position after = positions.get(ply);
            lastMove = suppliedMove != null ? suppliedMove : findMoveBetweenPositions(before, after);
            String san = "";
            if (lastMove != null) {
                try { san = TextIO.moveToString(before, lastMove, false, false); }
                catch (RuntimeException ignored) { }
            }
            if (!san.isEmpty()) moveLabel = before.fullMoveCounter + (before.whiteMove ? "." : "...") + san;
            else moveLabel = "Ply " + ply;
        }
        mirrorNavigatedLastMove = lastMove;
        mirrorCurrentLastMoveLabel = moveLabel;
        if (mirrorLastMoveText != null) mirrorLastMoveText.setText(moveLabel);
        applyMirrorBoardHighlights(lastMove);
        if (updateMoveStrip) updateMirrorMoveStrip();
        updateMirrorNavigationButtons();
    }

    private Move moveAtPly(ArrayList<Position> positions, int ply) {
        if (positions == null || ply <= 0 || ply >= positions.size()) return null;
        return findMoveBetweenPositions(positions.get(ply - 1), positions.get(ply));
    }

    private Move findMoveBetweenPositions(Position before, Position after) {
        if (before == null || after == null) return null;
        try {
            for (Move move : new org.petero.droidfish.gamelogic.MoveGen().legalMoves(before)) {
                Position candidate = new Position(before);
                candidate.makeMove(move, new org.petero.droidfish.gamelogic.UndoInfo());
                if (samePosition(candidate, after)) return move;
            }
        } catch (RuntimeException ignored) { }
        return null;
    }

    private ArrayList<Move> visibleMirrorAnalysisHints() {
        if (mirrorAnalysisHints.isEmpty()) return new ArrayList<>();
        int limit = mirrorAnalysisVariationCount > 1 ? mirrorAnalysisVariationCount : mirrorAnalysisArrowPlyCount;
        limit = Math.max(1, Math.min(ColorTheme.MAX_ARROWS, limit));
        ArrayList<Move> visible = new ArrayList<>();
        for (Move move : mirrorAnalysisHints) {
            if (move == null) continue;
            visible.add(move);
            if (visible.size() >= limit) break;
        }
        return visible;
    }

    private void applyMirrorBoardHighlights(Move lastMove) {
        if (mirrorBoard == null) return;
        // Last-move marking is a square overlay, never an engine PV arrow.
        mirrorBoard.setLastMove(lastMove);
        mirrorBoard.setBranchMoves(mirrorBranchMode ? branchMovesOnActivePath() : null);
        ArrayList<Move> visibleHints = visibleMirrorAnalysisHints();
        mirrorBoard.setMoveHints(visibleHints.isEmpty() ? null : visibleHints);
        mirrorBoard.setSelection(-1);
    }

    private void returnMirrorToLive() {
        stopMirrorAutoPlay();
        mirrorReplayMode = false;
        invalidateAllMirrorSectionCaches();
        mirrorReplayLiveSnapshot = false;
        mirrorReplayPgn = "";
        mirrorReplayPairingIndex = -1;
        mirrorReplayWhiteEngineId = mirrorReplayBlackEngineId = "";
        mirrorReplayPositions.clear();
        invalidateAllMirrorSectionCaches();
        clearMirrorSubCaches();
        mirrorReplayMoves.clear();
        mirrorReplayComments.clear();
        mirrorPly = -1;
        syncMirrorFromLive();
        renderMirrorSection();
        if (mirrorScroll != null && mirrorSectionTitle != null)
            mirrorScroll.post(() -> mirrorScroll.smoothScrollTo(0, Math.max(0, mirrorSectionHost.getTop() - dp(110))));
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

    /** Temporary, in-memory Mirror Board variation tree. Never written to PGN or tournament data. */
    private static final class MirrorBranchNode {
        final Position position;
        final Move move;
        final String san;
        final MirrorBranchNode parent;
        final ArrayList<MirrorBranchNode> children = new ArrayList<>();
        int selectedChild;
        MirrorBranchNode(Position position, Move move, String san, MirrorBranchNode parent) {
            this.position = new Position(position); this.move = move; this.san = san; this.parent = parent;
        }
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
        final int selectedSubTab = mirrorStandingsSubTab;
        addCachedMirrorSubpanel(mirrorStandingsSubCache, selectedSubTab, () -> {
            if (selectedSubTab == 0) renderMirrorStandingsTable();
            else if (selectedSubTab == 1) renderMirrorGraphSection();
            else renderMirrorCrosstableSection();
        });
    }

    private void selectMirrorStandingsSubTab(int tab) {
        mirrorStandingsSubTab = Math.max(0, Math.min(2, tab));
        invalidateMirrorSectionCache(1);
        renderMirrorSection();
        updateMirrorGraphScrollSpacer();
        if (tab == 1 && mirrorScroll != null && mirrorSectionHost != null) {
            mirrorScroll.post(() -> mirrorScroll.smoothScrollTo(0, Math.max(0, mirrorSectionHost.getTop() - dp(84))));
        }
    }

    private void renderMirrorStandingsTable() {
        TournamentManager tournament = activeTournamentManager;
        if (tournament == null) {
            TextView empty = text("No tournament loaded.");
            mirrorSectionHost.addView(empty, fullWidth());
            return;
        }
        boolean cupBracketShownAboveRatings = false;
        if (tournament.format() == TournamentManager.Format.CUP) {
            String cupSummary = formatCupProgress(tournament, tournament.engines());
            android.text.SpannableString styledCupSummary = new android.text.SpannableString(cupSummary);
            for (TournamentManager.Engine engine : tournament.engines()) {
                if (engine.displayName == null || engine.displayName.isEmpty()) continue;
                int from = 0;
                while ((from = cupSummary.indexOf(engine.displayName, from)) >= 0) {
                    styledCupSummary.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                            from, from + engine.displayName.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    from += engine.displayName.length();
                }
            }
            TextView body = text(""); body.setText(styledCupSummary);
            body.setTextSize(15);
            body.setTextColor(0xFFE0E0E0);
            body.setPadding(dp(10), dp(8), dp(10), dp(8));
            ScrollView cupScroll = new MirrorSectionScrollView(this);
            cupScroll.addView(body, new ScrollView.LayoutParams(-1, -2));
            if (tournament.ratingEnabled()) {
                // Keep the Cup bracket visible, then show the same score-ranked Elo table below it.
                mirrorSectionHost.addView(cupScroll, new LinearLayout.LayoutParams(-1, dp(220)));
                cupBracketShownAboveRatings = true;
            } else {
                mirrorSectionHost.addView(cupScroll, new LinearLayout.LayoutParams(-1, -1));
                return;
            }
        }

        List<TournamentManager.Standing> standings = tournament.standings();
        Map<String, Double> ratings = tournament.ratings();
        boolean showRatingColumn = tournament.ratingEnabled();
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

        // Fixed columns intentionally make the table horizontally swipeable on phones.
        final int engineWidth = dp(178);
        final int ratingWidth = dp(88);
        final int scoreWidth = dp(96);
        final int resultWidth = dp(78);
        LinearLayout table = new LinearLayout(this);
        table.setOrientation(LinearLayout.VERTICAL);
        table.setPadding(dp(4), dp(4), dp(4), dp(8));
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        addMirrorStandingHeaderCell(header, "Engine", engineWidth);
        if (showRatingColumn) addMirrorStandingHeaderCell(header, "Rating", ratingWidth);
        addMirrorStandingHeaderCell(header, "Score", scoreWidth);
        addMirrorStandingHeaderCell(header, "W", resultWidth);
        addMirrorStandingHeaderCell(header, "D", resultWidth);
        addMirrorStandingHeaderCell(header, "L", resultWidth);
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
            engineName.setTextSize(14);
            engineName.setMaxLines(2);
            engineName.setEllipsize(android.text.TextUtils.TruncateAt.END);
            engineName.setTypeface(null, android.graphics.Typeface.BOLD);
            LinearLayout engineNameLine = new LinearLayout(this);
            engineNameLine.setGravity(android.view.Gravity.CENTER_VERTICAL);
            addMirrorEngineIcon(engineNameLine, standing.engineId, name, 28);
            engineNameLine.addView(engineName, new LinearLayout.LayoutParams(0, -2, 1));
            engineCell.addView(engineNameLine, fullWidth());
            row.addView(engineCell, new LinearLayout.LayoutParams(engineWidth, -2));

            if (showRatingColumn) {
                double elo = ratings.containsKey(standing.engineId) ? ratings.get(standing.engineId) : 3000.0;
                addMirrorStandingSimpleCell(row, formatElo(elo, tournament.ratingShowDecimals()), ratingWidth, rowColor, true);
            }
            String scoreMain = String.format(java.util.Locale.US, "%.1f/%s", standing.points,
                    (maxScore == Math.rint(maxScore) ? Integer.toString((int)maxScore) :
                            String.format(java.util.Locale.US, "%.1f", maxScore)));
            addMirrorStandingScoreCell(row, scoreMain, scorePct, scoreWidth, rowColor);
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
        mirrorSectionHost.addView(vertical, cupBracketShownAboveRatings ?
                new LinearLayout.LayoutParams(-1, 0, 1.0f) :
                new LinearLayout.LayoutParams(-1, -1));
    }

    private void addMirrorStandingSimpleCell(LinearLayout row, String value, int width, int background, boolean bold) {
        TextView cell = text(value);
        cell.setTextSize(13);
        cell.setGravity(android.view.Gravity.CENTER);
        cell.setTypeface(null, bold ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        cell.setTextColor(0xFFE0E0E0);
        cell.setPadding(dp(2), dp(13), dp(2), dp(13));
        cell.setBackgroundColor(background);
        row.addView(cell, new LinearLayout.LayoutParams(width, -2));
    }

    private void addMirrorStandingScoreCell(LinearLayout row, String value, double percent, int width, int background) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(android.view.Gravity.CENTER);
        cell.setPadding(dp(2), dp(7), dp(2), dp(7));
        cell.setBackgroundColor(background);
        TextView score = text(value);
        score.setTextSize(13);
        score.setGravity(android.view.Gravity.CENTER);
        score.setTypeface(null, android.graphics.Typeface.BOLD);
        TextView pct = text(String.format(java.util.Locale.US, "%.1f%%", percent));
        pct.setTextSize(10);
        pct.setTextColor(0xFFB8C4CC);
        pct.setGravity(android.view.Gravity.CENTER);
        cell.addView(score, fullWidth());
        cell.addView(pct, fullWidth());
        row.addView(cell, new LinearLayout.LayoutParams(width, -2));
    }

    private String[] mirrorGraphMetricLabels() {
        return new String[]{"Eval", "Depth", "Nodes", "NPS", "Time", "Engine WDL", "SelDepth"};
    }

    private String mirrorGraphMetricKey(int metric) {
        switch (metric) {
            case 1: return "Depth|D";
            case 2: return "Nodes|N";
            case 3: return "NPS|nps";
            case 4: return "EngineTime|Time|TimeUsed";
            case 6: return "SelDepth|SDepth|SD";
            default: return "Eval|eval";
        }
    }

    private boolean isMirrorAnalysisWaitingSnapshot(String analysis) {
        if (analysis == null) return true;
        String value = analysis.trim();
        return value.isEmpty() || value.startsWith("WHITE analysis waiting") ||
                value.startsWith("BLACK analysis waiting");
    }

    private String mirrorGraphRawValue(String comment, int metric) {
        if (comment == null || comment.trim().isEmpty()) return "";
        if (metric == 5) {
            java.util.regex.Matcher whiteWdl = java.util.regex.Pattern.compile(
                    "(?i)WhiteWDL:\\s*W:\\s*(\\d+(?:\\.\\d+)?)%\\s+D:\\s*(\\d+(?:\\.\\d+)?)%\\s+L:\\s*(\\d+(?:\\.\\d+)?)%")
                    .matcher(comment);
            if (whiteWdl.find()) return whiteWdl.group(1) + "|" + whiteWdl.group(3);
            java.util.regex.Matcher engineWdl = java.util.regex.Pattern.compile(
                    "(?i)Engine\\s+WDL:\\s*W:\\s*(\\d+(?:\\.\\d+)?)%\\s+D:\\s*(\\d+(?:\\.\\d+)?)%\\s+L:\\s*(\\d+(?:\\.\\d+)?)%")
                    .matcher(comment);
            return engineWdl.find() ? "ENGINE|" + engineWdl.group(1) + "|" + engineWdl.group(3) : "";
        }

        String value = findCommentValue(comment, mirrorGraphMetricKey(metric));
        if (!value.isEmpty()) {
            if (metric == 1 && value.indexOf('/') >= 0) return value.substring(0, value.indexOf('/'));
            if (metric == 6 && value.indexOf('/') >= 0) return value.substring(value.indexOf('/') + 1);
            return value;
        }

        // Also accept the raw UCI-style output used by engine panels, for example
        // "d:20/29 t:2.70 n:282k nps:212k". PGN snapshots use d20/29, n282k,
        // 212knps and et2.70s; both formats describe the same search sample.
        switch (metric) {
            case 0:
                value = firstGraphMatch(comment, new String[]{
                        "(?i)\\bEval\\s*[:=]\\s*([+-]?(?:M\\d+|\\d+(?:\\.\\d+)?))",
                        "(?i)(?:^|\\s)([+-]?(?:M\\d+|\\d+(?:\\.\\d+)?))(?=\\s|$)"});
                break;
            case 1:
                value = firstGraphMatch(comment, new String[]{
                        "(?i)\\bDepth\\s*[:=]\\s*(\\d+)(?:/\\d+)?",
                        "(?i)(?:^|\\s)d:([0-9]+)(?:/[0-9]+)?(?=\\s|$)"});
                break;
            case 2:
                value = firstGraphMatch(comment, new String[]{
                        "(?i)\\bNodes\\s*[:=]\\s*([0-9]+(?:\\.[0-9]+)?[kmb]?)",
                        "(?i)(?:^|\\s)n:?([0-9]+(?:\\.[0-9]+)?[kmb]?)(?=\\s|$)"});
                break;
            case 3:
                value = firstGraphMatch(comment, new String[]{
                        "(?i)\\bNPS\\s*[:=]\\s*([0-9]+(?:\\.[0-9]+)?[kmb]?)",
                        "(?i)([0-9]+(?:\\.[0-9]+)?(?:k|m)?nps)\\b"});
                break;
            case 4:
                value = firstGraphMatch(comment, new String[]{
                        "(?i)\\b(?:EngineTime|SearchTime|Time)\\s*[:=]\\s*([0-9]+(?::[0-9]{2})?(?:\\.[0-9]+)?s?)",
                        "(?i)(?:^|\\s)et([0-9]+(?:\\.[0-9]+)?s?)(?=\\s|$)",
                        "(?i)(?:^|\\s)t:([0-9]+(?:\\.[0-9]+)?)(?=\\s|$)"});
                break;
            case 6:
                value = firstGraphMatch(comment, new String[]{
                        "(?i)\\bSelDepth\\s*[:=]\\s*(\\d+)",
                        "(?i)\\bDepth\\s*[:=]\\s*\\d+/(\\d+)",
                        "(?i)(?:^|\\s)d:?\\d+/(\\d+)(?=\\s|$)"});
                break;
            default:
                value = "";
                break;
        }
        return value == null ? "" : value;
    }

    private String firstGraphMatch(String input, String[] patterns) {
        for (String expression : patterns) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(expression).matcher(input);
            if (matcher.find()) return matcher.group(1);
        }
        return "";
    }

    private String mirrorGraphValueForPly(String raw, int metric, int ply) {
        String value = mirrorGraphRawValue(raw, metric);
        if (metric == 5 && value.startsWith("ENGINE|")) {
            String[] wd = value.split("\\|");
            if (wd.length == 3) return wd[1];
        }
        if (metric == 5 && value.contains("|")) {
            String[] wd = value.split("\\|");
            if (wd.length == 2) return ((ply & 1) == 0 ? wd[0] : wd[1]);
        }
        // TournamentRunner stores every evaluation from White's point of view,
        // including the Black engine's search. Do not negate Black's value again.
        return value;
    }

    private void selectMirrorGraphMetric(int metric) {
        mirrorGraphMetric = Math.max(0, Math.min(6, metric));
        invalidateMirrorStandingsSubCache(1);
        invalidateMirrorSectionCache(1);
        renderMirrorSection();
        updateMirrorGraphScrollSpacer();
    }

    private String mirrorGraphEngineName(boolean white) {
        if (mirrorReplayMode) return white ? mirrorReplayWhiteName : mirrorReplayBlackName;
        String name = white ? currentWhiteName : currentBlackName;
        String id = white ? currentWhiteEngineId : currentBlackEngineId;
        return name == null || name.trim().isEmpty() ? mirrorAnalysisEngineNameForId(id) : name;
    }

    private String formatMirrorGraphClock(double seconds) {
        if (Double.isNaN(seconds) || Double.isInfinite(seconds) || seconds <= 0) return "—";
        if (seconds >= 60) {
            long whole = (long)seconds;
            return String.format(java.util.Locale.US, "%d:%02d", whole / 60, whole % 60);
        }
        return String.format(java.util.Locale.US, "%.1fs", seconds);
    }

    private void renderMirrorGraphSection() {
        ArrayList<String> comments = new ArrayList<>();
        ArrayList<String> moves = currentMirrorSanMoves(comments);
        TournamentRunner.AnalysisHistorySnapshot runnerHistory = null;
        if (!mirrorReplayMode && !mirrorBranchMode && runner != null) {
            // Reuse the runner's already-maintained per-position search snapshots.
            // This is the same analysis used for PGN move comments and does not
            // create a second search or depend on asynchronous UI callback timing.
            runnerHistory = runner.getAnalysisHistorySnapshot();
        }
        final int runnerOpeningOffset = runnerHistory == null ? -1 : runnerHistory.openingPlies;
        final int graphMoveCount = Math.max(moves.size(), runnerHistory == null ? 0 : runnerHistory.completedPlies);
        // Opening-book plies have no engine search sample. They remain visible on
        // the evaluation baseline, but are not fabricated as depth/nodes/NPS/time.
        final int bookOpeningPlies = runnerHistory == null ? 8 * 2 : runnerHistory.openingPlies;
        String[] labels = mirrorGraphMetricLabels();
        mirrorGraphMetric = Math.max(0, Math.min(labels.length - 1, mirrorGraphMetric));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(3), dp(4), dp(3), dp(10));
        content.setBackgroundColor(0xFF242424);

        // CCC-style compact metric strip at the top.
        HorizontalScrollView metricScroll = new HorizontalScrollView(this);
        metricScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout metricStrip = new LinearLayout(this);
        metricStrip.setOrientation(LinearLayout.HORIZONTAL);
        for (int metric = 0; metric < labels.length; metric++) {
            final int selected = metric;
            TextView tab = text(labels[metric]);
            tab.setTextSize(11);
            tab.setGravity(android.view.Gravity.CENTER);
            tab.setPadding(dp(10), dp(9), dp(10), dp(9));
            tab.setTypeface(null, metric == mirrorGraphMetric ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            tab.setTextColor(metric == mirrorGraphMetric ? 0xFFFFFFFF : 0xFFBBC5CC);
            tab.setBackgroundColor(metric == mirrorGraphMetric ? 0xFF455A64 : 0xFF303030);
            tab.setOnClickListener(v -> selectMirrorGraphMetric(selected));
            metricStrip.addView(tab, new LinearLayout.LayoutParams(-2, dp(36)));
        }
        metricScroll.addView(metricStrip, new HorizontalScrollView.LayoutParams(-2, -2));
        content.addView(metricScroll, fullWidth());

        ArrayList<Float> whiteValues = new ArrayList<>();
        ArrayList<Float> blackValues = new ArrayList<>();
        double[] depthSum = {0, 0}, npsSum = {0, 0}, searchTimeSum = {0, 0};
        int[] depthCount = {0, 0}, npsCount = {0, 0};
        int[] recorded = {0, 0};
        for (int ply = 0; ply < graphMoveCount; ply++) {
            String raw = ply < comments.size() ? comments.get(ply) : "";
            // Activity snapshots are keyed by resulting-position ply. Runner
            // snapshots are keyed from the position after its assigned opening.
            int liveIndex = ply + 1;
            String live = !mirrorReplayMode && !mirrorBranchMode && liveIndex < mirrorLiveAnalysisByPly.size()
                    ? mirrorLiveAnalysisByPly.get(liveIndex) : "";
            int side = ply & 1;
            if (runnerHistory != null) {
                int runnerIndex = ply - runnerOpeningOffset;
                ArrayList<String> perSide = side == 0 ? runnerHistory.white : runnerHistory.black;
                if (runnerIndex >= 0 && runnerIndex < perSide.size()) {
                    // Prefer a completed/current Runner sample. A waiting marker is
                    // not a sample: retain the per-ply Activity snapshot as fallback
                    // because it may have arrived just before the runner row changed.
                    String runnerSample = perSide.get(runnerIndex);
                    if (!isMirrorAnalysisWaitingSnapshot(runnerSample)) live = runnerSample;
                }
            }
            if (raw == null || raw.trim().isEmpty()) raw = live;
            String depthRaw = mirrorGraphRawValue(raw, 1);
            double depth = parseMirrorGraphValue(depthRaw, 1);
            if (!Double.isFinite(depth) && !live.isEmpty()) depth = parseMirrorGraphValue(mirrorGraphRawValue(live, 1), 1);
            if (Double.isFinite(depth)) { depthSum[side] += depth; depthCount[side]++; }
            String npsRaw = mirrorGraphRawValue(raw, 3);
            double nps = parseMirrorGraphValue(npsRaw, 3);
            if (!Double.isFinite(nps) && !live.isEmpty()) nps = parseMirrorGraphValue(mirrorGraphRawValue(live, 3), 3);
            if (Double.isFinite(nps)) { npsSum[side] += nps; npsCount[side]++; }
            String timeRaw = mirrorGraphRawValue(raw, 4);
            double moveSeconds = parseMirrorGraphValue(timeRaw, 4);
            if (!Double.isFinite(moveSeconds) && !live.isEmpty())
                moveSeconds = parseMirrorGraphValue(mirrorGraphRawValue(live, 4), 4);
            if (Double.isFinite(moveSeconds) && moveSeconds > 0) searchTimeSum[side] += moveSeconds;

            String value = mirrorGraphValueForPly(raw, mirrorGraphMetric, ply);
            double parsed = parseMirrorGraphValue(value, mirrorGraphMetric);
            if (!Double.isFinite(parsed) && !live.isEmpty()) {
                value = mirrorGraphValueForPly(live, mirrorGraphMetric, ply);
                parsed = parseMirrorGraphValue(value, mirrorGraphMetric);
            }
            boolean hasRecordedSample = Double.isFinite(parsed);
            boolean valid = hasRecordedSample;
            if (mirrorGraphMetric == 0) {
                if (ply < bookOpeningPlies) {
                    parsed = 0.0;
                    valid = true;
                }
            }
            if (hasRecordedSample) recorded[side]++;
            whiteValues.add(side == 0 && valid ? (float)parsed : Float.NaN);
            blackValues.add(side == 1 && valid ? (float)parsed : Float.NaN);
        }
        // Include the currently-running search as one extra point. The sample is
        // taken from the runner's existing current-position history row first, so
        // the graph is not limited by a delayed Activity callback.
        if (!mirrorReplayMode && runner != null && !runner.isFinished()) {
            int livePly = graphMoveCount;
            int liveSide = livePly & 1;
            // Do not seed the live point from lastWhite/lastBlackAnalysis: those
            // fields intentionally retain the previous completed search. Start
            // empty, use a current Runner row when present, then the per-ply cache.
            String liveAnalysis = runnerHistory == null ? latestTournamentAnalysis(currentWhiteToMove) : "";
            if (runnerHistory != null) {
                int runnerIndex = livePly - runnerOpeningOffset;
                ArrayList<String> perSide = liveSide == 0 ? runnerHistory.white : runnerHistory.black;
                if (runnerIndex >= 0 && runnerIndex < perSide.size()) {
                    String runnerSample = perSide.get(runnerIndex);
                    if (!isMirrorAnalysisWaitingSnapshot(runnerSample)) liveAnalysis = runnerSample;
                }
            }
            int liveSampleIndex = livePly + 1;
            if (liveSampleIndex < mirrorLiveAnalysisByPly.size()) {
                String activitySample = mirrorLiveAnalysisByPly.get(liveSampleIndex);
                if (isMirrorAnalysisWaitingSnapshot(liveAnalysis) &&
                        !isMirrorAnalysisWaitingSnapshot(activitySample))
                    liveAnalysis = activitySample;
            }
            String value = mirrorGraphValueForPly(liveAnalysis, mirrorGraphMetric, livePly);
            double parsed = parseMirrorGraphValue(value, mirrorGraphMetric);
            boolean hasRecordedSample = Double.isFinite(parsed);
            boolean valid = hasRecordedSample;
            if (mirrorGraphMetric == 0 && livePly < bookOpeningPlies) {
                parsed = 0.0;
                valid = true;
            }
            if (hasRecordedSample) recorded[liveSide]++;
            // This is an optional in-progress point at the end of the history.
            // Append it only when the current search has published a real sample;
            // unlike completed plies, no later point depends on its index yet.
            if (hasRecordedSample || (mirrorGraphMetric == 0 && valid)) {
                whiteValues.add(liveSide == 0 && valid ? (float)parsed : Float.NaN);
                blackValues.add(liveSide == 1 && valid ? (float)parsed : Float.NaN);
            }
        }

        String[] titles = {"Evaluation (White's view)", "Search depth", "Nodes searched", "Nodes per second",
                "Engine search time", "Engine WDL win probability", "Selective search depth"};
        TextView chartTitle = text(titles[mirrorGraphMetric]);
        chartTitle.setTextSize(13); chartTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        chartTitle.setTextColor(0xFFE2E8EC); chartTitle.setPadding(dp(8), dp(8), dp(8), dp(2));
        content.addView(chartTitle, fullWidth());
        String axisText = mirrorGraphMetric == 0 ? "Evaluation in pawns · positive favors White" :
                (mirrorGraphMetric == 2 || mirrorGraphMetric == 3 ? "Logarithmic scale · by move" : "By move number");
        TextView axisNote = text(axisText);
        axisNote.setTextSize(10); axisNote.setTextColor(0xFF9EABB4);
        axisNote.setPadding(dp(8), 0, dp(8), dp(3));
        content.addView(axisNote, fullWidth());
        int minimumSamplesForLine = 2;
        String sampleHint = (recorded[0] < minimumSamplesForLine || recorded[1] < minimumSamplesForLine)
                ? " · Need 2 samples per engine for a line" : "";
        TextView sampleSummary = text("Recorded search samples · White " + recorded[0] + " · Black " + recorded[1] + sampleHint);
        sampleSummary.setTextSize(10);
        sampleSummary.setTextColor(0xFF9EABB4);
        sampleSummary.setPadding(dp(8), 0, dp(8), dp(4));
        content.addView(sampleSummary, fullWidth());

        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int graphCount = Math.max(1, Math.max(whiteValues.size(), blackValues.size()));
        int chartWidth = Math.max(screenWidth - dp(14), dp(58) + dp(34) * graphCount);
        HorizontalScrollView chartScroll = new HorizontalScrollView(this);
        chartScroll.setFillViewport(false);
        chartScroll.setHorizontalScrollBarEnabled(true);
        chartScroll.addView(new MirrorGraphView(this, whiteValues, blackValues, mirrorGraphMetric, graphCount),
                new HorizontalScrollView.LayoutParams(chartWidth, dp(220)));
        content.addView(chartScroll, new LinearLayout.LayoutParams(-1, dp(220)));
        // Open on the newest moves, like the reference graph. The user can
        // still swipe horizontally back to the flat opening-book segment.
        chartScroll.post(() -> chartScroll.fullScroll(View.FOCUS_RIGHT));

        LinearLayout legend = new LinearLayout(this);
        legend.setGravity(android.view.Gravity.CENTER);
        legend.setPadding(0, dp(4), 0, dp(6));
        TextView whiteLegend = text("◆  " + mirrorGraphEngineName(true));
        whiteLegend.setTextSize(10); whiteLegend.setTextColor(0xFFF2F2F2);
        TextView blackLegend = text("●  " + mirrorGraphEngineName(false));
        blackLegend.setTextSize(10); blackLegend.setTextColor(0xFFB7B7B7);
        legend.addView(whiteLegend, new LinearLayout.LayoutParams(0, -2, 1));
        legend.addView(blackLegend, new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(legend, fullWidth());

        // Minimal source table as in the reference: one row per engine.
        LinearLayout statsTable = new LinearLayout(this);
        statsTable.setOrientation(LinearLayout.VERTICAL);
        statsTable.setBackgroundColor(0xFF303030);
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        String[] columns = {"ENGINE", "AVG DEPTH", "AVG NPS", "SEARCH TIME"};
        float[] weights = {1.45f, 0.9f, 0.95f, 0.95f};
        for (int i = 0; i < columns.length; i++) {
            TextView cell = text(columns[i]);
            cell.setTextSize(9); cell.setTypeface(null, android.graphics.Typeface.BOLD);
            cell.setTextColor(0xFFBFC8CE); cell.setGravity(android.view.Gravity.CENTER);
            cell.setPadding(dp(2), dp(7), dp(2), dp(7));
            header.addView(cell, new LinearLayout.LayoutParams(0, -2, weights[i]));
        }
        statsTable.addView(header, fullWidth());
        String[] rowNames = {mirrorGraphEngineName(true), mirrorGraphEngineName(false)};
        for (int side = 0; side < 2; side++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setBackgroundColor(side == 0 ? 0xFF262626 : 0xFF3A3A3A);
            String depthText = depthCount[side] == 0 ? "—" : String.format(java.util.Locale.US, "%.1f", depthSum[side] / depthCount[side]);
            String npsText = npsCount[side] == 0 ? "—" : compactViewerCount((long)(npsSum[side] / npsCount[side]));
            String[] values = {rowNames[side], depthText, npsText, formatMirrorGraphClock(searchTimeSum[side])};
            for (int i = 0; i < values.length; i++) {
                TextView cell = text(values[i]);
                cell.setTextSize(i == 0 ? 10 : 10); cell.setTextColor(0xFFE0E0E0);
                cell.setGravity(i == 0 ? android.view.Gravity.CENTER_VERTICAL : android.view.Gravity.CENTER);
                cell.setMaxLines(1); cell.setEllipsize(android.text.TextUtils.TruncateAt.END);
                cell.setPadding(dp(3), dp(7), dp(3), dp(7));
                row.addView(cell, new LinearLayout.LayoutParams(0, -2, weights[i]));
            }
            statsTable.addView(row, fullWidth());
        }
        content.addView(statsTable, fullWidth());
        TextView source = text("Source: saved PGN annotations and existing live analysis snapshots · no extra engine searches");
        source.setTextSize(9); source.setTextColor(0xFF87949C);
        source.setPadding(dp(7), dp(7), dp(7), 0);
        content.addView(source, fullWidth());
        mirrorSectionHost.addView(content, new LinearLayout.LayoutParams(-1, -2));
    }

    private double parseMirrorGraphValue(String value, int metric) {
        if (value == null || value.trim().isEmpty() || value.equalsIgnoreCase("n/a") || value.equals("?")) return Double.NaN;
        String v = value.trim().replace(",", "");
        if (metric == 0 && v.toUpperCase(java.util.Locale.US).contains("M"))
            return v.startsWith("-") ? -10.0 : 10.0;
        if (metric == 5 && v.contains("|")) {
            try { return Double.parseDouble(v.substring(0, v.indexOf('|'))); }
            catch (RuntimeException ignored) { return Double.NaN; }
        }
        if (metric == 1 && v.contains("/")) v = v.substring(0, v.indexOf('/'));
        if (metric == 6 && v.contains("/")) v = v.substring(v.indexOf('/') + 1);
        if (metric == 4 && v.contains(":")) {
            try {
                String[] parts = v.split(":");
                if (parts.length == 2)
                    return Double.parseDouble(parts[0]) * 60.0 + Double.parseDouble(parts[1].replaceAll("(?i)s$", ""));
                if (parts.length == 3)
                    return Double.parseDouble(parts[0]) * 3600.0 + Double.parseDouble(parts[1]) * 60.0 +
                            Double.parseDouble(parts[2].replaceAll("(?i)s$", ""));
            } catch (RuntimeException ignored) { return Double.NaN; }
        }
        if (metric == 3) v = v.replaceAll("(?i)nps$", "");
        if (metric == 4) v = v.replaceAll("(?i)s$", "");
        double scale = 1.0;
        if (!v.isEmpty()) {
            char suffix = Character.toUpperCase(v.charAt(v.length() - 1));
            if (suffix == 'K') { scale = 1000.0; v = v.substring(0, v.length() - 1); }
            else if (suffix == 'M') { scale = 1000000.0; v = v.substring(0, v.length() - 1); }
            else if (suffix == 'G') { scale = 1000000000.0; v = v.substring(0, v.length() - 1); }
        }
        try { return Double.parseDouble(v) * scale; }
        catch (NumberFormatException ignored) { return Double.NaN; }
    }

    private final class MirrorGraphView extends View {
        private final ArrayList<Float> whiteValues;
        private final ArrayList<Float> blackValues;
        private final int metric;
        private final int moveCount;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        MirrorGraphView(android.content.Context context, ArrayList<Float> white, ArrayList<Float> black,
                        int metric, int moveCount) {
            super(context);
            this.whiteValues = new ArrayList<>(white);
            this.blackValues = new ArrayList<>(black);
            this.metric = metric;
            this.moveCount = moveCount;
            setBackgroundColor(0xFF242424);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(0xFF242424);
            float left = dp(48), right = getWidth() - dp(12), top = dp(16), bottom = getHeight() - dp(34);
            if (right <= left || bottom <= top) return;
            float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
            for (Float value : whiteValues) if (value != null && (!Float.isNaN(value) && !Float.isInfinite(value))) {
                float axis = axisValue(value);
                if (Float.isNaN(axis) || Float.isInfinite(axis)) continue;
                min = Math.min(min, axis); max = Math.max(max, axis);
            }
            for (Float value : blackValues) if (value != null && (!Float.isNaN(value) && !Float.isInfinite(value))) {
                float axis = axisValue(value);
                if (Float.isNaN(axis) || Float.isInfinite(axis)) continue;
                min = Math.min(min, axis); max = Math.max(max, axis);
            }
            boolean hasData = (!Float.isNaN(min) && !Float.isInfinite(min) && !Float.isNaN(max) && !Float.isInfinite(max));
            if (metric == 0) {
                // Keep the reference evaluation scale fixed at -2..+2.
                // Scores outside this range are clipped to the chart edge so
                // move-to-move comparisons remain visually consistent.
                min = -2.0f; max = 2.0f;
            } else if (metric == 2 || metric == 3 || metric == 6) {
                if (!hasData) { min = metric == 6 ? 0 : 3; max = metric == 6 ? 2 : 9; }
                else {
                    min = (float)Math.floor(min);
                    max = (float)Math.ceil(max);
                    if (min == max) { min = Math.max(0, min - 1); max += 1; }
                }
            } else if (metric == 5) {
                min = 0; max = 100;
            } else {
                if (!hasData || max <= 0) max = 1;
                max = max * 1.12f;
                min = 0;
            }
            if (max <= min) max = min + 1;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(0xFF555555);
            for (int i = 0; i <= 4; i++) {
                float y = top + (bottom - top) * i / 4f;
                canvas.drawLine(left, y, right, y, paint);
            }
            if (metric == 0) {
                float zeroY = mapY(0, min, max, top, bottom);
                paint.setStrokeWidth(dp(1.5f)); paint.setColor(0xFF9A9A9A);
                canvas.drawLine(left, zeroY, right, zeroY, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setTextSize(dp(10));
            paint.setColor(0xFFD0D0D0);
            for (int i = 0; i <= 4; i++) {
                float axis = max - (max - min) * i / 4f;
                String label = formatAxisLabel(axis);
                float y = top + (bottom - top) * i / 4f;
                canvas.drawText(label, dp(3), y + dp(4), paint);
            }
            if (hasData) {
                float step = moveCount <= 1 ? 0 : (right - left) / Math.max(1, moveCount - 1f);
                drawSeries(canvas, whiteValues, true, step, left, top, bottom, min, max);
                drawSeries(canvas, blackValues, false, step, left, top, bottom, min, max);
            } else {
                paint.setStyle(Paint.Style.FILL); paint.setTextSize(dp(13)); paint.setColor(0xFFB0B0B0);
                String empty = "No recorded move analysis yet";
                canvas.drawText(empty, left + Math.max(0, (right - left - paint.measureText(empty)) / 2),
                        (top + bottom) / 2, paint);
            }
            // Show the full move number at the White ply. The Black sample follows
            // it halfway to the next number, as in the reference chart.
            paint.setStyle(Paint.Style.FILL); paint.setTextSize(dp(10)); paint.setColor(0xFFE0E0E0);
            float step = moveCount <= 1 ? 0 : (right - left) / Math.max(1, moveCount - 1f);
            int labelStep = moveCount > 28 ? 4 : (moveCount > 14 ? 2 : 1);
            for (int ply = 0; ply < moveCount; ply += 2 * labelStep) {
                float x = left + step * ply;
                String label = Integer.toString(ply / 2 + 1);
                canvas.drawText(label, x - paint.measureText(label) / 2, getHeight() - dp(8), paint);
            }
        }

        private float axisValue(float value) {
            if (metric == 2 || metric == 3 || metric == 6)
                return value >= 0 ? (float)Math.log10(value + 1.0f) : Float.NaN;
            return value;
        }

        private float mapY(float value, float min, float max, float top, float bottom) {
            float axis = axisValue(value);
            if ((Float.isNaN(axis) || Float.isInfinite(axis))) return bottom;
            if (metric == 0) axis = Math.max(min, Math.min(max, axis));
            return bottom - (axis - min) / (max - min) * (bottom - top);
        }

        private void drawSeries(Canvas canvas, ArrayList<Float> values, boolean white, float step,
                                float left, float top, float bottom, float min, float max) {
            float previousX = 0, previousY = 0;
            boolean previousValid = false;
            for (int i = 0; i < values.size(); i++) {
                Float value = values.get(i);
                boolean thisSidePly = white ? (i & 1) == 0 : (i & 1) == 1;
                if (!thisSidePly) continue; // The other engine's turn is not a gap in this series.
                boolean logMetric = metric == 2 || metric == 3 || metric == 6;
                boolean valid = value != null && (!Float.isNaN(value) && !Float.isInfinite(value)) && (!logMetric || value >= 0);
                // Skip missing metric samples without breaking the line. This connects
                // the last and next available snapshot for the same engine.
                if (!valid) continue;
                float x = left + step * i;
                float y = mapY(value, min, max, top, bottom);
                if (previousValid) {
                    paint.setStyle(Paint.Style.STROKE);
                    if (white) {
                        paint.setStrokeWidth(dp(4));
                        paint.setColor(0xFFFFFFFF);
                        canvas.drawLine(previousX, previousY, x, y, paint);
                    } else {
                        paint.setStrokeWidth(dp(5));
                        paint.setColor(0xFFB8B8B8);
                        canvas.drawLine(previousX, previousY, x, y, paint);
                        paint.setStrokeWidth(dp(2.8f));
                        paint.setColor(0xFF080808);
                        canvas.drawLine(previousX, previousY, x, y, paint);
                    }
                }
                float radius = dp(4.2f);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(white ? 0xFFFFFFFF : 0xFF080808);
                canvas.drawCircle(x, y, radius, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1.3f));
                paint.setColor(white ? 0xFF555555 : 0xFFB8B8B8);
                canvas.drawCircle(x, y, radius, paint);
                previousX = x; previousY = y; previousValid = true;
            }
        }

        private String formatAxisLabel(float value) {
            if (metric == 0) {
                if (Math.abs(value) < 0.005f) return "0";
                if (Math.abs(value - Math.round(value)) < 0.05f)
                    return Long.toString(Math.round(value));
                return String.format(java.util.Locale.US, "%.1f", value);
            }
            if (metric == 1) return String.format(java.util.Locale.US, "%.0f", value);
            if (metric == 2 || metric == 3 || metric == 6) return compactAxisCount(Math.max(0, (long)Math.round(Math.pow(10, value) - 1.0)));
            if (metric == 4) {
                if (value >= 60) {
                    long seconds = Math.max(0, (long)value);
                    return String.format(java.util.Locale.US, "%d:%02d", seconds / 60, seconds % 60);
                }
                return String.format(java.util.Locale.US, "%.1f", value);
            }
            if (metric == 5) return String.format(java.util.Locale.US, "%.0f%%", value);
            return compactAxisCount((long)value);
        }

        private String compactAxisCount(long value) {
            if (value >= 1_000_000_000L) return String.format(java.util.Locale.US, "%.0fB", value / 1_000_000_000.0);
            if (value >= 1_000_000L) return String.format(java.util.Locale.US, "%.0fM", value / 1_000_000.0);
            if (value >= 1_000L) return String.format(java.util.Locale.US, "%.0fK", value / 1_000.0);
            return Long.toString(value);
        }
    }

    private void renderMirrorCrosstableSection() {
        TournamentManager tournament = activeTournamentManager;
        if (tournament == null) { mirrorSectionHost.addView(text("No tournament loaded."), fullWidth()); return; }
        List<TournamentManager.Engine> allEngines = tournament.engines();
        HashMap<String, TournamentManager.Engine> enginesById = new HashMap<>();
        for (TournamentManager.Engine engine : allEngines) enginesById.put(engine.id, engine);
        ArrayList<TournamentManager.Engine> engines = new ArrayList<>();
        for (TournamentManager.Standing standing : tournament.standings()) {
            TournamentManager.Engine engine = enginesById.get(standing.engineId);
            if (engine != null) engines.add(engine);
        }
        // Build the head-to-head scores once. The matrix is then drawn as one
        // lightweight custom row view per engine instead of thousands of
        // individual TextViews (64 engines previously meant 4,096 cell views).
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

        int nameWidth = dp(170), cellWidth = dp(88), pointsWidth = dp(52);
        LinearLayout table = new LinearLayout(this); table.setOrientation(LinearLayout.VERTICAL);
        LinearLayout header = new LinearLayout(this); header.setOrientation(LinearLayout.HORIZONTAL);
        addMirrorStandingHeaderCell(header, "Engine", nameWidth);
        for (TournamentManager.Engine engine : engines) {
            LinearLayout cell = new LinearLayout(this); cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(android.view.Gravity.CENTER); cell.setPadding(dp(2), dp(3), dp(2), dp(3));
            addMirrorEngineIcon(cell, engine.id, engine.displayName, 30);
            TextView name = text(engine.displayName); name.setTextSize(11); name.setMaxLines(2);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END); name.setGravity(android.view.Gravity.CENTER);
            name.setTypeface(null, android.graphics.Typeface.BOLD);
            cell.addView(name, new LinearLayout.LayoutParams(cellWidth - dp(4), -2));
            header.addView(cell, new LinearLayout.LayoutParams(cellWidth, -2));
        }
        addMirrorStandingHeaderCell(header, "Pts", pointsWidth);
        table.addView(header, new LinearLayout.LayoutParams(-2, -2));

        for (int i = 0; i < engines.size(); i++) {
            TournamentManager.Engine engine = engines.get(i);
            LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setBackgroundColor((i & 1) == 0 ? 0xFF303030 : 0xFF292929);
            LinearLayout nameCell = new LinearLayout(this); nameCell.setGravity(android.view.Gravity.CENTER_VERTICAL);
            nameCell.setPadding(dp(4), dp(7), dp(3), dp(7));
            TextView name = text((i + 1) + ". " + engine.displayName); name.setTextSize(13); name.setMaxLines(2);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setTypeface(null, android.graphics.Typeface.BOLD);
            addMirrorEngineIcon(nameCell, engine.id, engine.displayName, 30);
            nameCell.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(nameCell, new LinearLayout.LayoutParams(nameWidth, dp(48)));

            String[] cells = new String[engines.size()];
            StringBuilder rowAccessibility = new StringBuilder("Head-to-head scores for ").append(engine.displayName);
            double totalPoints = 0;
            for (int j = 0; j < engines.size(); j++) {
                cells[j] = "—";
                if (i == j) continue;
                String key = engine.id + "\u0001" + engines.get(j).id;
                double points = crossScores.containsKey(key) ? crossScores.get(key) : 0.0;
                int played = crossGames.containsKey(key) ? crossGames.get(key) : 0;
                if (played > 0) cells[j] = formatCrosstableScore(points) + "/" + played;
                totalPoints += points;
                rowAccessibility.append("; ").append(engines.get(j).displayName).append(": ").append(cells[j]);
            }
            MirrorCrosstableCellsView scoreCells = new MirrorCrosstableCellsView(i, cells, cellWidth);
            scoreCells.setContentDescription(rowAccessibility.toString());
            row.addView(scoreCells, new LinearLayout.LayoutParams(cellWidth * engines.size(), dp(48)));

            TextView points = text(String.format(java.util.Locale.US, "%.1f", totalPoints));
            points.setTextSize(11); points.setGravity(android.view.Gravity.CENTER); points.setTypeface(null, android.graphics.Typeface.BOLD);
            row.addView(points, new LinearLayout.LayoutParams(pointsWidth, dp(48)));
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

    /** Draw a crosstable row in Canvas to avoid allocating one Android View per cell. */
    private final class MirrorCrosstableCellsView extends View {
        private final int rowIndex;
        private final String[] values;
        private final int cellWidth;
        private final android.graphics.Paint backgroundPaint = new android.graphics.Paint();
        private final android.graphics.Paint textPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        MirrorCrosstableCellsView(int rowIndex, String[] values, int cellWidth) {
            super(TournamentActivity.this);
            this.rowIndex = rowIndex;
            this.values = values;
            this.cellWidth = cellWidth;
            textPaint.setColor(0xFFFFFFFF);
            textPaint.setTextAlign(android.graphics.Paint.Align.CENTER);
            textPaint.setTextSize(android.util.TypedValue.applyDimension(
                    android.util.TypedValue.COMPLEX_UNIT_SP, 13, getResources().getDisplayMetrics()));
            setWillNotDraw(false);
        }

        @Override protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            float height = getHeight();
            android.graphics.Paint.FontMetrics metrics = textPaint.getFontMetrics();
            float baseline = height / 2.0f - (metrics.ascent + metrics.descent) / 2.0f;
            for (int column = 0; column < values.length; column++) {
                int background = rowIndex == column ? 0xFF252525 :
                        ((rowIndex + column) % 2 == 0 ? 0xFF303030 : 0xFF292929);
                backgroundPaint.setColor(background);
                float left = column * cellWidth;
                canvas.drawRect(left, 0, left + cellWidth, height, backgroundPaint);
                String value = values[column];
                if (value != null && !value.isEmpty())
                    canvas.drawText(value, left + cellWidth / 2.0f, baseline, textPaint);
            }
        }
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
        count.setTextSize(15);
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
        // Schedule rows are useful immediately. Move counts are filled from the
        // immutable PGN cache when the background preparation worker completes.
        ensureMirrorScheduleMoveCountsReady(tournament);
        mirrorScheduleRows.clear();
        mirrorSchedulePairingIndexes.clear();
        List<TournamentManager.Engine> engines = new ArrayList<>(tournament.engines());
        List<TournamentManager.Pairing> pairings = new ArrayList<>(tournament.pairings());
        final HashMap<Integer, TournamentManager.Pairing> pairingsByIndex = new HashMap<>();
        for (TournamentManager.Pairing item : pairings) pairingsByIndex.put(item.index, item);
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
        HashMap<Integer, Integer> priorityPositions = new HashMap<>();
        for (int i = 0; i < priorityQueue.size(); i++) priorityPositions.put(priorityQueue.get(i), i + 1);
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
            Integer queuedPosition = priorityPositions.get(pairing.index);
            int queuePosition = queuedPosition == null ? -1 : queuedPosition;
            String state = pairing.replayPending ? " · replay queued" : (queuePosition > 0 ? " · priority #" + queuePosition : "");
            String prefix = swiss ? "R" + pairing.round + "." + pairing.gameNumber
                    : (cup ? "Pair " + pairing.cupPairNumber + " · Game " + pairing.cupGameInPair
                    : Integer.toString(pairing.index + 1));
            String row = prefix + ". " + white + " - " + black + " (" + mirrorScheduleResultLabel(pairing) + ")" + state +
                    "\n" + scheduleMoveCountLabel(tournament, pairing);
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
                TournamentManager.Pairing pairing = pairingsByIndex.get(pairingIndex);
                if (pairing == null) {
                    TextView fallback = new TextView(TournamentActivity.this); fallback.setText(getItem(position));
                    fallback.setTextColor(0xFFE6E6E6); fallback.setPadding(dp(10), dp(7), dp(28), dp(7)); return fallback;
                }
                LinearLayout row = new LinearLayout(TournamentActivity.this); row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                row.setPadding(dp(7), dp(7), dp(7), dp(7));
                row.setBackgroundColor((position & 1) == 0 ? 0xFF303030 : 0xFF292929);
                String white = pairing.isComplete() && !pairing.whiteNameAtPlay.isEmpty() ? pairing.whiteNameAtPlay : engineDisplayName(engines, pairing.whiteEngineId);
                String black = pairing.isComplete() && !pairing.blackNameAtPlay.isEmpty() ? pairing.blackNameAtPlay : engineDisplayName(engines, pairing.blackEngineId);
                LinearLayout left = new LinearLayout(TournamentActivity.this); left.setGravity(android.view.Gravity.CENTER_VERTICAL);
                TextView number = text((pairing.index + 1) + "."); number.setTextSize(12); number.setTextColor(0xFFBFCBD2);
                left.addView(number, new LinearLayout.LayoutParams(dp(25), -2));
                addMirrorEngineIcon(left, pairing.whiteEngineId, white, 38);
                LinearLayout whiteLabels = new LinearLayout(TournamentActivity.this); whiteLabels.setOrientation(LinearLayout.VERTICAL);
                String[] whiteNameParts = splitMirrorEngineNameVersion(white);
                TextView whiteName = text(whiteNameParts[0]); whiteName.setText(scheduleEngineName(whiteNameParts[0], pairing, true)); whiteName.setTextSize(14); whiteName.setTypeface(null, android.graphics.Typeface.BOLD);
                whiteName.setMaxLines(1); whiteName.setEllipsize(android.text.TextUtils.TruncateAt.END);
                whiteLabels.addView(whiteName, fullWidth());
                TextView whiteRank = text(mirrorScheduleRankLabel(pairing.whiteEngineId));
                whiteRank.setTextSize(12); whiteRank.setTextColor(0xFFD3DCE1);
                whiteLabels.addView(whiteRank, fullWidth());
                left.addView(whiteLabels, new LinearLayout.LayoutParams(0, -2, 1));
                row.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
                LinearLayout center = new LinearLayout(TournamentActivity.this); center.setOrientation(LinearLayout.VERTICAL); center.setGravity(android.view.Gravity.CENTER);
                TextView result = text(mirrorScheduleResultLabel(pairing)); result.setTextSize(14); result.setTypeface(null, android.graphics.Typeface.BOLD);
                TextView moves = text(scheduleMoveCountLabel(tournament, pairing)); moves.setTextSize(11); moves.setTextColor(0xFFBFCBD2);
                center.addView(result, new LinearLayout.LayoutParams(-2, -2)); center.addView(moves, new LinearLayout.LayoutParams(-2, -2));
                row.addView(center, new LinearLayout.LayoutParams(dp(82), -2));
                LinearLayout right = new LinearLayout(TournamentActivity.this); right.setGravity(android.view.Gravity.CENTER_VERTICAL);
                addMirrorEngineIcon(right, pairing.blackEngineId, black, 38);
                LinearLayout blackLabels = new LinearLayout(TournamentActivity.this); blackLabels.setOrientation(LinearLayout.VERTICAL);
                String[] blackNameParts = splitMirrorEngineNameVersion(black);
                TextView blackName = text(blackNameParts[0]); blackName.setText(scheduleEngineName(blackNameParts[0], pairing, false)); blackName.setTextSize(14); blackName.setTypeface(null, android.graphics.Typeface.BOLD);
                blackName.setMaxLines(1); blackName.setEllipsize(android.text.TextUtils.TruncateAt.END);
                blackLabels.addView(blackName, fullWidth());
                TextView blackRank = text(mirrorScheduleRankLabel(pairing.blackEngineId));
                blackRank.setTextSize(12); blackRank.setTextColor(0xFFD3DCE1);
                blackLabels.addView(blackRank, fullWidth());
                right.addView(blackLabels, new LinearLayout.LayoutParams(0, -2, 1));
                row.addView(right, new LinearLayout.LayoutParams(0, -2, 1));
                return row;
            }
        };
        list.setAdapter(adapter);
        if (mirrorScheduleHasSavedScroll) {
            final int restorePosition = mirrorScheduleFirstVisiblePosition;
            final int restoreTop = mirrorScheduleFirstVisibleTop;
            list.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    if (list.getViewTreeObserver().isAlive())
                        list.getViewTreeObserver().removeOnPreDrawListener(this);
                    list.setSelectionFromTop(Math.max(0, Math.min(restorePosition, adapter.getCount() - 1)), restoreTop);
                    mirrorScheduleHasSavedScroll = false;
                    return true;
                }
            });
        } else {
            list.post(this::scrollMirrorScheduleToCurrentGame);
        }
        // A delayed single tap opens the selected PGN; a second tap on the same
        // row within the platform double-tap window opens adjudication instead.
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= mirrorSchedulePairingIndexes.size()) return;
            if (mirrorSchedulePendingClick != null) {
                mirrorScheduleClickHandler.removeCallbacks(mirrorSchedulePendingClick);
                mirrorSchedulePendingClick = null;
                int previous = mirrorSchedulePendingPosition;
                mirrorSchedulePendingPosition = -1;
                if (previous == position) {
                    if (savedTournamentReviewMode) {
                        Toast.makeText(this, "Completed tournament review is read-only", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    int pairingIndex = mirrorSchedulePairingIndexes.get(position);
                    if (pairingIndex >= 0) showMirrorScheduleAdjudication(tournament, pairingIndex);
                    return;
                } else if (previous >= 0 && previous < mirrorSchedulePairingIndexes.size()) {
                    int previousIndex = mirrorSchedulePairingIndexes.get(previous);
                    if (previousIndex >= 0) loadMirrorScheduledGame(tournament, previousIndex);
                }
            }
            mirrorSchedulePendingPosition = position;
            mirrorSchedulePendingClick = () -> {
                int pending = mirrorSchedulePendingPosition;
                mirrorSchedulePendingPosition = -1;
                mirrorSchedulePendingClick = null;
                if (pending >= 0 && pending < mirrorSchedulePairingIndexes.size()) {
                    int pairingIndex = mirrorSchedulePairingIndexes.get(pending);
                    if (pairingIndex >= 0) loadMirrorScheduledGame(tournament, pairingIndex);
                }
            };
            long doubleTapWindow = Math.max(300L, android.view.ViewConfiguration.getDoubleTapTimeout());
            mirrorScheduleClickHandler.postDelayed(mirrorSchedulePendingClick, doubleTapWindow + 40L);
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            cancelMirrorSchedulePendingClick();
            if (savedTournamentReviewMode) return true;
            if (position >= 0 && position < mirrorSchedulePairingIndexes.size()) {
                int pairingIndex = mirrorSchedulePairingIndexes.get(position);
                if (pairingIndex >= 0) showMirrorScheduleContextMenu(tournament, pairingIndex);
            }
            return true;
        });
        mirrorSectionHost.addView(list, new LinearLayout.LayoutParams(-1, -1));
    }


    private void scrollMirrorScheduleToCurrentGame() {
        if (mirrorScheduleList == null || mirrorSchedulePairingIndexes.isEmpty() || activeTournamentManager == null) return;
        TournamentManager.Pairing active = activeTournamentManager.current();
        final int targetPairing = active != null ? active.index : (currentGameNumber > 0 ? currentGameNumber - 1 : -1);
        if (targetPairing < 0) return;
        for (int row = 0; row < mirrorSchedulePairingIndexes.size(); row++) {
            if (mirrorSchedulePairingIndexes.get(row) == targetPairing) {
                final int targetRow = row;
                mirrorScheduleList.post(() -> {
                    if (mirrorScheduleList != null && mirrorScheduleList.getAdapter() != null &&
                            targetRow < mirrorScheduleList.getAdapter().getCount())
                        mirrorScheduleList.setSelection(targetRow);
                });
                return;
            }
        }
    }

    private void cancelMirrorSchedulePendingClick() {
        if (mirrorSchedulePendingClick != null) mirrorScheduleClickHandler.removeCallbacks(mirrorSchedulePendingClick);
        mirrorSchedulePendingClick = null;
        mirrorSchedulePendingPosition = -1;
    }

    private void showMirrorScheduleContextMenu(TournamentManager tournament, int pairingIndex) {
        if (blockSavedTournamentReviewEdit()) return;
        new AlertDialog.Builder(this).setTitle("Schedule actions · Game " + (pairingIndex + 1))
                .setItems(new String[]{"Anomalies", "Play Queue", "Replay Game", "Adjudicate / Re-adjudicate"}, (dialog, which) -> {
                    if (which == 0) showAnomaliesDialog(tournament);
                    else if (which == 1) showMirrorPlayQueueEditor(tournament);
                    else if (which == 2) showReplayGamePicker(tournament);
                    else showMirrorScheduleAdjudication(tournament, pairingIndex);
                }).show();
    }

    private void showMirrorScheduleAdjudication(TournamentManager tournament, int pairingIndex) {
        if (blockSavedTournamentReviewEdit()) return;
        TournamentManager.Pairing pairing = findMirrorPairing(tournament, pairingIndex);
        if (pairing == null) return;
        if (!pairing.isComplete() || pairing.replayPending) {
            Toast.makeText(this, "Only completed games can be adjudicated from the anomaly schedule", Toast.LENGTH_LONG).show();
            return;
        }
        if (tournament.format() == TournamentManager.Format.CUP) {
            Toast.makeText(this, "Cup results are locked to preserve bracket advancement; use CONFIRM in Anomalies", Toast.LENGTH_LONG).show();
            showAnomaliesDialog(tournament);
            return;
        }
        final int[] selected = {pairing.outcome == TournamentManager.Outcome.WHITE_WIN ? 0 :
                pairing.outcome == TournamentManager.Outcome.BLACK_WIN ? 1 : 2};
        String[] results = {"White wins (1-0)", "Black wins (0-1)", "Draw (1/2-1/2)"};
        new AlertDialog.Builder(this).setTitle("Adjudicate Game " + (pairingIndex + 1))
                .setSingleChoiceItems(results, selected[0], (dialog, which) -> selected[0] = which)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("CONFIRM", (dialog, which) -> {
                    TournamentManager.Outcome outcome = selected[0] == 0 ? TournamentManager.Outcome.WHITE_WIN :
                            selected[0] == 1 ? TournamentManager.Outcome.BLACK_WIN : TournamentManager.Outcome.DRAW;
                    try {
                        tournament.updatePairingResult(pairingIndex, outcome);
                        String warning = "";
                        try { saveTournamentPgnAutomatically(tournament); }
                        catch (Exception exportError) { warning = " · PGN export update failed: " + exportError.getMessage(); }
                        invalidateCachedSchedule(tournament);
                        refreshMirrorRanks();
                        invalidateAllMirrorSectionCaches();
                        clearMirrorSubCaches();
                        if (isMirrorShowing()) renderMirrorSection();
                        Toast.makeText(this, "Game adjudicated and removed from pending anomalies" + warning, Toast.LENGTH_LONG).show();
                    } catch (Exception error) {
                        Toast.makeText(this, "Could not adjudicate game: " + error.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }).show();
    }

    private String mirrorScheduleResultLabel(TournamentManager.Pairing pairing) {
        if (pairing == null) return "*";
        if (pairing.isComplete()) return pairingResultLabel(pairing.outcome);
        boolean current = runner != null && !runner.isFinished() && currentGameNumber == pairing.index + 1;
        if (current && !pairing.whiteEngineId.isEmpty() && !pairing.blackEngineId.isEmpty()) return "...";
        return "*";
    }

    private String mirrorScheduleRankLabel(String engineId) {
        Integer rank = engineId == null ? null : mirrorRanks.get(engineId);
        return "#" + (rank == null ? "—" : rank);
    }

    private String[] splitMirrorEngineNameVersion(String displayName) {
        String value = displayName == null ? "" : displayName.trim();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "^(.*\\S)\\s+((?:v)?\\d+(?:\\.\\d+)*(?:[-+._A-Za-z0-9]*))$").matcher(value);
        if (matcher.matches() && !matcher.group(1).trim().isEmpty())
            return new String[]{matcher.group(1).trim(), matcher.group(2).trim()};
        return new String[]{value, ""};
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
                MirrorPgnHistory history = readMirrorPgnHistory(pgn);
                ArrayList<Position> positions = history.positions;
                ArrayList<String> moves = history.moves;
                ArrayList<String> comments = history.comments;
                if (positions.size() != moves.size() + 1)
                    throw new IOException("PGN move history could not be reconstructed completely");
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
                    updateMirrorLastMove(0, null, false);
                    mirrorMovesSubTab = 0;
                    mirrorMovesListHasSavedPosition = false;
                    mirrorMovesListScrollY = 0;
                    invalidateMirrorMovesSubCache(0);
                    invalidateMirrorSectionCache(0);
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
        TournamentRunner liveRunner = runner;
        if (liveRunner != null) {
            try { currentPgnMoves = liveRunner.currentPgn(); }
            catch (RuntimeException e) { Toast.makeText(this, "Could not capture live PGN: " + e.getMessage(), Toast.LENGTH_LONG).show(); return; }
        }
        ArrayList<String> comments = new ArrayList<>();
        ArrayList<String> moves = currentMirrorSanMoves(comments);
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
        String whiteAnalysis = "—    Depth: —    Nodes: —    NPS: —";
        String blackAnalysis = "—    Depth: —    Nodes: —    NPS: —";
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
        updateMirrorEngineOutputDisplays(whiteAnalysis, blackAnalysis);
        updateMirrorEvaluationBars(whiteAnalysis, blackAnalysis);
        updateMirrorRankedNames();
        updateMirrorMoveStrip();
        if (mirrorNavigationMode || mirrorEngineOutputHidden) updateMirrorFocusPanels();
        if (mirrorStatusText != null) mirrorStatusText.setText((mirrorReplayLiveSnapshot ? "Live snapshot" : "Saved PGN") +
                " · ply " + safePly + "/" + mirrorReplayMoves.size());
    }

    private void renderMirrorInfoSection() {
        Button activeShortcut = new Button(this);
        activeShortcut.setText("ACTIVE TOURNAMENTS · RESUME / VIEW");
        activeShortcut.setOnClickListener(v -> chooseSavedTournament());
        mirrorSectionHost.addView(activeShortcut, fullWidth());
        Button completedShortcut = new Button(this);
        completedShortcut.setText("COMPLETED TOURNAMENTS · VIEW ONLY");
        completedShortcut.setOnClickListener(v -> chooseCompletedTournaments());
        mirrorSectionHost.addView(completedShortcut, fullWidth());
        TournamentManager t = activeTournamentManager;
        if (t == null) {
            TextView empty = text("No tournament loaded. Choose Active Tournaments to resume an event, or Completed Tournaments to view a read-only archive.");
            empty.setTextColor(0xFFE0E0E0);
            empty.setPadding(dp(10), dp(12), dp(10), dp(8));
            mirrorSectionHost.addView(empty, fullWidth());
            return;
        }
        StringBuilder info = new StringBuilder();
        info.append(t.title()).append("\n");
        info.append(t.isComplete() ? "STATUS · COMPLETED — READ ONLY\n\n" : "STATUS · ACTIVE — RESUMABLE\n\n");
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
            String pgnBook = t.openingPgnFileName();
            if (pgnBook == null || pgnBook.trim().isEmpty())
                pgnBook = selectedOpeningFileName == null ? "" : selectedOpeningFileName.trim();
            if (pgnBook.isEmpty() || pgnBook.equals("No PGN file selected") || pgnBook.equals("Saved PGN book loaded"))
                pgnBook = "Selected PGN file name unavailable";
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
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        TextView body = text(info.toString());
        body.setTextSize(14);
        body.setTextColor(0xFFE0E0E0);
        body.setPadding(dp(10), dp(8), dp(10), dp(6));
        content.addView(body, fullWidth());
        TextView engineHeading = text("ENGINE LIST");
        engineHeading.setTextSize(14); engineHeading.setTypeface(null, android.graphics.Typeface.BOLD);
        engineHeading.setTextColor(0xFFBFCBD2); engineHeading.setPadding(dp(10), dp(10), dp(10), dp(4));
        content.addView(engineHeading, fullWidth());
        for (int i = 0; i < t.engines().size(); i++) {
            TournamentManager.Engine engine = t.engines().get(i);
            LinearLayout engineRow = new LinearLayout(this);
            engineRow.setOrientation(LinearLayout.HORIZONTAL);
            engineRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
            engineRow.setPadding(dp(12), dp(4), dp(10), dp(4));
            if (engineIconEnabledFor(engine.id)) {
                Bitmap engineBitmap = mirrorEngineIcon(engine.id, engine.displayName);
                if (engineBitmap != null) {
                    ImageView icon = new ImageView(this);
                    icon.setImageDrawable(new BitmapDrawable(getResources(), engineBitmap));
                    icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    engineRow.addView(icon, new LinearLayout.LayoutParams(dp(30), dp(30)));
                } else {
                    TextView fallbackIcon = text("♟");
                    fallbackIcon.setTextSize(23);
                    fallbackIcon.setTextColor(0xFFBFCBD2);
                    fallbackIcon.setGravity(android.view.Gravity.CENTER);
                    engineRow.addView(fallbackIcon, new LinearLayout.LayoutParams(dp(30), dp(30)));
                }
            }
            TextView engineLine = text((i + 1) + ". " + engine.displayName);
            engineLine.setTextSize(16); engineLine.setTypeface(null, android.graphics.Typeface.BOLD);
            engineLine.setTextColor(0xFFFFFFFF);
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, -2, 1);
            nameParams.leftMargin = dp(8);
            engineRow.addView(engineLine, nameParams);
            content.addView(engineRow, fullWidth());
        }
        TextView elapsed = text("Tournament elapsed: " + formatElapsedSeconds(
                t == activeTournamentManager ? currentTournamentElapsedMs() : t.tournamentElapsedMs()));
        elapsed.setTextSize(13); elapsed.setTextColor(0xFFBFCBD2);
        elapsed.setPadding(dp(10), dp(12), dp(10), dp(12));
        content.addView(elapsed, fullWidth());
        ScrollView scroll = new MirrorSectionScrollView(this);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
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
        gvWhiteAnalysis=text("White ---  Depth: ---  Nodes: ---  NPS: ---"); gvWhiteAnalysis.setTextSize(16); gvWhiteAnalysis.setLines(2); gvWhiteAnalysis.setMaxLines(2); page.addView(gvWhiteAnalysis,fullWidth());
        gvBlackAnalysis=text("Black ---  Depth: ---  Nodes: ---  NPS: ---"); gvBlackAnalysis.setTextSize(16); gvBlackAnalysis.setLines(2); gvBlackAnalysis.setMaxLines(2); page.addView(gvBlackAnalysis,fullWidth());
        gvMoves=text("No PGN loaded."); gvMoves.setTextSize(16); page.addView(gvMoves,fullWidth());
        gvStatus=text("Open a PGN to review recorded analysis."); page.addView(gvStatus,fullWidth());
        Button fresh=new Button(this); fresh.setText("New empty game"); page.addView(fresh,fullWidth()); fresh.setOnClickListener(v -> startEmptyGameViewer());
        Button openBottom=new Button(this); openBottom.setText("Open a PGN"); page.addView(openBottom,fullWidth()); openBottom.setOnClickListener(v->openGameViewerPicker());
        Button back=new Button(this); back.setText("Go to Setup"); page.addView(back,fullWidth()); back.setOnClickListener(v->{ stopGameViewerAnalysis(); boardSelectionActive = false; setContentView(setupView); });
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
        if (gameViewerPositions.isEmpty()) return;
        int oldPly = gameViewerPly;
        int targetPly = Math.max(0, Math.min(ply, gameViewerPositions.size() - 1));
        animateGameStep(gameViewerBoard, gameViewerPositions, oldPly, targetPly);
        gameViewerPly = targetPly;
        renderGameViewerPly();
        if (gameViewerAnalysisOn && !gameViewerEngineId.isEmpty()) startGameViewerAnalysis();
    }
    private void renderGameViewerPly() {
        if(gameViewerPositions.isEmpty()) { gameViewerPositions.add(new Position()); gameViewerPly=0; }
        gameViewerBoard.setPosition(new Position(gameViewerPositions.get(gameViewerPly)));
        String[] names=extractPgnNames(gameViewerPgn);
        String resultTag = extractPgnResult(gameViewerPgn);
        gvWhiteName.setText(appendResultScore(names[0], resultScore(resultTag, true)));
        gvBlackName.setText(appendResultScore(names[1], resultScore(resultTag, false)));
        String whiteComment="", blackComment="";
        String whiteClock="", blackClock="", whiteUsed="", blackUsed="";
        for(int ply=1; ply<=gameViewerPly && ply<=gameViewerComments.size(); ply++) {
            String c=gameViewerComments.get(ply-1);
            if((ply & 1)==1) {
                if(!c.isEmpty()) whiteComment=c;
                String clock=findCommentValue(c,"WhiteClock|WClock|ClockW|Clock");
                if (clock.isEmpty()) clock = findPgnClockAnnotation(c);
                String used=findCommentValue(c,"WhiteTime|WTime|TimeWhite|T|Time|TimeUsed");
                if(!clock.isEmpty()) whiteClock=clock; if(!used.isEmpty()) whiteUsed=used;
            } else {
                if(!c.isEmpty()) blackComment=c;
                String clock=findCommentValue(c,"BlackClock|BClock|ClockB|Clock");
                if (clock.isEmpty()) clock = findPgnClockAnnotation(c);
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
        for (int i = 0; i < gameViewerMoves.size() && i < gameViewerPly; i++) {
            if ((i & 1) == 0) {
                if (moves.length() > 0) moves.append("  ");
                moves.append((i / 2) + 1).append('.');
            } else {
                moves.append("  ");
            }
            moves.append(gameViewerMoves.get(i));
        }
        gvMoves.setText(moves.length()==0?"No moves yet.":moves.toString()); gvStatus.setText("Ply "+gameViewerPly+" / "+(gameViewerPositions.size()-1));
    }
    private String findPgnClockAnnotation(String comment) {
        if (comment == null) return "";
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\[%clk\\s+([^\\]]+)\\]", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(comment);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String analysisFromComment(String c) {
        if (c == null || c.trim().isEmpty() || c.trim().equalsIgnoreCase("Book.")) return "";
        String eval = findCommentValue(c, "Eval|eval");
        String depth = findCommentValue(c, "Depth|D");
        String selDepth = findCommentValue(c, "SelDepth|SDepth|SD");
        if (depth.indexOf('/') >= 0) {
            String[] parts = depth.split("/", 2);
            depth = parts[0];
            if (selDepth.isEmpty()) selDepth = parts[1];
        }
        String nodes = findCommentValue(c, "Nodes|N");
        String nps = findCommentValue(c, "NPS|nps");
        if (nps.toLowerCase(java.util.Locale.US).endsWith("knps"))
            nps = nps.substring(0, nps.length() - 4) + "K";
        else if (nps.toLowerCase(java.util.Locale.US).endsWith("mnps"))
            nps = nps.substring(0, nps.length() - 4) + "M";
        String engineTime = findCommentValue(c, "EngineTime");
        String moveTime = findCommentValue(c, "MoveTime|Time|TimeUsed");
        String clock = findCommentValue(c, "Clock|clk");
        String depthDisplay = dash(depth);
        if (depthDisplay.indexOf('/') < 0 && !selDepth.isEmpty()) depthDisplay += "/" + selDepth;
        StringBuilder result = new StringBuilder();
        result.append("Eval: ").append(dash(eval)).append("\nDepth: ").append(depthDisplay)
                .append("\nNPS: ").append(dash(nps)).append("\nNodes: ").append(dash(nodes));
        if (!clock.isEmpty()) result.append("\nClock: ").append(clock);
        if (!moveTime.isEmpty() || !engineTime.isEmpty())
            result.append("\nTime: ").append(dash(!moveTime.isEmpty() ? moveTime : engineTime));
        java.util.regex.Matcher w = java.util.regex.Pattern.compile(
                "(?i)EngineWDL:\\s*W:\\s*([0-9]+(?:\\.[0-9]+)?)%\\s+D:\\s*([0-9]+(?:\\.[0-9]+)?)%\\s+L:\\s*([0-9]+(?:\\.[0-9]+)?)%")
                .matcher(c);
        if (w.find()) result.append("\nEngine WDL: W ").append(w.group(1)).append("%  D ")
                .append(w.group(2)).append("%  L ").append(w.group(3)).append('%');
        String pv = extractSavedCommentPv(c);
        if (!pv.isEmpty()) result.append("\nPV: ").append(pv);
        return result.toString();
    }

    private String extractSavedCommentPv(String comment) {
        if (comment == null || comment.trim().isEmpty()) return "";
        java.util.regex.Matcher old = java.util.regex.Pattern.compile(
                "(?i)\\bPV:\\s*(.*?)(?=\\s+(?:EngineWDL|WhiteWDL):|$)").matcher(comment);
        if (old.find()) return firstTwoSanPlies(old.group(1));
        java.util.regex.Matcher compact = java.util.regex.Pattern.compile(
                "(?i)\\bt\\S+\\s+(.+?)(?=\\s+(?:EngineWDL|WhiteWDL):|$)").matcher(comment);
        return compact.find() ? firstTwoSanPlies(compact.group(1)) : "";
    }

    private String firstTwoSanPlies(String value) {
        if (value == null) return "";
        value = value.trim().replaceFirst("(?i)^eval\\s+[+-]?(?:M\\d+|\\d+(?:\\.\\d+)?)\\s*", "");
        StringBuilder out = new StringBuilder();
        int count = 0;
        String pendingMoveNumber = "";
        for (String token : value.split("\\s+")) {
            if (token.isEmpty()) continue;
            if (token.matches("\\d+\\.{1,3}")) { pendingMoveNumber = token; continue; }
            if (token.matches("(?i)(EngineWDL|WhiteWDL):.*")) break;
            java.util.regex.Matcher numbered = java.util.regex.Pattern.compile("^(\\d+\\.{1,3})(.+)$").matcher(token);
            String number = pendingMoveNumber;
            String san = token;
            pendingMoveNumber = "";
            if (numbered.matches()) { number = numbered.group(1); san = numbered.group(2); }
            if (out.length() > 0) out.append(' ');
            if (!number.isEmpty()) out.append(number);
            out.append(san);
            if (++count == 2) break;
        }
        return out.toString();
    }
    private String extractPgnResult(String pgn) {
        if (pgn == null) return "*";
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?m)^\\[Result\\s+\"([^\"]*)\"\\]").matcher(pgn);
        return matcher.find() ? matcher.group(1).trim() : "*";
    }

    private String resultScore(String result, boolean white) {
        if ("1-0".equals(result)) return white ? "1" : "0";
        if ("0-1".equals(result)) return white ? "0" : "1";
        if ("1/2-1/2".equals(result) || "½-½".equals(result)) return "½";
        return "";
    }

    private CharSequence appendResultScore(String name, String score) {
        String value = name == null ? "" : name;
        if (score == null || score.isEmpty()) return value;
        String display = value + "  " + score;
        if (!"1".equals(score) && !"0".equals(score)) return display;
        android.text.SpannableString styled = new android.text.SpannableString(display);
        int color = "1".equals(score) ? 0xFF36C878 : 0xFFFF5B5B;
        styled.setSpan(new android.text.style.ForegroundColorSpan(color), display.length() - score.length(),
                display.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        styled.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), display.length() - score.length(),
                display.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return styled;
    }

    private int scheduleOutcomeColor(TournamentManager.Pairing pairing, boolean white) {
        if (pairing == null) return 0xFFE6E6E6;
        if (pairing.outcome == TournamentManager.Outcome.DRAW ||
                pairing.outcome == TournamentManager.Outcome.UNFINISHED) return 0xFFE6E6E6;
        boolean winner = white ? pairing.outcome == TournamentManager.Outcome.WHITE_WIN
                : pairing.outcome == TournamentManager.Outcome.BLACK_WIN;
        return winner ? 0xFF36C878 : 0xFFFF5B5B;
    }

    private CharSequence scheduleEngineName(String name, TournamentManager.Pairing pairing, boolean white) {
        String value = name == null ? "" : name;
        int color = scheduleOutcomeColor(pairing, white);
        if (color == 0xFFE6E6E6 || value.isEmpty()) return value;
        android.text.SpannableString styled = new android.text.SpannableString(value);
        styled.setSpan(new android.text.style.ForegroundColorSpan(color), 0, value.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        styled.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, value.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return styled;
    }

    private void animateGameStep(ChessBoardPlay board, ArrayList<Position> positions,
                                 int oldPly, int targetPly) {
        if (board == null || positions == null ||
                Math.abs(targetPly - oldPly) != 1 || oldPly < 0 || targetPly < 0 ||
                oldPly >= positions.size() || targetPly >= positions.size()) return;
        int durationMs = moveAnimationDurationMs();
        if (durationMs <= 0) return;
        if (targetPly > oldPly) {
            Move move = findMoveBetweenPositions(positions.get(oldPly), positions.get(targetPly));
            if (move != null) board.setAnimMove(positions.get(oldPly), move, true, durationMs);
        } else {
            Move move = findMoveBetweenPositions(positions.get(targetPly), positions.get(oldPly));
            if (move != null) board.setAnimMove(positions.get(targetPly), move, false, durationMs);
        }
    }

    private String[] extractPgnNames(String pgn) {
        String white="White", black="Black"; java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?m)^\\[White\\s+\"([^\"]*)\"\\]").matcher(pgn); if(m.find())white=m.group(1);
        m=java.util.regex.Pattern.compile("(?m)^\\[Black\\s+\"([^\"]*)\"\\]").matcher(pgn); if(m.find())black=m.group(1); return new String[]{white,black};
    }
    private String findAnalysis(int ply, boolean white) {
        if (ply <= 0) return "---  Depth: ---  Nodes: ---  NPS: ---";
        String c = ply - 1 < gameViewerComments.size() ? gameViewerComments.get(ply - 1) : "";
        String eval = findCommentValue(c, "Eval|eval");
        String depth = findCommentValue(c, "D|Depth");
        String selDepth = findCommentValue(c, "SelDepth|SDepth|SD");
        String nodes = findCommentValue(c, "N|Nodes");
        String nps = findCommentValue(c, "NPS|nps");
        String wdl = "";
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(?i)EngineWDL:\\s*W:\\s*([0-9]+(?:\\.[0-9]+)?)%\\s+D:\\s*([0-9]+(?:\\.[0-9]+)?)%\\s+L:\\s*([0-9]+(?:\\.[0-9]+)?)%")
                .matcher(c);
        if (matcher.find()) wdl = "  Engine WDL: " + matcher.group(1) + "/" + matcher.group(2) + "/" + matcher.group(3);
        String depthDisplay = dash(depth);
        if (depthDisplay.indexOf('/') < 0 && selDepth != null && !selDepth.isEmpty())
            depthDisplay += "/" + dash(selDepth);
        return dash(eval) + "  Depth: " + depthDisplay +
                "  Nodes: " + dash(nodes) + "  NPS: " + dash(nps) + wdl;
    }
    private String dash(String v) { return v==null||v.isEmpty()?"---":v; }
    private String findCommentValue(String comment,String keys) {
        if (comment == null) return "";
        String lowerKeys = keys.toLowerCase(java.util.Locale.US);
        String[] aliases = keys.split("\\|");
        // Prefer explicit legacy labels (Depth:, SelDepth:) before compact tokens.
        // The one-letter D alias is intentionally skipped for depth because it also
        // occurs in the EngineWDL draw field (D:xx%).
        for (String key : aliases) {
            if (key.equalsIgnoreCase("D") && lowerKeys.contains("depth")) continue;
            java.util.regex.Matcher labelled = java.util.regex.Pattern.compile(
                    "(?i)(?:^|[\\s,;])" + java.util.regex.Pattern.quote(key) + "\\s*[:=]\\s*([^\\s,;]+)").matcher(comment);
            if (labelled.find()) return labelled.group(1);
        }
        java.util.regex.Matcher m;
        if (lowerKeys.contains("depth") && !lowerKeys.contains("seldepth")) {
            m = java.util.regex.Pattern.compile("(?i)(?:^|\\s)d(\\d+(?:/\\d+)?)(?=\\s+n[0-9])").matcher(comment);
            if (m.find()) return m.group(1);
        }
        if (lowerKeys.contains("seldepth")) {
            m = java.util.regex.Pattern.compile("(?i)(?:^|\\s)d\\d+/(\\d+)(?=\\s+n[0-9])").matcher(comment);
            if (m.find()) return m.group(1);
        }
        if (lowerKeys.contains("eval")) {
            m = java.util.regex.Pattern.compile("(?i)(?:^|\\s)([+-]?(?:M\\d+|\\d+(?:\\.\\d+)?))(?=\\s|$)").matcher(comment);
            if (m.find()) return m.group(1);
        }
        if (lowerKeys.contains("nodes")) {
            m = java.util.regex.Pattern.compile("(?i)(?:^|\\s)n([0-9]+(?:\\.[0-9]+)?[kmb]?)").matcher(comment);
            if (m.find()) return m.group(1);
        }
        if (lowerKeys.contains("nps")) {
            m = java.util.regex.Pattern.compile("(?i)(?:^|\\s)([0-9]+(?:\\.[0-9]+)?(?:k|m)?nps)").matcher(comment);
            if (m.find()) return m.group(1);
        }
        if (lowerKeys.contains("clock") || lowerKeys.contains("clk")) {
            m = java.util.regex.Pattern.compile("(?i)(?:^|\\s)clk([^\\s]+)").matcher(comment);
            if (m.find()) return m.group(1);
        }
        if (lowerKeys.contains("enginet")) {
            // Compact PGN snapshots store engine search duration as etXs.
            m = java.util.regex.Pattern.compile("(?i)(?:^|\\s)et([0-9]+(?:\\.[0-9]+)?)(?:s)?(?=\\s|$)").matcher(comment);
            if (m.find()) return m.group(1) + "s";
            // Do not confuse legacy tX move-elapsed time with engine search time.
            return "";
        }
        if (lowerKeys.contains("time") || lowerKeys.contains("timeused")) {
            m = java.util.regex.Pattern.compile("(?i)(?:^|\\s)t([0-9]+(?::[0-9]{2})?(?:\\.[0-9]+)?)").matcher(comment);
            if (m.find()) return m.group(1);
        }
        return "";
    }
    private void selectGameViewerAnalysisEngine() {
        if (availableEngines.isEmpty()) loadEngineChoices();
        CharSequence[] labels = new CharSequence[availableEngines.size()];
        for (int i = 0; i < labels.length; i++) {
            EngineChoice choice = availableEngines.get(i);
            labels[i] = engineLabelWithIcon(choice.id, choice.name, choice.name, 24);
        }
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
            @Override public void notifyEngineName(String name) { runOnUiThread(()->gvBlackName.setText(appendResultScore(name, resultScore(extractPgnResult(gameViewerPgn), false)))); }
            @Override public void reportEngineError(String errMsg) { runOnUiThread(()->Toast.makeText(TournamentActivity.this,"Analysis engine: "+errMsg,Toast.LENGTH_LONG).show()); }
            @Override public void notifyEngineInitialized() { }
            @Override public void notifySearchResult(int id,String cmd,Move ponder) { }
            @Override public void notifyPV(int id,Position pos,ArrayList<PvInfo> infos,Move ponderMove) {
                if (infos==null || infos.isEmpty() || infos.get(0)==null) return; PvInfo pv=infos.get(0);
                int whitePerspectiveScore = (pos != null && pos.whiteMove) ? pv.score : -pv.score;
                String eval=pv.isMate?((whitePerspectiveScore>=0?"+M":"-M")+Math.abs(whitePerspectiveScore)) : String.format(java.util.Locale.US,"%+.2f",whitePerspectiveScore/100.0);
                StringBuilder variation=new StringBuilder(); Position cursor=new Position(pos); ArrayList<Move> hints=new ArrayList<>();
                if(pv.pv!=null) for(Move m:pv.pv) { if(hints.size()<2) hints.add(m); String san=TextIO.moveToString(cursor,m,false,false); if(variation.length()>0)variation.append(' '); if(cursor.whiteMove)variation.append(cursor.fullMoveCounter).append('.'); variation.append(san); cursor.makeMove(m,new org.petero.droidfish.gamelogic.UndoInfo()); }
                String engineWdl = pv.hasWdl ? "\nEngine WDL: W " + String.format(java.util.Locale.US, "%.1f", pv.wdlWin / 10.0) +
                        "%  D " + String.format(java.util.Locale.US, "%.1f", pv.wdlDraw / 10.0) +
                        "%  L " + String.format(java.util.Locale.US, "%.1f", pv.wdlLoss / 10.0) + "%" : "";
                String output="Eval: "+eval+"    Depth: "+pv.depth+"/"+pv.seldepth+
                        "    NPS: "+compactViewerCount(pv.nps)+"    Nodes: "+compactViewerCount(pv.nodes)+
                        engineWdl+"\n"+variation;
                runOnUiThread(()->{ if(!gameViewerAnalysisOn)return; gvWhiteAnalysis.setText(formatEngineOutput(output)); gvBlackAnalysis.setText("Black ---  Depth: ---  Nodes: ---  NPS: ---"); gameViewerHints.clear(); gameViewerHints.addAll(hints); gameViewerBoard.setMoveHints(gameViewerHints); });
            }
        });
        gameViewerEngine.setEngineOptions(new org.petero.droidfish.EngineOptions()); gameViewerEngine.setBookOptions(new BookOptions());
        int id=++gameViewerSearchId; gameViewerEngine.queueStartEngine(id,gameViewerEngineId);
        Position pos=new Position(gameViewerPositions.get(gameViewerPly));
        uiHandler.postDelayed(()->{ if(gameViewerEngine==null||!gameViewerAnalysisOn)return; try { gameViewerEngine.queueAnalyzeRequest(DroidComputerPlayer.SearchRequest.analyzeRequest(++gameViewerSearchId,pos,new ArrayList<Move>(),pos,false,gameViewerEngineId,1)); } catch(RuntimeException ex) { Toast.makeText(this,"Unable to start analysis: "+ex.getMessage(),Toast.LENGTH_LONG).show(); } },700);
    }
    private void stopGameViewerEngineOnly() { if(gameViewerEngine!=null) { try { gameViewerEngine.stopSearch(); gameViewerEngine.shutdownEngine(); } catch(RuntimeException ignored){} gameViewerEngine=null; } }
    private String compactViewerCount(long value) { if(value>=1_000_000)return String.format(java.util.Locale.US,"%.1fM",value/1_000_000.0); if(value>=1_000)return String.format(java.util.Locale.US,"%.0fK",value/1_000.0); return Long.toString(value); }
    private String compactMirrorShortcutCount(long value) {
        if (value >= 1_000_000L) return String.format(java.util.Locale.US, "%.1fm", value / 1_000_000.0);
        if (value >= 1_000L) return String.format(java.util.Locale.US, "%.0fk", value / 1_000.0);
        return Long.toString(value);
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
                        boardSelectionActive = false;
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

    private void showTournamentMoveAnimationDialog() {
        final int[] durations = {0, 80, 320, 450, 800};
        final String[] labels = {"Off (lightest)", "Fast (80 ms)", "Normal (320 ms)", "Slow (450 ms)", "Very slow (800 ms)"};
        android.content.SharedPreferences preferences = getPreferences(MODE_PRIVATE);
        int current = moveAnimationDurationMs();
        int selected = 2;
        for (int i = 0; i < durations.length; i++) if (durations[i] == current) { selected = i; break; }
        new AlertDialog.Builder(this).setTitle("Piece move animation")
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    preferences.edit().putInt("tournamentMoveAnimationMs", durations[which]).apply();
                    dialog.dismiss();
                    Toast.makeText(this, "Move animation: " + labels[which], Toast.LENGTH_SHORT).show();
                }).setNegativeButton("CLOSE", null).show();
    }

    private void showRatingsDialog() {
        TournamentManager tournament = activeTournamentManager;
        if (tournament == null) {
            Toast.makeText(this, "No tournament is loaded", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!tournament.ratingEnabled()) {
            new AlertDialog.Builder(this).setTitle("Ratings")
                    .setMessage("Elo rating was not enabled when this tournament was created. Existing tournaments are not modified retroactively. Enable rating when creating the next tournament.")
                    .setPositiveButton("CLOSE", null).show();
            return;
        }
        Map<String, Double> ratings = tournament.ratings();
        ArrayList<TournamentManager.Standing> ranked = new ArrayList<>(tournament.standings());
        Collections.sort(ranked, (a, b) -> {
            double ar = ratings.containsKey(a.engineId) ? ratings.get(a.engineId) : 0.0;
            double br = ratings.containsKey(b.engineId) ? ratings.get(b.engineId) : 0.0;
            int compare = Double.compare(br, ar);
            return compare != 0 ? compare : a.engineId.compareToIgnoreCase(b.engineId);
        });
        StringBuilder out = new StringBuilder("Ratings\n");
        for (int i = 0; i < ranked.size(); i++) {
            TournamentManager.Standing standing = ranked.get(i);
            double rating = ratings.containsKey(standing.engineId) ? ratings.get(standing.engineId) : 3000.0;
            out.append(i + 1).append(".").append(engineDisplayName(tournament.engines(), standing.engineId)).append("\n   (")
                    .append(formatElo(rating, tournament.ratingShowDecimals())).append(")  +")
                    .append(standing.wins).append(" =").append(standing.draws).append(" -").append(standing.losses);
            if (i + 1 < ranked.size()) out.append("\n");
        }
        String ratingsText = out.toString();
        new AlertDialog.Builder(this).setTitle("Ratings · Elo")
                .setMessage(ratingsText)
                .setNegativeButton("COPY", (dialog, which) -> {
                    ClipboardManager clipboard = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                    if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Tournament ratings", ratingsText));
                    Toast.makeText(this, "Ratings copied", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton(tournament.ratingShowDecimals() ? "SHOW WHOLE" : "SHOW DECIMAL", (dialog, which) -> {
                    try {
                        tournament.setRatingShowDecimals(!tournament.ratingShowDecimals());
                        invalidateMirrorStandingsSubCache(0);
                        if (isMirrorShowing() && mirrorSelectedTab == 1) renderMirrorSection();
                        showRatingsDialog();
                    } catch (Exception error) {
                        Toast.makeText(this, "Could not change rating display: " + error.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setPositiveButton("CLOSE", null).show();
    }

    /** Standings are derived from game results; correct a game result to update both records consistently. */
    private void showStandingsResultPicker(TournamentManager tournament) {
        List<TournamentManager.Pairing> pairings = tournament.pairings();
        ArrayList<Integer> completed = new ArrayList<>();
        ArrayList<CharSequence> labels = new ArrayList<>();
        List<TournamentManager.Engine> engines = tournament.engines();
        for (TournamentManager.Pairing pairing : pairings) if (pairing.isComplete()) {
            completed.add(pairing.index);
            TournamentManager.Engine white = findTournamentEngine(engines, pairing.whiteEngineId);
            TournamentManager.Engine black = findTournamentEngine(engines, pairing.blackEngineId);
            labels.add(android.text.TextUtils.concat("Game " + (pairing.index + 1) + ": ",
                    engineLabelWithIcon(white.id, white.displayName, white.displayName, 20), " - ",
                    engineLabelWithIcon(black.id, black.displayName, black.displayName, 20),
                    " (" + pairingResultLabel(pairing.outcome) + ")"));
        }
        if (completed.isEmpty()) {
            Toast.makeText(this, "No completed game results to edit", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("Choose result to correct")
                .setItems(labels.toArray(new CharSequence[0]), (dialog, which) ->
                        showPairingResultEditor(tournament, completed.get(which)))
                .setNegativeButton("CANCEL", null).show();
    }

    private void showPairingResultEditor(TournamentManager tournament, int pairingIndex) {
        if (blockSavedTournamentReviewEdit()) return;
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
                                    invalidateMirrorSectionCache(1); invalidateMirrorSectionCache(2); invalidateMirrorSectionCache(3);
                                    if (isMirrorShowing() && !mirrorReplayMode && (mirrorSelectedTab == 1 || mirrorSelectedTab == 2 || mirrorSelectedTab == 3)) renderMirrorSection();
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
        if (runner != null) {
            try { currentPgnMoves = runner.currentPgn(); }
            catch (RuntimeException ignored) { }
        }
        String moves = currentPgnMoves == null || currentPgnMoves.isEmpty() ? "No moves played yet." : currentPgnMoves;
        TextView body = text(moves); body.setTextIsSelectable(true); body.setTextSize(16); body.setPadding(dp(12), dp(8), dp(12), dp(8));
        ScrollView sc = new ScrollView(this); sc.addView(body);
        new AlertDialog.Builder(this).setTitle("Current game moves").setView(sc)
                .setPositiveButton("TOURNAMENT", (d, w) -> { currentTournamentScreen = 0; boardSelectionActive = true; saveBoardSelectionState(0); setContentView(playView); }).setNegativeButton("CLOSE", null).show();
    }

    private void showScheduleDialog() {
        try {
            if (activeTournamentManager == null) activeTournamentManager = TournamentManager.restore(this);
            TournamentManager tournament = activeTournamentManager;
            if (cachedScheduleDialog != null && cachedScheduleTournament == tournament) {
                if (cachedScheduleDirty) refreshCachedSchedule(tournament);
                cachedScheduleDialog.show();
                // Recheck after show: the background move-count preparation may
                // have completed between the first refresh and displaying dialog.
                if (cachedScheduleDirty && scheduleMoveCountsReady) refreshCachedSchedule(tournament);
                scrollCachedScheduleToCurrentGame();
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
                    int pairingIndex = position < cachedSchedulePairingIndexes.size()
                            ? cachedSchedulePairingIndexes.get(position) : -1;
                    TournamentManager.Pairing pairing = cachedSchedulePairingsByIndex.get(pairingIndex);
                    String rowText = getItem(position);
                    int[] nameRanges = cachedScheduleNameRangesByIndex.get(pairingIndex);
                    if (pairing != null && rowText != null && nameRanges != null && nameRanges.length == 4 &&
                            nameRanges[0] >= 0 && nameRanges[1] <= rowText.length() &&
                            nameRanges[2] >= nameRanges[1] && nameRanges[3] <= rowText.length()) {
                        android.text.SpannableString styled = new android.text.SpannableString(rowText);
                        int whiteColor = scheduleOutcomeColor(pairing, true);
                        int blackColor = scheduleOutcomeColor(pairing, false);
                        if (whiteColor != 0xFFE6E6E6) styled.setSpan(new android.text.style.ForegroundColorSpan(whiteColor),
                                nameRanges[0], nameRanges[1], android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        if (blackColor != 0xFFE6E6E6) styled.setSpan(new android.text.style.ForegroundColorSpan(blackColor),
                                nameRanges[2], nameRanges[3], android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        row.setText(styled);
                    }
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
            // Build immediately if the worker completed during dialog creation.
            if (cachedScheduleDirty && scheduleMoveCountsReady) refreshCachedSchedule(tournament);
            scrollCachedScheduleToCurrentGame();
        } catch (Exception e) {
            Toast.makeText(this, "Could not load tournament schedule: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void scrollCachedScheduleToCurrentGame() {
        if (cachedScheduleList == null || cachedSchedulePairingIndexes.isEmpty() || currentGameNumber <= 0) return;
        TournamentManager.Pairing activePairing = cachedScheduleTournament == null ? null : cachedScheduleTournament.current();
        final int currentPairingIndex = activePairing != null ? activePairing.index : currentGameNumber - 1;
        for (int row = 0; row < cachedSchedulePairingIndexes.size(); row++) {
            if (cachedSchedulePairingIndexes.get(row) == currentPairingIndex) {
                final int target = row;
                cachedScheduleList.post(() -> {
                    if (cachedScheduleList != null && cachedScheduleList.getAdapter() != null && target < cachedScheduleList.getAdapter().getCount())
                        cachedScheduleList.setSelection(target);
                });
                return;
            }
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
        // Start background move-count preparation, but don't make the user wait
        // for every historical PGN before the schedule itself becomes visible.
        boolean moveCountsReady = ensureMirrorScheduleMoveCountsReady(tournament);
        StringBuilder copy = new StringBuilder(Math.max(256, tournament.pairings().size() * 64));
        cachedScheduleRows.clear();
        cachedSchedulePairingIndexes.clear();
        cachedSchedulePairingsByIndex.clear();
        cachedScheduleNameRangesByIndex.clear();
        List<TournamentManager.Engine> engines = tournament.engines();
        List<Integer> priorityQueue = tournament.playQueue();
        HashMap<Integer, Integer> priorityPositions = new HashMap<>();
        for (int i = 0; i < priorityQueue.size(); i++) priorityPositions.put(priorityQueue.get(i), i + 1);
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
                cachedSchedulePairingIndexes.add(-1);
                if (copy.length() > 0) copy.append("\n\n");
                copy.append(heading);
            }
            if (cup && pairing.round - 1 != displayedCupStage) {
                if (displayedCupStage >= 0) appendCupByeRows(tournament, engines, displayedCupStage, copy);
                displayedCupStage = pairing.round - 1;
                displayedCupMatch = -1;
                String heading = CupTournamentManager.stageLabel(tournament.cupBracketSize(), displayedCupStage).toUpperCase(java.util.Locale.US);
                cachedScheduleRows.add("========== " + heading + " ==========");
                cachedSchedulePairingIndexes.add(-1);
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
                cachedSchedulePairingIndexes.add(-1);
                if (copy.length() > 0) copy.append("\n\n");
                copy.append(matchHeading);
            }
            String white = pairing.isComplete() && !pairing.whiteNameAtPlay.isEmpty()
                    ? pairing.whiteNameAtPlay : engineDisplayName(engines, pairing.whiteEngineId);
            String black = pairing.isComplete() && !pairing.blackNameAtPlay.isEmpty()
                    ? pairing.blackNameAtPlay : engineDisplayName(engines, pairing.blackEngineId);
            String result = pairingResultLabel(pairing.outcome);
            int endedAtMoves = cachedFullMoveCount(tournament, pairing);
            Integer queuedPosition = priorityPositions.get(pairing.index);
            int queuePosition = queuedPosition == null ? -1 : queuedPosition;
            String state = pairing.replayPending ? " · replay queued" : (queuePosition > 0 ? " · priority #" + queuePosition : "");
            String gamePrefix = swiss ? "R" + pairing.round + "." + pairing.gameNumber
                    : (cup ? "Pair " + pairing.cupPairNumber + " · Game " + pairing.cupGameInPair
                    : Integer.toString(pairing.index + 1));
            String moveCountLabel = endedAtMoves < 0 ? "Counting moves…" : endedAtMoves + " moves";
            String row = gamePrefix + ". " + white + " - " + black + " (" + result + ")" + state
                    + "\n" + moveCountLabel;
            cachedScheduleRows.add(row);
            cachedSchedulePairingIndexes.add(pairing.index);
            cachedSchedulePairingsByIndex.put(pairing.index, pairing);
            int whiteNameStart = gamePrefix.length() + 2;
            int whiteNameEnd = whiteNameStart + white.length();
            int blackNameStart = whiteNameEnd + 3;
            int blackNameEnd = blackNameStart + black.length();
            cachedScheduleNameRangesByIndex.put(pairing.index,
                    new int[]{whiteNameStart, whiteNameEnd, blackNameStart, blackNameEnd});
            if (copy.length() > 0) copy.append("\n\n");
            copy.append(row);
        }
        if (cup && displayedCupStage >= 0) appendCupByeRows(tournament, engines, displayedCupStage, copy);
        if (swiss && displayedSwissRound > 0) appendSwissByeRows(tournament, engines, displayedSwissRound, copy);
        if (cachedScheduleRows.isEmpty()) {
            cachedScheduleRows.add("No scheduled games.");
            cachedSchedulePairingIndexes.add(-1);
            copy.append("No scheduled games.");
        }
        cachedScheduleContents = copy.toString();
        final int cachedFirstPosition = cachedScheduleList == null ? 0 : cachedScheduleList.getFirstVisiblePosition();
        View cachedFirstChild = cachedScheduleList == null ? null : cachedScheduleList.getChildAt(0);
        final int cachedFirstTop = cachedFirstChild == null ? 0 : cachedFirstChild.getTop();
        cachedScheduleAdapter.notifyDataSetChanged();
        if (cachedScheduleList != null) cachedScheduleList.post(() -> {
            if (cachedFirstPosition > 0 || cachedFirstTop != 0) cachedScheduleList.setSelectionFromTop(cachedFirstPosition, cachedFirstTop);
            else scrollCachedScheduleToCurrentGame();
        });
        if (cachedScheduleDialog != null) cachedScheduleDialog.setTitle(scheduleTitle(tournament));
        if (cachedScheduleBracketButton != null)
            cachedScheduleBracketButton.setVisibility(tournament.format() == TournamentManager.Format.CUP ? View.VISIBLE : View.GONE);
        cachedScheduleDirty = !moveCountsReady;
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
            cachedSchedulePairingIndexes.add(-1);
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
            cachedSchedulePairingIndexes.add(-1);
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
        if (tournament != null && scheduleMoveCountCacheOwner == tournament) {
            scheduleMoveCountsReady = false;
            scheduleMoveCountsBuilding = false;
            scheduleMoveCountBuildGeneration++;
            invalidateMirrorSectionCache(2);
        }
        if (cachedScheduleTournament == tournament) {
            cachedScheduleDirty = true;
            if (cachedScheduleDialog != null && cachedScheduleDialog.isShowing()) {
                if (cachedScheduleRefreshRunnable != null) uiHandler.removeCallbacks(cachedScheduleRefreshRunnable);
                cachedScheduleRefreshRunnable = () -> {
                    cachedScheduleRefreshRunnable = null;
                    if (cachedScheduleDialog != null && cachedScheduleDialog.isShowing() &&
                            cachedScheduleTournament == tournament && cachedScheduleDirty)
                        refreshCachedSchedule(tournament);
                };
                uiHandler.postDelayed(cachedScheduleRefreshRunnable, 900L);
            }
        }
    }

    /** Mirror-board entry point: use the same queue editor but return to the Mirror Schedule. */
    private void showMirrorPlayQueueEditor(TournamentManager tournament) {
        if (blockSavedTournamentReviewEdit()) return;
        showPlayQueueEditor(tournament, true);
    }

    /** Main-board entry point retains its existing return-to-main-schedule behavior. */
    private void showPlayQueueEditor(TournamentManager tournament) {
        showPlayQueueEditor(tournament, false);
    }

    /** Multi-select unfinished scheduled games in the operator's chosen execution order. */
    private void showPlayQueueEditor(TournamentManager tournament, boolean returnToMirrorSchedule) {
        ArrayList<Integer> gameIndices = new ArrayList<>();
        ArrayList<CharSequence> labels = new ArrayList<>();
        List<TournamentManager.Engine> engines = tournament.engines();
        List<TournamentManager.Pairing> pairings = tournament.pairings();
        for (TournamentManager.Pairing pairing : pairings) {
            if (pairing.isComplete() || pairing.replayPending || pairing.index == tournament.activePairingIndex()) continue;
            TournamentManager.Engine white = findTournamentEngine(engines, pairing.whiteEngineId);
            TournamentManager.Engine black = findTournamentEngine(engines, pairing.blackEngineId);
            gameIndices.add(pairing.index);
            labels.add(android.text.TextUtils.concat("Game " + (pairing.index + 1) + ": ",
                    engineLabelWithIcon(white.id, white.displayName, white.displayName, 20), " - ",
                    engineLabelWithIcon(black.id, black.displayName, black.displayName, 20)));
        }
        if (gameIndices.isEmpty()) {
            Toast.makeText(this, "No unfinished games available for the play queue", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean[] checked = new boolean[gameIndices.size()];
        for (int i = 0; i < gameIndices.size(); i++) checked[i] = tournament.isQueued(gameIndices.get(i));
        new AlertDialog.Builder(this).setTitle("Play queue · Select games")
                .setMultiChoiceItems(labels.toArray(new CharSequence[0]), checked, (dialog, which, isChecked) -> {
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
                }).setPositiveButton("DONE", (dialog, which) -> {
                    if (returnToMirrorSchedule) refreshMirrorScheduleAfterQueueEdit(tournament);
                    else showScheduleDialog();
                }).setNegativeButton("CANCEL", (dialog, which) -> {
                    if (returnToMirrorSchedule) refreshMirrorScheduleAfterQueueEdit(tournament);
                    else showScheduleDialog();
                }).show();
    }

    private void refreshMirrorScheduleAfterQueueEdit(TournamentManager tournament) {
        invalidateCachedSchedule(tournament);
        invalidateMirrorSectionCache(2);
        if (isMirrorShowing() && mirrorSelectedTab == 2) renderMirrorSection();
    }

    /** Select a completed schedule entry to replay with its persisted original opening. */
    private void showReplayGamePicker(TournamentManager tournament) {
        if (blockSavedTournamentReviewEdit()) return;
        if (tournament.format() == TournamentManager.Format.CUP) {
            Toast.makeText(this, "Cup results are locked to preserve the knockout bracket. A future Cup correction tool can handle controlled match corrections.", Toast.LENGTH_LONG).show();
            return;
        }
        ArrayList<Integer> gameIndices = new ArrayList<>();
        ArrayList<CharSequence> labels = new ArrayList<>();
        List<TournamentManager.Engine> engines = tournament.engines();
        for (TournamentManager.Pairing pairing : tournament.pairings()) {
            if (!pairing.isComplete() || pairing.replayPending) continue;
            TournamentManager.Engine white = findTournamentEngine(engines, pairing.whiteEngineId);
            TournamentManager.Engine black = findTournamentEngine(engines, pairing.blackEngineId);
            String opening = pairing.openingName == null || pairing.openingName.trim().isEmpty()
                    ? "Starting position" : pairing.openingName;
            gameIndices.add(pairing.index);
            labels.add(android.text.TextUtils.concat("Game " + (pairing.index + 1) + ": ",
                    engineLabelWithIcon(white.id, white.displayName, white.displayName, 20), " - ",
                    engineLabelWithIcon(black.id, black.displayName, black.displayName, 20),
                    " (" + pairingResultLabel(pairing.outcome) + ") · " + opening));
        }
        if (gameIndices.isEmpty()) {
            Toast.makeText(this, "No completed games available to replay", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("Choose a game to replay")
                .setItems(labels.toArray(new CharSequence[0]), (dialog, which) -> {
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
        if (blockSavedTournamentReviewEdit()) return;
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
        if (blockSavedTournamentReviewEdit()) return;
        List<TournamentManager.Engine> engines = tournament.engines();
        if (engines.isEmpty()) {
            Toast.makeText(this, "No tournament engines are available to edit", Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence[] labels = new CharSequence[engines.size()];
        for (int i = 0; i < engines.size(); i++) {
            TournamentManager.Engine engine = engines.get(i);
            String detail = engine.displayName + " · " + tournament.completedGamesForEngine(engine.id) + " completed";
            labels[i] = engineLabelWithIcon(engine.id, engine.displayName, detail, 24);
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
            ArrayList<CharSequence> choiceLabels = new ArrayList<>();
            for (EngineChoice choice : availableEngines) {
                if (choice.id == null || choice.id.trim().isEmpty()) continue;
                choices.add(choice);
                choiceLabels.add(engineLabelWithIcon(choice.id, choice.name,
                        choice.name + "\n" + choice.id, 24));
            }
            if (choices.isEmpty()) {
                Toast.makeText(this, "No installed replacement engines were found", Toast.LENGTH_SHORT).show();
                return;
            }
            new AlertDialog.Builder(this).setTitle("Select replacement executable")
                    .setItems(choiceLabels.toArray(new CharSequence[0]), (d, selected) -> {
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
        if (blockSavedTournamentReviewEdit()) return;
        List<TournamentManager.Pairing> pairings = tournament.pairings();
        int lockedIndex = -1;
        if (runner != null) {
            TournamentManager.Pairing current = tournament.current();
            if (current != null) lockedIndex = current.index;
        }
        ArrayList<Integer> editable = new ArrayList<>();
        ArrayList<CharSequence> labels = new ArrayList<>();
        List<TournamentManager.Engine> engines = tournament.engines();
        for (TournamentManager.Pairing pairing : pairings) {
            TournamentManager.Engine white = findTournamentEngine(engines, pairing.whiteEngineId);
            TournamentManager.Engine black = findTournamentEngine(engines, pairing.blackEngineId);
            String gameLabel = tournament.format() == TournamentManager.Format.SWISS
                    ? "Round " + pairing.round + " · Game " + pairing.gameNumber
                    : "Game " + (pairing.index + 1);
            CharSequence label = android.text.TextUtils.concat(gameLabel + ": ",
                    engineLabelWithIcon(white.id, white.displayName, white.displayName, 20), " - ",
                    engineLabelWithIcon(black.id, black.displayName, black.displayName, 20),
                    " (" + pairingResultLabel(pairing.outcome) + ")");
            if (pairing.replayPending) {
                // The replacement game owns this record until it finishes.
            } else if (pairing.isComplete()) {
                editable.add(pairing.index); labels.add(android.text.TextUtils.concat(label, " · correct result"));
            } else if (runner == null || pairing.index > lockedIndex) {
                editable.add(pairing.index); labels.add(android.text.TextUtils.concat(label, " · edit pairing"));
            }
        }
        if (editable.isEmpty()) {
            Toast.makeText(this, runner == null ? "No games available to edit" : "No future pairings available to edit", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("Choose a scheduled game")
                .setItems(labels.toArray(new CharSequence[0]), (dialog, which) -> {
                    int index = editable.get(which);
                    TournamentManager.Pairing pairing = tournament.pairings().get(index);
                    if (pairing.isComplete()) showPairingResultEditor(tournament, index);
                    else showPairingEnginesEditor(tournament, index);
                }).setNegativeButton("CANCEL", null).show();
    }

    private void showPairingEnginesEditor(TournamentManager tournament, int pairingIndex) {
        TournamentManager.Pairing pairing = tournament.pairings().get(pairingIndex);
        List<TournamentManager.Engine> engines = tournament.engines();
        ArrayList<CharSequence> names = new ArrayList<>();
        int whiteSelection = 0, blackSelection = Math.min(1, engines.size() - 1);
        for (int i = 0; i < engines.size(); i++) {
            TournamentManager.Engine engine = engines.get(i);
            names.add(engineLabelWithIcon(engine.id, engine.displayName, engine.displayName, 24));
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

    private TournamentManager.Engine findTournamentEngine(List<TournamentManager.Engine> engines, String id) {
        for (TournamentManager.Engine engine : engines) if (engine.id.equals(id)) return engine;
        String fallbackName = id == null || id.isEmpty() ? "Unknown engine" : id;
        return new TournamentManager.Engine(fallbackName, fallbackName);
    }

    private String engineDisplayName(List<TournamentManager.Engine> engines, String id) {
        for (TournamentManager.Engine engine : engines) if (engine.id.equals(id)) return engine.displayName;
        return id;
    }

    private static final class TournamentChessBoard extends ChessBoardPlay {
        private Move lastMoveMarker;
        private ArrayList<Move> branchMoveMarkers;
        boolean fillLastMoveUnderPieces;
        private final Paint lastMovePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint branchMovePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        TournamentChessBoard(android.content.Context context) { super(context, null); }
        void setLastMove(Move move) {
            lastMoveMarker = move;
            invalidate();
        }
        void setBranchMoves(ArrayList<Move> moves) {
            branchMoveMarkers = moves == null ? null : new ArrayList<>(moves);
            invalidate();
        }
        @Override protected void drawSquareUnderlay(Canvas canvas, int square, int x, int y) {
            if (!fillLastMoveUnderPieces || lastMoveMarker == null || sqSize <= 0) return;
            if (square != lastMoveMarker.from && square != lastMoveMarker.to) return;
            lastMovePaint.setStyle(Paint.Style.FILL);
            lastMovePaint.setColor(square == lastMoveMarker.to ? 0x88B8F28A : 0x88FFD36A);
            canvas.drawRect(x, y, x + sqSize, y + sqSize, lastMovePaint);
        }

        @Override protected void drawExtraSquares(Canvas canvas) {
            super.drawExtraSquares(canvas);
            if (lastMoveMarker != null && sqSize > 0 && !fillLastMoveUnderPieces) {
                // Retain the legacy outline when under-piece square fill is disabled.
                lastMovePaint.setStyle(Paint.Style.STROKE);
                lastMovePaint.setStrokeWidth(Math.max(2.0f * getResources().getDisplayMetrics().density, sqSize / 18.0f));
                XYCoord from = sqToPix(Position.getX(lastMoveMarker.from), Position.getY(lastMoveMarker.from));
                XYCoord to = sqToPix(Position.getX(lastMoveMarker.to), Position.getY(lastMoveMarker.to));
                float inset = lastMovePaint.getStrokeWidth() / 2.0f;
                lastMovePaint.setColor(0xFF5BC0EB);
                canvas.drawRect(from.x + inset, from.y + inset, from.x + sqSize - inset, from.y + sqSize - inset, lastMovePaint);
                lastMovePaint.setColor(0xFF65D66F);
                canvas.drawRect(to.x + inset, to.y + inset, to.x + sqSize - inset, to.y + sqSize - inset, lastMovePaint);
            }
            if (branchMoveMarkers != null && !branchMoveMarkers.isEmpty()) {
                branchMovePaint.setStyle(Paint.Style.STROKE);
                branchMovePaint.setStrokeCap(Paint.Cap.ROUND);
                branchMovePaint.setStrokeWidth(Math.max(2.5f * getResources().getDisplayMetrics().density, sqSize / 24.0f));
                branchMovePaint.setColor(0xD944E064);
                for (Move branchMove : branchMoveMarkers) {
                    if (branchMove == null) continue;
                    XYCoord a = sqToPix(Position.getX(branchMove.from), Position.getY(branchMove.from));
                    XYCoord b = sqToPix(Position.getX(branchMove.to), Position.getY(branchMove.to));
                    float ax = a.x + sqSize * 0.5f, ay = a.y + sqSize * 0.5f;
                    float bx = b.x + sqSize * 0.5f, by = b.y + sqSize * 0.5f;
                    canvas.drawLine(ax, ay, bx, by, branchMovePaint);
                    float angle = (float)Math.atan2(by - ay, bx - ax);
                    float head = sqSize * 0.18f;
                    canvas.drawLine(bx, by, bx - head * (float)Math.cos(angle - 0.55f), by - head * (float)Math.sin(angle - 0.55f), branchMovePaint);
                    canvas.drawLine(bx, by, bx - head * (float)Math.cos(angle + 0.55f), by - head * (float)Math.sin(angle + 0.55f), branchMovePaint);
                }
            }
        }
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
        final ArrayList<CharSequence> labels = new ArrayList<>();
        final ArrayAdapter<CharSequence> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_single_choice, labels);
        final int[] selectedIndex = {0};
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(12), dp(8), dp(12), dp(8));
        content.addView(text("Move engines into strength order. #1 is strongest. The top eight are distributed across the bracket; remaining engines fill the vacant seed positions."), fullWidth());
        ListView list = new ListView(this); list.setChoiceMode(ListView.CHOICE_MODE_SINGLE); list.setAdapter(adapter);
        content.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(340)));
        LinearLayout controls = new LinearLayout(this); controls.setOrientation(LinearLayout.HORIZONTAL);
        Button up = new Button(this); up.setText("Move up"); Button down = new Button(this); down.setText("Move down");
        controls.addView(up, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        controls.addView(down, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1)); content.addView(controls, fullWidth());
        Runnable refresh = () -> { labels.clear(); for (int i=0;i<working.size();i++) {
                EngineChoice engine = working.get(i);
                labels.add(engineLabelWithIcon(engine.id, engine.name, "#"+(i+1)+"  "+engine.name, 24));
            } adapter.notifyDataSetChanged();
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
        final ArrayList<CharSequence> labels = new ArrayList<>();
        final ArrayAdapter<CharSequence> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_single_choice, labels);
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
            for (int i = 0; i < working.size(); i++) {
                EngineChoice engine = working.get(i);
                labels.add(engineLabelWithIcon(engine.id, engine.name, "#" + (i + 1) + "  " + engine.name, 24));
            }
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
        CharSequence[] labels = new CharSequence[selectedEngines.size()];
        boolean[] checked = new boolean[selectedEngines.size()];
        Set<String> updatedDisabledIds = new HashSet<>(engineIconDisabledIds);
        for (int i = 0; i < selectedEngines.size(); i++) {
            EngineChoice engine = selectedEngines.get(i);
            labels[i] = engineLabelWithIcon(engine.id, engine.name,
                    engine.name + " · " + iconFileNameForEngine(engine.id, engine.name), 24);
            checked[i] = !updatedDisabledIds.contains(normalizeIconKey(engine.id));
        }
        new AlertDialog.Builder(this).setTitle("Engine icons · check to enable")
                .setMultiChoiceItems(labels, checked, (dialog, which, enabled) -> {
                    String id = normalizeIconKey(selectedEngines.get(which).id);
                    if (enabled) updatedDisabledIds.remove(id); else updatedDisabledIds.add(id);
                })
                .setPositiveButton("Apply", (dialog, which) -> { engineIconDisabledIds.clear(); engineIconDisabledIds.addAll(updatedDisabledIds); saveSetup(); engineIconBitmaps.clear(); invalidateAllMirrorSectionCaches(); clearMirrorSubCaches(); if (isMirrorShowing()) { updateMirrorRankedNames(); renderMirrorSection(); } })
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
                    byte[] buffer = new byte[8192]; int n;
                    while ((n = in.read(buffer)) > 0 && out.size() < 8 * 1024 * 1024) out.write(buffer, 0, n);
                    bytes = out.toByteArray();
                }
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true; BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
                int max = Math.max(opts.outWidth, opts.outHeight); opts.inSampleSize = 1;
                // The previous 96px decode was enlarged into 48–76dp ImageViews,
                // which softened otherwise sharp source logos. Decode enough pixels
                // for the largest view while keeping a bounded per-icon cache size.
                while (max / opts.inSampleSize > 512) opts.inSampleSize *= 2;
                opts.inJustDecodeBounds = false;
                opts.inScaled = false;
                Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
                if (decoded != null) {
                    float scale = Math.min(256.0f / decoded.getWidth(), 256.0f / decoded.getHeight());
                    if (scale < 1.0f) {
                        bitmap = Bitmap.createScaledBitmap(decoded, Math.max(1, Math.round(decoded.getWidth() * scale)),
                                Math.max(1, Math.round(decoded.getHeight() * scale)), true);
                    } else {
                        bitmap = decoded;
                    }
                }
            }
        } catch (Exception ignored) { }
        engineIconBitmaps.put(key, bitmap);
        return bitmap;
    }

    private boolean engineIconEnabledFor(String engineId) {
        return engineIconsCheck != null && engineIconsCheck.isChecked() &&
                !engineIconDisabledIds.contains(normalizeIconKey(engineId));
    }

    private Bitmap mirrorEngineIcon(String engineId, String displayName) {
        if (!engineIconEnabledFor(engineId)) return null;
        scanEngineIconFolder();
        Uri uri = null;
        for (String key : engineIconLookupKeys(engineId, displayName)) {
            uri = engineIconUris.get(key);
            if (uri != null) break;
        }
        if (uri == null) uri = engineIconUris.get("default");
        return decodeMirrorIcon(uri);
    }

    /** Build a list label with the configured engine PNG or a visible fallback mark. */
    private CharSequence engineLabelWithIcon(String engineId, String displayName, String label, int iconSizeDp) {
        if (!engineIconEnabledFor(engineId)) return label;
        Bitmap bitmap = mirrorEngineIcon(engineId, displayName);
        if (bitmap == null) return "♟  " + label;
        android.graphics.drawable.BitmapDrawable drawable = new BitmapDrawable(getResources(), bitmap);
        int size = dp(iconSizeDp);
        drawable.setBounds(0, 0, size, size);
        String value = "\uFFFC  " + label;
        android.text.SpannableString result = new android.text.SpannableString(value);
        result.setSpan(new android.text.style.ImageSpan(drawable, android.text.style.ImageSpan.ALIGN_BOTTOM),
                0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return result;
    }

    private void addMirrorEngineIcon(LinearLayout row, String engineId, String displayName, int size) {
        if (!engineIconEnabledFor(engineId)) return;
        Bitmap bitmap = mirrorEngineIcon(engineId, displayName);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(size), dp(size));
        lp.leftMargin = dp(3); lp.rightMargin = dp(4);
        if (bitmap != null) {
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(new BitmapDrawable(getResources(), bitmap));
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            row.addView(icon, lp);
        } else {
            TextView fallback = text("♟");
            fallback.setGravity(android.view.Gravity.CENTER);
            fallback.setTextSize(Math.max(16, size * 0.72f));
            fallback.setTextColor(0xFFBFCBD2);
            row.addView(fallback, lp);
        }
    }

    private void chooseEngines() {
        if (availableEngines.isEmpty()) { Toast.makeText(this, "No engines found", Toast.LENGTH_LONG).show(); return; }
        final ArrayList<EngineChoice> dialogSelection = new ArrayList<>(selectedEngines);
        CharSequence[] labels = new CharSequence[availableEngines.size()];
        for (int i = 0; i < availableEngines.size(); i++) {
            EngineChoice choice = availableEngines.get(i);
            labels[i] = engineLabelWithIcon(choice.id, choice.name, choice.name, 24);
        }
        int selectedFormat = formatSpinner.getSelectedItemPosition();
        String selectionTitle = selectedFormat == TournamentManager.Format.SWISS.ordinal()
                ? "Choose engines for Swiss"
                : (selectedFormat == TournamentManager.Format.CUP.ordinal()
                    ? "Choose Cup entrants (default bracket size is 32)"
                    : (selectedFormat == TournamentManager.Format.GAUNTLET.ordinal()
                        ? "Choose engines; selection order sets the gauntlet engine"
                        : "Choose tournament engines"));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(8), dp(4), dp(8), dp(4));
        TextView count = text(dialogSelection.size() + " engines selected");
        count.setTextSize(14);
        count.setTypeface(null, android.graphics.Typeface.BOLD);
        content.addView(count, fullWidth());
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button selectAll = new Button(this); selectAll.setText("Select all"); selectAll.setAllCaps(false);
        Button deselectAll = new Button(this); deselectAll.setText("Deselect all"); deselectAll.setAllCaps(false);
        Button viewSelected = new Button(this); viewSelected.setText("View selected"); viewSelected.setAllCaps(false);
        actions.addView(selectAll, new LinearLayout.LayoutParams(0, -2, 1));
        actions.addView(deselectAll, new LinearLayout.LayoutParams(0, -2, 1));
        actions.addView(viewSelected, new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(actions, fullWidth());
        ListView list = new ListView(this);
        list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        ArrayAdapter<CharSequence> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_multiple_choice, labels);
        list.setAdapter(adapter);
        for (int i = 0; i < availableEngines.size(); i++) {
            EngineChoice candidate = availableEngines.get(i);
            for (EngineChoice selected : dialogSelection) {
                if (selected.id.equals(candidate.id)) { list.setItemChecked(i, true); break; }
            }
        }
        content.addView(list, new LinearLayout.LayoutParams(-1, dp(400)));

        Runnable refreshCount = () -> count.setText(dialogSelection.size() + " engines selected");
        selectAll.setOnClickListener(v -> {
            dialogSelection.clear();
            dialogSelection.addAll(availableEngines);
            for (int i = 0; i < availableEngines.size(); i++) list.setItemChecked(i, true);
            refreshCount.run();
        });
        deselectAll.setOnClickListener(v -> {
            dialogSelection.clear();
            for (int i = 0; i < availableEngines.size(); i++) list.setItemChecked(i, false);
            refreshCount.run();
        });
        list.setOnItemClickListener((parent, view, position, id) -> {
            EngineChoice choice = availableEngines.get(position);
            if (list.isItemChecked(position)) {
                boolean present = false;
                for (EngineChoice selected : dialogSelection) if (selected.id.equals(choice.id)) { present = true; break; }
                if (!present) dialogSelection.add(choice);
            } else {
                for (int i = dialogSelection.size() - 1; i >= 0; i--)
                    if (dialogSelection.get(i).id.equals(choice.id)) dialogSelection.remove(i);
            }
            refreshCount.run();
        });
        viewSelected.setOnClickListener(v -> {
            ArrayList<EngineChoice> sorted = new ArrayList<>(dialogSelection);
            Collections.sort(sorted, (a, b) -> a.name.compareToIgnoreCase(b.name));
            if (sorted.isEmpty()) {
                Toast.makeText(this, "No engines selected", Toast.LENGTH_SHORT).show();
                return;
            }
            CharSequence[] selectedLabels = new CharSequence[sorted.size()];
            boolean[] removeChecked = new boolean[sorted.size()];
            for (int i = 0; i < sorted.size(); i++) selectedLabels[i] = sorted.get(i).name;
            new AlertDialog.Builder(this).setTitle("Selected engines — alphabetical order")
                    .setMultiChoiceItems(selectedLabels, removeChecked, (dialog, which, checked) -> removeChecked[which] = checked)
                    .setPositiveButton("Remove selected", (dialog, which) -> {
                        Set<String> removeIds = new HashSet<>();
                        for (int i = 0; i < sorted.size(); i++) if (removeChecked[i]) removeIds.add(sorted.get(i).id);
                        for (int i = dialogSelection.size() - 1; i >= 0; i--)
                            if (removeIds.contains(dialogSelection.get(i).id)) dialogSelection.remove(i);
                        for (int i = 0; i < availableEngines.size(); i++) {
                            String idAtRow = availableEngines.get(i).id;
                            boolean checked = false;
                            for (EngineChoice selected : dialogSelection) if (selected.id.equals(idAtRow)) { checked = true; break; }
                            list.setItemChecked(i, checked);
                        }
                        refreshCount.run();
                    }).setNegativeButton("Close", null).show();
        });

        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(selectionTitle).setView(content)
                .setPositiveButton("Apply", null).setNegativeButton(android.R.string.cancel, null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            Set<String> previousIds = new HashSet<>();
            for (EngineChoice e : selectedEngines) previousIds.add(e.id);
            Set<String> updatedIds = new HashSet<>();
            for (EngineChoice e : dialogSelection) updatedIds.add(e.id);
            if (!previousIds.equals(updatedIds)) { swissRankOrderConfirmed = false; cupRankOrderConfirmed = false; }
            syncRatingInitialValues();
            selectedEngines.clear(); selectedEngines.addAll(dialogSelection);
            for (EngineChoice engine : selectedEngines)
                if (!ratingSetupValues.containsKey(engine.id)) ratingSetupValues.put(engine.id, "3000");
            syncSwissRankedEngines(); syncCupRankedEngines();
            updateSelectedText();
            saveSetup();
            dialog.dismiss();
        }));
        dialog.show();
    }

    private void updateSelectedText() {
        StringBuilder b = new StringBuilder();
        for (EngineChoice e : selectedEngines) { if (b.length() > 0) b.append(", "); b.append(e.name); }
        if (selectedCountText != null) selectedCountText.setText(selectedEngines.size() + " engines selected");
        selectedText.setText(b.length() == 0 ? "No engines selected" : b.toString());
        updateEstimatedGames();
        rebuildRatingInitialRows();
    }

    private void updateEstimatedGames() {
        if (gameEstimateText == null || selectedEngines == null || formatSpinner == null) return;
        int n = selectedEngines.size();
        if (n < 2) {
            gameEstimateText.setText("Estimated games: Select at least two engines");
            return;
        }
        int format = formatSpinner.getSelectedItemPosition();
        int gamesPerPair = 2;
        try { if (gamesInput != null) gamesPerPair = Math.max(1, Integer.parseInt(gamesInput.getText().toString().trim())); }
        catch (RuntimeException ignored) { }
        long total;
        if (format == TournamentManager.Format.ROUND_ROBIN.ordinal()) {
            total = ((long)n * (n - 1) / 2L) * gamesPerPair;
            String formula = gamesPerPair == 2 ? (n + " × " + (n - 1))
                    : (n + " × " + (n - 1) + " ÷ 2 × " + gamesPerPair);
            gameEstimateText.setText("Estimated games: " + total + " (" + formula + ", " + gamesPerPair + " games per pairing)");
        } else if (format == TournamentManager.Format.MATCH.ordinal()) {
            if (n != 2) gameEstimateText.setText("Estimated games: Match format requires exactly 2 engines");
            else gameEstimateText.setText("Estimated games: " + gamesPerPair);
        } else if (format == TournamentManager.Format.GAUNTLET.ordinal()) {
            total = (long)(n - 1) * gamesPerPair;
            gameEstimateText.setText("Estimated games: " + total + " (" + (n - 1) + " opponents × " + gamesPerPair + ")");
        } else if (format == TournamentManager.Format.SWISS.ordinal()) {
            int rounds;
            try { rounds = Integer.parseInt(swissRoundsInput.getText().toString().trim()); }
            catch (RuntimeException ignored) { rounds = TournamentManager.recommendedSwissRounds(n); }
            rounds = Math.max(1, rounds);
            total = (long)rounds * (n / 2) * 2L;
            gameEstimateText.setText("Estimated games: " + total + " (" + rounds + " rounds × " + (n / 2) + " pairings × 2 games)");
        } else {
            gameEstimateText.setText("Estimated games: Bracket-dependent (calculated after Cup seeding)");
        }
    }

    private void syncRatingInitialValues() {
        for (Map.Entry<String, EditText> entry : ratingInitialInputs.entrySet()) {
            EditText input = entry.getValue();
            if (input != null) ratingSetupValues.put(entry.getKey(), input.getText().toString());
        }
    }

    private void updateRatingSetupUi() {
        boolean enabled = ratingEnabledCheck != null && ratingEnabledCheck.isChecked();
        if (ratingInitialContainer != null) ratingInitialContainer.setVisibility(enabled ? View.VISIBLE : View.GONE);
        if (enabled) rebuildRatingInitialRows();
    }

    private void rebuildRatingInitialRows() {
        if (ratingInitialContainer == null) return;
        syncRatingInitialValues();
        ratingInitialContainer.removeAllViews();
        ratingInitialInputs.clear();
        if (ratingEnabledCheck == null || !ratingEnabledCheck.isChecked()) return;
        TextView heading = text("Initial Elo for each engine (default 3000; allowed 0–10000)");
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        ratingInitialContainer.addView(heading, fullWidth());
        for (EngineChoice engine : selectedEngines) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView name = text(engine.name);
            name.setMaxLines(2);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            EditText rating = new EditText(this);
            rating.setSingleLine(true);
            rating.setInputType(android.text.InputType.TYPE_CLASS_NUMBER |
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL |
                    android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
            rating.setHint("3000");
            String value = ratingSetupValues.get(engine.id);
            rating.setText(value == null || value.trim().isEmpty() ? "3000" : value);
            row.addView(rating, new LinearLayout.LayoutParams(dp(112), -2));
            ratingInitialInputs.put(engine.id, rating);
            rating.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    ratingSetupValues.put(engine.id, s.toString());
                }
                @Override public void afterTextChanged(android.text.Editable editable) { }
            });
            ratingInitialContainer.addView(row, fullWidth());
        }
    }

    private Map<String, Double> collectInitialRatings() {
        HashMap<String, Double> values = new HashMap<>();
        for (EngineChoice engine : selectedEngines) {
            EditText input = ratingInitialInputs.get(engine.id);
            String raw = input == null ? ratingSetupValues.get(engine.id) : input.getText().toString();
            if (raw == null || raw.trim().isEmpty()) raw = "3000";
            double value;
            try { value = Double.parseDouble(raw.trim()); }
            catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("Enter a valid initial Elo rating for " + engine.name);
            }
            if (!Double.isFinite(value) || value < 0.0 || value > 10000.0)
                throw new IllegalArgumentException("Initial Elo for " + engine.name + " must be between 0 and 10000");
            values.put(engine.id, value);
        }
        return values;
    }

    private String formatElo(double rating, boolean decimals) {
        return decimals ? String.format(java.util.Locale.US, "%.2f", rating) :
                Long.toString(Math.round(rating));
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
        if (pgnPlaybackIntervalInput != null) e.putString("setupPgnPlaybackInterval", pgnPlaybackIntervalInput.getText().toString());
        e.putString("setupOpeningStart", openingStartInput.getText().toString());
        e.putBoolean("setupRepeat", forcedPairFormat ? repeatOpeningBeforeForcedPairFormat : repeatOpeningCheck.isChecked());
        if (startPositionModeSpinner != null) e.putInt("setupStartPositionMode", startPositionModeSpinner.getSelectedItemPosition());
        if (chess960StartInput != null) e.putString("setupChess960Start", chess960StartInput.getText().toString());
        if (variantSeedInput != null) e.putString("setupStartPositionSeed", variantSeedInput.getText().toString());
        if (openingFenInput != null) e.putString("setupOpeningFen", openingFenInput.getText().toString());
        e.putBoolean("setupArrows", showTournamentArrowsCheck.isChecked());
        e.putBoolean("setupEngineIconsEnabled", engineIconsCheck != null && engineIconsCheck.isChecked());
        syncRatingInitialValues();
        e.putBoolean("setupRatingEnabled", ratingEnabledCheck != null && ratingEnabledCheck.isChecked());
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
        if (startPositionModeSpinner != null) {
            int savedMode = p.contains("setupStartPositionMode") ? p.getInt("setupStartPositionMode", 0) :
                    (p.getBoolean("setupChess960Enabled", false) ? 1 : 0);
            startPositionModeSpinner.setSelection(Math.max(0, Math.min(3, savedMode)));
        }
        if (chess960StartInput != null) chess960StartInput.setText(p.getString("setupChess960Start", "1"));
        if (variantSeedInput != null) variantSeedInput.setText(p.getString("setupStartPositionSeed", ""));
        if (openingFenInput != null) openingFenInput.setText(p.getString("setupOpeningFen", ""));
        updateStartingPositionUi();
        int savedFormat = p.getInt("setupFormat", 0);
        formatSpinner.setSelection(savedFormat);
        applyTournamentFormatUi(savedFormat);
        timeModeSpinner.setSelection(p.getInt("setupTimeMode", 0));
        baseInput.setText(p.getString("setupBase", baseInput.getText().toString())); incrementInput.setText(p.getString("setupIncrement", incrementInput.getText().toString()));
        limitInput.setText(p.getString("setupLimit", limitInput.getText().toString())); openingPliesInput.setText(p.getString("setupPlies", openingPliesInput.getText().toString()));
        if (pgnPlaybackIntervalInput != null) pgnPlaybackIntervalInput.setText(p.getString("setupPgnPlaybackInterval", "1.0"));
        openingStartInput.setText(p.getString("setupOpeningStart", openingStartInput.getText().toString()));
        showTournamentArrowsCheck.setChecked(p.getBoolean("setupArrows", true));
        if (engineIconsCheck != null) engineIconsCheck.setChecked(p.getBoolean("setupEngineIconsEnabled", true));
        if (ratingEnabledCheck != null) ratingEnabledCheck.setChecked(p.getBoolean("setupRatingEnabled", false));
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
        ratingSetupValues.clear();
        // Initial Elo values belong to one tournament only; every fresh setup starts at 3000.
        for (EngineChoice engine : selectedEngines) ratingSetupValues.put(engine.id, "3000");
        updateSelectedText();
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
        updateRatingSetupUi();
        updateSwissSeedingUi(); updateCupSeedingUi();
        restoringSetup = false;
    }
    private String mirrorHeaderLabel() {
        if (mirrorHeaderShowsTitle) {
            return activeTournamentManager == null ? "Tournament" : activeTournamentManager.title();
        }
        long elapsedSeconds = Math.max(0L, currentTournamentElapsedMs()) / 1000L;
        String elapsed = String.format(java.util.Locale.US, "%02d:%02d:%02d",
                elapsedSeconds / 3600L, (elapsedSeconds / 60L) % 60L, elapsedSeconds % 60L);
        return "Game: " + currentGameNumber + "/" + currentGameTotal +
                "  TC: " + mirrorTcLabel + "  T: " + elapsed;
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
        final TournamentManager t = activeTournamentManager;
        if (t == null) return;
        t.setTournamentElapsedMs(currentTournamentElapsedMs());
        // Full JSON serialization, GZIP compression, verification and external mirrors
        // must never run on the Android UI thread or compete synchronously with a move.
        PENDING_ELAPSED_SAVE.set(t);
        scheduleElapsedSaveWorker();
        lastTournamentElapsedSaveNs = System.nanoTime();
    }

    private static void scheduleElapsedSaveWorker() {
        if (!ELAPSED_SAVE_WORKER_ACTIVE.compareAndSet(false, true)) return;
        TOURNAMENT_SAVE_EXECUTOR.execute(() -> {
            try {
                TournamentManager pending;
                while ((pending = PENDING_ELAPSED_SAVE.getAndSet(null)) != null) {
                    long saveStartNs = System.nanoTime();
                    try {
                        pending.saveElapsedCheckpoint();
                        long saveMs = (System.nanoTime() - saveStartNs) / 1_000_000L;
                        if (saveMs >= 100L)
                            android.util.Log.w("CET-TournamentSave", "Lightweight elapsed checkpoint took " + saveMs + " ms");
                    } catch (Exception checkpointError) {
                        android.util.Log.e("TournamentSave", "Lightweight elapsed checkpoint failed; falling back to full save", checkpointError);
                        try { pending.save(); }
                        catch (Exception fullSaveError) {
                            android.util.Log.e("TournamentSave", "Background elapsed full-save fallback failed", fullSaveError);
                        }
                    }
                }
            } finally {
                ELAPSED_SAVE_WORKER_ACTIVE.set(false);
                if (PENDING_ELAPSED_SAVE.get() != null) scheduleElapsedSaveWorker();
            }
        });
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
            // Elapsed time is only a recovery checkpoint; result, pause and stop paths
            // still request immediate saves. Sixty-second checkpoints avoid repeatedly
            // serializing and compressing the whole tournament during engine searches.
            if (lastTournamentElapsedSaveNs == 0L || now - lastTournamentElapsedSaveNs >= 60_000_000_000L) persistTournamentElapsedTime();
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

    private long lastMirrorHeaderRefreshNs;

    private void refreshLiveClocks() {
        TournamentRunner r=runner; if(r==null || playView==null) return;
        if (r.isPaused()) {
            if (isMirrorShowing() && mirrorPauseButton != null) mirrorPauseButton.setText("PLAY");
            return;
        }
        long now = System.nanoTime();
        long w=r.remainingMs(true), b=r.remainingMs(false);
        if(whiteClockText!=null && w>=0) whiteClockText.setText(formatClock(w));
        if(blackClockText!=null && b>=0) blackClockText.setText(formatClock(b));
        long elapsed=r.activeMoveTimeMs();
        if(elapsed>=0) {
            boolean white=r.whiteToMove();
            String display = formatClockDuration(elapsed);
            TextView t=white?whiteMoveTimeText:blackMoveTimeText;
            if(t!=null)t.setText(display);
            // Mirror is another presentation of these clocks. Avoid synchronizing
            // its board, icon bitmaps, ranks and navigation ten times per second.
            if (isMirrorShowing()) {
                TextView mirrorTime = white ? mirrorWhiteMoveTime : mirrorBlackMoveTime;
                if (mirrorTime != null) mirrorTime.setText(display);
            }
        }
        if (isMirrorShowing()) {
            if (mirrorWhiteClock != null && w >= 0) mirrorWhiteClock.setText(formatClock(w));
            if (mirrorBlackClock != null && b >= 0) mirrorBlackClock.setText(formatClock(b));
            if (mirrorPauseButton != null) mirrorPauseButton.setText(r.isPaused() ? "PLAY" : "PAUSE");
            if (mirrorHeaderText != null && now - lastMirrorHeaderRefreshNs >= 1_000_000_000L) {
                mirrorHeaderText.setText(mirrorHeaderLabel());
                lastMirrorHeaderRefreshNs = now;
            }
        }
        updateExpandedClockDisplay();
    }

    private void startTournament() {
        if (tournamentLoadInProgress || tournamentReviewPreparationInProgress) {
            Toast.makeText(this, "Tournament data is still loading", Toast.LENGTH_SHORT).show();
            return;
        }
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
            int modeIndex = startPositionModeSpinner == null ? 0 : startPositionModeSpinner.getSelectedItemPosition();
            TournamentManager.StartPositionMode startPositionMode = TournamentManager.StartPositionMode.values()[Math.max(0, Math.min(3, modeIndex))];
            int chess960Start = 1;
            if (startPositionMode == TournamentManager.StartPositionMode.CHESS960) {
                chess960Start = positiveInt(chess960StartInput, "starting Chess960 position number", 1);
                if (chess960Start > 960) throw new IllegalArgumentException("Chess960 starting position must be between 1 and 960");
            }
            String seedText = variantSeedInput == null ? "" : variantSeedInput.getText().toString().trim();
            boolean seededRandomMode = startPositionMode == TournamentManager.StartPositionMode.DFRC ||
                    startPositionMode == TournamentManager.StartPositionMode.CHESS324;
            Long requestedSeed = !seededRandomMode || seedText.isEmpty() ? null : Long.valueOf(seedText);
            String startingFen = openingFenInput == null ? "" : openingFenInput.getText().toString().trim().replaceAll("\\s+", " ");
            if (!startingFen.isEmpty()) TextIO.readFEN(startingFen); // Validate before creating/saving the tournament.
            // A selected PGN is applied in full by default (0); positive values cap half-moves.
            if (!startingFen.isEmpty() && openingPlies > 0)
                throw new IllegalArgumentException("A custom starting FEN overrides PGN/BIN openings; set the opening-ply limit to 0");
            if (openingPlies > 0 && selectedOpeningPgn.trim().isEmpty() && selectedOpeningBinPath.trim().isEmpty())
                throw new IllegalArgumentException("Select a PGN/BIN opening before setting a custom opening-ply limit");
            if (baseMillis > Integer.MAX_VALUE) throw new IllegalArgumentException("Base time is too large");
            if (format == TournamentManager.Format.MATCH && selectedEngines.size() != 2)
                throw new IllegalArgumentException("Match format requires exactly two engines");
            ArrayList<TournamentManager.Engine> engines = new ArrayList<>();
            for (EngineChoice e : selectedEngines) engines.add(new TournamentManager.Engine(e.id, e.name));
            String tournamentName = titleInput.getText().toString().trim();
            if (tournamentName.isEmpty()) tournamentName = "My Tournament";
            boolean enableRatings = ratingEnabledCheck != null && ratingEnabledCheck.isChecked();
            Map<String, Double> initialRatingValues = enableRatings ? collectInitialRatings() : java.util.Collections.emptyMap();
            saveSetup();
            final String finalTournamentName = tournamentName;
            final int finalSwissRounds = swissRounds;
            final int finalChess960Start = chess960Start;
            final Map<String, Double> finalInitialRatings = new HashMap<>(initialRatingValues);
            final String finalOpeningPgn = selectedOpeningPgn;
            final String finalOpeningBinPath = selectedOpeningBinPath;
            final String finalOpeningFileName = selectedOpeningFileName;
            final boolean finalRepeatOpening = format == TournamentManager.Format.SWISS ||
                    format == TournamentManager.Format.CUP || repeatOpeningCheck.isChecked();

            // Schedule construction, JSON serialization, compression and verification
            // can all be substantial for large fields. Keep them off the UI thread,
            // and do not launch engines until the private snapshot has committed.
            tournamentLoadInProgress = true;
            if (startButton != null) startButton.setEnabled(false);
            if (resumeButton != null) resumeButton.setEnabled(false);
            if (stopButton != null) stopButton.setEnabled(false);
            Toast.makeText(this, "Building schedule and saving tournament in background…", Toast.LENGTH_SHORT).show();
            TOURNAMENT_SAVE_EXECUTOR.execute(() -> {
                TournamentManager created = null;
                Exception failure = null;
                try {
                    created = new TournamentManager(this, finalTournamentName, format,
                            engines, games, baseMillis, increment, finalOpeningPgn, openingPlies,
                            finalRepeatOpening, TournamentManager.TimeMode.values()[timeModeIndex],
                            limitValue, openingStart, finalSwissRounds, swissSeedingMode,
                            swissRankOrderIds, cupSeedingMode, cupRankOrderIds);
                    created.setRatingSettings(enableRatings, finalInitialRatings, false);
                    created.setOpeningBinPath(finalOpeningBinPath);
                    long positionSeed = requestedSeed == null ? created.startingPositionSeed() : requestedSeed;
                    created.setStartingPositionSettings(startPositionMode, finalChess960Start, positionSeed, startingFen);
                    created.setOpeningPgnFileName(finalOpeningPgn.trim().isEmpty() ? "" : finalOpeningFileName);
                    created.save();
                } catch (Exception error) {
                    failure = error;
                }
                final TournamentManager savedTournament = created;
                final Exception saveFailure = failure;
                runOnUiThread(() -> {
                    tournamentLoadInProgress = false;
                    if (isFinishing()) return;
                    if (saveFailure != null || savedTournament == null) {
                        if (startButton != null) startButton.setEnabled(true);
                        if (resumeButton != null) resumeButton.setEnabled(true);
                        if (stopButton != null) stopButton.setEnabled(false);
                        Toast.makeText(this, "Could not create/save tournament: " +
                                (saveFailure == null ? "unknown error" : saveFailure.getMessage()), Toast.LENGTH_LONG).show();
                        return;
                    }
                    // Reset only the rating defaults for the engine IDs included in
                    // this successfully saved tournament.
                    for (TournamentManager.Engine engine : engines) ratingSetupValues.put(engine.id, "3000");
                    rebuildRatingInitialRows();
                    launchTournament(savedTournament);
                });
            });
        } catch (Exception e) {
            tournamentLoadInProgress = false;
            TournamentRunner failedRunner = runner;
            runner = null;
            if (failedRunner != null) {
                try { failedRunner.stop(); } catch (RuntimeException ignored) { }
            }
            if (startButton != null) startButton.setEnabled(true);
            if (resumeButton != null) resumeButton.setEnabled(true);
            if (stopButton != null) stopButton.setEnabled(false);
            // A failed start must return to setup instead of leaving a half-initialized board.
            if (setupView != null) { boardSelectionActive = false; setContentView(setupView); }
            Toast.makeText(this, "Could not start tournament: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_TOURNAMENT_ENGINE_OPTIONS) {
            if (resultCode == RESULT_OK && data != null) {
                @SuppressWarnings("unchecked")
                Map<String, String> options = (Map<String, String>) data.getSerializableExtra("org.petero.droidfish.ucioptions");
                if (options != null) {
                    if (pendingAnalysisEngineOptions && mirrorAnalysisEngine != null) {
                        mirrorAnalysisEngine.setEngineUCIOptions(options);
                    } else if (!pendingAnalysisEngineOptions && runner != null && !pendingTournamentOptionsEngineId.isEmpty()) {
                        runner.setUciOptionsForTournamentEngine(pendingTournamentOptionsWhite, pendingTournamentOptionsEngineId, options);
                    }
                }
            }
            boolean restartAnalysis = pendingAnalysisEngineOptions && mirrorAnalysisOn && mirrorAnalysisEngineActive;
            pendingAnalysisEngineOptions = false;
            pendingTournamentOptionsEngineId = "";
            pendingTournamentOptionsWhite = false;
            if (restartAnalysis) startMirrorAnalysis();
            return;
        }
        if (requestCode == REQUEST_ENGINE_ICON_FOLDER && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri tree = data.getData();
            try {
                int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getContentResolver().takePersistableUriPermission(tree, flags);
            } catch (SecurityException ignored) { }
            engineIconFolderUri = tree;
            engineIconUris.clear(); engineIconBitmaps.clear(); engineIconFolderScanned = false;
            invalidateAllMirrorSectionCaches(); clearMirrorSubCaches();
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
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/json", "application/gzip", "application/x-gzip", "application/octet-stream", "text/plain"});
        try { startActivityForResult(intent, REQUEST_TOURNAMENT_JSON); }
        catch (android.content.ActivityNotFoundException error) {
            Toast.makeText(this, "No file picker is available to import a tournament JSON", Toast.LENGTH_LONG).show();
        }
    }

    private void importTournamentJson(Uri uri) {
        final String sourceName = queryDisplayName(uri);
        new Thread(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("Could not open selected JSON file");
                // Accept legacy plain JSON and gzip-compressed .json.gz files by
                // inspecting the bytes, with a limit on the decoded JSON payload.
                String json = TournamentManager.readTournamentJson(in);
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
        SavedTournamentEntry(String fileName, String label) {
            this.fileName = fileName;
            this.label = label;
        }
    }

    private String savedTournamentMenuLabel(String fileName) {
        String stem = fileName == null ? "Saved tournament" : fileName;
        stem = stem.replaceFirst("\\.json(?:\\.gz)?$", "");
        String sequence = "";
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("^(.*)_([0-9]{4,})$").matcher(stem);
        if (matcher.matches()) {
            stem = matcher.group(1);
            sequence = " · #" + matcher.group(2);
        }
        return stem.replace('_', ' ') + sequence;
    }

    /** Active events are the only tournaments offered for resuming. */
    private void chooseSavedTournament() {
        chooseTournamentList(false);
    }

    /** Completed archives are exposed through a separate, read-only picker. */
    private void chooseCompletedTournaments() {
        chooseTournamentList(true);
    }

    private void chooseTournamentList(boolean completedOnly) {
        if (tournamentLoadInProgress || tournamentReviewPreparationInProgress) {
            Toast.makeText(this, "Tournament data is still loading", Toast.LENGTH_SHORT).show();
            return;
        }
        // Do not make the Active/Resume picker wait for a full legacy migration:
        // a selected legacy tournament is restored on its own worker. The full
        // classification pass is needed only to populate the separate Completed
        // archive list, and is attempted at most once per process.
        if (completedOnly && runner == null && !legacyMigrationAttemptedThisProcess &&
                TournamentManager.hasLegacyTournamentsToMigrate(this)) {
            legacyMigrationAttemptedThisProcess = true;
            tournamentLoadInProgress = true;
            final ProgressDialog progress = new ProgressDialog(this);
            progress.setMessage("Organizing saved tournaments…");
            progress.setIndeterminate(true);
            progress.setCancelable(false);
            progress.show();
            new Thread(() -> {
                TournamentManager.migrateLegacyTournaments(this);
                runOnUiThread(() -> {
                    progress.dismiss();
                    tournamentLoadInProgress = false;
                    if (!isFinishing()) showTournamentList(completedOnly);
                });
            }, "tournament-legacy-migration").start();
            return;
        }
        showTournamentList(completedOnly);
    }

    private void showTournamentList(boolean completedOnly) {
        List<String> files = completedOnly ? TournamentManager.completedTournamentFiles(this) :
                TournamentManager.activeTournamentFiles(this);
        if (files.isEmpty()) {
            Toast.makeText(this, completedOnly ? "No archived tournaments" : "No active tournaments", Toast.LENGTH_SHORT).show();
            return;
        }

        // Listing is metadata-only. Restore is performed only after selection.
        final ArrayList<SavedTournamentEntry> entries = new ArrayList<>();
        String[] labels = new String[files.size()];
        for (int i = 0; i < files.size(); i++) {
            String file = files.get(i);
            SavedTournamentEntry entry = new SavedTournamentEntry(file, savedTournamentMenuLabel(file));
            entries.add(entry);
            labels[i] = entry.label;
        }
        String title = completedOnly ? "Archived tournaments · View only" : "Active tournaments · Resume / View";
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView row = (TextView) super.getView(position, convertView, parent);
                row.setPadding(dp(18), dp(13), dp(18), dp(13));
                row.setTextSize(16);
                return row;
            }
        };
        AlertDialog listDialog = new AlertDialog.Builder(this).setTitle(title)
            .setAdapter(adapter, (dialog, which) -> {
                SavedTournamentEntry entry = entries.get(which);
                if (runner != null) {
                    if (!completedOnly && activeTournamentManager != null &&
                            entry.fileName.equals(activeTournamentManager.savedFileName())) {
                        setContentView(isMirrorShowing() ? mirrorView : playView);
                    } else {
                        Toast.makeText(this, "Pause and stop the active tournament before opening another saved event", Toast.LENGTH_LONG).show();
                    }
                } else {
                    showSavedTournamentActions(entry.fileName);
                }
            })
            .setNegativeButton("Cancel", null).create();
        listDialog.setOnShowListener(dialog -> listDialog.getListView().setOnItemLongClickListener(
                (parent, view, position, id) -> {
                    if (position < 0 || position >= entries.size()) return true;
                    if (runner != null || tournamentLoadInProgress || tournamentReviewPreparationInProgress) {
                        Toast.makeText(this, "Stop the active tournament before managing saved tournaments", Toast.LENGTH_LONG).show();
                        return true;
                    }
                    String selectedFile = entries.get(position).fileName;
                    listDialog.dismiss();
                    showTournamentManagementActions(selectedFile, completedOnly);
                    return true;
                }));
        listDialog.show();
    }

    private void showTournamentManagementActions(String fileName, boolean currentlyCompleted) {
        String[] actions = currentlyCompleted
                ? new String[]{"Move to active tournament", "Delete tournament"}
                : new String[]{"Move to archive", "Delete tournament"};
        new AlertDialog.Builder(this).setTitle(savedTournamentMenuLabel(fileName))
                .setItems(actions, (dialog, which) -> {
                    if (currentlyCompleted && which == 0) {
                        runTournamentStorageAction("Restoring tournament to active…", fileName,
                                () -> TournamentManager.moveToActive(this, fileName), false);
                    } else if (!currentlyCompleted && which == 0) {
                        runTournamentStorageAction("Moving tournament to archive…", fileName,
                                () -> TournamentManager.moveToArchive(this, fileName), true);
                    } else {
                        new AlertDialog.Builder(this).setTitle("Delete tournament?")
                                .setMessage(savedTournamentMenuLabel(fileName) + " and its PGNs, results, and recovery data will be permanently deleted.")
                                .setNegativeButton("Cancel", null)
                                .setPositiveButton("Delete", (d, w) -> runTournamentStorageAction("Deleting tournament…", fileName,
                                        () -> TournamentManager.deleteSavedTournament(this, fileName), currentlyCompleted))
                                .show();
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private interface TournamentStorageAction { void run() throws Exception; }

    private void runTournamentStorageAction(String message, String fileName,
                                            TournamentStorageAction action, boolean refreshCompletedList) {
        if (tournamentLoadInProgress || tournamentReviewPreparationInProgress || runner != null) {
            Toast.makeText(this, "Tournament storage is currently in use", Toast.LENGTH_LONG).show();
            return;
        }
        tournamentLoadInProgress = true;
        ProgressDialog progress = new ProgressDialog(this);
        progress.setMessage(message);
        progress.setIndeterminate(true);
        progress.setCancelable(false);
        progress.show();
        new Thread(() -> {
            Exception failure = null;
            try { action.run(); } catch (Exception error) { failure = error; }
            final Exception result = failure;
            runOnUiThread(() -> {
                progress.dismiss();
                tournamentLoadInProgress = false;
                if (result != null) {
                    Toast.makeText(this, "Tournament operation failed: " + result.getMessage(), Toast.LENGTH_LONG).show();
                } else {
                    if (activeTournamentManager != null && fileName.equals(activeTournamentManager.savedFileName()))
                        activeTournamentManager = null;
                    cachedScheduleDirty = true;
                    if (cachedScheduleDialog != null) { cachedScheduleDialog.dismiss(); cachedScheduleDialog = null; }
                    cachedAnomalyDialog = null;
                    Toast.makeText(this, "Tournament updated", Toast.LENGTH_SHORT).show();
                    if (!isFinishing()) showTournamentList(refreshCompletedList);
                }
            });
        }, "tournament-storage-action").start();
    }

    private void showSavedTournamentActions(String fileName) {
        if (tournamentLoadInProgress) return;
        TournamentDiagnostics.setPhase("tournament_restore", "selected saved tournament=" + fileName);
        tournamentLoadInProgress = true;
        final ProgressDialog progressDialog = new ProgressDialog(this);
        progressDialog.setMessage("Loading selected tournament…");
        progressDialog.setIndeterminate(true);
        progressDialog.setCancelable(false);
        progressDialog.show();
        new Thread(() -> {
            TournamentManager saved = null;
            Exception failure = null;
            long restoreStartedNs = System.nanoTime();
            try { saved = TournamentManager.restore(this, fileName); }
            catch (Exception error) { failure = error; TournamentDiagnostics.recordError("RESTORE", "selected_tournament_restore_failed file=" + fileName, error); }
            TournamentDiagnostics.record("RESTORE", "selected_tournament_loader_finished", "file=" + fileName + " elapsedMs=" +
                    ((System.nanoTime() - restoreStartedNs) / 1_000_000L));
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
        final boolean completedArchive = TournamentManager.isCompletedTournament(this, fileName) || saved.isComplete();
        final String[] actions = completedArchive
                ? new String[]{"View completed tournament"}
                : new String[]{"Resume tournament", "View tournament", "Delete tournament"};
        new AlertDialog.Builder(this).setTitle(saved.title()).setItems(actions, (dialog, which) -> {
            if (which == 0) {
                if (completedArchive) {
                    showSavedTournamentReview(saved);
                } else {
                    // Resume is a continuation, not a request to open the review UI.
                    // The review screen builds the complete Schedule tab and calculates
                    // move counts by parsing completed PGNs on the UI thread. For large
                    // tournaments that unnecessary work can trigger Android's ANR
                    // before the engines even start. Restore has already completed on
                    // the loader thread, so load only the saved settings and continue
                    // directly with the background opening/standings preparation.
                    loadTournamentSetup(saved);
                    savedTournamentReviewMode = false;
                    launchTournament(saved);
                }
            } else if (!completedArchive && which == 1) {
                showSavedTournamentReview(saved);
            } else if (!completedArchive && which == 2) {
                new AlertDialog.Builder(this).setTitle("Delete saved tournament?").setMessage(saved.title() + " will be permanently deleted.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Delete", (d, w) -> {
                        try { TournamentManager.deleteSavedTournament(this, fileName); Toast.makeText(this, "Tournament deleted", Toast.LENGTH_SHORT).show(); }
                        catch (Exception e) { Toast.makeText(this, "Delete failed: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
                    }).show();
            }
        }).setNegativeButton("Cancel", null).show();
    }

    private boolean blockSavedTournamentReviewEdit() {
        if (!savedTournamentReviewMode && !mirrorReplayMode) return false;
        String message = mirrorReplayMode
                ? "Game review is read-only. Return to the live game before changing tournament settings or results."
                : "Saved tournament review is read-only. Resume an unfinished tournament to make changes.";
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        return true;
    }

    /** Open a saved tournament in read-only Mirror Board review without starting engines. */
    private void showSavedTournamentReview(TournamentManager saved) {
        showSavedTournamentReview(saved, null);
    }

    private void showSavedTournamentReview(TournamentManager saved, Runnable afterReady) {
        if (runner != null) {
            Toast.makeText(this, "Pause and stop the active tournament before reviewing another event", Toast.LENGTH_LONG).show();
            return;
        }
        if (tournamentReviewPreparationInProgress) return;
        tournamentReviewPreparationInProgress = true;
        TOURNAMENT_SAVE_EXECUTOR.execute(() -> {
            List<TournamentManager.Standing> reviewStandings;
            List<TournamentManager.Engine> reviewEngines;
            String reviewStandingsText;
            try {
                reviewStandings = saved.standings();
                reviewEngines = saved.engines();
                reviewStandingsText = formatStandings(saved, reviewStandings, reviewEngines);
            } catch (Exception error) {
                runOnUiThread(() -> {
                    tournamentReviewPreparationInProgress = false;
                    if (!isFinishing()) Toast.makeText(this, "Could not prepare tournament review: " + error.getMessage(), Toast.LENGTH_LONG).show();
                });
                return;
            }
            final List<TournamentManager.Standing> finalStandings = reviewStandings;
            final String finalStandingsText = reviewStandingsText;
            runOnUiThread(() -> {
                tournamentReviewPreparationInProgress = false;
                if (isFinishing()) return;
                showSavedTournamentReviewPrepared(saved, finalStandings, finalStandingsText);
                if (afterReady != null) afterReady.run();
            });
        });
    }

    private void showSavedTournamentReviewPrepared(TournamentManager saved,
            List<TournamentManager.Standing> reviewStandings, String reviewStandingsText) {
        if (runner != null) {
            Toast.makeText(this, "Pause and stop the active tournament before reviewing another event", Toast.LENGTH_LONG).show();
            return;
        }
        activeTournamentManager = saved;
        savedTournamentReviewMode = true;
        currentTournamentScreen = 1;
        boardSelectionActive = true;
        saveBoardSelectionState(1);
        mirrorReplayMode = false;
        mirrorReplayLiveSnapshot = false;
        mirrorReplayPairingIndex = -1;
        mirrorReplayWhiteEngineId = mirrorReplayBlackEngineId = "";
        mirrorReplayPositions.clear(); mirrorReplayMoves.clear(); mirrorReplayComments.clear();
        mirrorReplayPgn = "";
        mirrorPly = -1;
        mirrorPositions.clear();
        mirrorLiveSanMoves.clear();
        mirrorLiveComments.clear();
        mirrorLiveAnalysisByPly.clear();
        mirrorLiveHistoryInitialized = false;
        latestPosition = standardStartPosition();
        currentGameNumber = 0;
        currentGameTotal = saved.pairings().size();
        mirrorTcLabel = mirrorTimeControlLabel(saved);
        mirrorHeaderShowsTitle = true;
        currentWhiteEngineId = currentBlackEngineId = "";
        currentWhiteName = "WHITE";
        currentBlackName = "BLACK";
        currentBoardWhiteScore = currentBoardBlackScore = "";
        lastWhiteAnalysis = lastBlackAnalysis = "—";
        clearTournamentMainWdl();
        updateMirrorEvaluationBars(lastWhiteAnalysis, lastBlackAnalysis);
        if (tournamentTitleText != null) tournamentTitleText.setText(saved.title());
        if (statusText != null) statusText.setText(saved.isComplete() ? "Completed tournament review" : "Saved tournament review");
        if (moveStatusText != null) moveStatusText.setText(saved.isComplete() ? "TOURNAMENT COMPLETE · REVIEW MODE" : "SAVED TOURNAMENT · REVIEW MODE");
        if (standingsText != null) standingsText.setText(reviewStandingsText);
        if (tournamentBoard != null) {
            tournamentBoard.setPosition(new Position(latestPosition));
            tournamentBoard.setLastMove(null);
            tournamentBoard.setMoveHints(null);
            tournamentBoard.setSelection(-1);
        }
        if (mirrorBoard != null) {
            mirrorBoard.setPosition(new Position(latestPosition));
            mirrorBoard.setLastMove(null);
            mirrorBoard.invalidate();
        }
        if (playPauseButton != null) { playPauseButton.setText("REVIEW"); playPauseButton.setEnabled(false); }
        if (mirrorPauseButton != null) { mirrorPauseButton.setText("REVIEW"); mirrorPauseButton.setEnabled(false); }
        if (startButton != null) startButton.setEnabled(false);
        if (resumeButton != null) resumeButton.setEnabled(true);
        if (stopButton != null) stopButton.setEnabled(false);
        refreshMirrorRanks(reviewStandings);
        invalidateAllMirrorSectionCaches();
        clearMirrorSubCaches();
        setContentView(mirrorView);
        selectMirrorTab(2, false);
        if (mirrorHeaderText != null) mirrorHeaderText.setText(mirrorHeaderLabel());
    }

    private void loadTournamentSetup(TournamentManager tournament) {
        loadingTournamentSetup = true;
        selectedEngines.clear();
        if (startButton != null) startButton.setEnabled(true);
        if (resumeButton != null) resumeButton.setEnabled(true);
        if (stopButton != null) stopButton.setEnabled(false);
        for (TournamentManager.Engine engine : tournament.engines()) selectedEngines.add(new EngineChoice(engine.id, engine.displayName));
        ratingSetupValues.clear();
        Map<String, Double> savedInitialRatings = tournament.initialRatings();
        for (TournamentManager.Engine engine : tournament.engines()) {
            Double savedRating = savedInitialRatings.get(engine.id);
            ratingSetupValues.put(engine.id, savedRating == null ? "3000" : Double.toString(savedRating));
        }
        if (ratingEnabledCheck != null) ratingEnabledCheck.setChecked(tournament.ratingEnabled());
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
        if (startPositionModeSpinner != null) startPositionModeSpinner.setSelection(tournament.startPositionMode().ordinal());
        if (chess960StartInput != null) chess960StartInput.setText(String.valueOf(tournament.chess960StartId()));
        if (variantSeedInput != null) variantSeedInput.setText(String.valueOf(tournament.startingPositionSeed()));
        if (openingFenInput != null) openingFenInput.setText(tournament.openingFen());
        updateStartingPositionUi();
        loadingTournamentSetup = false;
        // The authoritative opening book is already stored in this tournament's JSON.
        // Do not duplicate a potentially multi-megabyte PGN into SharedPreferences.
    }

    private void updateTurnNameLabels() {
        if (whiteNameText != null) {
            whiteNameText.setText(appendResultScore(currentWhiteName, currentBoardWhiteScore));
            whiteNameText.setTextColor(currentWhiteToMove ? 0xFFFFFFFF : 0xFF9A9A9A);
        }
        if (blackNameText != null) {
            blackNameText.setText(appendResultScore(currentBlackName, currentBoardBlackScore));
            blackNameText.setTextColor(currentWhiteToMove ? 0xFF9A9A9A : 0xFFFFFFFF);
        }
        if (whiteClockText != null) whiteClockText.setAlpha(currentWhiteToMove ? 1.0f : 0.58f);
        if (blackClockText != null) blackClockText.setAlpha(currentWhiteToMove ? 0.58f : 1.0f);
        if (whiteClockRow != null) whiteClockRow.setBackgroundColor(currentWhiteToMove ? 0xFF3C4650 : 0xFF303030);
        if (blackClockRow != null) blackClockRow.setBackgroundColor(currentWhiteToMove ? 0xFF303030 : 0xFF3C4650);
        updateMainOutputTitles();
    }

    /** Put the output, focus icon and clock/name/time row in one side-owned container. */
    private void createMirrorPlayerGroups() {
        if (mirrorPage == null || mirrorBoardFrame == null || mirrorBlackOutputPanel == null ||
                mirrorWhiteOutputPanel == null || mirrorBlackRow == null || mirrorWhiteRow == null ||
                mirrorFocusBlackIcon == null || mirrorFocusWhiteIcon == null) return;
        if (mirrorBlackPlayerGroup == null) mirrorBlackPlayerGroup = new LinearLayout(this);
        if (mirrorWhitePlayerGroup == null) mirrorWhitePlayerGroup = new LinearLayout(this);
        mirrorBlackPlayerGroup.setOrientation(LinearLayout.VERTICAL);
        mirrorWhitePlayerGroup.setOrientation(LinearLayout.VERTICAL);
        ViewGroup.LayoutParams blackOutputParams = mirrorBlackOutputPanel.getLayoutParams();
        ViewGroup.LayoutParams blackIconParams = mirrorFocusBlackIcon.getLayoutParams();
        ViewGroup.LayoutParams blackRowParams = mirrorBlackRow.getLayoutParams();
        ViewGroup.LayoutParams whiteOutputParams = mirrorWhiteOutputPanel.getLayoutParams();
        ViewGroup.LayoutParams whiteIconParams = mirrorFocusWhiteIcon.getLayoutParams();
        ViewGroup.LayoutParams whiteRowParams = mirrorWhiteRow.getLayoutParams();
        View[] children = {mirrorBlackOutputPanel, mirrorFocusBlackIcon, mirrorBlackRow,
                mirrorWhiteOutputPanel, mirrorFocusWhiteIcon, mirrorWhiteRow};
        for (View child : children) {
            if (child.getParent() instanceof ViewGroup) ((ViewGroup)child.getParent()).removeView(child);
        }
        mirrorBlackPlayerGroup.removeAllViews();
        mirrorWhitePlayerGroup.removeAllViews();
        mirrorBlackPlayerGroup.addView(mirrorBlackOutputPanel, blackOutputParams == null ? new LinearLayout.LayoutParams(-1, dp(90)) : blackOutputParams);
        mirrorBlackPlayerGroup.addView(mirrorFocusBlackIcon, blackIconParams == null ? new LinearLayout.LayoutParams(dp(64), dp(64)) : blackIconParams);
        mirrorBlackPlayerGroup.addView(mirrorBlackRow, blackRowParams == null ? fullWidth() : blackRowParams);
        mirrorWhitePlayerGroup.addView(mirrorWhiteRow, whiteRowParams == null ? fullWidth() : whiteRowParams);
        mirrorWhitePlayerGroup.addView(mirrorFocusWhiteIcon, whiteIconParams == null ? new LinearLayout.LayoutParams(dp(64), dp(64)) : whiteIconParams);
        mirrorWhitePlayerGroup.addView(mirrorWhiteOutputPanel, whiteOutputParams == null ? new LinearLayout.LayoutParams(-1, dp(90)) : whiteOutputParams);
        reorderMirrorPlayerGroups();
    }

    /** Reorder one side's children for its position above or below the board. */
    private void configureMirrorPlayerGroup(LinearLayout group, boolean white, boolean aboveBoard) {
        if (group == null) return;
        View output = white ? mirrorWhiteOutputPanel : mirrorBlackOutputPanel;
        View focusIcon = white ? mirrorFocusWhiteIcon : mirrorFocusBlackIcon;
        View playerRow = white ? mirrorWhiteRow : mirrorBlackRow;
        ViewGroup.LayoutParams outputParams = output.getLayoutParams();
        ViewGroup.LayoutParams iconParams = focusIcon.getLayoutParams();
        ViewGroup.LayoutParams rowParams = playerRow.getLayoutParams();
        group.removeAllViews();
        if (aboveBoard) {
            group.addView(output, outputParams);
            group.addView(focusIcon, iconParams);
            group.addView(playerRow, rowParams);
        } else {
            group.addView(playerRow, rowParams);
            group.addView(focusIcon, iconParams);
            group.addView(output, outputParams);
        }
    }

    /** Move two side-owned groups around the board; flip can no longer separate their children. */
    private void reorderMirrorPlayerGroups() {
        if (mirrorPage == null || mirrorBoardFrame == null || mirrorFocusMovesScroll == null ||
                mirrorBlackPlayerGroup == null || mirrorWhitePlayerGroup == null) return;
        LinearLayout topGroup = mirrorUiFlipped ? mirrorWhitePlayerGroup : mirrorBlackPlayerGroup;
        LinearLayout bottomGroup = mirrorUiFlipped ? mirrorBlackPlayerGroup : mirrorWhitePlayerGroup;
        configureMirrorPlayerGroup(topGroup, mirrorUiFlipped, true);
        configureMirrorPlayerGroup(bottomGroup, !mirrorUiFlipped, false);
        if (mirrorBlackPlayerGroup.getParent() instanceof ViewGroup)
            ((ViewGroup)mirrorBlackPlayerGroup.getParent()).removeView(mirrorBlackPlayerGroup);
        if (mirrorWhitePlayerGroup.getParent() instanceof ViewGroup)
            ((ViewGroup)mirrorWhitePlayerGroup.getParent()).removeView(mirrorWhitePlayerGroup);
        int boardIndex = mirrorPage.indexOfChild(mirrorBoardFrame);
        if (boardIndex < 0) return;
        mirrorPage.addView(topGroup, boardIndex, fullWidth());
        // The side-owned clock/name/move-time row must stay adjacent to the board.
        // Insert the bottom group immediately after the board, before move navigation.
        int currentBoardIndex = mirrorPage.indexOfChild(mirrorBoardFrame);
        mirrorPage.addView(bottomGroup, currentBoardIndex + 1, fullWidth());
        mirrorPage.requestLayout();
    }

    /** Flip each board and keep the matching side-owned analysis groups attached. */
    private void toggleTournamentFlip() {
        if (tournamentBoard != null) tournamentBoard.setFlipped(!tournamentBoard.flipped);
        if (mirrorBoard != null) { mirrorBoard.setFlipped(!mirrorBoard.flipped); mirrorBoard.invalidate(); }
        if (expandedMainBoard != null) expandedMainBoard.setFlipped(!expandedMainBoard.flipped);
        tournamentUiFlipped = !tournamentUiFlipped;
        mirrorUiFlipped = !mirrorUiFlipped;
        expandedPanelsSwapped = !expandedPanelsSwapped;
        expandedBottomPanelWhite = !expandedBottomPanelWhite;
        saveExpandedPanelLayoutPreferences();
        reorderMirrorPlayerGroups();
        if (expandedMainDialog != null && expandedMainDialog.isShowing()) {
            applyExpandedPlayerRowOrder(!expandedWhitePanelBelowBoard);
            applyExpandedPanelRowOrder();
        }
        applyMainBoardAnalysisPositions();
        updateExpandedBoardControls();
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
            List<TournamentManager.Standing> initialStandings = null;
            try {
                tournament.prepareOpeningAssignments();
                // New tournaments with an unassigned BIN book are persisted by
                // setAnomalyRuleSettingsIfMissing(); PGN/no-book preparation saves
                // when it initializes assignments. Imported events are already saved.
                tournament.setAnomalyRuleSettingsIfMissing(anomalyRulesEnabled, configuredRuleStart, configuredMaximumMoves);
                // A large schedule can make standings() expensive. Prepare it here,
                // not synchronously on the UI thread immediately before engine start.
                try { initialStandings = tournament.standings(); }
                catch (RuntimeException standingsError) {
                    android.util.Log.w("CET-Tournament", "Initial standings unavailable during launch", standingsError);
                }
            } catch (Exception error) {
                failure = error;
            }
            final Exception preparationError = failure;
            final List<TournamentManager.Standing> preparedStandings = initialStandings;
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
                launchPreparedTournament(tournament, preparedStandings);
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
        ArrayList<CharSequence> labels = new ArrayList<>();
        List<TournamentManager.Engine> engines = tournament.engines();
        for (String id : advancing) {
            if (updatedIds.contains(id)) continue;
            for (TournamentManager.Engine engine : engines) if (engine.id.equals(id)) {
                pendingIds.add(id);
                labels.add(engineLabelWithIcon(engine.id, engine.displayName,
                        engine.displayName + " · " + engine.executableId, 24));
                break;
            }
        }
        new AlertDialog.Builder(this).setTitle("Update engines for " + tournament.cupNextStageLabel())
                .setItems(labels.toArray(new CharSequence[0]), (dialog, which) -> {
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
        ArrayList<CharSequence> labels = new ArrayList<>();
        for (EngineChoice choice : availableEngines) {
            if (choice.id.equals(target.executableId)) continue;
            boolean used = false;
            for (TournamentManager.Engine engine : tournament.engines())
                if (!engine.id.equals(target.id) && engine.executableId.equals(choice.id)) { used = true; break; }
            if (!used) {
                choices.add(choice);
                labels.add(engineLabelWithIcon(choice.id, choice.name, choice.name + "\n" + choice.id, 24));
            }
        }
        if (choices.isEmpty()) {
            Toast.makeText(this, "No unused replacement executable is available for " + target.displayName, Toast.LENGTH_LONG).show();
            showCupEngineUpdateList(tournament, updatedIds);
            return;
        }
        new AlertDialog.Builder(this).setTitle("Replace " + target.displayName + " for next stage")
                .setItems(labels.toArray(new CharSequence[0]), (dialog, which) -> {
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

    /** Display-only cleanup for legacy PGN comments and normalize Depth/SelDepth. */
    private String formatEngineOutput(String value) {
        if (value == null) return "";
        String formatted = combineDepthFields(value);
        formatted = formatted.replaceAll("(?i)\\s*TBHits:\\s*[^\\s]+", "");
        formatted = formatted.replaceAll("(?i)\\s+Time:\\s*[^\\s]+", "");
        formatted = formatted.replaceAll("(?i)\\s+Hash:\\s*[^\\s]+", "");
        formatted = formatted.replaceAll("(Nodes:\\s*[^\\s]+)\\s+(NPS:\\s*[^\\s]+)", "$2    $1");
        formatted = formatted.replaceAll("[\\r\\n]+", " ");
        formatted = formatted.replaceAll("\\s{2,}", " ");
        return formatted.trim();
    }

    /** Read one UCI statistic from the latest analysis header. */
    private String analysisField(String analysis, String field) {
        if (analysis == null) return "—";
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(?i)(?:^|\\s)" + java.util.regex.Pattern.quote(field) + "\\s*:\\s*([^\\s]+)")
                .matcher(analysis);
        return matcher.find() ? matcher.group(1) : "—";
    }

    /** Depth followed by selective depth, with no separate SelDepth display row. */
    private String combinedDepthDisplay(String analysis) {
        String depth = analysisField(analysis, "Depth");
        String selectiveDepth = analysisField(analysis, "SelDepth");
        String combined;
        if (depth.equals("—") || depth.indexOf('/') >= 0) combined = depth;
        else combined = selectiveDepth.equals("—") || selectiveDepth.equals(depth)
                ? depth : depth + "/" + selectiveDepth;
        // Keep the two depth numbers together when a clock is adjacent in a narrow row.
        return combined.replace("/", "\u2060/\u2060");
    }

    /** Normalize any engine, replay or PGN string before displaying it. */
    private String combineDepthFields(String value) {
        if (value == null || value.isEmpty()) return value == null ? "" : value;
        String depth = analysisField(value, "Depth");
        String combined = combinedDepthDisplay(value);
        if (!depth.equals("—")) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?i)(\\bDepth\\s*:\\s*)[^\\s]+")
                    .matcher(value);
            if (matcher.find()) {
                StringBuffer result = new StringBuffer();
                matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(matcher.group(1) + combined));
                matcher.appendTail(result);
                value = result.toString();
            }
        }
        return value.replaceAll("(?i)\\s+SelDepth\\s*:\\s*[^\\s]+", "");
    }

    /** PV only: never add a visible 'PV' or 'Variation' heading. */
    private String extractAnalysisVariation(String analysis) {
        if (analysis == null || analysis.trim().isEmpty()) return "—";
        if (analysis.trim().equalsIgnoreCase("Book.")) return "Book.";
        java.util.regex.Matcher inlinePv = java.util.regex.Pattern.compile(
                "(?i)\\bPV\\s*:\\s*(.*?)(?=\\s+(?:EngineWDL|WhiteWDL):|$)").matcher(analysis);
        if (inlinePv.find() && !inlinePv.group(1).trim().isEmpty()) return inlinePv.group(1).trim();
        String[] lines = analysis.split("\\r?\\n");
        StringBuilder variation = new StringBuilder();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.regionMatches(true, 0, "Engine WDL:", 0, 11) ||
                    line.regionMatches(true, 0, "WhiteWDL:", 0, 9)) continue;
            line = line.replaceFirst("(?i)^PV\\s*:?\\s*", "");
            if (variation.length() > 0) variation.append('\n');
            variation.append(line);
        }
        return variation.length() == 0 ? "—" : variation.toString();
    }

    /** Expanded Main Board: Eval, combined Depth, NPS and Nodes. */
    private String formatMainBoardEngineOutput(String analysis) {
        // Two spaces separate the compact metric fields, matching the requested Main Board format.
        String header = "Eval: " + analysisField(analysis, "Eval") +
                "  Depth: " + combinedDepthDisplay(analysis) +
                "  Nodes: " + analysisField(analysis, "Nodes") +
                "  NPS: " + analysisField(analysis, "NPS");
        String variation = extractAnalysisVariation(analysis);
        return variation.equals("—") ? header : header + "\n" + variation;
    }

    private void fitMainBoardMetricsToViewport(TextView metrics) {
        if (metrics == null) return;
        float baseSizeSp = 17.0f;
        float minSizeSp = 14.5f;
        metrics.setTextSize(baseSizeSp);
        int availablePx = 0;
        android.view.ViewParent parent = metrics.getParent();
        if (parent instanceof View) availablePx = ((View)parent).getWidth();
        if (availablePx <= 0) availablePx = getResources().getDisplayMetrics().widthPixels - dp(12);
        float textWidthPx = metrics.getPaint().measureText(metrics.getText().toString());
        if (textWidthPx > availablePx && availablePx > 0) {
            float fittedSize = Math.max(minSizeSp, baseSizeSp * availablePx / textWidthPx);
            metrics.setTextSize(fittedSize);
        }
    }

    private void setMainBoardEngineOutput(TextView metrics, TextView variation, String analysis) {
        if (metrics != null) {
            metrics.setText(formatMainBoardEngineOutput(analysis).split("\\n", 2)[0]);
            fitMainBoardMetricsToViewport(metrics);
        }
        if (variation != null) variation.setText(extractAnalysisVariation(analysis));
    }

    /** Mirror Board metric column: four fixed rows with selective depth appended to Depth. */
    private String formatMirrorMetrics(String analysis) {
        return "Eval: " + analysisField(analysis, "Eval") +
                "\nDepth: " + combinedDepthDisplay(analysis) +
                "\nNPS: " + analysisField(analysis, "NPS") +
                "\nNodes: " + analysisField(analysis, "Nodes");
    }

    private String extractMirrorMoveVariation(String analysis) {
        String variation = extractAnalysisVariation(analysis);
        // TournamentRunner prefixes its formatted SAN line with "eval +x.xx".
        // Mirror Board's PV area is for moves only, so remove that display prefix.
        variation = variation.replaceFirst("(?i)^eval\\s+[+-]?(?:M\\d+|\\d+(?:\\.\\d+)?)\\s*", "").trim();
        // External analysis feeds can include a bare initial move index ("1 ")
        // before the SAN PV. It is metadata, not part of the variation display.
        variation = variation.replaceFirst("^1\\s+(?=[a-hKQRBN0-9])", "").trim();
        return variation.isEmpty() ? "—" : variation;
    }

    private void updateMirrorEngineOutputDisplays(String whiteAnalysis, String blackAnalysis) {
        if (mirrorBlackAnalysisText != null) mirrorBlackAnalysisText.setText(formatMirrorMetrics(blackAnalysis));
        if (mirrorWhiteAnalysisText != null) mirrorWhiteAnalysisText.setText(formatMirrorMetrics(whiteAnalysis));
        if (mirrorBlackPvText != null) mirrorBlackPvText.setText(extractMirrorMoveVariation(blackAnalysis));
        if (mirrorWhitePvText != null) mirrorWhitePvText.setText(extractMirrorMoveVariation(whiteAnalysis));
        // During a read-only replay, subtly emphasize the engine belonging to the
        // side to move at the selected ply. Live mode restores both panels fully.
        float whiteAlpha = 1.0f, blackAlpha = 1.0f;
        if (mirrorReplayMode) {
            ArrayList<Position> positions = currentMirrorPositions();
            int ply = Math.max(0, Math.min(mirrorDisplayedPly(), positions.size() - 1));
            if (!positions.isEmpty()) {
                boolean whiteToMove = positions.get(ply).whiteMove;
                whiteAlpha = whiteToMove ? 1.0f : 0.72f;
                blackAlpha = whiteToMove ? 0.72f : 1.0f;
            }
        }
        if (mirrorWhiteOutputPanel != null) mirrorWhiteOutputPanel.setAlpha(whiteAlpha);
        if (mirrorBlackOutputPanel != null) mirrorBlackOutputPanel.setAlpha(blackAlpha);
    }

    /** Coalesce analysis callbacks so a busy UI processes only the newest line per side. */
    private void postEngineAnalysisUpdate(boolean white, String analysis) {
        if (white) pendingWhiteEngineAnalysisUi.set(analysis);
        else pendingBlackEngineAnalysisUi.set(analysis);
        if (engineAnalysisUiUpdatePosted.compareAndSet(false, true))
            uiHandler.post(this::drainEngineAnalysisUpdates);
    }

    private void drainEngineAnalysisUpdates() {
        String white = pendingWhiteEngineAnalysisUi.getAndSet(null);
        String black = pendingBlackEngineAnalysisUi.getAndSet(null);
        if (white != null) applyEngineAnalysisUpdate(true, white);
        if (black != null) applyEngineAnalysisUpdate(false, black);
        engineAnalysisUiUpdatePosted.set(false);
        if ((pendingWhiteEngineAnalysisUi.get() != null || pendingBlackEngineAnalysisUi.get() != null) &&
                engineAnalysisUiUpdatePosted.compareAndSet(false, true))
            uiHandler.post(this::drainEngineAnalysisUpdates);
    }

    private String firstPrincipalVariation(String analysis) {
        if (analysis == null || analysis.isEmpty()) return "";
        String[] lines = analysis.split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.toLowerCase(java.util.Locale.US).startsWith("engine wdl:")) continue;
            line = line.replaceFirst("^\\d+\\.{1,3}\\s*", "");
            String[] tokens = line.split("\\s+");
            for (String token : tokens) {
                if (token.matches("(?i)^(?:[KQRBN]?[a-h]?[1-8]?x?[a-h][1-8](?:=[QRBN])?[+#]?|O-O(?:-O)?[+#]?|[a-h][1-8][a-h][1-8][qrbn]?)$"))
                    return token;
            }
        }
        return "";
    }

    private String latestTournamentAnalysis(boolean white) {
        String pending = (white ? pendingWhiteEngineAnalysisUi : pendingBlackEngineAnalysisUi).get();
        if (pending != null && !pending.trim().isEmpty()) return pending;
        return white ? lastWhiteAnalysis : lastBlackAnalysis;
    }

    private static final java.util.regex.Pattern NATIVE_WDL_PATTERN = java.util.regex.Pattern.compile(
            "(?i)Engine\\s+WDL:\\s*W\\s*:?\\s*([0-9]+(?:\\.[0-9]+)?)%?\\s+D\\s*:?\\s*([0-9]+(?:\\.[0-9]+)?)%?\\s+L\\s*:?\\s*([0-9]+(?:\\.[0-9]+)?)%?");

    /** WDL is a live, engine-native metric shown only in the Tournament Main Board panels. */
    private void updateTournamentMainWdl(boolean white, String analysis) {
        TextView target = mainBoardSharedOutputTop != null ? blackWdlText :
                (white ? whiteWdlText : blackWdlText);
        if (target == null) return;
        if (mainBoardSharedOutputTop != null && whiteWdlText != null)
            whiteWdlText.setVisibility(View.GONE);
        java.util.regex.Matcher matcher = NATIVE_WDL_PATTERN.matcher(analysis == null ? "" : analysis);
        if (!matcher.find()) {
            target.setText("");
            target.setVisibility(View.GONE);
            return;
        }
        String line = "W: " + matcher.group(1) + "   D: " + matcher.group(2) + "   L: " + matcher.group(3);
        target.setText(line);
        target.setVisibility(View.VISIBLE);
        if (mainBoardSharedOutputTop != null) {
            if (whiteWdlText != null) whiteWdlText.setVisibility(View.GONE);
        }
    }

    private void clearTournamentMainWdl() {
        if (whiteWdlText != null) { whiteWdlText.setText(""); whiteWdlText.setVisibility(View.GONE); }
        if (blackWdlText != null) { blackWdlText.setText(""); blackWdlText.setVisibility(View.GONE); }
    }

    private void refreshTournamentMainWdlFromCache() {
        if (mainBoardSharedOutputTop != null) {
            updateTournamentMainWdl(mainBoardSharedActiveWhite,
                    mainBoardSharedActiveWhite ? lastWhiteAnalysis : lastBlackAnalysis);
        } else {
            updateTournamentMainWdl(true, lastWhiteAnalysis);
            updateTournamentMainWdl(false, lastBlackAnalysis);
        }
    }

    private void applyEngineAnalysisUpdate(boolean white, String analysis) {
        TextView target = isMirrorShowing() ? null : (white ? whiteAnalysisText : blackAnalysisText);
        if (white) lastWhiteAnalysis = analysis; else lastBlackAnalysis = analysis;
        // Keep the newest existing search snapshot for the move the current side is
        // about to make. The graph reads this cache; no additional search is started.
        if (!mirrorReplayMode && !mirrorBranchMode && runner != null && !runner.isFinished() &&
                mirrorLiveHistoryInitialized && mirrorPly < 0 && white == currentWhiteToMove) {
            int samplePly = mirrorLiveSanMoves.size() + 1;
            while (mirrorLiveAnalysisByPly.size() <= samplePly) mirrorLiveAnalysisByPly.add("");
            mirrorLiveAnalysisByPly.set(samplePly, analysis == null ? "" : analysis);
        }
        if (!mirrorReplayMode) {
            updateMirrorEngineOutputDisplays(lastWhiteAnalysis, lastBlackAnalysis);
            updateMirrorEvaluationBars(lastWhiteAnalysis, lastBlackAnalysis);
        }
        if (isMirrorShowing() && mirrorEngineOutputHidden) updateMirrorFocusPanels();
        if (mainBoardSharedOutputTop != null) {
            mainBoardSharedActiveWhite = white;
            setMainBoardEngineOutput(blackAnalysisText, blackAnalysisPvText, analysis);
            updateMainOutputTitles();
        } else if (target != null) {
            setMainBoardEngineOutput(target, white ? whiteAnalysisPvText : blackAnalysisPvText, analysis);
        }
        refreshTournamentMainWdlFromCache();
        updateExpandedEngineMetrics();
        updateExpandedMoveHints();
        scheduleMirrorGraphRefreshAfterAnalysis();
    }

    /** Refresh the visible live graph at most once per second from existing engine callbacks. */
    private void scheduleMirrorGraphRefreshAfterAnalysis() {
        if (!isMirrorShowing() || mirrorReplayMode || mirrorBranchMode || runner == null || runner.isFinished() ||
                mirrorSelectedTab != 1 || mirrorStandingsSubTab != 1 || mirrorGraphRefreshRunnable != null)
            return;
        Runnable refresh = () -> {
            mirrorGraphRefreshRunnable = null;
            if (!isMirrorShowing() || mirrorReplayMode || mirrorBranchMode || runner == null || runner.isFinished() ||
                    mirrorSelectedTab != 1 || mirrorStandingsSubTab != 1) return;
            int previousScrollY = mirrorScroll == null ? 0 : mirrorScroll.getScrollY();
            invalidateMirrorStandingsSubCache(1);
            invalidateMirrorSectionCache(1);
            renderMirrorSection();
            updateMirrorGraphScrollSpacer();
            if (mirrorScroll != null) mirrorScroll.post(() -> mirrorScroll.scrollTo(0, previousScrollY));
        };
        mirrorGraphRefreshRunnable = refresh;
        uiHandler.postDelayed(refresh, 1000L);
    }

    private void launchPreparedTournament(final TournamentManager tournament,
                                          List<TournamentManager.Standing> preparedStandings) {
        // Standings were prepared on the background tournament-preparation thread.
        // Do not repeat a full schedule scan on the UI thread at launch.
        boolean standingsAlreadyPrepared = activeTournamentManager == tournament && !mirrorRanks.isEmpty();
        savedTournamentReviewMode = false;
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
        if (playPauseButton != null) playPauseButton.setEnabled(true);
        if (mirrorPauseButton != null) mirrorPauseButton.setEnabled(true);
        if (!standingsAlreadyPrepared)
            refreshMirrorRanks(preparedStandings == null ? Collections.emptyList() : preparedStandings);
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
                    mirrorHeaderShowsTitle = false;
                    if (mirrorHeaderText != null) mirrorHeaderText.setText(mirrorHeaderLabel());
                    statusText.setText("Game " + currentGameNumber + "/" + currentGameTotal + " " + tournament.title());
                    if (openingStartInput != null && tournament.repeatOpening()) {
                        // Paired color-reversal games use one PGN opening per pair:
                        // games 1-2 use start, games 3-4 use start+1, etc.
                        int openingNumber = tournament.openingStartIndex() + Math.max(0, pairing.index / 2);
                        openingStartInput.setText(String.valueOf(openingNumber));
                    }
                    if (mirrorAnalysisEngine != null) { try { mirrorAnalysisEngine.stopSearch(); } catch (RuntimeException ignored) { } }
                    mirrorReplayPgn = "";
                    currentPgnMoves = "";
                    pendingWhiteEngineAnalysisUi.set(null);
                    pendingBlackEngineAnalysisUi.set(null);
                    lastWhiteAnalysis = "Book."; lastBlackAnalysis = "Book."; mirrorLastMoveTimeMs = 0;
                    clearTournamentMainWdl();
                    updateMirrorEvaluationBars(lastWhiteAnalysis, lastBlackAnalysis);
                    mirrorLatestAnalysisOutput = "Select an engine, then turn analysis on.";
                    updateMirrorShortcutAnalysisState();
                    mirrorLiveHistoryRebuildGeneration++;
                    mirrorLiveHistoryRebuildPending = false;
                    mirrorLiveSanMoves.clear(); mirrorLiveComments.clear(); mirrorLiveAnalysisByPly.clear();
                    mirrorBranchMode = false; mirrorBranchRoot = mirrorBranchCurrent = null;
                    mirrorBranchBasePositions.clear(); mirrorBranchBaseMoves.clear();
                    mirrorNavigationMode = false;
                    if (mirrorPrimaryControls != null) mirrorPrimaryControls.setVisibility(View.VISIBLE);
                    if (mirrorNavigationControls != null) mirrorNavigationControls.setVisibility(View.GONE);
                    mirrorAnalysisHints.clear(); mirrorAnalysisHintsByPly.clear();
                    if (mirrorBoard != null) {
                        mirrorBoard.setClickable(false);
                        mirrorBoard.setBranchMoves(null);
                        mirrorBoard.setMoveHints(null);
                    }
                    // A new pairing changes the Moves and Schedule sections only.
                    // Keep standings/graph/crosstable panel caches and defer rebuild
                    // of the visible section until after engine startup is dispatched.
                    invalidateMirrorSectionCache(0);
                    invalidateMirrorSectionCache(2);
                    invalidateMirrorMovesSubCache(0);
                    mirrorPositions.clear();
                    mirrorLiveHistoryInitialized = false;
                    if (!mirrorReplayMode) mirrorPly = -1;
                    latestPosition = standardStartPosition();
                    mirrorPositions.add(new Position(latestPosition));
                    mirrorNavigatedLastMove = null;
                    if (tournamentBoard != null) {
                        tournamentBoard.setPosition(new Position(latestPosition));
                        // The board may have been attached while the layout was hidden;
                        // force the first draw so the starting pieces appear immediately.
                        tournamentBoard.requestLayout();
                        tournamentBoard.invalidate();
                    }
                    if (mirrorBoard != null) {
                        mirrorBoard.setPosition(new Position(latestPosition));
                        updateMirrorLastMove(0, null);
                    }
                    updateMirrorNavigationButtons();
                    tournamentVariationHints.clear();
                    tournamentVariationPositionHash = Long.MIN_VALUE;
                    applyTournamentVariationHints();
                    if (tournamentBoard != null) { tournamentBoard.setSelection(-1); tournamentBoard.setLastMove(null); }
                    if (mirrorBoard != null) { mirrorBoard.setSelection(-1); mirrorBoard.setLastMove(null); }
                    moveStatusText.setText("PGN: —");
                    if (openingText != null) openingText.setText("Opening: Starting position");
                    if (whiteMoveTimeText != null) whiteMoveTimeText.setText("—");
                    if (blackMoveTimeText != null) blackMoveTimeText.setText("—");
                    currentWhiteName = white;
                    currentBlackName = black;
                    mainBoardSharedOutputTop = null;
                    mainBoardSharedActiveWhite = true; // Every new game starts from White to move.
                    currentBoardWhiteScore = currentBoardBlackScore = "";
                    applyMainBoardAnalysisPositions();
                    currentWhiteEngineId = pairing.whiteEngineId;
                    currentBlackEngineId = pairing.blackEngineId;
                    updateTurnNameLabels();
                    updateMirrorRankedNames();
                    if (!mirrorReplayMode && isMirrorShowing() &&
                            (mirrorSelectedTab == 0 || mirrorSelectedTab == 2))
                        scheduleDeferredMirrorSectionRefresh(tournament, 1100L);
                    // Attach/layout the Main Board first, then re-apply the current
                    // position on the view queue. This avoids the initial blank-board
                    // frame seen when the tournament starts from a previously hidden view.
                    if (tournamentBoard != null) tournamentBoard.post(() -> {
                        if (latestPosition != null) tournamentBoard.setPosition(new Position(latestPosition));
                        tournamentBoard.requestLayout();
                        tournamentBoard.invalidate();
                    });
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
                final long callbackPostedNs = System.nanoTime();
                runOnUiThread(() -> {
                    long uiWorkStartedNs = System.nanoTime();
                    long uiDelayMs = Math.max(0L, (uiWorkStartedNs - callbackPostedNs) / 1_000_000L);
                    long historyWorkMs = 0L;
                    long mirrorSyncWorkMs = 0L;
                    if (uiDelayMs >= 250L)
                        android.util.Log.w("CET-UiLag", "Position callback delayed by " + uiDelayMs + " ms");
                    String analysisForLastMove = null;
                    Position previousPosition = mirrorPositions.isEmpty() ? null : new Position(mirrorPositions.get(mirrorPositions.size() - 1));
                    if (lastMove != null && previousPosition != null && !samePosition(previousPosition, position)) {
                        try {
                            Position expected = new Position(previousPosition);
                            expected.makeMove(lastMove, new org.petero.droidfish.gamelogic.UndoInfo());
                            if (samePosition(expected, position)) {
                                mirrorLiveSanMoves.add(TextIO.moveToString(previousPosition, lastMove, false, false));
                                boolean moverWhite = !whiteToMove;
                                analysisForLastMove = latestTournamentAnalysis(moverWhite);
                            }
                        } catch (RuntimeException ignored) { }
                    }
                    latestPosition = new Position(position);
                    tournamentVariationHints.clear();
                    tournamentVariationPositionHash = Long.MIN_VALUE;
                    if (tournamentBoard != null) tournamentBoard.setMoveHints(null);
                    currentWhiteToMove = whiteToMove;
                    if (mainBoardSharedOutputTop != null) {
                        mainBoardSharedActiveWhite = whiteToMove;
                        setMainBoardEngineOutput(blackAnalysisText, blackAnalysisPvText,
                                whiteToMove ? lastWhiteAnalysis : lastBlackAnalysis);
                        updateMainOutputTitles();
                        refreshTournamentMainWdlFromCache();
                    }
                    updateTurnNameLabels();
                    if (pgnMoves != null) currentPgnMoves = pgnMoves;
                    boolean rebuildMirrorHistory = !mirrorLiveHistoryInitialized || pgnMoves != null;
                    long historyStartedNs = System.nanoTime();
                    ensureMirrorHistoryMatchesLivePgn(position, rebuildMirrorHistory, moveToHighlight, previousPosition);
                    historyWorkMs = (System.nanoTime() - historyStartedNs) / 1_000_000L;
                    mirrorLiveHistoryInitialized = true;
                    if (analysisForLastMove != null) {
                        int analysisPly = mirrorPositions.size() - 1;
                        if (analysisPly >= 0) {
                            while (mirrorLiveAnalysisByPly.size() <= analysisPly)
                                mirrorLiveAnalysisByPly.add("");
                            mirrorLiveAnalysisByPly.set(analysisPly, analysisForLastMove);
                        }
                    }
                    // Incremental live callbacks do not carry a full PGN snapshot.
                    // Preserve the mover's remaining clock in the live Mirror move
                    // comment as well; completed PGN export stores the same value in
                    // GameTree.Node and serializes it as the standard [%clk] field.
                    if (pgnMoves == null && lastMove != null && !mirrorLiveSanMoves.isEmpty() &&
                            (whiteTimeMs > 0L || blackTimeMs > 0L)) {
                        int commentIndex = mirrorLiveSanMoves.size() - 1;
                        while (mirrorLiveComments.size() <= commentIndex) mirrorLiveComments.add("");
                        String comment = mirrorLiveComments.get(commentIndex);
                        if ((comment == null || comment.trim().isEmpty()) && analysisForLastMove != null)
                            comment = analysisForLastMove;
                        if (findCommentValue(comment, "Clock|clk").isEmpty()) {
                            long moverRemainingMs = whiteToMove ? blackTimeMs : whiteTimeMs;
                            String clockField = "Clock: " + formatPgnClock(moverRemainingMs);
                            comment = comment == null || comment.trim().isEmpty()
                                    ? clockField : comment.trim() + " " + clockField;
                        }
                        mirrorLiveComments.set(commentIndex, comment == null ? "" : comment);
                    }
                    if (moveToHighlight != null) mirrorNavigatedLastMove = moveToHighlight;
                    if (expandedMainBoard != null) expandedMainBoard.setLastMove(moveToHighlight);
                    if (lastMove != null && previousPosition != null && !samePosition(previousPosition, position)) {
                        try {
                            Position expected = new Position(previousPosition);
                            expected.makeMove(lastMove, new org.petero.droidfish.gamelogic.UndoInfo());
                            if (samePosition(expected, position)) {
                                int animationMs = moveAnimationDurationMs();
                                if (animationMs > 0 && mirrorBoard != null && isMirrorShowing() && !mirrorReplayMode && mirrorPly < 0)
                                    mirrorBoard.setAnimMove(previousPosition, lastMove, true, animationMs);
                                if (animationMs > 0 && tournamentBoard != null && !isMirrorShowing())
                                    tournamentBoard.setAnimMove(previousPosition, lastMove, true, animationMs);
                                if (animationMs > 0 && expandedMainBoard != null)
                                    expandedMainBoard.setAnimMove(previousPosition, lastMove, true, animationMs);
                            }
                        } catch (RuntimeException ignored) { }
                    }
                    if (isMirrorShowing() && !mirrorReplayMode && mirrorPly < 0) {
                        long mirrorSyncStartedNs = System.nanoTime();
                        syncMirrorFromLive();
                        mirrorSyncWorkMs = (System.nanoTime() - mirrorSyncStartedNs) / 1_000_000L;
                    }
                    invalidateMirrorMovesSubCache(0);
                    invalidateMirrorMovesSubCache(2);
                    invalidateMirrorSectionCache(0);
                    invalidateMirrorStandingsSubCache(1);
                    invalidateMirrorSectionCache(1);
                    if (isMirrorShowing() && !mirrorReplayMode && (mirrorSelectedTab == 0 ||
                            (mirrorSelectedTab == 1 && mirrorStandingsSubTab == 1)))
                        scheduleMirrorSectionRefreshAfterMove(true);
                    if (!mirrorReplayMode && mirrorAnalysisOn && mirrorAnalysisEngineActive && mirrorPly < 0) startMirrorAnalysis();
                    if (tournamentBoard != null) {
                        tournamentBoard.setPosition(position);
                        tournamentBoard.setSelection(-1);
                        tournamentBoard.setLastMove(moveToHighlight);
                    }
                    updateMirrorNavigationButtons();
                    whiteClockText.setText(formatClock(whiteTimeMs));
                    blackClockText.setText(formatClock(blackTimeMs));
                    whiteClockText.setAlpha(whiteToMove ? 1.0f : 0.72f);
                    blackClockText.setAlpha(whiteToMove ? 0.72f : 1.0f);
                    updateExpandedMainBoard(true);
                    int livePlyCount = mirrorLiveHistoryInitialized ? mirrorLiveSanMoves.size() : currentMirrorSanMoves(null).size();
                    moveStatusText.setText("PGN: " + livePlyCount + " plies");
                    long callbackWorkMs = (System.nanoTime() - uiWorkStartedNs) / 1_000_000L;
                    if (callbackWorkMs >= 16L || historyWorkMs >= 8L || mirrorSyncWorkMs >= 8L) {
                        TournamentDiagnostics.record("ANIMATION", "position_callback_ui_work",
                                "uiDelayMs=" + uiDelayMs + " uiWorkMs=" + callbackWorkMs +
                                " historyMs=" + historyWorkMs + " mirrorSyncMs=" + mirrorSyncWorkMs +
                                " livePly=" + livePlyCount + " fullPgnSnapshot=" + (pgnMoves != null) +
                                " mirrorVisible=" + isMirrorShowing() + " expandedVisible=" +
                                (expandedMainDialog != null && expandedMainDialog.isShowing()));
                    }
                });
            }
            @Override public void onEngineAnalysis(boolean white, String analysis) {
                postEngineAnalysisUpdate(white, analysis);
            }
            @Override public void onEngineVariationHints(boolean white, long positionHash, ArrayList<Move> hints) {
                final ArrayList<Move> snapshot = hints == null ? new ArrayList<>() : new ArrayList<>(hints);
                runOnUiThread(() -> {
                    if (latestPosition == null || latestPosition.zobristHash() != positionHash || latestPosition.whiteMove != white) return;
                    tournamentVariationPositionHash = positionHash;
                    tournamentVariationHints.clear();
                    tournamentVariationHints.addAll(snapshot);
                    applyTournamentVariationHints();
                });
            }
            @Override public void onReviewPosition(Position position, int ply, int totalPlies,
                                                   String whiteAnalysis, String blackAnalysis, long moveTimeMs) {
                runOnUiThread(() -> {
                    clearTournamentMainWdl();
                    latestPosition = new Position(position);
                    tournamentVariationHints.clear();
                    tournamentVariationPositionHash = Long.MIN_VALUE;
                    tournamentBoard.setPosition(position);
                    tournamentBoard.setMoveHints(null);
                    Move reviewLastMove = null;
                    if (ply > 0 && ply < mirrorPositions.size())
                        reviewLastMove = findMoveBetweenPositions(mirrorPositions.get(ply - 1), mirrorPositions.get(ply));
                    tournamentBoard.setLastMove(reviewLastMove);
                    if (mainBoardSharedOutputTop != null) {
                        mainBoardSharedActiveWhite = position.whiteMove;
                        setMainBoardEngineOutput(blackAnalysisText, blackAnalysisPvText,
                                mainBoardSharedActiveWhite ? whiteAnalysis : blackAnalysis);
                        updateMainOutputTitles();
                    } else {
                        setMainBoardEngineOutput(whiteAnalysisText, whiteAnalysisPvText, whiteAnalysis);
                        setMainBoardEngineOutput(blackAnalysisText, blackAnalysisPvText, blackAnalysis);
                    }
                    moveStatusText.setText("PAUSED · Reviewing ply " + ply + "/" + totalPlies + " · Next/Previous to navigate");
                    String used = ply == 0 ? "—" : formatClockDuration(moveTimeMs);
                    if (ply > 0 && (ply % 2 == 1)) whiteMoveTimeText.setText(used); else if (ply > 0) blackMoveTimeText.setText(used);
                });
            }
            @Override public void onMoveTime(boolean whiteMoved, long moveTimeMs, String openingName) {
                runOnUiThread(() -> {
                    mirrorLastMoveTimeMs = Math.max(0, moveTimeMs);
                    TextView target = whiteMoved ? whiteMoveTimeText : blackMoveTimeText;
                    if (target != null) target.setText(moveTimeMs <= 0 ? "—" : formatClockDuration(moveTimeMs));
                    if (openingText != null) openingText.setText("Opening: " + (openingName == null || openingName.isEmpty() ? "Starting position" : openingName));
                });
            }
            @Override public void onGameResultPending(TournamentManager.Pairing pairing, TournamentManager.Outcome outcome, String reason) {
                runOnUiThread(() -> {
                    if (statusText == null || moveStatusText == null) return;
                    String resultLabel = outcome == TournamentManager.Outcome.WHITE_WIN ? "WHITE WINS" :
                            outcome == TournamentManager.Outcome.BLACK_WIN ? "BLACK WINS" :
                            outcome == TournamentManager.Outcome.DRAW ? "DRAW" : "GAME FINISHED";
                    String why = reason == null || reason.trim().isEmpty() ? "" : " · " + reason.trim();
                    String pending = "Game " + (pairing.index + 1) + " · " + resultLabel + why + " · Saving result…";
                    statusText.setText(pending);
                    moveStatusText.setText(pending);
                });
            }
            @Override public void onGameFinished(TournamentManager.Pairing pairing, TournamentManager.Outcome outcome, String pgn) {
                onGameFinished(pairing, outcome, pgn, "adjudication");
            }
            @Override public void onGameFinished(TournamentManager.Pairing pairing, TournamentManager.Outcome outcome, String pgn, String reason) {
                // The result and its PGN are already durably committed by TournamentRunner.
                // Keep full PGN reconciliation and expensive Mirror section reconstruction
                // off the immediate game-transition/UI path.
                runOnUiThread(() -> {
                    long resultUiStartedNs = System.nanoTime();
                    currentPgnMoves = pgn == null ? "" : pgn;
                    if (pgn != null && latestPosition != null)
                        scheduleCompletedMirrorHistoryRebuild(pgn, new Position(latestPosition), pairing.index);
                    switch (outcome) {
                        case WHITE_WIN: currentBoardWhiteScore = "1"; currentBoardBlackScore = "0"; break;
                        case BLACK_WIN: currentBoardWhiteScore = "0"; currentBoardBlackScore = "1"; break;
                        case DRAW: currentBoardWhiteScore = currentBoardBlackScore = "½"; break;
                        default: currentBoardWhiteScore = currentBoardBlackScore = ""; break;
                    }
                    updateTurnNameLabels();
                    updateMirrorRankedNames();
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
                    invalidateMirrorStandingsSubCache(0); invalidateMirrorStandingsSubCache(2);
                    invalidateMirrorSectionCache(1); invalidateMirrorSectionCache(2); invalidateMirrorSectionCache(3);
                    if (isMirrorShowing() && (mirrorSelectedTab == 1 || mirrorSelectedTab == 2 || mirrorSelectedTab == 3))
                        scheduleDeferredMirrorSectionRefresh(tournament, 1100L);
                    invalidateCachedSchedule(tournament);
                    if (cachedAnomalyDialog != null && cachedAnomalyDialog.isShowing() &&
                            cachedAnomalyTournament == tournament && cachedAnomalySummary != null && cachedAnomalyRows != null) {
                        final TournamentManager anomalyTournament = tournament;
                        uiHandler.postDelayed(() -> {
                            if (cachedAnomalyDialog != null && cachedAnomalyDialog.isShowing() &&
                                    cachedAnomalyTournament == anomalyTournament && cachedAnomalySummary != null && cachedAnomalyRows != null)
                                refreshAnomalyRows(anomalyTournament, cachedAnomalySummary, cachedAnomalyRows, cachedAnomalyDialog);
                        }, 900L);
                    }
                    if (tournamentTitleText != null) tournamentTitleText.setText(tournament.title());
                    if (!tournament.externalSaveError().isEmpty())
                        statusText.setText(statusText.getText() + " · JSON mirror: " + tournament.externalSaveError());
                    long resultUiMs = (System.nanoTime() - resultUiStartedNs) / 1_000_000L;
                    TournamentDiagnostics.record("UI", "game_finished_result_ui_dispatched",
                            "game=" + pairing.index + " elapsedMs=" + resultUiMs +
                                    " mirrorTab=" + mirrorSelectedTab + " mirrorVisible=" + isMirrorShowing());
                    if (resultUiMs >= 50L)
                        android.util.Log.w("CET-TournamentUI", "Game result UI work took " + resultUiMs + "ms");
                });
                TOURNAMENT_EXPORT_EXECUTOR.execute(() -> {
                    Exception exportError = null;
                    String standingsSummary = null;
                    HashMap<String, Integer> ranksAfterGame = null;
                    try { saveTournamentPgnAutomatically(tournament); }
                    catch (Exception error) { exportError = error; }
                    try {
                        List<TournamentManager.Standing> completedStandings = tournament.standings();
                        List<TournamentManager.Engine> completedEngines = tournament.engines();
                        standingsSummary = formatStandings(tournament, completedStandings, completedEngines);
                        ranksAfterGame = new HashMap<>();
                        for (int i = 0; i < completedStandings.size(); i++)
                            ranksAfterGame.put(completedStandings.get(i).engineId, i + 1);
                    } catch (RuntimeException standingsError) {
                        android.util.Log.w("CET-TournamentSave", "Could not prepare standings snapshot after Game " +
                                (pairing.index + 1), standingsError);
                    }
                    final Exception savedError = exportError;
                    final String savedStandingsSummary = standingsSummary;
                    final HashMap<String, Integer> savedRanksAfterGame = ranksAfterGame;
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        if (savedStandingsSummary != null) standingsText.setText(savedStandingsSummary);
                        if (savedRanksAfterGame != null && activeTournamentManager == tournament) {
                            mirrorRanks.clear();
                            mirrorRanks.putAll(savedRanksAfterGame);
                            updateMirrorRankedNames();
                        }
                        if (savedError != null && activeTournamentManager == tournament && statusText != null)
                            statusText.setText(statusText.getText() + " · PGN auto-save failed: " + savedError.getMessage());
                    });
                });
            }
            @Override public void onTournamentFinished() {
                freezeTournamentElapsedClock();
                String finalStandings = null;
                try { finalStandings = formatStandings(tournament, tournament.standings(), tournament.engines()); }
                catch (RuntimeException standingsError) {
                    android.util.Log.w("CET-TournamentSave", "Could not prepare final standings", standingsError);
                }
                final String finalStandingsSummary = finalStandings;
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
                        if (finalStandingsSummary != null) standingsText.setText(finalStandingsSummary);
                        invalidateCachedSchedule(tournament);
                        showCupStageCheckpointDialog(tournament);
                    } else {
                        statusText.setText("Tournament complete");
                        moveStatusText.setText("TOURNAMENT COMPLETE");
                        if (finalStandingsSummary != null) standingsText.setText(finalStandingsSummary);
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
        runner.setTournamentArrowCount(tournamentArrowCount);
        startButton.setEnabled(false); resumeButton.setEnabled(false); stopButton.setEnabled(true);
        if (playPauseButton != null) playPauseButton.setText("P");
        if (tournamentTitleText != null) tournamentTitleText.setText(tournament.title());
        SharedPreferences savedBoardPrefs = getPreferences(MODE_PRIVATE);
        int savedBoardSelection = Math.max(0, Math.min(2, savedBoardPrefs.getInt("tournamentLastBoardSelection", 0)));
        int savedNewBoardReturn = Math.max(0, Math.min(1, savedBoardPrefs.getInt("tournamentNewBoardReturnScreen", 0)));
        boardSelectionActive = true;
        if (savedBoardSelection == 2) {
            expandedMainReturnScreen = savedNewBoardReturn;
            currentTournamentScreen = savedNewBoardReturn;
            setContentView(savedNewBoardReturn == 1 ? mirrorView : playView);
            showExpandedMainBoard();
            saveBoardSelectionState(2);
        } else if (savedBoardSelection == 1) {
            currentTournamentScreen = 1;
            setContentView(mirrorView);
            saveBoardSelectionState(1);
        } else {
            currentTournamentScreen = 0;
            setContentView(playView);
            saveBoardSelectionState(0);
        }
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

    /**
     * Prepare move counts for the Mirror Schedule away from the UI thread. The old
     * path reconstructed every legal position in every saved PGN while drawing
     * rows; the schedule only needs a main-line SAN token count.
     */
    private boolean ensureMirrorScheduleMoveCountsReady(TournamentManager tournament) {
        if (scheduleMoveCountCacheOwner != tournament) {
            scheduleMoveCountCache.clear();
            scheduleMoveCountCacheOwner = tournament;
            scheduleMoveCountsReady = false;
            scheduleMoveCountsBuilding = false;
            scheduleMoveCountBuildGeneration++;
        }
        if (scheduleMoveCountsReady) {
            int expectedEntries = 0;
            boolean matchesCurrentPairings = true;
            for (TournamentManager.Pairing pairing : tournament.pairings()) {
                if (pairing == null || pairing.pgn == null || pairing.pgn.isEmpty()) continue;
                expectedEntries++;
                ScheduleMoveCountCacheEntry cached = scheduleMoveCountCache.get(pairing.index);
                if (cached == null || cached.pgnReference != pairing.pgn) {
                    matchesCurrentPairings = false;
                    break;
                }
            }
            if (matchesCurrentPairings && expectedEntries == scheduleMoveCountCache.size()) return true;
            scheduleMoveCountsReady = false;
        }
        if (scheduleMoveCountsBuilding) return false;
        scheduleMoveCountsBuilding = true;
        final long generation = ++scheduleMoveCountBuildGeneration;
        final ArrayList<ScheduleMoveCountTask> tasks = new ArrayList<>();
        for (TournamentManager.Pairing pairing : tournament.pairings()) {
            if (pairing != null && pairing.pgn != null && !pairing.pgn.isEmpty())
                tasks.add(new ScheduleMoveCountTask(pairing.index, pairing.pgn));
        }
        final HashMap<Integer, ScheduleMoveCountCacheEntry> cached = new HashMap<>(scheduleMoveCountCache);
        MIRROR_PREPARE_EXECUTOR.execute(() -> {
            HashMap<Integer, ScheduleMoveCountCacheEntry> prepared = new HashMap<>();
            for (ScheduleMoveCountTask task : tasks) {
                if (generation != scheduleMoveCountBuildGeneration) break;
                ScheduleMoveCountCacheEntry prior = cached.get(task.pairingIndex);
                if (prior != null && prior.pgnReference == task.pgnReference) {
                    prepared.put(task.pairingIndex, prior);
                } else {
                    int moveCount = 0;
                    try { moveCount = countFullMoves(task.pgnReference); }
                    catch (RuntimeException ignored) { }
                    prepared.put(task.pairingIndex, new ScheduleMoveCountCacheEntry(task.pgnReference, moveCount));
                }
            }
            runOnUiThread(() -> {
                if (isFinishing() || generation != scheduleMoveCountBuildGeneration ||
                        activeTournamentManager != tournament) return;
                // Pairings can finish or receive a replacement PGN while the
                // background count is running. Never publish counts for that
                // stale snapshot; request a fresh pass instead.
                boolean stale = false;
                for (TournamentManager.Pairing pairing : tournament.pairings()) {
                    if (pairing == null) continue;
                    String currentPgn = pairing.pgn;
                    ScheduleMoveCountCacheEntry ready = prepared.get(pairing.index);
                    if (currentPgn == null || currentPgn.isEmpty()) {
                        if (ready != null) { stale = true; break; }
                    } else if (ready == null || ready.pgnReference != currentPgn) {
                        stale = true;
                        break;
                    }
                }
                if (stale) {
                    scheduleMoveCountsBuilding = false;
                    scheduleMoveCountsReady = false;
                    invalidateMirrorSectionCache(2);
                    if (cachedScheduleDialog != null && cachedScheduleDialog.isShowing() &&
                            cachedScheduleTournament == tournament) {
                        cachedScheduleDirty = true;
                        refreshCachedSchedule(tournament);
                    }
                    if (isMirrorShowing() && mirrorSelectedTab == 2) renderMirrorSection();
                    return;
                }
                scheduleMoveCountCache.clear();
                scheduleMoveCountCache.putAll(prepared);
                scheduleMoveCountsBuilding = false;
                scheduleMoveCountsReady = true;
                invalidateMirrorSectionCache(2);
                if (cachedScheduleDialog != null && cachedScheduleDialog.isShowing() && cachedScheduleTournament == tournament)
                    refreshCachedSchedule(tournament);
                if (isMirrorShowing() && mirrorSelectedTab == 2) renderMirrorSection();
            });
        });
        return false;
    }

    /** Cache completed-game move counts; schedule refreshes should not reparse every old PGN. */
    private int cachedFullMoveCount(TournamentManager tournament, TournamentManager.Pairing pairing) {
        if (scheduleMoveCountCacheOwner != tournament) {
            scheduleMoveCountCache.clear();
            scheduleMoveCountCacheOwner = tournament;
            scheduleMoveCountsReady = false;
            scheduleMoveCountsBuilding = false;
            scheduleMoveCountBuildGeneration++;
        }
        String pgn = pairing == null ? null : pairing.pgn;
        if (pgn == null || pgn.isEmpty()) return 0;
        ScheduleMoveCountCacheEntry cached = scheduleMoveCountCache.get(pairing.index);
        if (cached != null && cached.pgnReference == pgn) return cached.fullMoves;
        // A missing count is prepared asynchronously; never parse a full PGN on
        // the UI thread while opening or refreshing Schedule.
        return scheduleMoveCountsBuilding || !scheduleMoveCountsReady ? -1 : 0;
    }

    private String scheduleMoveCountLabel(TournamentManager tournament, TournamentManager.Pairing pairing) {
        int count = cachedFullMoveCount(tournament, pairing);
        return count < 0 ? "Counting moves…" : count + " Moves";
    }

    private int countFullMoves(String pgn) {
        if (pgn == null || pgn.trim().isEmpty()) return 0;
        // Count SAN plies on the actual main line, independent of the full-move
        // number embedded in a starting FEN. This avoids reporting move 58 for a
        // 31-move game that began from a FEN whose counter was already at 28.
        int plies = org.petero.droidfish.tournament.PgnOpeningBook.mainLinePlyCount(pgn);
        if (plies > 0) return (plies + 1) / 2;
        // Compatibility fallback for PGNs without parseable movetext.
        java.util.regex.Matcher plyCount = java.util.regex.Pattern.compile("(?im)^\\s*\\[PlyCount\\s+\"(\\d+)\"\\]").matcher(pgn);
        if (plyCount.find()) {
            try { return (Integer.parseInt(plyCount.group(1)) + 1) / 2; }
            catch (NumberFormatException ignored) { }
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:^|\\s)(\\d+)\\.").matcher(pgn);
        int max = 0; while (m.find()) try { max = Math.max(max, Integer.parseInt(m.group(1))); } catch (NumberFormatException ignored) { }
        return max;
    }

    private void stopAndSaveTournament() {
        final TournamentRunner oldRunner = runner;
        if (oldRunner == null) { setContentView(playView); return; }
        oldRunner.pause();
        if (stopButton != null) stopButton.setEnabled(false);
        if (statusText != null) statusText.setText("Saving tournament in background…");
        TOURNAMENT_LIFECYCLE_EXECUTOR.execute(() -> {
            Exception failure = null;
            TournamentManager activeTournament = activeTournamentManager;
            try {
                // Do not race the just-finished game's PGN/result writer with this
                // explicit stop/save transaction. Waiting happens only on this worker.
                oldRunner.awaitPendingGameFinalization();
                if (activeTournament == null) activeTournament = TournamentManager.restore(TournamentActivity.this);
                activeTournamentManager = activeTournament;
                freezeTournamentElapsedClock();
                activeTournament.save();
                saveTournamentPgnAutomatically(activeTournament);
                oldRunner.stop();
            } catch (Exception e) {
                failure = e;
            }
            final TournamentManager savedTournament = activeTournament;
            final Exception saveFailure = failure;
            runOnUiThread(() -> {
                if (saveFailure == null) {
                    if (runner == oldRunner) runner = null;
                    if (savedTournament != null) activeTournamentManager = savedTournament;
                    if (startButton != null) startButton.setEnabled(true);
                    if (resumeButton != null) resumeButton.setEnabled(true);
                    if (stopButton != null) stopButton.setEnabled(false);
                    if (statusText != null) statusText.setText("Tournament stopped and saved. Use Resume Saved Tournament to continue.");
                    Toast.makeText(TournamentActivity.this, "Tournament saved", Toast.LENGTH_LONG).show();
                    boardSelectionActive = false;
                    setContentView(setupView);
                } else {
                    if (stopButton != null) stopButton.setEnabled(true);
                    if (statusText != null) statusText.setText("Tournament is paused; save failed.");
                    Toast.makeText(TournamentActivity.this,
                            "Could not save tournament: " + saveFailure.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        });
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
        if (blockSavedTournamentReviewEdit()) return;
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
            updateExpandedBoardControls();
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
        updateExpandedBoardControls();
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
        activityLeaving = true;
        activityVisible = false;
        TournamentDiagnostics.record("ACTIVITY", "on_pause", "board=" + currentTournamentScreen + " newBoardDialog=" +
                (expandedMainDialog != null && expandedMainDialog.isShowing()));
        if (boardSelectionActive) saveBoardSelectionState(expandedMainDialog != null && expandedMainDialog.isShowing() ? 2 : currentTournamentScreen);
        saveExpandedPanelLayoutPreferences();
        if (mirrorAnalysisEngine != null) { try { mirrorAnalysisEngine.stopSearch(); } catch (RuntimeException ignored) { } }
        saveSetup();
        // Backgrounding the Activity is not a tournament pause request. Keep the
        // runner, clocks, engines and durable result pipeline alive while the user
        // checks another app. Explicit Pause/Back/Stop actions remain authoritative.
        TournamentDiagnostics.record("ACTIVITY", "tournament_continues_in_background",
                "runnerPresent=" + (runner != null) + " paused=" + (runner != null && runner.isPaused()) +
                " finished=" + (runner != null && runner.isFinished()));
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        activityLeaving = false;
        activityVisible = true;
        TournamentDiagnostics.record("ACTIVITY", "on_resume", "board=" + currentTournamentScreen);
        restoreBoardSelectionState();
        if (mirrorAnalysisOn && mirrorAnalysisEngineActive) startMirrorAnalysis();
        // Do not auto-resume the tournament when returning from Home/Recents. The user explicitly taps Play.
        if (runner != null && runner.isPaused() && playPauseButton != null) playPauseButton.setText("Play");
    }

    @Override protected void onDestroy() {
        stopMirrorAutoPlay();
        stopMirrorAnalysisEngineOnly();
        stopGameViewerAnalysis();
        if (mirrorLiveSectionRefreshRunnable != null) {
            uiHandler.removeCallbacks(mirrorLiveSectionRefreshRunnable);
            mirrorLiveSectionRefreshRunnable = null;
        }
        if (mirrorGraphRefreshRunnable != null) {
            uiHandler.removeCallbacks(mirrorGraphRefreshRunnable);
            mirrorGraphRefreshRunnable = null;
        }
        if (expandedMovesRefreshRunnable != null) {
            uiHandler.removeCallbacks(expandedMovesRefreshRunnable);
            expandedMovesRefreshRunnable = null;
        }
        uiHandler.removeCallbacks(clockRefresh);
        if (uiStallWatchdog != null) {
            uiStallWatchdog.interrupt();
            uiStallWatchdog = null;
        }
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
    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
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
