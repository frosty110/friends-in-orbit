package app.orbit.widget.settings

import androidx.compose.runtime.Immutable
import app.orbit.data.WidgetSource

/**
 * WIDGET-12, WIDGET-15: the one state contract of Widget settings (ARCH-02).
 *
 * [Ready.source] and [Ready.namesHidden] are read back from the stored
 * settings, never held separately on the screen, so each has one owner
 * (rules.md Code 7): a tap writes, and the stored value comes back here.
 */
sealed interface WidgetSettingsUiState {
    data object Loading : WidgetSettingsUiState

    @Immutable
    data class Ready(
        /** Every active list, in the user's Lists order. Archived lists are not offered. */
        val lists: List<ListOption>,
        val source: WidgetSource,
        val namesHidden: Boolean,
        val preview: WidgetSettingsPreview,
    ) : WidgetSettingsUiState
}

/** One list the widget can be pointed at. The name shows only on this screen. */
@Immutable
data class ListOption(val id: Long, val name: String)

/** "Up now on this widget": what the widget will lead with, given the choices. */
sealed interface WidgetSettingsPreview {
    /**
     * The person the widget leads with. [masked] is true when the widget hides
     * names, and the screen then draws "Contact" and no photo, as the widget will.
     */
    @Immutable
    data class Person(val name: String, val photoUri: String?, val masked: Boolean) : WidgetSettingsPreview

    /** Nobody to suggest right now: "All quiet for now." (WIDGET-10). */
    data object Quiet : WidgetSettingsPreview

    /** The chosen list is archived or deleted (WIDGET-14). */
    data object SourceMissing : WidgetSettingsPreview
}
