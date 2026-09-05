# Next-thread priority — 2026-09-02

This file is the **self-contained restart contract** for the next ChatGPT thread in the `otphelper` project. Do not rely on conversational memory alone. Read this file, `docs/CURRENT_HANDOFF.md`, Draft PR #1, and the current branch HEAD before doing anything destructive.

## Current exact state

- Repository: `GoodLight999/otphelper`
- Working branch: `agent/magicos-resilience-and-backup`
- Draft PR: #1
- Current branch HEAD at the time of this handoff: `263a8c91543b46e59adfc4416c4ac4a693485c67`
- Previously verified upstream base: `jd1378/otphelper@6fd3bbeffd50627dd57844493a8ab203ddd09fdc`
- The user reported on 2026-09-02 that upstream has released a newer version. Treat the old upstream SHA as historical only and refresh upstream before integration.
- The one-off disposable-signature test APK is **not** the permanent build and should not become an update lineage.
- The currently installed old APK must **not** be uninstalled until its data/phrase backup has been verified.

## Priority 0A — sync the new upstream release first

At the start of the next thread:

1. fetch current `jd1378/otphelper` `main`, releases/tags, and the exact new release commit;
2. compare the new upstream against both `6fd3bbe...` and the current fork branch;
3. inventory all changed files/behaviors before merging;
4. preserve all fork-only MagicOS persistence, Recents/launcher, NotificationListener, Accessibility fallback, Shizuku, SMS, phrase backup/restore, privacy, signing, migration, and CI contracts;
5. resolve conflicts deliberately; never accept `ours` or `theirs` wholesale for affected architectural files;
6. rerun all JVM, lint, APK contract, privacy, and API 35/36 emulator CI after integration;
7. keep PR #1 Draft until permanent-signing and HONOR physical gates pass.

## Priority 0B — permanently solve signing identity

The user's instruction is explicit: **the assistant should own the signing-migration work rather than simply telling the user to run a PowerShell command.** Do as much of the execution as the available connected tools permit, and never pretend the signing problem is solved if a required secret cannot actually be persisted.

Cryptographic facts:

- the private key for the old installed APK is lost;
- therefore no new APK can be made to update that old-signature installation in place;
- the disposable clean-install APK created for debugging uses another temporary key and should be retired;
- the next production/test lineage must start with exactly one permanent signing identity and retain it indefinitely.

Required outcome:

1. preserve phrase exports and verify the ADB private-data backup before removal of the old-signature package;
2. create exactly one permanent RSA-4096 signing JKS with a long validity period;
3. record the public certificate SHA-256;
4. produce at least two independently recoverable encrypted backups of the JKS/credential material before treating the key as permanent;
5. configure the repository's five signing values using the safest available connected mechanism:
   - `OTPHELPER_SIGNING_KEYSTORE_B64`
   - `OTPHELPER_KEYSTORE_PASSWORD`
   - `OTPHELPER_KEY_ALIAS`
   - `OTPHELPER_KEY_PASSWORD`
   - `OTPHELPER_SIGNING_CERT_SHA256`
6. if the connected GitHub surface still cannot write repository secrets, state that exact capability boundary rather than faking success; complete every other step and reduce any unavoidable user action to the smallest possible secret-storage handoff;
7. run CI and require the fixed-keystore and fixed-certificate verification paths to execute, not skip;
8. obtain the fixed-signed normal debug APK;
9. only after verified backup, replace the old-signature installation and restore with `-ExpectedCertificateSha256`;
10. prove debug -> release in-place update;
11. prove a second higher-version in-place update using the same certificate;
12. thereafter reject any build whose signer differs from the pinned permanent certificate.

Never create a permanent signing key only inside an ephemeral runtime and call that solved. The key must have a durable recovery path outside the temporary execution environment.

## Priority 0C — ship a strong default OTP recipe

The user reports frequent false positives in the current app. The fork must ship a substantially safer default detection recipe, not merely expose configuration for the user to fix manually.

Work already started on the branch before this handoff:

- commit `6347fa4c6c4d0922cfa09b2ceafe20fc3f6cf14f` began regression coverage for false-positive contexts;
- commit `263a8c91543b46e59adfc4416c4ac4a693485c67` added candidate-local context handling so a weak identifier and a real OTP in the same notification do not automatically select the wrong token;
- current Japanese defaults were identified as too permissive because generic words such as `コード` and `パスワード` can sit near unrelated 4+ character identifiers.

Design target:

- strong OTP/authentication context should win (`OTP`, `認証コード`, `確認コード`, `ログインコード`, `本人確認`, `verification code`, etc.);
- weak commercial/technical identifiers should not trigger by themselves (order/product/reservation/tracking/build/version/promo/coupon/reference IDs, dates, times, prices, balances, points, phone numbers, hashes, UUID-like strings);
- do **not** solve this by globally ignoring a notification merely because it contains an order/card/reference number: genuine bank/commerce OTP notifications often contain both transaction identifiers and the real OTP;
- rank/evaluate candidate-local context so the real authentication token wins when several number-like strings coexist;
- preserve upstream multilingual OTP coverage;
- add regression tests for both false-positive suppression and true-positive preservation;
- make the safer behavior/default lists apply on a fresh install and remain effective after restoring old user settings where possible.

Before merging upstream, inspect whether the new upstream version changed `CodeExtractor`, phrase defaults, DataStore migration, or tests, then adapt the strong recipe on top of the new implementation rather than blindly carrying old regex code forward.

## Disposable APK status

A prior one-off artifact existed only to give the user an installable clean-install test APK. It was signed with a one-run disposable key and is **not** the permanent lineage. Do not publish it as the fork release and do not design future updates around its certificate.

The old installed APK is different: keep it intact until backup verification because it may contain user data that must be migrated.

## Release gates

Do not merge/publish merely because emulator CI is green. Before release, require:

- current upstream integrated;
- strong default recipe tests green;
- one durable permanent signing identity established;
- fixed-signer CI verification green;
- old-data backup/restore proven;
- debug -> release and second-version in-place updates proven;
- full `docs/HONOR_PHYSICAL_TEST_PLAN.md` completed on the HONOR device.

## Canonical references

- `docs/CURRENT_HANDOFF.md`
- `docs/SIGNING_MIGRATION.md`
- `docs/HONOR_PHYSICAL_TEST_PLAN.md`
- `docs/DATA_BACKUP_POLICY.md`
- `docs/FORK_MAINTENANCE.md`
- `tools/new-otphelper-signing-key.ps1`
- `tools/otphelper-adb-migration.ps1`
- Draft PR #1

When the next thread begins, the correct first instruction is effectively: **read this file, refresh current GitHub/upstream state, then execute Priority 0A/0B/0C without asking the user to repeat the project history.**
