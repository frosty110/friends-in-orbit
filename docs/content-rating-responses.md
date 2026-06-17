# Content rating responses — Play Console IARC questionnaire

**Source of truth for filling the Play Console → App content → Content rating IARC form.**
Human: answer each Play Console IARC question using the responses below.

Expected rating: **Everyone (ESRB) / PEGI 3 / USK 0 / CLASS IND L**

---

## IARC questionnaire responses

Play Console presents the IARC questionnaire as a series of yes/no questions grouped by category.
Answer each as shown below.

### Violence

| Question | Answer |
|----------|--------|
| Does the app contain any violence? | No |
| Does the app contain cartoon or fantasy violence? | No |
| Does the app contain realistic violence? | No |
| Does the app contain intense or graphic violence? | No |

### Sexual content

| Question | Answer |
|----------|--------|
| Does the app contain sexual content or nudity? | No |
| Does the app contain references to sexual themes? | No |

### Mature themes

| Question | Answer |
|----------|--------|
| Does the app contain profanity? | No |
| Does the app contain references to drugs, alcohol, or tobacco? | No |
| Does the app contain horror or frightening content? | No |

### Gambling

| Question | Answer |
|----------|--------|
| Does the app simulate gambling? | No |
| Does the app include real-money gambling? | No |
| Does the app include in-app purchases or premium currency? | No (v1.0 — no IAP in initial release) |

### User-generated content

| Question | Answer |
|----------|--------|
| Does the app allow users to share content publicly with other users? | No |
| Does the app include user-generated content that is visible to other users? | No |
| Does the app contain social features (chat, forums, etc.)? | No |

Orbit stores notes and contact data locally on the device. No content is shared publicly or with
other users. There is no social, chat, forum, or community feature.

### Ads

| Question | Answer |
|----------|--------|
| Does the app display advertisements? | No |

### Location

| Question | Answer |
|----------|--------|
| Does the app share real-time location with others? | No |
| Does the app use precise location? | No |

---

## Expected resulting rating

| Rating body | Expected rating |
|-------------|----------------|
| ESRB (US) | Everyone |
| PEGI (Europe) | PEGI 3 |
| USK (Germany) | USK 0 |
| CLASS IND (Brazil) | L (Livre — all ages) |
| CERO (Japan) | A (all ages) |
| GRAC (South Korea) | All |

These ratings are consistent with a utility app that handles personal contact information locally
with no violence, sexual content, gambling, or public user-generated content.

---

## Notes for Play Console

- Orbit is a personal contacts/calling utility. It does not target children.
  Set the **target age group to 18+** in the "Target audience and content" section.
- There are no in-app purchases in v1.0. If a one-time "support the developer" IAP is added in a
  future version, update the questionnaire and App content → In-app purchases answer before that
  release goes live.
- Re-fill the questionnaire if a future release adds any social sharing, public notes, or community
  features — those would change the UGC answers above.
