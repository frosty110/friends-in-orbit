package app.orbit.ui.screens.onboarding

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.orbit.ui.components.PhIcon
import app.orbit.ui.theme.OrbitTheme
import app.orbit.ui.theme.orbitCardShadow

/**
 * Reusable permission rationale screen. Drives all three permission
 * onboarding steps (call log, contacts, notifications) — each route is a
 * thin caller that supplies the copy and the manifest constant.
 *
 * Pain-points addressed:
 *   - #1 dead-end disabled Continue → secondary "Continue without it"
 *     path is always available. Granted lifts primary to "Continue";
 *     denied keeps both options visible with a warm degraded-mode message.
 *   - #2 mystery permission ask → step-specific copy explaining the
 *     concrete user benefit + read-only-on-device promise.
 *   - #3 unrecoverable denial → "permanently denied" detection (Android's
 *     `shouldShowRequestPermissionRationale` returns false after the
 *     second-and-final deny) routes to `ACTION_APPLICATION_DETAILS_SETTINGS`.
 *   - #10 permission re-prompt loop → ON_RESUME re-reads granted status so
 *     a flip in Settings reflects without re-launching the launcher.
 *
 * Voice: sentence case, no exclamation marks, no "Awesome!"
 */
@Composable
fun OnboardingPermScreen(
    step: OnboardingStep,
    permission: String,
    skipPermission: SkipPermission,
    iconName: String,
    title: String,
    body: String,
    promiseTitle: String,
    promise: String,
    deniedNote: String,
    granted: Boolean,
    hasBeenAsked: Boolean,
    onRefresh: () -> Unit,
    onLauncherFired: () -> Unit,
    onBack: () -> Unit,
    onContinue: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        // F-2 fix (2026-04-30 hot-fix-260430-hs4) — flip the persisted
        // hasBeenAsked flag BEFORE refreshing UI state so the recomposed
        // isPermanentlyDenied recompute sees the new flag value.
        onLauncherFired()
        onRefresh()
    }

    var permanentlyDenied by remember { mutableStateOf(false) }
    var showSkipDialog by remember { mutableStateOf(false) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                onRefresh()
                permanentlyDenied = isPermanentlyDenied(activity, permission, granted, hasBeenAsked)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(granted, hasBeenAsked) {
        permanentlyDenied = isPermanentlyDenied(activity, permission, granted, hasBeenAsked)
    }

    OnboardingScaffold(
        step = step,
        onBack = onBack,
        primary = OnboardingAction(
            label = when {
                granted -> "Continue"
                permanentlyDenied -> "Open settings"
                else -> "Allow access"
            },
            onClick = {
                when {
                    granted -> onContinue()
                    permanentlyDenied -> openAppSettings(context)
                    else -> launcher.launch(permission)
                }
            },
        ),
        secondary = if (granted) null else OnboardingAction(
            label = "Continue without it",
            onClick = { showSkipDialog = true },
        ),
    ) {
        Text(
            text = title,
            style = OrbitTheme.type.title.copy(color = OrbitTheme.colors.fg),
        )
        Spacer(Modifier.height(OrbitTheme.spacing.x2))
        Text(
            text = body,
            style = OrbitTheme.type.body.copy(color = OrbitTheme.colors.fgMuted),
        )

        Spacer(Modifier.height(OrbitTheme.spacing.x6))

        if (permanentlyDenied) {
            PermanentlyDeniedBanner()
            Spacer(Modifier.height(OrbitTheme.spacing.x4))
        }

        PermissionExplainerCard(
            iconName = iconName,
            granted = granted,
            promiseTitle = promiseTitle,
            promise = promise,
            permanentlyDenied = permanentlyDenied,
            deniedNote = deniedNote,
        )
    }

    if (showSkipDialog) {
        OnboardingSkipDialog(
            permission = skipPermission,
            onConfirm = {
                showSkipDialog = false
                onContinue()
            },
            onDismiss = { showSkipDialog = false },
        )
    }
}

/**
 * ONB-14 — surfaced when [isPermanentlyDenied] returns true. Tells the user
 * exactly which three permissions to flip in their phone's settings. Copy
 * locked by 09-UI-SPEC §"Permanently-denied banner".
 */
@Composable
private fun PermanentlyDeniedBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(OrbitTheme.shapes.md)
            .background(OrbitTheme.colors.warningTint)
            .padding(OrbitTheme.spacing.x3),
    ) {
        Text(
            text = "Permissions → allow: Contacts, Call logs, Notifications",
            style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.warning),
        )
    }
}

@Composable
private fun PermissionExplainerCard(
    iconName: String,
    granted: Boolean,
    promiseTitle: String,
    promise: String,
    permanentlyDenied: Boolean,
    deniedNote: String,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(OrbitTheme.spacing.x3),
        modifier = Modifier
            .fillMaxWidth()
            .orbitCardShadow(OrbitTheme.shapes.lg, OrbitTheme.colors.isDark)
            .clip(OrbitTheme.shapes.lg)
            .background(OrbitTheme.colors.surface)
            .padding(OrbitTheme.spacing.x4),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .clip(OrbitTheme.shapes.md)
                .background(if (granted) OrbitTheme.colors.positiveTint else OrbitTheme.colors.accentTint),
        ) {
            PhIcon(
                name = if (granted) "check" else iconName,
                size = 20.dp,
                tint = if (granted) OrbitTheme.colors.positive else OrbitTheme.colors.accentPress,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = when {
                    granted -> "Allowed"
                    permanentlyDenied -> "Denied — change in settings"
                    else -> promiseTitle
                },
                style = OrbitTheme.type.body.copy(
                    color = when {
                        granted -> OrbitTheme.colors.positive
                        permanentlyDenied -> OrbitTheme.colors.fgMuted
                        else -> OrbitTheme.colors.fg
                    },
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            Spacer(Modifier.height(OrbitTheme.spacing.x1))
            Text(
                text = if (granted || !permanentlyDenied) promise else deniedNote,
                style = OrbitTheme.type.meta.copy(color = OrbitTheme.colors.fgMuted),
            )
        }
    }
}

private fun isPermanentlyDenied(
    activity: Activity?,
    permission: String,
    granted: Boolean,
    hasBeenAsked: Boolean,
): Boolean {
    if (granted || activity == null) return false
    // F-2 fix (2026-04-30 hot-fix-260430-hs4): a permission is permanently
    // denied only if we have asked at least once AND
    // shouldShowRequestPermissionRationale returns false. The hasBeenAsked
    // flag is per-permission in AppPrefs; flipped by the launcher callback
    // above. Pre-fix, the never-asked case (first-launch) and the
    // don't-ask-again case were indistinguishable — both returned false
    // from shouldShowRequestPermissionRationale — so fresh-install users
    // saw the deep-link banner before the system dialog had ever fired.
    if (!hasBeenAsked) return false
    return !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
}

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    context.startActivity(intent)
}

@PreviewLightDark
@PreviewFontScale
@Composable
private fun OnboardingPermScreenPreview() {
    OrbitTheme {
        OnboardingPermScreen(
            step = OnboardingStep.PermContacts,
            permission = "android.permission.READ_CONTACTS",
            skipPermission = SkipPermission.Contacts,
            iconName = "users",
            title = "Build lists from your people",
            body = "Orbit reads your phone contacts so you can pick who goes on each list by name.",
            promiseTitle = "Stays on your device",
            promise = "We don't upload your address book.",
            deniedNote = "You can still create lists. To add contacts, allow access in your phone's settings.",
            granted = false,
            hasBeenAsked = false,
            onRefresh = {},
            onLauncherFired = {},
            onBack = {},
            onContinue = {},
        )
    }
}
