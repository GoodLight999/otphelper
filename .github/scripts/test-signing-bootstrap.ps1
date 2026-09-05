[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not $env:RUNNER_TEMP) {
    throw 'RUNNER_TEMP is required for the signing bootstrap smoke test.'
}

$testPassword = 'ci-only-signing-smoke-password-2026'
$output = Join-Path $env:RUNNER_TEMP 'otphelper-signing-smoke'
$fakeGhDirectory = Join-Path $env:RUNNER_TEMP 'otphelper-fake-gh-bin'
$captureDirectory = Join-Path $env:RUNNER_TEMP 'otphelper-fake-gh-capture'
$decoded = Join-Path $env:RUNNER_TEMP 'otphelper-signing-smoke-decoded.jks'
$temporaryPin = Join-Path $env:RUNNER_TEMP 'otphelper-signing-smoke-pin.txt'

Remove-Item -LiteralPath $output, $fakeGhDirectory, $captureDirectory -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $decoded, $temporaryPin -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $fakeGhDirectory, $captureDirectory -Force | Out-Null

$fakeGh = Join-Path $fakeGhDirectory 'gh'
$fakeGhScript = @'
#!/usr/bin/env bash
set -euo pipefail

if [[ "${1:-}" == "auth" && "${2:-}" == "status" ]]; then
  exit 0
fi

if [[ "${1:-}" == "secret" && "${2:-}" == "set" ]]; then
  name="${3:?missing secret name}"
  if [[ "${4:-}" != "--repo" ]]; then
    echo "missing --repo" >&2
    exit 2
  fi
  repository="${5:?missing repository}"
  cat > "${OTPHELPER_FAKE_GH_CAPTURE:?}/${name}.value"
  printf '%s' "$repository" > "${OTPHELPER_FAKE_GH_CAPTURE}/${name}.repository"
  exit 0
fi

echo "unexpected fake gh invocation: $*" >&2
exit 2
'@
[System.IO.File]::WriteAllText(
    $fakeGh,
    $fakeGhScript,
    [System.Text.UTF8Encoding]::new($false)
)
& chmod 700 $fakeGh
if ($LASTEXITCODE -ne 0) {
    throw 'Unable to make the fake GitHub CLI executable.'
}

$originalPath = $env:PATH
$env:PATH = "$fakeGhDirectory$([System.IO.Path]::PathSeparator)$originalPath"
$env:OTPHELPER_TEST_SIGNING_PASSWORD = $testPassword
$env:OTPHELPER_FAKE_GH_CAPTURE = $captureDirectory
$env:OTPHELPER_SIGNING_BOOTSTRAP_TEST = '1'
try {
    ./tools/new-otphelper-signing-key.ps1 `
        -OutputDirectory $output `
        -ValidityYears 25 `
        -PasswordEnvironmentVariable OTPHELPER_TEST_SIGNING_PASSWORD `
        -Repository GoodLight999/otphelper `
        -ConfigureGitHubSecrets `
        -ConfirmCreate
}
finally {
    $env:PATH = $originalPath
    Remove-Item Env:OTPHELPER_TEST_SIGNING_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:OTPHELPER_FAKE_GH_CAPTURE -ErrorAction SilentlyContinue
    Remove-Item Env:OTPHELPER_SIGNING_BOOTSTRAP_TEST -ErrorAction SilentlyContinue
}

$manifest = Get-Content -LiteralPath (Join-Path $output 'manifest.json') -Raw |
    ConvertFrom-Json
if ($manifest.schema -ne 'otphelper.signing-bootstrap' -or $manifest.version -ne 2) {
    throw 'Signing bootstrap emitted invalid manifest metadata.'
}
if ($manifest.alias -ne 'otphelper') {
    throw 'Signing bootstrap emitted an unexpected key alias.'
}

$fingerprint = (Get-Content -LiteralPath (
    Join-Path $output 'otphelper-signing-certificate-sha256.txt'
) -Raw).Trim()
if ($fingerprint -notmatch '^[0-9a-f]{64}$') {
    throw 'Signing bootstrap emitted an invalid certificate SHA-256.'
}
if ($manifest.certificateSha256 -ne $fingerprint) {
    throw 'Signing bootstrap manifest fingerprint does not match the fingerprint file.'
}

$keystore = Join-Path $output 'otphelper-permanent-signing.jks'
$encoded = (Get-Content -LiteralPath (
    Join-Path $output 'otphelper-signing-keystore-base64.txt'
) -Raw).Trim()
[System.IO.File]::WriteAllBytes($decoded, [Convert]::FromBase64String($encoded))
$originalHash = (Get-FileHash -LiteralPath $keystore -Algorithm SHA256).Hash
$decodedHash = (Get-FileHash -LiteralPath $decoded -Algorithm SHA256).Hash
if ($originalHash -ne $decodedHash) {
    throw 'Base64 keystore output does not reconstruct the generated JKS.'
}

$env:OTPHELPER_KEYTOOL_PASSWORD = $testPassword
try {
    & keytool `
        -list `
        -keystore $keystore `
        -alias otphelper `
        -storepass:env OTPHELPER_KEYTOOL_PASSWORD
    if ($LASTEXITCODE -ne 0) {
        throw 'keytool could not reopen the generated JKS.'
    }
}
finally {
    Remove-Item Env:OTPHELPER_KEYTOOL_PASSWORD -ErrorAction SilentlyContinue
}

# The production verifier must always compare against a pin. This smoke test generates an isolated
# throwaway JKS, so provide an isolated throwaway pin rather than weakening production verification.
[System.IO.File]::WriteAllText(
    $temporaryPin,
    $fingerprint,
    [System.Text.UTF8Encoding]::new($false)
)
$env:OTPHELPER_KEYSTORE_PATH = $keystore
$env:OTPHELPER_KEYSTORE_PASSWORD = $testPassword
$env:OTPHELPER_KEY_ALIAS = 'otphelper'
$env:OTPHELPER_PINNED_SIGNING_CERT_FILE = $temporaryPin
try {
    & bash ./.github/scripts/verify-signing-keystore.sh
    if ($LASTEXITCODE -ne 0) {
        throw 'Permanent-keystore verifier rejected the generated JKS.'
    }
}
finally {
    Remove-Item Env:OTPHELPER_KEYSTORE_PATH -ErrorAction SilentlyContinue
    Remove-Item Env:OTPHELPER_KEYSTORE_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:OTPHELPER_KEY_ALIAS -ErrorAction SilentlyContinue
    Remove-Item Env:OTPHELPER_PINNED_SIGNING_CERT_FILE -ErrorAction SilentlyContinue
}

$expectedSecrets = [ordered]@{
    OTPHELPER_SIGNING_KEYSTORE_B64 = $encoded
    OTPHELPER_KEYSTORE_PASSWORD = $testPassword
    OTPHELPER_KEY_ALIAS = 'otphelper'
    OTPHELPER_KEY_PASSWORD = $testPassword
}
foreach ($entry in $expectedSecrets.GetEnumerator()) {
    $valuePath = Join-Path $captureDirectory "$($entry.Key).value"
    $repositoryPath = Join-Path $captureDirectory "$($entry.Key).repository"
    if (-not (Test-Path -LiteralPath $valuePath -PathType Leaf)) {
        throw "Fake GitHub CLI did not receive Secret $($entry.Key)."
    }
    $actualBytes = [System.IO.File]::ReadAllBytes($valuePath)
    $expectedBytes = [System.Text.Encoding]::UTF8.GetBytes([string]$entry.Value)
    $actualEncoded = [Convert]::ToBase64String($actualBytes)
    $expectedEncoded = [Convert]::ToBase64String($expectedBytes)
    if ($actualBytes.Length -ne $expectedBytes.Length -or $actualEncoded -cne $expectedEncoded) {
        throw "Secret $($entry.Key) changed in transit or gained a trailing newline."
    }
    $repository = [System.IO.File]::ReadAllText($repositoryPath)
    if ($repository -cne 'GoodLight999/otphelper') {
        throw "Secret $($entry.Key) was sent to an unexpected repository: $repository"
    }
}

$unexpectedLegacySecret = Join-Path $captureDirectory 'OTPHELPER_SIGNING_CERT_SHA256.value'
if (Test-Path -LiteralPath $unexpectedLegacySecret -PathType Leaf) {
    throw 'Certificate identity must be repository-pinned, not transported as a mutable Secret.'
}

# Exercise the post-bootstrap path that operators actually use now that the permanent identity
# already exists. First prove that a JKS that does not match the committed production pin is refused
# before any Secret write. Then temporarily substitute the throwaway pin inside this isolated CI
# checkout and prove exact four-Secret transport on the success path. The original pin is restored in
# a finally block before any later CI verification can observe the test value.
$repositoryPin = Join-Path $PSScriptRoot '..' 'signing' 'otphelper-cert-sha256.txt'
$productionPinBytes = [System.IO.File]::ReadAllBytes($repositoryPin)
Get-ChildItem -LiteralPath $captureDirectory -File -ErrorAction SilentlyContinue |
    Remove-Item -Force

$env:PATH = "$fakeGhDirectory$([System.IO.Path]::PathSeparator)$originalPath"
$env:OTPHELPER_TEST_SIGNING_PASSWORD = $testPassword
$env:OTPHELPER_FAKE_GH_CAPTURE = $captureDirectory
try {
    $mismatchRejected = $false
    try {
        ./tools/configure-otphelper-signing-secrets.ps1 `
            -KeystorePath $keystore `
            -PasswordEnvironmentVariable OTPHELPER_TEST_SIGNING_PASSWORD `
            -Repository GoodLight999/otphelper `
            -ConfirmConfigure
    }
    catch {
        $mismatchRejected = $_.Exception.Message -like '*not the repository-pinned signer*'
        if (-not $mismatchRejected) {
            throw
        }
    }
    if (-not $mismatchRejected) {
        throw 'Existing-signer configurator accepted a JKS that does not match the production pin.'
    }
    if (@(Get-ChildItem -LiteralPath $captureDirectory -File -ErrorAction SilentlyContinue).Count -ne 0) {
        throw 'Existing-signer configurator wrote a Secret before rejecting a mismatched JKS.'
    }

    [System.IO.File]::WriteAllText(
        $repositoryPin,
        $fingerprint,
        [System.Text.UTF8Encoding]::new($false)
    )

    ./tools/configure-otphelper-signing-secrets.ps1 `
        -KeystorePath $keystore `
        -PasswordEnvironmentVariable OTPHELPER_TEST_SIGNING_PASSWORD `
        -Repository GoodLight999/otphelper `
        -ConfirmConfigure
}
finally {
    [System.IO.File]::WriteAllBytes($repositoryPin, $productionPinBytes)
    $env:PATH = $originalPath
    Remove-Item Env:OTPHELPER_TEST_SIGNING_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:OTPHELPER_FAKE_GH_CAPTURE -ErrorAction SilentlyContinue
}

foreach ($entry in $expectedSecrets.GetEnumerator()) {
    $valuePath = Join-Path $captureDirectory "$($entry.Key).value"
    $repositoryPath = Join-Path $captureDirectory "$($entry.Key).repository"
    if (-not (Test-Path -LiteralPath $valuePath -PathType Leaf)) {
        throw "Existing-signer configurator did not send Secret $($entry.Key)."
    }
    $actualBytes = [System.IO.File]::ReadAllBytes($valuePath)
    $expectedBytes = [System.Text.Encoding]::UTF8.GetBytes([string]$entry.Value)
    if (
        $actualBytes.Length -ne $expectedBytes.Length -or
        [Convert]::ToBase64String($actualBytes) -cne [Convert]::ToBase64String($expectedBytes)
    ) {
        throw "Existing-signer Secret $($entry.Key) changed in transit or gained a trailing newline."
    }
    if ([System.IO.File]::ReadAllText($repositoryPath) -cne 'GoodLight999/otphelper') {
        throw "Existing-signer Secret $($entry.Key) was sent to the wrong repository."
    }
}
if (Test-Path -LiteralPath $unexpectedLegacySecret -PathType Leaf) {
    throw 'Existing-signer configurator must not transport the certificate fingerprint as a Secret.'
}
if ((Get-Content -LiteralPath $repositoryPin -Raw).Trim().ToLowerInvariant() -eq $fingerprint) {
    throw 'Signing contract test failed to restore the production repository pin.'
}

Write-Host 'Signing bootstrap and existing-signer configuration verified: pin rejection, JKS, certificate, Base64, and exact four-Secret transport.'
