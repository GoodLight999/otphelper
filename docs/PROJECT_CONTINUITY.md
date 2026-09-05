# OTP Helper project continuity

This is the canonical, non-secret handoff for future work on `GoodLight999/otphelper`.
Read this file before changing signing, upstream-sync, OTP extraction, MagicOS persistence, phrase defaults, or release workflows.

## Repository state

- Repository: `GoodLight999/otphelper`
- Working branch: `agent/magicos-resilience-and-backup`
- Integration: Draft PR #1
- Upstream: `jd1378/otphelper`
- Upstream release synced: `v1.20.6` / versionCode `54`
- Upstream `main` language additions through commit `dc5c57cfc031a18d552752961d38c9957827c82a` were reviewed and selectively incorporated.
- Fork version suffixes remain `-magic` and `-magic-play`.

Always refresh upstream release and `main` before assuming these two upstream markers are still current.

## Permanent Android signing identity — DO NOT ROTATE

The fork has one permanent signing identity. Android update continuity depends on preserving this exact private key.

Public certificate SHA-256:

`6f8ad841a7c0aa63a05f6efdb204a20881b4aebee2fee0834c80f84aa6a7b8a9`

The authoritative public pin is committed at:

`.github/signing/otphelper-cert-sha256.txt`

The private JKS, keystore password, and key password MUST NOT be committed to this public repository.
They belong in GitHub Actions repository Secrets and in independent private operator backups.

Required GitHub Actions Secrets are exactly:

- `OTPHELPER_SIGNING_KEYSTORE_B64`
- `OTPHELPER_KEYSTORE_PASSWORD`
- `OTPHELPER_KEY_ALIAS`
- `OTPHELPER_KEY_PASSWORD`

`OTPHELPER_SIGNING_CERT_SHA256` is obsolete as a mutable Secret. Certificate identity is repository-pinned instead.

### Signing invariants

1. Never generate a replacement signing key for routine development or releases.
2. Never change `.github/signing/otphelper-cert-sha256.txt` merely to make CI pass.
3. If a supplied JKS does not match the pinned certificate, reject it before writing any GitHub Secret.
4. If signing Secrets are unavailable, do not publish an installable APK under a disposable key.
5. Both manual artifacts and GitHub prereleases use the same four Secrets and the same certificate pin.
6. Every distributed APK is verified with `apksigner` after build.
7. The bootstrap generator refuses to create a second identity once a repository pin exists.
8. Deliberate signer rotation is a separate migration event and must never occur as an incidental fix.

### Configuring the existing signer

Use `tools/configure-otphelper-signing-secrets.ps1` with an existing backup of the permanent JKS. The helper:

- never generates a key;
- exports the JKS certificate first;
- requires its SHA-256 to equal the repository pin before writing any Secret;
- writes exactly the four private Secret values through `gh secret set` standard input without appending a newline;
- can optionally dispatch ordinary Android CI after successful Secret setup, without creating a release.

Recommended operator command shape only; do not put secret values in chat or source control:

```powershell
pwsh ./tools/configure-otphelper-signing-secrets.ps1 `
  -KeystorePath '<private-path-to-existing-jks>' `
  -TriggerVerificationWorkflow `
  -ConfirmConfigure
```

The default verification dispatch is `ci.yml` on `agent/magicos-resilience-and-backup`. Override with `-VerificationWorkflow` / `-VerificationRef` if those names change. A mismatched JKS must be rejected before any Secret write **or workflow dispatch**; this rejection and the exact four-Secret plus CI-trigger success path are covered by the signing contract test.

After configuration, require the dispatched Android CI run to show `Verify fixed signing keystore`, `Verify fixed signing certificate`, and fixed-signed APK artifact upload executing successfully rather than skipping.

### Private-key recovery across ChatGPT threads

Normal cross-thread work does **not** require reading the private JKS: future threads can read this repository ledger and CI can consume the GitHub Actions Secrets without exposing their values.

A private recovery artifact named `otphelper-signing-vault-PRIVATE.txt` was generated for the operator. Assistant-side File Library search has **not confirmed that this newly generated artifact is indexed project-wide**. Therefore this repository must not assume that future ChatGPT threads can retrieve it automatically.

For guaranteed ChatGPT-side disaster recovery, the operator should add that private vault file to the ChatGPT project's private files/File Library once. A future thread should search for that exact filename only if the four GitHub Secrets must actually be reconstructed. Never quote its secret contents into chat, PRs, issues, commits, logs, or public documentation.

Even if the private vault is temporarily unavailable, do not generate a replacement signer. Restore the existing JKS from another backup or leave distribution blocked.

## Upstream 1.20.6 sync policy

Do not blindly replace fork files with upstream files.

Accepted upstream work includes:

- version `1.20.6` / versionCode `54`;
- Unicode-aware phrase-boundary corrections;
- Persian confirmation-ID phrase fix;
- Turkish `şifreniz` detection from current upstream `main`;
- relevant detection regression coverage.

Known fork-specific divergence that must remain unless proven safe on physical devices:

- do not restore upstream `startService()` calls against `SmsListener` / `NotificationListener`; the fork already identified this pattern as a physical-device crash/regression risk;
- preserve MagicOS/HONOR persistence and Android 15/16 sensitive-notification work already implemented in PR #1.

## OTP extraction policy

The extractor is standards-first and candidate-ranked rather than first-match-only.

Priority rules:

1. WICG/WebOTP origin-bound form (`@domain #code`) outranks heuristic parsing.
2. Rank heuristic candidates primarily by authentication-phrase proximity.
3. Treat code length only as a weak tiebreaker; never let “six digits looks OTP-like” defeat stronger context.
4. Strong default contexts include verification/security/confirmation/authentication/authorization/login/sign-in/access code/passcode/PIN, OTP/2FA/MFA, and explicit Japanese authentication/login/two-factor/one-time wording.
5. Locally penalize or reject competing identifiers such as order, tracking, reservation, invoice, account, card, coupon, product, source/error/status, QR, version/build, serial, postal, and similar IDs.
6. Do not globally blacklist a whole message merely because it also contains a competing identifier; a real OTP may coexist in the same notification.
7. Preserve multilingual phrases and Unicode boundaries.
8. Evaluate notification lines locally before cross-line inference. Bare title/sender/conversation metadata must not borrow an authentication phrase from a separate body field.
9. When structured Android notification fields are available, cross-line fallback uses body fields and excludes title metadata.
10. A pre-phrase numeric matcher must enumerate competing numeric candidates independently instead of consuming from the first number through a later authentication phrase.
11. Preserve legitimate grouped OTP forms such as `123 456`, but do not merge independent long values such as `244080 923030`; current grouped numeric matching joins space-separated groups only when every group is at most three digits.
12. Add regression tests for every false positive or false negative before broadening a generic regex. Real-notification invariants are recorded in `docs/OTP_DETECTION_REGRESSIONS.md`.

The precision-first phrase profile retained in the operator's File Library was reviewed when strengthening these defaults. Its useful authentication contexts and decoy categories were adapted to local candidate ranking rather than copied blindly into global ignore rules.

## Persisted phrase-default migration

Runtime listeners construct `CodeExtractor` from the phrase lists persisted in `UserSettings`, so changing `CodeExtractorDefaults` alone is insufficient for existing installations.

The fork now versions persisted defaults with protobuf field `phrase_defaults_version` and `PhraseDefaultsMigrator.CURRENT_VERSION`.

Migration policy is deliberately conservative:

- a sensitive/ignored/cleanup list is upgraded only when it is byte-for-byte equal to a known historical default snapshot;
- any edit, deletion, import, reordering, empty list, or custom-only list is treated as intentional user configuration and preserved;
- each list is evaluated independently;
- fresh legacy-data migration seeds current defaults and writes the current phrase-default version in the same settings write;
- existing installations enqueue an idempotent `MigratePhraseDefaultsWorker` once when their defaults version is stale;
- reset-to-default continues to use the current `CodeExtractorDefaults` profile.

This allows untouched users to receive stronger defaults automatically without silently restoring phrases that a user deliberately removed.

## Important regression tests

Current tests cover, among other cases:

- origin-bound WebOTP beats human-text decoys;
- explicit verification/login code beats earlier account/order/technical IDs;
- the observed `244080` notification-metadata false positive resolves to body OTP `923030`, including flattened and split-line variants;
- grouped OTPs such as English/Spanish Instagram `123 456` remain normalized to `123456` rather than being broken by the metadata-number fix;
- order/tracking/coupon/source/status codes are rejected without authentication context;
- MFA/access/temporary-passcode and expanded Japanese authentication wording;
- raw word `off` no longer globally suppresses a real OTP notification while percentage-discount wording remains ignorable;
- `versionCode`, `barcode`, and `unicode` do not trigger generic `code` matching;
- Chinese no-space OTP text and Turkish `Şifreniz` work;
- historical untouched default lists upgrade, while edited/empty/custom lists remain unchanged;
- the existing upstream YAML regression suite remains authoritative.

## CI status and expectations

Do not encode a supposed “latest passing SHA” in this handoff. Draft PR #1 and current-head GitHub Actions are the source of truth because every follow-up commit invalidates a previous green run.

For a normal current-head validation require all of these to pass:

- `Test`;
- `Privacy contracts`;
- Android CI `static-build-and-test`;
- Android API 35 emulator smoke test;
- Android API 36 emulator smoke test.

For a **releasable fixed-signed** state, also require a current-head Android CI run with the real four signing Secrets in which pre-build JKS verification, post-build APK certificate verification, and fixed-signed APK artifact upload execute successfully rather than skip.

## Remaining operator-side release dependency

The GitHub connector available to ChatGPT cannot write GitHub Actions Secrets. Therefore the four permanent signing Secrets must be configured from an authenticated operator environment before fixed-signed distributable artifacts can be produced by GitHub Actions.

Use `tools/configure-otphelper-signing-secrets.ps1`; do not use the bootstrap generator to create another key. Prefer its `-TriggerVerificationWorkflow` option so Secret setup and the required fixed-signing verification run are initiated from one operator command. GitHub Secrets are intentionally unreadable after creation; future threads verify presence indirectly from CI behavior.

## Physical-device migration

The previous ephemeral-signed APK cannot be updated in place by the new permanent signer. Preserve app data first and follow `docs/SIGNING_MIGRATION.md` / `tools/otphelper-adb-migration.ps1` for the one-time transition.

After the permanent-signed installation is established, every later build using this identity should update in place normally.

## Future-thread startup checklist

1. Read this file.
2. Read `docs/OTP_DETECTION_REGRESSIONS.md` before changing OTP extraction or notification parsing.
3. Read the newest `docs/NEXT_THREAD_*.md` if present.
4. Inspect Draft PR #1 and current workflow results.
5. Fetch upstream `jd1378/otphelper` current release and `main` before syncing; preserve explicit fork divergences.
6. Never rotate the signing key.
7. Do not request or expose private vault contents unless private-key recovery is actually needed.
8. If private recovery is needed, first look for `otphelper-signing-vault-PRIVATE.txt` in the user's private project/File Library; if unavailable, use another operator backup rather than creating a new signer.
9. Keep this continuity file current whenever architecture, signing identity, phrase-default migration version, release process, OTP invariants, or major physical-device findings change.
