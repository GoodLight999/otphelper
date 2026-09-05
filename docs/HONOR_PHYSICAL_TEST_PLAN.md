# HONOR MagicOS physical validation plan

This document is the release-gating test plan for the exact HONOR firmware that originally killed OTP Helper. Emulator tests validate Android contracts; this plan validates proprietary MagicOS process management and real third-party notification behavior.

## Target device record

Complete this section before each run.

| Field | Value |
|---|---|
| Device model | DNP-NX9 |
| Android version / SDK | Android 16 / API 36 |
| MagicOS build | record from Settings |
| OTP Helper commit | record full SHA |
| Version name / code | record from diagnostics |
| APK flavor | normal unless a play-specific test is required |
| APK certificate SHA-256 | record from `apksigner` or migration Status |
| Standard Shizuku or fork | record package/version/server UID |
| Test date and tester | record |

Never compare results from different firmware builds without recording the build identifiers.

## Evidence package

For every failed case, export the in-app diagnostics report immediately and name it:

```text
otphelper-<commit-short>-<case-id>-<yyyyMMdd-HHmm>-<pass-or-fail>.txt
```

When ADB is available, capture a standardized package before and after every persistence, reboot, update, or recovery case:

```powershell
pwsh ./tools/collect-otphelper-device-evidence.ps1 `
  -TestLabel HN-08-before `
  -Compress

# perform exactly one test case

pwsh ./tools/collect-otphelper-device-evidence.ps1 `
  -TestLabel HN-08-after-pass `
  -Compress
```

Use `<case-id>-after-fail` for a failure. The collector records package/process/service, AlarmManager, JobScheduler, AppOp, listener, Accessibility, standby-bucket, and device-build state in a fixed format. It does not collect Room/DataStore contents, broad notification dumps, notification text from other apps, or unrelated listener/Accessibility/whitelist package names.

Logcat is excluded by default. Add it only for one narrowly scoped failure while OTP Helper is running:

```powershell
pwsh ./tools/collect-otphelper-device-evidence.ps1 `
  -TestLabel HN-11-after-fail `
  -IncludeRedactedLogcat `
  -Compress
```

The optional log is limited to the current OTP Helper PID and receives an additional numeric/code/email/phone/token redaction pass. The raw device serial is hashed by default. Inspect every diagnostic and evidence archive before attaching it to a PR or sharing it outside the private project.

Recommended evidence pairs:

- HN-08/HN-09/HN-10: immediately before screen-off idle and immediately after the OTP attempt;
- HN-11/HN-12: immediately before process/task removal and after the recovery deadline;
- HN-14: before reboot and after post-unlock stabilization;
- HN-15/HN-16: before installation, immediately after installation, and after the first successful OTP;
- any FAIL: `after-fail` package plus the in-app diagnostics export.

Do not include raw OTP values in issue, PR, or Notion text.

## Baseline configuration

Unless a test case explicitly changes one item:

1. grant `POST_NOTIFICATIONS`;
2. grant notification-listener access;
3. exempt OTP Helper from battery optimization;
4. Settings → Apps → App launch → OTP Helper;
5. disable automatic management;
6. enable auto-launch, secondary launch, and background execution;
7. lock the OTP Helper card in Recents;
8. leave Accessibility fallback disabled;
9. leave Shizuku repair disabled after any required one-time listener repair;
10. keep the visible persistence notification enabled;
11. use a real OTP notification from an app other than OTP Helper for capability tests.

A self-test notification verifies extraction and downstream handling, but does not prove that Android or MagicOS exposes another app's sensitive notification body.

## Result vocabulary

- **PASS**: observed behavior exactly matches the expected result.
- **FAIL**: expected behavior is absent or duplicated.
- **PLATFORM LIMIT**: behavior matches a documented Android limitation, such as explicit Force stop or a locked private notification exposing only `publicVersion`.
- **BLOCKED**: test cannot be executed because its prerequisite is unavailable; record the missing prerequisite.

Do not convert PLATFORM LIMIT into PASS. Do not convert BLOCKED into PASS.

## Test matrix

### HN-01 — Recents visibility and lock

**Setup:** launch OTP Helper normally.

**Procedure:**

1. open Recents;
2. confirm OTP Helper has a normal task card;
3. apply the MagicOS card lock;
4. leave and reopen Recents.

**Expected:** the task is visible and remains locked. There is no separate LeakCanary/Leaks launcher entry.

### HN-02 — direct App launch settings fallback

**Procedure:** open Permissions and press the OEM autostart/App launch item.

**Expected:** either the exact App launch screen opens, or HONOR System Manager opens as a usable fallback. The button must not appear to do nothing after a runtime `SecurityException`.

### HN-03 — standard listener, real OTP, unlocked

**Setup:** Accessibility disabled. Notification access enabled. Device unlocked.

**Procedure:** request one real OTP from another app or service.

**Expected:** exactly one code is detected and handled according to settings. Diagnostics report standard listener connected.

### HN-04 — standard listener after Android 15/16 repair

**Setup:** if HN-03 receives redacted content, run the documented Shizuku repair or equivalent ADB AppOp command.

**Procedure:** request a new real OTP.

**Expected:** repair reports only command application and listener reconnect state. A real OTP is detected exactly once if the source notification exposes the body afterward.

### HN-05 — Accessibility-only fallback

**Setup:** disable notification-listener access; enable only OTP Helper's notification-only Accessibility service.

**Procedure:** request a real OTP while unlocked.

**Expected:** exactly one code is detected when the Accessibility notification event contains usable text. No window-content, gesture, key-event, or screenshot permission is requested.

### HN-06 — standard listener plus Accessibility deduplication

**Setup:** enable both ingestion paths.

**Procedure:** request one real OTP.

**Expected:** one detection, one clipboard operation, and one result notification. Diagnostics may report both services connected, but the code is not processed twice.

### HN-07 — locked-device notification behavior

**Setup:** test separately with standard-only, Accessibility-only, and both enabled.

**Procedure:** lock the screen, wait one minute, then request a real OTP.

**Expected:** record whether the full notification or only `Notification.publicVersion` is available. Failure to expose private content while locked may be a PLATFORM LIMIT; duplicate processing is still a FAIL.

### HN-08 — 30-minute idle survival

**Procedure:** leave the device untouched for 30 minutes with the screen off, then unlock and request a real OTP.

**Expected:** persistence notification exists, the selected ingestion path reports connected or repairs promptly, and the OTP is detected exactly once.

### HN-09 — two-hour idle survival

Repeat HN-08 after two hours.

### HN-10 — overnight idle survival

Repeat HN-08 after at least six hours. Record charging state, battery percentage change, and whether MagicOS altered App launch controls.

### HN-11 — task removal recovery

**Procedure:** swipe OTP Helper away from Recents without using Force stop. Wait at least 15 seconds, then request a real OTP.

**Expected:** the foreground service survives or the recovery alarm restarts it; OTP detection continues. The task card itself may remain absent until the UI is opened again.

### HN-12 — service destruction recovery

**Procedure:** use the least destructive available method to stop the service process without placing the package in Android's stopped state. Do not use Settings → Force stop for this case.

**Expected:** AlarmManager and watchdog recovery recreate the persistence service, subject to Android background-start rules.

### HN-13 — explicit Force stop

**Procedure:** Settings → Apps → OTP Helper → Force stop. Then request an OTP without manually launching OTP Helper.

**Expected:** no self-recovery is required or claimed. Mark PLATFORM LIMIT. Launch OTP Helper once, then confirm normal recovery resumes.

### HN-14 — reboot before manual launch

**Procedure:** reboot the device and do not open OTP Helper. After user unlock and system stabilization, request an OTP.

**Expected:** Boot/User-unlock recovery starts the persistence layer and the selected ingestion path works. Record any MagicOS delay.

### HN-15 — package replacement

**Procedure:** install a higher-version APK signed by the same permanent certificate over the existing installation.

**Expected:** installation succeeds without uninstalling or clearing data; `MY_PACKAGE_REPLACED` recovery runs; settings, history, permissions where retained by Android, and phrase lists remain usable.

### HN-16 — second in-place permanent-key update

Repeat HN-15 with another higher version code.

**Expected:** a second successful update proves the permanent signing lineage is reusable.

### HN-17 — Shizuku absent

**Setup:** uninstall or make Shizuku Manager unavailable.

**Expected:** standard listener and SMS remain functional. Advanced recovery reports Manager absent without treating ordinary monitoring as failed.

### HN-18 — Shizuku installed but service stopped

**Expected:** advanced recovery distinguishes Manager installed from Binder unavailable. No false success is shown.

### HN-19 — Shizuku permission denied

**Expected:** repair requests or reports permission accurately and makes no privileged claim.

### HN-20 — Shizuku shell service granted

**Procedure:** start Shizuku with shell UID 2000, grant client permission, run repair.

**Expected:** only the narrow AppOp and listener refresh commands run. Listener access is restored if a later command fails.

### HN-21 — unsupported Shizuku server UID

**Expected:** any server UID other than 0 or 2000 is rejected as insufficient privilege.

### HN-22 — SMS-mode survival

**Setup:** normal flavor, SMS mode selected, SMS permissions granted. Notification listener and Accessibility may be disabled.

**Procedure:** request an OTP by SMS after 30-minute idle and after task removal.

**Expected:** manifest SMS receiver is refreshed by the mode-aware worker and detects the code exactly once.

### HN-23 — individual phrase backup

For each of sensitive/detection, ignored/exclusion, and cleanup/removal lists:

1. add distinctive valid entries;
2. export the current list;
3. replace the list contents;
4. import the saved file.

**Expected:** the correct list is restored with order preserved and duplicates removed.

### HN-24 — complete phrase backup

1. export all three lists;
2. deliberately change all three;
3. import the complete backup.

**Expected:** all lists change atomically. A malformed field or invalid regular expression leaves all existing lists unchanged.

### HN-25 — legacy relaxed imports

Test a JSON string array and a UTF-8 one-phrase-per-line file for each individual list.

**Expected:** valid values import; blank lines and duplicates normalize as documented.

### HN-26 — diagnostic redaction

Generate diagnostics after processing a known test OTP and a long telephone-like number.

**Expected:** OTP/PIN/code values and standalone numeric runs of four or more digits are redacted. Ordinary package names and non-sensitive words remain readable.

### HN-27 — notification-action receiver isolation

Attempt to invoke an internal Copy/Ignore broadcast from an unrelated unsigned test application or shell context lacking the app-defined signature permission.

**Expected:** the receiver is inaccessible. Normal notification actions created by OTP Helper continue to work.

## Release gate

A fixed-signed APK may be described as a physical-test candidate only after:

- all automated CI jobs pass on the exact commit;
- signing-certificate verification runs and passes;
- HN-01 through HN-06 pass while unlocked;
- HN-08, HN-11, HN-14, HN-15, and HN-16 pass;
- HN-23 and HN-24 pass;
- before/after evidence packages exist for HN-08, HN-11, HN-14, HN-15, and HN-16;
- every failure has an exported diagnostic report, an `after-fail` evidence package, and a disposition.

A general claim of MagicOS resilience additionally requires HN-09 and HN-10 on the target firmware.

## Handoff record

Append each completed physical run to the Notion project page with:

- commit and certificate fingerprint;
- firmware build;
- case results;
- diagnostic and ADB evidence artifact names;
- unresolved failures;
- exact settings changed during the run.

Do not replace prior runs; preserve them as historical evidence because MagicOS firmware updates can change behavior.
