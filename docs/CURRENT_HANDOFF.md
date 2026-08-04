# Current handoff — OTP Helper MagicOS fork

**Status date:** 2026-08-04 JST  
**Repository:** `GoodLight999/otphelper`  
**Implementation branch:** `agent/magicos-resilience-and-backup`  
**Integration:** Draft PR #1  
**Upstream base:** `jd1378/otphelper@6fd3bbeffd50627dd57844493a8ab203ddd09fdc`

This file is the canonical current-state handoff. Historical investigation and test records remain in the Notion project page and PR discussion. When facts change, update this file rather than appending another contradictory “final” section elsewhere.

## Current branch state

The latest implementation includes:

- MagicOS-oriented foreground persistence and recovery;
- a visible, silent, low-priority foreground-service notification rather than a hidden/minimum-priority event;
- a private `MainActivity` behind one exported MAIN/LAUNCHER alias;
- private internal repair/settings Activity and notification-action Receiver surfaces;
- explicit immutable PendingIntents for internal navigation and notification actions;
- Recents visibility and HONOR System Manager fallback;
- standard notification listener, notification-only Accessibility fallback, SMS path, and optional Shizuku repair;
- complete individual/all phrase-list import and export;
- redacted diagnostics and actual service-connection tracking;
- guarded permanent-signing bootstrap and ADB data migration;
- exact normal/play debug/release APK permission contracts;
- explicit fork version suffixes: `-magic` and `-magic-play`;
- normalized universal APK filenames;
- Android system backup restricted to the DataStore settings object, excluding Room OTP history;
- a redacted, manifest-hashed ADB evidence collector for HONOR physical checkpoints;
- weekly upstream synchronization with durable conflict artifacts when a clean merge is impossible;
- GitHub-prerelease-only fork distribution with APK checksums and public signing metadata;
- API 35/36 emulator validation and multiple privacy/signing CI contracts.

The branch remains **Draft / not distributable** until the permanent signing identity and HONOR physical release gates are complete.

## What is proven automatically

The required workflows are:

1. **Test** — legacy Gradle build compatibility.
2. **Android CI** — PowerShell parsing and signing-bootstrap execution, JVM tests, Lint, normal/play debug and minified release builds, APK inspection, API 35 and API 36 emulator tests.
3. **Privacy contracts** — backup-rule allowlists, exact no-newline GitHub Secret transport through a fake `gh`, and rejection of committed private signing/migration material.

The workflows use the current Node 24 generations of GitHub's official checkout, Java setup, artifact upload, and Gradle setup Actions. Gradle caching uses the explicit open-source/basic provider rather than duplicate setup-java and setup-gradle caches.

A successful Android CI proves:

- all PowerShell maintenance tools parse, including signing, migration, and device-evidence helpers;
- the permanent signing helper can generate and reopen a disposable JKS;
- certificate SHA-256 and Base64 JKS output are coherent;
- all five signing Secret values can be written to `gh secret set` through standard input without gaining a trailing newline;
- normal/play unit tests and Lint pass;
- all four APK variants build;
- all four APKs are structurally valid and signed during validation;
- normal APKs end in `-magic` and play APKs end in `-magic-play`;
- debug APKs remain debuggable for the one-time migration;
- release APKs are non-debuggable;
- exact permission sets match the maintained allowlists;
- INTERNET, ACCESS_NETWORK_STATE, and REQUEST_IGNORE_BATTERY_OPTIMIZATIONS are absent;
- required persistence, listener, Accessibility, and Shizuku components exist;
- exported surface is limited to the launcher alias and system-bound components;
- `MainActivity`, internal actions, and notification actions remain private;
- MainActivity is not excluded from Recents;
- internal notification actions remain signature-protected;
- LeakCanary and experiment fixtures are absent;
- standard listener and Accessibility service actually bind on API 35 and API 36 emulators.

When the permanent signing Secrets are absent, Android CI uses a disposable key only to make all four APKs inspectable, then explicitly refuses artifact upload. A disposable validation APK is never a distribution channel.

It does **not** prove HONOR proprietary process-killer behavior or that a real third-party OTP body is exposed by Android privacy rules.

## Permanent signing blocker

The physical-test APK installed on the HONOR device was signed by an ephemeral GitHub Actions debug key. That private key no longer exists, so no future APK can update it in place.

Do not distribute another ephemeral-key APK.

Required sequence:

1. preserve phrase-list exports;
2. run the ADB private-data backup while the current APK is still installed and debuggable;
3. generate one permanent JKS with `tools/new-otphelper-signing-key.ps1`;
4. create at least two independently encrypted backups of the JKS and password;
5. configure the five GitHub Actions signing Secrets;
6. require fixed-certificate verification to run and pass in CI;
7. install the fixed-signed normal debug APK after uninstalling the old-signature APK;
8. restore with `-ExpectedCertificateSha256` so data cannot be cleared under the wrong signer;
9. update debug → release in place;
10. perform a second higher-version in-place update.

Full procedure: [`SIGNING_MIGRATION.md`](SIGNING_MIGRATION.md).

## Fork release boundary

The fixed-signed release workflow:

- runs manually or when a GitHub **prerelease** is created;
- refuses to run when any signing Secret is absent;
- verifies every APK against the pinned certificate;
- publishes normal and play release APKs only;
- includes `SHA256SUMS.txt` and `release-metadata.json` with the source commit and public certificate SHA-256;
- uploads assets with the repository-scoped GitHub CLI rather than a third-party release Action;
- never creates or uploads a Google Play App Bundle.

The fork uses an independent signing identity and is not an update path for upstream/F-Droid/Google Play installations.

## HONOR physical blocker

Run the exact firmware matrix in [`HONOR_PHYSICAL_TEST_PLAN.md`](HONOR_PHYSICAL_TEST_PLAN.md).

Minimum release-gating cases include:

- visible and lockable Recents task;
- usable App launch/System Manager path;
- real third-party OTP through standard listener;
- Accessibility-only fallback;
- duplicate suppression with both paths enabled;
- 30-minute idle, task removal, and reboot-before-launch;
- first and second permanent-key in-place updates;
- individual and complete phrase backup/restore.

Use `tools/collect-otphelper-device-evidence.ps1` before and after persistence, reboot, recovery, and update cases. The collector records only package-scoped state, hashes the device serial by default, excludes notification/database contents, and creates a file-hashed manifest. Every failed case requires an in-app diagnostics export and an `after-fail` ADB evidence package.

Explicit Android Force stop is recorded as a platform limit, not as an app persistence failure.

## Privacy contracts

- The APK has no internet or network-state permission.
- Android cloud backup and device transfer include only `files/datastore/`.
- That DataStore object includes phrase lists, behavior/UI settings, and the last Detection Test text.
- Room OTP history is excluded from Android system backup.
- ADB signing-migration archives may contain OTP history and must remain private.
- ADB physical-test evidence excludes database/settings contents and broad notification dumps.
- Optional evidence logcat is PID-scoped and redacted, but must still be inspected before sharing.
- JKS, keystore, Base64 key material, ADB backup directories, and physical evidence directories must not be committed.

Full policy: [`DATA_BACKUP_POLICY.md`](DATA_BACKUP_POLICY.md).

## Upstream maintenance

The scheduled workflow merges `jd1378/otphelper:main` into a dated bot branch and opens a Draft PR for clean merges. Pull-request workflows perform validation automatically.

GitHub Issues are disabled in this repository. A merge conflict therefore produces a 90-day `upstream-sync-conflict` artifact containing base/upstream SHAs, unmerged paths, and Git status; the workflow then fails visibly and writes the report to the job summary.

Before accepting an upstream sync, review:

- all Manifests and backup XML;
- `App`, `MainActivity`, persistence components, and both notification services;
- exported/private component boundaries and PendingIntents;
- Shizuku API integration;
- phrase screens, repositories, and DataStore schema;
- Gradle dependencies and permission changes;
- release/signing workflows;
- exact APK permission and version-suffix contracts;
- maintenance tools and their private-output ignore rules;
- Japanese and English resources.

## Where to resume

1. Read this file.
2. Read the latest Draft PR #1 conversation and workflow results.
3. Read the final section of the Notion project page for device-specific history.
4. Inspect the current branch head rather than trusting an older SHA written in historical notes.
5. Do not merge or publish solely because emulator CI is green.
6. Do not uninstall the current physical-test APK before its ADB backup has been verified.

## Documentation index

- [`FORK_MAINTENANCE.md`](FORK_MAINTENANCE.md) — architecture and maintenance rules
- [`PLATFORM_CONTRACTS.md`](PLATFORM_CONTRACTS.md) — official API/AOSP/Shizuku basis
- [`SIGNING_MIGRATION.md`](SIGNING_MIGRATION.md) — permanent signing runbook
- [`HONOR_PHYSICAL_TEST_PLAN.md`](HONOR_PHYSICAL_TEST_PLAN.md) — physical validation matrix and evidence requirements
- [`DATA_BACKUP_POLICY.md`](DATA_BACKUP_POLICY.md) — Android backup/privacy policy
- [`../tools/README.md`](../tools/README.md) — signing, migration, and evidence helper commands
