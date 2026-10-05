// android/app/src/main/java/app/orbit/widget/WidgetPeople.kt
//
// The people a widget shows, resolved once in provideGlance: their faces as
// bitmaps, their colours as day and night pairs, and what a tap on them does.
// The composables in WidgetContent.kt only lay these out; Glance recompositions
// run in the system's widget host, so the work stays here (widgets README,
// "Known gotchas").
package app.orbit.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.glance.action.Action
import androidx.glance.appwidget.action.actionStartActivity
import app.orbit.data.entity.ContactEntity
import app.orbit.domain.usecase.WidgetSurfaceData
import app.orbit.nav.AppLinks
import app.orbit.nav.Routes
import app.orbit.ui.components.AvatarBitmaps
import app.orbit.ui.theme.WidgetAvatarColors
import app.orbit.ui.theme.WidgetAvatarTones
import app.orbit.ui.theme.WidgetSizes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One person on a widget.
 *
 * [name] is what the widget writes: the display name, or "Contact" in minimal
 * mode (WIDGET-04). [face] is null in minimal mode, which draws a silhouette.
 * [open] opens the deck of the list that surfaced them, with them on top
 * (WIDGET-08); [call] opens the dialer, and is null on a device without one,
 * where the widget offers no Call control rather than one that does nothing.
 */
class WidgetPerson(
    val name: String,
    val face: WidgetFace?,
    val open: Action,
    val call: Action?,
)

/**
 * WIDGET-11: the app's avatar, prepared for a widget. Either a [photo]
 * (already a circle) or, when there is none, a [monogram] (white letters on
 * clear, tinted by the widget) on a circle of [colors]. See
 * AvatarBitmaps.initialsMask for why the letters are a bitmap and the colours
 * are not.
 */
class WidgetFace(
    val photo: Bitmap?,
    val monogram: Bitmap?,
    val colors: WidgetAvatarColors,
)

/**
 * The first [max] people of [data] as the widget will show them. Reads photos
 * from the address book, so it runs on the IO dispatcher.
 */
suspend fun widgetPeople(
    context: Context,
    data: WidgetSurfaceData,
    max: Int,
    minimalMode: Boolean,
    tones: WidgetAvatarTones,
): List<WidgetPerson> = withContext(Dispatchers.IO) {
    val sizePx = (WidgetSizes.avatarLarge * context.resources.displayMetrics.density).toInt()
    (listOfNotNull(data.primary) + data.alternatives).take(max).map { contact ->
        WidgetPerson(
            name = if (minimalMode) MINIMAL_NAME else contact.displayName,
            face = if (minimalMode) null else face(context, contact, sizePx, tones),
            open = actionStartActivity(openIntent(context, data.listIdByContactId[contact.id])),
            call = dialIntent(contact).takeIf { it.resolveActivity(context.packageManager) != null }
                ?.let { actionStartActivity(it) },
        )
    }
}

private fun face(
    context: Context,
    contact: ContactEntity,
    sizePx: Int,
    tones: WidgetAvatarTones,
): WidgetFace {
    val photo = AvatarBitmaps.photo(context, contact.photoUri, contact.phoneContactId, sizePx)
    return WidgetFace(
        photo = photo,
        monogram = if (photo == null) {
            AvatarBitmaps.initialsMask(context, contact.displayName, sizePx)
        } else {
            null
        },
        colors = tones.forName(contact.displayName),
    )
}

/** The list's deck when known; Orbit's start screen otherwise. */
private fun openIntent(context: Context, listId: Long?) =
    if (listId == null) {
        openHomeIntent(context)
    } else {
        AppLinks.openRoute(context, Routes.card(listId.toString()))
    }

/** WIDGET-04: the masked name, the same word the in-app curtain uses. */
const val MINIMAL_NAME = "Contact"
