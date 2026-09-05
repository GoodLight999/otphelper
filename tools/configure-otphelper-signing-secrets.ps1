[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$KeystorePath,

    [ValidatePattern('^[A-Za-z0-9._-]+$')]
    [string]$Alias = 'otphelper',

    [string]$Repository = 'GoodLight999/otphelper',

    [string]$PasswordEnvironmentVariable,

    [switch]$ConfirmConfigure
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-PlainText {
    param([Parameter(Mandatory)][securestring]$SecureValue)
    return [System.Net.NetworkCredential]::new('', $SecureValue).Password
}

function Assert-Command {
    param([Parameter(Mandatory)][string]$Name)
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($null -eq $command) {
        throw "$Name was not found in PATH."
    }
    return $command.Source
}

function Set-GitHubSecretFromText {
    param(
        [Parameter(Mandatory)][string]$GhPath,
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Value,
        [Parameter(Mandatory)][string]$TargetRepository
    )

    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $GhPath
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.Environment['GH_PROMPT_DISABLED'] = '1'
    foreach ($argument in @('secret', 'set', $Name, '--repo', $TargetRepository)) {
        [void]$startInfo.ArgumentList.Add($argument)
    }

    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    if (-not $process.Start()) {
        throw "Unable to start GitHub CLI while configuring $Name."
    }

    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    try {
        # Write the exact value without a trailing newline. Passwords and Base64 input are treated
        # as byte-sensitive transport data by the signing pipeline.
        $process.StandardInput.Write($Value)
        $process.StandardInput.Close()
        $process.WaitForExit()
        $stdout = $stdoutTask.GetAwaiter().GetResult().Trim()
        $stderr = $stderrTask.GetAwaiter().GetResult().Trim()
        if ($process.ExitCode -ne 0) {
            throw "gh secret set failed for $Name ($($process.ExitCode)):`n$stdout`n$stderr"
        }
    }
    finally {
        if (-not $process.HasExited) {
            $process.Kill($true)
        }
        $process.Dispose()
    }

    Write-Host "PASS GitHub Secret configured: $Name"
}

if (-not $ConfirmConfigure) {
    throw @"
Existing permanent signing Secret configuration is intentionally gated.
Verify that the supplied JKS is an operator backup of the already-pinned signer, then re-run with
-ConfirmConfigure. This command never generates or rotates an Android signing key.
"@
}

$resolvedKeystorePath = [System.IO.Path]::GetFullPath($KeystorePath)
if (-not (Test-Path -LiteralPath $resolvedKeystorePath -PathType Leaf)) {
    throw "Keystore does not exist: $resolvedKeystorePath"
}
if ((Get-Item -LiteralPath $resolvedKeystorePath).Length -le 0) {
    throw "Keystore is empty: $resolvedKeystorePath"
}

$repositoryPinPath = Join-Path $PSScriptRoot '..' '.github' 'signing' 'otphelper-cert-sha256.txt'
if (-not (Test-Path -LiteralPath $repositoryPinPath -PathType Leaf)) {
    throw "Repository signing pin is missing: $repositoryPinPath"
}
$expectedFingerprint = (Get-Content -LiteralPath $repositoryPinPath -Raw).Trim().ToLowerInvariant()
if ($expectedFingerprint -notmatch '^[0-9a-f]{64}$') {
    throw 'Repository signing pin must contain exactly one 64-hex SHA-256 fingerprint.'
}

$keytool = Assert-Command 'keytool'
$gh = Assert-Command 'gh'
& $gh auth status --hostname github.com
if ($LASTEXITCODE -ne 0) {
    throw 'GitHub CLI is not authenticated for github.com.'
}

if ([string]::IsNullOrWhiteSpace($PasswordEnvironmentVariable)) {
    $passwordSecure = Read-Host 'Enter the existing permanent signing password' -AsSecureString
    $password = Get-PlainText $passwordSecure
}
else {
    $password = [Environment]::GetEnvironmentVariable($PasswordEnvironmentVariable)
    if ([string]::IsNullOrWhiteSpace($password)) {
        throw "Password environment variable is missing or blank: $PasswordEnvironmentVariable"
    }
    Write-Host "Using password from environment variable '$PasswordEnvironmentVariable'."
}

if ([string]::IsNullOrWhiteSpace($password)) {
    throw 'The signing password must not be blank.'
}

$tempCertificate = Join-Path ([System.IO.Path]::GetTempPath()) ("otphelper-cert-{0}.pem" -f [Guid]::NewGuid())
$keystoreBase64 = $null
$env:OTPHELPER_KEYTOOL_PASSWORD = $password
try {
    & $keytool `
        -exportcert `
        -rfc `
        -keystore $resolvedKeystorePath `
        -alias $Alias `
        -file $tempCertificate `
        -storepass:env OTPHELPER_KEYTOOL_PASSWORD
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to open the JKS and export the requested signing certificate.'
    }

    $pem = Get-Content -LiteralPath $tempCertificate -Raw
    $certificate = [System.Security.Cryptography.X509Certificates.X509Certificate2]::CreateFromPem($pem)
    $actualFingerprint = $certificate.GetCertHashString(
        [System.Security.Cryptography.HashAlgorithmName]::SHA256
    ).ToLowerInvariant()

    if ($actualFingerprint -cne $expectedFingerprint) {
        throw @"
Refusing to configure GitHub Secrets because the supplied JKS is not the repository-pinned signer.
Expected: $expectedFingerprint
Actual:   $actualFingerprint
Do not change the repository pin to make this JKS pass. Restore the existing permanent signer instead.
"@
    }

    Write-Host "PASS permanent signer matches repository pin: $actualFingerprint"

    $keystoreBase64 = [Convert]::ToBase64String(
        [System.IO.File]::ReadAllBytes($resolvedKeystorePath)
    )
    if ([string]::IsNullOrWhiteSpace($keystoreBase64)) {
        throw 'Unable to encode the permanent JKS as Base64.'
    }

    # The fork's permanent signer was generated with one password for both the JKS and key entry.
    # Keep the four private values in sync with that established identity; the public fingerprint
    # remains repository-pinned and is intentionally not a mutable GitHub Secret.
    Set-GitHubSecretFromText $gh 'OTPHELPER_SIGNING_KEYSTORE_B64' $keystoreBase64 $Repository
    Set-GitHubSecretFromText $gh 'OTPHELPER_KEYSTORE_PASSWORD' $password $Repository
    Set-GitHubSecretFromText $gh 'OTPHELPER_KEY_ALIAS' $Alias $Repository
    Set-GitHubSecretFromText $gh 'OTPHELPER_KEY_PASSWORD' $password $Repository

    Write-Host 'PASS all four permanent signing Secrets configured.'
    Write-Host 'Re-run Android CI and require both fixed-signing verification steps to execute rather than skip.'
}
finally {
    Remove-Item Env:OTPHELPER_KEYTOOL_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $tempCertificate -Force -ErrorAction SilentlyContinue
    $password = $null
    $keystoreBase64 = $null
}
