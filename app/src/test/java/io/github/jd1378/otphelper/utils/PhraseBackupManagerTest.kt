package io.github.jd1378.otphelper.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PhraseBackupManagerTest {
  @Test
  fun singleBackupRoundTripsAndDeduplicates() {
    val encoded =
        PhraseBackupManager.encodeSingle(
            PhraseListKind.SENSITIVE,
            listOf("code", "otp", "code", ""),
        )

    assertEquals(
        listOf("code", "otp"),
        PhraseBackupManager.decodeSingle(encoded, PhraseListKind.SENSITIVE),
    )
  }

  @Test
  fun singleImportAcceptsPlainText() {
    assertEquals(
        listOf("first", "second"),
        PhraseBackupManager.decodeSingle("first\nsecond\nfirst\n", PhraseListKind.IGNORED),
    )
  }

  @Test
  fun singleImportRejectsWrongKind() {
    val encoded = PhraseBackupManager.encodeSingle(PhraseListKind.CLEANUP, listOf("remove"))
    assertThrows(IllegalArgumentException::class.java) {
      PhraseBackupManager.decodeSingle(encoded, PhraseListKind.SENSITIVE)
    }
  }

  @Test
  fun completeBackupDecodesEveryList() {
    val decoded =
        PhraseBackupManager.decodeAll(
            """
            {
              "schema": "otphelper.phrases",
              "version": 1,
              "lists": {
                "sensitive_phrases": ["code"],
                "ignored_phrases": ["spam"],
                "cleanup_phrases": ["remove"]
              }
            }
            """.trimIndent()
        )

    assertEquals(listOf("code"), decoded.sensitive)
    assertEquals(listOf("spam"), decoded.ignored)
    assertEquals(listOf("remove"), decoded.cleanup)
  }
}
