package io.github.jd1378.otphelper

import io.github.jd1378.otphelper.utils.CodeExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    assertNull(extractor.getCode("Source code: 9911"))
    assertNull(extractor.getCode("Status code: 5030"))
  }

  @Test
  fun competingTechnicalCodeDoesNotHideLaterOtp() {
    val message = "Error code 5000. Your login code is 789012."
    assertEquals("789012", extractor.getCode(message))
  }

  @Test
  fun strongestEnglishContextsCoverMfaAndAccessCodes() {
    assertEquals("654321", extractor.getCode("Your MFA code is 654321."))
    assertEquals("A7C9P2", extractor.getCode("Access code: A7C9P2"))
    assertEquals("887766", extractor.getCode("Temporary passcode: 887766"))
  }

  @Test
  fun strongestJapaneseContextsCoverAdditionalAuthentication() {
    assertEquals("112233", extractor.getCode("追加認証コードは 112233 です。"))
    assertEquals("445566", extractor.getCode("次の認証番号 445566 を入力してください。"))
    assertEquals("778899", extractor.getCode("お客様の認証コード: 778899"))
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
  fun rawOffWordNoLongerGloballySuppressesRealOtpNotifications() {
    assertFalse(extractor.shouldIgnore("Turn off VPN. Your verification code is 123456."))
    assertTrue(extractor.shouldIgnore("Save 20% off today"))
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
