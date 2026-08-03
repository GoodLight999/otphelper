package io.github.jd1378.otphelper.utils

import android.Manifest
import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.WorkManager
import io.github.jd1378.otphelper.AccessibilityNotificationService
import io.github.jd1378.otphelper.BuildConfig
import io.github.jd1378.otphelper.MainActivity
import io.github.jd1378.otphelper.ModeOfOperation
import io.github.jd1378.otphelper.NotificationListener
import io.github.jd1378.otphelper.PersistenceService
import io.github.jd1378.otphelper.UserSettings
import io.github.jd1378.otphelper.worker.persistenceWatchdogWorkName
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

object DiagnosticsReportManager {
  fun build(context: Context, settings: UserSettings): String {
    val appContext = context.applicationContext
    val activityManager = appContext.getSystemService(ActivityManager::class.java)
    val powerManager = appContext.getSystemService(PowerManager::class.java)
    val notificationManager = NotificationManagerCompat.from(appContext)
    val listenerPermission = NotificationListener.isNotificationListenerServiceEnabled(appContext)
    val accessibilityEnabled = AccessibilityNotificationService.isEnabled(appContext)
    val shizuku = ShizukuConnectionManager.snapshot(appContext)
    val health = MonitoringHealthStore.snapshot(appContext)
    val persistenceRunning = isPersistenceServiceRunning(appContext)
    val excludedFromRecents = isExcludedFromRecents(appContext)
    val watchdogStates = watchdogStates(appContext)
    val receiveSmsGranted =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED
    val readSmsGranted =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED

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
      appendLine("backgroundRestricted=${backgroundRestricted(activityManager)}")
      appendLine("appStandbyBucket=${appStandbyBucket(appContext)}")
      appendLine()
      appendLine("[monitoring]")
      appendLine("postNotificationsGranted=${NotificationHelper.hasNotifPermission(appContext)}")
      appendLine("notificationsEnabled=${notificationManager.areNotificationsEnabled()}")
      appendLine("notificationListenerPermission=$listenerPermission")
      appendLine("notificationListenerActuallyConnected=${health.listenerConnected}")
      appendLine("notificationListenerConnectionChangedAt=${formatMillis(health.listenerChangedAt)}")
      appendLine("accessibilityNotificationServiceEnabled=$accessibilityEnabled")
      appendLine("accessibilityNotificationServiceActuallyConnected=${health.accessibilityConnected}")
      appendLine(
          "accessibilityNotificationConnectionChangedAt=${formatMillis(health.accessibilityChangedAt)}")
      appendLine("receiveSmsGranted=$receiveSmsGranted")
      appendLine("readSmsGranted=$readSmsGranted")
      appendLine("shizukuManagerInstalled=${shizuku.managerInstalled}")
      appendLine("shizukuBinderAlive=${shizuku.binderAlive}")
      appendLine("shizukuBinderEverReceived=${shizuku.binderEverReceived}")
      appendLine("shizukuServerVersion=${shizuku.serverVersion ?: "unknown"}")
      appendLine("shizukuServerUid=${shizuku.serverUid ?: "unknown"}")
      appendLine("shizukuPermission=${shizuku.permission}")
      appendLine("persistenceServiceRunning=$persistenceRunning")
      appendLine("ignoringBatteryOptimizations=${powerManager?.isIgnoringBatteryOptimizations(appContext.packageName) ?: "unknown"}")
      appendLine("watchdogWork=$watchdogStates")
      appendLine("autostartSettingsAvailable=${AutostartHelper.hasAutostartSettings(appContext)}")
      appendLine()
      appendLine("[automatic checks]")
      appendLine(check("App is visible in Recents", !excludedFromRecents))
      when (settings.modeOfOperation) {
        ModeOfOperation.Notification -> {
          appendLine(check("Notification access permission is enabled", listenerPermission))
          appendLine(check("Notification listener is actually connected", health.listenerConnected))
          appendLine(
              if (!accessibilityEnabled) {
                "INFO Optional Accessibility notification-event path is disabled"
              } else {
                check(
                    "Accessibility notification-event service is actually connected",
                    health.accessibilityConnected,
                )
              })
          appendLine(
              when {
                !shizuku.managerInstalled -> "INFO Optional Shizuku Manager is not installed"
                !shizuku.binderAlive ->
                    "INFO Optional Shizuku Manager is installed but its service is not running"
                else -> "PASS Optional Shizuku Binder is connected"
              })
        }
        ModeOfOperation.SMS -> {
          appendLine(check("RECEIVE_SMS permission is granted", receiveSmsGranted))
          appendLine(check("READ_SMS permission is granted", readSmsGranted))
          appendLine("INFO Notification listener, Accessibility and Shizuku paths are not required in SMS mode")
        }
        else -> appendLine("WARN Monitoring mode is not configured")
      }
      appendLine(check("Foreground persistence service is running", persistenceRunning))
      appendLine(
          check(
              "Battery optimization exemption is enabled",
              powerManager?.isIgnoringBatteryOptimizations(appContext.packageName) == true,
          ))
      if (settings.modeOfOperation == ModeOfOperation.Notification &&
          Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        appendLine(
            "INFO Android 15+ can redact OTP content from NotificationListenerService; " +
                "AOSP trusts the listener when RECEIVE_SENSITIVE_NOTIFICATIONS AppOp is allowed")
        appendLine(
            "INFO Optional Shizuku repair applies that AppOp as shell/root and toggles listener " +
                "access to refresh AOSP's trusted-listener UID cache")
        appendLine(
            "INFO Android Accessibility TYPE_NOTIFICATION_STATE_CHANGED remains a public API; " +
                "AOSP may substitute Notification.publicVersion while the device is locked")
      }
      appendLine()
      appendLine("[recent redacted log]")
      val logs = AppLogger.readRecent(appContext)
      append(if (logs.isBlank()) "(no persisted log entries)\n" else logs)
    }
  }

  private fun check(name: String, passed: Boolean): String =
      if (passed) "PASS $name" else "WARN $name"

  private fun timestamp(): String = formatDate(Date())

  private fun formatMillis(value: Long): String =
      if (value <= 0L) "never" else formatDate(Date(value))

  private fun formatDate(date: Date): String {
    val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
    format.timeZone = TimeZone.getDefault()
    return format.format(date)
  }

  private fun backgroundRestricted(activityManager: ActivityManager?): String =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        (activityManager?.isBackgroundRestricted ?: "unknown").toString()
      } else {
        "unsupported"
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
          context.packageManager.getActivityInfo(component, PackageManager.ComponentInfoFlags.of(0))
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
}
