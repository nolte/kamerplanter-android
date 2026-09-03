# Requirements — Add a plant, by photo identification or by hand

<!--
Produced via the `requirements-elicit` skill, following
spec/project/requirements-elicitation/ (canonical spec resolved from the shared
claude-shared repo; not vendored in this project).
`c_d` is an uncertainty proxy (self-consistency-derived), not a calibrated
probability. A requirement is `confirmed` only after an explicit teach-back.

Elicited as the requirements gate of an `issue-orchestrate` run over
https://github.com/nolte/kamerplanter-android/issues/50. No roadmap item covers plant
creation today — R-4 is the plant list, R-5 the plant detail screen — so this artefact
precedes its roadmap item rather than following one.

Every endpoint claim below was read from the vendored schema
`core/network/openapi/openapi.json` (release `v0.2.0`, `apiVersion 1.0.0`), not from the
upstream spec drafts, which differ. Backend source citations are against
`nolte/kamerplanter@develop` as of 2026-08-21 (`8c75a63ea`).
-->

## Bounded context

- **What:** creating a plant instance from the app. The user picks one of two routes —
  photograph the plant and let the instance identify it, or enter it by hand — and both
  routes end in the same editable form, which is confirmed before anything is written.
  Identification pre-fills; it never creates. A photo taken during the flow is kept with
  the plant as its cover picture. Recognition happens **only** in the instance; the app
  captures, renders and writes.
- **For whom:** the plant owner adding a new plant, who may not know what it is.
  Secondarily the self-hoster, whose instance decides through
  `GET /api/v1/recognition/status` and the `plant_identification` consent whether the
  identification route exists at all.
- **Out of scope:**
  - The USB microscope as an image source. It resolves an aphid, not a shrub; `REQ-052`
    §8.2 reduces the mobile client to camera plus photo library, and the microscope
    already belongs to the pest path (issues #1, #10).
  - The conversational AI assistant (`/api/v1/t/{tenant_slug}/ai/…`, `REQ-031`) as a third
    route — see R4.
  - Disease and pest diagnosis from the same photo (`/cv-diagnosis`, `include_health`).
  - Editing an existing plant. Correction happens *before* creation here.
  - Species master-data management beyond the single creation in R21: no cultivars, no
    lifecycle, no phase sequences, no enrichment review.
  - Care profiles, phases and reminders — server-side consequences of creation.
  - Contributing the photo as a recognition reference (`POST /identification/reference`).
  - Identification history (`GET /identification/history`) as a browsable screen.
  - Offline capture queues, refused outright by `REQ-052` §8.3.

## Understanding KPI

- Thresholds: `τ_low = 0.4`, `τ_high = 0.8`, self-consistency `k = 2`, question budget = `6`
  (spec defaults; unchanged).
- Question turns spent: 3 decision turns (each a tightly-coupled group) + 2 teach-backs +
  1 targeted follow-up after the first teach-back was rejected = **6 / 6**.
- `U_gate = min_d c_d` over required dimensions = **0.85**.
- Termination: `saturation` — every dimension is teach-back confirmed and no remaining
  question carries positive net EVPI. The budget was reached in the same turn, but the
  stop is saturation, not the cap; no cell sits below `τ_high`.

### Gap matrix

| Dimension | Applicable | `c_d` | Uncertainty source | Evidence event |
|---|---|---|---|---|
| `functional` | yes | 0.90 | specification (resolved) | six questions across three decision turns; endpoint contracts read from the pinned schema; two teach-backs |
| `non_functional` | yes | 0.85 | interpretation | `REQ-052` §3 profiles, the 5 MB `/identify` ceiling, the one-original-two-derivatives decision; teach-back |
| `constraints` | yes | 0.85 | interpretation | ADR 0001 isolation rule, `OpenApiDocumentPreparer.KEEP_TAGS`, the absent species-search endpoint, the absent role lookup |
| `domain_objects` | yes | 0.90 | specification (resolved) | `PlantCreate`, `SuggestionResponse`, `SelectResultResponse`, `PlantPhotoResponse` read field-by-field from the pinned schema; the `instance_id` convention read from backend source |
| `actors` | yes | 0.85 | specification (resolved) | bounded context; the `viewer` / `grower` split established from `src/backend/app/api/v1/species/router.py:140-143` |
| `acceptance_criteria` | yes | 0.85 | specification (resolved) | issue #50's criteria, revised by the five decisions; teach-back |
| `edge_cases` | yes | 0.85 | specification (resolved) | the outcome table in R33, the weak-result retry in R16, photo-failure isolation in R30; teach-back |
| `scope_boundaries` | yes | 0.90 | specification (resolved) | the out-of-scope list above, revised in both directions during the interview; teach-back |

Self-consistency (`k ≥ 2`) was decisive on two dimensions:

- **`scope_boundaries` — how many routes the request asked for.** The originating phrase
  was *"die Wahl haben zwischen Erkennung durch ai und Bilderkennung, oder vollständig
  manuell"*. Two readings: one recognition route (AI *is* the image recognition) plus a
  manual one, or three routes treating the conversational assistant as its own. The
  instance offers both surfaces — `/identification/identify` and
  `/t/{tenant_slug}/ai/conversations` — so the ambiguity was real rather than academic.
  The operator chose two routes; see R4.
- **`functional` — what happens when the identified species is not in the catalogue.**
  Three readings: refuse and point at the web UI, create the species, or create it after a
  client-side duplicate check. The operator first chose refusal, rejected it at the
  teach-back, and settled on plain creation once the server-side dedup was established;
  see R25, R26 and the correction record below.

## Requirements

<!-- EARS/CNL form; each tagged confirmed/assumed with traceability. -->

### Routes and availability

- **R1** — WHEN the user starts adding a plant, the app SHALL offer at most two routes:
  identification from a photo, and manual entry. Which of the two are offered is governed by
  R2 and R3 — on an instance with recognition switched off, the manual route is the only one.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: issue #50 §Summary; Q1; teach-back
- **R2** — WHILE an instance is connected, the app SHALL offer the manual route regardless
  of the recognizer's state, and SHALL offer neither route while disconnected.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: issue #50 §Scope; teach-back
- **R3** — The app SHALL resolve `GET /api/v1/recognition/status` before offering the
  identification route, and SHALL NOT offer it WHILE `available` is false.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: pinned schema, `IdentificationStatusResponse`; the endpoint is unauthenticated by design so the UI can decide before login; teach-back
- **R4** — The conversational AI assistant SHALL NOT be a third route. It returns advice
  prose, not a `species_key`, so its result would still have to be looked up in the
  catalogue before it could fill the form.
  - _dimension_: `scope_boundaries` · _status_: `confirmed` · _source_: Q1 = "Ein Erkennungsweg + manuell"; teach-back

### Consent

- **R5** — The app SHALL NOT capture an image for identification before the
  `plant_identification` consent is granted; the check happens before the camera opens,
  never merely before the upload.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: `REQ-029-A` §0.1.1 point 2 (Phase 1 sends the photo to Pl@ntNet, so consent is a hard precondition); teach-back
- **R6** — The app SHALL obtain that consent in-app via `/api/v1/privacy/consents`,
  displaying the purpose label, description and legal basis **verbatim as the server
  supplies them**, and SHALL NOT author its own consent wording.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: `ConsentResponse` fields in the pinned schema; the same rule this project already adopted for `pest_detection_cloud` (`project/requirements/pest-detection.md` R4); teach-back
- **R7** — The photo upload that keeps a picture with the plant SHALL NOT require consent.
  `plant_identification` covers sending the image to a third party, not storing it on the
  user's own instance.
  - _dimension_: `constraints` · _status_: `confirmed` · _source_: `REQ-052` §9 ("Der Einwilligungsvorbehalt … betrifft nicht die Erfassung, sondern den Versand an den Dritten"); teach-back

### Capture

- **R8** — The app SHALL offer camera and photo library as image sources on **both**
  routes, request each permission only WHEN the user reaches for that source, and route a
  permanently denied permission into system settings rather than a dead end.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: `REQ-052` §8.2, AK-69; Q5 = "Ja, gleiche Aufnahme auf beiden Wegen"; teach-back
- **R9** — The app SHALL show every captured image at the size it will be uploaded — that
  is, after normalization — and SHALL allow discarding and retaking before anything is sent.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: `REQ-052` §2.1, AK-61; teach-back
- **R10** — The app SHALL derive both uploads from **one** captured original: the
  `/identify` payload under the `recognition` profile (1280 px long edge, quality 0.85) and
  the plant photo under the `gallery` profile (2048 px, quality 0.9). It SHALL NOT re-upload
  the recognition-sized bytes as the plant's picture.
  - _dimension_: `non_functional` · _status_: `confirmed` · _source_: `REQ-052` §3; `/identify` does not persist the image (pinned schema: "EXIF-stripped … and is never persisted"); teach-back
- **R11** — Every uploaded image SHALL carry no EXIF when it leaves the device, and a
  capture rotation that lived only in metadata SHALL be written into the pixels first.
  - _dimension_: `non_functional` · _status_: `confirmed` · _source_: `REQ-052` §5, AK-63, AK-65; teach-back
- **R12** — The normalization profile SHALL be a parameter of the existing
  `core/camera/JpegDownscale`, not a second copy of it. It currently hard-codes
  `MAX_EDGE_PX = 2048` and quality 90 — the pest-detection profile, documented as such in
  its KDoc.
  - _dimension_: `constraints` · _status_: `confirmed` · _source_: `core/camera/src/main/kotlin/…/JpegDownscale.kt:24-36`; `REQ-052` §3 ("Die Profile sind der einzige Ort, an dem diese Zahlen stehen"); teach-back

### Identification

- **R13** — The app SHALL present the returned suggestions in rank order with confidence,
  common and scientific name, SHALL require an explicit selection, and SHALL persist that
  selection through `POST /identification/{request_key}/select`.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: `REQ-029-A` §0.1.1 point 3; teach-back
- **R14** — The app SHALL NOT create anything before the form is confirmed, including WHEN
  a suggestion carries `auto_accept: true`; that flag is a display hint, never an
  instruction to skip a step.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: `REQ-029-A` §0.1.1 point 3 ("kein stilles Auto-Anlegen der Top-1, auch nicht oberhalb `CONFIDENCE_AUTO_ACCEPT`"); teach-back
- **R15** — `is_plant: false`, an empty suggestion list, and a list holding only
  low-confidence candidates SHALL be three visibly different outcomes.
  - _dimension_: `edge_cases` · _status_: `confirmed` · _source_: `IdentifyResponse` in the pinned schema; `REQ-029` §1.2; teach-back
- **R16** — The app SHALL send `organ=auto` by default, and SHALL offer an explicit organ
  choice (`leaf`, `flower`, `fruit`, `bark`, `habit`) only after one of R15's weak outcomes,
  re-running `/identify` with the chosen value on the image already captured.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: Q4 = "Erst bei schwachem Ergebnis fragen"; teach-back

### The capture form

- **R17** — Both routes SHALL reach the same editable form. On the identification route the
  species field SHALL carry the confirmed match and SHALL display its scientific name, so
  the user can see *what* was matched rather than only that something was.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: issue #50 §Summary; teach-back
- **R18** — On the manual route the species field SHALL search the loaded catalogue by
  common **and** scientific name, and SHALL report "nothing matches" differently from "the
  catalogue is empty". The search SHALL be client-side: `GET /api/v1/species` accepts
  `offset`/`limit` and nothing else.
  - _dimension_: `constraints` · _status_: `confirmed` · _source_: pinned schema; `src/backend/app/api/v1/species/router.py` declares no `/search` route either, so `REQ-021` §3.8's assumed `GET /species/search?q=…` does not exist; teach-back
- **R19** — The app SHALL pre-fill `instance_id` with the **web UI's own rule for a single
  plant**, `{PREFIX}-{MMDD}-{SUFFIX}`: `PREFIX` is the first five `A`–`Z`/`0`–`9` characters
  of the species' scientific name after folding diacritics and dropping everything else,
  upper-cased, or `PLANT` while no species is chosen; `MMDD` is today's month and day; `SUFFIX`
  is the current time in milliseconds modulo 36³ as three base-36 characters. The field SHALL
  stay visible and editable, and SHALL be re-proposed when the species changes unless the user
  has edited it.
  - _dimension_: `domain_objects` · _status_: `confirmed` · _source_: `src/frontend/src/utils/idGenerator.ts` `generateInstanceId` and its call sites in `src/frontend/src/pages/pflanzen/PlantInstanceCreateDialog.tsx`; the plants observed on the operator's instance (`MONST-0713-WG7`, `AGLAO-0617-RB5`, `DAHLI-0710-3LN`); operator decision 2026-09-03 "UI und App sollten den selben Regeln folgen"
- **R20** — The location SHALL NOT be part of the identifier. (Superseded: the earlier text
  omitted the *location segment* of the planting-run convention when no location was chosen;
  the web UI's rule has no location segment at all.)
  - _dimension_: `domain_objects` · _status_: `confirmed` · _source_: `idGenerator.ts` reads only the species name and the clock; operator decision 2026-09-03
- **R21** — The app SHALL check the proposed identifier for uniqueness against the plant
  list it has loaded — stepping the suffix past any identifier in use, which the web UI
  leaves to the clock — and SHALL treat that check as best-effort. `GET /plant-instances` is
  paginated (default 50, maximum 200) and `POST /plant-instances` declares no `409`, so a
  collision is neither fully detectable client-side nor refused server-side.
  - _dimension_: `edge_cases` · _status_: `confirmed` · _source_: pinned schema, both routes; teach-back
- **R22** — The app SHALL pre-fill `planted_on` with the current date, keep it editable, and
  refuse creation with a field-level message WHEN it or `instance_id` is cleared.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: `PlantCreate` required fields in the pinned schema; teach-back
- **R23** — The app SHALL offer site selection from `GET /sites` and location selection from
  `GET /locations?site_key=…`, enabling the location field only after a site is chosen.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: pinned schema — `site_key` is a required query parameter on the locations route, so no tenant-wide locations call exists; teach-back
- **R24** — The app SHALL NOT offer any other `PlantCreate` field; the remainder is sent as
  `null`.
  - _dimension_: `scope_boundaries` · _status_: `confirmed` · _source_: `REQ-021` §3.8's Quick-Add field set (species, nickname, site, location); teach-back

### The species gap

- **R25** — WHEN the selected suggestion carries `species_in_database: false`, the app SHALL
  offer to create the species through `POST /api/v1/species`, populated from the fields
  `SpeciesCreate` actually declares — `scientific_name`, `common_names`, `genus` — and SHALL
  then create the plant against the returned key. The suggestion's `family` and `gbif_id` have
  **no home in that schema**: it carries `family_key` (a reference the app cannot resolve from
  a family *name*) and no GBIF field at all, so the taxonomic linkage the identification
  supplies is dropped on creation. Named rather than smuggled in: sending either field would
  be rejected or silently ignored.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: Q2 revised at the teach-back to "Doch anlegen lassen"; `SpeciesCreate` requires only `scientific_name`; teach-back 2
- **R26** — The app SHALL NOT perform its own duplicate check before that call. The backend
  create is idempotent: it resolves an existing record by canonical dedup key through an
  atomic UPSERT on `scientific_name_normalized`, and upstream names this exact
  identify→create path as the reason the rule exists. That idempotency covers the normalized
  scientific name only: the route still declares a `409` ("A conflicting resource already
  exists (duplicate key or unique constraint)"), which R33 carries as its own outcome.
  - _dimension_: `constraints` · _status_: `confirmed` · _source_: `src/backend/app/domain/services/species_service.py:206-213`; teach-back 2
- **R27** — The app SHALL treat a `403` on species creation as a named outcome rather than
  a preventable state. No pre-flight role check is possible: the pinned surface carries no
  role field on `/users/me` or `TenantResponse`, and creation is refused for a `viewer`.
  Note that `403` is **undeclared** on this route — the schema lists `201/401/404/409/422` —
  so the generated client will not type it and the app recognizes it by status code alone.
  - _dimension_: `actors` · _status_: `confirmed` · _source_: `src/backend/app/api/v1/species/router.py:140-143`; pinned schema, `UserProfileResponse` (the response of `/api/v1/users/me`) and `TenantResponse` fields; teach-back 2

### The photo

- **R28** — The app SHALL offer keeping the captured photo with the plant on **both**
  routes, defaulted on whenever a photo exists, and SHALL issue no upload when the user
  turns it off.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: Q5 = "Ja, gleiche Aufnahme auf beiden Wegen"; teach-back
- **R29** — The app SHALL upload that photo only after the plant exists, through
  `POST /plant-instances/{key}/photos`, and SHALL then set it as the cover explicitly
  through `PUT /plant-instances/{key}/photos/{attachment_id}/cover`. The explicit call is
  kept although `cover_photo_ref` already resolves to the first photo: without it
  `is_cover` stays `false`, and a client reading that flag would show a plant with no cover.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: pinned schema, `PlantPhotoListResponse.cover_photo_ref` ("explicit cover or first photo"); teach-back
- **R30** — A failed photo upload or a failed cover call SHALL NOT roll back or repeat the
  creation. The plant is reported as created and the photo as not saved, with the retry
  leading to the plant's detail screen rather than back into the capture flow.
  - _dimension_: `edge_cases` · _status_: `confirmed` · _source_: issue #50 §8; the per-instance quota is enforced before bytes are written, so a refusal leaves no orphan; teach-back

### Creation, linking and outcomes

- **R31** — After a successful creation on the identification route, the app SHALL link the
  identification request to the new instance through
  `POST /identification/{request_key}/instance` as a best-effort step; a failure there SHALL
  NOT be reported as a failed creation.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: pinned schema, `LinkInstanceRequest`; teach-back
- **R32** — A successful creation SHALL land on the created plant's detail screen, and the
  plant list SHALL show it after a reload.
  - _dimension_: `functional` · _status_: `confirmed` · _source_: issue #50 §9; teach-back
- **R33** — The app SHALL render each of these as its own state with its own next action,
  and SHALL NOT offer "try again" on a `403`: not connected · identification not offered ·
  consent required · `401` credential refused · `403` role · not a plant · nothing
  recognized · image refused (`422`) · species conflict (`409` on species creation) ·
  rate limited · instance not understood · transport failure · photo not saved.
  - _dimension_: `edge_cases` · _status_: `confirmed` · _source_: issue #50 §10; the shape already used by `PlantListState` and `PestDetectionState`; teach-back

### Constraints on the implementation

- **R34** — No generated DTO, Retrofit type or OkHttp type SHALL appear in the feature
  module; the network surface is reached through app-owned seams.
  - _dimension_: `constraints` · _status_: `confirmed` · _source_: `CLAUDE.md` §Architecture; ADR 0001; the existing `NetworkPestDetectionClient` pattern; teach-back
- **R35** — `OpenApiDocumentPreparer.KEEP_TAGS` SHALL gain `identification`, `recognition`,
  `species` and `sites`, and the checked-in client SHALL be regenerated from the pinned
  schema with its digest check still passing. `plant-photos` is already present, so the
  photo path needs no widening.
  - _dimension_: `constraints` · _status_: `confirmed` · _source_: `buildSrc/src/main/kotlin/OpenApiDocumentPreparer.kt:36-49`; tags read per operation from the pinned schema; teach-back

## Surviving assumptions / open risks

- **A duplicate `instance_id` is not preventable.** R21 makes the check best-effort by
  construction. Nothing observed refuses a collision server-side, so two plants can end up
  sharing a human-facing identifier. Named rather than solved: solving it needs an upstream
  uniqueness guarantee this project does not own.
- **The `403` on species creation is discovered late.** R27 accepts that a `viewer` learns
  they may not create a species only after choosing a suggestion. The alternative — a
  pre-flight role lookup — has no endpoint on the pinned surface. If upstream later exposes
  the caller's role, this becomes a preventable state and R27 should be revisited.
- **`429` is undeclared.** `REQ-029` §3.7 raises `RateLimitError` as a `429`, but the pinned
  schema declares only `401/403/404/422` on `/identify`. R33 requires it to read as its own
  outcome; until the schema declares it, the app recognizes it by status code alone.
- ~~**The web UI's own `instance_id` rule for single plants was not located.**~~ Located on
  2026-09-03 (`src/frontend/src/utils/idGenerator.ts`) after the first device run showed the
  planting-run prefix could not be derived at all — see the correction record. R19/R20 now
  follow it.
- **Deviation from `REQ-021` §3.8.** The Quick-Add flow specifies four fields — species,
  nickname, site, location. This form carries the same four plus `instance_id` and
  `planted_on`, which are mandatory on `PlantCreate` and which that flow does not name.
  Hiding them would mean inventing the plant's human-facing identity silently, which R19
  refuses. Note that no numbered requirement names the nickname (`plant_name`) field on its
  own; it reaches the form through this deviation and through R24's carve-out, which is thin
  ground for a field the user sees. Worth promoting to its own requirement on the next pass.
- **Two statuses this flow depends on are undeclared in the schema.** `429` on `/identify`
  (already named above) and `403` on `POST /api/v1/species` (R27). Both are recognized by
  status code alone, because the generated client types only what the document declares. A
  third, `400` on `/identify`, was listed as an outcome in an earlier draft of R33 and has
  been removed: the route declares `401/403/404/422` and never `400`.

## Correction record

- **2026-09-03 — R19/R20 re-decided after the first device run.** The planting-run convention
  derives its prefix from the *species key*, and the instance's species keys are plain numbers
  (`8271634`): on the Pixel 7a the identifier field stayed empty after every species choice.
  Looking for the rule the web UI applies instead — the observation the artefact had recorded
  as "not made" — found `generateInstanceId` in `src/frontend/src/utils/idGenerator.ts`, and
  every plant on the operator's instance carries its shape (`MONST-0713-WG7`). The operator
  decided that app and web UI follow one rule; R19 now cites the web UI's, R20's location
  segment is gone, and R21 keeps the app's one addition, the step past a taken identifier.
  The planting-run reading is kept here because it is the only rule the *backend* has, and
  the next reader will find it first, as this one did.

- **2026-08-22 — the species gap was re-decided after the first teach-back.** The interview
  first settled on refusing creation and pointing at the web UI, on the stated grounds that
  `REQ-048` makes client-side species creation a duplicate risk. The operator rejected that
  at the teach-back. Reading the shipped service rather than the spec draft showed the
  premise was wrong: `create_species` has been idempotent since `REQ-048` Stufe 1, resolving
  by canonical dedup key through an atomic UPSERT, and its own comment names the
  identify→create path as the reason. The risk that motivated the refusal does not exist,
  so R25/R26 record creation without a client-side pre-match. The rejected reading is kept
  here rather than deleted, because the same wrong premise is easy to re-derive from the
  spec draft alone.

- **2026-08-28 — five schema claims corrected after a pre-merge review.** The artefact was
  written from the pinned schema, but four of its citations did not survive being checked
  against it a second time, and one requirement contradicted another:
  - **R25** named `family` and `gbif_id` as fields to populate on `POST /api/v1/species`.
    `SpeciesCreate` declares neither — it carries `family_key`, and no GBIF field at all.
    Implemented literally, both would have been rejected or dropped. The requirement now says
    which three fields survive and names the lost taxonomic linkage as a consequence.
  - **R26** said the create is idempotent and therefore needs no client-side check. That
    holds for the normalized scientific name, and it is *not* the whole story: the route
    declares a `409` for other unique-constraint collisions, which R33 had no state for.
  - **R27** cited a `UserResponse` schema. There is none; `/api/v1/users/me` answers with
    `UserProfileResponse`. The same requirement's `403` is undeclared on the species route,
    which was worth saying explicitly rather than leaving to be discovered.
  - **R33** listed `400` among the image-refusal statuses. `/identify` declares
    `401/403/404/422` and never `400`.
  - **R1** read as an unconditional "offer exactly two routes" while R3 forbids offering the
    identification route when the recognizer is unavailable. An implementer following R1 as
    written would ship the failure R3 exists to prevent.

  None of these was found by the elicitation or by writing the artefact; all five came out of
  reading the schema against the prose once more. The lesson is the same one the feature audit
  learned on the same day: a citation is a claim, and a claim that was true when written is
  not thereby true now.
