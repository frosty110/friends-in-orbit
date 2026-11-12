package app.orbit.widget.settings

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.orbit.BuildConfig
import app.orbit.data.AppPrefs
import app.orbit.ui.components.LocalPrivacyCurtain
import app.orbit.ui.theme.OrbitDarkMode
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.theme.OrbitThemeId
import app.orbit.ui.theme.OrbitThemes
import app.orbit.ui.theme.ThemeSettings
import app.orbit.widget.WidgetUpdateScheduler
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.combine

/**
 * Widget settings (WIDGET-12, WIDGET-15): the launcher's configure activity
 * for both widgets, opened when a widget is placed, from the launcher's
 * reconfigure, and from a widget's "Choose a list" (WIDGET-14).
 *
 * A separate activity, not a route in MainActivity: the launcher starts a
 * configure activity expecting a result for this one widget, and routing that
 * through the main task would bring whatever Orbit screen was open forward and
 * tangle the result with its back stack. It runs in its own task (manifest:
 * empty taskAffinity, excluded from recents).
 *
 * It holds Orbit's window rules itself, because it is Orbit's window and shows
 * list names: the privacy curtain while it is not in the foreground (PRIV-03,
 * the same ON_STOP / ON_START signal MainActivity uses) and no screenshots in
 * release builds (PRIV-04).
 */
@AndroidEntryPoint
class WidgetSettingsActivity : ComponentActivity() {

    @Inject lateinit var appPrefs: AppPrefs

    private val viewModel: WidgetSettingsViewModel by viewModels()

    private var curtain by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Exported so the launcher can start it, so any app can, with any id:
        // only one of our placed widgets gets a settings screen.
        val appWidgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (WidgetUpdateScheduler.widgetFor(this, appWidgetId) == null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        // OK from the start, not only on Done: a launcher removes a widget
        // whose first configuration is cancelled, and Back must not remove
        // the widget the user just placed. It works with every list until
        // they choose otherwise. (The first draw is the ViewModel's: Glance
        // sends no first update to a widget that has a configure screen.)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))

        if (!BuildConfig.DEBUG) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> curtain = true
                    Lifecycle.Event.ON_START -> curtain = false
                    else -> Unit
                }
            },
        )

        // The same three appearance prefs AppViewModel maps for MainActivity.
        val themeFlow = combine(appPrefs.colorTheme, appPrefs.darkMode, appPrefs.accentHue) { theme, dark, hue ->
            ThemeSettings(
                themeId = OrbitThemeId.fromKey(theme),
                darkMode = OrbitDarkMode.fromKey(dark),
                accentHue = if (hue < 0) null else hue,
            )
        }

        setContent {
            val settings by themeFlow.collectAsStateWithLifecycle(initialValue = ThemeSettings.DEFAULT)
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            CompositionLocalProvider(LocalPrivacyCurtain provides curtain) {
                OrbitTheme(settings = settings, darkTheme = OrbitThemes.effectiveDark(settings, isSystemInDarkTheme())) {
                    WidgetSettingsContent(
                        state = state,
                        snackbarEvents = viewModel.snackbarEvents,
                        onSelectSource = viewModel::onSelectSource,
                        onShowNamesChange = viewModel::onShowNamesChange,
                        onDone = ::finish,
                    )
                }
            }
        }
    }
}
