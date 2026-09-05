package io.github.jd1378.otphelper

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jd1378.otphelper.ui.navigation.MainDestinations
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivateActivityPendingIntentTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val instrumentation = InstrumentationRegistry.getInstrumentation()

  @Test
  fun appCreatedDeepLinkPendingIntentLaunchesPrivateMainActivity() {
    val info =
        context.packageManager.getActivityInfo(
            ComponentName(context, MainActivity::class.java),
            PackageManager.ComponentInfoFlags.of(0),
        )
    assertFalse("MainActivity must remain non-exported", info.exported)

    val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
    try {
      val pendingIntent =
          getDeepLinkPendingIntent(
              context,
              MainDestinations.SETTINGS_ROUTE,
          )
      pendingIntent.send()

      val activity = instrumentation.waitForMonitorWithTimeout(monitor, 10_000L)
      assertNotNull(
          "App-created PendingIntent did not launch the private MainActivity",
          activity,
      )
      activity?.runOnUiThread { activity.finishAndRemoveTask() }
      instrumentation.waitForIdleSync()
    } finally {
      instrumentation.removeMonitor(monitor)
    }
  }
}
