# OTP Helper maintenance tools

These PowerShell 7 helpers exist to make the one unavoidable signing transition reproducible and to prevent a mistaken APK from destroying the current app data.

Read [`../docs/SIGNING_MIGRATION.md`](../docs/SIGNING_MIGRATION.md) before using either tool.

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
