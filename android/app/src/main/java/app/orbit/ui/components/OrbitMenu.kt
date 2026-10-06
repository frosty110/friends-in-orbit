package app.orbit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.orbit.ui.theme.OrbitTheme

/**
 * Menu ordering contract (2026-08-15 UAT feedback).
 *
 * Every options / overflow menu in Orbit follows the same shape:
 *
 *   1. Everyday actions first, in rough order of how often they're used.
 *   2. Destructive actions last, after a divider, in [OrbitColors.danger].
 *   3. Leading icons are all-or-none within a menu: every row has one or no
 *      row does, so a blank slot never reads as a missing icon (Browse's row
 *      menu once showed three icons and one gap). A convention from
 *      2026-10-06, so menus built before it are brought in line as they are
 *      touched, not retrofitted in one sweep.
 *
 * Callers list their actions in "most used first" order and mark the
 * destructive ones with [OrbitMenuTone.Destructive]; [orderedForMenu] sinks
 * those to the bottom (stable, so the caller's ordering is preserved within
 * each group) and [OrbitDropdownMenu] paints them. A menu that quietly puts
 * "Archive" above "Rename" — as Lists Manager used to — is the bug this
 * exists to prevent.
 */
enum class OrbitMenuTone { Default, Destructive }

/**
 * One row in an [OrbitDropdownMenu].
 *
 * @param label the action, sentence case, no exclamation marks (voice guidelines).
 * @param onClick fired after the menu dismisses itself.
 * @param tone [OrbitMenuTone.Destructive] paints the row in danger and sinks it
 *   below the divider. Use it for anything that removes, hides, or ends
 *   something the user would have to rebuild by hand (archive, delete, ignore).
 * @param icon optional Phosphor icon name rendered leading the label. All or
 *   none within one menu (contract point 3 above).
 * @param supporting optional second line — used where a destructive action
 *   needs to say what it actually does.
 * @param selected marks the current choice in a menu of options (the picker's
 *   sort order): a trailing check in fg, and "selected" for TalkBack. Added
 *   2026-10-05 so option menus stop needing a stock Material menu with an
 *   accent tick; defaults to false, so existing callers are unchanged.
 */
data class OrbitMenuAction(
    val label: String,
    val onClick: () -> Unit,
    val tone: OrbitMenuTone = OrbitMenuTone.Default,
    val icon: String? = null,
    val supporting: String? = null,
    val enabled: Boolean = true,
    val selected: Boolean = false,
)

/**
 * Sinks destructive actions to the bottom, preserving the caller's order
 * within each group ([sortedBy] is stable). Pure, so the ordering contract is
 * unit-testable without a Compose host.
 */
fun List<OrbitMenuAction>.orderedForMenu(): List<OrbitMenuAction> =
    sortedBy { it.tone == OrbitMenuTone.Destructive }

/**
 * The one dropdown menu Orbit renders. Handles ordering, the destructive
 * divider, danger tinting, and self-dismissal so no call site has to remember
 * `menuExpanded = false` before its own callback.
 */
@Composable
fun OrbitDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    actions: List<OrbitMenuAction>,
    modifier: Modifier = Modifier,
) {
    val ordered = actions.orderedForMenu()
    val firstDestructive = ordered.indexOfFirst { it.tone == OrbitMenuTone.Destructive }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.background(OrbitTheme.colors.surface),
    ) {
        ordered.forEachIndexed { index, action ->
            // Divider only when destructive actions follow something else —
            // a menu whose single action is destructive doesn't need a rule
            // above it.
            if (index == firstDestructive && index > 0) {
                HorizontalDivider(color = OrbitTheme.colors.line)
            }
            val labelColor = when {
                !action.enabled -> OrbitTheme.colors.fgSubtle
                action.tone == OrbitMenuTone.Destructive -> OrbitTheme.colors.danger
                else -> OrbitTheme.colors.fg
            }
            DropdownMenuItem(
                text = {
                    Column(modifier = Modifier.widthIn(max = 260.dp)) {
                        Text(
                            text = action.label,
                            style = OrbitTheme.type.body,
                            color = labelColor,
                        )
                        if (action.supporting != null) {
                            Text(
                                text = action.supporting,
                                style = OrbitTheme.type.meta,
                                color = OrbitTheme.colors.fgMuted,
                            )
                        }
                    }
                },
                leadingIcon = action.icon?.let { icon ->
                    {
                        PhIcon(
                            name = icon,
                            size = 18.dp,
                            tint = if (action.tone == OrbitMenuTone.Destructive) {
                                OrbitTheme.colors.danger
                            } else {
                                OrbitTheme.colors.fgMuted
                            },
                        )
                    }
                },
                trailingIcon = if (action.selected) {
                    { PhIcon(name = "check", size = 18.dp, tint = OrbitTheme.colors.fg) }
                } else {
                    null
                },
                enabled = action.enabled,
                onClick = {
                    onDismissRequest()
                    action.onClick()
                },
                modifier = if (action.selected) Modifier.semantics { selected = true } else Modifier,
            )
        }
    }
}
