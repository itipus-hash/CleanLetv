# Claim the remote's Settings and Source buttons for the CleanYourTV launcher on a LeTV X4-65S.
# The Windows counterpart of tools/letv-buttons.sh - same steps, same checks.
#
#   Settings (KEYCODE_SETTINGS / scancode 357): LeTV's com.stv.globalsetting service keeps a TYPE_PHONE
#   window (ty=2008) over the whole screen for its own settings drawer, and that layer always sits above
#   TYPE_APPLICATION, so the launcher's LauncherSettingsActivity starts and is never seen. Disabling the
#   service removes the window; the TV's own Settings activities are untouched.
#   Source (scancode 187): the theme's source_panel_service calls com.stv.signalsourcemanager directly,
#   so this script only makes sure that app is enabled.
#
# Disabling a system component needs root. Undo: su -c 'pm enable <component>'.
#
# Usage: $env:TV_HOST='192.168.1.50'; .\tools\letv-buttons.ps1
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
if (-not $env:TV_HOST) { throw 'Set TV_HOST first: $env:TV_HOST="192.168.1.50"' }
$Py = Join-Path $PSScriptRoot 'venv\Scripts\python.exe'
if (-not (Test-Path $Py)) { $Py = Join-Path $PSScriptRoot 'venv\bin\python' }
if (-not (Test-Path $Py)) { throw 'run: python -m venv tools/venv; tools\venv\Scripts\pip install -r tools\requirements.txt' }
$Tv = Join-Path $PSScriptRoot 'tv.py'

$Service = 'com.stv.globalsetting/.service.GlobalSettingService'
$Source = 'com.stv.signalsourcemanager'

function Tv([string]$Command) { & $Py -I $Tv $Command }

# A firmware update can remove the app, and disabling the service once already takes it out of
# `dumpsys package`'s resolver table - so the check is for the APK on disk, which survives both.
if (-not ((Tv 'pm path com.stv.globalsetting') -match '^package:')) {
    throw 'com.stv.globalsetting is not installed on this firmware. Press Settings and run: tools\tv.py "dumpsys window windows"'
}

# pm refuses to change a system component's state for the shell (uid 2000), so both go through su.
Tv "su -c 'pm disable $Service'" | Out-Null
Tv "su -c 'pm enable $Source'" | Out-Null

# Android 6.0 has no `pm list packages -d` and this firmware prints no disabledComponents line either.
# The check that does work is the one the owner cares about: press Settings, see which window comes up.
Tv 'am start -n com.gathin.tvhome/.MainActivity; sleep 3; input keyevent 176; sleep 2' | Out-Null
$focus = (Tv 'dumpsys window windows' | Select-String -Pattern 'mCurrentFocus=(.+)$').Matches.Groups[1].Value
Tv 'input keyevent 4' | Out-Null

if ($focus -match 'com\.stv\.globalsetting') {
    throw "The LeTV drawer is still on top (focus: $focus). Another component may own that window; find it with: tools\tv.py 'dumpsys window windows'"
}
if ($focus -notmatch 'LauncherSettingsActivity') {
    throw "Settings went to $focus, which is neither LeTV's drawer nor our settings screen. Check settings_key_panel and settings_key_keycodes in the theme's config.json"
}
Write-Host "OK: Settings opens the launcher's own settings screen (LauncherSettingsActivity)."
Write-Host "Undo: su -c 'pm enable $Service'"