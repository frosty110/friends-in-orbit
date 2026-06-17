package app.orbit.ui.components

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * True when list / contact names should be replaced with a neutral noun.
 * Driven by [app.orbit.AppViewModel.privacyCurtainActive] — flips on
 * focus loss (ON_STOP) and restores on focus regain (ON_START), via the
 * MainActivity LifecycleEventObserver.
 *
 * PRIV-03 consumer set: every composable that renders
 * `contact.name`, `list.name`, or `displayName` MUST check `.current` and,
 * when true, render the literal string "Contact" for contact names and
 * "List" for list names (list names stay masked because they are
 * user-authored and relationship-revealing, but "Contact" was the wrong
 * noun for them). Avatar inputs (initials source, photo) are masked the same
 * way as the adjacent text — real initials or a face under the curtain is a
 * leak.
 *
 * 2026-04-28: the user-facing minimal-mode toggle was removed; the curtain
 * is now strictly auto-only (foreground state). `staticCompositionLocalOf`
 * because the value changes infrequently (on ON_STOP / ON_START only).
 */
val LocalPrivacyCurtain: ProvidableCompositionLocal<Boolean> =
    staticCompositionLocalOf { false }
