package app.orbit.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.orbit.data.ChipTone

/**
 * List-name chip that respects [LocalPrivacyCurtain]. CORE-08 — when the
 * curtain is on (foreground lost) the chip renders the generic "List" label
 * in a neutral `ChipTone.Stone`. List names stay masked (they are
 * user-authored and relationship-revealing per
 * features/privacy-and-lock/README.md) but anonymize to "List". Thin wrapper
 * around [OrbitChip]; introduces no new visual primitives.
 *
 * `ChipTone` ground truth (`Model.kt:18`): only Terracotta, Sage, Amber, Brick,
 * Stone exist; the enum must NOT be extended. `Stone` is the canonical
 * curtain-on / read-only neutral marker.
 */
@Composable
fun ListContextChip(
    listName: String,
    tone: ChipTone = ChipTone.Terracotta,
    modifier: Modifier = Modifier,
) {
    val curtain = LocalPrivacyCurtain.current
    OrbitChip(
        label = if (curtain) "List" else listName,
        tone = if (curtain) ChipTone.Stone else tone,
        modifier = modifier,
    )
}
