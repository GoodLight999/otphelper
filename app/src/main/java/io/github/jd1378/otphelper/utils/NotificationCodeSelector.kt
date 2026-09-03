package io.github.jd1378.otphelper.utils

/**
 * Selects an OTP from notification-shaped text without letting unrelated notification metadata
 * borrow authentication context from the message body.
 *
 * Android notifications expose title, sender/conversation metadata, body, summary and expanded
 * text as separate fields. OTP Helper historically concatenated those fields with newlines before
 * running [CodeExtractor]. A numeric conversation title such as an SMS short code could therefore
 * be mistaken for the OTP when a later body line contained a strong phrase such as "one-time
 * password".
 *
 * The precision rule is deliberately structural rather than provider-specific:
 * 1. evaluate every non-empty line independently first;
 * 2. a line can only produce a code from authentication context on that same line;
 * 3. ignored/exclusion phrases are evaluated locally for that line;
 * 4. only when no line-local result exists, fall back to the historical whole-text parser so
 *    legitimate messages that put the code and authentication phrase on different lines still work.
 */
object NotificationCodeSelector {
  fun selectCode(rawText: String, extractor: CodeExtractor): String? {
    if (rawText.isBlank()) return null

    val lines =
        rawText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()

    for (line in lines) {
      if (extractor.shouldIgnore(line)) continue
      val cleanedLine = extractor.cleanup(line)
      if (cleanedLine.isBlank()) continue
      extractor.getCode(cleanedLine, false)?.takeIf { it.isNotBlank() }?.let { return it }
    }

    // Keep compatibility with providers that split e.g. "123456" and "Your verification code"
    // across separate notification lines. Whole-text parsing is intentionally only the fallback.
    if (extractor.shouldIgnore(rawText)) return null
    val cleanedText = extractor.cleanup(rawText)
    if (cleanedText.isBlank()) return null
    return extractor.getCode(cleanedText, false)?.takeIf { it.isNotBlank() }
  }
}
