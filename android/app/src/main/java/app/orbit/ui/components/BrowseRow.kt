package app.orbit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.orbit.data.Contact
import app.orbit.ui.theme.OrbitTheme

/**
 * Reusable row used by Browse + Global Search.
 *
 * Visual contract (BROWSE-01 + queue-order merge):
 *   - Position-number column (24dp min-width) → Avatar (44dp) → Column(name h3 +
 *     #19 due-dot/status word + meta last-call) → trailing phone icon
 *   - Position number renders blank when [queuePosition] is null (GlobalSearch
 *     consumers + Browse's "Other members" section); default preserves the legacy
 *     call site. Accent when [isHead] (queue head), else fgMuted.
 *   - The position column is independent of the #19 due-dot — a row can show a
 *     queue position AND a due dot at once.
 *   - Row min-height = 48dp (tap target floor — rules.md §Design 3)
 *   - Hairline divider via parent (BrowseListScreen draws between rows)
 *
 * 2026-06-09 #19 — orientation additions (all default-valued so the Global
 * Search call site is untouched):
 *   - [due] renders the spec'd quiet due dot next to the name
 *     (features/browse/README.md:23,34 — accent token, dot not badge).
 *   - [statusLabel] ("Paused" / "Ignored") renders a small muted status word
 *     after the name and mutes the name itself.
 *   - [showCallMeta] = false suppresses the last-call line entirely — when
 *     READ_CALL_LOG is denied, "Never called" would be a false claim.
 *
 * Curtain (PRIV-03 / CORE-08): contact name reads "Contact" when
 * [LocalPrivacyCurtain] `.current` is true.
 *
 * Accessibility (UI-SPEC §BROWSE-05): two CustomAccessibilityActions —
 * "Call {FirstName}" and "Open details". Row body tap opens detail; trailing
 * phone icon tap dials. Two distinct hit areas, each ≥ `spacing.tapMin`.
 */
@Composable
fun BrowseRow(
    contact: Contact,
    onTap: () -> Unit,
    onDial: () -> Unit,
    modifier: Modifier = Modifier,
    due: Boolean = contact.due,
    statusLabel: String? = null,
    showCallMeta: Boolean = true,
    queuePosition: Int? = null,   // null → render blank 24dp column (GlobalSearch + "Other members" rows)
    isHead: Boolean = false,      // queue head (position 1) → accent color on the position number
) {
    val curtain = LocalPrivacyCurtain.current
    val displayName = if (curtain) "Contact" else contact.name
    val firstName = displayName.substringBefore(' ').ifBlank { displayName }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x3),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = OrbitTheme.spacing.tapMin)
            .clickable(onClick = onTap)
            .padding(
                horizontal = OrbitTheme.spacing.x5,
                vertical = OrbitTheme.spacing.x3,
            )
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(label = "Call $firstName") {
                        onDial(); true
                    },
                    CustomAccessibilityAction(label = "Open details") {
                        onTap(); true
                    },
                )
            },
    ) {
        // Documented dp-token exception (project convention, Code rule 2): `widthIn(min = 24.dp)`
        // is the sole raw `.dp` in this file. Rationale: `OrbitSpacing` exposes a 4dp grid
        // (x1..x10) plus `tapMin = 48dp`; none describe a "single-glyph numeric column width",
        // and 24dp keeps single-digit and 99-cap two-digit positions aligned without a one-off
        // token. Blank when [queuePosition] is null so non-queued / GlobalSearch rows still align.
        Text(
            text = queuePosition?.let { "$it" } ?: "",
            style = OrbitTheme.type.statValue,
            color = if (isHead) OrbitTheme.colors.accent else OrbitTheme.colors.fgMuted,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = 24.dp),
        )
        Avatar(name = displayName, size = 44.dp)
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x2),
            ) {
                Text(
                    text = displayName,
                    // Paused/ignored rows read muted — visually distinct without
                    // shouting (#19).
                    color = if (statusLabel != null) OrbitTheme.colors.fgMuted else OrbitTheme.colors.fg,
                    style = OrbitTheme.type.h3,
                )
                if (due && statusLabel == null) {
                    // Quiet due dot — accent token per features/browse/README.md:34.
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(OrbitTheme.shapes.full)
                            .background(OrbitTheme.colors.accent)
                            .semantics { contentDescription = "Due" },
                    )
                }
                if (statusLabel != null) {
                    Text(
                        text = statusLabel,
                        style = OrbitTheme.type.meta,
                        color = OrbitTheme.colors.fgSubtle,
                    )
                }
            }
            if (showCallMeta) {
                val lastCalled = contact.lastCalledLabel
                val secondaryText = if (lastCalled.isBlank()) "Never called" else "Last call: $lastCalled"
                Text(
                    text = secondaryText,
                    style = OrbitTheme.type.meta,
                    color = OrbitTheme.colors.fgMuted,
                )
            }
        }
        // Trailing phone icon — separate tap target ≥48dp.
        Box(
            modifier = Modifier
                .defaultMinSize(
                    minWidth = OrbitTheme.spacing.tapMin,
                    minHeight = OrbitTheme.spacing.tapMin,
                )
                .clickable(onClick = onDial),
            contentAlignment = Alignment.Center,
        ) {
            PhIcon(
                name = "phone-call",
                size = 22.dp,
                tint = OrbitTheme.colors.accent,
            )
        }
    }
}

// 2026-06-09 #19 — preview fixtures for the new due / status / no-meta states.
private fun previewContact(name: String, lastCalled: String) = Contact(
    id = "preview-$name",
    name = name,
    phone = "+1 555 0100",
    lastCalledLabel = lastCalled,
    avgLengthLabel = "",
    pickupRateLabel = "",
    totalCalls = 0,
    due = false,
    listIds = emptyList(),
    bestWindowLabel = "",
    heat = FloatArray(24) { 0f },
    history = emptyList(),
    notes = emptyList(),
    patternNote = "",
)

@PreviewLightDark
@Composable
private fun BrowseRowPreview() {
    OrbitTheme {
        Column(modifier = Modifier.background(OrbitTheme.colors.bg)) {
            BrowseRow(
                contact = previewContact("Avery Quinn", "3 days ago"),
                onTap = {},
                onDial = {},
                due = true,
                queuePosition = 1,
                isHead = true,
            )
            BrowseRow(
                contact = previewContact("Sam Patel", "2 months ago"),
                onTap = {},
                onDial = {},
                statusLabel = "Paused",
            )
            BrowseRow(
                contact = previewContact("Jordan Lee", ""),
                onTap = {},
                onDial = {},
                showCallMeta = false,
            )
        }
    }
}
