package io.github.jd1378.otphelper

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.view.accessibility.AccessibilityEvent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.AndroidEntryPoint
import io.github.jd1378.otphelper.di.AutoUpdatingListenerUtils
import io.github.jd1378.otphelper.di.RecentDetectedCodesHolder
import io.github.jd1378.otphelper.utils.AppLogger
import io.github.jd1378.otphelper.worker.CodeDetectedWorker
import javax.inject.Inject

/**
 * Optional, user-enabled fallback for OEMs that repeatedly detach NotificationListenerService.
 * It listens only to notification-state events and never inspects windows or performs gestures.
 */
@AndroidEntryPoint
class AccessibilityNotificationService : AccessibilityService() {
  @Inject lateinit var autoUpdatingListenerUtils: AutoUpdatingListenerUtils
  @Inject lateinit var recentDetectedCodesHolder: RecentDetectedCodesHolder

  override fun onServiceConnected() {
    super.onServiceConnected()
    AppLogger.i(TAG, "accessibility notification fallback connected")
    PersistenceService.start(applicationContext)
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    if (event?.eventType != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) return
    if (autoUpdatingListenerUtils.modeOfOperation != ModeOfOperation.Notification) return

    val packageName = event.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return
    if (packageName == packageNameForSelf()) return

    val notification = event.parcelableData as? Notification
    if (notification != null) {
      val isForegroundService =
          (notification.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0
      val isOngoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
      if (isForegroundService || isOngoing) return
    }

    autoUpdatingListenerUtils.awaitCodeExtractor()
    val extractor = autoUpdatingListenerUtils.codeExtractor ?: return
    val text = collectText(event, notification)
    if (text.isBlank() || extractor.shouldIgnore(text)) return

    val cleaned = extractor.cleanup(text)
    val code = extractor.getCode(cleaned, false)?.takeIf { it.isNotBlank() } ?: return
    val signature = "$packageName|$code|$cleaned"
    if (recentDetectedCodesHolder.isDuplicate(signature, System.currentTimeMillis())) return

    val stableId = signature.hashCode().toUInt().toString()
    val data =
        workDataOf(
            "packageName" to packageName,
            "notificationId" to stableId,
            "notificationTag" to "accessibility-fallback",
            "text" to cleaned,
            "code" to code,
        )
    WorkManager.getInstance(applicationContext)
        .enqueue(OneTimeWorkRequestBuilder<CodeDetectedWorker>().setInputData(data).build())
    AppLogger.i(TAG, "code detected through accessibility fallback, pkg=$packageName")
  }

  override fun onInterrupt() {
    AppLogger.w(TAG, "accessibility notification fallback interrupted")
  }

  override fun onDestroy() {
    AppLogger.w(TAG, "accessibility notification fallback destroyed")
    PersistenceService.scheduleRestart(applicationContext)
    super.onDestroy()
  }

  private fun collectText(event: AccessibilityEvent, notification: Notification?): String {
    val result = StringBuilder()
    event.text.forEach { value ->
      if (!value.isNullOrBlank()) result.append(value).append('\n')
    }
    val extras = notification?.extras
    if (extras != null) {
      for (key in NotificationListener.notification_text_keys) {
        extras.getCharSequence(key)?.takeIf { it.isNotBlank() }?.let { result.append(it).append('\n') }
      }
      for (key in NotificationListener.notification_text_arrays_keys) {
        extras.getCharSequenceArray(key)?.forEach { value ->
          if (!value.isNullOrBlank()) result.append(value).append('\n')
        }
      }
    }
    return result.toString()
  }

  private fun packageNameForSelf(): String = applicationContext.packageName

  companion object {
    private const val TAG = "AccessibilityNotif"
  }
}
