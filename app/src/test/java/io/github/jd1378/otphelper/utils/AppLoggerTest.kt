package io.github.jd1378.otphelper.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLoggerTest {
  @Test
  fun redactsStandaloneOtpLikeNumbers() {
    val redacted = AppLogger.redact("code=123456 package=com.example version=1.20.5")

    assertFalse(redacted.contains("123456"))
    assertTrue(redacted.contains("<redacted>"))
    assertTrue(redacted.contains("1.20.5"))
  }

  @Test
  fun redactsStandaloneNumericSecretsWithoutKeyword() {
    val redacted = AppLogger.redact("received 987654 from notification")

    assertFalse(redacted.contains("987654"))
    assertTrue(redacted.contains("<redacted-number>"))
  }
}
