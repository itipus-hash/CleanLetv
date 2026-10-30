package com.gathin.tvhome;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.UserManager;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Calls that only exist from API 24 or 26, wrapped so the launcher also runs on older TVs.
 *
 * LeTV X4-65S (DemeterUHD) runs Android 6.0 (SDK 23). Every method here is an enhancement, never
 * something the home screen needs to draw: the network callback refreshes the Wi-Fi pill, the font
 * loader picks a nicer typeface, the input-source labels name the HDMI ports, and the changed-package
 * check detects a newly installed app. On API 23 they are skipped, and the launcher falls back to the
 * platform defaults.
 */
final class Compat {

    private Compat() {
    }

    // ---- API 24: ConnectivityManager.registerDefaultNetworkCallback -------------------------------

    /**
     * Registers the default-network callback. Returns false only when nothing could be registered,
     * so the caller knows the pill will not refresh on network changes.
     *
     * API 24 has the default-network callback itself. API 21-23 (the LeTV X4-65S runs Android 6.0)
     * has no such call, but it does have {@link ConnectivityManager#registerNetworkCallback} with a
     * request, so every network is listened to instead: onAvailable then means one came up and
     * onLost one went down. Without this the pill on those sets waits for the next resume - a Home
     * keypress that restarts the launcher - to catch up (reported on the X4-65S).
     */
    static boolean registerDefaultNetworkCallback(ConnectivityManager cm, ConnectivityManager.NetworkCallback callback) {
        if (cm == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            cm.registerDefaultNetworkCallback(callback);
            return true;
        }
        try {
            // INTERNET keeps the request in step with what the pill counts as online; the transports
            // cover everything a TV can be connected through (VPN has its own callback).
            NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .addTransportType(NetworkCapabilities.TRANSPORT_BLUETOOTH)
                    .build();
            cm.registerNetworkCallback(request, callback);
        } catch (Exception e) {
            return false;
        }
        return true;
    }

    /**
     * Registers a callback for VPN transports. The overload used here is API 24, so on API 23 the VPN
     * badge stays dark.
     */
    static void registerVpnCallback(ConnectivityManager cm, ConnectivityManager.NetworkCallback callback) {
        if (cm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return;
        }
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build();
        cm.registerNetworkCallback(request, callback);
    }

    /**
     * Unregisters a network callback, tolerating one that was never registered.
     *
     * <p>{@link ConnectivityManager#unregisterNetworkCallback} throws {@code IllegalArgumentException:
     * Invalid NetworkCallback} for a callback the manager does not know. The VPN callback is never
     * registered below API 24, so on the LeTV X4-65S (Android 6.0) onDestroy threw there, and the
     * activity crashed with "Unable to destroy activity" every time it was recreated (theme change,
     * column change) instead of shutting down cleanly.
     */
    static void unregisterNetworkCallback(ConnectivityManager cm, ConnectivityManager.NetworkCallback callback) {
        if (cm == null) {
            return;
        }
        try {
            cm.unregisterNetworkCallback(callback);
        } catch (Throwable ignored) {
            // Never registered (API 23 has no VPN callback), or already gone with the service.
        }
    }

    // ---- API 24: UserManager.isUserUnlocked --------------------------------------------------------

    /**
     * True when the user is unlocked. Before API 24 there is no direct-boot state, so a locked user
     * cannot be told apart from an unlocked one: the caller is told "unlocked" and reads the config
     * right away.
     */
    static boolean isUserUnlocked(UserManager users) {
        if (users == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return true;
        }
        return users.isUserUnlocked();
    }

    // ---- API 24: Configuration.getLocales -------------------------------------------------------------

    /** The first locale's language code, on any API level. */
    static String primaryLanguage(Context context) {
        Configuration cfg = context.getResources().getConfiguration();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return cfg.getLocales().get(0).getLanguage();
        }
        Locale locale = cfg.locale;
        return locale == null ? "" : locale.getLanguage();
    }

    // ---- API 24: TvInputInfo.isHidden / loadCustomLabel ----------------------------------------------

    /**
     * True when the input is hidden in the TV's own Settings > Inputs. The method is API 24, so on
     * API 23 hidden inputs are listed in the menu as well.
     *
     * TvInputInfo itself only exists from API 21, but the two methods are API 24, so they are called
     * reflectively: the compile-time stub for API 23 does not carry them.
     */
    static boolean isInputHidden(Object info, Context context) {
        if (info == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false;
        }
        try {
            Method m = info.getClass().getMethod("isHidden", Context.class);
            return (Boolean) m.invoke(info, context);
        } catch (Throwable t) {
            return false;
        }
    }

    /** The name the user gave the input in Settings > Inputs, or null when there is none. */
    static CharSequence loadCustomLabel(Object info, Context context) {
        if (info == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return null;
        }
        try {
            Method m = info.getClass().getMethod("loadCustomLabel", Context.class);
            return (CharSequence) m.invoke(info, context);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- API 26: Resources.getFont ---------------------------------------------------------------------

    /**
     * Loads a font from res/font, or null when the API is missing. The caller keeps the platform
     * typeface, which is what the launcher already used before the custom font was added.
     */
    static Typeface loadFont(Resources res, int id) {
        if (res == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return null;
        }
        return res.getFont(id);
    }

    // ---- API 26: PackageManager.getChangedPackages -----------------------------------------------------

    /**
     * The names of the packages that changed since the given sequence number, or an empty list when
     * the API is missing. The caller then simply rebuilds the tiles, which is the safe fallback.
     *
     * PackageManager.ChangedPackages is API 26 and its class is absent from the API 23 stub, so both
     * the call and the result are reached reflectively.
     */
    static List<String> changedPackageNames(PackageManager pm, int since) {
        if (pm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return new ArrayList<>();
        }
        try {
            Method m = PackageManager.class.getMethod("getChangedPackages", int.class);
            Object changed = m.invoke(pm, since);
            if (changed == null) {
                return new ArrayList<>();
            }
            Method names = changed.getClass().getMethod("getPackageNames");
            Object list = names.invoke(changed);
            List<String> out = new ArrayList<>();
            if (list instanceof List) {
                for (Object o : (List<?>) list) {
                    if (o != null) {
                        out.add(o.toString());
                    }
                }
            }
            return out;
        } catch (Throwable t) {
            return new ArrayList<>();
        }
    }

    /** The current changed-package sequence number, or the value passed in when the API is missing. */
    static int packageSequence(PackageManager pm, int fallback) {
        if (pm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return fallback;
        }
        try {
            Method m = PackageManager.class.getMethod("getChangedPackages", int.class);
            Object changed = m.invoke(pm, 0);
            if (changed == null) {
                return fallback;
            }
            Method seq = changed.getClass().getMethod("getSequenceNumber");
            return (Integer) seq.invoke(changed);
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** The active network, or null when there is none. API 23 and up. */
    static Network activeNetwork(ConnectivityManager cm) {
        if (cm == null) {
            return null;
        }
        return cm.getActiveNetwork();
    }

    // ---- TvInputManager without a TV input service ------------------------------------------------------

    /**
     * The TV's input list, or an empty list when the firmware has no TV input service.
     *
     * TvInputManager.getTvInputList() calls into ITvInputManager, which is null on sets whose firmware
     * never registers the service (LeTV X4-65S: a TV without HDMI-in, AV or a tuner). The call then
     * throws a NullPointerException from inside the framework, so it is caught here and the caller gets
     * an empty list, which is what it does with a TV that has no inputs anyway.
     */
    static List<TvInputInfo> tvInputList(TvInputManager tim) {
        List<TvInputInfo> out = new ArrayList<>();
        if (tim == null) {
            return out;
        }
        try {
            List<TvInputInfo> list = tim.getTvInputList();
            if (list != null) {
                out.addAll(list);
            }
        } catch (Throwable ignored) {
            // No TV input service on this firmware.
        }
        return out;
    }

    /**
     * Registers the input callback. Returns false when the firmware has no TV input service, so the
     * caller skips the matching unregister later.
     */
    static boolean registerInputCallback(TvInputManager tim, TvInputManager.TvInputCallback callback, Handler handler) {
        if (tim == null) {
            return false;
        }
        try {
            tim.registerCallback(callback, handler);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Unregisters the input callback, ignoring a firmware without a TV input service. */
    static void unregisterInputCallback(TvInputManager tim, TvInputManager.TvInputCallback callback) {
        if (tim == null) {
            return;
        }
        try {
            tim.unregisterCallback(callback);
        } catch (Throwable ignored) {
            // Never registered, or the service went away.
        }
    }

    /**
     * The connection state of an input, or -1 when the firmware has no TV input service. -1 is the
     * "unknown" state the caller already uses for a port it could not read.
     */
    static int inputState(TvInputManager tim, String inputId) {
        if (tim == null || inputId == null) {
            return -1;
        }
        try {
            return tim.getInputState(inputId);
        } catch (Throwable ignored) {
            return -1;
        }
    }
}
