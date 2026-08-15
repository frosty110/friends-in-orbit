# Working in this repo

Orbit is an Android app (Kotlin + Jetpack Compose, Room, Hilt) for calling the
people you keep meaning to call. This file is the working contract for AI
sessions. Read it first; it is short on purpose.

## Start here

| Question | File |
|---|---|
| What is this feature meant to do? | [`features/INDEX.md`](features/INDEX.md) → the feature's `README.md` |
| What does this screen owe the user? | [`features/PAGE_VIEWS.md`](features/PAGE_VIEWS.md) |
| What rules must my code follow? | [`features/_foundations/rules.md`](features/_foundations/rules.md) |
| How do I make a change here? | [`features/_foundations/development-cycle.md`](features/_foundations/development-cycle.md) |
| How do I word user-facing text? | [`features/_foundations/voice.md`](features/_foundations/voice.md) |
| Where do design decisions live? | [`DESIGN.md`](DESIGN.md) |
| Why is it built this way? | [`features/_foundations/ADRs/`](features/_foundations/ADRs/) |

## The loop

**Orient → Plan → Change → Verify → Document → Land.** Full detail in
[`development-cycle.md`](features/_foundations/development-cycle.md). The short
version:

1. **Read before writing.** The feature spec, the page view, and the code's own
   comments. This codebase explains its reasoning densely — a comment saying why
   something *isn't* done the obvious way is usually load-bearing.
2. **Find the mechanism before fixing.** Name the line and the sequence that
   produces the symptom. "This looks wrong" is not a root cause. If you can't find
   it, say so instead of fixing the most suspicious-looking thing nearby.
3. **Match existing precedent** over inventing a second way to do something.
4. **Verify, then report what you actually ran** (see below).
5. **Update the docs in the same commit** as the change.

## Verification — the rule that matters most

**Cloud and web sessions have no Android SDK. They cannot build or test this
project.** That is normal and fine. What is not fine is letting the reader assume
otherwise.

Use only these phrasings:

- *"Tests pass"* — you ran them and watched them pass.
- *"This compiles"* — you compiled it.
- *"Not verified — no Android SDK in this environment"* — you reasoned it through
  and could not run it. Say this **up front**, not in a footnote, and be concrete
  about which claims are reasoned rather than observed.

"Done" means verified, or explicitly labelled unverified. Never report completion
because the code looks right.

When the environment does have the SDK:

```sh
cd android
./gradlew :app:testDebugUnitTest        # unit tests
./gradlew :app:ktlintFormat             # format
./gradlew :app:assembleDebug            # it compiles
python3 ../scripts/check-conventions.py # citation + PII-logging gates
```

CI additionally enforces a 90% coverage floor on the `domain` layer.

## Non-negotiables

Full list in [`rules.md`](features/_foundations/rules.md); these are the ones
most often broken:

- **PII never reaches a log line** (Code 4). Names, numbers, note bodies, list
  names. Structured non-PII events with ids are fine in infrastructure.
- **No silent fallbacks** (Code 3). A failed write tells the user.
- **48dp minimum tap target** (Design 3), **one accent element per screen**
  (Design 5), **tokens only — no hardcoded colours or spacing** (Design 1).
- **One state contract per screen**, `stateIn(WhileSubscribed(5_000L))` (ARCH-02).
- **Screen-local UI state has exactly one owner** (Code 7). Two writers for one
  value is the bug, every time.
- **Cite conventions you rely on**, and define any new requirement ID in its
  feature spec in the same PR. An ID that exists only in a comment is dangling.

## Scope and communication

- **Deliver the whole ask.** If part is blocked, finish the rest and say plainly
  what you left and why. Narrowing the work is the requester's call, not yours.
- **Don't widen scope silently.** Drive-by refactors make the real change
  unreviewable. Notice something? Mention it; don't fold it in uninvited.
- **Ask only when it changes what you'd build.** One question with options beats
  building the wrong thing thoroughly.
- **A repeat bug is a design smell.** Fix the thing that made it representable,
  and add the test — don't patch it a second time.
- **Other agents' reports are evidence, not fact.** Check before building on them.

## Landing work

Commits are `type(scope): subject`; the body explains **why**, including the
mechanism for a bug fix and what you did and did not verify. Long bodies are the
convention here — `git log` is the durable record of reasoning. Add a
`Co-Authored-By:` trailer. Work on the branch you were given; never push to
`main`, and don't open a PR unless asked.
