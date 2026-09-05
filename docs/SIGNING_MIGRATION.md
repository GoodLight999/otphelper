# Permanent signing migration

This runbook governs the one-time transition from the unrecoverable ephemeral test signature to the permanent signing identity now selected for this OTP Helper fork.

Android accepts an ordinary in-place APK update only when the new APK is signed by the same signing identity as the installed APK. The old physical-test APK was signed on an ephemeral GitHub Actions runner and its private key no longer exists, so the first transition to the permanent signer requires a guarded reinstall/restore. After that transition, all fork builds must keep the permanent identity below.

## Permanent identity — already created, do not regenerate

Repository: `GoodLight999/otphelper`

Public certificate SHA-256:

`6f8ad841a7c0aa63a05f6efdb204a20881b4aebee2fee0834c80f84aa6a7b8a9`

Authoritative public pin:

`.github/signing/otphelper-cert-sha256.txt`

The generator `tools/new-otphelper-signing-key.ps1` now refuses to create another permanent identity while this repository pin exists. Do not modify the pin merely to make a build pass. Routine builds and releases must restore/use the existing JKS instead.

The permanent fork key is independent from upstream/F-Droid/Play signing keys. An APK signed by one identity cannot normally update an installation signed by another.

## Required GitHub Actions Secrets

Exactly four repository Secrets carry the private signing material:

| Secret | Value |
|---|---|
| `OTPHELPER_SIGNING_KEYSTORE_B64` | Base64 encoding of the permanent JKS |
| `OTPHELPER_KEYSTORE_PASSWORD` | Permanent keystore password |
| `OTPHELPER_KEY_ALIAS` | Alias of the permanent key |
| `OTPHELPER_KEY_PASSWORD` | Password of the permanent key entry |

The certificate SHA-256 is intentionally **not** a mutable Secret. Both pre-build JKS verification and post-build APK verification read the public repository pin above. A legacy `OTPHELPER_SIGNING_CERT_SHA256` environment value, if supplied by an old local script, is accepted only when it exactly equals the repository pin and can never override it.

GitHub repository Secrets are configured under **Settings → Secrets and variables → Actions**, or with `gh secret set`. Never commit the JKS, its Base64 form, or passwords.

## Private-key continuity

Normal future work should not need to read the private key at all: GitHub Actions consumes the four repository Secrets and the repository itself records only the public pin and operating rules.

The operator should also retain private recovery material outside GitHub. The canonical non-secret cross-thread handoff is `docs/PROJECT_CONTINUITY.md`. If the GitHub Secrets ever need to be reconstructed, use the existing operator backup whose JKS certificate hashes to the pinned SHA-256 above. Never solve a missing backup by silently generating a new signer.

## Phase 0 — preserve the old installation

Before replacing the old ephemeral-signed APK:

1. keep the current APK installed;
2. do not clear its data or uninstall it;
3. export the three phrase lists as an additional human-readable backup;
4. confirm `adb shell run-as io.github.jd1378.otphelper id` works when the current build is debuggable.

## Phase 1 — back up private app data

Connect exactly one authorized device and inspect status:

```powershell
pwsh ./tools/otphelper-adb-migration.ps1 -Action Status
```

Create the backup:

```powershell
pwsh ./tools/otphelper-adb-migration.ps1 `
  -Action Backup `
  -BackupDirectory ./otphelper-adb-backup
```

Require `app-data.tar`, `metadata.json`, and `package-dump.txt`, then keep an independent copy before proceeding.

## Phase 2 — ensure the existing permanent key is in GitHub Secrets

Do **not** run the key generator for this repository now. Install/restore the four existing permanent-key Secret values listed above.

Before touching the phone, run CI on `agent/magicos-resilience-and-backup` and require:

1. static/unit/lint/build checks pass;
2. Android API 35 and API 36 emulator jobs pass;
3. permanent-keystore verification runs rather than skips;
4. post-build APK certificate verification runs rather than skips;
5. every distributed APK reports certificate SHA-256 `6f8ad841a7c0aa63a05f6efdb204a20881b4aebee2fee0834c80f84aa6a7b8a9`.

If signing Secrets are absent, CI may use a disposable key only for non-distributable validation. It must refuse to publish that APK as an installable release artifact.

## Phase 3 — replace the old-signature APK once

Only after the backup and fixed-signer CI checks succeed:

1. uninstall the old-signature APK;
2. install the permanent-signed **normal debug** APK;
3. launch it once if Android requires this before `run-as` works;
4. restore with the pinned permanent fingerprint:

```powershell
pwsh ./tools/otphelper-adb-migration.ps1 `
  -Action Restore `
  -BackupDirectory ./otphelper-adb-backup `
  -ExpectedCertificateSha256 '6f8ad841a7c0aa63a05f6efdb204a20881b4aebee2fee0834c80f84aa6a7b8a9' `
  -ConfirmRestore
```

The restore helper verifies the installed APK certificate before destructive data-clear/restore operations. A mismatch must abort.

## Phase 4 — verify restored behavior

Confirm settings/history, phrase lists, notification permission, notification-listener access, battery exemption, Android 15/16 sensitive-notification AppOp where used, MagicOS App launch controls, Recents lock, and any optional Shizuku/Accessibility state required by the installation.

## Phase 5 — prove durable in-place updates

Install a permanent-signed release APK over the restored permanent-signed debug APK without uninstalling or clearing data. Then install a second permanent-signed APK with a higher version code over that installation.

The second update is the proof that this is a durable signing lineage rather than a one-time migration accident.

## Failure rules

Do not proceed when any of these occur:

- the old installation cannot be backed up;
- backup integrity checks fail;
- the permanent JKS is unavailable or its certificate does not equal the repository pin;
- fixed-certificate CI verification skips or fails when a distributable build is intended;
- the installed permanent-signed debug APK does not equal the pinned fingerprint;
- restored DataStore/Room data cannot be opened;
- one permanent-signed APK cannot update another permanent-signed APK in place.

Do not compensate by weakening certificate checks, changing the repository pin to match an accidental key, committing a keystore, or distributing another disposable-signed APK.
