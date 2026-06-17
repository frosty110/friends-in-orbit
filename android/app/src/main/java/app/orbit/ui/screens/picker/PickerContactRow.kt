package app.orbit.ui.screens.picker

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.orbit.ui.components.Avatar
import app.orbit.ui.components.LocalPrivacyCurtain
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.util.formatRelative
import coil.compose.AsyncImage
import java.time.Instant

/**
 * Single picker contact row (PICK-04).
 *
 * Layout: avatar (44dp) leading + Column(name h3 + 1-2 metadata lines) +
 * trailing Material3 Checkbox. Tap on the row toggles selection — the checkbox
 * itself is non-interactive ([androidx.compose.material3.Checkbox.onCheckedChange]
 * = null) so the row is the single tap target.
 *
 * Selected-row tint: [OrbitTheme.colors.accentTint] background — cluster-tier
 * accent, NOT the action-tier `accent` (per the per-screen accent budget).
 *
 * Privacy curtain (PRIV-03 / CORE-08): name reads "Contact" when
 * [LocalPrivacyCurtain] `.current` is true — same shape as
 * [app.orbit.ui.components.BrowseRow].
 *
 * Metadata format:
 *   Line 1 (call line):
 *     - callCount > 0  → "Last called {relative} · {N} {call|calls}"
 *     - callCount == 0 → literal "never called" (lowercase per PICK-04)
 *     - both absent    → "—" in fgSubtle (covered by callCount == 0 branch since
 *                         lastCallAt is null when callCount == 0)
 *   Line 2 (memberships, optional):
 *     - listNames.size in 1..3   → "In: ${listNames.joinToString(", ")}"
 *     - listNames.size > 3       → "In: A, B, C + N more"
 *     - empty                    → omit line entirely (no "In: none")
 *
 * "never called" lowercase first letter is a PICK-04 invariant. The
 * zero-count phrasing is forbidden everywhere in source.
 *
 * Long-press opens a small anchored action menu: "Ignore" for a
 * normal row (with the locked supporting copy "Hide {name} from Orbit. They
 * stay in your phone's contacts."), "Unignore" for an ignored row. Ignored
 * rows (visible only behind the "Show ignored" filter entry) render muted
 * with an "Ignored" tag in place of the checkbox and are NOT selectable —
 * tapping one opens the same menu, so the row never dead-ends. [onIgnore] /
 * [onUnignore] default to null so non-curation callers keep the plain
 * tap-to-select row.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PickerContactRow(
    contact: PickerContact,
    isSelected: Boolean,
    onToggle: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onIgnore: ((PickerContact) -> Unit)? = null,
    onUnignore: ((PickerContact) -> Unit)? = null,
) {
    val curtain = LocalPrivacyCurtain.current
    val displayName = if (curtain) "Contact" else contact.displayName
    val haptics = LocalHapticFeedback.current

    // The action available from the long-press menu (null = no menu at all).
    val menuAction: ((PickerContact) -> Unit)? =
        if (contact.isIgnored) onUnignore else onIgnore
    var menuExpanded by remember { mutableStateOf(false) }

    val callLine: String = if (contact.callCount == 0 || contact.lastCallAt == null) {
        "never called"
    } else {
        val rel = formatRelative(contact.lastCallAt)
        val callsWord = if (contact.callCount == 1) "call" else "calls"
        "Last called $rel · ${contact.callCount} $callsWord"
    }

    val membershipLine: String? = when {
        contact.listNames.isEmpty() -> null
        contact.listNames.size <= 3 -> "In: ${contact.listNames.joinToString(", ")}"
        else -> {
            val head = contact.listNames.take(3).joinToString(", ")
            val rest = contact.listNames.size - 3
            "In: $head + $rest more"
        }
    }

    val rowBackground = if (isSelected) OrbitTheme.colors.accentTint else Color.Transparent
    // Ignored rows read as parked, not gone — muted name, no checkbox.
    val nameColor = if (contact.isIgnored) OrbitTheme.colors.fgMuted else OrbitTheme.colors.fg

    Box(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x3),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = OrbitTheme.spacing.tapMin)
                .background(rowBackground)
                .combinedClickable(
                    onClick = {
                        if (contact.isIgnored) {
                            // No selection for ignored rows — surface the
                            // Unignore action instead of a dead tap.
                            if (menuAction != null) menuExpanded = true
                        } else {
                            onToggle(contact.contactId)
                        }
                    },
                    onLongClick = if (menuAction != null) {
                        {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            menuExpanded = true
                        }
                    } else {
                        null
                    },
                )
                .padding(
                    horizontal = OrbitTheme.spacing.x4,
                    vertical = OrbitTheme.spacing.x3,
                )
                .semantics { selected = isSelected },
        ) {
            // The photo is PII just like the name: under the
            // curtain the row falls back to initials derived from the masked
            // name, never the contact's face.
            if (!curtain && !contact.photoUri.isNullOrBlank()) {
                AsyncImage(
                    model = contact.photoUri,
                    contentDescription = null,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape),
                )
            } else {
                Avatar(name = displayName, size = 44.dp)
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    style = OrbitTheme.type.body,
                    color = nameColor,
                )
                Text(
                    text = callLine,
                    style = OrbitTheme.type.meta,
                    color = OrbitTheme.colors.fgMuted,
                )
                if (membershipLine != null) {
                    Text(
                        text = membershipLine,
                        style = OrbitTheme.type.meta,
                        color = OrbitTheme.colors.fgMuted,
                    )
                }
            }

            if (contact.isIgnored) {
                Text(
                    text = "Ignored",
                    style = OrbitTheme.type.meta,
                    color = OrbitTheme.colors.fgSubtle,
                )
            } else {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = null,
                )
            }
        }

        if (menuAction != null) {
            PickerRowActionMenu(
                expanded = menuExpanded,
                isIgnored = contact.isIgnored,
                displayName = displayName,
                onDismiss = { menuExpanded = false },
                onAction = {
                    menuExpanded = false
                    menuAction(contact)
                },
            )
        }
    }
}

/**
 * The row's long-press menu. One quiet action per row state:
 * "Ignore" with its locked supporting line, or "Unignore" for an already
 * ignored row. Anchored [DropdownMenu] (FilterChipsRow precedent) — a modal
 * sheet would be too loud for a single action.
 */
@Composable
private fun PickerRowActionMenu(
    expanded: Boolean,
    isIgnored: Boolean,
    displayName: String,
    onDismiss: () -> Unit,
    onAction: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        if (isIgnored) {
            DropdownMenuItem(
                text = {
                    Text(
                        text = "Unignore",
                        style = OrbitTheme.type.body,
                        color = OrbitTheme.colors.fg,
                    )
                },
                onClick = onAction,
            )
        } else {
            DropdownMenuItem(
                text = {
                    Column(modifier = Modifier.widthIn(max = 260.dp)) {
                        Text(
                            text = "Ignore",
                            style = OrbitTheme.type.body,
                            color = OrbitTheme.colors.fg,
                        )
                        Text(
                            text = "Hide $displayName from Orbit. They stay in your phone's contacts.",
                            style = OrbitTheme.type.meta,
                            color = OrbitTheme.colors.fgMuted,
                        )
                    }
                },
                onClick = onAction,
            )
        }
    }
}

@Preview(name = "PickerContactRow — light", showBackground = true)
@Composable
private fun PickerContactRowPreviewLight() {
    OrbitTheme(darkTheme = false) {
        Column {
            PickerContactRow(
                contact = previewContact(
                    id = 1L,
                    name = "Sarah Levin",
                    callCount = 4,
                    lastCallAt = Instant.now().minusSeconds(3 * 24 * 3600),
                    listNames = listOf("Inner orbit"),
                ),
                isSelected = true,
                onToggle = {},
            )
            PickerContactRow(
                contact = previewContact(
                    id = 2L,
                    name = "Marcus Reid",
                    callCount = 0,
                    lastCallAt = null,
                    listNames = emptyList(),
                ),
                isSelected = false,
                onToggle = {},
            )
            PickerContactRow(
                contact = previewContact(
                    id = 3L,
                    name = "Priya Anand",
                    callCount = 12,
                    lastCallAt = Instant.now().minusSeconds(60 * 24 * 3600),
                    listNames = listOf("Inner orbit", "Late night", "People who ground me", "Family"),
                ),
                isSelected = false,
                onToggle = {},
            )
            PickerContactRow(
                contact = previewContact(
                    id = 6L,
                    name = "Dana Wells",
                    callCount = 0,
                    lastCallAt = null,
                    listNames = emptyList(),
                    isIgnored = true,
                ),
                isSelected = false,
                onToggle = {},
                onUnignore = {},
            )
        }
    }
}

@Preview(name = "PickerContactRow — dark", showBackground = true)
@Composable
private fun PickerContactRowPreviewDark() {
    OrbitTheme(darkTheme = true) {
        Column {
            PickerContactRow(
                contact = previewContact(
                    id = 4L,
                    name = "Jordan Hale",
                    callCount = 1,
                    lastCallAt = Instant.now().minusSeconds(24 * 3600),
                    listNames = listOf("Late night"),
                ),
                isSelected = true,
                onToggle = {},
            )
            PickerContactRow(
                contact = previewContact(
                    id = 5L,
                    name = "Eli Park",
                    callCount = 0,
                    lastCallAt = null,
                    listNames = emptyList(),
                ),
                isSelected = false,
                onToggle = {},
            )
        }
    }
}

private fun previewContact(
    id: Long,
    name: String,
    callCount: Int,
    lastCallAt: Instant?,
    listNames: List<String>,
    isIgnored: Boolean = false,
): PickerContact = PickerContact(
    contactId = id,
    displayName = name,
    phone = "+15555550123",
    photoUri = null,
    isIgnored = isIgnored,
    callCount = callCount,
    lastCallAt = lastCallAt,
    firstSeenByAppAt = Instant.now().minusSeconds(30L * 24 * 3600),
    listIds = emptySet(),
    listNames = listNames,
    isCommonlyCalled = false,
    isRarelyCalled = false,
    isRecentlyAdded = false,
    isLongGap = false,
)
