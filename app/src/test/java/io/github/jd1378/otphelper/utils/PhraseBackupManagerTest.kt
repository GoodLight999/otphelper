package io.github.jd1378.otphelper.utils

import java.io.StringReader
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
  fun boundedReaderAcceptsMaximumSizedInput() {
    val input = "x".repeat(PhraseBackupManager.MAX_FILE_CHARS)
    assertEquals(input, PhraseBackupManager.readBounded(StringReader(input)))
  }

  @Test
  fun boundedReaderRejectsOversizedInputBeforeReturningIt() {
    val input = "x".repeat(PhraseBackupManager.MAX_FILE_CHARS + 1)
    assertThrows(IllegalArgumentException::class.java) {
      PhraseBackupManager.readBounded(StringReader(input))
    }
  }

  @Test
  fun singleImportAcceptsPlainText() {
    assertEquals(
        listOf("first", "second"),
        PhraseBackupManager.decodeSingle("first\nsecond\nfirst\n", PhraseListKind.IGNORED),
    )
  }

  @Test
  fun singleImportAcceptsJsonStringArray() {
    assertEquals(
        listOf("first", "second"),
        PhraseBackupManager.decodeSingle("[\"first\", \"second\"]", PhraseListKind.CLEANUP),
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
  fun singleImportRejectsNonStringValues() {
    assertThrows(IllegalArgumentException::class.java) {
      PhraseBackupManager.decodeSingle("[\"valid\", 123]", PhraseListKind.SENSITIVE)
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

  @Test
  fun completeBackupRejectsUnknownSchema() {
    assertThrows(IllegalArgumentException::class.java) {
      PhraseBackupManager.decodeAll(
          """
          {
            "schema": "some.other.app",
            "version": 1,
            "lists": {
              "sensitive_phrases": [],
              "ignored_phrases": [],
              "cleanup_phrases": []
            }
          }
          """.trimIndent()
      )
    }
  }

  @Test
  fun completeBackupRejectsFutureVersion() {
    assertThrows(IllegalArgumentException::class.java) {
      PhraseBackupManager.decodeAll(
          """
          {
            "schema": "otphelper.phrases",
            "version": 999,
            "lists": {
              "sensitive_phrases": [],
              "ignored_phrases": [],
              "cleanup_phrases": []
            }
          }
          """.trimIndent()
      )
    }
  }

  @Test
  fun completeBackupRejectsMissingList() {
    assertThrows(IllegalArgumentException::class.java) {
      PhraseBackupManager.decodeAll(
          """
          {
            "schema": "otphelper.phrases",
            "version": 1,
            "lists": {
              "sensitive_phrases": [],
              "ignored_phrases": []
            }
          }
          """.trimIndent()
      )
    }
  }
}
