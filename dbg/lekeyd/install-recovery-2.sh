#!/system/bin/sh
# Local boot hooks for the ClearTV desktop.
#
# /system/etc/install-recovery.sh (started by init as `service flash_recovery`, class main,
# oneshot) calls this file as its last line, so everything here runs once per boot, as root.
#
# lekeyd: makes the LeEco remote's Le button (乐视遥控器 Le 键) a global shortcut for the app
# library, including while another app is in front. It has to be a plain process: the firmware
# force-stops every package that holds an accessibility service within about half a second of
# another app reaching the foreground, so no app can listen for keys globally on this TV.
#
# The daemon shells out to `am start`, so wait until the framework is up before starting it.
(
    while [ "$(getprop sys.boot_completed)" != "1" ]; do
        sleep 1
    done
    sleep 3
    exec /system/bin/sh /system/bin/lekeyd.sh
) </dev/null >/dev/null 2>&1 &
