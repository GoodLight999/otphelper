package io.github.jd1378.otphelper.di

import androidx.compose.runtime.Stable
import io.github.jd1378.otphelper.ModeOfOperation
import io.github.jd1378.otphelper.repository.UserSettingsRepository
import io.github.jd1378.otphelper.utils.AppLogger
import io.github.jd1378.otphelper.utils.CodeExtractor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private data class ListenerSettingsSnapshot(
    val codeExtractor: CodeExtractor? = null,
    val isAutoDismissEnabled: Boolean = false,
    val isAutoMarkAsReadEnabled: Boolean = false,
    val modeOfOperation: ModeOfOperation = ModeOfOperation.UNRECOGNIZED,
)

@Singleton
@Stable
class AutoUpdatingListenerUtils
@Inject
constructor(private val userSettingsRepository: UserSettingsRepository) {
  companion object {
    const val TAG = "AutoUpdatingCodeExtractor"
    private const val INITIAL_SETTINGS_TIMEOUT_SECONDS = 5L
  }

  private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
    AppLogger.e(TAG, exception.message ?: exception.toString(), exception)
  }

  private val scope = CoroutineScope(Dispatchers.IO + exceptionHandler)
  private val latch = CountDownLatch(1)
  private val snapshot = AtomicReference(ListenerSettingsSnapshot())

  val codeExtractor: CodeExtractor?
    get() = snapshot.get().codeExtractor

  val isAutoDismissEnabled: Boolean
    get() = snapshot.get().isAutoDismissEnabled

  val isAutoMarkAsReadEnabled: Boolean
    get() = snapshot.get().isAutoMarkAsReadEnabled

  val modeOfOperation: ModeOfOperation
    get() = snapshot.get().modeOfOperation

  init {
    scope.launch {
      userSettingsRepository.userSettings.collect { settings ->
        val updated =
            ListenerSettingsSnapshot(
                codeExtractor =
                    CodeExtractor(
                        settings.sensitivePhrasesList,
                        settings.ignoredPhrasesList,
                        settings.cleanupPhrasesList,
                    ),
                isAutoDismissEnabled = settings.isAutoDismissEnabled,
                isAutoMarkAsReadEnabled = settings.isAutoMarkAsReadEnabled,
                modeOfOperation = settings.modeOfOperation,
            )
        snapshot.set(updated)
        latch.countDown()
        AppLogger.i(
            TAG,
            "settings updated: mode=${updated.modeOfOperation}, " +
                "autoDismiss=${updated.isAutoDismissEnabled}, " +
                "autoMarkAsRead=${updated.isAutoMarkAsReadEnabled}, " +
                "sensitivePhrases=${settings.sensitivePhrasesList.size}, " +
                "ignoredPhrases=${settings.ignoredPhrasesList.size}, " +
                "cleanupPhrases=${settings.cleanupPhrasesList.size}",
        )
      }
    }
  }

  fun awaitCodeExtractor(): Boolean {
    val ready = latch.await(INITIAL_SETTINGS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    if (!ready) {
      AppLogger.w(TAG, "initial listener settings did not load within the timeout")
    }
    return ready
  }
}
