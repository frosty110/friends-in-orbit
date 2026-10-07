# Ignored

**Route:** `settings/ignored`
**Group:** Settings & data
**Status:** active
**Last reviewed:** 2026-10-06
**Spec:** [settings](../settings/README.md): IGNORE-01 to IGNORE-10 (defined this round); PRIV-03 in [privacy-and-lock](../privacy-and-lock/README.md)

---

## Reached from

- Settings: the "Ignored" row, and nowhere else

## What the user sees

- App bar: Back and "Ignored"
- A line of reassurance, in both states, in the glossary's words: "Ignored people don't come up on your lists or in nudges. Their calls and notes are kept."
- One row per ignored person, newest first: face, name, "Ignored 3 days ago", and "Unignore"
- No control on the page is in the accent

## Actions and menus

- "Unignore": they come back to their lists and nudges at once; "Unignored {name}" with Undo, which ignores them again. If the unignore or the Undo cannot be written, "Couldn't save your change", with no Undo, and nothing changes

## States

- Loading: the app bar, then a quiet skeleton; never "No one ignored" before the rows are known
- Empty: "No one ignored" with the reassurance line
- Error: "Couldn't load ignored people" / "Nothing is lost. Try again in a moment." with Try again
- Privacy curtain: names read "Contact" and no photos are shown (PRIV-03)

## Leads to

- Back returns to Settings

## Tests that pin it

- `SettingsIgnoredViewModelTest` (rows newest first, unignore and undo and their failures, Error and recovery)
- `UnignoreContactUseCaseTest`, `IgnoreContactUseCaseTest`, `ContactDaoIgnoredTest`
- Gallery previews: `SettingsIgnoredContentPreview`, the Ready with two people, Loading and Error previews added this round, with the curtain pass
