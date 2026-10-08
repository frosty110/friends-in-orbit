package app.orbit.notify

import android.content.Context
import android.content.res.Configuration
import app.orbit.data.AppPrefs
import app.orbit.ui.theme.OrbitThemes
import app.orbit.ui.theme.ResolvedTheme
import app.orbit.ui.theme.deviceAccentHue
import app.orbit.ui.theme.themeSettingsSnapshot

/**
 * The app's theme in the mode it is showing, for what Orbit draws in the
 * shade: a notification's accent colour and a nudge's face carry the colours
 * the same things have in the app. Shared by the nudge ([ListPromptWorker])
 * and the notification after a call ([PostCallNotifier]) since 2026-10-07; it
 * was the nudge worker's own until then.
 */
internal suspend fun resolveNotificationTheme(context: Context, appPrefs: AppPrefs): ResolvedTheme {
    val settings = appPrefs.themeSettingsSnapshot()
    val uiMode = context.resources.configuration.uiMode
    val night = (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    return OrbitThemes.resolve(
        settings,
        isDark = OrbitThemes.effectiveDark(settings, night),
        deviceHue = deviceAccentHue(context),
    )
}
