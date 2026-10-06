# Design handoff: what these prototypes are

`design/preview/` and `design/ui_kits/mobile_app/` are the HTML and JSX prototypes exported from Claude Design in June 2026, before the 2026-06-22 Home redesign and the 2026-10-05 UX pass. They are reference only, never production code: [`../DESIGN.md`](../DESIGN.md)'s priority table governs, the Kotlin theme under `android/app/src/main/java/app/orbit/ui/theme/` is ground truth for what renders, and `design/colors_and_type.css` is canonical for token values (the two were brought back into step on 2026-10-05).

The handoff bundle's chat transcripts and `project/` folder were never committed; the intent they held lives in `features/*/README.md` and `vision/`. Do not recreate these prototypes pixel for pixel: build from the specs and the tokens, and read a difference between a prototype and the app as the prototype being stale.
