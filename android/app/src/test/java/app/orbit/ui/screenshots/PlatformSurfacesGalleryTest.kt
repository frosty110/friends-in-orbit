package app.orbit.ui.screenshots

import android.app.Application
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.appwidget.action.actionStartActivity
import androidx.test.core.app.ApplicationProvider
import app.orbit.R
import app.orbit.domain.contactFixture
import app.orbit.notify.NudgeNotification
import app.orbit.notify.OrbitNotifications
import app.orbit.ui.components.AvatarBitmaps
import app.orbit.ui.theme.OrbitThemes
import app.orbit.ui.theme.OrbitWidgetTheme
import app.orbit.ui.theme.ThemeSettings
import app.orbit.ui.theme.orbitWidgetAvatarTones
import app.orbit.ui.theme.orbitWidgetColorProviders
import app.orbit.widget.NextCallBody
import app.orbit.widget.SuggestionsBody
import app.orbit.widget.WidgetBreakpoints
import app.orbit.widget.WidgetFace
import app.orbit.widget.WidgetPerson
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the surfaces Orbit draws outside its own window to PNGs, so they are
 * reviewed as images: both widgets at every breakpoint and at typical phone
 * sizes, their empty state, their picker previews, and a nudge with its
 * lock-screen version. Light and dark.
 *
 * The widgets go through Glance's own RemoteViews translation
 * ([GlanceRemoteViews]) and Android's RemoteViews inflation, the path a
 * launcher takes, so what is drawn is what a home screen would show (minus the
 * launcher's own font and scale choices). A widget's size picks its
 * breakpoint the way Android does: the one that fits and is closest.
 *
 * Also writes the picker's static fallback images (WIDGET-09): the light
 * renderings of the preview layouts, copied by hand into
 * res/drawable-nodpi/widget_preview_*.png.
 *
 * Runs only with -Pscreenshots, like [PreviewGalleryTest]; it records, it does
 * not compare.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class PlatformSurfacesGalleryTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val outputDir: File by lazy {
        File(System.getProperty("orbit.screenshots.dir") ?: "build/screenshots", "platform").apply { mkdirs() }
    }

    @Test
    fun widgets() = runBlocking {
        for (night in listOf(false, true)) {
            setNight(night)
            val mode = if (night) "dark" else "light"
            val people = samplePeople()
            // The same three, led by someone without a photo, for the monogram.
            val initialsFirst = listOf(people[1], people[0], people[2])

            val nextCallSizes = WidgetBreakpoints.nextCall + listOf(
                DpSize(170.dp, 180.dp),
                DpSize(200.dp, 220.dp),
                DpSize(330.dp, 120.dp),
                DpSize(300.dp, 64.dp),
            )
            for (size in nextCallSizes) {
                val bucket = bucketFor(size, WidgetBreakpoints.nextCall)
                renderWidget("next-call-${tag(size)}-$mode", size, bucket) {
                    NextCallBody(people.take(1), openApp())
                }
                renderWidget("next-call-initials-${tag(size)}-$mode", size, bucket) {
                    NextCallBody(initialsFirst.take(1), openApp())
                }
            }

            val suggestionSizes = WidgetBreakpoints.suggestions + listOf(
                DpSize(330.dp, 120.dp),
                DpSize(330.dp, 170.dp),
                DpSize(330.dp, 260.dp),
                DpSize(170.dp, 180.dp),
                DpSize(330.dp, 64.dp),
            )
            for (size in suggestionSizes) {
                val bucket = bucketFor(size, WidgetBreakpoints.suggestions)
                renderWidget("suggestions-${tag(size)}-$mode", size, bucket) {
                    SuggestionsBody(initialsFirst, openApp())
                }
            }

            for (size in listOf(DpSize(170.dp, 180.dp), DpSize(330.dp, 170.dp), DpSize(300.dp, 64.dp))) {
                val bucket = bucketFor(size, WidgetBreakpoints.suggestions)
                renderWidget("empty-${tag(size)}-$mode", size, bucket) {
                    SuggestionsBody(emptyList(), openApp())
                }
            }
        }
    }

    @Test
    fun pickerPreviews() {
        for (night in listOf(false, true)) {
            setNight(night)
            val mode = if (night) "dark" else "light"
            renderLayout(R.layout.widget_preview_next_call, DpSize(180.dp, 180.dp), "preview-next-call-$mode")
            renderLayout(R.layout.widget_preview_suggestions, DpSize(330.dp, 165.dp), "preview-suggestions-$mode")
            renderLayout(R.layout.widget_loading, DpSize(180.dp, 180.dp), "loading-$mode")
        }
        // The picker's static fallback (previewImage): light, no backdrop,
        // clear corners. Copied to res/drawable-nodpi/widget_preview_*.png.
        setNight(false)
        renderLayout(R.layout.widget_preview_next_call, DpSize(180.dp, 180.dp), "previewImage-2x2", bare = true)
        renderLayout(R.layout.widget_preview_suggestions, DpSize(330.dp, 165.dp), "previewImage-4x2", bare = true)
    }

    @Test
    fun nudge() {
        // Robolectric has no dialer; register one so the Call action shows.
        val pm = org.robolectric.Shadows.shadowOf(context.packageManager)
        val dialer = android.content.ComponentName("com.example.dialer", "com.example.dialer.DialActivity")
        pm.addActivityIfNotPresent(dialer)
        pm.addIntentFilterForActivity(
            dialer,
            android.content.IntentFilter(Intent.ACTION_DIAL).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataScheme("tel")
            },
        )
        for (night in listOf(false, true)) {
            setNight(night)
            val mode = if (night) "dark" else "light"
            OrbitNotifications.ensureChannels(context)
            val theme = OrbitThemes.resolve(ThemeSettings.DEFAULT, isDark = night)
            val kai = contactFixture(id = 7L, displayName = "Kai Nakamura")
            val sizePx = context.resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_width)
            val (bg, fg) = theme.tones.avatarPalette(kai.displayName)
            val face = AvatarBitmaps.initials(context, kai.displayName, sizePx, bg.toArgb(), fg.toArgb())
            val named = NudgeNotification.build(
                context, listId = 3L, listName = "Family", dueCount = 2,
                subject = kai, face = face, accent = theme.colors.accent.toArgb(),
            )
            val nameless = NudgeNotification.build(
                context, listId = 3L, listName = "Family", dueCount = 2,
                subject = null, face = null, accent = theme.colors.accent.toArgb(),
            )
            renderNotification(named, "nudge-named-$mode", expanded = true)
            renderNotification(named, "nudge-named-collapsed-$mode", expanded = false)
            renderNotification(nameless, "nudge-nameless-$mode", expanded = false)
            renderNotification(named.publicVersion!!, "nudge-lockscreen-$mode", expanded = false)
        }
    }

    // ─── Sample data ─────────────────────────────────────────────────────────

    private fun samplePeople(): List<WidgetPerson> {
        val tones = orbitWidgetAvatarTones(ThemeSettings.DEFAULT)
        val sizePx = (56 * context.resources.displayMetrics.density).roundToInt()
        fun person(name: String, photo: Bitmap?): WidgetPerson = WidgetPerson(
            name = name,
            face = WidgetFace(
                photo = photo,
                monogram = if (photo == null) AvatarBitmaps.initialsMask(context, name, sizePx) else null,
                colors = tones.forName(name),
            ),
            openIntent = Intent(Intent.ACTION_VIEW),
            dialIntent = Intent(Intent.ACTION_DIAL),
        )
        return listOf(
            person("Kai Nakamura", fakePhoto(sizePx)),
            person("Priya Shah", null),
            person("Sarah Okafor-Lindqvist", null),
        )
    }

    /** A stand-in photo: a warm gradient disc, so photo avatars are visible as such. */
    private fun fakePhoto(sizePx: Int): Bitmap {
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, sizePx.toFloat(), sizePx.toFloat(),
                0xFF8FA9C9.toInt(), 0xFFE0B38A.toInt(), Shader.TileMode.CLAMP,
            )
        }
        Canvas(out).drawOval(0f, 0f, sizePx.toFloat(), sizePx.toFloat(), paint)
        return out
    }

    private fun openApp() = actionStartActivity(Intent(Intent.ACTION_MAIN))

    // ─── Rendering ───────────────────────────────────────────────────────────

    private suspend fun renderWidget(
        name: String,
        size: DpSize,
        bucket: DpSize,
        content: @Composable () -> Unit,
    ) {
        val colors = orbitWidgetColorProviders(ThemeSettings.DEFAULT)
        val remoteViews = GlanceRemoteViews().compose(context, bucket) {
            OrbitWidgetTheme(colors = colors) { content() }
        }.remoteViews
        drawInto(name, size) { parent -> remoteViews.apply(context, parent) }
    }

    private fun renderLayout(layout: Int, size: DpSize, name: String, bare: Boolean = false) {
        drawInto(name, size, bare = bare) { parent ->
            LayoutInflater.from(context).inflate(layout, parent, false)
        }
    }

    private fun renderNotification(notification: Notification, name: String, expanded: Boolean) {
        val builder = Notification.Builder.recoverBuilder(context, notification)
        val views: RemoteViews = if (expanded) {
            builder.createBigContentView() ?: builder.createContentView()
        } else {
            builder.createContentView()
        }
        drawInto(name, DpSize(380.dp, if (expanded) 140.dp else 96.dp), wrapHeight = true) { parent ->
            views.apply(context, parent)
        }
    }

    private fun drawInto(
        name: String,
        size: DpSize,
        wrapHeight: Boolean = false,
        bare: Boolean = false,
        build: (ViewGroup) -> View,
    ) {
        val density = context.resources.displayMetrics.density
        val w = (size.width.value * density).roundToInt()
        val h = (size.height.value * density).roundToInt()
        val margin = if (bare) 0 else (12 * density).roundToInt()
        val parent = FrameLayout(context)
        val view = build(parent)
        val height = if (wrapHeight) ViewGroup.LayoutParams.WRAP_CONTENT else h
        parent.addView(view, FrameLayout.LayoutParams(w, height))
        val widthSpec = View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY)
        val heightSpec = if (wrapHeight) {
            View.MeasureSpec.makeMeasureSpec(h * 3, View.MeasureSpec.AT_MOST)
        } else {
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        }
        parent.measure(widthSpec, heightSpec)
        parent.layout(0, 0, parent.measuredWidth, parent.measuredHeight)
        // A wallpaper-ish backdrop, so the widget's own corners and surface show.
        val bitmap = Bitmap.createBitmap(
            parent.measuredWidth + margin * 2,
            parent.measuredHeight + margin * 2,
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)
        if (!bare) canvas.drawColor(0xFF5B6B7A.toInt())
        canvas.translate(margin.toFloat(), margin.toFloat())
        parent.draw(canvas)
        File(outputDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun setNight(night: Boolean) {
        RuntimeEnvironment.setQualifiers(if (night) "+night" else "+notnight")
    }

    private fun tag(size: DpSize) = "${size.width.value.roundToInt()}x${size.height.value.roundToInt()}"

    /** The breakpoint Android shows at [size]: the closest of those that fit, else the smallest. */
    private fun bucketFor(size: DpSize, breakpoints: Set<DpSize>): DpSize =
        breakpoints
            .filter { it.width <= size.width && it.height <= size.height }
            .minByOrNull {
                val dw = (size.width - it.width).value
                val dh = (size.height - it.height).value
                dw * dw + dh * dh
            }
            ?: breakpoints.minBy { it.width.value * it.height.value }
}
