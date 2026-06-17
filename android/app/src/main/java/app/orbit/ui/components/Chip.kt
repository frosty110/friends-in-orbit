package app.orbit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.orbit.data.ChipTone
import app.orbit.ui.theme.OrbitChipTones
import app.orbit.ui.theme.OrbitTheme

private data class ToneTriple(val bg: Color, val fg: Color, val dot: Color)

@Composable
private fun toneTriple(tone: ChipTone): ToneTriple = when (tone) {
    ChipTone.Terracotta -> ToneTriple(OrbitChipTones.Terracotta.bg, OrbitChipTones.Terracotta.fg, OrbitChipTones.Terracotta.dot)
    ChipTone.Sage       -> ToneTriple(OrbitChipTones.Sage.bg,       OrbitChipTones.Sage.fg,       OrbitChipTones.Sage.dot)
    ChipTone.Amber      -> ToneTriple(OrbitChipTones.Amber.bg,      OrbitChipTones.Amber.fg,      OrbitChipTones.Amber.dot)
    ChipTone.Brick      -> ToneTriple(OrbitChipTones.Brick.bg,      OrbitChipTones.Brick.fg,      OrbitChipTones.Brick.dot)
    ChipTone.Stone      -> ToneTriple(OrbitChipTones.Stone.bg,      OrbitChipTones.Stone.fg,      OrbitChipTones.Stone.dot)
}

@Composable
fun OrbitChip(
    label: String,
    tone: ChipTone = ChipTone.Terracotta,
    modifier: Modifier = Modifier,
) {
    val (bg, fg, dot) = toneTriple(tone)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .clip(OrbitTheme.shapes.full)
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dot),
        )
        Text(
            text = label,
            style = OrbitTheme.type.micro.copy(
                color = fg,
                letterSpacing = 0.02.em,
            ),
        )
    }
}
