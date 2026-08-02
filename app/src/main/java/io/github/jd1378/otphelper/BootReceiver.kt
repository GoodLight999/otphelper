package io.github.jd1378.otphelper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.jd1378.otphelper.utils.AppLogger

class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    val action = intent?.action
    AppLogger.i("BootReceiver", "onReceive: action=$action")
    if (action !in
        setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_USER_UNLOCKED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )) return

    val appContext = context.applicationContext
    PersistenceService.start(appContext)
    MyWorkManager.schedulePersistenceWatchdog(appContext)
    MyWorkManager.rebindListeners(appContext, true)
  }
}
