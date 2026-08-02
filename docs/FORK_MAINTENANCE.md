# otphelper fork maintenance

This fork tracks [`jd1378/otphelper`](https://github.com/jd1378/otphelper) while adding an OEM-resilience layer, diagnostic tooling, phrase-list backup support, and an optional verified Shizuku repair path. Keep fork-specific code isolated where practical so upstream merges remain reviewable.

## Fork-specific goals

1. Remain available on aggressive Android firmware, especially HONOR MagicOS.
2. Recover the notification listener after process death, task removal, reboot, app update, and OEM listener detachment.
3. Distinguish notification-access permission from the listener's actual connected state.
4. Verify actual cross-package notification-body delivery rather than treating service connection as success.
5. Keep Shizuku only as an optional means to restore notification access and the sensitive-notification AppOp; never make Shizuku a goal or prerequisite.
6. Export/import sensitive, ignored, and cleanup phrases individually or as one complete backup.
7. Let a user export a redacted diagnostic report without collecting logcat manually.

## Notification-reading architecture

The supported ingestion paths are:

- `NotificationListenerService` for notification mode;
- the existing SMS receiver for the normal/SMS-capable flavor.

An Accessibility notification-event reader was prototyped and then removed. A connected Accessibility service does not prove that notification body text is present, and the exact HONOR/Android 16 target did not provide usable OTP text. The final APK contains no Accessibility service or Accessibility setup UI.

Android 15 and later redact OTP and similar sensitive data from untrusted notification listeners. The hidden `RECEIVE_SENSITIVE_NOTIFICATIONS` permission is not available to ordinary third-party apps. AOSP also provides the corresponding `RECEIVE_SENSITIVE_NOTIFICATIONS` AppOp specifically so shell can override the redaction without requiring the app to declare the hidden permission.

`NotificationIngestionSelfTest` therefore rejects same-package self-notifications. A valid test is created by shell/Shizuku using:

```text
cmd notification post -t "OTP Helper external read test" <unique-tag> "One-time verification code: <token>"
```

The test passes only when OTP Helper receives the matching notification from a different package and the exact random token remains in the delivered notification body.

## Persistence architecture

- `PersistenceService`: visible `specialUse` foreground service; returns `START_STICKY`; requests `NotificationListenerService.requestRebind()` every minute.
- `PersistenceWatchdogWorker`: 15-minute WorkManager safety net; retries the foreground service, direct listener rebind, and the normal listener-repair worker.
- `WatchdogReceiver`: `AlarmManager.setAndAllowWhileIdle()` recovery after task removal or service destruction.
- `BootReceiver`: repairs after boot, user unlock, and package replacement.
- `MonitoringHealthStore`: records actual `onListenerConnected` / `onListenerDisconnected`. A fresh process clears stale connected state before the real callback restores it.

The persistent notification reports three distinct states:

1. notification access missing;
2. notification listener actually connected;
3. permission enabled while the listener is disconnected.

Android can still prevent automatic resurrection after the user explicitly **Force stops** the app. No ordinary app can override that stopped state until the user launches it again or another permitted system event clears it.

## Optional Shizuku repair and verification

Shizuku is an insurance path only. Standard notification mode, SMS mode, the foreground service, WorkManager watchdog, and AlarmManager recovery do not depend on it.

The implementation uses the standard Shizuku client protocol, which is also used by the `thedjchi/Shizuku` fork. That fork retains the manager application ID `moe.shizuku.privileged.api` and builds the standard API/provider projects.

The app explicitly declares `rikka.shizuku.ShizukuProvider`. Merely adding the provider library dependency does not insert the provider into the application Manifest. The provider is the Binder delivery endpoint and must be exported with `${applicationId}.shizuku`, `multiprocess=false`, and `android.permission.INTERACT_ACROSS_USERS_FULL`.

Shizuku Binder delivery is asynchronous. `Shizuku.pingBinder()` is only an alive check after delivery; it is not discovery. `ShizukuConnectionManager` registers:

- `addBinderReceivedListenerSticky`;
- `addBinderDeadListener`;
- `addRequestPermissionResultListener`.

The Permissions screen and diagnostic report distinguish:

- manager not installed;
- manager installed but Binder not received/alive;
- Binder connected, with server API, UID, and permission state.

After Binder and permission validation, the short-lived UserService reapplies:

- notification-listener access;
- Doze whitelist;
- `RUN_IN_BACKGROUND` for the current Android user;
- `RUN_ANY_IN_BACKGROUND` for the current Android user;
- on Android 15 and later, `RECEIVE_SENSITIVE_NOTIFICATIONS` AppOp.

A successful command exit is not considered success. The same shell-side UserService posts the unique external OTP probe, and Shizuku repair is reported as successful only when NotificationListener receives the actual random code. If the body is still redacted or missing, the UI explicitly reports that Shizuku is not a usable notification-reading path on that device.

## Phrase backup format

Complete backup:

```json
{
  "schema": "otphelper.phrases",
  "version": 1,
  "lists": {
    "sensitive_phrases": [],
    "ignored_phrases": [],
    "cleanup_phrases": []
  }
}
```

Single-list backups use `kind` and `phrases`. Single-list import also accepts a JSON string array or UTF-8 text with one phrase per line.

Import behavior:

- validates schema, version, required lists, array element types, and regular expressions;
- normalizes blank lines and duplicates while preserving the first occurrence and order;
- limits each phrase, each list, and the complete file;
- stops reading as soon as the 2,000,000-character file limit would be exceeded;
- applies complete backups in one DataStore update so partial restoration cannot occur.

Each of the three phrase screens exposes individual import/export and complete import/export.

## Built-in diagnostics

The Permissions screen can copy or export a diagnostic report containing:

- version/flavor and device information;
- Recents visibility;
- notification permission and actual listener connection separately;
- latest external notification-body probe result and timestamp;
- foreground-service state;
- battery-optimization state and app standby bucket;
- watchdog WorkManager state;
- Shizuku manager, Binder, server API/UID, and permission state;
- a bounded, rotating, on-device log.

The persistent log is redacted before writing. OTP/PIN/code values and standalone 4–10 digit values are replaced, and the log is size-limited with one rotated file.

## Automated validation

`.github/workflows/ci.yml` validates every PR, main push, manual dispatch, and automated upstream-sync branch.

Static/build job:

- Actionlint for every workflow;
- normal/play JVM unit tests;
- normal/play Android Lint;
- normal/play debug APK builds;
- normal/play minified release APK builds;
- ZIP integrity and debug signature verification;
- merged-APK Manifest inspection for required services and permissions;
- explicit verification of the Shizuku provider attributes;
- explicit failure if LeakCanary/`LeakLauncherActivity` or the removed Accessibility service appears in an APK;
- XML-level verification that `MainActivity` itself is not excluded from Recents.

API 35 and API 36 emulator jobs:

- verify MainActivity creates a visible Recents task;
- check service/receiver export and binding permissions;
- verify the real Shizuku provider exists with the required attributes;
- start the foreground persistence service;
- confirm the periodic watchdog is registered;
- grant notification access and wait for the real `onListenerConnected()` callback;
- grant the sensitive-notification AppOp;
- post the OTP probe from shell with `cmd notification post`;
- require the exact random cross-package OTP token to reach NotificationListener;
- restore AppOp and notification-listener access after the test;
- verify actual connection-state tracking is distinct from permission state.

The legacy full-build workflow and the enhanced CI both cancel superseded runs for the same PR, preventing stale builds from hiding the current result.

## LeakCanary / “Leaks” launcher

LeakCanary was accidentally included as a debug dependency, which installed its launcher activity as a visible “Leaks” app entry. LeakCanary is removed from all build variants. CI inspects every final debug APK and fails if any LeakCanary component remains. Updating to the corrected APK removes the component; an OEM launcher may require a launcher refresh or reboot to clear a stale cached icon.

## MagicOS verification checklist

The emulator verifies standard Android 15/16 behavior, but HONOR's proprietary process killer must still be tested on a physical device:

1. Grant notification access and notification permission.
2. In MagicOS App launch settings, disable automatic management and enable auto-launch, secondary launch, and background running.
3. Open Recents and swipe down on the OTP Helper card until the lock icon appears.
4. Exempt the app from battery optimization.
5. If using Shizuku, confirm the Permissions screen shows Binder connected, then run “Repair and verify with Shizuku.” Treat only the notification-body-test success message as success.
6. Send a real OTP notification while the app is open.
7. Send one after leaving the app in Recents for 30 minutes.
8. Remove the app task, wait for the persistent notification to return, then send another OTP.
9. Lock the screen for at least 30 minutes and retest.
10. Reboot and retest before manually opening the app.
11. Update the APK over the installed build and retest.
12. Export each list and the complete backup, clear settings, then import and compare all entries.
13. Export the built-in diagnostic report if any case fails.

For deeper investigation, `adb logcat -s OtpHelper:*` captures the same component-scoped events. The built-in report normally avoids needing this step.

## Upstream workflow

`.github/workflows/upstream-sync.yml` runs weekly and can be dispatched manually. It:

1. fetches `jd1378/otphelper:main`;
2. merges it into a dated bot branch;
3. opens or updates a draft PR when the merge is clean;
4. explicitly dispatches the enhanced Android CI for that bot branch;
5. opens an issue when conflicts require manual resolution.

The explicit CI dispatch is required because a PR created with the repository `GITHUB_TOKEN` does not normally trigger another `pull_request` workflow.

Before merging an upstream-sync PR, review at least the Manifest, `App`, `MainActivity`, `NotificationListener`, WorkManager setup, phrase screens/ViewModels, Gradle dependencies, and resource-string conflicts.

## Release discipline

- Keep upstream version identity visible; add a fork suffix only when publishing binaries.
- Build both `normal` and `play` flavors.
- The `normal` flavor is the primary MagicOS build because it preserves SMS fallback.
- Never make Shizuku a setup prerequisite.
- Treat Shizuku as useful only when the external notification-body probe passes.
- Treat the persistent notification as a functional status surface, not decoration.
