#!/usr/bin/env bash
# Download the engine release pinned in engine.properties, verify its SHA-256,
# and stage its Android libraries and header in engine/.
#
# Published AARs are built this way, so what ships is byte-for-byte what the
# engine release published. The archive carries no host library; an existing
# engine/host/ (from scripts/build-engine.sh HOST_ONLY=1) is left in place for
# the unit tests.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/engine"

prop() { sed -n "s/^$1=//p" "$ROOT/engine.properties"; }
VERSION="$(prop version)"
URL="$(prop url)"
SHA256="$(prop sha256)"
ABI="$(prop abi)"

if [[ -z "$VERSION" || -z "$URL" || -z "$SHA256" ]]; then
    echo "error: engine.properties pins no release yet (version/url/sha256 are empty)." >&2
    echo "       Build from source instead: scripts/build-engine.sh [../taladb]" >&2
    exit 1
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "Downloading $URL"
curl -fsSL --retry 3 -o "$WORK/engine.zip" "$URL"
ACTUAL="$( (sha256sum "$WORK/engine.zip" 2>/dev/null || shasum -a 256 "$WORK/engine.zip") | cut -d' ' -f1)"
if [[ "$ACTUAL" != "$SHA256" ]]; then
    echo "error: SHA-256 mismatch for $URL" >&2
    echo "       expected $SHA256" >&2
    echo "       actual   $ACTUAL" >&2
    exit 1
fi

unzip -q "$WORK/engine.zip" -d "$WORK"
SRC="$WORK/taladb-ffi-android-$VERSION"
[[ -f "$SRC/include/taladb.h" && -d "$SRC/jniLibs" ]] || {
    echo "error: unexpected archive layout in $URL" >&2
    exit 1
}

HEADER_ABI="$(sed -n 's/^#define TALADB_FFI_ABI_VERSION \([0-9]*\)$/\1/p' "$SRC/include/taladb.h")"
if [[ "$HEADER_ABI" != "$ABI" ]]; then
    echo "error: release $VERSION has C ABI version ${HEADER_ABI:-<none>}, but this package" >&2
    echo "       is written for $ABI (engine.properties abi=). Update the JNI shim first." >&2
    exit 1
fi

rm -rf "$OUT/include" "$OUT/jniLibs"
mkdir -p "$OUT"
cp -R "$SRC/include" "$SRC/jniLibs" "$OUT/"
echo "release $VERSION ($URL, sha256 $SHA256)" > "$OUT/SOURCE"
echo "Staged engine release $VERSION in $OUT"
