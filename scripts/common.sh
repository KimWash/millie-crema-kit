# shared helpers (sourced)
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLS="$ROOT/millie-compat/tools/bin"
SMALI="$TOOLS/smali-2.5.2.jar"
BAKSMALI="$TOOLS/baksmali-2.5.2.jar"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
die() { echo "error: $*" >&2; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || die "'$1' not found ($2)"; }
ensure_smali() {
  mkdir -p "$TOOLS"
  for j in smali baksmali; do
    f="$TOOLS/$j-2.5.2.jar"
    [ -s "$f" ] || { echo "downloading $j 2.5.2"; curl -fsSL -o "$f" "https://bitbucket.org/JesusFreke/smali/downloads/$j-2.5.2.jar" || die "download $j failed"; }
  done
}
build_tools() {
  local bt; bt=$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)
  [ -n "$bt" ] || die "Android SDK build-tools not found under $SDK"
  echo "$bt"
}
android_jar() {
  local j; for v in 17 19 21 33 34 35 36; do [ -f "$SDK/platforms/android-$v/android.jar" ] && { j="$SDK/platforms/android-$v/android.jar"; break; }; done
  [ -n "$j" ] || die "no platforms/android-*/android.jar under $SDK"
  echo "$j"
}
