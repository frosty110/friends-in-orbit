# Mission

**Status:** active
**Last reviewed:** 2026-06-30
**Canonical for:** mission, target user, non-negotiable principles
**Ground truth:** n/a (principle-level)

## What Orbit is

An Android app that reduces the cognitive load of deciding who to call. Users organize contacts into mood and context-based lists (inner orbit, outer orbit, late night, people who ground me), and the app surfaces one person at a time with a simple yes-or-no decision.

## Why it exists

To increase the frequency and quality of relational reaching-out by removing the "who should I call right now?" friction. Built for people whose relationships matter and who want a lightweight way to stay in touch more consistently.

## What Orbit adapts to

Life isn't a steady cadence. Some weeks have room for everyone; some weeks you're underwater. Orbit blends into the life the user is actually living — a gentle nudge toward the connection they want, paced to the season they're in, drawing on the support network they already have. When life is full, it asks for less and lets nothing pile up. When someone needs people more, it leans in. It is never one more thing demanding a fixed pace.

This is what separates Orbit from every cadence tracker: the goal is not a number to hit, it is a relationship rhythm the user keeps — and Orbit bends that rhythm to fit their life, never the other way around. Made concrete in `features/life-right-now/`.

## Target user

**Primary:** someone with a growing community of people they want to stay in touch with.

**Secondary:** anyone managing many relational contacts where deciding who to reach out to feels like work (long-distance friends, family, sales/networking relationships, parents of adult kids).

## Non-negotiable principles

1. **Reduce activation energy.** Notifications and widgets bring the suggestion to the user; the app reduces cognitive load to yes-or-no.
2. **Privacy-first.** Local-only storage, encrypted at rest, auto-hide list names when the app loses focus.
3. **Generic framing.** The app stays generic and broadly useful. No use-case-specific features in the product surface.
4. **Algorithm decides, user decides yes or no.** The app never asks "who should I call?"
5. **No gamification.** No streaks, no achievements. Reflection stats only.
6. **No shame-based nudges.** Never per-person nags. Notifications surface opportunity, never absence.
7. **Bend with the user's life.** A person's capacity for connection changes with their season — busy, depleted, grieving, lonely, far from home. Orbit adapts to the season the user is in; it never holds them to a pace their life can't keep, and never treats a quiet stretch as failure. The goal serves the user; the user never serves the goal. Made concrete in `features/life-right-now/`.

## What Orbit should feel like

- A well-worn notebook
- A warm room you return to
- A quiet friend who hands you the right name at the right time

## What Orbit should never feel like

A CRM. A productivity tool. A social media app. A game. Something optimized for engagement.
