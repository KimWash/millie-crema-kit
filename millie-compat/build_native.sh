#!/usr/bin/env bash
# Rebuild the three LD_PRELOAD shims (prebuilt copies are committed next to the sources).
set -euo pipefail
source "$(dirname "$0")/../scripts/common.sh"
NDK=${ANDROID_NDK_HOME:-$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1)}
[ -d "$NDK" ] || die "Android NDK not found"
CC=$(ls "$NDK"/toolchains/llvm/prebuilt/*/bin/armv7a-linux-androideabi19-clang | head -1)
build() { "$CC" -shared -fPIC -O2 -Wl,--hash-style=sysv -o "$ROOT/millie-compat/native/$1/$2" "$ROOT/millie-compat/native/$1/$3" "${@:4}"; echo "built native/$1/$2"; }
build statvfs_shim libcompat_statvfs.so statvfs_shim.c
build webtls_shim libcompat_webtls.so webtls_shim.c -ldl -llog
build props_shim libcompat_props.so props_shim.c -ldl
