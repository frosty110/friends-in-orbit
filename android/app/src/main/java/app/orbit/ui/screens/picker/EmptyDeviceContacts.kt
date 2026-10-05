package app.orbit.ui.screens.picker

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import app.orbit.R
import app.orbit.ui.components.OrbitScreenMessage
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
    // The shared people-screen message (2026-10-05), so every empty state on
    // these screens has one layout.
    OrbitScreenMessage(
        icon = "users",
        title = stringResource(R.string.picker_no_device_contacts_title),
        body = stringResource(R.string.picker_no_device_contacts_body),
        modifier = modifier,
    )
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
