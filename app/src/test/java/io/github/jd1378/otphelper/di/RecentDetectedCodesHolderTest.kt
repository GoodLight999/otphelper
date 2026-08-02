package io.github.jd1378.otphelper.di

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentDetectedCodesHolderTest {
  @Test
  fun differentTextShapesFromTwoBackendsAreDeduplicated() {
    val holder = RecentDetectedCodesHolder()
    val now = 10_000L

    assertFalse(holder.isDuplicate("com.example|123456|Your code is 123456", now))
    assertTrue(
        holder.isDuplicate(
            "com.example|123456|Your code is 123456\nYour code is 123456",
            now + 100,
        ))
  }

  @Test
  fun differentCodesAreNotDeduplicated() {
    val holder = RecentDetectedCodesHolder()
    val now = 20_000L

    assertFalse(holder.isDuplicate(RecentDetectedCodesHolder.signature("com.example", "111111"), now))
    assertFalse(
        holder.isDuplicate(RecentDetectedCodesHolder.signature("com.example", "222222"), now + 100))
  }

  @Test
  fun duplicateWindowExpires() {
    val holder = RecentDetectedCodesHolder()
    val now = 30_000L
    val signature = RecentDetectedCodesHolder.signature("com.example", "123456")

    assertFalse(holder.isDuplicate(signature, now))
    assertFalse(holder.isDuplicate(signature, now + DUPLICATE_DETECTION_WINDOW_MS + 1))
  }
}
