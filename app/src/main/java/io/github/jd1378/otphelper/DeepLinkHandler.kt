package io.github.jd1378.otphelper

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.Stable
import androidx.core.net.toUri
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

@Singleton
@Stable
class DeepLinkHandler @Inject constructor() {
  val event = MutableStateFlow<Event>(Event.None)

  fun handleDeepLink(intent: Intent?) {
    if (intent == null) return

    val editedIntent = Intent(intent)
    editedIntent.flags =
        (editedIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK.inv()) or
            Intent.FLAG_ACTIVITY_SINGLE_TOP or
            Intent.FLAG_ACTIVITY_CLEAR_TOP
    event.update { Event.NavigateWithDeepLink(editedIntent) }
  }

  fun consumeEvent() {
    event.update { Event.None }
  }
}

sealed interface Event {
  @Stable data class NavigateWithDeepLink(val intent: Intent) : Event

  data object None : Event
}

const val OTPHELPER_APP_SCHEME = "otphelper"

internal fun buildDeepLinkIntent(
    context: Context,
    route: String,
    navArgValue: String? = null,
): Intent {
  var baseUri = "$OTPHELPER_APP_SCHEME://$route"
  if (!navArgValue.isNullOrEmpty()) {
    baseUri += "/$navArgValue"
  }
  return Intent(context, MainActivity::class.java)
      .setAction(Intent.ACTION_VIEW)
      .setData(baseUri.toUri())
      .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
}

fun getDeepLinkPendingIntent(
  context: Context,
  route: String,
  navArgValue: String? = null,
): PendingIntent {
  val routeIntent = buildDeepLinkIntent(context, route, navArgValue)
  val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

  val options = ActivityOptions.makeBasic()
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
    options.pendingIntentCreatorBackgroundActivityStartMode =
        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
  }
  return PendingIntent.getActivity(
      context,
      0,
      routeIntent,
      flags,
      options.toBundle(),
  )
}
