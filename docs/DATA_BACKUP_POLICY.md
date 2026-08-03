# Android data backup policy

OTP Helper handles two materially different classes of local data:

1. user configuration, including phrase lists and behavior settings;
2. detected-code history stored in the Room database.

The Android backup policy intentionally treats them differently.

## Backed up

Only files below the app-private `files/datastore/` directory are included in Android Auto Backup and device-to-device transfer.

This contains the protobuf DataStore user settings, including:

- sensitive/code-detection phrases;
- ignored/exclusion phrases;
- cleanup/removal phrases;
- mode and behavior settings;
- setup and UI preferences.

Cloud backup on Android 12+ is disabled when the device lacks client-side backup encryption capabilities.

## Not backed up

The following are excluded because the rules use an explicit DataStore-only allowlist:

- Room databases and their WAL/SHM files;
- detected OTP/code history;
- diagnostic logs;
- cache and code cache;
- WorkManager runtime databases;
- temporary files;
- signing or migration material, which must never exist inside the app package or repository.

The policy is the same on both Android backup-rule formats:

- Android 12 and later: `res/xml/data_extraction_rules.xml`;
- Android 11 and lower: `res/xml/backup_rules.xml`.

## Cloud backup and device transfer

Android 12+ defines cloud backup and device-to-device transfer separately. OTP Helper explicitly allows only `file/datastore/.` in both sections. Omitting the device-transfer section would allow the platform default to include substantially more app data, so both sections must remain present.

## Manual phrase backup

The in-app individual and complete phrase-list export features are separate from Android system backup. They produce user-selected files through the Storage Access Framework and are the preferred portable, inspectable backup for phrase lists.

A complete phrase backup does not contain OTP history or unrelated app settings.

## Signing-transition ADB backup

`tools/otphelper-adb-migration.ps1` is a narrowly scoped exception for the one unavoidable signing transition. It deliberately archives the full debuggable app-private directory so settings and history can survive uninstalling the unrecoverable ephemeral-signature APK.

That archive may contain OTP history and must be handled as private data. It is not an ordinary cloud backup format and must not be committed to Git, attached to public issues, or retained after the permanent-key migration unless the user deliberately chooses to keep an encrypted recovery copy.

## Automated enforcement

The `Privacy contracts` GitHub Actions workflow parses both XML rule files and requires:

- exactly one DataStore include in legacy Auto Backup;
- exactly one DataStore include in Android 12+ cloud backup;
- exactly one DataStore include in Android 12+ device transfer;
- encrypted-capability gating for cloud backup;
- no remaining sample/TODO rule placeholders.

The main APK inspection also rejects internet permissions and pins the exact expected permission set for normal/play debug/release variants.

## Change rule

Any future proposal to back up Room history or additional directories must be an explicit product decision, must update this document, and must add a narrowly defined include rule. Do not remove the allowlist or rely on Android's broad default backup behavior.
