Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$tempRoot = Join-Path $env:RUNNER_TEMP 'otphelper-device-evidence-smoke'
$fakeBin = Join-Path $tempRoot 'bin'
$output = Join-Path $tempRoot 'evidence'
New-Item -ItemType Directory -Path $fakeBin -Force | Out-Null

$fakeAdb = Join-Path $fakeBin 'adb'
$fakeAdbContent = @'
#!/usr/bin/env bash
set -euo pipefail

if [[ "${1:-}" == "devices" ]]; then
  printf 'List of devices attached\nFAKE-SERIAL-2026\tdevice\n'
  exit 0
fi
if [[ "${1:-}" == "get-state" ]]; then
  printf 'device\n'
  exit 0
fi
if [[ "${1:-}" == "get-serialno" ]]; then
  printf 'FAKE-SERIAL-2026\n'
  exit 0
fi
if [[ "${1:-}" == "logcat" ]]; then
  printf '%s\n' '08-04 12:00:00.000 4321 4321 I OTPHelper: OTP code: 123456 user@example.com +81 90-1234-5678 token=secret-token'
  exit 0
fi

if [[ "${1:-}" == "shell" ]]; then
  shift
  if [[ "${1:-}" == "pm" && "${2:-}" == "path" ]]; then
    printf 'package:/data/app/~~fake/io.github.jd1378.otphelper/base.apk\n'
    exit 0
  fi
  if [[ "${1:-}" == "getprop" ]]; then
    case "${2:-}" in
      ro.build.fingerprint) printf 'HONOR/DNP-NX9/fake:16/FAKE/20260804:user/release-keys\n' ;;
      ro.product.manufacturer) printf 'HONOR\n' ;;
      ro.product.model) printf 'DNP-NX9\n' ;;
      ro.build.version.sdk) printf '36\n' ;;
      ro.build.version.release) printf '16\n' ;;
      ro.build.version.security_patch) printf '2026-08-01\n' ;;
      *) printf '\n' ;;
    esac
    exit 0
  fi
  if [[ "${1:-}" == "pidof" ]]; then
    printf '4321\n'
    exit 0
  fi
  if [[ "${1:-}" == "settings" && "${2:-}" == "get" && "${3:-}" == "secure" ]]; then
    case "${4:-}" in
      enabled_notification_listeners)
        printf 'com.example.other/.Listener:io.github.jd1378.otphelper/.NotificationListener\n'
        ;;
      enabled_accessibility_services)
        printf 'com.example.reader/.Service:io.github.jd1378.otphelper/.AccessibilityNotificationService\n'
        ;;
      *) printf 'null\n' ;;
    esac
    exit 0
  fi
  if [[ "${1:-}" == "cmd" && "${2:-}" == "deviceidle" && "${3:-}" == "whitelist" ]]; then
    printf 'system,com.example.other\nuser,io.github.jd1378.otphelper\n'
    exit 0
  fi
  if [[ "${1:-}" == "cmd" && "${2:-}" == "appops" ]]; then
    printf 'RECEIVE_SENSITIVE_NOTIFICATIONS: allow\nPOST_NOTIFICATION: allow\n'
    exit 0
  fi
  if [[ "${1:-}" == "am" && "${2:-}" == "get-standby-bucket" ]]; then
    printf '10\n'
    exit 0
  fi
  if [[ "${1:-}" == "run-as" ]]; then
    printf 'files:\ntotal 4\n-rw------- 1 u0_a123 u0_a123 128 Aug 4 12:00 datastore.pb\ndatabases:\n-rw------- 1 u0_a123 u0_a123 4096 Aug 4 12:00 otp_history.db\n'
    exit 0
  fi
  if [[ "${1:-}" == "dumpsys" ]]; then
    printf 'fake dumpsys output for %s\n' "${2:-unknown}"
    exit 0
  fi
fi

printf 'unsupported fake adb invocation: %q' "$@" >&2
printf '\n' >&2
exit 2
'@
[System.IO.File]::WriteAllText($fakeAdb, $fakeAdbContent, [System.Text.UTF8Encoding]::new($false))
& chmod +x $fakeAdb
if ($LASTEXITCODE -ne 0) {
    throw 'Unable to mark fake adb executable.'
}

$originalPath = $env:PATH
try {
    $env:PATH = "$fakeBin$([System.IO.Path]::PathSeparator)$originalPath"
    ./tools/collect-otphelper-device-evidence.ps1 `
        -OutputDirectory $output `
        -TestLabel 'CI-HN-08-after-pass' `
        -IncludeRedactedLogcat `
        -Compress
}
finally {
    $env:PATH = $originalPath
}

$manifestPath = Join-Path $output 'evidence-manifest.json'
$digestPath = Join-Path $output 'evidence-manifest.sha256'
$archivePath = $output + '.zip'
foreach ($required in @($manifestPath, $digestPath, $archivePath)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Device evidence collector did not create required output: $required"
    }
}

$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
if ($manifest.schema -ne 'otphelper.device-evidence' -or $manifest.version -ne 1) {
    throw 'Device evidence manifest metadata is invalid.'
}
if ($manifest.testLabel -ne 'CI-HN-08-after-pass') {
    throw 'Device evidence manifest lost the supplied test label.'
}
if ($manifest.device.serial -eq 'FAKE-SERIAL-2026' -or $manifest.device.serial -notmatch '^sha256:[0-9a-f]{16}$') {
    throw 'Device serial was not pseudonymized by default.'
}
if (-not $manifest.privacy.logcatIncluded -or -not $manifest.privacy.logcatRedacted) {
    throw 'Device evidence manifest did not record redacted logcat inclusion.'
}
if ($manifest.privacy.unrelatedListenerAndAccessibilityPackagesCollected) {
    throw 'Device evidence manifest claims unrelated listener packages were collected.'
}
if ($manifest.privacy.unrelatedBatteryWhitelistPackagesCollected) {
    throw 'Device evidence manifest claims unrelated whitelist packages were collected.'
}

$listener = Get-Content -LiteralPath (Join-Path $output 'notification-listener-setting.txt') -Raw
$accessibility = Get-Content -LiteralPath (Join-Path $output 'accessibility-setting.txt') -Raw
$whitelist = Get-Content -LiteralPath (Join-Path $output 'battery-whitelist.txt') -Raw
foreach ($content in @($listener, $accessibility, $whitelist)) {
    if ($content -notmatch 'otpHelperPresent=True') {
        throw 'OTP Helper presence was not preserved in minimized setting evidence.'
    }
    if ($content -match 'com\.example\.') {
        throw 'Unrelated package names leaked into minimized setting evidence.'
    }
}

$logcat = Get-Content -LiteralPath (Join-Path $output 'redacted-logcat.txt') -Raw
foreach ($secret in @('123456', 'user@example.com', '+81 90-1234-5678', 'secret-token')) {
    if ($logcat.Contains($secret)) {
        throw "Sensitive fake logcat value was not redacted: $secret"
    }
}
if ($logcat -notmatch '<redacted-code>' -or $logcat -notmatch '<redacted-email>') {
    throw 'Expected code/email redaction markers were not emitted.'
}

$manifestFileNames = @($manifest.files | ForEach-Object { $_.name })
if ($manifestFileNames -contains 'evidence-manifest.json' -or $manifestFileNames -contains 'evidence-manifest.sha256') {
    throw 'Manifest attempted to self-hash recursively.'
}
foreach ($file in @($manifest.files)) {
    $path = Join-Path $output $file.name
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Manifest references a missing evidence file: $($file.name)"
    }
    $actual = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $file.sha256) {
        throw "Evidence file digest mismatch: $($file.name)"
    }
}

$digestLine = (Get-Content -LiteralPath $digestPath -Raw).Trim()
$manifestDigest = (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($digestLine -ne "$manifestDigest  evidence-manifest.json") {
    throw 'Final evidence manifest digest file is incorrect.'
}

Write-Host 'PASS Device evidence collector generated minimized, redacted, self-verifying output.'
