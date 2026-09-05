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

  @Test
  fun redactsLongPhoneNumbers() {
    val redacted = AppLogger.redact("smsOrigin=09012345678 sender=+819012345678")

    assertFalse(redacted.contains("09012345678"))
    assertFalse(redacted.contains("819012345678"))
    assertTrue(redacted.contains("<redacted-number>"))
  }

  @Test
  fun doesNotTreatOtpInsidePackageNameAsASecretKey() {
    val redacted = AppLogger.redact("package=io.github.jd1378.otphelper")

    assertTrue(redacted.contains("io.github.jd1378.otphelper"))
  }

  @Test
  fun doesNotRedactOrdinaryWordsAfterCodeKeyword() {
    val redacted = AppLogger.redact("code detected in notification")

    assertTrue(redacted.contains("code detected"))
  }
}
