[CmdletBinding()]
param(
    [string]$OutputDirectory = (Join-Path $PWD (
        'otphelper-device-evidence-' + [DateTimeOffset]::Now.ToString('yyyyMMdd-HHmmss')
    )),

    [string]$Serial,

    [string]$TestLabel = 'manual-checkpoint',

    [switch]$IncludeRedactedLogcat,

    [switch]$IncludeDeviceSerial,

    [switch]$Compress
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$Package = 'io.github.jd1378.otphelper'
$ListenerComponents = @(
    "$Package/$Package.NotificationListener",
    "$Package/.NotificationListener"
)
$AccessibilityComponents = @(
    "$Package/$Package.AccessibilityNotificationService",
    "$Package/.AccessibilityNotificationService"
)
$Utf8NoBom = [System.Text.UTF8Encoding]::new($false)

$adbCommand = Get-Command adb -ErrorAction SilentlyContinue
if ($null -eq $adbCommand) {
    throw 'adb was not found in PATH. Install current Android SDK Platform Tools first.'
}

$script:AdbPath = $adbCommand.Source
$script:AdbPrefix = @()
if (-not [string]::IsNullOrWhiteSpace($Serial)) {
    $script:AdbPrefix = @('-s', $Serial)
}

function New-AdbProcess {
    param([Parameter(Mandatory)][string[]]$Arguments)

    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $script:AdbPath
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    foreach ($argument in @($script:AdbPrefix + $Arguments)) {
        [void]$startInfo.ArgumentList.Add($argument)
    }

    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    if (-not $process.Start()) {
        throw "Unable to start adb: adb $($Arguments -join ' ')"
    }
    return $process
}

function Invoke-AdbText {
    param(
        [Parameter(Mandatory)][string[]]$Arguments,
        [switch]$AllowFailure
    )

    $process = New-AdbProcess -Arguments $Arguments
    try {
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        $process.WaitForExit()
        $stdout = $stdoutTask.GetAwaiter().GetResult()
        $stderr = $stderrTask.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0 -and -not $AllowFailure) {
            throw "adb failed ($($process.ExitCode)): adb $($Arguments -join ' ')`n$stderr"
        }
        return [pscustomobject]@{
            ExitCode = $process.ExitCode
            StdOut = $stdout
            StdErr = $stderr
        }
    }
    finally {
        $process.Dispose()
    }
}

function Get-AdbText {
    param([Parameter(Mandatory)][string[]]$Arguments)
    return (Invoke-AdbText -Arguments $Arguments).StdOut.Trim()
}

function Assert-OneDevice {
    if (-not [string]::IsNullOrWhiteSpace($Serial)) {
        $state = Get-AdbText @('get-state')
        if ($state -ne 'device') {
            throw "ADB device '$Serial' is not ready: $state"
        }
        return
    }

    $deviceLines = Get-AdbText @('devices')
    $devices = @(
        ($deviceLines -split "`r?`n") |
            Where-Object { $_ -match "\tdevice$" }
    )
    if ($devices.Count -ne 1) {
        throw "Expected exactly one authorized ADB device, found $($devices.Count). Use -Serial when multiple devices are connected."
    }
}

function Assert-PackageInstalled {
    $result = Invoke-AdbText -Arguments @('shell', 'pm', 'path', $Package) -AllowFailure
    if ($result.ExitCode -ne 0 -or [string]::IsNullOrWhiteSpace($result.StdOut)) {
        throw "$Package is not installed on the selected device."
    }
}

function Protect-SensitiveText {
    param([AllowEmptyString()][string]$Text)

    $protected = $Text
    $protected = [regex]::Replace(
        $protected,
        '(?i)(authorization|bearer|token|secret|password)\s*[:=]\s*[^\s,;]+',
        '$1=<redacted>'
    )
    $protected = [regex]::Replace(
        $protected,
        '(?i)(otp|one[- ]?time|verification|security|auth|pass|pin|code)\D{0,20}\d{4,8}',
        '$1 <redacted-code>'
    )
    $protected = [regex]::Replace(
        $protected,
        '(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b',
        '<redacted-email>'
    )
    $protected = [regex]::Replace(
        $protected,
        '(?<!\w)\+?[0-9][0-9 ()-]{7,}[0-9](?!\w)',
        '<redacted-phone>'
    )
    $protected = [regex]::Replace(
        $protected,
        '(?<![\d.])\d{4,8}(?![\d.])',
        '<redacted-number>'
    )
    return $protected
}

function Write-Utf8File {
    param(
        [Parameter(Mandatory)][string]$Path,
        [AllowEmptyString()][string]$Content
    )

    [System.IO.File]::WriteAllText($Path, $Content, $Utf8NoBom)
}

function New-EvidenceRecord {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][int]$ExitCode,
        [Parameter(Mandatory)][string[]]$Arguments,
        [Parameter(Mandatory)][string]$Path
    )

    return [pscustomobject]@{
        name = $Name
        exitCode = $ExitCode
        path = [System.IO.Path]::GetFileName($Path)
        command = @($Arguments)
    }
}

function Save-CommandEvidence {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string[]]$Arguments,
        [switch]$Redact
    )

    $result = Invoke-AdbText -Arguments $Arguments -AllowFailure
    $body = @(
        "exitCode=$($result.ExitCode)",
        '--- stdout ---',
        $result.StdOut.TrimEnd(),
        '--- stderr ---',
        $result.StdErr.TrimEnd()
    ) -join "`n"
    if ($Redact) {
        $body = Protect-SensitiveText $body
    }

    $path = Join-Path $OutputDirectory ($Name + '.txt')
    Write-Utf8File -Path $path -Content ($body + "`n")
    return New-EvidenceRecord -Name $Name -ExitCode $result.ExitCode -Arguments $Arguments -Path $path
}

function Save-ColonSettingPresenceEvidence {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Setting,
        [Parameter(Mandatory)][string[]]$AcceptedComponents
    )

    $arguments = @('shell', 'settings', 'get', 'secure', $Setting)
    $result = Invoke-AdbText -Arguments $arguments -AllowFailure
    $trimmedSetting = $result.StdOut.Trim()
    $splitValues = $trimmedSetting -split ':'
    $values = @(
        $splitValues |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    )
    $matches = @($AcceptedComponents | Where-Object { $values -contains $_ })
    $body = @(
        "exitCode=$($result.ExitCode)",
        "otpHelperPresent=$($matches.Count -gt 0)",
        "matchedOtpHelperComponent=$($matches -join ',')",
        "stderr=$($result.StdErr.Trim())"
    ) -join "`n"

    $path = Join-Path $OutputDirectory ($Name + '.txt')
    Write-Utf8File -Path $path -Content ($body + "`n")
    return New-EvidenceRecord -Name $Name -ExitCode $result.ExitCode -Arguments $arguments -Path $path
}

function Save-PackageLinePresenceEvidence {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string[]]$Arguments
    )

    $result = Invoke-AdbText -Arguments $Arguments -AllowFailure
    $escapedPackage = [regex]::Escape($Package)
    $outputLines = $result.StdOut -split "`r?`n"
    $matchingLines = @(
        $outputLines |
            Where-Object { $_ -match "(^|,)$escapedPackage(,|$)" }
    )
    $body = @(
        "exitCode=$($result.ExitCode)",
        "otpHelperPresent=$($matchingLines.Count -gt 0)",
        '--- matching OTP Helper lines only ---',
        ($matchingLines -join "`n"),
        '--- stderr ---',
        $result.StdErr.TrimEnd()
    ) -join "`n"

    $path = Join-Path $OutputDirectory ($Name + '.txt')
    Write-Utf8File -Path $path -Content ($body + "`n")
    return New-EvidenceRecord -Name $Name -ExitCode $result.ExitCode -Arguments $Arguments -Path $path
}

function Get-Sha256Text {
    param([Parameter(Mandatory)][string]$Value)

    $bytes = [System.Text.Encoding]::UTF8.GetBytes($Value)
    $hash = [System.Security.Cryptography.SHA256]::HashData($bytes)
    return [Convert]::ToHexString($hash).ToLowerInvariant()
}

Assert-OneDevice
Assert-PackageInstalled

if (Test-Path -LiteralPath $OutputDirectory) {
    $existing = @(Get-ChildItem -LiteralPath $OutputDirectory -Force -ErrorAction SilentlyContinue)
    if ($existing.Count -gt 0) {
        throw "Evidence directory already exists and is not empty: $OutputDirectory"
    }
}
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null

$deviceSerial = Get-AdbText @('get-serialno')
$fingerprint = Get-AdbText @('shell', 'getprop', 'ro.build.fingerprint')
$manufacturer = Get-AdbText @('shell', 'getprop', 'ro.product.manufacturer')
$model = Get-AdbText @('shell', 'getprop', 'ro.product.model')
$sdk = Get-AdbText @('shell', 'getprop', 'ro.build.version.sdk')
$release = Get-AdbText @('shell', 'getprop', 'ro.build.version.release')
$securityPatch = Get-AdbText @('shell', 'getprop', 'ro.build.version.security_patch')

$commands = [System.Collections.Generic.List[object]]::new()
$commands.Add((Save-CommandEvidence 'package-path' @('shell', 'pm', 'path', $Package)))
$commands.Add((Save-CommandEvidence 'package-dump' @('shell', 'dumpsys', 'package', $Package)))
$commands.Add((Save-CommandEvidence 'process-id' @('shell', 'pidof', $Package)))
$commands.Add((Save-CommandEvidence 'activity-services' @('shell', 'dumpsys', 'activity', 'services', $Package)))
$commands.Add((Save-CommandEvidence 'activity-exit-info' @('shell', 'dumpsys', 'activity', 'exit-info', $Package)))
$commands.Add((Save-CommandEvidence 'jobscheduler' @('shell', 'dumpsys', 'jobscheduler', $Package)))
$commands.Add((Save-CommandEvidence 'alarms' @('shell', 'dumpsys', 'alarm', $Package)))
$commands.Add((Save-CommandEvidence 'app-ops' @('shell', 'cmd', 'appops', 'get', '--user', 'current', $Package)))
$commands.Add((Save-CommandEvidence 'sensitive-notification-appop' @(
    'shell', 'cmd', 'appops', 'get', '--user', 'current', $Package,
    'RECEIVE_SENSITIVE_NOTIFICATIONS'
)))

$listenerEvidence = @{
    Name = 'notification-listener-setting'
    Setting = 'enabled_notification_listeners'
    AcceptedComponents = $ListenerComponents
}
$commands.Add((Save-ColonSettingPresenceEvidence @listenerEvidence))

$accessibilityEvidence = @{
    Name = 'accessibility-setting'
    Setting = 'enabled_accessibility_services'
    AcceptedComponents = $AccessibilityComponents
}
$commands.Add((Save-ColonSettingPresenceEvidence @accessibilityEvidence))

$batteryWhitelistEvidence = @{
    Name = 'battery-whitelist'
    Arguments = @('shell', 'cmd', 'deviceidle', 'whitelist')
}
$commands.Add((Save-PackageLinePresenceEvidence @batteryWhitelistEvidence))

$commands.Add((Save-CommandEvidence 'standby-bucket' @(
    'shell', 'am', 'get-standby-bucket', $Package
)))
$commands.Add((Save-CommandEvidence 'battery-state' @('shell', 'dumpsys', 'battery')))
$commands.Add((Save-CommandEvidence 'private-file-layout' @(
    'shell', 'run-as', $Package, 'ls', '-laR',
    'files', 'databases', 'no_backup', 'shared_prefs'
) -Redact))

if ($IncludeRedactedLogcat) {
    $processIdResult = Invoke-AdbText -Arguments @('shell', 'pidof', '-s', $Package) -AllowFailure
    $otpHelperProcessId = $processIdResult.StdOut.Trim()
    if ($processIdResult.ExitCode -eq 0 -and $otpHelperProcessId -match '^\d+$') {
        $commands.Add((Save-CommandEvidence 'redacted-logcat' @(
            'logcat', '-d', '-v', 'threadtime', '--pid', $otpHelperProcessId
        ) -Redact))
    }
    else {
        $path = Join-Path $OutputDirectory 'redacted-logcat.txt'
        Write-Utf8File -Path $path -Content (
            "exitCode=$($processIdResult.ExitCode)`n" +
            "logcatCollected=false`n" +
            "reason=OTP Helper process is not currently running.`n"
        )
        $logcatRecord = @{
            Name = 'redacted-logcat'
            ExitCode = $processIdResult.ExitCode
            Arguments = @('logcat', '-d', '-v', 'threadtime', '--pid', '<otphelper-pid>')
            Path = $path
        }
        $commands.Add((New-EvidenceRecord @logcatRecord))
    }
}

$serialValue = if ($IncludeDeviceSerial) {
    $deviceSerial
}
else {
    'sha256:' + (Get-Sha256Text $deviceSerial).Substring(0, 16)
}

$manifest = [ordered]@{
    schema = 'otphelper.device-evidence'
    version = 1
    createdAt = [DateTimeOffset]::Now.ToString('o')
    testLabel = $TestLabel
    package = $Package
    device = [ordered]@{
        serial = $serialValue
        manufacturer = $manufacturer
        model = $model
        androidRelease = $release
        sdk = $sdk
        securityPatch = $securityPatch
        fingerprint = $fingerprint
    }
    privacy = [ordered]@{
        rawNotificationDatabaseCollected = $false
        broadNotificationDumpCollected = $false
        logcatIncluded = [bool]$IncludeRedactedLogcat
        logcatRedacted = [bool]$IncludeRedactedLogcat
        deviceSerialIncluded = [bool]$IncludeDeviceSerial
        unrelatedListenerAndAccessibilityPackagesCollected = $false
        unrelatedBatteryWhitelistPackagesCollected = $false
    }
    commands = @($commands)
    files = @()
}

$manifestPath = Join-Path $OutputDirectory 'evidence-manifest.json'
$fileRecords = @(
    Get-ChildItem -LiteralPath $OutputDirectory -File |
        Where-Object { $_.Name -ne 'evidence-manifest.json' -and $_.Name -ne 'evidence-manifest.sha256' } |
        Sort-Object Name |
        ForEach-Object {
            [ordered]@{
                name = $_.Name
                bytes = $_.Length
                sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
            }
        }
)
$manifest.files = $fileRecords
Write-Utf8File -Path $manifestPath -Content (($manifest | ConvertTo-Json -Depth 8) + "`n")

$manifestDigest = (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash.ToLowerInvariant()
$manifestDigestParameters = @{
    Path = (Join-Path $OutputDirectory 'evidence-manifest.sha256')
    Content = "$manifestDigest  evidence-manifest.json`n"
}
Write-Utf8File @manifestDigestParameters

Write-Host "Evidence collected: $OutputDirectory"
Write-Host "Device: $manufacturer $model / Android $release (API $sdk)"
Write-Host 'Notification contents and OTP database contents were not collected.'

if ($Compress) {
    $archivePath = $OutputDirectory.TrimEnd([System.IO.Path]::DirectorySeparatorChar) + '.zip'
    if (Test-Path -LiteralPath $archivePath) {
        throw "Evidence archive already exists: $archivePath"
    }
    Compress-Archive -Path (Join-Path $OutputDirectory '*') -DestinationPath $archivePath
    Write-Host "Evidence archive: $archivePath"
}
