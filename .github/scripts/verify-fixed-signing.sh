#!/usr/bin/env bash
set -euo pipefail

expected="${OTPHELPER_SIGNING_CERT_SHA256:-}"
expected="$(printf '%s' "$expected" | tr -d ':[:space:]' | tr '[:upper:]' '[:lower:]')"
if [[ ! "$expected" =~ ^[0-9a-f]{64}$ ]]; then
  echo "OTPHELPER_SIGNING_CERT_SHA256 must be a 64-digit SHA-256 certificate digest" >&2
  exit 1
fi

apksigner=""
while IFS= read -r candidate; do
  apksigner="$candidate"
done < <(find "${ANDROID_SDK_ROOT:?}/build-tools" -type f -name apksigner | sort -V)

if [[ -z "$apksigner" ]]; then
  echo "apksigner was not found under ANDROID_SDK_ROOT/build-tools" >&2
  exit 1
fi

mapfile -t apks < <(find app/build/outputs/apk -type f -name '*.apk' -print | sort)
if (( ${#apks[@]} == 0 )); then
  echo "No APKs were found" >&2
  exit 1
fi

for apk in "${apks[@]}"; do
  output="$($apksigner verify --print-certs "$apk")"
  actual="$(printf '%s\n' "$output" \
    | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' \
    | head -n 1 \
    | tr -d ':[:space:]' \
    | tr '[:upper:]' '[:lower:]')"

  if "$apksigner" verify --verbose "$apk" | grep -q '^Verified using v1 scheme (JAR signing): false$'; then
    : # v1 is optional on modern Android; the certificate check below is authoritative.
  fi

  if [[ "$actual" != "$expected" ]]; then
    echo "Signing certificate mismatch: $apk" >&2
    echo "expected=$expected" >&2
    echo "actual=$actual" >&2
    exit 1
  fi

  echo "Fixed signing certificate verified: $apk ($actual)"
done
