#!/system/bin/sh
# Installed as /system/etc/kyobo-bridge.sh and run from install-recovery.sh at boot (as root).
# Starts jecrits' bridge and sends ONLY 교보도서관 (kr.co.kyobobook.KEL) traffic through it.
# Other apps (Millie, browser, ...) are not redirected.
D=/data/local/millie_bridge
B=$D/bin/millie-device-bridge
L=$D/kyobo-bridge.log
P=18080
PKG=kr.co.kyobobook.KEL

[ -x $B ] || exit 0
mkdir -p $D
echo "==== start $(date) ====" >> $L

# the CA private key stays root-only
chmod 0700 $D 2>/dev/null
chmod 0600 $D/ca.pem 2>/dev/null

# one bridge process
for Q in $(ps | grep millie-device-bridge | grep -v grep | while read U PID R; do echo $PID; done); do kill $Q 2>/dev/null; done
$B --daemon >> $L 2>&1 &

U=$(grep "^$PKG " /data/system/packages.list 2>/dev/null | while read N UI R; do echo $UI; done)
[ -n "$U" ] || { echo "$PKG not installed, no redirect" >> $L; exit 0; }

rule() { # rule <dport>: Kyobo's outgoing TCP to <dport> -> local bridge
  while iptables -t nat -D OUTPUT -p tcp -m owner --uid-owner $U ! -d 127.0.0.1/32 --dport $1 -j REDIRECT --to-ports $P 2>/dev/null; do :; done
  iptables -t nat -A OUTPUT -p tcp -m owner --uid-owner $U ! -d 127.0.0.1/32 --dport $1 -j REDIRECT --to-ports $P
}
rule 80
rule 443
rule 8000:8999
echo "redirecting uid $U ($PKG) -> 127.0.0.1:$P" >> $L
exit 0
