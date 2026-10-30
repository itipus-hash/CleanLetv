package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;

/** Remote-friendly settings owned by the launcher; system settings stay one row away. */
public final class LauncherSettingsActivity extends Activity {
    /** Rows below the dock the launcher can actually show on this screen; the picker hides the rest. */
    static final String EXTRA_MAX_ROWS_BELOW_DOCK = "max_rows_below_dock";
    private SharedPreferences choices;
    private final TextView[] columnChoices = new TextView[4];
    private final TextView[] rowChoices = new TextView[4];
    private TextView wallpaperChoice;
    private TextView animationsChoice;
    private TextView advancedChoice;
    private TextView followThemeChoice;
    private TextView greetingNameChoice;
    private TextView statsChoice;
    private TextView hitokotoChoice;
    private TextView hitokotoCategoriesChoice;
    private TextView leKeyCategoriesChoice;
    private TextView voiceKeyChoice;
    private TextView leKeyFunctionChoice;
    private TextView homeLeKeyFunctionChoice;
    private TextView libraryButtonChoice;
    private TextView weatherCityChoice;
    private TextView weatherKeyChoice;
    private TextView weatherHostChoice;
    private TextView weatherChoice;
    private String greetingName;
    private String voiceKeyApp;
    /** The city the weather line names; empty means "locate me from the network address". */
    private String weatherCity = "";
    private String weatherApiKey = "";
    private String weatherApiHost = Config.DEFAULT_WEATHER_HOST;
    private int columns;
    private int rowsBelowDock;
    /** From MainActivity: the most rows below the dock this screen can show. 3 when not told. */
    private int maxRowsBelowDock = 3;
    private boolean libraryButton;
    /** config.xml le_key_function: what the Le key opens while an app is in front. */
    private int leKeyFunction;
    /** config.xml home_le_key_function: what the Le key opens on Home itself. */
    private int homeLeKeyFunction;
    private boolean animations;
    private boolean advancedEffects;
    private boolean showStats;
    private boolean showHitokoto;
    private int weatherMode;
    private int hitokotoCategories = Sentences.ALL;
    private int leHitokotoCategories = Sentences.ALL;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        Config config = Config.load(this);
        columns = config.columns;
        rowsBelowDock = config.rowsBelowDock;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;
        greetingName = config.greetingName;
        showStats = config.showStats;
        showHitokoto = config.showHitokoto;
        weatherMode = config.weatherMode;
        hitokotoCategories = config.hitokotoCategories;
        leHitokotoCategories = config.leHitokotoCategories;
        voiceKeyApp = config.voiceKeyApp;
        // Config already prefers the on-TV choice over the theme's, so these three come back with
        // what is in force - which is what the rows have to show.
        weatherCity = choices.getString(Config.WEATHER_CITY_KEY, "").trim();
        weatherApiKey = config.weatherApiKey;
        weatherApiHost = config.weatherApiHost;
        libraryButton = config.libraryButton;
        leKeyFunction = config.leKeyFunction;
        homeLeKeyFunction = config.homeLeKeyFunction;

        int[] ids = {R.id.columns4, R.id.columns5, R.id.columns6, R.id.columns7};
        for (int i = 0; i < ids.length; i++) {
            final int value = i + 4;
            columnChoices[i] = findViewById(ids[i]);
            columnChoices[i].setOnClickListener(v -> {
                if (columns == value) return;
                columns = value;
                choices.edit().putInt("columns", value).apply();
                setResult(RESULT_OK);
                refresh();
            });
        }
        int[] rowIds = {R.id.rows0, R.id.rows1, R.id.rows2, R.id.rows3};
        maxRowsBelowDock = Math.max(0, getIntent().getIntExtra(EXTRA_MAX_ROWS_BELOW_DOCK, 3));
        // Only offer the counts the screen can actually show: 3 rows pushed the dock against the focused
        // app's name and came out looking exactly like 2, which reads as "the setting does nothing".
        for (int i = 0; i < rowIds.length; i++) {
            final int value = i;
            rowChoices[i] = findViewById(rowIds[i]);
            rowChoices[i].setVisibility(value > maxRowsBelowDock ? View.GONE : View.VISIBLE);
            rowChoices[i].setOnClickListener(v -> {
                if (rowsBelowDock == value) return;
                rowsBelowDock = value;
                choices.edit().putInt(Config.ROWS_BELOW_DOCK_KEY, value).apply();
                setResult(RESULT_OK);
                refresh();
            });
        }
        // Keep the numeric choices increasing in the Hebrew reading direction. Reorder the
        // actual views so D-pad focus follows their positions without changing the saved values.
        if (getResources().getBoolean(R.bool.rtl_app_grid)) {
            reverseRow(columnChoices);
            reverseRow(rowChoices);
        }
        wallpaperChoice = findViewById(R.id.wallpaperChoice);
        wallpaperChoice.setOnClickListener(v -> showWallpaperPicker());
        greetingNameChoice = findViewById(R.id.greetingNameChoice);
        greetingNameChoice.setOnClickListener(v -> showGreetingNameDialog());
        weatherCityChoice = findViewById(R.id.weatherCityChoice);
        weatherCityChoice.setOnClickListener(v -> showWeatherCityDialog());
        weatherKeyChoice = findViewById(R.id.weatherKeyChoice);
        weatherKeyChoice.setOnClickListener(v -> showWeatherKeyDialog());
        weatherHostChoice = findViewById(R.id.weatherHostChoice);
        weatherChoice = findViewById(R.id.weatherChoice);
        weatherHostChoice.setOnClickListener(v -> showWeatherHostDialog());
        weatherChoice.setOnClickListener(v -> showWeatherModePicker());
        // The hint under each credential row goes with it: hiding the row and leaving the hint
        // behind would orphan a sentence about a setting that is no longer there.
        weatherCityChoice.setTag(findViewById(R.id.weatherCityHint));
        weatherKeyChoice.setTag(findViewById(R.id.weatherKeyHint));
        weatherHostChoice.setTag(findViewById(R.id.weatherHostHint));
        statsChoice = findViewById(R.id.statsChoice);
        statsChoice.setOnClickListener(v -> {
            showStats = !showStats;
            choices.edit().putBoolean(Config.SHOW_STATS_KEY, showStats).apply();
            setResult(RESULT_OK);
            refresh();
        });
        hitokotoChoice = findViewById(R.id.hitokotoChoice);
        hitokotoChoice.setOnClickListener(v -> {
            showHitokoto = !showHitokoto;
            choices.edit().putBoolean(Config.SHOW_HITOKOTO_KEY, showHitokoto).apply();
            setResult(RESULT_OK);
            refresh();
        });
        hitokotoCategoriesChoice = findViewById(R.id.hitokotoCategoriesChoice);
        hitokotoCategoriesChoice.setOnClickListener(v -> showHitokotoCategories());
        voiceKeyChoice = findViewById(R.id.voiceKeyChoice);
        voiceKeyChoice.setOnClickListener(v -> showVoiceKeyAppPicker());
        leKeyFunctionChoice = findViewById(R.id.leKeyFunctionChoice);
        leKeyFunctionChoice.setOnClickListener(v -> showLeKeyFunctionPicker());
        homeLeKeyFunctionChoice = findViewById(R.id.homeLeKeyFunctionChoice);
        homeLeKeyFunctionChoice.setOnClickListener(v -> showHomeLeKeyFunctionPicker());
        leKeyCategoriesChoice = findViewById(R.id.leKeyCategoriesChoice);
        leKeyCategoriesChoice.setOnClickListener(v -> showLeKeyCategories());
        libraryButtonChoice = findViewById(R.id.libraryButtonChoice);
        libraryButtonChoice.setOnClickListener(v -> {
            libraryButton = !libraryButton;
            choices.edit().putBoolean(Config.LIBRARY_BUTTON_KEY, libraryButton).apply();
            setResult(RESULT_OK);
            refresh();
        });
        animationsChoice = findViewById(R.id.animationsChoice);
        animationsChoice.setOnClickListener(v -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations)
                    .putBoolean("advanced_effects", advancedEffects).apply();
            setResult(RESULT_OK);
            refresh();
        });
        advancedChoice = findViewById(R.id.advancedChoice);
        advancedChoice.setOnClickListener(v -> {
            if (advancedEffects) {
                setAdvanced(false);
            } else {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.advanced_effects)
                        .setMessage(R.string.advanced_effects_warning)
                        .setPositiveButton(R.string.enable, (dialog, which) -> setAdvanced(true))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });
        followThemeChoice = findViewById(R.id.followThemeChoice);
        followThemeChoice.setOnClickListener(v -> {
            if (!hasOnTvChoices()) return;
            // Every key hasOnTvChoices() looks at has to go, or the row would stay unchecked (and
            // one setting would quietly keep its on-TV value) after a restore that claims to be
            // complete. Home's own Le-key choice was the one left behind.
            choices.edit().remove("columns").remove(Config.ROWS_BELOW_DOCK_KEY).remove("animations")
                    .remove("advanced_effects").remove(Config.WALLPAPER_CHOICE_KEY)
                    .remove(Config.GREETING_NAME_KEY).remove(Config.SHOW_STATS_KEY)
                    .remove(Config.SHOW_HITOKOTO_KEY).remove(Config.WEATHER_MODE_KEY)
                    .remove(Config.HITOKOTO_CATEGORIES_KEY)
                    .remove(Config.LE_HITOKOTO_CATEGORIES_KEY).remove(Config.VOICE_KEY_APP)
                    .remove(Config.LIBRARY_BUTTON_KEY).remove(Config.LE_KEY_FUNCTION_KEY)
                    .remove(Config.HOME_LE_KEY_FUNCTION_KEY)
                    .commit();
            Config current = Config.load(this);
            columns = current.columns;
            rowsBelowDock = current.rowsBelowDock;
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
            greetingName = current.greetingName;
            showStats = current.showStats;
            showHitokoto = current.showHitokoto;
            weatherMode = current.weatherMode;
            hitokotoCategories = current.hitokotoCategories;
            leHitokotoCategories = current.leHitokotoCategories;
            voiceKeyApp = current.voiceKeyApp;
            libraryButton = current.libraryButton;
            leKeyFunction = current.leKeyFunction;
            homeLeKeyFunction = current.homeLeKeyFunction;
            setResult(RESULT_OK);
            refresh();
        });
        findViewById(R.id.defaultHomeChoice).setOnClickListener(v -> {
            Toast.makeText(this, R.string.home_settings_help, Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_HOME_SETTINGS));
        });
        // The hint goes with the row: with one desktop there is no chooser to open, so neither the
        // row nor the sentence under it has anything left to say.
        findViewById(R.id.defaultHomeChoice).setTag(findViewById(R.id.defaultHomeHint));
        findViewById(R.id.tvSettingsChoice).setOnClickListener(v ->
                open(SystemSettings.intent(this, Settings.ACTION_SETTINGS)));
        refresh();
        columnChoices[Math.max(0, Math.min(columns - 4, 3))].requestFocus();
    }

    private void reverseRow(TextView[] views) {
        LinearLayout row = (LinearLayout) views[0].getParent();
        row.removeAllViews();
        for (int i = views.length - 1; i >= 0; i--) row.addView(views[i]);
    }

    private void showWallpaperPicker() {
        File dir = Config.dir(this);
        List<File> images = new java.util.ArrayList<>(WallpaperRotation.images(
                dir == null ? null : new File(dir, "wallpapers")));
        // A theme with a single wallpaper.jpg offers that picture too (Config maps the choice back to it).
        File single = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        if (single != null && single.isFile()) images.add(0, single);
        String[] labels = new String[images.size() + 1];
        for (int i = 0; i < images.size(); i++) labels[i] = images.get(i).getName();
        labels[images.size()] = getString(R.string.wallpaper_none);
        String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        int checked = "none".equals(current) ? images.size()
                : indexOf(images, current);
        new AlertDialog.Builder(this)
                .setTitle(R.string.wallpaper_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == images.size()) {
                        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, "none").apply();
                    } else {
                        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY,
                                images.get(which).getName()).apply();
                    }
                    setResult(RESULT_OK);
                    Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNeutralButton(R.string.wallpaper_theme_default, (dialog, which) -> {
                    choices.edit().remove(Config.WALLPAPER_CHOICE_KEY).apply();
                    setResult(RESULT_OK);
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * The name shown after the greeting, typed with the TV's own keyboard.
     *
     * <p>It is a text field rather than a list of choices because a name is not a fixed set, and the
     * set's Sogou keyboard handles pinyin, so a Chinese name is a few D-pad presses. The theme can
     * still supply one; "theme default" drops the on-TV choice and goes back to it.
     */
    private void showGreetingNameDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        // Show what the row shows: the effective name, which is the theme's when nothing was typed.
        String current = choices.contains(Config.GREETING_NAME_KEY)
                ? choices.getString(Config.GREETING_NAME_KEY, "") : greetingName;
        input.setText(current);
        input.setSelection(input.getText().length());
        input.setHint(R.string.greeting_name_none);

        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        FrameLayout frame = new FrameLayout(this);
        frame.setPadding(pad, pad / 2, pad, 0);
        frame.addView(input, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.greeting_name_label)
                .setView(frame)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    greetingName = input.getText().toString().trim();
                    choices.edit().putString(Config.GREETING_NAME_KEY, greetingName).apply();
                    setResult(RESULT_OK);
                    refresh();
                })
                .setNeutralButton(R.string.greeting_name_theme_default, (d, which) -> {
                    choices.edit().remove(Config.GREETING_NAME_KEY).apply();
                    greetingName = Config.load(this).greetingName;
                    setResult(RESULT_OK);
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(d -> {
            input.requestFocus();
            // The keyboard has to come up by itself: a D-pad press on the field only focuses it.
            dialog.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        });
        dialog.show();
    }

    /** Hands the typed text back so the shared dialog can update the field behind the row. */
    private interface WeatherField {
        void set(String value);
    }

    /**
     * The city the weather line names. Empty is the interesting answer: it means "work out where
     * this set is from its network address", which is what a TV wants by default - it has no GPS
     * and nobody wants to type a city name into a television.
     */
    private void showWeatherCityDialog() {
        showWeatherTextDialog(R.string.weather_city_label, R.string.weather_city_auto,
                Config.WEATHER_CITY_KEY, weatherCity, v -> weatherCity = v);
    }

    /** The QWeather key. Typed rather than picked, and never echoed back in full on the row. */
    private void showWeatherKeyDialog() {
        showWeatherTextDialog(R.string.weather_key_label, R.string.weather_key_none,
                Config.WEATHER_API_KEY_KEY, weatherApiKey, v -> weatherApiKey = v);
    }

    /**
     * The QWeather API host. Every developer account is issued its own, and QWeather is retiring
     * the public domains - so this is the one field where the default is known to be temporary.
     */
    private void showWeatherHostDialog() {
        showWeatherTextDialog(R.string.weather_host_label, R.string.weather_host_hint,
                Config.WEATHER_API_HOST_KEY, weatherApiHost, v -> weatherApiHost = v);
    }

    /**
     * One typed field, saved straight through to the settings. The three weather rows are the same
     * shape - a city name and two credentials are not a fixed set of choices - so they share this
     * instead of three near-copies of the greeting dialog. Unlike the greeting there is no "theme
     * default" button: an empty value is already the meaningful default for all three.
     */
    private void showWeatherTextDialog(int titleRes, int hintRes, String key, String current,
                                       WeatherField field) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(current);
        input.setSelection(input.getText().length());
        input.setHint(hintRes);

        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        FrameLayout frame = new FrameLayout(this);
        frame.setPadding(pad, pad / 2, pad, 0);
        frame.addView(input, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(titleRes)
                .setView(frame)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    String typed = input.getText().toString().trim();
                    field.set(typed);
                    choices.edit().putString(key, typed).apply();
                    setResult(RESULT_OK);
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(d -> {
            input.requestFocus();
            // The keyboard has to come up by itself: a D-pad press on the field only focuses it.
            dialog.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        });
        dialog.show();
    }

    /**
     * The app the remote's voice button opens. The very list MainActivity shows on a first press, so
     * both entry points look the same; "not set" drops the choice, and the next press asks again.
     */
    private void showVoiceKeyAppPicker() {        AppPicker.show(this, R.string.voice_key_label, 0, R.string.voice_key_none, voiceKeyApp, pkg -> {
            voiceKeyApp = pkg;
            SharedPreferences.Editor edit = choices.edit();
            if (pkg.isEmpty()) edit.remove(Config.VOICE_KEY_APP);
            else edit.putString(Config.VOICE_KEY_APP, pkg);
            edit.apply();
            setResult(RESULT_OK);
            refresh();
        });
    }

    /**
     * What the Le key opens while an app is in front. The library keeps the app list, the daily
     * sentence reuses the very same panel with the day's quote in it - covering the running app
     * either way, which is the point of the choice. On Home the key always opens the library, so
     * this never changes how the desktop behaves.
     */
    /**
     * The twelve sentence categories, all ticked by default. Every toggle is saved at once, so the
     * choice survives even if the screen is killed before the dialog closes - and the desktop behind
     * the dialog picks up the new filter on its next resume.
     */
    private void showHitokotoCategories() {
        AppPicker.showMulti(this, R.string.hitokoto_categories, R.string.hitokoto_categories_hint,
                Sentences.CATEGORIES, hitokotoCategories, mask -> {
                    hitokotoCategories = mask;
                    choices.edit().putInt(Config.HITOKOTO_CATEGORIES_KEY, mask).apply();
                    setResult(RESULT_OK);
                    refresh();
                });
    }

    /** The panel's own filter: separate from Home's, and saved the same way. */
    private void showLeKeyCategories() {
        AppPicker.showMulti(this, R.string.le_key_categories, R.string.hitokoto_categories_hint,
                Sentences.CATEGORIES, leHitokotoCategories, mask -> {
                    leHitokotoCategories = mask;
                    choices.edit().putInt(Config.LE_HITOKOTO_CATEGORIES_KEY, mask).apply();
                    setResult(RESULT_OK);
                    refresh();
                });
    }

    /**
     * The row's value: "全部" while every category is ticked (or none - an empty mask falls back to
     * all, see Hitokoto.load), otherwise the categories themselves - "诗词、动画" reads better than a
     * count, because the count says nothing about what will actually come up on the desktop.
     */
    private String categorySummary(int mask) {
        int ticked = mask & Sentences.ALL;
        if (ticked == 0 || Integer.bitCount(ticked) >= Sentences.CATEGORIES.length) {
            return getString(R.string.hitokoto_categories_all);
        }
        StringBuilder names = new StringBuilder();
        for (int c = 0; c < Sentences.CATEGORIES.length; c++) {
            if ((ticked & (1 << c)) == 0) continue;
            if (names.length() > 0) names.append(getString(R.string.category_separator));
            names.append(Sentences.CATEGORIES[c]);
        }
        return names.toString();
    }

    private void showLeKeyFunctionPicker() {
        String[] labels = {getString(R.string.app_library), getString(R.string.hitokoto_title)};
        String[] values = {String.valueOf(Config.LE_KEY_LIBRARY),
                String.valueOf(Config.LE_KEY_HITOKOTO)};
        // AppPicker's rows rather than setSingleChoiceItems: those CheckedTextView rows take no
        // focus on this set, and a remote could then only reach Cancel.
        AppPicker.showChoice(this, R.string.le_key_function, 0, 0, labels, values,
                String.valueOf(leKeyFunction), value -> {
                    leKeyFunction = Integer.parseInt(value);
                    choices.edit().putInt(Config.LE_KEY_FUNCTION_KEY, leKeyFunction).apply();
                    setResult(RESULT_OK);
                    refresh();
                });
    }

    /**
     * Same two choices as the row above, but for the panel Home itself draws: a separate key, so the
     * desktop can show the sentence while an app in front still gets the app list.
     */
    private void showHomeLeKeyFunctionPicker() {
        String[] labels = {getString(R.string.app_library), getString(R.string.hitokoto_title)};
        String[] values = {String.valueOf(Config.LE_KEY_LIBRARY),
                String.valueOf(Config.LE_KEY_HITOKOTO)};
        AppPicker.showChoice(this, R.string.home_le_key_function, 0, 0, labels, values,
                String.valueOf(homeLeKeyFunction), value -> {
                    homeLeKeyFunction = Integer.parseInt(value);
                    choices.edit().putInt(Config.HOME_LE_KEY_FUNCTION_KEY, homeLeKeyFunction).apply();
                    setResult(RESULT_OK);
                    refresh();
                });
    }

    private static int indexOf(List<File> images, String name) {
        if (name == null) return -1;
        for (int i = 0; i < images.size(); i++) {
            if (images.get(i).getName().equals(name)) return i;
        }
        return -1;
    }

    private void setAdvanced(boolean enabled) {
        advancedEffects = enabled;
        choices.edit().putBoolean("advanced_effects", enabled).apply();
        setResult(RESULT_OK);
        refresh();
    }

    private void refresh() {
        for (int i = 0; i < columnChoices.length; i++) {
            columnChoices[i].setSelected(columns == i + 4);
        }
        // A theme can ask for more rows than the screen has room for: show the one that is actually drawn.
        int effectiveRows = Math.min(rowsBelowDock, maxRowsBelowDock);
        for (int i = 0; i < rowChoices.length; i++) {
            rowChoices[i].setSelected(effectiveRows == i);
        }
        animationsChoice.setText(getString(R.string.animations_state,
                getString(animations ? R.string.on : R.string.off)));
        advancedChoice.setText(getString(R.string.advanced_effects_state,
                getString(advancedEffects ? R.string.on : R.string.off)));
        advancedChoice.setEnabled(animations);
        advancedChoice.setAlpha(animations ? 1f : 0.5f);
        String wallpaperCurrent = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        wallpaperChoice.setText(getString(R.string.wallpaper_state,
                "none".equals(wallpaperCurrent) ? getString(R.string.wallpaper_none)
                        : wallpaperCurrent != null ? wallpaperCurrent
                        : getString(R.string.wallpaper_theme_default)));
        greetingNameChoice.setText(getString(R.string.greeting_name_state,
                greetingName.isEmpty() ? getString(R.string.greeting_name_none) : greetingName));
        // The key is never echoed in full: a settings screen is read from across the room, and
        // "set" versus "not set" is the only thing that matters at that distance.
        weatherCityChoice.setText(getString(R.string.weather_city_state,
                weatherCity.isEmpty() ? getString(R.string.weather_city_auto) : weatherCity));
        weatherKeyChoice.setText(getString(R.string.weather_key_state,
                weatherApiKey.isEmpty() ? getString(R.string.weather_key_none)
                        : getString(R.string.weather_key_set)));
        weatherHostChoice.setText(getString(R.string.weather_host_state, weatherApiHost));
        statsChoice.setText(getString(R.string.stats_state,
                getString(showStats ? R.string.on : R.string.off)));
        weatherChoice.setText(getString(R.string.weather_mode_state,
                getString(weatherModeLabel(weatherMode))));
        // With the weather off there is nothing left to set up: the city, the key and the host all
        // describe a line that is not drawn, so they step out of the way instead of teasing.
        boolean weatherOn = weatherMode != Config.WEATHER_OFF;
        setRowVisible(weatherCityChoice, weatherOn);
        setRowVisible(weatherKeyChoice, weatherOn);
        setRowVisible(weatherHostChoice, weatherOn);
        // The desktop switch opens the system's home chooser, so it is only worth a row while the
        // chooser would have something to choose between. A cleaned set has the stock launcher
        // disabled: one desktop, one row less.
        setRowVisible(findViewById(R.id.defaultHomeChoice), launcherCount() > 1);
        hitokotoChoice.setText(getString(R.string.hitokoto_state,
                getString(showHitokoto ? R.string.on : R.string.off)));
        hitokotoCategoriesChoice.setText(getString(R.string.hitokoto_categories_state,
                categorySummary(hitokotoCategories)));
        leKeyCategoriesChoice.setText(getString(R.string.le_key_categories_state,
                categorySummary(leHitokotoCategories)));
        voiceKeyChoice.setText(getString(R.string.voice_key_state,
                voiceKeyApp.isEmpty() ? getString(R.string.voice_key_none)
                        : AppPicker.labelOf(this, voiceKeyApp)));
        leKeyFunctionChoice.setText(getString(R.string.le_key_function_state,
                getString(leKeyFunction == Config.LE_KEY_HITOKOTO
                        ? R.string.hitokoto_title : R.string.app_library)));
        homeLeKeyFunctionChoice.setText(getString(R.string.home_le_key_function_state,
                getString(homeLeKeyFunction == Config.LE_KEY_HITOKOTO
                        ? R.string.hitokoto_title : R.string.app_library)));
        libraryButtonChoice.setText(getString(R.string.library_button_state,
                getString(libraryButton ? R.string.on : R.string.off)));
        followThemeChoice.setSelected(!hasOnTvChoices());
    }

    /** The four levels, in the order they are offered. */
    // The sun-only level sits above the humidity one because its label is the shorter of the two:
    // read down the list, the lines grow instead of jumping back to a short one.
    private static final int[] WEATHER_MODES = {
            Config.WEATHER_OFF, Config.WEATHER_SKY, Config.WEATHER_SUN,
            Config.WEATHER_EXTRA, Config.WEATHER_EXTRA_SUN,
    };

    private static final int[] WEATHER_MODE_LABELS = {
            R.string.weather_mode_off, R.string.weather_mode_sky, R.string.weather_mode_sun,
            R.string.weather_mode_extra, R.string.weather_mode_extra_sun,
    };

    private static int weatherModeLabel(int mode) {
        for (int i = 0; i < WEATHER_MODES.length; i++) {
            if (WEATHER_MODES[i] == mode) return WEATHER_MODE_LABELS[i];
        }
        return WEATHER_MODE_LABELS[WEATHER_MODE_LABELS.length - 1];
    }

    /**
     * How much weather Home draws. One choice of five: off, the sky alone, the sky with humidity
     * and ultraviolet, the sky with the sun, or all three lines. The dialog carries no hint of its
     * own - the labels say the whole thing, and the row underneath explains what off costs.
     */
    private void showWeatherModePicker() {
        String[] labels = new String[WEATHER_MODES.length];
        String[] values = new String[WEATHER_MODES.length];
        for (int i = 0; i < WEATHER_MODES.length; i++) {
            labels[i] = getString(WEATHER_MODE_LABELS[i]);
            values[i] = String.valueOf(WEATHER_MODES[i]);
        }
        AppPicker.showChoice(this, R.string.weather_mode_title, 0, 0,
                labels, values, String.valueOf(weatherMode), value -> {
                    int mode = Config.WEATHER_SUN;
                    try {
                        mode = Integer.parseInt(value);
                    } catch (NumberFormatException ignored) {
                        // A value that is not a number keeps the fullest level rather than losing
                        // the weather over a typo.
                    }
                    weatherMode = mode;
                    choices.edit().putInt(Config.WEATHER_MODE_KEY, weatherMode).apply();
                    setResult(RESULT_OK);
                    refresh();
                });
    }

    /** A row and the hint under it share one visibility, so a switch can take both away at once. */
    private void setRowVisible(View row, boolean visible) {
        row.setVisibility(visible ? View.VISIBLE : View.GONE);
        Object tag = row.getTag();
        if (tag instanceof View) ((View) tag).setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /**
     * How many launchers the TV's home chooser can offer. Disabled ones - the stock launcher on a
     * cleaned set - do not count: the chooser never shows them, so a row that opens it would be a
     * dead end with one entry in it.
     */
    private int launcherCount() {
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        return getPackageManager().queryIntentActivities(home, 0).size();
    }

    private boolean hasOnTvChoices() {
        // The three weather credential keys are deliberately absent, here and in the reset above:
        // a city name and two credentials are setup, not a choice between the theme and the TV,
        // and clearing them would silently take the weather line down until the key was typed in
        // again. The display switch above them is a plain choice, so it does reset with the rest.
        return choices.contains("columns") || choices.contains(Config.ROWS_BELOW_DOCK_KEY)
                || choices.contains("animations") || choices.contains("advanced_effects")
                || choices.contains(Config.WALLPAPER_CHOICE_KEY)
                || choices.contains(Config.GREETING_NAME_KEY)
                || choices.contains(Config.SHOW_STATS_KEY)
                || choices.contains(Config.SHOW_HITOKOTO_KEY)
                || choices.contains(Config.WEATHER_MODE_KEY)
                || choices.contains(Config.HITOKOTO_CATEGORIES_KEY)
                || choices.contains(Config.LE_HITOKOTO_CATEGORIES_KEY)
                || choices.contains(Config.VOICE_KEY_APP)
                || choices.contains(Config.LIBRARY_BUTTON_KEY)
                || choices.contains(Config.LE_KEY_FUNCTION_KEY)
                || choices.contains(Config.HOME_LE_KEY_FUNCTION_KEY);
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
