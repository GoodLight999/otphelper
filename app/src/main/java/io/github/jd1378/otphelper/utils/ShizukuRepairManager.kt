package io.github.jd1378.otphelper.utils

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import io.github.jd1378.otphelper.NotificationListener
import io.github.jd1378.otphelper.PersistenceService
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
      Handler(Looper.getMainLooper()).post { Shizuku.requestPermission(REQUEST_CODE) }
      return ShizukuRepairResult.PERMISSION_REQUESTED
    }

    val packageName = context.packageName
    val listener = ComponentName(context, NotificationListener::class.java).flattenToString()
    val commands =
        listOf(
            "cmd notification allow_listener ${shellQuote(listener)}",
            "cmd deviceidle whitelist +${shellQuote(packageName)}",
            "cmd appops set --user current ${shellQuote(packageName)} RUN_IN_BACKGROUND allow",
            "cmd appops set --user current ${shellQuote(packageName)} RUN_ANY_IN_BACKGROUND allow",
        )
    commands.forEach(::runCommand)
    PersistenceService.requestListenerRebind(context)
    return ShizukuRepairResult.SUCCESS
  }

  /**
   * Shizuku 13.1.5 keeps the legacy process bridge at runtime but hides it from Kotlin callers.
   * Keep the reflection in one optional class so normal operation is unaffected and this can be
   * replaced by a dedicated UserService without touching the persistence architecture.
   */
  private fun runCommand(command: String) {
    val method =
        Shizuku::class.java
            .getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            )
            .apply { isAccessible = true }
    val process =
        method.invoke(null, arrayOf("sh", "-c", command), null, null) as? Process
            ?: throw IllegalStateException("Shizuku process bridge returned no process")
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
