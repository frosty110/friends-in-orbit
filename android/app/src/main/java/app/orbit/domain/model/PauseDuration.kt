package app.orbit.domain.model

import java.time.Duration

/**
 * User-selectable durations for [PauseContactUseCase] (DOM-08). A sealed class (not an
 * enum) so `Indefinite` can carry `null` — `PauseContactUseCase` maps `null` to the
 * named sentinel `INDEFINITE_PAUSE_SENTINEL = Instant.parse("9999-12-31T23:59:59Z")`
 * at write time. NOT `Instant.MAX` — Room's Long-based InstantTypeConverter
 * truncates `Instant.MAX` into nonsense on round-trip.
 *
 * Callers MUST import `app.orbit.domain.model.PauseDuration`.
 *
 * @property duration  Length of the pause. `null` means indefinite; the use case maps
 *                     this to the 9999 sentinel at write time.
 *
 * How a pause reads ("Paused Sam for 1 week", "Paused 3 people indefinitely")
 * is copy, so it lives in string resources, one whole sentence per duration,
 * built in one place: `app.orbit.ui.util.pausedSnackbar`. Until 2026-10-05
 * this class carried an English `snackbarPhrase` ("for 1 week",
 * "indefinitely") for that sentence; a whole sentence per duration keeps the
 * same guarantee (no "for indefinitely") in every language.
 */
sealed class PauseDuration(
    val duration: Duration?,
) {
    data object OneWeek : PauseDuration(Duration.ofDays(7))
    data object OneMonth : PauseDuration(Duration.ofDays(30))
    data object Indefinite : PauseDuration(null)
}
