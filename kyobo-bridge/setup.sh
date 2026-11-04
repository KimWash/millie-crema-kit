#!/usr/bin/env bash
# usage: kyobo-bridge/setup.sh
# Installs jecrits' bridge (extracted by extract.sh) for 교보도서관 only, on a rooted CREMA-0640L.
#  - bridge binary  -> /data/local/millie_bridge/bin/millie-device-bridge (root only)
#  - its CA          : generated ON THIS DEVICE by --init-ca; only that certificate is added to the
#                      system trust store (the CA shipped inside the SoundUp image is NOT used)
#  - boot            : /system/etc/kyobo-bridge.sh, started from install-recovery.sh
#  - redirect        : iptables, Kyobo's uid only (Millie and other apps untouched)
# Writes /sdcard/millie-crema-backup/<time>-kyobo-bridge/restore.sh (used by remove.sh).
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
BIN="$DIR/bin/millie-device-bridge"
[ -x "$BIN" ] || { echo "run kyobo-bridge/extract.sh <soundup .img> first"; exit 1; }
command -v adb >/dev/null || { echo "adb not found"; exit 1; }
command -v openssl >/dev/null || { echo "openssl not found"; exit 1; }
adb shell "su -c id" | grep -q 'uid=0' || { echo "root (su) required"; exit 1; }
adb shell "grep -q '^kr.co.kyobobook.KEL ' /data/system/packages.list 2>/dev/null || su -c \"grep -q '^kr.co.kyobobook.KEL ' /data/system/packages.list\"" \
  || echo "note: 교보도서관 is not installed yet; the redirect starts after it is installed and the device reboots"

TS=$(date +%Y%m%d-%H%M%S)
BK=/sdcard/millie-crema-backup/$TS-kyobo-bridge
D=/data/local/millie_bridge
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT

echo "[1/4] binary"
adb push "$BIN" /data/local/tmp/millie-device-bridge >/dev/null
adb push "$DIR/kyobo-bridge.sh" /data/local/tmp/kyobo-bridge.sh >/dev/null
adb shell "su -c 'mkdir -p $D/bin && cp /data/local/tmp/millie-device-bridge $D/bin/ && chown 0.0 $D/bin/millie-device-bridge && chmod 0755 $D/bin/millie-device-bridge && chmod 0700 $D && rm /data/local/tmp/millie-device-bridge'"

echo "[2/4] device CA (generated on the device)"
adb shell "su -c '[ -f $D/ca.pem ] || $D/bin/millie-device-bridge --init-ca'" | tail -2
adb shell "su -c 'chmod 0600 $D/ca.pem'"
adb shell "su -c 'cat $D/ca.pem'" | tr -d '\r' > "$W/ca.pem"
openssl x509 -in "$W/ca.pem" -out "$W/cert.pem" 2>/dev/null || { echo "could not read the generated CA"; exit 1; }
rm -f "$W/ca.pem"   # it may contain the private key; keep only the certificate on this computer
H=$(openssl x509 -in "$W/cert.pem" -noout -subject_hash_old)
CAF=$H.0
# first free <hash>.N (Android's `ls` echoes the name even when missing, so test with [ -e ]);
# reuse the slot if the same certificate is already there (re-run)
FP=$(openssl x509 -in "$W/cert.pem" -noout -fingerprint -sha256 | cut -d= -f2)
i=0
while adb shell "[ -e /system/etc/security/cacerts/$H.$i ] && echo exists" | grep -q exists; do
  adb shell "cat /system/etc/security/cacerts/$H.$i" | tr -d '\r' | openssl x509 -noout -fingerprint -sha256 2>/dev/null | grep -q "$FP" && break
  i=$((i+1)); [ $i -lt 10 ] || { echo "too many $H.* certificates"; exit 1; }
done
CAF=$H.$i
{ openssl x509 -in "$W/cert.pem"; openssl x509 -in "$W/cert.pem" -noout -text; } > "$W/$CAF"
openssl x509 -in "$W/cert.pem" -noout -subject -enddate | sed 's/^/      /'
adb push "$W/$CAF" /data/local/tmp/$CAF >/dev/null

echo "[3/4] system files (backup + restore script: $BK)"
cat > "$W/apply.sh" <<EOF
#!/system/bin/sh
# mksh needs a writable TMPDIR for here-documents (its default does not exist on this ROM)
export TMPDIR=/data/local/tmp
B=$BK
mkdir -p \$B
mount -o remount,rw /system
cp -p /system/etc/install-recovery.sh \$B/install-recovery.sh
cat /data/local/tmp/$CAF > /system/etc/security/cacerts/$CAF
chmod 644 /system/etc/security/cacerts/$CAF; chown 0.0 /system/etc/security/cacerts/$CAF
cat /data/local/tmp/kyobo-bridge.sh > /system/etc/kyobo-bridge.sh
chmod 755 /system/etc/kyobo-bridge.sh; chown 0.0 /system/etc/kyobo-bridge.sh
grep -q kyobo-bridge.sh /system/etc/install-recovery.sh || {
  echo '' >> /system/etc/install-recovery.sh
  echo '# 교보도서관 bridge (millie-crema-kit/kyobo-bridge)' >> /system/etc/install-recovery.sh
  echo '[ -x /system/etc/kyobo-bridge.sh ] && /system/bin/sh /system/etc/kyobo-bridge.sh &' >> /system/etc/install-recovery.sh
}
cat > \$B/restore.sh <<'R'
#!/system/bin/sh
for Q in \$(ps | grep millie-device-bridge | grep -v grep | while read U PID X; do echo \$PID; done); do kill \$Q; done
U=\$(grep '^kr.co.kyobobook.KEL ' /data/system/packages.list | while read N UI X; do echo \$UI; done)
if [ -n "\$U" ]; then for p in 80 443 8000:8999; do
  while iptables -t nat -D OUTPUT -p tcp -m owner --uid-owner \$U ! -d 127.0.0.1/32 --dport \$p -j REDIRECT --to-ports 18080 2>/dev/null; do :; done
done; fi
mount -o remount,rw /system
# cat (not cp -p): the copy on /sdcard has sdcard ownership/mode; the boot hook must stay root 0755
cat $BK/install-recovery.sh > /system/etc/install-recovery.sh
chown 0.0 /system/etc/install-recovery.sh; chmod 755 /system/etc/install-recovery.sh
rm -f /system/etc/security/cacerts/$CAF /system/etc/kyobo-bridge.sh
mount -o remount,ro /system
rm -rf $D
echo "kyobo-bridge removed"
R
sync
mount -o remount,ro /system
rm -f /data/local/tmp/$CAF /data/local/tmp/kyobo-bridge.sh
echo "  installed /system/etc/kyobo-bridge.sh, /system/etc/security/cacerts/$CAF"
EOF
adb push "$W/apply.sh" /data/local/tmp/kyobo-apply.sh >/dev/null
adb shell "su -c 'sh /data/local/tmp/kyobo-apply.sh; rm /data/local/tmp/kyobo-apply.sh'"

echo "[4/4] start"
adb shell "su -c 'sh /system/etc/kyobo-bridge.sh'"
adb shell "am force-stop kr.co.kyobobook.KEL" || true
adb shell "su -c 'tail -n 3 $D/kyobo-bridge.log 2>/dev/null || cat $D/kyobo-bridge.log'" | tr -d '\r' | tail -3
echo "done. Open 교보도서관 again. Undo: kyobo-bridge/remove.sh"
