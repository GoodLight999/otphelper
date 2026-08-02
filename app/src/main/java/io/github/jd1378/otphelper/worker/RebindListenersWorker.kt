package io.github.jd1378.otphelper.worker

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.jd1378.otphelper.ModeOfOperation
import io.github.jd1378.otphelper.NotificationListener
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

    if (userSettings.modeOfOperation == ModeOfOperation.SMS) {
      if (hasSmsPermission(applicationContext)) {
        try {
          applicationContext.startService(Intent(applicationContext, SmsListener::class.java))
        } catch (error: Throwable) {
          AppLogger.e(TAG, "Failed to start SmsListener", error)
        }
        SmsListener.disable(applicationContext)
        SmsListener.enable(applicationContext)
      } else if (!silent) {
        NotificationHelper.sendSmsPermissionRevokedNotif(applicationContext)
      }
    } else {
      SmsListener.disable(applicationContext)
    }

    if (isNotificationListenerServiceEnabled(applicationContext)) {
      try {
        NotificationListener.enable(applicationContext)
        NotificationListener.requestRebind(
            ComponentName(applicationContext, NotificationListener::class.java))
        AppLogger.i(TAG, "notification listener rebind requested")
      } catch (error: Throwable) {
        AppLogger.e(TAG, "Failed to request NotificationListener rebind", error)
        return Result.retry()
      }
    } else if (!silent) {
      NotificationHelper.sendPermissionRevokedNotif(applicationContext)
    }
    return Result.success()
  }
}
