package io.github.jd1378.otphelper

import io.github.jd1378.otphelper.utils.CodeExtractor
import org.junit.Assert.assertEquals
import org.junit.Test

class MagicRecipeRegressionTest {
  private val extractor = CodeExtractor()

  @Test
  fun rejectsJapaneseCommerceIdentifiers() {
    val cases =
        listOf(
            "ご注文番号 987654。注文コード: 123456",
            "配送状況を更新しました。追跡コード: 12345678",
            "10%OFFクーポンコード: SAVE2026",
            "商品コード：ABCD1234",
            "アプリのバージョンコード：123456",
            "予約コード: 654321",
            "お問い合わせ番号 987654、受付コード: 123456",
        )

    cases.forEach { message ->
      assertEquals("Should reject non-OTP identifier: $message", null, extractor.getCode(message))
    }
  }

  @Test
  fun rejectsEnglishCommerceIdentifiers() {
    val cases =
        listOf(
            "Your order code is 123456",
            "Tracking number 9876543210. Tracking code 123456",
            "Promo code SAVE2026",
            "Product code ABCD1234",
            "Build number 123456. Build code 654321",
            "Reservation code 123456",
        )

    cases.forEach { message ->
      assertEquals("Should reject non-OTP identifier: $message", null, extractor.getCode(message))
    }
  }

  @Test
  fun rejectsUnusuallyLongGenericCodes() {
    assertEquals(null, extractor.getCode("Your code is 1234567890123456"))
    assertEquals(null, extractor.getCode("コード: ABCDEFGHIJKLMNOP"))
  }

  @Test
  fun keepsStrongJapaneseOtpSignals() {
    val cases =
        mapOf(
            "認証コード: 123456" to "123456",
            "確認コードは12345です" to "12345",
            "ログインコード：654321" to "654321",
            "ワンタイムパスコード「112233」" to "112233",
            "パスワード：123456 ご利用金額：JPY 54,321" to "123456",
            "注文番号 987654。本人確認用コード: 123456" to "123456",
        )

    cases.forEach { (message, expected) ->
      assertEquals("Should keep real OTP: $message", expected, extractor.getCode(message))
    }
  }

  @Test
  fun keepsStrongEnglishOtpSignalsEvenWithOtherIdentifiers() {
    val cases =
        mapOf(
            "Order number 987654. Your verification code is 123456" to "123456",
            "Tracking number 9876543210. OTP 112233" to "112233",
            "Reservation code ABCD1234. Login code: 654321" to "654321",
        )

    cases.forEach { (message, expected) ->
      assertEquals("Should keep real OTP: $message", expected, extractor.getCode(message))
    }
  }
}
