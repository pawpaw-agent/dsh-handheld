/*
 * dsh-handheld localoptions.h for Dropbear dbclient (Android).
 *
 * Based on the MIT-licensed localoptions.h from ribbons/android-dropbear
 * (https://github.com/ribbons/android-dropbear, SPDX-License-Identifier: MIT),
 * adjusted for the dsh-handheld use case:
 *   - Client auth via the DROPBEAR_PASSWORD environment variable. The
 *     cli-auth.c patch (scripts/build-dropbear.sh) makes getenv() the only
 *     password path (getpass() doesn't exist on Android), so credentials
 *     work exactly like a plain Termux `ssh user@host`.
 *   - Server password auth disabled: crypt() is unavailable on Android.
 */

// Disable server password auth as crypt() isn't available under Android
#define DROPBEAR_SVR_PASSWORD_AUTH 0

// Client auth: password via DROPBEAR_PASSWORD env (with the getpass patch),
// plus public key auth for key-based setups.
#define DROPBEAR_CLI_PASSWORD_AUTH 1
#define DROPBEAR_USE_PASSWORD_ENV 1
#define DROPBEAR_CLI_PUBKEY_AUTH 1

// Speed up symmetrical ciphers and hashes at the expense of larger binaries
#define DROPBEAR_SMALL_CODE 0

// Include non-standard shell path prior to Android 11 in default shell list
#if __ANDROID_MIN_SDK_VERSION__ > 30
    #error "COMPAT_USER_SHELLS override no longer needed"
#endif
#define COMPAT_USER_SHELLS "/bin/sh","/system/bin/sh"
