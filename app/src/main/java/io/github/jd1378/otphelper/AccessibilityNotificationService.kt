package io.github.jd1378.otphelper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.AndroidEntryPoint
import io.github.jd1378.otphelper.di.AutoUpdatingListenerUtils
import io.github.jd1378.otphelper.di.RecentDetectedCodesHolder
import io.github.jd1378.otphelper.utils.AppLogger
import io.github.jd1378.otphelper.utils.MonitoringHealthStore
import io.github.jd1378.otphelper.utils.NotificationCodeSelector
import io.github.jd1378.otphelper.worker.CodeDetectedWorker
import javax.inject.Inject

/**
 * Optional notification-ingestion path based on the public Accessibility API.
 *
 * Android's API contract for [AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED] exposes the
 * posted [Notification] through [AccessibilityEvent.getParcelableData] and may also expose text
 * through [AccessibilityEvent.getText]. The AOSP Android 16 implementation sends the full
 * Notification while the device is unlocked, but may substitute its publicVersion while locked.
 *
 * This service deliberately requests no window hierarchy, gestures, key events, or screenshots.
 */
@AndroidEntryPoint
class AccessibilityNotificationService : AccessibilityService() {
  @Inject lateinit var autoUpdatingListenerUtils: AutoUpdatingListenerUtils
  @Inject lateinit var recentDetectedCodesHolder: RecentDetectedCodesHolder

  override fun onServiceConnected() {
    super.onServiceConnected()
    MonitoringHealthStore.markAccessibilityConnected(applicationContext, true)
    AppLogger.i(TAG, "accessibility notification service connected")
    PersistenceService.start(applicationContext)
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    if (event?.eventType != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) return
    if (!autoUpdatingListenerUtils.awaitCodeExtractor()) return
    val listenerSettings = autoUpdatingListenerUtils.current()
    if (listenerSettings.modeOfOperation != ModeOfOperation.Notification) return

    val notification = event.parcelableData as? Notification
    val isNotificationEvent =
        notification != null || event.className?.toString() == Notification::class.java.name
    if (!isNotificationEvent) return

    val packageName = event.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return
    if (packageName == applicationContext.packageName) return

    if (notification != null) {
      val isForegroundService = notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0
      val isOngoing = notification.flags and Notification.FLAG_ONGOING_EVENT != 0
      if (isForegroundService || isOngoing) return
    }

    val extractor = listenerSettings.codeExtractor ?: return
    val rawText = collectNotificationText(event, notification)
    if (rawText.isBlank()) return

    val cleanedText = extractor.cleanup(rawText)
    val code = NotificationCodeSelector.selectCode(rawText, extractor) ?: return
    val signature = RecentDetectedCodesHolder.signature(packageName, code)
    if (recentDetectedCodesHolder.isDuplicate(signature, System.currentTimeMillis())) return

    val data =
        try {
          workDataOf(
              "packageName" to packageName,
              "notificationId" to signature.hashCode().toUInt().toString(),
              "notificationTag" to "accessibility-notification",
              "text" to cleanedText,
              "code" to code,
          )
        } catch (error: Throwable) {
          AppLogger.e(TAG, "Accessibility notification was too large to enqueue", error)
          return
        }

    WorkManager.getInstance(applicationContext)
        .enqueue(OneTimeWorkRequestBuilder<CodeDetectedWorker>().setInputData(data).build())
    AppLogger.i(TAG, "code detected through accessibility notification event, pkg=$packageName")
    PersistenceService.start(applicationContext)
  }

  override fun onInterrupt() {
    AppLogger.w(TAG, "accessibility notification service interrupted")
  }

  override fun onDestroy() {
    MonitoringHealthStore.markAccessibilityConnected(applicationContext, false)
    AppLogger.w(TAG, "accessibility notification service destroyed")
    PersistenceService.scheduleRestart(applicationContext)
    super.onDestroy()
  }

  private fun collectNotificationText(
      event: AccessibilityEvent,
      notification: Notification?,
  ): String {
    val lines = linkedSetOf<String>()

    fun add(value: CharSequence?) {
      value?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(lines::add)
    }

    event.text.forEach(::add)
    add(event.contentDescription)
    for (index in 0 until event.recordCount) {
      val record = event.getRecord(index)
      record.text.forEach(::add)
      add(record.contentDescription)
    }

    if (notification != null) {
      add(notification.tickerText)
      NotificationListener.extractNotificationText(notification).lineSequence().forEach { add(it) }
    }

    return lines.joinToString(separator = "\n")
  }

  companion object {
    private const val TAG = "AccessibilityNotif"

    fun isEnabled(context: Context): Boolean {
      val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
      return manager
          .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
          .any { info ->
            val serviceInfo = info.resolveInfo.serviceInfo
            serviceInfo.packageName == context.packageName &&
                serviceInfo.name == AccessibilityNotificationService::class.java.name
          }
    }
  }
}
