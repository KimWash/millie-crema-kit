#!/usr/bin/env bash
# usage: millie-compat/build_jar.sh <stock.jar> <patches/xxx.diff> <out.jar>
# baksmali the stock jar's classes.dex, apply the smali diff, re-assemble, and write a copy of the jar with
# the new classes.dex (all other entries untouched). The stock jar must be dex-bearing (de-odexed).
set -euo pipefail
source "$(dirname "$0")/../scripts/common.sh"
IN=$1; DIFF=$2; OUT=$3
need java "JDK 8+"; need patch "patch"; need zip "zip"; ensure_smali
unzip -l "$IN" | grep -q ' classes.dex$' || die "$IN has no classes.dex (odexed firmware?) - see millie-compat/README.md (De-odex)"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
java -jar "$BAKSMALI" d -a 17 -o "$W/a" "$IN"
( cd "$W" && patch -s -p1 -d a < "$DIFF" ) || die "patch did not apply cleanly (different firmware build?)"
find "$W/a" -name '*.orig' -delete
java -Xmx2g -jar "$SMALI" a -a 17 -o "$W/classes.dex" "$W/a"
cp "$IN" "$W/out.jar"; ( cd "$W" && zip -q out.jar classes.dex )
cp "$W/out.jar" "$OUT"
echo "built $OUT ($(wc -c < "$OUT") bytes)"
