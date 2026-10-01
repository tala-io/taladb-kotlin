#!/usr/bin/env bash
# Assert the native libraries in a built AAR will load on a device.
#
#   - every ABI the package supports is present, with both libraries
#   - every LOAD segment is 16 KB aligned (Google Play, and 16 KB-page devices)
#   - no dependency is recorded as a path: a DT_NEEDED like
#     /home/<builder>/.../libtaladb_ffi.so links fine and fails on every device
#
# Usage: scripts/check-aar.sh [path/to.aar]
# Needs llvm-readelf (from the NDK) or readelf on PATH, or READELF set.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
AAR="${1:-$ROOT/taladb/build/outputs/aar/taladb-release.aar}"
ABIS=(arm64-v8a armeabi-v7a x86_64)

READELF="${READELF:-}"
if [[ -z "$READELF" ]]; then
    SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
    READELF="$(ls "$SDK"/ndk/*/toolchains/llvm/prebuilt/*/bin/llvm-readelf 2>/dev/null | sort -V | tail -1 || true)"
    [[ -n "$READELF" ]] || READELF="$(command -v readelf)"
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
unzip -q "$AAR" -d "$WORK"

status=0
fail() { echo "FAIL: $*"; status=1; }

for abi in "${ABIS[@]}"; do
    for lib in libtaladb_ffi.so libtaladb_jni.so; do
        so="$WORK/jni/$abi/$lib"
        [[ -f "$so" ]] || { fail "$abi/$lib missing"; continue; }
        for align in $("$READELF" -lW "$so" | awk '$1 == "LOAD" { print $NF }'); do
            (( align >= 0x4000 )) || fail "$abi/$lib has a LOAD segment aligned to $align"
        done
        while read -r needed; do
            [[ "$needed" != */* ]] || fail "$abi/$lib depends on a path: $needed"
        done < <("$READELF" -d "$so" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p')
    done
done

extra="$(find "$WORK/jni" -name '*.so' ! -name libtaladb_ffi.so ! -name libtaladb_jni.so)"
[[ -z "$extra" ]] || fail "unexpected libraries: $extra"

[[ $status -eq 0 ]] && echo "OK: $AAR"
exit $status
