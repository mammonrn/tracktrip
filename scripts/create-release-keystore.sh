#!/usr/bin/env bash
#
# Creates the release signing keystore for the Tracktrip Android app.
#
# RUN THIS ON YOUR OWN MACHINE — never in CI, never in a shared/remote shell,
# and never inside this repository's working tree.
#
# The keystore is the private key that proves a release APK is genuinely from
# you. If you lose it you can never ship an update to an already-published app
# again (Play Store rejects a differently-signed APK for the same package).
# If someone else obtains it they can publish an app that Android trusts as
# yours. So: keep it secret, keep a backup, and keep it out of git.
#
# Usage:
#   ./scripts/create-release-keystore.sh [output-path]
#
# Default output path is ~/keystores/tracktrip-release.jks
set -euo pipefail

OUT="${1:-$HOME/keystores/tracktrip-release.jks}"
ALIAS="tracktrip"
# 27 years. Google Play requires a certificate valid through at least
# 2033-10-22; 27 years comfortably clears that and is the long-standing
# convention for Android release keys.
DAYS=9855

if [ -e "$OUT" ]; then
  echo "ERROR: $OUT already exists. Refusing to overwrite an existing keystore." >&2
  echo "If you really mean to replace it, move the old one aside first." >&2
  exit 1
fi

mkdir -p "$(dirname "$OUT")"

echo "Creating release keystore at: $OUT"
echo "Alias: $ALIAS   Validity: $DAYS days (~27 years)   Key: RSA 4096"
echo
echo "You'll be asked for a password (twice) and some identifying details."
echo "Use a strong, unique password and store it in your password manager —"
echo "it cannot be recovered if lost."
echo

# keytool warns that JKS is a proprietary format and suggests PKCS12. That
# warning is harmless here — Android signs fine with JKS. Swap in
# -storetype PKCS12 if you prefer the modern format; both work with Gradle.
keytool -genkeypair \
  -v \
  -keystore "$OUT" \
  -alias "$ALIAS" \
  -keyalg RSA \
  -keysize 4096 \
  -validity "$DAYS" \
  -storetype JKS

chmod 600 "$OUT"

echo
echo "Done. Keystore written to $OUT (permissions set to 600)."
echo
echo "NEXT STEPS"
echo "  1. Back it up somewhere private and durable (password manager vault,"
echo "     encrypted drive). Losing it means you can never update the app."
echo "  2. Do NOT copy it into this repo. *.jks is gitignored, but the safest"
echo "     place is outside the repository entirely."
echo "  3. Get the SHA-1 fingerprint for creating the Google OAuth Android"
echo "     client (see ./scripts/keystore-sha1.sh, or run the one-liner in"
echo "     android/README.md)."
