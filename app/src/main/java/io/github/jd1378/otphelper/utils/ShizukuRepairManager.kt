package io.github.jd1378.otphelper.utils

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import io.github.jd1378.otphelper.BuildConfig
import io.github.jd1378.otphelper.NotificationListener
import io.github.jd1378.otphelper.PersistenceService
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
}

/** Optional elevated repair path. Normal monitoring never depends on Shizuku. */
object ShizukuRepairManager {
  private const val REQUEST_CODE = 0x4f54
  private const val MINIMUM_USER_SERVICE_VERSION = 11
  private const val USER_SERVICE_TAG = "otphelper-background-repair-v1"
  private const val BIND_TIMEOUT_SECONDS = 15L

  fun repair(context: Context): ShizukuRepairResult {
    val appContext = context.applicationContext
    if (!ShizukuConnectionManager.isManagerInstalled(appContext)) {
      return ShizukuRepairResult.MANAGER_NOT_INSTALLED
    }

    // Binder delivery through ShizukuProvider is asynchronous. Never treat an immediate false
    // ping as proof that Shizuku is unavailable.
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
    val commands = buildRepairCommands(packageName, listener, Build.VERSION.SDK_INT)

    val args =
        Shizuku.UserServiceArgs(
                ComponentName(BuildConfig.APPLICATION_ID, RepairUserService::class.java.name))
            .tag(USER_SERVICE_TAG)
            .daemon(false)
            .processNameSuffix("repair")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    val result = CompletableFuture<String>()
    val connection =
        object : ServiceConnection {
          override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Thread(
                    {
                      try {
                        val service = IRepairService.Stub.asInterface(binder)
                        requireNotNull(service) { "Shizuku returned an invalid repair service" }
                        result.complete(service.execute(commands))
                      } catch (error: Throwable) {
                        result.completeExceptionally(error)
                      }
                    },
                    "otphelper-shizuku-repair",
                )
                .start()
          }

          override fun onServiceDisconnected(name: ComponentName?) {
            if (!result.isDone) {
              result.completeExceptionally(
                  IllegalStateException("Shizuku repair service disconnected unexpectedly"))
            }
          }
        }

    try {
      Shizuku.bindUserService(args, connection)
      val output = result.get(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      AppLogger.i("ShizukuRepair", output.ifBlank { "repair commands completed" })
    } finally {
      try {
        Shizuku.unbindUserService(args, connection, true)
      } catch (error: Throwable) {
        AppLogger.w("ShizukuRepair", "unable to unbind repair service: ${error.message}")
      }
    }

    PersistenceService.requestListenerRebind(appContext)
    return ShizukuRepairResult.SUCCESS
  }

  internal fun buildRepairCommands(
      packageName: String,
      listenerComponent: String,
      sdkInt: Int,
  ): Array<String> =
      buildList {
            add("cmd notification allow_listener ${shellQuote(listenerComponent)}")
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
          }
          .toTypedArray()

  private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
}
