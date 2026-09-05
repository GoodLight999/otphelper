package io.github.jd1378.otphelper

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherBoundaryTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val packageManager = context.packageManager

  @Test
  fun launcherAliasIsExportedWhileMainActivityRemainsPrivate() {
    val mainComponent = ComponentName(context, MainActivity::class.java)
    val mainInfo =
        packageManager.getActivityInfo(
            mainComponent,
            PackageManager.ComponentInfoFlags.of(0),
        )
    assertFalse("MainActivity must remain non-exported", mainInfo.exported)

    val launcherComponent = ComponentName(context.packageName, "${context.packageName}.LauncherActivity")
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
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
        )

    assertEquals(
        "OTP Helper must expose exactly one launcher Activity surface",
        1,
        resolved.size,
    )
    assertEquals(launcherComponent.className, resolved.single().activityInfo.name)
    assertEquals(mainComponent.className, resolved.single().activityInfo.targetActivity)
  }
}
