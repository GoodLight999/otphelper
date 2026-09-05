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
 * 1. evaluate every non-empty line from the complete visible notification independently first;
 * 2. a line can only produce a code from authentication context on that same line;
 * 3. ignored/exclusion phrases are evaluated locally for that line;
 * 4. only when no line-local result exists, allow cross-line parsing inside [crossLineText];
 * 5. notification callers pass body-only text as [crossLineText], so a title/sender identifier can
 *    never borrow an authentication phrase from a later body line.
 *
 * [crossLineText] defaults to [rawText] for non-structured callers and tests. Android notification
 * ingestion should pass the body-only representation whenever a Notification object is available.
 */
object NotificationCodeSelector {
  fun selectCode(
      rawText: String,
      extractor: CodeExtractor,
      crossLineText: String = rawText,
  ): String? {
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

    // Preserve providers that split the code and phrase across body lines, but never restore title
    // or sender metadata to this fallback when the caller can distinguish notification fields.
    if (crossLineText.isBlank() || extractor.shouldIgnore(crossLineText)) return null
    val cleanedCrossLineText = extractor.cleanup(crossLineText)
    if (cleanedCrossLineText.isBlank()) return null
    return extractor.getCode(cleanedCrossLineText, false)?.takeIf { it.isNotBlank() }
  }
}
