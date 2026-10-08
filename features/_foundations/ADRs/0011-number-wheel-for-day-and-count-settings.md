# ADR 0011: Day and count settings are chosen on a horizontal number wheel

**Status:** accepted
**Date:** 2026-10-07
**Deciders:** the maintainer
**Supersedes:** ADR 0010's choice of control (the slider). ADR 0010's range (1 to 60
days), its 2-day default and its "do not raise the floor" rule all stand.
**Refines:** `features/orbit-lists/` (How often, the smart rule), `features/contact-detail/`
(CONTACT-03), `features/settings/` (SET-10, PICK-07), and `DESIGN.md`'s component table

## Context

ADR 0010 recorded that one screenshot had been read as a bug twice: the 2-day default
drawn flush against the slider's "1 day" end, because on a linear 1 to 60 day track 2
days sits at 1.7%. It left the fix open: "if it is not acceptable, the fix is the scale,
not the floor". On 2026-10-07 the maintainer read it as a bug a third time ("It's on two
days") and asked for "the scroll thing, a fancy scroll that you go towards the date".

The slider was not the only weak numeric input:

- **The two interval sliders had drifted** although ADR 0010 said they must move in
  lockstep: List settings' moved continuously and told TalkBack percentages of its
  track; Contact detail's moved a day at a time.
- **The smart rule's "Added in the last N days"** was a 7 to 180 day slider: a day was a
  few pixels wide.
- **The smart rule's long gap** was a typed number field, the one field on List settings
  that raised the keyboard, mid-page where the keyboard lands.
- **The percentage sliders** moved continuously, 2% per TalkBack adjustment.
- **Settings' "Groups when adding people"** used ± steppers, one unit per tap, over ranges
  that run to 3650 days: 30 days to a year was 335 taps.

## Options

1. **A non-linear slider** (ADR 0010's suggestion). Gives the low end room, but a
   drag still lands near a value rather than on it, and it does nothing for the
   steppers or the typed field.
2. **Presets plus a stepper** ("Every week, 2 weeks, month" chips, then ±). Fast for
   the common rhythms, but two controls for one value, and the stepper is still slow
   across a wide range.
3. **A vertical wheel** (iOS's picker). Precise and familiar, but every place it would
   sit is a vertically scrolling page that saves as you change it: a thumb that lands on
   the wheel to scroll the page spins the value instead, and the change saves.
4. **A horizontal wheel.** The same flick-and-settle precision, every value reachable,
   no compression at either end, and its gesture runs across the page's scroll instead
   of fighting it.

## Decision

**Every whole-number setting is chosen on one horizontal number wheel,
`OrbitWheelPicker`.** The value under the centre band is the choice.

- It flings and snaps; a tap on a value brings it to the centre; each value that crosses
  the centre gives a haptic tick (`SegmentFrequentTick`).
- It writes **once per gesture**, when it comes to rest, tracking its last write itself
  (the saved value returns only after Room round-trips the write).
- **TalkBack hears one adjustable control**: its name, its value in words, one value
  per swipe up or down. Keyboard and D-pad step it with left and right.
- **No accent** (rules.md Design 5): ink on the quiet `bgSubtle` band.
- It is at least 48dp tall and as wide as its widest value needs at the current font
  scale (rules.md Design 2 and 3).
- **Landmark words** sit under familiar values ("2 weeks" under 14, "1 month" under 30).
- **A stored value outside the usual range widens the range** rather than being
  clamped. Showing one number while the rule runs another, then saving the shown one on
  the first touch, is the bug ADR 0010 recorded.
- The sentence over the wheel stays whole and follows it ("Aim for every 14 days"), so a
  translator can still order the words (voice.md).

Applied to: the keep-in-touch interval in List settings and in Contact detail's custom
schedule (one shared `IntervalDaysPicker`, so they cannot drift again), the smart rule's
days, long gap and percentages, and the four values in "Groups when adding people".

**`OrbitSlider` stays for continuous values only**: today the accent hue, where any point
on the dial is as good as its neighbour and the track itself is the picture.

## Consequences

- The 2-day default now reads "2" in the centre of the wheel, under "Aim for every 2
  days". The rendering ADR 0010 accepted as correct-but-confusing is gone.
- The long gap no longer raises a keyboard on List settings.
- Each wheel shows a run of values at once, so its height is fixed (about 64dp with
  landmark words); the row it replaces was a little shorter.
- Reaching a far value is a fling, not a drag or many taps; reaching an exact one is a
  short nudge or a tap on it.

## Non-consequences

- **No migration.** Every stored value is still valid, and none is rewritten by opening a
  screen.
- The engine and `RuleParams.KeepInTouch.withIntervalHours` are untouched; the wheel
  commits whole days through the same entry point the slider did.

## Related

- ADR 0010: the range, default and floor this keeps.
- `OrbitWheelPickerTest`, `IntervalDaysPickerTest`, `SmartRuleEditorTest`.
