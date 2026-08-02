package io.github.jd1378.otphelper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.jd1378.otphelper.utils.AppLogger

class WatchdogReceiver : BroadcastReceiver() {
  companion object {
    const val ACTION_RESTART = "io.github.jd1378.otphelper.action.RESTART_PERSISTENCE"
  }

  override fun onReceive(context: Context, intent: Intent?) {
    if (intent?.action != ACTION_RESTART) return
    AppLogger.i("WatchdogReceiver", "restart alarm received")
    PersistenceService.start(context.applicationContext)
    MyWorkManager.schedulePersistenceWatchdog(context.applicationContext)
    MyWorkManager.rebindListeners(context.applicationContext, true)
  }
}
