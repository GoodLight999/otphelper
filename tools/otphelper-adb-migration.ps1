[CmdletBinding()]
param(
    [ValidateSet('Status', 'Backup', 'Restore')]
    [string]$Action = 'Status',

    [string]$BackupDirectory = (Join-Path $PWD 'otphelper-adb-backup'),

    [string]$Serial,

    [string]$ExpectedCertificateSha256,

    [switch]$ConfirmRestore
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$Package = 'io.github.jd1378.otphelper'
$ListenerComponent = "$Package/$Package.NotificationListener"
$ListenerComponentShort = "$Package/.NotificationListener"
$AccessibilityComponent = "$Package/$Package.AccessibilityNotificationService"
$AccessibilityComponentShort = "$Package/.AccessibilityNotificationService"
$ArchiveName = 'app-data.tar'
$MetadataName = 'metadata.json'
$PackageDumpName = 'package-dump.txt'

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
    param(
        [Parameter(Mandatory)][string[]]$Arguments,
        [switch]$RedirectStandardInput
    )

    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $script:AdbPath
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.RedirectStandardInput = $RedirectStandardInput.IsPresent
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
        $stdout = $stdoutTask.GetAwaiter().GetResult().Trim()
        $stderr = $stderrTask.GetAwaiter().GetResult().Trim()
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
    return (Invoke-AdbText -Arguments $Arguments).StdOut
}

function Assert-OneDevice {
    if (-not [string]::IsNullOrWhiteSpace($Serial)) {
        $state = Get-AdbText @('get-state')
        if ($state -ne 'device') {
            throw "ADB device '$Serial' is not ready: $state"
        }
        return
    }

    $devices = @(Get-AdbText @('devices') -split "`r?`n" | Where-Object { $_ -match "\tdevice$" })
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

function Assert-RunAsAvailable {
    $result = Invoke-AdbText -Arguments @('shell', 'run-as', $Package, 'id') -AllowFailure
    if ($result.ExitCode -ne 0 -or $result.StdOut -notmatch 'uid=') {
        throw @"
run-as cannot access $Package. The installed APK must be debuggable.
For restoration, install the permanent-key DEBUG APK first; do not install a non-debuggable release APK until restoration is complete.
$($result.StdErr)
"@
    }
}

function Normalize-CertificateSha256 {
    param([Parameter(Mandatory)][string]$Value)

    $normalized = ($Value -replace '[:\s]', '').ToLowerInvariant()
    if ($normalized -notmatch '^[0-9a-f]{64}$') {
        throw 'Certificate SHA-256 must contain exactly 64 hexadecimal digits.'
    }
    return $normalized
}

function Find-ApkSigner {
    $command = Get-Command apksigner -ErrorAction SilentlyContinue
    if ($null -ne $command) {
        return $command.Source
    }

    $sdkRoots = @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME) |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        Select-Object -Unique
    foreach ($sdkRoot in $sdkRoots) {
        $buildToolsRoot = Join-Path $sdkRoot 'build-tools'
        if (-not (Test-Path -LiteralPath $buildToolsRoot -PathType Container)) {
            continue
        }
        $candidate = Get-ChildItem -LiteralPath $buildToolsRoot -Recurse -File -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -in @('apksigner', 'apksigner.bat') } |
            Sort-Object @{
                Expression = {
                    try { [version]$_.Directory.Name } catch { [version]'0.0' }
                }
            }, @{
                Expression = { $_.FullName }
            } -Descending |
            Select-Object -First 1
        if ($null -ne $candidate) {
            return $candidate.FullName
        }
    }

    throw 'apksigner was not found. Install Android SDK Build Tools or add apksigner to PATH.'
}

function Get-InstalledCertificateSha256 {
    $packagePaths = @(Get-AdbText @('shell', 'pm', 'path', $Package) -split "`r?`n" |
        ForEach-Object { $_ -replace '^package:', '' } |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    if ($packagePaths.Count -eq 0) {
        throw "No installed APK path was returned for $Package."
    }
    $baseApk = $packagePaths | Where-Object { $_ -match '(^|/)base\.apk$' } | Select-Object -First 1
    if ([string]::IsNullOrWhiteSpace($baseApk)) {
        $baseApk = $packagePaths[0]
    }

    $temporaryApk = Join-Path ([System.IO.Path]::GetTempPath()) (
        'otphelper-installed-' + [guid]::NewGuid().ToString('N') + '.apk'
    )
    try {
        [void](Invoke-AdbText -Arguments @('pull', $baseApk, $temporaryApk))
        $apkSigner = Find-ApkSigner
        $output = @(& $apkSigner verify --print-certs $temporaryApk 2>&1)
        if ($LASTEXITCODE -ne 0) {
            throw "apksigner failed while reading the installed certificate:`n$($output -join "`n")"
        }
        $match = [regex]::Match(
            ($output -join "`n"),
            '(?im)^Signer #1 certificate SHA-256 digest:\s*([0-9a-f:]+)\s*$'
        )
        if (-not $match.Success) {
            throw 'apksigner did not report a signer SHA-256 digest for the installed APK.'
        }
        return Normalize-CertificateSha256 $match.Groups[1].Value
    }
    finally {
        Remove-Item -LiteralPath $temporaryApk -Force -ErrorAction SilentlyContinue
    }
}

function Test-PermissionGranted {
    param([Parameter(Mandatory)][string]$Permission)
    $result = Invoke-AdbText -Arguments @('shell', 'pm', 'check-permission', $Permission, $Package) -AllowFailure
    return $result.StdOut -eq 'granted'
}

function Test-ComponentInSetting {
    param(
        [Parameter(Mandatory)][string]$Setting,
        [Parameter(Mandatory)][string[]]$AcceptedComponents
    )
    $value = (Invoke-AdbText -Arguments @('shell', 'settings', 'get', 'secure', $Setting) -AllowFailure).StdOut
    $components = @($value -split ':' | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    return @($AcceptedComponents | Where-Object { $components -contains $_ }).Count -gt 0
}

function Test-ListenerEnabled {
    return Test-ComponentInSetting 'enabled_notification_listeners' @(
        $ListenerComponent,
        $ListenerComponentShort
    )
}

function Test-SensitiveAppOpAllowed {
    $result = Invoke-AdbText -Arguments @(
        'shell', 'cmd', 'appops', 'get', '--user', 'current', $Package,
        'RECEIVE_SENSITIVE_NOTIFICATIONS'
    ) -AllowFailure
    return ($result.StdOut + "`n" + $result.StdErr) -match '(?im)RECEIVE_SENSITIVE_NOTIFICATIONS:\s*allow'
}

function Test-BatteryWhitelist {
    $result = Invoke-AdbText -Arguments @('shell', 'cmd', 'deviceidle', 'whitelist') -AllowFailure
    $escapedPackage = [regex]::Escape($Package)
    return @($result.StdOut -split "`r?`n" | Where-Object {
        $_ -match "(^|,)$escapedPackage(,|$)"
    }).Count -gt 0
}

function Test-AccessibilityEnabled {
    return Test-ComponentInSetting 'enabled_accessibility_services' @(
        $AccessibilityComponent,
        $AccessibilityComponentShort
    )
}

function Save-AppArchive {
    param([Parameter(Mandatory)][string]$ArchivePath)

    $process = New-AdbProcess -Arguments @('exec-out', 'run-as', $Package, 'tar', '-cf', '-', '.')
    try {
        $stderrTask = $process.StandardError.ReadToEndAsync()
        $file = [System.IO.File]::Create($ArchivePath)
        try {
            $process.StandardOutput.BaseStream.CopyToAsync($file).GetAwaiter().GetResult()
        }
        finally {
            $file.Dispose()
        }
        $process.WaitForExit()
        $stderr = $stderrTask.GetAwaiter().GetResult().Trim()
        if ($process.ExitCode -ne 0) {
            Remove-Item -LiteralPath $ArchivePath -Force -ErrorAction SilentlyContinue
            throw "ADB app-data backup failed ($($process.ExitCode)): $stderr"
        }
    }
    finally {
        $process.Dispose()
    }
    if ((Get-Item -LiteralPath $ArchivePath).Length -lt 512) {
        throw 'The generated app-data archive is unexpectedly small; refusing to treat it as valid.'
    }
}

function Restore-AppArchive {
    param([Parameter(Mandatory)][string]$ArchivePath)

    $process = New-AdbProcess -Arguments @('exec-in', 'run-as', $Package, 'tar', '-xf', '-') -RedirectStandardInput
    try {
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        $file = [System.IO.File]::OpenRead($ArchivePath)
        try {
            $file.CopyToAsync($process.StandardInput.BaseStream).GetAwaiter().GetResult()
            $process.StandardInput.Close()
        }
        finally {
            $file.Dispose()
        }
        $process.WaitForExit()
        $stdout = $stdoutTask.GetAwaiter().GetResult().Trim()
        $stderr = $stderrTask.GetAwaiter().GetResult().Trim()
        if ($process.ExitCode -ne 0) {
            throw "ADB app-data restore failed ($($process.ExitCode)):`n$stdout`n$stderr"
        }
    }
    finally {
        $process.Dispose()
    }
}

function Invoke-BestEffort {
    param(
        [Parameter(Mandatory)][string]$Description,
        [Parameter(Mandatory)][string[]]$Arguments
    )

    $result = Invoke-AdbText -Arguments $Arguments -AllowFailure
    if ($result.ExitCode -eq 0) {
        Write-Host "PASS $Description"
    }
    else {
        Write-Warning "$Description failed: $($result.StdErr)"
    }
}

function Start-OtpHelperBestEffort {
    # Resolve the current exported launcher Activity/alias instead of targeting private MainActivity.
    Invoke-BestEffort 'Launch OTP Helper through its launcher contract' @(
        'shell', 'monkey', '-p', $Package,
        '-c', 'android.intent.category.LAUNCHER', '1'
    )
}

Assert-OneDevice
Assert-PackageInstalled

$deviceSerial = Get-AdbText @('get-serialno')
$fingerprint = Get-AdbText @('shell', 'getprop', 'ro.build.fingerprint')

switch ($Action) {
    'Status' {
        $runAs = Invoke-AdbText -Arguments @('shell', 'run-as', $Package, 'id') -AllowFailure
        $installedCertificate = try {
            Get-InstalledCertificateSha256
        }
        catch {
            "unavailable: $($_.Exception.Message)"
        }
        [pscustomobject]@{
            DeviceSerial = $deviceSerial
            Fingerprint = $fingerprint
            PackageInstalled = $true
            InstalledCertificateSha256 = $installedCertificate
            RunAsAvailable = ($runAs.ExitCode -eq 0 -and $runAs.StdOut -match 'uid=')
            NotificationListenerEnabled = Test-ListenerEnabled
            SensitiveNotificationAppOpAllowed = Test-SensitiveAppOpAllowed
            BatteryOptimizationExempt = Test-BatteryWhitelist
            PostNotificationsGranted = Test-PermissionGranted 'android.permission.POST_NOTIFICATIONS'
            ReceiveSmsGranted = Test-PermissionGranted 'android.permission.RECEIVE_SMS'
            ReadSmsGranted = Test-PermissionGranted 'android.permission.READ_SMS'
            AccessibilityNotificationServiceEnabled = Test-AccessibilityEnabled
        } | Format-List
    }

    'Backup' {
        Assert-RunAsAvailable
        New-Item -ItemType Directory -Path $BackupDirectory -Force | Out-Null
        $archivePath = Join-Path $BackupDirectory $ArchiveName
        $metadataPath = Join-Path $BackupDirectory $MetadataName
        $packageDumpPath = Join-Path $BackupDirectory $PackageDumpName

        Write-Host 'Force-stopping OTP Helper so DataStore and Room files are consistent...'
        [void](Invoke-AdbText -Arguments @('shell', 'am', 'force-stop', $Package))
        try {
            Write-Host 'Streaming private app data through run-as...'
            Save-AppArchive -ArchivePath $archivePath

            $packageDump = Get-AdbText @('shell', 'dumpsys', 'package', $Package)
            [System.IO.File]::WriteAllText(
                $packageDumpPath,
                $packageDump,
                [System.Text.UTF8Encoding]::new($false)
            )

            $metadata = [ordered]@{
                schema = 'otphelper.adb-migration'
                version = 1
                createdAt = [DateTimeOffset]::Now.ToString('o')
                deviceSerial = $deviceSerial
                fingerprint = $fingerprint
                package = $Package
                notificationListenerEnabled = Test-ListenerEnabled
                sensitiveNotificationAppOpAllowed = Test-SensitiveAppOpAllowed
                batteryOptimizationExempt = Test-BatteryWhitelist
                postNotificationsGranted = Test-PermissionGranted 'android.permission.POST_NOTIFICATIONS'
                receiveSmsGranted = Test-PermissionGranted 'android.permission.RECEIVE_SMS'
                readSmsGranted = Test-PermissionGranted 'android.permission.READ_SMS'
                accessibilityNotificationServiceEnabled = Test-AccessibilityEnabled
                archiveSha256 = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
            }
            $metadata | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $metadataPath -Encoding utf8NoBOM

            Write-Host "Backup complete: $BackupDirectory"
            Write-Host "Archive SHA-256: $($metadata.archiveSha256)"
            Write-Warning 'Keep the current APK installed until the permanent key and fixed-signed DEBUG APK are ready.'
        }
        finally {
            Start-OtpHelperBestEffort
        }
    }

    'Restore' {
        if (-not $ConfirmRestore) {
            throw 'Restore clears the installed app data. Re-run with -ConfirmRestore only after installing the permanent-key DEBUG APK.'
        }
        if ([string]::IsNullOrWhiteSpace($ExpectedCertificateSha256)) {
            throw 'Restore requires -ExpectedCertificateSha256 for the permanent signing identity.'
        }
        Assert-RunAsAvailable

        $archivePath = Join-Path $BackupDirectory $ArchiveName
        $metadataPath = Join-Path $BackupDirectory $MetadataName
        if (-not (Test-Path -LiteralPath $archivePath -PathType Leaf)) {
            throw "Backup archive not found: $archivePath"
        }
        if (-not (Test-Path -LiteralPath $metadataPath -PathType Leaf)) {
            throw "Backup metadata not found: $metadataPath"
        }

        $metadata = Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json
        if ($metadata.schema -ne 'otphelper.adb-migration' -or $metadata.version -ne 1) {
            throw 'Unsupported OTP Helper ADB backup metadata.'
        }
        if ($metadata.package -ne $Package) {
            throw "Backup package mismatch: $($metadata.package)"
        }
        $actualHash = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actualHash -ne $metadata.archiveSha256) {
            throw "Backup archive SHA-256 mismatch. Expected $($metadata.archiveSha256), got $actualHash."
        }

        $expectedCertificate = Normalize-CertificateSha256 $ExpectedCertificateSha256
        $installedCertificate = Get-InstalledCertificateSha256
        if ($installedCertificate -ne $expectedCertificate) {
            throw @"
Installed APK certificate mismatch. Refusing to clear app data.
Expected permanent certificate: $expectedCertificate
Installed APK certificate:      $installedCertificate
Install the fixed-signed DEBUG APK produced from the permanent keystore, then retry Restore.
"@
        }
        Write-Host "PASS Installed APK uses the expected permanent certificate: $installedCertificate"

        Write-Host 'Clearing the fresh installation before restoring private data...'
        [void](Invoke-AdbText -Arguments @('shell', 'pm', 'clear', $Package))
        Assert-RunAsAvailable

        Write-Host 'Restoring private app data through run-as...'
        Restore-AppArchive -ArchivePath $archivePath
        [void](Invoke-AdbText -Arguments @(
            'shell', 'run-as', $Package, 'rm', '-rf', 'cache', 'code_cache'
        ) -AllowFailure)
        [void](Invoke-AdbText -Arguments @(
            'shell', 'run-as', $Package, 'rm', '-f',
            'no_backup/androidx.work.workdb',
            'no_backup/androidx.work.workdb-shm',
            'no_backup/androidx.work.workdb-wal',
            'no_backup/androidx.work.workdb-journal'
        ) -AllowFailure)

        if ($metadata.postNotificationsGranted) {
            Invoke-BestEffort 'Restore POST_NOTIFICATIONS' @('shell', 'pm', 'grant', $Package, 'android.permission.POST_NOTIFICATIONS')
        }
        if ($metadata.receiveSmsGranted) {
            Invoke-BestEffort 'Restore RECEIVE_SMS' @('shell', 'pm', 'grant', $Package, 'android.permission.RECEIVE_SMS')
        }
        if ($metadata.readSmsGranted) {
            Invoke-BestEffort 'Restore READ_SMS' @('shell', 'pm', 'grant', $Package, 'android.permission.READ_SMS')
        }
        if ($metadata.sensitiveNotificationAppOpAllowed) {
            Invoke-BestEffort 'Restore RECEIVE_SENSITIVE_NOTIFICATIONS AppOp' @(
                'shell', 'cmd', 'appops', 'set', '--user', 'current', $Package,
                'RECEIVE_SENSITIVE_NOTIFICATIONS', 'allow'
            )
        }
        if ($metadata.notificationListenerEnabled) {
            Invoke-BestEffort 'Restore notification-listener access' @(
                'shell', 'cmd', 'notification', 'allow_listener', $ListenerComponent
            )
        }
        if ($metadata.batteryOptimizationExempt) {
            Invoke-BestEffort 'Restore battery-optimization exemption' @(
                'shell', 'cmd', 'deviceidle', 'whitelist', "+$Package"
            )
        }

        Start-OtpHelperBestEffort

        Write-Host 'Private data restore completed.'
        Write-Warning 'MagicOS App launch switches, Recents lock, and Shizuku client permission may still require manual confirmation.'
        if ($metadata.accessibilityNotificationServiceEnabled) {
            Write-Warning 'Accessibility notification reading was enabled in the backup but is intentionally not re-enabled automatically. Review it manually under advanced notification recovery.'
        }
    }
}
