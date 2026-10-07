package app.orbit.ui.screens.settings.ignored

import androidx.compose.runtime.Immutable
import app.orbit.ui.util.UiText

/**
 * IGNORE-06: view-state contract for the Settings → Ignored route.
 *
 * Sealed interface with four variants so the screen can branch declaratively
 * without nullable juggling: Loading is the structural [stateIn] initial value
 * (observable synchronously before the upstream Flow emits; the screen draws
 * a list skeleton), Empty is the "No one ignored" leaf state, Ready carries
 * the sorted row list, and Error means the list could not be read (shown
 * with Try again, rubric D6).
 *
 * Every variant is `@Immutable` so Compose can skip recomposition when the
 * enclosing state reference is unchanged (Pitfall 1, stable equality keys).
 */
sealed interface SettingsIgnoredUiState {

    @Immutable data object Loading : SettingsIgnoredUiState

    @Immutable data class Ready(val ignored: List<IgnoredContactRow>) : SettingsIgnoredUiState

    @Immutable data object Empty : SettingsIgnoredUiState

    /** The ignored list could not be read; the screen offers Try again. */
    @Immutable data object Error : SettingsIgnoredUiState
}

/**
 * Pre-formatted row projection consumed by SettingsIgnoredScreen. The relative
 * label is computed inside the VM (not the UI) so the screen stays a pure
 * `state -> composable` projection; see project architecture conventions:
 * ViewModels never know about composables.
 *
 * `ignoredRelativeLabel` is [UiText] ("Ignored {today}"), resolved by the screen.
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
    val ignoredRelativeLabel: UiText,
)
