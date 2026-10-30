package com.gathin.tvhome;

import android.util.Log;
import android.view.SurfaceHolder;

import java.lang.reflect.Method;

/**
 * Drives the input the way the factory source panel does, through the TV API this MStar/LETV board keeps
 * on the boot classpath (signalcommon.jar, com.mstar.android.jar). Nothing is bundled and nothing is
 * compiled against: the classes are resolved by reflection, so the launcher stays a plain app and the same
 * APK still runs on sets that have no such API.
 *
 * <p>The factory path, from the decompiled SignalSourceManager: {@code PortManager.doPortSwitch(name)}
 * looks the name up in its port table and calls {@code CommonDesk.SetInputSource(EnumInputSource)}, which
 * forwards to {@code TvCommonManager.setInputSource(enum)}. The enum constants below are the port table's,
 * in its order (ATV, DTV, CVBS, VGA, YPBPR, HDMI, HDMI2, HDMI3).
 *
 * <p>Switching the port is not enough to see the picture: on this board the set draws the input into a
 * Surface its client hands it, {@code CommonDesk.setDisplayHolder}. Without a holder the port changes
 * underneath Home and the screen never moves, which is what made the first version of the menu look dead.
 */
final class MstarSourceSwitch {
    private static final String TAG = "MstarSourceSwitch";
    private static final String DESK = "com.mstar.tvsrvfunc.CommonDeskImpl";
    private static final String ENUM = "com.mstar.android.tvapi.common.vo.TvOsType$EnumInputSource";
    private static final String MANAGER = "com.mstar.android.tvapi.common.TvManager";
    private static final String PLAYER = "com.mstar.android.tvapi.common.TvPlayer";

    /** The input that means "no port": the Android UI on screen, which is where Home lives. */
    static final String STORAGE = "E_INPUT_SOURCE_STORAGE";

    private static boolean probed;
    private static Object desk;
    private static Class<?> enumType;
    private static Method setInputSource;
    private static Method getCurrentInputSource;
    private static Method setDisplay;
    private static Method detectInputSource;

    private MstarSourceSwitch() {
    }

    /** True on a set that exposes the API: the Inputs menu then switches in place instead of leaving Home. */
    static boolean available() {
        probe();
        return setInputSource != null;
    }

    /** The constant name of the input on screen, or null when the set will not say. */
    static String current() {
        probe();
        if (getCurrentInputSource == null) return null;
        try {
            Object value = getCurrentInputSource.invoke(desk);
            return value instanceof Enum ? ((Enum<?>) value).name() : null;
        } catch (Throwable t) {
            // GetCurrentInputSource reaches TvCommonManager, which some builds leave out of the boot image
            // while SetInputSource still works. The menu then simply marks no row as current.
            return null;
        }
    }

    /** @param constant a {@code TvOsType.EnumInputSource} constant, e.g. {@code E_INPUT_SOURCE_HDMI2} */
    static boolean switchTo(String constant) {
        probe();
        if (setInputSource == null) return false;
        Object value = constantValue(constant);
        if (value == null) {
            Log.w(TAG, "no such input: " + constant);
            return false;
        }
        try {
            setInputSource.invoke(desk, value);
            return true;
        } catch (Throwable t) {
            // The TV service can refuse the switch (SecurityException) or the port can be gone (binder death).
            Log.w(TAG, "switch to " + constant + " failed", t);
            return false;
        }
    }

    /**
     * Whether anything is plugged into a port, the way the factory's own source list asks: {@code
     * TvPlayer.detectInputSource}. Null when the set will not say, so a caller can tell "no device" from
     * "cannot ask" and never prints a state it did not get.
     */
    static Boolean connected(String constant) {
        probe();
        if (detectInputSource == null) return null;     // no TvPlayer here at all
        Object value = constantValue(constant);
        if (value == null) return null;
        try {
            Object player = player();
            if (player == null) return null;
            Object answer = detectInputSource.invoke(player, value);
            return answer instanceof Boolean ? (Boolean) answer : null;
        } catch (Throwable t) {
            Log.i(TAG, "detect " + constant + " failed: " + t);
            return null;
        }
    }

    /**
     * Hands the set a surface to draw the input into ({@code CommonDesk.setDisplayHolder}, which is
     * {@code TvPlayer.setDisplay}). Without this the port changes but the picture stays hidden behind the
     * launcher. Only ever called with a live holder: the native side dereferences it, so passing null to
     * detach takes the whole process down with a SIGSEGV (checked on this board) - let the surface die
     * instead, the way the factory source panel does.
     */
    static boolean attach(SurfaceHolder holder) {
        probe();
        if (setDisplay == null || holder == null) return false;
        try {
            Object player = player();
            if (player == null) return false;
            setDisplay.invoke(player, holder);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "setDisplay failed", t);
            return false;
        }
    }

    private static Object player() {
        try {
            Object manager = Class.forName(MANAGER).getMethod("getInstance").invoke(null);
            return manager == null ? null : manager.getClass().getMethod("getPlayerManager").invoke(manager);
        } catch (Throwable t) {
            // The tvapi server is down (it restarts with the set's TV service); nothing to ask this time.
            Log.i(TAG, "no TvPlayer: " + t);
            return null;
        }
    }

    private static Object constantValue(String constant) {
        if (enumType == null || constant == null) return null;
        for (Object value : enumType.getEnumConstants()) {
            if (((Enum<?>) value).name().equals(constant)) return value;
        }
        return null;
    }

    private static synchronized void probe() {
        if (probed) return;
        probed = true;
        try {
            Class<?> deskClass = Class.forName(DESK);
            desk = deskClass.getMethod("getInstance").invoke(null);
            enumType = Class.forName(ENUM);
            setInputSource = deskClass.getMethod("SetInputSource", enumType);
            getCurrentInputSource = deskClass.getMethod("GetCurrentInputSource");
        } catch (Throwable t) {
            Log.i(TAG, "no MStar source API on this set: " + t);
            desk = null;
            enumType = null;
            setInputSource = null;
            getCurrentInputSource = null;
        }
        try {
            // Resolved from the interface, so the port table above and the surface below share one enum.
            Class<?> playerType = Class.forName(PLAYER);
            setDisplay = playerType.getMethod("setDisplay", SurfaceHolder.class);
            if (enumType == null) enumType = Class.forName(ENUM);
            detectInputSource = playerType.getMethod("detectInputSource", enumType);
        } catch (Throwable t) {
            Log.i(TAG, "no MStar player API on this set: " + t);
            setDisplay = null;
            detectInputSource = null;
        }
    }
}
