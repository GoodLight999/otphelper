package io.github.jd1378.otphelper.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.jd1378.otphelper.MyWorkManager
import io.github.jd1378.otphelper.PersistenceService
import io.github.jd1378.otphelper.utils.AppLogger

const val persistenceWatchdogWorkName = "persistence_watchdog_work"

class PersistenceWatchdogWorker(
    context: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(context, workerParameters) {
  override suspend fun doWork(): Result {
    AppLogger.i("PersistenceWatchdog", "watchdog tick")
    PersistenceService.start(applicationContext)
    PersistenceService.requestListenerRebind(applicationContext)
    MyWorkManager.rebindListeners(applicationContext, true)
    return Result.success()
  }
}
