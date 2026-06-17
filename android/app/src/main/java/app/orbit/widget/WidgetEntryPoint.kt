package app.orbit.widget

import app.orbit.data.AppPrefs
import app.orbit.domain.usecase.WidgetSurfaceUseCase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * WIDGET-03 — Hilt entry point that bridges the [SingletonComponent]
 * DI graph into `GlanceAppWidget.provideGlance`, which runs outside the
 * ViewModel/Activity hierarchy and cannot receive `@Inject` bindings through
 * the normal component chain.
 *
 * Consumed via:
 * ```kotlin
 * val entry = EntryPointAccessors.fromApplication(
 *     context, WidgetEntryPoint::class.java,
 * )
 * val data = entry.widgetSurfaceUseCase()()
 * val prefs = entry.appPrefs()
 * ```
 *
 * T-11-01 (Threat Register): the entry point exposes exactly two methods —
 * [widgetSurfaceUseCase] and [appPrefs]. No DAO, no DatabaseKeyProvider, no
 * repository is exposed through this interface (avoiding the
 * "directly reading Room from a widget" anti-pattern). Minimal surface =
 * minimal information-disclosure risk.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    /** WIDGET-01/02 — cross-list contact source for widget rendering. */
    fun widgetSurfaceUseCase(): WidgetSurfaceUseCase

    /** WIDGET-04 — minimal-mode masking flag read at render time. */
    fun appPrefs(): AppPrefs
}
