# OTP Helper — HONOR MagicOS resilience fork

This repository is a maintained fork of [`jd1378/otphelper`](https://github.com/jd1378/otphelper). It preserves the original offline OTP/SMS functionality while adding a dedicated resilience layer for HONOR MagicOS, Android 15/16 notification restrictions, complete phrase-list backup, and reproducible diagnostics.

> [!IMPORTANT]
> No current APK from this fork is offered as a stable download yet. The earlier physical-test APK was signed by an ephemeral CI debug key whose private key no longer exists. CI now refuses to upload installable APKs until one permanent signing identity is configured and verified. Do not treat an old Actions artifact as an update channel.

## Fork status

- implementation branch: `agent/magicos-resilience-and-backup`
- integration PR: [#1 — harden MagicOS persistence and Android 15/16 OTP reading](https://github.com/GoodLight999/otphelper/pull/1)
- PR remains Draft until permanent signing migration and the HONOR physical matrix are complete
- upstream sync runs weekly and opens a Draft PR for clean upstream merges

Start with the canonical current-state handoff:

- [`docs/CURRENT_HANDOFF.md`](docs/CURRENT_HANDOFF.md)

Architecture and operational rules:

- [`docs/FORK_MAINTENANCE.md`](docs/FORK_MAINTENANCE.md)
- [`docs/PLATFORM_CONTRACTS.md`](docs/PLATFORM_CONTRACTS.md)
- [`docs/SIGNING_MIGRATION.md`](docs/SIGNING_MIGRATION.md)
- [`docs/HONOR_PHYSICAL_TEST_PLAN.md`](docs/HONOR_PHYSICAL_TEST_PLAN.md)
- [`docs/DATA_BACKUP_POLICY.md`](docs/DATA_BACKUP_POLICY.md)
- [`tools/README.md`](tools/README.md)

## What this fork adds

### MagicOS persistence

- normal visible Recents task instead of hiding the app card;
- visible `specialUse` foreground service using `START_STICKY`;
- low-priority, silent, ongoing persistence notification rather than a hidden/minimum-priority event;
- one-minute listener-health heartbeat;
- 15-minute WorkManager watchdog;
- AlarmManager recovery after task removal or service destruction;
- boot, user-unlock, and package-replacement recovery;
- HONOR/Huawei App launch targets plus a System Manager fallback when MagicOS rejects direct launch;
- built-in guidance for App launch controls, Recents locking, and battery optimization.

Android's explicit **Force stop** places the package in the stopped state. An ordinary app cannot bypass that state; the user must launch it again or a permitted system action must clear it.

### Notification-reading paths

1. **Standard NotificationListenerService** — primary path.
2. **Notification-only Accessibility service** — optional fallback limited to `TYPE_NOTIFICATION_STATE_CHANGED`; it does not request window content, gestures, key events, or screenshots.
3. **Shizuku repair** — optional Android 15+ repair for the standard listener. Shizuku is not used as a general notification reader.
4. **SMS receiver** — independent path retained in the normal flavor.

The standard listener and Accessibility path share package+code duplicate suppression.

### Phrase backup

The sensitive/detection, ignored/exclusion, and cleanup/removal phrase screens support:

- individual import and export;
- complete import and export of all three lists;
- versioned JSON;
- JSON string arrays and one-phrase-per-line UTF-8 text for individual imports;
- atomic complete restore;
- type, schema, version, size, and regular-expression validation.

### Diagnostics and validation

The app can copy or export a redacted diagnostic report containing actual connection state, foreground-service/watchdog state, battery and standby information, optional Shizuku state, and bounded rotating logs.

CI validates:

- normal/play JVM tests and Android Lint;
- all normal/play debug and minified release APKs;
- exact flavor-specific permission allowlists;
- absence of internet, network-state, and direct battery-whitelist permissions;
- debug APKs remain debuggable while release APKs do not;
- merged APK Manifest contracts;
- Recents visibility;
- foreground service and watchdog startup;
- real NotificationListener and Accessibility service binding on API 35 and API 36 emulators;
- absence of LeakCanary and experiment-only fixtures;
- signature-protected internal notification actions;
- fixed signing-certificate identity when signing Secrets are configured;
- actual disposable-JKS generation, fingerprint derivation, Base64 reconstruction, and JKS reopening;
- Android backup allowlists and rejection of committed signing/migration secrets.

## How OTP detection works

The original application works in two main modes.

### Notification mode

The app receives posted notifications through the selected notification-ingestion path, combines the available public text fields, applies ignored phrases and cleanup phrases, then matches the configured code-detection rules. The extracted code is handled according to the user's clipboard, notification, history, and optional dismissal settings.

Android 15/16 or the source app may redact sensitive notification content. A connected listener proves service connectivity, not that a particular third-party OTP body was exposed. Real OTP capability is therefore validated separately on the target firmware.

### SMS mode

The normal flavor listens for incoming SMS messages, applies the same ignore, cleanup, and detection pipeline, and handles the extracted code according to the user's settings.

## Local development

Requirements:

- JDK 17;
- Android SDK with API 36 and current Build Tools;
- PowerShell 7 for the signing/migration helpers.

Run JVM tests, Lint, and debug builds:

```bash
./gradlew --no-daemon \
  :app:testNormalDebugUnitTest \
  :app:testPlayDebugUnitTest \
  :app:lintNormalDebug \
  :app:lintPlayDebug \
  :app:assembleNormalDebug \
  :app:assemblePlayDebug
```

Debug APKs are written below:

```text
app/build/outputs/apk/normal/debug/
app/build/outputs/apk/play/debug/
```

A local debug build without the permanent signing environment variables uses the machine's ordinary debug identity. It is suitable for isolated development only and must not be distributed as the fork's update lineage.

Release and distributable debug builds require the fixed signing inputs documented in [`docs/SIGNING_MIGRATION.md`](docs/SIGNING_MIGRATION.md).

## Permanent signing bootstrap

Create permanent signing material only after reading the migration runbook:

```powershell
pwsh ./tools/new-otphelper-signing-key.ps1 `
  -OutputDirectory ./otphelper-signing-output `
  -ConfirmCreate
```

Back up the resulting JKS independently before configuring CI or uninstalling any existing APK. The helper never writes the password to disk and can optionally configure the five GitHub Actions Secrets through `gh secret set`.

## One-time device migration

The old physical-test signature cannot be updated in place. Preserve its private data before replacing it:

```powershell
pwsh ./tools/otphelper-adb-migration.ps1 `
  -Action Backup `
  -BackupDirectory ./otphelper-adb-backup
```

Restore only after installing a debuggable APK signed by the permanent key. Restore requires the expected permanent certificate SHA-256 and refuses to clear data when the installed certificate differs.

See [`docs/SIGNING_MIGRATION.md`](docs/SIGNING_MIGRATION.md) for the complete sequence.

## Fork distribution boundary

Fixed-signed fork releases are limited to this repository's GitHub prereleases. Each release contains:

- normal release APK;
- play-flavor release APK;
- `SHA256SUMS.txt`;
- `release-metadata.json` containing the source commit and public signing-certificate SHA-256.

The workflow deliberately does not generate or upload a Google Play App Bundle. The fork's permanent signing identity is independent of the upstream project's official distribution identities.

## Upstream project and store builds

The original project and its official store/release channels remain available from [`jd1378/otphelper`](https://github.com/jd1378/otphelper).

Those APKs are signed by identities not controlled by this fork. They are not interchangeable in place with future permanent-key fork builds despite sharing the same package ID.

## Privacy

OTP Helper works offline. Final APK inspection rejects `INTERNET` and `ACCESS_NETWORK_STATE`, and notification/SMS contents are processed on-device. Diagnostic persistence and Logcat output redact OTP/PIN/code-like values and long standalone numeric runs before they are written.

Android system backup and device transfer include only DataStore settings and phrase lists. The Room OTP-history database is excluded. The one-time ADB signing-migration archive is different: it may contain OTP history and must remain private. See [`docs/DATA_BACKUP_POLICY.md`](docs/DATA_BACKUP_POLICY.md).

## Credits

This fork retains and builds upon the work of the upstream author and contributors. Original project credits, translations, store listings, and donation information are maintained in the upstream repository.
