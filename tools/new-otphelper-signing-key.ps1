[CmdletBinding()]
param(
    [string]$OutputDirectory = (Join-Path $PWD 'otphelper-signing-output'),

    [ValidatePattern('^[A-Za-z0-9._-]+$')]
    [string]$Alias = 'otphelper',

    [string]$DistinguishedName = 'CN=OTP Helper Fork, OU=GoodLight999, O=GoodLight999, C=JP',

    [ValidateRange(25, 100)]
    [int]$ValidityYears = 50,

    [string]$Repository = 'GoodLight999/otphelper',

    [string]$PasswordEnvironmentVariable,

    [switch]$ConfigureGitHubSecrets,

    [switch]$ConfirmCreate
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
        # Write the exact value. PowerShell's normal pipeline appends a record terminator, which is
        # unacceptable for keystore passwords and other byte-sensitive signing inputs.
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

if (-not $ConfirmCreate) {
    throw @"
Permanent signing-key creation is intentionally gated.
Review docs/SIGNING_MIGRATION.md, choose two independent backup locations, then re-run with -ConfirmCreate.
"@
}

# Once a public certificate pin exists, this repository already has a permanent Android update
# identity. Generating a second private key is almost always destructive because Android treats it
# as a different application signer. The bootstrap contract test is the sole automated bypass and
# deliberately uses an isolated throwaway directory.
$repositoryPinPath = Join-Path $PSScriptRoot '..' '.github' 'signing' 'otphelper-cert-sha256.txt'
$bootstrapContractTest = $env:OTPHELPER_SIGNING_BOOTSTRAP_TEST -ceq '1'
if ((Test-Path -LiteralPath $repositoryPinPath -PathType Leaf) -and -not $bootstrapContractTest) {
    $existingPin = (Get-Content -LiteralPath $repositoryPinPath -Raw).Trim()
    if ($existingPin -match '^[0-9A-Fa-f]{64}$') {
        throw @"
A permanent signing identity is already pinned for this repository:
$($existingPin.ToLowerInvariant())

Refusing to generate a replacement private key. Use the existing GitHub Actions signing Secrets
or restore the operator backup for that certificate. Deliberate signer rotation requires a separate
migration procedure and must not be performed with this bootstrap command.
"@
    }
}

$keytool = Assert-Command 'keytool'
$gh = $null
if ($ConfigureGitHubSecrets) {
    $gh = Assert-Command 'gh'
    & $gh auth status --hostname github.com
    if ($LASTEXITCODE -ne 0) {
        throw 'GitHub CLI is not authenticated for github.com.'
    }
}

$outputPath = [System.IO.Path]::GetFullPath($OutputDirectory)
$targetFiles = @(
    'otphelper-permanent-signing.jks',
    'otphelper-signing-certificate.pem',
    'otphelper-signing-certificate-sha256.txt',
    'otphelper-signing-keystore-base64.txt',
    'SHA256SUMS.txt',
    'manifest.json',
    'README.txt'
)

if (Test-Path -LiteralPath $outputPath) {
    $existing = @(Get-ChildItem -LiteralPath $outputPath -Force -ErrorAction SilentlyContinue)
    if ($existing.Count -gt 0) {
        throw "Output directory is not empty. Refusing to overwrite signing material: $outputPath"
    }
}
else {
    New-Item -ItemType Directory -Path $outputPath -Force | Out-Null
}

$keystorePath = Join-Path $outputPath $targetFiles[0]
$certificatePath = Join-Path $outputPath $targetFiles[1]
$fingerprintPath = Join-Path $outputPath $targetFiles[2]
$base64Path = Join-Path $outputPath $targetFiles[3]
$checksumsPath = Join-Path $outputPath $targetFiles[4]
$manifestPath = Join-Path $outputPath $targetFiles[5]
$readmePath = Join-Path $outputPath $targetFiles[6]

if ([string]::IsNullOrWhiteSpace($PasswordEnvironmentVariable)) {
    $passwordSecure = Read-Host 'Enter one strong password for the keystore and key' -AsSecureString
    $passwordConfirmSecure = Read-Host 'Enter the same password again' -AsSecureString
    $password = Get-PlainText $passwordSecure
    $passwordConfirm = Get-PlainText $passwordConfirmSecure
}
else {
    $password = [Environment]::GetEnvironmentVariable($PasswordEnvironmentVariable)
    if ([string]::IsNullOrWhiteSpace($password)) {
        throw "Password environment variable is missing or blank: $PasswordEnvironmentVariable"
    }
    $passwordConfirm = $password
    Write-Host "Using password from environment variable '$PasswordEnvironmentVariable'."
}

if ([string]::IsNullOrWhiteSpace($password)) {
    throw 'The signing password must not be blank.'
}
if ($password.Length -lt 16) {
    throw 'Use a signing password of at least 16 characters.'
}
if ($password -cne $passwordConfirm) {
    throw 'The two signing passwords do not match.'
}

$validityDays = $ValidityYears * 365
$created = $false
$keystoreBase64 = $null
$env:OTPHELPER_KEYTOOL_PASSWORD = $password
try {
    & $keytool `
        -genkeypair `
        -v `
        -keystore $keystorePath `
        -storetype JKS `
        -alias $Alias `
        -keyalg RSA `
        -keysize 4096 `
        -validity $validityDays `
        -dname $DistinguishedName `
        -storepass:env OTPHELPER_KEYTOOL_PASSWORD `
        -keypass:env OTPHELPER_KEYTOOL_PASSWORD `
        -noprompt
    if ($LASTEXITCODE -ne 0) {
        throw 'keytool failed to generate the permanent signing key.'
    }
    $created = $true

    & $keytool `
        -exportcert `
        -rfc `
        -keystore $keystorePath `
        -alias $Alias `
        -file $certificatePath `
        -storepass:env OTPHELPER_KEYTOOL_PASSWORD
    if ($LASTEXITCODE -ne 0) {
        throw 'keytool failed to export the signing certificate.'
    }
}
finally {
    Remove-Item Env:OTPHELPER_KEYTOOL_PASSWORD -ErrorAction SilentlyContinue
    $passwordConfirm = $null
}

try {
    $pem = Get-Content -LiteralPath $certificatePath -Raw
    $certificate = [System.Security.Cryptography.X509Certificates.X509Certificate2]::CreateFromPem($pem)
    $fingerprint = $certificate.GetCertHashString(
        [System.Security.Cryptography.HashAlgorithmName]::SHA256
    ).ToLowerInvariant()
    if ($fingerprint -notmatch '^[0-9a-f]{64}$') {
        throw 'Unable to derive a valid SHA-256 certificate fingerprint.'
    }

    [System.IO.File]::WriteAllText(
        $fingerprintPath,
        $fingerprint,
        [System.Text.UTF8Encoding]::new($false)
    )

    $keystoreBase64 = [Convert]::ToBase64String(
        [System.IO.File]::ReadAllBytes($keystorePath)
    )
    [System.IO.File]::WriteAllText(
        $base64Path,
        $keystoreBase64,
        [System.Text.UTF8Encoding]::new($false)
    )

    $checksumLines = @(
        "$(Get-FileHash -LiteralPath $keystorePath -Algorithm SHA256 | Select-Object -ExpandProperty Hash)  $([System.IO.Path]::GetFileName($keystorePath))",
        "$(Get-FileHash -LiteralPath $certificatePath -Algorithm SHA256 | Select-Object -ExpandProperty Hash)  $([System.IO.Path]::GetFileName($certificatePath))"
    ) | ForEach-Object { $_.ToLowerInvariant() }
    [System.IO.File]::WriteAllLines(
        $checksumsPath,
        $checksumLines,
        [System.Text.UTF8Encoding]::new($false)
    )

    $manifest = [ordered]@{
        schema = 'otphelper.signing-bootstrap'
        version = 2
        createdAt = [DateTimeOffset]::Now.ToString('o')
        repository = $Repository
        alias = $Alias
        distinguishedName = $DistinguishedName
        validityYears = $ValidityYears
        certificateSha256 = $fingerprint
        keystoreFile = [System.IO.Path]::GetFileName($keystorePath)
        certificateFile = [System.IO.Path]::GetFileName($certificatePath)
    }
    $manifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $manifestPath -Encoding utf8NoBOM

    $localInstructions = @"
OTP Helper permanent signing material

Certificate SHA-256:
$fingerprint

Required GitHub Actions Secrets:
OTPHELPER_SIGNING_KEYSTORE_B64       <- contents of otphelper-signing-keystore-base64.txt
OTPHELPER_KEYSTORE_PASSWORD          <- the password entered during generation
OTPHELPER_KEY_ALIAS                  <- $Alias
OTPHELPER_KEY_PASSWORD               <- the same password entered during generation

The expected certificate identity is NOT a Secret. It is pinned publicly in:
.github/signing/otphelper-cert-sha256.txt

Do not commit, email, or upload this directory as an ordinary public attachment.
Create at least two independent private backups before deleting any copy.
The certificate and fingerprint may be shared; the JKS and password must remain private.
See docs/SIGNING_MIGRATION.md before replacing the currently installed APK.
"@
    [System.IO.File]::WriteAllText(
        $readmePath,
        $localInstructions,
        [System.Text.UTF8Encoding]::new($false)
    )

    if (-not $IsWindows) {
        & chmod 700 $outputPath
        & chmod 600 $keystorePath $base64Path $manifestPath $readmePath $checksumsPath
        & chmod 644 $certificatePath $fingerprintPath
    }

    if ($ConfigureGitHubSecrets) {
        Set-GitHubSecretFromText $gh 'OTPHELPER_SIGNING_KEYSTORE_B64' $keystoreBase64 $Repository
        Set-GitHubSecretFromText $gh 'OTPHELPER_KEYSTORE_PASSWORD' $password $Repository
        Set-GitHubSecretFromText $gh 'OTPHELPER_KEY_ALIAS' $Alias $Repository
        Set-GitHubSecretFromText $gh 'OTPHELPER_KEY_PASSWORD' $password $Repository
    }

    Write-Host "Permanent signing material created: $outputPath"
    Write-Host "Certificate SHA-256: $fingerprint"
    Write-Warning 'Do not uninstall the current OTP Helper APK yet. Back it up first with otphelper-adb-migration.ps1.'
}
catch {
    if ($created) {
        Write-Warning "Signing material was created but post-generation processing failed: $($_.Exception.Message)"
        Write-Warning "Inspect and securely remove the incomplete directory if it will not be used: $outputPath"
    }
    throw
}
finally {
    $password = $null
    $keystoreBase64 = $null
}
