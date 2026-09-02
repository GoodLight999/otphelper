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
3. If a supplied JKS does not match the pinned certificate, reject it.
4. If signing Secrets are unavailable, do not publish an installable APK under a disposable key.
5. Both manual artifacts and GitHub prereleases use the same four Secrets and the same certificate pin.
6. Every distributed APK is verified with `apksigner` after build.
7. The bootstrap generator refuses to create a second identity once a repository pin exists.
8. Deliberate signer rotation is a separate migration event and must never occur as an incidental fix.

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
8. Add regression tests for every false positive or false negative before broadening a generic regex.

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
- order/tracking/coupon/source/status codes are rejected without authentication context;
- MFA/access/temporary-passcode and expanded Japanese authentication wording;
- raw word `off` no longer globally suppresses a real OTP notification while percentage-discount wording remains ignorable;
- `versionCode`, `barcode`, and `unicode` do not trigger generic `code` matching;
- Chinese no-space OTP text and Turkish `Şifreniz` work;
- historical untouched default lists upgrade, while edited/empty/custom lists remain unchanged;
- the existing upstream YAML regression suite remains authoritative.

## CI status and expectations

At commit `912da3ee0f5343229cb2b2be54e3fd8561c33796`, Android CI completed successfully, including the static build/test job and Android API 35/36 emulator smoke tests.

A contemporaneous Privacy contracts run failed only because its signing-bootstrap smoke test still generated a throwaway certificate while production verification had just become repository-pinned. The bootstrap test was subsequently updated to use an isolated temporary pin without weakening production verification, and the corrected Privacy contracts run passed.

For a releasable state require all current-head workflows to pass, then require a run with the real fixed signing Secrets where certificate-verification steps execute rather than skip.

## Remaining operator-side release dependency

The GitHub connector available to ChatGPT cannot write GitHub Secrets. Therefore the four permanent signing Secrets must exist in repository Actions Secrets before fixed-signed distributable artifacts can be produced by GitHub Actions.

Once Secrets are installed, future threads should verify their presence indirectly by running CI/release workflows and confirming that fixed-signing verification executes successfully. GitHub Secrets are intentionally unreadable after creation.

## Physical-device migration

The previous ephemeral-signed APK cannot be updated in place by the new permanent signer. Preserve app data first and follow `docs/SIGNING_MIGRATION.md` / `tools/otphelper-adb-migration.ps1` for the one-time transition.

After the permanent-signed installation is established, every later build using this identity should update in place normally.

## Future-thread startup checklist

1. Read this file.
2. Read the newest `docs/NEXT_THREAD_*.md` if present.
3. Inspect Draft PR #1 and current workflow results.
4. Fetch upstream `jd1378/otphelper` current release and `main` before syncing; preserve explicit fork divergences.
5. Never rotate the signing key.
6. Do not request or expose private vault contents unless private-key recovery is actually needed.
7. If private recovery is needed, first look for `otphelper-signing-vault-PRIVATE.txt` in the user's private project/File Library; if unavailable, use another operator backup rather than creating a new signer.
8. Keep this continuity file current whenever architecture, signing identity, phrase-default migration version, release process, or major physical-device findings change.
