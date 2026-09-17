#!/usr/bin/env bash
# Compute PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM
# (URL-safe Base64 of SHA-256(DER certificate), no padding).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="${1:-$ROOT/app/build/outputs/apk/release/app-release.apk}"

hex_to_b64url() {
  python3 -c 'import binascii,base64,sys; h="".join(ch for ch in sys.stdin.read() if ch.isalnum()); print(base64.urlsafe_b64encode(binascii.unhexlify(h)).decode().rstrip("="))'
}

if [[ -f "$APK" ]]; then
  BUILD_TOOLS="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}/build-tools"
  APKSIGNER="$(ls -1d "$BUILD_TOOLS"/*/apksigner 2>/dev/null | sort | tail -1 || true)"
  if [[ -n "${APKSIGNER}" ]]; then
    HEX="$("$APKSIGNER" verify --print-certs "$APK" | awk -F': ' '/SHA-256 digest/{print $2; exit}')"
    echo "$HEX" | hex_to_b64url
    exit 0
  fi
fi

# Fallback: export the project-local release cert and hash it.
KEYSTORE="${KEYSTORE:-$ROOT/keystore/igni-release.jks}"
STOREPASS="${STOREPASS:-igni-dpc-local}"
ALIAS="${ALIAS:-igni}"
keytool -exportcert -alias "$ALIAS" -keystore "$KEYSTORE" -storepass "$STOREPASS" \
  | openssl dgst -binary -sha256 \
  | python3 -c 'import base64,sys; print(base64.urlsafe_b64encode(sys.stdin.buffer.read()).decode().rstrip("="))'
