# otphelper fork maintenance

This fork tracks [`jd1378/otphelper`](https://github.com/jd1378/otphelper) while adding an OEM-resilience layer, diagnostic tooling, and phrase-list backup support. Keep fork-specific code isolated where practical so upstream merges remain reviewable.

## Fork-specific goals

1. Remain available on aggressive Android firmware, especially HONOR MagicOS.
2. Recover the notification listener after process death, task removal, reboot, app update, and OEM listener detachment.
3. Distinguish notification-access permission from the listener's actual connected state.
4. Provide an optional Accessibility notification-event fallback when the standard notification listener is unreliable.
5. Provide an optional Shizuku repair action without making normal operation depend on Shizuku.
6. Export/import sensitive, ignored, and cleanup phrases individually or as one complete backup.
7. Let a user export a redacted diagnostic report without collecting logcat manually.

## Persistence architecture

- `PersistenceService`: visible `specialUse` foreground service; returns `START_STICKY`; requests `NotificationListenerService.requestRebind()` every minute.
- `PersistenceWatchdogWorker`: 15-minute WorkManager safety net; retries the foreground service, direct listener rebind, and the normal listener-repair worker.
- `WatchdogReceiver`: `AlarmManager.setAndAllowWhileIdle()` recovery after task removal or service destruction.
- `BootReceiver`: repairs after boot, user unlock, and package replacement.
- `MonitoringHealthStore`: records actual `onListenerConnected` / `onListenerDisconnected` and Accessibility connection callbacks. A fresh process clears stale connected flags before callbacks restore them.
- `AccessibilityNotificationService`: opt-in fallback restricted to `TYPE_NOTIFICATION_STATE_CHANGED`; it cannot retrieve window content and performs no gestures.
- `RecentDetectedCodesHolder`: suppresses duplicate detection across the standard listener and Accessibility fallback by package and detected code.

The persistent notification reports four distinct states:

1. notification access missing;
2. standard listener actually connected;
3. standard listener disconnected but Accessibility fallback connected;
4. permission enabled while both ingestion services are disconnected.

Android can still prevent automatic resurrection after the user explicitly **Force stops** the app. No ordinary app can override that stopped state until the user launches it again or another permitted system event clears it.

## Optional Shizuku repair

Shizuku is an insurance path only. Standard notification mode, SMS mode, the foreground service, WorkManager watchdog, AlarmManager recovery, and Accessibility fallback do not depend on it.

The implementation uses the supported Shizuku `UserService`/AIDL API rather than the deprecated private `newProcess` bridge. The service is short-lived, non-daemon, tagged with a stable identifier for R8 builds, and destroyed immediately after use.

It reapplies:

- notification-listener access;
- Doze whitelist;
- `RUN_IN_BACKGROUND` for the current Android user;
- `RUN_ANY_IN_BACKGROUND` for the current Android user;
- on Android 15 and later only, `RECEIVE_SENSITIVE_NOTIFICATIONS`, matching the app's existing ADB procedure.

Command generation is covered by JVM tests for Android 14/15 branching, current-user targeting, and shell quoting.

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
- Accessibility permission and actual connection separately;
- foreground-service state;
- battery-optimization state and app standby bucket;
- watchdog WorkManager state;
- optional Shizuku availability;
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
- explicit failure if `excludeFromRecents="true"` returns.

API 35 emulator job:

- verifies MainActivity creates a visible Recents task;
- checks service/receiver export and binding permissions;
- starts the foreground persistence service;
- confirms the periodic watchdog is registered;
- grants notification access with shell and waits for the real `onListenerConnected()` callback;
- enables the Accessibility service through secure settings and waits for the real `onServiceConnected()` callback;
- restores notification and Accessibility settings after the tests;
- verifies actual connection-state tracking is distinct from permission state.

The legacy full-build workflow and the enhanced CI both cancel superseded runs for the same PR, preventing stale builds from hiding the current result.

## MagicOS verification checklist

The emulator verifies Android behavior, but HONOR's proprietary process killer must still be tested on a physical device after persistence changes:

1. Grant notification access and notification permission.
2. Enable the Accessibility fallback and confirm its description states notification events only.
3. In MagicOS App launch settings, disable automatic management and enable auto-launch, secondary launch, and background running.
4. Open Recents and swipe down on the OTP Helper card until the lock icon appears.
5. Exempt the app from battery optimization. Shizuku repair is optional, not required.
6. Send an OTP notification while the app is open.
7. Send one after leaving the app in Recents for 30 minutes.
8. Remove the app task, wait for the persistent notification to return, then send another OTP.
9. Lock the screen for at least 30 minutes and retest.
10. Reboot and retest before manually opening the app.
11. Update the APK over the installed build and retest.
12. Confirm standard listener plus Accessibility fallback produces only one history/copy action.
13. Export each list and the complete backup, clear settings, then import and compare all entries.
14. Export the built-in diagnostic report if any case fails.

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
- Never silently enable Accessibility. Android requires explicit user consent.
- Never make Shizuku a setup prerequisite.
- Treat the persistent notification as a functional status surface, not decoration.
