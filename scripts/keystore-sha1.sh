#!/usr/bin/env bash
#
# Prints the SHA-1 (and SHA-256) certificate fingerprints of a keystore.
# You need the SHA-1 to create the Google OAuth "Android" client ID.
#
# RUN THIS ON YOUR OWN MACHINE, where the keystore lives.
#
# Usage:
#   ./scripts/keystore-sha1.sh [keystore-path] [alias]
set -euo pipefail

KS="${1:-$HOME/keystores/tracktrip-release.jks}"
ALIAS="${2:-tracktrip}"

if [ ! -e "$KS" ]; then
  echo "ERROR: keystore not found at $KS" >&2
  echo "Pass the path explicitly: ./scripts/keystore-sha1.sh /path/to/your.jks [alias]" >&2
  exit 1
fi

keytool -list -v -keystore "$KS" -alias "$ALIAS" | grep -E "SHA1:|SHA256:"
