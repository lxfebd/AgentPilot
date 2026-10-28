#!/usr/bin/env bash
# Build a signed (or, without properties, unsigned) release APK and print the
# artifact facts the release checklist needs: path, size, SHA-256, and the
# signer certificate fingerprints.
#
# Usage:
#   ./release-build.sh
#
# Signing properties (never commit these; pass on the command line or keep them
# in ~/.gradle/gradle.properties, which is gitignored):
#   agentPilot.releaseStoreFile      absolute path to the release keystore (.jks)
#   agentPilot.releaseStorePassword  keystore password
#   agentPilot.releaseKeyAlias       key alias inside the keystore
#   agentPilot.releaseKeyPassword    key password
#
# Without those properties the script still produces the unsigned APK and
# fails loudly, so an accidental publish of an unsigned build is impossible
# to miss.
set -euo pipefail
cd "$(dirname "$0")"

echo "== building release APK =="
./gradlew :app:assembleRelease "$@"

APK="app/build/outputs/apk/release/app-release.apk"
if [[ ! -f "$APK" ]]; then
    echo "FAIL: expected artifact not found at $APK" >&2
    exit 1
fi

echo
echo "== artifact =="
ls -l "$APK" | awk '{print "size:", $5, "bytes"}'
if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$APK" | awk '{print "sha256:", $1}'
fi

echo
echo "== signature check =="
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/d/android/android-sdk}}"
APKSIGNER="$SDK/build-tools/36.0.0/apksigner"
if [[ ! -x "$APKSIGNER" ]]; then
    APKSIGNER="$(ls -1 "$SDK"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)"
fi
if [[ -n "$APKSIGNER" && -x "$APKSIGNER" ]]; then
    if "$APKSIGNER" verify --print-certs "$APK" | head -8; then
        echo
        echo "SIGNED: the release APK carries a signer."
        echo "Compare the SHA-256 digest above with the certificate fingerprint of"
        echo "the currently installed app before publishing: the update flow refuses"
        echo "APKs signed by a different key."
    else
        echo
        echo "NOT SIGNED (or unverifiable): do NOT publish this build."
        echo "Pass the agentPilot.release* properties to produce a signed APK."
        exit 2
    fi
else
    echo "apksigner not found under $SDK/build-tools; signature not verified."
    echo "Install build-tools or verify with Android Studio before publishing."
    exit 3
fi
