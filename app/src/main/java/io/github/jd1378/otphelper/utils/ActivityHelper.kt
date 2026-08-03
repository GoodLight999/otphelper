package io.github.jd1378.otphelper.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import androidx.core.content.ContextCompat

class ActivityHelper {
  companion object {
    @SuppressLint("QueryPermissionsNeeded")
    fun isCallable(context: Context, intent: Intent): Boolean {
      val packageManager = context.packageManager
      val explicitInfo =
          intent.component?.let { component ->
            runCatching {
                  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getActivityInfo(
                        component,
                        PackageManager.ComponentInfoFlags.of(0),
                    )
                  } else {
                    @Suppress("DEPRECATION") packageManager.getActivityInfo(component, 0)
                  }
                }
                .getOrNull()
          }
      if (intent.component != null) {
        return explicitInfo?.let { isAccessible(context, it) } == true
      }

      return packageManager
          .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
          .mapNotNull { it.activityInfo }
          .any { isAccessible(context, it) }
    }

    private fun isAccessible(context: Context, activityInfo: ActivityInfo): Boolean {
      val samePackage = activityInfo.packageName == context.packageName
      val exportedOrInternal = samePackage || activityInfo.exported
      val requiredPermission = activityInfo.permission
      val permissionGranted =
          requiredPermission.isNullOrBlank() ||
              ContextCompat.checkSelfPermission(context, requiredPermission) ==
                  PackageManager.PERMISSION_GRANTED
      return exportedOrInternal && permissionGranted
    }

    fun adjustFontSize(context: Context, scale: Float = 1.0f): Context {
      val configuration: Configuration = context.resources.configuration
      configuration.fontScale = scale
      return context.createConfigurationContext(configuration)
    }
  }
}
