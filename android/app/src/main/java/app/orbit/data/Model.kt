package app.orbit.data

import androidx.compose.runtime.Immutable

// v1 domain model. Mirrors PRD §Feature Specification and §Contact Data Model.
// Kept as plain immutable data classes so they can be shared by Compose state
// and a future Room layer with minimal friction.

@Immutable
enum class RuleTemplate(val label: String, val subtitle: String, val iconAsset: String) {
    RoundRobin("Round robin",       "Rotate through everyone evenly",       "shuffle"),
    Recency("Recency",               "Longest-silent surfaces first",        "clock-counter-clockwise"),
    SpacedRepetition("Spaced repetition", "Interval grows if you keep in touch", "calendar-blank"),
    Manual("Manual",                 "You pick, no suggestions",             "list-bullets"),
}

@Immutable
enum class ChipTone { Terracotta, Sage, Amber, Brick, Stone }

@Immutable
data class OrbitList(
    val id: String,
    val name: String,
    val memberCount: Int,
    val rule: RuleTemplate,
    val ruleSummary: String,        // "every 2w", "weekly", etc.
    val dueCount: Int,
    val tone: ChipTone,
    val activeStartHour: Int? = null,  // 0..23
    val activeEndHour: Int? = null,
    val notifyEnabled: Boolean = true,
    val intervalDays: Int = 14,
)

@Immutable
enum class CallDirection { Outgoing, Incoming }

@Immutable
data class CallEntry(
    val direction: CallDirection,
    val relativeWhen: String,   // "11 days ago"
    val lengthLabel: String,    // "14 min"
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
    val lastCalledLabel: String,   // "11 days ago"
    val avgLengthLabel: String,    // "14 min"
    val pickupRateLabel: String,   // "82%"
    val totalCalls: Int,
    val due: Boolean,
    val listIds: List<String>,
    val bestWindowLabel: String,
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
