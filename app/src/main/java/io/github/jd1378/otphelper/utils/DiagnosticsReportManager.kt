package io.github.jd1378.otphelper.utils

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.work.WorkManager
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

object DiagnosticsReportManager {
  fun build(context: Context, settings: UserSettings): String {
    val appContext = context.applicationContext
    val activityManager = appContext.getSystemService(ActivityManager::class.java)
    val powerManager = appContext.getSystemService(PowerManager::class.java)
    val notificationManager = NotificationManagerCompat.from(appContext)
    val listenerPermission = NotificationListener.isNotificationListenerServiceEnabled(appContext)
    val health = MonitoringHealthStore.snapshot(appContext)
    val selfTest = NotificationIngestionSelfTest.snapshot(appContext)
    val persistenceRunning = isPersistenceServiceRunning(appContext)
    val excludedFromRecents = isExcludedFromRecents(appContext)
    val watchdogStates = watchdogStates(appContext)
    val shizuku = ShizukuConnectionManager.snapshot(appContext)

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
      appendLine("notificationBodySelfTest=${selfTest.state}")
      appendLine("notificationBodySelfTestStartedAt=${formatMillis(selfTest.startedAt)}")
      appendLine("persistenceServiceRunning=$persistenceRunning")
      appendLine("ignoringBatteryOptimizations=${powerManager?.isIgnoringBatteryOptimizations(appContext.packageName) ?: "unknown"}")
      appendLine("watchdogWork=$watchdogStates")
      appendLine("autostartSettingsAvailable=${AutostartHelper.hasAutostartSettings(appContext)}")
      appendLine()
      appendLine("[shizuku]")
      appendLine("managerPackage=${ShizukuConnectionManager.MANAGER_PACKAGE}")
      appendLine("managerInstalled=${shizuku.managerInstalled}")
      appendLine("binderAlive=${shizuku.binderAlive}")
      appendLine("binderEverReceived=${shizuku.binderEverReceived}")
      appendLine("serverVersion=${shizuku.serverVersion ?: "unknown"}")
      appendLine("serverUid=${shizuku.serverUid ?: "unknown"}")
      appendLine("permission=${shizuku.permission}")
      appendLine()
      appendLine("[automatic checks]")
      appendLine(check("App is visible in Recents", !excludedFromRecents))
      appendLine(check("Notification access permission is enabled", listenerPermission))
      appendLine(check("Notification listener is actually connected", health.listenerConnected))
      appendLine(
          when (selfTest.state) {
            NotificationIngestionSelfTest.State.PASSED ->
                "PASS Actual notification body is readable"
            NotificationIngestionSelfTest.State.IDLE ->
                "INFO Actual notification-body self-test has not been run"
            else -> "WARN Actual notification-body self-test state=${selfTest.state}"
          })
      appendLine(check("Foreground persistence service is running", persistenceRunning))
      appendLine(
          check(
              "Battery optimization exemption is enabled",
              powerManager?.isIgnoringBatteryOptimizations(appContext.packageName) == true,
          ))
      appendLine(
          when {
            !shizuku.managerInstalled -> "INFO Optional Shizuku Manager is not installed"
            shizuku.binderAlive -> "PASS Optional Shizuku Binder is connected"
            else -> "WARN Shizuku Manager is installed but its Binder is not connected"
          })
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
