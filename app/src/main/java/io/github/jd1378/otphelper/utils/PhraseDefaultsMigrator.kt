package io.github.jd1378.otphelper.utils

import io.github.jd1378.otphelper.UserSettings

/**
 * Versioned migration for persisted phrase lists.
 *
 * Phrase lists are user-editable, so a migration must never append new defaults blindly. We only
 * replace a list when it is byte-for-byte equal to a known historical default list. Any edit,
 * deletion, reordering, import, or custom-only list is treated as intentional user configuration.
 */
object PhraseDefaultsMigrator {
  const val CURRENT_VERSION = 1

  private val legacySensitiveV1205 =
      listOf(
          "code",
          "One[-\\s]Time[-\\s]Password",
          "کد",
          "رمز",
          "\\bOTP\\W",
          "\\b2FA\\W",
          "Einmalkennwort",
          "contraseña",
          "c[oó]digo",
          "clave",
          "\\bel siguiente PIN\\W",
          "验证码",
          "校验码",
          "識別碼",
          "認證",
          "驗證",
          "код",
          "סיסמ",
          "\\bהקוד\\W",
          "\\bקוד\\W",
          "\\bKodu\\W",
          "\\bKodunuz\\W",
          "\\b[sş]ifre:\\W",
          "\\bKodi\\W",
          "\\bKods\\W",
          "\\b(?:m|sms)?TAN\\W",
          "\\bcodice\\W",
          "コード",
          "パスワード",
          "認証番号",
          "ワンタイム",
          "\\bvahvistuskoodi",
          "\\bkertakäyttökoodisi\\W",
          "\\bkod\\W",
          "\\bautoryzacji\\W",
          "Parol\\s+dlya\\s+podtverzhdeniya",
          "\\bпароль\\W",
          "인증번호",
      )

  // Upstream 1.20.6 fixed Unicode boundaries and added Persian confirmation-ID wording. Supporting
  // this exact snapshot makes the fork migration robust even for users who imported upstream
  // defaults before installing the fork.
  private val legacySensitiveV1206 =
      listOf(
          "code",
          "One[-\\s]Time[-\\s]Password",
          "کد",
          "رمز",
          "شناسه\\s+تا[یي][یي]د",
          "\\bOTP\\W",
          "\\b2FA\\W",
          "Einmalkennwort",
          "contraseña",
          "c[oó]digo",
          "clave",
          "\\bel siguiente PIN\\W",
          "验证码",
          "校验码",
          "識別碼",
          "認證",
          "驗證",
          "код",
          "סיסم",
          "(?<![\\p{L}\\p{N}_])הקוד(?![\\p{L}\\p{N}_])",
          "(?<![\\p{L}\\p{N}_])קוד(?![\\p{L}\\p{N}_])",
          "\\bKodu\\W",
          "\\bKodunuz\\W",
          "\\b[sş]ifre:\\W",
          "\\bKodi\\W",
          "\\bKods\\W",
          "\\b(?:m|sms)?TAN\\W",
          "\\bcodice\\W",
          "コード",
          "パスワード",
          "認証番号",
          "ワンタイム",
          "\\bvahvistuskoodi",
          "\\bkertakäyttökoodisi\\W",
          "\\bkod\\W",
          "\\bautoryzacji\\W",
          "Parol\\s+dlya\\s+podtverzhdeniya",
          "(?<![\\p{L}\\p{N}_])пароль(?![\\p{L}\\p{N}_])",
          "인증번호",
      )

  private val legacyIgnoredV1205 =
      listOf(
          "تخفیف",
          "takhfif",
          "off",
          "اشتباه وارد شده",
          "RatingCode",
          "vscode",
          "versionCode",
          "unicode",
          "discount code",
          "fancode",
          "encode",
          "decode",
          "barcode",
          "codex",
      )

  private val legacyIgnoredV1206 =
      listOf(
          "تخفیف",
          "تخفیفات",
          "تخفیفها",
          "takhfif",
          "off",
          "اشتباه وارد شده",
          "RatingCode",
          "vscode",
          "versionCode",
          "unicode",
          "discount code",
          "fancode",
          "encode",
          "decode",
          "barcode",
          "codex",
      )

  private val legacyCleanup =
      listOf(
          "[a-zA-Z0-9][a-zA-Z0-9-]{0,61}\\.[a-zA-Z]{2,}(?:[.a-zA-Z]{0,3}(?=\\s+)|)",
          "['\"]",
          "Endziffer-\\d+",
          "Ending \\d+",
          "<#>",
          "share OTP",
      )

  fun migrate(settings: UserSettings): UserSettings {
    if (settings.phraseDefaultsVersion >= CURRENT_VERSION) return settings

    val sensitive =
        when (settings.sensitivePhrasesList) {
          legacySensitiveV1205, legacySensitiveV1206 -> CodeExtractorDefaults.sensitivePhrases
          else -> settings.sensitivePhrasesList
        }
    val ignored =
        when (settings.ignoredPhrasesList) {
          legacyIgnoredV1205, legacyIgnoredV1206 -> CodeExtractorDefaults.ignoredPhrases
          else -> settings.ignoredPhrasesList
        }
    val cleanup =
        if (settings.cleanupPhrasesList == legacyCleanup) {
          CodeExtractorDefaults.cleanupPhrases
        } else {
          settings.cleanupPhrasesList
        }

    return settings
        .toBuilder()
        .clearSensitivePhrases()
        .addAllSensitivePhrases(sensitive)
        .clearIgnoredPhrases()
        .addAllIgnoredPhrases(ignored)
        .clearCleanupPhrases()
        .addAllCleanupPhrases(cleanup)
        .setPhraseDefaultsVersion(CURRENT_VERSION)
        .build()
  }
}
