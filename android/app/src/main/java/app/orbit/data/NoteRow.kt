package app.orbit.data

import app.orbit.ui.util.UiText

/**
 * In-memory note row — the UI-facing projection of a persisted `NoteEntity`.
 * Carries VM-pre-formatted timestamps so the NotesSection composable can
 * render relative ↔ absolute toggles without touching
 * `java.time.Instant.now()` (DOM-01 Clock-injection invariant).
 *
 * Room annotations (`@Entity`, `@PrimaryKey(autoGenerate = true)`) live on
 * `NoteEntity` under `app.orbit.data.entity/`; ViewModels map entity → row
 * when collecting the Flow out of `NoteRepository`. The mapper runs inside
 * the VM's `combine`, sourced from `clock.now()`.
 */
data class NoteRow(
    val id: Long = 0,
    val contactId: Long,
    val body: String,
    val createdAtMs: Long,
    /**
     * Pre-formatted by the VM mapper with [app.orbit.ui.util.formatRelative],
     * e.g. "14 days ago", "today". [UiText] so the words come from resources
     * (UX rubric 3.4); null only in fixtures that never show it.
     */
    val relativeTimestamp: UiText? = null,
    /**
     * Pre-formatted by the VM mapper with [app.orbit.ui.util.formatAbsolute],
     * e.g. "Mar 14 · 2:14pm": numbers and the locale's month name, no words.
     */
    val absoluteTimestamp: String = "",
)
