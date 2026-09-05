# OTP Helper fork maintenance

This fork tracks [`jd1378/otphelper`](https://github.com/jd1378/otphelper) while maintaining a HONOR MagicOS resilience layer, Android 15/16 notification-recovery paths, safer OTP extraction, phrase-list portability, redacted diagnostics, permanent signing discipline, and reproducible CI contracts.

The canonical current-state entry point is [`CURRENT_HANDOFF.md`](CURRENT_HANDOFF.md), which points to [`PROJECT_CONTINUITY.md`](PROJECT_CONTINUITY.md). This file records **stable architectural preservation rules**, not a second copy of changing release/signing state. When exact current state matters, the continuity ledger, Draft PR #1, and current-head CI win.

## Decision rule

Platform capability is determined from the official Android API contract, AOSP implementation, and the official Shizuku API contract. Emulator and physical-device observations catch implementation regressions and OEM differences; they do not justify claims beyond the capability actually observed.

A feature remains only when it has a specific role in reading notifications, maintaining that capability, preserving user configuration, or proving the resulting package. UI messages must describe only what the app actually verified.

Do not claim that a service connection, AppOp command, Binder connection, or self-test notification proves that a particular third-party OTP body was readable.

## Maintained notification paths

### Standard NotificationListenerService

`NotificationListenerService` is the primary notification path.

Preserve these rules:

- actual `onListenerConnected()` state is recorded separately from notification-access permission;
- rebind is requested only when permission remains enabled and the listener is actually disconnected;
- foreground-service and ongoing notifications are ignored as OTP sources;
- one immutable settings snapshot is used for each notification;
- settings initialization is bounded rather than blocking the listener indefinitely;
- package+code duplicate suppression is shared with the Accessibility fallback;
- disconnected, permission-missing, fallback-active, and healthy states remain distinguishable.

On Android 15+, sensitive notification content may be withheld from untrusted listeners. The optional repair applies the narrow `RECEIVE_SENSITIVE_NOTIFICATIONS` AppOp repair first and refreshes listener registration afterward because trust state can be cached around listener registration.

### Notification-only Accessibility fallback

`AccessibilityNotificationService` is optional and belongs under advanced recovery.

It subscribes only to `TYPE_NOTIFICATION_STATE_CHANGED` and accepts notification events, not arbitrary UI events. It may read notification/event text exposed through the Accessibility notification event and public `Notification` fields.

It must not request window content, gestures, key events, screenshots, or accessibility-tool status merely to obtain OTP notifications. Android may expose only `Notification.publicVersion` while locked; do not present this path as an unrestricted lock-screen bypass.

### Optional Shizuku repair

Shizuku is not a notification reader. It is a narrow repair path for the standard listener on Android 15+.

Preserve these rules:

- use the official Shizuku API/provider libraries and provider contract;
- distinguish Manager installation, Binder availability, API version, server UID, and client permission;
- accept only shell UID 2000 or root UID 0 for privileged repair;
- use a short-lived, non-daemon `UserService`;
- run only the intended AppOp and listener-registration repair commands;
- restore listener registration if a later repair command fails;
- report command application and observed reconnect state without claiming OTP readability.

Ordinary app startup must not require Shizuku.

### SMS

The normal flavor retains the upstream SMS receiver. SMS mode is independent of NotificationListenerService, Accessibility, and Shizuku.

Workers and diagnostics remain mode-aware. Notification-listener failure is not an application fault while SMS mode is selected.

## Notification-field and OTP extraction invariants

Notification title/sender/conversation metadata must not borrow authentication wording from a different body field. Evaluate visible lines locally first; structured cross-line fallback uses body fields and excludes title metadata.

The core extractor is standards-first and candidate-ranked:

- WICG/WebOTP origin-bound form outranks heuristic text parsing;
- authentication-phrase proximity is stronger than weak code-shape preferences;
- competing order/tracking/account/coupon/technical identifiers are handled locally rather than globally suppressing a notification that may also contain a real OTP;
- numeric candidates before a later OTP phrase are enumerated independently instead of one regex consuming from an earlier metadata number through the real OTP;
- grouped OTPs such as `123 456` remain supported, while independent long values such as `244080 923030` must not be merged;
- multilingual phrases and Unicode-aware boundaries must be preserved.

Every reported false positive/false negative becomes a regression before generic regex behavior is broadened. Read [`OTP_DETECTION_REGRESSIONS.md`](OTP_DETECTION_REGRESSIONS.md) before changing extraction or notification-field selection.

## Persistence architecture

### PersistenceService

The visible foreground service:

- uses the maintained `specialUse` foreground-service contract on supported Android versions;
- calls `startForeground()` promptly;
- returns `START_STICKY`;
- checks listener callback health every minute;
- requests rebind only when the selected listener should be available but is actually disconnected;
- schedules AlarmManager recovery after task removal or service destruction.

Its notification remains visible, ongoing, silent, badge-free, lock-screen secret, and LOW priority. Do not hide the persistence mechanism behind an invisible/minimum-priority notification.

### WorkManager and receivers

Maintained recovery layers include:

- `PersistenceWatchdogWorker` as a 15-minute safety net;
- mode-aware `RebindListenersWorker`;
- `WatchdogReceiver` for AlarmManager recovery;
- `BootReceiver` for boot completion, user unlock, and package replacement;
- `MonitoringHealthStore` for actual callback health without carrying stale process state forward.

A WorkManager tick or alarm does not bypass Android background-start rules. Persistence startup must continue to handle start denial and fall back to the repair actions Android permits.

### Android Force stop

Explicit Settings → Force stop places the package in Android's stopped state. Ordinary app code cannot self-recover from that state until the user launches/interacts with the package again. Record it as a platform limit, not a MagicOS-resilience failure.

## HONOR and OEM settings

`AutostartHelper` validates candidate activities before launch and catches runtime `SecurityException`/launch failures. Known HONOR/Huawei App launch targets may be tried, with HONOR/Huawei System Manager as a usable fallback.

The manual MagicOS route remains documented:

1. Settings → Apps → App launch → OTP Helper;
2. disable automatic management;
3. allow auto-launch, secondary launch, and background execution;
4. lock the OTP Helper card in Recents;
5. exempt OTP Helper from battery optimization.

`MainActivity` must remain visible in Recents. Reintroducing `excludeFromRecents=true` is a release-blocking regression.

## Phrase-list backup and default migration

Three configurable lists are maintained:

- sensitive/code-detection phrases;
- ignored/exclusion phrases;
- cleanup/removal phrases.

Each phrase screen supports individual import/export and complete import/export of all three lists. Complete backup is versioned JSON; individual import also accepts the documented relaxed formats.

Imports must validate schema/version/types/size/regex rules, preserve first-occurrence order while removing duplicates, and apply a complete restore atomically so one invalid field cannot partially replace settings.

Persisted default-list migration is deliberately conservative. Only byte-for-byte known historical defaults are upgraded automatically. Edited, reordered, imported, emptied, or custom lists are treated as intentional user configuration and preserved. Keep the migration idempotent and versioned.

## Diagnostics and privacy

Diagnostics may report version/flavor/device state, selected mode, permission state, actual listener/Accessibility callback health, SMS state where relevant, Shizuku state dimensions, persistence state, battery/standby state, watchdog state, and bounded logs.

OTP/PIN/code-like values, long standalone numeric runs, and other defined sensitive fields are redacted before diagnostic persistence and Logcat output. Exception logging passes through the same redaction path.

Do not put raw OTP values into PRs, issue-like records, project notes, evidence filenames, or release metadata.

## Android backup boundary

Android system backup and device transfer use explicit DataStore-only allowlists.

Back up the settings DataStore, including phrase lists and user configuration. Exclude Room OTP history, diagnostics, WorkManager runtime state, caches, and temporary files. Android 12+ cloud backup keeps its encryption-capability gate.

The ADB archive used for the one-time signing migration is different: it may contain private app data including OTP history and must remain private.

See [`DATA_BACKUP_POLICY.md`](DATA_BACKUP_POLICY.md).

## Internal component protection

Private/internal activities and notification actions must remain inaccessible to unrelated applications while staying reachable through app-owned explicit intents/PendingIntents.

Preserve:

- private `MainActivity` behind the exported launcher alias;
- non-exported `InternalActionActivity`;
- explicit immutable PendingIntents;
- the app-defined signature permission protecting notification action delivery;
- no external custom-scheme surface merely for internal deep links.

The Accessibility service and Shizuku provider retain only the exported/binding contracts required by their official platform APIs.

## APK identity and permissions

Fork variants retain the upstream base version and add explicit suffixes:

- normal: `-magic`;
- play: `-magic-play`.

CI inspects normal/play × debug/release and requires the expected package ID, version suffixes, debuggability contracts, flavor-specific permissions, and internal component boundaries.

Unexpected permissions are release-blocking until reviewed. The fork intentionally has no `INTERNET` and no `ACCESS_NETWORK_STATE`; do not reintroduce them incidentally through dependencies.

## Permanent signing discipline

Android update identity is part of the product. The original physical-test APK used an unrecoverable ephemeral CI signer, but the fork now has **one existing permanent signing identity**.

Current exact signer state belongs in [`PROJECT_CONTINUITY.md`](PROJECT_CONTINUITY.md) and [`SIGNING_MIGRATION.md`](SIGNING_MIGRATION.md). Stable rules are:

- never rotate/regenerate the permanent signer for routine development, Secret recovery, or CI repair;
- the public certificate SHA-256 is repository-pinned in `.github/signing/otphelper-cert-sha256.txt`;
- the private signer is represented by exactly four GitHub Actions Secrets: JKS Base64, keystore password, alias, and key password;
- certificate identity is **not** a fifth mutable Secret;
- a supplied JKS must match the public pin before any Secret write;
- when Secrets are absent, CI may use a disposable validation key only for non-distributable checks;
- every distributed APK is verified against the pinned certificate after build;
- the one-time old-signer migration verifies the installed APK certificate before destructive restore;
- after migration, prove debug → release and a second higher-version in-place update.

`tools/new-otphelper-signing-key.ps1` is bootstrap-only and intentionally refuses routine replacement-key generation once the repository pin exists. Use `tools/configure-otphelper-signing-secrets.ps1` with an **existing** permanent JKS backup. Its opt-in `-TriggerVerificationWorkflow` path can configure the four Secrets and dispatch ordinary Android CI in one operator command; it does not publish a release.

The signing contract test must prove mismatched-JKS rejection before any Secret/workflow action, exact no-newline four-Secret transport on success, and the requested verification-workflow dispatch.

## Fork release boundary

Fork distribution is GitHub-prerelease-only.

The fixed-signed release path:

- refuses incomplete permanent signing configuration;
- builds and inspects the maintained APK variants;
- verifies every distributable APK certificate against the repository pin;
- publishes only intended normal/play release APKs plus checksums/metadata;
- does not create a Google Play App Bundle or contact Google Play.

Fork APKs and upstream/F-Droid/Play APKs are not interchangeable in place because their signing identities differ.

## Automated validation

### Test

The upstream-compatible test workflow runs the full Gradle build so fork changes continue to satisfy upstream behavior.

### Android CI

Current Android CI validates, among other things:

- actionlint and PowerShell syntax;
- signing bootstrap/configuration contracts;
- normal/play JVM tests and Lint;
- normal/play debug and minified release builds;
- APK Manifest, permission, privacy, component, version, filename, and debuggability contracts;
- fixed-certificate verification when permanent Secrets exist;
- refusal to upload disposable-key APKs;
- API 35 and API 36 service-binding/persistence instrumentation.

### Privacy contracts

The independent privacy workflow validates DataStore-only Android backup rules, encryption gating, absence of committed private signing/app-data/evidence material, signing-transport contracts, and the physical-evidence collector's minimization/redaction/hash behavior.

Do not hard-code a “latest passing SHA” in architecture documentation. Draft PR #1 and current-head workflow results are the validation source of truth.

## Upstream synchronization

Before every upstream sync, refresh both the current release and `jd1378/otphelper:main`; never rely on an old handoff SHA.

The weekly/manual sync workflow attempts a merge into a dated bot branch. A clean merge opens a Draft PR and relies on ordinary PR checks. A conflict records base/upstream SHAs, unmerged paths, and Git status in a retained artifact/job summary and fails visibly.

Review conflicts deliberately. Do not accept `ours` or `theirs` wholesale for architecture-sensitive files. In particular review Manifests/backup XML, persistence and notification paths, Shizuku integration, DataStore/phrase migration, dependencies/permissions, signing/release workflows, version identity, resources, and maintained regression tests.

## Physical release gate

Run [`HONOR_PHYSICAL_TEST_PLAN.md`](HONOR_PHYSICAL_TEST_PLAN.md) on the exact target firmware.

At minimum, prove the visible/lockable Recents task, usable App launch fallback, real third-party OTP through the standard listener, Accessibility-only fallback, duplicate suppression with both paths, idle/task-removal/reboot recovery, phrase backup/restore, and first/second permanent-key in-place updates.

A self-test notification proves extraction/downstream handling only; it does not prove Android exposed another application's sensitive OTP content.

## Documentation ownership

- `CURRENT_HANDOFF.md`: tiny pointer to the canonical state.
- `PROJECT_CONTINUITY.md`: current non-secret project/signing/OTP state.
- `OTP_DETECTION_REGRESSIONS.md`: durable extraction and notification-field invariants.
- `SIGNING_MIGRATION.md`: one-time operator migration runbook.
- `HONOR_PHYSICAL_TEST_PLAN.md`: physical release evidence gate.
- Draft PR #1: implementation/CI merge gate.
- This file: stable architecture and upstream-preservation rules.

Do not add another contradictory “final state” or duplicate exact signing state into an older handoff. Update the canonical files instead.
