package app.orbit.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

// Compose's shadow() approximates the CSS box-shadows. Values are hand-tuned
// to match the two-layer soft warm shadows (--shadow-card, --shadow-hero).
// Shadow color is tinted warm in light mode and blacker in dark to mirror CSS.
fun Modifier.orbitCardShadow(shape: Shape = RoundedCornerShape(16.dp), isDark: Boolean = false): Modifier =
    this.shadow(
        elevation = 6.dp,
        shape = shape,
        ambientColor = if (isDark) Color.Black else Color(0xFF211E1C),
        spotColor = if (isDark) Color.Black else Color(0xFF211E1C),
    )

fun Modifier.orbitHeroShadow(shape: Shape = RoundedCornerShape(24.dp), isDark: Boolean = false): Modifier =
    this.shadow(
        elevation = 18.dp,
        shape = shape,
        ambientColor = if (isDark) Color.Black else Color(0xFF211E1C),
        spotColor = if (isDark) Color.Black else Color(0xFF211E1C),
    )
