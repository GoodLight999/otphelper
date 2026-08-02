package io.github.jd1378.otphelper

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.github.jd1378.otphelper.MyWorkManager.doCleanupPhrasesMigration
import io.github.jd1378.otphelper.MyWorkManager.doDataMigration
import io.github.jd1378.otphelper.MyWorkManager.enableHistoryCleanup
import io.github.jd1378.otphelper.repository.UserSettingsRepository
import io.github.jd1378.otphelper.utils.ActivityHelper
import io.github.jd1378.otphelper.utils.AppLogger
import io.github.jd1378.otphelper.utils.SettingsHelper
import io.github.jd1378.otphelper.utils.ShizukuRepairManager
import io.github.jd1378.otphelper.utils.ShizukuRepairResult
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val INTENT_ACTION_OPEN_NOTIFICATION_LISTENER_SETTINGS =
    "INTENT_ACTION_OPEN_NOTIFICATION_LISTENER_SETTINGS"

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

  @Inject lateinit var userSettingsRepository: UserSettingsRepository
  @Inject lateinit var deepLinkHandler: DeepLinkHandler

  companion object {
    const val scale = 1.15f
  }

  override fun attachBaseContext(newBase: Context) {
    super.attachBaseContext(ActivityHelper.adjustFontSize(newBase, scale))
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    installSplashScreen()
    super.onCreate(savedInstanceState)
    AppLogger.i("MainActivity", "onCreate")
    ActivityHelper.adjustFontSize(this, scale)

    handleIntent(intent)

    lifecycleScope.launch {
      val settings = userSettingsRepository.fetchSettings()
      AppLogger.i(
          "MainActivity",
          "settings loaded: setupFinished=${settings.isSetupFinished}, " +
              "mode=${settings.modeOfOperation}, migrationDone=${settings.isMigrationDone}, " +
              "cleanupPhrasesMigrated=${settings.isCleanupPhrasesMigrated}",
      )

      if (!settings.isMigrationDone) {
        doDataMigration(applicationContext)
        enableHistoryCleanup(applicationContext)
      } else if (!settings.isCleanupPhrasesMigrated) {
        doCleanupPhrasesMigration(applicationContext)
      }
    }

    PersistenceService.start(applicationContext)
    MyWorkManager.schedulePersistenceWatchdog(applicationContext)
    setContent { OtpHelperApp(deepLinkHandler) }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleIntent(intent)
  }

  override fun onStart() {
    super.onStart()
    handleIntent(intent)
    intent = null
  }

  private fun handleIntent(intent: Intent?) {
    AppLogger.d("MainActivity", "handleIntent: action=${intent?.action}, data=${intent?.data}")
    when (intent?.action) {
      INTENT_ACTION_OPEN_NOTIFICATION_LISTENER_SETTINGS ->
          SettingsHelper.openNotificationListenerSettings(this)
      INTENT_ACTION_REPAIR_BACKGROUND -> runStandardBackgroundRepair(showConfirmation = true)
      INTENT_ACTION_SHIZUKU_REPAIR -> runOptionalShizukuRepair()
      else -> deepLinkHandler.handleDeepLink(intent)
    }
    if (intent?.action == INTENT_ACTION_REPAIR_BACKGROUND ||
        intent?.action == INTENT_ACTION_SHIZUKU_REPAIR) {
      intent.action = null
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
    // Standard recovery is always attempted first. Shizuku is only retained if the post-repair
    // notification-body self-test succeeds.
    runStandardBackgroundRepair(showConfirmation = false)
    lifecycleScope.launch {
      val message =
          try {
            when (withContext(Dispatchers.IO) { ShizukuRepairManager.repair(applicationContext) }) {
              ShizukuRepairResult.SUCCESS -> getString(R.string.persistence_shizuku_success)
              ShizukuRepairResult.MANAGER_NOT_INSTALLED ->
                  getString(R.string.persistence_shizuku_manager_missing)
              ShizukuRepairResult.SERVICE_NOT_RUNNING ->
                  getString(R.string.persistence_shizuku_service_not_running)
              ShizukuRepairResult.UNSUPPORTED ->
                  getString(R.string.persistence_shizuku_unsupported)
              ShizukuRepairResult.PERMISSION_DENIED ->
                  getString(R.string.persistence_shizuku_permission_denied)
              ShizukuRepairResult.PERMISSION_REQUESTED ->
                  getString(R.string.persistence_shizuku_permission_requested)
              ShizukuRepairResult.NOTIFICATION_PERMISSION_MISSING ->
                  getString(R.string.persistence_shizuku_notification_permission_missing)
              ShizukuRepairResult.NOTIFICATION_LISTENER_NOT_CONNECTED ->
                  getString(R.string.persistence_shizuku_listener_not_connected)
              ShizukuRepairResult.NOTIFICATION_TEXT_UNREADABLE ->
                  getString(R.string.persistence_shizuku_notification_unreadable)
            }
          } catch (error: Throwable) {
            AppLogger.e("MainActivity", "Shizuku repair failed", error)
            getString(
                R.string.persistence_shizuku_failed,
                error.cause?.message ?: error.message ?: error.javaClass.simpleName,
            )
          }
      Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
    }
  }
}
