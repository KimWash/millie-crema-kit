#!/usr/bin/env bash
# usage: kyobo-bridge/remove.sh
# Undoes setup.sh with the newest restore script it left on the device.
set -euo pipefail
R=$(adb shell "ls -d /sdcard/millie-crema-backup/*-kyobo-bridge 2>/dev/null" | tr -d '\r' | sort | tail -1)
[ -n "$R" ] || { echo "no kyobo-bridge backup found on the device"; exit 1; }
echo "using $R/restore.sh"
adb shell "su -c 'sh $R/restore.sh'"
adb shell "am force-stop kr.co.kyobobook.KEL" || true
