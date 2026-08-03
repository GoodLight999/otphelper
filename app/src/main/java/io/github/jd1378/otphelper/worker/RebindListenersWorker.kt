package io.github.jd1378.otphelper.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.jd1378.otphelper.ModeOfOperation
import io.github.jd1378.otphelper.NotificationListener.Companion.isNotificationListenerServiceEnabled
import io.github.jd1378.otphelper.PersistenceService
import io.github.jd1378.otphelper.SmsListener
import io.github.jd1378.otphelper.SmsListener.Companion.hasSmsPermission
import io.github.jd1378.otphelper.repository.UserSettingsRepository
import io.github.jd1378.otphelper.utils.AppLogger
import io.github.jd1378.otphelper.utils.NotificationHelper

const val rebindListenersWorkName = "rebind_listeners_work"

@HiltWorker
class RebindListenersWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val userSettingsRepository: UserSettingsRepository,
) : CoroutineWorker(context, workerParams) {

  companion object {
    const val TAG: String = "RebindListenersWorker"
  }

  override suspend fun doWork(): Result {
    val userSettings = userSettingsRepository.fetchSettings()
    AppLogger.i(
        TAG,
        "doWork: setupFinished=${userSettings.isSetupFinished}, mode=${userSettings.modeOfOperation}",
    )
    if (!userSettings.isSetupFinished) return Result.success()

    val silent = inputData.getBoolean("silent", false)
    PersistenceService.start(applicationContext)

    when (userSettings.modeOfOperation) {
      ModeOfOperation.SMS -> {
        if (hasSmsPermission(applicationContext)) {
          // SmsListener is a manifest BroadcastReceiver, not a Service. Re-enabling its component is
          // the valid repair operation; startService() against it always fails.
          SmsListener.disable(applicationContext)
          SmsListener.enable(applicationContext)
          AppLogger.i(TAG, "SMS receiver component refreshed")
        } else if (!silent) {
          NotificationHelper.sendSmsPermissionRevokedNotif(applicationContext)
        }
      }
      ModeOfOperation.Notification -> {
        SmsListener.disable(applicationContext)
        if (isNotificationListenerServiceEnabled(applicationContext)) {
          PersistenceService.requestListenerRebind(applicationContext)
          AppLogger.i(TAG, "notification listener rebind requested")
        } else if (!silent) {
          NotificationHelper.sendPermissionRevokedNotif(applicationContext)
        }
      }
      else -> AppLogger.w(TAG, "unsupported mode, no listener repair performed")
    }
    return Result.success()
  }
}
