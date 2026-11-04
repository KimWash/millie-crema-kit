#!/usr/bin/env bash
# usage: kyobo-bridge/extract.sh <crema-soundup-...-system-by-jecrits-*.img>
# Copies jecrits' bridge binary (/system/etc/millie-bridge.*) out of the Crema SoundUp system image.
# The binary is not part of this repository.
set -euo pipefail
IMG=${1:?usage: extract.sh <soundup system .img>}
DIR="$(cd "$(dirname "$0")" && pwd)"
DEBUGFS=$(command -v debugfs || ls /opt/homebrew/opt/e2fsprogs/sbin/debugfs /usr/local/opt/e2fsprogs/sbin/debugfs 2>/dev/null | head -1 || true)
[ -n "$DEBUGFS" ] || { echo "debugfs not found (brew install e2fsprogs)"; exit 1; }
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
n=0
for i in $(seq -w 0 99); do
  "$DEBUGFS" -R "dump /etc/millie-bridge.$i $W/p.$i" "$IMG" 2>/dev/null || true
  [ -s "$W/p.$i" ] || { rm -f "$W/p.$i"; break; }
  n=$((n+1))
done
[ $n -gt 0 ] || { echo "no /etc/millie-bridge.* in $IMG"; exit 1; }
mkdir -p "$DIR/bin"
cat "$W"/p.* > "$DIR/bin/millie-device-bridge"
chmod 755 "$DIR/bin/millie-device-bridge"
echo "extracted $n parts -> kyobo-bridge/bin/millie-device-bridge"
file "$DIR/bin/millie-device-bridge" | sed 's/, BuildID.*//'
shasum -a 256 "$DIR/bin/millie-device-bridge"
