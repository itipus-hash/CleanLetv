package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The apps that can be opened from a remote button, and the list that picks one.
 *
 * <p>Used twice for the voice button (scancode 167, keylayout-patched to F6): by MainActivity when
 * the button is pressed and nothing has been chosen yet, and by LauncherSettingsActivity so the
 * choice can be changed later. Both show the same list, so the flow reads the same in either place.
 *
 * <p>TV apps declare LEANBACK_LAUNCHER and phone apps CATEGORY_LAUNCHER; both are listed, TV apps
 * first, and {@link #launch} starts the app through the entry point it actually declares - starting a
 * leanback app with a plain MAIN intent restarts it instead of resuming it.
 */
final class AppPicker {
    /** Rows shown before the list starts scrolling; 7 keeps the dialog well inside a 1080p screen. */
    private static final int MAX_VISIBLE_ROWS = 7;
    /** One row's height. Fixed, so the dialog can be sized to a whole number of rows before layout. */
    private static final int ROW_HEIGHT_DP = 46;

    private AppPicker() { }

    /** One launchable app: what gets saved, and what the list shows. */
    static final class Item {
        final String packageName;
        final String label;

        Item(String packageName, String label) {
            this.packageName = packageName;
            this.label = label;
        }
    }

    /** What the picker reports: a package, or "" for "nothing chosen". */
    interface OnPick {
        void onPick(String value);
    }

    /** A ticked/un-ticked list: reports the whole new mask after every change. */
    interface OnToggle {
        void onToggle(int mask);
    }

    /**
     * Every app the owner installed, TV ones first, then by label. Preinstalled apps are left out: the
     * TV's own Settings, file manager, weather and the rest are noise in a list you pick a shortcut
     * from, and nobody points a remote button at them. Our own launcher is out too - opening Home from
     * Home does nothing visible.
     */
    static List<Item> apps(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Map<String, Item> byPackage = new LinkedHashMap<>();
        // Two passes rather than one: the LEANBACK pass fills the map first, so a package that ships
        // both entry points keeps its TV label and its TV ordering.
        for (String category : new String[]{
                Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo ri : pm.queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                if (ri.activityInfo == null || !ri.activityInfo.exported) continue;
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(ctx.getPackageName()) || byPackage.containsKey(pkg)) continue;
                ApplicationInfo info = ri.activityInfo.applicationInfo;
                // FLAG_UPDATED_SYSTEM_APP too: an app that came with the TV and was then updated from a
                // store is still one the owner never chose to install.
                if (info == null || (info.flags & (ApplicationInfo.FLAG_SYSTEM
                        | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) continue;
                CharSequence loaded = ri.loadLabel(pm);
                String label = loaded == null ? pkg : loaded.toString().trim();
                byPackage.put(pkg, new Item(pkg, label.isEmpty() ? pkg : label));
            }
        }
        List<Item> list = new ArrayList<>(byPackage.values());
        Collections.sort(list, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return list;
    }

    /** The label to show for a saved package, or the package itself when it is gone or unnamed. */
    static String labelOf(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) return "";
        PackageManager pm = ctx.getPackageManager();
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException gone) {
            return pkg;
        }
    }

    /**
     * Opens {@code pkg} the way its own tile would, from any context. False when the app is gone or
     * refuses to start, which is what the caller turns into "pick another one".
     */
    static boolean launch(Context ctx, String pkg) {
        Intent intent = launchIntent(ctx, pkg);
        if (intent == null) return false;
        try {
            ctx.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    /**
     * The intent that opens {@code pkg}, or null when it has no launcher entry left. The component is
     * explicit and carries the category the app declares, so a leanback app resumes rather than
     * restarts; NEW_TASK lets this work from a non-Activity context too.
     */
    private static Intent launchIntent(Context ctx, String pkg) {
        PackageManager pm = ctx.getPackageManager();
        for (String category : new String[]{
                Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            ResolveInfo ri = pm.resolveActivity(
                    new Intent(Intent.ACTION_MAIN).addCategory(category).setPackage(pkg), 0);
            if (ri == null || ri.activityInfo == null) continue;
            return new Intent(Intent.ACTION_MAIN)
                    .setComponent(new ComponentName(pkg, ri.activityInfo.name))
                    .addCategory(category)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        }
        return null;
    }

    /**
     * Shows the list. {@code current} is the saved package (ticked, may be null), {@code clearRes} a
     * "nothing chosen" button - pass 0 for a picker that must end with a choice.
     *
     * <p>The rows are plain TextViews rather than AlertDialog's setSingleChoiceItems: that list's rows
     * are not focusable on this TV, so the D-pad could not move inside it and the dialog could only be
     * cancelled. A focusable row also gets the launcher's own focus outline (input_row_bg), the same
     * one the tile and menu rows use.
     *
     * <p>The question and its one-line hint go in a custom title, not in setMessage: AlertDialog shows
     * a message INSTEAD of a list (both are the same body slot), so a message silently ate the list and
     * left a dialog with one Cancel button.
     */
    static void show(Activity activity, int titleRes, int messageRes, int clearRes,
                     String current, OnPick onPick) {
        List<Item> apps = apps(activity);
        String[] labels = new String[apps.size()];
        String[] values = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            labels[i] = apps.get(i).label;
            values[i] = apps.get(i).packageName;
        }
        showChoice(activity, titleRes, messageRes, clearRes, labels, values, current, onPick);
    }

    /**
     * The same dialog for a choice that is not an app: the Le-key setting picks between the library
     * and the daily sentence. Labels and values are handed in, everything else - the focusable rows,
     * the check mark, the widened window - is the machinery the app list uses.
     */
    static void showChoice(Activity activity, int titleRes, int messageRes, int clearRes,
                           String[] labels, String[] values, String current, OnPick onPick) {
        float density = activity.getResources().getDisplayMetrics().density;
        int rowHeight = (int) (ROW_HEIGHT_DP * density);

        LinearLayout rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (8 * density);
        rows.setPadding(pad, 0, pad, 0);

        ScrollView scroll = new MaxHeightScrollView(activity,
                rowHeight * Math.max(1, Math.min(labels.length, MAX_VISIBLE_ROWS)));
        scroll.addView(rows, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setCustomTitle(header(activity, titleRes, messageRes))
                .setView(scroll)
                .setNegativeButton(android.R.string.cancel, null);
        if (clearRes != 0) {
            builder.setNeutralButton(clearRes, (dialog, which) -> onPick.onPick(""));
        }
        AlertDialog dialog = builder.create();

        int checked = 0;
        for (int i = 0; i < labels.length; i++) {
            final String value = values[i];
            boolean selected = value.equals(current);
            if (selected) checked = i;
            TextView row = row(activity, labels[i], selected, rowHeight, density);
            row.setOnClickListener(v -> {
                dialog.dismiss();
                onPick.onPick(value);
            });
            rows.addView(row);
        }
        final int start = checked;
        dialog.setOnShowListener(d -> {
            // Start on the app already chosen, so the list opens where the eye expects it.
            View first = rows.getChildAt(Math.min(start, rows.getChildCount() - 1));
            if (first != null) first.requestFocus();
        });
        dialog.show();
        // AlertDialog's default width is narrow: the question wrapped mid-sentence with one word on the
        // second line. Widen it, capped so it never covers the screen.
        Window window = dialog.getWindow();
        if (window != null) {
            int screen = activity.getResources().getDisplayMetrics().widthPixels;
            window.setLayout(Math.min((int) (480 * density), (int) (screen * 0.9f)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    /**
     * A list where several rows can be ticked at once - the sentence categories. OK toggles the
     * focused row and the list stays open (twelve categories one dialog at a time would be silly);
     * Back or the OK button closes it. Every toggle is reported immediately, so the caller saves as
     * the owner ticks, and the desktop behind the dialog is already following the new filter.
     *
     * <p>The last tick cannot be removed: an empty filter would leave both faces with nothing to
     * show, which reads as a bug rather than a choice.
     */
    static void showMulti(Activity activity, int titleRes, int messageRes,
                          String[] labels, int mask, OnToggle onToggle) {
        float density = activity.getResources().getDisplayMetrics().density;
        int rowHeight = (int) (ROW_HEIGHT_DP * density);

        LinearLayout rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (8 * density);
        rows.setPadding(pad, 0, pad, 0);

        ScrollView scroll = new MaxHeightScrollView(activity,
                rowHeight * Math.max(1, Math.min(labels.length, MAX_VISIBLE_ROWS)));
        scroll.addView(rows, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setCustomTitle(header(activity, titleRes, messageRes))
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .create();

        final int[] state = {mask};
        for (int i = 0; i < labels.length; i++) {
            final int bit = 1 << i;
            final String label = labels[i];
            final TextView row = row(activity, label, (state[0] & bit) != 0, rowHeight, density);
            row.setOnClickListener(v -> {
                int next = state[0] ^ bit;
                if (next == 0) return;
                state[0] = next;
                boolean ticked = (next & bit) != 0;
                row.setText(ticked ? "✓  " + label : label);
                row.setTypeface(ticked ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                onToggle.onToggle(next);
            });
            rows.addView(row);
        }
        dialog.setOnShowListener(d -> {
            View first = rows.getChildAt(0);
            if (first != null) first.requestFocus();
        });
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            int screen = activity.getResources().getDisplayMetrics().widthPixels;
            window.setLayout(Math.min((int) (480 * density), (int) (screen * 0.9f)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    /** One app row: focusable, white label, and a tick on the app that is chosen now. */
    private static TextView row(Activity activity, String label, boolean selected,
                                int rowHeight, float density) {
        TextView row = new TextView(activity);
        row.setText(selected ? "✓  " + label : label);
        row.setTextSize(16);
        row.setTextColor(0xFFFFFFFF);
        row.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        row.setSingleLine(true);
        row.setEllipsize(TextUtils.TruncateAt.END);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int side = (int) (14 * density);
        row.setPadding(side, 0, side, 0);
        row.setBackgroundResource(R.drawable.input_row_bg);
        // focusableInTouchMode as well: a phone remote or a mouse puts the TV in touch mode, where a
        // plain focusable view cannot take focus (the black-screen bug of 2026-10-23).
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        row.setClickable(true);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, rowHeight));
        return row;
    }

    /**
     * A ScrollView that stops growing at {@code maxHeight}. AlertDialog forces its custom view to
     * MATCH_PARENT in both directions, so a height set on the view itself is thrown away and a long
     * list would fill the screen and get clipped mid-row; clamping the measurement is the only way
     * left to make it scroll.
     */
    private static final class MaxHeightScrollView extends ScrollView {
        private final int maxHeight;

        MaxHeightScrollView(Context ctx, int maxHeight) {
            super(ctx);
            this.maxHeight = maxHeight;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec,
                    MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST));
        }
    }

    /** The title block: the question, and under it the one sentence that says what picking does. */
    private static View header(Activity activity, int titleRes, int messageRes) {
        float density = activity.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (24 * density), (int) (20 * density),
                (int) (24 * density), (int) (4 * density));
        TextView title = new TextView(activity);
        title.setText(titleRes);
        title.setTextSize(20);
        title.setTextColor(0xFFFFFFFF);
        box.addView(title);
        if (messageRes != 0) {
            TextView hint = new TextView(activity);
            hint.setText(messageRes);
            hint.setTextSize(14);
            hint.setTextColor(0xFFACB8C1);   // the same grey the settings rows use for their hints
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = (int) (6 * density);
            box.addView(hint, lp);
        }
        return box;
    }
}
