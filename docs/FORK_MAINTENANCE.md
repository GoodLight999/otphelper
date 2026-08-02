# otphelper fork maintenance

This fork tracks [`jd1378/otphelper`](https://github.com/jd1378/otphelper) while adding an OEM-resilience layer and phrase-list backup support. Keep fork-specific code isolated where practical so upstream merges remain reviewable.

## Fork-specific goals

1. Remain available on aggressive Android firmware, especially HONOR MagicOS.
2. Recover the notification listener after process death, task removal, reboot, app update, and OEM listener detachment.
3. Provide an optional Accessibility notification-event fallback when the standard notification listener is unreliable.
4. Provide an optional Shizuku repair action for notification-listener permission, Doze whitelist, and background AppOps.
5. Export/import sensitive, ignored, and cleanup phrases individually or as one complete backup.

## Persistence architecture

- `PersistenceService`: visible `specialUse` foreground service; returns `START_STICKY`; requests `NotificationListenerService.requestRebind()` every minute.
- `PersistenceWatchdogWorker`: 15-minute WorkManager safety net; restarts the foreground service and requests a listener repair.
- `WatchdogReceiver`: AlarmManager recovery after the user removes the task or the service is destroyed.
- `BootReceiver`: repairs after boot, user unlock, and package replacement.
- `AccessibilityNotificationService`: opt-in fallback restricted to `TYPE_NOTIFICATION_STATE_CHANGED`; it does not retrieve window content or perform gestures.
- `ShizukuRepairManager`: opt-in elevated repair. Normal notification and SMS operation does not require Shizuku.

Android can still prevent automatic resurrection after the user explicitly **Force stops** the app. No ordinary app can override that state until the user launches it again or another permitted system event clears the stopped state.

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

Single-list backups use `kind` and `phrases`. Single-list import also accepts a JSON string array or UTF-8 text with one phrase per line. Imports are normalized, deduplicated in original order, size-limited, and regex-validated before repository writes.

## MagicOS verification checklist

Test on a physical HONOR device after every persistence change:

1. Grant notification access and notification permission.
2. Enable the Accessibility fallback and confirm its description states notification events only.
3. In MagicOS App launch settings, disable automatic management and enable auto-launch, secondary launch, and background running.
4. Exempt the app from battery optimization, or run the Shizuku repair action.
5. Send an OTP notification while the app is open.
6. Send one after leaving the app in Recents for 30 minutes.
7. Remove the app task, wait for the persistent notification to return, then send another OTP.
8. Lock the screen for at least 30 minutes and retest.
9. Reboot and retest before manually opening the app.
10. Update the APK over the installed build and retest.
11. Confirm standard listener plus Accessibility fallback produces only one history/copy action.
12. Export each list and the complete backup, clear settings, then import and compare all entries.

Capture `adb logcat` filtered by these tags when a case fails:

- `PersistenceService`
- `PersistenceWatchdog`
- `WatchdogReceiver`
- `BootReceiver`
- `NotificationListener`
- `AccessibilityNotif`
- `RebindListenersWorker`

## Upstream workflow

`.github/workflows/upstream-sync.yml` runs weekly and can be dispatched manually. It:

1. fetches `jd1378/otphelper:main`;
2. merges it into a dated bot branch;
3. opens a draft PR when the merge is clean;
4. opens an issue when conflicts require manual resolution.

Before merging an upstream-sync PR, review at least the Manifest, `App`, `MainActivity`, `NotificationListener`, WorkManager setup, phrase screens/ViewModels, Gradle dependencies, and resource-string conflicts.

## Release discipline

- Keep upstream version identity visible; add a fork suffix only when publishing binaries.
- Build both `normal` and `play` flavors.
- The `normal` flavor is the primary MagicOS build because it preserves SMS fallback.
- Never silently enable Accessibility. Android requires explicit user consent.
- Treat the persistent notification as a functional status surface, not decoration.
