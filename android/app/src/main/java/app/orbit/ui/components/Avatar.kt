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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.orbit.ui.theme.OrbitTheme
import coil.compose.SubcomposeAsyncImage

/**
 * The one avatar for a person, everywhere they appear (UX rubric decision 5):
 * their address-book photo when there is one, otherwise their initials on a
 * colour picked from their name. Always a circle.
 *
 * Before 2026-10-05 each screen had its own photo wrapper (NextUpAvatar,
 * ContactPhoto, CallLogAvatar, RowAvatar), and several showed an empty crop
 * when a photo failed to load. Here the initials are the loading and error
 * state as well as the fallback, so a face or a monogram is always drawn.
 *
 * The avatar is decorative: the person's name is always written next to it, so
 * it is hidden from TalkBack (it used to read the initials aloud, "A Q").
 * Callers under the privacy curtain pass `photoUri = null` and a masked name.
 *
 * Initials are sized from the circle in dp, not sp, so they stay inside a
 * fixed circle at 200% font scale (they are a monogram, not reading text).
 */
@Composable
fun Avatar(
    name: String,
    size: Dp = 44.dp,
    modifier: Modifier = Modifier,
    photoUri: String? = null,
) {
    val circle = modifier
        .size(size)
        .clip(CircleShape)
        .clearAndSetSemantics {}
    if (photoUri.isNullOrBlank()) {
        Initials(name = name, size = size, modifier = circle)
    } else {
        SubcomposeAsyncImage(
            model = photoUri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            loading = { Initials(name = name, size = size) },
            error = { Initials(name = name, size = size) },
            modifier = circle.background(OrbitTheme.colors.bgSubtle),
        )
    }
}

@Composable
private fun Initials(name: String, size: Dp, modifier: Modifier = Modifier) {
    // 15-05b L6 — cache the deterministic palette pick + initials derivation
    // per name so recomposition skips the hash loop and split when name is
    // unchanged. Keyed on `palettes` too so a theme switch re-picks the avatar
    // color (THEMING 2026-06-22).
    val palettes = OrbitTheme.tones.avatarPalettes
    val (bg, fg, initials) = remember(name, palettes) {
        var hash = 0
        for (c in name) hash = (hash * 31 + c.code)
        val (palBg, palFg) = palettes[(hash and Int.MAX_VALUE) % palettes.size]
        val rendered = name.split(' ')
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.first().uppercase() }
        Triple(palBg, palFg, rendered)
    }
    // Dp.toSp() divides out the font scale, so the drawn size tracks the circle.
    val fontSize = with(LocalDensity.current) { (size * INITIALS_SCALE).toSp() }

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
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.01).em,
            maxLines = 1,
        )
    }
}

private const val INITIALS_SCALE = 0.36f
