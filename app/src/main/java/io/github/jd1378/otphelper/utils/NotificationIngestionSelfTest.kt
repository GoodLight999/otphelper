package io.github.jd1378.otphelper.utils

import android.content.Context
import android.os.SystemClock
import io.github.jd1378.otphelper.BuildConfig
import java.security.SecureRandom

/**
 * Verifies actual cross-package notification-body delivery in instrumentation tests.
 *
 * A separate CI-only fixture APK posts the probe. An OTP Helper self-notification is rejected
 * because same-package delivery would not prove that real third-party OTPs are readable. No
 * production UI or elevated integration invokes this helper.
 */
object NotificationIngestionSelfTest {
  const val FIXTURE_PACKAGE = "io.github.jd1378.otphelper.fixture"
  private const val FIXTURE_RECEIVER = "$FIXTURE_PACKAGE/.ProbeReceiver"
  private const val FIXTURE_ACTION = "$FIXTURE_PACKAGE.POST_OTP"
  private const val PREFS = "notification_ingestion_self_test"
  private const val KEY_TOKEN = "token"
  private const val KEY_TAG = "tag"
  private const val KEY_STATE = "state"
  private const val KEY_STARTED_AT = "started_at"
  private const val TIMEOUT_MS = 15_000L
  private val random = SecureRandom()

  enum class State {
    IDLE,
    PENDING,
    PASSED,
    FAILED,
    TIMED_OUT,
  }

  data class Probe(val token: String, val tag: String)

  data class Snapshot(val state: State, val startedAt: Long)

  fun prepareExternalProbe(context: Context): Probe {
    val token = (100_000 + random.nextInt(900_000)).toString()
    val tag = "otphelper_probe_${System.currentTimeMillis()}_${random.nextInt(10_000)}"
    preferences(context)
        .edit()
        .putString(KEY_TOKEN, token)
        .putString(KEY_TAG, tag)
        .putString(KEY_STATE, State.PENDING.name)
        .putLong(KEY_STARTED_AT, System.currentTimeMillis())
        .apply()
    AppLogger.i(TAG, "external fixture notification-body probe prepared")
    return Probe(token = token, tag = tag)
  }

  fun buildFixtureBroadcastCommand(probe: Probe): String =
      "am broadcast -a $FIXTURE_ACTION -n $FIXTURE_RECEIVER " +
          "--es token ${shellQuote(probe.token)} --es tag ${shellQuote(probe.tag)}"

  fun handlePostedNotification(
      context: Context,
      packageName: String,
      notificationTag: String?,
      text: String,
  ): Boolean {
    val prefs = preferences(context)
    if (prefs.getString(KEY_STATE, State.IDLE.name) != State.PENDING.name) return false
    val expectedTag = prefs.getString(KEY_TAG, null) ?: return false
    if (notificationTag != expectedTag) return false

    if (packageName != FIXTURE_PACKAGE || packageName == BuildConfig.APPLICATION_ID) {
      prefs.edit().putString(KEY_STATE, State.FAILED.name).apply()
      AppLogger.w(TAG, "rejected notification probe from unexpected package=$packageName")
      return true
    }

    val token = prefs.getString(KEY_TOKEN, null)
    val passed = !token.isNullOrEmpty() && text.contains(token)
    prefs.edit().putString(KEY_STATE, if (passed) State.PASSED.name else State.FAILED.name).apply()
    AppLogger.i(
        TAG,
        "fixture notification-body probe completed: passed=$passed, sourcePackage=$packageName",
    )
    return true
  }

  fun snapshot(context: Context): Snapshot {
    val prefs = preferences(context)
    val raw = prefs.getString(KEY_STATE, State.IDLE.name)
    var state = runCatching { State.valueOf(raw ?: State.IDLE.name) }.getOrDefault(State.IDLE)
    val startedAt = prefs.getLong(KEY_STARTED_AT, 0L)
    if (state == State.PENDING && System.currentTimeMillis() - startedAt > TIMEOUT_MS) {
      state = State.TIMED_OUT
      prefs.edit().putString(KEY_STATE, state.name).apply()
    }
    return Snapshot(state, startedAt)
  }

  fun awaitResult(context: Context, timeoutMs: Long = TIMEOUT_MS): State {
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    while (SystemClock.elapsedRealtime() < deadline) {
      val state = snapshot(context).state
      if (state != State.PENDING) return state
      Thread.sleep(100L)
    }
    preferences(context).edit().putString(KEY_STATE, State.TIMED_OUT.name).apply()
    return State.TIMED_OUT
  }

  private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

  private fun preferences(context: Context) =
      context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  private const val TAG = "NotificationSelfTest"
}
