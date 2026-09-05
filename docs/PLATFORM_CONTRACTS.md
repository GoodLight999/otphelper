# Platform contracts used by this fork

This file records the authoritative contracts behind the fork-specific notification implementation. Device and emulator observations are regression evidence, not substitutes for these contracts.

## Android Accessibility notification events

Primary API reference:

- https://developer.android.com/reference/android/view/accessibility/AccessibilityEvent

Relevant contract:

- `TYPE_NOTIFICATION_STATE_CHANGED` remains a public event type in Android 16.
- `AccessibilityRecord.getParcelableData()` contains the posted `Notification`, when applicable.
- `AccessibilityRecord.getText()` may contain notification text.
- The event source/window hierarchy is never available for this event type.
- API 36 deprecates `TYPE_ANNOUNCEMENT`, not `TYPE_NOTIFICATION_STATE_CHANGED`.

Implementation consequence:

- keep an optional notification-only AccessibilityService;
- subscribe only to `typeNotificationStateChanged`;
- read event text and the parcelable Notification;
- do not request window content, gestures, keys, or screenshots;
- do not claim unrestricted content while locked, because AOSP may provide `Notification.publicVersion` for private notifications.

AOSP source locations used for implementation review:

- https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/core/java/android/view/accessibility/AccessibilityEvent.java
- https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/services/accessibility/java/com/android/server/accessibility/AccessibilityManagerService.java

## Android 15+ sensitive NotificationListener content

Primary API/AOSP source locations:

- https://developer.android.com/reference/android/Manifest.permission#RECEIVE_SENSITIVE_NOTIFICATIONS
- https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/services/core/java/com/android/server/notification/NotificationManagerService.java
- https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/core/java/android/app/AppOpsManager.java

Relevant contract:

- Android protects OTP and other sensitive notification content from untrusted listeners.
- AOSP includes `OP_RECEIVE_SENSITIVE_NOTIFICATIONS` in listener trust evaluation.
- `MODE_ALLOWED` is one official trusted-listener condition.
- trusted listener UIDs are refreshed when listener components are added/enabled.

Implementation consequence:

- the AppOp is applied before notification-listener access is unregistered and re-registered;
- command completion and listener reconnection are reported separately from actual OTP extraction;
- the app never describes Binder connection or AppOp application alone as successful notification parsing.

## Shell authority

AOSP source:

- https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/packages/Shell/AndroidManifest.xml

Relevant contract:

- the Android shell identity has the privileges used by `cmd appops` and notification-listener shell commands, subject to Android-version and OEM policy.

Implementation consequence:

- Shizuku repair accepts only server UID 2000 (shell) or UID 0 (root).

## Shizuku client integration

Official developer guide:

- https://github.com/RikkaApps/Shizuku-API

Relevant contract:

- include `dev.rikka.shizuku:api` and `dev.rikka.shizuku:provider`;
- declare `rikka.shizuku.ShizukuProvider`;
- Binder delivery is asynchronous and must be tracked with Binder received/dead listeners;
- Shizuku APIs must be called only while the Binder is alive;
- request client permission through the official permission API;
- UserService runs as shell UID 2000 or root UID 0;
- use a stable UserService tag when R8 can rename classes;
- implement reserved destroy transaction `16777114` in AIDL and terminate the non-daemon process.

Implementation consequence:

- Manager package visibility and Binder availability are separate states;
- `pingBinder()` is only an alive check after Binder delivery, not Manager discovery;
- the repair path uses a short-lived UserService rather than deprecated/private `newProcess` behavior.

## thedjchi Shizuku fork compatibility

Fork and developer guide:

- https://github.com/thedjchi/Shizuku
- https://github.com/thedjchi/Shizuku-API

Relevant compatibility facts:

- Manager application ID remains `moe.shizuku.privileged.api`.
- Client developers are directed to the standard Shizuku API contract.
- Fork-specific Stealth mode is not assumed or required by OTP Helper.

Implementation consequence:

- OTP Helper uses only the standard public Shizuku client API and the standard Manager package ID.

## LeakCanary

LeakCanary has no production role in OTP Helper. It previously exposed a separate launcher entry named “Leaks” in a debug APK.

Implementation consequence:

- LeakCanary is absent from all variants;
- final APK Manifest inspection fails if a LeakCanary launcher or component appears.
