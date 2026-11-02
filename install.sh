#!/usr/bin/env bash
# millie-crema installer — applies the selected parts to a rooted CREMA-0640L (Android 4.2.2) over adb.
# Run with no options for an interactive menu, or pick parts with flags (see --help / README.md).
set -euo pipefail
source "$(dirname "$0")/scripts/common.sh"

usage() {
  cat <<'EOF'
usage: ./install.sh [parts...] [options]

parts (none given = interactive menu):
  --root          install su + Superuser app (needs --su-zip or --su-binary+--su-apk; see README)
  --framework     patched framework.jar (API 18/19 backports, WebView bridge, compat hooks)   [required by most]
  --core          patched core.jar (TLS 1.2 + AES-GCM for Java, SHA-256 APK signatures)
  --system        platform.xml storage gids + Millie's DRM native libs into /system/lib
  --wrap          Millie-only process wrapper: statvfs/TLS/heap shims, -Xverify:none, heap tuning
  --webcompat     per-app WebView/runtime config for Millie (/system/etc/webview_compat)
  --browser-tls   let the stock browser use TLS 1.2 too
  --launcher      "밀리의 서재" home-screen launcher (network wait screen, logo splash)
  --crema-home    "크레마 홈" e-ink home launcher (crema-home/: KOReader shelf, light/Wi-Fi, running apps)
  --home APK      install some other e-ink home launcher APK instead
  --debloat       disable dead CREMA/YES24 stock apps (pm disable; reversible)
  --hosts         pin Millie host IPs in /system/etc/hosts (NOT recommended: IPs rotate)
  --all           framework core system wrap webcompat launcher crema-home

options:
  --apk PATH              your original Millie 2.4.0.0 APK (launcher logo/icon; also installs it with --install-millie)
  --install-millie        adb install the APK given with --apk (after the framework is in place)
  --su-zip PATH           SuperSU flashable zip (for --root; su binary + APK extracted automatically)
  --su-binary PATH        path to su binary for arm (for --root, alternative to --su-zip)
  --su-apk PATH           path to Superuser/SuperSU APK (for --root, with --su-binary)
  --stock-framework PATH  unmodified framework.jar of this device (needed if the device is already modified
  --stock-core PATH       and no stock copy was saved before)
  --no-reboot             don't reboot at the end
  --dry-run               build everything and print the device script, change nothing
EOF
}

PARTS=(); APK=""; INSTALL_MILLIE=0; STOCK_FW=""; STOCK_CORE=""; REBOOT=1; DRY=0; HOME_APK=""
SU_ZIP=""; SU_BIN=""; SU_APK_PATH=""
while [ $# -gt 0 ]; do
  case "$1" in
    --root|--framework|--core|--system|--wrap|--webcompat|--browser-tls|--launcher|--crema-home|--debloat|--hosts) PARTS+=("${1#--}");;
    --home) HOME_APK=$2; PARTS+=(home); shift;;
    --all) PARTS+=(framework core system wrap webcompat launcher crema-home);;
    --apk) APK=$2; shift;;
    --install-millie) INSTALL_MILLIE=1;;
    --su-zip) SU_ZIP=$2; shift;;
    --su-binary) SU_BIN=$2; shift;;
    --su-apk) SU_APK_PATH=$2; shift;;
    --stock-framework) STOCK_FW=$2; shift;;
    --stock-core) STOCK_CORE=$2; shift;;
    --no-reboot) REBOOT=0;;
    --dry-run) DRY=1;;
    -h|--help) usage; exit 0;;
    *) die "unknown option $1 (see --help)";;
  esac
  shift
done

ask() {  # ask "question" default(y/n) -> 0 if yes
  local a; read -r -p "$1 [$( [ "$2" = y ] && echo Y/n || echo y/N )] " a || true
  a=${a:-$2}; [[ "$a" =~ ^[Yy] ]]
}
if [ ${#PARTS[@]} -eq 0 ]; then
  echo "== millie-crema: choose what to apply =="
  ask "0) root       - install su + Superuser app (needed once on a fresh device)" n && PARTS+=(root)
  ask "1) framework  - API 18/19 backports, WebView bridge, compat hooks (required by most parts)" y && PARTS+=(framework)
  ask "2) core       - TLS 1.2 + AES-GCM, SHA-256 APK signatures" y && PARTS+=(core)
  ask "3) system     - storage gids + DRM native libs" y && PARTS+=(system)
  ask "4) wrap       - Millie-only shims (PDF statvfs, WebView TLS, heap tuning, faster start)" y && PARTS+=(wrap)
  ask "5) webcompat  - EPUB viewer compat, touch/page-turn fixes, warm-up, Glide/GIF fixes" y && PARTS+=(webcompat)
  ask "6) launcher   - '밀리의 서재' launcher with network wait + logo splash" y && PARTS+=(launcher)
  ask "7) browser-tls - stock browser TLS 1.2" n && PARTS+=(browser-tls)
  ask "8) debloat    - disable dead YES24/CREMA stock apps" n && PARTS+=(debloat)
  ask "9) hosts      - pin Millie IPs in /etc/hosts (not recommended)" n && PARTS+=(hosts)
  ask "10) crema-home - '크레마 홈' e-ink home launcher" y && PARTS+=(crema-home)
  if ask "11) home     - install some other home launcher APK instead" n; then read -r -p "    path to APK: " HOME_APK; PARTS+=(home); fi
  if [ -z "$APK" ]; then read -r -p "Millie APK for the launcher logo (empty = text logo): " APK || true; fi
fi
has() { local p; for p in "${PARTS[@]}"; do [ "$p" = "$1" ] && return 0; done; return 1; }
[ ${#PARTS[@]} -gt 0 ] || { echo "nothing selected"; exit 0; }
echo "parts: ${PARTS[*]}"

# ---------------------------------------------------------------- preflight
need adb "Android platform-tools"
adb get-state >/dev/null 2>&1 || die "no adb device"
SDKV=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
MODEL=$(adb shell getprop ro.product.model | tr -d '\r')
[ "$SDKV" = "17" ] || die "device API is $SDKV, this kit targets Android 4.2.2 (API 17)"
[ "$MODEL" = "CREMA-0640L" ] || echo "warning: built and tested on CREMA-0640L, this is '$MODEL'"
if ! has root; then
  adb shell "su -c id" 2>/dev/null | grep -q 'uid=0' || die "root (su) is required — run with --root first"
fi
TS=$(date +%Y%m%d-%H%M%S)
W="$ROOT/.build/$TS"; mkdir -p "$W/stage"; CACHE="$ROOT/.cache"; mkdir -p "$CACHE"
DEV_STAGE=/data/local/tmp/millie-crema-stage
BK=/sdcard/millie-crema-backup/$TS
STOCK_DIR=/sdcard/millie-crema-backup/stock
SCRIPT="$W/apply.sh"
cat > "$SCRIPT" <<EOF
#!/system/bin/sh
# generated by install.sh $TS
STAGE=$DEV_STAGE
BK=$BK
mkdir -p \$BK
echo '#!/system/bin/sh' > \$BK/restore.sh
echo 'mount -o remount,rw /system' >> \$BK/restore.sh
backup() {  # remember how to undo a change to file \$1
  f=\$1
  grep -q "'\$f'\$" \$BK/restore.sh && return
  if [ -e "\$f" ]; then mkdir -p "\$BK\${f%/*}"; cp -p "\$f" "\$BK\$f"; echo "cp -p '\$BK\$f' '\$f'" >> \$BK/restore.sh
  else echo "rm -f '\$f'" >> \$BK/restore.sh; fi
}
put() { backup "\$2"; mkdir -p "\${2%/*}"; cp "\$STAGE/\$1" "\$2" && chmod \$3 "\$2" && echo "  installed \$2"; }
setbp() {  # set key=value in /system/build.prop
  backup /system/build.prop
  grep -v "^\$1=" /system/build.prop > \$STAGE/bp.tmp; echo "\$1=\$2" >> \$STAGE/bp.tmp
  cat \$STAGE/bp.tmp > /system/build.prop; chmod 644 /system/build.prop; echo "  build.prop: \$1"
}
mount -o remount,rw /system
EOF
emit() { echo "$*" >> "$SCRIPT"; }
WIPE_DALVIK=0

stock_jar() {  # stock_jar <name> <marker> <user-path>  -> path of an unmodified copy of /system/framework/<name>
  local name=$1 marker=$2 user=$3
  local cur="$W/$name.device"
  if [ -n "$user" ]; then echo "$user"; return; fi
  if [ -f "$CACHE/stock-$name" ]; then echo "$CACHE/stock-$name"; return; fi
  if adb shell "ls $STOCK_DIR/$name" 2>/dev/null | grep -q "$name"; then
    adb pull "$STOCK_DIR/$name" "$CACHE/stock-$name" >/dev/null; echo "$CACHE/stock-$name"; return
  fi
  adb pull "/system/framework/$name" "$cur" >/dev/null
  if unzip -p "$cur" classes.dex 2>/dev/null | grep -aq "$marker"; then
    die "/system/framework/$name is already modified and no stock copy is known - pass --stock-${name%.jar} PATH"
  fi
  cp "$cur" "$CACHE/stock-$name"
  emit "mkdir -p $STOCK_DIR; [ -e $STOCK_DIR/$name ] || cp -p /system/framework/$name $STOCK_DIR/$name"
  echo "$CACHE/stock-$name"
}

# ---------------------------------------------------------------- parts
if has root; then
  echo "[root] checking root status"
  if adb shell "su -c id" 2>/dev/null | grep -q 'uid=0'; then
    echo "[root] device is already rooted, skipping"
  else
    SU_BIN_FINAL=""; SU_APK_FINAL=""
    if [ -n "$SU_ZIP" ]; then
      [ -f "$SU_ZIP" ] || die "--su-zip file not found: $SU_ZIP"
      echo "[root] extracting su binary and APK from $SU_ZIP"
      SU_EX="$W/su-extract"; mkdir -p "$SU_EX"
      unzip -o "$SU_ZIP" -d "$SU_EX" >/dev/null 2>&1
      SU_BIN_FINAL=$(find "$SU_EX" -type f -name 'su' -path '*arm*' | head -1)
      [ -z "$SU_BIN_FINAL" ] && SU_BIN_FINAL=$(find "$SU_EX" -type f -name 'su' | head -1)
      SU_APK_FINAL=$(find "$SU_EX" -type f -name '*.apk' | head -1)
      [ -n "$SU_BIN_FINAL" ] || die "no su binary found in $SU_ZIP"
      [ -n "$SU_APK_FINAL" ] || die "no Superuser APK found in $SU_ZIP"
    elif [ -n "$SU_BIN" ] && [ -n "$SU_APK_PATH" ]; then
      [ -f "$SU_BIN" ] || die "--su-binary file not found: $SU_BIN"
      [ -f "$SU_APK_PATH" ] || die "--su-apk file not found: $SU_APK_PATH"
      SU_BIN_FINAL="$SU_BIN"; SU_APK_FINAL="$SU_APK_PATH"
    else
      echo ""
      echo "  --root requires the SuperSU files. Provide ONE of:"
      echo "    --su-zip PATH          SuperSU flashable zip (e.g. UPDATE-SuperSU-v2.82.zip)"
      echo "    --su-binary + --su-apk separate su binary (arm) and Superuser APK"
      echo ""
      echo "  Download SuperSU from the official XDA thread or use the version"
      echo "  that was used to root this device originally."
      die "--root needs --su-zip or (--su-binary + --su-apk)"
    fi
    # Try adb root (works on userdebug/eng builds, devices with ro.secure=0)
    adb root >/dev/null 2>&1 || true; sleep 2
    if adb shell "id" 2>/dev/null | grep -q 'uid=0'; then
      echo "[root] adbd running as root, installing su binary"
      adb shell "mount -o remount,rw /system"
      adb push "$SU_BIN_FINAL" /system/xbin/su
      adb shell "chown 0:0 /system/xbin/su && chmod 6755 /system/xbin/su"
      adb shell "[ -e /system/bin/su ] || ln -s ../xbin/su /system/bin/su"
      adb shell "mount -o remount,ro /system"
      echo "[root] installing Superuser app"
      adb install -r "$SU_APK_FINAL"
      if adb shell "su -c id" 2>/dev/null | grep -q 'uid=0'; then
        echo "[root] root installed and verified"
      else
        echo "[root] WARNING: su installed but verification failed — grant root in the Superuser app on next prompt"
      fi
    else
      echo "[root] adbd does not have root access — pushing zip for recovery install"
      [ -n "$SU_ZIP" ] || die "no --su-zip and adbd is not root; provide a flashable zip for recovery"
      adb push "$SU_ZIP" /sdcard/supersu.zip
      echo ""
      echo "  === Manual step required ==="
      echo "  1. Device will reboot to recovery"
      echo "  2. In recovery: Install > /sdcard/supersu.zip > Swipe to flash"
      echo "  3. Reboot"
      echo "  4. Re-run this script (without --root)"
      echo ""
      if ask "Reboot to recovery now?" y; then adb reboot recovery; fi
      exit 0
    fi
  fi
fi

if has framework; then
  echo "[framework] building"
  S=$(stock_jar framework.jar 'Landroid/webkit/EvalBridge;' "$STOCK_FW")
  "$ROOT/millie-compat/build_jar.sh" "$S" "$ROOT/millie-compat/patches/framework.diff" "$W/stage/framework.jar"
  emit "put framework.jar /system/framework/framework.jar 644"; WIPE_DALVIK=1
fi
if has core; then
  echo "[core] building"
  S=$(stock_jar core.jar 'ECDHE-RSA-AES128-GCM-SHA256' "$STOCK_CORE")
  "$ROOT/millie-compat/build_jar.sh" "$S" "$ROOT/millie-compat/patches/core.diff" "$W/stage/core.jar"
  emit "put core.jar /system/framework/core.jar 644"; WIPE_DALVIK=1
fi
if has system; then
  echo "[system] platform.xml + DRM libs"
  adb pull /system/etc/permissions/platform.xml "$W/platform.xml" >/dev/null
  python3 - "$W/platform.xml" "$W/stage/platform.xml" <<'PY'
import re, sys
s = open(sys.argv[1]).read()
m = re.search(r'(<permission name="android.permission.READ_EXTERNAL_STORAGE"\s*>)(.*?)(</permission>)', s, re.S)
if not m: sys.exit("READ_EXTERNAL_STORAGE block not found in platform.xml")
body = m.group(2)
for gid in ("sdcard_rw", "media_rw"):
    if 'gid="%s"' % gid not in body: body += '    <group gid="%s" />\n    ' % gid
s = s[:m.start(2)] + body + s[m.end(2):]
open(sys.argv[2], "w").write(s)
PY
  emit "put platform.xml /system/etc/permissions/platform.xml 644"
  emit 'for l in libPocoFoundation.so libPocoZip.so libsqlcipher.so; do src=$(ls /data/app-lib/kr.co.millie.eink-*/$l 2>/dev/null); if [ -n "$src" ]; then backup /system/lib/$l; cp $src /system/lib/$l; chmod 644 /system/lib/$l; echo "  installed /system/lib/$l"; else echo "  skip $l (install Millie first, then re-run --system)"; fi; done'
fi
if has wrap; then
  echo "[wrap] shims + wrapper"
  for n in statvfs webtls props; do cp "$ROOT/millie-compat/native/${n}_shim/libcompat_$n.so" "$W/stage/"; emit "put libcompat_$n.so /system/lib/libcompat_$n.so 644"; done
  for f in millie_wrap.sh millie_vmopts millie_props; do cp "$ROOT/millie-compat/webcompat/$f" "$W/stage/"; emit "put $f /system/etc/webview_compat/$f 644"; done
  emit "setbp wrap.kr.co.millie.eink 'sh /system/etc/webview_compat/millie_wrap.sh'"
fi
if has webcompat; then
  echo "[webcompat] config"
  for e in js sub dedupe warm swlayer unleak setstatic; do cp "$ROOT/millie-compat/webcompat/kr.co.millie.eink.$e" "$W/stage/"; emit "put kr.co.millie.eink.$e /system/etc/webview_compat/kr.co.millie.eink.$e 644"; done
  if has launcher; then cp "$ROOT/millie-compat/webcompat/kr.co.millie.eink.shownsignal" "$W/stage/"; emit "put kr.co.millie.eink.shownsignal /system/etc/webview_compat/kr.co.millie.eink.shownsignal 644"; fi
fi
if has browser-tls; then
  echo "[browser-tls]"
  cp "$ROOT/millie-compat/native/webtls_shim/libcompat_webtls.so" "$W/stage/"; emit "put libcompat_webtls.so /system/lib/libcompat_webtls.so 644"
  emit "setbp wrap.com.android.browser LD_PRELOAD=/system/lib/libcompat_webtls.so"
fi
if has hosts; then
  echo "[hosts] resolving Millie hosts on this computer"
  need dig "bind tools"
  { echo "127.0.0.1		    localhost"
    for h in api www image cover contents static gateway apis live-api; do
      ip=$(dig +short "$h.millie.co.kr" A | grep -E '^[0-9.]+$' | head -1); [ -n "$ip" ] && echo "$ip	$h.millie.co.kr"
    done
    ip=$(dig +short millie.co.kr A | grep -E '^[0-9.]+$' | head -1); [ -n "$ip" ] && echo "$ip	millie.co.kr"; } > "$W/stage/hosts"
  emit "put hosts /system/etc/hosts 644"
fi
if has debloat; then
  echo "[debloat]"
  # NEVER disable com.android.location.fused on 4.2: system_server crashes -> boot loop.
  for p in com.yes24.appstore com.estmob.android.sendanywhere com.android.email com.android.exchange com.android.music \
           com.android.musicfx com.melon.wizard com.yes24.ebook.einkstore com.keph.crema.shine kr.co.aladin.carta \
           kr.co.aladin.ebook com.bandinlunis com.incube.epub.pad com.svox.pico; do
    emit "pm list packages -e | grep -q 'package:$p\$' && { pm disable $p >/dev/null && echo '  disabled $p'; echo 'pm enable $p' >> \$BK/restore.sh; }"
  done
fi
[ "$WIPE_DALVIK" = 1 ] && emit 'echo "rm -rf /data/dalvik-cache/*" >> $BK/restore.sh; rm -rf /data/dalvik-cache/*; echo "  dalvik-cache wiped (first boot will be slow)"'
emit 'sync; mount -o remount,ro /system; echo "restore script: $BK/restore.sh"'

if has crema-home; then
  echo "[crema-home] building"
  "$ROOT/crema-home/build.sh"
fi
if has launcher; then
  echo "[launcher] building"
  if [ -n "$APK" ]; then "$ROOT/millie-launcher/build.sh" --apk "$APK"; else "$ROOT/millie-launcher/build.sh"; fi
fi

# ---------------------------------------------------------------- apply
if [ "$DRY" = 1 ]; then
  echo "== dry run: staged files in $W/stage, device script follows =="; cat "$SCRIPT"; exit 0
fi
adb shell "rm -rf $DEV_STAGE; mkdir -p $DEV_STAGE"
for f in "$W"/stage/*; do [ -e "$f" ] && adb push "$f" "$DEV_STAGE/" >/dev/null; done
adb push "$SCRIPT" "$DEV_STAGE/apply.sh" >/dev/null
adb shell "su -c 'sh $DEV_STAGE/apply.sh'"
adb shell "su -c 'rm -rf $DEV_STAGE'"

if has launcher; then
  out=$(adb install -r "$ROOT/millie-launcher/out/millie-launcher.apk" 2>&1 || true)
  if echo "$out" | grep -q INCONSISTENT_CERTIFICATES; then
    adb uninstall kr.co.millie.crema.launcher >/dev/null; out=$(adb install "$ROOT/millie-launcher/out/millie-launcher.apk" 2>&1 || true)
  fi
  echo "[launcher] $(echo "$out" | tail -1)"
fi
if has crema-home; then
  out=$(adb install -r "$ROOT/crema-home/out/crema-home.apk" 2>&1 || true)
  if echo "$out" | grep -q INCONSISTENT_CERTIFICATES; then
    adb uninstall kr.co.crema.home >/dev/null; out=$(adb install "$ROOT/crema-home/out/crema-home.apk" 2>&1 || true)
  fi
  echo "[crema-home] $(echo "$out" | tail -1)  -> press HOME on the device, pick '크레마 홈', tap '항상'"
fi
if has home; then echo "[home] $(adb install -r "$HOME_APK" 2>&1 | tail -1)  -> press HOME on the device, pick it, tap '항상'"; fi
if [ "$INSTALL_MILLIE" = 1 ]; then
  [ -n "$APK" ] || die "--install-millie needs --apk"
  if has framework && [ "$REBOOT" = 1 ]; then echo "[millie] will be installed after the reboot"; else echo "[millie] $(adb install -r "$APK" 2>&1 | tail -1)"; fi
fi
if [ "$REBOOT" = 1 ] && { has framework || has core || has system || has wrap || has browser-tls || has hosts; }; then
  echo "rebooting (first boot after a framework/core change re-optimizes everything: a few minutes)"
  adb reboot; adb wait-for-device
  until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 3; done
  if [ "$INSTALL_MILLIE" = 1 ]; then echo "[millie] $(adb install -r "$APK" 2>&1 | tail -1)"; fi
  echo "done"
else
  echo "done (reboot to activate system changes)"
fi
