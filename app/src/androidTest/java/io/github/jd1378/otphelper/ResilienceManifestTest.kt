package io.github.jd1378.otphelper

import android.Manifest
import android.app.ActivityManager
import android.app.PendingIntent
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import io.github.jd1378.otphelper.utils.MonitoringHealthSnapshot
import io.github.jd1378.otphelper.utils.MonitoringHealthStore
import io.github.jd1378.otphelper.worker.persistenceWatchdogWorkName
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import rikka.shizuku.ShizukuProvider

@RunWith(AndroidJUnit4::class)
class ResilienceManifestTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val packageManager = context.packageManager
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val nonSuppressingUiAutomation by lazy {
    instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
  }

  @Test
  fun launcherRemainsVisibleInRecents() {
    val activityInfo =
        packageManager.getActivityInfo(
            ComponentName(context, MainActivity::class.java),
            PackageManager.ComponentInfoFlags.of(0),
        )
    assertEquals(0, activityInfo.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS)

    val activity = launchMainActivity()
    try {
      val tasks = activity.getSystemService(ActivityManager::class.java).appTasks
      val mainTasks =
          tasks.filter { task ->
            task.taskInfo.baseIntent.component?.className == MainActivity::class.java.name
          }
      assertTrue("OTP Helper should own a visible recent task", tasks.isNotEmpty())
      assertTrue("OTP Helper should have a task rooted in MainActivity", mainTasks.isNotEmpty())
      assertTrue(
          "OTP Helper's recent task must not use FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS",
          mainTasks.all { task ->
            task.taskInfo.baseIntent.flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS == 0
          },
      )
    } finally {
      finishActivity(activity)
    }
  }

  @Test
  fun resilienceComponentsHaveExpectedPermissionsAndTypes() {
    val persistence =
        packageManager.getServiceInfo(
            ComponentName(context, PersistenceService::class.java),
            PackageManager.ComponentInfoFlags.of(0),
        )
    assertFalse(persistence.exported)
    assertTrue(
        persistence.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE != 0)

    val listener =
        packageManager.getServiceInfo(
            ComponentName(context, NotificationListener::class.java),
            PackageManager.ComponentInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
        )
    assertFalse(listener.exported)
    assertEquals(Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE, listener.permission)

    val accessibility =
        packageManager.getServiceInfo(
            ComponentName(context, AccessibilityNotificationService::class.java),
            PackageManager.ComponentInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
        )
    assertTrue(accessibility.exported)
    assertEquals(Manifest.permission.BIND_ACCESSIBILITY_SERVICE, accessibility.permission)
    assertNotNull(accessibility.metaData)

    val shizukuProvider =
        packageManager.getProviderInfo(
            ComponentName(context, ShizukuProvider::class.java),
            PackageManager.ComponentInfoFlags.of(0),
        )
    assertTrue(shizukuProvider.exported)
    assertEquals("android.permission.INTERACT_ACROSS_USERS_FULL", shizukuProvider.readPermission)
    assertEquals("${context.packageName}.shizuku", shizukuProvider.authority)

    val actionReceiver =
        packageManager.getReceiverInfo(
            ComponentName(context, NotifActionReceiver::class.java),
            PackageManager.ComponentInfoFlags.of(0),
        )
    assertFalse(actionReceiver.exported)
    assertEquals(
        "io.github.jd1378.otphelper.permission.BROADCAST_CODE",
        actionReceiver.permission,
    )

    val bootReceiver =
        packageManager.getReceiverInfo(
            ComponentName(context, BootReceiver::class.java),
            PackageManager.ComponentInfoFlags.of(0),
        )
    assertFalse(bootReceiver.exported)

    val watchdogReceiver =
        packageManager.getReceiverInfo(
            ComponentName(context, WatchdogReceiver::class.java),
            PackageManager.ComponentInfoFlags.of(0),
        )
    assertFalse(watchdogReceiver.exported)
  }

  @Test
  fun privateNotificationActionReceiverAcceptsExplicitPendingIntent() {
    val pendingIntent =
        PendingIntent.getBroadcast(
            context,
            0x4f54,
            Intent(context, NotifActionReceiver::class.java)
                .setAction(NotifActionReceiver.INTENT_ACTION_CODE_COPY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    pendingIntent.send()
    instrumentation.waitForIdleSync()
  }

  @Test
  fun internalActionActivityIsPrivateButReachableInApp() {
    val info =
        packageManager.getActivityInfo(
            ComponentName(context, InternalActionActivity::class.java),
            PackageManager.ComponentInfoFlags.of(0),
        )
    assertFalse(info.exported)
    assertTrue(info.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0)
    assertTrue(info.flags and ActivityInfo.FLAG_NO_HISTORY != 0)

    // UiAutomation.executeShellCommand does not itself interpret shell redirection. Invoke an
    // explicit Android shell so stderr from ActivityManager is captured instead of passing
    // "2>&1" to `am` as an ordinary argument.
    val externalAttempt =
        executeShellCommand(
            "sh -c 'am start -W -n ${context.packageName}/.InternalActionActivity " +
                "-a $INTENT_ACTION_REPAIR_BACKGROUND 2>&1; echo __EXIT__:\$?'",
        )
    assertTrue(
        "Shell UID unexpectedly launched the private internal Activity: $externalAttempt",
        externalAttempt.contains("Permission Denial", ignoreCase = true) ||
            externalAttempt.contains("not exported", ignoreCase = true) ||
            externalAttempt.contains("SecurityException", ignoreCase = true),
    )
    assertFalse(
        "ActivityManager reported success for a non-exported Activity: $externalAttempt",
        externalAttempt.contains("__EXIT__:0"),
    )

    context.startActivity(
        Intent(context, InternalActionActivity::class.java)
            .setAction(INTENT_ACTION_REPAIR_BACKGROUND)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    instrumentation.waitForIdleSync()
  }

  @Test
  fun foregroundServiceAndWatchdogCanStart() {
    val activity = launchMainActivity()
    try {
      PersistenceService.start(context)
      MyWorkManager.schedulePersistenceWatchdog(context)
      Thread.sleep(2_000)

      @Suppress("DEPRECATION")
      val services =
          context.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
      assertTrue(
          "PersistenceService should be running",
          services.any { service ->
            service.service.className == PersistenceService::class.java.name
          },
      )

      val watchdog =
          WorkManager.getInstance(context)
              .getWorkInfosForUniqueWork(persistenceWatchdogWorkName)
              .get(10, TimeUnit.SECONDS)
      assertTrue("Persistence watchdog should be scheduled", watchdog.isNotEmpty())
    } finally {
      finishActivity(activity)
    }
  }

  @Test
  fun notificationListenerCanActuallyBind() {
    val component = ComponentName(context, NotificationListener::class.java).flattenToString()
    MonitoringHealthStore.markListenerConnected(context, false)
    try {
      executeShellCommand("cmd notification disallow_listener $component")
      executeShellCommand("cmd notification allow_listener $component")
      assertTrue(
          "NotificationListenerService did not report onListenerConnected after permission grant",
          waitForHealth { it.listenerConnected },
      )
    } finally {
      executeShellCommand("cmd notification disallow_listener $component")
    }
  }

  @Test
  fun accessibilityNotificationServiceCanActuallyBind() {
    val component =
        ComponentName(context, AccessibilityNotificationService::class.java).flattenToShortString()
    val previousServices = executeShellCommand("settings get secure enabled_accessibility_services").trim()
    val previousEnabled = executeShellCommand("settings get secure accessibility_enabled").trim()
    val restoredServices = previousServices.takeUnless { it.isBlank() || it == "null" }
    val mergedServices =
        restoredServices
            ?.split(':')
            ?.filter { it.isNotBlank() }
            ?.plus(component)
            ?.distinct()
            ?.joinToString(":")
            ?: component

    MonitoringHealthStore.markAccessibilityConnected(context, false)
    try {
      executeShellCommand(
          "cmd appops set --user current ${context.packageName} " +
              "ACCESS_RESTRICTED_SETTINGS allow",
      )
      executeShellCommand("settings put secure enabled_accessibility_services $mergedServices")
      executeShellCommand("settings put secure accessibility_enabled 1")

      val effectiveServices =
          executeShellCommand("settings get secure enabled_accessibility_services").trim()
      assertTrue(
          "Accessibility component was not persisted in secure settings: $effectiveServices",
          effectiveServices.split(':').contains(component),
      )
      assertTrue(
          "Accessibility notification service did not report onServiceConnected",
          waitForHealth(timeoutMs = 30_000L) { it.accessibilityConnected },
      )
    } finally {
      if (restoredServices == null) {
        executeShellCommand("settings delete secure enabled_accessibility_services")
      } else {
        executeShellCommand("settings put secure enabled_accessibility_services $restoredServices")
      }
      if (previousEnabled.isBlank() || previousEnabled == "null") {
        executeShellCommand("settings delete secure accessibility_enabled")
      } else {
        executeShellCommand("settings put secure accessibility_enabled $previousEnabled")
      }
      executeShellCommand(
          "cmd appops set --user current ${context.packageName} " +
              "ACCESS_RESTRICTED_SETTINGS default",
      )
    }
  }

  @Test
  fun monitoringHealthDistinguishesPermissionFromActualConnections() {
    MonitoringHealthStore.markProcessStarted(context)
    var snapshot = MonitoringHealthStore.snapshot(context)
    assertFalse(snapshot.listenerConnected)
    assertFalse(snapshot.accessibilityConnected)

    MonitoringHealthStore.markListenerConnected(context, true)
    MonitoringHealthStore.markAccessibilityConnected(context, true)
    snapshot = MonitoringHealthStore.snapshot(context)
    assertTrue(snapshot.listenerConnected)
    assertTrue(snapshot.accessibilityConnected)
    assertTrue(snapshot.listenerChangedAt > 0L)
    assertTrue(snapshot.accessibilityChangedAt > 0L)

    MonitoringHealthStore.markListenerConnected(context, false)
    snapshot = MonitoringHealthStore.snapshot(context)
    assertFalse(snapshot.listenerConnected)
    assertTrue(snapshot.accessibilityConnected)
  }

  private fun launchMainActivity(): MainActivity {
    val intent =
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    val activity = instrumentation.startActivitySync(intent)
    instrumentation.waitForIdleSync()
    return activity as MainActivity
  }

  private fun finishActivity(activity: MainActivity) {
    instrumentation.runOnMainSync { activity.finishAndRemoveTask() }
    instrumentation.waitForIdleSync()
  }

  private fun waitForHealth(
      timeoutMs: Long = 15_000L,
      predicate: (MonitoringHealthSnapshot) -> Boolean,
  ): Boolean {
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    while (SystemClock.elapsedRealtime() < deadline) {
      if (predicate(MonitoringHealthStore.snapshot(context))) return true
      Thread.sleep(100L)
    }
    return predicate(MonitoringHealthStore.snapshot(context))
  }

  private fun executeShellCommand(command: String): String {
    val descriptor = nonSuppressingUiAutomation.executeShellCommand(command)
    return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
  }
}
