# Next-thread priority — 2026-09-02

This note is an urgent continuation marker for the OTP Helper MagicOS fork.

## Priority 0: rebase/sync against the new upstream release

The user reported on 2026-09-02 that upstream `jd1378/otphelper` has published a new version after the fork's previously verified base (`6fd3bbeffd50627dd57844493a8ab203ddd09fdc`).

At the start of the next thread, do **not** continue implementation from the old upstream assumption. First:

1. fetch the current upstream `main`, tags/releases, and exact new version/commit;
2. compare the new upstream against both `6fd3bbe...` and the current fork branch;
3. inventory changed files and behavior before merging;
4. preserve all fork-only MagicOS persistence, Recents/launcher, NotificationListener, Accessibility fallback, Shizuku, SMS, phrase backup/restore, privacy, signing, migration, and CI contracts;
5. resolve conflicts deliberately rather than accepting `ours`/`theirs` wholesale;
6. rerun all JVM, lint, APK contract, privacy, and API 35/36 emulator CI after integration;
7. keep PR #1 Draft until physical-device and signing gates pass.

## Priority 0: permanently solve signing identity

The disposable clean-install APK created during debugging is not a release/update identity. The old installed APK's ephemeral signing key is lost, so there is no cryptographic way to make a new APK update that installation in place.

The next thread must establish one permanent signing identity and stop using disposable keys for user-facing builds:

1. while the old debuggable APK still exists, preserve phrase exports and perform/verify the ADB private-data backup;
2. generate one permanent JKS locally with `tools/new-otphelper-signing-key.ps1`;
3. create at least two independently encrypted backups of the JKS and credentials, stored separately;
4. record the public certificate SHA-256 separately;
5. configure GitHub Actions Secrets:
   - `OTPHELPER_SIGNING_KEYSTORE_B64`
   - `OTPHELPER_KEYSTORE_PASSWORD`
   - `OTPHELPER_KEY_ALIAS`
   - `OTPHELPER_KEY_PASSWORD`
   - `OTPHELPER_SIGNING_CERT_SHA256`
6. run CI and require the fixed-keystore and fixed-certificate verification paths to execute (not skip);
7. download the fixed-signed normal debug APK;
8. only after verified backup, uninstall the old-signature package, install the permanent-key debug APK, and restore with `-ExpectedCertificateSha256`;
9. prove debug -> release in-place update;
10. prove a second higher-version in-place update using the same certificate;
11. thereafter reject any build whose signer differs from the pinned permanent certificate.

Do not publish a release or tell the user to uninstall the current APK before backup verification.

## Existing canonical references

- `docs/CURRENT_HANDOFF.md`
- `docs/SIGNING_MIGRATION.md`
- `docs/HONOR_PHYSICAL_TEST_PLAN.md`
- `docs/DATA_BACKUP_POLICY.md`
- Draft PR #1

When the next thread begins, read this note first, then refresh upstream facts from GitHub rather than trusting the 2026-08-04 upstream snapshot.
