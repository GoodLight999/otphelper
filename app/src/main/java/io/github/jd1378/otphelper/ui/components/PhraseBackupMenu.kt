package io.github.jd1378.otphelper.ui.components

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.jd1378.otphelper.R
import kotlinx.coroutines.launch

@Composable
fun PhraseBackupMenuItems(
    filePrefix: String,
    dismissMenu: () -> Unit,
    exportCurrent: suspend (Context, Uri) -> Unit,
    importCurrent: suspend (Context, Uri) -> Int,
    exportAll: suspend (Context, Uri) -> Unit,
    importAll: suspend (Context, Uri) -> Int,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  fun runOperation(operation: suspend () -> String) {
    dismissMenu()
    scope.launch {
      val message =
          try {
            operation()
          } catch (error: Throwable) {
            context.getString(R.string.phrase_backup_failed, error.message ?: error.javaClass.simpleName)
          }
      Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
  }

  val exportCurrentLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runOperation {
          exportCurrent(context, uri)
          context.getString(R.string.phrase_export_success)
        }
      }
  val importCurrentLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runOperation {
          val count = importCurrent(context, uri)
          context.resources.getQuantityString(R.plurals.phrase_import_success, count, count)
        }
      }
  val exportAllLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runOperation {
          exportAll(context, uri)
          context.getString(R.string.phrase_export_all_success)
        }
      }
  val importAllLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runOperation {
          val count = importAll(context, uri)
          context.resources.getQuantityString(R.plurals.phrase_import_all_success, count, count)
        }
      }

  DropdownMenuItem(
      text = { Text(stringResource(R.string.export_this_list)) },
      onClick = { exportCurrentLauncher.launch("otphelper-$filePrefix.json") },
  )
  DropdownMenuItem(
      text = { Text(stringResource(R.string.import_this_list)) },
      onClick = { importCurrentLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
  )
  DropdownMenuItem(
      text = { Text(stringResource(R.string.export_all_phrase_lists)) },
      onClick = { exportAllLauncher.launch("otphelper-all-phrases.json") },
  )
  DropdownMenuItem(
      text = { Text(stringResource(R.string.import_all_phrase_lists)) },
      onClick = { importAllLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
  )
}
