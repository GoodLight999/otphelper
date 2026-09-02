package io.github.jd1378.otphelper

import io.github.jd1378.otphelper.utils.CodeExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodeExtractorHardeningTest {
  private val extractor = CodeExtractor()

  @Test
  fun originBoundWebOtpOutranksHumanTextDecoys() {
    val message =
        """
        Order code: 4520
        Your verification code is 835204.

        @example.com #835204
        """.trimIndent()

    assertEquals("835204", extractor.getCode(message))
  }

  @Test
  fun originBoundWebOtpAcceptsAlphanumericCodeWithDigit() {
    val message = "Your authentication code is A1B2C3.\n\n@example.com #A1B2C3"
    assertEquals("A1B2C3", extractor.getCode(message))
  }

  @Test
  fun strongerVerificationContextWinsOverEarlierAccountCode() {
    val message = "Account code 4520. Your verification code is 835204."
    assertEquals("835204", extractor.getCode(message))
  }

  @Test
  fun cardQualifierDoesNotSuppressExplicitVerificationCode() {
    val message = "Your card verification code is 246810."
    assertEquals("246810", extractor.getCode(message))
  }

  @Test
  fun genericCompetingIdentifierIsRejected() {
    assertNull(extractor.getCode("Your order code is 4520."))
    assertNull(extractor.getCode("Tracking code: 883104."))
    assertNull(extractor.getCode("Coupon code 1234 expires tonight."))
  }

  @Test
  fun plainPasscodeIsRecognizedWithoutSubstringMatchingCode() {
    assertEquals("246810", extractor.getCode("Your passcode is 246810."))
  }

  @Test
  fun codeDoesNotMatchInsideUnrelatedIdentifierWords() {
    assertNull(extractor.getCode("versionCode 1234"))
    assertNull(extractor.getCode("barcode 123456"))
    assertNull(extractor.getCode("unicode 987654"))
  }

  @Test
  fun chinesePhraseWorksWithoutAsciiWordBoundaries() {
    assertEquals("334455", extractor.getCode("您的验证码334455，请勿泄露。"))
  }

  @Test
  fun upstreamTurkishYourPasswordPhraseIsRecognized() {
    assertEquals("123456", extractor.getCode("Şifreniz: 123456"))
  }

  @Test
  fun unrelatedNumberWithoutAuthenticationContextIsIgnored() {
    assertNull(extractor.getCode("Your parcel weighs 4520 grams."))
  }
}
