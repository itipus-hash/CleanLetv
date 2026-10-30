package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

/** On-demand installed-app picker. Uses the launcher's glass resources; no backdrop capture or blur. */
final class AppLibraryDialog {
    /**
     * The catalogue, the labels and the icons load here, off the UI thread: each label and icon opens the app's
     * resources, and on a TCL G09 that froze Home for 60-140 ms (2 to 4 janky frames) every time the library opened.
     */
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    /** Labels kept between openings (LOADER thread only), until the app is updated. */
    private static final Map<String, Known> KNOWN = new HashMap<>();

    private static final class Known {
        final long updated;
        final String label;

        Known(long updated, String label) {
            this.updated = updated;
            this.label = label;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    /** Icons loaded for this opening, dropped with it. */
    private final Map<String, Drawable> icons = new HashMap<>();
    private final Set<String> iconsLoading = new HashSet<>();
    private final Runnable dismissed;
    private boolean closed;
    /** Only the latest load fills the catalogue. */
    private int loads;
    private final Activity activity;
    private final Predicate<String> onHome;
    private final Runnable changed;
    private final LinearLayout panel;
    private final boolean reducedMotion;
    private final boolean animations;
    private final boolean rtl;
    private TextView instructions;
    /** The daily-sentence face of the panel (le_key_function); null until showQuote runs. */
    private TextView quoteText, quoteFrom;
    /** Which categories that face draws from, so a Le press can draw again from the same pool. */
    private int quoteCategories = Sentences.ALL;
    /** Guards against a slow draw overwriting a quicker one that was asked for later. */
    private long quoteDraw;
    /**
     * Every sentence this panel has shown, oldest first, with {@link #quoteAt} pointing at the one
     * on screen. Left and Up walk back through it; Right and Down walk forward again, and only past
     * the end is a new sentence drawn.
     */
    private final List<Quote> quoteTrail = new ArrayList<>();
    private int quoteAt = -1;
    /** True while that face is the one on screen, so a package change does not turn it into the catalogue. */
    private boolean quoteFace;
    private int totalApps;
    final PopupWindow popup;
    private final float density;
    private List<ResolveInfo> apps = new ArrayList<>();
    private String selectedPackage;
    private int selectedPosition;
    private int selectedOffset;
    private final Map<String, Long> installTimes = new HashMap<>();
    private final Map<String, String> labels = new HashMap<>();
    private long catalogTime;
    /** The owner's catalogue order; empty while the automatic sort is in charge. LOADER fills it. */
    private final List<String> order = new ArrayList<>();
    /** Package being carried through the catalogue, or null. */
    private String carriedPackage;
    /** True while the owner moves apps around: one app can be carried, put down, and the next picked up. */
    private boolean moveMode;
    private PopupWindow actionPopup;
    private RecyclerView catalog;
    private FrameLayout shelf;
    private GlassView highlight;
    private SpringAnimation spring;
    private float scrollOffset;
    private boolean highlightShown;
    private final int catalogWidth;

    private void stopHighlight() {
        if (spring != null) spring.cancel();
        if (catalog != null) catalog.stopScroll();
        catalog = null;
        highlightShown = false;
    }

    private void highlight(View card, boolean animate) {
        if (highlight == null || card.getParent() == null) return;
        Rect rect = new Rect(0, 0, card.getWidth(), card.getHeight());
        shelf.offsetDescendantRectToMyCoords(card, rect);
        ViewGroup.LayoutParams lp = highlight.getLayoutParams();
        lp.width = rect.width() + dp(12);
        lp.height = rect.height() + dp(12);
        highlight.setLayoutParams(lp);
        highlight.setTranslationY(rect.top - dp(6));
        float x = rect.left - dp(6) + scrollOffset;
        if (!highlightShown || !animate || !animations) {
            spring.cancel();
            highlight.setTranslationX(x - scrollOffset);
        } else spring.animateToFinalPosition(x);
        highlightShown = true;
        highlight.setAlpha(1f);
    }

    private GradientDrawable surface(int color, int radius) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(radius));
        bg.setStroke(dp(1), 0x18FFFFFF);
        return bg;
    }

    private void resize(int width, int height) {
        popup.setWidth(width);
        popup.setHeight(height);
        if (popup.isShowing()) popup.update(width, height);
    }

    AppLibraryDialog(Activity activity, boolean animations, boolean reducedMotion, Predicate<String> onHome, Runnable changed, Runnable dismissed) {
        this.activity = activity;
        this.animations = animations;
        this.rtl = activity.getResources().getBoolean(R.bool.rtl_app_grid);
        this.reducedMotion = reducedMotion;
        this.onHome = onHome;
        this.changed = changed;
        this.dismissed = dismissed;
        density = activity.getResources().getDisplayMetrics().density;
        panel = new LinearLayout(activity);
        panel.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(8), dp(12), dp(12));
        panel.setBackgroundResource(R.drawable.glass_panel);
        View decor = activity.getWindow().getDecorView();
        // On Home the window is already laid out. In the translucent overlay activity it is not: the
        // decor reports 0 until the first traversal, which would squeeze the catalogue down to its
        // 200 dp minimum. The display metrics give the same answer there, since that window fills
        // the screen too.
        int available = decor.getWidth() > 0
                ? decor.getWidth()
                : activity.getResources().getDisplayMetrics().widthPixels;
        catalogWidth = Math.min(dp(800), Math.max(dp(200), available - dp(64)));
        int width = catalogWidth;
        int height = dp(280);
        popup = new PopupWindow(panel, width, height, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setOnDismissListener(() -> {
            closed = true;
            carriedPackage = null;
            moveMode = false;
            dismissActions();
            stopHighlight();
            icons.clear();
            dismissed.run();
        });
    }

    void close() {
        dismissActions();
        if (popup.isShowing()) {
            popup.dismiss();
        } else if (!closed) {
            closed = true;   // still loading: it never shows
            dismissed.run();
        }
    }

    void show() {
        load(this::reveal);
    }

    /**
     * The panel's other face, for config.xml le_key_function = 每日一言: the same glass panel, the
     * same dim-behind overlay, and the day's sentence where the app list would be. Covering the app
     * underneath is what stops it (see leplay.sh), and the panel says nothing about that on purpose
     * - it is a sentence to read, not a status message.
     */
    /**
     * Hands the panel's keyboard to one view. The popup is focusable (new PopupWindow(panel, w, h,
     * true)), so it owns every key while it is up: a face with no focusable child — the daily
     * sentence, or an empty catalogue — swallows Le and OK alike and only Back (the decor dismisses
     * itself) ever gets out. The catalogue rows carry the same logic inline; this is the version for
     * the faces that have no rows.
     *
     * <p>{@code onNext} is what Right and Down spend themselves on, {@code onPrev} what Left and Up
     * spend themselves on. The daily-sentence face walks its trail of shown sentences with them, so
     * that face has exactly three ways out — Le, OK and Back — which is also all its hint line
     * promises. Le lives here too, closing: it is the one key the user already reads as "get me
     * out", and it is claimed before it closes so the daemon's ACTION_TOGGLE_LIBRARY cannot put the
     * panel straight back up.
     */
    private void focusForToggle(View view, Runnable onNext, Runnable onPrev) {
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        view.setOnKeyListener((v, key, event) -> {
            boolean le = key == KeyEvent.KEYCODE_F5;   // the patched Le button (dbg/fixkl.sh)
            boolean next = key == KeyEvent.KEYCODE_DPAD_RIGHT
                    || key == KeyEvent.KEYCODE_DPAD_DOWN;
            boolean prev = key == KeyEvent.KEYCODE_DPAD_LEFT
                    || key == KeyEvent.KEYCODE_DPAD_UP;
            boolean ok = key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                    || key == KeyEvent.KEYCODE_NUMPAD_ENTER;
            if (event.getAction() == KeyEvent.ACTION_DOWN) return le || next || prev || ok;
            if (event.getAction() != KeyEvent.ACTION_UP || event.isCanceled()) return false;
            if (le) {
                // Claimed first: the lekeyd daemon also fires ACTION_TOGGLE_LIBRARY for this key,
                // and without the claim its intent would arrive a moment later and reopen the panel.
                boolean mine = !(activity instanceof MainActivity)
                        || ((MainActivity) activity).claimLePress();
                if (!mine) return true;
                close();
                return true;
            }
            if (next) {
                // Le used to live here. The arrows are the switch now, and a face with nothing to
                // switch to (an empty catalogue) lets the key go on its way.
                if (onNext == null) return false;
                onNext.run();
                return true;
            }
            if (prev) {
                if (onPrev == null) return false;
                onPrev.run();
                return true;
            }
            if (ok) {
                close();
                return true;
            }
            return false;
        });
        view.requestFocus();
    }

    void showQuote(int categories) {
        quoteFace = true;
        quoteCategories = categories;
        // Every open starts a fresh trail: the sentences from the last visit are not where the user
        // expects Left to land.
        quoteTrail.clear();
        quoteAt = -1;
        stopHighlight();
        resize(catalogWidth, dp(280));
        panel.removeAllViews();
        // No title: the words up there read as a label on a poster. The blank line keeps the panel's
        // top rhythm, so the sentence still starts where the library's title does.
        panel.addView(new View(activity), new LinearLayout.LayoutParams(-1, dp(44)));
        // Sentence and source sit in the middle of the panel, whatever their length: a one-line
        // sentence would otherwise leave the lower half of a 260 dp panel empty.
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER);
        // The library's own title is 22 sp; a poster sentence has to read from the sofa, so the
        // quote sits at 24 and its source at 16 rather than the panel's usual hint sizes.
        // Starts empty and fills in a moment: the pool is in the app, so there is nothing to wait
        // for beyond the first index of the asset.
        quoteText = text("", 24, 0xFFFFFFFF);
        quoteText.setMaxLines(3);
        quoteText.setEllipsize(TextUtils.TruncateAt.END);
        quoteText.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        quoteText.setPadding(dp(16), dp(10), dp(16), dp(4));
        // No rows in this face, so the sentence itself is the keyboard target. Right and Down draw
        // the next sentence, Left and Up go back through the ones already seen; Le, OK and Back are
        // the only ways out, and the hint line says so.
        focusForToggle(quoteText, this::nextQuote, this::prevQuote);
        body.addView(quoteText, new LinearLayout.LayoutParams(-1, -2));
        quoteFrom = text("", 16, 0xA6FFFFFF);
        quoteFrom.setPadding(dp(16), dp(6), dp(16), dp(6));
        quoteFrom.setGravity(Gravity.END);
        body.addView(quoteFrom, new LinearLayout.LayoutParams(-1, -2));
        panel.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));
        TextView hint = text(activity.getString(R.string.le_quote_close_hint), 15, 0xBBFFFFFF);
        hint.setSingleLine(true);
        hint.setPadding(dp(12), dp(2), dp(12), dp(6));
        panel.addView(hint, new LinearLayout.LayoutParams(-1, dp(30)));
        // A new draw on every open, unlike Home's sentence, which is fixed for the day.
        nextQuote();
        reveal();
    }

    /**
     * Right or Down on the daily-sentence face: the sentence after the one on screen. Walking back
     * with Left or Up and then pressing Right again shows a sentence already seen rather than
     * drawing a new one, the way a browser's forward button does. A token keeps a slow draw from
     * overwriting a quicker one that started later.
     */
    void nextQuote() {
        if (closed || !quoteFace) return;
        if (quoteAt + 1 < quoteTrail.size()) {
            quoteAt++;
            renderQuote(quoteTrail.get(quoteAt));
            return;
        }
        final long token = ++quoteDraw;
        Hitokoto.loadRandom(activity, main, quoteCategories,
                (text, from, who) -> {
                    if (token != quoteDraw || closed) return;
                    quoteTrail.add(new Quote(text, from, who));
                    quoteAt = quoteTrail.size() - 1;
                    renderQuote(quoteTrail.get(quoteAt));
                });
    }

    /** Left or Up: the sentence before the one on screen, when there is one to go back to. */
    void prevQuote() {
        if (closed || !quoteFace || quoteAt <= 0) return;
        quoteAt--;
        renderQuote(quoteTrail.get(quoteAt));
    }

    private void renderQuote(Quote quote) {
        if (closed || quoteText == null) return;
        quoteText.setText(quote.text);
        String source = Hitokoto.source(quote.from, quote.who);
        quoteFrom.setText(source);
        quoteFrom.setVisibility(source.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /**
     * Opens the catalogue with one app already selected, so the remote's Menu button lands on the app
     * that was focused on Home instead of the first one in the list.
     */
    void show(String packageName) {
        selectedPackage = packageName;
        show();
    }

    private void reveal() {
        popup.showAtLocation(activity.getWindow().getDecorView(), Gravity.CENTER, 0, 0);
        View container = panel.getRootView();
        if (container.getLayoutParams() instanceof WindowManager.LayoutParams) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) container.getLayoutParams();
            params.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            params.dimAmount = 0.55f;
            activity.getWindowManager().updateViewLayout(container, params);
        }
        panel.setAlpha(0f);
        panel.animate().alpha(1f).setDuration(reducedMotion ? Motion.REDUCED_MS : 180).start();
    }

    /** Called by the activity's existing package receiver; an uninstall cannot leave a stale action. */
    /** Packages with a leanback entry, from the last refresh. */
    private final java.util.Set<String> tvPackages = new java.util.HashSet<>();

    void refresh() {
        // The sentence has no catalogue to rebuild, and load() would replace the panel's contents
        // with the app list under the reader's nose. The next opening picks the new apps up anyway.
        if (quoteFace) return;
        load(null);
    }

    /** Reads the catalogue on LOADER, then fills it on the UI thread and runs {@code then}. */
    private void load(Runnable then) {
        dismissActions();
        carriedPackage = null;   // a rebuild from the package manager ends any move in progress
        moveMode = false;
        int load = ++loads;
        PackageManager pm = activity.getPackageManager();
        String self = activity.getPackageName();
        LOADER.execute(() -> {
            Map<String, ResolveInfo> unique = new LinkedHashMap<>();
            Set<String> tv = new HashSet<>();
            // Prefer the TV entry when an app declares both categories.
            for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
                for (ResolveInfo info : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                    if (info.activityInfo == null || !info.activityInfo.exported) continue;
                    String pkg = info.activityInfo.packageName;
                    if (Intent.CATEGORY_LEANBACK_LAUNCHER.equals(category)) tv.add(pkg);
                    if (!pkg.equals(self) && !unique.containsKey(pkg)) unique.put(pkg, info);
                }
            }
            Map<String, String> names = new HashMap<>();
            Map<String, Long> installed = new HashMap<>();
            for (Map.Entry<String, ResolveInfo> entry : unique.entrySet()) {
                String pkg = entry.getKey();
                long updated = 0;
                try {
                    PackageInfo info = pm.getPackageInfo(pkg, 0);
                    installed.put(pkg, info.firstInstallTime);
                    updated = info.lastUpdateTime;
                } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
                    installed.put(pkg, 0L);
                }
                Known known = KNOWN.get(pkg);
                if (known == null || known.updated != updated) {
                    known = new Known(updated, loadLabel(pm, entry.getValue()));
                    KNOWN.put(pkg, known);
                }
                names.put(pkg, known.label);
            }
            KNOWN.keySet().retainAll(unique.keySet());
            List<String> manual = LibraryOrder.load(activity);
            main.post(() -> {
                if (closed || load != loads) return;
                tvPackages.clear();
                tvPackages.addAll(tv);
                installTimes.clear();
                installTimes.putAll(installed);
                labels.clear();
                labels.putAll(names);
                order.clear();
                order.addAll(manual);
                fill(new ArrayList<>(unique.values()));
                if (then != null) then.run();
            });
        });
    }

    private void fill(List<ResolveInfo> catalogue) {
        totalApps = catalogue.size();
        apps = catalogue;
        catalogTime = System.currentTimeMillis();
        Collator collator = Collator.getInstance();
        // The owner's order wins; apps it does not mention yet (installed since the last move) go to
        // the end, by label. Nothing sorts by Home status: adding or removing an app must not move
        // the list under the owner's finger.
        Map<String, Integer> rank = new HashMap<>();
        for (int i = 0; i < order.size(); i++) rank.put(order.get(i), i);
        apps.sort((a, b) -> {
            String pa = a.activityInfo.packageName, pb = b.activityInfo.packageName;
            Integer ra = rank.get(pa), rb = rank.get(pb);
            if (ra != null || rb != null) {
                if (ra == null) return 1;
                if (rb == null) return -1;
                return ra.compareTo(rb);
            }
            int byLabel = collator.compare(label(a), label(b));
            return byLabel != 0 ? byLabel : pa.compareTo(pb);
        });
        showCatalog();
    }

    private String label(ResolveInfo app) {
        String pkg = app.activityInfo.packageName;
        String cached = labels.get(pkg);
        if (cached != null) return cached;
        String value = loadLabel(activity.getPackageManager(), app);
        labels.put(pkg, value);
        return value;
    }

    private static String loadLabel(PackageManager pm, ResolveInfo app) {
        String pkg = app.activityInfo.packageName;
        try { return Cards.cleanLabel(String.valueOf(app.loadLabel(pm)), pkg); }
        catch (RuntimeException unavailable) { return pkg; }
    }

    /** The app's icon from this opening's cache, or loaded on LOADER and set once it arrives. */
    private void bindIcon(ImageView icon, ResolveInfo app) {
        String pkg = app.activityInfo.packageName;
        icon.setTag(pkg);
        Drawable cached = icons.get(pkg);
        icon.setImageDrawable(cached);
        if (cached != null || !iconsLoading.add(pkg)) return;
        PackageManager pm = activity.getPackageManager();
        LOADER.execute(() -> {
            Drawable loaded;
            try { loaded = app.loadIcon(pm); }
            catch (RuntimeException unavailable) { loaded = null; }
            Drawable result = loaded;
            main.post(() -> {
                iconsLoading.remove(pkg);
                if (closed) return;
                Drawable shown = result != null ? result : activity.getDrawable(R.drawable.ic_apps);
                icons.put(pkg, shown);
                // The view may show another app by now; a view bound to it later takes it from the cache.
                if (pkg.equals(icon.getTag())) icon.setImageDrawable(shown);
            });
        });
    }

    private void heading(String text) {
        TextView title = text(text, 18, 0xFFFFFFFF);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(dp(12), dp(10), dp(12), dp(8));
        panel.addView(title, new LinearLayout.LayoutParams(-1, -2));
    }

    private void showCatalog() {
        stopHighlight();
        resize(catalogWidth, dp(280));
        panel.removeAllViews();
        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(4), dp(8), dp(4));
        TextView title = text(activity.getString(R.string.app_library), 22, Color.WHITE);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(36), 1));
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        TextView count = text(activity.getResources().getQuantityString(
                R.plurals.library_app_count, totalApps, totalApps), 13, 0xA6FFFFFF);
        count.setSingleLine(true);
        header.addView(count, new LinearLayout.LayoutParams(-2, -2));
        panel.addView(header, new LinearLayout.LayoutParams(-1, dp(44)));
        instructions = text(activity.getString(R.string.library_hint_add), 13, 0xBBFFFFFF);
        instructions.setSingleLine(true);
        instructions.setEllipsize(TextUtils.TruncateAt.END);
        instructions.setPadding(dp(12), 0, dp(12), dp(6));
        panel.addView(instructions, new LinearLayout.LayoutParams(-1, dp(26)));
        panel.setFocusableInTouchMode(apps.isEmpty());
        if (apps.isEmpty()) {
            instructions.setVisibility(View.GONE);
            heading(activity.getString(R.string.library_empty));
            // The catalogue is gone, so nothing else can take the keyboard: the panel itself has
            // to answer the toggle keys (see focusForToggle).
            focusForToggle(panel, null, null);
            return;
        }
        shelf = new FrameLayout(activity);
        // The glass uses physical x coordinates; only the catalogue mirrors its item order.
        shelf.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        shelf.setClipChildren(false);
        RecyclerView list = new RecyclerView(activity);
        list.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        catalog = list;
        list.setLayoutManager(new LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false) {
            @Override protected boolean isLayoutRTL() { return rtl; }
        });
        list.setItemAnimator(null);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setClipToPadding(false);
        list.setPadding(dp(10), dp(8), dp(10), dp(8));
        list.setAdapter(new Catalog());
        shelf.addView(list, new FrameLayout.LayoutParams(-1, -1));
        highlight = new GlassView(activity, null);
        highlight.setRadius(dp(20));
        highlight.setAlpha(0f);
        highlight.setFocusable(false);
        highlight.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        shelf.addView(highlight, new FrameLayout.LayoutParams(dp(144), dp(180)));
        scrollOffset = 0;
        spring = new SpringAnimation(highlight, new FloatPropertyCompat<View>("libraryScrollX") {
            @Override public float getValue(View view) { return view.getTranslationX() + scrollOffset; }
            @Override public void setValue(View view, float value) { view.setTranslationX(value - scrollOffset); }
        });
        if (reducedMotion) Motion.reduce(spring);
        else spring.setSpring(new SpringForce().setStiffness(260f).setDampingRatio(0.58f));
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                scrollOffset += dx;
                highlight.setTranslationX(highlight.getTranslationX() - dx);
            }
        });
        panel.addView(shelf, new LinearLayout.LayoutParams(-1, 0, 1));
        int position = Math.min(selectedPosition, apps.size() - 1);
        for (int i = 0; i < apps.size(); i++) {
            if (apps.get(i).activityInfo.packageName.equals(selectedPackage)) { position = i; break; }
        }
        ((LinearLayoutManager) list.getLayoutManager()).scrollToPositionWithOffset(position, selectedOffset);
        final int target = position;
        list.post(() -> {
            if (catalog != list || !popup.isShowing()) return;
            RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(target);
            if (holder != null) holder.itemView.requestFocus();
        });
    }

    private void dismissActions() {
        PopupWindow menu = actionPopup;
        actionPopup = null;
        if (menu != null) menu.dismiss();
    }

    private void restoreCardFocus() {
        if (catalog == null || !popup.isShowing()) return;
        catalog.post(() -> {
            if (catalog == null || !popup.isShowing() || actionPopup != null) return;
            RecyclerView.ViewHolder holder = catalog.findViewHolderForAdapterPosition(selectedPosition);
            if (holder != null) holder.itemView.requestFocus();
        });
    }

    private void changeHome(ResolveInfo app, boolean add) {
        String pkg = app.activityInfo.packageName;
        if (!LibraryApps.save(activity, pkg, add)) {
            Toast.makeText(activity, R.string.library_save_failed, Toast.LENGTH_LONG).show();
            return;
        }
        dismissActions();
        changed.run();
        // Only this tile changes: its badge flips and the hint follows the focused app. A full
        // refresh would rebuild the catalogue and pull the focus back to the first tile.
        int position = -1;
        for (int i = 0; i < apps.size(); i++) {
            if (apps.get(i).activityInfo.packageName.equals(pkg)) { position = i; break; }
        }
        if (position >= 0 && catalog != null) {
            RecyclerView.Adapter<?> adapter = catalog.getAdapter();
            if (adapter != null) adapter.notifyItemChanged(position);
        }
        if (pkg.equals(selectedPackage)) updateHint();
    }

    /** The line under the title: what the keys do for the app in focus right now. */
    private void updateHint() {
        if (instructions == null) return;
        if (moveMode) {
            instructions.setText(R.string.library_move_hint);
        } else {
            instructions.setText(onHome.test(selectedPackage)
                    ? R.string.library_hint_open : R.string.library_hint_add);
        }
    }

    private void open(ResolveInfo app) {
        close();
        Intent intent = new Intent(Intent.ACTION_MAIN).setComponent(
                new ComponentName(app.activityInfo.packageName, app.activityInfo.name));
        // TV apps keep the leanback launch intent, so an app already running resumes instead of restarting.
        if (tvPackages.contains(app.activityInfo.packageName)) intent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try { activity.startActivity(intent); }
        catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(activity, activity.getString(R.string.cant_open, label(app)), Toast.LENGTH_SHORT).show();
        }
    }

    // ---- catalogue order --------------------------------------------------------------------------

    /**
     * Carries an app: Left/Right slide it through the catalogue and OK puts it down, with the mode
     * still on so the next app can be picked up; Back leaves the mode for good.
     */
    private void startMove(String pkg) {
        if (pkg == null) return;
        boolean entering = !moveMode;
        moveMode = true;
        carriedPackage = pkg;
        selectedPackage = pkg;
        updateCarryVisuals();
        updateHint();
        // The hint is worth a toast when the mode starts; picking the next app up needs no words.
        if (entering) Toast.makeText(activity, R.string.library_move_hint, Toast.LENGTH_LONG).show();
    }

    private void dropCarried() {
        if (carriedPackage == null) return;
        carriedPackage = null;
        updateCarryVisuals();
    }

    /** Leaves move mode for good: whatever is in hand is put down and the catalogue goes back to normal. */
    private void leaveMoveMode() {
        carriedPackage = null;
        moveMode = false;
        updateCarryVisuals();
        updateHint();
    }

    /** Dims every other tile while one is carried; the carried one stands out at full size. */
    private void updateCarryVisuals() {
        if (catalog == null) return;
        for (int i = 0; i < catalog.getChildCount(); i++) {
            View item = catalog.getChildAt(i);
            int pos = catalog.getChildAdapterPosition(item);
            String pkg = pos != RecyclerView.NO_POSITION && pos >= 0 && pos < apps.size()
                    ? apps.get(pos).activityInfo.packageName : null;
            boolean carried = pkg != null && pkg.equals(carriedPackage);
            item.setAlpha(carriedPackage != null && !carried ? 0.45f : 1f);
            item.setScaleX(carried ? 1.06f : 1f);
            item.setScaleY(carried ? 1.06f : 1f);
        }
    }

    /** Slides the carried app one place; the visible order becomes the owner's from now on. */
    private void moveCarried(int to) {
        int from = -1;
        for (int i = 0; i < apps.size(); i++) {
            if (apps.get(i).activityInfo.packageName.equals(carriedPackage)) { from = i; break; }
        }
        if (from < 0) { dropCarried(); return; }
        to = Math.max(0, Math.min(apps.size() - 1, to));
        if (to == from) return;
        // Freeze what is on screen first: the automatic sort only decides where apps installed later go.
        List<String> next = new ArrayList<>();
        for (ResolveInfo app : apps) next.add(app.activityInfo.packageName);
        next.remove(from);
        next.add(to, carriedPackage);
        order.clear();
        order.addAll(next);
        LibraryOrder.save(activity, order);
        apps.add(to, apps.remove(from));
        selectedPackage = carriedPackage;
        selectedOffset = centerOffset();
        if (catalog != null) {
            RecyclerView.Adapter<?> adapter = catalog.getAdapter();
            if (adapter != null) adapter.notifyDataSetChanged();
            if (catalog.getLayoutManager() instanceof LinearLayoutManager) {
                ((LinearLayoutManager) catalog.getLayoutManager()).scrollToPositionWithOffset(to, selectedOffset);
            }
            final int target = to;
            catalog.post(() -> {
                if (catalog == null || !popup.isShowing() || carriedPackage == null) return;
                RecyclerView.ViewHolder holder = catalog.findViewHolderForAdapterPosition(target);
                if (holder != null) holder.itemView.requestFocus();
            });
        }
    }

    /** Offset that puts a tile in the middle of the shelf, so a carried app stays visible while moving. */
    private int centerOffset() {
        if (catalog == null) return 0;
        return Math.max(0, (catalog.getWidth() - dp(132) - dp(12)) / 2);
    }

    private void showActions(ResolveInfo app, View anchor) {
        dismissActions();
        selectedPackage = app.activityInfo.packageName;
        String pkg = selectedPackage;
        if (spring != null) spring.cancel();
        View menuPanel = activity.getLayoutInflater().inflate(R.layout.popup_inputs, panel, false);
        LinearLayout rows = menuPanel.findViewById(R.id.rows);
        PopupWindow menu = new PopupWindow(menuPanel, dp(220), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        actionPopup = menu;
        menu.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        menu.setOutsideTouchable(true);
        menu.setAnimationStyle(0);
        menu.setElevation(0f);
        menu.setOnDismissListener(() -> {
            if (actionPopup == menu) actionPopup = null;
            restoreCardFocus();
        });
        // Up and Down already add and remove, so the menu only offers what they cannot: moving the
        // app inside the catalogue, and removing the app from the TV altogether.
        addAction(rows, menu, R.string.reorder, () -> {
            dismissActions();
            startMove(pkg);
        });
        addAction(rows, menu, R.string.uninstall_app, () -> {
            dismissActions();
            close();
            try {
                activity.startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException | SecurityException e) {
                Toast.makeText(activity, R.string.not_available, Toast.LENGTH_SHORT).show();
            }
        });
        int menuWidth = dp(220);
        for (int i = 0; i < rows.getChildCount(); i++) {
            TextView title = rows.getChildAt(i).findViewById(R.id.title);
            menuWidth = Math.max(menuWidth, (int) Math.ceil(title.getPaint().measureText(title.getText().toString())) + dp(58));
            title.setEllipsize(TextUtils.TruncateAt.END);
        }
        menuWidth = Math.min(menuWidth, panel.getWidth() - dp(24));
        menu.setWidth(menuWidth);
        menuPanel.measure(View.MeasureSpec.makeMeasureSpec(menuWidth, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        int[] location = new int[2];
        anchor.getLocationOnScreen(location);
        int[] panelLocation = new int[2];
        panel.getLocationOnScreen(panelLocation);
        Rect screen = new Rect(panelLocation[0], panelLocation[1],
                panelLocation[0] + panel.getWidth(), panelLocation[1] + panel.getHeight());
        int x = Math.max(screen.left + dp(12), Math.min(location[0] + anchor.getWidth() - dp(12), screen.right - menuWidth - dp(12)));
        int y = Math.max(screen.top + dp(12), Math.min(location[1] + dp(8), screen.bottom - menuPanel.getMeasuredHeight() - dp(12)));
        menuPanel.setAlpha(0f);
        menuPanel.setPivotY(0f);
        menuPanel.setScaleY(0.92f);
        menuPanel.setTranslationY(-dp(8));
        menu.showAtLocation(activity.getWindow().getDecorView(), Gravity.TOP | Gravity.START, x, y);
        menuPanel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(reducedMotion ? Motion.REDUCED_MS : 220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        rows.getChildAt(0).requestFocus();
    }

    private void addAction(LinearLayout rows, PopupWindow menu, int label, Runnable action) {
        View row = activity.getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((TextView) row.findViewById(R.id.title)).setText(label);
        row.setFocusableInTouchMode(true);
        row.findViewById(R.id.icon).setVisibility(View.GONE);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        row.findViewById(R.id.status).setVisibility(View.GONE);
        row.setOnClickListener(v -> action.run());
        rows.addView(row);
        row.setOnKeyListener((v, key, event) -> {
            // The popup owns the keyboard, so a second Menu press closes the menu the way Back does.
            if (key == KeyEvent.KEYCODE_MENU && event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                menu.dismiss();
                return true;
            }
            int index = rows.indexOfChild(v);
            return event.getAction() == KeyEvent.ACTION_DOWN && (key == KeyEvent.KEYCODE_DPAD_LEFT
                    || key == KeyEvent.KEYCODE_DPAD_RIGHT || key == KeyEvent.KEYCODE_DPAD_UP && index == 0
                    || key == KeyEvent.KEYCODE_DPAD_DOWN && index == rows.getChildCount() - 1);
        });
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        Typeface face = Compat.loadFont(activity.getResources(), R.font.outfit_medium);
        if (face != null) view.setTypeface(face);
        view.setLetterSpacing(0.01f);
        view.setIncludeFontPadding(false);
        return view;
    }

    private int dp(int value) { return Math.round(value * density); }

    private final class Catalog extends RecyclerView.Adapter<Row> {
        @NonNull @Override public Row onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            FrameLayout row = new FrameLayout(activity);
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(dp(132), dp(170));
            params.setMargins(dp(6), dp(2), dp(6), dp(2));
            row.setLayoutParams(params);
            row.setPadding(dp(10), dp(12), dp(10), dp(10));
            row.setFocusableInTouchMode(true);
            row.setClickable(true);
            LinearLayout content = new LinearLayout(activity);
            content.setOrientation(LinearLayout.VERTICAL);
            // The status line under the name is only there for a freshly installed app, so the icon
            // and its name sit in the middle of the tile instead of hugging the top.
            content.setGravity(Gravity.CENTER);
            row.addView(content, new FrameLayout.LayoutParams(-1, -1));
            ImageView icon = new ImageView(activity);
            icon.setId(R.id.icon);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            content.addView(icon, new LinearLayout.LayoutParams(dp(60), dp(60)));
            TextView name = text("", 15, Color.WHITE);
            name.setId(R.id.title);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(2);
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(-1, dp(44));
            nameParams.topMargin = dp(10);
            content.addView(name, nameParams);
            TextView status = text("", 11, 0xBBFFFFFF);
            status.setId(R.id.status);
            status.setGravity(Gravity.CENTER);
            status.setMaxLines(2);
            content.addView(status, new LinearLayout.LayoutParams(-1, -2));
            View check = new View(activity);
            check.setId(R.id.dot);
            FrameLayout.LayoutParams badge = new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP | Gravity.END);
            badge.setMargins(0, dp(2), dp(2), 0);
            row.addView(check, badge);
            return new Row(row);
        }
        @Override public void onBindViewHolder(@NonNull Row holder, int position) {
            ResolveInfo app = apps.get(position);
            String pkg = app.activityInfo.packageName;
            bindIcon(holder.itemView.findViewById(R.id.icon), app);
            TextView name = holder.itemView.findViewById(R.id.title);
            name.setText(label(app));
            name.setEllipsize(TextUtils.TruncateAt.END);
            TextView status = holder.itemView.findViewById(R.id.status);
            boolean home = onHome.test(pkg);
            boolean recent = LibraryApps.recent(installTimes.getOrDefault(pkg, 0L), catalogTime);
            // The check in the corner already says the app is on Home; only a fresh install,
            // which has no badge yet, needs a word under the name.
            if (recent) status.setText(R.string.library_recent);
            status.setVisibility(recent ? View.VISIBLE : View.GONE);
            holder.itemView.setBackground(surface(home ? 0x1475BBA4 : 0x0CFFFFFF, 18));
            View check = holder.itemView.findViewById(R.id.dot);
            check.getLayoutParams().width = dp(home ? 18 : 8);
            check.getLayoutParams().height = dp(home ? 18 : 8);
            if (home) check.setBackgroundResource(R.drawable.library_check);
            else check.setBackground(surface(0xFF70D9A0, 8));
            check.setVisibility(home || recent ? View.VISIBLE : View.GONE);
            boolean carried = pkg.equals(carriedPackage);
            holder.itemView.setAlpha(carriedPackage != null && !carried ? 0.45f : 1f);
            holder.itemView.setScaleX(carried ? 1.06f : 1f);
            holder.itemView.setScaleY(carried ? 1.06f : 1f);
            holder.itemView.setOnClickListener(v -> {
                if (carriedPackage != null) dropCarried();
                else if (moveMode) startMove(pkg);   // move mode stays on: pick the next app up
                else open(app);
            });
            holder.itemView.setOnLongClickListener(v -> { showActions(app, v); return true; });
            holder.itemView.setOnFocusChangeListener((v, focused) -> {
                if (focused) {
                    selectedPackage = pkg;
                    updateHint();
                    v.post(() -> { if (v.hasFocus() && catalog != null) highlight(v, true); });
                    int index = holder.getBindingAdapterPosition();
                    if (index != RecyclerView.NO_POSITION) selectedPosition = index;
                }
            });
            holder.itemView.setOnKeyListener((v, key, event) -> {
                int index = holder.getBindingAdapterPosition();
                boolean ok = key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                        || key == KeyEvent.KEYCODE_NUMPAD_ENTER;
                boolean back = key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_ESCAPE;
                if (carriedPackage != null) {
                    // While an app is carried Left/Right slide it through the catalogue and OK puts it
                    // down with the mode still on; Back or Menu leaves the mode. Every other key
                    // (Home, volume) still belongs to the TV.
                    boolean move = key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT;
                    boolean putDown = ok;
                    boolean leave = back || key == KeyEvent.KEYCODE_MENU || key == KeyEvent.KEYCODE_F5;
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        if (move) {
                            int step = key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1;
                            moveCarried(index + (rtl ? -step : step));
                        }
                        return move || putDown || leave;
                    }
                    if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                        if (putDown) dropCarried();
                        else if (leave) leaveMoveMode();
                    }
                    return move || putDown || leave;
                }
                if (moveMode) {
                    // Nothing in hand but the mode is on: OK picks this app up, Back or Menu leaves the
                    // mode. Up/Down still add and remove from Home; arrows still move the focus.
                    if (back || key == KeyEvent.KEYCODE_MENU || key == KeyEvent.KEYCODE_F5) {
                        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) leaveMoveMode();
                        return true;
                    }
                    if (ok) {
                        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) startMove(pkg);
                        return true;
                    }
                }
                boolean edge = (rtl ? key == KeyEvent.KEYCODE_DPAD_RIGHT : key == KeyEvent.KEYCODE_DPAD_LEFT) && index == 0
                        || (rtl ? key == KeyEvent.KEYCODE_DPAD_LEFT : key == KeyEvent.KEYCODE_DPAD_RIGHT) && index == apps.size() - 1;
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    return key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN || edge;
                }
                if (event.getAction() != KeyEvent.ACTION_UP || event.isCanceled()) return false;
                if (key == KeyEvent.KEYCODE_DPAD_UP) {
                    if (home) changeHome(app, false);
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (!home) changeHome(app, true);
                    return true;
                }
                if (key == KeyEvent.KEYCODE_MENU) {
                    showActions(app, v);
                    return true;
                }
                if (key == KeyEvent.KEYCODE_F5) {
                    // The remote's Le button, which the patched keylayout sends as F5 (dbg/fixkl.sh).
                    // Inside the library it is the toggle: a second press closes the catalogue. It is
                    // matched on the keycode because the popup owns the keyboard here, so neither
                    // MainActivity.dispatchKeyEvent nor the top bar sees the press. Menu itself keeps
                    // opening the per-app actions, which is what a real Menu press should do.
                    //
                    // The press is claimed first: the lekeyd daemon also fires ACTION_TOGGLE_LIBRARY
                    // for the same key, and without the claim its intent would arrive a moment later
                    // and open the library straight back up.
                    boolean mine = !(activity instanceof MainActivity) || ((MainActivity) activity).claimLePress();
                    if (mine) close();
                    return true;
                }
                return false;
            });
        }
        @Override public int getItemCount() { return apps.size(); }
    }

    private static final class Row extends RecyclerView.ViewHolder {
        Row(View view) { super(view); }
    }

    /** One sentence the daily-sentence panel has already drawn, kept so Left and Up can return to it. */
    private static final class Quote {
        final String text, from, who;

        Quote(String text, String from, String who) {
            this.text = text;
            this.from = from;
            this.who = who;
        }
    }
}
