package io.github.jd1378.otphelper.fixture;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** CI-only external app that posts an OTP notification from a genuinely different package. */
public final class ProbeReceiver extends BroadcastReceiver {
  private static final String CHANNEL_ID = "otp_probe";
  private static final int NOTIFICATION_ID = 1;

  @Override
  public void onReceive(Context context, Intent intent) {
    String token = intent.getStringExtra("token");
    String tag = intent.getStringExtra("tag");
    if (token == null || token.isEmpty() || tag == null || tag.isEmpty()) return;

    NotificationManager manager = context.getSystemService(NotificationManager.class);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      manager.createNotificationChannel(
          new NotificationChannel(
              CHANNEL_ID,
              "OTP probe notifications",
              NotificationManager.IMPORTANCE_DEFAULT));
    }

    Notification.Builder builder =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(context, CHANNEL_ID)
            : new Notification.Builder(context);
    Notification notification =
        builder
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("External OTP test")
            .setContentText("One-time verification code: " + token)
            .setStyle(
                new Notification.BigTextStyle()
                    .bigText("External app verification code: " + token))
            .setAutoCancel(true)
            .build();
    manager.notify(tag, NOTIFICATION_ID, notification);
  }
}
