package app.orbit.ui.components

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.provider.ContactsContract
import androidx.annotation.ColorInt
import androidx.core.content.res.ResourcesCompat
import app.orbit.R
import java.io.InputStream
import kotlin.math.min

/**
 * [Avatar] as a bitmap, for the surfaces Compose cannot draw into: a nudge's
 * large icon and the home-screen widgets (UX rubric decision 5, one avatar
 * everywhere). Same rules as [Avatar]: the address-book photo when there is
 * one, otherwise [avatarInitials] in Inter SemiBold at [INITIALS_SCALE] of the
 * circle, on the colour [app.orbit.ui.theme.OrbitTones.avatarPalette] picks
 * for the name. Always a circle; the corners are transparent.
 *
 * Callers pass sizes in pixels so the monogram is sized from the circle, not
 * from the font scale, exactly as the in-app avatar sizes it in dp: at 200%
 * font scale the letters still sit inside the circle.
 */
object AvatarBitmaps {

    /**
     * The person's photo, centre-cropped to a [sizePx] circle, or null when
     * they have none or it cannot be read (READ_CONTACTS revoked, the photo
     * deleted since the last sync). Null means "draw the initials", the same
     * fallback the in-app avatar uses for its loading and error states.
     *
     * Reads the full-size display photo through [phoneContactId] when there is
     * one, because [photoUri] is the 96px thumbnail and a 64dp notification
     * icon would show it soft; falls back to the thumbnail.
     */
    fun photo(context: Context, photoUri: String?, phoneContactId: Long?, sizePx: Int): Bitmap? {
        if (photoUri.isNullOrBlank()) return null
        val bytes = runCatching {
            openPhoto(context, photoUri, phoneContactId)?.use { it.readBytes() }
        }.getOrNull() ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val shortest = min(bounds.outWidth, bounds.outHeight)
        if (shortest <= 0) return null
        var sample = 1
        while (shortest / (sample * 2) >= sizePx) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        return circleCrop(decoded, sizePx)
    }

    /** The monogram on its palette colour: a finished avatar, for a nudge's large icon. */
    fun initials(
        context: Context,
        name: String,
        sizePx: Int,
        @ColorInt background: Int,
        @ColorInt foreground: Int,
    ): Bitmap = drawInitials(context, name, sizePx, background, foreground)

    /**
     * The monogram alone, white on transparent, for a widget to tint. A widget
     * swaps its colours when the phone switches between light and dark without
     * asking Orbit to redraw, so the circle and the letters take their day and
     * night colours from the widget, and only the letter shapes are a bitmap.
     */
    fun initialsMask(context: Context, name: String, sizePx: Int): Bitmap =
        drawInitials(context, name, sizePx, background = null, foreground = Color.WHITE)

    private fun openPhoto(context: Context, photoUri: String, phoneContactId: Long?): InputStream? {
        val resolver = context.contentResolver
        val fullSize = phoneContactId?.let { id ->
            ContactsContract.Contacts.openContactPhotoInputStream(
                resolver,
                ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id),
                true,
            )
        }
        return fullSize ?: resolver.openInputStream(Uri.parse(photoUri))
    }

    private fun circleCrop(source: Bitmap, sizePx: Int): Bitmap {
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val side = min(source.width, source.height)
        val left = (source.width - side) / 2
        val top = (source.height - side) / 2
        val circle = RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawOval(circle, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(source, Rect(left, top, left + side, top + side), circle, paint)
        return out
    }

    private fun drawInitials(
        context: Context,
        name: String,
        sizePx: Int,
        @ColorInt background: Int?,
        @ColorInt foreground: Int,
    ): Bitmap {
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val size = sizePx.toFloat()
        if (background != null) {
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background }
            canvas.drawOval(RectF(0f, 0f, size, size), fill)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = foreground
            textSize = size * INITIALS_SCALE
            textAlign = Paint.Align.CENTER
            typeface = interSemiBold(context)
            letterSpacing = INITIALS_TRACKING
        }
        val metrics = paint.fontMetrics
        val baseline = size / 2f - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(avatarInitials(name), size / 2f, baseline, paint)
        return out
    }

    // Inter ships in res/font (DESIGN.md); a font that fails to load falls back
    // to the system sans at the same weight rather than to no monogram.
    private fun interSemiBold(context: Context): Typeface =
        runCatching { ResourcesCompat.getFont(context, R.font.inter_semibold) }.getOrNull()
            ?: Typeface.create(Typeface.SANS_SERIF, SEMI_BOLD, false)

    private const val SEMI_BOLD = 600

    /** Matches the in-app monogram's -0.01em tracking. */
    private const val INITIALS_TRACKING = -0.01f
}
