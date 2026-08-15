package app.orbit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
 */
@Composable
fun OrbitScreen(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OrbitTheme.colors.bg)
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime)),
    ) {
        content()
    }
}
