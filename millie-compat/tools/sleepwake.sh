#!/bin/zsh
# usage: sleepwake.sh N  — force-stop Millie, screen off until Wi-Fi drops, wake, launch immediately
S=$1; N=$2
adb shell "su -c 'svc power stayon false'"
adb shell am force-stop kr.co.millie.eink
adb shell dumpsys power | tr -d '\r' | grep -q "mScreenOn=true" && adb shell input keyevent 26
t=$(date +%s); until ! adb shell dumpsys connectivity | grep -q "CONNECTED/CONNECTED"; do [ $(( $(date +%s)-t )) -gt 40 ] && break; done
adb shell sleep 5
adb logcat -c
adb shell input keyevent 26
adb shell am start -n kr.co.millie.crema.launcher/.Go >/dev/null; (sleep 6; adb shell screencap -p /sdcard/w.png; adb pull /sdcard/w.png $S/wait_$N.png >/dev/null 2>&1) &
s=$(date +%s); r=none
while [ $(( $(date +%s)-s )) -lt 600 ]; do
  f=$(adb shell "dumpsys activity activities" | tr -d '\r' | grep -m1 mFocusedActivity)
  case "$f" in *Bookshelf*) r=bookshelf; break;; esac
done
adb logcat -d -v time > $S/sw_$N.log
echo "run $N: result=$r after $(( $(date +%s)-s ))s"
grep -E 'aveeno' $S/sw_$N.log | sed -E 's/[A-Za-z0-9+\/=_-]{20,}/<redacted>/g; s/userId=[^,]*/userId=<id>/' | grep -v -E 'KeyStore|LoginActivity|^\s*$' | grep -i -E 'signin|observe|error|exception|fail|timeout|unknownhost|connect' | cut -c1-200 | head -8
grep -E 'ConnectivityService.*(CONNECTED|DISCONNECTED)|WifiStateMachine.*(Connected|Disconnected)' $S/sw_$N.log | cut -c1-150 | head -4
