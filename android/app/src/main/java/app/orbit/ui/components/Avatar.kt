package app.orbit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import app.orbit.ui.theme.OrbitAvatarTones

@Composable
fun Avatar(
    name: String,
    size: Dp = 44.dp,
    modifier: Modifier = Modifier,
) {
    // 15-05b L6 — cache the deterministic palette pick + initials derivation
    // per name so recomposition skips the hash loop and split when name is
    // unchanged. The local `var hash` lives inside the remember lambda and
    // runs only when `name` changes.
    val (bg, fg, initials) = remember(name) {
        var hash = 0
        for (c in name) hash = (hash * 31 + c.code)
        val (palBg, palFg) = OrbitAvatarTones.palettes[(hash and Int.MAX_VALUE) % OrbitAvatarTones.palettes.size]
        val rendered = name.split(' ')
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.first().uppercase() }
        Triple(palBg, palFg, rendered)
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(bg),
    ) {
        Text(
            text = initials,
            color = fg,
            fontSize = (size.value * 0.36f).sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.01).em,
        )
    }
}
