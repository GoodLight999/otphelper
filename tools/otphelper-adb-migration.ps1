[CmdletBinding()]
param(
    [ValidateSet('Status', 'Backup', 'Restore')]
    [string]$Action = 'Status',

    [string]$BackupDirectory = (Join-Path $PWD 'otphelper-adb-backup'),

    [string]$Serial,

    [switch]$ConfirmRestore
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$Package = 'io.github.jd1378.otphelper'
$ListenerComponent = "$Package/$Package.NotificationListener"
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
        [Parameter(Mandatory)]
        [string[]]$Arguments,
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
        throw "Unable to start adb: $($Arguments -join ' ')"
    }
    return $process
}

function Invoke-AdbText {
    param(
        [Parameter(Mandatory)]
        [string[]]$Arguments,
        [switch]$AllowFailure
    )

    $process = New-AdbProcess -Arguments $Arguments
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $process.WaitForExit()
    $stdout = $stdoutTask.GetAwaiter().GetResult().Trim()
    $stderr = $stderrTask.GetAwaiter().GetResult().Trim()

    if ($process.ExitCode -ne 0 -and -not $AllowFailure) {
        throw "adb failed ($($process.ExitCode)): adb $($Arguments -join ' ')`n$stderr"
    }

    [pscustomobject]@{
        ExitCode = $process.ExitCode
        StdOut = $stdout
        StdErr = $stderr
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

function Test-PermissionGranted {
    param([Parameter(Mandatory)][string]$Permission)
    $result = Invoke-AdbText -Arguments @('shell', 'pm', 'check-permission', $Permission, $Package) -AllowFailure
    return $result.StdOut -eq 'granted'
}

function Test-ListenerEnabled {
    $listeners = (Invoke-AdbText -Arguments @('shell', 'settings', 'get', 'secure', 'enabled_notification_listeners') -AllowFailure).StdOut
    return $listeners -split ':' -contains $ListenerComponent
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
    return ($result.StdOut -split "`r?`n") -contains $Package
}

function Test-AccessibilityEnabled {
    $component = "$Package/$Package.AccessibilityNotificationService"
    $services = (Invoke-AdbText -Arguments @('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services') -AllowFailure).StdOut
    return $services -split ':' -contains $component
}

function Save-AppArchive {
    param([Parameter(Mandatory)][string]$ArchivePath)

    $process = New-AdbProcess -Arguments @('exec-out', 'run-as', $Package, 'tar', '-cf', '-', '.')
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
    if ((Get-Item -LiteralPath $ArchivePath).Length -lt 512) {
        throw 'The generated app-data archive is unexpectedly small; refusing to treat it as a valid backup.'
    }
}

function Restore-AppArchive {
    param([Parameter(Mandatory)][string]$ArchivePath)

    $process = New-AdbProcess -Arguments @('exec-in', 'run-as', $Package, 'tar', '-xf', '-') -RedirectStandardInput
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

Assert-OneDevice
Assert-PackageInstalled

$deviceSerial = Get-AdbText @('get-serialno')
$fingerprint = Get-AdbText @('shell', 'getprop', 'ro.build.fingerprint')

switch ($Action) {
    'Status' {
        $runAs = Invoke-AdbText -Arguments @('shell', 'run-as', $Package, 'id') -AllowFailure
        [pscustomobject]@{
            DeviceSerial = $deviceSerial
            Fingerprint = $fingerprint
            PackageInstalled = $true
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

        Write-Host 'Streaming private app data through run-as...'
        Save-AppArchive -ArchivePath $archivePath

        $packageDump = Get-AdbText @('shell', 'dumpsys', 'package', $Package)
        [System.IO.File]::WriteAllText($packageDumpPath, $packageDump, [System.Text.UTF8Encoding]::new($false))

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
        Write-Warning 'Keep the current APK installed until the permanent signing key and a fixed-signed DEBUG APK are ready.'
    }

    'Restore' {
        if (-not $ConfirmRestore) {
            throw 'Restore clears the currently installed OTP Helper app data. Re-run with -ConfirmRestore after installing the permanent-key DEBUG APK.'
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
        $actualHash = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actualHash -ne $metadata.archiveSha256) {
            throw "Backup archive SHA-256 mismatch. Expected $($metadata.archiveSha256), got $actualHash."
        }

        Write-Host 'Clearing the fresh installation before restoring private data...'
        [void](Invoke-AdbText -Arguments @('shell', 'pm', 'clear', $Package))
        Assert-RunAsAvailable

        Write-Host 'Restoring private app data through run-as...'
        Restore-AppArchive -ArchivePath $archivePath
        [void](Invoke-AdbText -Arguments @('shell', 'run-as', $Package, 'rm', '-rf', 'cache', 'code_cache') -AllowFailure)

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

        Invoke-BestEffort 'Launch OTP Helper after restore' @(
            'shell', 'am', 'start', '-n', "$Package/$Package.MainActivity"
        )

        Write-Host 'Private data restore completed.'
        Write-Warning 'MagicOS App launch switches, Recents lock, and Shizuku client permission may still require manual confirmation.'
        if ($metadata.accessibilityNotificationServiceEnabled) {
            Write-Warning 'Accessibility notification reading was enabled in the backup but is intentionally not re-enabled automatically. Review it manually under advanced notification recovery.'
        }
    }
}
