// android/app/src/main/java/app/orbit/widget/OrbitWidget2x2.kt
//
// WIDGET-01 — 2×2 home-screen widget.
//
// FQN is permanent post-release: app.orbit.widget.OrbitWidget2x2
// FQN is permanent post-release: app.orbit.widget.OrbitWidget2x2Receiver
// NEVER rename either class — existing placed widgets break silently on FQN
// change.
//
// provideGlance reads minimalMode once via .first() before entering
// provideContent — widgets are one-shot renders; reading inside the composable
// would require a collected Flow and break the stateless-widget contract
// (Pitfall 4).
package app.orbit.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.state.PreferencesGlanceStateDefinition
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import app.orbit.ui.theme.OrbitDarkMode
import app.orbit.ui.theme.OrbitThemeId
import app.orbit.ui.theme.OrbitWidgetTheme
import app.orbit.ui.theme.ThemeSettings
import app.orbit.ui.theme.orbitWidgetColorProviders

class OrbitWidget2x2 : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entry = EntryPointAccessors.fromApplication(
            context,
            WidgetEntryPoint::class.java,
        )
        // One-shot snapshot — never keep a live Flow open in provideGlance.
        val data = entry.widgetSurfaceUseCase().invoke()
        // Read minimal mode + appearance before provideContent — one-shot, not
        // observed inside the composable (Pitfall 4 / WIDGET-04 / THEMING).
        val prefs = entry.appPrefs()
        val minimalMode = prefs.minimalModeEnabled.first()
        val themeSettings = ThemeSettings(
            themeId = OrbitThemeId.fromKey(prefs.colorTheme.first()),
            darkMode = OrbitDarkMode.fromKey(prefs.darkMode.first()),
            accentHue = prefs.accentHue.first().let { if (it < 0) null else it },
        )
        val widgetColors = orbitWidgetColorProviders(themeSettings)

        provideContent {
            OrbitWidgetTheme(colors = widgetColors) {
                WidgetBody2x2(
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
 * Sealed state representing the 2×2 widget body selection result.
 *
 * This is the JVM-testable seam: [OrbitWidget2x2Test] and [MinimalModeTest]
 * call [selectWidget2x2State] and assert on these sealed values without
 * needing a real Glance composition or Robolectric context.
 *
 * The actual rendering delegates to [WidgetBody2x2] in [OrbitWidget2x2.provideGlance].
 */
sealed class Widget2x2State {
    /**
     * Rendered when [app.orbit.domain.usecase.WidgetSurfaceData.primary] is null.
     * The widget shows "No one due" and a tap opens Home.
     */
    object Empty : Widget2x2State()

    /**
     * Rendered when a contact is available.
     * [displayedName] is "Contact" in minimal mode, real name otherwise (WIDGET-04).
     * [avatarIsMinimal] is true when minimalMode → silhouette shown.
     */
    data class Contact(
        val displayedName: String,
        val avatarIsMinimal: Boolean,
    ) : Widget2x2State()
}

/**
 * Pure selection function mapping [app.orbit.domain.usecase.WidgetSurfaceData]
 * + [minimalMode] to the [Widget2x2State] the composable renders.
 *
 * Used by [OrbitWidget2x2Test] and [MinimalModeTest] for JVM-only assertions.
 */
fun selectWidget2x2State(
    data: app.orbit.domain.usecase.WidgetSurfaceData,
    minimalMode: Boolean,
): Widget2x2State =
    if (data.primary == null) {
        Widget2x2State.Empty
    } else {
        Widget2x2State.Contact(
            displayedName = if (minimalMode) "Contact" else data.primary.displayName,
            avatarIsMinimal = minimalMode,
        )
    }
