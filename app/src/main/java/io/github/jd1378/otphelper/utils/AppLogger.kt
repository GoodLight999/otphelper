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
  private val keyedSecretPattern =
      Regex(
          "(?i)\\b(code|otp|pin)\\b(\\s*[:=]\\s*|\\s+)([A-Za-z0-9-]*\\d[A-Za-z0-9-]{3,31})")
  private val standaloneNumberPattern = Regex("(?<![A-Za-z0-9])\\d{4,}(?![A-Za-z0-9])")

  private val executor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "otphelper-file-logger").apply { isDaemon = true }
  }
  @Volatile private var appContext: Context? = null

  fun initialize(context: Context) {
    appContext = context.applicationContext
  }

  fun d(scope: String, message: String) = log("D", scope, message, null)

  fun i(scope: String, message: String) = log("I", scope, message, null)

  fun w(scope: String, message: String) = log("W", scope, message, null)

  fun e(scope: String, message: String, throwable: Throwable? = null) =
      log("E", scope, message, throwable)

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

  private fun log(level: String, scope: String, message: String, throwable: Throwable?) {
    val safeMessage = redact(message)
    val safeStack = throwable?.let(::stackTrace)?.let(::redact)
    val logcatMessage =
        if (safeStack.isNullOrBlank()) "[$scope] $safeMessage"
        else "[$scope] $safeMessage\n$safeStack"
    when (level) {
      "D" -> Log.d(TAG, logcatMessage)
      "I" -> Log.i(TAG, logcatMessage)
      "W" -> Log.w(TAG, logcatMessage)
      else -> Log.e(TAG, logcatMessage)
    }
    persist(level, scope, safeMessage, safeStack)
  }

  private fun persist(level: String, scope: String, safeMessage: String, safeStack: String?) {
    val context = appContext ?: return
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

  internal fun redact(value: String): String {
    val keyedRedacted =
        keyedSecretPattern.replace(value) { match ->
          "${match.groupValues[1]}${match.groupValues[2]}<redacted>"
        }
    return standaloneNumberPattern.replace(keyedRedacted, "<redacted-number>")
  }

  private fun stackTrace(throwable: Throwable): String {
    val writer = StringWriter()
    throwable.printStackTrace(PrintWriter(writer))
    return writer.toString()
  }
}
