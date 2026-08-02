package io.github.jd1378.otphelper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import io.github.jd1378.otphelper.repository.UserSettingsRepository
import io.github.jd1378.otphelper.utils.AppLogger
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

  @Inject lateinit var userSettingsRepository: UserSettingsRepository

  override fun onReceive(context: Context, intent: Intent?) {
    val action = intent?.action
    AppLogger.i("BootReceiver", "onReceive: action=$action")
    if (action !in
        setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_USER_UNLOCKED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )) return

    val appContext = context.applicationContext
    PersistenceService.start(appContext)
    MyWorkManager.schedulePersistenceWatchdog(appContext)
    MyWorkManager.rebindListeners(appContext, true)
  }
}
