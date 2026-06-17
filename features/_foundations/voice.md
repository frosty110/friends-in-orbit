# Voice and content rules

**Status:** active
**Last reviewed:** 2026-04-22
**Canonical for:** voice, tone, never-say list, empty-state framing
**Ground truth:** enforced at notification-formatter level (`features/notifications/README.md`); elsewhere enforced by review

When this doc disagrees with `README.md` §Content fundamentals, this doc wins.

## Voice

- **Warm but not saccharine.** This is an app for calling people you care about. Avoid cute, avoid clinical.
- **Direct, short sentences.** Matches the reduce-cognitive-load mission.
- **Second person.** "Your people," never "the contact."
- **Calm and unpressured.** Tone should feel safe and supportive — never clinical, never demanding. Reaching out is always the user's choice.
- **Empty states feel like a friend, not a coach.** "You're caught up," not "Great job, keep it going!"

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
- "You're caught up" — the all-done state
- "Want to..." — soft invitation, never demand
- "Surprise me" — the lightweight serendipity affordance

## Empty states — tone reference

- No one due: "You're caught up. Want to browse anyway?"
- No lists yet: quiet instruction, no urgency
- Permission denied: plain explanation of what's lost, offer to continue without
