package io.github.jd1378.otphelper

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeepLinkContractTest {
  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test
  fun generatedDeepLinkIntentIsExplicitAndStable() {
    val intent = buildDeepLinkIntent(context, "history_detail", "42")

    assertEquals(Intent.ACTION_VIEW, intent.action)
    assertEquals("otphelper://history_detail/42".toUri(), intent.data)
    assertEquals(ComponentName(context, MainActivity::class.java), intent.component)
    assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
    assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
    assertFalse(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
  }

  @Test
  fun handlerRemovesNewTaskWithoutDroppingNavigationFlags() {
    val original =
        Intent(Intent.ACTION_VIEW, "otphelper://settings".toUri()).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY,
        )
    val handler = DeepLinkHandler()

    handler.handleDeepLink(original)

    val edited = (handler.event.value as Event.NavigateWithDeepLink).intent
    assertFalse(edited.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    assertTrue(edited.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
    assertTrue(edited.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
    assertTrue(edited.flags and Intent.FLAG_ACTIVITY_NO_HISTORY != 0)
    assertEquals(original.data, edited.data)
  }
}
