package com.gathin.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ChangedPackages;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.SoundEffectConstants;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;


/**
 * tvOS style home: black, a pinned dock row, an app grid, and one glass highlight that springs between tiles.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {

    private static final int LAUNCHER_SETTINGS_REQUEST = 42;
    /** SourceViewActivity answering RESULT_FIRST_USER when the set would not give it a surface. */
    private static final int SOURCE_VIEW_REQUEST = 43;
    /** How long the normal focus restore gets before ensureFocus() steps in (see onWindowFocusChanged). */
    private static final long FOCUS_NET_MS = 250;
    /** Sent by HomeKeyService (settings_key_panel): open Launcher settings from here so Home rebuilds when they close. */
    static final String ACTION_LAUNCHER_SETTINGS = "com.gathin.tvhome.action.LAUNCHER_SETTINGS";
    /** Sent by HomeKeyService: a settings action to open in Home's own task. */
    static final String ACTION_OPEN_SETTINGS = "com.gathin.tvhome.action.OPEN_SETTINGS";
    static final String EXTRA_SETTINGS_ACTION = "settings_action";
    /** The system input switcher, forwarded here by InputsRequestActivity while handle_view_inputs is on. */
    static final String ACTION_VIEW_INPUTS = "com.android.tv.action.VIEW_INPUTS";
    /**
     * Sent by HomeKeyService: the Le button was pressed, toggle the app library. Handled in the service
     * too so the button works inside other apps, where the focused app would take the key for its own menu.
     */
    static final String ACTION_TOGGLE_LIBRARY = "com.gathin.tvhome.action.TOGGLE_LIBRARY";
    /**
     * Scancode of the LeEco remote's Le button after the keylayout patch (dbg/fixkl.sh): 189 is the
     * Bluetooth remote's LE key, rewritten to F5 there. The real Menu button is scancode 139 and keeps
     * opening the tile menu. 377 (LETV, the share button under Settings) is deliberately not patched:
     * it stays LETV so the firmware's own screenshot fires.
     */
    static final int LE_SCANCODE_LE = 189;
    /**
     * Scancode of the remote's voice button (the key under the share button). The firmware answers it
     * with two beeps and then does nothing: it starts com.stv.voice/.WindowService for openmic and
     * closemic, and that package is not installed on this TV. dbg/fixkl.sh rewrites it to F6, which
     * no app and no firmware hook binds, and the desktop turns it into "open the app you chose".
     * The beeps are left alone on purpose - they are the only feedback the button gives.
     */
    static final int VOICE_SCANCODE = 167;
    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    private boolean rtlAppGrid;
    private static final long MINUTE_MS = 60_000L;
    private static final long STATS_MS = 10_000L;
    /**
     * 2025-01-01. TVs without a clock battery boot at the epoch (08:00 in China) and keep it until network
     * time arrives; the clock, date and greeting stay hidden before then.
     */
    private static final long TIME_VALID_AFTER_MS = 1_735_689_600_000L;
    /**
     * View.forceHasOverlappingRendering only exists from API 29. It is a pure rendering hint (stop a view
     * from being drawn through an offscreen buffer), so on older systems the call is skipped instead of
     * crashing: the view still draws, just without that optimisation. LeTV X4-65S runs Android 6.0.
     */
    private static void noOverlappingRendering(View v) {
        if (v == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return;
        }
        v.forceHasOverlappingRendering(false);
    }
    private String liveTvPkg;
    /** Idle time on Home before ClockActivity; 0 is off (config.xml, home_idle_clock_minutes). */
    private long idleClockMs;
    private final Runnable showIdleClock = () -> startActivity(new Intent(this, ClockActivity.class));
    private String liveTvAction;
    private String liveTvInputId;
    private String inputAction;
    private String inputUriScheme;
    private String sourcePanelService;
    private String sourcePanelAction;
    private String sourcePanelExtra;
    private boolean coocaaSources;

    private AppAdapter adapter;
    private RecyclerView grid;
    private FrameLayout root;
    private WallpaperPlayer wallpaperPlayer;
    private GlassView glass;
    private View ambient;
    private TextView heroName;
    /** The top shelf: heroName, plus the cinematic shelf's "Last opened" line and dock position dots. */
    private View hero;
    private TextView heroMeta;
    private LinearLayout heroDots;
    /** Cinematic top shelf (config.xml, cinematic_home): the focused dock app's artwork behind a larger shelf. */
    private boolean cinematic;
    /** config.xml ambient_glow, overridable by a theme. Off: the glow view is gone and no fade or colour animator
     *  is started for it, so a focus move costs nothing extra on a launcher that runs all day. */
    private boolean ambientGlow;
    /** config.xml app_glow: the highlight takes the focused app's colour on every tile. */
    private boolean appGlow;
    /** config.xml glow_style: the shape setAmbient paints the glow mask in. */
    private String glowStyle;
    private ImageView heroArt;
    private static final float HERO_ART_ALPHA = 0.5f;
    /** Softened artwork per app (HeroArt, about 5 KB each), made the first time its dock tile is focused. */
    private final Map<String, Bitmap> heroArtCache = new HashMap<>();
    /** The app whose artwork heroArt shows, so focusing it again only fades it back in. */
    private String heroArtKey;
    /** The focus glow's colour while the cinematic shelf tints it, 0 for plain white glass. */
    private int glassAccent;
    private int ambientColor = -1;
    private ValueAnimator ambientAnimator;
    private boolean heroShown;
    private boolean animations;
    private boolean advancedEffects;
    private TextView greeting;
    private View clockBlock;
    private View btnInputs;
    /** config show_inputs_pill: false keeps the Inputs pill hidden even when the TV has inputs. */
    private boolean showInputsPill = true;
    private TextView btnWifi;
    /** The network pill's icon as last set, so a refresh that changes nothing does not lay the top bar out again. */
    private int wifiIcon;
    /** The pills have been filled in once; later resumes refresh them after the first frame. */
    private boolean pillsShown;
    private TextView shamsiDate;
    private TextView lunarDate;
    private TextView stats;
    private List<RaceWeekend.Race> raceCalendar;
    private View racePanel;
    private TextView raceKicker, raceHeadline, raceSessions;
    /** config.xml show_stats, overridable by a theme. Off: the line is gone and nothing is read. */
    private boolean showStats;
    /** config.xml show_hitokoto: the daily sentence, top right. Off: nothing is read from the pool. */
    private boolean showHitokoto;
    private int weatherMode;
    /** Which categories the sentence may come from (config.xml hitokoto_categories, ticked on TV). */
    private int hitokotoCategories = Sentences.ALL;
    private LinearLayout hitokotoBox;
    private TextView hitokotoText, hitokotoFrom;
    /**
     * The weather line under the clock. Everything about it is optional: no QWeather key means no
     * line at all, and a failed read keeps whatever the last successful one left. Re-read in
     * onResume like the sentence, because the city and the key are typed on the settings screen.
     */
    private LinearLayout weatherBox;
    private ImageView weatherIcon;
    private TextView weather;
    private TextView weatherExtra;
    private TextView weatherSun;
    private String weatherCity = "";
    private String weatherApiKey = "";
    private String weatherApiHost = Config.DEFAULT_WEATHER_HOST;
    /**
     * The app the remote's voice button opens (config.xml voice_key_app, changed on the TV). Empty
     * means nothing has been chosen yet: the first press asks, then opens what was picked.
     */
    private String voiceKeyApp;
    /**
     * How many rows below the dock this screen has room for (AppAdapter.maxRowsBelowDock). The
     * settings picker leaves the counts above it out: asking for more rows only pushed the dock against
     * the focused app's name and shrank the tiles for rows that could never appear.
     */
    private int maxRowsBelowDock = 3;
    /** The layout values this instance was built with (onCreate), so onResume can spot a change made on the TV. */
    private int builtColumns, builtRowsBelowDock;
    /** Top bar library button: the theme's value (config.xml library_button) and whether the library exists. */
    private boolean libraryButtonTheme, appLibraryOn;
    /**
     * What the Le button opens while Home is in front: {@link Config#LE_KEY_LIBRARY} (the catalogue)
     * or {@link Config#LE_KEY_HITOKOTO} (the daily sentence, same panel). The top-bar 应用库 button
     * always opens the catalogue - this is the key's own choice. Read in onCreate, re-read in onResume
     * because the settings screen is opened from its own task and no result comes back.
     */
    private int homeLeKeyFunction = Config.LE_KEY_LIBRARY;
    private boolean shamsi;
    /** greeting_name: said after the greeting, which is shown in capitals. */
    private String greetingName = "";
    /** Optional time-of-day backdrop (config.xml, sky_intensity); 0 keeps it hidden. */
    private SkyView sky;
    private String vpnApp;
    private SpringAnimation springX, springY;
    private float glassTargetX, glassTargetY;
    // How far the grid has scrolled the glass. springY works without it, in grid content space, so a
    // scroll moves the glass in lockstep with its tile instead of becoming a moving spring target.
    private float glassScrollShift;
    private int glassCardW;   // width of the tile the glass was last fitted to
    private boolean glassShown;
    /** Set while a dock scroll is in flight, so the glass is refitted once instead of on every frame. */
    private boolean refitAfterScroll;
    private final Rect tileRect = new Rect();
    private final Rect revealRect = new Rect();
    // Static so they survive the activity being destroyed while another app is open (low-memory TVs,
    // or Developer options → "Don't keep activities"); they only reset when the process dies.
    private static int lastFocused = 0;
    /** App of the last focused tile, so focus follows it when the app list changes. */
    private static String lastFocusedKey;
    private float density;
    /** Module cards (res/values/config.xml, module_cards): discovered on resume, shown only after the owner's Show. */
    private boolean moduleCards;
    /** config settings_key_keycodes / settings_key_panel, so the Settings button works on Home without HomeKeyService. */
    private int[] settingsKeycodes = new int[0];
    /** config settings_key_scancodes: the same, but matched on the scancode, which tells two buttons apart. */
    private int[] settingsScancodes = new int[0];
    /** The remote's Menu button opens the focused tile's context menu (menu_key_actions). */
    private boolean menuKeyActions = true;
    /** config input_source_scancodes: remote buttons that open the input/source menu. */
    private int[] inputSourceScancodes = new int[0];
    private boolean settingsKeyPanel;
    /** Calmer motion for weak GPUs (config.xml, reduced_motion): stiff spring, short fades, no replay. */
    private boolean reducedMotion;
    /** config.xml dock_row: the first row is a separate dock (band, parked low, top shelf) rather than plain grid. */
    private boolean dockRow;
    /** The highlight springs past its target (config.xml, focus_overshoot); false goes straight there. */
    private boolean focusOvershoot;
    /** Festive skin from the theme (see Festival); applied while it is active today. */
    private Festival festival = Festival.NONE;
    private boolean festive;
    private DiyaDrawable diya;
    private BellDrawable bell;
    private MarigoldDrawable marigold;
    private int greetingColor;
    /** The day the petal shower last played, so it greets the first Home of each day only. */
    private static LocalDate lastPetalShower;
    /** boot_to_live_tv: open the Live TV app on the first Home of each boot. */
    private boolean bootToLiveTv;
    /** hidden_apps as the adapter sees them (config.xml or a theme's config.json): a hidden app gets no card either. */
    private Set<String> hiddenApps = Collections.emptySet();
    private List<Cards.Card> cards = Collections.emptyList();
    /** The grid has been laid out and focused since the last resume; the card prompt waits for this. */
    private boolean tilesReady;
    /** ensureFocus() already re-posted itself once for the current return; see ensureFocus(). */
    private boolean focusRetry;
    private Dialog cardPrompt;
    /** Set when a decision could not be saved: no more prompts until the next resume, instead of asking in a loop. */
    private boolean promptsPaused;
    /** Between onStop and onStart: Home was fully covered by an app, not just by the translucent quick panel. */
    private boolean stopped;
    /** Between onStart and onStop: Home is (at least partly) on screen. */
    private boolean started;
    /** Rebuild the tiles on the way back to Home only when apps changed meanwhile (TileRefresh). */
    private final TileRefresh tileRefresh = new TileRefresh();
    /** PackageManager's change counter at the last rebuild, to catch a package broadcast that never arrived. */
    private int packageSequence;
    /** The Inputs or Module cards dropdown while one is open, so onPause can close it. */
    private PopupWindow openPopup;
    /** Reorder mode is on (top-bar button). */
    private boolean reordering;
    /** Key of the tile being carried in reorder mode, or null. */
    private String movingKey;
    /** Tile order when reorder mode started, to save only when something moved. */
    private List<String> orderAtStart;
    /**
     * fill_dock false: tiles in the dock when reorder mode started, plus those carried or swapped into it since
     * (#499). A grid app that only slid into a free slot, which reorder mode does not show, is not one of them.
     */
    private Set<String> dockInSession;
    /** fill_dock false: how many dock slots held an app when reorder mode started. */
    private int dockedAtStart;
    private View btnReorder;
    /** dock_apps as loaded (config.xml or a theme): a saved Order only applies while these are unchanged. */
    private List<String> dockApps;
    /** fill_dock false: the dock can have empty slots, which reorder mode leaves out and restores afterwards. */
    private boolean fillDockLeavesSlots;
    /** Edit-dock mode (Settings menu): a reorder session that swaps one chosen app into one dock place. */
    private boolean dockEditing;
    /** Dock place being changed in edit-dock mode, or -1 while the place is still being chosen. */
    private int dockEditSlot = -1;
    private AppLibraryDialog appLibrary;
    /**
     * The running Home, so HomeKeyService (same process) can toggle the library directly while Home
     * is resumed. Starting the activity instead pauses it first, and onStop closes the library, so
     * the second Le press found nothing to toggle and reopened it. See requestLibraryToggle.
     */
    private static MainActivity current;
    /** True between onResume and onPause: Home is the visible activity, popup or not. */
    private boolean resumed;
    /**
     * Uptime of the last Le press that was acted on. One physical press reaches Home through up to
     * three paths at once (dispatchKeyEvent, HomeKeyService, and lekeyd's intent), so the first one
     * within {@link #LE_PRESS_WINDOW_MS} wins and the rest are dropped. See claimLePress.
     */
    private long lastLePress;
    /** Long enough to swallow lekeyd's intent (~300-500 ms after the key), short enough to allow a quick second press. */
    private static final long LE_PRESS_WINDOW_MS = 800;
    private boolean libraryFocusPending;
    private RecyclerView.OnScrollListener pendingFocusScroll;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Refreshes the network pill when the connection changes: at boot Wi-Fi often connects after Home is shown. */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    /** Shows the Inputs pill once the TV registers an input: at boot HDMI inputs can appear after Home is shown. */
    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputAdded(String inputId) {
            updateInputsButton();
        }

        @Override
        public void onInputRemoved(String inputId) {
            updateInputsButton();
        }
    };
    /** Listens for any VPN coming up or going down, even one this app's traffic is excluded from. */
    private final ConnectivityManager.NetworkCallback vpnCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    private final IntentFilter packageFilter = new IntentFilter();
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Updates send REMOVED followed by ADDED; keep the tile until the new APK is ready.
            if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            // In the background this only marks the tiles stale; onResume rebuilds them once.
            if (!tileRefresh.onPackagesChanged()) return;
            reloadApps(false);
            if (appLibrary != null) appLibrary.refresh();
        }
    };
    // Right after boot the clock is often wrong until network time arrives; don't wait a minute to catch up.
    private final IntentFilter timeFilter = new IntentFilter(Intent.ACTION_TIME_CHANGED);
    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateClockVisibility();
        }
    };
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            updateRace();
            // The weather goes stale on its own clock (half an hour), so the minute hand is only a
            // convenient place to notice; the read itself is a timestamp compare, not a request.
            refreshWeather();
            // The pill follows the network callbacks and each return to Home; only the diya's flame follows the
            // signal level, so only it needs this minute's binder calls (about 7 ms on the UI thread).
            if (festive) updateWifiLabel();
            sky.update();
            wallpaperPlayer.update();
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        // config.xml, overlaid by config.json from a pushed theme (see Config and tools/theme.sh).
        Config cfg = Config.load(this);
        applyViewInputsHandler(cfg.handleViewInputs);
        settingsKeycodes = cfg.settingsKeycodes;
        settingsScancodes = cfg.settingsScancodes;
        settingsKeyPanel = cfg.settingsKeyPanel;
        menuKeyActions = cfg.menuKeyActions;
        inputSourceScancodes = cfg.inputSourceScancodes;
        COLUMNS = cfg.columns;
        // What this instance was built with, so onResume can tell whether the settings screen changed it.
        builtColumns = cfg.columns;
        builtRowsBelowDock = cfg.rowsBelowDock;
        animations = cfg.animations;
        advancedEffects = cfg.animations && cfg.advancedEffects;
        reducedMotion = cfg.reducedMotion;
        dockRow = cfg.dockRow;
        focusOvershoot = cfg.focusOvershoot;
        festival = cfg.festival;
        festive = festival.active(LocalDate.now());
        liveTvPkg = cfg.liveTvPackage;
        idleClockMs = cfg.homeIdleClockMinutes * MINUTE_MS;
        bootToLiveTv = cfg.bootToLiveTv;
        liveTvAction = cfg.liveTvAction;
        liveTvInputId = cfg.liveTvInputId;
        inputAction = cfg.inputAction;
        inputUriScheme = cfg.inputUriScheme;
        sourcePanelService = cfg.sourcePanelService;
        sourcePanelAction = cfg.sourcePanelAction;
        sourcePanelExtra = cfg.sourcePanelExtra;
        coocaaSources = cfg.coocaaSources;

        root = findViewById(R.id.root);
        root.setBackgroundColor(cfg.backgroundColor);
        // root fills the window with an opaque colour (Config keeps background_color opaque), so the window's own
        // black under it is a second full-screen fill on every frame for nothing. The starting window still uses
        // the theme's background, so launch looks the same.
        getWindow().setBackgroundDrawable(null);
        wallpaperPlayer = new WallpaperPlayer(this, root, cfg);
        sky = findViewById(R.id.sky);
        sky.setIntensity(cfg.skyIntensity);
        glass = findViewById(R.id.glass);
        if (festive) glass.setAccent(festival.accentColor());
        ambient = findViewById(R.id.ambient);
        // The glow is one bitmap, so its alpha can be applied while drawing it. Otherwise every frame of
        // a fade renders it into a full-screen offscreen buffer first.
        noOverlappingRendering(ambient);
        ambientGlow = cfg.ambientGlow;
        appGlow = cfg.appGlow;
        if (!ambientGlow) ambient.setVisibility(View.GONE);
        heroName = findViewById(R.id.heroName);
        hero = findViewById(R.id.hero);
        // The hero is now a group (name, and the cinematic lines): without this every fade of the default
        // shelf would render it through an offscreen buffer first, as the glow above notes.
        noOverlappingRendering(hero);
        heroMeta = findViewById(R.id.heroMeta);
        heroDots = findViewById(R.id.heroDots);
        heroArt = findViewById(R.id.heroArt);
        cinematic = cfg.cinematicHome;
        glowStyle = cfg.glowStyle;
        if (cinematic) setUpCinematicShelf();
        // dock_title false: the name above the dock (and the cinematic lines under it) never shows; the glow
        // still follows the dock.
        if (!cfg.dockTitle) hero.setVisibility(View.GONE);
        greeting = findViewById(R.id.greeting);
        clockBlock = findViewById(R.id.leftBlock);
        greetingColor = greeting.getCurrentTextColor();
        btnWifi = findViewById(R.id.btnWifi);
        shamsiDate = findViewById(R.id.shamsiDate);
        lunarDate = findViewById(R.id.lunarDate);
        stats = findViewById(R.id.stats);
        raceCalendar = cfg.raceCalendar;
        racePanel = findViewById(R.id.racePanel);
        raceKicker = findViewById(R.id.raceKicker);
        raceHeadline = findViewById(R.id.raceHeadline);
        raceSessions = findViewById(R.id.raceSessions);
        showStats = cfg.showStats;
        // Switched off on the TV: startStats never runs, so hide the empty view here instead of
        // waiting for a stopStats that only happens when the line was on.
        if (!showStats) stats.setVisibility(View.GONE);
        hitokotoBox = findViewById(R.id.hitokotoBox);
        hitokotoText = findViewById(R.id.hitokotoText);
        hitokotoFrom = findViewById(R.id.hitokotoFrom);
        showHitokoto = cfg.showHitokoto;
        weatherMode = cfg.weatherMode;
        hitokotoCategories = cfg.hitokotoCategories;
        if (!showHitokoto) hitokotoBox.setVisibility(View.GONE);
        voiceKeyApp = cfg.voiceKeyApp;
        weatherBox = findViewById(R.id.weatherBox);
        weatherIcon = findViewById(R.id.weatherIcon);
        weather = findViewById(R.id.weather);
        weatherExtra = findViewById(R.id.weatherExtra);
        weatherSun = findViewById(R.id.weatherSun);
        weatherCity = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE)
                .getString(Config.WEATHER_CITY_KEY, "").trim();
        weatherApiKey = cfg.weatherApiKey;
        weatherApiHost = cfg.weatherApiHost;
        // Whatever the last refresh left is shown right away; the network only ever improves on it.
        renderWeather(Weather.load(this));
        shamsi = cfg.shamsiDate;
        greetingName = cfg.greetingName;
        if (shamsi) {
            shamsiDate.setVisibility(View.VISIBLE);
            TextView date = findViewById(R.id.date);
            date.setTextSize(11f);
            date.setPadding(0, (int) (4 * density), 0, 0);
        }
        vpnApp = cfg.vpnApp;
        moduleCards = cfg.moduleCards;
        hiddenApps = new HashSet<>(cfg.hiddenApps);

        grid = findViewById(R.id.grid);
        rtlAppGrid = getResources().getBoolean(R.bool.rtl_app_grid);
        // Mirror only the app grid; the header and other locales keep their own layouts.
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS) {
            @Override
            protected boolean isLayoutRTL() {
                return rtlAppGrid;
            }

            /**
             * Every "bring this tile on screen" scroll lands here, both RecyclerView's own on focus and
             * onTileFocused's. The stock version treats the grid's top padding as the visible top, but that
             * padding only pushes the dock down the screen: grid rows scroll up into it. With the stock rule,
             * Up into a row above the padding dragged that row down to the padding line, cut it off at the
             * bottom of the screen and could scroll the focused tile out entirely, dropping focus into the
             * top bar. Grid tiles now scroll just enough to show the rect between the grid's top edge and its
             * bottom; dock tiles are left to onTileFocused, which parks the dock row on the padding line.
             */
            @Override
            public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
                                                         @NonNull Rect rect, boolean immediate,
                                                         boolean focusedChildVisible) {
                if (dockRow && getPosition(child) < COLUMNS) return false;
                // One press scrolls one row, the same as everywhere else in the grid. Reaching the last row
                // deliberately does NOT scroll the grid to its very bottom: taking the extra distance to
                // uncover the whole app library pill read as a jerk on the remote, so the pill is left partly
                // covered, exactly like any other row arriving under the fold.
                int top = child.getTop() + rect.top;
                int bottom = top + rect.height();
                // A row already fully on screen stays put, even inside the bottom padding (the last row
                // rows_below_dock leaves visible ends there; nudging it up was the bug). A row that has to scroll
                // in still stops at the padding line, clear of the screen edge, as before.
                int limitBottom = getHeight() - getPaddingBottom();
                int dy;
                if (top < 0) dy = top;
                else if (bottom <= getHeight()) dy = 0;
                else dy = Math.min(top, bottom - limitBottom);
                if (dy == 0) return false;
                if (immediate || reducedMotion || !animations) parent.scrollBy(0, dy);
                else parent.smoothScrollBy(0, dy);
                return true;
            }
        });
        ((GridLayoutManager) grid.getLayoutManager()).setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int position) { return adapter != null && adapter.isLibraryAt(position) ? COLUMNS : 1; }
        });
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        // Tiles that scroll out while one is carried come back from the view cache without a rebind; set their
        // dimming again whenever one attaches, so none stays dim after the drop.
        grid.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
            @Override
            public void onChildViewAttachedToWindow(@NonNull View v) {
                v.setAlpha(tileAlpha(grid.getChildAdapterPosition(v)));
            }

            @Override
            public void onChildViewDetachedFromWindow(@NonNull View v) {
            }
        });
        if (dockRow) grid.addItemDecoration(new DockDecoration(COLUMNS, density, cinematic, cfg.alwaysShowNames, cfg.dockOpacity, cfg.dockGlass));
        // Reduced motion: the tiles are simply there, with no entrance (reloadApps skips it there too).
        if (animations && !reducedMotion) {
            grid.setLayoutAnimation(new LayoutAnimationController(
                    AnimationUtils.loadAnimation(this, R.anim.tile_enter), 0.06f));
        }
        adapter = new AppAdapter(this, this, COLUMNS, cfg);
        dockApps = cfg.dockApps;
        fillDockLeavesSlots = !cfg.fillDock;
        Order savedOrder = Order.load(Order.file(this));
        adapter.setSavedOrder(Order.ranks(savedOrder, dockApps), Order.docked(savedOrder, dockApps));
        grid.setAdapter(adapter);

        // tvOS layout: push the dock row towards the bottom of the screen, leaving room for
        // rows_below_dock grid rows under it; the rest of the grid stays below until you press Down.
        int rowsBelowDock = cfg.rowsBelowDock;
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            // Item height follows the tile size, which follows the columns and the rows that must fit (AppAdapter.tileWidthPx).
            int inner = (r - l) - grid.getPaddingLeft() - grid.getPaddingRight();
            // The focused app's name is drawn above the first row, so the dock never rises into it.
            int shelfClearance = cfg.appLibrary ? hero.getMeasuredHeight() + (int) (40 * density) : 0;
            // The dock title sits above the dock; it needs its room too, or it would draw over the clock.
            int minTop = Math.max(shelfClearance,
                    cfg.dockRow && cfg.dockTitle ? (int) (AppAdapter.TITLE_ROOM_DP * density) : 0);
            // More rows than the screen holds cannot appear: asking for them only shrank every tile.
            maxRowsBelowDock = AppAdapter.maxRowsBelowDock(h, inner, COLUMNS, minTop, cfg.dockRow, density);
            int rows = Math.min(rowsBelowDock, maxRowsBelowDock);
            int tileW = AppAdapter.tileWidthPx(inner, h, COLUMNS, rows, cfg.dockTitle, cfg.dockRow, density);
            int itemH = AppAdapter.itemHeightPx(AppAdapter.tileHeightPx(tileW), density);
            int dockRow = itemH + (int) (AppAdapter.DOCK_EXTRA_DP * density);   // item height plus DockDecoration offsets
            // Exactly as tall as the dock and the rows it asks for, so the row after them starts at the bottom
            // edge and stays out of sight. Any slack here shows up as a sliver of the next row under the dock,
            // which is what made rows_below_dock 0 look like it had a row below it anyway.
            int want = Math.max(minTop, h - dockRow - itemH * rows);
            // No dock to push down: the grid starts at the top, with room for the first row's zoom and glass.
            if (!MainActivity.this.dockRow) want = (int) (AppAdapter.TOP_ROOM_DP * density);
            if (grid.getPaddingTop() != want) {
                grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
            }
            // Cold start or column count > 4: the first focus can run before the grid has its final width or
            // padding, leaving the glass at the XML design size until a key is pressed.
            grid.post(this::refitGlass);
        });

        // Keep the glass glued to its tile while the grid scrolls.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!glassShown) return;
                glassTargetY -= dy;
                glassScrollShift -= dy;
                glass.setTranslationY(glass.getTranslationY() - dy);
            }
        });

        // Liquid feel: soft springs on the highlight's position.
        springX = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_X);
        springY = new SpringAnimation(glass, new FloatPropertyCompat<View>("glassContentY") {
            @Override
            public float getValue(View v) {
                return v.getTranslationY() - glassScrollShift;
            }

            @Override
            public void setValue(View v, float value) {
                v.setTranslationY(value + glassScrollShift);
            }
        });
        for (SpringAnimation s : new SpringAnimation[]{springX, springY}) {
            if (reducedMotion) {
                Motion.reduce(s);
            } else if (!focusOvershoot) {
                // Critically damped: glides straight onto the tile without swinging past it.
                s.setSpring(new SpringForce().setStiffness(advancedEffects ? 520f : 440f).setDampingRatio(1f));
            } else {
                s.setSpring(new SpringForce().setStiffness(advancedEffects ? 320f : 260f)
                        .setDampingRatio(advancedEffects ? 0.76f : 0.58f));
            }
        }

        btnInputs = findViewById(R.id.btnInputs);
        View btnSettings = findViewById(R.id.btnSettings);
        btnInputs.setOnClickListener(v -> showInputs());
        btnWifi.setOnClickListener(v -> openNetworkPill());
        btnWifi.setOnLongClickListener(v -> {
            openSettings(systemIntent(Settings.ACTION_WIFI_SETTINGS));
            return true;
        });
        // The pill does exactly what the remote's Settings key does (handleSettingsKey): the launcher's
        // own settings when settings_key_panel is on, the TV's own Settings when it is off. There is no
        // intermediate panel any more - the settings screen carries the TV settings row itself, so the
        // two-step panel was one press of nothing.
        btnSettings.setOnClickListener(v -> {
            if (settingsKeyPanel) openLauncherSettings();
            else openSettings(systemIntent(Settings.ACTION_SETTINGS));
        });
        btnReorder = findViewById(R.id.btnReorder);
        btnReorder.setOnClickListener(v -> toggleReorder());
        btnReorder.setVisibility(cfg.reorderButton ? View.VISIBLE : View.GONE);
        if (!cfg.showNetworkPill) btnWifi.setVisibility(View.GONE);
        showInputsPill = cfg.showInputsPill;
        btnLibraryTop = findViewById(R.id.btnLibraryTop);
        btnLibraryTop.setOnClickListener(v -> {
            onLibraryRequested();
            libraryFromTopBar = true;   // closing it goes back to this button, not to the pill after the last row
        });
        libraryButtonTheme = cfg.libraryButton;
        homeLeKeyFunction = cfg.homeLeKeyFunction;
        appLibraryOn = cfg.appLibrary;
        btnLibraryTop.setVisibility(libraryButtonWanted() ? View.VISIBLE : View.GONE);
        iconOnlyButtons = cfg.iconOnlyButtons;
        wifiIconOnly = iconOnlyButtons && cfg.wifiLabelWidth <= 0;
        if (iconOnlyButtons) {
            if (cfg.wifiLabelWidth > 0) btnWifi.setMaxWidth(Math.round(cfg.wifiLabelWidth * density));
            else makeIconOnly(btnWifi);
            makeIconOnly((TextView) btnSettings);
        }
        btnSettings.setOnLongClickListener(v -> {
            showSettingsMenu();
            return true;
        });

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        boolean rtlMenuBar = getResources().getBoolean(R.bool.rtl_menu_bar);
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                // Back to the dock tile Up came from, not wherever the focus finder lands.
                focusTile(lastFocused % COLUMNS);
                return true;
            }
            int firstButton = btnInputs.getVisibility() == View.VISIBLE ? R.id.btnInputs : firstTopButton().getId();
            int leftButton = rtlMenuBar ? R.id.btnSettings : firstButton;
            int rightButton = rtlMenuBar ? firstButton : R.id.btnSettings;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return v.getId() == leftButton;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == rightButton;
        };
        btnInputs.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);
        btnReorder.setOnKeyListener(pillKeys);
        btnLibraryTop.setOnKeyListener(pillKeys);
        if (advancedEffects) {
            for (View button : new View[]{btnInputs, btnWifi, btnReorder, btnLibraryTop, btnSettings}) {
                button.setOnFocusChangeListener((view, focused) -> view.animate()
                        .scaleX(focused ? 1.04f : 1f).scaleY(focused ? 1.04f : 1f)
                        .setDuration(reducedMotion ? Motion.REDUCED_MS : 160).start());
            }
        }
        if (festive) applyFestiveSkin((TextView) btnInputs, (TextView) btnSettings);

        // Hide the glass when focus leaves the tiles.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean onTile = newF != null && newF.getId() == R.id.card;
            if (!onTile) hideGlass();
        });

        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageFilter.addDataScheme("package");
        timeFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        // Registered for the activity's whole life, not only while it is started: an app installed or updated while
        // another app is in front must still mark the tiles stale, or Home would have to rebuild on every return.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, packageFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(packageReceiver, packageFilter);
        }

        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        Compat.registerInputCallback(tim, inputCallback, handler);
        if (cm != null) {
            Compat.registerDefaultNetworkCallback(cm, networkCallback);
            Compat.registerVpnCallback(cm, vpnCallback);
        }
        // Not after recreate(): the launch intent survives it and would open the settings again.
        if (savedInstanceState == null && !openLauncherSettingsIfAsked(getIntent())
                && !openLibraryIfAsked(getIntent())) {
            openInputsIfAsked(getIntent());
        }
    }

    /**
     * ACTION_TOGGLE_LIBRARY: open the library, or close it when it is already up (a second press
     * exits). Sent by HomeKeyService for the Le button, and by the lekeyd daemon - the only path
     * that works while another app is in front, because the firmware force-stops every package
     * holding an accessibility service as soon as it is backgrounded.
     *
     * <p>The intent is replaced so a later recreate() does not toggle again. Cold start waits for
     * the first layout: the popup is centred and needs no anchor, but the focus hand-off after it
     * closes runs on the grid.
     */
    private boolean openLibraryIfAsked(Intent intent) {
        if (intent == null || !ACTION_TOGGLE_LIBRARY.equals(intent.getAction())) return false;
        setIntent(new Intent(Intent.ACTION_MAIN));
        // lekeyd fires this even when Home is in front and handleLeKey already acted on the same
        // press; without the guard the press would toggle twice and look like it did nothing.
        if (!claimLePress()) return true;
        if (appLibrary != null) {
            appLibrary.close();
            return true;
        }
        if (grid.isLaidOut()) {
            onLeKeyOnHome();
        } else {
            grid.post(this::onLeKeyOnHome);
        }
        return true;
    }

    /**
     * True for the first caller of one physical Le press. The press is reported by dispatchKeyEvent
     * (Home in front), by HomeKeyService's filtered key events (same process, same key) and by
     * lekeyd's TOGGLE_LIBRARY intent, all within a few hundred milliseconds; the losers are dropped
     * so the library does not open and close again in one go.
     */
    boolean claimLePress() {
        long now = SystemClock.uptimeMillis();
        if (now - lastLePress < LE_PRESS_WINDOW_MS) return false;
        lastLePress = now;
        return true;
    }

    /**
     * Leaves files/foreground holding this process's pid while Home is resumed, and removes it in
     * onPause. The lekeyd daemon reads it before firing ACTION_TOGGLE_LIBRARY: with Home in front the
     * key is handled here directly (no activity round trip, no pause/resume), so the daemon stays out
     * of the way. The pid is in the file because a file left behind by a killed process must not
     * convince the daemon that Home is still visible - it checks that the pid is alive.
     */
    private void markForeground(boolean foreground) {
        File marker = new File(getFilesDir(), "foreground");
        if (!foreground) {
            //noinspection ResultOfMethodCallIgnored
            marker.delete();
            return;
        }
        try (FileOutputStream out = new FileOutputStream(marker)) {
            out.write(String.valueOf(Process.myPid()).getBytes(StandardCharsets.UTF_8));
        } catch (IOException unwritable) {
            // The daemon then keeps firing intents on Home; claimLePress keeps that harmless.
        }
    }

    /**
     * HomeKeyService's entry point for the Le button. Home runs in the same process, so while it is
     * resumed the library is toggled directly, with no activity round trip - and the press is
     * claimed here, because dispatchKeyEvent reports the very same key a moment later. When Home is
     * not resumed - another app in front, or no instance at all - the overlay activity is started
     * instead: it draws the library over that app without bringing the desktop forward.
     */
    static void requestLibraryToggle(Context context) {
        MainActivity home = current;
        if (home != null && home.resumed && !home.isFinishing()) {
            if (home.claimLePress()) home.onLeKeyOnHome();
            return;
        }
        // The overlay is up and has the keyboard: the press is already being handled by the panel's
        // own F5 handler, and starting the activity again would reopen what that press just closed.
        if (AppLibraryActivity.isShowing()) return;
        try {
            context.startActivity(new Intent(context, AppLibraryActivity.class)
                    .setAction(ACTION_TOGGLE_LIBRARY)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            Toast.makeText(context, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * handle_view_inputs: switch InputsRequestActivity, the VIEW_INPUTS handler, on or off. It is disabled in the
     * manifest, so a TV with its own input picker never sees it; only the sets that need it turn it on. Written
     * only when the state differs, so an ordinary start does not touch the package manager.
     */
    private void applyViewInputsHandler(boolean on) {
        ComponentName handler = new ComponentName(this, InputsRequestActivity.class);
        PackageManager pm = getPackageManager();
        int want = on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
        if (pm.getComponentEnabledSetting(handler) != want) {
            pm.setComponentEnabledSetting(handler, want, PackageManager.DONT_KILL_APP);
        }
    }

    /**
     * VIEW_INPUTS, forwarded by InputsRequestActivity while handle_view_inputs is on: open the Inputs menu once
     * the pill is on screen. The intent is replaced so a later recreate() does not
     * open it again. Nothing to do when the TV has no inputs to list (the pill is hidden then).
     */
    private boolean openInputsIfAsked(Intent intent) {
        if (intent == null || !ACTION_VIEW_INPUTS.equals(intent.getAction())) return false;
        setIntent(new Intent(Intent.ACTION_MAIN));
        // The pill can be turned off (show_inputs_pill) while the menu is still wanted: the popup then
        // hangs under the Wi-Fi pill, so it is that button that has to be laid out and focused.
        final View pill = sourceAnchor();
        if (pill.isLaidOut()) {
            pill.requestFocus();
            showInputs();
            return true;
        }
        // Cold start: the popup anchors to the pill, so wait for the first layout.
        pill.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                pill.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                pill.post(() -> {
                    if (isFinishing() || pill.getVisibility() != View.VISIBLE) return;
                    pill.requestFocus();
                    showInputs();
                });
            }
        });
        return true;
    }

    private boolean openLauncherSettingsIfAsked(Intent intent) {
        if (intent == null || !ACTION_LAUNCHER_SETTINGS.equals(intent.getAction())) return false;
        setIntent(new Intent(Intent.ACTION_MAIN));
        startActivityForResult(launcherSettingsIntent(), LAUNCHER_SETTINGS_REQUEST);
        return true;
    }

    /**
     * A settings screen handed back by the quick panel or the remote's Settings key. Those run outside
     * Home's task, and a settings task of its own returns to the stock launcher on Back, so the screen is
     * opened from here instead, on top of Home.
     */
    private boolean openSettingsIfAsked(Intent intent) {
        if (intent == null || !ACTION_OPEN_SETTINGS.equals(intent.getAction())) return false;
        String action = intent.getStringExtra(EXTRA_SETTINGS_ACTION);
        setIntent(new Intent(Intent.ACTION_MAIN));
        if (action != null) openSettings(systemIntent(action));
        return true;
    }

    /**
     * Home pressed while Home is already in front: singleTask delivers it here, then onResume runs.
     * Back to the top with the first dock tile focused, like the stock launcher. Back from an app
     * never comes this way, so returning to the app you left from is unchanged (#118).
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (openSettingsIfAsked(intent)) return;
        if (openLauncherSettingsIfAsked(intent)) return;
        if (openLibraryIfAsked(intent)) return;
        if (openInputsIfAsked(intent)) return;
        if (intent == null || !intent.hasCategory(Intent.CATEGORY_HOME)) return;
        grid.scrollToPosition(0);
        lastFocused = 0;
        lastFocusedKey = adapter.getItemCount() > 0 ? adapter.keyAt(0) : null;
        if (stopped) {
            tileRefresh.onHomeAgain();
        } else {
            // Still on screen (the quick panel was over it, or Home was already in front): the tiles are current,
            // so only go back to the first one; rebuilding would replay the entrance animation for nothing.
            grid.post(() -> focusTile(0));
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        stopped = false;
        started = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(timeReceiver, timeFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(timeReceiver, timeFilter);
        }
        updateClockVisibility();
        tileRefresh.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        current = this;
        markForeground(true);
        // A festival that started or ended while Home was in the background: rebuild with or without it.
        if (festive != festival.active(LocalDate.now())) {
            recreate();
            return;
        }
        // Columns and rows below the dock are read in onCreate, and the settings screen cannot always hand its
        // result back: the firmware force-stops Home as soon as another screen comes to the front, and the
        // Settings key reaches the settings screen through HomeKeyService. Picking a new row count and pressing
        // Back then left the dock exactly where it was. Re-read them here and rebuild, as showStats does.
        if (layoutChanged()) {
            recreate();
            return;
        }
        restartIdleClock();
        promptsPaused = false;
        if (grid.getAdapter() == null) grid.setAdapter(adapter);   // dropped by onTrimMemory
        switch (tileRefresh.onResume(packagesChangedSinceRebuild())) {
            case REBUILD:
                tilesReady = false;
                reloadApps(true);
                break;
            case REFOCUS:
                // The same tiles: only the focus goes back, as after a rebuild, without replaying the entrance.
                tilesReady = false;
                reloadApps(true, false, false);
                break;
            default:
                break;
        }
        if (openLiveTvAfterBoot()) return;
        updateGreeting();
        updateRace();
        // The network and Inputs pills keep their state while Home is in the background, and callbacks update them
        // when the network or the inputs change, so this refresh only catches anything missed. Its binder calls
        // (about 7 ms on a TCL C755, most of it WifiManager.getConnectionInfo) would hold up the first frame back
        // on Home, so after the first resume it runs once that frame is drawn.
        if (pillsShown) {
            refreshPillsAfterNextDraw();
        } else {
            updateWifiLabel();
            updateInputsButton();
            pillsShown = true;
        }
        // The switch also lives behind the quick panel, where the settings screen is opened from a
        // task of its own and no result ever comes back here, so onActivityResult's recreate() is
        // not enough. Re-read it every resume: two reads of a boolean, and the line follows the
        // switch the moment Home comes back into view.
        boolean statsNow = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE)
                .getBoolean(Config.SHOW_STATS_KEY, showStats);
        if (statsNow != showStats) {
            showStats = statsNow;
            stats.setVisibility(showStats ? View.VISIBLE : View.GONE);
        }
        if (showStats) startStats();
        // Same reason as the stats switch above: the quick panel opens this screen from its own task,
        // so the on-TV choice is re-read here rather than trusted to onActivityResult.
        showHitokoto = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE)
                .getBoolean(Config.SHOW_HITOKOTO_KEY, showHitokoto);
        // The categories are ticked on that same screen. A different set means a different draw,
        // and loadHitokoto recomputes it from the date, so this only has to be re-read here.
        hitokotoCategories = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE)
                .getInt(Config.HITOKOTO_CATEGORIES_KEY, hitokotoCategories);
        loadHitokoto();
        // And the voice button's app, for the same reason: it is chosen behind that same panel, and
        // the next press of the button has to open the new app without a restart. An empty string is
        // a real value ("nothing chosen"), so the default is what we already hold.
        voiceKeyApp = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE)
                .getString(Config.VOICE_KEY_APP, voiceKeyApp).trim();
        // The city and the key are typed on that same screen, so both are re-read here. A changed
        // city means the line has to be re-resolved, which refreshWeather notices on its own.
        weatherCity = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE)
                .getString(Config.WEATHER_CITY_KEY, weatherCity).trim();
        weatherApiKey = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE)
                .getString(Config.WEATHER_API_KEY_KEY, weatherApiKey).trim();
        weatherApiHost = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE)
                .getString(Config.WEATHER_API_HOST_KEY, weatherApiHost).trim();
        if (weatherApiHost.isEmpty()) weatherApiHost = Config.DEFAULT_WEATHER_HOST;
        // The mode, for the same reason as showStats above: the settings screen opens in a task
        // of its own, so no result comes back. A theme value is the fallback.
        weatherMode = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE)
                .getInt(Config.WEATHER_MODE_KEY, weatherMode);
        refreshWeather();
        // The top bar's library button is switched on the same screen, and it should appear or go away as soon
        // as you come back rather than after a restart.
        btnLibraryTop.setVisibility(libraryButtonWanted() ? View.VISIBLE : View.GONE);
        // And the Le button's choice, for the same reason: it is ticked behind that same panel and the
        // next press has to obey the new value without a restart.
        homeLeKeyFunction = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE)
                .getInt(Config.HOME_LE_KEY_FUNCTION_KEY, homeLeKeyFunction);
        sky.update();
        wallpaperPlayer.start();
        handler.postDelayed(minuteTick, MINUTE_MS);
        if (festive && diya != null) {
            diya.start();
            LocalDate today = LocalDate.now();
            if (!reducedMotion && !today.equals(lastPetalShower)) {
                lastPetalShower = today;
                handler.postDelayed(this::playPetalShower, 450);
            }
        }
    }

    /**
     * Whether the top bar shows the library button: the TV's own choice when it has one, otherwise the theme's,
     * and never when the library itself is switched off.
     */
    private boolean libraryButtonWanted() {
        return appLibraryOn && getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE)
                .getBoolean(Config.LIBRARY_BUTTON_KEY, libraryButtonTheme);
    }

    /**
     * True when the settings screen changed a value this screen was laid out with. Both are only read in
     * onCreate, so a change needs a rebuild to show up; onActivityResult cannot be relied on for it (see onResume).
     */
    private boolean layoutChanged() {
        SharedPreferences sp = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        // The same ranges Config.load accepts, so a value it would ignore cannot trigger a rebuild loop.
        int columns = sp.getInt("columns", builtColumns);
        if (columns < 4 || columns > 7) columns = builtColumns;
        int rows = sp.getInt(Config.ROWS_BELOW_DOCK_KEY, builtRowsBelowDock);
        if (rows < 0 || rows > 3) rows = builtRowsBelowDock;
        return columns != builtColumns || rows != builtRowsBelowDock;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == LAUNCHER_SETTINGS_REQUEST && resultCode == RESULT_OK) recreate();
        // The set would not take a surface from us, so there is no picture to show here: hand the port to
        // its own panel rather than leave a black screen.
        if (requestCode == SOURCE_VIEW_REQUEST && resultCode == RESULT_FIRST_USER) openSourcePanel();
    }

    /** Rebuilds the tiles (and the module cards), then puts focus back where it was. */
    private void reloadApps(boolean animate) {
        reloadApps(animate, true);
    }

    /** @param rebuild false keeps the tiles as they are and only puts focus back (TileRefresh.Action.REFOCUS). */
    private void reloadApps(boolean animate, boolean rebuild) {
        reloadApps(animate, rebuild, true);
    }

    /**
     * @param entrance false puts focus back as an animated reload would, without replaying the tiles' entrance:
     *                 on a return to the same tiles it would only mark a change that didn't happen.
     */
    private void reloadApps(boolean animate, boolean rebuild, boolean entrance) {
        cancelPendingFocusScroll();
        RecyclerView.ViewHolder footer = grid.findViewHolderForAdapterPosition(adapter.appCount());
        final boolean restoreLibrary = adapter.isLibraryAt(adapter.appCount()) && footer != null
                && footer.itemView.findViewById(R.id.btnLibrary) != null
                && footer.itemView.findViewById(R.id.btnLibrary).hasFocus();
        // Capture the app before rebinding can move focus and overwrite its key.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        final boolean restoreFocus = appLibrary == null && (animate || grid.hasFocus());
        if (restoreFocus) {
            glassShown = false;
            glass.setAlpha(0f);
            heroShown = false;
            hero.setAlpha(0f);
            ambient.setAlpha(0f);
            heroArt.animate().cancel();
            heroArt.setAlpha(0f);
        }
        if (rebuild) reloadTiles();
        if (animate && entrance && animations && !reducedMotion) {
            grid.scheduleLayoutAnimation();
            // A rebuild redraws the grid anyway; without one the entrance needs a draw to start.
            if (!rebuild) grid.invalidate();
        }

        if (restoreFocus) {
            // Keep a focused footer at the end even when the app count changes.
            if (restoreLibrary) grid.post(this::focusLibrary);
            else restoreFocus();
        } else {
            // A package change must not steal focus from a top button or the Inputs popup.
            int pos = adapter.positionOf(returnKey);
            lastFocused = Math.max(0, Math.min(pos >= 0 ? pos : returnSlot, adapter.appCount() - 1));
            lastFocusedKey = adapter.keyAt(lastFocused);
            if (pos < 0) hideHero();
            // A newly installed app may have brought a card with it.
            tilesReady = true;
            showCardPrompt();
        }
    }

    /** True when PackageManager reports apps changed since the last rebuild (one binder call). */
    private boolean packagesChangedSinceRebuild() {
        return !Compat.changedPackageNames(getPackageManager(), packageSequence).isEmpty();
    }

    private int currentPackageSequence(int since) {
        return Compat.packageSequence(getPackageManager(), since);
    }

    /**
     * Rebuilds the tiles; the module cards the owner chose to show stand in for their app's tile.
     * Every step into the card code runs under CardFeature: if it fails, the tiles are the ordinary
     * ones and cards stay off for this process.
     */
    private void reloadTiles() {
        if (reordering) endReorder();   // an app installed or removed while reordering: keep the order so far
        // Read these before reload(): rebinding can move focus to the first tile and overwrite them.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        final List<Cards.Card> none = Collections.emptyList();
        // An updated app may bring new artwork; the backdrop is cheap to make again.
        heroArtCache.clear();
        heroArtKey = null;
        // One LEANBACK query for both the tiles and the card checks.
        final List<ResolveInfo> leanback = getPackageManager().queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
        cards = moduleCards ? CardFeature.run(() -> Cards.discover(this, hiddenApps,
                () -> Cards.load(Cards.storeFile(this)), leanback), none) : none;
        adapter.reload(cards.isEmpty() ? none : CardFeature.run(() -> Cards.shown(cards), none), leanback);
        lastFocusedKey = returnKey;
        lastFocused = returnSlot;
        packageSequence = currentPackageSequence(packageSequence);
    }

    /**
     * Return to the app we left from, or the same slot if that app is gone. Always refocus: after
     * reload() the view that holds focus may no longer show the app it had.
     */
    private void restoreFocus() {
        final boolean returnToLibrary = libraryFocusPending;
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        grid.post(() -> {
            int pos = adapter.positionOf(returnKey);
            if (returnToLibrary || libraryFocusPending) focusLibrary();
            else focusTile(pos >= 0 ? pos : returnSlot);
            tilesReady = true;
            showCardPrompt();
        });
    }

    /** Any key on Home restarts the countdown to the idle clock. */
    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        restartIdleClock();
    }

    private void restartIdleClock() {
        root.removeCallbacks(showIdleClock);
        if (idleClockMs > 0) root.postDelayed(showIdleClock, idleClockMs);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        markForeground(false);
        root.removeCallbacks(showIdleClock);
        endReorder();   // Home or another app while reordering keeps the order so far
        wallpaperPlayer.stop();
        stopAmbientAnimation();
        hero.animate().cancel();
        ambient.animate().cancel();
        heroArt.animate().cancel();
        handler.removeCallbacks(minuteTick);
        stopStats();
        if (diya != null) diya.stop();
        cancelPendingFocusScroll();
        // Nothing of ours stays on screen over another app; an undecided prompt simply returns next time.
        if (cardPrompt != null) cardPrompt.dismiss();
        if (openPopup != null) openPopup.dismiss();
        // The library is closed in onStop, not here: delivering a new intent to a resumed Home
        // pauses it first (performNewIntents -> callActivityOnPause) and resumes it right after,
        // so closing here would kill the library just before the toggle that asked for it.
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        if (appLibrary != null) appLibrary.close();
        unregisterReceiver(timeReceiver);
        handler.removeCallbacks(clockCheck);
        stopped = true;
        tileRefresh.onStop();
    }

    /**
     * Another app needs the memory while Home is in the background: let go of every tile's artwork, and
     * rebuild it when Home comes back. Not at UI_HIDDEN, the ordinary trip to an app, where keeping the
     * tiles is what makes the return instant.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // RUNNING_LOW and CRITICAL also arrive while Home is visible when an accessibility service keeps the
        // process in the foreground; never blank the screen the owner is looking at.
        boolean due = level >= TRIM_MEMORY_BACKGROUND
                || level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_CRITICAL;
        if (!due || started) return;
        adapter.clear();
        // Detach the adapter too: with Home stopped no layout pass runs, so the tile views (and the bitmaps
        // they hold) would stay attached until Home comes back. setAdapter(null) recycles them now.
        grid.setAdapter(null);
        grid.getRecycledViewPool().clear();
        heroArtCache.clear();
        heroArtKey = null;
        tileRefresh.invalidate();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            if (libraryFocusPending && appLibrary == null) focusLibrary();
            // Back from an app can leave the grid holding the focus with no tile under it, and Home then
            // draws nothing but black. The normal restore runs first; this is the safety net behind it.
            else {
                focusRetry = false;
                grid.postDelayed(this::ensureFocus, FOCUS_NET_MS);
            }
            showCardPrompt();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (current == this) current = null;
        // A half-built activity (onCreate threw before the receiver went in) must still be able to go away.
        try {
            unregisterReceiver(packageReceiver);
        } catch (Throwable ignored) {
            // Never registered.
        }
        wallpaperPlayer.close();
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        Compat.unregisterInputCallback(tim, inputCallback);
        if (cm != null) {
            Compat.unregisterNetworkCallback(cm, networkCallback);
            Compat.unregisterNetworkCallback(cm, vpnCallback);
        }
    }

    /**
     * A Settings button sent as a keycode (settings_key_keycodes, e.g. 83 on TCL Google TV remotes) opens the same
     * thing HomeKeyService would. While that service runs it consumes the key first; without it (not enabled,
     * or switched off by a force-stop) the stock launcher that used to handle the key is gone, and the button did
     * nothing even on Home.
     *
     * A scancode (settings_key_scancodes) is checked first: it is the only way to tell two buttons apart when the
     * firmware maps them to the same keycode, or to one Android does not define. The numbers are EV_KEY codes -
     * the `key N` lines of the .kl file the remote is bound to - which is what KeyEvent.getScanCode() returns; the
     * MSC_SCAN values getevent prints are something else. The LeTV X4-65S maps `key 357 SETTINGS` and
     * `key 139 MENU`, so its Settings button arrives as scancode 357 with keycode 176 (KEYCODE_SETTINGS).
     */
    private boolean handleSettingsKey(KeyEvent event) {
        if (!isSettingsKey(event)) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) openSettingsPanel();
        return true;
    }

    /** True for the remote's Settings button, matched on its scancode or its keycode. */
    private boolean isSettingsKey(KeyEvent event) {
        for (int scan : settingsScancodes) if (event.getScanCode() == scan) return true;
        for (int code : settingsKeycodes) if (event.getKeyCode() == code) return true;
        return false;
    }

    /** True for the remote's Source / Input button. Scancode only: this button often has no keycode at all. */
    private boolean isInputSourceKey(KeyEvent event) {
        for (int scan : inputSourceScancodes) if (event.getScanCode() == scan) return true;
        return false;
    }

    /** What the Settings button opens: the launcher's settings, or the set's own when the theme asks for that. */
    private void openSettingsPanel() {
        if (settingsKeyPanel) openLauncherSettings();
        else openSettings(systemIntent(Settings.ACTION_SETTINGS));
    }

    /**
     * The launcher's own settings, opened from here so Home rebuilds with the new choices when they
     * close (onActivityResult -> recreate). The screen itself carries the TV settings row, which is why
     * the Settings button and the remote's Settings key land here directly.
     */
    private void openLauncherSettings() {
        startActivityForResult(launcherSettingsIntent(), LAUNCHER_SETTINGS_REQUEST);
    }

    /**
     * The settings screen, told how many rows below the dock this screen can actually show, so it can
     * leave the unreachable counts out of the picker instead of letting a bigger number do nothing.
     */
    private Intent launcherSettingsIntent() {
        return new Intent(this, LauncherSettingsActivity.class)
                .putExtra(LauncherSettingsActivity.EXTRA_MAX_ROWS_BELOW_DOCK, maxRowsBelowDock);
    }

    /**
     * The remote's Source / Input button opens the input menu. Matched on the scancode, because a keycode can
     * mean different buttons and this one often has no keycode at all: the LeTV X4-65S maps it to
     * `key 187 VIRTUAL_NUMBER`, which Android does not turn into a keycode, so only the scancode 187 finds it.
     * With a source panel configured (source_panel_service) and no Live TV row, the panel opens straight away;
     * otherwise the Inputs menu lists the ports.
     */
    private boolean handleInputSourceKey(KeyEvent event) {
        if (!isInputSourceKey(event)) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            // The same button again closes the menu it opened. While the menu is up it owns the keyboard,
            // so this only covers a menu that lost the focus - addSourceMenuKeys is the real path.
            if (openPopup != null) {
                openPopup.dismiss();
                return true;
            }
            List<CoocaaSources.Source> coocaa = coocaaSources ? CoocaaSources.list(this) : Collections.emptyList();
            // A set that answers the switch API gets our list here too, so the button and the pill agree;
            // otherwise a configured panel stays the whole menu (no Live TV row to offer beside it).
            if (MstarSourceSwitch.available()) {
                showInputs();
            } else if (coocaa.isEmpty() && !sourcePanelService.isEmpty() && liveTvIntent() == null) {
                openSourcePanel();
            } else {
                showInputs();
            }
        }
        return true;
    }

    /**
     * The remote's Menu button on a focused tile opens that tile's context menu - Open, Remove from Home,
     * Reorder apps, App info - which is what a mouse's right click (and a long press) gives. Firmware that
     * sends KEYCODE_MENU for a different button ends up here as well, so a theme can turn it off with
     * menu_key_actions.
     */
    private boolean handleMenuKey(KeyEvent event) {
        if (!menuKeyActions || event.getKeyCode() != KeyEvent.KEYCODE_MENU) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            if (openPopup != null) openPopup.dismiss();      // a second press closes the menu
            else {
                int pos = focusedTilePosition();
                // A menu closed with Back can leave the window with nothing - or the grid itself -
                // focused, and then this answers -1 and the button does nothing at all (reported:
                // Menu dead until the Home key is pressed). The tile focus was last seen on still
                // holds the app the user was on, so its menu is what the button means to reopen.
                if (pos < 0) pos = lastFocusedTilePosition();
                if (pos >= 0) showTileMenu(pos);
                else if (appLibrary != null) appLibrary.close();
            }
        }
        return true;
    }

    /** The tile focus was last on, or -1 when there is none a menu could be opened for. */
    private int lastFocusedTilePosition() {
        if (adapter == null || lastFocused < 0 || lastFocused >= adapter.getItemCount()) return -1;
        AppAdapter.Entry entry = adapter.entryAt(lastFocused);
        return entry == null || entry.kind == AppAdapter.Entry.EMPTY ? -1 : lastFocused;
    }

    /**
     * The remote's Le button (乐视遥控器 Le 键) opens the app library, like the top-bar button. The
     * firmware consumes that button in PhoneWindowManager.interceptKeyBeforeQueueing for its own
     * screenshot / com.stv.lekey panel before any app or accessibility service can see it, so the
     * keylayouts are patched on the TV (dbg/fixkl.sh: scancode 189 LE rewritten to F5 in
     * /system/usr/keylayout/Vendor_3697_Product_0001.kl for the IR receiver and
     * Vendor_4c65_Product_3d4c.kl for the Bluetooth remote), which the firmware does pass on. F5
     * rather than MENU because a focused app turns MENU into its own options menu - pressing Le
     * inside another app popped that app's menu. No app binds F5, so the key is inert there.
     *
     * <p>Scancode 377 (LETV, the share button right under Settings) is NOT remapped: it keeps its
     * stock LETV keycode so the firmware's own takeScreenshot fires, which is what that button did
     * before. Only 189 reaches the launcher.
     *
     * <p>The scancode survives the rewrite and tells the buttons apart: Le is 189 (LE), the real
     * Menu button is 139 and keeps opening the tile menu. With a popup up, the
     * popup owns the keyboard and its own Menu handling closes it, exactly like the real button.
     *
     * <p>This is the desktop's own path, and the only one that needs no other process: it works
     * with the accessibility service off and with lekeyd dead. Inside other apps the key never
     * reaches here, so the lekeyd daemon (root, reads /dev/input directly) answers it with
     * ACTION_TOGGLE_LIBRARY; HomeKeyService is a second, weaker fallback for the same intent.
     */
    private boolean handleLeKey(KeyEvent event) {
        int scan = event.getScanCode();
        if (scan != LE_SCANCODE_LE) return false;   // 139 is the real Menu button, 377 is the share button
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled() && claimLePress()) {
            onLeKeyOnHome();
        }
        return true;
    }

    /**
     * The remote's voice button (scancode 167, the key under the share button) opens the app chosen
     * for it - a one-press shortcut. The firmware's two beeps stay: they are the only sign the button
     * was pressed at all, and the button's own com.stv.voice package is not installed, so nothing else
     * happens behind it.
     *
     * <p>On a fresh TV nothing is chosen yet, so the first press shows the list of apps; picking one
     * saves it and opens it immediately. The same list is a row in the on-TV settings, so the choice
     * can be changed later without going through this again.
     *
     * <p>Both the scancode and the keycode are matched: the keylayout (dbg/fixkl.sh) rewrites the
     * button to F6, and the scancode survives that rewrite. adb's "input keyevent 136" carries no
     * scancode and a USB keyboard's real F6 carries no 167, so either test alone would miss a path.
     *
     * <p>This only answers while Home is in front. Inside another app the key goes to that app, which
     * ignores F6, and the firmware kills any accessibility service holding the key (see 开发文档.md
     * §4.6b), so there is deliberately no global path here.
     */
    private boolean handleVoiceKey(KeyEvent event) {
        if (event.getScanCode() != VOICE_SCANCODE && event.getKeyCode() != KeyEvent.KEYCODE_F6) {
            return false;
        }
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) onVoiceKeyPressed();
        return true;
    }

    /**
     * Opens the chosen app, or asks for one when there is none. A package that no longer opens
     * (uninstalled, disabled, its launcher entry removed) counts as "none": the press ends in the
     * list rather than in a dead button, and the stale choice is dropped.
     */
    private void onVoiceKeyPressed() {
        if (!voiceKeyApp.isEmpty() && AppPicker.launch(this, voiceKeyApp)) return;
        if (!voiceKeyApp.isEmpty()) {
            Toast.makeText(this, getString(R.string.voice_key_gone,
                    AppPicker.labelOf(this, voiceKeyApp)), Toast.LENGTH_LONG).show();
            voiceKeyApp = "";
            getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).edit()
                    .remove(Config.VOICE_KEY_APP).apply();
        }
        askVoiceKeyApp();
    }

    /**
     * The first press's guide: pick an app and it is saved and opened at once, so the button shows
     * what it now does. Cancel changes nothing, which makes a stray press harmless.
     */
    private void askVoiceKeyApp() {
        AppPicker.show(this, R.string.voice_key_ask_title, R.string.voice_key_ask_message, 0,
                null, pkg -> {
                    voiceKeyApp = pkg;
                    getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).edit()
                            .putString(Config.VOICE_KEY_APP, pkg).apply();
                    AppPicker.launch(this, pkg);
                });
    }

    /**
     * The context menu for the tile at {@code position}, anchored on the tile the way the stock launcher
     * anchored it: Remove from Home (apps and cards only), Change app position, Uninstall (apps only).
     * OK on the tile still opens the app, so there is no Open row. An empty dock slot and the
     * "Default home" tile get no menu at all - there is nothing to remove or open - so the Menu
     * button falls through to the library instead of opening an empty panel.
     */
    private void showTileMenu(int position) {
        final AppAdapter.Entry entry = adapter.entryAt(position);
        if (entry == null || entry.kind == AppAdapter.Entry.EMPTY) return;
        View anchor = cardAt(position);
        if (anchor == null) return;

        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        // The menu owns the keyboard while it is up. When it closes - Back, a row, a second Menu
        // press - the tile it came from should take the focus back, so the next Menu press reopens
        // it and the glass keeps its anchor. The restore is skipped when focus already sits on a
        // tile: a row that moved it elsewhere (Remove from Home, Uninstall) has the last word.
        PopupWindow popup = glassPopup(panel, (int) (200 * density), () -> {
            View focus = getCurrentFocus();
            if (focus == null || focus.getId() != R.id.card) {
                View tile = cardAt(position);
                if (tile != null) tile.requestFocus();
            }
        });
        boolean isApp = entry.kind == AppAdapter.Entry.APP;

        if (isApp || entry.kind == AppAdapter.Entry.CARD) {
            addMenuRow(rows, popup, R.string.library_remove_shortcut, () -> {
                popup.dismiss();
                removeFromHome(entry.pkg, position);
            });
        }
        addMenuRow(rows, popup, R.string.reorder, () -> {
            popup.dismiss();
            moveTileFromMenu(position);
        });
        if (isApp) {
            addMenuRow(rows, popup, R.string.uninstall_app, () -> {
                popup.dismiss();
                uninstallApp(entry.pkg);
            });
        }
        fitMenuWidth(popup, panel, rows);
        // Flyout beside the tile, never over it: a context menu that covers its own icon reads as a bug.
        // The dock sits in the bottom band, so showAsDropDown is out; the menu goes next to the tile
        // (right in LTR, left in RTL) with a gap, and flips to the other side when there is no room.
        panel.measure(View.MeasureSpec.makeMeasureSpec(popup.getWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.UNSPECIFIED);
        int[] tileAt = new int[2];
        anchor.getLocationOnScreen(tileAt);
        boolean rtl = getResources().getBoolean(R.bool.rtl_menu_bar);
        int gap = (int) (12 * density);
        int margin = (int) (12 * density);
        int tileLeft = tileAt[0];
        // getLocationOnScreen already reports the focused tile's scaled position, but getWidth() is
        // the layout width: the tile grows about 7-12% (focusScale) about its centre, so the visual
        // right edge is width * scaleX. Without it the menu's leading edge tucked under the zoomed card.
        int tileRight = tileAt[0] + (int) (anchor.getWidth() * anchor.getScaleX());
        boolean roomRight = tileRight + gap + popup.getWidth() <= root.getWidth() - margin;
        boolean roomLeft = tileLeft - gap - popup.getWidth() >= margin;
        int x;
        if (rtl ? roomLeft : roomRight) {
            x = rtl ? tileLeft - popup.getWidth() - gap : tileRight + gap;
        } else if (rtl ? roomRight : roomLeft) {
            x = rtl ? tileRight + gap : tileLeft - popup.getWidth() - gap;
        } else {
            x = Math.max(margin, Math.min(tileLeft, root.getWidth() - popup.getWidth() - margin)); // too narrow either way
        }
        int tileCenterY = tileAt[1] + (int) (anchor.getHeight() * anchor.getScaleY() / 2);
        int y = Math.max(margin, Math.min(tileCenterY - panel.getMeasuredHeight() / 2,
                root.getHeight() - panel.getMeasuredHeight() - margin));
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        popup.showAtLocation(getWindow().getDecorView(), Gravity.TOP | Gravity.START, x, y);
        panel.animate().alpha(1f).scaleY(1f).setDuration(reducedMotion ? Motion.REDUCED_MS : 220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        rows.getChildAt(0).requestFocus();
    }

    /** The tile's own view, or null when it is not laid out (scrolled off, or mid-reload). */
    private View cardAt(int position) {
        RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(position);
        return holder == null ? null : holder.itemView.findViewById(R.id.card);
    }

    /**
     * Asks the package installer to remove an app; it shows its own confirmation. Home is force-stopped
     * while the installer is in front, so coming back is a cold start, the same as after system settings.
     */
    private void uninstallApp(String pkg) {
        if (pkg == null) return;
        try {
            startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Takes the app off Home the way the library's "Remove from Home" does, then puts focus somewhere sensible:
     * the tile that slid into this place, or the one before it when the last app went away.
     */
    private void removeFromHome(String pkg, int position) {
        if (pkg == null || !LibraryApps.save(this, pkg, false)) {
            Toast.makeText(this, R.string.library_save_failed, Toast.LENGTH_LONG).show();
            return;
        }
        final String gone = adapter.keyAt(position);
        reloadApps(false);
        final int next = Math.max(0, Math.min(position, adapter.appCount() - 1));
        grid.post(() -> {
            if (isDestroyed() || reordering) return;
            int at = adapter.positionOf(gone);
            focusTile(at >= 0 ? at : next);
        });
    }

    // ---- reorder ---------------------------------------------------------------------------------

    /** Blue the glass turns while reorder mode is on. */
    private static final int REORDER_ACCENT = 0xFF6EC1FF;

    /**
     * Reorder mode (top-bar button, or the tile menu's 更改应用位置 which starts with that tile already
     * in hand). While it is on, every key comes here first:
     * - nothing carried: arrows move focus as usual; OK on a tile picks it up instead of opening the app;
     *   Back ends the mode and saves the order.
     * - a tile carried: arrows move it; Back (or OK) puts it down and the mode stays on for the next one.
     * Other keys (volume, Home) pass through; leaving the launcher ends the mode and saves.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (handleSettingsKey(event)) return true;
        if (!reordering && handleInputSourceKey(event)) return true;
        // Before handleMenuKey: the Le button arrives as MENU (keylayout override) and is told apart
        // by its scancode, so it must be checked first.
        if (!reordering && handleLeKey(event)) return true;
        // The voice button (167 -> F6) is its own shortcut; it must not fall through to the tile menu.
        if (!reordering && handleVoiceKey(event)) return true;
        if (!reordering && handleMenuKey(event)) return true;
        if (!reordering) return super.dispatchKeyEvent(event);
        if (dockEditing) return dispatchDockEdit(event);
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        int code = event.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        boolean back = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE;
        if (movingKey != null) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down) moveBy(code);
                    return true;
                default:
                    if (ok || back) {
                        if (!down) drop();
                        return true;
                    }
                    return super.dispatchKeyEvent(event);
            }
        }
        if (back) {
            if (!down) endReorder();
            return true;
        }
        int pos = focusedTilePosition();
        if (ok && grid.hasFocus()) {
            // RecyclerView can briefly report NO_POSITION during a move or rebind. Still consume
            // OK on the grid: falling through would let the focused card open its app in reorder mode.
            if (!down && pos >= 0) pickUp(pos);
            return true;
        }
        return super.dispatchKeyEvent(event);   // arrows navigate; OK on a top-bar pill works as usual
    }

    /** Adapter position of the focused tile, or -1 when focus is not on a tile. */
    private int focusedTilePosition() {
        View f = getCurrentFocus();
        if (f == null || f.getId() != R.id.card || !(f.getParent() instanceof View)) return -1;
        return grid.getChildAdapterPosition((View) f.getParent());
    }

    /** The reorder button: turns the mode on (focus goes back to the last tile), or off again. */
    private void toggleReorder() {
        if (reordering) {
            endReorder();
            return;
        }
        if (!beginReorder()) return;
        focusTile(Math.max(0, Math.min(lastFocused, adapter.movableCount() - 1)));
        Toast.makeText(this, R.string.reorder_hint, Toast.LENGTH_LONG).show();
    }

    /**
     * "更改应用位置" from a menu: the mode starts with this tile already in hand, so the first arrow
     * press slides it instead of a second OK to pick it up. Back still ends the mode and saves.
     * Entered from the tile menu (that tile) and from the settings menu (the last focused tile).
     * The hint matches: the tile is already in hand, so it never asks for an OK to select it.
     */
    private void moveTileFromMenu(int position) {
        if (reordering) endReorder();
        AppAdapter.Entry entry = adapter.entryAt(position);
        if (entry == null || entry.kind == AppAdapter.Entry.EMPTY) {
            toggleReorder();   // no tile to carry (none focused yet): plain mode on the last tile
            return;
        }
        String key = entry.component.flattenToShortString();
        if (!beginReorder()) return;
        // beginReorder may reload the grid (empty dock slots drop out), so the tile is found by key.
        int at = adapter.positionOf(key);
        if (at >= 0 && at < adapter.movableCount()) {
            focusTile(at);
            pickUp(at);
        } else {
            // The "Default home" tile never moves: start the mode with focus on the last movable one.
            focusTile(Math.max(0, Math.min(position, adapter.movableCount() - 1)));
        }
        Toast.makeText(this, R.string.reorder_hint_moving, Toast.LENGTH_LONG).show();
    }

    /** The start shared by reorder and edit-dock mode. False when there is nothing to move. */
    private boolean beginReorder() {
        if (adapter.movableCount() < 2) return false;
        // Before the reload below: once it has dropped the empty slots, the dock row also shows grid apps.
        dockInSession = new HashSet<>();
        dockedAtStart = adapter.dockFilled();
        for (int i = 0; i < Math.min(dockedAtStart, adapter.movableCount()); i++) dockInSession.add(adapter.keyAt(i));
        adapter.setReordering(true);
        if (adapter.hasEmptySlots()) reloadTiles();   // every position must hold a tile that can move
        reordering = true;
        orderAtStart = adapter.movableKeys();
        glass.setAccent(REORDER_ACCENT);
        btnReorder.setActivated(true);
        return true;
    }

    /** Edit dock (Settings menu): focus goes to the first dock place, which OK then chooses. */
    private void startDockEdit() {
        if (reordering) endReorder();
        if (!beginReorder()) return;
        dockEditing = true;
        dockEditSlot = -1;
        focusTile(0);
        Toast.makeText(this, R.string.dock_edit_hint, Toast.LENGTH_LONG).show();
    }

    /**
     * Keys in edit-dock mode. First OK on a dock tile chooses that place (the other dock tiles dim) and focus moves
     * to the grid; then OK on any tile swaps that app into the place, saves the order and ends the mode. Back goes
     * one step back: from choosing the app to choosing the place, and from there out of the mode.
     */
    private boolean dispatchDockEdit(KeyEvent event) {
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        int code = event.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        boolean back = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE;
        if (back) {
            if (!down) {
                if (dockEditSlot >= 0) {
                    int slot = dockEditSlot;
                    dockEditSlot = -1;
                    dimOthers();
                    focusTile(slot);
                } else {
                    endReorder();
                }
            }
            return true;
        }
        int pos = focusedTilePosition();
        if (ok && pos >= 0) {
            if (!down) {
                int n = adapter.movableCount();
                if (dockEditSlot < 0) {
                    if (pos < Math.min(COLUMNS, n)) {
                        dockEditSlot = pos;
                        dimOthers();
                        focusTile(Math.min(COLUMNS, n - 1));
                        Toast.makeText(this, R.string.dock_edit_pick_app, Toast.LENGTH_LONG).show();
                    }
                } else if (pos < n) {
                    swapIntoDock(dockEditSlot, pos);
                }
            }
            return true;   // never opens the app while editing
        }
        return super.dispatchKeyEvent(event);   // arrows navigate as usual
    }

    /** Puts the app at pos into dock place slot, and the app that was there where the chosen one was; then saves. */
    private void swapIntoDock(int slot, int pos) {
        String slotKey = adapter.keyAt(slot);
        if (pos != slot) {
            keepScroll(() -> {
                adapter.move(pos, slot);
                adapter.move(adapter.positionOf(slotKey), pos);
            });
            dockInSession.add(adapter.keyAt(slot));
            if (pos >= COLUMNS) dockInSession.remove(slotKey);
        }
        endReorder();
        refocusAfterLayout(slot);
    }

    private void pickUp(int pos) {
        if (pos >= adapter.movableCount()) return;   // the "Default home" tile stays put
        movingKey = adapter.keyAt(pos);
        dimOthers();
    }

    /** Puts the carried tile down where it is; the mode stays on. */
    private void drop() {
        movingKey = null;
        dimOthers();
    }

    /** While a tile is carried, every other tile is dimmed so the carried one stands out; otherwise all are full. */
    private void dimOthers() {
        for (int i = 0; i < grid.getChildCount(); i++) {
            View item = grid.getChildAt(i);
            item.setAlpha(tileAlpha(grid.getChildAdapterPosition(item)));
        }
    }

    /** A tile's alpha: dim unless it is the carried one, or, in edit-dock mode, a dock tile other than the chosen place. */
    private float tileAlpha(int pos) {
        if (movingKey != null) {
            return pos != RecyclerView.NO_POSITION && movingKey.equals(adapter.keyAt(pos)) ? 1f : 0.45f;
        }
        if (dockEditSlot >= 0 && pos >= 0 && pos < COLUMNS && pos != dockEditSlot) return 0.45f;
        return 1f;
    }

    /** Ends the mode and saves the order if it changed since the mode started. */
    private void endReorder() {
        if (!reordering) return;
        dockEditing = false;
        dockEditSlot = -1;
        drop();
        reordering = false;
        adapter.setReordering(false);
        glass.setAccent(festive ? festival.accentColor() : 0);
        btnReorder.setActivated(false);
        List<String> keys = adapter.movableKeys();
        List<String> before = orderAtStart;
        orderAtStart = null;
        // fill_dock false: the dock is what the owner put there, saved with its size, so the empty slots that
        // come back after the mode go after it (#499). With fill_dock true every dock slot holds an app anyway.
        int docked = -1;
        if (fillDockLeavesSlots) {
            docked = DockSlots.dockCount(keys, COLUMNS, dockInSession);
            keys = DockSlots.dockFirst(keys, COLUMNS, dockInSession);
        }
        dockInSession = null;
        if (!keys.equals(before) || (fillDockLeavesSlots && docked != dockedAtStart)) {
            Order order = new Order(Order.dockKey(dockApps), keys, docked);
            if (Order.save(Order.file(this), order)) {
                adapter.setSavedOrder(Order.ranks(order, dockApps), Order.docked(order, dockApps));
            } else {
                Toast.makeText(this, R.string.reorder_not_saved, Toast.LENGTH_SHORT).show();
            }
        }
        if (fillDockLeavesSlots) reloadTiles();   // the empty dock slots left out while reordering come back
    }

    /**
     * Moves the carried tile one step. Left and Right wrap across rows, like a list; Up and Down move a whole row,
     * and Down into a short last row lands on its last tile. Nothing moves past either end or up into the top bar.
     */
    private void moveBy(int keyCode) {
        int from = adapter.positionOf(movingKey);
        int n = adapter.movableCount();
        if (from < 0 || from >= n) {
            drop();
            return;
        }
        int to;
        // rtl_app_grid (Hebrew) mirrors the grid: position 0 is on the right, so Left moves towards the end.
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) to = rtlAppGrid ? from + 1 : from - 1;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) to = rtlAppGrid ? from - 1 : from + 1;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) to = from - COLUMNS;
        else if (from + COLUMNS < n) to = from + COLUMNS;
        else to = from / COLUMNS < (n - 1) / COLUMNS ? n - 1 : from;
        if (to < 0 || to >= n || to == from) return;
        keepScroll(() -> adapter.move(from, to));
        // The owner carries this tile: where it lands decides whether it is in the dock, not dock_apps (#499).
        if (to < COLUMNS) dockInSession.add(movingKey);
        else dockInSession.remove(movingKey);
        refocusAfterLayout(to);
    }

    /**
     * Applies an adapter change without the grid jumping. After a move the layout manager anchors on the focused
     * tile, keeping it at its old place on screen and shifting every other row; pinning the first visible row at its
     * current offset as the next layout's anchor keeps the scroll where it was. onTileFocused still scrolls
     * afterwards if the moved tile itself ends up off screen.
     */
    private void keepScroll(Runnable change) {
        GridLayoutManager lm = (GridLayoutManager) grid.getLayoutManager();
        int first = lm.findFirstVisibleItemPosition();
        View firstView = first != RecyclerView.NO_POSITION ? lm.findViewByPosition(first) : null;
        int offset = firstView != null ? lm.getDecoratedTop(firstView) - grid.getPaddingTop() : 0;
        change.run();
        if (firstView != null) lm.scrollToPositionWithOffset(first, offset);
    }

    /**
     * Once the grid has applied the change, put the glass (and focus, if the move dropped it) on the tile at pos.
     * The grid has a fixed size, so it applies adapter changes on the next animation frame rather than in a layout
     * pass; until then it cannot find the tile by position. Wait frame by frame (at most 10) for that. No focus
     * change fires for a moved tile, so focusTile's already-focused path calls onTileFocused itself.
     */
    private void refocusAfterLayout(int pos) {
        grid.postOnAnimation(new Runnable() {
            int frames;

            @Override
            public void run() {
                if ((grid.hasPendingAdapterUpdates() || grid.isLayoutRequested()) && ++frames < 10) {
                    grid.postOnAnimation(this);
                    return;
                }
                focusTile(pos);
                dimOthers();   // views that came into view or were rebound during the move
            }
        });
    }

    @Override
    public void onBackPressed() {
        // Home screen: Back goes nowhere.
    }

    // ---- glass highlight -------------------------------------------------------------------------

    @Override
    public void onLibraryRequested() {
        togglePanel(false);
    }

    /**
     * The Le button while Home is in front. Unlike the top-bar button this one obeys
     * home_le_key_function: the catalogue, or the day's sentence in the same glass panel. Pressing it
     * again closes whichever face is up, and the dismiss listener below still returns the focus.
     */
    private void onLeKeyOnHome() {
        togglePanel(true);
    }

    /**
     * The categories the daily sentence is drawn from on Home, as ticked on the settings screen.
     * This is the row called 一言类别(Le键): one set for both Le panels, the one drawn over Home and
     * the one drawn over another app. The line in Home's top right corner has its own set
     * (hitokoto_categories) and is not affected by it.
     */
    private int leQuoteCategories() {
        int fallback = getResources().getInteger(R.integer.le_hitokoto_categories);
        return getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE)
                .getInt(Config.LE_HITOKOTO_CATEGORIES_KEY, fallback);
    }

    /**
     * @param fromLeKey true for the Le button on Home, which may open the daily sentence instead of
     *                  the catalogue; false for the top-bar 应用库 button, which always opens it.
     */
    private void togglePanel(boolean fromLeKey) {
        // A second press (the Le button, or the top-bar button) closes the library instead of
        // reopening it, so the button is a toggle. The dismiss listener below restores the focus.
        if (appLibrary != null) {
            appLibrary.close();
            return;
        }
        if (openPopup != null) openPopup.dismiss();
        libraryFocusPending = false;
        libraryFromTopBar = false;
        // Remember what had the focus, so closing the library returns there. The Le button is pressed
        // with a tile focused; sending the focus to the footer 应用库 button instead (what this used
        // to do unconditionally) calls onLibraryFocused, which hides the glass and the hero art - and
        // Home is then a black screen until a key is pressed.
        libraryReturnTile = focusedTilePosition();
        View focused = getWindow().getDecorView().findFocus();
        libraryReturnFooter = focused != null && focused.getId() == R.id.btnLibrary;
        appLibrary = new AppLibraryDialog(this, animations, reducedMotion, adapter::containsPackage, () -> reloadApps(false), () -> {
            appLibrary = null;
            openPopup = null;
            final int returnTile = libraryReturnTile;
            final boolean returnFooter = libraryReturnFooter;
            libraryReturnTile = -1;
            libraryReturnFooter = false;
            if (libraryFromTopBar) {
                libraryFromTopBar = false;
                btnLibraryTop.requestFocus();
                return;
            }
            if (returnTile >= 0) {
                // Opened with the Le button from a tile: go back to that tile. Posted because the popup
                // window is still being torn down while this runs.
                grid.post(() -> {
                    if (appLibrary == null && !isFinishing()) focusTile(returnTile);
                });
                return;
            }
            if (returnFooter) {
                // Opened from the footer 应用库 button: that button is where the user was, and going
                // back there keeps the D-pad path unchanged.
                libraryFocusPending = true;
                grid.post(() -> {
                    if (libraryFocusPending && appLibrary == null && hasWindowFocus() && !isFinishing()) focusLibrary();
                });
                return;
            }
            // Le button pressed with a pill (or nothing) focused: fall back to the tile the user was
            // last on rather than to the footer, so the glass and the hero come back.
            grid.post(() -> {
                if (appLibrary == null && !isFinishing()) ensureFocus();
            });
        });
        openPopup = appLibrary.popup;
        if (fromLeKey && homeLeKeyFunction == Config.LE_KEY_HITOKOTO) {
            // Same panel, same dim behind it, the day's sentence where the catalogue would be. A new
            // draw every press, unlike the greeting line, which is fixed for the day.
            appLibrary.showQuote(leQuoteCategories());
        } else {
            appLibrary.show();
        }
    }

    @Override
    public void onTileMenuRequested(int position) {
        if (position >= 0 && !reordering) showTileMenu(position);
    }

    @Override
    public void onTileFocused(int position, View card) {
        lastFocused = position;
        lastFocusedKey = adapter.keyAt(position);
        // Focus restored before the first layout (cold start): the tile has no size yet, and the glass
        // would be sized to 2*pad. Run again once the tile is laid out.
        if (card.getWidth() == 0) {
            card.post(() -> {
                if (card.isFocused() && card.getWidth() > 0) onTileFocused(position, card);
            });
            return;
        }
        // Tile rect in root coordinates, using layout positions (ignores the scale animation).
        Rect r = tileRect;
        r.set(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        float pad = 6f * density;        // matches GlassView's glow inset
        float scale = adapter.focusScale();
        float w = card.getWidth() * scale + 2 * pad;
        float h = card.getHeight() * scale + 2 * pad;
        float x = r.exactCenterX() - w / 2f;
        float y = r.exactCenterY() - h / 2f - (advancedEffects ? 4f * density : 0f);

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h || card.getWidth() != glassCardW) {
            lp.width = (int) w;
            lp.height = (int) h;
            glass.setLayoutParams(lp);
            // GlassView draws its slab inset by pad, i.e. exactly over the zoomed tile: same corners as the tile.
            glass.setRadius(AppAdapter.cornerRadius(card.getWidth(), density) * scale);
        }
        glassCardW = card.getWidth();
        glassTargetX = x;
        glassTargetY = y;
        // Cinematic shelf: the highlight glows in the dock app's colour, plain white in the grid. app_glow: every tile.
        boolean coloured = (appGlow || cinematic && position < COLUMNS) && !festive;
        int accent = coloured ? adapter.ambientColorAt(position) : 0;
        if ((cinematic || appGlow) && !festive && accent != glassAccent) {
            glassAccent = accent;
            glass.setAccent(accent);
        }
        if (!glassShown) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            if (animations && !reducedMotion) glass.animate().alpha(1f).setDuration(180).start();
            else glass.setAlpha(1f);
            glassShown = true;
        } else if (!animations) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
        } else {
            springX.animateToFinalPosition(x);
            springY.animateToFinalPosition(y - glassScrollShift);
        }
        View item = (View) card.getParent();
        if (dockRow && position < COLUMNS) {
            // Dock: park it at the bottom of the screen and show the top shelf for this app.
            int decoratedTop = item.getTop() - (int) (6 * density);
            int dy = decoratedTop - grid.getPaddingTop();
            // A 1px mismatch used to start a scroll on every dock move. Only scroll when the row is
            // actually off; reduced motion does it in one jump so it does not fight the highlight.
            boolean scroll = Math.abs(dy) > (int) (8 * density);
            if (scroll) {
                if (!animations || reducedMotion) grid.scrollBy(0, dy);
                else grid.smoothScrollBy(0, dy);
                // The scroll moves the row, so the glass has to follow once it settled. Without this the
                // highlight stays where the row was before the scroll and lands above the dock (#134).
                refitAfterScroll = true;
                grid.post(() -> {
                    if (isDestroyed() || !refitAfterScroll) return;
                    refitAfterScroll = false;
                    if (!card.isFocused()) return;
                    RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(position);
                    View c = vh == null ? null : vh.itemView.findViewById(R.id.card);
                    if (c != null && c.isFocused()) onTileFocused(position, c);
                });
            }
            showHero(position, scroll ? r.top - dy : r.top);
        } else {
            // Below the dock (or in a plain grid) the glow follows the tile without the big name; letting it
            // fade out left the grid on plain black.
            showGlowOnly(position);
            // Scroll just enough so the whole glass (not only the tile) is visible.
            revealRect.set(0, -(int) pad * 2, item.getWidth(), item.getHeight() + (int) pad);
            grid.requestChildRectangleOnScreen(item, revealRect, reducedMotion);
        }
        // Focus just landed on a tile: while one is carried, tiles that scrolled into view (or came
        // back after a reload) need their dimming too. The tile menu's "更改应用位置" picks a tile up
        // before any key press, so no move follows to do it.
        if (movingKey != null) dimOthers();
    }

    /** Refits the glass to the focused holder once the grid layout and tile dimensions settle. */
    private void refitGlass() {
        if (isDestroyed() || adapter.appCount() == 0 || libraryFocusPending) return;
        int pos = Math.max(0, Math.min(lastFocused, adapter.appCount() - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        View card = vh == null ? null : vh.itemView.findViewById(R.id.card);
        if (card == null || card.getWidth() <= 0 || !card.isFocused()) return;
        // The tile may have moved without changing size (the dock row parks at the bottom, the grid's
        // padding changes), so the position is checked too, not only the size (#134).
        Rect r = tileRect;
        r.set(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        float pad = 6f * density;
        float scale = adapter.focusScale();
        float w = card.getWidth() * scale + 2 * pad;
        float h = card.getHeight() * scale + 2 * pad;
        float x = r.exactCenterX() - w / 2f;
        float y = r.exactCenterY() - h / 2f - (advancedEffects ? 4f * density : 0f);
        boolean moved = Math.abs(x - glassTargetX) > 1f || Math.abs(y - glassTargetY) > 1f;
        if (!glassShown || moved || card.getWidth() != glassCardW
                || glass.getWidth() != w || glass.getHeight() != h) {
            onTileFocused(pos, card);
        }
    }

    // ---- top shelf -------------------------------------------------------------------------------

    private void showHero(int position, float dockTopInRoot) {
        String label = adapter.labelAt(position);
        int color = adapter.ambientColorAt(position);
        float y = dockTopInRoot - 14 * density - 26 * density - hero.getHeight();
        boolean changed = !label.contentEquals(heroName.getText());
        if (cinematic) showHeroArt(position);

        if (!animations || reducedMotion) {
            // Snap the shelf instead of sliding and fading it: each of those frames repaints the
            // full-screen panel, and this switch exists for the GPUs that cannot afford it.
            hero.animate().cancel();
            ambient.animate().cancel();
            fillHero(position, label);
            hero.setY(y);
            hero.setTranslationX(0f);
            hero.setAlpha(1f);
            if (ambientGlow) {
                setAmbient(color);
                ambient.setAlpha(1f);
            }
            heroShown = true;
            return;
        }

        if (!heroShown) {
            stopAmbientAnimation();
            hero.animate().cancel();
            ambient.animate().cancel();
            fillHero(position, label);
            hero.setY(y);
            hero.setTranslationX(0f);
            hero.animate().alpha(1f).setDuration(260).start();
            if (ambientGlow) {
                setAmbient(color);
                ambient.animate().alpha(1f).setDuration(600).start();
            }
            heroShown = true;
        } else if (changed) {
            if (advancedEffects) hero.animate().cancel();
            hero.animate().alpha(0f).translationX(-12 * density).setDuration(120).withEndAction(() -> {
                fillHero(position, label);
                hero.setY(y);
                hero.setTranslationX(12 * density);
                hero.animate().alpha(1f).translationX(0f).setDuration(200).start();
            }).start();
            if (ambientGlow && advancedEffects) {
                transitionAmbient(color);
            } else if (ambientGlow && color != ambientColor) {
                ambient.animate().alpha(0.35f).setDuration(200).withEndAction(() -> {
                    setAmbient(color);
                    ambient.animate().alpha(1f).setDuration(500).start();
                }).start();
            }
        } else {
            hero.setY(y);
        }
    }

    /** The shelf's text for this dock app: its name and, on the cinematic shelf, "Last opened" and the dots. */
    private void fillHero(int position, String label) {
        heroName.setText(label);
        if (!cinematic) return;
        updateHeroMeta(position);
        updateHeroDots(position);
    }

    // ---- cinematic shelf -------------------------------------------------------------------------

    /** The parts that never change: a larger name, the meta line and the dots, the artwork and the scrim. */
    private void setUpCinematicShelf() {
        heroName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 68f);
        heroName.setLetterSpacing(-0.025f);
        heroMeta.setVisibility(View.INVISIBLE);   // keeps its height, so the name does not jump when it fills in
        heroDots.setVisibility(View.VISIBLE);
        heroArt.setVisibility(View.VISIBLE);
        // One stretched bitmap: apply its alpha while drawing instead of through an offscreen buffer.
        noOverlappingRendering(heroArt);
        View scrim = findViewById(R.id.heroScrim);
        scrim.setBackground(scrimDrawable());
        scrim.setVisibility(View.VISIBLE);
    }

    /**
     * Darkens the side the shelf's text is on and the lower part of the screen, so the name, the dock and
     * the grid stay readable over the artwork. One small bitmap stretched over the screen, like the glow.
     */
    private Drawable scrimDrawable() {
        int w = 480, h = 270;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        boolean rtl = getResources().getBoolean(R.bool.rtl_menu_bar);
        Paint paint = new Paint();
        paint.setShader(new LinearGradient(rtl ? w : 0, 0, rtl ? 0 : w, 0,
                new int[]{0xD1060406, 0x59060406, 0x00060406}, new float[]{0f, 0.42f, 0.7f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0x00060406, 0x00060406, 0xDB060406, 0xFF060406}, new float[]{0f, 0.28f, 0.58f, 0.76f},
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        BitmapDrawable scrim = new BitmapDrawable(getResources(), bmp);
        scrim.setGravity(Gravity.FILL);
        return scrim;
    }

    /** Swaps the backdrop to this app's artwork: fade out, swap, fade in, the way the shelf's name changes. */
    private void showHeroArt(int position) {
        String key = adapter.keyAt(position);
        if (key == null) return;
        heroArt.animate().cancel();
        if (key.equals(heroArtKey)) {
            fadeHeroArt(420);
            return;
        }
        Bitmap art = heroArtCache.get(key);
        if (art == null) {
            art = HeroArt.from(adapter.artworkAt(position));
            if (art != null) heroArtCache.put(key, art);
        }
        heroArtKey = key;
        final Bitmap next = art;
        if (!animations || reducedMotion || heroArt.getAlpha() == 0f) {
            heroArt.setImageBitmap(next);
            fadeHeroArt(600);
            return;
        }
        heroArt.animate().alpha(0f).setDuration(140).withEndAction(() -> {
            heroArt.setImageBitmap(next);
            heroArt.animate().alpha(HERO_ART_ALPHA).setDuration(420).start();
        }).start();
    }

    private void fadeHeroArt(int ms) {
        if (!animations) heroArt.setAlpha(HERO_ART_ALPHA);
        else heroArt.animate().alpha(HERO_ART_ALPHA).setDuration(reducedMotion ? Math.min(ms, Motion.REDUCED_MS) : ms).start();
    }

    /** "Last opened yesterday, 9:40 PM", the time in white; nothing (but the same height) when it was never seen. */
    private void updateHeroMeta(int position) {
        long when = LastOpened.get(this, adapter.packageAt(position));
        if (when <= 0) {
            heroMeta.setVisibility(View.INVISIBLE);
            return;
        }
        String rel = LastOpened.describe(this, when);
        String text = getString(R.string.last_opened, rel);
        SpannableString line = new SpannableString(text);
        int at = text.indexOf(rel);
        if (at >= 0) line.setSpan(new ForegroundColorSpan(0xFFFFFFFF), at, at + rel.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        heroMeta.setText(line);
        heroMeta.setVisibility(View.VISIBLE);
    }

    /** One dot per dock app, the focused one drawn long and white. Hebrew docks start on the right, so do the dots. */
    private void updateHeroDots(int position) {
        int count = Math.min(adapter.dockFilled(), COLUMNS);
        heroDots.setVisibility(count > 1 ? View.VISIBLE : View.INVISIBLE);
        if (heroDots.getChildCount() != count) {
            heroDots.removeAllViews();
            for (int i = 0; i < count; i++) {
                View dot = new View(this);
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(3 * density);
                dot.setBackground(bg);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (6 * density), (int) (6 * density));
                if (i > 0) lp.leftMargin = (int) (6 * density);
                heroDots.addView(dot, lp);
            }
        }
        for (int i = 0; i < count; i++) {
            View dot = heroDots.getChildAt(rtlAppGrid ? count - 1 - i : i);
            boolean on = i == position;
            ((GradientDrawable) dot.getBackground()).setColor(on ? 0xFFFFFFFF : 0x4DFFFFFF);
            ViewGroup.LayoutParams lp = dot.getLayoutParams();
            int w = (int) ((on ? 20 : 6) * density);
            if (lp.width != w) {
                lp.width = w;
                dot.setLayoutParams(lp);
            }
        }
    }

    /**
     * The glow used to be a full-screen radial GradientDrawable. On Android 10 that drawable is
     * drawn on the CPU, so fading it for half a second redrew 1920×1080 every frame and the
     * highlight looked like it was running at a few frames per second. A small bitmap stretched
     * over the screen is one texture. It is painted once in white and tinted with the app's colour,
     * so changing apps neither repaints it on the CPU (about 11 ms) nor uploads it again.
     */
    private void setAmbient(int color) {
        if (color == ambientColor && ambient.getBackground() != null) return;
        ambientColor = color;
        BitmapDrawable glow = (BitmapDrawable) ambient.getBackground();
        if (glow == null) {
            int w = 480;
            int h = 270;
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            if (cinematic) {
                // Higher and stronger, from the corner away from the shelf's text: the app's colour floods
                // the top of the screen over its artwork, and the scrim keeps the text side dark.
                boolean rtl = getResources().getBoolean(R.bool.rtl_menu_bar);
                paint.setShader(new RadialGradient(
                        w * (rtl ? 0.20f : 0.80f), h * 0.06f, w * 0.78f,
                        new int[]{0xA6FFFFFF, 0x47FFFFFF, 0x00FFFFFF},
                        new float[]{0f, 0.5f, 1f},
                        Shader.TileMode.CLAMP));
            } else if (Config.GLOW_HORIZON.equals(glowStyle)) {
                // The colour washes down from the top edge and fades out three quarters of the way down,
                // with a brighter band along the top like light over a horizon.
                paint.setShader(new LinearGradient(0, 0, 0, h * 0.75f,
                        new int[]{0x66FFFFFF, 0x24FFFFFF, 0x00FFFFFF},
                        new float[]{0f, 0.4f, 1f},
                        Shader.TileMode.CLAMP));
                canvas.drawRect(0, 0, w, h, paint);
                paint.setShader(new RadialGradient(
                        w * 0.5f, -h * 0.25f, w * 0.55f,
                        new int[]{0x40FFFFFF, 0x00FFFFFF},
                        null,
                        Shader.TileMode.CLAMP));
            } else {
                paint.setShader(new RadialGradient(
                        w * 0.32f, h * 0.42f, w * 0.62f,
                        new int[]{0x5CFFFFFF, 0x26FFFFFF, 0x00FFFFFF},
                        new float[]{0f, 0.45f, 1f},
                        Shader.TileMode.CLAMP));
            }
            canvas.drawRect(0, 0, w, h, paint);
            glow = new BitmapDrawable(getResources(), bmp);
            glow.setGravity(Gravity.FILL);
            ambient.setBackground(glow);
        }
        // The default tint mode, SRC_IN, keeps the mask's alpha and takes the colour from the tint.
        glow.setTint(0xFF000000 | color);
    }

    private void transitionAmbient(int color) {
        // A rapid focus change continues from the colour actually displayed on screen.
        stopAmbientAnimation();
        ambient.animate().cancel();
        if (color == ambientColor) {
            ambient.animate().alpha(1f).setDuration(180).start();
            return;
        }
        ambient.animate().alpha(1f).setDuration(180).start();
        ambientAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), ambientColor, color);
        ambientAnimator.setDuration(360);
        ambientAnimator.addUpdateListener(animation -> setAmbient((int) animation.getAnimatedValue()));
        ambientAnimator.start();
    }

    private void stopAmbientAnimation() {
        if (ambientAnimator != null) {
            ambientAnimator.cancel();
            ambientAnimator = null;
        }
    }

    /**
     * Grid tiles (every tile with dock_row false): the glow behind the grid takes the focused tile's colour; the big
     * name above the dock does not show (every grid tile has its name underneath).
     */
    private void showGlowOnly(int position) {
        int color = adapter.ambientColorAt(position);
        stopAmbientAnimation();
        hero.animate().cancel();
        ambient.animate().cancel();
        hero.setAlpha(0f);
        heroArt.animate().cancel();
        heroArt.setAlpha(0f);
        heroShown = false;
        if (!ambientGlow) return;
        if (!animations || reducedMotion) {
            setAmbient(color);
            ambient.setAlpha(1f);
            return;
        }
        if (color == ambientColor && ambient.getBackground() != null) {
            ambient.animate().alpha(1f).setDuration(300).start();
            return;
        }
        // The same dip-and-swap as the dock's top shelf.
        ambient.animate().alpha(0.35f).setDuration(ambient.getAlpha() > 0.35f ? 200 : 0).withEndAction(() -> {
            setAmbient(color);
            ambient.animate().alpha(1f).setDuration(500).start();
        }).start();
    }

    private void hideHero() {
        if (!heroShown) return;
        stopAmbientAnimation();
        hero.animate().cancel();
        ambient.animate().cancel();
        heroArt.animate().cancel();
        heroShown = false;
        if (animations && !reducedMotion) {
            hero.animate().alpha(0f).setDuration(160).start();
            if (ambientGlow) ambient.animate().alpha(0f).setDuration(300).start();
            heroArt.animate().alpha(0f).setDuration(300).start();
        } else {
            hero.setAlpha(0f);
            ambient.setAlpha(0f);
            heroArt.setAlpha(0f);
        }
    }

    private void hideGlass() {
        if (!glassShown) return;
        glassShown = false;
        glass.animate().cancel();
        if (animations && !reducedMotion) glass.animate().alpha(0f).setDuration(160).start();
        else glass.setAlpha(0f);
    }

    // ---- optional system stats (config.xml show_stats) --------------------------------------------

    /** Background reader. Started only while Home is resumed and show_stats is on. */
    private HandlerThread statsThread;
    /** The current run's handler; a run whose handler is no longer this one stops (volatile: read off-thread). */
    private volatile Handler statsBg;

    private void startStats() {
        if (statsThread != null) return;
        stats.setVisibility(View.VISIBLE);
        cpuTotalPrev = -1;
        idlePrev = -1;
        procStatDead = false;
        statsThread = new HandlerThread("stats");
        statsThread.start();
        // Each start gets its own handler, held by its task: stopStats on the main thread can clear the field
        // at any moment without the reader tripping over it.
        Handler bg = new Handler(statsThread.getLooper());
        statsBg = bg;
        bg.post(new Runnable() {
            @Override
            public void run() {
                if (statsBg != bg) return;
                String line = readStatsLine();
                handler.post(() -> {
                    if (showStats && stats != null && statsBg == bg) stats.setText(line);
                });
                if (statsBg == bg) bg.postDelayed(this, STATS_MS);
            }
        });
    }

    private void stopStats() {
        if (statsThread == null) return;
        statsBg.removeCallbacksAndMessages(null);
        statsThread.quit();
        statsThread = null;
        statsBg = null;
        // Paused (the quick panel or an app on top): keep the last line on screen. Taking it away moved the grid up
        // under a glass highlight that stayed put.
        if (!showStats) stats.setVisibility(View.GONE);
    }

    /** One quiet line, e.g. "CPU 8%  ·  Memory 430 MB free  ·  Storage 3.1 GB free". Runs off the main thread. */
    private static void appendStat(StringBuilder line, String part) {
        if (line.length() > 0) line.append("  ·  ");
        line.append(part);
    }

    private String readStatsLine() {
        int cpu = cpuPercent();
        if (cpu < 0) {
            try {
                Thread.sleep(200);             // second sample, so the first line already has a CPU figure
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "";
            }
            cpu = cpuPercent();
        }
        // Android 8 and later hide /proc/stat from apps on many TVs: then the line leaves CPU out rather than
        // showing a dash that never fills in.
        StringBuilder line = new StringBuilder();
        if (cpu >= 0) line.append(getString(R.string.stats_cpu, cpu));
        long[] mem = memUsedOfTotalMb();
        if (mem != null) appendStat(line, getString(R.string.stats_memory, mem[0], mem[1]));
        String free = formatGb(getResources(), Environment.getDataDirectory().getFreeSpace());
        if (!free.isEmpty()) appendStat(line, getString(R.string.stats_storage, free));
        return line.toString();
    }

    // -1: need another sample. -2: this source cannot be read (Android 9 hides /proc/stat from apps).
    private static final int CPU_UNREADABLE = -2;
    private long cpuTotalPrev = -1, cpuIdlePrev = -1;
    private boolean procStatDead;
    private long idlePrev = -1, wallPrev = -1;

    private int cpuPercent() {
        if (!procStatDead) {
            int fromStat = cpuFromProcStat();
            if (fromStat != CPU_UNREADABLE) return fromStat;
            procStatDead = true;
            idlePrev = -1;
        }
        return cpuFromIdle();
    }

    private int cpuFromProcStat() {
        String s = readFirstLine("/proc/stat");
        if (s == null || !s.startsWith("cpu")) return CPU_UNREADABLE;
        String[] t = s.trim().split("\\s+");
        if (t.length < 5) return CPU_UNREADABLE;
        try {
            long user = Long.parseLong(t[1]), nice = Long.parseLong(t[2]), sys = Long.parseLong(t[3]);
            long idle = Long.parseLong(t[4]);
            long iowait = t.length > 5 ? Long.parseLong(t[5]) : 0;
            long irq = t.length > 6 ? Long.parseLong(t[6]) : 0;
            long soft = t.length > 7 ? Long.parseLong(t[7]) : 0;
            long total = user + nice + sys + idle + iowait + irq + soft;
            long idleAll = idle + iowait;
            if (cpuTotalPrev < 0) {
                cpuTotalPrev = total;
                cpuIdlePrev = idleAll;
                return -1;
            }
            long dt = total - cpuTotalPrev, di = idleAll - cpuIdlePrev;
            cpuTotalPrev = total;
            cpuIdlePrev = idleAll;
            if (dt <= 0) return -1;
            int busy = (int) Math.round((dt - di) * 100.0 / dt);
            return Math.max(0, Math.min(100, busy));
        } catch (NumberFormatException e) {
            return CPU_UNREADABLE;
        }
    }

    /** Busy percent from cpuidle time (microseconds) across the cores. -1 until the second sample. */
    private int cpuFromIdle() {
        long idle = 0;
        int cores = 0;
        for (int i = 0; i < 8; i++) {
            boolean any = false;
            for (int state = 0; state < 8; state++) {
                String t = readFirstLine("/sys/devices/system/cpu/cpu" + i
                        + "/cpuidle/state" + state + "/time");
                if (t == null) break;
                try {
                    idle += Long.parseLong(t.trim());
                    any = true;
                } catch (NumberFormatException e) {
                    break;
                }
            }
            if (!any) break;
            cores++;
        }
        if (cores == 0) return -1;
        long wall = SystemClock.elapsedRealtime() * 1000L;
        if (idlePrev < 0) {
            idlePrev = idle;
            wallPrev = wall;
            return -1;
        }
        long di = idle - idlePrev;
        long dw = (wall - wallPrev) * cores;
        idlePrev = idle;
        wallPrev = wall;
        if (dw <= 0) return -1;
        int busy = (int) Math.round((dw - di) * 100.0 / dw);
        return Math.max(0, Math.min(100, busy));
    }

    /** RAM usage as {used, total} in MB, used = MemTotal - MemAvailable, e.g. "945 / 1536 MB". Used over
     *  free so the line reads as a load against a known whole; null when /proc/meminfo cannot be read. */
    private long[] memUsedOfTotalMb() {
        long available = -1, total = -1;
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/meminfo"))) {
            String line;
            String[] t;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("MemAvailable:")) {
                    t = line.split("\\s+");
                    if (t.length >= 2) available = Long.parseLong(t[1]) / 1024;
                } else if (line.startsWith("MemTotal:")) {
                    t = line.split("\\s+");
                    if (t.length >= 2) total = Long.parseLong(t[1]) / 1024;
                }
            }
        } catch (IOException | NumberFormatException ignored) {
        }
        if (available < 0 || total <= 0) return null;
        return new long[]{Math.max(0, total - available), total};
    }

    private static String readFirstLine(String path) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(path))) {
            return r.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    private static String formatGb(Resources res, long bytes) {
        if (bytes <= 0) return "";
        double gb = bytes / (1024.0 * 1024 * 1024);
        return gb >= 10 ? res.getString(R.string.storage_gb_whole, gb)
                : res.getString(R.string.storage_gb_decimal, gb);
    }

    // ---- daily sentence (config.xml show_hitokoto) ------------------------------------------------

    private void loadHitokoto() {
        if (!showHitokoto) {
            hitokotoBox.setVisibility(View.GONE);
            return;
        }
        // The pool and the two ways to pick live in Hitokoto/Sentences: the Le-key panel draws from
        // the same pool, with the same category filter, but a new sentence on every open. Home's
        // draw is derived from the date, so it holds still all day and comes back after a restart.
        Hitokoto.loadDaily(this, handler, hitokotoCategories, this::renderHitokoto);
    }

    private void renderHitokoto(String text, String from, String who) {
        hitokotoText.setText(text);
        String source = Hitokoto.source(from, who);
        hitokotoFrom.setText(source);
        hitokotoFrom.setVisibility(source.isEmpty() ? View.GONE : View.VISIBLE);
        hitokotoBox.setVisibility(View.VISIBLE);
    }

    // ---- weather ------------------------------------------------------------------------------

    /**
     * Draws the line, or hides it. Hidden is the answer to three different situations - no key, no
     * network and nothing cached, or a line old enough to be about yesterday - and all three look
     * the same from here: a gap where the weather was.
     */
    private void renderWeather(Weather.Snapshot snap) {
        if (weatherMode == Config.WEATHER_OFF || weatherApiKey.isEmpty() || snap == null
                || Weather.expired(snap)
                || snap.city.isEmpty() || snap.temp == 0 && snap.text.isEmpty()) {
            weatherBox.setVisibility(View.GONE);
            weatherExtra.setVisibility(View.GONE);
            weatherSun.setVisibility(View.GONE);
            return;
        }
        // Line one: the sky. Both of the extra numbers come out of the same read as the
        // temperature, so they cost nothing to show; a field the answer did not carry is -1,
        // which is what "say nothing" looks like - a real 0 (night, desert air) is worth printing.
        StringBuilder line = new StringBuilder();
        line.append(snap.temp).append('°').append(' ').append(snap.city);
        if (!snap.text.isEmpty()) line.append(" · ").append(snap.text);
        if (snap.max != 0 || snap.min != 0) {
            line.append(" · 最高 ").append(snap.max).append("° 最低 ").append(snap.min).append('°');
        }
        weather.setText(line);
        weatherIcon.setImageResource(Weather.iconFor(snap.code));
        weatherBox.setVisibility(View.VISIBLE);
        // Line two: humidity and ultraviolet, in the sky's own type. Only the parts that answered.
        StringBuilder extra = new StringBuilder();
        if (snap.humidity >= 0) extra.append("湿度 ").append(snap.humidity).append('%');
        if (snap.uv >= 0) {
            if (extra.length() > 0) extra.append(" · ");
            extra.append("紫外线 ").append(snap.uv);
        }
        // The second line only exists in the two levels that ask for it.
        if (extra.length() > 0 && (weatherMode == Config.WEATHER_EXTRA
                || weatherMode == Config.WEATHER_EXTRA_SUN)) {
            weatherExtra.setText(extra);
            weatherExtra.setVisibility(View.VISIBLE);
        } else {
            weatherExtra.setVisibility(View.GONE);
        }
        // Line three: the sun, and only when the day actually has one.
        if ((weatherMode == Config.WEATHER_SUN || weatherMode == Config.WEATHER_EXTRA_SUN)
                && (!snap.sunrise.isEmpty() || !snap.sunset.isEmpty())) {
            StringBuilder sun = new StringBuilder("日出 ");
            sun.append(snap.sunrise.isEmpty() ? "--:--" : snap.sunrise);
            sun.append(" · 日落 ").append(snap.sunset.isEmpty() ? "--:--" : snap.sunset);
            weatherSun.setText(sun);
            weatherSun.setVisibility(View.VISIBLE);
        } else {
            weatherSun.setVisibility(View.GONE);
        }
    }

    /**
     * Reads the sky when the line has gone stale. Off the main thread, and the callback lands back
     * on it - the same shape as the sentence. Nothing is read without a key, so a TV that never
     * wanted weather never opens a connection for it.
     */
    private void refreshWeather() {
        if (weatherMode == Config.WEATHER_OFF || weatherApiKey.isEmpty()) {
            weatherBox.setVisibility(View.GONE);
            weatherExtra.setVisibility(View.GONE);
            weatherSun.setVisibility(View.GONE);
            return;
        }
        Weather.Snapshot cached = Weather.load(this);
        if (!Weather.stale(cached)) {
            renderWeather(cached);
            return;
        }
        Weather.refresh(this, handler, weatherCity, weatherApiKey, weatherApiHost, this::renderWeather);
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        int count = adapter.appCount();
        int target;
        // Horizontal: swallow the key at a row's ends so focus never wraps to the next row or
        // escapes up into the top bar. Mid-row moves fall through to the default focus search.
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            boolean forward = rtlAppGrid ? keyCode == KeyEvent.KEYCODE_DPAD_LEFT : keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
            if (forward && position == adapter.focusablePosition(count - 1) && adapter.isLibraryAt(count)) {
                focusLibrary();
                return true;
            }
            // In the dock, the last app is the row's end: the empty slots after it take no focus.
            int rowCount = position < COLUMNS ? Math.min(count, adapter.dockFilled()) : count;
            return GridNavigation.atHorizontalEdge(position, rowCount, COLUMNS,
                    rtlAppGrid, keyCode == KeyEvent.KEYCODE_DPAD_LEFT);
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < COLUMNS) return false;          // dock row: let focus move to the top bar
            target = position - COLUMNS;
        } else {
            int lastRowStart = ((count - 1) / COLUMNS) * COLUMNS;
            if (position >= lastRowStart) {
                if (adapter.isLibraryAt(count)) focusLibrary();
                return true;
            }
            target = Math.min(position + COLUMNS, count - 1);
        }
        focusTile(target);
        // The default focus search clicks for Left/Right; Up/Down are handled here, so click for them too.
        grid.playSoundEffect(keyCode == KeyEvent.KEYCODE_DPAD_UP
                ? SoundEffectConstants.NAVIGATION_UP : SoundEffectConstants.NAVIGATION_DOWN);
        return true;
    }

    @Override public void onLibraryFocused() {
        libraryFocusPending = false;
        hideGlass();
        hideHero();
    }

    /** The first top-bar button after Inputs that is shown: the network pill, Reorder, then Settings (always there). */
    private View firstTopButton() {
        if (btnWifi.getVisibility() == View.VISIBLE) return btnWifi;
        if (btnReorder.getVisibility() == View.VISIBLE) return btnReorder;
        return findViewById(R.id.btnSettings);
    }

    /**
     * What a source menu hangs under. The Inputs pill is the natural anchor, but a theme can turn it off
     * (show_inputs_pill): the remote's source key still opens the menu, and a GONE view has no position on
     * screen, so the menu would drop in at the top-left corner. The Wi-Fi pill stands in for it.
     */
    private View sourceAnchor() {
        return btnInputs.getVisibility() == View.VISIBLE ? btnInputs : firstTopButton();
    }

    @Override public void onLibraryUp() {
        if (adapter.appCount() == 0) { firstTopButton().requestFocus(); return; }
        int lastRow = ((adapter.appCount() - 1) / COLUMNS) * COLUMNS;
        focusTile(Math.min(lastRow + lastFocused % COLUMNS, adapter.appCount() - 1));
    }

    private void focusLibrary() {
        cancelPendingFocusScroll();
        int position = adapter.appCount();
        if (!adapter.isLibraryAt(position)) return;
        RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(position);
        if (holder != null) {
            View button = holder.itemView.findViewById(R.id.btnLibrary);
            Rect visible = new Rect();
            if (button.getGlobalVisibleRect(visible) && visible.height() >= button.getHeight()) {
                if (button.requestFocus()) libraryFocusPending = false;
                return;
            }
        }
        if (!animations) {
            grid.scrollToPosition(position);
            grid.post(() -> {
                if (appLibrary != null || !hasWindowFocus()) return;
                RecyclerView.ViewHolder footer = grid.findViewHolderForAdapterPosition(adapter.appCount());
                if (footer != null && adapter.isLibraryAt(adapter.appCount())
                        && footer.itemView.findViewById(R.id.btnLibrary).requestFocus()) libraryFocusPending = false;
            });
            return;
        }
        pendingFocusScroll = new RecyclerView.OnScrollListener() {
            @Override public void onScrollStateChanged(@NonNull RecyclerView rv, int state) {
                if (state != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                if (pendingFocusScroll != this) return;
                pendingFocusScroll = null;
                RecyclerView.ViewHolder footer = rv.findViewHolderForAdapterPosition(position);
                if (footer != null && footer.itemView.findViewById(R.id.btnLibrary).requestFocus())
                    libraryFocusPending = false;
            }
        };
        grid.addOnScrollListener(pendingFocusScroll);
        grid.smoothScrollToPosition(position);
    }

    private void cancelPendingFocusScroll() {
        if (pendingFocusScroll != null) {
            grid.removeOnScrollListener(pendingFocusScroll);
            pendingFocusScroll = null;
            grid.stopScroll();
        }
    }

    private void focusTile(int position) {
        libraryFocusPending = false;
        cancelPendingFocusScroll();
        if (adapter.appCount() == 0) {
            if (adapter.isLibraryAt(0)) focusLibrary();
            else firstTopButton().requestFocus();
            return;
        }
        final int pos = adapter.focusablePosition(Math.max(0, Math.min(position, adapter.appCount() - 1)));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            View card = vh.itemView.findViewById(R.id.card);
            // Already focused (e.g. back from an app): no focus change fires, so restore the glass by hand.
            if (card.isFocused()) onTileFocused(pos, card);
            else card.requestFocus();
            return;
        }
        // Not laid out yet: scroll there, then focus once the scroll settles.
        if (!animations) {
            grid.scrollToPosition(pos);
            grid.post(() -> {
                RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(pos);
                if (h != null) h.itemView.findViewById(R.id.card).requestFocus();
            });
            return;
        }
        pendingFocusScroll = new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                if (pendingFocusScroll != this) return;
                pendingFocusScroll = null;
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pos);
                View target = h != null ? h.itemView.findViewById(R.id.card) : rv.getChildAt(0);
                if (target != null) target.requestFocus();
            }
        };
        grid.addOnScrollListener(pendingFocusScroll);
        grid.smoothScrollToPosition(pos);
    }

    /**
     * Puts the focus back on a tile when Home comes back with nothing focused.
     *
     * Every path that moves the focus here - a rebuild, a return from an app, a cancelled scroll - can
     * end with the RecyclerView itself holding the focus and no card under it (reproduced: open an app
     * from the dock, press Back, and the focused view is the grid). Nothing then draws the backdrop,
     * the glass or the hero name, so Home is simply black until a key is pressed. The usual reason a
     * card cannot take the focus is touch mode (a phone remote or a mouse puts the set in it), which is
     * why item_app.xml marks the card focusableInTouchMode.
     */
    private void ensureFocus() {
        if (isFinishing() || adapter == null || adapter.appCount() == 0) return;
        View focused = getWindow().getDecorView().findFocus();
        // A pill, the library button or a tile keeps its focus; only the empty cases are repaired here.
        // Anything that cannot be clicked counts as empty too: the window root or the grid itself
        // holding the focus draws no backdrop, glass or hero name, so Home stays black until a key.
        if (focused != null && focused.isClickable()) return;
        // Repairing now: a scroll that is still on its way would take the focus back afterwards.
        cancelPendingFocusScroll();
        int pos = adapter.focusablePosition(Math.max(0, Math.min(lastFocused, adapter.appCount() - 1)));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null && vh.itemView.findViewById(R.id.card).requestFocus()) return;
        // The tile we came back to is not laid out: take any tile that is, rather than stay black.
        for (int i = 0; i < grid.getChildCount(); i++) {
            View card = grid.getChildAt(i).findViewById(R.id.card);
            if (card != null && card.requestFocus()) return;
        }
        // No tiles laid out at all yet (the first frame after a cold start): look again once they are.
        if (grid.getChildCount() == 0 && !focusRetry) {
            focusRetry = true;
            grid.postDelayed(this::ensureFocus, 120);
        }
    }

    // ---- top bar ---------------------------------------------------------------------------------

    /** Some TVs (Coocaa OS) set the network time without ACTION_TIME_CHANGED, so keep checking until it is valid. */
    private final Runnable clockCheck = this::updateClockVisibility;

    private void updateClockVisibility() {
        boolean valid = System.currentTimeMillis() >= TIME_VALID_AFTER_MS;
        clockBlock.setVisibility(valid ? View.VISIBLE : View.INVISIBLE);
        handler.removeCallbacks(clockCheck);
        if (valid) {
            updateGreeting();
        } else {
            handler.postDelayed(clockCheck, 1_000L);
        }
    }

    private void updateGreeting() {
        Calendar now = Calendar.getInstance();
        int hour = now.get(Calendar.HOUR_OF_DAY);
        int res;
        // Indonesian day parts run on different hours than English:
        // Pagi 00-10, Siang 10-15, Sore 15-18, Malam 18-24.
        // The app's own locale (per-app language on Android 13+), under either code Android uses for Indonesian.
        String lang = Compat.primaryLanguage(this);
        if ("in".equals(lang) || "id".equals(lang)) {
            if (hour < 10) res = R.string.good_morning;
            else if (hour < 15) res = R.string.good_afternoon;
            else if (hour < 18) res = R.string.good_evening;
            else res = R.string.good_night;
        } else {
            if (hour < 5) res = R.string.good_night;
            else if (hour < 12) res = R.string.good_morning;
            else if (hour < 17) res = R.string.good_afternoon;
            else if (hour < 22) res = R.string.good_evening;
            else res = R.string.good_night;
        }
        String festiveGreeting = festive ? festival.greetingText(getResources()) : "";
        if (!festiveGreeting.isEmpty()) {
            greeting.setText(festiveGreeting);
            greeting.setTextColor(liftTowardsWhite(festival.accentColor(), 0.25f));
        } else {
            greeting.setText(greetingName.isEmpty() ? getString(res)
                    : getString(R.string.greeting_with_name, getString(res), greetingName));
            greeting.setTextColor(greetingColor);
        }
        if (shamsi) shamsiDate.setText(Shamsi.today(getResources()));
        // The lunar date only turns over at midnight, but a minute is cheap: one Calendar and a
        // walk of at most 200 table entries, so it never needs a change detector of its own.
        Lunar.Date lunar = Lunar.of(now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1,
                now.get(Calendar.DAY_OF_MONTH));
        String lunarLine = Lunar.format(lunar);
        if (lunarLine.isEmpty()) {
            lunarDate.setVisibility(View.GONE);
        } else {
            lunarDate.setText(lunarLine);
            lunarDate.setVisibility(View.VISIBLE);
        }
    }

    // ---- race-weekend panel (a theme's race_calendar) -----------------------------------------------

    private static final int RACE_RED = 0xFFFF3B2F;

    /**
     * Shows or hides the race-weekend panel for this minute. Text only, set once a minute with the clock:
     * nothing animates, so the panel costs the GPU nothing between ticks.
     */
    private void updateRace() {
        if (raceCalendar.isEmpty()) return;
        RaceWeekend.State s = RaceWeekend.at(raceCalendar, java.time.Instant.now());
        if (s == null) {
            racePanel.setVisibility(View.GONE);
            return;
        }
        RaceWeekend.Race race = s.race;
        raceKicker.setText(race.place.isEmpty()
                ? getString(R.string.race_kicker_no_place, race.round, race.shortName())
                : getString(R.string.race_kicker, race.round, race.shortName(), race.place));

        if (s.live != null) {
            SpannableString live = new SpannableString("\u25CF  " + getString(R.string.race_live, s.live.title(getResources())));
            live.setSpan(new android.text.style.ForegroundColorSpan(RACE_RED), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            raceHeadline.setText(live);
        } else if (s.next != null) {
            String in = RaceWeekend.countdown(getResources(), s.untilNext);
            raceHeadline.setText(s.next.isRace() ? getString(R.string.race_lights_out, in)
                    : getString(R.string.race_next, s.next.title(getResources()), in));
        } else {
            raceHeadline.setText(R.string.race_finished);
        }

        // FP1 FRI 11:00 PM   FP2 SAT 2:30 AM ...: done sessions fade, the live one is red, the next one bright.
        java.time.format.DateTimeFormatter when = java.time.format.DateTimeFormatter.ofPattern(
                android.text.format.DateFormat.is24HourFormat(this) ? "EEE HH:mm" : "EEE h:mm a", Locale.getDefault())
                .withZone(java.time.ZoneId.systemDefault());
        android.text.SpannableStringBuilder row = new android.text.SpannableStringBuilder();
        for (int i = 0; i < race.sessions.size(); i++) {
            RaceWeekend.Session session = race.sessions.get(i);
            if (i > 0) row.append("     ");
            int start = row.length();
            row.append(session.shortName(getResources())).append("  ").append(when.format(session.start).toUpperCase(Locale.getDefault()));
            int color;
            switch (s.statuses.get(i)) {
                case DONE: color = 0x47FFFFFF; break;
                case LIVE: color = RACE_RED; break;
                case NEXT: color = 0xFFFFFFFF; break;
                default: color = 0x99FFFFFF;
            }
            row.setSpan(new android.text.style.ForegroundColorSpan(color), start, row.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        raceSessions.setText(row);
        racePanel.setVisibility(View.VISIBLE);
    }

    private void refreshPillsAfterNextDraw() {
        root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                root.getViewTreeObserver().removeOnPreDrawListener(this);
                // Posted from inside the frame's traversal, so it runs after that frame is drawn. A post straight
                // from onResume would run first: the frame is only scheduled after onResume returns.
                handler.post(() -> {
                    if (isDestroyed()) return;
                    updateWifiLabel();
                    updateInputsButton();
                });
                return true;
            }
        });
        root.invalidate();   // a resume that changes nothing on screen still draws once, so the refresh runs
    }

    /** Shows the Wi-Fi network name on the button when we can read it, otherwise the connection type. */
    private void updateWifiLabel() {
        String text = getString(R.string.wifi);
        boolean vpn = false, online = false;
        int icon = R.drawable.ic_wifi;
        // The diya's flame: 0 is out (offline), 1 to 4 follow the Wi-Fi signal, Ethernet burns steady at 4.
        int flame = 0;
        boolean steady = false;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            boolean wifi = false, ethernet = false;
            // All networks, not just the active one: with a VPN up the active network is the VPN itself.
            for (Network net : cm != null ? cm.getAllNetworks() : new Network[0]) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null) continue;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    vpn = true;
                    continue;
                }
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                online = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) ethernet = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true;
            }
            if (!online) {
                text = getString(R.string.offline);
                vpn = false;
            } else if (ethernet) {
                text = getString(R.string.ethernet);
                icon = R.drawable.ic_ethernet;
                flame = 4;
                steady = true;
            } else if (wifi) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                String ssid = info != null ? info.getSSID() : null;
                flame = info != null ? Math.max(1, Math.min(4, WifiManager.calculateSignalLevel(info.getRssi(), 5))) : 3;
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) text = ssid;
                }
            }
        } catch (Exception ignored) {
        }
        // With a VPN app configured, a VPN that is down matters too: show a red "VPN off" capsule then.
        boolean vpnDown = !vpn && online && !vpnApp.isEmpty();
        if (vpn || vpnDown) {
            // Network name, then the green lock-and-VPN (or red "VPN off") capsule drawn in place of the word.
            String badge = getString(vpn ? R.string.vpn : R.string.vpn_off);
            VpnBadge span = new VpnBadge(vpn ? getDrawable(R.drawable.ic_lock) : null, density);
            // The pill has a maximum width and ellipsizes at the end, which would cut the badge off after
            // a long network name, so shorten the name to whatever room the badge leaves. The total paddings
            // include the Wi-Fi icon.
            TextPaint paint = btnWifi.getPaint();
            float room = btnWifi.getMaxWidth() - btnWifi.getTotalPaddingStart() - btnWifi.getTotalPaddingEnd()
                    - span.getSize(paint, badge, 0, badge.length(), null) - paint.measureText(" ") - 2 * density;
            String name = TextUtils.ellipsize(text, paint, Math.max(0, room), TextUtils.TruncateAt.END).toString();
            // A long badge ("VPN отключён") can leave room for a stub like "MT…", which says nothing and, after
            // rounding, can push the badge itself out of the pill. Show the badge alone then.
            // The space stays even then: a line holding only the span gets no font metrics and collapses.
            text = name.length() < text.length() && name.length() < 5 ? "" : name;
            SpannableString label = new SpannableString(text + " " + badge);
            label.setSpan(span, text.length() + 1, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (!TextUtils.equals(btnWifi.getText(), label)) btnWifi.setText(label);
        } else if (!TextUtils.equals(btnWifi.getText(), text)) {
            btnWifi.setText(text);
        }
        if (wifiIconOnly) {
            btnWifi.setContentDescription(btnWifi.getText());
            btnWifi.setText(null);
        }
        if (festive && diya != null) {
            if (online && flame == 0) flame = 3;   // online some other way (cellular, USB tethering)
            diya.setSteady(steady);
            diya.setFlame(flame);
        } else {
            if (icon != wifiIcon) {
                wifiIcon = icon;
                btnWifi.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
            }
        }
    }

    /** config.xml icon_only_buttons. */
    private boolean iconOnlyButtons;
    /** icon_only_buttons without wifi_label_width: the Wi-Fi pill shows no network name. */
    private boolean wifiIconOnly;
    /** The app library button in the top bar (library_button). */
    private View btnLibraryTop;
    /** The open library came from that button: closing it returns there instead of scrolling to the grid's end. */
    private boolean libraryFromTopBar;
    /** Tile position the Le button opened the library from, or -1: closing it puts the focus back there. */
    private int libraryReturnTile = -1;
    /** The footer 应用库 button opened the library (D-pad path): closing it goes back to that button. */
    private boolean libraryReturnFooter;

    /** Turns a labelled top pill into the round TopButtonIcon shape: no text, the icon centred by even padding. */
    private void makeIconOnly(TextView pill) {
        pill.setContentDescription(pill.getText());
        pill.setText(null);
        pill.setMaxWidth(Integer.MAX_VALUE);
        int pad = Math.round(10 * density);
        pill.setPaddingRelative(pad, 0, pad, 0);
        pill.setCompoundDrawablePadding(0);
        pill.getLayoutParams().width = Math.round(42 * density);
    }

    // ---- festive skin ------------------------------------------------------------------------------

    /**
     * Swaps the pill icons for the festival's: a bell for Inputs that swings when focused, a diya for the
     * network whose flame follows the connection, and a marigold for Settings that turns when focused.
     */
    private void applyFestiveSkin(TextView inputs, TextView settings) {
        bell = new BellDrawable(density);
        marigold = new MarigoldDrawable(density);
        diya = new DiyaDrawable(density);
        bell.setReducedMotion(reducedMotion);
        marigold.setReducedMotion(reducedMotion);
        diya.setReducedMotion(reducedMotion);
        setFestiveIcon(inputs, bell);
        setFestiveIcon(btnWifi, diya);
        setFestiveIcon(settings, marigold);
        addFocusAction(inputs, bell::ring);
        addFocusAction(settings, marigold::turn);
    }

    private static void setFestiveIcon(TextView pill, Drawable icon) {
        // The pills tint their icons to follow focus; the festive ones keep their own colours.
        pill.setCompoundDrawableTintList(null);
        pill.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null);
    }

    /** Runs {@code action} when the view gains focus, keeping any focus listener it already had. */
    private static void addFocusAction(View view, Runnable action) {
        View.OnFocusChangeListener previous = view.getOnFocusChangeListener();
        view.setOnFocusChangeListener((v, focused) -> {
            if (previous != null) previous.onFocusChange(v, focused);
            if (focused) action.run();
        });
    }

    private void playPetalShower() {
        if (isFinishing() || isDestroyed()) return;
        PetalShowerView shower = new PetalShowerView(this);
        root.addView(shower, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        shower.play(60, 2200, () -> root.removeView(shower));
    }

    private static int liftTowardsWhite(int argb, float amount) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        r += (int) ((255 - r) * amount);
        g += (int) ((255 - g) * amount);
        b += (int) ((255 - b) * amount);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** The network pill opens the VPN app when one is configured and installed, otherwise Wi-Fi settings. */
    private void openNetworkPill() {
        Intent i = vpnApp.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(vpnApp);
        if (i == null && !vpnApp.isEmpty()) i = getPackageManager().getLaunchIntentForPackage(vpnApp);
        if (i != null) open(i);
        else openSettings(systemIntent(Settings.ACTION_WIFI_SETTINGS));
    }


    /** Every settings entry shares the standard intent and the vendor fallback (SystemSettings). */
    private Intent systemIntent(String standardAction) {
        return SystemSettings.intent(this, standardAction);
    }

    /** Hides the Inputs pill when it would have nothing to list: streaming boxes have no HDMI-in, AV or tuner. */
    private void updateInputsButton() {
        if (!showInputsPill) {
            btnInputs.setVisibility(View.GONE);
            return;
        }
        boolean any = liveTvIntent() != null || !sourcePanelService.isEmpty()
                || (coocaaSources && !CoocaaSources.list(this).isEmpty());
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (!any && tim != null) {
            for (TvInputInfo info : Compat.tvInputList(tim)) {
                // Inputs hidden in Settings > Inputs are left out of the menu, so they don't count here either.
                if (info.isPassthroughInput() && !Compat.isInputHidden(info, this)) {
                    any = true;
                    break;
                }
            }
        }
        btnInputs.setVisibility(any ? View.VISIBLE : View.GONE);
    }

    /** boot_to_live_tv: the first time Home comes up after the TV starts, open the Live TV app instead. Once per
     *  boot, keyed on Settings.Global.BOOT_COUNT, so a later Home press stays on Home. */
    private boolean openLiveTvAfterBoot() {
        if (!bootToLiveTv) return false;
        int boot = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        SharedPreferences prefs = getSharedPreferences("boot", MODE_PRIVATE);
        if (boot < 0 || prefs.getInt("live_tv_boot", -1) == boot) return false;
        prefs.edit().putInt("live_tv_boot", boot).apply();
        Intent i = liveTvIntent();
        if (i == null) return false;
        open(i);
        return true;
    }

    /** The Live TV app's launch intent: its leanback entry point, or the configured action if it has none. */
    private Intent liveTvIntent() {
        if (liveTvPkg.isEmpty()) return null;
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (i != null || liveTvAction.isEmpty()) return i;
        Intent byAction = new Intent(liveTvAction).setPackage(liveTvPkg);
        return getPackageManager().resolveActivity(byAction, 0) != null ? byAction : null;
    }

    /** The TV's own source-selection panel, for TVs that have no TvInputManager (TCL Android TV 9): a
     *  service, or an activity on sets whose panel is a dialog (BYINTEK U4's QuickSourceActivity). */
    private void openSourcePanel() {
        Intent i = new Intent(sourcePanelAction);
        ComponentName c = ComponentName.unflattenFromString(sourcePanelService);
        if (c != null) i.setComponent(c);
        int eq = sourcePanelExtra.indexOf('=');
        if (eq > 0) {
            try {
                i.putExtra(sourcePanelExtra.substring(0, eq).trim(),
                        Integer.parseInt(sourcePanelExtra.substring(eq + 1).trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        try {
            if (getPackageManager().resolveActivity(i, 0) != null) {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            }
            startService(i);
        } catch (Exception e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Opens a settings screen in Home's own task, without the FLAG_ACTIVITY_NEW_TASK that {@link #open}
     * adds. In a task of its own the screen's Back lands on whatever task sits beneath it - on this Letv
     * firmware that is the stock launcher, whose task outlives Home's, so Back from Wi-Fi settings came
     * back to Letv's desktop instead of ours. In our task the screen sits directly on top of Home, and
     * Back is Home even if the launcher process was killed while the settings were up.
     */
    /**
     * Opens a settings screen in a task of its own - FLAG_ACTIVITY_NEW_TASK is required here, not optional.
     *
     * Letv's framework force-stops this launcher about half a second after any other activity reaches the
     * front ("Force stopping com.gathin.tvhome appid=10044 user=0: from pid 2320", i.e. from system_server
     * itself, and only for the app that holds HOME - a normal app is left alone). Sharing Home's task is
     * therefore fatal for a settings screen: it is destroyed together with the task, so the screen flickers
     * away after a second. In a task of its own it survives, and Back leaves an empty stack, which makes
     * the system resolve HOME again and restart this launcher (measured: 645 ms).
     *
     * That last step only lands on Home while the stock launcher is disabled - `pm disable-user --user 0
     * com.stv.launcher`, which build-and-install.sh does. The stock launcher is a PERSISTENT system app, so
     * while it is enabled its task sits in the stack and Back from settings returns to it instead.
     */
    private void openSettings(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** `input_sources` in config.xml: "HW5=23" maps the framework input whose id ends in /HW5 to a vendor
     *  source number; "HW8=-" leaves that input out of the Inputs menu (a port the board reports but the set
     *  does not have). Returns the number, -1 for a left-out input, or null when the input is not listed. */
    private Integer mappedSource(String inputId) {
        if (inputId == null) return null;
        String hw = inputId.substring(inputId.lastIndexOf('/') + 1);
        for (String m : getResources().getStringArray(R.array.input_sources)) {
            int eq = m.indexOf('=');
            if (eq <= 0 || !m.substring(0, eq).trim().equals(hw)) continue;
            String v = m.substring(eq + 1).trim();
            if (v.equals("-")) return -1;
            try { return Integer.parseInt(v); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    /** Switches to a passthrough input (HDMI, AV). An input mapped in `input_sources` is opened by sending
     *  `input_action` with its source number as the int extra "input" (Xiaomi China firmware, whose tvplayer
     *  answers com.xiaomi.mitv.tvplayer.EXTSRC_PLAY and nothing else). Otherwise the standard way is
     *  ACTION_VIEW on its channel URI, which the Live TV app handles; where nothing takes that, the
     *  configured `input_action` is sent with the same URI instead (XGIMI's HDMI player only answers
     *  com.xgimi.action.hdmiPlayer). With `input_uri_scheme` set (YaOS: live-tv) the same URI goes out
     *  with that scheme and no MIME type. A player guarded by a permission this build does not declare
     *  throws SecurityException, which used to take Home down with it (YaOS before the manifest asked
     *  for ACCESS_TV_SOURCE). */
    private void openInput(String inputId) {
        Integer source = mappedSource(inputId);
        if (source != null && source >= 0 && !inputAction.isEmpty()) {
            try {
                startActivity(new Intent(inputAction).putExtra("input", source.intValue())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException | SecurityException e) {
                // fall through to the standard way
            }
        }
        Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
        Intent view = (inputUriScheme.isEmpty()
                ? new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel")
                : new Intent(Intent.ACTION_VIEW, uri.buildUpon().scheme(inputUriScheme).build()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(view);
            return;
        } catch (ActivityNotFoundException | SecurityException e) {
            if (inputAction.isEmpty()) {
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        open(new Intent(inputAction).setDataAndType(uri, "vnd.android.cursor.item/channel"));
    }

    /**
     * Our own source list, for sets whose factory panel is a full-screen app (this Letv/MStar board). The
     * HDMI ports come first because they are the ones a console or a stick sits on; the tuner and the
     * legacy ports follow. Picking a row hands the screen to {@link SourceViewActivity}, which is what the
     * set draws the input into - switching the port alone would leave the picture behind Home.
     */
    private void showSourceMenu() {
        View anchor = sourceAnchor();
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = glassPopup(panel, (int) (200 * density));
        String current = MstarSourceSwitch.current();
        List<SourceRow> listed = new ArrayList<>();

        Intent live = liveTvIntent();
        if (live != null) {
            addSourceRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), null, false,
                    null, () -> open(live));
            addSeparator(rows);
        }
        addSourceRow(rows, popup, R.drawable.ic_input, "HDMI 1", "E_INPUT_SOURCE_HDMI", current, listed);
        addSourceRow(rows, popup, R.drawable.ic_input, "HDMI 2", "E_INPUT_SOURCE_HDMI2", current, listed);
        addSourceRow(rows, popup, R.drawable.ic_input, "HDMI 3", "E_INPUT_SOURCE_HDMI3", current, listed);
        addSeparator(rows);
        addSourceRow(rows, popup, R.drawable.ic_livetv, getString(R.string.source_digital_tv),
                "E_INPUT_SOURCE_DTV", current, listed);
        addSourceRow(rows, popup, R.drawable.ic_livetv, getString(R.string.source_analog_tv),
                "E_INPUT_SOURCE_ATV", current, listed);
        addSourceRow(rows, popup, R.drawable.ic_av, getString(R.string.source_video),
                "E_INPUT_SOURCE_CVBS", current, listed);
        addSourceRow(rows, popup, R.drawable.ic_picture, getString(R.string.source_computer),
                "E_INPUT_SOURCE_VGA", current, listed);

        // A fixed width, not a measured one: the title carries layout_weight, so measuring the panel with
        // no constraint gives it no room and every label comes out as "HD...". Wide enough for the
        // longest row ("模拟电视" beside "已接入") and no wider.
        popup.setWidth(Math.max(anchor.getWidth(), (int) (210 * density)));
        int alignment = getResources().getBoolean(R.bool.rtl_menu_bar) ? Gravity.END : Gravity.START;
        dropIn(popup, panel, anchor, alignment);
        rows.getChildAt(0).requestFocus();
        labelConnections(listed);
    }

    /** One row of the source menu: the constant it opens, and the label that carries its state. */
    private static final class SourceRow {
        final String constant;
        final TextView status;
        final View dot;
        final boolean active;

        SourceRow(String constant, TextView status, View dot, boolean active) {
            this.constant = constant;
            this.status = status;
            this.dot = dot;
            this.active = active;
        }
    }

    /**
     * Marks the ports that have something on them, the way the factory panel does. Asked off the main
     * thread - a cold detect call takes a few hundred milliseconds - and only the ports that answer "yes"
     * are marked: an empty port stays quiet instead of putting a word next to every row.
     */
    private void labelConnections(List<SourceRow> listed) {
        if (listed.isEmpty()) return;
        new Thread(() -> {
            for (SourceRow row : listed) {
                if (row.active) continue;        // already says "当前", which matters more
                if (!Boolean.TRUE.equals(MstarSourceSwitch.connected(row.constant))) continue;
                runOnUiThread(() -> {
                    row.status.setText(R.string.source_connected);
                    row.status.setVisibility(View.VISIBLE);
                    row.dot.setVisibility(View.VISIBLE);
                });
            }
        }, "source-detect").start();
    }

    /** One source row: the constant is null for a plain action row (Live TV), which never marks as current. */
    private void addSourceRow(LinearLayout rows, PopupWindow popup, int iconRes, String title,
                              String constant, String current, List<SourceRow> listed) {
        boolean active = constant != null && constant.equals(current);
        addSourceRow(rows, popup, iconRes, title, constant, active, listed, null);
    }

    private void addSourceRow(LinearLayout rows, PopupWindow popup, int iconRes, String title,
                              String constant, boolean active, List<SourceRow> listed,
                              Runnable plainAction) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        dot.setVisibility(active ? View.VISIBLE : View.GONE);
        if (active) status.setText(R.string.source_current);
        else status.setVisibility(View.GONE);
        if (listed != null && constant != null) listed.add(new SourceRow(constant, status, dot, active));
        row.setOnClickListener(v -> {
            popup.dismiss();
            if (plainAction != null) {
                plainAction.run();
            } else {
                openSource(constant, title);
            }
        });
        addSourceMenuKeys(row, popup);
        rows.addView(row);
    }

    /**
     * Shows a port. The switch happens in the port's own window, after it has a surface for the set to draw
     * into, so the press never leaves the screen on Home with the port already changed underneath.
     */
    private void openSource(String constant, String title) {
        if (!MstarSourceSwitch.available()) {
            openSourcePanel();
            return;
        }
        startActivityForResult(new Intent(this, SourceViewActivity.class)
                .putExtra(SourceViewActivity.EXTRA_CONSTANT, constant)
                .putExtra(SourceViewActivity.EXTRA_TITLE, title), SOURCE_VIEW_REQUEST);
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        // An MStar board answers the switch API, so the list can change the port itself: opening the factory
        // panel (a full-screen activity) would hide Home for a press that could have stayed in place.
        if (MstarSourceSwitch.available()) {
            showSourceMenu();
            return;
        }

        // A configured source panel and no Live TV row to offer next to it: the panel is the whole menu.
        // The BYINTEK U4 needs this, as its TvInputManager lists three HDMI ports and an AV input it doesn't have.
        List<CoocaaSources.Source> coocaa = coocaaSources ? CoocaaSources.list(this) : Collections.emptyList();
        if (coocaa.isEmpty() && !sourcePanelService.isEmpty() && liveTvIntent() == null) {
            openSourcePanel();
            return;
        }
        View anchor = sourceAnchor();
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);

        // Compact: as wide as the Inputs pill (a touch wider so "HDMI 1" fits), hanging right below it.
        int width = Math.max(anchor.getWidth(), (int) (150 * density));
        final PopupWindow popup = glassPopup(panel, width);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        String tunerId = null;
        boolean tunerIsHw = false;
        boolean tunerIsAnalog = false;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(Compat.tvInputList(tim));
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            // An HDMI-CEC device (an Apple TV, a soundbar) gets an input of its own whose parent is the
            // port it sits on, so the port and the device would each get a row (Vestel MT7332: "HDMI 1"
            // and "HDMI Apple TV"). The device takes its port's row instead, and a bare port is listed
            // only when nothing on it speaks CEC.
            Set<String> inputIds = new HashSet<>();
            for (TvInputInfo info : inputs) inputIds.add(info.getId());
            Map<String, List<TvInputInfo>> cecDevices = new HashMap<>();
            for (TvInputInfo info : inputs) {
                String parent = info.getParentId();
                if (parent == null || !inputIds.contains(parent)) continue;
                if (!info.isPassthroughInput() || Compat.isInputHidden(info, this)) continue;
                List<TvInputInfo> onPort = cecDevices.get(parent);
                if (onPort == null) cecDevices.put(parent, onPort = new ArrayList<>());
                onPort.add(info);
            }
            // Some TVs (Sony) report every HDMI port as connected whether or not anything is plugged in,
            // so drop the HDMI status then. A TV with every port really in use loses it too.
            int hdmiPorts = 0, hdmiConnected = 0;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
                if (info.getParentId() != null && inputIds.contains(info.getParentId())) continue;
                hdmiPorts++;
                if (Compat.inputState(tim, info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) hdmiConnected++;
            }
            boolean hdmiStateBogus = hdmiPorts > 1 && hdmiConnected == hdmiPorts;
            for (TvInputInfo info : inputs) {
                if (info.getType() == TvInputInfo.TYPE_TUNER
                        && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (liveTvInputId.isEmpty() || liveTvInputId.equals(info.getId()))) {
                    // Prefer a hardware tuner: the framework names those ".../HW<n>". System apps such as
                    // AirPlay also register software inputs of type TUNER and may sort first (seen on TCL G05).
                    // Among tuners of the same kind, skip an analog input when another is present.
                    // MediaTek names them AnalogInputService and TunerInputService; alphabetical order
                    // would pick the analog one, and Live TV then opens a dead ATV source.
                    boolean hw = info.getId().matches(".*/HW\\d+$");
                    boolean analog = info.getId().toLowerCase(Locale.ROOT).contains("analog");
                    boolean better = tunerId == null
                            || (hw && !tunerIsHw)
                            || (hw == tunerIsHw && tunerIsAnalog && !analog)
                            || (hw == tunerIsHw && analog == tunerIsAnalog
                                    && info.getId().compareTo(tunerId) < 0);
                    if (better) {
                        tunerId = info.getId();
                        tunerIsHw = hw;
                        tunerIsAnalog = analog;
                    }
                }
                // Inputs hidden in the TV's Settings > Inputs stay out of the menu too.
                if (!info.isPassthroughInput() || Compat.isInputHidden(info, this)) continue;
                // input_sources can leave out ports the board reports but the set does not have ("HW8=-").
                if (Integer.valueOf(-1).equals(mappedSource(info.getId()))) continue;
                // A CEC device's row is added with its port, below.
                if (info.getParentId() != null && inputIds.contains(info.getParentId())) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase(Locale.ROOT).contains("HDMI")) label = "HDMI " + label;
                CharSequence custom = Compat.loadCustomLabel(info, this);
                boolean named = custom != null && custom.length() > 0 && !custom.toString().equals(label);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                List<TvInputInfo> devices = cecDevices.get(info.getId());
                if (devices != null) {
                    // "Apple TV · HDMI 1": the device's own name (or the one given to it or to its port in
                    // Settings > Inputs), then the port. Opening the device's input also makes it the CEC
                    // active source, which wakes it.
                    for (TvInputInfo device : devices) {
                        CharSequence name = Compat.loadCustomLabel(device, this);
                        if (name == null || name.length() == 0) name = named ? custom : device.loadLabel(this);
                        String deviceLabel = name == null || name.length() == 0 ? label : name + " \u00B7 " + label;
                        final String deviceId = device.getId();
                        int state = hdmiStateBogus ? -1 : Compat.inputState(tim, deviceId);
                        addInputRow(rows, popup, R.drawable.ic_input, deviceLabel, state, () -> openInput(deviceId));
                    }
                    continue;
                }
                // The name given in Settings > Inputs (e.g. "MacBook Pro") goes first, the port after it.
                if (named) label = custom + " \u00B7 " + label;
                final String id = info.getId();
                // Analog AV jacks have no hotplug detection, and some TVs report them as always connected
                // (TCL Smart TV Pro G03 says "Connected" with nothing plugged in), so AV rows show no state.
                int state = !hdmi || hdmiStateBogus ? -1 : Compat.inputState(tim, id);
                addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state,
                        () -> openInput(id));
            }
        }
        // Coocaa OS registers nothing with TvInputManager; com.skyworth.tv lists its sources instead.
        if (rows.getChildCount() == 0) {
            for (CoocaaSources.Source s : coocaa) {
                int icon = "HDMI".equals(s.name) ? R.drawable.ic_input
                        : "VOD".equals(s.name) ? R.drawable.ic_livetv : R.drawable.ic_av;
                addInputRow(rows, popup, icon, s.label, -1, () -> {
                    if (!CoocaaSources.open(this, s)) {
                        Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }
        // Nothing to list where there is no TvInputManager: the TV's own source panel switches inputs.
        if (rows.getChildCount() == 0 && !sourcePanelService.isEmpty()) {
            addInputRow(rows, popup, R.drawable.ic_input, getString(R.string.inputs), -1, this::openSourcePanel);
        }
        Intent liveTv = liveTvIntent();
        if (liveTv != null && tunerId != null) {
            // Tune that input only when the Live TV package itself handles the URI.
            // The unrestricted input player also resolves it (that is how HDMI opens), but on the
            // AIWA AI PONT SA that player stays black, and the Live TV package does not handle the
            // URI at all, which used to toast "Not available on this TV". Keep the leanback activity
            // then: it is the same path as the Live TV tile. Do not drop the package restriction.
            // An explicit tuner uses its channel collection (e.g. DVB-S rather than DVB-T/C).
            // Keep the legacy passthrough route for automatic selection: some OEM players need it.
            // If the configured input is absent, tunerId stays null and the app opens normally.
            boolean configured = !liveTvInputId.isEmpty();
            Intent tuned = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(configured ? TvContract.buildChannelsUriForInput(tunerId)
                                    : TvContract.buildChannelUriForPassthroughInput(tunerId),
                            configured ? TvContract.Channels.CONTENT_TYPE : TvContract.Channels.CONTENT_ITEM_TYPE)
                    .setPackage(liveTvPkg);
            if (tuned.resolveActivity(getPackageManager()) != null) liveTv = tuned;
        }
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            final Intent liveTvIntent = liveTv;
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTvIntent));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        // Fit the widest row: TVs that report a status on every port (Sony) would otherwise squeeze the names out.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max(width, Math.min(panel.getMeasuredWidth(), (int) (360 * density))));

        int alignment = getResources().getBoolean(R.bool.rtl_menu_bar) ? Gravity.END : Gravity.START;
        dropIn(popup, panel, anchor, alignment);
        rows.getChildAt(0).requestFocus();
    }

    private PopupWindow glassPopup(View panel, int width) {
        return glassPopup(panel, width, null);
    }

    private PopupWindow glassPopup(View panel, int width, Runnable onDismiss) {
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        // A focusable popup owns the keyboard, so Menu never reaches MainActivity.handleMenuKey():
        // the rows handle it themselves (addMenuRow closes the popup, as Back does). The panel is not
        // focusable and never sees the key, so there is deliberately no listener here.
        popup.setOnDismissListener(() -> {
            if (openPopup == popup) openPopup = null;
            if (onDismiss != null) onDismiss.run();
        });
        openPopup = popup;
        return popup;
    }

    /** Drop in: fade and unfold from the top edge, hanging under the anchor pill. */
    private void dropIn(PopupWindow popup, View panel, View anchor, int gravity) {
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        popup.showAsDropDown(anchor, 0, (int) (10 * density), gravity);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(reducedMotion ? Motion.REDUCED_MS : 220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
    }

    /**
     * The source menu is a focusable popup, so it owns the keyboard: while it is up, the remote's own
     * buttons never reach dispatchKeyEvent (Back and Home still work - the first is the popup's own,
     * the second the system's). Attached to every row, this makes the Source button close the menu it
     * opened, and the Settings button close it and open the settings panel. Before, both did nothing.
     */
    private void addSourceMenuKeys(View row, PopupWindow popup) {
        row.setOnKeyListener((v, code, event) -> {
            if (event.getAction() != KeyEvent.ACTION_UP || event.isCanceled()) return false;
            if (isInputSourceKey(event)) {
                popup.dismiss();
                return true;
            }
            if (!isSettingsKey(event)) return false;
            popup.dismiss();
            openSettingsPanel();
            return true;
        });
    }

    private void addSeparator(LinearLayout rows) {        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    private void addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // This TV reports "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        addSourceMenuKeys(row, popup);
        rows.addView(row);
    }

    // ---- launcher settings and module cards -------------------------------------------------------

    /** Keep a normal Settings click for the TV; the launcher's choices live behind hold OK. */
    private void showSettingsMenu() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = glassPopup(panel, (int) (200 * density));
        addSettingsMenuRow(rows, popup, R.string.launcher_settings, this::openLauncherSettings);
        if (moduleCards) addSettingsMenuRow(rows, popup, R.string.module_cards, this::showModuleCards);
        if (dockRow) addSettingsMenuRow(rows, popup, R.string.dock_edit, this::startDockEdit);
        // The app in hand is the tile that had focus before the pill was reached: the last one focused.
        addSettingsMenuRow(rows, popup, R.string.reorder, () -> moveTileFromMenu(lastFocused));
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max((int) (200 * density), panel.getMeasuredWidth()));
        // Hebrew puts Settings at the left of the header: grow the menu inward from it.
        dropIn(popup, panel, anchor, getResources().getBoolean(R.bool.rtl_menu_bar)
                ? Gravity.START : Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    private void addSettingsMenuRow(LinearLayout rows, PopupWindow popup, int title, Runnable action) {
        addMenuRow(rows, popup, title, action);
    }

    /**
     * One text-only row in a glass popup. The OK-up that opened the popup must not activate its first row, so
     * the row only fires on an OK it saw go down itself.
     */
    private void addMenuRow(LinearLayout rows, PopupWindow popup, int title, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        row.findViewById(R.id.icon).setVisibility(View.GONE);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        row.findViewById(R.id.status).setVisibility(View.GONE);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        // The OK-up after opening the menu must not activate its first row.
        final boolean[] pressed = {false};
        row.setOnKeyListener((v, code, event) -> {
            // A focusable popup owns the keyboard: this row-level listener is the only place the Menu
            // key still arrives, so a second press closes the menu - Back works too. F5 is the
            // remote's Le button (dbg/fixkl.sh) and closes the menu the same way, so the Le button
            // stays a single "get me back" key whether the library or this menu is up.
            if ((code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_F5)
                    && event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                popup.dismiss();
                return true;
            }
            if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) pressed[0] = true;
                return true;
            }
            boolean activate = pressed[0];
            pressed[0] = false;
            if (activate) {
                popup.dismiss();
                action.run();
            }
            return true;
        });
        rows.addView(row);
    }

    /**
     * Widens the popup to its longest row: labels differ per language and per app, and a translated string
     * that no longer fits would be ellipsized away. Never wider than the screen.
     */
    private void fitMenuWidth(PopupWindow popup, View panel, LinearLayout rows) {
        int width = (int) (200 * density);
        for (int i = 0; i < rows.getChildCount(); i++) {
            TextView title = rows.getChildAt(i).findViewById(R.id.title);
            width = Math.max(width, (int) Math.ceil(title.getPaint().measureText(title.getText().toString()))
                    + (int) (58 * density));
        }
        popup.setWidth(Math.min(width, root.getWidth() - (int) (24 * density)));
        panel.measure(View.MeasureSpec.makeMeasureSpec(popup.getWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.UNSPECIFIED);
    }

    /**
     * Asks about the next card that has no decision for its current signing key. One prompt at a
     * time, only over a laid out grid with window focus (not under a popup or another app), and
     * the next one follows as soon as this one is answered. Nothing is shown until the owner says Show.
     */
    private void showCardPrompt() {
        if (!moduleCards || !tilesReady || promptsPaused || cardPrompt != null || !hasWindowFocus() || isFinishing()
                || reordering) return;
        if (cards.isEmpty() || !CardFeature.working()) return;
        CardFeature.run(() -> {
            showCardPromptNow();
            return null;
        }, null);
    }

    private void showCardPromptNow() {
        final Cards.Card card = Cards.firstPending(cards);
        if (card == null) return;

        View panel = getLayoutInflater().inflate(R.layout.dialog_card_prompt, root, false);
        ((TextView) panel.findViewById(R.id.title)).setText(getString(R.string.card_prompt_title, card.appLabel));
        ((TextView) panel.findViewById(R.id.body)).setText(card.state == Cards.State.RESIGNED
                ? getString(R.string.card_prompt_resigned, card.appLabel)
                : card.state == Cards.State.RELABELLED
                ? getString(R.string.card_prompt_relabelled, card.appLabel)
                : getString(R.string.card_prompt_body, card.appLabel));
        TextView names = panel.findViewById(R.id.names);
        if (Cards.fold(card.label).equals(Cards.fold(card.appLabel))) names.setVisibility(View.GONE);
        else names.setText(getString(R.string.card_prompt_names, card.label, card.appLabel));
        ((TextView) panel.findViewById(R.id.identity)).setText(
                getString(R.string.card_prompt_identity, card.pkg, Cards.shortDigest(card.digest)));
        TextView keep = panel.findViewById(R.id.btnKeep);
        TextView show = panel.findViewById(R.id.btnShow);

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(panel);
        dialog.setCanceledOnTouchOutside(false);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) (440 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.6f);
        }
        // One decision per prompt: the buttons and Back (cancel) all end here.
        final boolean[] decided = {false};
        View.OnClickListener choose = v -> {
            if (decided[0]) return;
            decided[0] = true;
            cardPrompt = null;
            dialog.dismiss();
            decideCard(card, v.getId() == R.id.btnShow);   // guards its own card calls; the tile reload stays outside
            restoreFocus();   // the tiles changed under the prompt; this also chains the next prompt
        };
        keep.setOnClickListener(choose);
        show.setOnClickListener(choose);
        dialog.setOnCancelListener(d -> choose.onClick(keep));
        dialog.setOnDismissListener(d -> {
            if (cardPrompt == dialog) cardPrompt = null;
        });
        // Left and Right move between the two buttons; Up and Down stay put; the ends don't wrap.
        View.OnKeyListener keys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return v.getId() == R.id.btnKeep;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnShow;
        };
        keep.setOnKeyListener(keys);
        show.setOnKeyListener(keys);
        cardPrompt = dialog;
        dialog.show();
        keep.requestFocus();
    }

    /** Writes the owner's decision and rebuilds the tiles; focus is left where it is. */
    private void decideCard(Cards.Card card, boolean show) {
        boolean saved = CardFeature.run(() -> Cards.decide(Cards.storeFile(this), card, show), false);
        if (!saved) {
            Toast.makeText(this, R.string.card_not_saved, Toast.LENGTH_SHORT).show();
            promptsPaused = true;
        }
        reloadTiles();
    }

    /** Dropdown under the Settings pill (hold OK): every discovered card, its package and key; OK toggles Shown and Hidden. */
    private void showModuleCards() {
        if (cards.isEmpty() || !CardFeature.working()) {
            Toast.makeText(this, R.string.no_module_cards, Toast.LENGTH_SHORT).show();
            return;
        }
        CardFeature.run(() -> {
            showModuleCardsNow();
            return null;
        }, null);
    }

    private void showModuleCardsNow() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_cards, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = glassPopup(panel, (int) (400 * density));
        List<Cards.Card> listed = new ArrayList<>(cards);
        Collections.sort(listed, Cards::byLabel);
        for (Cards.Card card : listed) {
            View row = getLayoutInflater().inflate(R.layout.item_card_row, rows, false);
            row.setTag(card.pkg);
            ((TextView) row.findViewById(R.id.title)).setText(card.label);
            TextView app = row.findViewById(R.id.app);
            if (Cards.fold(card.label).equals(Cards.fold(card.appLabel))) app.setVisibility(View.GONE);
            else app.setText(getString(R.string.card_app_name, card.appLabel));
            ((TextView) row.findViewById(R.id.pkg)).setText(card.pkg);
            ((TextView) row.findViewById(R.id.digest)).setText(Cards.longDigest(card.digest));
            final String pkg = card.pkg;
            row.setOnClickListener(v -> CardFeature.run(() -> {
                // The list stays open; the decision is for the card as it is now, not as it was listed.
                Cards.Card now = cardFor(pkg);
                if (now == null) return null;
                decideCard(now, now.state != Cards.State.SHOWN);
                // Every row, not only this one: a toggle can move another card across the limit of 8.
                updateCardStates(rows);
                return null;
            }, null));
            rows.addView(row);
        }
        updateCardStates(rows);
        // Scroll inside the panel instead of running off the bottom of a 720p screen.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int[] at = new int[2];
        anchor.getLocationInWindow(at);
        int room = root.getHeight() - (at[1] + anchor.getHeight()) - (int) (34 * density);
        popup.setHeight(Math.min(panel.getMeasuredHeight(), Math.max(room, (int) (120 * density))));
        dropIn(popup, panel, anchor, Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    private Cards.Card cardFor(String pkg) {
        for (Cards.Card c : cards) if (c.pkg.equals(pkg)) return c;
        return null;
    }

    /** Writes the state of every row in the Module cards list from the current card list. */
    private void updateCardStates(LinearLayout rows) {
        List<Cards.Card> drawn = Cards.shown(cards);
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            Cards.Card card = row.getTag() instanceof String ? cardFor((String) row.getTag()) : null;
            ((TextView) row.findViewById(R.id.state)).setText(card != null ? stateText(card, drawn.contains(card)) : "");
        }
    }

    /** The row's state: a card the owner set to Shown but which is beyond the eight drawn says so. */
    private String stateText(Cards.Card card, boolean drawn) {
        switch (card.state) {
            case SHOWN:
                return getString(drawn ? R.string.card_state_shown : R.string.card_state_over_limit);
            case HIDDEN:
                return getString(R.string.card_state_hidden);
            case RESIGNED:
                return getString(R.string.card_state_new_key);
            case RELABELLED:
                return getString(R.string.card_state_new_label);
            default:
                return getString(R.string.card_state_new);
        }
    }
}
