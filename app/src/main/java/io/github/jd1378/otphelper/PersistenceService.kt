package io.github.jd1378.otphelper

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.jd1378.otphelper.utils.AppLogger

const val INTENT_ACTION_REPAIR_BACKGROUND = "INTENT_ACTION_REPAIR_BACKGROUND"
const val INTENT_ACTION_SHIZUKU_REPAIR = "INTENT_ACTION_SHIZUKU_REPAIR"

class PersistenceService : Service() {
  companion object {
    private const val TAG = "PersistenceService"
    private const val CHANNEL_ID = "otphelper_persistence"
    private const val NOTIFICATION_ID = 0x6f7470
    private const val HEARTBEAT_MS = 60_000L
    private const val RESTART_DELAY_MS = 8_000L

    fun start(context: Context) {
      val intent = Intent(context, PersistenceService::class.java)
      try {
        ContextCompat.startForegroundService(context, intent)
      } catch (error: Throwable) {
        AppLogger.e(TAG, "Unable to start foreground persistence service", error)
        MyWorkManager.rebindListeners(context, true)
      }
    }

    fun scheduleRestart(context: Context, delayMs: Long = RESTART_DELAY_MS) {
      val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
      val restartIntent = Intent(context, WatchdogReceiver::class.java).setAction(WatchdogReceiver.ACTION_RESTART)
      val pendingIntent =
          PendingIntent.getBroadcast(
              context,
              4817,
              restartIntent,
              PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
          )
      alarmManager.setAndAllowWhileIdle(
          AlarmManager.ELAPSED_REALTIME_WAKEUP,
          android.os.SystemClock.elapsedRealtime() + delayMs,
          pendingIntent,
      )
    }
  }

  private val handler = Handler(Looper.getMainLooper())
  private val heartbeat =
      object : Runnable {
        override fun run() {
          repairListenerConnection()
          updateNotification()
          handler.postDelayed(this, HEARTBEAT_MS)
        }
      }

  override fun onCreate() {
    super.onCreate()
    createChannel()
    startInForeground()
    handler.post(heartbeat)
    AppLogger.i(TAG, "foreground persistence service created")
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    AppLogger.i(TAG, "onStartCommand action=${intent?.action}, flags=$flags, startId=$startId")
    repairListenerConnection()
    updateNotification()
    return START_STICKY
  }

  override fun onTaskRemoved(rootIntent: Intent?) {
    AppLogger.w(TAG, "task removed; scheduling recovery")
    scheduleRestart(applicationContext, 2_000L)
    super.onTaskRemoved(rootIntent)
  }

  override fun onDestroy() {
    handler.removeCallbacksAndMessages(null)
    AppLogger.w(TAG, "service destroyed; scheduling recovery")
    scheduleRestart(applicationContext)
    MyWorkManager.schedulePersistenceWatchdog(applicationContext)
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun repairListenerConnection() {
    if (!NotificationListener.isNotificationListenerServiceEnabled(this)) return
    try {
      // requestRebind is idempotent. Repeating it is safer than trusting OEM process state.
      NotificationListener.requestRebind(ComponentName(this, NotificationListener::class.java))
      AppLogger.i(TAG, "notification listener rebind requested")
    } catch (error: Throwable) {
      AppLogger.e(TAG, "notification listener rebind failed", error)
    }
  }

  private fun startInForeground() {
    val notification = buildNotification()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val type =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
              ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
          else 0
      startForeground(NOTIFICATION_ID, notification, type)
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
  }

  private fun updateNotification() {
    if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
    NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
  }

  private fun buildNotification(): Notification {
    val openIntent =
        PendingIntent.getActivity(
            this,
            4818,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    val repairIntent =
        PendingIntent.getActivity(
            this,
            4819,
            Intent(this, MainActivity::class.java)
                .setAction(INTENT_ACTION_REPAIR_BACKGROUND)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    val accessibilityIntent =
        PendingIntent.getActivity(
            this,
            4820,
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    val listenerGranted = NotificationListener.isNotificationListenerServiceEnabled(this)
    val status =
        if (listenerGranted) R.string.persistence_notification_listener_ok
        else R.string.persistence_notification_listener_waiting

    return NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle(getString(R.string.persistence_notification_title))
        .setContentText(getString(status))
        .setContentIntent(openIntent)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setVisibility(NotificationCompat.VISIBILITY_SECRET)
        .addAction(0, getString(R.string.persistence_repair), repairIntent)
        .addAction(0, getString(R.string.persistence_accessibility), accessibilityIntent)
        .build()
  }

  private fun createChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = getSystemService(NotificationManager::class.java)
    val channel =
        NotificationChannel(
                CHANNEL_ID,
                getString(R.string.persistence_channel_name),
                NotificationManager.IMPORTANCE_MIN,
            )
            .apply {
              description = getString(R.string.persistence_channel_description)
              setShowBadge(false)
              lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
    manager.createNotificationChannel(channel)
  }
}
