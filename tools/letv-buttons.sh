#!/usr/bin/env bash
# Claim the remote's Settings and Source buttons for the CleanYourTV launcher on a LeTV X4-65S.
#
# Two separate problems, two separate fixes:
#
#   Settings (KEYCODE_SETTINGS / scancode 357). The launcher opens its own LauncherSettingsActivity, but LeTV's
#   com.stv.globalsetting service keeps a TYPE_PHONE window (ty=2008) over the whole screen for its own
#   settings drawer, and that layer always sits above TYPE_APPLICATION: our screen starts and is simply not
#   visible. Disabling .service.GlobalSettingService removes the window. Its activities (SettingActivity,
#   NetworkActivity, AboutActivity) are left alone, so the TV's own Settings app still works normally.
#
#   Source (scancode 187, `key 187 VIRTUAL_NUMBER`). LeTV routes KEYCODE_F17 to its own signal-source app
#   before any launcher sees it, so the launcher's source button never fires. The theme's
#   source_panel_service calls com.stv.signalsourcemanager directly instead; this script only makes sure
#   that app is enabled.
#
# Disabling a system component needs root, so this expects an already-rooted TV (`su` from adb shell, which
# is what SuperSU in the dock provides). Undo with tools/restore.sh or: su -c 'pm enable <component>'.
#
# Usage: TV_HOST=192.168.1.50 tools/letv-buttons.sh
set -euo pipefail
cd "$(dirname "$0")/.."
PY=tools/venv/bin/python
[ -x "$PY" ] || PY=tools/venv/Scripts/python.exe
export MSYS_NO_PATHCONV=1
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt"; exit 1; }

GLOBALSETTING_SERVICE=com.stv.globalsetting/.service.GlobalSettingService
SIGNAL_SOURCE=com.stv.signalsourcemanager

# A firmware update can remove the app, and disabling the service once already takes it out of
# `dumpsys package`'s resolver table - so the check is for the APK on disk, which survives both, and the
# run is idempotent.
PRESENT=$("$PY" -I tools/tv.py "pm path com.stv.globalsetting" </dev/null | tr -d '\r' | grep -c '^package:' || true)
if [ "${PRESENT:-0}" -eq 0 ]; then
  echo "com.stv.globalsetting is not installed on this firmware." >&2
  echo "Check with: tools/tv.py 'dumpsys window windows' after pressing Settings - whichever window comes" >&2
  echo "up is the one to deal with." >&2
  exit 1
fi

# pm disable on a system component is refused for the shell (uid 2000), so both go through su. pm enable on
# a system package the shell does not own is refused the same way, so it does too even when it is a no-op.
"$PY" -I tools/tv.py "su -c 'pm disable $GLOBALSETTING_SERVICE'" </dev/null
"$PY" -I tools/tv.py "su -c 'pm enable $SIGNAL_SOURCE'" </dev/null

# Android 6.0's `pm` has no `list packages -d` (that flag arrived in 7.1 and prints nothing here, which
# reads like "still enabled"), and this firmware's `dumpsys package` prints no disabledComponents line
# either. The check that does work is the one the user cares about: press Settings and see which window
# comes up. KEYCODE_SETTINGS through `input` is what the framework hands the service, so it reproduces it.
"$PY" -I tools/tv.py "input keyevent 176; sleep 2" </dev/null >/dev/null
FOCUS=$("$PY" -I tools/tv.py "dumpsys window windows" </dev/null | tr -d '\r' \
  | sed -n 's/.*mCurrentFocus=Window{[^}]* \([^ }]*\).*/\1/p' | head -n 1 || true)
case "$FOCUS" in
  *com.stv.globalsetting*)
    echo "The LeTV drawer is still on top after disabling the service (focus: $FOCUS)." >&2
    echo "On this firmware another component may own that window. Find it with:" >&2
    echo "  tools/tv.py 'dumpsys window windows' | grep -B2 globalsetting" >&2
    exit 1 ;;
  *LauncherSettingsActivity*)
    echo "OK: Settings opens the launcher's own settings screen (LauncherSettingsActivity)." ;;
  *)
    echo "Settings went to $FOCUS, which is neither LeTV's drawer nor our settings screen - check" >&2
    echo "settings_key_panel and settings_key_keycodes in the theme's config.json." >&2
    exit 1 ;;
esac
"$PY" -I tools/tv.py "input keyevent 4" </dev/null >/dev/null   # close the settings screen again

echo "Done. Press the remote's Settings button: the launcher's own settings screen should come up"
echo "(its last rows include the TV settings entry). Undo: su -c 'pm enable $GLOBALSETTING_SERVICE'"