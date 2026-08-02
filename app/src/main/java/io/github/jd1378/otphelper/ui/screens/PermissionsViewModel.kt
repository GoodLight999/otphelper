package io.github.jd1378.otphelper.ui.screens

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jd1378.otphelper.AccessibilityNotificationService
import io.github.jd1378.otphelper.ModeOfOperation
import io.github.jd1378.otphelper.MyWorkManager
import io.github.jd1378.otphelper.repository.UserSettingsRepository
import io.github.jd1378.otphelper.ui.navigation.MainDestinations
import io.github.jd1378.otphelper.utils.AutostartHelper
import io.github.jd1378.otphelper.utils.DiagnosticsReportManager
import io.github.jd1378.otphelper.utils.SettingsHelper
import io.github.jd1378.otphelper.utils.combine
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
data class PermissionsUiState(
    val hasNotifPerm: Boolean = false,
    val hasNotifListenerPerm: Boolean = false,
    val hasAccessibilityNotificationService: Boolean = false,
    val hasSmsListenerPerm: Boolean = false,
    val hasReadSmsPerm: Boolean = false,
    val isIgnoringBatteryOptimizations: Boolean = false,
    val hasAutostartSettings: Boolean = false,
    val hasRestrictedSettings: Boolean = false,
    val showSkipWarning: Boolean = false,
    val hasDoneAllSteps: Boolean = false,
    val modeOfOperation: ModeOfOperation = ModeOfOperation.UNRECOGNIZED,
)

@Stable
@HiltViewModel
class PermissionsViewModel
@Inject
constructor(
    private val savedStateHandle: SavedStateHandle,
    private val userSettingsRepository: UserSettingsRepository,
) : ViewModel() {

  private val _hasNotifPerm = MutableStateFlow(false)
  private val _hasNotifListenerPerm = MutableStateFlow(false)
  private val _hasAccessibilityNotificationService = MutableStateFlow(false)
  private val notificationReadStates =
      combine(_hasNotifListenerPerm, _hasAccessibilityNotificationService) { listener, accessibility ->
        listener to accessibility
      }
  private val _hasSmsListenerPerm = MutableStateFlow(false)
  private val _hasReadSmsPerm = MutableStateFlow(false)
  private val _isIgnoringBatteryOptimizations = MutableStateFlow(false)
  private val _hasAutoStartSettings = MutableStateFlow(false)
  private val _hasRestrictedSettings = MutableStateFlow(false)
  private val _showSkipWarning = MutableStateFlow(false)

  val uiState: StateFlow<PermissionsUiState> =
      combine(
              userSettingsRepository.userSettings,
              _hasNotifPerm,
              notificationReadStates,
              _hasSmsListenerPerm,
              _hasReadSmsPerm,
              _isIgnoringBatteryOptimizations,
              _hasAutoStartSettings,
              _hasRestrictedSettings,
              _showSkipWarning,
          ) {
              userSettings,
              hasNotifPerm,
              notificationReadStates,
              hasSmsListenerPerm,
              hasReadSmsPerm,
              isIgnoringBatteryOptimizations,
              hasAutostartSettings,
              hasRestrictedSettings,
              showSkipWarning ->
            val (hasNotifListenerPerm, hasAccessibilityNotificationService) =
                notificationReadStates
            val hasDoneAllSteps =
                when (userSettings.modeOfOperation) {
                  ModeOfOperation.Notification -> hasNotifPerm && hasNotifListenerPerm
                  ModeOfOperation.SMS -> hasReadSmsPerm && hasSmsListenerPerm
                  else -> false
                }
            PermissionsUiState(
                hasNotifPerm,
                hasNotifListenerPerm,
                hasAccessibilityNotificationService,
                hasSmsListenerPerm,
                hasReadSmsPerm,
                isIgnoringBatteryOptimizations,
                hasAutostartSettings,
                hasRestrictedSettings,
                showSkipWarning,
                hasDoneAllSteps && isIgnoringBatteryOptimizations,
                userSettings.modeOfOperation,
            )
          }
          .stateIn(
              scope = viewModelScope,
              started = SharingStarted.WhileSubscribed(5_000),
              initialValue = PermissionsUiState())

  fun updatePermissionsStatus(context: Context) {
    viewModelScope.launch {
      launch {
        _hasNotifPerm.update {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
          } else {
            true
          }
        }
      }
      launch {
        _hasNotifListenerPerm.update {
          NotificationManagerCompat.getEnabledListenerPackages(context)
              .contains(context.packageName)
        }
      }
      launch {
        _hasAccessibilityNotificationService.update {
          AccessibilityNotificationService.isEnabled(context)
        }
      }
      launch {
        _hasSmsListenerPerm.update {
          context.checkSelfPermission(android.Manifest.permission.RECEIVE_SMS) ==
              PackageManager.PERMISSION_GRANTED
        }
      }
      launch {
        _hasReadSmsPerm.update {
          context.checkSelfPermission(android.Manifest.permission.READ_SMS) ==
              PackageManager.PERMISSION_GRANTED
        }
      }
      launch {
        _isIgnoringBatteryOptimizations.update {
          val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
          powerManager.isIgnoringBatteryOptimizations(context.packageName)
        }
      }
      launch { _hasAutoStartSettings.update { AutostartHelper.hasAutostartSettings(context) } }

      launch {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          _hasRestrictedSettings.update { true }
        }
      }
    }
    MyWorkManager.rebindListeners(context, true)
  }

  fun onSetupFinish(onNavigateToRoute: (String, Boolean, Boolean) -> Unit) {
    _showSkipWarning.update { false }
    viewModelScope.launch {
      userSettingsRepository.setIsSetupFinished(true)
      onNavigateToRoute(MainDestinations.HOME_ROUTE, true, false)
    }
  }

  fun onSetupSkipPressed() {
    _showSkipWarning.update { true }
  }

  fun onOpenReadNotificationsPressed(context: Context) {
    SettingsHelper.openNotificationListenerSettings(context)
  }

  fun onOpenAccessibilityPressed(context: Context) {
    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
  }

  fun onOpenBatteryOptimizationsPressed(context: Context) {
    val intent = Intent().setAction(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    context.startActivity(intent)
  }

  suspend fun buildDiagnostics(context: Context): String =
      withContext(Dispatchers.IO) {
        DiagnosticsReportManager.build(context, userSettingsRepository.fetchSettings())
      }

  fun onOpenAutostartPressed(context: Context) {
    AutostartHelper.openAutostartSettings(context)
  }

  fun onOpenAppSettings(context: Context) {
    SettingsHelper.openApplicationSettings(context)
  }
}
