#!/system/bin/sh
# Remap the LeEco remote's "Le" button so the firmware no longer eats it, and put the share
# button back to taking screenshots.
#
# Stock mapping on both keylayouts:
#   scancode 377 -> keycode LETV (4415)   the share button under the Settings key
#   scancode 189 -> keycode LE   (4431)   the Le button
# services.jar's PhoneWindowManager intercepts both keycodes in interceptKeyBeforeQueueing
# (4415 -> takeScreenshot, 4431 -> com.stv.lekey service + com.letv.tv WelcomeActivity) and
# returns -1 (consumed), so no app ever sees them. Changing the keycode sidesteps that hook.
#
# What this script leaves alone / changes:
#   377 LETV  -> left as LETV. That is the share/screenshot button: the firmware's own
#               takeScreenshot fires and a JPEG lands in /sdcard/Pictures/Screenshots. Do NOT
#               remap this one to F5 again -- doing so is what made the button open the app
#               library and lost the screenshot. (An earlier version of this script rewrote
#               both keys; this one rewrites 377 back to LETV, so it repairs such a box.)
#   189 LE    -> F5 (135). The Le button opens the app library.
#   167 DVR   -> F6 (136). The voice button under the share button. The firmware answers it by
#               starting com.stv.voice/.WindowService (openmic / closemic) - a package this TV does
#               not have - so all it did was beep twice. F6 is inert for the firmware and for apps,
#               and the desktop turns it into "open the app you chose" (voice_key_app). The two
#               beeps stay: the user asked for them to be left alone, and they are the only feedback.
#               F6 is otherwise only bound to scancode 64 (a USB keyboard's F6), so 167 is free.
#   * MENU was tried first and is wrong: any focused app turns MENU into its own options
#     menu, so pressing Le inside another app popped that app's menu instead of the library.
#   * F5 is not special-cased by PhoneWindowManager and virtually no TV app binds it, so the
#     event is harmless wherever it lands.
# The desktop does not depend on the keycode at all: MainActivity.handleLeKey, HomeKeyService
# and the /system/bin/lekeyd daemon all match on the SCANCODE (189), so remapping is safe.
#
# Files touched: the two keylayouts the remote actually produces events on
#   Vendor_3697_Product_0001.kl  LETV Smart IR Receiver      (event0)
#   Vendor_4c65_Product_3d4c.kl  Nanosic BT remote keyboard  (event2)
# Backups: /data/local/tmp/kl-backup/  (first run only, never overwritten)
#
# Keylayouts are parsed once at boot: reboot after running this.
#
# Run it with --mount-master, or /system stays read-only (see 开发文档.md §9.8):
#   su --mount-master -c "mount -o remount,rw /system; sh /data/local/tmp/fixkl.sh"

BK=/data/local/tmp/kl-backup
mkdir -p $BK

for f in Vendor_3697_Product_0001 Vendor_4c65_Product_3d4c; do
    if [ ! -f $BK/$f.kl ]; then
        cp /system/usr/keylayout/$f.kl $BK/$f.kl
    fi
done

for f in Vendor_3697_Product_0001 Vendor_4c65_Product_3d4c; do
    # 377 goes back to LETV whatever it currently says (F5 / LETV / anything), so the
    # screenshot button works again and a second run changes nothing.
    sed -i 's/^key 377[[:space:]].*$/key 377     LETV/' /system/usr/keylayout/$f.kl
    # Matches LETV / LE / MENU / F5 alike, so the script is idempotent and also fixes a
    # machine that still has the earlier MENU remap applied.
    sed -i 's/^key 189[[:space:]].*$/key 189     F5/' /system/usr/keylayout/$f.kl
    # 167 is the voice button. Whatever it says now (DVR on the IR remote, RECORD on the
    # Bluetooth one, or an earlier remap) becomes F6, so a second run changes nothing.
    sed -i 's/^key 167[[:space:]].*$/key 167     F6/' /system/usr/keylayout/$f.kl
    chown root:root /system/usr/keylayout/$f.kl
    chmod 644 /system/usr/keylayout/$f.kl
done

echo "--- result ---"
grep -nE "^key (377|189|167|139|357|187) " /system/usr/keylayout/Vendor_3697_Product_0001.kl /system/usr/keylayout/Vendor_4c65_Product_3d4c.kl
echo "--- perms ---"
ls -la /system/usr/keylayout/Vendor_3697_Product_0001.kl /system/usr/keylayout/Vendor_4c65_Product_3d4c.kl
echo "--- backups ---"
ls -la $BK
