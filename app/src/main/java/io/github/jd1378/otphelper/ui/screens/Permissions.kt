package io.github.jd1378.otphelper.ui.screens

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jd1378.otphelper.ModeOfOperation
import io.github.jd1378.otphelper.R
import io.github.jd1378.otphelper.ui.components.CodeBlock
import io.github.jd1378.otphelper.ui.components.SkipDialog
import io.github.jd1378.otphelper.ui.components.TitleBar
import io.github.jd1378.otphelper.ui.components.TodoItem
import io.github.jd1378.otphelper.ui.components.verticalColumnScrollbar
import io.github.jd1378.otphelper.utils.Clipboard
import kotlinx.coroutines.launch

@Composable
fun Permissions(
    onNavigateToRoute: (String, Boolean, Boolean) -> Unit,
    upPress: () -> Unit,
    viewModel: PermissionsViewModel,
    setupMode: Boolean = false
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val lifecycleOwner = LocalLifecycleOwner.current
  val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var diagnosticsBusy by remember { mutableStateOf(false) }
  val permLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.updatePermissionsStatus(context)
      }
  val diagnosticsExportLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
          diagnosticsBusy = true
          val message =
              runCatching {
                    val report = viewModel.buildDiagnostics(context)
                    context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use {
                      it.write(report)
                    } ?: error("Unable to open diagnostic report destination")
                    context.getString(R.string.diagnostics_exported)
                  }
                  .getOrElse {
                    context.getString(
                        R.string.diagnostics_failed,
                        it.message ?: it.javaClass.simpleName,
                    )
                  }
          diagnosticsBusy = false
          Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
      }

  LaunchedEffect(lifecycleState) {
    when (lifecycleState) {
      Lifecycle.State.STARTED,
      Lifecycle.State.RESUMED -> viewModel.updatePermissionsStatus(context)
      else -> {}
    }
  }

  Scaffold(
      topBar = {
        TitleBar(
            upPress = upPress,
            text = LocalContext.current.getString(R.string.PERMISSION_ROUTE),
        )
      },
      bottomBar = {
        if (setupMode) {
          SkipDialog(show = uiState.showSkipWarning) { viewModel.onSetupFinish(onNavigateToRoute) }
          Row(Modifier.navigationBarsPadding().padding(10.dp)) {
            Spacer(modifier = Modifier.weight(1f))
            if (uiState.hasDoneAllSteps) {
              Button(onClick = { viewModel.onSetupFinish(onNavigateToRoute) }) {
                Text(text = stringResource(R.string.finish))
              }
            } else {
              OutlinedButton(onClick = { viewModel.onSetupSkipPressed() }) {
                Text(text = stringResource(R.string.skip))
              }
            }
          }
        }
      },
  ) { padding ->
    val scrollState = rememberScrollState()
    Column(
        Modifier.padding(padding)
            .verticalColumnScrollbar(scrollState)
            .verticalScroll(scrollState)
            .padding(top = 10.dp)
            .padding(horizontal = dimensionResource(R.dimen.padding_page)),
        verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.padding_page)),
    ) {
      Text(
          stringResource(R.string.permissions_desc),
          modifier = Modifier.fillMaxWidth(),
          fontSize = 15.sp)

      val permissionGranted = stringResource(R.string.permission_granted_to)
      val permissionNotGranted = stringResource(R.string.permission_not_granted_to)
      TodoItem(
          stringResource(R.string.permission_todo_post_notifications),
          actionText = stringResource(R.string.request),
          buttonEnabled = false,
          checked = uiState.hasNotifPerm,
          checkboxSemantics = {
            stateDescription = if (uiState.hasNotifPerm) permissionGranted else permissionNotGranted
          },
      ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
      }

      if (uiState.modeOfOperation == ModeOfOperation.SMS) {
        TodoItem(
            stringResource(R.string.permission_todo_receive_sms),
            actionText = stringResource(R.string.request),
            buttonEnabled = !uiState.hasSmsListenerPerm,
            checked = uiState.hasSmsListenerPerm,
            checkboxSemantics = {
              stateDescription =
                  if (uiState.hasSmsListenerPerm) permissionGranted else permissionNotGranted
            },
        ) {
          permLauncher.launch(Manifest.permission.RECEIVE_SMS)
        }

        TodoItem(
            stringResource(R.string.permission_todo_read_sms),
            actionText = stringResource(R.string.request),
            buttonEnabled = !uiState.hasReadSmsPerm,
            checked = uiState.hasReadSmsPerm,
            checkboxSemantics = {
              stateDescription =
                  if (uiState.hasReadSmsPerm) permissionGranted else permissionNotGranted
            },
        ) {
          permLauncher.launch(Manifest.permission.READ_SMS)
        }
        Text(
            stringResource(R.string.read_notifs_sms_mode_desc),
            modifier = Modifier.fillMaxWidth(),
            fontSize = 15.sp)
      }
      TodoItem(
          stringResource(R.string.permission_todo_read_notifications),
          checked = uiState.hasNotifListenerPerm,
          checkboxSemantics = {
            stateDescription =
                if (uiState.hasNotifListenerPerm) permissionGranted else permissionNotGranted
          },
      ) {
        viewModel.onOpenReadNotificationsPressed(context)
      }

      TodoItem(
          stringResource(R.string.permission_todo_remain_open),
          checked = uiState.isIgnoringBatteryOptimizations,
          checkboxSemantics = {
            stateDescription =
                if (uiState.isIgnoringBatteryOptimizations) permissionGranted
                else permissionNotGranted
          },
      ) {
        viewModel.onOpenBatteryOptimizationsPressed(context)
      }

      Text(
          stringResource(R.string.permission_resilience_desc),
          modifier = Modifier.fillMaxWidth(),
          fontSize = 15.sp,
      )
      Text(
          stringResource(R.string.permission_honor_recents_lock_desc),
          modifier = Modifier.fillMaxWidth(),
          fontSize = 15.sp,
      )

      if (uiState.modeOfOperation == ModeOfOperation.Notification) {
        Text(
            stringResource(R.string.permission_accessibility_notification_desc),
            modifier = Modifier.fillMaxWidth(),
            fontSize = 15.sp,
        )
        TodoItem(
            text = stringResource(R.string.permission_todo_accessibility_notifications),
            actionText = stringResource(R.string.open_settings),
            intermediate = !uiState.hasAccessibilityNotificationService,
            checked = uiState.hasAccessibilityNotificationService,
            checkboxSemantics = {
              stateDescription =
                  if (uiState.hasAccessibilityNotificationService) permissionGranted
                  else permissionNotGranted
            },
        ) {
          viewModel.onOpenAccessibilityPressed(context)
        }
      }

      if (uiState.modeOfOperation == ModeOfOperation.Notification &&
          Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        Text(
            stringResource(R.string.permission_shizuku_notification_desc),
            modifier = Modifier.fillMaxWidth(),
            fontSize = 15.sp,
        )
        val shizukuManager =
            stringResource(
                if (uiState.shizukuManagerInstalled) R.string.permission_shizuku_installed
                else R.string.permission_shizuku_not_installed)
        val shizukuBinder =
            stringResource(
                if (uiState.shizukuBinderAlive) R.string.permission_shizuku_connected
                else R.string.permission_shizuku_not_connected)
        Text(
            stringResource(
                R.string.permission_shizuku_status,
                shizukuManager,
                shizukuBinder,
                uiState.shizukuPermission,
            ),
            modifier = Modifier.fillMaxWidth(),
            fontSize = 15.sp,
        )
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = { viewModel.onRunShizukuRepair(context) },
        ) {
          Text(stringResource(R.string.permission_run_shizuku_repair))
        }

        Text(
            stringResource(R.string.read_notifs_android_15_desc),
            modifier = Modifier.fillMaxWidth(),
            fontSize = 15.sp)
        CodeBlock(stringResource(R.string.adb_command_sensitive_notifs))
        CodeBlock(stringResource(R.string.adb_command_kill_app))
      }

      Text(
          stringResource(R.string.diagnostics_desc),
          modifier = Modifier.fillMaxWidth(),
          fontSize = 15.sp,
      )
      OutlinedButton(
          modifier = Modifier.fillMaxWidth(),
          enabled = !diagnosticsBusy,
          onClick = {
            scope.launch {
              diagnosticsBusy = true
              val message =
                  runCatching {
                        val report = viewModel.buildDiagnostics(context)
                        Clipboard.copyToClipboard(context, report, false)
                        context.getString(R.string.diagnostics_copied)
                      }
                      .getOrElse {
                        context.getString(
                            R.string.diagnostics_failed,
                            it.message ?: it.javaClass.simpleName,
                        )
                      }
              diagnosticsBusy = false
              Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
          },
      ) {
        Text(stringResource(R.string.copy_diagnostics))
      }
      OutlinedButton(
          modifier = Modifier.fillMaxWidth(),
          enabled = !diagnosticsBusy,
          onClick = { diagnosticsExportLauncher.launch("otphelper-diagnostics.txt") },
      ) {
        Text(stringResource(R.string.export_diagnostics))
      }

      if (uiState.hasAutostartSettings) {
        Text(
            stringResource(R.string.perm_extra_desc),
            modifier = Modifier.fillMaxWidth(),
            fontSize = 15.sp)

        TodoItem(
            stringResource(R.string.permission_todo_autostart),
            intermediate = true,
            checked = true) {
              viewModel.onOpenAutostartPressed(context)
            }
      }

      if (uiState.hasRestrictedSettings) {
        Text(
            stringResource(R.string.perm_restricted_desc),
            modifier = Modifier.fillMaxWidth(),
            fontSize = 15.sp)

        TodoItem(
            stringResource(R.string.permission_todo_allow_restricted_settings),
            intermediate = true,
            checked = true,
        ) {
          viewModel.onOpenAppSettings(context)
        }
      }
    }
  }
}
