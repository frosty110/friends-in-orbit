---
name: my-orbit-design
description: Use this skill to generate well-branded interfaces and assets for My Orbit, either for production or throwaway prototypes/mocks/etc. Contains essential design guidelines, colors, type, fonts, assets, and UI kit components for prototyping.
user-invocable: true
---

Read the README.md file within this skill, and explore the other available files.

If creating visual artifacts (slides, mocks, throwaway prototypes, etc), copy assets out and create static HTML files for the user to view. If working on production code, you can copy assets and read the rules here to become an expert in designing with this brand.

If the user invokes this skill without any other guidance, ask them what they want to build or design, ask some questions, and act as an expert designer who outputs HTML artifacts _or_ production code, depending on the need.

## Quick orientation

My Orbit is an app for calling the people you keep meaning to call. Warm, quiet, unhurried — feels like a well-worn notebook, not a productivity tool. Used in emotionally loaded moments, so every surface is tuned to reduce cognitive load rather than optimize for engagement.

**Never use:** streaks, achievements, levels, gamification language, bright primaries, default tech blue, sharp corners, bouncy motion, emoji in product copy.

**Always use:** sentence case, second person, short sentences, warm colors (terracotta + sage + cream), 16px min body, 48px min tap target, rounded-outlined icons (Phosphor Regular).

## Files

- `README.md` — full voice, visual, and iconography guidelines
- `colors_and_type.css` — copy this into any artifact to get tokens + Inter fonts
- `fonts/` — self-hosted Inter (Regular/Medium/SemiBold/Bold)
- `assets/icons/` — 58 Phosphor Regular SVGs (use via CSS mask or inline SVG with currentColor)
- `assets/logo/` — wordmark + glyph
- `preview/` — design-token reference cards
- `ui_kits/mobile_app/` — interactive app recreation; crib components from here
