package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

/**
 * One input port, full screen. The set does not composite the input under the app windows: it draws it into
 * a Surface its client hands over ({@code CommonDesk.setDisplayHolder}), so switching the port from Home
 * alone leaves the picture hidden behind an opaque launcher. This window is that client - a SurfaceView the
 * set paints into - which is why the factory source panel looks like it "switches" while a bare API call
 * does not.
 *
 * <p>Back or Home closes it, and {@link #onStop()} puts the set back on STORAGE so the launcher is the
 * picture again instead of the port staying selected behind it.
 */
public class SourceViewActivity extends Activity implements SurfaceHolder.Callback {
    static final String EXTRA_CONSTANT = "constant";
    static final String EXTRA_TITLE = "title";

    /** Long enough for the port to have trained before asking whether there is a signal on it. */
    private static final long SIGNAL_CHECK_MS = 1500;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private String constant;
    private String title;
    private TextView hint;
    private TextView message;
    private boolean switched;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        constant = getIntent().getStringExtra(EXTRA_CONSTANT);
        title = getIntent().getStringExtra(EXTRA_TITLE);
        if (constant == null) {
            finish();
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_source);
        hint = findViewById(R.id.hint);
        hint.setText(getString(R.string.source_back_hint));
        message = findViewById(R.id.message);
        ((SurfaceView) findViewById(R.id.surface)).getHolder().addCallback(this);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        // Hand the surface over first: the set needs somewhere to draw before the port changes, or the
        // switch lands on a black screen.
        boolean bound = MstarSourceSwitch.attach(holder);
        if (!switched) {
            if (!bound) {
                // No surface from this set means no picture here: Home sends the port to its own panel
                // instead of leaving the viewer on a black screen.
                setResult(RESULT_FIRST_USER);
                finish();
                return;
            }
            switched = true;
        }
        // Something covered this window (the Settings panel) and the set was put back on STORAGE while it
        // was away, so take the port again now that the picture has somewhere to go.
        if (!constant.equals(MstarSourceSwitch.current()) && !MstarSourceSwitch.switchTo(constant)) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        handler.postDelayed(this::checkSignal, SIGNAL_CHECK_MS);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        handler.removeCallbacksAndMessages(null);
        // Deliberately no setDisplay(null): the native side dereferences the holder and takes the whole
        // process down with a SIGSEGV (checked on this board). The factory app never detaches either - it
        // lets the surface go, which is what stops the set drawing into it.
    }

    /** Says so in the middle of the screen when the port has nothing on it, then fades the way out. */
    private void checkSignal() {
        Boolean connected = MstarSourceSwitch.connected(constant);
        if (connected != null && !connected) {
            // A black screen with no explanation reads as a broken launcher, so this stays up.
            message.setText(title + "\n" + getString(R.string.source_no_signal));
            message.setVisibility(View.VISIBLE);
            return;
        }
        hint.animate().alpha(0f).setStartDelay(3000).setDuration(600).start();
    }

    /** Home and Recents leave this screen for good, so it does not wait in the task to grab the port back. */
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        finish();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Closing it (Back, Home, or a failed switch) is what returns the set to the launcher: the port
        // must not stay selected behind Home, where nothing would be showing but the sound would.
        if (!isFinishing() || isChangingConfigurations()) return;
        handler.removeCallbacksAndMessages(null);
        MstarSourceSwitch.switchTo(MstarSourceSwitch.STORAGE);
    }
}
