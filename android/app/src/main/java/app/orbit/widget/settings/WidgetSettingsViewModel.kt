package app.orbit.widget.settings

import android.appwidget.AppWidgetManager
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.orbit.R
import app.orbit.data.AppPrefs
import app.orbit.data.WidgetSource
import app.orbit.data.repository.ListRepository
import app.orbit.di.ApplicationScope
import app.orbit.domain.usecase.WidgetSurfaceUseCase
import app.orbit.ui.screens.picker.SnackbarEvent
import app.orbit.ui.util.UiText
import app.orbit.widget.OneWidgetRefresher
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Widget settings for one placed widget (WIDGET-12, WIDGET-15).
 *
 * The widget's id arrives as the launcher's [AppWidgetManager.EXTRA_APPWIDGET_ID]
 * extra, which an activity's ViewModel reads from its [SavedStateHandle].
 * [WidgetSettingsActivity] has already checked it names one of our widgets.
 *
 * Changes save as they are made, as Settings' Appearance does, and redraw this
 * widget at once. The writes run on the application scope (rules.md Code 6):
 * Done closes the screen straight after a tap, and a write cancelled with the
 * ViewModel would leave the choice the user saw unsaved. They run one at a
 * time, in the order tapped, so two quick taps cannot land out of order.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class WidgetSettingsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    listRepo: ListRepository,
    private val appPrefs: AppPrefs,
    private val widgetSurface: WidgetSurfaceUseCase,
    private val refresher: OneWidgetRefresher,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val appWidgetId: Int =
        savedStateHandle[AppWidgetManager.EXTRA_APPWIDGET_ID] ?: AppWidgetManager.INVALID_APPWIDGET_ID

    val uiState: StateFlow<WidgetSettingsUiState> =
        combine(listRepo.observeActive(), appPrefs.widgetSettings(appWidgetId)) { lists, settings ->
            lists to settings
        }.mapLatest { (lists, settings) ->
            // The same source the widget renders from, so the preview cannot
            // disagree with the home screen.
            val data = widgetSurface(settings.source, max = 1)
            val lead = data.primary
            WidgetSettingsUiState.Ready(
                lists = lists.sortedBy { it.sortOrder }.map { ListOption(it.id, it.name) },
                source = settings.source,
                namesHidden = settings.namesHidden,
                preview = when {
                    data.sourceMissing -> WidgetSettingsPreview.SourceMissing
                    lead == null -> WidgetSettingsPreview.Quiet
                    else -> WidgetSettingsPreview.Person(
                        name = lead.displayName,
                        photoUri = lead.photoUri,
                        masked = settings.namesHidden,
                    )
                },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), WidgetSettingsUiState.Loading)

    private val _snackbarEvents = MutableSharedFlow<SnackbarEvent>(extraBufferCapacity = 1)
    val snackbarEvents: SharedFlow<SnackbarEvent> = _snackbarEvents.asSharedFlow()

    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        // Drains in order and ends once onCleared closes the channel and the
        // last queued write has landed.
        appScope.launch {
            for (write in writes) write()
        }
        // The widget's first draw. When this screen opens at placement,
        // Glance sends the new widget no update of its own (a configure
        // screen is expected to ask), so without this a widget left with Back
        // would sit on its loading frame until the hourly sweep. Queued with
        // the writes, on the application scope, so an immediate Back cannot
        // cancel it; on a reconfigure it is one redundant redraw.
        writes.trySend { redraw() }
    }

    fun onSelectSource(source: WidgetSource) = save { appPrefs.setWidgetSource(appWidgetId, source) }

    fun onShowNamesChange(show: Boolean) = save { appPrefs.setWidgetNamesHidden(appWidgetId, hidden = !show) }

    private fun save(write: suspend () -> Unit) {
        writes.trySend {
            val saved = try {
                write()
                true
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                // No id, no list name: an event only (rules.md Code 4).
                Timber.tag("widget").w(t, "widget_settings_save_failed")
                _snackbarEvents.tryEmit(SnackbarEvent(UiText.res(R.string.widget_settings_save_failed)))
                false
            }
            if (saved) redraw()
        }
    }

    /**
     * The choice is saved either way; a failed redraw only delays it to the
     * debounced refresh [app.orbit.widget.WidgetUpdateScheduler.refreshOne]
     * falls back to, so it is logged, not shown.
     */
    private suspend fun redraw() {
        try {
            refresher.refresh(appWidgetId)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Timber.tag("widget").w(t, "widget_settings_redraw_deferred")
        }
    }

    override fun onCleared() {
        writes.close()
    }
}
