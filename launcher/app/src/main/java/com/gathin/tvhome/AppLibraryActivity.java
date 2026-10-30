package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The app library as a layer over whatever app is in front, for the Le button pressed outside Home.
 *
 * <p>Bringing the desktop forward to show the library meant losing the app the user was in - the
 * video they were watching, the page they were on - and then having to find their way back. This
 * activity does the same job with a translucent window: only the library panel is drawn, the app
 * behind it stays on screen, picking an app switches straight to it, and a second Le press or Back
 * puts the user back where they were.
 *
 * <p>Started by the lekeyd daemon (and by HomeKeyService) with ACTION_TOGGLE_LIBRARY in its own
 * task, so finishing lands back on the app that was in front instead of on the desktop. It is not
 * exported: both callers run as root and reach it through `am start`, which bypasses the check.
 *
 * <p>The panel is the very same {@link AppLibraryDialog} Home uses, so both entry points look and
 * behave identically. The one thing this activity cannot know is which apps are already on Home -
 * the desktop may not even be running - so it passes a predicate that marks none of them.
 */
public class AppLibraryActivity extends Activity {

    /** True while the panel is on screen, so the Le button is not acted on twice (see below). */
    private static boolean showing;

    /**
     * How long after this activity starts a second ACTION_TOGGLE_LIBRARY is ignored. Starting the
     * activity takes a moment, and until it reaches onResume the daemon has no marker to look at: a
     * press landing in that gap is the same press that started it, not a request to close.
     */
    private static final long SETTLE_MS = 1200;

    private AppLibraryDialog library;
    private long started;

    static boolean isShowing() {
        return showing;
    }

    /**
     * config.xml le_key_function: which face the panel shows while it covers another app. Home
     * itself always opens the library; this activity is only ever started over a running app (by
     * lekeyd, or by Home when it is not the app in front), so the choice lives here and nowhere else.
     */
    private boolean quote;
    /** config.xml le_hitokoto_categories: the panel has its own filter, separate from Home's. */
    private int quoteCategories = Sentences.ALL;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        started = SystemClock.uptimeMillis();
        Config cfg = Config.load(this);
        quote = cfg.leKeyFunction == Config.LE_KEY_HITOKOTO;
        quoteCategories = cfg.leHitokotoCategories;
        library = new AppLibraryDialog(this, cfg.animations, cfg.reducedMotion,
                pkg -> false,     // no desktop to compare against: nothing is marked as "on Home"
                () -> { },        // nothing behind us to reload
                this::finish);    // closed by an app pick, by Back, or by a second Le press
        // The panel is a popup window, so it needs this activity's window to be attached first.
        final View decor = getWindow().getDecorView();
        if (quote) decor.post(this::showQuotePanel);
        else decor.post(library::show);
    }

    private void showQuotePanel() {
        if (!isFinishing()) library.showQuote(quoteCategories);
    }

    @Override
    protected void onResume() {
        super.onResume();
        showing = true;
        // Same marker Home keeps: the lekeyd daemon reads it and stays out of the way, so this
        // press is handled here - by the panel's own F5 handler - instead of coming back a moment
        // later as a second ACTION_TOGGLE_LIBRARY that would open the library straight back up.
        markForeground(true);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Still coming up: this is the press that started the activity arriving twice, not a
        // second press meant to close it. Closing here would flash the panel open and shut.
        if (SystemClock.uptimeMillis() - started < SETTLE_MS) return;
        // Reachable while the panel is up (the activity finishes as soon as it pauses). Le closes
        // whichever face is showing, exactly like Back does; Right is the one that draws the next
        // sentence, and that is the panel's own key handler.
        if (library != null) library.close();
        else finish();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // The panel's rows take the keys while it is up. This is the fallback for the moment before
        // it appears (and if it never does): without it, a translucent activity with no focusable
        // window in it would swallow both the Le button and Back.
        int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_F5 || key == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                // Le is a plain "get me out" key now, whichever face is up: the same as Back.
                if (library != null) library.close();
                else finish();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // An app was picked, Home was pressed, or something else is taking over. The panel lives in
        // this window and the task must not linger with an invisible popup attached to it.
        showing = false;
        markForeground(false);
        // Hand the daemon the one piece of information it cannot see from outside: the library is
        // gone. It then gives the app behind us its playback back - covering that app stopped it,
        // and a video app does not resume by itself. See dbg/lekeyd/leplay.sh for when it does.
        signalClosed();
        if (library != null) library.close();
        finish();
    }

    /** Leaves files/library_closed behind for lekeyd, which picks it up within a quarter second. */
    private void signalClosed() {
        try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(), "library_closed"))) {
            out.write('1');
        } catch (IOException unwritable) {
            // No flag, no resume: the film stays paused, which is the old behaviour.
        }
    }

    /**
     * Keeps files/foreground holding this process's pid while the panel is up - the same file and
     * the same format {@link MainActivity} uses, because the daemon only knows about one of them.
     * Home rewrites it on its next onResume, which always follows this activity's onPause.
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
            // The daemon then also fires for the key; closing twice is harmless, opening twice is
            // what the popup's own F5 handler already prevents.
        }
    }
}
