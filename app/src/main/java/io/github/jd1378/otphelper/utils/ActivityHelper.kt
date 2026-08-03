package io.github.jd1378.otphelper.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.core.content.ContextCompat

class ActivityHelper {
  companion object {
    @SuppressLint("QueryPermissionsNeeded")
    fun isCallable(context: Context, intent: Intent): Boolean {
      val matches =
          context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
      return matches.any { resolveInfo ->
        val activityInfo = resolveInfo.activityInfo ?: return@any false
        val samePackage = activityInfo.packageName == context.packageName
        val exportedOrInternal = samePackage || activityInfo.exported
        val requiredPermission = activityInfo.permission
        val permissionGranted =
            requiredPermission.isNullOrBlank() ||
                ContextCompat.checkSelfPermission(context, requiredPermission) ==
                    PackageManager.PERMISSION_GRANTED
        exportedOrInternal && permissionGranted
      }
    }

    fun adjustFontSize(context: Context, scale: Float = 1.0f): Context {
      val configuration: Configuration = context.resources.configuration
      configuration.fontScale = scale
      return context.createConfigurationContext(configuration)
    }
  }
}
