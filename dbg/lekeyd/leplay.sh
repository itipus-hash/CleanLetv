#!/system/bin/sh
# Playback guard for the app library overlay.
#
# Showing the library means putting a translucent activity on top of whatever app is in front, and
# Android stops the activity underneath (verified: state=STOPPED, and the audio track goes inactive).
# A video app treats that as "user left" and pauses, and the ones here do not resume by themselves
# when the library closes - so opening the library silently ended the film.
#
# Two steps, both driven by lekeyd (this script runs as root; the app cannot):
#   was     right before the library opens: remember which package was actually playing
#   resume  a second after it closes: if we are back on that same package and nothing is playing
#           any more, send one media-play key
#
# "Was actually playing" is read from the audio mixer, not from the app's MediaSession: several of
# these TV players leave the session sitting at state=3 (playing) while their audio track is gone,
# so the session is stale exactly when it matters. dumpsys media.audio_flinger reports the truth.
#
# The package check matters too: without it, picking a different app out of the library would
# start playback in that app. And the "still silent" check keeps an app that resumed on its own
# from being paused again by the very key meant to help it.

export PATH=/system/bin:/system/xbin:$PATH

STATE=/data/local/tmp/le_playing

# True while some app is really pushing audio out.
audio_active() {
    dumpsys media.audio_flinger 2>/dev/null | grep -q "of which [1-9][0-9]* are active"
}

# The app on top of the audio focus stack - the one that would be making the noise.
focus_top() {
    dumpsys audio 2>/dev/null \
        | sed -n "/Audio Focus stack entries/,/^\$/p" \
        | sed -n "s/.*pack: \([^ ]*\).*/\1/p" \
        | tail -1
}

# The package of the activity on screen.
top_pkg() {
    dumpsys activity activities 2>/dev/null \
        | grep -m1 mResumedActivity \
        | sed "s/.*u0 \([^/]*\)\/.*/\1/"
}

case "$1" in
was)
    pkg=
    audio_active && pkg=$(focus_top)
    if [ -n "$pkg" ]; then
        echo "$pkg" > "$STATE"
    else
        rm -f "$STATE"
    fi
    ;;
resume)
    sleep 1
    [ -f "$STATE" ] || exit 0
    was=$(cat "$STATE")
    rm -f "$STATE"
    [ -n "$was" ] || exit 0
    [ "$(top_pkg)" = "$was" ] || exit 0
    audio_active && exit 0
    input keyevent 126
    ;;
esac
