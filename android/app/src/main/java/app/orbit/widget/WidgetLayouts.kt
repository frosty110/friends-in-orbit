// android/app/src/main/java/app/orbit/widget/WidgetLayouts.kt
//
// WIDGET-07: responsive widgets, which arrangement each size gets.
//
// Both widgets declare SizeMode.Responsive with the breakpoints below. Glance
// composes the widget once per breakpoint and Android shows, for the size the
// user gave it, the breakpoint that fits and is closest to it. Inside that
// composition LocalSize is the breakpoint, not the widget's exact size, so
// these functions are total over the breakpoints and the layouts stretch to
// the real size with weighted spacers.
//
// Each breakpoint is the smallest size its arrangement fits in with 12dp of
// padding (4dp above and below a strip), a 48dp Call button (rules.md
// Design 3) and an 18sp name: the button is the last thing in most layouts,
// and a layout that overflows clips it first. Widths start at 110dp, the
// narrowest two-cell widget Android guarantees (70n - 30dp for n cells).
//
// Pure (no Glance runtime), so OrbitWidgetLayoutTest pins every breakpoint on
// the JVM.
package app.orbit.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.orbit.ui.theme.WidgetSizes

/** The arrangements a widget can take. */
enum class WidgetLayout {
    /** One row tall: avatar, the name where it fits ([stripShowsName]), a round Call button. */
    STRIP,

    /** A small square: avatar and a round Call side by side, the name below. */
    COMPACT,

    /** A roomy square: avatar, name, then a labelled Call button, centred. */
    HERO,

    /** Wide and short, one person: avatar, name, round Call in a row. */
    WIDE,

    /** Call suggestions, wide: the lead person on the left, the others listed on the right. */
    SPLIT,

    /** Call suggestions, wide and tall: the lead person in a row on top, the others below. */
    STACK,
}

object WidgetBreakpoints {
    /**
     * One row: a face and a Call button, 36 + 48 inside 86dp. The widget
     * info's minResizeHeight is 56dp so a launcher never offers a row too
     * short for the 48dp button.
     */
    val Strip = DpSize(110.dp, 56.dp)

    /** One row with room for the name between the face and Call. */
    val StripWide = DpSize(180.dp, 56.dp)

    /** The smallest two-row square: a 48dp row over a one-line name, 76 inside 12dp padding. */
    val Compact = DpSize(110.dp, 100.dp)

    /** A typical 2×2: 44 avatar, one-line name, 48 Call, gaps; 136 inside 12dp padding. */
    val Hero = DpSize(150.dp, 160.dp)

    /** A roomy 2×2: same arrangement, 56 avatar, name on up to two lines. */
    val HeroLarge = DpSize(180.dp, 200.dp)

    /** 3×2 and 4×2 at their shortest. */
    val Wide = DpSize(250.dp, 100.dp)

    /** A 4×2 with room for two more people in the list. Call suggestions only. */
    val SplitTall = DpSize(250.dp, 150.dp)

    /** 4×3 and up. Call suggestions only. */
    val Stack = DpSize(250.dp, 200.dp)

    /** "Next call" shows one person, so it stops at [Wide]. */
    val nextCall: Set<DpSize> = setOf(Strip, StripWide, Compact, Hero, HeroLarge, Wide)

    /** "Call suggestions" shows up to three. */
    val suggestions: Set<DpSize> =
        setOf(Strip, StripWide, Compact, Hero, HeroLarge, Wide, SplitTall, Stack)
}

/** The arrangement for the one-person "Next call" widget at [size]. */
fun nextCallLayout(size: DpSize): WidgetLayout = when {
    size.height < WidgetBreakpoints.Compact.height -> WidgetLayout.STRIP
    size.width >= WidgetBreakpoints.Wide.width -> WidgetLayout.WIDE
    size.width >= WidgetBreakpoints.Hero.width &&
        size.height >= WidgetBreakpoints.Hero.height -> WidgetLayout.HERO
    else -> WidgetLayout.COMPACT
}

/**
 * The arrangement for "Call suggestions" at [size], with [alternatives] people
 * to show after the lead. Narrower than [WidgetBreakpoints.Wide] it is a
 * one-person widget like "Next call"; with nobody else to show, a wide widget
 * gives the lead person the whole width.
 */
fun suggestionsLayout(size: DpSize, alternatives: Int): WidgetLayout = when {
    size.width < WidgetBreakpoints.Wide.width -> nextCallLayout(size)
    size.height < WidgetBreakpoints.Compact.height -> WidgetLayout.STRIP
    alternatives == 0 -> WidgetLayout.WIDE
    size.height >= WidgetBreakpoints.Stack.height -> WidgetLayout.STACK
    else -> WidgetLayout.SPLIT
}

/**
 * How many people after the lead fit at [size]. Each takes a full 48dp row
 * (rules.md Design 3), so a 4×2 at its shortest lists one, not two squeezed.
 */
fun alternativeSlots(size: DpSize): Int = when {
    size.width < WidgetBreakpoints.Wide.width -> 0
    size.height < WidgetBreakpoints.Compact.height -> 0
    size.height >= WidgetBreakpoints.SplitTall.height -> 2
    else -> 1
}

/** A strip writes the name only where it fits beside the face and the Call button. */
fun stripShowsName(size: DpSize): Boolean = size.width >= WidgetBreakpoints.StripWide.width

/** The lead avatar on a square widget: larger once the square has room for it. */
fun heroAvatarDp(size: DpSize): Int =
    if (size.height >= WidgetBreakpoints.HeroLarge.height &&
        size.width >= WidgetBreakpoints.HeroLarge.width
    ) {
        WidgetSizes.avatarLarge
    } else {
        WidgetSizes.avatarMedium
    }
