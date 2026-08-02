package io.github.jd1378.otphelper

import android.Manifest
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import io.github.jd1378.otphelper.utils.MonitoringHealthStore
import io.github.jd1378.otphelper.worker.persistenceWatchdogWorkName
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResilienceManifestTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val packageManager = context.packageManager

  @Test
  fun launcherRemainsVisibleInRecents() {
    val activityInfo =
        packageManager.getActivityInfo(
            ComponentName(context, MainActivity::class.java),
            PackageManager.ComponentInfoFlags.of(0),
        )
    assertEquals(0, activityInfo.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS)

    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
      scenario.onActivity { activity ->
        val tasks = activity.getSystemService(ActivityManager::class.java).appTasks
        assertTrue("OTP Helper should own a visible recent task", tasks.isNotEmpty())
        assertFalse(
            tasks.first().taskInfo.baseIntent.flags and
                android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS != 0)
      }
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
  fun foregroundServiceAndWatchdogCanStart() {
    ActivityScenario.launch(MainActivity::class.java).use {
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
    }
  }

  @Test
  fun notificationListenerCanActuallyBindOnApi35() {
    val component = ComponentName(context, NotificationListener::class.java).flattenToString()
    MonitoringHealthStore.markListenerConnected(context, false)
    try {
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
  fun accessibilityFallbackCanActuallyBindOnApi35() {
    val component =
        ComponentName(context, AccessibilityNotificationService::class.java).flattenToString()
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
      executeShellCommand("settings put secure enabled_accessibility_services $mergedServices")
      executeShellCommand("settings put secure accessibility_enabled 1")
      assertTrue(
          "Accessibility fallback did not report onServiceConnected after enablement",
          waitForHealth { it.accessibilityConnected },
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
    }
  }

  @Test
  fun monitoringHealthDistinguishesPermissionFromActualConnection() {
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

  private fun waitForHealth(predicate: (io.github.jd1378.otphelper.utils.MonitoringHealthSnapshot) -> Boolean): Boolean {
    val deadline = SystemClock.elapsedRealtime() + 15_000L
    while (SystemClock.elapsedRealtime() < deadline) {
      if (predicate(MonitoringHealthStore.snapshot(context))) return true
      Thread.sleep(100L)
    }
    return predicate(MonitoringHealthStore.snapshot(context))
  }

  private fun executeShellCommand(command: String): String {
    val descriptor =
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
  }
}
