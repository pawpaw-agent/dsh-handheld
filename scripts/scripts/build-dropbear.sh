#!/bin/bash -e
# Build Dropbear dbclient for Android (arm64) using the NDK.
#
# Based on the MIT-licensed build script from ribbons/android-dropbear
# (https://github.com/ribbons/android-dropbear, SPDX-License-Identifier: MIT)
# — adjusted for the dsh-handheld project (single arm64 target, dbclient only).
#
# Usage:
#   ANDROID_NDK_HOME=/path/to/ndk ./scripts/build-dropbear.sh
#   [DROPBEAR_VERSION=DROPBEAR_2026.94] [BUILD_ONLY=dbclient]
#
# Output: ./build-dropbear-output/{dbclient,LICENSE.txt}
#
# Always run from this script's own directory (resolved absolutely),
# so paths (localoptions.h, output dir) stay valid after `cd dropbear`.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

TARGET=${TARGET:-aarch64-linux-android}
PLATFORM=21
DROPBEAR_VERSION=${DROPBEAR_VERSION:-DROPBEAR_2026.94}
BUILD_ONLY=${BUILD_ONLY:-"dbclient dropbearkey"}
OUTDIR=${OUTDIR:-"$PROJECT_ROOT/build-dropbear-output"}

if [ -z "$ANDROID_NDK_HOME" ]; then
    echo "ANDROID_NDK_HOME is not set" >&2
    exit 1
fi

toolchain=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64
[ -x "$toolchain/bin/$TARGET$PLATFORM-clang" ] || {
    echo "toolchain clang not found: $toolchain/bin/$TARGET$PLATFORM-clang" >&2
    exit 1
}

# Clone upstream dropbear at the pinned tag (official mkj/dropbear source).
rm -rf dropbear
git clone -q -b "$DROPBEAR_VERSION" --depth 1 https://github.com/mkj/dropbear.git
cd dropbear

./configure --host="$TARGET" --disable-lastlog --disable-utmp --disable-wtmp \
    AR="$toolchain/bin/llvm-ar" \
    CC="$toolchain/bin/$TARGET$PLATFORM-clang" \
    RANLIB="$toolchain/bin/llvm-ranlib" \
    STRIP="$toolchain/bin/llvm-strip"

# Apply the Android-specific options (disables server password auth: crypt()
# is unavailable on Android; keeps client DROPBEAR_PASSWORD env auth).
# Explicit source path: CWD is dropbear/ here.
cp "$SCRIPT_DIR/localoptions.h" .

# cli-auth.c patch (python, exact-text): when DROPBEAR_USE_PASSWORD_ENV,
# never call getpass() (Android bionic lacks it). getenv must return a
# value; otherwise close with a clear message. Original code is kept under
# #else so USE_PASSWORD_ENV=0 builds are unchanged.
python3 - <<'PYEOF'
import re
p = 'src/cli-auth.c'
s = open(p).read()
old = '''\tif (cli_opts.batch_mode) {
\t\tdropbear_close("BatchMode active, no interactive session possible.");
\t}

\tif (!cli_opts.batch_mode) {
\t\tpassword = getpass(prompt);
\t}

\t/* 0x03 is a ctrl-c character in the buffer. */
\tif (password == NULL || strchr(password, '\\3') != NULL) {
\t\tdropbear_close("Interrupted.");
\t}'''
new = '''#if DROPBEAR_USE_PASSWORD_ENV
\tdropbear_close("DROPBEAR_PASSWORD not set");
#else
''' + old + '''
#endif'''
if 'DROPBEAR_PASSWORD not set' in s:
    print('cli-auth already patched')
else:
    assert old in s, 'getpass block not found'
    s = s.replace(old, new, 1)
    open(p, 'w').write(s)
    print('cli-auth.c patched: env-only password path')
PYEOF

make PROGRAMS="$BUILD_ONLY"

# Collect outputs.
cd ..
mkdir -p "$OUTDIR"
for prog in $BUILD_ONLY; do
    [ -f "dropbear/$prog" ] && install -m 0755 "dropbear/$prog" "$OUTDIR/$prog"
done
install -m 0644 dropbear/LICENSE "$OUTDIR/LICENSE.txt"

echo "built $BUILD_ONLY (dropbear $DROPBEAR_VERSION) -> $OUTDIR"
ls -la "$OUTDIR"
