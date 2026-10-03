#!/usr/bin/env bash
# Publish to a throwaway local Maven repository and check the result against
# what Maven Central requires, so a release tag never discovers a broken
# POM or a missing javadoc/sources jar. Nothing is signed or uploaded.
#
# Usage: scripts/check-publication.sh [version]      default: 0.0.0-dryrun
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="${1:-0.0.0-dryrun}"
REPO="$(mktemp -d)"
trap 'rm -rf "$REPO"' EXIT

cd "$ROOT"
./gradlew publishToMavenLocal -PVERSION_NAME="$VERSION" -Dmaven.repo.local="$REPO" \
    --console=plain --no-configuration-cache

DIR="$REPO/dev/taladb/taladb-android/$VERSION"
BASE="$DIR/taladb-android-$VERSION"
fail() { echo "error: $*" >&2; exit 1; }

for suffix in .aar .pom .module -sources.jar -javadoc.jar; do
    [[ -s "$BASE$suffix" ]] || fail "missing $(basename "$BASE$suffix")"
done

POM="$BASE.pom"
for element in name description url licenses developers scm; do
    grep -q "<$element>" "$POM" || fail "POM has no <$element>, which Maven Central requires"
done
grep -q "<version>$VERSION</version>" "$POM" || fail "POM version is not $VERSION"

# Listings go through variables: `unzip -l | grep -q` fails under pipefail
# when grep exits early and unzip gets SIGPIPE.
has() { local listing; listing="$(unzip -l "$1")"; grep -q "$2" <<<"$listing"; }
has "$BASE-javadoc.jar" ' index.html$' || fail "javadoc jar has no index.html"
has "$BASE-sources.jar" 'dev/taladb/TalaDB.kt' || fail "sources jar has no dev/taladb/TalaDB.kt"
for abi in arm64-v8a armeabi-v7a x86_64; do
    has "$BASE.aar" "jni/$abi/libtaladb_ffi.so" || fail "AAR has no jni/$abi/libtaladb_ffi.so"
done

echo "OK: dev.taladb:taladb-android:$VERSION would publish:"
ls -1 "$DIR" | sed 's/^/  /'
