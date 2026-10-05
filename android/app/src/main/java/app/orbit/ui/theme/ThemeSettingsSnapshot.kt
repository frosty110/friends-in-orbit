package app.orbit.ui.theme

import app.orbit.data.AppPrefs
import kotlinx.coroutines.flow.first

/**
 * The user's appearance choice, read once, for surfaces that render outside
 * the app's composition: the home-screen widgets and a nudge's large icon.
 * Both draw a person's avatar in the colours the app would use, so both need
 * the same theme, accent dial and light or dark choice the app resolves.
 *
 * One-shot by design: a widget render and a notification post are single
 * snapshots, and keeping a Flow open in either would outlive the render
 * (see the provideGlance notes in the widget package). [AppPrefs] stores raw keys so the data
 * layer has no theme dependency; this is where they become a [ThemeSettings].
 */
suspend fun AppPrefs.themeSettingsSnapshot(): ThemeSettings =
    ThemeSettings(
        themeId = OrbitThemeId.fromKey(colorTheme.first()),
        darkMode = OrbitDarkMode.fromKey(darkMode.first()),
        accentHue = accentHue.first().let { if (it < 0) null else it },
    )
