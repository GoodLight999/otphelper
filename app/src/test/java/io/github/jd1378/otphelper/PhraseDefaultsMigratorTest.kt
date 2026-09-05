package io.github.jd1378.otphelper

import io.github.jd1378.otphelper.utils.CodeExtractorDefaults
import io.github.jd1378.otphelper.utils.PhraseDefaultsMigrator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PhraseDefaultsMigratorTest {
  @Test
  fun untouchedLegacy1205DefaultsUpgradeToCurrentProfile() {
    val before =
        UserSettings.newBuilder()
            .addAllSensitivePhrases(PhraseDefaultsMigrator.legacySensitiveV1205)
            .addAllIgnoredPhrases(PhraseDefaultsMigrator.legacyIgnoredV1205)
            .addAllCleanupPhrases(PhraseDefaultsMigrator.legacyCleanup)
            .build()

    val after = PhraseDefaultsMigrator.migrate(before)

    assertEquals(CodeExtractorDefaults.sensitivePhrases, after.sensitivePhrasesList)
    assertEquals(CodeExtractorDefaults.ignoredPhrases, after.ignoredPhrasesList)
    assertEquals(CodeExtractorDefaults.cleanupPhrases, after.cleanupPhrasesList)
    assertEquals(PhraseDefaultsMigrator.CURRENT_VERSION, after.phraseDefaultsVersion)
    assertTrue(after.sensitivePhrasesList.any { it.contains("MFA") })
    assertTrue(after.sensitivePhrasesList.any { it.contains("二段階認証") })
  }

  @Test
  fun untouchedUpstream1206DefaultsUpgradeToCurrentProfile() {
    val before =
        UserSettings.newBuilder()
            .addAllSensitivePhrases(PhraseDefaultsMigrator.legacySensitiveV1206)
            .addAllIgnoredPhrases(PhraseDefaultsMigrator.legacyIgnoredV1206)
            .addAllCleanupPhrases(PhraseDefaultsMigrator.legacyCleanup)
            .build()

    val after = PhraseDefaultsMigrator.migrate(before)

    assertEquals(CodeExtractorDefaults.sensitivePhrases, after.sensitivePhrasesList)
    assertEquals(CodeExtractorDefaults.ignoredPhrases, after.ignoredPhrasesList)
    assertEquals(PhraseDefaultsMigrator.CURRENT_VERSION, after.phraseDefaultsVersion)
  }

  @Test
  fun anySensitiveCustomizationIsPreservedExactly() {
    val custom = PhraseDefaultsMigrator.legacySensitiveV1205.toMutableList().apply { add("my-bank-token") }
    val before =
        UserSettings.newBuilder()
            .addAllSensitivePhrases(custom)
            .addAllIgnoredPhrases(PhraseDefaultsMigrator.legacyIgnoredV1205)
            .addAllCleanupPhrases(PhraseDefaultsMigrator.legacyCleanup)
            .build()

    val after = PhraseDefaultsMigrator.migrate(before)

    assertEquals(custom, after.sensitivePhrasesList)
    // Other untouched lists are still eligible for their own independent upgrade.
    assertEquals(CodeExtractorDefaults.ignoredPhrases, after.ignoredPhrasesList)
  }

  @Test
  fun clearedOrCustomOnlyListsRemainClearedOrCustom() {
    val before =
        UserSettings.newBuilder()
            .addAllIgnoredPhrases(listOf("my-private-ignore-rule"))
            .addAllCleanupPhrases(listOf("my-cleanup-rule"))
            .build()

    val after = PhraseDefaultsMigrator.migrate(before)

    assertTrue(after.sensitivePhrasesList.isEmpty())
    assertEquals(listOf("my-private-ignore-rule"), after.ignoredPhrasesList)
    assertEquals(listOf("my-cleanup-rule"), after.cleanupPhrasesList)
    assertEquals(PhraseDefaultsMigrator.CURRENT_VERSION, after.phraseDefaultsVersion)
  }

  @Test
  fun currentVersionMigrationIsIdempotentAndReturnsSameInstance() {
    val before =
        UserSettings.newBuilder()
            .setPhraseDefaultsVersion(PhraseDefaultsMigrator.CURRENT_VERSION)
            .addSensitivePhrases("custom")
            .build()

    val after = PhraseDefaultsMigrator.migrate(before)

    assertSame(before, after)
  }
}
