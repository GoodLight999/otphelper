package io.github.jd1378.otphelper.di

import androidx.compose.runtime.Stable
import javax.inject.Inject
import javax.inject.Singleton

// Window during which an identical package/code pair is treated as a duplicate. The standard
// NotificationListener and Accessibility fallback can expose the same notification text in
// different shapes, so including the full text in the signature would allow double copies.
const val DUPLICATE_DETECTION_WINDOW_MS = 5_000L

@Singleton
@Stable
class RecentDetectedCodesHolder @Inject constructor() {
  private val recentSignatures = HashMap<String, Long>()

  @Synchronized
  fun isDuplicate(signature: String, now: Long): Boolean {
    pruneExpired(now)
    val lastSeen = recentSignatures[signature]
    recentSignatures[signature] = now
    return lastSeen != null && now - lastSeen <= DUPLICATE_DETECTION_WINDOW_MS
  }

  private fun pruneExpired(now: Long) {
    val iterator = recentSignatures.iterator()
    while (iterator.hasNext()) {
      if (now - iterator.next().value > DUPLICATE_DETECTION_WINDOW_MS) {
        iterator.remove()
      }
    }
  }

  companion object {
    fun signature(packageName: String, code: String): String = "$packageName|$code"
  }
}
