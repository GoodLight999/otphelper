# OTP Helper project continuity

This is the canonical, non-secret handoff for future work on `GoodLight999/otphelper`.
Read this file before changing signing, upstream-sync, OTP extraction, MagicOS persistence, or release workflows.

## Repository state

- Repository: `GoodLight999/otphelper`
- Working branch: `agent/magicos-resilience-and-backup`
- Integration: Draft PR #1
- Upstream: `jd1378/otphelper`
- Upstream release synced: `v1.20.6` / versionCode `54`
- Upstream `main` language additions through commit `dc5c57cfc031a18d552752961d38c9957827c82a` were reviewed and selectively incorporated.
- Fork version suffixes remain `-magic` and `-magic-play`.

## Permanent Android signing identity — DO NOT ROTATE

The fork now has one permanent signing identity. Android update continuity depends on preserving this exact private key.

Public certificate SHA-256:

`6f8ad841a7c0aa63a05f6efdb204a20881b4aebee2fee0834c80f84aa6a7b8a9`

The authoritative public pin is committed at:

`.github/signing/otphelper-cert-sha256.txt`

The private JKS, keystore password, and key password MUST NOT be committed to this public repository.
They belong in GitHub Actions repository Secrets and in the operator's private backup vault.

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
8. A deliberate signer rotation is a separate migration event that breaks ordinary in-place update continuity and must never be performed as an incidental fix.

### Private-key recovery across ChatGPT threads

The operator maintains a private File Library artifact named:

`otphelper-signing-vault-PRIVATE.txt`

Future ChatGPT threads working on this project should search the user's File Library for that exact filename only when actual private-key recovery is necessary. Do not quote its secret contents into chat, PRs, issues, commits, logs, or public documentation.

Normal CI/release work should use the GitHub Actions Secrets without reading the private vault at all.

## Upstream 1.20.6 sync policy

Do not blindly replace fork files with upstream files.

Accepted upstream work includes:

- version `1.20.6` / versionCode `54`;
- Unicode-aware phrase-boundary corrections;
- Persian confirmation-ID phrase fix;
- Turkish `şifreniz` detection from current upstream `main`;
- relevant detection regression coverage.

Known fork-specific divergence that must remain unless proven safe on physical devices:

- do not restore upstream `startService()` calls against `SmsListener` / `NotificationListener`; the fork already identified this pattern as a physical-device crash/regression risk.
- preserve MagicOS/HONOR persistence and Android 15/16 sensitive-notification work already implemented in PR #1.

## OTP extraction policy

The extractor is intentionally standards-first and candidate-ranked rather than first-match-only.

Priority rules:

1. WICG/WebOTP origin-bound form (`@domain #code`) outranks heuristic parsing.
2. For heuristic text, rank candidate codes by authentication-phrase proximity.
3. Treat code length only as a weak tiebreaker; never let 'six digits looks OTP-like' defeat stronger context.
4. Locally penalize or reject competing identifiers such as order, tracking, reservation, invoice, account, card, coupon, product, version, build, serial, postal, and similar IDs.
5. Do not globally blacklist a whole message merely because it also contains a competing identifier; a real OTP may coexist in the same notification.
6. Preserve multilingual phrases and Unicode boundaries.
7. Add regression tests for every false positive or false negative before broadening a generic regex.

Important current tests include:

- origin-bound WebOTP beats human-text decoys;
- explicit verification code beats earlier account/order IDs;
- order/tracking/coupon codes are rejected without authentication context;
- `versionCode`, `barcode`, and `unicode` do not trigger generic `code` matching;
- Chinese no-space OTP text works;
- Turkish `Şifreniz` works;
- existing upstream YAML regression suite remains authoritative.

## CI status and expectations

At commit `912da3ee0f5343229cb2b2be54e3fd8561c33796`, Android CI completed successfully, including the static build/test job and Android API 35/36 emulator smoke tests.

A contemporaneous Privacy contracts run failed only because its signing-bootstrap smoke test still generated a throwaway certificate while the production verifier had just become repository-pinned. The production signing behavior was correct; the test contract was stale. The bootstrap test has since been updated to use an isolated temporary pin without weakening production verification.

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
7. Keep this continuity file current whenever architecture, signing identity, release process, or major physical-device findings change.
