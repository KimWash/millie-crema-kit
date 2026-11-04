#!/usr/bin/env bash
# usage: crema-home/shelf-sync/setup.sh
# Lets crema-home show the 밀리의 서재 bookshelf: installs /system/etc/millie-shelf.sh (a root loop
# that copies Millie's bookshelf database to /sdcard/crema/.millie/ when it changes) and starts it
# at boot from install-recovery.sh. Writes /sdcard/millie-crema-backup/<time>-millie-shelf/restore.sh.
# (교보도서관 needs nothing here: kyobo-compat exports its bookshelf from inside the app.)
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
command -v adb >/dev/null || { echo "adb not found"; exit 1; }
adb shell "su -c id" | grep -q 'uid=0' || { echo "root (su) required"; exit 1; }
adb shell "busybox stat -c %Y /system" >/dev/null 2>&1 || { echo "busybox stat not available on the device"; exit 1; }

TS=$(date +%Y%m%d-%H%M%S)
BK=/sdcard/millie-crema-backup/$TS-millie-shelf
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT

adb push "$DIR/millie-shelf.sh" /data/local/tmp/millie-shelf.sh >/dev/null
cat > "$W/apply.sh" <<EOF
#!/system/bin/sh
export TMPDIR=/data/local/tmp
B=$BK
mkdir -p \$B
mount -o remount,rw /system
cp /system/etc/install-recovery.sh \$B/install-recovery.sh
cat /data/local/tmp/millie-shelf.sh > /system/etc/millie-shelf.sh
chmod 755 /system/etc/millie-shelf.sh; chown 0.0 /system/etc/millie-shelf.sh
grep -q millie-shelf.sh /system/etc/install-recovery.sh || {
  echo '' >> /system/etc/install-recovery.sh
  echo '# crema-home: 밀리 bookshelf copy (millie-crema-kit/crema-home/shelf-sync)' >> /system/etc/install-recovery.sh
  echo '[ -x /system/etc/millie-shelf.sh ] && /system/bin/sh /system/etc/millie-shelf.sh &' >> /system/etc/install-recovery.sh
}
echo '#!/system/bin/sh' > \$B/restore.sh
echo '[ -f /data/local/tmp/millie-shelf.pid ] && kill \$(cat /data/local/tmp/millie-shelf.pid) 2>/dev/null; rm -f /data/local/tmp/millie-shelf.pid' >> \$B/restore.sh
echo 'mount -o remount,rw /system' >> \$B/restore.sh
echo 'cat $BK/install-recovery.sh > /system/etc/install-recovery.sh; chown 0.0 /system/etc/install-recovery.sh; chmod 755 /system/etc/install-recovery.sh' >> \$B/restore.sh
echo 'rm -f /system/etc/millie-shelf.sh' >> \$B/restore.sh
echo 'mount -o remount,ro /system' >> \$B/restore.sh
echo 'rm -rf /sdcard/crema/.millie' >> \$B/restore.sh
echo 'echo millie-shelf removed' >> \$B/restore.sh
sync
mount -o remount,ro /system
rm -f /data/local/tmp/millie-shelf.sh
# (re)start now
/system/bin/sh /system/etc/millie-shelf.sh > /dev/null 2>&1 &
echo "installed /system/etc/millie-shelf.sh; undo: su -c 'sh \$B/restore.sh'"
EOF
adb push "$W/apply.sh" /data/local/tmp/millie-shelf-apply.sh >/dev/null
adb shell "su -c 'TMPDIR=/data/local/tmp sh /data/local/tmp/millie-shelf-apply.sh; rm /data/local/tmp/millie-shelf-apply.sh'" | tr -d '\r'
sleep 3
adb shell "ls -l /sdcard/crema/.millie/" | tr -d '\r'
