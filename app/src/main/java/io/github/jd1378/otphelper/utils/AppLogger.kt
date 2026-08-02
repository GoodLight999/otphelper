package io.github.jd1378.otphelper.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Central logger with a small, redacted on-device log for diagnostics export. */
object AppLogger {
  const val TAG = "OtpHelper"
  private const val LOG_DIRECTORY = "diagnostics"
  private const val LOG_FILE = "otphelper.log"
  private const val ROTATED_LOG_FILE = "otphelper.log.1"
  private const val MAX_LOG_BYTES = 512 * 1024L
  private const val MAX_EXPORTED_CHARS = 120_000

  private val executor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "otphelper-file-logger").apply { isDaemon = true }
  }
  @Volatile private var appContext: Context? = null

  fun initialize(context: Context) {
    appContext = context.applicationContext
  }

  fun d(scope: String, message: String) {
    Log.d(TAG, "[$scope] $message")
    persist("D", scope, message, null)
  }

  fun i(scope: String, message: String) {
    Log.i(TAG, "[$scope] $message")
    persist("I", scope, message, null)
  }

  fun w(scope: String, message: String) {
    Log.w(TAG, "[$scope] $message")
    persist("W", scope, message, null)
  }

  fun e(scope: String, message: String, throwable: Throwable? = null) {
    if (throwable != null) {
      Log.e(TAG, "[$scope] $message", throwable)
    } else {
      Log.e(TAG, "[$scope] $message")
    }
    persist("E", scope, message, throwable)
  }

  fun readRecent(context: Context): String {
    val directory = File(context.filesDir, LOG_DIRECTORY)
    val rotated = File(directory, ROTATED_LOG_FILE)
    val current = File(directory, LOG_FILE)
    return buildString {
          if (rotated.isFile) append(runCatching { rotated.readText() }.getOrDefault(""))
          if (current.isFile) append(runCatching { current.readText() }.getOrDefault(""))
        }
        .takeLast(MAX_EXPORTED_CHARS)
  }

  private fun persist(level: String, scope: String, message: String, throwable: Throwable?) {
    val context = appContext ?: return
    val safeMessage = redact(message)
    val safeStack = throwable?.let(::stackTrace)?.let(::redact)
    executor.execute {
      runCatching {
        val directory = File(context.filesDir, LOG_DIRECTORY).apply { mkdirs() }
        val current = File(directory, LOG_FILE)
        if (current.length() >= MAX_LOG_BYTES) {
          val rotated = File(directory, ROTATED_LOG_FILE)
          if (rotated.exists()) rotated.delete()
          current.renameTo(rotated)
        }
        val timestamp =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())
        current.appendText(
            buildString {
              append(timestamp).append(' ').append(level).append(" [").append(scope).append("] ")
              append(safeMessage).append('\n')
              if (!safeStack.isNullOrBlank()) append(safeStack).append('\n')
            }
        )
      }
    }
  }

  internal fun redact(value: String): String =
      value
          .replace(Regex("(?<![A-Za-z0-9])\\d{4,10}(?![A-Za-z0-9])"), "<redacted-number>")
          .replace(Regex("(?i)(code|otp|pin)(\\s*[:=]?\\s*)[A-Za-z0-9-]{4,12}"), "$1$2<redacted>")

  private fun stackTrace(throwable: Throwable): String {
    val writer = StringWriter()
    throwable.printStackTrace(PrintWriter(writer))
    return writer.toString()
  }
}
