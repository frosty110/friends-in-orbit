# Home

> **Intent** — The front door. Home exists to orient you in two seconds and then get out of the way by handing you *someone to call*. It is a launchpad, not a dashboard — and crucially, it should **always have a recommendation**. There is no inbox to clear and no "you're done": the right framing is a person worth reaching, available at any moment. Home should never feel like a backlog, a to-do list, or a set of obligations coming due.

**Mission tie** — This is the surface that decides whether the core loop even starts. Friction or guilt here means the loop never runs. Calm, always-a-recommendation, one tap to start — that is the whole job.

---

## Today

<img src="./_assets/home.png" width="300" alt="Home screen — Orbit title, search/lists/settings, 'Today / 23 people ready', a list card, and New list" />
<img src="./_assets/home-postcall.png" width="300" alt="Home with a post-call banner: 'You just called Cameron — Add a note while it's fresh' with Add a note / Dismiss" />

- App bar: wordmark **Orbit**, plus **Search**, **Lists**, **Settings** icons.
- A quiet date eyebrow (**Today**) over a **count headline** (**23 people ready**) — *this is the framing we're moving away from; see `HOME-6`.*
- One card per list: avatar tile, a terracotta **due-count badge** (`23`), list name, and member count (`27 people`). Tapping a card drops you into that list's Card View.
- A **New list** affordance below the cards.
- A **ReflectionFooter** at the foot of the scroll — the app's one line of wisdom. *(Shipped.)*
- A contextual **post-call banner** after you place a call ("You just called Cameron · Add a note while it's fresh"). *(Shipped — and deliberately narrow; see `HOME-4`.)*
- When nobody is due-now, the header reads **"All caught up · Nobody is due right now."** This is the holdout the rest of the app already abandoned: Card View killed the "you're done" terminal state on 2026-05-08 (the *tide-marker* change — see `SurfaceResult.kt`, which calls the old single "all caught up" screen "a completion mechanic that worked against the queue's actual semantics, infinite by design"). Home is the lone screen still speaking that language.

What's missing, in priority order: Home doesn't show *who* a tap will give you (`HOME-3`), and it still speaks in **counts and deadlines** ("due", "caught up") instead of **recommendations** (`HOME-6`). The empty space is a real but secondary problem — addressed by `HOME-5`/`HOME-7` once the framing is right.

---

## Where it's going

### `HOME-3` · "Next up" on every list card · **Now**
Tapping a card today is a blind jump — you don't know who you're about to get. Show the head of that list's queue on the card face: first name + avatar (**"Next: Kai"**). It turns the tap into a *known* choice — lower friction, higher follow-through — and previews the payoff. The data already exists: `SurfaceNextUseCase` computes exactly this head for Card View, and `CardFeed.upNextFor` already carries an `UpNextHint`; Home just surfaces it one level up. Keep it to one name — a peek, not a list. Names mask under the privacy curtain (PRIV-03), like list names already do. **This is the headline move**, and together with `HOME-6` it's what makes Home an always-on recommender.

### `HOME-6` · Retire the "due / caught up" framing · **Now**
Home's job is to **always recommend someone to talk to** — not to count how many people are due, and never to announce that you're done. "Due", "N people ready", and "All caught up" are inbox-and-deadline vocabulary that fights the mission and the voice: deadlines breed the exact guilt the app refuses. Drop the obligation framing from the surface:
- **No "All caught up / Nobody is due" state.** With `HOME-3`, every card already shows who's next, so there is nothing to "catch up" on — the state simply ceases to exist.
- **Demote the raw count.** The header becomes calm orientation (just **Today**); the *recommendation* (the next person) is the centerpiece, not a tally.
- **Keep the ranking, drop the deadline words.** The queue still orders internally by `nextDueAt` — that's the engine choosing the *best* person to surface, invisible to the user. We keep the ordering; we remove the "due" language from the screen.
- **Open copy decision:** whether any quiet count survives (e.g. a soft "3 ready" read as *opportunity*, not backlog) or disappears entirely. Lean toward less.

### `HOME-4` · Post-call "add a note" banner · **Shipped — keep it narrow**
The banner captures memory at its freshest ("You just called Cameron · Add a note"). This shipped. The earlier idea of *also* asking "mark how it went" was tried and **deliberately dropped** (commit `c22a5ce` — advance the card silently after a call, no Mark-it prompt): rating a call is exactly the performative friction the app avoids. Keep the banner to one warm action — add a note — and nothing more.

### `HOME-5` · Make the quiet canvas earn its keep · **Next**
With one or two lists, the 2-column grid leaves the lower two-thirds of Home empty. Reclaim it by dropping the 2-up grid for a **single column of full-width list cards**. A wider card has room to *be* the recommendation rather than just a label — list name + member count on one line, the **"Next up"** person (`HOME-3`) given real estate (avatar + first name + why-now), and space below for the rhythm strip (`HOME-7`). It also reads calmer: one thing at a time, top to bottom, echoing the one-at-a-time spirit of the core loop.

*Tradeoff:* full-width is ideal for the 1–4 list case (the common one) but lengthens the scroll for someone with many lists. Open question — stay single-column always, or fall back to the grid past N lists. Lean single-column; the warm, unhurried read is worth the scroll.

The **ReflectionFooter** ("The call that makes your day can make someone else's…") already ships as the first, deliberately calm cut and stays at the foot of the scroll.

### `HOME-7` · A 7-day "rhythm" strip on each list card · **Later** · ⚠ brand-risk
The idea: under each full-width card, a small 7-day graph of the list's connection rhythm — a gentle baseline of how often you reach this list's people, one bar per day, each bar made of the people you actually talked to that day, weighted by how long you spoke. The data all exists (`CallEventEntity` carries `occurredAt`, `durationSeconds`, `contactId`), so it's feasible today.

**This one needs care, because it runs straight at this doc's own spine.** The vision's through-line is *stats → context*: "today the app gives you logistics (last called, average length, call count); the vision gives you context." A 7-day graph of call count + duration is exactly the *logistics* we're trying to move away from — and "average calls made" rendered on screen is one short step from a target, a score, a streak: the gamification the mission names as a failure mode. Built literally, it's a quantified-self dashboard, and that is off-brand.

So if it ships, it ships **reframed as reflection, not performance**:
- **Presence over metrics.** A memory of *who* you connected with this week, not a scoreboard of *how many*. Names/faces carry it; numbers stay quiet or absent.
- **No targets, no averages-as-goals, no streaks.** Any baseline is a calm reference ("your usual rhythm"), never a bar to clear, and never a "you're below average" framing.
- **Duration as soft visual weight, not a printed number.** A fuller mark = a longer talk; we don't show "37 min."
- **Subordinate to the mission filter.** If a glance at it ever produces guilt or a *should*, it has failed and comes out.

Decision before this graduates: does a weekly-rhythm reflection actually make someone *more likely to pick up the phone* (the mission filter), or is it decoration that quietly reintroduces performance pressure? If we can't answer yes to the first, it stays in the doc, not in the app.

---

## Cut

- **`HOME-1` · "Surprise me"** — *tried and removed.* A random "just give me someone" button fought the model. Orbit recommends *intentionally* — the right person for now, ranked — and randomness undercut both trust in the recommendation and the warm, unhurried feel (it read as a slot machine). The always-on "Next up" (`HOME-3`) delivers the "zero deciding, one name" payoff without the gamble.
- **`HOME-2` · Warm "caught up" zero-state** — *shipped, now being retired.* Superseded by `HOME-6`: in an always-recommend model there is no zero-state to soften, because there is no zero.

---

## Build note

Sourcing each tile's "Next up" name collides with **ADR 0006**, which made Home a single query (`HomeFeed` — "the N+1 `combine(observeMembersOf × N)` pattern is gone"). Two paths, to settle in the plan phase:

- **Preferred — denormalize a `nextUp` (contact id + display name) onto `ListEntity`**, kept fresh by the same mutators that already maintain `dueCount`. Home stays one query; honors ADR 0006. Costs a Room migration + mutator wiring.
- **Alternative — run `SurfaceNextUseCase` per active list inside `HomeFeed`.** No migration, reuses the exact Card View contract (zero divergence risk), but reintroduces the per-list N-combine ADR 0006 deliberately removed.
