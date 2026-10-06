# Settings

**Route:** `settings`
**Group:** Settings & data
**Status:** active
**Last reviewed:** 2026-10-06
**Spec:** [settings](../settings/README.md): SET-09, SET-10, SET-11, SET-12, SET-13, SET-14 (the last three defined this round), EXPORT-01 to EXPORT-03 and PICK-07 (defined this round); [privacy-and-lock](../privacy-and-lock/README.md)

---

## Reached from

- Home: the Settings icon in the app bar
- "Open settings" on the call log notices of Card view, Browse, Contact detail and Call history

## What the user sees

- App bar: Back and "Settings"
- Groups, top to bottom:
  - "Appearance": "Theme" ("Pick a color that feels like you"): Warm, Cool, Forest, Plum, Mono, and Wallpaper, which follows the phone's wallpaper; "Light & dark": System, Light, Dark; "Accent": "Match theme" or "Custom accent" with a colour slider (TalkBack says the colour, "Teal")
  - "Permissions": Contacts, Call log and Notifications, each with its state: "Allowed"; "Not allowed" with "Allow"; or "Off in your phone's settings" with "Open phone settings", shown only once the phone was asked (SET-12). Notifications also read as off when the phone has them switched off for Orbit (SET-14)
  - "Contacts": "Sync now", with "Last synced 5 minutes ago" or "Never synced"
  - "Call history": call sync, which follows the Call log permission, with "Sync now" and when it last synced; "Import range" / "How far back to read": "1 month", "3 months", "6 months" or "1 year"; the "Call history" row ("Calls with the people in your contacts")
  - "People": "Groups when adding people" ("Where Commonly called, Rarely called and the others begin", SET-10); "Ignored", with "3 ignored" or "No one ignored"
  - "Data": "Export your data" ("An encrypted file, protected by a password"), "Import backup" ("Replace what's here with an exported file"), "Reset Orbit" ("Erase every list, person, and note on this phone")
  - "About": "Orbit" with "Version 1.4.0", "Send feedback", "Privacy policy" ("Read how Orbit handles your data"), the promise "Everything stays on your phone: no cloud, no tracking." (SET-13), "Source code", "Open source licenses" ("What we built on")
- Nothing on the page is in the accent: the Sync now buttons are secondary (a sync is maintenance, not the screen's job), and the dialogs' confirming buttons carry their own

## Actions and menus

- Theme, light and dark, and the accent apply live and persist as you change them; the widgets follow
- "Allow" asks the phone for the permission. "Open phone settings" opens Orbit's page in the phone's settings (for notifications switched off on Android 12, the phone's notification settings for Orbit). Granting Contacts or Call log, here or in the phone's settings, starts a sync at once
- "Sync now" reads "Syncing…" while it runs; the row then says when it last synced ("Just now", "5 minutes ago", "2 hours ago", then days)
- An import range chip persists at once; choosing a longer range re-reads the call log that far back
- "Groups when adding people" opens a dialog: "Groups when adding people" / "When you add people to a list, Orbit sorts your contacts into these groups. Choose where each one begins.", a stepper per group worded as a sentence ("Commonly called: the top 20%", "Rarely called: the bottom 30%", "Recently added: the last 30 days", and the long gap in days), "These two groups overlap: together they can't be more than 100%." when they do, and "Save" or "Cancel"; Save commits, Cancel discards, and rotation keeps the dialog open
- "Ignored" opens the Ignored screen; "Call history" opens Call history
- "Export your data" opens a sheet: "Export your data" / "We'll save an encrypted file of your lists, people, call history, notes, and custom schedules. Pick a strong password: we don't store it, and we can't recover it.", "Password" ("At least 8 characters."), "Type it again", "Export"; then the phone's save dialog; "Saved your encrypted backup." or "Couldn't save the file." (the row is the retry)
- "Import backup" opens the phone's file picker, then "Open your backup" / "Enter the password you chose when you exported this file.", then asks "Restore this backup?" / "This replaces everything in Orbit with the backup: 4 lists and 52 people. What's on this phone now will be erased." with "Replace" and "Cancel". Outcomes: "Backup restored.", "That file couldn't be read. Check it's an Orbit backup.", "This backup was made by a newer version of Orbit. Update Orbit first.", "Couldn't restore the backup. Nothing was changed."
- While an export, an import or a reset runs, Export, Import and Reset are disabled: the busy row reads "Saving…", "Checking the file…", "Restoring…" or "Resetting…", and the other two read "Waiting for the other backup step to finish"
- "Reset Orbit" asks "Reset Orbit?" / "Every list, person, note, and call record on this phone will be erased. Your phone's own contacts and call log are not touched." with "Reset" and "Cancel". While it runs the row reads "Resetting…" and Back is held. A reset finishes even if you leave the screen, clears the widgets at once, and restarts Orbit into onboarding, even if you had left Settings or Orbit was in the background when it finished; "Couldn't finish the reset. Try again." shows wherever you are if it could not
- "Send feedback" opens your mail app; "Privacy policy" and "Source code" open the browser; "Open source licenses" opens the list, with "Close"

## States

- Loading: the app bar alone, never a flash of default values (SET-09)
- Error: "Orbit couldn't load your settings" / "Nothing is lost. Try again in a moment." with Try again (SET-11)
- Each permission row shows one of its three states; the sync rows read "Never synced" before a first sync
- Privacy curtain: nothing on this screen names a person or a list, so nothing changes

## Leads to

- Ignored and Call history; Back returns here
- The phone's permission dialogs, Orbit's page in the phone's settings, the phone's save and open dialogs
- The browser ("Privacy policy", "Source code") and the mail app ("Send feedback")
- Onboarding's Welcome, after a reset
- Back returns to Home, or to the screen whose notice opened Settings

## Tests that pin it

- `SettingsViewModelTest` (permission states, including never asked and granted while away; a sync on grant; the import range and its re-read; Error and recovery; the reset as in flight, outliving the screen, and its failure staying off the screen's snackbar; appearance writes)
- `SettingsDataRowsTest` (what the disabled Data rows say during an export, an import and a reset)
- `PermissionRowActionTest`, `PickerThresholdsValidationTest`
- `ExportViewModelTest` (added this round), `ImportViewModelTest`, `ImportServiceTest`, `ResetServiceTest` (the wipe's order, and its outcome read after the fact), `AppViewModelTest` (the outcome handed to the Activity), `AppPrefsTest`, `PassphraseEncryptorTest`, `RelativeTimeTest` (the sync rows' wording)
- Gallery previews: `SettingsContentPreview`, `SettingsContentGrantedSyncingPreview`, `SettingsContentResettingPreview`, `AppearanceSectionPreview`, `PermissionsRowPreview`, `PickerThresholdsRowLightPreview`, `PickerThresholdsDialogLightPreview`, `ExportPassphraseSheetLightPreview`, `ImportPassphraseSheetLightPreview`, `ImportConfirmDialogPreview`, `AboutSectionPreview`, `LicensesDialogPreview`
