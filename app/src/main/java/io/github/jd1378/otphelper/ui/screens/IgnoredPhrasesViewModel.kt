package io.github.jd1378.otphelper.ui.screens

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jd1378.otphelper.di.AutoUpdatingListenerUtils
import io.github.jd1378.otphelper.repository.UserSettingsRepository
import io.github.jd1378.otphelper.utils.CodeExtractor
import io.github.jd1378.otphelper.utils.CodeExtractorDefaults
import io.github.jd1378.otphelper.utils.PhraseBackupManager
import io.github.jd1378.otphelper.utils.PhraseListKind
import javax.inject.Inject
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Stable
@HiltViewModel
class IgnoredPhrasesViewModel
@Inject
constructor(
    private val savedStateHandle: SavedStateHandle,
    private val userSettingsRepository: UserSettingsRepository,
    val autoUpdatingListenerUtils: AutoUpdatingListenerUtils,
) : ViewModel() {
  val showResetToDefaultDialog = MutableStateFlow(false)
  val showNewIgnoredPhraseDialog = MutableStateFlow(false)
  val showClearListDialog = MutableStateFlow(false)

  val ignoredPhrases =
      userSettingsRepository.userSettings
          .map { it.ignoredPhrasesList.toPersistentList() }
          .stateIn(
              scope = viewModelScope,
              started = SharingStarted.WhileSubscribed(5000),
              initialValue = persistentListOf<String>(),
          )

  fun resetToDefault() {
    showResetToDefaultDialog.value = false
    viewModelScope.launch {
      userSettingsRepository.setIgnoredPhrases(CodeExtractorDefaults.ignoredPhrases)
    }
  }

  fun clearList() {
    showClearListDialog.value = false
    viewModelScope.launch { userSettingsRepository.setIgnoredPhrases(listOf()) }
  }

  fun addNewPhrase(it: String) {
    showNewIgnoredPhraseDialog.value = false
    viewModelScope.launch {
      if (ignoredPhrases.value.indexOf(it) == -1) {
        val newList = ignoredPhrases.value.add(it)
        userSettingsRepository.setIgnoredPhrases(newList)
      }
    }
  }

  fun deletePhrase(index: Int) {
    viewModelScope.launch {
      val newList = ignoredPhrases.value.removeAt(index)
      userSettingsRepository.setIgnoredPhrases(newList)
    }
  }

  suspend fun exportCurrent(context: Context, uri: Uri) {
    val settings = userSettingsRepository.fetchSettings()
    PhraseBackupManager.writeText(
        context,
        uri,
        PhraseBackupManager.encodeSingle(PhraseListKind.IGNORED, settings.ignoredPhrasesList),
    )
  }

  suspend fun importCurrent(context: Context, uri: Uri): Int {
    val phrases =
        PhraseBackupManager.decodeSingle(
            PhraseBackupManager.readText(context, uri),
            PhraseListKind.IGNORED,
        )
    require(phrases.all(::isIgnoredPhraseParsable)) { "The backup contains an invalid regular expression" }
    userSettingsRepository.setIgnoredPhrases(phrases)
    return phrases.size
  }

  suspend fun exportAll(context: Context, uri: Uri) {
    PhraseBackupManager.writeText(
        context,
        uri,
        PhraseBackupManager.encodeAll(userSettingsRepository.fetchSettings()),
    )
  }

  suspend fun importAll(context: Context, uri: Uri): Int {
    val lists = PhraseBackupManager.decodeAll(PhraseBackupManager.readText(context, uri))
    require(lists.sensitive.all(::isSensitivePhraseParsable)) {
      "The sensitive list contains an invalid regular expression"
    }
    require(lists.ignored.all(::isIgnoredPhraseParsable)) {
      "The ignored list contains an invalid regular expression"
    }
    require(lists.cleanup.all(::isCleanupPhraseParsable)) {
      "The cleanup list contains an invalid regular expression"
    }
    userSettingsRepository.setSensitivePhrases(lists.sensitive)
    userSettingsRepository.setIgnoredPhrases(lists.ignored)
    userSettingsRepository.setCleanupPhrases(lists.cleanup)
    return lists.sensitive.size + lists.ignored.size + lists.cleanup.size
  }

  fun isIgnoredPhraseParsable(str: String): Boolean {
    if (str.isBlank()) return false
    return try {
      CodeExtractor(listOf("code"), listOf(str, "a_b_c_d_e")).shouldIgnore("a_b_c_d_e")
    } catch (e: Throwable) {
      false
    }
  }

  private fun isSensitivePhraseParsable(str: String): Boolean =
      try {
        str.isNotBlank() && CodeExtractor(listOf(str, "code")).getCode("Code: 123456") == "123456"
      } catch (e: Throwable) {
        false
      }

  private fun isCleanupPhraseParsable(str: String): Boolean =
      try {
        str.isNotBlank().also {
          if (it) CodeExtractor(listOf("code"), listOf("foo"), listOf(str, "a_b_c_d_e")).cleanup("bar")
        }
      } catch (e: Throwable) {
        false
      }
}
