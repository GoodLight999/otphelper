# Permanent signing migration

This runbook governs the one-time transition from the unrecoverable ephemeral debug signature to one permanent signing identity for the OTP Helper fork.

Android accepts an in-place APK update only when the new APK is signed by the same signing identity as the installed APK. The physical-test APK was signed on an ephemeral GitHub Actions runner, and its private key no longer exists. Its public certificate cannot be used to reconstruct that private key.

The permanent fork key therefore starts a new, durable update lineage. Once selected, do not rotate it casually. Losing it makes future in-place updates of fork builds impossible.

## Scope and identity

- package ID remains `io.github.jd1378.otphelper`;
- the permanent fork key is not the upstream project's signing key;
- permanent-key fork builds cannot update an upstream/F-Droid/Play APK signed by a different identity;
- upstream/F-Droid/Play builds cannot update a permanent-key fork installation;
- both fork debug and release APKs use the same permanent identity so the migration can restore private data through a debuggable APK and then update to release in place.

## Required repository Secrets

The workflows require all five values before distributing an APK:

| Secret | Value |
|---|---|
| `OTPHELPER_SIGNING_KEYSTORE_B64` | Base64 encoding of the permanent JKS file |
| `OTPHELPER_KEYSTORE_PASSWORD` | Permanent keystore password |
| `OTPHELPER_KEY_ALIAS` | Alias of the permanent key |
| `OTPHELPER_KEY_PASSWORD` | Password of the permanent key |
| `OTPHELPER_SIGNING_CERT_SHA256` | 64-hex SHA-256 digest of the signing certificate |

The current bootstrap helper deliberately uses the same strong password for the JKS and key entry. The workflow keeps separate Secret names because Gradle signing configuration requires both fields and a future controlled migration may choose different values.

GitHub repository Secrets are configured under **Settings → Secrets and variables → Actions**, or with `gh secret set`. Never put the JKS, Base64 value, or password in commits, pull-request text, issue comments, Actions variables, logs, or ordinary cloud attachments.

## Phase 0 — do not disturb the current installation

Before the permanent key exists:

1. keep the current physical-test APK installed;
2. do not clear its data;
3. do not uninstall it;
4. export the three phrase lists from the app as an additional human-readable backup;
5. confirm that `adb shell run-as io.github.jd1378.otphelper id` works.

The private-data migration requires the currently installed APK and the first permanent-key APK to be debuggable while their respective backups or restores are performed.

## Phase 1 — back up the current installation

Connect exactly one authorized device, then run:

```powershell
pwsh ./tools/otphelper-adb-migration.ps1 -Action Status
```

Record the reported installed certificate and permission state. Then create the private-data backup:

```powershell
pwsh ./tools/otphelper-adb-migration.ps1 `
  -Action Backup `
  -BackupDirectory ./otphelper-adb-backup
```

Verify that the backup directory contains:

- `app-data.tar`;
- `metadata.json`;
- `package-dump.txt`.

Copy the entire backup directory to a second encrypted location before proceeding. It may contain settings and OTP history.

## Phase 2 — create the permanent key

Run the guarded bootstrap helper on a trusted local machine with JDK 17+ and PowerShell 7:

```powershell
pwsh ./tools/new-otphelper-signing-key.ps1 `
  -OutputDirectory ./otphelper-signing-output `
  -ConfirmCreate
```

The helper:

- refuses to overwrite a non-empty directory;
- obtains the password through secure prompts;
- passes the password to `keytool` through an environment variable rather than a command-line value;
- creates a 4096-bit RSA JKS key with a long validity period;
- exports the public certificate;
- derives the certificate SHA-256 with the .NET certificate API;
- creates the Base64 representation expected by GitHub Actions;
- writes checksums and a non-secret manifest;
- never writes the password to disk;
- applies restrictive Unix file modes when supported.

To configure the repository Secrets through an authenticated GitHub CLI in the same operation:

```powershell
pwsh ./tools/new-otphelper-signing-key.ps1 `
  -OutputDirectory ./otphelper-signing-output `
  -Repository GoodLight999/otphelper `
  -ConfigureGitHubSecrets `
  -ConfirmCreate
```

The Secret values are sent to `gh secret set` through standard input. They are not placed in process arguments.

## Phase 3 — preserve the key independently

Before building or uninstalling anything, create at least two independent encrypted backups of:

- `otphelper-permanent-signing.jks`;
- the password;
- the alias;
- `otphelper-signing-certificate.pem`;
- `otphelper-signing-certificate-sha256.txt`;
- `SHA256SUMS.txt`.

At least one backup must be offline or otherwise independent of the primary computer and GitHub account. Verify the copied JKS hash against `SHA256SUMS.txt`.

The public certificate and fingerprint are not secret. The JKS and password are secret.

## Phase 4 — validate CI signing before touching the device

After setting all five Secrets:

1. manually dispatch **Android CI** on `agent/magicos-resilience-and-backup`;
2. require the static, API 35, and API 36 jobs to pass;
3. require **Verify fixed signing certificate** to run and pass rather than be skipped;
4. require the `otphelper-magic-os-fixed-signed-apks` artifact to exist;
5. download the normal debug APK;
6. run `apksigner verify --verbose --print-certs <apk>` locally;
7. verify that the reported signer SHA-256 exactly equals `OTPHELPER_SIGNING_CERT_SHA256`.

Do not proceed if the fixed-certificate step is skipped, the artifact is absent, or the fingerprint differs.

## Phase 5 — replace the old-signature APK

Only after Phases 1–4 are complete:

1. keep the ADB backup and permanent key backups available;
2. uninstall the old-signature APK because Android cannot update it in place;
3. install the fixed-signed **normal debug** APK;
4. launch it once if Android requires this before `run-as` becomes available;
5. confirm `run-as` works;
6. restore with the pinned permanent fingerprint:

```powershell
pwsh ./tools/otphelper-adb-migration.ps1 `
  -Action Restore `
  -BackupDirectory ./otphelper-adb-backup `
  -ExpectedCertificateSha256 '<64-hex permanent certificate SHA-256>' `
  -ConfirmRestore
```

Before calling `pm clear`, the restore helper pulls the installed base APK, invokes `apksigner`, and compares the installed certificate against the expected permanent fingerprint. Any mismatch aborts without clearing data.

## Phase 6 — verify the restored debug installation

Confirm all of the following before installing release:

- settings and history are present;
- all three phrase lists match their exported backups;
- notification permission is granted;
- notification-listener access is enabled and the listener reports connected;
- battery optimization is exempted;
- `RECEIVE_SENSITIVE_NOTIFICATIONS` AppOp is allowed when it was previously enabled;
- MagicOS App launch switches are enabled;
- the OTP Helper Recents card is locked;
- Shizuku client permission is re-approved when used;
- Accessibility is manually re-enabled only when that fallback is desired.

The migration tool intentionally does not automate Accessibility enablement or proprietary MagicOS controls.

## Phase 7 — prove in-place updates

Build or obtain a fixed-signed release APK from the same permanent key. Install it over the restored fixed-signed debug APK without uninstalling or clearing data.

Then build a second fixed-signed APK with a higher version code and install it over the first release. This second update proves that the permanent update lineage is reusable rather than a one-time accident.

Record the permanent certificate SHA-256 in the Notion handoff page and PR, but never record the JKS or password.

## Failure rules

Stop immediately when any of these occur:

- the current installation is no longer debuggable before backup;
- `app-data.tar` hash validation fails;
- the permanent JKS has fewer than two verified backups;
- CI fixed-certificate verification is skipped or fails;
- the installed debug APK certificate does not equal the pinned fingerprint;
- `run-as` is unavailable on the permanent-key debug APK;
- restored DataStore or Room data cannot be opened;
- a fixed-key APK cannot update another fixed-key APK in place.

Do not compensate by weakening certificate checks, committing a keystore, or distributing another ephemeral-key APK.

## Source basis

Android's official signing documentation defines the app signing key as part of Android's secure update model and states that it is used for the lifetime of the app. GitHub's official Actions documentation requires sensitive workflow values to be stored as Secrets rather than ordinary repository content or variables.
