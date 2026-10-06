package app.orbit.ui.screens.onboarding

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.orbit.R
import app.orbit.ui.components.OrbitButton
import app.orbit.ui.components.OrbitButtonVariant
import app.orbit.ui.components.PhIcon
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.util.asString

/**
 * Onboarding-done confirmation. The `OnboardingDoneViewModel.init`
 * block has already fired the `setOnboardingComplete(true)` write by the
 * time this composes; the CTA is enabled once the write completes.
 *
 * No back button (returning to onboarding after completion is wrong UX
 * and would also let the user rerun the welcome flow on a "completed"
 * install). No step counter (Done is framing, not counted).
 */
@Composable
fun OnboardingDoneScreen(
    onFinish: () -> Unit,
    vm: OnboardingDoneViewModel = hiltViewModel(),
) {
    val completed by vm.completed.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // The VM's one-off messages (a failed "asked once" write) show over the
    // content through the scaffold's host; repeatOnLifecycle(STARTED) so a
    // backgrounded screen does not queue them.
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.snackbarEvents.collect { event ->
                snackbarHostState.showSnackbar(event.message.asString(context))
            }
        }
    }
    // ONB-30: the notifications ask lives here, after the first list exists,
    // instead of as a third permission screen before the user saw anyone.
    // Screen-local, one owner (rules.md Code 7).
    var nudges by rememberSaveable {
        mutableStateOf(
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                NudgeAsk.On
            } else {
                NudgeAsk.Ask
            },
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // The OS has now been asked once. Settings reads this flag to tell a
        // never-asked permission (offer Allow) from one turned off in the
        // phone's settings (SET-12); without it a decline here read as
        // "never asked" there.
        vm.onNudgeLauncherFired()
        nudges = if (granted) NudgeAsk.On else NudgeAsk.Declined
    }
    OnboardingDoneContent(
        completed = completed,
        onFinish = onFinish,
        nudges = nudges,
        onAllowNudges = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) },
        snackbarHostState = snackbarHostState,
    )
}

/** Where the Done screen's nudge ask stands. */
internal enum class NudgeAsk { Ask, On, Declined }

/**
 * Stateless inner extracted so `@PreviewLightDark` +
 * `@PreviewFontScale` (D-06) can render without `hiltViewModel()` /
 * `collectAsStateWithLifecycle()` at preview time, and so
 * `OnboardingDoneScreenTest` can pin the nudge ask and the CTA gate (ONB-30,
 * ONB-23) on the JVM.
 */
@Composable
internal fun OnboardingDoneContent(
    completed: Boolean,
    onFinish: () -> Unit,
    nudges: NudgeAsk = NudgeAsk.Ask,
    onAllowNudges: () -> Unit = {},
    snackbarHostState: SnackbarHostState? = null,
) {
    OnboardingScaffold(
        title = stringResource(R.string.onb_done_title),
        step = null,
        onBack = null,
        primary = OnboardingAction(
            label = stringResource(R.string.onb_done_cta),
            onClick = onFinish,
            enabled = completed,
        ),
        snackbarHostState = snackbarHostState,
    ) {
        Spacer(Modifier.height(OrbitTheme.spacing.x10))

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(72.dp)
                    .clip(OrbitTheme.shapes.full)
                    .background(OrbitTheme.colors.positiveTint),
            ) {
                // positiveText, not positive: the glyph must clear 3:1 on its tint.
                PhIcon(name = "check", size = 32.dp, tint = OrbitTheme.colors.positiveText)
            }
            Spacer(Modifier.height(OrbitTheme.spacing.x6))
            Text(
                text = stringResource(R.string.onb_done_title),
                style = OrbitTheme.type.title.copy(color = OrbitTheme.colors.fg),
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(OrbitTheme.spacing.x3))
            // Teach the core loop (one card, yes or no), not list-browsing.
            Text(
                text = stringResource(R.string.onb_done_body),
                style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fgMuted),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = OrbitTheme.spacing.x4),
            )
            Spacer(Modifier.height(OrbitTheme.spacing.x8))
            SwipeHint()
            Spacer(Modifier.height(OrbitTheme.spacing.x8))
            NudgeAskCard(state = nudges, onAllow = onAllowNudges)
        }
    }
}

/**
 * ONB-30: the one place onboarding asks for notifications, after the user has
 * a list and has just read what Orbit does. A Secondary button, so "Open
 * Orbit" keeps the screen's single accent. Once answered it becomes a plain
 * line, never a second ask.
 */
@Composable
private fun NudgeAskCard(state: NudgeAsk, onAllow: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clip(OrbitTheme.shapes.lg)
            .background(OrbitTheme.colors.surface)
            .padding(OrbitTheme.spacing.x4),
    ) {
        PhIcon(name = "bell", size = 22.dp, tint = OrbitTheme.colors.fgMuted)
        Spacer(Modifier.height(OrbitTheme.spacing.x2))
        Text(
            text = stringResource(
                when (state) {
                    NudgeAsk.Ask -> R.string.onb_done_nudge_ask
                    NudgeAsk.On -> R.string.onb_done_nudge_on
                    NudgeAsk.Declined -> R.string.onb_done_nudge_declined
                },
            ),
            style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fg),
            textAlign = TextAlign.Center,
        )
        if (state == NudgeAsk.Ask) {
            Spacer(Modifier.height(OrbitTheme.spacing.x3))
            OrbitButton(
                text = stringResource(R.string.onb_done_allow_nudges),
                onClick = onAllow,
                variant = OrbitButtonVariant.Secondary,
            )
        }
    }
}

/**
 * One quiet teaching beat for the swipe mechanic: a mini stand-in card
 * flanked by the two swipe directions, with
 * the same labels Card View commits under ("Later" left, "Sooner" right).
 * Static, muted tones only — the "Open Orbit" CTA below keeps the screen's
 * single accent element.
 */
@Composable
private fun SwipeHint() {
    val swipeDescription = stringResource(R.string.onb_done_swipe_a11y)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x5),
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = swipeDescription
        },
    ) {
        // The same words Card view commits under (strings_card.xml).
        SwipeHintSide(icon = "arrow-left", label = stringResource(R.string.card_later))
        // Mini card: a quiet surface with an avatar dot and a name-length
        // bar — just enough to read as "a person's card".
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x2),
            modifier = Modifier
                .clip(OrbitTheme.shapes.lg)
                .background(OrbitTheme.colors.surface)
                .border(1.dp, OrbitTheme.colors.lineSoft, OrbitTheme.shapes.lg)
                .padding(horizontal = OrbitTheme.spacing.x5, vertical = OrbitTheme.spacing.x4),
        ) {
            Box(
                Modifier
                    .size(24.dp)
                    .clip(OrbitTheme.shapes.full)
                    .background(OrbitTheme.colors.bgSubtle),
            )
            Box(
                Modifier
                    .size(width = 40.dp, height = 6.dp)
                    .clip(OrbitTheme.shapes.full)
                    .background(OrbitTheme.colors.lineSoft),
            )
        }
        SwipeHintSide(icon = "arrow-right", label = stringResource(R.string.card_sooner))
    }
}

@Composable
private fun SwipeHintSide(icon: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        PhIcon(name = icon, size = 16.dp, tint = OrbitTheme.colors.fgMuted)
        Spacer(Modifier.height(OrbitTheme.spacing.x1))
        Text(
            text = label,
            style = OrbitTheme.type.micro.copy(color = OrbitTheme.colors.fgMuted),
        )
    }
}

@PreviewLightDark
@PreviewFontScale
@Composable
private fun OnboardingDoneContentPreview() {
    OrbitTheme {
        OnboardingDoneContent(
            completed = true,
            onFinish = {},
        )
    }
}

@PreviewLightDark
@Composable
private fun OnboardingDoneNudgesOnPreview() {
    OrbitTheme {
        OnboardingDoneContent(completed = true, onFinish = {}, nudges = NudgeAsk.On)
    }
}
