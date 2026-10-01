#!/usr/bin/env bash
# Build the TalaDB native library from a local checkout of tala-io/taladb and
# stage it in engine/, where the Gradle build looks for it.
#
#   engine/include/taladb.h                  C header the JNI shim compiles against
#   engine/jniLibs/<abi>/libtaladb_ffi.so    Android libraries packed into the AAR
#   engine/host/libtaladb_ffi.{so,dylib}     host library for the JVM unit tests
#   engine/SOURCE                            where the files came from
#
# Use this to develop against unreleased engine changes, and in CI to get the
# host library, which the release archive does not carry. Published AARs are
# built from the release archive instead (scripts/fetch-engine.sh), so what
# ships is byte-for-byte what the engine release published.
#
# Usage:
#   scripts/build-engine.sh [path-to-taladb-checkout]     default: ../taladb
#   HOST_ONLY=1 scripts/build-engine.sh ...               skip the Android ABIs
#
# Requires: cargo, and for the Android ABIs cargo-ndk plus an NDK (found via
# ANDROID_NDK_HOME, else the newest under $ANDROID_HOME/ndk).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$(cd "${1:-${TALADB_ENGINE_SRC:-$ROOT/../taladb}}" && pwd)"
FFI="$SRC/packages/bindings/ffi"
OUT="$ROOT/engine"
ABIS=(arm64-v8a armeabi-v7a x86_64)

[[ -f "$FFI/Cargo.toml" ]] || {
    echo "error: $SRC is not a taladb checkout with packages/bindings/ffi" >&2
    echo "       (the FFI crate moved there with ABI version 2)" >&2
    exit 1
}

TARGET_DIR="$(cargo metadata --manifest-path "$FFI/Cargo.toml" --no-deps --format-version 1 |
    python3 -c 'import sys, json; print(json.load(sys.stdin)["target_directory"])')"

rm -rf "$OUT"
mkdir -p "$OUT/include" "$OUT/host"
cp "$FFI/include/taladb.h" "$OUT/include/"

if [[ "${HOST_ONLY:-0}" != 1 ]]; then
    if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
        SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
        ANDROID_NDK_HOME="$(ls -d "$SDK"/ndk/*/ 2>/dev/null | sort -V | tail -1)"
        ANDROID_NDK_HOME="${ANDROID_NDK_HOME%/}"
        export ANDROID_NDK_HOME
    fi
    [[ -d "${ANDROID_NDK_HOME:-}" ]] || { echo "error: no NDK found; set ANDROID_NDK_HOME" >&2; exit 1; }
    echo "Building Android ABIs with NDK $(basename "$ANDROID_NDK_HOME")"
    args=()
    for abi in "${ABIS[@]}"; do args+=(-t "$abi"); done
    # --platform matches the AAR's minSdk. The 16 KB page size flag comes from
    # the engine's .cargo/config.toml, so it applies here exactly as in its
    # release build.
    (cd "$FFI" && cargo ndk "${args[@]}" --platform 24 -o "$OUT/jniLibs" build --release)
    # `-o` copies every cdylib in the build — including libredb-<hash>.so,
    # because redb 2.x (the legacy-migration dependency) declares a cdylib
    # crate type. libtaladb_ffi.so links redb statically and never loads it,
    # so anything else here is dead weight in every app.
    find "$OUT/jniLibs" -name '*.so' ! -name libtaladb_ffi.so -delete
fi

echo "Building host library"
cargo build --manifest-path "$FFI/Cargo.toml" --release
case "$(uname -s)" in
    Darwin) cp "$TARGET_DIR/release/libtaladb_ffi.dylib" "$OUT/host/" ;;
    *)      cp "$TARGET_DIR/release/libtaladb_ffi.so" "$OUT/host/" ;;
esac

echo "local $(git -C "$SRC" describe --always --dirty) ($SRC)" > "$OUT/SOURCE"
echo "Staged engine from $(cat "$OUT/SOURCE") in $OUT"
