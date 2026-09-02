# Current handoff — OTP Helper MagicOS fork

**Status date:** 2026-09-02 JST  
**Repository:** `GoodLight999/otphelper`  
**Implementation branch:** `agent/magicos-resilience-and-backup`  
**Integration:** Draft PR #1

This file is the canonical project handoff. Do **not** rely on ChatGPT thread memory as the sole source of truth. At every continuation, inspect the current branch HEAD and current upstream before trusting historical SHAs in this document.

For the immediate next-thread execution contract, read [`NEXT_THREAD_2026-09-02.md`](NEXT_THREAD_2026-09-02.md) first.

## September 2 priority override

Three items are now Priority 0 and must be handled before release:

1. **New upstream version:** the user reported on 2026-09-02 that `jd1378/otphelper` has released a newer version than the previously verified upstream base `6fd3bbeffd50627dd57844493a8ab203ddd09fdc`. Refresh upstream `main`, releases/tags, and the exact release commit before integrating anything further.
2. **Permanent signing identity:** the old installed APK's private signing key is lost, and the one-off clean-install APK was also signed with a disposable key. Neither is a viable future update lineage. Establish exactly one durable permanent signing identity and prove repeated in-place updates before release.
3. **False-positive reduction / strong default recipe:** the user reports frequent erroneous OTP detections. The fork must ship safer detection behavior by default rather than requiring manual phrase tuning.

The assistant is expected to own as much of the signing/upstream/recipe execution as the connected tools permit. If a connected service cannot perform a required secret write, state that precise capability boundary; do not report the signing problem as solved until the key has a durable recovery path and the fixed signer is actually exercised by CI.

## Current implementation state

The branch includes:

- MagicOS-oriented foreground persistence and recovery;
- visible, silent, low-priority foreground-service notification;
- private `MainActivity` behind one exported MAIN/LAUNCHER alias;
- visible Recents task and HONOR/System Manager recovery paths;
- foreground persistence service using `specialUse` and `START_STICKY`;
- one-minute notification-listener heartbeat/rebind;
- 15-minute WorkManager watchdog;
- AlarmManager restart safety;
- boot/user-unlock/package-replaced recovery;
- actual listener connection state separated from permission state;
- NotificationListener primary path;
- notification-only Accessibility fallback;
- shared duplicate suppression between ingestion paths;
- SMS path in the normal flavor;
- official Shizuku API/provider integration with a short-lived UserService for supported AppOp repair and rollback;
- individual and all-in-one phrase import/export with versioned validation;
- redacted diagnostics;
- Android system backup restricted to DataStore settings, excluding Room OTP history;
- private explicit immutable PendingIntents and signature-protected internal notification actions;
- private internal action Activity and explicit private MainActivity deep links;
- weekly upstream synchronization with durable conflict artifacts;
- permanent-signing bootstrap and ADB migration tooling;
- GitHub-prerelease-only release workflow, with no Play upload/AAB;
- API 35/36 emulator validation and privacy/signing CI contracts;
- package-scoped HONOR device evidence collection with serial pseudonymization and redaction.

The branch remains **Draft / not distributable** until upstream refresh, permanent signing, safer OTP detection, and HONOR physical release gates are complete.

## Strong default OTP recipe work

The current upstream-derived detector is too permissive in some contexts. In particular, generic Japanese signals such as `コード` and `パスワード` can occur near unrelated identifiers and cause false positives.

Work started on 2026-09-02:

- commit `6347fa4c6c4d0922cfa09b2ceafe20fc3f6cf14f` began false-positive regression coverage;
- commit `263a8c91543b46e59adfc4416c4ac4a693485c67` added candidate-local context handling so a weak identifier and a real OTP in the same notification do not automatically select the wrong token.

Required design:

- strong authentication phrases such as OTP / verification / authentication / login / confirmation / one-time password and strong Japanese equivalents should dominate;
- order/product/reservation/tracking/build/version/promo/coupon/reference IDs, dates, times, prices, balances, points, phone numbers, hashes and UUID-like strings must not trigger merely because they resemble a code;
- never globally discard a notification merely because it also contains a transaction/card/order identifier, because genuine OTP messages often include both;
- evaluate candidate-local context and prefer the true authentication token when multiple number-like strings coexist;
- preserve multilingual upstream true positives;
- maintain regression tests for both false-positive suppression and true-positive preservation;
- safer behavior should be present on fresh install and remain effective after restoring older settings where architecture permits.

When integrating the new upstream release, inspect whether it changed `CodeExtractor`, default phrase lists, DataStore migrations, or tests. Re-apply the behavior against the new implementation rather than blindly retaining the old regex structure.

## Permanent signing state and rules

Cryptographic state:

- the private key for the currently installed old APK is unavailable;
- therefore a new APK cannot cryptographically update that installation in place;
- a previous one-off downloadable APK was generated with another disposable one-run key and is clean-install-only;
- that disposable APK should be retired from the permanent lineage;
- do **not** uninstall the currently installed old APK until its phrase/private-data backup has been verified.

Required permanent sequence:

1. preserve phrase exports and verify the ADB private-data backup while the old debuggable APK still exists;
2. create exactly one permanent RSA-4096 JKS with long validity;
3. create at least two independently recoverable encrypted backups of the JKS and credentials;
4. record the public certificate SHA-256 separately;
5. persist the five signing values required by Actions:
   - `OTPHELPER_SIGNING_KEYSTORE_B64`
   - `OTPHELPER_KEYSTORE_PASSWORD`
   - `OTPHELPER_KEY_ALIAS`
   - `OTPHELPER_KEY_PASSWORD`
   - `OTPHELPER_SIGNING_CERT_SHA256`
6. require CI's fixed-keystore pre-build verification to execute;
7. require every generated APK certificate to match the pinned SHA-256;
8. only after backup verification, replace the old-signature installation with the permanent-key debug APK and restore via `-ExpectedCertificateSha256`;
9. prove debug -> release in-place update;
10. prove a second higher-version in-place update using the same signer;
11. reject any future build whose signer differs from the pinned permanent certificate.

Never call signing solved if the only copy of the private key lives in an ephemeral execution environment.

Full procedure: [`SIGNING_MIGRATION.md`](SIGNING_MIGRATION.md).

## Automated validation already established

Required workflows:

1. **Test** — upstream-compatible Gradle build/test path.
2. **Android CI** — PowerShell parsing/signing bootstrap, JVM tests, Lint, normal/play debug and minified release builds, APK inspection, API 35/36 instrumentation.
3. **Privacy contracts** — backup allowlists, signing-secret transport contracts, rejection of committed private signing/migration material, evidence-collector tests.

A green run can prove the maintained static/emulator contracts, including:

- signing helper generation/reopen logic;
- certificate SHA-256/Base64 coherence;
- all four APK structures/signatures;
- normal/play version suffixes;
- debug/release debuggable contracts;
- exact permission allowlists;
- absence of INTERNET/ACCESS_NETWORK_STATE/REQUEST_IGNORE_BATTERY_OPTIMIZATIONS;
- persistence/listener/Accessibility/Shizuku components;
- restricted exported/private surfaces;
- Recents contract;
- signature-protected internal actions;
- API 35/36 listener and Accessibility binding.

When permanent signing values are absent, ordinary CI may use a disposable key only for structural validation and must refuse user-facing installable artifact distribution. A disposable validation signature is never the release lineage.

CI does **not** prove HONOR proprietary process-killer behavior or real third-party OTP body visibility.

## Disposable APK status

A previous one-off workflow produced an installable clean-install APK solely because the user needed a concrete artifact during development. Its signer is disposable and it is **not** the permanent release/update identity. Do not publish it as the final fork and do not build migration assumptions around its certificate.

The currently installed old APK is different: retain it until data backup verification because it may contain data that needs migration.

## HONOR physical release gate

Run the exact matrix in [`HONOR_PHYSICAL_TEST_PLAN.md`](HONOR_PHYSICAL_TEST_PLAN.md).

Minimum required physical cases:

- launcher/Recents behavior and Recents lockability;
- MagicOS/System Manager recovery guidance;
- real third-party OTP through standard listener;
- Accessibility-only fallback;
- duplicate suppression with multiple paths enabled;
- idle/Doze, task removal, and reboot-before-launch;
- phrase backup/restore;
- permanent-key first and second in-place updates;
- standardized before/after evidence packages.

Use `tools/collect-otphelper-device-evidence.ps1` at persistence/reboot/recovery/update checkpoints. Evidence is package-scoped, serial-hashed by default, excludes Room/DataStore contents and broad notification dumps, and includes file hashes/manifest digest.

Explicit Android Force stop remains a platform limit rather than an app persistence failure.

## Privacy boundaries

- APK has no internet/network-state permission.
- Android cloud/device-transfer backup includes only `files/datastore/`.
- Room OTP history is excluded from system backup.
- ADB signing-migration archives may contain OTP history and must remain private.
- Physical evidence excludes database/settings contents and broad notification dumps.
- Optional logcat is PID-scoped and redacted but must still be inspected before sharing.
- JKS, Base64 private key material, signing passwords, ADB backup directories, and physical evidence directories must not be committed publicly.

Full policy: [`DATA_BACKUP_POLICY.md`](DATA_BACKUP_POLICY.md).

## Upstream integration rule

Before accepting the new upstream version, review at minimum:

- all Manifests and backup XML;
- `App`, `MainActivity`, persistence components, NotificationListener and Accessibility service;
- exported/private boundaries and PendingIntents;
- Shizuku integration;
- `CodeExtractor`, detection defaults and detection tests;
- phrase screens, repositories and DataStore schema/migrations;
- Gradle/dependency/permission changes;
- release/signing workflows;
- maintenance tools and private-output ignore rules;
- Japanese and English resources.

Resolve conflicts deliberately and rerun all current CI gates.

## Where to resume in a new thread

1. Read [`NEXT_THREAD_2026-09-02.md`](NEXT_THREAD_2026-09-02.md).
2. Read this file.
3. Read Draft PR #1 and current workflow results.
4. Inspect the current branch HEAD; do not trust a historical hard-coded head SHA.
5. Refresh current upstream `main` plus releases/tags before merging.
6. Continue Priority 0 upstream/signing/recipe work without asking the user to repeat project history.
7. Do not merge/publish solely because emulator CI is green.
8. Do not uninstall the old physical APK before verified backup.

## Documentation index

- [`NEXT_THREAD_2026-09-02.md`](NEXT_THREAD_2026-09-02.md) — immediate continuation contract
- [`FORK_MAINTENANCE.md`](FORK_MAINTENANCE.md) — architecture/maintenance rules
- [`PLATFORM_CONTRACTS.md`](PLATFORM_CONTRACTS.md) — platform API basis
- [`SIGNING_MIGRATION.md`](SIGNING_MIGRATION.md) — permanent signing/migration runbook
- [`HONOR_PHYSICAL_TEST_PLAN.md`](HONOR_PHYSICAL_TEST_PLAN.md) — physical validation matrix
- [`DATA_BACKUP_POLICY.md`](DATA_BACKUP_POLICY.md) — backup/privacy policy
- [`../tools/README.md`](../tools/README.md) — signing, migration and evidence helper commands
