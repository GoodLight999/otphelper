package io.github.jd1378.otphelper.utils

import androidx.compose.runtime.Immutable
import io.github.jd1378.otphelper.utils.CodeExtractorDefaults.currencyIndicators
import io.github.jd1378.otphelper.utils.CodeExtractorDefaults.skipPhrases
import kotlinx.collections.immutable.persistentListOf

@Immutable
data class CodeExtractorResult(
  val matchResult: MatchResult,
  val phraseGroup: Int,
  val codeGroup: Int,
)

object CodeExtractorDefaults {
  val sensitivePhrases =
      persistentListOf(
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
          "\\bel siguiente PIN\\W", // spanish
          "验证码",
          "校验码",
          "識別碼",
          "認證",
          "驗證",
          "код",
          "סיסמ",
          "\\bהקוד\\W",
          "\\bקוד\\W",
          "\\bKodu\\W", // "code" in turkish
          "\\bKodunuz\\W", // "your code" in turkish
          "\\b[sş]ifre:\\W", // "password" in turkish
          "\\bKodi\\W",
          "\\bKods\\W",
          "\\b(?:m|sms)?TAN\\W",
          "\\bcodice\\W", // "code" in italian
          "認証(?:用)?コード", // strong Japanese authentication context
          "確認コード",
          "検証コード",
          "セキュリティコード",
          "ログインコード",
          "本人確認(?:用)?コード",
          "ワンタイム(?:パス)?コード",
          "コード", // generic Japanese fallback; guarded by local non-OTP context checks below
          "パスワード", // generic Japanese fallback; preserves 3-D Secure style messages
          "認証番号", // "authentication number" in japanese
          "ワンタイム", // "one time" in japanese
          "\\bvahvistuskoodi", // "confirmation code" in finnish
          "\\bkertakäyttökoodisi\\W", // "your single-use code" in finnish
          "\\bkod\\W", // PL
          "\\bautoryzacji\\W", // PL
          "Parol\\s+dlya\\s+podtverzhdeniya", // russian
          "\\bпароль\\W", // russian
          "인증번호", // "authentication number" in korean
      )

  val skipPhrases =
      persistentListOf(
          "مقدار",
          "مبلغ",
          "amount",
          "برای",
          "-ارز",
          // avoids detecting space separated code as bunch of words:
          "[a-zA-Z0-9] [a-zA-Z0-9] [a-zA-Z0-9] [a-zA-Z0-9] ?",
      )

  val currencyIndicators =
      persistentListOf(
          "USD",
          "EUR",
          "GBP",
          "[$€£]",
      )

  val ignoredPhrases =
      persistentListOf(
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

  val cleanupPhrases =
      persistentListOf(
          "[a-zA-Z0-9][a-zA-Z0-9-]{0,61}\\.[a-zA-Z]{2,}(?:[.a-zA-Z]{0,3}(?=\\s+)|)", // simpleDomainRegex
          "['\"]",
          "Endziffer-\\d+",
          "Ending \\d+",
          "<#>",
          "share OTP",
      )

  // Contexts that commonly contain code-like identifiers but are not authentication codes.
  // These are deliberately NOT exposed as global ignore phrases: a real OTP notification can
  // mention an order/account identifier elsewhere in the same message. Each extracted candidate
  // is judged against only its local left-hand context.
  val nonOtpContextPhrases =
      persistentListOf(
          "クーポン(?:コード)?",
          "プロモ(?:ーション)?コード",
          "招待コード",
          "紹介コード",
          "商品コード",
          "製品コード",
          "品番",
          "型番",
          "注文(?:番号|コード|ID)",
          "受注番号",
          "予約(?:番号|コード|ID)",
          "受付番号",
          "お問い合わせ番号",
          "問合せ番号",
          "追跡(?:番号|コード|ID)",
          "配送(?:番号|コード|ID)",
          "伝票番号",
          "チケット(?:番号|コード|ID)",
          "会員番号",
          "顧客番号",
          "シリアル(?:番号|コード)",
          "バージョン(?:番号|コード)?",
          "ビルド(?:番号|コード)?",
          "郵便番号",
          "\\b(?:coupon|promo|promotion|discount|referral|invite|product|order|tracking|shipment|booking|reservation|ticket|serial|version|build|postal|zip)\\s*(?:code|id|number)\\b",
      )

  val strongOtpContextPhrases =
      persistentListOf(
          "\\bOTP\\b",
          "\\b2FA\\b",
          "One[-\\s]?Time(?:[-\\s](?:Password|Passcode|Code))?",
          "\\bverification\\s+code\\b",
          "\\bauthentication\\s+code\\b",
          "\\bsecurity\\s+code\\b",
          "\\bconfirmation\\s+code\\b",
          "\\blogin\\s+code\\b",
          "認証(?:番号|(?:用)?コード)",
          "確認コード",
          "検証コード",
          "本人確認(?:用)?コード",
          "ワンタイム(?:パスワード|パスコード|コード)?",
          "使い捨て(?:パスワード|コード)",
          "ログインコード",
      )
}

class CodeExtractor // this comment is to separate parts
(
  private val sensitivePhrases: List<String> = CodeExtractorDefaults.sensitivePhrases,
  private val ignoredPhrases: List<String> = CodeExtractorDefaults.ignoredPhrases,
  private val cleanupPhrases: List<String> = CodeExtractorDefaults.cleanupPhrases,
) {

  private data class Candidate(
    val match: MatchResult,
    val phraseGroup: Int,
    val codeGroup: Int,
    val code: String,
    val hasStrongContext: Boolean,
  )

  val generalCodeMatcher: Regex =
      """(${sensitivePhrases.joinToString("|")})(?:\s*(?!${
        skipPhrases.joinToString("|")
      })(?:[^\s:：܃︓﹕.'"\d\u0660-\u0669\u06F0-\u06F9]|[\d\u0660-\u0669\u06F0-\u06F9,\s]+(?:${
        currencyIndicators.joinToString(
            "|",
        )
      })|[\d\u0660-\u0669\u06F0-\u06F9][^\d\u0660-\u0669\u06F0-\u06F9]))*\s*[:：܃︓﹕]?\s*(["'「]?)${
        ""
      }([\d\u0660-\u0669\u06F0-\u06F9a-zA-Z\-]{4,}|(?: [\d\u0660-\u0669\u06F0-\u06F9a-zA-Z]){4,}|)\1?(?:[^\d\u0660-\u0669\u06F0-\u06F9a-zA-Z]|${'$'})"""
          .toRegex(
              setOf(
                  RegexOption.IGNORE_CASE,
                  RegexOption.MULTILINE,
              ),
          )

  val specialCodeMatcher =
      """((?:[\d\u0660-\u0669\u06F0-\u06F9]-?){4,}(?=\s)|[\d\u0660-\u0669\u06F0-\u06F9 ]{4,}(?=\s)|[\d\u0660-\u0669\u06F0-\u06F9]{4,})[^:]*(${
        sensitivePhrases.joinToString(
            "|",
        )
      })"""
          .toRegex(setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

  val ignoredPhrasesRegex =
      """\b(${ignoredPhrases.joinToString("|")})\b"""
          .toRegex(setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

  val cleanupPhrasesRegex =
      """(${cleanupPhrases.joinToString("|")})"""
          .toRegex(setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

  private val nonOtpContextRegex =
      """(${CodeExtractorDefaults.nonOtpContextPhrases.joinToString("|")})"""
          .toRegex(setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

  private val strongOtpContextRegex =
      """(${CodeExtractorDefaults.strongOtpContextPhrases.joinToString("|")})"""
          .toRegex(setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

  // doCleanup is added for convenience in unit testing
  fun getCode(str: String, doCleanup: Boolean = true): String? {
    if (sensitivePhrases.isEmpty()) return null
    val cleanStr =
        if (doCleanup) {
          cleanup(str)
        } else {
          str
        }

    val candidate = findCandidate(cleanStr) ?: return null
    return toEnglishNumbers(candidate.code)
  }

  fun getCodeMatch(str: String?): CodeExtractorResult? {
    if (str.isNullOrEmpty() || sensitivePhrases.isEmpty()) return null
    val candidate = findCandidate(str) ?: return null
    return CodeExtractorResult(candidate.match, candidate.phraseGroup, candidate.codeGroup)
  }

  private fun findCandidate(str: String): Candidate? {
    val generalCandidates =
        generalCodeMatcher.findAll(str).mapNotNull { match ->
          val code = normalizeCode(match.groups[3]?.value)
          candidateFromMatch(str, match, phraseGroup = 1, codeGroup = 3, code = code)
        }

    // Prefer a strong local authentication phrase over an earlier generic "code" candidate.
    selectBestCandidate(generalCandidates.toList())?.let { return it }

    val specialCandidates =
        specialCodeMatcher.findAll(str).mapNotNull { match ->
          val code = normalizeCode(match.groups[1]?.value)
          candidateFromMatch(str, match, phraseGroup = 2, codeGroup = 1, code = code)
        }
    return selectBestCandidate(specialCandidates.toList())
  }

  private fun candidateFromMatch(
    source: String,
    match: MatchResult,
    phraseGroup: Int,
    codeGroup: Int,
    code: String?,
  ): Candidate? {
    if (code.isNullOrEmpty() || !isPlausibleOtpCode(code)) return null

    val context = localLeftContext(source, match)
    val strong = strongOtpContextRegex.containsMatchIn(context)
    val nonOtp = nonOtpContextRegex.containsMatchIn(context)
    if (nonOtp && !strong) return null

    return Candidate(
        match = match,
        phraseGroup = phraseGroup,
        codeGroup = codeGroup,
        code = code,
        hasStrongContext = strong,
    )
  }

  private fun selectBestCandidate(candidates: List<Candidate>): Candidate? =
      candidates
          .sortedWith(
              compareByDescending<Candidate> { it.hasStrongContext }
                  .thenBy { it.match.range.first },
          )
          .firstOrNull()

  private fun localLeftContext(source: String, match: MatchResult): String {
    // Generic words such as "code" often begin after their qualifier ("order code",
    // "login code"). Looking only to the left prevents a later real OTP phrase from blessing an
    // earlier order/tracking identifier in the same notification.
    val start = (match.range.first - CONTEXT_LOOKBEHIND_CHARS).coerceAtLeast(0)
    val endExclusive = (match.range.last + 1).coerceAtMost(source.length)
    return source.substring(start, endExclusive)
  }

  private fun normalizeCode(code: String?): String? =
      code?.replace(" ", "")?.replace("-", "")?.takeIf { it.isNotBlank() }

  private fun isPlausibleOtpCode(code: String): Boolean {
    // Most OTPs are 4–10 characters. Keep a little headroom for uncommon providers while
    // rejecting UUIDs, tracking numbers, hashes and other long identifiers that generic "code"
    // wording can otherwise pull out of notifications.
    return code.length in 4..12 && code.any { it.isLetterOrDigit() }
  }

  private fun toEnglishNumbers(number: String?): String? {
    if (number.isNullOrEmpty()) return null
    val chars = CharArray(number.length)
    for (i in number.indices) {
      var ch = number[i]
      if (ch.code in 0x0660..0x0669) {
        ch -= (0x0660 - '0'.code)
      } else if (ch.code in 0x06f0..0x06F9) {
        ch -= (0x06f0 - '0'.code)
      }
      chars[i] = ch
    }
    return String(chars)
  }

  fun shouldIgnore(str: String): Boolean {
    if (ignoredPhrases.isEmpty()) return false
    return ignoredPhrasesRegex.containsMatchIn(str)
  }

  fun getIgnorePhrase(str: String): String? {
    if (ignoredPhrases.isEmpty()) return null
    return ignoredPhrasesRegex.find(str)?.value
  }

  fun cleanup(str: String): String {
    if (cleanupPhrases.isEmpty()) return str
    return str.replace(cleanupPhrasesRegex, "")
  }

  private companion object {
    const val CONTEXT_LOOKBEHIND_CHARS = 48
  }
}
