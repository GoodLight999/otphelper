package io.github.jd1378.otphelper

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherBoundaryTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val packageManager = context.packageManager
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val mainComponent = ComponentName(context, MainActivity::class.java)
  private val launcherComponent =
      ComponentName(context.packageName, "${context.packageName}.LauncherActivity")

  @Test
  fun launcherAliasIsExportedWhileMainActivityRemainsPrivate() {
    val mainInfo =
        packageManager.getActivityInfo(
            mainComponent,
            PackageManager.ComponentInfoFlags.of(0),
        )
    assertFalse("MainActivity must remain non-exported", mainInfo.exported)

    val launcherInfo =
        packageManager.getActivityInfo(
            launcherComponent,
            PackageManager.ComponentInfoFlags.of(0),
        )
    assertTrue("Launcher alias must remain exported", launcherInfo.exported)
    assertEquals(mainComponent.className, launcherInfo.targetActivity)

    val launcherIntent =
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(context.packageName)
    val resolved =
        packageManager.queryIntentActivities(
            launcherIntent,
            PackageManager.ResolveInfoFlags.of(0),
        )

    assertEquals(
        "OTP Helper must expose exactly one launcher Activity surface",
        1,
        resolved.size,
    )
    assertEquals(launcherComponent.className, resolved.single().activityInfo.name)
    assertEquals(mainComponent.className, resolved.single().activityInfo.targetActivity)
  }

  @Test
  fun launcherAliasCreatesVisibleRecentsTask() {
    val launcherIntent =
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(launcherComponent)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

    val activity = instrumentation.startActivitySync(launcherIntent)
    try {
      instrumentation.waitForIdleSync()
      assertTrue("Launcher alias did not reach MainActivity", activity is MainActivity)

      val tasks = context.getSystemService(ActivityManager::class.java).appTasks
      val launchedTask =
          tasks.firstOrNull { task ->
            val baseComponent = task.taskInfo.baseIntent.component
            baseComponent == launcherComponent || baseComponent?.className == mainComponent.className
          }
      assertNotNull("Launcher alias did not create an OTP Helper Recents task", launchedTask)
      assertEquals(
          "Launcher-created task must remain visible in Recents",
          0,
          launchedTask!!.taskInfo.baseIntent.flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
      )
    } finally {
      instrumentation.runOnMainSync { activity.finishAndRemoveTask() }
      instrumentation.waitForIdleSync()
    }
  }
}
