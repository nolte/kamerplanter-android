---
id: F-12
title: Add a plant, by photo or by hand
status: ready
roadmap_item: R-8
sprint: null
created: 2026-09-02
ended: null
verifies_sprint_value: null
consistency_check: []
---

## Description

Every plant in the app had to be created in the web UI first: the Plants tab could list and
open a plant, never add one. This feature gives the app the one write it was missing, in two
routes that end in the same form. By hand, the user searches the instance's species catalogue
and fills the form in. By photo, the instance identifies the species and the form arrives
pre-filled — filled in, never created: nothing is written until the user confirms, however
sure the recogniser was. The photo taken along the way can be kept as the plant's cover
picture, on either route, and only when the user says so.

Decomposed directly from the confirmed requirement set in
`project/requirements/plant-capture.md` (R1–R35) and the acceptance criteria of issue #50,
which are cited by number below where they differ in wording.

## Acceptance criteria

- [x] **acceptance-1** The Plants tab offers adding a plant while connected, and does not offer it while disconnected.
- [x] **acceptance-2** The manual route reaches the form whenever the app is connected, regardless of the recogniser's state; the identification route is offered only when the instance reports it available.
- [x] **acceptance-3** No image is captured for identification before the `plant_identification` consent exists; the flow shows the instance's own consent wording and grants it on request.
- [x] **acceptance-4** The image sent for identification is normalised to the recognition profile and carries no EXIF; the photo kept with the plant is normalised to the gallery profile from the same capture and is never the recognition-sized bytes.
- [x] **acceptance-5** Suggestions are shown in rank order with confidence, common and scientific name; the user selects one explicitly, and nothing is created before the form is confirmed, `auto_accept` or not.
- [x] **acceptance-6** Not a plant, nothing recognised, and only weak candidates are three visibly different outcomes, and each offers to re-run the identification with a chosen organ on the image already captured.
- [x] **acceptance-7** The species field searches the loaded catalogue by common and scientific name, shows the chosen species' scientific name, and reports "nothing matches" differently from "the catalogue is empty".
- [x] **acceptance-8** `instance_id` and `planted_on` are pre-filled with an editable proposal, and creation is refused with a field-level message when either is cleared or the identifier is already in use.
- [x] **acceptance-9** A location is selectable only after a site is chosen, and the sites come from the instance's site list.
- [x] **acceptance-10** A successful creation lands on the created plant's page, and the list shows it after its reload.
- [x] **acceptance-11** A suggestion the catalogue does not carry is created as a species from the identification result, without a duplicate check of the app's own, and the plant is created against the returned key; a refusal by role does not offer a retry.
- [x] **acceptance-12** With keeping on, the photo is uploaded to the created plant as its cover after the plant exists; with keeping off, no upload is issued.
- [x] **acceptance-13** A failed photo upload or cover call never rolls back or repeats the creation: the plant is reported as created, the photo as not saved, and the way on is the plant's page.
- [x] **acceptance-14** The identification is linked to the created plant as a best-effort step whose failure is not reported as a failed creation.
- [x] **acceptance-15** Every outcome — not connected, refused credential, role, rejected fields, unreachable — is its own state with its own action, and a role does not offer a retry.

## Test hooks

- **acceptance-1** — `PlantsScreen` offers the action on `Content` and `Empty` only — **met 2026-09-02** — screen-level, established by reading `PlantsContent`; the disconnected, failed and loading states carry no action
- **acceptance-2** — the manual route is the form itself (`PlantCaptureScreen`), reachable from the Plants tab whenever it renders; the identification gate is `NetworkPlantCaptureClientTest` `a recogniser that is not available is not offered, and no consent is read` and `an instance without the route is not offered`, and its surface `PlantCaptureViewModelTest` `the identification route is offered only where the recogniser is, and never gates the form` plus `PlantCaptureFormTest.theIdentificationRouteIsOfferedOnlyWhereTheRecogniserIs` — **met 2026-09-02**
- **acceptance-3** — `NetworkPlantCaptureClientTest` `an ungranted consent comes back with the instance's own wording`, `granting the consent names the identification purpose`; `PlantCaptureViewModelTest` `a missing consent is asked for before any image, in the instance's words, and granted from the flow` (an image offered before the grant is refused), `a consent the instance would not record stays a prompt, and a refused credential leaves the form`; `PlantCaptureFormTest.theConsentIsAskedInTheInstancesOwnWordsBeforeTheCamera` — **met 2026-09-02**
- **acceptance-4** — `NormalizationProfile` (`core/camera`) carries the two profiles; `CapturedImageBytesTest` (instrumented) cuts one EXIF-bearing landscape frame twice and asserts on the bytes: each cut inside its own long edge, upright, no GPS, no orientation tag, no APP1 segment, and the two cuts differ; `PlantCaptureViewModelTest` `the image is previewed at the recognition cut and sent as it, with organ auto and the app's language`, `choosing a candidate records the choice, fills the form, and cuts the photo at the gallery profile` pin which profile each upload is asked for — **met 2026-09-02**
- **acceptance-5** — `NetworkPlantCaptureClientTest` `an identification carries its ranked candidates and the organ asked for`, `selecting and linking address the request by key`; `PlantCaptureViewModelTest` `choosing a candidate records the choice, fills the form, and cuts the photo at the gallery profile` (an `auto_accept` candidate fills the form and creates nothing); `PlantCaptureFormTest.candidatesShowNamesAndConfidenceAndTheBestMatchIsOnlyLabelled` — **met 2026-09-02**
- **acceptance-6** — `PlantCaptureViewModelTest` `not a plant, nothing recognised and only weak candidates are three different answers`, `an organ hint re-runs the identification on the same bytes`; `PlantCaptureFormTest.theThreeWeakAnswersReadDifferentlyAndEachOffersTheOrganHint` — **met 2026-09-02**
- **acceptance-7** — `PlantCaptureViewModelTest` `the species search matches common and scientific names and tells a miss from an empty catalogue`, `choosing a species shows its scientific name and typing again clears the choice` — **met 2026-09-02**
- **acceptance-8** — `InstanceIdProposalTest` (four cases over the convention, the taken sequence and the missing location), `PlantCaptureViewModelTest` `the identifier is proposed from species and location and skips what is taken`, `an identifier the user edited is never overwritten by a proposal`, `a submission without species, identifier or date is refused field by field`, `a taken identifier is refused before anything is sent` — **met 2026-09-02**
- **acceptance-9** — `PlantCaptureViewModelTest` `choosing a site loads its locations and a new site drops the old location`; `NetworkPlantCaptureClientTest` `locations are asked for under their site` — **met 2026-09-02**
- **acceptance-10** — `PlantCaptureScreen` navigates on `Created` with the photo saved; `NetworkPlantCaptureClientTest` `creating a plant sends the form's fields and nothing else, then announces the change` pins the list's reload signal — **met 2026-09-02**
- **acceptance-11** — `NetworkPlantCaptureClientTest` `creating a species sends only what the schema declares`, `a species conflict and a viewer's refusal are named, not retried`, `a rejected species carries the instance's reason`; `PlantCaptureViewModelTest` `a candidate the catalogue lacks becomes a species to create, and the plant is created against its key`, `a role that may not create species is a refusal without retry, a conflict keeps the form`, `a species created before the plant was refused is not created twice`, `a species the instance rejects is named in its words, and no plant is created`; `PlantCaptureFormTest.aSpeciesTheCatalogueLacksIsShownAsAboutToBeCreated` — **met 2026-09-02**
- **acceptance-12** — `PlantCaptureViewModelTest` `creating sends the form's fields, keeps the photo as the cover, and lands on the plant`, `with keeping turned off no photo is uploaded`; `NetworkDiaryWriteTest` `a cover photo is uploaded first and then named the cover` — **met 2026-09-02**
- **acceptance-13** — `PlantCaptureViewModelTest` `a failed photo never rolls the plant back and is reported as its own outcome`; `NetworkDiaryWriteTest` `a failed cover call after a stored photo is done, not retried` (the photo is on the instance; only the explicit flag was missed, and that is a log line) — **met 2026-09-02**
- **acceptance-14** — `NetworkPlantCaptureClientTest` `selecting and linking address the request by key`; `PlantCaptureViewModelTest` `the identification is linked to the plant best effort, and never on the manual route` (a failed link still lands on the created plant) — **met 2026-09-02**
- **acceptance-15** — `PlantCaptureViewModelTest` `a refused credential and no connection are their own states`, `a rejected plant names the instance's reason and keeps the form`, `a role and a refused credential are their own outcomes`, and for the identification route `the recogniser's refusals are their own steps` (refused image, rate limit, unreachable, role without retry, a consent revoked meanwhile, a refused credential) — **met 2026-09-02**

## Consistency notes

Decomposed by hand rather than by `feature-decompose`, which is not available in this
repository's toolset; the criteria are the issue's 26, folded where two of them assert one
behaviour from two sides (the two photo-profile criteria, the two consent criteria) so that
each remains atomic. Criterion 25 of the issue (the four generated tags) and 26 (no generated
type in the feature module) are build-level constraints — the first is pinned by
`checkApiClientUpToDate`, the second by review — and carry no criterion here for the same
reason the other features leave R-numbered constraints without one.
