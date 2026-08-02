package io.github.jd1378.otphelper.utils

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import io.github.jd1378.otphelper.NotificationListener
import java.io.BufferedReader
import java.io.InputStreamReader
import rikka.shizuku.Shizuku

enum class ShizukuRepairResult {
  SUCCESS,
  UNAVAILABLE,
  PERMISSION_REQUESTED,
}

object ShizukuRepairManager {
  private const val REQUEST_CODE = 0x4f54

  fun repair(context: Context): ShizukuRepairResult {
    if (!Shizuku.pingBinder()) return ShizukuRepairResult.UNAVAILABLE
    if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
      Shizuku.requestPermission(REQUEST_CODE)
      return ShizukuRepairResult.PERMISSION_REQUESTED
    }

    val packageName = context.packageName
    val listener = ComponentName(context, NotificationListener::class.java).flattenToString()
    val commands =
        listOf(
            "cmd notification allow_listener ${shellQuote(listener)}",
            "cmd deviceidle whitelist +${shellQuote(packageName)}",
            "cmd appops set ${shellQuote(packageName)} RUN_IN_BACKGROUND allow",
            "cmd appops set ${shellQuote(packageName)} RUN_ANY_IN_BACKGROUND allow",
        )
    commands.forEach(::runCommand)
    NotificationListener.requestRebind(ComponentName(context, NotificationListener::class.java))
    return ShizukuRepairResult.SUCCESS
  }

  @Suppress("DEPRECATION")
  private fun runCommand(command: String) {
    val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
    val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
    val error = BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }
    val exitCode = process.waitFor()
    process.destroy()
    if (exitCode != 0) {
      throw IllegalStateException(
          "Command failed ($exitCode): ${error.ifBlank { output }.trim().take(300)}")
    }
  }

  private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"
}
