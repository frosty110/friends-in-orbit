package app.orbit.ui.screens.picker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.orbit.ui.components.PhIcon
import app.orbit.ui.theme.OrbitTheme

/**
 * Empty-device-contacts state.
 *
 * Distinct from [PermissionDeniedEmpty]: this state renders when READ_CONTACTS
 * IS granted but the device address book has zero phone-bearing contacts.
 * No CTA — this is pure information (the user cannot fix it from inside the app).
 *
 * Locked copy:
 *   - Heading: "No contacts on this device"
 *   - Body:    "Add people to your phone's contacts, then come back here."
 */
@Composable
fun EmptyDeviceContacts(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(OrbitTheme.spacing.x6),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PhIcon(
            name = "users",
            size = OrbitTheme.spacing.x6,
            tint = OrbitTheme.colors.fgMuted,
        )
        Spacer(Modifier.height(OrbitTheme.spacing.x3))
        Text(
            text = "No contacts on this device",
            style = OrbitTheme.type.h3,
            color = OrbitTheme.colors.fg,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(OrbitTheme.spacing.x2))
        Text(
            text = "Add people to your phone's contacts, then come back here.",
            style = OrbitTheme.type.body,
            color = OrbitTheme.colors.fgMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 280.dp),
        )
    }
}

@Preview(name = "EmptyDeviceContacts — light", showBackground = true)
@Composable
private fun EmptyDeviceContactsPreviewLight() {
    OrbitTheme(darkTheme = false) {
        EmptyDeviceContacts()
    }
}

@Preview(name = "EmptyDeviceContacts — dark", showBackground = true)
@Composable
private fun EmptyDeviceContactsPreviewDark() {
    OrbitTheme(darkTheme = true) {
        EmptyDeviceContacts()
    }
}
