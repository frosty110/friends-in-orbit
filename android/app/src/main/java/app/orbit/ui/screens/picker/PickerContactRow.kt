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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.orbit.ui.components.Avatar
import app.orbit.ui.components.LocalPrivacyCurtain
import app.orbit.ui.components.OrbitDropdownMenu
import app.orbit.ui.components.OrbitIconButton
import app.orbit.ui.components.OrbitMenuAction
import app.orbit.ui.components.OrbitMenuTone
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.util.formatRelative
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
 * A trailing ⋮ button — and still a long-press anywhere on the row — opens a
 * small anchored action menu:
 *   - "Open in Contacts" ([onOpenInPhone]): hands the row to the device
 *     contacts app, where the call and message history for an unrecognised
 *     number actually lives. Hidden when the row has no
 *     [PickerContact.phoneContactId] behind it.
 *   - "Ignore" for a normal row (with the locked supporting copy "Hide {name}
 *     from Orbit. They stay in your phone's contacts."), "Unignore" for an
 *     ignored one.
 *
 * The ⋮ button exists because these two actions were long-press-only, and a
 * long-press-only action is one most people never discover — yet "who is this
 * number?" and "hide this spam caller" are the jobs users arrive at the picker
 * with.
 *
 * Ignored rows (visible only behind the "Show ignored" filter entry) render
 * muted with an "Ignored" tag in place of the checkbox and are NOT selectable —
 * tapping one opens the same menu, so the row never dead-ends. [onIgnore] /
 * [onUnignore] / [onOpenInPhone] default to null so non-curation callers keep
 * the plain tap-to-select row.
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
    onOpenInPhone: ((PickerContact) -> Unit)? = null,
) {
    val curtain = LocalPrivacyCurtain.current
    val displayName = if (curtain) "Contact" else contact.displayName
    val haptics = LocalHapticFeedback.current

    // The ignore-side action for this row's state (null = not a curation
    // caller, so no ignore/unignore entry).
    val ignoreAction: ((PickerContact) -> Unit)? =
        if (contact.isIgnored) onUnignore else onIgnore
    // The menu exists if EITHER action is available — "Open in Contacts" alone
    // is reason enough to offer it.
    val hasMenu = ignoreAction != null || onOpenInPhone != null
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
                            if (hasMenu) menuExpanded = true
                        } else {
                            onToggle(contact.contactId)
                        }
                    },
                    onLongClick = if (hasMenu) {
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
            Avatar(name = displayName, size = 44.dp, photoUri = if (curtain) null else contact.photoUri)

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

            // Visible entry to the row menu. Long-press still opens it, but a
            // long-press-only action is an action most people never find —
            // "ignore this spam caller" and "who IS this?" are exactly the
            // jobs someone arrives at this screen with, so they get an
            // affordance they can see. Quiet tint: maintenance, not a primary
            // action.
            if (hasMenu) {
                OrbitIconButton(
                    icon = "dots-three-vertical",
                    onClick = { menuExpanded = true },
                    tint = OrbitTheme.colors.fgMuted,
                    contentDescription = "More actions for $displayName",
                )
            }
        }

        if (hasMenu) {
            PickerRowActionMenu(
                expanded = menuExpanded,
                isIgnored = contact.isIgnored,
                displayName = displayName,
                onDismiss = { menuExpanded = false },
                // No `menuExpanded = false` here — OrbitDropdownMenu dismisses
                // itself before firing the callback.
                onIgnoreAction = ignoreAction?.let { action -> { action(contact) } },
                onOpenInPhone = onOpenInPhone?.let { action -> { action(contact) } },
            )
        }
    }
}

/**
 * The row's action menu, reached by the trailing ⋮ button or a long-press.
 * Anchored [OrbitDropdownMenu] (FilterChipsRow precedent) — a modal sheet would
 * be too loud for two quiet actions.
 *
 * Actions are listed most-used-first per the menu ordering contract;
 * [OrbitDropdownMenu] sinks the destructive one below a divider itself, so the
 * order here is "Open in Contacts", then Ignore/Unignore:
 *
 *   - "Open in Contacts" is everyday and non-destructive — identifying an
 *     unknown number is the question that comes *before* deciding to hide it.
 *   - "Ignore" carries [OrbitMenuTone.Destructive]: it hides someone the user
 *     would otherwise have to go find again. It keeps its locked supporting
 *     line — the promise that ignoring touches only Orbit, never the phone's
 *     address book, is the whole reason the action is safe to offer inline.
 *   - "Unignore" restores, so it stays [OrbitMenuTone.Default].
 *
 * [onIgnoreAction] is null for non-curation callers, [onOpenInPhone] for rows
 * with no device contact behind them. [OrbitDropdownMenu] dismisses itself
 * before firing a callback, so neither needs to.
 */
@Composable
private fun PickerRowActionMenu(
    expanded: Boolean,
    isIgnored: Boolean,
    displayName: String,
    onDismiss: () -> Unit,
    onIgnoreAction: (() -> Unit)?,
    onOpenInPhone: (() -> Unit)?,
) {
    OrbitDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        actions = buildList {
            if (onOpenInPhone != null) {
                add(
                    OrbitMenuAction(
                        label = "Open in Contacts",
                        onClick = onOpenInPhone,
                        supporting = "See their call and message history in your " +
                            "phone's contacts app.",
                    ),
                )
            }
            if (onIgnoreAction != null) {
                add(
                    if (isIgnored) {
                        // Restoring someone is not destructive — it stays in fg.
                        OrbitMenuAction(label = "Unignore", onClick = onIgnoreAction)
                    } else {
                        OrbitMenuAction(
                            label = "Ignore",
                            onClick = onIgnoreAction,
                            tone = OrbitMenuTone.Destructive,
                            supporting = "Hide $displayName from Orbit. " +
                                "They stay in your phone's contacts.",
                        )
                    },
                )
            }
        },
    )
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
