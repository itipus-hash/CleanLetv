#!/system/bin/sh
# Keeps /system/bin/lekeyd alive.
#
# lekeyd is a plain read loop over /dev/input (see dbg/lekeyd/lekeyd.c). It should never exit,
# but the Le button would fail silently if it did, so the loop restarts it.
#
# The environment is set explicitly because this is started from install-recovery.sh, whose
# environment is whatever init happened to export. `am` (which lekeyd shells out to for the
# toggle) needs ANDROID_ROOT and ANDROID_DATA to find the framework.
#
# Two copies of this wrapper appear per boot: /init.rc runs install-recovery.sh from two
# services (flash_recovery and installLetvMisc --misc). lekeyd itself refuses to run twice and
# exits with 2, which is what the slower loop below is for - it keeps watch, and takes over if
# the real instance ever dies.
export ANDROID_ROOT=/system
export ANDROID_DATA=/data
export PATH=/sbin:/system/sbin:/system/bin:/system/xbin
while true; do
    /system/bin/lekeyd
    if [ $? -eq 2 ]; then
        sleep 30
    else
        sleep 1
    fi
done
