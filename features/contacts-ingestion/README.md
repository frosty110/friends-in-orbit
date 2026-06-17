# contacts-ingestion

**Status:** in-progress
**Last reviewed:** 2026-06-09
**Ground truth:**
- Code: `android/app/src/main/java/app/orbit/data/android/ContactsReader.kt`, `android/app/src/main/java/app/orbit/domain/usecase/IngestPhoneContactsUseCase.kt` (delta-sync), `android/app/src/main/java/app/orbit/calllog/ContactsIngestWorker.kt` (background trigger), `android/app/src/main/java/app/orbit/data/entity/ContactPhoneEntity.kt` (`contact_phones`)
- Tests: `android/app/src/test/java/app/orbit/domain/usecase/IngestPhoneContactsUseCaseTest.kt`, `android/app/src/test/java/app/orbit/calllog/ContactsIngestWorkerTest.kt`

---

## Product

### Why it exists

The user's phone contacts are the source of truth. Orbit keeps a thin metadata layer (list membership, notes, overrides) keyed to Android's contact ID, with phone number as fallback. Without this ingestion, there is nothing to surface and no way to bulk-populate lists.

### User story

As a user during onboarding, I multi-select people from my phone contacts and add them to a list. The app never asks me to re-type anyone. If I rename a contact in my phone, Orbit keeps up. If I delete a contact, Orbit doesn't lose my notes.

### Behavior

**Source of truth.** `ContactsContract`. Read-only sync. Orbit never writes to system contacts.

**Metadata layer.** Keyed to normalized phone numbers (unique `contacts.normalizedPhone` plus the per-number `contact_phones` table); `phoneContactId` is carried for device linkage but matching is number-first.

**Bulk add.** Multi-select picker with search and filter. Used during onboarding and when adding to existing lists. Shows name, photo, primary number. Filter chips AND together by default; the call-frequency triplet (Commonly called / Rarely called / Never called) behaves as a single-select toggle group because the predicates are mutually exclusive — tapping one switches groups in a single tap.

**Rename handling.** Auto-match by number; the delta-sync refreshes `displayName` (and photo/starred flag) in place, so a rename propagates on the next ingest pass. If no number matches, the Orbit contact orphans (`isOrphaned`) — surfaced in contact-detail with a manual re-link path.

**Deletion handling.** Keep app data (notes, history, list memberships), flag contact as orphaned (`isOrphaned = true`); the flag flips back automatically if the device contact returns. User can archive permanently or re-link to a different phone contact.

**Contact creation strictness (decision per PRD §Open Decisions).** Currently spec'd as **strict** — a contact must exist in phone contacts to be added to Orbit. Soft mode (adding to Orbit also creates a phone contact) is deferred.

### Acceptance criteria

- [ ] Bulk-add picker scrolls smoothly with 500+ contacts (virtualized list).
- [ ] Search matches by name and by number.
- [ ] Deleting a phone contact → Orbit shows disconnected badge, preserves all metadata.
- [ ] Renaming a phone contact → Orbit reflects new name within one app session.
- [ ] Data Safety form justifies `READ_CONTACTS` in terms this feature alone satisfies.
- [ ] All metadata persisted through encrypted Room per ADR 0002.

### Not in scope

- Writing to system contacts. Read-only per PRD.
- Importing from non-system sources (vCard files, Google Contacts API, CSV).
- Contact deduplication beyond the system's own merged-contacts handling.
- Syncing contacts across devices.

### Open product questions

- PRD §Open Decisions: strict vs soft contact creation. Currently strict; soft mode deferred.
- Re-link UX for orphaned contacts: manual picker, or fuzzy-match suggestions? Leaning manual for v1.
- Should the bulk-add picker show contacts already on the target list (greyed out), or hide them? Leaning show-greyed for clarity.

---

## Technical

### Architecture

- `ContactsReader` — ContentResolver wrapper; pure Android. Silently returns empty when `READ_CONTACTS` is not granted.
- `IngestPhoneContactsUseCase` — the delta-sync. One pass, single transaction: (1) **insert** device contacts with no matching Room row; (2) **refresh** matched rows whose `displayName` / `photoUri` / `phoneContactId` / `isStarred` drifted, clearing `isOrphaned`; (3) **sync the phone set** — `contact_phones` rows replaced when the device number set or primary changed; (4) **flag orphans** (`isOrphaned = true`) for mirrored rows with no device match. Rows are never deleted — notes and history survive an address-book deletion. An empty read (permission revoked or truly empty) returns without orphaning anything. Emits a non-PII `IngestSummary` (inserted/refreshed/orphaned/restored).
- `ContactsIngestWorker` — runs the use case off the cold-start path. Triggered by (a) the `READ_CONTACTS` grant transition and (b) a `ContactsContract` `ContentObserver` fire (via `ContentObserverController`). A 24h DataStore TTL dedupes grant-path re-runs; observer fires set `force = true` and bypass the TTL ("the address book changed right now" must not wait a day).

### Data model

`ContactEntity` (table `contacts`, fields engines/UI read; user-owned flags like ignore/archive/pause are never touched by ingest):
```
id: Long,
phoneContactId: Long?,      // device contact id; mirrored rows only
phoneNumber: String,        // primary (display) number
normalizedPhone: String,    // unique match key
displayName: String,        // cached from ContactsContract, refreshed by delta-sync
photoUri: String?,
isStarred: Boolean,         // device starred flag, imported + refreshed
isOrphaned: Boolean,        // true when the device row vanished; flips back if it returns
pausedUntil: Instant?, isIgnored, isArchived, ruleOverrideJson, ...
```

`ContactPhoneEntity` (table `contact_phones`) — one row per number a contact carries (second SIM, work line), `normalizedPhone` globally unique, exactly one `isPrimary = true` per contact, CASCADE-deletes with the contact. The call-log reconciler builds its number → contact lookup from this table so second-number calls match.

### Permissions / integrations

- **Manifest:** `android.permission.READ_CONTACTS`.
- **ContentResolver URIs:** `ContactsContract.CommonDataKinds.Phone.CONTENT_URI` for numbers, `ContactsContract.Contacts.CONTENT_URI` for the contacts themselves.
- **Photos:** `ContactsContract.Contacts.Photo` — fetched lazily via Coil.

### Known gotchas

- Match keys are normalized phone strings, not lookup keys — cross-contact secondary-number collisions resolve first-wins at ingest (`OnConflictStrategy.IGNORE`).
- Photo URIs expire; fetch lazily, never cache the URI itself.
- "Permission revoked" and "address book truly empty" are indistinguishable from an empty `ContactsReader.readAll()` — the ingest returns `IngestSummary.EMPTY` without orphaning anything, because mass-orphaning on a revoke would be data loss in spirit.
- The worker's 24h TTL guards against clock rollback (WR-04): a negative `Duration.between` funnels through the work block and heals the drift.
- The TTL must be bypassed (`force = true`) on observer fires — gating "the address book changed right now" on a 24h TTL made new contacts invisible for up to a day.

### Not in scope (technical)

- Ingesting on every app resume. The grant transition + `ContentObserver` + TTL cover freshness without cold-start cost.
- Caching a full contacts snapshot in app storage. Room mirrors only the metadata layer Orbit needs.

### Open technical questions

- ~~Read-through every collect or snapshot-on-app-start?~~ Resolved: Room is the read model; the device provider is only touched by the ingest pass (worker-triggered), keeping cold start free of provider scans.
