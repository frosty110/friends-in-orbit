# Orbit usability test kit

> **Status:** ready to run. Written 2026-10-05 to close [UX rubric](ux-rubric.md) bar item 4 ("a moderated test with five people"). Nobody has run it yet, so the rubric's AAA verdict stays **provisional** until someone does.
>
> Everything a moderator needs for five 40-minute sessions: who to recruit, how to set the phone up, what to say, the tasks, what counts as success, and a sheet to score it. No special tools; a phone, a notebook and a screen recorder are enough.

**Contents:** [Why five](#why-five-people) · [Who to recruit](#who-to-recruit) · [Set-up](#set-up) · [Session script](#session-script) · [Tasks](#tasks) · [Word cards](#word-cards) · [Scoring sheet](#scoring-sheet) · [What passes](#what-passes)

---

## Why five people

Five sessions find most of the problems that block people on a small set of core tasks, and they are cheap enough to repeat after fixes. This test is not a survey and makes no statistical claim. It answers two questions the rubric cannot answer from code:

1. **Can someone who has never seen Orbit get to their first call, and back, without help?**
2. **Does it feel the way it is meant to feel:** warm, calm, unhurried, never guilt?

---

## Who to recruit

Five adults who match the person Orbit is for ([`features/INDEX.md`](../features/INDEX.md)): someone with people they mean to call more often and don't.

| Must | Why |
|---|---|
| Uses an Android phone daily | The test runs on their mental model of Android, not iOS. |
| Can name three people they "keep meaning to call" | The app's job has to be real for them. |
| Has not seen Orbit before | First-run behaviour is under test. |

**Mix across the five:** at least one person over 60, at least one who uses large text or display size, at least one who uses dark mode, and if possible one TalkBack user (run their session with TalkBack on; they may need 60 minutes).

**Screener questions** (ask by message before booking):

1. Which phone do you use most days? *(screen out iPhone-only)*
2. Is there anyone you'd like to call more often than you do? Roughly how many people? *(screen out "no one")*
3. Do you change text size, display size, or use any accessibility settings? *(for the mix)*
4. Have you used an app called Orbit? *(screen out "yes")*

Offer a thank-you (a gift card is normal). Get consent to record the screen and voice; say recordings are deleted after notes are written.

---

## Set-up

**The phone.** Use a test phone, not the participant's. Their contacts are private, and a real call to a real person mid-test is a bad surprise.

1. Install the debug build from the branch under test.
2. Load the seed contacts: 25 made-up people with names of mixed length and script (include one long name such as "Maximiliana Oyelaran-Fitzgerald" and one with no last name), numbers that route to a line you control, and a call history spread over 90 days so "last called" varies.
3. Set the phone's own settings to the participant's usual ones where you can (text size, dark mode). Note them on the sheet.
4. Clear the app's data so onboarding starts fresh.
5. Start the screen recorder with the microphone on.

**The room.** Quiet, the participant holds the phone, the moderator sits beside and slightly behind. A second person taking notes is ideal but optional.

---

## Session script

Read the boxed parts close to word for word, so every session starts the same.

> Thanks for coming. I'm going to ask you to try an app and think out loud while you do: what you're looking at, what you expect, what surprises you. We're testing the app, not you. Nothing you do can be wrong, and you won't hurt my feelings; I didn't design it.
>
> This phone has made-up contacts, so any call goes to a test line, not a real person. If you'd stop and ask someone in real life, ask me, but I may answer with a question.
>
> I'll give you a few small tasks. Take your time.

**During tasks:** stay quiet. If they go silent, prompt with "What are you looking for?" or "What do you expect to happen?". Never point, never name a control. If they are stuck for two minutes, or ask to give up, mark the task failed, show them the way, and move on.

**After each task**, ask the one-question ease rating:

> On a scale of 1 to 7, where 1 is very difficult and 7 is very easy, how easy was that?

**At the end**, run the [word cards](#word-cards), then ask:

> If you were telling a friend what this app is, what would you say?
>
> Was there anything that made you feel rushed, nagged or guilty?
>
> Would you keep it on your phone? Why, or why not?

---

## Tasks

Give each task on a card, one at a time, in this order. Tasks 1 to 5 are the core journeys; all five must pass for the AAA bar.

| # | Task card (what the participant reads) | Success means | Watch for |
|---|---|---|---|
| 1 | "You've just installed Orbit. Set it up so it can help you keep in touch with a few people you care about." | Reaches the first screen that shows their own people (Home or Card view) with at least one list. | Reading permission screens or skipping them; hesitation at each ask; time to first value (target under 2 minutes, at most five taps that aren't reading). |
| 2 | "Orbit thinks you should call someone today. Call them." | Places the call from the Call button on the right person. | Tapping the card face expecting details (it must open details, not dial); whether they understand *why* this person. |
| 3 | "That call went well. You'd like to remember you talked about her new job. Make a note of that." | The note is saved on that person. | Whether the after-call moment offers it; whether they find the person's details. |
| 4 | "You don't want to call Sam this week. Tell Orbit to bring him up later." | Sam is moved later, and the participant can say when he'll come back. | Whether Later is found without hints; whether they notice and trust Undo. |
| 5 | "You've changed your mind; you'd like Sam to come up sooner after all. Put it back." | Undoes the Later, or uses Sooner, and Sam is back. | Recovery without fear; whether the snackbar named the right person. |
| 6 | "Make a new list for your college friends and put three people in it." | List exists with three people. | Finding the create control; picker clarity; the words in list settings. |
| 7 | "You'd like Orbit to remind you about this list only in the evenings." | That list's nudge time changed to an evening time under "When to nudge" (the one place nudge timing is set since 2026-10-08, LIST-25). | Whether "nudges", "When to nudge", and the time picker make sense, and whether anyone looks for a separate time-of-day setting. |
| 8 | "Find out how often you've talked to Priya this year." | Opens Priya's call history (filtered to her). | Whether "View all calls" shows only her. |

**Optional, if time allows:** switch the app to dark mode; turn on a different colour theme; add the widget to the home screen.

---

## Word cards

Lay out these 30 cards face up in a random order (a subset of the Microsoft Product Reaction Cards, balanced positive and negative). Say:

> Pick the five words that best describe how the app felt to you. Then tell me why you picked each.

| | | | | |
|---|---|---|---|---|
| Calm | Warm | Easy | Kind | Personal |
| Thoughtful | Simple | Trustworthy | Relaxed | Clear |
| Friendly | Polished | Fast | Helpful | Inviting |
| Confusing | Busy | Pushy | Cold | Slow |
| Complicated | Annoying | Generic | Stressful | Guilt-inducing |
| Boring | Unfinished | Childish | Overwhelming | Intrusive |

Record the five words and the reason given for each. The reasons matter more than the words.

---

## Scoring sheet

Copy one per participant.

```
Participant: P_   Date: ____   Moderator: ____   Build: ________
Phone settings: text size ___  display size ___  dark mode Y/N  TalkBack Y/N

Task | Pass/Fail | Time | Ease 1-7 | Help given? | Notes (quotes, where they looked)
 1   |           |      |          |             |
 2   |           |      |          |             |
 3   |           |      |          |             |
 4   |           |      |          |             |
 5   |           |      |          |             |
 6   |           |      |          |             |
 7   |           |      |          |             |
 8   |           |      |          |             |

Accidental call? Y/N   (any call the participant did not intend)
Words picked (5): ____________________________________________
Warm word unprompted before the cards? Y/N  Which: ___________
"Rushed, nagged or guilty?" ____________________________________
"Keep it?" ____________________________________________________
Top 3 problems seen (with timestamp in the recording):
1.
2.
3.
```

**Severity for each problem** (agree after all five sessions):

| Level | Meaning |
|---|---|
| 4 Blocker | Stopped a task or caused an accidental call or lost data. Fix before release. |
| 3 Major | Big delay or a wrong path most people took. Fix before scoring AAA. |
| 2 Minor | Slowed someone or caused a moment of doubt. |
| 1 Cosmetic | Noticed, didn't matter. |

---

## What passes

The rubric's bar item 4 is met when, across all five sessions:

1. **No task failures on tasks 1 to 5**, and no severity 4 problem anywhere.
2. **No accidental calls.**
3. **At least four of five people use a warm word unprompted** ("calm", "kind", "easy", "relaxed", or similar) before the word cards, and no more than one person picks any negative card.
4. **Median ease of 6 or higher** on tasks 1 to 5.
5. **No one reports feeling guilty or nagged.**

If any of these miss, fix the problems found, then run another round of five. Record each round's results and the build tested at the bottom of this file, so the rubric's verdict can cite them.

### Rounds run

| Date | Build | Passes? | Notes |
|---|---|---|---|
| | | | Not yet run. |
