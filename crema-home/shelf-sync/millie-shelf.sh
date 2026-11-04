#!/system/bin/sh
# Installed as /system/etc/millie-shelf.sh, started at boot (as root) from install-recovery.sh.
# 밀리의 서재 keeps its bookshelf in its private databases/books.db (title, author, cover URL,
# progress, expiry). The app can't be modified (integrity check), so this copies that one
# database, when it changes, to /sdcard/crema/.millie/ where crema-home can read it.
# Only books.db is copied (never user.db, cookies or account data). Book files stay in Millie.
SRC=/data/data/kr.co.millie.eink/databases
OUT=/sdcard/crema/.millie
INTERVAL=20
PID=/data/local/tmp/millie-shelf.pid

# keep running when started from an adb/su session that then ends
trap '' HUP

# one instance (Android's ps shows "sh", not the script name, so use a pid file)
[ -f $PID ] && kill $(cat $PID) 2>/dev/null
echo $$ > $PID

# external storage is mounted a while after boot
n=0
while [ ! -d /sdcard/Android ] && [ $n -lt 60 ]; do sleep 5; n=$((n+1)); done

last=""
while true; do
  if [ -f $SRC/books.db ]; then
    cur="$(busybox stat -c %Y $SRC/books.db $SRC/books.db-wal 2>/dev/null)"
    if [ "$cur" != "$last" ]; then
      mkdir -p $OUT
      # copy db + wal into a temp dir, then swap, so a reader never sees half a pair
      rm -rf $OUT/.tmp; mkdir -p $OUT/.tmp
      cat $SRC/books.db > $OUT/.tmp/books.db 2>/dev/null
      [ -f $SRC/books.db-wal ] && cat $SRC/books.db-wal > $OUT/.tmp/books.db-wal 2>/dev/null
      rm -f $OUT/books.db $OUT/books.db-wal
      mv $OUT/.tmp/books.db $OUT/books.db
      [ -f $OUT/.tmp/books.db-wal ] && mv $OUT/.tmp/books.db-wal $OUT/books.db-wal
      rm -rf $OUT/.tmp
      date +%s > $OUT/stamp
      last="$cur"
    fi
  fi
  sleep $INTERVAL
done
