# otphelper fork maintenance

This fork tracks [`jd1378/otphelper`](https://github.com/jd1378/otphelper) while adding an OEM-resilience layer, diagnostic tooling, complete phrase-list backup support, and Android 15/16 notification-reading compatibility.

## Decision rule

Platform capability is determined from the official Android API contract, AOSP implementation, and the official Shizuku API contract. Emulator and device tests are used to catch implementation regressions and OEM differences; they are not used to overrule a documented platform capability.

A feature is kept only when it has a specific, documented role in reading notifications or preserving that ability. UI success messages must describe only what the app actually verified.

## Supported notification-reading paths

### Standard NotificationListenerService

`NotificationListenerService` is the primary and default notification path. Actual `onListenerConnected()` state is tracked separately from the user-facing notification-access permission.

On Android 15 and later, OTP and other sensitive notification content can be withheld from an untrusted listener. AOSP treats a listener UID as trusted when the `RECEIVE_SENSITIVE_NOTIFICATIONS` AppOp is allowed, among other privileged cases. AOSP records trusted listener UIDs when the listener component is added/enabled, so the repair procedure applies the AppOp first and then refreshes notification-listener registration.

Each posted notification is processed using one immutable settings snapshot. Phrase extraction, mode, auto-dismiss, and mark-as-read settings cannot be mixed across concurrent DataStore updates. Initial settings loading has a bounded wait instead of blocking a listener thread forever.

### Accessibility notification events

The optional `AccessibilityNotificationService` remains because Android 16 still defines `AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED`. The public API contract provides notification text through `AccessibilityEvent.getText()` and may provide the posted `Notification` through `getParcelableData()`.

The service subscribes only to notification-state events and reads:

- `AccessibilityEvent.text` and content descriptions;
- event records;
- `Notification.tickerText`;
- public `Notification.EXTRA_*` text fields from the parcelable Notification.

It does not request window content, gestures, key events, or screenshots, and it is not declared as an accessibility tool. AOSP may substitute `Notification.publicVersion` for a private notification while the device is locked, so this path is not presented as an unrestricted lock-screen bypass.

The standard listener and Accessibility path share package+code duplicate suppression. Accessibility is hidden under **advanced notification recovery** and is unnecessary when the standard listener works.

### Optional Shizuku repair

Shizuku is not itself a notification reader. It is an optional Android 15+ repair path for the standard NotificationListenerService.

The implementation follows the official Shizuku API:

- includes `rikka.shizuku.ShizukuProvider`;
- distinguishes Manager installation from actual Binder connection;
- registers Binder lifecycle callbacks only when advanced recovery, diagnostics, or repair is opened;
- checks API version, permission, and server UID;
- uses a short-lived, non-daemon `UserService` with a stable tag and version;
- uses the reserved AIDL destroy transaction so the UserService process exits cleanly.

The UserService runs with shell UID 2000 or root UID 0 and applies only the narrow repair operations:

1. on Android 15+, set `RECEIVE_SENSITIVE_NOTIFICATIONS` AppOp to `allow` for OTP Helper;
2. unregister and re-register the notification listener so AOSP refreshes its trusted-listener UID cache.

If the command sequence fails after listener access was removed, OTP Helper immediately retries the `allow_listener` command as a rollback. A successful result means that the AppOp operation completed and, when observed, the standard listener reconnected. It does not falsely claim that a particular OTP body was read.

The user's `thedjchi/Shizuku` fork retains the standard Manager application ID `moe.shizuku.privileged.api` and directs client developers to the standard Shizuku API, so this integration uses the normal API contract rather than fork-specific private behavior.

Shizuku controls are collapsed by default. Standard notification reading and SMS do not initialize the optional Shizuku integration during ordinary app startup.

### SMS

The existing SMS receiver remains available in the normal/SMS-capable flavor. It is independent of NotificationListenerService, Accessibility, and Shizuku. Watchdog and diagnostic checks are mode-aware and do not report notification-listener failures while SMS mode is selected.

## Persistence architecture

- `PersistenceService`: visible `specialUse` foreground service; returns `START_STICKY`; checks listener health every minute and requests a rebind only when the stored callback state is disconnected.
- `PersistenceWatchdogWorker`: 15-minute WorkManager safety net that invokes the mode-aware repair worker.
- `RebindListenersWorker`: refreshes only the selected mode. SMS mode refreshes the manifest receiver; Notification mode repairs the listener.
- `WatchdogReceiver`: `AlarmManager.setAndAllowWhileIdle()` recovery after task removal or service destruction.
- `BootReceiver`: repairs after boot, user unlock, and package replacement.
- `MonitoringHealthStore`: records actual standard-listener and Accessibility-service callbacks. A fresh process clears stale connection flags.

The persistent notification distinguishes:

1. standard notification listener connected;
2. standard listener disconnected but Accessibility notification events connected;
3. notification access missing;
4. permission enabled while the standard listener is disconnected.

The Permissions screen no longer requests a listener rebind on every lifecycle refresh. It requests one only when notification access changes from disabled to enabled. `MainActivity` processes each incoming Intent once rather than repeating it from both `onCreate()` and `onStart()`.

Android's explicit **Force stop** stopped state cannot be bypassed by an ordinary app. The user must launch the app again or wait for a permitted system action that clears the stopped state.

## HONOR / OEM settings

OEM app-launch activities are checked by actual component metadata. An activity is considered callable only when it exists, is exported, and does not require a permission OTP Helper lacks. This prevents the previous HONOR false positive where the button was shown but `startActivity()` threw `SecurityException`.

When direct launch is blocked, the UI gives the manual MagicOS route:

1. Settings → Apps → App launch → OTP Helper;
2. disable automatic management;
3. allow auto-launch, secondary launch, and background execution;
4. lock the OTP Helper card in Recents;
5. exempt OTP Helper from battery optimization.

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

## Built-in diagnostics and privacy

The Permissions screen can copy or export a report containing:

- version/flavor and device information;
- Recents visibility;
- the selected monitoring mode;
- notification-access permission and actual standard-listener connection;
- Accessibility enablement and actual connection;
- SMS permissions when SMS mode is selected;
- optional Shizuku Manager installation, Binder delivery, API version, server UID, and client permission;
- foreground-service, battery-optimization, standby-bucket, and watchdog state;
- a bounded, rotating on-device log.

Automatic checks are mode-aware. Optional Shizuku being absent or stopped is informational, not a warning.

OTP/PIN/code values and standalone numeric runs of four or more digits are redacted. Redaction applies both to the persisted diagnostic log and Android Logcat, including exception stack traces. Package names containing the substring `otp` are no longer accidentally treated as secret values.

## Internal notification actions

The notification action receiver is protected by the app-defined `io.github.jd1378.otphelper.permission.BROADCAST_CODE` permission with `signature` protection level. The merged APK inspection verifies both the permission declaration and the receiver binding so another app cannot forge OTP Helper's Copy or Ignore actions.

## Automated validation

`.github/workflows/ci.yml` validates every PR, main push, manual dispatch, and automated upstream-sync branch.

Static/build validation:

- Actionlint;
- normal/play JVM unit tests;
- normal/play Android Lint;
- normal/play debug and minified release builds;
- APK ZIP integrity and signature checks;
- merged-APK Manifest inspection;
- required standard listener, Accessibility service, ShizukuProvider, persistence components, and signature-protected internal action receiver;
- explicit failure if LeakCanary or the former experiment-only fixture appears in a distributed APK;
- XML-level verification that `MainActivity` is not excluded from Recents;
- Shizuku repair command-order, rollback-command, and shell-quoting unit tests;
- diagnostic redaction tests covering OTPs, long telephone numbers, ordinary words, and package names.

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
2. Configure MagicOS App launch, Recents lock, and battery optimization exemption.
3. Test a real OTP while unlocked with only the standard listener enabled.
4. If Android 15/16 redacts that real OTP, run the optional Shizuku repair or equivalent documented ADB AppOp procedure and repeat.
5. Enable the optional notification-only Accessibility service only when testing that fallback.
6. Test while locked, remembering that Android may provide only `publicVersion` for private notifications.
7. Repeat after 30 minutes idle, task removal, reboot-before-launch, and an in-place APK update.
8. Export diagnostics after any failure and record which path was actually connected.
9. Verify phrase-list individual and complete backup/restore.

A self-test notification from OTP Helper proves the extraction, WorkManager, clipboard, Toast, and result-notification pipeline. It does not by itself prove that a different app's real OTP body escaped Android 15/16 sensitive-content redaction.

## Upstream workflow

`.github/workflows/upstream-sync.yml` runs weekly and can be dispatched manually. It fetches `jd1378/otphelper:main`, creates a dated merge branch, opens a draft PR for clean merges, dispatches enhanced CI, and opens an issue for conflicts.

Before merging an upstream-sync PR, review the Manifest, `App`, `MainActivity`, both notification-reading services, Shizuku API integration, WorkManager setup, phrase screens/ViewModels, Gradle dependencies, signing workflows, and resource conflicts.

## Signing and release discipline

Android in-place updates require the same signing identity. The transient debug key that signed the currently installed physical-test APK was generated on an ephemeral CI runner and is unavailable; it cannot be recovered from the APK certificate.

Therefore:

- no new APK is distributed until one permanent keystore is created and backed up;
- CI requires `OTPHELPER_SIGNING_KEYSTORE_B64`, `OTPHELPER_KEYSTORE_PASSWORD`, `OTPHELPER_KEY_ALIAS`, `OTPHELPER_KEY_PASSWORD`, and `OTPHELPER_SIGNING_CERT_SHA256` before uploading an APK;
- every generated APK certificate is compared with the pinned SHA-256 value;
- the release workflow uses the same five values and refuses to run when any is missing;
- the old independent `ANDROID_SIGNING_KEY` release path has been removed;
- the App Bundle certificate is also verified before release or Play upload;
- keystores and passwords are never committed to the repository;
- losing the permanent key makes future in-place updates impossible.

Until the permanent key transition is deliberately performed with an ADB data backup/restore plan, keep the current physical-test APK installed and do not uninstall it.

Additional release rules:

- keep upstream version identity visible; add a fork suffix only for published binaries;
- build both `normal` and `play` flavors;
- use the normal flavor as the primary MagicOS build because it preserves SMS;
- do not claim OTP readability merely from service connection, Binder connection, or command completion;
- do not remove a documented platform path solely because one emulator or OEM sample fails;
- keep the persistent notification as an operational status surface.
