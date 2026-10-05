package app.orbit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.orbit.ui.theme.OrbitTheme

/**
 * The message a people screen shows in place of its list: nothing here yet,
 * nothing matches, Orbit can't see your calls, or the list couldn't load.
 * One layout for every such state on the people screens (Call history,
 * Browse, Search, the pickers), so they read as one app (rubric D4: "one
 * empty state") and every one of them offers the next step (rubric D6).
 *
 * The title says what is true, the body what it means or what to do, and the
 * optional action does it. Callers pick the action's weight: [actionVariant]
 * is Secondary by default so the message never spends a screen's single
 * accent (rules.md §Design 5) unless it is the only thing to do there, as on
 * an error or permission state.
 *
 * It scrolls rather than clips: at 200% font scale on a short window the body
 * can be taller than the space left under the app bar (rubric gate G3).
 */
@Composable
fun OrbitScreenMessage(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    icon: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    actionVariant: OrbitButtonVariant = OrbitButtonVariant.Secondary,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.fillMaxSize(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = OrbitTheme.spacing.x6, vertical = OrbitTheme.spacing.x8),
        ) {
            if (icon != null) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(OrbitTheme.spacing.x9)
                        .clip(CircleShape)
                        .background(OrbitTheme.colors.bgSubtle),
                ) {
                    PhIcon(name = icon, size = 28.dp, tint = OrbitTheme.colors.fgMuted)
                }
                Box(Modifier.size(OrbitTheme.spacing.x4))
            }
            Text(
                text = title,
                style = OrbitTheme.type.h3,
                color = OrbitTheme.colors.fg,
                textAlign = TextAlign.Center,
            )
            if (body != null) {
                Box(Modifier.size(OrbitTheme.spacing.x2))
                Text(
                    text = body,
                    style = OrbitTheme.type.body,
                    color = OrbitTheme.colors.fgMuted,
                    textAlign = TextAlign.Center,
                )
            }
            if (actionLabel != null && onAction != null) {
                Box(Modifier.size(OrbitTheme.spacing.x5))
                OrbitButton(
                    text = actionLabel,
                    onClick = onAction,
                    variant = actionVariant,
                )
            }
        }
    }
}

/**
 * A quiet stand-in for a list of people while it loads: muted circles and
 * bars shaped like the rows that will replace them, so nothing jumps when they
 * land. Static on purpose: no shimmer, because Orbit keeps idle surfaces still
 * (rules.md §Design 8).
 *
 * It exists so a screen never says something false while it waits. Browse used
 * to show "No one here yet" for a moment before a full list arrived (rubric
 * D6), and a blank screen reads as broken. TalkBack hears one "Loading" node
 * instead of a dozen unlabelled shapes.
 */
@Composable
fun OrbitListSkeleton(
    modifier: Modifier = Modifier,
    rows: Int = 6,
    showSectionLabel: Boolean = false,
    avatarSize: Dp = SkeletonAvatarSize,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = "Loading" }
            .padding(vertical = OrbitTheme.spacing.x2),
    ) {
        if (showSectionLabel) {
            SkeletonBar(
                widthFraction = 0.22f,
                height = OrbitTheme.spacing.x3,
                modifier = Modifier.padding(
                    horizontal = OrbitTheme.spacing.x4,
                    vertical = OrbitTheme.spacing.x2,
                ),
            )
        }
        repeat(rows) { index ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x3),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = OrbitTheme.spacing.tapMin)
                    .padding(horizontal = OrbitTheme.spacing.x4, vertical = OrbitTheme.spacing.x3),
            ) {
                Box(
                    modifier = Modifier
                        .size(avatarSize)
                        .clip(CircleShape)
                        .background(OrbitTheme.colors.bgSubtle),
                )
                Column(
                    verticalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x2),
                    modifier = Modifier.weight(1f),
                ) {
                    // Alternate widths so the block reads as a list of
                    // different names, not a barcode.
                    SkeletonBar(widthFraction = if (index % 2 == 0) 0.55f else 0.4f, height = OrbitTheme.spacing.x4)
                    SkeletonBar(widthFraction = if (index % 2 == 0) 0.35f else 0.45f, height = OrbitTheme.spacing.x3)
                }
            }
        }
    }
}

@Composable
private fun SkeletonBar(widthFraction: Float, height: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(OrbitTheme.shapes.sm)
            .background(OrbitTheme.colors.bgSubtle),
    )
}

/** The row avatar size used by Browse, Search and Call history rows. */
private val SkeletonAvatarSize = 44.dp

@PreviewLightDark
@PreviewFontScale
@Composable
private fun OrbitScreenMessagePreview() {
    OrbitTheme {
        Box(Modifier.background(OrbitTheme.colors.bg)) {
            OrbitScreenMessage(
                icon = "phone-slash",
                title = "Orbit can't see your calls",
                body = "Call history is read from your phone's call log, and Orbit doesn't have access to it right now.",
                actionLabel = "Open settings",
                onAction = {},
                actionVariant = OrbitButtonVariant.Primary,
            )
        }
    }
}

@PreviewLightDark
@Composable
private fun OrbitListSkeletonPreview() {
    OrbitTheme {
        Box(Modifier.background(OrbitTheme.colors.bg)) {
            OrbitListSkeleton(showSectionLabel = true)
        }
    }
}
