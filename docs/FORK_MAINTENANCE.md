# otphelper fork maintenance

This fork tracks [`jd1378/otphelper`](https://github.com/jd1378/otphelper) while adding an OEM-resilience layer, diagnostic tooling, phrase-list backup support, and Android 15/16 notification-body validation.

## Fork-specific goals

1. Remain available on aggressive Android firmware, especially HONOR MagicOS.
2. Recover the notification listener after process death, task removal, reboot, app update, and OEM listener detachment.
3. Distinguish notification-access permission from the listener's actual connected state.
4. Verify actual third-party notification-body delivery rather than treating service connection as success.
5. Export/import sensitive, ignored, and cleanup phrases individually or as one complete backup.
6. Let a user export a redacted diagnostic report without collecting logcat manually.

## Supported notification-reading paths

The distributed app retains only paths that directly contribute to reading OTP data:

- `NotificationListenerService` for notification mode;
- the existing SMS receiver in the normal/SMS-capable flavor.

### Accessibility was removed

A notification-event Accessibility service was prototyped, but a successful service connection did not prove that notification body text was available. On the target HONOR/Android 16 device, the service did not provide usable OTP text. The final APK contains no Accessibility service, setup UI, permission, or status handling.

### Shizuku was removed

The Shizuku prototype was corrected to use the proper provider and asynchronous Binder lifecycle, then tested on API 35 and API 36 with:

- the sensitive-notification AppOp applied;
- notification access fully disconnected and reconnected afterward;
- a random six-digit OTP posted from a different package;
- success requiring the exact token in the delivered body.

The external OTP body still did not become readable in those tests. Shizuku therefore did not establish a useful notification-reading path and was removed completely. The distributed APK contains no Shizuku dependency, provider, permission, UI, service, AIDL interface, or diagnostic state.

## Android 15/16 sensitive-notification handling

Android 15 and later can hide OTP and other sensitive notification content from untrusted notification listeners. The app keeps the existing ADB procedure that applies the `RECEIVE_SENSITIVE_NOTIFICATIONS` AppOp and then restarts the app/listener so Android reevaluates the listener's trust state.

A CI-only fixture APK is used to validate this behavior. It is a separate package and UID, has no launcher activity, and is never included in the distributed OTP Helper APKs.

The fixture posts a notification containing a random six-digit token. The API 35 and API 36 tests require:

1. the third-party OTP is not readable with the AppOp at its default value;
2. the AppOp is set to `allow`;
3. notification access is disconnected and reconnected;
4. the exact random token becomes readable through `NotificationListenerService`.

This is materially stronger than testing only `onListenerConnected()` or posting an OTP Helper self-notification.

## Persistence architecture

- `PersistenceService`: visible `specialUse` foreground service; returns `START_STICKY`; requests `NotificationListenerService.requestRebind()` every minute.
- `PersistenceWatchdogWorker`: 15-minute WorkManager safety net; retries the foreground service, direct listener rebind, and the normal listener-repair worker.
- `WatchdogReceiver`: `AlarmManager.setAndAllowWhileIdle()` recovery after task removal or service destruction.
- `BootReceiver`: repairs after boot, user unlock, and package replacement.
- `MonitoringHealthStore`: records actual `onListenerConnected` / `onListenerDisconnected`. A fresh process clears stale connected state before the real callback restores it.

The persistent notification reports three states:

1. notification access missing;
2. notification listener actually connected;
3. permission enabled while the listener is disconnected.

Android can still prevent automatic resurrection after the user explicitly **Force stops** the app. No ordinary app can override that stopped state until the user launches it again or another permitted system event clears it.

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
- foreground-service state;
- battery-optimization state and app standby bucket;
- watchdog WorkManager state;
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
- explicit failure if LeakCanary/`LeakLauncherActivity`, Shizuku, the removed Accessibility service, or the CI fixture appears in a distributed APK;
- XML-level verification that `MainActivity` itself is not excluded from Recents.

API 35 and API 36 emulator jobs:

- install the CI-only notification fixture as a separate package/UID;
- verify MainActivity creates a visible Recents task;
- check service/receiver export and binding permissions;
- start the foreground persistence service;
- confirm the periodic watchdog is registered;
- grant notification access and wait for the real `onListenerConnected()` callback;
- compare third-party OTP delivery before and after the documented ADB AppOp procedure;
- restore AppOp and notification-listener access after the test;
- verify actual connection-state tracking is distinct from permission state.

The fixture APK is built only for CI and is not uploaded as an installable OTP Helper artifact.

The legacy full-build workflow and enhanced CI both cancel superseded runs for the same PR, preventing stale builds from hiding the current result.

## LeakCanary / “Leaks” launcher

LeakCanary was accidentally included as a debug dependency, which installed its launcher activity as a visible “Leaks” app entry. LeakCanary is removed from all build variants. CI inspects every final debug APK and fails if any LeakCanary component remains.

Updating to the corrected APK removes the component from OTP Helper. An OEM launcher may require a launcher refresh or reboot to clear a stale cached icon after the old build is replaced.

## MagicOS verification checklist

The emulator verifies standard Android 15/16 behavior, but HONOR's proprietary process killer must still be tested on a physical device:

1. Grant notification access and notification permission.
2. On Android 15/16, apply the ADB sensitive-notification AppOp procedure shown in the app, including the restart step.
3. In MagicOS App launch settings, disable automatic management and enable auto-launch, secondary launch, and background running.
4. Open Recents and swipe down on the OTP Helper card until the lock icon appears.
5. Exempt the app from battery optimization.
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

Before merging an upstream-sync PR, review at least the Manifest, `App`, `MainActivity`, `NotificationListener`, WorkManager setup, phrase screens/ViewModels, Gradle dependencies, and resource-string conflicts.

## Release discipline

- Keep upstream version identity visible; add a fork suffix only when publishing binaries.
- Build both `normal` and `play` flavors.
- The `normal` flavor is the primary MagicOS build because it preserves SMS fallback.
- Do not ship experimental integrations that do not demonstrably improve notification-body access.
- Treat the persistent notification as a functional status surface, not decoration.
