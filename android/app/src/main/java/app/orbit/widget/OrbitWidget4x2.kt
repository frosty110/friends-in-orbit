// android/app/src/main/java/app/orbit/widget/OrbitWidget4x2.kt
//
// WIDGET-02: 4×2 home-screen widget, the lead person and up to two more.
//
// WIDGET-07: SizeMode.Responsive (WidgetBreakpoints.suggestions). Narrow, it
// is a one-person widget like "Next call"; at 4×2 the others are listed beside
// the lead; at 4×3 and up, below it with their own quiet call buttons.
// Glance 1.1.1 has no horizontal scroll, so the others are a static list, not
// a carousel. That stays the decision (widgets README, "Not in scope").
//
// FQN is permanent post-release: app.orbit.widget.OrbitWidget4x2
// FQN is permanent post-release: app.orbit.widget.OrbitWidget4x2Receiver
// NEVER rename either class — existing placed widgets break silently on FQN
// change.
//
// provideGlance reads everything once, before provideContent (see
// OrbitWidget2x2). Widgets are one-shot renders.
package app.orbit.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.state.PreferencesGlanceStateDefinition
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import app.orbit.ui.theme.OrbitWidgetTheme
import app.orbit.ui.theme.deviceAccentHue
import app.orbit.ui.theme.orbitWidgetAvatarTones
import app.orbit.ui.theme.orbitWidgetColorProviders
import app.orbit.ui.theme.themeSettingsSnapshot

class OrbitWidget4x2 : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    override val sizeMode = SizeMode.Responsive(WidgetBreakpoints.suggestions)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entry = EntryPointAccessors.fromApplication(
            context,
            WidgetEntryPoint::class.java,
        )
        // One-shot snapshot — never keep a live Flow open in provideGlance.
        val data = entry.widgetSurfaceUseCase().invoke()
        // Minimal mode + appearance, one-shot (WIDGET-04 / THEMING).
        val prefs = entry.appPrefs()
        val minimalMode = prefs.minimalModeEnabled.first()
        val themeSettings = prefs.themeSettingsSnapshot()
        val deviceHue = deviceAccentHue(context)
        val widgetColors = orbitWidgetColorProviders(themeSettings, deviceHue)
        val people = widgetPeople(
            context = context,
            data = data,
            max = 3,
            minimalMode = minimalMode,
            tones = orbitWidgetAvatarTones(themeSettings, deviceHue),
        )

        provideContent {
            OrbitWidgetTheme(colors = widgetColors) {
                SuggestionsBody(
                    people = people,
                    onOpenApp = actionStartActivity(openHomeIntent(context)),
                )
            }
        }
    }
}

// ─── Pure testable selection helper ──────────────────────────────────────────

/**
 * Sealed state representing the 4×2 widget body selection result.
 *
 * This is the JVM-testable seam: [OrbitWidget4x2Test] and [MinimalModeTest]
 * call [selectWidget4x2State] and assert on these sealed values without
 * needing a real Glance composition or Robolectric context.
 *
 * The actual rendering is [SuggestionsBody], fed by [widgetPeople], which
 * applies the same masking.
 */
sealed class Widget4x2State {
    /**
     * Layout seam (review WR-03): true when the body renders a single
     * full-width pane: no divider, no alternatives column. Mirrors
     * [suggestionsLayout], which gives the lead the whole width when there is
     * nobody else; keep the two predicates in sync.
     */
    abstract val isFullWidth: Boolean

    /**
     * Rendered when [app.orbit.domain.usecase.WidgetSurfaceData.primary] is null.
     * The widget shows "All quiet for now." (WIDGET-10) and a tap opens Home.
     */
    object Empty : Widget4x2State() {
        override val isFullWidth: Boolean = true
    }

    /**
     * Rendered when a contact is available.
     *
     * [primaryDisplayedName] is "Contact" in minimal mode, real name otherwise (WIDGET-04).
     * [primaryAvatarIsMinimal] is true when minimalMode → silhouette shown for primary.
     * [alternatives] is 0..2 entries with masked names in minimal mode (WIDGET-04).
     */
    data class Populated(
        val primaryDisplayedName: String,
        val primaryAvatarIsMinimal: Boolean,
        val alternatives: List<AlternativeState>,
    ) : Widget4x2State() {
        /** Full-width when there are no alternatives (review WR-03). */
        override val isFullWidth: Boolean get() = alternatives.isEmpty()
    }
}

/**
 * Per-alternative masking state for the 4×2 widget.
 * [displayedName] is "Contact" in minimal mode (WIDGET-04), real name otherwise.
 * [avatarIsMinimal] means the widget shows a silhouette, not a face, in minimal mode.
 */
data class AlternativeState(
    val displayedName: String,
    val avatarIsMinimal: Boolean,
)

/**
 * Pure selection function mapping [app.orbit.domain.usecase.WidgetSurfaceData]
 * + [minimalMode] to the [Widget4x2State] the composable renders.
 *
 * Used by [OrbitWidget4x2Test] and [MinimalModeTest] for JVM-only assertions.
 */
fun selectWidget4x2State(
    data: app.orbit.domain.usecase.WidgetSurfaceData,
    minimalMode: Boolean,
): Widget4x2State =
    if (data.primary == null) {
        Widget4x2State.Empty
    } else {
        Widget4x2State.Populated(
            primaryDisplayedName = if (minimalMode) MINIMAL_NAME else data.primary.displayName,
            primaryAvatarIsMinimal = minimalMode,
            alternatives = data.alternatives.map { contact ->
                AlternativeState(
                    displayedName = if (minimalMode) MINIMAL_NAME else contact.displayName,
                    avatarIsMinimal = minimalMode,
                )
            },
        )
    }
