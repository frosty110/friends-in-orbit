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
 * @property duration  Length of the pause. `null` means indefinite — the use case maps
 *                     this to the 9999 sentinel at write time.
 * @property snackbarPhrase How the pause reads at the end of every "Paused …"
 *                     snackbar, single or bulk: "for 1 week", "for 1 month",
 *                     "indefinitely". It carries its own preposition because
 *                     "indefinitely" takes none; building "for {label}" at the
 *                     call site is what produced "Paused 3 contacts for
 *                     indefinitely". One owner, so the three pause paths cannot
 *                     word it differently again.
 */
sealed class PauseDuration(
    val duration: Duration?,
    val snackbarPhrase: String
) {
    data object OneWeek : PauseDuration(Duration.ofDays(7), "for 1 week")
    data object OneMonth : PauseDuration(Duration.ofDays(30), "for 1 month")
    data object Indefinite : PauseDuration(null, "indefinitely")
}
