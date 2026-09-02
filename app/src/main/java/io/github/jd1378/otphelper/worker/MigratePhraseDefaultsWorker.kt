package io.github.jd1378.otphelper.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.jd1378.otphelper.repository.UserSettingsRepository
import io.github.jd1378.otphelper.utils.AppLogger
import io.github.jd1378.otphelper.utils.PhraseDefaultsMigrator

const val migratePhraseDefaultsWorkName = "phrase_defaults_migrate_work"

@HiltWorker
class MigratePhraseDefaultsWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val userSettingsRepository: UserSettingsRepository,
) : CoroutineWorker(context, workerParams) {

  companion object {
    const val TAG: String = "MigratePhraseDefaultsWorker"
  }

  override suspend fun doWork(): Result {
    val before = userSettingsRepository.fetchSettings()
    if (before.phraseDefaultsVersion >= PhraseDefaultsMigrator.CURRENT_VERSION) {
      return Result.success()
    }

    AppLogger.i(
        TAG,
        "doWork: migrating phrase defaults ${before.phraseDefaultsVersion} -> ${PhraseDefaultsMigrator.CURRENT_VERSION}",
    )
    userSettingsRepository.migratePhraseDefaultsIfNeeded()
    return Result.success()
  }
}
