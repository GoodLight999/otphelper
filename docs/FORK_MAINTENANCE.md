# OTP Helper fork maintenance

This fork tracks [`jd1378/otphelper`](https://github.com/jd1378/otphelper) while adding a maintained HONOR MagicOS resilience layer, Android 15/16 notification-recovery paths, complete phrase-list backup, redacted diagnostics, permanent signing discipline, and reproducible CI contracts.

The canonical current-state entry point is [`CURRENT_HANDOFF.md`](CURRENT_HANDOFF.md). This document describes the architecture and the rules for preserving it across upstream merges.

## Decision rule

Platform capability is determined from the official Android API contract, AOSP implementation, and the official Shizuku API contract. Emulator and physical-device observations are used to catch implementation regressions and OEM differences; they do not overrule a documented platform capability.

A feature remains only when it has a specific role in reading notifications, maintaining that capability, preserving user configuration, or proving the resulting package. UI messages must describe only what the app actually verified.

Do not claim that a service connection, AppOp command, or Binder connection proves that a particular third-party OTP body was readable.

## Maintained notification paths

### Standard NotificationListenerService

`NotificationListenerService` is the primary path.

The fork:

- records actual `onListenerConnected()` state separately from notification-access permission;
- requests the official listener rebind only when required;
- ignores foreground-service and ongoing notifications;
- processes each notification using one immutable settings snapshot;
- uses bounded settings initialization rather than blocking a listener thread indefinitely;
- shares package+code duplicate suppression with the Accessibility fallback;
- reports disconnected, permission-missing, Accessibility-fallback, and healthy states separately.

On Android 15 and later, sensitive notification content may be withheld from untrusted listeners. AOSP considers the `RECEIVE_SENSITIVE_NOTIFICATIONS` AppOp as one trust input and caches trusted listener UIDs when listener registration changes. The optional repair therefore applies the AppOp first and refreshes listener registration afterward.

### Notification-only Accessibility fallback

`AccessibilityNotificationService` is optional and collapsed under advanced recovery.

It subscribes only to `TYPE_NOTIFICATION_STATE_CHANGED` and accepts an event only when it contains a `Notification` parcelable or uses the official `android.app.Notification` class name. This excludes ordinary Toast events.

It reads only:

- event text and content descriptions;
- event records;
- `Notification.tickerText`;
- public `Notification.EXTRA_*` text fields.

It does not request window content, gestures, key events, screenshots, or accessibility-tool status.

Android may replace a private notification with `Notification.publicVersion` while the device is locked. This path is not an unrestricted lock-screen bypass.

### Optional Shizuku repair

Shizuku is not a notification reader. It is an optional repair path for the standard listener on Android 15+.

The integration:

- uses the official `dev.rikka.shizuku:api` and provider libraries;
- declares `rikka.shizuku.ShizukuProvider` using the official authority and protection permission;
- distinguishes Manager installation, Binder delivery, server UID, API version, and client permission;
- accepts only shell UID 2000 or root UID 0;
- uses a short-lived, non-daemon `UserService` with stable tag/version;
- runs only the narrow AppOp and listener-registration commands;
- restores listener registration when a later repair command fails;
- reports command application and observed reconnect state without claiming OTP readability.

Ordinary app startup does not require Shizuku.

### SMS

The normal flavor retains the upstream SMS receiver. SMS mode is independent of NotificationListenerService, Accessibility, and Shizuku.

Workers and diagnostics are mode-aware. Notification-listener failure must not be reported as a fault while SMS mode is selected.

## Persistence architecture

### PersistenceService

The visible foreground service:

- uses `foregroundServiceType="specialUse"` on supported Android versions;
- declares the special-use subtype in the Manifest;
- calls `startForeground()` immediately;
- returns `START_STICKY`;
- checks standard-listener callback health every minute;
- requests rebind only when permission remains enabled and the listener is actually disconnected;
- schedules AlarmManager recovery after task removal or service destruction.

Its notification is ongoing, silent, badge-free, lock-screen secret, and uses a LOW channel plus `PRIORITY_LOW`. Do not reduce it to minimum priority or hide it from the user.

### WorkManager and receivers

- `PersistenceWatchdogWorker`: 15-minute safety net.
- `RebindListenersWorker`: repairs only the currently selected mode.
- `WatchdogReceiver`: receives AlarmManager recovery broadcasts.
- `BootReceiver`: handles boot completion, user unlock, and package replacement.
- `MonitoringHealthStore`: records actual callbacks and clears stale state when a new process begins.

A WorkManager tick or AlarmManager delivery does not create an exemption from every Android background-start rule. `PersistenceService.start()` must continue to catch failure and fall back to listener rebind.

### Android Force stop

Explicit Settings → Force stop places the package in Android's stopped state. An ordinary application cannot bypass this state. Record this as a platform limitation rather than a MagicOS-resilience failure.

## HONOR and OEM settings

`AutostartHelper` checks candidate Activity metadata before launching it. A candidate is usable only when it exists, is exported or internal, and does not require a permission the app lacks.

MagicOS may still expose an Activity in package metadata and reject the actual launch with `SecurityException`. The helper therefore:

1. tries known HONOR/Huawei App launch Activities;
2. catches runtime launch failures;
3. tries other known OEM targets;
4. opens HONOR/Huawei System Manager itself as the final usable fallback.

The Permissions screen also documents the manual route:

1. Settings → Apps → App launch → OTP Helper;
2. disable automatic management;
3. allow auto-launch, secondary launch, and background execution;
4. lock the OTP Helper card in Recents;
5. exempt OTP Helper from battery optimization.

`MainActivity` must remain visible in Recents. Any reintroduction of `excludeFromRecents=true` is a release-blocking regression.

## Phrase-list backup

Three lists are maintained:

- sensitive/code-detection phrases;
- ignored/exclusion phrases;
- cleanup/removal phrases.

Each phrase screen exposes:

- individual export;
- individual import;
- complete export of all three lists;
- complete import of all three lists.

Native complete format:

```json
{
  "schema": "otphelper.phrases",
  "version": 1,
  "lists": {
    "sensitive_phrases": [],
    "ignored_phrases": [],
    "cleanup_phrases": []
  }
}
```

Single-list import also accepts a JSON string array or UTF-8 text with one phrase per line.

Import must:

- validate schema, version, types, limits, and regular expressions;
- normalize blank entries and duplicates while preserving first occurrence and order;
- stop reading when the file-size limit is exceeded;
- apply a complete backup in one DataStore update;
- preserve all current lists when any complete-backup field is invalid.

## Diagnostics and logging

The Permissions screen can copy or export a diagnostic report containing:

- version, flavor, and device information;
- Recents visibility;
- selected operation mode;
- permission state and actual listener callback state separately;
- Accessibility enablement and actual callback state separately;
- SMS permission state when relevant;
- Shizuku Manager, Binder, API version, server UID, and client permission separately;
- foreground-service, battery-optimization, standby-bucket, and watchdog state;
- bounded rotating logs.

OTP/PIN/code-like values and long standalone numeric runs are redacted before on-device persistence and Logcat output. Exception stack traces pass through the same redaction path.

Do not include raw OTP values in PRs, Notion, issue-like records, or physical-test filenames.

## Android backup and privacy

Android system backup uses an explicit DataStore-only allowlist on both backup-rule formats.

Backed up:

- the complete protobuf DataStore settings object, including phrase lists, behavior/UI settings, and last Detection Test text.

Excluded:

- Room OTP history and WAL/SHM files;
- diagnostic logs;
- WorkManager runtime databases;
- cache and temporary files.

Android 12+ cloud backup requires encryption capabilities. Device-to-device transfer has its own explicit DataStore allowlist; omitting that section would restore Android's broad default.

The full ADB archive used for the one-time signing transition is different: it may contain OTP history and must remain private.

See [`DATA_BACKUP_POLICY.md`](DATA_BACKUP_POLICY.md).

## Internal component protection

`NotifActionReceiver` is protected by the app-defined `io.github.jd1378.otphelper.permission.BROADCAST_CODE` signature permission.

APK inspection verifies:

- the permission declaration;
- symbolic `signature` or compiled `0x2` protection level;
- the receiver's required permission;
- absence of externally leaked experiment components.

The Accessibility service and Shizuku provider retain the binding/export contracts required by their official platform APIs.

## APK identity and permissions

Fork variants retain the upstream base version while adding explicit suffixes:

- normal: `-magic`;
- play: `-magic-play`.

Universal APK filenames use `universal` without a duplicated hyphen.

CI inspects normal/play × debug/release and requires:

- package ID `io.github.jd1378.otphelper`;
- the correct fork version suffix;
- debug APKs to remain debuggable for the signing migration;
- release APKs to be non-debuggable;
- exact flavor-specific permission sets;
- no `INTERNET`;
- no `ACCESS_NETWORK_STATE`;
- no direct `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`;
- no LeakCanary or experiment fixture;
- no Recents exclusion.

An unexpected permission is a release-blocking change until reviewed and added deliberately.

## Permanent signing discipline

Android update identity is part of the product. The old physical-test APK was signed by an ephemeral CI key whose private key is unrecoverable.

Therefore:

- never distribute another ephemeral-key APK;
- create one permanent JKS and preserve at least two independent encrypted backups;
- configure all five required repository Secrets;
- sign debug and release fork APKs with the same permanent identity;
- pin and verify the certificate SHA-256 on every APK;
- restore old app data only after the installed fixed-signed debug APK certificate matches the expected permanent fingerprint;
- prove debug → release and a second higher-version in-place update.

The signing bootstrap:

- refuses overwrite;
- obtains interactive passwords through a secure prompt;
- never writes the password to disk;
- supplies keytool passwords through environment variables;
- creates certificate, fingerprint, Base64 JKS, checksums, and metadata;
- can send the five Secrets through `gh secret set` with exact no-newline standard-input transport.

CI uses a disposable JKS and a fake `gh` executable to verify generation, Base64 reconstruction, JKS reopening, destination repository, and byte-exact Secret transport.

See [`SIGNING_MIGRATION.md`](SIGNING_MIGRATION.md).

## Fork release boundary

Fork releases are GitHub-prerelease-only.

The fixed-signed release workflow:

- runs manually or on a `prereleased` event;
- refuses incomplete signing Secrets;
- builds and inspects all four APK variants;
- verifies every certificate against the pinned fingerprint;
- publishes only normal and play release APKs;
- includes `SHA256SUMS.txt` and `release-metadata.json`;
- uploads release assets through the repository-scoped GitHub CLI;
- does not create an App Bundle or contact Google Play.

Fork APKs and upstream/F-Droid/Play APKs are not interchangeable in place because their signing identities differ.

## Automated validation

### Test

The legacy workflow runs the full Gradle build to preserve upstream compatibility.

### Android CI

Every PR and relevant branch update validates:

- actionlint;
- PowerShell parsing;
- disposable permanent-signing bootstrap;
- normal/play JVM tests;
- normal/play Lint;
- normal/play debug and minified release builds;
- all four APK Manifest, permission, privacy, component, version, and debuggability contracts;
- certificate verification when permanent Secrets exist;
- explicit refusal to upload ephemeral-key APKs;
- API 35 and API 36 service-binding and persistence tests.

### Privacy contracts

The independent workflow validates:

- legacy and Android 12+ DataStore-only backup XML;
- encryption gating for cloud backup;
- no sample/TODO backup rules;
- no committed JKS, keystore, Base64 signing material, or ADB backup directories;
- byte-exact no-newline transport of all five signing Secrets through the fake GitHub CLI.

The workflows use the Node 24 generations of official checkout, Java setup, artifact upload, and Gradle setup Actions. Gradle cache ownership remains with `setup-gradle`; do not restore duplicate setup-java caching.

## Upstream synchronization

The weekly/manual workflow fetches `jd1378/otphelper:main` and attempts a merge into a dated bot branch.

Clean merge:

- push the bot branch;
- open a Draft PR;
- rely on ordinary PR workflows for validation.

Conflict:

- write base/upstream SHAs, unmerged paths, and Git status to `upstream-sync-conflict.md`;
- upload a 90-day artifact;
- add the report to the job summary;
- fail visibly.

GitHub Issues are disabled in this repository, so conflict reporting must not depend on issue creation.

Before merging an upstream-sync PR, review:

- all Manifests and backup XML;
- `App`, `MainActivity`, persistence components, and both notification services;
- Shizuku integration;
- DataStore schema and phrase screens;
- dependencies and generated permissions;
- signing and release workflows;
- version suffixes and APK filenames;
- Japanese and English resources;
- all maintained documentation contracts.

## Physical release gate

Run [`HONOR_PHYSICAL_TEST_PLAN.md`](HONOR_PHYSICAL_TEST_PLAN.md) on the exact firmware.

At minimum, prove:

- visible/lockable Recents task;
- usable App launch or System Manager fallback;
- real third-party OTP through the standard listener;
- Accessibility-only fallback;
- no duplicate handling with both paths;
- 30-minute idle, task removal, and reboot-before-launch;
- first and second permanent-key in-place updates;
- individual and complete phrase backup/restore.

A self-test notification proves the app's extraction and downstream pipeline only. It does not prove Android exposed another app's sensitive OTP body.

## Documentation rule

- `CURRENT_HANDOFF.md`: current state; overwrite when facts change.
- Notion project page: append-only investigation, device, and decision history.
- Draft PR #1: code/CI evidence and merge gate.
- This file: maintained architecture and upstream-preservation rules.

Do not add another contradictory “final state” section. Update the canonical files instead.
