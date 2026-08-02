package io.github.jd1378.otphelper.utils

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import rikka.shizuku.Shizuku

/**
 * Tracks the asynchronous Shizuku Binder lifecycle.
 *
 * `Shizuku.pingBinder()` is only an alive check after Binder delivery; it is not Manager/service
 * discovery. The Binder is delivered asynchronously through ShizukuProvider, so callers must wait
 * for the received listener before using the API.
 */
object ShizukuConnectionManager {
  const val MANAGER_PACKAGE = "moe.shizuku.privileged.api"
  private const val DEFAULT_BINDER_WAIT_MS = 8_000L

  private val initialized = AtomicBoolean(false)
  private val binderEverReceived = AtomicBoolean(false)

  private val binderReceivedListener =
      Shizuku.OnBinderReceivedListener {
        binderEverReceived.set(true)
        AppLogger.i(TAG, "Shizuku Binder received; version=${safeVersion()}, uid=${safeUid()}")
      }

  private val binderDeadListener =
      Shizuku.OnBinderDeadListener { AppLogger.w(TAG, "Shizuku Binder died") }

  private val permissionResultListener =
      Shizuku.OnRequestPermissionResultListener { requestCode, result ->
        AppLogger.i(TAG, "Shizuku permission result: requestCode=$requestCode, result=$result")
      }

  fun initialize(context: Context) {
    if (!initialized.compareAndSet(false, true)) return
    // The provider is created before Application.onCreate. A sticky listener therefore handles
    // both an already-delivered Binder and a Binder delivered later after Shizuku starts/restarts.
    Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
    Shizuku.addBinderDeadListener(binderDeadListener)
    Shizuku.addRequestPermissionResultListener(permissionResultListener)
    AppLogger.i(
        TAG,
        "Shizuku lifecycle initialized; managerInstalled=${isManagerInstalled(context)}, " +
            "binderAlive=${isBinderAlive()}",
    )
  }

  fun isManagerInstalled(context: Context): Boolean =
      runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
              context.packageManager.getPackageInfo(
                  MANAGER_PACKAGE,
                  PackageManager.PackageInfoFlags.of(0),
              )
            } else {
              @Suppress("DEPRECATION")
              context.packageManager.getPackageInfo(MANAGER_PACKAGE, 0)
            }
            true
          }
          .getOrDefault(false)

  fun isBinderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

  /** Waits for actual Binder delivery instead of treating an immediate false ping as absence. */
  fun awaitBinder(context: Context, timeoutMs: Long = DEFAULT_BINDER_WAIT_MS): Boolean {
    initialize(context.applicationContext)
    if (isBinderAlive()) return true

    val latch = CountDownLatch(1)
    val listener = Shizuku.OnBinderReceivedListener { latch.countDown() }
    Shizuku.addBinderReceivedListenerSticky(listener)
    return try {
      if (isBinderAlive()) return true
      latch.await(timeoutMs, TimeUnit.MILLISECONDS)
      isBinderAlive()
    } finally {
      Shizuku.removeBinderReceivedListener(listener)
    }
  }

  fun snapshot(context: Context): ShizukuConnectionSnapshot {
    initialize(context.applicationContext)
    val alive = isBinderAlive()
    val permission =
        if (!alive) {
          "unavailable"
        } else {
          runCatching {
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) "granted"
                else if (Shizuku.shouldShowRequestPermissionRationale()) "denied-permanently"
                else "not-granted"
              }
              .getOrElse { "error:${it.javaClass.simpleName}" }
        }
    return ShizukuConnectionSnapshot(
        managerInstalled = isManagerInstalled(context),
        binderAlive = alive,
        binderEverReceived = binderEverReceived.get(),
        serverVersion = if (alive) safeVersion() else null,
        serverUid = if (alive) safeUid() else null,
        permission = permission,
    )
  }

  private fun safeVersion(): Int? = runCatching { Shizuku.getVersion() }.getOrNull()

  private fun safeUid(): Int? = runCatching { Shizuku.getUid() }.getOrNull()

  private const val TAG = "ShizukuConnection"
}

data class ShizukuConnectionSnapshot(
    val managerInstalled: Boolean,
    val binderAlive: Boolean,
    val binderEverReceived: Boolean,
    val serverVersion: Int?,
    val serverUid: Int?,
    val permission: String,
) {
  override fun toString(): String =
      "managerInstalled=$managerInstalled,binderAlive=$binderAlive," +
          "binderEverReceived=$binderEverReceived,serverVersion=${serverVersion ?: "unknown"}," +
          "serverUid=${serverUid ?: "unknown"},permission=$permission"
}
