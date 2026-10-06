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
     * The intent every outside surface starts Orbit with. If Orbit is already
     * open it is brought forward and handed the intent
     * ([Intent.FLAG_ACTIVITY_SINGLE_TOP], [MainActivity.onNewIntent]) instead
     * of a second MainActivity being stacked on the first; anything above it
     * in the task is cleared ([Intent.FLAG_ACTIVITY_CLEAR_TOP]). [openRoute]
     * and the launcher shortcuts both build on it, so a warm tap behaves the
     * same whichever surface it came from; until 2026-10-06 the shortcuts set
     * no flags, and what a warm "Call next" did depended on the launcher.
     */
    fun launch(context: Context): Intent =
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

    /**
     * An intent that opens [route] in Orbit, on top of [launch]'s flags.
     *
     * Android compares PendingIntents without their extras, so three people on
     * one widget opening three lists would otherwise all open the first; the
     * route doubles as the intent's identifier to keep them apart.
     */
    fun openRoute(context: Context, route: String): Intent =
        launch(context).apply {
            putExtra(EXTRA_NAVIGATE_TO, route)
            identifier = route
        }

    /**
     * Where a launch intent lands, decided before anything is read: a route
     * from a nudge or a widget ([Open]), a shortcut still to be resolved
     * ([CallNext], [Search]), or nothing in particular ([Nothing]), when the
     * app opens where it is.
     */
    sealed interface Landing {
        /** A route from [EXTRA_NAVIGATE_TO], handed to the nav host as is. */
        data class Open(val route: String) : Landing

        /** The "Call next" shortcut: resolve with [callNextRoute] at the moment of the tap. */
        data object CallNext : Landing

        /** The "Search" shortcut: [Routes.GlobalSearch]. */
        data object Search : Landing

        /** A plain launch, an unrelated action, or a shortcut before onboarding. */
        data object Nothing : Landing
    }

    /**
     * The pure half of [MainActivity]'s routing, shared by its cold start
     * (`onCreate`) and its warm re-entry (`onNewIntent`).
     *
     * A route in the extra wins over any action, so a nudge or widget tap
     * never waits on anything. The shortcut actions exist from install, so
     * they wait for onboarding to be done ([onboardingComplete]): before that
     * there is no list to open, and a search screen over the welcome flow
     * would strand the user. Only those two branches make the caller read the
     * flag, and only [Landing.CallNext] makes it read who is next.
     */
    fun landingFor(intent: Intent?, onboardingComplete: Boolean): Landing {
        intent ?: return Landing.Nothing
        intent.getStringExtra(EXTRA_NAVIGATE_TO)?.let { return Landing.Open(it) }
        if (!onboardingComplete) return Landing.Nothing
        return when (intent.action) {
            ACTION_CALL_NEXT -> Landing.CallNext
            ACTION_SEARCH -> Landing.Search
            else -> Landing.Nothing
        }
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
