#!/usr/bin/env bash
# usage: kyobo-compat/build.sh <KyoboLibrary_1.2.0_patched_by_salmonpa_260929.apk> [out.apk]
# Builds a 교보도서관 APK for Android 4.2 (CREMA Sound):
#  - inserts crema.kyobo.CompatWebViewClient under the library WebViewClient (ui.main.z$g):
#    library-site JS/CSS are rewritten to ES5 + CSS fallbacks, with polyfills (poly.js)
#  - re-signs with a SHA-1 v1 signature (4.2 cannot verify SHA-256 v1 signatures)
# Signing key: ~/.android/debug.keystore (same key every build -> `adb install -r` keeps the login).
set -euo pipefail
IN=${1:?usage: build.sh <salmonpa apk> [out.apk]}
DIR="$(cd "$(dirname "$0")" && pwd)"
OUT=${2:-$DIR/out/KyoboLibrary_1.2.0_crema.apk}
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
AJAR=$SDK/platforms/android-17/android.jar
TOOLS=${SMALI_DIR:-$DIR/../../millie-crema/tools}
SMALI=$TOOLS/smali-2.5.2.jar; BAKSMALI=$TOOLS/baksmali-2.5.2.jar
KS=${KEYSTORE:-$HOME/.android/debug.keystore}
for f in "$AJAR" "$SMALI" "$BAKSMALI" "$KS" "$BT/d8" "$BT/apksigner" "$BT/zipalign"; do [ -e "$f" ] || { echo "missing: $f"; exit 1; }; done

W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
echo "[1/5] compile compat classes"
mkdir -p "$W/cls" "$W/d8"
javac -source 8 -target 8 -bootclasspath "$AJAR" -cp "$AJAR" -d "$W/cls" "$DIR"/src/crema/kyobo/*.java 2>&1 | grep -v "^warning\|^1 warning\|bootstrap" || true
"$BT/d8" --min-api 17 --output "$W/d8" $(find "$W/cls" -name '*.class') --lib "$AJAR"
java -jar "$BAKSMALI" d "$W/d8/classes.dex" -o "$W/smali"

echo "[2/5] disassemble app"
mkdir -p "$W/apk"; (cd "$W/apk" && unzip -q "$IN")
java -jar "$BAKSMALI" d --api 17 "$W/apk/classes.dex" -o "$W/smali"

echo "[3/5] patch library WebViewClient"
Z="$W/smali/kr/co/kyobobook/KEL/ui/main/z\$g.smali"
[ -f "$Z" ] || { echo "ui/main/z\$g.smali not found (different APK?)"; exit 1; }
grep -q '^.super Landroid/webkit/WebViewClient;' "$Z" || { echo "z\$g is not a direct WebViewClient subclass"; exit 1; }
grep -q 'shouldInterceptRequest' "$Z" && { echo "z\$g already overrides shouldInterceptRequest"; exit 1; }
sed -i '' -e 's#^\.super Landroid/webkit/WebViewClient;#.super Lcrema/kyobo/CompatWebViewClient;#' \
  -e 's#invoke-direct {\([vp][0-9]*\)}, Landroid/webkit/WebViewClient;-><init>()V#invoke-direct {\1}, Lcrema/kyobo/CompatWebViewClient;-><init>()V#' \
  -e 's#invoke-super \(.*\), Landroid/webkit/WebViewClient;->#invoke-super \1, Lcrema/kyobo/CompatWebViewClient;->#' "$Z"
grep -q 'CompatWebViewClient;-><init>' "$Z" || { echo "constructor patch failed"; exit 1; }

echo "[3b] API 18/19 calls -> crema.kyobo.Api17"
# StandardCharsets.X, Objects.*, StatFs.*Long/*Bytes, Context.getExternalFilesDirs: called without a
# version check by the app's own code (download/storage), which crashes on 4.2 with NoClassDefFoundError.
A17='Lcrema/kyobo/Api17;'
grep -rlE 'Ljava/nio/charset/StandardCharsets;|Ljava/util/Objects;->|Landroid/os/StatFs;->get[A-Za-z]+(Long|Bytes)\(|;->getExternalFilesDirs\(' "$W/smali" \
  | grep -v '/crema/kyobo/' | while read -r f; do
  sed -i '' -E \
    -e "s#Ljava/nio/charset/StandardCharsets;->#${A17}->#g" \
    -e "s#invoke-static(/range)? \{([^}]*)\}, Ljava/util/Objects;->#invoke-static\1 {\2}, ${A17}->#g" \
    -e "s#invoke-virtual(/range)? \{([^}]*)\}, Landroid/os/StatFs;->(get[A-Za-z]+(Long|Bytes))\(\)J#invoke-static\1 {\2}, ${A17}->\3(Landroid/os/StatFs;)J#g" \
    -e "s#invoke-virtual(/range)? \{([^}]*)\}, L[^;]+;->getExternalFilesDirs\(Ljava/lang/String;\)\[Ljava/io/File;#invoke-static\1 {\2}, ${A17}->getExternalFilesDirs(Landroid/content/Context;Ljava/lang/String;)[Ljava/io/File;#g" \
    "$f"
done
# epub viewer: parses "Chrome/NN." from the WebView UA (no Chrome token on 4.2 -> crash on open)
EV="$W/smali/kr/co/kyobobook/KEL/viewer/epub/ViewerEpubMainActivity.smali"
[ -f "$EV" ] || { echo "ViewerEpubMainActivity.smali not found"; exit 1; }
sed -i '' -E "s#invoke-virtual \{([vp][0-9]+)\}, Landroid/webkit/WebSettings;->getUserAgentString\(\)Ljava/lang/String;#invoke-static {\1}, ${A17}->getUserAgentString(Landroid/webkit/WebSettings;)Ljava/lang/String;#g" "$EV"
grep -q "${A17}->getUserAgentString" "$EV" || { echo "epub viewer UA patch failed"; exit 1; }

echo "[3c] bookshelf sharing hooks (crema.kyobo.Shelf)"
APPF="$W/smali/kr/co/kyobobook/KEL/EBookCaseApplication.smali"
LINKF="$W/smali/kr/co/kyobobook/KEL/ui/intro/ApplinkActivity.smali"
# Application.onCreate: start the bookshelf export thread
perl -0pi -e 's#(\.method public onCreate\(\)V\n(?:.*\n)*?\s*invoke-super \{p0\}, Landroid/app/Application;->onCreate\(\)V\n)#$1\n    invoke-static {p0}, Lcrema/kyobo/Shelf;->start(Landroid/content/Context;)V\n#' "$APPF"
grep -q 'Lcrema/kyobo/Shelf;->start' "$APPF" || { echo "Application hook failed"; exit 1; }
# ApplinkActivity.onCreate: kyobolibrarykel://crema-open/<id> opens a book
perl -0pi -e 's#(\.method protected onCreate\(Landroid/os/Bundle;\)V\n(?:.*\n)*?\s*invoke-super \{p0, p1\}, Landroidx/appcompat/app/d;->onCreate\(Landroid/os/Bundle;\)V\n)#$1\n    invoke-static {p0}, Lcrema/kyobo/Shelf;->applink(Landroid/app/Activity;)Z\n    move-result v0\n    if-eqz v0, :crema_not_ours\n    return-void\n    :crema_not_ours\n#' "$LINKF"
grep -q 'Lcrema/kyobo/Shelf;->applink' "$LINKF" || { echo "Applink hook failed"; exit 1; }

left=$(grep -rnE 'Ljava/nio/charset/StandardCharsets;|Ljava/util/Objects;->|Landroid/os/StatFs;->get[A-Za-z]+Long\(|;->getExternalFilesDirs\(' "$W/smali" | grep -v '/crema/kyobo/' | grep -v 'Api17;->' || true)
[ -z "$left" ] || { echo "unpatched API 18/19 references:"; echo "$left"; exit 1; }

echo "[4/5] assemble"
java -jar "$SMALI" a --api 17 "$W/smali" -o "$W/classes.dex"
# start from the original zip so every other entry keeps its original compression
# (raw resources such as .wav must stay STORED: openRawResourceFd fails on compressed entries)
cp "$IN" "$W/unsigned.apk"
zip -q -d "$W/unsigned.apk" 'META-INF/*'
mkdir -p "$W/new/assets/crema"
cp "$W/classes.dex" "$W/new/classes.dex"
cp "$DIR/poly.js" "$DIR/debug.js" "$W/new/assets/crema/"
(cd "$W/new" && zip -q "$W/unsigned.apk" classes.dex assets/crema/poly.js assets/crema/debug.js)

echo "[5/5] align + sign (v1 SHA-1 for API 17, v2)"
mkdir -p "$(dirname "$OUT")"
"$BT/zipalign" -f -p 4 "$W/unsigned.apk" "$W/aligned.apk"
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --ks-key-alias androiddebugkey --min-sdk-version 17 \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled false --out "$OUT" "$W/aligned.apk"
"$BT/apksigner" verify --min-sdk-version 17 "$OUT"
ls -l "$OUT"
echo "install: adb install -r \"$OUT\""
