# Development cycle

**Status:** active
**Last reviewed:** 2026-08-15
**Canonical for:** how a change gets made in this repo, by a human or an agent

---

Most of this repo's work is done in AI sessions. That is a fine way to build, but
it fails in a specific way: an agent can produce a confident, well-commented,
plausible change that was never run. The cycle below exists to make that failure
mode visible instead of shippable.

The loop is **Orient → Plan → Change → Verify → Document → Land**. It is the same
loop for a one-line fix and a feature; only the depth of each step changes.

---

## Orient

Read before writing. In order, stopping when you have enough:

1. [`features/INDEX.md`](../INDEX.md) → the owning feature's `README.md` — what
   this is supposed to do, and its status.
2. [`features/PAGE_VIEWS.md`](../PAGE_VIEWS.md) → the screen's page view — what a
   user expects to be able to see and do there.
3. [`rules.md`](rules.md) — the global rules, especially before touching UI.
4. [`voice.md`](voice.md) — before writing a single user-visible string.
5. The code's own comments. This codebase documents its reasoning densely; a
   comment explaining why something is *not* done the obvious way is usually
   load-bearing.

**Reproduce or locate the problem before proposing a fix.** For a bug, name the
mechanism — the specific line and the sequence that produces the symptom. "This
looks wrong" is not a root cause. If you cannot find the mechanism, say so
explicitly rather than fixing the most suspicious-looking thing nearby.

## Plan

State the change and its blast radius before making it. For anything beyond a
local edit, say which files, which layers, and what could break. If two readings
of the request lead to materially different work, ask — once, with the options —
rather than building the wrong one thoroughly.

Prefer the change the codebase already knows how to make. This repo has strong
precedents (`@ApplicationScope` for work outliving a screen, `snapshotFlow` +
`debounce` for search fields, `PickerCommitBus` for post-pop snackbars). Matching
one is almost always better than inventing a second way.

## Change

- Follow [`rules.md`](rules.md). Cite a rule where a future reader would
  otherwise "simplify" the code back into a bug.
- Comments explain *why*. Match the surrounding density.
- Deliver the whole ask. If part is blocked, finish everything else and say
  plainly what you left and why — scaling the work down is the requester's call.
- Don't widen scope silently. A drive-by refactor in an unrelated file makes the
  real change unreviewable.

## Verify

**This is the step that gets skipped, and the one that matters most.**

Run what you can, and be precise about what you ran:

```sh
cd android
./gradlew :app:testDebugUnitTest        # unit tests
./gradlew :app:ktlintFormat             # format (auto-applied by the pre-commit hook)
./gradlew :app:assembleDebug            # it compiles
python3 ../scripts/check-conventions.py # docs/citation + PII-logging gates
```

CI additionally enforces a **90% coverage floor on the `domain` layer**
(`scripts/coverage-summary.py --min-domain 90`) and runs the instrumented suite
on an emulator (non-blocking). Compose UI changes belong in `androidTest`; pure
logic belongs in `test` where it runs on every push. The exception is a check
that only reads the semantics tree (labels, text, what TalkBack would hear):
it can run on the JVM under Robolectric with `@Config(sdk = [33], application =
Application::class)`, so it gates every push instead of waiting for an emulator
(`ContactDetailCurtainTest`, `PreviewGalleryTest`). Gestures, focus and real
windows stay in `androidTest`.

### The preview gallery

`PreviewGalleryTest` renders every `@Preview` on the JVM, in light and dark and
at font scales 0.85 to 2.0, to `android/app/build/screenshots/`, and audits each
one as it goes. It runs only on request:

```sh
./gradlew :app:testDebugUnitTest -Pscreenshots                    # everything (about an hour)
  -Porbit.screenshots.only='HomeScreenKt|CardViewScreenKt'       # a subset, by "FileKt.method"
  -Porbit.screenshots.qualifiers=w360dp-h740dp-xhdpi             # another size; landscape: w740dp-h360dp-land-xhdpi
  -Porbit.screenshots.curtain                                    # privacy curtain down (PRIV-03)
  -Porbit.a11y.strict                                            # fail on any finding
```

`a11y-report.md` lists every enabled control without a TalkBack label or under
48dp (gate G2). With `-Porbit.screenshots.curtain`, `curtain-report.md` lists
any preview person or list name that still reaches text, a field or a label.
Use it for any UI change: look at the screens you touched at 200% and 360dp,
and keep both reports at "None."

**New behaviour ships with a test.** A bug fix ships with a test that fails
without the fix — for a regression that has now happened twice, that test is the
only thing that stops a third.

### Honesty about verification

Say what you actually did. These are the only honest phrasings:

- "Tests pass" — you ran them and saw them pass.
- "This compiles" — you compiled it.
- "Not verified — no Android SDK in this environment" — you reasoned it through
  and could not run it.

The last one is common and completely acceptable: **cloud and web sessions have
no Android SDK, so they cannot build or test this project.** What is not
acceptable is letting a reader assume otherwise. If you could not run it, lead
with that, and be concrete about which claims are reasoned rather than observed.

Never report a task complete because the code looks right. "Done" means verified,
or explicitly labelled unverified.

## Document

Documentation is part of the change, not a follow-up:

- New requirement ID → define it in the owning feature spec in the same PR.
- New user-visible behaviour → update the feature `README.md` and page view.
- New convention or hazard → add it to [`rules.md`](rules.md) with a number, or
  to the feature spec. A convention that lives only in one file's comments will
  be violated by the next file.
- Changed a doc's claims → update `Last reviewed`.

If you discover a dangling reference (a doc, rule or ID that nothing defines),
record it under "Known documentation debt" in `rules.md` rather than leaving it
for the next reader to rediscover.

## Land

Commits are `type(scope): subject` — `fix(picker):`, `feat(home):`,
`test(home):`, `docs(foundations):`. The subject says what changed; **the body
says why**, including the mechanism for a bug fix and what you did and did not
verify. Bodies here are long by convention and that is deliberate — `git log` is
the durable record of reasoning.

Agent-authored commits carry a `Co-Authored-By:` trailer. Work on the branch you
were given; never push to `main` directly.

---

## Definition of done

A change is done when all of these are true:

- [ ] The whole request is addressed, or the gap is stated explicitly.
- [ ] It follows [`rules.md`](rules.md); deviations are justified at the call site.
- [ ] New behaviour has a test; a fixed bug has a test that would have caught it.
- [ ] Everything runnable in this environment was run, and the result is reported
      accurately — including "could not verify, and here's why".
- [ ] Docs affected by the change are updated in the same commit.
- [ ] The commit body explains why, not just what.

---

## For agents specifically

A few failure modes worth naming, because they recur:

**Don't fix by resemblance.** Finding code that pattern-matches a known bug is
not the same as finding *this* bug. Trace the mechanism to a specific line, or
report that you couldn't.

**Prefer the smallest change that removes the cause.** Rewriting a working
subsystem to fix a two-line defect destroys the review signal.

**Treat a repeat bug as a design smell.** The picker's search box broke twice the
same way. The second fix wasn't another patch — it was removing the second writer
so the class of bug became unrepresentable, plus a test. Ask "what made this
possible?", not just "what line is wrong?".

**Don't take another agent's report at face value.** A subagent's or a previous
session's conclusion is evidence, not fact. Check it against the code before
building on it.

**Report faithfully.** If tests fail, say so and show the output. If you skipped a
step, say which. A correct "I couldn't verify this" is worth more than a
confident wrong claim, because it tells the reader where to look.
