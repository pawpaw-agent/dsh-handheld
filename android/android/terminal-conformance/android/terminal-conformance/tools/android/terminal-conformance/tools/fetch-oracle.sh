#!/bin/bash
# Regenerate the vendored reference-emulator jar used as the conformance oracle.
#
# The harness compares an in-house terminal against this implementation, so the
# oracle's exact bytes matter: a silently different oracle would move the goalposts
# without any test changing. Hence the pinned version and the recorded digest.
#
# Usage: fetch-oracle.sh [outputJar]
set -euo pipefail

VERSION="0.118.1"
GROUP_PATH="com/github/termux/termux-app"
ARTIFACT="terminal-emulator"
URL="https://jitpack.io/${GROUP_PATH}/${ARTIFACT}/${VERSION}/${ARTIFACT}-${VERSION}.aar"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="${1:-$HERE/../libs/termux-terminal-emulator-${VERSION}-classes.jar}"
EXPECTED="09ec707eb38e89ee8a7046201240f62fd68c5fbbc22116e187bf6a5d859de2dc"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

echo "downloading $ARTIFACT $VERSION from JitPack"
curl -fsSL --max-time 120 -o "$work/oracle.aar" "$URL"

echo "extracting classes.jar"
python3 - "$work/oracle.aar" "$work/classes.jar" <<'PY'
import sys
import zipfile

with zipfile.ZipFile(sys.argv[1]) as archive:
    with open(sys.argv[2], "wb") as out:
        out.write(archive.read("classes.jar"))
PY

actual="$(sha256sum "$work/classes.jar" | cut -d' ' -f1)"
if [ "$actual" != "$EXPECTED" ]; then
    echo "SHA-256 mismatch" >&2
    echo "  expected: $EXPECTED" >&2
    echo "  actual:   $actual" >&2
    echo "Upstream republished this version, or the version pin needs updating." >&2
    exit 1
fi

mkdir -p "$(dirname "$OUT")"
cp "$work/classes.jar" "$OUT"
echo "ok: $OUT"
echo "    sha256 $actual"
