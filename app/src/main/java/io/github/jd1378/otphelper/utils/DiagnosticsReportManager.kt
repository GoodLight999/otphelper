package io.github.jd1378.otphelper.utils

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import androidx.work.WorkManager
import io.github.jd1378.otphelper.AccessibilityNotificationService
import io.github.jd1378.otphelper.BuildConfig
import io.github.jd1378.otphelper.MainActivity
import io.github.jd1378.otphelper.NotificationListener
import io.github.jd1378.otphelper.PersistenceService
import io.github.jd1378.otphelper.UserSettings
import io.github.jd1378.otphelper.worker.persistenceWatchdogWorkName
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import rikka.shizuku.Shizuku

object DiagnosticsReportManager {
  fun build(context: Context, settings: UserSettings): String {
    val appContext = context.applicationContext
    val activityManager = appContext.getSystemService(ActivityManager::class.java)
    val powerManager = appContext.getSystemService(PowerManager::class.java)
    val notificationManager = NotificationManagerCompat.from(appContext)
    val listenerEnabled = NotificationListener.isNotificationListenerServiceEnabled(appContext)
    val accessibilityEnabled = isAccessibilityFallbackEnabled(appContext)
    val persistenceRunning = isPersistenceServiceRunning(appContext)
    val excludedFromRecents = isExcludedFromRecents(appContext)
    val watchdogStates = watchdogStates(appContext)
    val shizukuState = shizukuState()

    return buildString {
      appendLine("OTP Helper diagnostics")
      appendLine("generated=${timestamp()}")
      appendLine()
      appendLine("[app]")
      appendLine("package=${appContext.packageName}")
      appendLine("versionName=${BuildConfig.VERSION_NAME}")
      appendLine("versionCode=${BuildConfig.VERSION_CODE}")
      appendLine("flavor=${BuildConfig.FLAVOR}")
      appendLine("debug=${BuildConfig.DEBUG}")
      appendLine("setupFinished=${settings.isSetupFinished}")
      appendLine("mode=${settings.modeOfOperation}")
      appendLine("excludedFromRecents=$excludedFromRecents")
      appendLine()
      appendLine("[device]")
      appendLine("manufacturer=${Build.MANUFACTURER}")
      appendLine("brand=${Build.BRAND}")
      appendLine("model=${Build.MODEL}")
      appendLine("device=${Build.DEVICE}")
      appendLine("sdk=${Build.VERSION.SDK_INT}")
      appendLine("release=${Build.VERSION.RELEASE}")
      appendLine("fingerprint=${Build.FINGERPRINT}")
      appendLine("backgroundRestricted=${activityManager?.isBackgroundRestricted ?: "unknown"}")
      appendLine("appStandbyBucket=${appStandbyBucket(appContext)}")
      appendLine()
      appendLine("[monitoring]")
      appendLine("postNotificationsGranted=${NotificationHelper.hasNotifPermission(appContext)}")
      appendLine("notificationsEnabled=${notificationManager.areNotificationsEnabled()}")
      appendLine("notificationListenerEnabled=$listenerEnabled")
      appendLine("accessibilityFallbackEnabled=$accessibilityEnabled")
      appendLine("persistenceServiceRunning=$persistenceRunning")
      appendLine("ignoringBatteryOptimizations=${powerManager?.isIgnoringBatteryOptimizations(appContext.packageName) ?: "unknown"}")
      appendLine("watchdogWork=$watchdogStates")
      appendLine("autostartSettingsAvailable=${AutostartHelper.hasAutostartSettings(appContext)}")
      appendLine("shizuku=$shizukuState")
      appendLine()
      appendLine("[automatic checks]")
      appendLine(check("App is visible in Recents", !excludedFromRecents))
      appendLine(check("Notification access is enabled", listenerEnabled))
      appendLine(check("Foreground persistence service is running", persistenceRunning))
      appendLine(check("Battery optimization exemption is enabled", powerManager?.isIgnoringBatteryOptimizations(appContext.packageName) == true))
      appendLine(
          if (accessibilityEnabled) "PASS Accessibility fallback is enabled"
          else "INFO Accessibility fallback is optional and currently disabled")
      appendLine(
          if (shizukuState.startsWith("available")) "INFO Optional Shizuku is available"
          else "INFO Optional Shizuku is not active; normal monitoring does not depend on it")
      appendLine()
      appendLine("[recent redacted log]")
      val logs = AppLogger.readRecent(appContext)
      append(if (logs.isBlank()) "(no persisted log entries)\n" else logs)
    }
  }

  private fun check(name: String, passed: Boolean): String =
      if (passed) "PASS $name" else "WARN $name"

  private fun timestamp(): String {
    val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
    format.timeZone = TimeZone.getDefault()
    return format.format(Date())
  }

  private fun isAccessibilityFallbackEnabled(context: Context): Boolean {
    val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
    return manager
        .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { info ->
          val serviceInfo = info.resolveInfo?.serviceInfo
          serviceInfo?.packageName == context.packageName &&
              serviceInfo.name == AccessibilityNotificationService::class.java.name
        }
  }

  @Suppress("DEPRECATION")
  private fun isPersistenceServiceRunning(context: Context): Boolean {
    val manager = context.getSystemService(ActivityManager::class.java) ?: return false
    return manager.getRunningServices(Int.MAX_VALUE).any {
      it.service.className == PersistenceService::class.java.name
    }
  }

  private fun isExcludedFromRecents(context: Context): Boolean {
    val component = ComponentName(context, MainActivity::class.java)
    val activityInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          context.packageManager.getActivityInfo(
              component,
              PackageManager.ComponentInfoFlags.of(0),
          )
        } else {
          @Suppress("DEPRECATION")
          context.packageManager.getActivityInfo(component, 0)
        }
    return activityInfo.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0
  }

  private fun watchdogStates(context: Context): String =
      runCatching {
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(persistenceWatchdogWorkName)
                .get(5, TimeUnit.SECONDS)
                .joinToString(prefix = "[", postfix = "]") { it.state.name }
          }
          .getOrElse { "error:${it.javaClass.simpleName}" }

  private fun appStandbyBucket(context: Context): String {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return "unsupported"
    val manager = context.getSystemService(UsageStatsManager::class.java) ?: return "unknown"
    return when (manager.appStandbyBucket) {
      UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "ACTIVE"
      UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "WORKING_SET"
      UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "FREQUENT"
      UsageStatsManager.STANDBY_BUCKET_RARE -> "RARE"
      UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "RESTRICTED"
      else -> manager.appStandbyBucket.toString()
    }
  }

  private fun shizukuState(): String =
      runCatching {
            if (!Shizuku.pingBinder()) {
              "inactive"
            } else {
              val permission =
                  if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) "granted"
                  else "not-granted"
              "available(version=${Shizuku.getVersion()},permission=$permission)"
            }
          }
          .getOrElse { "unavailable:${it.javaClass.simpleName}" }
}
