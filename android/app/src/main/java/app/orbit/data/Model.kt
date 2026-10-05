package app.orbit.data

import androidx.compose.runtime.Immutable
import app.orbit.ui.util.UiText

// v1 domain model. Mirrors PRD §Feature Specification and §Contact Data Model.
// Kept as plain immutable data classes so they can be shared by Compose state
// and a future Room layer with minimal friction.
//
// Labels a person reads are UiText (UX rubric 3.4): the mappers that fill them
// run without a Context, and the screen resolves them in the user's language.
// The unused v0 `RuleTemplate` enum and `OrbitList` class, which carried
// English template names in Kotlin, were removed on 2026-10-05; templates are
// RuleTemplateEntity rows and lists are ListEntity rows.

@Immutable
enum class ChipTone { Terracotta, Sage, Amber, Brick, Stone }

@Immutable
enum class CallDirection { Outgoing, Incoming }

@Immutable
data class CallEntry(
    val direction: CallDirection,
    val relativeWhen: UiText,   // "11 days ago" (formatRelative)
    val lengthLabel: UiText,    // "14 min" (formatDuration)
)

@Immutable
data class Note(
    val relativeWhen: String,
    val body: String,
)

@Immutable
data class Contact(
    val id: String,
    val name: String,
    val phone: String,
    val lastCalledLabel: UiText?,  // "11 days ago"; null when never called
    val avgLengthLabel: UiText?,   // "14 min"; null when no call was measured
    val pickupRateLabel: String,   // "82%"
    val totalCalls: Int,
    val due: Boolean,
    val listIds: List<String>,
    val bestWindowLabel: UiText?,  // "Evenings"; null until there is enough history
    val heat: FloatArray,          // 24 hourly pickup rates 0..1
    val history: List<CallEntry>,
    val notes: List<Note>,
    val patternNote: String,       // "Usually calls in the evening..."
    val photoUri: String? = null,  // Coil AsyncImage on Card + Detail
) {
    // Avoid auto-generated equals pitfalls on FloatArray — good enough for UI state.
    override fun equals(other: Any?) = this === other || (other is Contact && id == other.id)
    override fun hashCode() = id.hashCode()
}
