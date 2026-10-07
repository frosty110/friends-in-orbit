package app.orbit.widget

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import app.orbit.R
import app.orbit.ui.components.avatarInitials
import app.orbit.ui.theme.DarkColors
import app.orbit.ui.theme.LightColors
import app.orbit.ui.theme.WarmTones
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * WIDGET-09: the widget picker previews are XML (Android draws them before any
 * Orbit code runs), so their colours are copies of theme tokens. This keeps
 * the copies honest: if a Warm token moves, or a sample name stops hashing to
 * the avatar colour the preview paints it, this fails.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class WidgetPreviewResourcesTest {

    private val context: Application get() = ApplicationProvider.getApplicationContext()

    private fun color(id: Int): Int = context.getColor(id)

    private fun assertToken(token: Color, id: Int, what: String) =
        assertEquals(token.toArgb(), color(id), "$what matches its token")

    @Test
    fun lightColours_matchTheWarmLightTokens() {
        RuntimeEnvironment.setQualifiers("+notnight")
        assertToken(LightColors.surface, R.color.widget_preview_surface, "surface")
        assertToken(LightColors.fg, R.color.widget_preview_fg, "fg")
        assertToken(LightColors.fgMuted, R.color.widget_preview_fg_muted, "fgMuted")
        assertToken(LightColors.line, R.color.widget_preview_line, "line")
        assertToken(LightColors.accent, R.color.widget_preview_accent, "accent")
        assertToken(LightColors.accentFg, R.color.widget_preview_on_accent, "accentFg")
    }

    @Test
    fun darkColours_matchTheWarmDarkTokens() {
        RuntimeEnvironment.setQualifiers("+night")
        assertToken(DarkColors.surface, R.color.widget_preview_surface, "surface")
        assertToken(DarkColors.fg, R.color.widget_preview_fg, "fg")
        assertToken(DarkColors.fgMuted, R.color.widget_preview_fg_muted, "fgMuted")
        assertToken(DarkColors.line, R.color.widget_preview_line, "line")
        assertToken(DarkColors.accent, R.color.widget_preview_accent, "accent")
        assertToken(DarkColors.accentFg, R.color.widget_preview_on_accent, "accentFg")
    }

    @Test
    fun samplePeople_wearTheAvatarTheAppWouldGiveThem() {
        val samples = listOf(
            Triple(
                R.string.widget_preview_name,
                R.string.widget_preview_initials,
                R.color.widget_preview_avatar_lead_bg to R.color.widget_preview_avatar_lead_fg,
            ),
            Triple(
                R.string.widget_preview_alt_1,
                R.string.widget_preview_alt_1_initials,
                R.color.widget_preview_avatar_alt_1_bg to R.color.widget_preview_avatar_alt_1_fg,
            ),
            Triple(
                R.string.widget_preview_alt_2,
                R.string.widget_preview_alt_2_initials,
                R.color.widget_preview_avatar_alt_2_bg to R.color.widget_preview_avatar_alt_2_fg,
            ),
        )
        samples.forEach { (nameId, initialsId, colors) ->
            val name = context.getString(nameId)
            val (bg, fg) = WarmTones.avatarPalette(name)
            assertEquals(avatarInitials(name), context.getString(initialsId), "initials for $name")
            assertEquals(bg.toArgb(), color(colors.first), "avatar background for $name")
            assertEquals(fg.toArgb(), color(colors.second), "avatar letters for $name")
        }
    }
}
