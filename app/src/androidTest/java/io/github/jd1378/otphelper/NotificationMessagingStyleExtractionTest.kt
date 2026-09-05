package io.github.jd1378.otphelper

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
  fun currentMessagingStyleMessagesAreBodyTextAndNewestOtpWins() {
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

    val completeText = NotificationListener.extractNotificationText(notification)
    val bodyText = NotificationListener.extractNotificationBodyText(notification)

    // Conversation/title metadata stays out of the cross-line body representation.
    assertFalse(bodyText.lineSequence().any { it.trim() == "244080" })
    assertTrue(bodyText.contains(newestMessage))
    assertTrue(bodyText.contains(olderMessage))

    // MessagingStyle#getMessages() is chronological. Extraction deliberately appends those
    // messages newest-first so an expired earlier OTP cannot win merely because it was added first.
    assertTrue(bodyText.indexOf(newestMessage) < bodyText.lastIndexOf(olderMessage))

    assertEquals(
        "923030",
        NotificationCodeSelector.selectCode(completeText, CodeExtractor(), bodyText),
    )
  }
}
