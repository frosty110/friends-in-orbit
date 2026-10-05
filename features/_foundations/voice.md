# Voice and content rules

**Status:** active
**Last reviewed:** 2026-10-05
**Canonical for:** voice, tone, never-say list, empty-state framing
**Ground truth:** enforced at notification-formatter level (`features/notifications/README.md`); elsewhere enforced by review

When this doc disagrees with `README.md` §Content fundamentals, this doc wins.

## Voice

- **Warm but not saccharine.** This is an app for calling people you care about. Avoid cute, avoid clinical.
- **Direct, short sentences.** Matches the reduce-cognitive-load mission.
- **Second person.** "Your people," never "the contact."
- **Calm and unpressured.** Tone should feel safe and supportive — never clinical, never demanding. Reaching out is always the user's choice.
- **Empty states feel like a friend, not a coach.** "All quiet for now," not "Great job, keep it going!"

## Writing rules

- **Sentence case.** Everywhere. No title case except brand name "Orbit."
- **No exclamation marks.** Ever.
- **No emoji in product copy.** (Okay in chat conversations with the builder; never in the app.)
- **Active voice. Present tense.** "You have 3 people due" not "3 people are due for you."
- **16sp minimum body size.** Used in emotionally loaded moments — don't make people squint.

## Never say

- Streaks, achievements, levels, XP, rewards, unlocks
- "You haven't called X in N days" — shame framing
- "Crush your goals," "stay on track," "beat your record" — hustle framing
- "Great job," "awesome," "keep it going!" — coach framing
- Emoji, unicode glyphs, ASCII art in product copy
- "The contact," "the user," "the entity" — clinical framing

## Always say

- "Patterns," "rhythms," "gaps" — neutral temporal framing
- "Your people" — possessive + human
- "All quiet for now" for the moment nobody is due. (Until 2026-10-05 this said "You're caught up". The queue is continuous by design since the tide-marker change of 2026-05-08, and Home's HOME-6 retired "caught up" and "due" language, so nothing should read as a cleared backlog.)
- "Want to..." — soft invitation, never demand
- "Surprise me" — the lightweight serendipity affordance

## Empty states — tone reference

- No one due: "All quiet for now." Then who comes up next and when, and a way to browse anyway.
- No lists yet: quiet instruction, no urgency
- Permission denied: plain explanation of what's lost, offer to continue without

## Glossary: one word for one idea

Added 2026-10-05 ([UX rubric](../../vision/ux-rubric.md) D7). The same idea had up to four names on screen; a reader should never wonder whether two words mean two things.

| Say | Means | Don't say |
|---|---|---|
| **Later** | Move this person further out on this list. | Skip, defer, pass, snooze |
| **Sooner** | Bring this person forward on this list. | Surface sooner, move up, boost |
| **Nudge** | The notification Orbit sends when someone on a list is worth a call. | Reminder, prompt, notification (in UI copy), alert |
| **List** | A group of people the user keeps in touch with, with its own rhythm. | Orbit (as a noun for a list), group, circle |
| **Rhythm** | How often the user means to talk to people on a list ("every 2 weeks"), and the 7-day strip on Home. | Cadence, frequency, interval, threshold |
| **Call** | A phone call. "Log a call" for one Orbit couldn't see. | Connection (in UI copy), interaction |
| **Note** | Something the user wrote about a person. | Memo, comment |
| **Pause** | Stop nudges for a person or list for a while. | Mute (for people), suspend |

**Time since a call** is always worded by one formatter, the same way everywhere: "today", "yesterday", "3 days ago", "2 weeks ago", "3 months ago". Never "27 days ago" on one screen and "3 weeks" on another. Times of day follow the phone's 12 or 24 hour setting.

