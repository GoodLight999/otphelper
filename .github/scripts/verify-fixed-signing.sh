#!/usr/bin/env bash
set -euo pipefail

pinned_file="${OTPHELPER_PINNED_SIGNING_CERT_FILE:-.github/signing/otphelper-cert-sha256.txt}"
if [[ ! -s "$pinned_file" ]]; then
  echo "Pinned signing certificate file is missing or empty: $pinned_file" >&2
  exit 1
fi

expected="$(cat "$pinned_file" | tr -d ':[:space:]' | tr '[:upper:]' '[:lower:]')"
if [[ ! "$expected" =~ ^[0-9a-f]{64}$ ]]; then
  echo "Pinned signing certificate must be a 64-digit SHA-256 digest" >&2
  exit 1
fi

declared="${OTPHELPER_SIGNING_CERT_SHA256:-}"
if [[ -n "$declared" ]]; then
  declared="$(printf '%s' "$declared" | tr -d ':[:space:]' | tr '[:upper:]' '[:lower:]')"
  if [[ "$declared" != "$expected" ]]; then
    echo "Declared signing certificate conflicts with repository-pinned identity" >&2
    echo "pinned=$expected" >&2
    echo "declared=$declared" >&2
    exit 1
  fi
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

  if [[ "$actual" != "$expected" ]]; then
    echo "Signing certificate mismatch: $apk" >&2
    echo "pinned=$expected" >&2
    echo "actual=$actual" >&2
    exit 1
  fi

  echo "Pinned signing certificate verified: $apk ($actual)"
done
