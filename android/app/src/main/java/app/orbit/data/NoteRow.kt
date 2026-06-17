package app.orbit.data

/**
 * In-memory note row — the UI-facing projection of a persisted `NoteEntity`.
 * Carries VM-pre-formatted timestamp strings so the NotesSection composable can
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
    /** Pre-formatted by VM mapper — e.g., "14 days ago", "today". */
    val relativeTimestamp: String = "",
    /** Pre-formatted by VM mapper — e.g., "Mar 14 · 2:14 pm". */
    val absoluteTimestamp: String = "",
)
