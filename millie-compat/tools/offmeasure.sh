#!/bin/zsh
# usage: offmeasure.sh S label "<vmopts>" — cold start (offline), open 사랑의 기술 from 다운로드 tab; load time + GC in 60 s after tap
S=$1; L=$2; O=$3
adb shell "echo '$O' > /data/local/tmp/millie_vmopts"
adb shell am force-stop kr.co.millie.eink; adb logcat -c
adb shell am start -n kr.co.millie.crema.launcher/.Go >/dev/null
s=$(date +%s); until adb shell dumpsys activity activities | grep mFocusedActivity | grep -q Bookshelf; do [ $(( $(date +%s)-s )) -gt 80 ] && { echo "[$L] no bookshelf"; exit 1; }; done
adb shell sleep 5; adb shell input tap 72 89; adb shell sleep 4
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
XY=$(adb shell cat /sdcard/ui.xml | tr '<' '\n' | grep 'text="사랑의 기술"' | grep -o 'bounds="[^"]*"' | head -1 | sed -E 's/.*\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\].*/\1 \2 \3 \4/' | awk '{print int(($1+$3)/2), $2-150}')
adb logcat -c; T0=$(date +%s); adb shell input tap $XY
until adb logcat -d | grep -q "emitGetLocation"; do [ $(( $(date +%s)-T0 )) -gt 120 ] && break; done
T=$(( $(date +%s)-T0 ))
while [ $(( $(date +%s)-T0 )) -lt 60 ]; do :; done
adb logcat -d -v time > $S/off_$L.log
P=$(adb shell ps | tr -d '\r' | awk '/kr.co.millie.eink$/{print $2}')
G=$(grep -E "\( *$P\).*GC_" $S/off_$L.log | awk '{for(i=1;i<=NF;i++) if($i=="total"){gsub("ms","",$(i+1)); s+=$(i+1); n++}} END{print n" GCs "s"ms"}')
F=$(grep -cE "\( *$P\).*GC_FOR_ALLOC" $S/off_$L.log)
H=$(grep -E "\( *$P\).*GC_" $S/off_$L.log | tail -1 | grep -o '[0-9]*K/[0-9]*K')
M=$(adb shell dumpsys meminfo $P | tr -d '\r' | awk '/TOTAL/{print $2; exit}')
echo "[$L] load=${T}s  60s-window: $G (FOR_ALLOC=$F) heap=$H PSS=${M}kB"
