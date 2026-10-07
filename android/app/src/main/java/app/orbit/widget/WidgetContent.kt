// android/app/src/main/java/app/orbit/widget/WidgetContent.kt
//
// Shared Glance composables used by OrbitWidget2x2 ("Next call") and
// OrbitWidget4x2 ("Call suggestions"). Composables only: the intents they
// fire are built in WidgetIntents.kt, so this file can be left out of the
// unit-test coverage count as UI (see the Kover filter in app/build.gradle.kts).
//
// THEME NOTE: All color access is via GlanceTheme.colors.* (M3 slot aliases)
// inside OrbitWidgetTheme, plus the avatar's day and night pairs from
// WidgetAvatarColors. Zero Color(0x..) literals, and all text styles are
// OrbitWidgetTextStyles.* and sizes WidgetSizes.* / WidgetSpacing.*: tokens
// only (rules.md Design 1).
//
// 2026-10-05 (UX rubric plan item 3.2). Before: a square first initial on a
// grey tile, a bare phone glyph, a 12sp alternatives list, square corners on
// a rounded home screen, and one fixed size each. Now:
// - WIDGET-07 responsive: every size gets an arrangement (WidgetLayouts.kt).
// - WIDGET-08 only Call dials: a tap on a person opens their deck in Orbit,
//   and the labelled accent Call button is the one control that opens the
//   dialer, as on Card view (CARD-01). A stray tap on a widget used to dial.
// - WIDGET-10 the empty state says "All quiet for now."
// - WIDGET-11 it looks like Orbit: Android's own widget corner radius, the
//   theme's colours in light and dark, and the app's avatar (photo, else the
//   two-letter monogram on its palette colour, always a circle).
//
// TAP-TO-DIAL: ACTION_DIAL through actionStartActivity, which Glance wraps in
// a FLAG_IMMUTABLE PendingIntent. No CALL_PHONE (PRIV-05).
package app.orbit.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import app.orbit.R
import app.orbit.ui.theme.OrbitWidgetTextStyles
import app.orbit.ui.theme.WidgetSizes
import app.orbit.ui.theme.WidgetSpacing

// ─── Entry points ────────────────────────────────────────────────────────────

/** "Next call": one person, arranged for the widget's size. */
@Composable
fun NextCallBody(people: List<WidgetPerson>, onOpenApp: Action) {
    val lead = people.firstOrNull()
    if (lead == null) {
        EmptyState(onOpenApp)
        return
    }
    val size = LocalSize.current
    val layout = nextCallLayout(size)
    WidgetFrame(strip = layout == WidgetLayout.STRIP) {
        LeadPerson(lead, layout)
    }
}

/** "Call suggestions": the lead person and, where there is room, up to two more. */
@Composable
fun SuggestionsBody(people: List<WidgetPerson>, onOpenApp: Action) {
    val lead = people.firstOrNull()
    if (lead == null) {
        EmptyState(onOpenApp)
        return
    }
    val size = LocalSize.current
    val others = people.drop(1).take(alternativeSlots(size))
    val layout = suggestionsLayout(size, others.size)
    WidgetFrame(strip = layout == WidgetLayout.STRIP) {
        when (layout) {
            WidgetLayout.SPLIT -> Row(modifier = GlanceModifier.fillMaxSize()) {
                Box(modifier = GlanceModifier.defaultWeight().fillMaxHeight()) {
                    LeadPerson(lead, WidgetLayout.COMPACT)
                }
                Divider()
                Column(
                    modifier = GlanceModifier.defaultWeight().fillMaxHeight(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Half a 4×2 has no width for a name and a call button
                    // side by side, so these rows open the person's deck,
                    // one tap from their Call button.
                    others.forEach { OtherPerson(it, withCall = false) }
                }
            }
            // Centred, so a tall widget is composed around its people rather
            // than leaving an empty band under them (UX rubric D3).
            WidgetLayout.STACK -> Column(
                modifier = GlanceModifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LeadPerson(lead, WidgetLayout.WIDE)
                Spacer(GlanceModifier.height(WidgetSpacing.x2.dp))
                others.forEach { OtherPerson(it, withCall = true) }
            }
            else -> LeadPerson(lead, layout)
        }
    }
}

// ─── Frame ───────────────────────────────────────────────────────────────────

/**
 * The widget's card: the theme's surface, Android's own widget corner radius
 * (so it matches every other widget on the home screen, whatever the
 * launcher's shape), and marked as the widget background so launchers can
 * animate it as one piece. A one-row [strip] keeps only a sliver of padding
 * above and below, so its 48dp Call button fits a single row.
 */
@Composable
private fun WidgetFrame(
    modifier: GlanceModifier = GlanceModifier,
    strip: Boolean = false,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .appWidgetBackground()
            .cornerRadius(android.R.dimen.system_app_widget_background_radius)
            .background(
                ImageProvider(R.drawable.widget_shape_card),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.surface),
            )
            .padding(
                horizontal = WidgetSpacing.x3.dp,
                vertical = if (strip) WidgetSpacing.x1.dp else WidgetSpacing.x3.dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

// ─── The lead person ─────────────────────────────────────────────────────────

/** The person a widget leads with, in the arrangement [layout] asks for. */
@Composable
private fun LeadPerson(person: WidgetPerson, layout: WidgetLayout) {
    val context = LocalContext.current
    val size = LocalSize.current
    val openLabel = context.getString(R.string.widget_open_person, person.name)
    val body = GlanceModifier
        .clickable(actionStartActivity(person.openIntent))
        .semantics { contentDescription = openLabel }
    when (layout) {
        WidgetLayout.STRIP -> Row(
            modifier = body.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PersonAvatar(person, WidgetSizes.avatarSmall)
            if (stripShowsName(size)) {
                Spacer(GlanceModifier.width(WidgetSpacing.x3.dp))
                Name(person, maxLines = 1, modifier = GlanceModifier.defaultWeight())
                Spacer(GlanceModifier.width(WidgetSpacing.x2.dp))
            } else {
                // Too narrow for a name: the face says who, and TalkBack
                // reads the name from the row's label.
                Spacer(GlanceModifier.defaultWeight())
            }
            CallButton(person, labelled = false)
        }

        WidgetLayout.WIDE -> Row(
            modifier = body.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PersonAvatar(person, WidgetSizes.avatarLarge)
            Spacer(GlanceModifier.width(WidgetSpacing.x3.dp))
            Name(person, maxLines = 2, modifier = GlanceModifier.defaultWeight())
            Spacer(GlanceModifier.width(WidgetSpacing.x2.dp))
            CallButton(person, labelled = false)
        }

        WidgetLayout.HERO -> {
            val avatar = heroAvatarDp(size)
            Column(
                modifier = body.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PersonAvatar(person, avatar)
                Spacer(GlanceModifier.height(WidgetSpacing.x2.dp))
                Name(
                    person,
                    maxLines = if (avatar == WidgetSizes.avatarLarge) 2 else 1,
                    align = TextAlign.Center,
                )
                Spacer(GlanceModifier.height(WidgetSpacing.x2.dp))
                CallButton(person, labelled = true)
            }
        }

        // COMPACT, and the lead pane of SPLIT. A 36dp face so face and Call
        // fit side by side in the narrowest square (110dp).
        else -> Column(modifier = body.fillMaxSize()) {
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PersonAvatar(person, WidgetSizes.avatarSmall)
                Spacer(GlanceModifier.defaultWeight())
                CallButton(person, labelled = false)
            }
            Spacer(GlanceModifier.defaultWeight())
            Name(person, maxLines = 1)
        }
    }
}

// ─── Everyone else ───────────────────────────────────────────────────────────

/**
 * A person after the lead: avatar and name in a full-width 48dp row that opens
 * their deck. With [withCall], a quiet phone button at the end dials them: the
 * muted icon a list of people may carry (rules.md Design 6), so the accent
 * stays on the lead's Call (Design 5).
 */
@Composable
private fun OtherPerson(person: WidgetPerson, withCall: Boolean) {
    val context = LocalContext.current
    val openLabel = context.getString(R.string.widget_open_person, person.name)
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(WidgetSizes.tapMin.dp)
            .clickable(actionStartActivity(person.openIntent))
            .semantics { contentDescription = openLabel },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PersonAvatar(person, WidgetSizes.avatarSmall)
        Spacer(GlanceModifier.width(WidgetSpacing.x3.dp))
        Text(
            text = person.name,
            style = OrbitWidgetTextStyles.body.copy(color = GlanceTheme.colors.onSurface),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
        )
        val call = person.dialIntent
        if (withCall && call != null) {
            val callLabel = context.getString(R.string.widget_call_person, person.name)
            Box(
                modifier = GlanceModifier
                    .size(WidgetSizes.tapMin.dp)
                    .clickable(actionStartActivity(call))
                    .semantics { contentDescription = callLabel },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    provider = ImageProvider(R.drawable.ph_phone),
                    contentDescription = null,
                    modifier = GlanceModifier.size(WidgetSizes.icon.dp),
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
                )
            }
        }
    }
}

// ─── Parts ───────────────────────────────────────────────────────────────────

@Composable
private fun Name(
    person: WidgetPerson,
    maxLines: Int,
    modifier: GlanceModifier = GlanceModifier,
    align: TextAlign = TextAlign.Start,
) {
    Text(
        text = person.name,
        style = OrbitWidgetTextStyles.contactName.copy(
            color = GlanceTheme.colors.onSurface,
            textAlign = align,
        ),
        maxLines = maxLines,
        modifier = modifier,
    )
}

/**
 * The lead person's Call control, the widget's one accent element (rules.md
 * Design 5): a pill reading "Call" where the widget is a roomy square, and a
 * round phone button where it is not. Either way TalkBack reads "Call {name}".
 * Absent on a device with no dialer.
 */
@Composable
private fun CallButton(person: WidgetPerson, labelled: Boolean) {
    val call = person.dialIntent ?: return
    val context = LocalContext.current
    val callLabel = context.getString(R.string.widget_call_person, person.name)
    val shape = GlanceModifier
        .height(WidgetSizes.tapMin.dp)
        .background(
            ImageProvider(R.drawable.widget_shape_pill),
            colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
        )
        .clickable(actionStartActivity(call))
        .semantics { contentDescription = callLabel }
    val icon: @Composable () -> Unit = {
        Image(
            provider = ImageProvider(R.drawable.ph_phone),
            contentDescription = null,
            modifier = GlanceModifier.size(WidgetSizes.icon.dp),
            colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimary),
        )
    }
    if (labelled) {
        Row(
            modifier = shape.padding(horizontal = WidgetSpacing.x5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Spacer(GlanceModifier.width(WidgetSpacing.x2.dp))
            Text(
                text = context.getString(R.string.widget_call),
                style = OrbitWidgetTextStyles.label.copy(color = GlanceTheme.colors.onPrimary),
                maxLines = 1,
            )
        }
    } else {
        Box(modifier = shape.width(WidgetSizes.tapMin.dp), contentAlignment = Alignment.Center) {
            icon()
        }
    }
}

/**
 * WIDGET-11: the app's avatar. Photo when there is one (already cut to a
 * circle); otherwise the Inter monogram tinted with the palette colour for the
 * name, on an oval of the matching background, each a day and night pair so
 * the widget follows the phone into dark mode on its own. Circles are shape
 * drawables, not clipped outlines, so they stay round on every host. In
 * minimal mode (WIDGET-04) a silhouette, so neither a face nor initials
 * reach the home screen.
 *
 * Decorative: the name is always written beside it, as in the app.
 */
@Composable
private fun PersonAvatar(person: WidgetPerson, sizeDp: Int) {
    val circle = GlanceModifier.size(sizeDp.dp)
    val face = person.face
    val photo = face?.photo
    val monogram = face?.monogram
    when {
        photo != null -> Image(
            provider = ImageProvider(photo),
            contentDescription = null,
            modifier = circle,
        )

        face != null && monogram != null -> Box(
            modifier = circle.background(
                ImageProvider(R.drawable.widget_shape_circle),
                colorFilter = ColorFilter.tint(
                    ColorProvider(
                        day = face.colors.backgroundDay,
                        night = face.colors.backgroundNight,
                    ),
                ),
            ),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(monogram),
                contentDescription = null,
                modifier = GlanceModifier.size(sizeDp.dp),
                colorFilter = ColorFilter.tint(
                    ColorProvider(
                        day = face.colors.foregroundDay,
                        night = face.colors.foregroundNight,
                    ),
                ),
            )
        }

        else -> Box(
            modifier = circle.background(
                ImageProvider(R.drawable.widget_shape_circle),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.surfaceVariant),
            ),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ph_user),
                contentDescription = null,
                modifier = GlanceModifier.size((sizeDp / 2).dp),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
            )
        }
    }
}

/** A quiet 1dp rule between the lead and the others. */
@Composable
private fun Divider() {
    Spacer(GlanceModifier.width(WidgetSpacing.x2.dp))
    Box(
        modifier = GlanceModifier
            .width(1.dp)
            .fillMaxHeight()
            .background(GlanceTheme.colors.outline),
    ) {}
    Spacer(GlanceModifier.width(WidgetSpacing.x3.dp))
}

// ─── Empty state ─────────────────────────────────────────────────────────────

/**
 * WIDGET-10: nobody to suggest right now, said like a friend: "All quiet for
 * now." Not "caught up" or "no one due": Orbit always recommends someone
 * (HOME-6), so an empty widget is a lull, not a finish line, and counts and
 * deadlines are the vocabulary the app retired. The Orbit glyph above it, in
 * the muted tone, so the empty widget is still recognisably Orbit. A tap opens
 * the app.
 *
 * It shows only when no list can surface anyone: no lists yet, or everyone
 * paused. The widget does not add "who comes up next": its source,
 * WidgetSurfaceUseCase, reads nothing but SurfaceNextUseCase on purpose, and
 * a second query for upcoming people would be a second copy of its filters.
 */
@Composable
private fun EmptyState(onOpenApp: Action) {
    val context = LocalContext.current
    val text = context.getString(R.string.widget_empty)
    // One whole sentence for TalkBack, not the two strings glued in code.
    val description = context.getString(R.string.widget_empty_a11y)
    val compact = LocalSize.current.height < WidgetBreakpoints.Compact.height
    WidgetFrame(
        modifier = GlanceModifier
            .clickable(onOpenApp)
            .semantics { contentDescription = description },
        strip = compact,
    ) {
        val glyph: @Composable () -> Unit = {
            Image(
                provider = ImageProvider(R.drawable.ic_notification),
                contentDescription = null,
                modifier = GlanceModifier.size(WidgetSizes.glyph.dp),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
            )
        }
        val words: @Composable () -> Unit = {
            Text(
                text = text,
                style = OrbitWidgetTextStyles.body.copy(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                ),
                maxLines = if (compact) 1 else 2,
            )
        }
        if (compact) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                glyph()
                Spacer(GlanceModifier.width(WidgetSpacing.x3.dp))
                words()
            }
        } else {
            Column(
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                glyph()
                Spacer(GlanceModifier.height(WidgetSpacing.x2.dp))
                words()
            }
        }
    }
}
