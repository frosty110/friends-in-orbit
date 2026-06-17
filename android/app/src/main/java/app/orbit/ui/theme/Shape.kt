package app.orbit.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

@Immutable
data class OrbitShapes(
    val xs: RoundedCornerShape,
    val sm: RoundedCornerShape,
    val md: RoundedCornerShape,
    val lg: RoundedCornerShape,
    val xl: RoundedCornerShape,
    val full: RoundedCornerShape,
    // Material3 ModalBottomSheet top-rounded shape token. Eliminates
    // RoundedCornerShape literals in PauseSheet, CreateListBottomSheet, and any
    // other bottom-sheet composables. Top corners 24dp, bottom corners 0dp.
    val bottomSheet: RoundedCornerShape,
)

internal val OrbitShapeSet = OrbitShapes(
    xs = RoundedCornerShape(4.dp),
    sm = RoundedCornerShape(8.dp),
    md = RoundedCornerShape(12.dp),
    lg = RoundedCornerShape(16.dp),
    xl = RoundedCornerShape(24.dp),
    full = RoundedCornerShape(999.dp),
    bottomSheet = RoundedCornerShape(
        topStart = 24.dp,
        topEnd = 24.dp,
        bottomStart = 0.dp,
        bottomEnd = 0.dp,
    ),
)

internal val LocalOrbitShapes = staticCompositionLocalOf { OrbitShapeSet }
