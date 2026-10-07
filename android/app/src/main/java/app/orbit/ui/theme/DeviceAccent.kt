package app.orbit.ui.theme

import android.content.Context

/**
 * The hue of the wallpaper's accent (Android 12+ dynamic colour), for the
 * Wallpaper theme ([OrbitThemeId.DEVICE]). Only the hue is used; lightness and
 * saturation come from Orbit's contrast-safe generator.
 *
 * Returns null when the wallpaper accent is close to grey, because its hue is
 * then noise: the theme falls back to Warm's hue instead of a random one.
 */
fun deviceAccentHue(context: Context): Float? {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(context.getColor(android.R.color.system_accent1_500), hsv)
    return if (hsv[1] < MIN_SATURATION) null else hsv[0]
}

private const val MIN_SATURATION = 0.12f
