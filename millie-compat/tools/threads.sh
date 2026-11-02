#!/bin/zsh
# usage: threads.sh SECONDS — per-thread CPU ticks + GC count of Millie over the window
W=${1:-10}
P=$(adb shell ps | tr -d '\r' | awk '/kr.co.millie.eink$/{print $2}')
A=$(adb shell "cat /proc/$P/task/*/stat" | tr -d '\r'); adb logcat -c
t=$(( $(date +%s)+W )); while [ $(date +%s) -lt $t ]; do :; done
B=$(adb shell "cat /proc/$P/task/*/stat" | tr -d '\r')
echo "GCs/${W}s: $(adb logcat -d | grep -c "( *$P).*GC_")"
python3 -I - "$A" "$B" <<'PY'
import sys,re
def load(s):
    d={}
    for l in s.splitlines():
        m=re.match(r'(\d+) \((.*)\) (.*)',l)
        if m:
            f=m.group(3).split(); d[m.group(1)]=(m.group(2), int(f[11])+int(f[12]))
    return d
a=load(sys.argv[1]); b=load(sys.argv[2])
print(sorted(((b[t][1]-a.get(t,('',0))[1], b[t][0]) for t in b), reverse=True)[:4])
PY
