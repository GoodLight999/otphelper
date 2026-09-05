package io.github.jd1378.otphelper

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.jd1378.otphelper.utils.AppLogger
import io.github.jd1378.otphelper.utils.SettingsHelper
import io.github.jd1378.otphelper.utils.ShizukuRepairManager
import io.github.jd1378.otphelper.utils.ShizukuRepairResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Private trampoline for actions created by OTP Helper itself.
 *
 * MainActivity must remain exported for the launcher and validated deep links, so internal repair
 * operations must not be dispatched through its public Intent surface. Every caller uses an
 * explicit immutable PendingIntent or an explicit in-app Intent targeting this non-exported
 * Activity.
 */
class InternalActionActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    AppLogger.i(TAG, "onCreate: action=${intent?.action}")
    handleAction()
  }

  override fun onNewIntent(intent: android.content.Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    AppLogger.i(TAG, "onNewIntent: action=${intent.action}")
    handleAction()
  }

  private fun handleAction() {
    when (intent?.action) {
      INTENT_ACTION_OPEN_NOTIFICATION_LISTENER_SETTINGS -> {
        SettingsHelper.openNotificationListenerSettings(this)
        finish()
      }
      INTENT_ACTION_REPAIR_BACKGROUND -> {
        runStandardBackgroundRepair(showConfirmation = true)
        finish()
      }
      INTENT_ACTION_SHIZUKU_REPAIR -> runOptionalShizukuRepair()
      else -> {
        AppLogger.w(TAG, "Unsupported internal action: ${intent?.action}")
        finish()
      }
    }
  }

  private fun runStandardBackgroundRepair(showConfirmation: Boolean) {
    PersistenceService.start(applicationContext)
    MyWorkManager.schedulePersistenceWatchdog(applicationContext)
    MyWorkManager.rebindListeners(applicationContext)
    if (showConfirmation) {
      Toast.makeText(this, R.string.persistence_repair_started, Toast.LENGTH_LONG).show()
    }
  }

  private fun runOptionalShizukuRepair() {
    runStandardBackgroundRepair(showConfirmation = false)
    lifecycleScope.launch {
      val message =
          try {
            when (withContext(Dispatchers.IO) { ShizukuRepairManager.repair(applicationContext) }) {
              ShizukuRepairResult.APPLIED_AND_LISTENER_CONNECTED ->
                  getString(R.string.persistence_shizuku_applied_connected)
              ShizukuRepairResult.APPLIED_RECONNECT_PENDING ->
                  getString(R.string.persistence_shizuku_applied_pending)
              ShizukuRepairResult.MANAGER_NOT_INSTALLED ->
                  getString(R.string.persistence_shizuku_manager_missing)
              ShizukuRepairResult.SERVICE_NOT_RUNNING ->
                  getString(R.string.persistence_shizuku_service_not_running)
              ShizukuRepairResult.UNSUPPORTED ->
                  getString(R.string.persistence_shizuku_unsupported)
              ShizukuRepairResult.INSUFFICIENT_PRIVILEGE ->
                  getString(R.string.persistence_shizuku_insufficient_privilege)
              ShizukuRepairResult.PERMISSION_DENIED ->
                  getString(R.string.persistence_shizuku_permission_denied)
              ShizukuRepairResult.PERMISSION_REQUESTED ->
                  getString(R.string.persistence_shizuku_permission_requested)
            }
          } catch (error: Throwable) {
            AppLogger.e(TAG, "Shizuku notification repair failed", error)
            getString(
                R.string.persistence_shizuku_failed,
                error.cause?.message ?: error.message ?: error.javaClass.simpleName,
            )
          }
      Toast.makeText(this@InternalActionActivity, message, Toast.LENGTH_LONG).show()
      finish()
    }
  }

  companion object {
    private const val TAG = "InternalActionActivity"
  }
}
