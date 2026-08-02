package io.github.jd1378.otphelper.utils

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.jd1378.otphelper.BuildConfig
import io.github.jd1378.otphelper.R
import java.security.SecureRandom

object NotificationIngestionSelfTest {
  const val NOTIFICATION_ID = 0x6f7474
  private const val CHANNEL_ID = "otphelper_ingestion_self_test"
  private const val PREFS = "notification_ingestion_self_test"
  private const val KEY_TOKEN = "token"
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

  data class Snapshot(val state: State, val startedAt: Long)

  @SuppressLint("MissingPermission")
  fun start(context: Context): String {
    val appContext = context.applicationContext
    createChannel(appContext)
    val token = (100_000 + random.nextInt(900_000)).toString()
    preferences(appContext)
        .edit()
        .putString(KEY_TOKEN, token)
        .putString(KEY_STATE, State.PENDING.name)
        .putLong(KEY_STARTED_AT, System.currentTimeMillis())
        .apply()

    val notification =
        NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("OTP Helper notification read test")
            .setContentText("One-time verification code: $token")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("OTP Helper notification ingestion self-test. Verification code: $token"))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, notification)
    AppLogger.i(TAG, "notification ingestion self-test posted")
    return token
  }

  fun handlePostedNotification(
      context: Context,
      packageName: String,
      notificationId: Int,
      text: String,
  ): Boolean {
    if (packageName != BuildConfig.APPLICATION_ID || notificationId != NOTIFICATION_ID) return false
    val prefs = preferences(context)
    val token = prefs.getString(KEY_TOKEN, null)
    val passed = !token.isNullOrEmpty() && text.contains(token)
    prefs.edit().putString(KEY_STATE, if (passed) State.PASSED.name else State.FAILED.name).apply()
    NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    AppLogger.i(TAG, "notification ingestion self-test completed: passed=$passed")
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

  fun runBlocking(context: Context, timeoutMs: Long = TIMEOUT_MS): State {
    start(context)
    val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
    while (android.os.SystemClock.elapsedRealtime() < deadline) {
      val state = snapshot(context).state
      if (state != State.PENDING) return state
      Thread.sleep(100L)
    }
    preferences(context).edit().putString(KEY_STATE, State.TIMED_OUT.name).apply()
    NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    return State.TIMED_OUT
  }

  private fun createChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
        NotificationChannel(
                CHANNEL_ID,
                "OTP Helper notification read test",
                NotificationManager.IMPORTANCE_LOW,
            )
            .apply { setShowBadge(false) })
  }

  private fun preferences(context: Context) =
      context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  private const val TAG = "NotificationSelfTest"
}
