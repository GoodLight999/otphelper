package io.github.jd1378.otphelper

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jd1378.otphelper.utils.MonitoringHealthStore
import io.github.jd1378.otphelper.utils.NotificationIngestionSelfTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * These methods are executed in separate instrumentation processes by the CI script. The script
 * applies the AppOp and force-stops OTP Helper before starting the positive method, matching the
 * user-visible ADB procedure rather than changing trust state inside one live process.
 */
@RunWith(AndroidJUnit4::class)
class NotificationBodyAccessTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val listenerComponent =
      ComponentName(context, NotificationListener::class.java).flattenToString()

  @Test
  fun thirdPartyOtpIsNotReadableWithDefaultAppOp() {
    prepareListener()
    try {
      val probe = NotificationIngestionSelfTest.prepareExternalProbe(context)
      val output =
          executeShellCommand(NotificationIngestionSelfTest.buildFixtureBroadcastCommand(probe))
      assertTrue("Fixture broadcast failed: $output", output.contains("Broadcast completed"))
      val result = NotificationIngestionSelfTest.awaitResult(context)
      assertNotEquals(
          "Third-party OTP was readable with the sensitive-notification AppOp at default",
          NotificationIngestionSelfTest.State.PASSED,
          result,
      )
      assertTrue(
          "Expected redaction or callback suppression, got $result",
          result == NotificationIngestionSelfTest.State.FAILED ||
              result == NotificationIngestionSelfTest.State.TIMED_OUT,
      )
    } finally {
      executeShellCommand("cmd notification disallow_listener $listenerComponent")
    }
  }

  @Test
  fun thirdPartyOtpIsReadableAfterAllowedAppOpAndProcessRestart() {
    prepareListener()
    try {
      val probe = NotificationIngestionSelfTest.prepareExternalProbe(context)
      val output =
          executeShellCommand(NotificationIngestionSelfTest.buildFixtureBroadcastCommand(probe))
      assertTrue("Fixture broadcast failed: $output", output.contains("Broadcast completed"))
      assertEquals(
          "Listener did not receive the real third-party OTP body after AppOp allow and restart",
          NotificationIngestionSelfTest.State.PASSED,
          NotificationIngestionSelfTest.awaitResult(context),
      )
    } finally {
      executeShellCommand("cmd notification disallow_listener $listenerComponent")
    }
  }

  private fun prepareListener() {
    executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
    executeShellCommand(
        "pm grant ${NotificationIngestionSelfTest.FIXTURE_PACKAGE} " +
            Manifest.permission.POST_NOTIFICATIONS)
    MonitoringHealthStore.markListenerConnected(context, false)
    executeShellCommand("cmd notification disallow_listener $listenerComponent")
    executeShellCommand("cmd notification allow_listener $listenerComponent")
    val deadline = SystemClock.elapsedRealtime() + 15_000L
    while (SystemClock.elapsedRealtime() < deadline) {
      if (MonitoringHealthStore.snapshot(context).listenerConnected) return
      Thread.sleep(100L)
    }
    assertTrue(
        "NotificationListenerService did not connect before OTP body test",
        MonitoringHealthStore.snapshot(context).listenerConnected,
    )
  }

  private fun executeShellCommand(command: String): String {
    val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
    return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
  }
}
