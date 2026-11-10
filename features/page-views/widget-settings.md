# Widget settings

**Route:** none: a screen of its own, outside the app's navigation, opened for one placed widget (the launcher hands it that widget)
**Group:** Settings & data
**Status:** active
**Last reviewed:** 2026-11-09
**Spec:** [widgets](../widgets/README.md): WIDGET-12 to WIDGET-16 (defined this round); PRIV-03 and PRIV-04 in [privacy-and-lock](../privacy-and-lock/README.md)

---

## Reached from

- Placing either widget, "Next call" or "Call suggestions", on the home screen: the launcher opens it straight after the drop. Backing out keeps a working widget that suggests from every list
- The launcher's long-press on a placed widget, then its settings or reconfigure entry (where the launcher offers one)
- A widget that says "Choose a list", because its list was archived or deleted

## What the user sees

- App bar: Back and "Widget settings"
- "Up now on this widget": the face and name of the person this widget leads with, given the choices below. No Call button
- "Suggest people from": "Every list" with "Whoever is up next across your lists" beneath it, then each active list by name, in the order of Lists. The chosen one is tinted and checked. Archived lists are not offered
- "On the home screen": "Show names and faces", a switch, on by default. Off, "Shows "Contact" and a plain circle instead" appears beneath it
- "Done" at the foot, the screen's one accent element

## Actions and menus

- Choosing a list, or "Every list": saved at once, and this widget redraws within a second or two. The preview changes to that list's next person. Other widgets are untouched
- "Show names and faces" off: saved at once; this widget writes "Contact" and draws a plain circle for every person, in text and to TalkBack, and so does the preview. On again restores names and faces
- If a choice cannot be saved: "Couldn't save that. Try again.", and the previous choice stays selected
- "Done" and Back close the screen. There is nothing to confirm: every choice was saved when it was made

## States

- Loading: the app bar and Done, then the sections once the lists are read
- No lists yet (placed before onboarding finishes, or every list archived): only "Every list", with "Your lists will show up here once you make one." beneath it, and "All quiet for now." in the preview
- Nobody to suggest right now: "All quiet for now." in the preview
- The chosen list was archived or deleted: "This widget's list is archived or deleted. Choose another below." in the preview, and no list is checked
- Privacy curtain: list names read "List", and the preview person reads "Contact" with no photo (PRIV-03). Release builds keep the screen out of screenshots and the app switcher (PRIV-04)

## Leads to

- Done or Back returns to the home screen, or to wherever the launcher opened it from. It never opens the rest of Orbit

## Tests that pin it

- `WidgetSettingsViewModelTest` (lists offered in order, a choice saves and redraws this widget, hidden names mask the preview, an archived choice, no lists, a failed save)
- `WidgetSurfaceUseCaseTest` (one list's queue, an archived or deleted list), `AppPrefsTest` (per-widget settings), `WidgetUpdateSchedulerTest` (an id that is not one of Orbit's widgets is refused)
- Gallery previews: `WidgetSettingsEveryListPreview`, `WidgetSettingsOneListNamesHiddenPreview`, `WidgetSettingsSourceMissingPreview`, `WidgetSettingsNoListsPreview`, with the curtain pass
