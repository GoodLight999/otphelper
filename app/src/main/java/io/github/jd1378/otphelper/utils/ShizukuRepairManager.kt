package io.github.jd1378.otphelper.utils

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import io.github.jd1378.otphelper.BuildConfig
import io.github.jd1378.otphelper.NotificationListener
import io.github.jd1378.otphelper.shizuku.IRepairService
import io.github.jd1378.otphelper.shizuku.RepairUserService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import rikka.shizuku.Shizuku

enum class ShizukuRepairResult {
  SUCCESS,
  MANAGER_NOT_INSTALLED,
  SERVICE_NOT_RUNNING,
  UNSUPPORTED,
  PERMISSION_DENIED,
  PERMISSION_REQUESTED,
  NOTIFICATION_PERMISSION_MISSING,
  NOTIFICATION_LISTENER_NOT_CONNECTED,
  NOTIFICATION_TEXT_UNREADABLE,
}

/** Optional elevated repair path. Normal monitoring never depends on Shizuku. */
object ShizukuRepairManager {
  private const val REQUEST_CODE = 0x4f54
  private const val MINIMUM_USER_SERVICE_VERSION = 11
  private const val USER_SERVICE_TAG = "otphelper-background-repair-v1"
  private const val BIND_TIMEOUT_SECONDS = 15L
  private const val LISTENER_WAIT_MS = 10_000L

  fun repair(context: Context): ShizukuRepairResult {
    val appContext = context.applicationContext
    if (!ShizukuConnectionManager.isManagerInstalled(appContext)) {
      return ShizukuRepairResult.MANAGER_NOT_INSTALLED
    }

    if (!ShizukuConnectionManager.awaitBinder(appContext)) {
      return ShizukuRepairResult.SERVICE_NOT_RUNNING
    }
    if (Shizuku.getVersion() < MINIMUM_USER_SERVICE_VERSION) {
      return ShizukuRepairResult.UNSUPPORTED
    }
    if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
      if (Shizuku.shouldShowRequestPermissionRationale()) {
        return ShizukuRepairResult.PERMISSION_DENIED
      }
      Handler(Looper.getMainLooper()).post { Shizuku.requestPermission(REQUEST_CODE) }
      return ShizukuRepairResult.PERMISSION_REQUESTED
    }

    val packageName = appContext.packageName
    val listener = ComponentName(appContext, NotificationListener::class.java).flattenToString()
    val repairCommands = buildRepairCommands(packageName, listener, Build.VERSION.SDK_INT)

    val args =
        Shizuku.UserServiceArgs(
                ComponentName(BuildConfig.APPLICATION_ID, RepairUserService::class.java.name))
            .tag(USER_SERVICE_TAG)
            .daemon(false)
            .processNameSuffix("repair")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    val serviceFuture = CompletableFuture<IRepairService>()
    val connection =
        object : ServiceConnection {
          override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = IRepairService.Stub.asInterface(binder)
            if (service == null) {
              serviceFuture.completeExceptionally(
                  IllegalStateException("Shizuku returned an invalid repair service"))
            } else {
              serviceFuture.complete(service)
            }
          }

          override fun onServiceDisconnected(name: ComponentName?) {
            if (!serviceFuture.isDone) {
              serviceFuture.completeExceptionally(
                  IllegalStateException("Shizuku repair service disconnected unexpectedly"))
            }
          }
        }

    try {
      Shizuku.bindUserService(args, connection)
      val service = serviceFuture.get(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)

      // NotificationManagerService can retain the listener's trust state for the lifetime of the
      // connection. Apply the AppOp first, then force a complete disallow/allow reconnect so the
      // new sensitive-notification trust state is evaluated before the probe is posted.
      MonitoringHealthStore.markListenerConnected(appContext, false)
      val repairOutput = service.execute(repairCommands)
      AppLogger.i("ShizukuRepair", repairOutput.ifBlank { "repair commands completed" })

      if (!waitForListenerConnection(appContext)) {
        return ShizukuRepairResult.NOTIFICATION_LISTENER_NOT_CONNECTED
      }
      if (!NotificationHelper.hasNotifPermission(appContext) ||
          !NotificationManagerCompat.from(appContext).areNotificationsEnabled()) {
        return ShizukuRepairResult.NOTIFICATION_PERMISSION_MISSING
      }

      // Post from the Shizuku/shell side. A same-package self-notification would not prove that
      // real third-party OTP bodies survive Android's sensitive-notification redaction.
      val probe = NotificationIngestionSelfTest.prepareExternalProbe(appContext)
      val probeOutput = service.execute(arrayOf(buildProbeCommand(probe)))
      AppLogger.i("ShizukuRepair", probeOutput.ifBlank { "external probe notification posted" })
      return when (NotificationIngestionSelfTest.awaitResult(appContext)) {
        NotificationIngestionSelfTest.State.PASSED -> ShizukuRepairResult.SUCCESS
        else -> ShizukuRepairResult.NOTIFICATION_TEXT_UNREADABLE
      }
    } finally {
      try {
        Shizuku.unbindUserService(args, connection, true)
      } catch (error: Throwable) {
        AppLogger.w("ShizukuRepair", "unable to unbind repair service: ${error.message}")
      }
    }
  }

  internal fun buildRepairCommands(
      packageName: String,
      listenerComponent: String,
      sdkInt: Int,
  ): Array<String> =
      buildList {
            add("cmd deviceidle whitelist +${shellQuote(packageName)}")
            add(
                "cmd appops set --user current ${shellQuote(packageName)} " +
                    "RUN_IN_BACKGROUND allow")
            add(
                "cmd appops set --user current ${shellQuote(packageName)} " +
                    "RUN_ANY_IN_BACKGROUND allow")
            if (sdkInt >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
              add(
                  "cmd appops set --user current ${shellQuote(packageName)} " +
                      "RECEIVE_SENSITIVE_NOTIFICATIONS allow")
            }
            add("cmd notification disallow_listener ${shellQuote(listenerComponent)}")
            add("cmd notification allow_listener ${shellQuote(listenerComponent)}")
          }
          .toTypedArray()

  internal fun buildProbeCommand(probe: NotificationIngestionSelfTest.Probe): String =
      "cmd notification post -t ${shellQuote("OTP Helper external read test")} " +
          "${shellQuote(probe.tag)} " +
          shellQuote("One-time verification code: ${probe.token}")

  private fun waitForListenerConnection(context: Context): Boolean {
    val deadline = SystemClock.elapsedRealtime() + LISTENER_WAIT_MS
    while (SystemClock.elapsedRealtime() < deadline) {
      if (MonitoringHealthStore.snapshot(context).listenerConnected) return true
      Thread.sleep(100L)
    }
    return MonitoringHealthStore.snapshot(context).listenerConnected
  }

  private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
}
