# Orbit UX rubric

> **Status:** adopted 2026-10-05. The owner asked for my best answer to every open decision; they are recorded under [Decisions](#decisions) with the reasoning. The plan was carried out the same day and Orbit was [re-scored](#re-score-after-the-work): about 2.9 of 4, up from 1.5. **Not AAA yet**: what is left needs a phone and five people, and is listed in the re-score.
>
> What "world class" means for Orbit, written so two people scoring the same build land within one point of each other. Every score needs evidence: a screenshot, a recording, a test report, or a `file:line`. A score without evidence does not count.

**Contents:** [How to score](#how-to-score) · [The AAA bar](#the-aaa-bar) · [Hard gates](#hard-gates) · [The 12 dimensions](#the-12-dimensions) · [Re-score after the work](#re-score-after-the-work) · [Baseline before the work](#baseline-before-the-work) · [What was unprofessional](#what-was-unprofessional) · [Plan to reach AAA](#plan-to-reach-aaa) · [Decisions](#decisions)

---

## Why a rubric, and why these dimensions

Orbit has one job: remove the "who should I call?" friction, so the user actually calls the people they care about. Everything below derives from that job and from the feeling the app promises: warm, quiet, unhurried ([`DESIGN.md`](../DESIGN.md), [`voice.md`](../features/_foundations/voice.md)).

From first principles, a world-class app for that job has to do five things at once:

1. **Make the right call obvious and effortless.** One name, a human reason, one tap. (Core loop)
2. **Never make the user think about the app.** Clear places, clear words, predictable movement. (Navigation, content, interaction)
3. **Look and feel crafted.** Every pixel intentional, one system, no stock parts. (Visual craft, system coherence, motion)
4. **Work for everyone, everywhere, every time.** Any eyes, any hands, any state, any device. (Accessibility, states, platform, performance)
5. **Earn trust and leave the user feeling good.** Private by default, kind in tone, never guilt. (Trust, emotional design)

The 12 dimensions cover those five without overlap. They are calibrated against public bars for top-tier apps: the Apple Design Awards categories (Interaction, Visuals and Graphics, Inclusivity, Delight and Fun), Google Play's core app quality and large-screen quality guidelines, Material 3, WCAG 2.2, and Nielsen's usability heuristics.

---

## How to score

Each dimension gets a whole number from 0 to 4. The anchors are the same for every dimension; each dimension then says concretely what a 4 requires.

| Score | Name | What it means |
|---|---|---|
| **0** | Broken | Defects block the task or mislead the user. |
| **1** | Functional | It works, but it is rough: obvious inconsistencies, stock parts, missing states. Feels like a prototype. |
| **2** | Solid | Consistent and dependable, few rough edges. A typical well-made indie app. |
| **3** | Polished | Considered detail everywhere. Best in its category. |
| **4** | World class | Sets the bar. Every detail is intentional, and reviewers single it out. |

**Evidence pack for each scoring round:** screenshots of every screen in light and dark at 360dp and a tablet width; the same at 200% font scale; a TalkBack recording of the five core journeys; the state matrix; the string export; startup and frame-timing benchmarks.

**Rules for scoring**

- Score the **worst screen**, not the average. A world-class Home does not rescue a broken picker.
- Two people score independently, then reconcile anything more than one point apart.
- A dimension cannot score above 2 while any of its **must** items fail.
- Re-score after every release that changes UI. Keep the evidence with the score.

---

## What "AAA" means here

"AAA" is not a certification anyone issues. The term comes from games, where it was borrowed from bond credit ratings in the 1990s to mean the highest grade of production ([Wikipedia](https://en.wikipedia.org/wiki/AAA_(video_game_industry))). For an app, the honest translation is: **built to the standard of the apps the platforms themselves hold up as the best**, with nothing left rough. Three public bars define that standard, and Orbit's rubric folds all three in:

1. **The Apple Design Awards** judge six things: *Delight and Fun*, *Innovation*, *Interaction* ("intuitive interfaces and effortless controls that are perfectly tailored to their platform"), *Inclusivity* ("a great experience for all"), *Social Impact*, and *Visuals and Graphics* ("outstanding artistic direction, animations, and graphical quality") ([Apple Newsroom, 2025](https://www.apple.com/newsroom/2025/06/apple-unveils-winners-and-finalists-of-the-2025-apple-design-awards/)). Those map to D1, D5, D3, D8, D12 and Orbit's mission.
2. **Google's core app quality guidelines** set the Android floor: light and dark themes, 45 to 75 character lines, standard back and gesture navigation, state kept across backgrounding, 48dp touch targets, 4.5:1 text contrast (3:1 for large text), labels on every control, start-up under two seconds or a progress state, at least 60fps, no crashes or ANRs, permissions requested only when the feature needs them, and graceful degradation when they are refused ([developer.android.com](https://developer.android.com/docs/quality-guidelines/core-app-quality)). The large-screen guidelines add tiers; "large screen ready" (tier 3) means the app runs full screen and every critical flow works on tablets and foldables ([developer.android.com](https://developer.android.com/docs/quality-guidelines/archive/adaptive/large-screen-app-quality)).
3. **WCAG 2.2** is the accessibility standard. Level AA is what laws and platforms anchor to, and the W3C itself advises against requiring Level AAA as a blanket policy, because some AAA criteria cannot be met for some content ([W3C](https://www.w3.org/TR/WCAG22/)). So Orbit takes **all of AA** as a hard gate, plus the three AAA criteria that suit a calm phone app: **1.4.6** enhanced contrast (7:1) for primary text, **2.5.5** enhanced target size (Android's 48dp already exceeds it), and **2.3.3** animation from interactions can be turned off.

In one sentence: **Orbit is AAA when it would be a credible Apple Design Award finalist for Interaction and Inclusivity, passes every Google core quality check and the large-screen-ready tier, and meets WCAG 2.2 AA plus those three AAA criteria, with five real users confirming it.**

## The AAA bar

Orbit is AAA when **all** of these hold:

1. Every [hard gate](#hard-gates) passes.
2. **Core loop, Visual craft, Interaction and motion, and Accessibility score 4.**
3. Every other dimension scores **3 or higher**.
4. A moderated test with five people from the target audience finds no task failures on the core journeys, and at least four of five describe the app with warm words unprompted ("calm", "kind", "easy").

---

## Hard gates

A failed gate means "not AAA", whatever the scores say.

| Gate | Passes when |
|---|---|
| **G1 · No lost work, no silent failure** | Every write either succeeds or tells the user, with a way to retry. Destructive actions have Undo or an explicit confirm. |
| **G2 · Accessible floor** | WCAG 2.2 AA on every screen in light and dark: 4.5:1 text contrast, 3:1 for UI parts, 48dp targets, every control labelled for TalkBack. |
| **G3 · Nothing breaks at the extremes** | No clipped or overlapping text at 200% font scale, with 40-character names, on a 360dp-wide phone, or in landscape. |
| **G4 · No dead ends** | Every screen and every state has a way forward and a way back. |
| **G5 · Stable** | No crash or ANR in the core journeys across the test matrix; crash-free sessions at or above 99.9% once in production. |
| **G6 · Promises kept** | Everything the app claims is true: data stays on device, the privacy curtain covers every surface it says it does, templates do what their subtitle says. |

---

## The 12 dimensions

Each dimension lists why it matters for Orbit, what a **4** requires (the **must** items gate scores above 2), and how to test it.

### D1 · Core loop

*From "I should call someone" to a good call, with no friction and no doubt.*

**A 4 requires**
- **Must:** one tap from a widget or a notification to that person's call; two taps at most from a cold launch.
- **Must:** every suggestion carries a human reason ("you talked about her new job", "usually free on Sunday mornings"), not only statistics.
- **Must:** dialing happens only from a clearly labelled control; no surface dials on a stray tap.
- Later and Sooner are labelled, named the same everywhere, and undoable.
- After a call the loop closes kindly: an optional note, and the next person only when the user asks.
- Five people, shown Home cold, can say who is next and why within five seconds.

**Test:** tap counts on the five core journeys, screen recordings, the five-second test.

### D2 · Navigation and information architecture

*The user always knows where they are, where things live, and how to get back.*

**A 4 requires**
- **Must:** one title and one primary action per screen.
- **Must:** icons keep their platform meaning (a hamburger opens a drawer; overflow is three dots).
- **Must:** one entry point per job; no duplicate "new" buttons on one screen.
- Every task is three levels deep or less; back always lands where the user expects, with predictive back previews.
- One word per concept across the app (people or contacts; later, sooner or skip).

**Test:** tree test of ten tasks, an entry-point inventory, a terminology glossary.

### D3 · Visual craft

*Every screen looks composed and intentional, in both themes, at every size.*

**A 4 requires**
- **Must:** the brand typeface ships, with a disciplined type scale and tabular figures for numbers.
- **Must:** one accent element per screen ([Design 5](../features/_foundations/rules.md)).
- **Must:** no screen looks unfinished: no lone half-width tile in a grid, no screen-tall empty area, no orphan control.
- Consistent spacing rhythm and gutters; one icon family at consistent sizes and stroke.
- Real contact photos where they exist, graceful initials where they do not, one avatar shape everywhere (app and widgets).
- Dark mode is designed, not inverted, and is reviewed per screen.
- Meets or beats the design prototypes in `vision/*/design-*.png`.

**Test:** side-by-side screenshot review, light and dark, at 360dp, 411dp and a tablet width.

### D4 · Design system coherence

*One system, used the same way everywhere, so polish survives new features.*

**A 4 requires**
- **Must:** tokens only; no hardcoded colour, spacing or type values outside `ui/theme/` ([Design 1](../features/_foundations/rules.md)).
- **Must:** no stock Material parts leaking through unthemed (menus, snackbars, dialogs, checkboxes, progress, pickers).
- One component per job (one button family, one row, one sheet, one dialog, one empty state), each with documented states.
- Screenshot tests guard every shared component in light, dark and large font.

**Test:** the hardcoded-value count, a component inventory, the screenshot test suite.

### D5 · Interaction, feedback and motion

*Every touch is answered, every change is explained, nothing surprises.*

**A 4 requires**
- **Must:** every action shows a response within 100ms, and its result within a second or a progress state.
- **Must:** reversible actions offer Undo; irreversible ones sit behind a labelled control or a light confirm.
- **Must:** every gesture has a visible alternative (no swipe-only or long-press-only features without a menu path).
- Transitions show where things come from and go to (the card opening into Contact detail, rows moving when the list changes), within [Design 8](../features/_foundations/rules.md)'s calm limits.
- Haptics at commit moments: a call starts, a swipe commits, a drag picks up.
- The system "remove animations" setting is honoured.

**Test:** frame-by-frame recordings of each action; an action-by-action feedback checklist.

### D6 · States and resilience

*Every screen is designed for every situation it can be in, not just the happy path.*

**A 4 requires**
- **Must:** every screen has designed loading, empty, error and permission-denied states; no flash of wrong content while loading.
- **Must:** empty states are friendly and offer the next step; errors say what happened and what to do.
- Handles 0 contacts, 2,000 contacts, very long names, names with emoji, no call history, and right-to-left text.

**Test:** a state matrix per screen with a screenshot in each cell, and a stress data set.

### D7 · Content and voice

*Every word sounds like a calm friend and means exactly one thing.*

**A 4 requires**
- **Must:** full compliance with [`voice.md`](../features/_foundations/voice.md): sentence case, no exclamation marks, no shame, no coaching.
- **Must:** no internal jargon reaches the user ("cadence", "cooldown", "chip-match thresholds", "30d").
- One phrasing for time ("3 weeks ago" or "27 days ago", not both on one screen).
- Every user-facing string lives in resources, with plurals, ready to translate.

**Test:** a full string export reviewed in context, against a glossary.

### D8 · Accessibility

*Anyone can use every part of Orbit: with TalkBack, large text, low vision, limited dexterity, or motion sensitivity.*

**A 4 requires**
- **Must:** gate G2 passes.
- **Must:** TalkBack completes every core journey with no unlabelled control and a sensible reading order; the card deck works fully through accessibility actions.
- Body text reaches WCAG AAA contrast (7:1) in both themes; secondary text at least 4.5:1.
- Colour is never the only signal (due dots, permission states).
- 200% font scale keeps names whole and layouts usable; Switch Access reaches everything.

**Test:** Accessibility Scanner on every screen; TalkBack and Switch Access recordings; a font-scale matrix.

### D9 · Android platform craft

*Orbit feels native to modern Android and uses the platform to save the user steps.*

**A 4 requires**
- **Must:** edge-to-edge with correct insets, predictive back, and the splash screen API.
- **Must:** notifications carry the person's name and face, a direct "Call" action, and respect lock-screen privacy.
- **Must:** widgets are responsive across sizes, follow dark mode, and match the in-app design.
- A themed (monochrome) icon, app shortcuts ("Call next"), per-app language, and an optional "use device colours" theme.
- Tablets, foldables and landscape get a real layout, not a stretched phone.

**Test:** Google's core app quality and large-screen quality checklists.

### D10 · Performance and stability

*Fast enough that the user never waits, stable enough that they never think about it.*

**A 4 requires**
- **Must:** gate G5 passes.
- **Must:** no flash of placeholder or wrong data on any screen.
- Cold start to an interactive Home under one second on a mid-range phone (Baseline Profiles).
- At least 99% of frames on time while scrolling lists and swiping the deck.

**Test:** Macrobenchmark startup and frame timing; Android vitals once live.

### D11 · Trust and privacy

*The user always knows what Orbit knows, and never feels watched or tricked.*

**A 4 requires**
- **Must:** gate G6 passes.
- **Must:** each permission request shows its value first, and declining leaves a useful app.
- The on-device promise is visible where it matters (onboarding, Settings, export).
- No dark patterns: no guilt, no pre-ticked choices, no hidden costs.

**Test:** a permission journey review; a privacy surface audit (app, notifications, widgets, app switcher).

### D12 · Emotional design and delight

*Using Orbit leaves people feeling warmer about their people, not guilty about them.*

**A 4 requires**
- **Must:** no guilt mechanics: no streaks, and counts read as invitations ("3 ready"), never as debts.
- A brand moment where it matters: onboarding, the empty states, the app icon.
- Small, calm rewards for connection: a quiet acknowledgement after a call, and a calm word when nobody is due (never "caught up" or "done": the queue is continuous by design, HOME-6).
- Five users describe it with warm words unprompted.

**Test:** a desirability study (pick-five word cards) and think-aloud sessions.

---

## Re-score after the work

Scored on 2026-10-05 against `2cf083b`, after the plan below was carried out the same day. Same rules as the baseline: the worst screen sets the score, and a **must** that fails caps a dimension at 2. One person scored it (me), so the two-scorer reconciliation the rules ask for is still owed.

**What the evidence is.** Unlike the baseline, this round was run, not only read:

- **The preview gallery**, rendered on the JVM (Robolectric, native graphics): all 414 previews, covering every `@Preview` in light and dark and at font scales 0.85 to 2.0, then the 319 screen, sheet and dialog previews again on a 360dp phone and in landscape. I looked at the screens the scores lean on.
- **The gallery's accessibility audit**, which checks every enabled clickable node for a label and a 48dp target: 2,342 controls across 414 previews at the default size, 2,000 across 319 at 360dp and 1,910 across 319 in landscape. The 360dp and landscape passes found three last problems (nudge days 42dp wide on a narrow phone, the thresholds dialog squeezing its steppers to 42dp, the picker's permission card crushing "Grant access" to 6dp); all three are fixed and those screens re-rendered clean. The final full run reports **none**.
- **`ThemeContrastTest`**: every theme, light and dark, including the Wallpaper theme across the hue wheel: 4.5:1 for text, 3:1 for UI parts, 7:1 for primary text.
- **The unit suite**: 793 tests in 117 classes pass (2 skipped, both long-standing `@Ignore`s) with no failed attempts in the final run, and `assembleDebug` builds. One flake was unexplained at the time: `OnboardingDoneViewModelTest`'s first test timed out once in an earlier run and passed on retry. The audit that followed found the mechanism, and "one DataStore instance" was the problem rather than the alibi: `AppPrefs` read a process-wide DataStore singleton (`preferencesDataStore`), one JVM served a whole test class, so every method shared the store's actor, cache and lock while `MainDispatcherRule` swapped `Dispatchers.Main` under coroutines still alive; a write stranded across a method boundary blocked every later edit, and the retry, alone in a fresh JVM, could only pass. Fixed in the 2026-10-05 audit round by giving each test its own DataStore on a scope it cancels (`AppPrefs` takes the store; `testutil/TestDataStore.kt`), so nothing can leak between methods. Plus `check-conventions.py` (citations, no PII in logs, requirement IDs).

**What is still not evidence.** No device or emulator was available, so nothing here was watched on a phone: no TalkBack or Switch Access walk-through, no Accessibility Scanner, no Macrobenchmark run (the startup benchmark and Baseline Profile generator exist but have never run), no crash matrix, no five-person test. Items that rest on those are marked *(reasoned)* or *unverified*, and they are exactly what stands between Orbit and AAA.

### Hard gates

| Gate | Before | Now | Evidence |
|---|---|---|---|
| G1 · No lost work | Fails | **Passes** | Each Card view action carries its own undo token, so Undo can only revert its own person (CARD-02, `CardViewViewModelInteractionTest`). Home, Lists, list settings, Settings, Ignored, Browse, Search, Contact detail, Call history and the pickers show an error with Try again instead of failing silently. Moves into an archived list now say "Couldn't save your change" instead of an Undo that did nothing. |
| G2 · Accessible floor | Fails | **Passes in automated checks** | Contrast gates above, in every theme. The audit finds no unlabelled control and none under 48dp at any size it renders. Later and Sooner are named; switches, sliders and headings announce properly. TalkBack itself not run *(reasoned)*. |
| G3 · Extremes | Fails | **Passes in renders** | Every preview at 200%; the whole gallery at 360dp and in landscape; 40-character names on Card view and Home. Fixed on the way: Card view's landscape layout (CARD-06), Call stacking above Later and Sooner at large text, Home's header stacking on narrow cards, the list chip covering the avatar. One renderer quirk: a hyphenated name breaks mid-word here; Android breaks after the hyphen *(reasoned)*. |
| G4 · No dead ends | Passes | **Passes** | Every error state has Try again; every empty state offers the next step; unknown deep links are ignored rather than crashing (`OrbitNavHost`). *Corrected in the 2026-10-05 audit round:* Card view's Error state offered only "Go home" and could not be reached, so this row was overstated for one screen; the card package gave it Try again (CARD-07) with a test. |
| G5 · Stable | Unknown | **Unknown** | Every screen's data stream catches its errors into an error state, so a failing query shows a message instead of crashing *(reasoned)*. *Corrected in the 2026-10-05 audit round:* Card view's did not. `CardFeed` ran on the application scope with no `catch`, so a failing query there would have taken the process down (the same shape `BrowseFeed` had already fixed); the card package added the catch, a `Failed` snapshot and a recovery test (CARD-07). Not measured: no crash matrix on devices, no production data. |
| G6 · Promises kept | At risk | **Passes** | Lock-screen nudges carry a public version without names (`NudgeNotification.kt`). The privacy curtain now holds everywhere the gallery can see: a new curtain mode renders every preview with the curtain down and flags any person or list name in text, fields, titles or TalkBack labels. Names showed through the curtain on eight surfaces, all now fixed: Contact detail's overflow label (found by the strings agent, now under `ContactDetailCurtainTest`); the Lists rows, List settings (title, name field, members) and onboarding's people preview (found by reading every screen that shows a name); and the move-to-list sheet, the picker's commit bar and the convert dialog (found by the new check). Card view's title also masked a list as "Contact"; it now says "List". The final curtain run over all 414 previews reports none. Templates do what they say (fixed earlier, B5). |

### Scores

| Dimension | Before | Now | Why, and what keeps it from 4 |
|---|---|---|---|
| D1 Core loop | 2 | **3** | Only the labelled Call button dials (CARD-01); Later and Sooner are named and undoable (CARD-02); the card leads with the last note and the pair's rhythm (CARD-04); a confirmed call says "Called Kai" with "Add a note" (CARD-03); Home calls the next person in one tap (HOME-9); a nudge's Call action and the widget open the dialer in one tap. **Short of 4:** the deck still advances by itself after a call (CORE-04, kept on purpose, see below), and the five-second test has not been run. |
| D2 Navigation and IA | 1 | **3** | Lists has its own icon and Card view's menu is three dots; one create control on Lists (LIST-20); a list opens its cards from Home and Lists alike (LIST-23); "View all calls" shows that person; predictive back is on; one word per concept (voice.md glossary: people, Later, Sooner, nudges). **Short of 4:** no tree test. |
| D3 Visual craft | 2 | **3** | Inter ships with a full type scale mapped into Material, tabular figures on stats; one accent per screen applied screen by screen; faces with initials fallback everywhere, widgets included; dark mode reviewed per screen in the gallery. **Short of 4:** "meets or beats the prototypes" and "reviewers single it out" need someone other than the author. |
| D4 System coherence | 1 | **3** | Literal spacing values in product code (padding, gaps, spacers, offsets outside `ui/theme/`) went from 149 to none, and there are no colour or type literals outside `ui/theme/`; menus, snackbars, dialogs, chips, checkboxes, sliders and the time picker are themed; one chip, checkbox, slider, switch, search field, snackbar, state message (`OrbitScreenMessage`, with the static `OrbitListSkeleton` beside it, each a row in DESIGN.md's components table since the 2026-10-05 audit round) and avatar. **Short of 4:** the gallery's label, 48dp and curtain audits gate CI since the audit round (a separate `-Pscreenshots` job in strict mode), so a regression in any preview fails the build, but nothing yet compares renders against approved goldens, so a pixel change still passes. |
| D5 Interaction and motion | 1 | **3** | Directional 250 to 350ms screen transitions; a calm press response on every control (`OrbitPressIndication`); rows animate in and out on Home, Lists and the pickers; haptics when a swipe commits and when a long press opens a menu; everything stills when the system turns animations off. **Short of 4:** no haptic when a call starts, the Home card does not open into Card view (no shared element yet), and nothing was timed frame by frame on a device. |
| D6 States and resilience | 1 | **3** | Real loading states (no false "No one here yet"), error states with Try again on every data screen, an honest permission-denied state in Call history, no Settings flash; long names, emoji initials and no call history are all handled and previewed. **Short of 4:** 2,000 contacts and right-to-left text were not tested. |
| D7 Content and voice | 2 | **3** | Every user-facing string is a resource: 733 strings and 65 plurals, with `UiText` from ViewModels and no English in the domain layer; one formatter for time since a call; plain words for every setting; sentence case; no em dashes in copy (`CopyAuditTest` checks nudges). **Short of 4:** a human read of the string export in context is still owed, although `VoiceAuditTest` (added in the 2026-10-05 audit round) now holds every translatable resource to the glossary mechanically: no exclamation marks, no em dashes or arrows, none of the forbidden phrases including "due today" and "not due", "contacts" only for the phone's address book; and the three fixed-order date patterns are not translatable. |
| D8 Accessibility | 1 | **3** *(provisional)* | G2 passes in automated checks; primary text at 7:1 (WCAG AAA 1.4.6) in every theme; screen titles are headings and pane titles, so TalkBack announces each new screen; every Undo is a 48dp target (Material's snackbar action was 40dp); no control pretends to be tappable when it is not (note rows were a dead tap); sliders say "Every 14 days"; the card deck works through accessibility actions; animations honour the system setting (AAA 2.3.3). **Provisional because** the must "TalkBack completes every core journey" is reasoned, not observed. If the TalkBack walk-through finds a blocker, this drops to 2. |
| D9 Android platform | 1 | **3** | Edge-to-edge with per-theme bar icons, predictive back, splash API; nudges with the person's face, a Call action and a private lock-screen version; responsive widgets with real previews; themed icon; "Call next" shortcuts; the phone's 24-hour setting; per-app language wired; a Wallpaper theme; a landscape Card view and a 640dp content cap on wide windows. **Short of 4:** no two-pane tablet layout (out of scope by decision 7). |
| D10 Performance and stability | 2 | **2** | No placeholder flashes, and a Baseline Profile and startup benchmark exist. **Stays 2:** nothing was measured. G5 is unknown, and a hand-written profile is a guess until the generator runs on a device. |
| D11 Trust and privacy | 2 | **3** | G6 passes; notifications are asked on Done, in context, after the first list exists (ONB-30), so the user meets their own people after two permission screens instead of three; declining any permission leaves a working app. **Short of 4:** the permission journey has not been reviewed on a device. |
| D12 Emotional design | 2 | **3** | A brand moment on Welcome (`OrbitMark`, ONB-31) and a themed icon; "Called Kai" after a confirmed call; "All quiet for now." when nobody is due, never "caught up" (CARD-05, HOME-6); no streaks or debts anywhere. **Short of 4:** the desirability study has not been run. |

**Overall: about 2.9 of 4, up from about 1.5.** Eleven dimensions at 3 and one at 2.

### Is it AAA?

**No, not yet, and nothing more can be done about that from a cloud session.** The AAA bar needs Core loop, Visual craft, Interaction and Accessibility at 4, every other dimension at 3, every gate passing and five real users confirming it. What remains is evidence that only a phone and people can produce:

1. **On a device:** a TalkBack and Switch Access walk-through of the five core journeys, and Accessibility Scanner on every screen (D8 to 4, confirms G2).
2. **Measure:** run `:benchmark`'s startup benchmark and the Baseline Profile generator on a mid-range phone, plus a frame-timing benchmark for the deck and lists (D10 to 3, starts G5).
3. **Five people:** run [`ux-test-kit.md`](ux-test-kit.md) (the five-second test, the core journeys, the word cards). It is the AAA bar's fourth condition and the last step for D1, D3 and D12.
4. **Approve the gallery as goldens** and compare against them in CI (D4 to 4).
5. **Design work still open:** the Home card opening into Card view as one motion (D5), right-to-left and 2,000-contact passes (D6), a human read of the string export (D7).
6. **One product question for the owner:** D1's "the next person only when the user asks" conflicts with CORE-04, where the deck moves on by itself once the call log confirms a call. I kept CORE-04 because it is a deliberate product decision; changing it is the owner's call.

### The plan, item by item

| # | Status | Where |
|---|---|---|
| 0.1 Per-person undo | Done | `468d68d` (CARD-02) |
| 0.2 Only Call dials | Done | `468d68d` (CARD-01) |
| 0.3 Labels, roles, headings, 48dp | Done | `468d68d`, `1af5d3e`, `e8c93ae`, `abfae55`, `71dee87` |
| 0.4 Contrast | Done | `1af5d3e`, `822da7b` |
| 0.5 Real loading and denied states | Done | `f2918f8`, `0c1f1c8`, `0e58d7f` |
| 0.6 200% and long names | Done in renders | gallery, `24b936f`, `36d4269` |
| 0.7 Private lock screen | Done | `dbdb806` |
| 1.1 Inter, type scale, shapes | Done | `1af5d3e` |
| 1.2 Theme every Material part | Done | `1af5d3e`, `d8d7db0`, `abfae55` |
| 1.3 Navigation grammar | Done | `a961e2b`, `0c1f1c8`, `18521c1` |
| 1.4 One accent per screen | Done | `a961e2b`, `f2918f8`, `abfae55` |
| 1.5 Motion | Mostly | `1af5d3e`, `2c36fa4`; the Home-to-Card shared element is not built |
| 1.6 One vocabulary and time formatter | Done | `1d3266f`, `1081f3d`, `912308f`, `7de65ca` |
| 1.7 One component per job, tokens | Done | `d8d7db0`, `5336b42`, DESIGN.md |
| 2.1 Home to the design | Done | HOME-5 to HOME-10, `a961e2b`, `2c36fa4` |
| 2.2 Why now | Done | `468d68d` (CARD-04) |
| 2.3 After a call, all quiet | Done, with one open question | `468d68d` (CARD-03, CARD-05); CORE-04 above |
| 2.4 Onboarding | Done | `0e58d7f` (ONB-30, ONB-31) |
| 3.1 Nudges with a face and Call | Done | `dbdb806` |
| 3.2 Widgets | Done | `dbdb806` |
| 3.3 Bars, icon, shortcuts, 24-hour, language | Done | `1af5d3e`, `dbdb806`, `1d3266f`, `165ce7c` |
| 3.4 Strings into resources | Done | `1081f3d`, `912308f` |
| 3.5 Error states | Done | `49565af`, `f2918f8`, `557c68a`, `0c1f1c8`, `abfae55` |
| 3.6 Tablet and landscape | Done to decision 7 | `24b936f` (CARD-06), the 640dp cap; no two-pane |
| 4.1 Screenshot tests | Partly | the gallery renders everything, and its label, 48dp and curtain audits run as a CI job (2026-10-05 audit round); goldens are still to approve, so pixel changes pass |
| 4.2 Baseline Profile and benchmarks | Written, not run | `6e98188` |
| 4.3 Automated accessibility checks | Done | `6e98188`, run in this re-score |
| 4.4 Five-person test | Kit ready, not run | [`ux-test-kit.md`](ux-test-kit.md) |

**The audit round that followed (2026-10-05, after the re-score).** Every screen, menu and setting was read against its spec, the design sources and the tests; the findings were fixed as a set of packages that land together. What they close, so the rows above are read with it:

| Item | Status | Where |
|---|---|---|
| Card view catches its feed's errors and offers Try again (CARD-07) | Done | card package; corrects G4 and G5 above |
| A visible Select on Browse (vision BROWSE-2); bar actions disabled until something is selected | Done | browse package |
| One pause sheet and one wording ("Until you unpause"), Unignore, "Quick actions" and "More actions for {x}", "Next up", "Recently called", "Add to lists", no "due" as a deadline | Done | shared components, strings; `voice.md` glossary |
| The unit-test flake: a per-test DataStore replaces the process-wide singleton | Done | `AppPrefs(dataStore)`, `testutil/TestDataStore.kt`, the fixtures |
| The gallery's audits gate CI; `VoiceAuditTest` holds the strings to the glossary | Done | build package; goldens remain open (4.1) |
| Design sources back in step with the Kotlin theme; the vision files and the flows prototype describe the shipped app; ADR 0006 amended for the skeletons that ship | Done | `design/`, `vision/`, `features/_foundations/` |

---

## Baseline before the work

*Kept as the record of where the work started. The re-score above supersedes these scores.*


Scored on 2026-10-05 against commit `df3b41b`, from the shipped screenshots in `vision/*/actual-*.png` and four read-only code audits (accessibility and platform, states and feedback, visual system, copy and navigation). Every claim the scores lean on was checked by hand against the code; contrast ratios were computed from the token values. Nothing was run on a device, so items marked *(reasoned)* follow from the code but were not watched happening.

**Short version:** the concept and the calm, warm look are genuinely good, and Card view is close to excellent. Underneath, the app is a well-made prototype rather than a finished product: the system isn't one system yet, the edges (states, accessibility, platform) are rough, and a few things are simply wrong.

### Hard gates

| Gate | Today | Why |
|---|---|---|
| G1 · No lost work | **Fails** | After three quick swipes in Card view, Undo on the first snackbar reverts the *third* person: snackbars queue (`ui/screens/card/CardViewScreen.kt:208`) but the undo slot holds only the latest action (`domain/undo/UndoStack.kt:31`). |
| G2 · Accessible floor | **Fails** | The Later and Sooner arrows on Card view have no label (`CardViewScreen.kt:604-616`). White on the terracotta button is 3.88:1 (light) and 3.17:1 (dark), below the 4.5:1 AA floor. Light-mode secondary text ("Skip", "View details") is 2.84:1. In Mono dark the count badge is white on a near-white accent, 1.35:1 (`CountBadge.kt:32`). Switches announce no on or off state (`ui/components/OrbitSwitch.kt:40`). |
| G3 · Extremes | **Fails** *(reasoned)* | At 200% font scale, Home list names sit in a fixed 118dp box (`HomeScreen.kt:461`) and the app bar is a fixed 56dp high (`ui/components/AppBar.kt:35`), so titles and names clip. |
| G4 · No dead ends | Passes | Every screen found has a way back. |
| G5 · Stable | Unknown | Not measured. Only Card view catches data-stream errors; elsewhere a data error may crash *(reasoned)*. |
| G6 · Promises kept | At risk | Nudges set no lock-screen public version (`notify/ListPromptWorker.kt:167`), so list names the app hides elsewhere can show on the lock screen. |

### Scores

| Dimension | Today | One-line reason |
|---|---|---|
| D1 Core loop | **2** | One name at a time with a clear Call button is right; but the whole card face dials on a stray tap, the arrows are unlabelled, context is statistics only, and Home needs three taps to reach a call. |
| D2 Navigation and IA | **1** | The hamburger icon means "Lists" on Home and "list actions" on Card view; Lists has up to three create-list controls; tapping a list does different things on Home and Lists; "View all calls" shows everyone's calls. |
| D3 Visual craft | **2** | The palette and Card view are lovely. But it renders in Roboto (Inter is not bundled), the accent is spent many times on 8 of 9 screens, Home and Lists leave most of the screen empty, and the widget's square avatar doesn't match the app. |
| D4 System coherence | **1** | 338 hardcoded `dp` values; Material 3 gets colours but no type or shapes, so stock menus, snackbars, dialogs and the time picker leak through; filter chips in 4 styles, 11 empty-state layouts, two icon libraries. |
| D5 Interaction, feedback, motion | **1** | Every screen change is Navigation's default 700ms crossfade, twice the house limit; rows pop instead of animating; haptics on 4 interactions; no pressed state on secondary buttons; the wrong-person Undo. |
| D6 States and resilience | **1** | Card view and Browse can flash a false "no one here" before data arrives *(reasoned)*; Call history says "No calls yet" when access is denied; most screens have no error state; Settings flashes wrong permission values. |
| D7 Content and voice | **2** | Warm, no exclamation marks, no streaks. But one idea has four names (nudges, reminders, notifications, prompts), time since a call is worded three ways, internal terms reach users ("Picker thresholds", "chip-match", "cadence"), and none of the several hundred strings in the app can be translated (no `stringResource` call anywhere). |
| D8 Accessibility | **1** | Gate G2 fails; no headings or pane titles anywhere; sliders announce "10 percent" instead of "every 7 days"; several controls under 48dp. Good bones: 16sp floor, custom actions on Browse rows. |
| D9 Android platform | **1** | No predictive back; status-bar icons ignore the in-app theme; nudges are plain text with no Call action; widgets don't resize and show blank previews in the picker; no themed icon, shortcuts, 24-hour time or tablet layout. |
| D10 Performance and stability | **2** | Unmeasured: no Baseline Profile or benchmarks. The loading flashes above cap it at 2. |
| D11 Trust and privacy | **2** | Strong promise and honest permission rationales. Weakened by names on the lock screen and all three permissions asked before the user sees a single person. |
| D12 Emotional design | **2** | The voice is kind and there is no gamification. But there is no brand moment (onboarding is text on cream), nothing acknowledges a call you made, and when nobody is due the app falls back to deadline words ("No one is up next", the widget's "No one due"). |

**Overall: about 1.5 of 4.** AAA needs four dimensions at 4, the rest at 3, and all gates passing.

---

## What was unprofessional

You asked what is unprofessional or below a world-class bar. Ranked by how much a first-time user would notice. *All fifteen were addressed in the work that followed; the plan table in the re-score says where.*

1. **The card dials if you touch it.** Tapping the face of the card, the natural way to look closer, places a call. Your own vision notes record an accidental call during review. (`CardViewScreen.kt:490`)
2. **Primary buttons fail contrast.** White on terracotta is 3.88:1. The design docs promise contrast "is a gate, not a hope", but `ThemeContrastTest` only checks button text against the 3:1 threshold for UI parts, and never checks `fgSubtle` at all.
3. **Stock Material parts in a hand-built design.** Lavender menus, grey snackbars with lavender actions, a purple time picker, square checkboxes and chips, and ripples the design brief forbids. They look like another app.
4. **The wrong font.** The design is drawn in Inter; the app ships Roboto.
5. **A hamburger that isn't a menu, and up to three ways to make a list on one screen.** Icons that lie and duplicate controls read as unfinished.
6. **Slow, directionless screen changes.** A 700ms fade on every navigation is the clearest "not native, not crafted" signal.
7. **Undo can undo the wrong person.**
8. **Unlabelled arrows on the most important screen.** Sighted users guess; TalkBack users hear nothing.
9. **Empty-looking screens.** A single half-width tile on Home, then a screen of cream; Lists the same. The Home design in `vision/00-home/design-twotone.png` is far richer than what shipped.
10. **Words that change meaning.** Nudges, reminders, prompts and notifications for one thing; later, skip, defer and pass for another; "27 days ago" next to "3 weeks".
11. **Developer vocabulary.** "Picker thresholds: edit chip-match thresholds", "Convert to static list", "30d".
12. **Lowercase sentences in a confirm dialog.** "this removes the list. people stay in your contacts." (a PRD line pins it; see decisions).
13. **Widgets that look like placeholders.** A square initial, a phone glyph, and blank previews in the widget picker.
14. **Eight onboarding screens and three permission asks before the user sees any of their own people.**
15. **False statements.** "No calls yet" when the app simply can't read calls; a brief "No one here yet" before the list loads.

---

## Plan to reach AAA

Four phases, ordered so the cheapest, most visible professionalism lands first and nothing built later has to be redone. *Status per item: see [the plan, item by item](#the-plan-item-by-item).* Sizes: **S** under a day, **M** a few days, **L** a week or more. Each item names the dimension it moves.

### Phase 0 · Fix what is wrong or untrue (gates)

| # | Change | Moves | Size |
|---|---|---|---|
| 0.1 | Card view snackbars name the person and each carries its own undo; a newer one replaces older ones. | G1, D5 | S |
| 0.2 | Tapping the card face opens details; dialing only from the Call button. | D1 | S |
| 0.3 | Label Later and Sooner (visible text and TalkBack); switches get a role and state; sliders read "every 7 days"; headings and pane titles; every target at least 48dp. | G2, D8 | M |
| 0.4 | Contrast: darken the primary button fill (or change its label colour) to reach 4.5:1 in every theme; raise light `fgSubtle` to 4.5:1; fix Mono dark on-accent colours; extend `ThemeContrastTest` to every text token and to 4.5:1 for button labels. | G2, D3 | M |
| 0.5 | Real loading states on Card view and Browse; a permission-denied state in Call history; saved values in Settings while loading. | D6, D10 | S |
| 0.6 | 200% font scale and long-name pass on every screen. | G3 | M |
| 0.7 | Lock-screen nudges hide list names (a public version), matching the in-app curtain. | G6, D11 | S |

### Phase 1 · Make it look and feel professional

| # | Change | Moves | Size |
|---|---|---|---|
| 1.1 | Bundle Inter and map the full type scale (and shapes) into Material 3; tabular figures on numbers. | D3, D4 | M |
| 1.2 | Theme every Material part (menus, snackbars, dialogs, sheets, time picker, checkboxes, chips) and the press style. | D3, D4 | M |
| 1.3 | Fix the navigation grammar: Lists gets its own icon; Card view's menu becomes three dots; one create control on Lists; tapping a list does the same thing everywhere; "View all calls" filters to the person. | D2 | S |
| 1.4 | One accent per screen, applied screen by screen (List settings first). | D3 | M |
| 1.5 | Motion: 250 to 350ms directional transitions, the Home tile opening into Card view, rows that animate in and out, predictive back, one set of haptics, pressed states everywhere. | D5, D9 | M |
| 1.6 | One vocabulary and one time formatter; plain words for every setting; sentence case everywhere. | D7 | S |
| 1.7 | Consolidate components: one chip, row, sheet, field, empty state, avatar (photo with initials fallback, circle everywhere including widgets). Move remaining `dp` values to tokens. | D4 | L |

### Phase 2 · Make the core loop world class

| # | Change | Moves | Size |
|---|---|---|---|
| 2.1 | Home built to the design prototype: per list, the next person with a face, a human reason and one-tap Call; a composed layout when there is only one list. | D1, D3 | L |
| 2.2 | "Why now" on the card: the last note, what you talked about, the pattern, ahead of raw statistics. | D1, D12 | M |
| 2.3 | After a call: "Called Kai" with an optional note, and the next person only when the user asks. A calm "All quiet for now." when nobody is due, never "caught up" (HOME-6). | D1, D5, D12 | M |
| 2.4 | Onboarding to five taps or fewer before first value; ask for notifications when nudges are first turned on; a real brand moment on Welcome. | D1, D11, D12 | M |

### Phase 3 · Platform and reach

| # | Change | Moves | Size |
|---|---|---|---|
| 3.1 | Nudges with the person's face and a Call action; lock-screen privacy. | D9 | M |
| 3.2 | Widgets: responsive sizes, real previews, system corner radius, dark mode, matching avatars. | D9, D3 | M |
| 3.3 | Status-bar icons follow the in-app theme; themed icon; "Call next" shortcuts; 24-hour time; per-app language. | D9 | S |
| 3.4 | Every string into resources with plurals; dates and times localized. | D7, D9 | L |
| 3.5 | Error states with Retry on every screen; app-level error handling for data feeds. | D6, G5 | M |
| 3.6 | Tablet, foldable and landscape layouts (decision needed). | D9 | L |

### Phase 4 · Prove it

| # | Change | Moves | Size |
|---|---|---|---|
| 4.1 | Screenshot tests for every shared component and screen state (light, dark, 200%). | D4, G3 | M |
| 4.2 | Baseline Profile plus startup and frame benchmarks. | D10 | M |
| 4.3 | Automated accessibility checks in the UI tests. | D8 | S |
| 4.4 | A five-person moderated usability and desirability test. | AAA bar | M |

---

## Decisions

The owner asked for my best recommendation on each open question and for the work to proceed on that basis. Each answer below is a decision, with the reason, so it can be revisited on its merits.

| # | Question | Decision | Why |
|---|---|---|---|
| 1 | What "AAA" means | Top-tier product quality as defined [above](#what-aaa-means-here): WCAG 2.2 AA in full, plus AAA 1.4.6 for primary text, 2.5.5 and 2.3.3. Not blanket WCAG AAA. | W3C advises against blanket AAA; the three chosen criteria are the ones that matter for reading, tapping and motion on a phone. |
| 2 | The terracotta button | Keep the hue, change the lightness: a deeper terracotta fill with white text at 4.5:1 or better in light mode; in dark mode a lighter terracotta with a dark label. The accent dial's generator is held to the same 4.5:1. | The button is the most important control on most screens. A brand colour that fails contrast on its primary use is not a brand asset. Hue carries the identity; lightness carries legibility. |
| 3 | Card face tap | Tapping the card opens the person's details. Only the labelled Call button dials. | A call is the one action in Orbit that cannot be undone and reaches another person. It must never be one stray tap away. |
| 4 | Home direction | Build toward `vision/00-home/design-twotone.png`: each list is a full-width card showing the next person, a face, a human reason, and the 7-day rhythm; tapping it opens that list's deck. | It is the owner's own design target, it answers "who next and why" at a glance, and it fixes the half-empty grid. |
| 5 | Contact photos | Use the address-book photo everywhere a person appears, with initials as the fallback, through one avatar component, in widgets too. | Faces are how people recognise people; initials are a fallback, not an identity. |
| 6 | Material You | Add a sixth theme, "Wallpaper" (stored as `device`), that takes the wallpaper's accent hue and runs it through the existing contrast-safe generator. The five curated themes stay. | It makes Orbit feel native on Android 12+ (minimum SDK is 31), and the generator means it can never break contrast. |
| 7 | Screen sizes | Phones first; "large screen ready" (Google tier 3) is required: full screen, landscape works, content width capped on wide windows. Two-pane layouts are out of scope for now. | Tier 3 is the floor for a quality Android app; a two-pane redesign is a product decision of its own. |
| 8 | The lowercase delete dialog | Sentence case: "This removes the list. People stay in your contacts." The PRD line is updated. | `voice.md` is canonical for voice and requires sentence case everywhere; lowercase read as a typo in review. |
| 9 | One phone icon per screen (Design 6) | Amended: one *accent* call action per screen; a list of people may carry a quiet, muted dial icon on each row (Browse, Search). Nowhere else repeats it. | Calling is the app's job, and dialing from a list is the shortest path to it. Muting the icon keeps the accent meaningful. |
| 10 | Proof with five users | I can't recruit people, so I've written a ready-to-run test kit (tasks, script, success criteria, word cards, scoring sheet) in [`ux-test-kit.md`](ux-test-kit.md). AAA stays **provisional** until the owner runs it. | The bar asks real people to confirm it; that part can only be done by people. |
