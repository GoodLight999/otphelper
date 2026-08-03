# OTP Helper maintenance tools

## `otphelper-adb-migration.ps1`

PowerShell 7 helper for the one-time transition from the unrecoverable ephemeral debug signature to a permanent signing key.

**Do not run `Restore` until the permanent keystore is backed up, GitHub signing Secrets are configured, and a fixed-signed debuggable APK has been built.** The currently installed physical-test APK should remain installed until then.

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

After uninstalling the old-signature APK and installing the permanent-key **debuggable** APK, pass the permanent certificate fingerprint pinned in the `OTPHELPER_SIGNING_CERT_SHA256` GitHub Secret:

```powershell
pwsh ./tools/otphelper-adb-migration.ps1 `
  -Action Restore `
  -BackupDirectory ./otphelper-adb-backup `
  -ExpectedCertificateSha256 '<64-hex permanent certificate SHA-256>' `
  -ConfirmRestore
```

Before clearing any data, Restore pulls the installed base APK, verifies it with `apksigner`, and refuses to proceed unless its signing certificate exactly matches `-ExpectedCertificateSha256`. This prevents accidentally restoring into another ephemeral-key build that cannot receive future permanent-key updates.

After the certificate gate, Restore verifies the archive hash, clears the fresh app data, restores files through `run-as`, removes stale WorkManager runtime state, and best-effort restores:

- `POST_NOTIFICATIONS`;
- `RECEIVE_SMS` and `READ_SMS`, when previously granted;
- `RECEIVE_SENSITIVE_NOTIFICATIONS` AppOp;
- notification-listener access;
- battery-optimization exemption.

It deliberately does **not** automatically re-enable Accessibility. MagicOS App launch switches, the Recents lock, and Shizuku client permission may still need manual confirmation.

After the restore is verified, a permanent-key release APK can update over the permanent-key debug APK because both are signed by the same fixed identity.
