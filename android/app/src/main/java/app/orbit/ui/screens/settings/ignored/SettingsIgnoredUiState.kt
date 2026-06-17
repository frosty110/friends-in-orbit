package app.orbit.ui.screens.settings.ignored

import androidx.compose.runtime.Immutable

/**
 * IGNORE-06 — view-state contract for the Settings → Ignored route.
 *
 * Sealed interface with three variants so the screen can branch declaratively
 * without nullable juggling: Loading is the structural [stateIn] initial value
 * (observable synchronously before the upstream Flow emits), Empty is the
 * "no ignored contacts" leaf state, Ready carries the sorted row list.
 *
 * Every variant is `@Immutable` so Compose can skip recomposition when the
 * enclosing state reference is unchanged (Pitfall 1 — stable equality keys).
 */
sealed interface SettingsIgnoredUiState {

    @Immutable data object Loading : SettingsIgnoredUiState

    @Immutable data class Ready(val ignored: List<IgnoredContactRow>) : SettingsIgnoredUiState

    @Immutable data object Empty : SettingsIgnoredUiState
}

/**
 * Pre-formatted row projection consumed by SettingsIgnoredScreen. The relative
 * label is computed inside the VM (not the UI) so the screen stays a pure
 * `state -> composable` projection — see project architecture conventions:
 * ViewModels never know about composables.
 *
 * `ignoredAtMs` is the millisecond timestamp; the screen does not currently
 * read it directly but it is preserved for stable list keys / future "Sort by
 * ignored time" UX without re-deriving the source-of-truth from the row.
 */
@Immutable
data class IgnoredContactRow(
    val id: Long,
    val name: String,
    val photoUri: String?,
    val ignoredAtMs: Long,
    val ignoredRelativeLabel: String,
)
