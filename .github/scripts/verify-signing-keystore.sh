#!/usr/bin/env bash
set -euo pipefail

keystore="${OTPHELPER_KEYSTORE_PATH:?OTPHELPER_KEYSTORE_PATH is required}"
store_password="${OTPHELPER_KEYSTORE_PASSWORD:?OTPHELPER_KEYSTORE_PASSWORD is required}"
alias_name="${OTPHELPER_KEY_ALIAS:?OTPHELPER_KEY_ALIAS is required}"
expected="${OTPHELPER_SIGNING_CERT_SHA256:?OTPHELPER_SIGNING_CERT_SHA256 is required}"

expected="$(printf '%s' "$expected" | tr -d ':[:space:]' | tr '[:upper:]' '[:lower:]')"
if [[ ! "$expected" =~ ^[0-9a-f]{64}$ ]]; then
  echo "OTPHELPER_SIGNING_CERT_SHA256 must be a 64-hex certificate digest" >&2
  exit 1
fi
if [[ ! -s "$keystore" ]]; then
  echo "Permanent signing keystore is missing or empty: $keystore" >&2
  exit 1
fi

certificate="$(mktemp)"
cleanup() {
  rm -f "$certificate"
  unset OTPHELPER_KEYTOOL_PASSWORD
}
trap cleanup EXIT

export OTPHELPER_KEYTOOL_PASSWORD="$store_password"
keytool \
  -exportcert \
  -rfc \
  -keystore "$keystore" \
  -alias "$alias_name" \
  -storepass:env OTPHELPER_KEYTOOL_PASSWORD \
  -file "$certificate" \
  >/dev/null

actual="$(
  openssl x509 -in "$certificate" -noout -fingerprint -sha256 \
    | sed -n 's/^sha256 Fingerprint=//Ip' \
    | tr -d ':[:space:]' \
    | tr '[:upper:]' '[:lower:]'
)"
if [[ ! "$actual" =~ ^[0-9a-f]{64}$ ]]; then
  echo "Unable to derive SHA-256 from the permanent signing certificate" >&2
  exit 1
fi
if [[ "$actual" != "$expected" ]]; then
  echo "Permanent keystore certificate mismatch" >&2
  echo "expected=$expected" >&2
  echo "actual=$actual" >&2
  exit 1
fi

printf 'Permanent keystore verified: alias=%s certificateSha256=%s\n' "$alias_name" "$actual"
