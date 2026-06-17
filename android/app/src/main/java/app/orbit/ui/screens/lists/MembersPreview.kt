package app.orbit.ui.screens.lists

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.orbit.ui.components.Avatar
import app.orbit.ui.components.PhIcon
import app.orbit.ui.theme.OrbitTheme

/**
 * Members preview block for List Configuration.
 *
 * Renders the current members of a list as a vertical column of avatar + name
 * rows. Receives the FULL member list (the upstream 20-cap in
 * [ListConfigViewModel] is gone: it reported "20 people" for a 50-person list
 * and made rows 21+ unremovable). The count line is always the true total;
 * lists longer than [COLLAPSED_VISIBLE_COUNT] collapse behind an honest
 * "Showing 20 of N" label with a "Show all" affordance.
 *
 * F-6 / F-7 — STATIC lists also expose:
 *   - Trailing remove ("x") affordance per row → fires [onRemoveMember] which
 *     dispatches the optimistic remove + UndoStack-backed snackbar in the VM.
 *   - "Add contacts" row at the bottom → fires [onAddContacts] which routes to
 *     ContactPickerScreen via the nav graph.
 * SMART lists hide both — membership is rule-derived, not user-curated.
 *
 * Empty-state copy depends on list type:
 *  - SMART → "No one matches this rule right now."
 *  - STATIC → "No one in this list yet."
 *
 * Token-clean — no inline color hex literals, no RoundedCornerShape, no fontSize literals.
 *
 * Section eyebrow + count is rendered by the parent [SettingGroup] in
 * `ListConfigScreen`; this composable owns only the body content.
 */
@Composable
fun MembersPreview(
    members: List<ListConfigContactSnapshot>,
    isSmart: Boolean,
    modifier: Modifier = Modifier,
    onRemoveMember: (Long, String) -> Unit = { _, _ -> },
    onAddContacts: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 18.dp),
    ) {
        Text(
            text = "${members.size} ${if (members.size == 1) "person" else "people"}",
            style = OrbitTheme.type.eyebrow.copy(color = OrbitTheme.colors.fgMuted),
        )
        if (members.isEmpty()) {
            Text(
                text = if (isSmart) {
                    "No one matches this rule right now."
                } else {
                    "No one in this list yet."
                },
                style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fgMuted),
                modifier = Modifier.padding(top = 12.dp),
            )
        } else {
            // Visual collapse for long lists. The full list is always available
            // behind "Show all" so every row stays removable; the collapsed
            // label is honest about the truncation.
            var expanded by rememberSaveable { mutableStateOf(false) }
            val collapsed = !expanded && members.size > COLLAPSED_VISIBLE_COUNT
            val visible = if (collapsed) members.take(COLLAPSED_VISIBLE_COUNT) else members
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 12.dp),
            ) {
                visible.forEach { snapshot ->
                    MemberRow(
                        snapshot = snapshot,
                        showRemove = !isSmart,
                        onRemove = { onRemoveMember(snapshot.id, snapshot.displayName) },
                    )
                }
            }
            if (collapsed) {
                ShowAllRow(
                    visibleCount = COLLAPSED_VISIBLE_COUNT,
                    totalCount = members.size,
                    onShowAll = { expanded = true },
                )
            }
        }
        if (!isSmart) {
            AddContactsRow(
                hasMembers = members.isNotEmpty(),
                onAddContacts = onAddContacts,
            )
        }
    }
}

/** Collapsed-preview size. Visual budget only; never the count. */
private const val COLLAPSED_VISIBLE_COUNT: Int = 20

/**
 * Honest truncation row — "Showing 20 of 52" + a "Show all" tap target
 * (≥48dp per rules.md §Design 3). Disappears once expanded.
 */
@Composable
private fun ShowAllRow(
    visibleCount: Int,
    totalCount: Int,
    onShowAll: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = OrbitTheme.spacing.tapMin)
            .clickable(onClick = onShowAll)
            .semantics { contentDescription = "Show all $totalCount members" },
    ) {
        Text(
            text = "Showing $visibleCount of $totalCount",
            style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgSubtle),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "Show all",
            style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
        )
    }
}

@Composable
private fun MemberRow(
    snapshot: ListConfigContactSnapshot,
    showRemove: Boolean,
    onRemove: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = snapshot.displayName },
    ) {
        Avatar(name = snapshot.displayName, size = 32.dp)
        Text(
            text = snapshot.displayName,
            style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
            modifier = Modifier.weight(1f),
        )
        if (showRemove) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .defaultMinSize(
                        minWidth = OrbitTheme.spacing.tapMin,
                        minHeight = OrbitTheme.spacing.tapMin,
                    )
                    .clickable(onClick = onRemove)
                    .semantics {
                        contentDescription = "Remove ${snapshot.displayName} from list"
                    },
            ) {
                PhIcon(
                    name = "x",
                    size = 18.dp,
                    tint = OrbitTheme.colors.fgMuted,
                )
            }
        }
    }
}

@Composable
private fun AddContactsRow(
    hasMembers: Boolean,
    onAddContacts: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onAddContacts)
            .padding(top = if (hasMembers) 14.dp else 12.dp, bottom = 4.dp)
            .semantics { contentDescription = "Add contacts to list" },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.defaultMinSize(minWidth = 32.dp, minHeight = 32.dp),
        ) {
            PhIcon(
                name = "user-plus",
                size = 20.dp,
                tint = OrbitTheme.colors.accent,
            )
        }
        Text(
            text = "Add contacts",
            style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.accent),
        )
    }
}

// region Previews

@Preview(name = "MembersPreview — smart, empty, light", showBackground = true)
@Composable
private fun MembersPreviewSmartEmptyLightPreview() {
    OrbitTheme(darkTheme = false) {
        Box(
            modifier = Modifier.background(OrbitTheme.colors.surface).padding(8.dp),
        ) {
            MembersPreview(members = emptyList(), isSmart = true)
        }
    }
}

@Preview(name = "MembersPreview — static, empty, dark", showBackground = true)
@Composable
private fun MembersPreviewStaticEmptyDarkPreview() {
    OrbitTheme(darkTheme = true) {
        Box(
            modifier = Modifier.background(OrbitTheme.colors.surface).padding(8.dp),
        ) {
            MembersPreview(members = emptyList(), isSmart = false)
        }
    }
}

@Preview(name = "MembersPreview — populated, light", showBackground = true)
@Composable
private fun MembersPreviewPopulatedLightPreview() {
    OrbitTheme(darkTheme = false) {
        Box(
            modifier = Modifier.background(OrbitTheme.colors.surface).padding(8.dp),
        ) {
            MembersPreview(
                members = listOf(
                    ListConfigContactSnapshot(1L, "Alex Rivera", null),
                    ListConfigContactSnapshot(2L, "Sam Patel", null),
                    ListConfigContactSnapshot(3L, "Jordan Lee", null),
                ),
                isSmart = false,
            )
        }
    }
}

// endregion
