# otphelper fork maintenance

This fork tracks [`jd1378/otphelper`](https://github.com/jd1378/otphelper) while adding an OEM-resilience layer, diagnostic tooling, complete phrase-list backup support, and Android 15/16 notification-reading compatibility.

## Decision rule

Platform capability is determined from the official Android API contract, AOSP implementation, and the official Shizuku API contract. Emulator and device tests are used to catch implementation regressions and OEM differences; they are not used to overrule a documented platform capability.

A feature is kept only when it has a specific, documented role in reading notifications or preserving that ability. UI success messages must describe only what the app actually verified.

## Supported notification-reading paths

### Standard NotificationListenerService

`NotificationListenerService` remains the primary notification path. Actual `onListenerConnected()` state is tracked separately from the user-facing notification-access permission.

On Android 15 and later, OTP and other sensitive notification content can be withheld from an untrusted listener. AOSP treats a listener UID as trusted when the `RECEIVE_SENSITIVE_NOTIFICATIONS` AppOp is allowed, among other privileged cases. AOSP records trusted listener UIDs when the listener component is added/enabled, so the repair procedure applies the AppOp first and then refreshes notification-listener registration.

### Accessibility notification events

The optional `AccessibilityNotificationService` remains because Android 16 still defines `AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED`. The public API contract provides notification text through `AccessibilityEvent.getText()` and may provide the posted `Notification` through `getParcelableData()`.

The service subscribes only to notification-state events and reads:

- `AccessibilityEvent.text` and content descriptions;
- event records;
- `Notification.tickerText`;
- public `Notification.EXTRA_*` text fields from the parcelable Notification.

It does not request window content, gestures, key events, or screenshots, and it is not declared as an accessibility tool. AOSP may substitute `Notification.publicVersion` for a private notification while the device is locked, so this path is not presented as an unrestricted lock-screen bypass.

The standard listener and Accessibility path share package+code duplicate suppression.

### Optional Shizuku repair

Shizuku is not itself a notification reader. It is an optional Android 15+ repair path for the standard NotificationListenerService.

The implementation follows the official Shizuku API:

- includes `rikka.shizuku.ShizukuProvider`;
- receives the Binder asynchronously through sticky Binder listeners;
- distinguishes Manager installation from actual Binder connection;
- checks API version, permission, and server UID;
- uses a short-lived, non-daemon `UserService` with a stable tag and version;
- uses the reserved AIDL destroy transaction so the UserService process exits cleanly.

The UserService runs with shell UID 2000 or root UID 0 and applies only the narrow repair operations:

1. on Android 15+, set `RECEIVE_SENSITIVE_NOTIFICATIONS` AppOp to `allow` for OTP Helper;
2. unregister and re-register the notification listener so AOSP refreshes its trusted-listener UID cache.

A successful result means that the official AppOp operation completed and, when observed, the standard listener reconnected. It does not falsely claim that a particular OTP body was read.

The user's `thedjchi/Shizuku` fork retains the standard Manager application ID `moe.shizuku.privileged.api` and directs client developers to the standard Shizuku API, so this integration uses the normal API contract rather than fork-specific private behavior.

### SMS

The existing SMS receiver remains available in the normal/SMS-capable flavor. It is independent of NotificationListenerService, Accessibility, and Shizuku.

## Persistence architecture

- `PersistenceService`: visible `specialUse` foreground service; returns `START_STICKY`; requests `NotificationListenerService.requestRebind()` every minute.
- `PersistenceWatchdogWorker`: 15-minute WorkManager safety net.
- `WatchdogReceiver`: `AlarmManager.setAndAllowWhileIdle()` recovery after task removal or service destruction.
- `BootReceiver`: repairs after boot, user unlock, and package replacement.
- `MonitoringHealthStore`: records actual standard-listener and Accessibility-service callbacks. A fresh process clears stale connection flags.

The persistent notification distinguishes:

1. standard notification listener connected;
2. standard listener disconnected but Accessibility notification events connected;
3. notification access missing;
4. permission enabled while the standard listener is disconnected.

Android's explicit **Force stop** stopped state cannot be bypassed by an ordinary app. The user must launch the app again or wait for a permitted system action that clears the stopped state.

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
- normalizes blank lines and duplicates while preserving first occurrence and order;
- limits each phrase, each list, and the complete file;
- stops reading as soon as the 2,000,000-character file limit would be exceeded;
- applies complete backups in one DataStore update.

Each of the three phrase screens exposes individual import/export and complete import/export.

## Built-in diagnostics

The Permissions screen can copy or export a redacted report containing:

- version/flavor and device information;
- Recents visibility;
- notification-access permission and actual standard-listener connection;
- Accessibility enablement and actual connection;
- Shizuku Manager installation, Binder delivery, API version, server UID, and client permission;
- foreground-service, battery-optimization, standby-bucket, and watchdog state;
- a bounded, rotating on-device log.

OTP/PIN/code values and standalone 4–10 digit values are redacted before log persistence.

## Automated validation

`.github/workflows/ci.yml` validates every PR, main push, manual dispatch, and automated upstream-sync branch.

Static/build validation:

- Actionlint;
- normal/play JVM unit tests;
- normal/play Android Lint;
- normal/play debug and minified release builds;
- APK ZIP integrity and signature checks;
- merged-APK Manifest inspection;
- required standard listener, Accessibility service, ShizukuProvider, and persistence components;
- explicit failure if LeakCanary or the former experiment-only fixture appears in a distributed APK;
- XML-level verification that `MainActivity` is not excluded from Recents;
- Shizuku repair command-order and shell-quoting unit tests.

API 35 and API 36 emulator validation:

- MainActivity creates a visible Recents task;
- service, receiver, provider, and binding permissions match their official contracts;
- foreground persistence service starts;
- watchdog work is registered;
- NotificationListenerService actually reports `onListenerConnected()`;
- AccessibilityService is enabled without UiAutomation suppression and actually reports `onServiceConnected()`;
- actual connection state remains distinct from permission/configuration state.

The former cross-package OTP fixture and pass/fail gate were removed. Platform capability is not inferred from an emulator notification sample.

## LeakCanary / “Leaks” launcher

LeakCanary was accidentally included as a debug dependency, exposing its launcher as a separate “Leaks” app entry. LeakCanary is removed from every build variant. CI fails if any LeakCanary component appears in a final APK.

Updating to a corrected APK removes the component. A launcher may retain a stale cached icon until it refreshes or the device restarts.

## MagicOS physical verification checklist

The emulator validates standard Android contracts, not HONOR's proprietary process killer. Test the exact MagicOS firmware physically:

1. Grant notification permission and notification access.
2. Optionally enable the notification-only Accessibility service.
3. On Android 15/16, run the Shizuku repair or the equivalent documented ADB AppOp procedure for the standard listener.
4. In MagicOS App launch settings, disable automatic management and enable auto-launch, secondary launch, and background running.
5. Lock OTP Helper in Recents and exempt it from battery optimization.
6. Test a real OTP while unlocked with the standard listener enabled.
7. Test with only the Accessibility notification path enabled.
8. Test while locked, remembering that Android may provide only `publicVersion` for private notifications.
9. Repeat after 30 minutes idle, task removal, reboot-before-launch, and APK update.
10. Export diagnostics after any failure and record which path was connected.
11. Verify phrase-list individual and complete backup/restore.

## Upstream workflow

`.github/workflows/upstream-sync.yml` runs weekly and can be dispatched manually. It fetches `jd1378/otphelper:main`, creates a dated merge branch, opens a draft PR for clean merges, dispatches enhanced CI, and opens an issue for conflicts.

Before merging an upstream-sync PR, review the Manifest, `App`, `MainActivity`, both notification-reading services, Shizuku API integration, WorkManager setup, phrase screens/ViewModels, Gradle dependencies, and resource conflicts.

## Release discipline

- Keep upstream version identity visible; add a fork suffix only for published binaries.
- Build both `normal` and `play` flavors.
- Use the normal flavor as the primary MagicOS build because it preserves SMS.
- Do not claim OTP readability merely from service connection, Binder connection, or command completion.
- Do not remove a documented platform path solely because one emulator or OEM sample fails.
- Keep the persistent notification as an operational status surface.
