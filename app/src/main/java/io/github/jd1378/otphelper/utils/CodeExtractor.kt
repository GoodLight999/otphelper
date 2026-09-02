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

// Java/Kotlin's traditional \b handling is not reliable enough for all scripts used by OTP
// providers. These boundaries explicitly treat every Unicode letter/number/underscore as a word
// character, matching the upstream 1.20.6 fix without sacrificing CJK phrases that have no spaces.
private const val WORD_START = "(?<![\\p{L}\\p{N}_])"
private const val WORD_END = "(?![\\p{L}\\p{N}_])"

object CodeExtractorDefaults {
  val sensitivePhrases =
      persistentListOf(
          "${WORD_START}code$WORD_END",
          "${WORD_START}passcode$WORD_END",
          "One[-\\s]Time[-\\s]Password",
          "کد",
          "رمز",
          "شناسه\\s+تا[یي][یي]د", // "confirmation id" in persian; upstream 1.20.6
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
          "${WORD_START}הקוד$WORD_END",
          "${WORD_START}קוד$WORD_END",
          "\\bKodu\\W", // "code" in turkish
          "\\bKodunuz\\W", // "your code" in turkish
          "\\b[sş]ifre:\\W", // "password" in turkish
          "${WORD_START}[sş]ifreniz$WORD_END", // "your password" in turkish; upstream main
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
          "${WORD_START}пароль$WORD_END", // russian; Unicode-aware upstream boundary
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
  // This follows the same principle used by mature SMS rule engines: competing identifiers are
  // classified locally instead of globally blacklisting the whole message, because a real OTP can
  // coexist with an order/reference/account number in the same notification.
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
          "参照(?:番号|コード|ID)",
          "照会(?:番号|コード|ID)",
          "取引(?:番号|コード|ID)",
          "請求(?:番号|コード|ID)",
          "追跡(?:番号|コード|ID)",
          "配送(?:番号|コード|ID)",
          "伝票番号",
          "チケット(?:番号|コード|ID)",
          "会員(?:番号|コード|ID)",
          "顧客(?:番号|コード|ID)",
          "口座(?:番号|コード|ID)",
          "カード(?:番号|コード|ID)",
          "電話(?:番号|コード)",
          "シリアル(?:番号|コード)",
          "バージョン(?:番号|コード)?",
          "ビルド(?:番号|コード)?",
          "郵便番号",
          "\\b(?:coupon|promo|promotion|discount|referral|invite|product|order|reference|ref|transaction|invoice|receipt|tracking|shipment|booking|reservation|ticket|member|customer|account|card|phone|telephone|serial|version|build|postal|zip)\\s*(?:code|id|number|no\\.?)\\b",
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
          "\\bpasscode\\b",
          "認証(?:番号|(?:用)?コード)",
          "確認コード",
          "検証コード",
          "本人確認(?:用)?コード",
          "ワンタイム(?:パスワード|パスコード|コード)?",
          "使い捨て(?:パスワード|コード)",
          "ログインコード",
          "验证码",
          "校验码",
          "認證",
          "驗證",
          "인증번호",
          "Einmalkennwort",
          "شناسه\\s+تا[یي][یي]د",
          "${WORD_START}[sş]ifreniz$WORD_END",
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
    val score: Int,
  )

  // WICG/WebOTP origin-bound SMS format. It is standards-based and therefore outranks heuristic
  // text parsing. The code is 4-10 alphanumeric characters and must contain at least one digit.
  private val originBoundCodeMatcher =
      """^\s*(@[A-Za-z0-9.-]+)\s+#([A-Za-z0-9]{4,10})(?:\s+@[A-Za-z0-9.-]+)?\s*$"""
          .toRegex(setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

  val generalCodeMatcher: Regex =
      """(${sensitivePhrases.joinToString("|")})(?:\s*(?!${
        skipPhrases.joinToString("|")
      })(?:[^\s:：܃︓﹕.'"\d\u0660-\u0669\u06F0-\u06F9]|[\d\u0660-\u0669\u06F0-\u06F9,\s]+(?:${
        currencyIndicators.joinToString(
            "|",
        )
      })|[\d\u0660-\u0669\u06F0-\u06F9][^\d\u0660-\u0669\u06F0-\u06F9]))*\s*[:：܃︓﹕]?\s*(["'「]?)${
        ""
      }([\d\u0660-\u0669\u06F0-\u06F9a-zA-Z\-]{4,}|(?: [\d\u0660-\u0669\u06F0-\u06F9a-zA-Z]){4,}|)\2?(?:[^\d\u0660-\u0669\u06F0-\u06F9a-zA-Z]|${'$'})"""
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
      """$WORD_START(${ignoredPhrases.joinToString("|")})$WORD_END"""
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

    // Domain cleanup intentionally removes host names, so standards-based WebOTP extraction must
    // happen on the original text before the historical cleanup pass.
    findOriginBoundCandidate(str)?.let { return toEnglishNumbers(it.code) }

    val cleanStr =
        if (doCleanup) {
          cleanup(str)
        } else {
          str
        }

    val candidate = findHeuristicCandidate(cleanStr) ?: return null
    return toEnglishNumbers(candidate.code)
  }

  fun getCodeMatch(str: String?): CodeExtractorResult? {
    if (str.isNullOrEmpty() || sensitivePhrases.isEmpty()) return null
    val candidate = findOriginBoundCandidate(str) ?: findHeuristicCandidate(str) ?: return null
    return CodeExtractorResult(candidate.match, candidate.phraseGroup, candidate.codeGroup)
  }

  private fun findOriginBoundCandidate(source: String): Candidate? {
    val match = originBoundCodeMatcher.findAll(source).lastOrNull() ?: return null
    val code = normalizeCode(match.groups[2]?.value) ?: return null
    if (code.length !in 4..10 || code.none { it.isDigit() }) return null
    return Candidate(
        match = match,
        phraseGroup = 1,
        codeGroup = 2,
        code = code,
        score = ORIGIN_BOUND_SCORE,
    )
  }

  private fun findHeuristicCandidate(str: String): Candidate? {
    val generalCandidates =
        generalCodeMatcher.findAll(str).mapNotNull { match ->
          val code = normalizeCode(match.groups[3]?.value)
          candidateFromMatch(str, match, phraseGroup = 1, codeGroup = 3, code = code)
        }

    val specialCandidates =
        specialCodeMatcher.findAll(str).mapNotNull { match ->
          val code = normalizeCode(match.groups[1]?.value)
          candidateFromMatch(str, match, phraseGroup = 2, codeGroup = 1, code = code)
        }

    // Mature SMS parsers rank competing candidates rather than accepting the first code-looking
    // token. This is especially important for notifications that contain both an order/reference
    // identifier and a real OTP.
    return selectBestCandidate((generalCandidates + specialCandidates).toList())
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
    val strongMatches = strongOtpContextRegex.findAll(context).toList()
    val hasStrongContext = strongMatches.isNotEmpty()
    val hasNonOtpContext = nonOtpContextRegex.containsMatchIn(context)
    if (hasNonOtpContext && !hasStrongContext) return null

    var score = 0
    if (hasStrongContext) {
      val lastStrong = strongMatches.last()
      val distance = (context.length - lastStrong.range.last - 1).coerceAtLeast(0)
      score += STRONG_CONTEXT_SCORE - distance.coerceAtMost(STRONG_CONTEXT_DISTANCE_CAP)
    }
    if (hasNonOtpContext) score -= COMPETING_IDENTIFIER_PENALTY

    score +=
        when (code.length) {
          6 -> 30
          4, 5, 8 -> 20
          7 -> 10
          else -> 0
        }
    if (code.any { it.isLetter() } && code.any { it.isDigit() }) score += MIXED_CODE_SCORE

    return Candidate(
        match = match,
        phraseGroup = phraseGroup,
        codeGroup = codeGroup,
        code = code,
        score = score,
    )
  }

  private fun selectBestCandidate(candidates: List<Candidate>): Candidate? =
      candidates
          .sortedWith(
              compareByDescending<Candidate> { it.score }
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
    // WebOTP itself specifies 4-10 alphanumeric characters with at least one number. Heuristic
    // providers occasionally use 11-12 characters, so retain the fork's small compatibility
    // headroom while rejecting UUIDs, tracking hashes and other long identifiers.
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
    const val CONTEXT_LOOKBEHIND_CHARS = 64
    const val ORIGIN_BOUND_SCORE = 10_000
    const val STRONG_CONTEXT_SCORE = 200
    const val STRONG_CONTEXT_DISTANCE_CAP = 80
    const val COMPETING_IDENTIFIER_PENALTY = 30
    const val MIXED_CODE_SCORE = 10
  }
}
