#!/bin/bash
#
# AutoTube clean reinstall for macOS / Linux.
#
#   1. Put this script and AutoTube.apk in your platform-tools folder
#      (the folder that contains adb).
#   2. Plug in the phone with USB debugging on.
#   3. Run:  bash install.sh
#
# Optional: bash install.sh /path/to/AutoTube.apk
#
# Android Auto only shows apps that the phone recorded as installed from the
# Play Store. This script installs AutoTube through the phone's own installer
# with that record set, so Android Auto accepts it.

set -u

PKG="com.vk.autotube"
HERE="$(cd "$(dirname "$0")" && pwd)"
APK="${1:-$HERE/AutoTube.apk}"
REMOTE="/sdcard/Download/AutoTube.apk"

bold()  { printf "\n\033[1m%s\033[0m\n" "$1"; }
ok()    { printf "  \033[32m✓\033[0m %s\n" "$1"; }
warn()  { printf "  \033[33m!\033[0m %s\n" "$1"; }
fail()  { printf "\n\033[31mStopped:\033[0m %s\n\n" "$1"; exit 1; }

# ---- find adb ---------------------------------------------------------------
if [ -x "$HERE/adb" ]; then
  ADB="$HERE/adb"
elif [ -x "$HERE/platform-tools/adb" ]; then
  ADB="$HERE/platform-tools/adb"
elif command -v adb >/dev/null 2>&1; then
  ADB="$(command -v adb)"
elif [ -x "$HOME/Downloads/platform-tools/adb" ]; then
  ADB="$HOME/Downloads/platform-tools/adb"
else
  fail "Can't find adb. Put this script inside your platform-tools folder."
fi

[ -f "$APK" ] || fail "Can't find $APK. Put AutoTube.apk next to this script."

is_installed() {
  "$ADB" shell pm list packages "$PKG" 2>/dev/null | tr -d '\r' | grep -qx "package:$PKG"
}

# ---- 1. phone ---------------------------------------------------------------
bold "1/7  Checking the phone"
"$ADB" start-server >/dev/null 2>&1
STATE="$("$ADB" get-state 2>/dev/null | tr -d '\r')"
if [ "$STATE" != "device" ]; then
  "$ADB" devices
  fail "Phone not ready. Plug it in, unlock it, and accept \"Allow USB debugging\"."
fi
ok "Connected to $("$ADB" shell getprop ro.product.model | tr -d '\r')"

# ---- 2. remove old version ----------------------------------------------------
bold "2/7  Removing the old AutoTube"
if is_installed; then
  if "$ADB" uninstall "$PKG" >/dev/null 2>&1; then ok "Removed"; else warn "Couldn't remove it, continuing"; fi
else
  ok "Nothing to remove"
fi

# ---- 3. copy apk --------------------------------------------------------------
bold "3/7  Copying the new APK to the phone"
"$ADB" shell rm -f "$REMOTE" >/dev/null 2>&1
"$ADB" push "$APK" "$REMOTE" >/dev/null 2>&1 || fail "Copy failed."
ok "Copied to Downloads"

# ---- 4. find its id -----------------------------------------------------------
bold "4/7  Finding the file on the phone"
ID=""
for _ in $(seq 1 20); do
  ID="$("$ADB" shell "content query --uri content://media/external/file --projection _id --where \"_display_name='AutoTube.apk'\"" 2>/dev/null \
        | tr -d '\r' | sed -n 's/.*_id=\([0-9][0-9]*\).*/\1/p' | sort -n | tail -1)"
  [ -n "$ID" ] && break
  sleep 1
done
[ -n "$ID" ] || fail "The phone didn't list the APK yet. Run the script again."
ok "Found (id $ID)"

# ---- 5. install ---------------------------------------------------------------
bold "5/7  Installing. Look at your phone."
OUT="$("$ADB" shell am start -a android.intent.action.INSTALL_PACKAGE \
  -t application/vnd.android.package-archive \
  -d "content://media/external/file/$ID" \
  --grant-read-uri-permission \
  --es android.intent.extra.INSTALLER_PACKAGE_NAME com.android.vending \
  --ez android.intent.extra.NOT_UNKNOWN_SOURCE true 2>&1 | tr -d '\r')"
if echo "$OUT" | grep -qi "error"; then
  echo "$OUT"
  fail "The phone's installer didn't open."
fi
echo "  Tap Install on the phone."
echo "  If Play Protect warns you, tap More details, then Install anyway."
for _ in $(seq 1 120); do
  is_installed && break
  sleep 1
done
is_installed || fail "AutoTube wasn't installed (waited 2 minutes)."
ok "Installed"

# ---- 6. check install record ----------------------------------------------------
bold "6/7  Checking Android Auto will accept it"
INFO="$("$ADB" shell dumpsys package "$PKG" 2>/dev/null | tr -d '\r')"
INSTALLER="$(echo "$INFO" | sed -n 's/.*installerPackageName=\([^ ]*\).*/\1/p' | head -1)"
INITIATOR="$(echo "$INFO" | sed -n 's/.*initiatingPackageName=\([^ ]*\).*/\1/p' | head -1)"
if [ "$INSTALLER" = "com.android.vending" ] && [ "$INITIATOR" != "com.android.shell" ]; then
  ok "Recorded as a Play Store install"
else
  warn "Recorded installer: ${INSTALLER:-unknown}, started by ${INITIATOR:-unknown}."
  warn "Android Auto may hide AutoTube. Send this output to Claude."
fi

# ---- 7. permissions and restart -------------------------------------------------
bold "7/7  Permissions and restart"
if "$ADB" shell pm grant "$PKG" android.permission.ACCESS_FINE_LOCATION >/dev/null 2>&1; then
  ok "Location allowed (used for the driving lock)"
else
  warn "Open AutoTube on the phone and tap Allow"
fi
"$ADB" shell pm grant "$PKG" android.permission.ACCESS_COARSE_LOCATION >/dev/null 2>&1
"$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1 && ok "Opened AutoTube once on the phone"
"$ADB" shell am force-stop com.google.android.projection.gearhead >/dev/null 2>&1 && ok "Restarted Android Auto"
"$ADB" shell rm -f "$REMOTE" >/dev/null 2>&1

bold "Done"
echo "  Unplug, connect to the car, and open AutoTube from the car's app list."
echo "  Not there? On the phone: Android Auto settings > Customize launcher > tick AutoTube."
echo
