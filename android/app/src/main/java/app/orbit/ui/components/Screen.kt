package app.orbit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.orbit.ui.theme.OrbitTheme

/**
 * The screen shell every full-screen destination sits in.
 *
 * Insets: system bars **union the IME**. `MainActivity` calls
 * `enableEdgeToEdge()`, which turns off `decorFitsSystemWindows` — so the
 * manifest's `adjustResize` no longer resizes the window and the keyboard
 * simply draws over whatever is beneath it. That's how a text field could sit
 * under the keyboard while you typed into it (2026-08-15 UAT, list creation).
 *
 * Padding the shell by the IME inset shrinks the screen to the visible area
 * instead, which is what `adjustResize` used to do. Scrollable bodies
 * (`verticalScroll` / `LazyColumn`) then scroll a focused field into that area
 * on their own — Compose's text fields request it on focus. A screen that
 * hosts a text field should therefore keep its body scrollable; the shell
 * guarantees the space, not the scrolling.
 *
 * Width: content is capped at [MaxContentWidth] and centred, so on a tablet,
 * a foldable or a phone in landscape lines stay readable and controls stay
 * within reach (Google's "large screen ready" tier; UX rubric decision 7). The
 * background still fills the window. On a phone in portrait nothing changes.
 * Insets also include display cutouts, which matter in landscape.
 */
@Composable
fun OrbitScreen(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = Modifier
            .fillMaxSize()
            .background(OrbitTheme.colors.bg),
    ) {
        Column(
            modifier = modifier
                .fillMaxHeight()
                .widthIn(max = MaxContentWidth)
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.systemBars
                        .union(WindowInsets.displayCutout)
                        .union(WindowInsets.ime),
                ),
        ) {
            content()
        }
    }
}

/** Widest a screen's content grows: about 75 characters of body text. */
val MaxContentWidth = 640.dp
