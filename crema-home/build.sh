#!/usr/bin/env bash
# usage: crema-home/build.sh
# Builds crema-home/out/crema-home.apk (plain SDK: aapt2 + javac + d8, no Gradle), API 17.
set -euo pipefail
source "$(dirname "$0")/../scripts/common.sh"
BT=$(build_tools); AJ=$(android_jar); need javac "JDK 8+"; need keytool "JDK"
H="$ROOT/crema-home"; W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
mkdir -p "$W/cls" "$W/dex" "$H/out"
# -0 otf: fonts stay stored (uncompressed) so they are mapped instead of inflated into RAM
"$BT/aapt2" link -o "$W/base.apk" --manifest "$H/AndroidManifest.xml" -I "$AJ" -A "$H/assets" -0 otf -0 png \
  --min-sdk-version 17 --target-sdk-version 17
javac -nowarn -source 8 -target 8 -encoding UTF-8 -bootclasspath "$AJ" -d "$W/cls" $(find "$H/src" -name '*.java') \
  2>&1 | grep -v -E '^Note:|warning' || true
"$BT/d8" --release --lib "$AJ" --min-api 17 --output "$W/dex" $(find "$W/cls" -name '*.class')
cp "$W/base.apk" "$W/u.apk"; ( cd "$W/dex" && zip -q ../u.apk classes.dex )
"$BT/zipalign" -f 4 "$W/u.apk" "$W/a.apk"
KS="$H/local.keystore"
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass crema-home -keypass crema-home -alias home \
  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=crema-home" >/dev/null 2>&1
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:crema-home --key-pass pass:crema-home \
  --out "$H/out/crema-home.apk" "$W/a.apk"
echo "built crema-home/out/crema-home.apk ($(wc -c < "$H/out/crema-home.apk") bytes)"
