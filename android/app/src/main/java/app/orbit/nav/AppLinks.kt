package app.orbit.nav

import android.content.Context
import android.content.Intent
import app.orbit.MainActivity
import app.orbit.domain.usecase.WidgetSurfaceData

/**
 * How the surfaces outside the app open a place inside it: a nudge's body, a
 * person on a widget, and the launcher shortcuts all land through
 * [MainActivity], which hands the route to `OrbitNavHost` (D-17).
 *
 * One builder so the three agree on the extra, the flags and the identity.
 */
object AppLinks {

    /** Intent extra carrying a [Routes] path for [MainActivity] to open (D-17). */
    const val EXTRA_NAVIGATE_TO = "app.orbit.extra.NAVIGATE_TO"

    /**
     * LAUNCH-01: the launcher shortcut actions. They carry no route because
     * "next" is only known at the moment of the tap; [MainActivity] resolves
     * them, and only once onboarding is done.
     */
    const val ACTION_CALL_NEXT = "app.orbit.action.CALL_NEXT"
    const val ACTION_SEARCH = "app.orbit.action.SEARCH"

    /**
     * An intent that opens [route] in Orbit. If Orbit is already open it is
     * brought forward and given the route ([Intent.FLAG_ACTIVITY_SINGLE_TOP],
     * [MainActivity.onNewIntent]) instead of stacking a second copy.
     *
     * Android compares PendingIntents without their extras, so three people on
     * one widget opening three lists would otherwise all open the first; the
     * route doubles as the intent's identifier to keep them apart.
     */
    fun openRoute(context: Context, route: String): Intent =
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NAVIGATE_TO, route)
            identifier = route
        }

    /**
     * LAUNCH-01: where "Call next" lands. The person the widgets show first,
     * on the deck of the list that surfaced them, where the labelled Call
     * button is one tap away. Null when nobody is next; Orbit then opens as
     * it would from its icon.
     *
     * The shortcut never dials by itself: a call reaches another person and
     * cannot be undone, so it happens only from a control that shows who
     * (CARD-01).
     */
    fun callNextRoute(next: WidgetSurfaceData): String? {
        val person = next.primary ?: return null
        val listId = next.listIdByContactId[person.id] ?: return null
        return Routes.card(listId.toString())
    }
}
