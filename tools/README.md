# OTP Helper maintenance tools

These PowerShell 7 helpers make the one unavoidable signing transition reproducible, prevent a mistaken APK from destroying the current app data, and collect consistent HONOR physical-test evidence without copying notification databases or broad notification dumps.

Read [`../docs/SIGNING_MIGRATION.md`](../docs/SIGNING_MIGRATION.md) before changing signing configuration. Read [`../docs/HONOR_PHYSICAL_TEST_PLAN.md`](../docs/HONOR_PHYSICAL_TEST_PLAN.md) before running physical release gates.

## Permanent signing identity

This repository already has one permanent Android signing identity. Its public certificate SHA-256 is pinned in:

`.github/signing/otphelper-cert-sha256.txt`

Do **not** generate another key for routine builds, Secret setup, or recovery. Android update continuity depends on preserving the existing private key that matches that pin.

Exactly four GitHub Actions Secrets carry private signing material:

- `OTPHELPER_SIGNING_KEYSTORE_B64`;
- `OTPHELPER_KEYSTORE_PASSWORD`;
- `OTPHELPER_KEY_ALIAS`;
- `OTPHELPER_KEY_PASSWORD`.

The expected certificate SHA-256 is deliberately public and repository-pinned; it is not a fifth mutable Secret.

## `configure-otphelper-signing-secrets.ps1`

Use this helper to install an **existing backup of the permanent JKS** into GitHub Actions Secrets without creating or rotating a signer.

Requirements:

- PowerShell 7;
- JDK 17+ with `keytool` in `PATH`;
- authenticated GitHub CLI (`gh`) with permission to set Actions Secrets for `GoodLight999/otphelper`;
- the existing permanent JKS and its existing password.

Example:

```powershell
pwsh ./tools/configure-otphelper-signing-secrets.ps1 `
  -KeystorePath 'C:\private\otphelper-permanent-signing.jks' `
  -ConfirmConfigure
```

The helper performs the important check **before writing any Secret**: it exports the certificate from the supplied JKS and requires its SHA-256 to equal `.github/signing/otphelper-cert-sha256.txt`. A mismatched JKS is rejected. It never changes the public pin and never generates a key.

The password is read as a secure prompt by default. For controlled automation, `-PasswordEnvironmentVariable <NAME>` reads it from an environment variable. Secret values are sent to `gh secret set` through exact standard-input writes without adding a newline.

After all four Secrets are configured, re-run Android CI. `Verify fixed signing keystore` and `Verify fixed signing certificate` must **execute successfully rather than skip**, and fixed-signed APK artifact upload must execute.

## `new-otphelper-signing-key.ps1`

Guarded **bootstrap-only** generator retained for reproducibility and CI contract tests. The live repository already has a public signer pin, so normal invocation intentionally refuses to create a second identity.

Do not remove or weaken that refusal to make Secret setup easier. Use `configure-otphelper-signing-secrets.ps1` with the existing JKS instead.

The bootstrap tool remains useful only for isolated test environments where `OTPHELPER_SIGNING_BOOTSTRAP_TEST=1` is deliberately set by the repository's signing-contract test. Its generated format is:

- `otphelper-permanent-signing.jks` — secret private signing material;
- `otphelper-signing-certificate.pem` — public certificate;
- `otphelper-signing-certificate-sha256.txt` — public signer fingerprint;
- `otphelper-signing-keystore-base64.txt` — Base64 encoding of the JKS;
- `SHA256SUMS.txt` — copy-verification hashes;
- `manifest.json` — non-secret generation metadata;
- `README.txt` — local recovery instructions and Secret mapping.

The bootstrap script uses one password for the JKS and its key entry and never writes that password to disk. Its `-ConfigureGitHubSecrets` path configures the same four private Secrets listed above; certificate identity remains repository-pinned.

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

After uninstalling the old-signature APK and installing the permanent-key **debuggable** APK, pass the permanent certificate fingerprint from `.github/signing/otphelper-cert-sha256.txt`:

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
