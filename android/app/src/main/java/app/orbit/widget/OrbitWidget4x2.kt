// android/app/src/main/java/app/orbit/widget/OrbitWidget4x2.kt
//
// WIDGET-02 — 4×2 home-screen widget: static three-column layout.
//
// Glance 1.1.1 has no LazyRow or horizontal scroll primitive. The 4×2 ships
// static-only — primary card left, two alternative cards stacked right.
// Swipe-between is deferred to v1.1. This is the locked fallback, not a scope
// reduction.
//
// FQN is permanent post-release: app.orbit.widget.OrbitWidget4x2
// FQN is permanent post-release: app.orbit.widget.OrbitWidget4x2Receiver
// NEVER rename either class — existing placed widgets break silently on FQN
// change.
//
// provideGlance reads minimalMode once via .first() before entering
// provideContent — widgets are one-shot renders; reading inside the composable
// would require a collected Flow and break the stateless-widget contract.
package app.orbit.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.state.PreferencesGlanceStateDefinition
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import app.orbit.ui.theme.OrbitWidgetTheme

class OrbitWidget4x2 : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entry = EntryPointAccessors.fromApplication(
            context,
            WidgetEntryPoint::class.java,
        )
        // One-shot snapshot — never keep a live Flow open in provideGlance.
        val data = entry.widgetSurfaceUseCase().invoke()
        // Read minimal mode before provideContent — one-shot, not observed inside
        // the composable (WIDGET-04).
        val minimalMode = entry.appPrefs().minimalModeEnabled.first()

        provideContent {
            OrbitWidgetTheme {
                WidgetBody4x2(
                    data = data,
                    minimalMode = minimalMode,
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
 * The actual rendering delegates to [WidgetBody4x2] in [OrbitWidget4x2.provideGlance].
 */
sealed class Widget4x2State {
    /**
     * Layout seam (review WR-03): true when the body renders a single
     * full-width pane — no divider, no alternatives column. Mirrors the
     * `alternatives.isEmpty()` branch in [OrbitWidget4x2Content]; keep the
     * two predicates in sync.
     */
    abstract val isFullWidth: Boolean

    /**
     * Rendered when [app.orbit.domain.usecase.WidgetSurfaceData.primary] is null.
     * The widget shows "No one due" full-width and a tap opens Home.
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
 * [avatarIsMinimal] drives [ContactAvatar] to show the silhouette in minimal mode.
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
            primaryDisplayedName = if (minimalMode) "Contact" else data.primary.displayName,
            primaryAvatarIsMinimal = minimalMode,
            alternatives = data.alternatives.map { contact ->
                AlternativeState(
                    displayedName = if (minimalMode) "Contact" else contact.displayName,
                    avatarIsMinimal = minimalMode,
                )
            },
        )
    }
