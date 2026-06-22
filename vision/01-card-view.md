# Card View

> **Intent** — The heartbeat of the product. Card View exists to hand you exactly one person and answer a single question — *"is now a good time to reach them?"* — with just enough context that you can say yes (call) or not-yet (defer) without thinking hard. Everything about it should reduce the cognitive load of deciding *who*, and lower the activation energy of actually reaching out. If only one screen in Orbit is great, it has to be this one.

**Mission tie** — This *is* the mission. "One name at a time, with enough context to say yes." Every other screen is in service of making this moment good.

---

## Today

<img src="./_assets/card-view.png" width="300" alt="Card View — colored 'KN' avatar, 'due today', 'Kai Nakamura', 'It's been 3 weeks', a 24-hour 'usually answers / Mornings' heatmap with 'Good time to call', stats row, and Call/Later/Sooner controls" />
<img src="./_assets/card-list-actions.png" width="300" alt="Card View list-actions menu — Browse people, Add contacts, Edit list — on a lavender Material surface" />

- A hero card: deterministic colour avatar, a **due eyebrow** ("due today" / "ahead of today"), the **name**, a **why-now** line ("It's been 2 months."), then either a **24-hour "usually answers" heatmap** (when history exists) or a **"No call history yet"** pill.
- A **stats row** at the card's foot: *Last called · Avg length · Calls*.
- Controls: a left **circle arrow** (Later), a primary **Call {firstName}** button, a right **circle arrow** (Sooner), and a text row **Skip · View details**.
- **Swipe** the card left = Later, right = Sooner; **tap the card** = call; the deck advances with a crossfade. A list-context chip sits top-right.
- The **List actions** menu (hamburger) offers *Browse people · Add contacts · Edit list*.

It's already a beautiful, focused screen. The gaps are about *context* and a couple of friction/clarity snags.

---

## Where it's going

### `CARD-1` · Put the last note / topic on the card face · **Now**
This is the single highest-leverage change in the whole app. Today the card shows logistics (last called, avg length, count) but hides the one thing that actually makes you pick up the phone: *what you last talked about.* The data already exists — notes are stored and even peeked *below* the card. Pull the most recent note up **onto** the card face, between the why-now line and the stats, as one quiet line: *"Last time: she just moved to Denver."* This is the literal definition of "enough context to say yes." Nearly free given the data is already there.

### `CARD-2` · Persistent, quiet Later/Sooner labels + fix the asymmetry · **Now**
The two circle arrows are unlabeled forever after onboarding teaches them once. Add tiny `micro`/muted captions ("Later" / "Sooner") under them. While there: the bottom text row says **"Skip · View details"**, but "Skip" maps to swipe-*left* (Later) while the right arrow is "Sooner" — the labels don't agree with each other. Reconcile the language so Later/Sooner/Skip mean one consistent thing everywhere on this screen.

### `CARD-3` · De-risk the accidental call · **Now**
The *entire* hero card is a tap-to-dial target, and a placed call can't be undone. (During this very review, a mis-tap near "View details" dialled a real person.) Make the labelled **Call** button the dialer, and let a full-card tap open **View details** instead — or add a light confirm. One-tap calling stays (via the clearly-labelled button); the foot-gun goes away. This is a safety fix, not a friction add.

### `CARD-4` · "Reached them another way" · **Next**
Calling isn't the only way people stay in touch — a lot of contact is a text or seeing someone in person. Today the only completion path that satisfies the rhythm engine is a phone call, so texting someone looks like ghosting them. Surface a small **"Reached another way"** action (it can reuse the existing *Log a connection* path) that records the touchpoint and advances the deck. This widens the product from "a calling app" to "a staying-in-touch app" without diluting the loop.

### `CARD-5` · "Comes up again in ~X" after you decide · **Next**
When you defer or finish with someone, there's no signal of *when they'll return.* A brief "comes up again in about 2 weeks" (on commit, or as a faint line) closes the loop and quiets the "did I deal with this?" itch — the same reassurance instinct behind the "quiet is okay" zero-state.

### `CARD-6` · Warm the no-history state · **Next**
"No call history yet" reads like a dead end in the middle of the card. Reframe it neutrally and warmly — *"First call — no history yet."* — so a brand-new relationship feels like an opening, not an absence. Careful: keep it neutral, never anything that edges toward the forbidden "you haven't called…" shame framing.

### `CARD-7` · Optional, quiet sense of the deck · **Later**
Some people want to know "how many are left" without it becoming a streak. Explore a *very* faint position cue (a hairline, or "·· of 23") — strictly optional and strictly non-gamified. The risk is turning a calm loop into a counter to grind; if it can't be done calmly, don't do it.

### `CARD-8` · A gentle conversation prompt · **Later**
When there's an occasion or a hook (a birthday, a note that mentions an upcoming trip), offer a soft opener line. This is the natural extension of `CARD-1`: not just *why now*, but *what to open with* — the deepest possible answer to "give me enough to say yes."
