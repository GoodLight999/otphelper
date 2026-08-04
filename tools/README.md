# OTP Helper maintenance tools

These PowerShell 7 helpers make the one unavoidable signing transition reproducible, prevent a mistaken APK from destroying the current app data, and collect consistent HONOR physical-test evidence without copying notification databases or broad notification dumps.

Read [`../docs/SIGNING_MIGRATION.md`](../docs/SIGNING_MIGRATION.md) before changing the signing identity. Read [`../docs/HONOR_PHYSICAL_TEST_PLAN.md`](../docs/HONOR_PHYSICAL_TEST_PLAN.md) before running physical release gates.

## `new-otphelper-signing-key.ps1`

Guarded bootstrap for the permanent fork signing identity.

Requirements:

- PowerShell 7;
- JDK 17+ with `keytool` in `PATH`;
- optional authenticated GitHub CLI when using `-ConfigureGitHubSecrets`.

Create local signing material:

```powershell
pwsh ./tools/new-otphelper-signing-key.ps1 `
  -OutputDirectory ./otphelper-signing-output `
  -ConfirmCreate
```

The script refuses to overwrite a non-empty directory and prompts twice for one strong password used by both the JKS and its key entry. It passes that password to `keytool` through an environment variable rather than a command-line argument.

Output includes:

- `otphelper-permanent-signing.jks` — secret private signing material;
- `otphelper-signing-certificate.pem` — public certificate;
- `otphelper-signing-certificate-sha256.txt` — pinned signer fingerprint;
- `otphelper-signing-keystore-base64.txt` — value for the JKS GitHub Secret;
- `SHA256SUMS.txt` — copy-verification hashes;
- `manifest.json` — non-secret generation metadata;
- `README.txt` — local recovery instructions and Secret mapping.

The password is never written to disk.

To configure all five repository Secrets through standard input to `gh secret set`:

```powershell
pwsh ./tools/new-otphelper-signing-key.ps1 `
  -OutputDirectory ./otphelper-signing-output `
  -Repository GoodLight999/otphelper `
  -ConfigureGitHubSecrets `
  -ConfirmCreate
```

The required Secrets are:

- `OTPHELPER_SIGNING_KEYSTORE_B64`;
- `OTPHELPER_KEYSTORE_PASSWORD`;
- `OTPHELPER_KEY_ALIAS`;
- `OTPHELPER_KEY_PASSWORD`;
- `OTPHELPER_SIGNING_CERT_SHA256`.

Create and verify at least two independent encrypted backups of the JKS and password before using the key. Do not uninstall the current physical-test APK merely because key generation succeeded.

## `otphelper-adb-migration.ps1`

PowerShell 7 helper for the one-time transition from the unrecoverable ephemeral debug signature to the permanent signing key.

**Do not run `Restore` until the permanent keystore is backed up, GitHub signing Secrets are configured, fixed-certificate CI verification passes, and a fixed-signed debuggable APK has been built.** The currently installed physical-test APK should remain installed until then.

The tool requires current Android SDK Platform Tools. Certificate inspection and restore also require Android SDK Build Tools (`apksigner`) through `PATH`, `ANDROID_SDK_ROOT`, or `ANDROID_HOME`.

### Inspect the connected device

```powershell
pwsh ./tools/otphelper-adb-migration.ps1 -Action Status
```

Use `-Serial <adb-serial>` when more than one authorized device is connected. Status includes the installed APK certificate SHA-256 when `apksigner` is available.

### Back up the current debuggable installation

```powershell
pwsh ./tools/otphelper-adb-migration.ps1 `
  -Action Backup `
  -BackupDirectory ./otphelper-adb-backup
```

The script:

1. verifies that `adb`, the package, and `run-as` are available;
2. force-stops OTP Helper so DataStore and Room files are consistent;
3. streams private app data directly to `app-data.tar` without using a shared device path;
4. records an SHA-256 digest and relevant permission/AppOp state;
5. relaunches OTP Helper in a `finally` block even when backup fails, so it is not left in Android's stopped state.

Keep the complete backup directory private. It can contain app settings and OTP history.

### Restore after installing the permanent-key debug APK

After uninstalling the old-signature APK and installing the permanent-key **debuggable** APK, pass the permanent certificate fingerprint pinned in `OTPHELPER_SIGNING_CERT_SHA256`:

```powershell
pwsh ./tools/otphelper-adb-migration.ps1 `
  -Action Restore `
  -BackupDirectory ./otphelper-adb-backup `
  -ExpectedCertificateSha256 '<64-hex permanent certificate SHA-256>' `
  -ConfirmRestore
```

Before clearing any data, Restore:

1. pulls the installed base APK;
2. verifies it with `apksigner`;
3. compares the signer certificate with `-ExpectedCertificateSha256`;
4. refuses to proceed on any mismatch.

This prevents restoring into another ephemeral-key or unintended APK that cannot receive future permanent-key updates.

After the certificate gate, Restore verifies the archive hash, clears the fresh app data, restores files through `run-as`, removes stale WorkManager runtime state, and best-effort restores:

- `POST_NOTIFICATIONS`;
- `RECEIVE_SMS` and `READ_SMS`, when previously granted;
- `RECEIVE_SENSITIVE_NOTIFICATIONS` AppOp;
- notification-listener access;
- battery-optimization exemption.

It deliberately does **not** automatically re-enable Accessibility. MagicOS App launch switches, the Recents lock, and Shizuku client permission may still need manual confirmation.

After the restore is verified, a permanent-key release APK can update over the permanent-key debug APK because both are signed by the same fixed identity. A second higher-version fixed-key update must also be tested before declaring the update lineage complete.

## `collect-otphelper-device-evidence.ps1`

Read-only ADB evidence collector for HONOR physical test checkpoints. It gives each test case the same package/process/service/watchdog/AppOp evidence instead of relying on screenshots or an unbounded `dumpsys notification` dump.

Requirements:

- PowerShell 7;
- current Android SDK Platform Tools with `adb` in `PATH`;
- one authorized device, or an explicit `-Serial` value;
- OTP Helper installed on the selected device.

Capture a normal checkpoint and create a ZIP:

```powershell
pwsh ./tools/collect-otphelper-device-evidence.ps1 `
  -TestLabel HN-08-after-pass `
  -Compress
```

When several devices are connected:

```powershell
pwsh ./tools/collect-otphelper-device-evidence.ps1 `
  -Serial '<adb-serial>' `
  -TestLabel HN-14-after-fail `
  -Compress
```

The default output directory contains a timestamp. The script refuses to reuse a non-empty directory. The raw device serial is replaced by a short SHA-256 identifier unless `-IncludeDeviceSerial` is deliberately supplied.

Collected evidence includes:

- build fingerprint, manufacturer, model, Android release/API, and security patch;
- package dump and installed APK path;
- process state and package-scoped Activity service state;
- package exit history where supported;
- package-scoped JobScheduler and AlarmManager state;
- package AppOps and the sensitive-notification AppOp;
- whether OTP Helper itself is present in notification-listener, Accessibility, and device-idle settings;
- standby bucket and battery state;
- private file **names/layout only** when the installed APK is debuggable.

It does not collect:

- Room database contents;
- DataStore contents;
- broad notification dumps;
- notification text from other apps;
- unrelated enabled-listener, Accessibility, or battery-whitelist package names.

Logcat is excluded by default. For one narrowly scoped failure, it can be added with redaction:

```powershell
pwsh ./tools/collect-otphelper-device-evidence.ps1 `
  -TestLabel HN-11-after-fail `
  -IncludeRedactedLogcat `
  -Compress
```

The optional log captures only the current OTP Helper PID and applies additional code/number/email/phone/token redaction. Inspect every archive before attaching it to a PR or sharing it outside the private project.

Each evidence package includes:

- `evidence-manifest.json` with command exit codes and file hashes;
- `evidence-manifest.sha256` for the final manifest;
- one UTF-8 file per evidence source.

Recommended naming is `<case-id>-before`, `<case-id>-after-pass`, or `<case-id>-after-fail`. Keep the in-app redacted diagnostics export beside the ADB evidence package; the two sources answer different questions.