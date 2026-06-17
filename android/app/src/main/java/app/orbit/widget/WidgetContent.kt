// android/app/src/main/java/app/orbit/widget/WidgetContent.kt
//
// Shared Glance composables used by OrbitWidget2x2 and OrbitWidget4x2.
// All composables are package-internal.
//
// THEME NOTE: All color access is via GlanceTheme.colors.* (M3 slot aliases)
// inside OrbitWidgetTheme. Zero Color(0x..) literals (THEME-02 grep-enforced).
// All text styles are OrbitWidgetTextStyles.* — zero fontSize = N.sp literals
// (THEME-02b grep-enforced). Spacing from WidgetSpacing.xN.dp.
//
// PHOTO LOADING NOTE (UI-SPEC §Minimal Mode avatar note): Glance does not
// support Coil or any third-party image loader at compose time for v1. Even
// when photoUri is non-null, the avatar renders the first-initial fallback.
// Minimal mode replaces the initial with a bundled silhouette (person icon,
// res/drawable/widget_ic_person — review WR-08).
//
// TAP-TO-DIAL: uses actionStartActivity(Intent(ACTION_DIAL)) — Glance
// generates FLAG_IMMUTABLE PendingIntents. No CALL_PHONE (PRIV-05).
package app.orbit.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.background
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
import app.orbit.MainActivity
import app.orbit.R
import app.orbit.data.entity.ContactEntity
import app.orbit.domain.usecase.WidgetSurfaceData
import app.orbit.ui.theme.OrbitWidgetTextStyles
import app.orbit.ui.theme.WidgetSpacing

// ─── Intent helpers ──────────────────────────────────────────────────────────

/**
 * Builds an ACTION_DIAL intent for the given contact. Uses [ContactEntity.phoneNumber]
 * (the display-number field) as the dialer data URI.
 *
 * [Uri.fromParts] (not `Uri.parse`) so numbers containing `#` or wait/pause
 * characters (`555-1234,,123#`) are opaque-encoded instead of being truncated
 * at the URI fragment delimiter (review WR-04).
 *
 * Never uses CALL_PHONE or ACTION_CALL (PRIV-05). Glance wraps this in a
 * FLAG_IMMUTABLE PendingIntent via actionStartActivity.
 */
fun dialIntent(contact: ContactEntity): Intent =
    Intent(Intent.ACTION_DIAL).apply {
        data = Uri.fromParts("tel", contact.phoneNumber, null)
    }

/**
 * Opens Orbit's Home screen (default start destination) — used by the empty
 * state tap target. No NAVIGATE_TO extra; MainActivity starts normally and
 * lands on Home per NavGraph default.
 */
fun openHomeIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java)

// ─── Avatar ──────────────────────────────────────────────────────────────────

/**
 * Circular avatar at [sizeDp]dp.
 *
 * In minimal mode: renders the bundled person-silhouette vector
 * ([R.drawable.widget_ic_person]) to avoid leaking the contact's initial on
 * the home screen (T-11-04 / WIDGET-04).
 *
 * In normal mode: renders the contact's first initial on a surfaceVariant
 * background. Glance does not load Coil/photoUri at compose time for v1 —
 * even when [photoUri] is non-null the initial fallback is rendered
 * (UI-SPEC §Minimal Mode avatar note). Production-fidelity photo loading
 * is a v1.1 deferred item.
 */
@androidx.compose.runtime.Composable
fun ContactAvatar(
    displayName: String,
    photoUri: String?,
    minimalMode: Boolean,
    sizeDp: Int,
    modifier: GlanceModifier = GlanceModifier,
) {
    if (minimalMode) {
        // Silhouette: no name or photo visible — T-11-04 mitigation (WIDGET-04)
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .size(sizeDp.dp)
                .background(GlanceTheme.colors.surfaceVariant)
                .semantics { contentDescription = "Contact" },
        ) {
            Image(
                // Bundled person vector (review WR-08) — legacy system icons
                // are OEM-restyled and not guaranteed to be a silhouette.
                provider = ImageProvider(R.drawable.widget_ic_person),
                contentDescription = null,
                modifier = GlanceModifier.size((sizeDp / 2).dp),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
            )
        }
    } else {
        // Initial fallback — photoUri ignored in v1 (Glance photo-loading limitation)
        val initial = displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .size(sizeDp.dp)
                .background(GlanceTheme.colors.surfaceVariant)
                .semantics { contentDescription = displayName },
        ) {
            Text(
                text = initial,
                style = OrbitWidgetTextStyles.contactName,
            )
        }
    }
}

// ─── 2×2 Card ────────────────────────────────────────────────────────────────

/**
 * Full 2×2 card body. Root column is one tap target that launches ACTION_DIAL.
 *
 * Layout (UI-SPEC §2×2 Layout Contract):
 *   Avatar 44dp → 8dp gap → Name (or "Contact" in minimal mode) → 8dp gap → Phone icon
 *
 * Per WIDGET-04: in minimal mode the name is "Contact" and the avatar is a silhouette.
 */
@androidx.compose.runtime.Composable
fun ContactCard2x2(
    contact: ContactEntity,
    minimalMode: Boolean,
) {
    val displayedName = if (minimalMode) "Contact" else contact.displayName
    val callDescription = "Call ${if (minimalMode) "contact" else contact.displayName}"

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.surface)
            .padding(WidgetSpacing.x3.dp)
            .clickable(actionStartActivity(dialIntent(contact))),
        verticalAlignment = Alignment.Vertical.CenterVertically,
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
    ) {
        ContactAvatar(
            displayName = contact.displayName,
            photoUri = contact.photoUri,
            minimalMode = minimalMode,
            sizeDp = 44,
        )
        Spacer(GlanceModifier.height(WidgetSpacing.x2.dp))
        Text(
            text = displayedName,
            style = OrbitWidgetTextStyles.contactName.copy(
                color = GlanceTheme.colors.onSurface,
            ),
        )
        Spacer(GlanceModifier.height(WidgetSpacing.x2.dp))
        // Phone icon — accent tint (UI-SPEC §Color: accent reserved for call CTA)
        Image(
            provider = ImageProvider(R.drawable.widget_ic_call),
            contentDescription = callDescription,
            modifier = GlanceModifier.size(24.dp),
            colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
        )
    }
}

// ─── Empty state ─────────────────────────────────────────────────────────────

/**
 * "No one due" empty state. Displayed when [WidgetSurfaceData.primary] is null.
 * Tapping opens Orbit's Home screen via [onOpenApp].
 *
 * Copy locked per UI-SPEC §Copywriting Contract: "No one due" (sentence case,
 * no exclamation, no gamification language). Icon below text is 24dp.
 */
@androidx.compose.runtime.Composable
fun EmptyCaughtUpState(
    onOpenApp: Action,
) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.surface)
            .padding(WidgetSpacing.x3.dp)
            .clickable(onOpenApp)
            .semantics { contentDescription = "No one due. Tap to open Orbit." },
        verticalAlignment = Alignment.Vertical.CenterVertically,
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
    ) {
        Text(
            text = "No one due",
            style = OrbitWidgetTextStyles.meta.copy(
                color = GlanceTheme.colors.onSurfaceVariant,
            ),
        )
        // Empty-state icon: 24dp per UI-SPEC §2×2 Layout Contract.
        // Bundled info vector (review WR-08), tinted muted — no accent here.
        Spacer(GlanceModifier.height(WidgetSpacing.x2.dp))
        Image(
            provider = ImageProvider(R.drawable.widget_ic_info),
            contentDescription = null,
            modifier = GlanceModifier.size(24.dp),
            colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
        )
    }
}

// ─── WidgetBody2x2 (testable seam) ───────────────────────────────────────────

/**
 * Pure composable body for the 2×2 widget — extracted so tests can exercise
 * the ContactCard2x2 / EmptyCaughtUpState branch without standing up the full
 * Glance widget or the Hilt EntryPoint. Both [OrbitWidget2x2Test] and
 * [MinimalModeTest] call this function directly.
 *
 * Design: JVM-only test seam. provideGlance resolves [data] and [minimalMode]
 * via WidgetEntryPoint, then delegates to this function. Tests inject fake data
 * without needing a real Context or WidgetEntryPoint.
 */
@androidx.compose.runtime.Composable
fun WidgetBody2x2(
    data: WidgetSurfaceData,
    minimalMode: Boolean,
    onOpenApp: Action,
) {
    if (data.primary == null) {
        EmptyCaughtUpState(onOpenApp = onOpenApp)
    } else {
        ContactCard2x2(contact = data.primary, minimalMode = minimalMode)
    }
}

// ─── 4×2 composables ─────────────────────────────────────────────────────────

/**
 * Full 4×2 widget body — static three-column layout: no LazyRow/horizontal
 * scroll exists in Glance 1.1.1, so the layout ships static. Swipe-between is
 * deferred to v1.1.
 *
 * Layout (UI-SPEC §4×2 Layout Contract):
 *   Primary card (~60% left, defaultWeight) | 1dp divider | Alternatives column (~40% right, defaultWeight)
 *
 * Full-width branch (review WR-03): when [alternatives] is empty the divider
 * and right column collapse and the single pane (primary card, or the empty
 * state when [primary] is null) takes the full widget width. This branch is
 * mirrored by [Widget4x2State.isFullWidth] — keep the two predicates in sync.
 *
 * Per WIDGET-04: in minimal mode every name is "Contact" and every avatar is
 * a silhouette. Per WIDGET-02: each card is individually tap-to-dial.
 *
 * [onOpenApp] is the Action to open Orbit Home (used when primary is null).
 * Threading it in keeps this composable Context-free (Glance composables
 * should not capture Context directly).
 */
@androidx.compose.runtime.Composable
fun OrbitWidget4x2Content(
    primary: ContactEntity?,
    alternatives: List<ContactEntity>,
    minimalMode: Boolean,
    onOpenApp: Action,
) {
    if (alternatives.isEmpty()) {
        // Full-width branch (review WR-03): no divider, no right column —
        // the single pane spans the whole widget. Covers both the
        // zero-alternatives case and primary == null (the use case never
        // emits alternatives without a primary).
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.background)
                .padding(WidgetSpacing.x2.dp),
        ) {
            if (primary == null) {
                EmptyCaughtUpState(onOpenApp = onOpenApp)
            } else {
                PrimaryCard(contact = primary, minimalMode = minimalMode)
            }
        }
        return
    }

    Row(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.background)
            .padding(WidgetSpacing.x2.dp),
    ) {
        // Primary column — takes the left half via defaultWeight
        Box(modifier = GlanceModifier.defaultWeight()) {
            if (primary == null) {
                EmptyCaughtUpState(onOpenApp = onOpenApp)
            } else {
                PrimaryCard(contact = primary, minimalMode = minimalMode)
            }
        }

        // 1dp vertical divider (UI-SPEC §4×2 Layout §Color secondary semantic)
        Box(
            modifier = GlanceModifier
                .fillMaxHeight()
                .width(1.dp)
                .background(GlanceTheme.colors.outline),
        ) {}

        // Alternatives column — takes the right half via defaultWeight
        Column(modifier = GlanceModifier.defaultWeight()) {
            alternatives.forEachIndexed { i, alt ->
                if (i > 0) Spacer(GlanceModifier.height(WidgetSpacing.x1.dp))
                AlternativeCard(contact = alt, minimalMode = minimalMode)
            }
        }
    }
}

/**
 * Primary card in the 4×2 widget — left column, full height.
 *
 * Mirrors [ContactCard2x2] structure but lives inside the 4×2 layout weight.
 * Per UI-SPEC §Color: accent (primary) is reserved for the call icon here only.
 */
@androidx.compose.runtime.Composable
fun PrimaryCard(
    contact: ContactEntity,
    minimalMode: Boolean,
) {
    val displayedName = if (minimalMode) "Contact" else contact.displayName
    val callDescription = "Call ${if (minimalMode) "contact" else contact.displayName}"

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.surface)
            .padding(WidgetSpacing.x4.dp)
            .clickable(actionStartActivity(dialIntent(contact))),
        verticalAlignment = Alignment.Vertical.CenterVertically,
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
    ) {
        ContactAvatar(
            displayName = contact.displayName,
            photoUri = contact.photoUri,
            minimalMode = minimalMode,
            sizeDp = 44,
        )
        Spacer(GlanceModifier.height(WidgetSpacing.x2.dp))
        Text(
            text = displayedName,
            style = OrbitWidgetTextStyles.contactName.copy(
                color = GlanceTheme.colors.onSurface,
            ),
        )
        Spacer(GlanceModifier.height(WidgetSpacing.x2.dp))
        // Phone icon — accent tint (UI-SPEC §Color: accent reserved for primary call CTA)
        Image(
            provider = ImageProvider(R.drawable.widget_ic_call),
            contentDescription = callDescription,
            modifier = GlanceModifier.size(24.dp),
            colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
        )
    }
}

/**
 * Alternative card in the 4×2 widget — stacked in the right column.
 *
 * Per UI-SPEC §Spacing exceptions: min height ≥48dp tap floor. Per UI-SPEC
 * §Color: no accent on alternative cards — text uses onSurfaceVariant.
 *
 * In minimal mode the name is "Contact" (WIDGET-04) and the avatar is a
 * silhouette. Content description explicitly states "Call contact" in minimal
 * mode to avoid leaking the real name through a11y.
 */
@androidx.compose.runtime.Composable
fun AlternativeCard(
    contact: ContactEntity,
    minimalMode: Boolean,
) {
    val displayedName = if (minimalMode) "Contact" else contact.displayName
    val callDescription = "Call ${if (minimalMode) "contact" else contact.displayName}"

    Row(
        modifier = GlanceModifier
            // fillMaxWidth within the alternatives column so the tap target
            // spans the full column (≥48dp floor, review WR-07); height min
            // 48dp via padding
            .fillMaxWidth()
            .padding(horizontal = WidgetSpacing.x2.dp, vertical = WidgetSpacing.x2.dp)
            .clickable(actionStartActivity(dialIntent(contact)))
            .semantics { contentDescription = callDescription },
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        ContactAvatar(
            displayName = contact.displayName,
            photoUri = contact.photoUri,
            minimalMode = minimalMode,
            sizeDp = 32,
        )
        Spacer(GlanceModifier.width(WidgetSpacing.x2.dp))
        Text(
            text = displayedName,
            style = OrbitWidgetTextStyles.meta.copy(
                color = GlanceTheme.colors.onSurfaceVariant,
            ),
        )
    }
}

// ─── WidgetBody4x2 (testable seam) ───────────────────────────────────────────

/**
 * Pure composable body for the 4×2 widget — extracted so tests can exercise
 * the three-column layout branch without standing up the full Glance widget
 * or the Hilt EntryPoint. [OrbitWidget4x2Test] and [MinimalModeTest] call this
 * function directly via [selectWidget4x2State].
 *
 * Design: JVM-only test seam mirroring [WidgetBody2x2].
 */
@androidx.compose.runtime.Composable
fun WidgetBody4x2(
    data: WidgetSurfaceData,
    minimalMode: Boolean,
    onOpenApp: Action,
) {
    OrbitWidget4x2Content(
        primary = data.primary,
        alternatives = data.alternatives,
        minimalMode = minimalMode,
        onOpenApp = onOpenApp,
    )
}
