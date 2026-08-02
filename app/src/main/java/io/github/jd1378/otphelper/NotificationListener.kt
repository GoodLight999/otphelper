package io.github.jd1378.otphelper

import android.annotation.SuppressLint
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.AndroidEntryPoint
import io.github.jd1378.otphelper.di.AutoUpdatingListenerUtils
import io.github.jd1378.otphelper.di.DETECTION_LOCK
import io.github.jd1378.otphelper.di.DETECTION_TIMEOUT_MS
import io.github.jd1378.otphelper.di.RecentDetectedCodesHolder
import io.github.jd1378.otphelper.di.RecentDetectedMessageHolder
import io.github.jd1378.otphelper.utils.AppLogger
import io.github.jd1378.otphelper.utils.MonitoringHealthStore
import io.github.jd1378.otphelper.worker.CodeDetectedWorker
import javax.inject.Inject

@AndroidEntryPoint
class NotificationListener : NotificationListenerService() {

  @Inject lateinit var autoUpdatingListenerUtils: AutoUpdatingListenerUtils
  @Inject lateinit var recentDetectedMessageHolder: RecentDetectedMessageHolder
  @Inject lateinit var recentDetectedCodesHolder: RecentDetectedCodesHolder

  companion object {
    const val TAG = "NotificationListener"
    private val redactedNotificationMessages =
        mutableSetOf(
            "Sensitive notification content hidden",
            "محتوای اعلان حساس پنهان شده است",
            "تم إخفاء المحتوى الحساس في الإشعار",
            "已隐藏敏感通知内容",
            "系統已隱藏含有私密資訊的通知內容",
            "Деликатното съдържание в известието е скрито",
            "S'ha amagat contingut sensible de les notificacions",
            "Vertrauliche Benachrichtigungsinhalte ausgeblendet",
            "Contenido sensible de la notificación oculto",
            "Märguande delikaatne sisu peideti",
            "Le contenu sensible de la notification a été masqué",
            "संवेदनशील जानकारी वाली सूचना का कॉन्टेंट छिपा है",
            "Contenuti sensibili della notifica nascosti",
            "יש תוכן רגיש בהתראה שהוסתר",
            "プライベートな通知内容は表示されません",
            "민감한 알림 콘텐츠 숨김",
            "Treść poufnego powiadomienia została ukryta",
            "Conteúdo de notificação sensível oculto",
            "Conținutul sensibil din notificări a fost ascuns",
            "Конфиденциальная информация в уведомлении скрыта",
            "உணர்வுபூர்வமான அறிவிப்பு உள்ளடக்கம் மறைக்கப்பட்டது",
            "Hassas bildirim içerikleri gizlendi",
            "Чутливий вміст сповіщення приховано",
            "Đã ẩn nội dung thông báo nhạy cảm",
        )

    val notificationTextKeys =
        listOf(
            Notification.EXTRA_TITLE,
            Notification.EXTRA_TITLE_BIG,
            Notification.EXTRA_TEXT,
            Notification.EXTRA_SUB_TEXT,
            Notification.EXTRA_INFO_TEXT,
            Notification.EXTRA_SUMMARY_TEXT,
            Notification.EXTRA_BIG_TEXT,
        )
    val notificationTextArrayKeys = listOf(Notification.EXTRA_TEXT_LINES)

    fun isNotificationListenerServiceEnabled(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun enable(context: Context) {
      context.packageManager.setComponentEnabledSetting(
          ComponentName(context, NotificationListener::class.java),
          PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
          PackageManager.DONT_KILL_APP,
      )
    }

    fun disable(context: Context) {
      context.packageManager.setComponentEnabledSetting(
          ComponentName(context, NotificationListener::class.java),
          PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
          PackageManager.DONT_KILL_APP,
      )
    }

    fun extractNotificationText(notification: Notification): String {
      val extras = notification.extras
      return buildString {
        for (key in notificationTextKeys) {
          extras.getCharSequence(key)?.toString()?.takeIf { it.isNotEmpty() }?.let {
            append(it).append('\n')
          }
        }
        for (key in notificationTextArrayKeys) {
          extras.getCharSequenceArray(key)?.forEach { value ->
            if (!value.isNullOrBlank()) append(value).append('\n')
          }
        }
      }
    }

    @SuppressLint("DiscouragedApi")
    private fun hasRedactedMessage(notif: Notification): Boolean {
      try {
        val resId =
            Resources.getSystem().getIdentifier("redacted_notification_message", "string", "android")
        val res = Resources.getSystem().getString(resId)
        if (res.isNotBlank()) redactedNotificationMessages.add(res)
      } catch (_: Throwable) {}
      notif.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.let { str ->
        if (redactedNotificationMessages.contains(str)) return true
      }
      return false
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    AppLogger.i(TAG, "onStartCommand: action=${intent?.action}, flags=$flags, startId=$startId")
    super.onStartCommand(intent, flags, startId)
    return START_STICKY
  }

  override fun onListenerConnected() {
    super.onListenerConnected()
    MonitoringHealthStore.markListenerConnected(applicationContext, true)
    AppLogger.i(TAG, "onListenerConnected")
    PersistenceService.start(applicationContext)
  }

  override fun onNotificationPosted(sbn: StatusBarNotification?) {
    super.onNotificationPosted(sbn)
    if (sbn == null) return

    AppLogger.d(TAG, "onNotificationPosted: pkg=${sbn.packageName}, id=${sbn.id}")
    val notification = sbn.notification
    val rawNotificationText = extractNotificationText(notification)

    autoUpdatingListenerUtils.awaitCodeExtractor()
    if (autoUpdatingListenerUtils.modeOfOperation != ModeOfOperation.Notification &&
        !autoUpdatingListenerUtils.isAutoDismissEnabled &&
        !autoUpdatingListenerUtils.isAutoMarkAsReadEnabled) {
      return
    }

    if (sbn.packageName == BuildConfig.APPLICATION_ID && sbn.id == R.id.code_detected_notify_id) {
      return
    }

    val isForegroundService = (notification.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0
    val isOngoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
    if (isForegroundService || isOngoing) {
      AppLogger.d(TAG, "skipping: foregroundService=$isForegroundService, ongoing=$isOngoing")
      return
    }

    var codeDetected = false
    if (autoUpdatingListenerUtils.modeOfOperation == ModeOfOperation.Notification) {
      val codeExtractor = autoUpdatingListenerUtils.codeExtractor ?: return
      if (codeExtractor.shouldIgnore(rawNotificationText)) {
        AppLogger.d(TAG, "notification ignored by ignore phrases, pkg=${sbn.packageName}")
        return
      }
      val notificationText = codeExtractor.cleanup(rawNotificationText)
      if (notificationText.isNotEmpty()) {
        val code = codeExtractor.getCode(notificationText, false)
        if (code.isNullOrEmpty()) {
          AppLogger.d(TAG, "no code found in notification, pkg=${sbn.packageName}")
        } else {
          codeDetected = true
          val signature = RecentDetectedCodesHolder.signature(sbn.packageName, code)
          if (recentDetectedCodesHolder.isDuplicate(signature, System.currentTimeMillis())) {
            AppLogger.d(TAG, "code detected but duplicate, skipping enqueue, pkg=${sbn.packageName}")
          } else {
            AppLogger.i(TAG, "code detected in notification, enqueueing worker, pkg=${sbn.packageName}")
            val data: Data =
                try {
                  workDataOf(
                      "packageName" to sbn.packageName,
                      "notificationId" to sbn.id.toString(),
                      "notificationTag" to sbn.tag,
                      "text" to notificationText,
                      "code" to code,
                  )
                } catch (error: Throwable) {
                  AppLogger.e(TAG, "Notification too large to enqueue", error)
                  return
                }
            WorkManager.getInstance(applicationContext)
                .enqueue(OneTimeWorkRequestBuilder<CodeDetectedWorker>().setInputData(data).build())
          }
        }
      }
    } else {
      val message = synchronized(DETECTION_LOCK) { recentDetectedMessageHolder.message }
      if (message != null) {
        if (System.currentTimeMillis() - message.timestamp > DETECTION_TIMEOUT_MS) return
        codeDetected = hasRedactedMessage(notification) || rawNotificationText.contains(message.body)
      }
    }

    if (!codeDetected) return
    AppLogger.i(
        TAG,
        "post-detection actions: autoMarkAsRead=${autoUpdatingListenerUtils.isAutoMarkAsReadEnabled}, " +
            "autoDismiss=${autoUpdatingListenerUtils.isAutoDismissEnabled}, pkg=${sbn.packageName}",
    )
    if (autoUpdatingListenerUtils.isAutoMarkAsReadEnabled) {
      notification.actions?.forEach { action ->
        val isReadAction =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                action.semanticAction == Notification.Action.SEMANTIC_ACTION_MARK_AS_READ) {
              true
            } else {
              val title = action.title.toString().lowercase()
              title.contains("mark") && title.contains("read")
            }
        if (isReadAction) {
          try {
            action.actionIntent.send()
          } catch (_: Throwable) {
            AppLogger.d(TAG, "failed to use notification action '${action.title}'")
          }
        }
      }
    }
    if (autoUpdatingListenerUtils.isAutoDismissEnabled) cancelNotification(sbn.key)
    synchronized(DETECTION_LOCK) { recentDetectedMessageHolder.message = null }
  }

  override fun onListenerDisconnected() {
    super.onListenerDisconnected()
    MonitoringHealthStore.markListenerConnected(applicationContext, false)
    AppLogger.w(TAG, "Notification listener disconnected")
    PersistenceService.start(applicationContext)
    if (isNotificationListenerServiceEnabled(applicationContext)) {
      AppLogger.i(TAG, "Notification permission remains enabled; requesting immediate rebind")
      requestRebind(ComponentName(this, NotificationListener::class.java))
    }
  }

  override fun onDestroy() {
    MonitoringHealthStore.markListenerConnected(applicationContext, false)
    AppLogger.w(TAG, "Notification listener destroyed")
    PersistenceService.scheduleRestart(applicationContext)
    super.onDestroy()
  }
}
