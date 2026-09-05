package io.github.jd1378.otphelper

import io.github.jd1378.otphelper.utils.CodeExtractor
import io.github.jd1378.otphelper.utils.NotificationCodeSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationCodeSelectorTest {
  private val extractor = CodeExtractor()

  @Test
  fun smsConversationTitleDoesNotBeatAmazonBodyOtp() {
    val notificationText =
        """
        244080
        923030は、Amazonのワンタイムパスワードです。
        誰とも共有しないでください。
        """.trimIndent()

    assertEquals("923030", NotificationCodeSelector.selectCode(notificationText, extractor))
  }

  @Test
  fun arbitraryNumericNotificationTitleCannotBorrowLaterOtpContext() {
    val notificationText =
        """
        777777
        314159 is your one-time password. Do not share it.
        """.trimIndent()

    assertEquals("314159", NotificationCodeSelector.selectCode(notificationText, extractor))
  }

  @Test
  fun splitBodyFallbackExcludesNumericConversationTitle() {
    val completeNotification =
        """
        244080
        923030
        Amazonのワンタイムパスワードです。
        誰とも共有しないでください。
        """.trimIndent()
    val bodyOnly =
        """
        923030
        Amazonのワンタイムパスワードです。
        誰とも共有しないでください。
        """.trimIndent()

    assertEquals(
        "923030",
        NotificationCodeSelector.selectCode(completeNotification, extractor, bodyOnly),
    )
  }

  @Test
  fun codeAndPhraseOnSeparateLinesStillUseWholeTextFallback() {
    val notificationText =
        """
        481516
        Your verification code
        """.trimIndent()

    assertEquals("481516", NotificationCodeSelector.selectCode(notificationText, extractor))
  }

  @Test
  fun ignoredMetadataLineDoesNotSuppressIndependentRealOtpLine() {
    val notificationText =
        """
        discount code 1234
        Your verification code is 567890.
        """.trimIndent()

    assertEquals("567890", NotificationCodeSelector.selectCode(notificationText, extractor))
  }

  @Test
  fun numericMetadataAloneIsNotAnOtp() {
    assertNull(NotificationCodeSelector.selectCode("244080", extractor))
  }
}
