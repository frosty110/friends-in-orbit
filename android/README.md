# Orbit — Android

Native Kotlin + Jetpack Compose implementation of the Orbit app, per
`../features/INDEX.md` (per-feature spec) and the design system in `../design/ui_kits/mobile_app/`.

## Requirements

- Android Studio Ladybug (or newer) — bundles JDK 17 + Gradle + Android SDK.
- Android SDK platform 35 + build-tools 35 (installed automatically on first sync).
- A device or emulator running **Android 12+ (API 31)** — the PRD's minimum.

If you prefer the command line, install JDK 17 and Gradle 8.11 yourself, then
run `gradle wrapper` once inside `android/` to generate `gradle-wrapper.jar`.

## Open the project

1. Android Studio → **Open…** → select the `android/` folder.
2. Wait for the first Gradle sync (downloads AGP 8.7, Compose BOM 2024.12, Room, Coil, nav-compose).
3. Run configuration is auto-created. Pick an emulator (Pixel 7 / API 34+
   recommended for edge-to-edge) and hit **Run**.

## What's implemented (v0.1)

Cosmetic-first pass — the navigation, theme, and every Tier-1 + Tier-2 screen
from `UI Kit Inventory` in the PRD render against a seeded in-memory repo.
**No real Android integrations yet**: CALL_LOG reader, ContactsContract ingest,
widgets, notifications, BiometricPrompt, and encrypted export are declared in
the manifest but not wired. That's deliberate — it lets the UI run on any
emulator without granting permissions and matches the design system's
"cosmetic-only" stance for this milestone.

**Screens wired in the nav graph:**

| Route                        | Source                                     | State |
| ---                          | ---                                        | ---   |
| `home`                       | `HomeScreen.kt`                            | Lists + due counts + Surprise me |
| `card/{listId}`              | `CardViewScreen.kt`                        | Drag-to-swipe with tilt, live heat strip, call CTA |
| `browse/{listId}`            | `BrowseListScreen.kt`                      | Search + due dots + chevron |
| `contact/{contactId}`        | `ContactDetailScreen.kt`                   | Hero, stats card, history, notes, Pause |
| `lists`                      | `ListsManagerScreen.kt`                    | List rows + "New list" dashed CTA |
| `lists/{listId}/config`      | `ListConfigScreen.kt`                      | Rule picker, interval slider, active hours, notify toggle |
| `settings`                   | `SettingsScreen.kt`                        | Privacy / Notifications / Data / About |
| `onboard/permissions`        | `OnboardingPermissionsScreen.kt`           | Call log + Contacts, plain-language copy |
| `onboard/create-list`        | `OnboardingCreateListScreen.kt`            | Template picker |
| `onboard/bulk-add`           | `OnboardingBulkAddScreen.kt`               | Multi-select from candidate list |

## Design system

Tokens are ported 1:1 from `../design/colors_and_type.css` and wired via
`CompositionLocalProvider` in `OrbitTheme`. Read through any composable:

```kotlin
OrbitTheme.colors.accent         // --accent
OrbitTheme.colors.surface        // --surface
OrbitTheme.type.hero             // --fs-hero + SemiBold + -1% tracking
OrbitTheme.shapes.lg             // 16dp — cards
OrbitTheme.spacing.x5            // 20dp — inside-card padding
```

The Material3 `ColorScheme` slots are aligned so stock M3 widgets (Slider,
Switch if used, TextField) pick up the warm palette without extra wiring.

**Fonts.** The spec calls for Inter. `Type.kt` currently falls back to
`FontFamily.SansSerif` (Roboto) — the `.woff2` files in `../design/fonts/` can't be
used by Android directly. Drop TTF/OTF Inter files into `app/src/main/res/font/`
and point `OrbitFont` at them to complete the port.

**Icons.** Phosphor SVGs live in `app/src/main/assets/icons/` and render via
Coil's SVG decoder. Referenced by name in code: `PhIcon(name = "phone-call")`.

## Next work (not in v0.1)

Roughly in PRD-v1 priority order. Each is a clean extension of the current scope.

1. **Room persistence.** Wire entities behind `OrbitRepository` — schema
   mirrors the data classes in `data/Model.kt`.
2. **Call log ingestion.** `ReadCallLogPermission` + `CallLog.Calls` content
   resolver query, 90-day window default (PRD §Call Detection).
3. **Contacts sync.** `ContactsContract` read, phone-number-as-fallback matching.
4. **Biometric lock.** `BiometricPrompt` gate at `MainActivity.onStart`.
5. **Notifications.** `POST_NOTIFICATIONS` flow + daily digest + list prompts.
6. **Widgets.** 2x2 + 4x2 Glance widgets.
7. **Encrypted export.** Symmetric-key JSON export via `StorageAccessFramework`.
8. **Tests.** Paparazzi screenshot tests for every Tier-1 screen against the
   two themes.
