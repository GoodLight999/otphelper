package io.github.jd1378.otphelper

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jd1378.otphelper.utils.CodeExtractor
import io.github.jd1378.otphelper.utils.NotificationCodeSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationMessagingStyleExtractionTest {
  @Test
  fun latestMessagingStyleMessageIsBodyFallbackWithoutReplayingOlderOtp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val user = Person.Builder().setName("OTP Helper test user").build()
    val sender = Person.Builder().setName("Example service").build()
    val olderMessage = "111111 is your verification code."
    val newestMessage = "923030は、Amazonのワンタイムパスワードです。"

    val style =
        NotificationCompat.MessagingStyle(user)
            .setConversationTitle("244080")
            .addMessage(
                NotificationCompat.MessagingStyle.Message(
                    olderMessage,
                    1_000L,
                    sender,
                )
            )
            .addMessage(
                NotificationCompat.MessagingStyle.Message(
                    newestMessage,
                    2_000L,
                    sender,
                )
            )

    val notification =
        NotificationCompat.Builder(context, "otphelper-messaging-style-test")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("244080")
            .setStyle(style)
            .build()

    // Simulate a source/OEM that leaves the structured MessagingStyle data available but does not
    // provide a useful flattened message body. The fallback must therefore come from EXTRA_MESSAGES
    // through NotificationCompat.MessagingStyle rather than from EXTRA_TEXT.
    notification.extras.remove(Notification.EXTRA_TEXT)
    notification.extras.remove(Notification.EXTRA_BIG_TEXT)
    notification.extras.remove(Notification.EXTRA_TEXT_LINES)

    val completeText = NotificationListener.extractNotificationText(notification)
    val bodyText = NotificationListener.extractNotificationBodyText(notification)

    // Conversation/title metadata stays out of cross-line body inference. Only the latest current
    // MessagingStyle message is added as fallback, so an expired older OTP is not replayed when the
    // conversation posts again.
    assertFalse(bodyText.lineSequence().any { it.trim() == "244080" })
    assertTrue(bodyText.contains(newestMessage))
    assertFalse(bodyText.contains(olderMessage))

    assertEquals(
        "923030",
        NotificationCodeSelector.selectCode(completeText, CodeExtractor(), bodyText),
    )
  }
}
