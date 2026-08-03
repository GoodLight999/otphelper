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
import io.github.jd1378.otphelper.BuildConfig
import io.github.jd1378.otphelper.NotificationListener
import io.github.jd1378.otphelper.shizuku.IRepairService
import io.github.jd1378.otphelper.shizuku.RepairUserService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import rikka.shizuku.Shizuku

enum class ShizukuRepairResult {
  APPLIED_AND_LISTENER_CONNECTED,
  APPLIED_RECONNECT_PENDING,
  MANAGER_NOT_INSTALLED,
  SERVICE_NOT_RUNNING,
  UNSUPPORTED,
  INSUFFICIENT_PRIVILEGE,
  PERMISSION_DENIED,
  PERMISSION_REQUESTED,
}

/**
 * Optional Android 15+ notification repair backed by the official Shizuku UserService API.
 *
 * AOSP treats a NotificationListenerService UID as trusted when the
 * RECEIVE_SENSITIVE_NOTIFICATIONS AppOp is MODE_ALLOWED. AOSP caches trusted listener UIDs when
 * the service is added/enabled, so the repair toggles listener access after applying the AppOp.
 * It does not claim that a particular OEM notification was read; it reports only that the official
 * trust-setting operation was applied and whether the listener reconnected.
 */
object ShizukuRepairManager {
  private const val REQUEST_CODE = 0x4f54
  private const val MINIMUM_USER_SERVICE_VERSION = 11
  private const val USER_SERVICE_TAG = "otphelper-sensitive-notification-repair-v1"
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
    val shizukuUid = Shizuku.getUid()
    if (shizukuUid != 0 && shizukuUid != 2000) {
      return ShizukuRepairResult.INSUFFICIENT_PRIVILEGE
    }
    if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
      if (Shizuku.shouldShowRequestPermissionRationale()) {
        return ShizukuRepairResult.PERMISSION_DENIED
      }
      Handler(Looper.getMainLooper()).post { Shizuku.requestPermission(REQUEST_CODE) }
      return ShizukuRepairResult.PERMISSION_REQUESTED
    }

    val listenerComponent =
        ComponentName(appContext, NotificationListener::class.java).flattenToString()
    val commands = buildRepairCommands(appContext.packageName, listenerComponent, Build.VERSION.SDK_INT)
    val args =
        Shizuku.UserServiceArgs(
                ComponentName(BuildConfig.APPLICATION_ID, RepairUserService::class.java.name))
            .tag(USER_SERVICE_TAG)
            .daemon(false)
            .processNameSuffix("notification_repair")
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
      val output = service.execute(commands)
      AppLogger.i("ShizukuRepair", output.ifBlank { "notification repair commands completed" })
      return if (waitForListenerConnection(appContext)) {
        ShizukuRepairResult.APPLIED_AND_LISTENER_CONNECTED
      } else {
        ShizukuRepairResult.APPLIED_RECONNECT_PENDING
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
            if (sdkInt >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
              add(
                  "cmd appops set --user current ${shellQuote(packageName)} " +
                      "RECEIVE_SENSITIVE_NOTIFICATIONS allow")
            }
            // AOSP stores trusted listener UIDs when the component is added/enabled.
            add("cmd notification disallow_listener ${shellQuote(listenerComponent)}")
            add("cmd notification allow_listener ${shellQuote(listenerComponent)}")
          }
          .toTypedArray()

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
