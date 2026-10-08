# ADR 0012: Text is typed where it is tapped, kept above the keyboard by one shared field

**Status:** accepted
**Date:** 2026-10-07
**Deciders:** the maintainer
**Supersedes:** none
**Refines:** `design/README.md` "Text input and the keyboard", `DESIGN.md`'s component
table, and every screen with a text field

## Context

The maintainer reported: "Sometimes when I click on an input box, it gets covered up by
the [keyboard]", and asked whether typing should instead happen in a separate input
shown above the keyboard.

`design/README.md` already promised that a focused field is always above the keyboard.
The app is edge-to-edge, so `OrbitScreen` pads by the keyboard's inset and relies on
Compose to scroll a focused field into what is left. In the Compose version the app
resolves (foundation 1.8.2), that relocation is a heuristic in
`ContentInViewNode.onRemeasured`:

- it **skips the keyboard's resize altogether while another bring-into-view scroll is
  still animating**, and tapping a field that needs scrolling starts one at the very
  moment the keyboard begins to slide;
- it relocates only a field that was **wholly visible just before** the resize;
- and tapping an unfocused field may scroll **only its cursor** into view, not the field
  (the open b/216790855 in `CoreTextField`).

So whether a field ended up under the keyboard depended on where it sat when tapped:
"sometimes". Nothing in the app could reproduce it on a device here (no emulator in the
cloud container), so the mechanism is read from the library source, not observed.

The fields themselves were in three styles (Material's filled field restyled, Material's
outlined field, hand-drawn `BasicTextField`s), each with its own keyboard handling. The
filled ones had no outline, which rules.md Design 4 requires at 3:1. Several had no name
for TalkBack, and the note fields drew an accent cursor on a screen whose one accent is
Call.

## Options

1. **A docked input above the keyboard for every field** (the maintainer's suggestion;
   Todoist's quick add). Always visible by construction. But you type away from the
   field, its label and the sentence it completes. The value has two copies with two
   writers (rules.md Code 7). Forms of more than one field (a password and its confirm)
   do not fit, and it is not how Android or iOS fields behave.
2. **Move each inline edit into a sheet.** Sheets handle the keyboard, but turning a
   rename or a note into a modal adds a step to every edit.
3. **Type where you tapped, and make "above the keyboard" deterministic.** One shared
   field asks its scrolling ancestor, once the keyboard has stopped moving, to show the
   whole field (label to helper line) plus breathing room. It no longer depends on
   timing or on where the field sat.

## Decision

**Option 3.** Every text field is `OrbitTextField`, which uses `keepAboveKeyboard`.

- **Label above the field, inside the same tap target and TalkBack node.** When a
  heading or a sentence right above already names it, the field takes that as its
  TalkBack name instead of repeating it on screen.
- **Outlined in `fgSubtle`**, the colour `ThemeContrastTest` holds at 3:1, over a
  `bgSubtle` fill. Focus draws a 2dp ink ring, an error a `danger` ring with its message
  below. The cursor is ink.
- **Keyboard defaults**: sentence capitalisation; Done on a single line, a newline on
  several; password fields use the password keyboard, with Next then Done. Where a form
  has one obvious action, Done performs it (export) once it is allowed,
  and otherwise only puts the keyboard away.
- A docked input stays the right pattern for one case this app does not have yet: a
  chat-style composer pinned to the bottom of a conversation.

## Consequences

- Covered fields stop depending on luck: after the keyboard settles (about 64ms), the
  whole field is brought into view with 16dp to spare.
- One look and one behaviour for every field. The `imePadding()` calls that sat inside
  `OrbitScreen` or inside Material sheets did nothing (both already consume the inset),
  and their comments said otherwise. The sheet ones are gone; the two picker screens keep
  theirs as documented guards.
- Snackbar hosts that sit outside `OrbitScreen` (Contact detail, Settings) pad for the
  navigation bar and the keyboard themselves.
- Two surfaces are not boxes and so are not `OrbitTextField`: search (`OrbitSearchField`)
  and the post-call note page's writing area (NOTE-04), which is the page itself and
  scrolls its own cursor into view inside a screen `OrbitScreen` already shrinks above
  the keyboard. The note page draws the ink cursor; search keeps its accent cursor. Both
  are named for TalkBack by their placeholder while empty. List settings' rename from
  the title uses the field's `TextFieldValue` form, so the cursor opens after the old
  name (added when the owner-review branch merged this ADR, 2026-10-08).

## Related

- `OrbitTextFieldTest`, `NewListContentTest` (New list's name step; it replaced the create sheet `CreateListContentTest` covered), `ExportPassphraseContentTest`.
- `design/README.md`, "Text input and the keyboard".
