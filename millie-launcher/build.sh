#!/usr/bin/env bash
# usage: millie-launcher/build.sh [--apk Millie-2.4.0.0.apk]
# Builds millie-launcher/out/millie-launcher.apk. With --apk, Millie's own logo/icon are taken from YOUR copy of the
# original APK (they are not in this repository); without it a text logo and a plain icon are used.
set -euo pipefail
source "$(dirname "$0")/../scripts/common.sh"
APK=""; [ "${1:-}" = "--apk" ] && APK=${2:-}
BT=$(build_tools); AJ=$(android_jar); need javac "JDK 8+"; need keytool "JDK"
L="$ROOT/millie-launcher"; W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
cp -r "$L/res" "$W/res"
if [ -n "$APK" ]; then
  [ -f "$APK" ] || die "no such APK: $APK"
  mkdir -p "$W/res/drawable-nodpi" "$W/res/drawable-hdpi"
  res_path() { "$BT/aapt" dump --values resources "$APK" 2>/dev/null | grep -A1 "$1:" | grep -o 'res/[^"]*\.png' | tail -1; }
  for pair in "drawable/splash_logo:drawable-nodpi/millie_logo.png" "drawable/splash_name:drawable-nodpi/millie_name.png" "mipmap/ic_launcher:drawable-hdpi/ic_launcher.png"; do
    r=$(res_path "${pair%%:*}"); [ -n "$r" ] && unzip -p "$APK" "$r" > "$W/res/${pair##*:}" && echo "  using $r for ${pair##*:}"
  done
fi
mkdir -p "$W/gen" "$W/cls" "$W/dex"
"$BT/aapt" package -f -M "$L/AndroidManifest.xml" -S "$W/res" -I "$AJ" -F "$W/base.apk" -J "$W/gen"
RJ=$(find "$W/gen" -name R.java | head -1)
javac -nowarn -source 8 -target 8 -bootclasspath "$AJ" -d "$W/cls" "$RJ" "$L"/src/kr/co/millie/crema/launcher/*.java 2>&1 | grep -v -E '^Note:|warning' || true
"$BT/d8" --min-api 17 --release --lib "$AJ" --output "$W/dex" $(find "$W/cls" -name '*.class')
cp "$W/base.apk" "$W/u.apk"; ( cd "$W/dex" && zip -q ../u.apk classes.dex )
"$BT/zipalign" -f 4 "$W/u.apk" "$W/a.apk"
KS="$L/local.keystore"
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass millie-crema -keypass millie-crema -alias launcher \
  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=millie-crema launcher" >/dev/null 2>&1
mkdir -p "$L/out"
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:millie-crema --key-pass pass:millie-crema --v1-signing-enabled true \
  --out "$L/out/millie-launcher.apk" "$W/a.apk"
echo "built millie-launcher/out/millie-launcher.apk"
