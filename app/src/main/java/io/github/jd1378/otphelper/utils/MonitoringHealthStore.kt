package io.github.jd1378.otphelper.utils

import android.content.Context

data class MonitoringHealthSnapshot(
    val listenerConnected: Boolean,
    val listenerChangedAt: Long,
    val accessibilityConnected: Boolean,
    val accessibilityChangedAt: Long,
)

object MonitoringHealthStore {
  private const val PREFS = "monitoring_health"
  private const val KEY_LISTENER_CONNECTED = "listener_connected"
  private const val KEY_LISTENER_CHANGED_AT = "listener_changed_at"
  private const val KEY_ACCESSIBILITY_CONNECTED = "accessibility_connected"
  private const val KEY_ACCESSIBILITY_CHANGED_AT = "accessibility_changed_at"

  /** A fresh process must not inherit a stale "connected" flag from a killed process. */
  fun markProcessStarted(context: Context) {
    preferences(context)
        .edit()
        .putBoolean(KEY_LISTENER_CONNECTED, false)
        .putBoolean(KEY_ACCESSIBILITY_CONNECTED, false)
        .apply()
  }

  fun markListenerConnected(context: Context, connected: Boolean) {
    preferences(context)
        .edit()
        .putBoolean(KEY_LISTENER_CONNECTED, connected)
        .putLong(KEY_LISTENER_CHANGED_AT, System.currentTimeMillis())
        .apply()
  }

  fun markAccessibilityConnected(context: Context, connected: Boolean) {
    preferences(context)
        .edit()
        .putBoolean(KEY_ACCESSIBILITY_CONNECTED, connected)
        .putLong(KEY_ACCESSIBILITY_CHANGED_AT, System.currentTimeMillis())
        .apply()
  }

  fun snapshot(context: Context): MonitoringHealthSnapshot {
    val preferences = preferences(context)
    return MonitoringHealthSnapshot(
        listenerConnected = preferences.getBoolean(KEY_LISTENER_CONNECTED, false),
        listenerChangedAt = preferences.getLong(KEY_LISTENER_CHANGED_AT, 0L),
        accessibilityConnected = preferences.getBoolean(KEY_ACCESSIBILITY_CONNECTED, false),
        accessibilityChangedAt = preferences.getLong(KEY_ACCESSIBILITY_CHANGED_AT, 0L),
    )
  }

  private fun preferences(context: Context) =
      context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
