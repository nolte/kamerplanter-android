# Roadmap

The prioritised queue governed by `spec/project/roadmap/`: highest priority at the top.
Items are added and retargeted by the `roadmap-plan` skill, never by hand; the
detail-level invariant is enforced by `roadmap-refine`. Every item carries its own inline
YAML block and cites at least one outcome from [`goals.md`](goals.md).

Item IDs are `R-<n>`, assigned monotonically and **never reused** across the project's
lifetime, even after an item is deleted or cancelled.

The queue is deliberately flat: reading order alone expresses priority, so there is no
phase grouping that could drift out of step with it.

Every item carries `mvp: false` while `project/mission.md` does not exist, per the roadmap
spec's uniform-stance rule. Once the mission is authored, `roadmap-plan` sets the flags;
R-1 and R-2 are the current candidates.

<!--
Recorded operator override, 2026-08-13, per spec/project/requirements-elicitation/
§Consumer contract: only R-1 rests on a confirmed requirement artefact
(project/requirements/backend-connection.md, U_gate 0.85). R-2 through R-7 are queued
against their GitHub issues, each of which already carries a verified backend endpoint
surface, but without an elicited artefact. The override is bounded by a rule rather than
open-ended: no item is promoted to `detail: fine` before `requirements-elicit` has run for
it. `fine` means near-term, and near-term means understood — which is why every item below
R-1 sits at `coarse` or `backlog`.
-->

### R-1 — Connect the app to a self-hosted kamerplanter instance

```yaml
id: R-1
title: Connect the app to a self-hosted kamerplanter instance
detail: fine
outcomes: [O-1, O-5]
target_sprint: null
mvp: false
status: proposed
```

A plant owner reaches their own instance in whichever way fits the moment — scanning the
pairing QR code shown in the web UI, pasting an API key, or pointing the app at a
credential-free light-mode instance. Nothing is stored before it has been verified against
the instance, the secret is held under an Android Keystore key rather than in clear text,
and the connection can be changed or removed from Settings at any time. This item also
delivers the generated OpenAPI client that every later network-bearing item consumes, which
is why it sits at the top of the queue.

- [ ] Generated API client from a pinned schema with recorded provenance
- [ ] Connection model and its state machine
- [ ] Keystore-backed credential storage
- [ ] QR pairing path
- [ ] API key path
- [ ] Light-mode path
- [ ] Verification before persist, plus tenant resolution
- [ ] Session lifecycle: refresh, rotation, 401 teardown
- [ ] Credential seam and request interceptor in `core/network/`
- [ ] Settings surface: connection state, change, disconnect
- [ ] Transport security policy and version-incompatibility diagnostics

### R-2 — Identify a pest from a microscope or phone photo

```yaml
id: R-2
title: Identify a pest from a microscope or phone photo
detail: coarse
outcomes: [O-2]
target_sprint: null
mvp: false
status: proposed
```

The user picks an image source — the phone camera for a damage pattern, the USB microscope
for the animal itself — and their instance returns its identification, rendered with marked
regions, confidence and the beneficial-insect warning. This closes the open upload half of
issue #1, whose own body defers to "the existing upload / identification flow" that this
item is.

### R-3 — Ship builds that run without system warnings

```yaml
id: R-3
title: Ship builds that run without system warnings
detail: coarse
outcomes: [O-6]
target_sprint: null
mvp: false
status: proposed
```

The UVC native libraries are aligned for 4 KB memory pages, so Android 15+ shows a
page-size warning over the app and a Play release would be rejected. Independent of every
other item in the queue.

### R-4 — See your plants as a filterable list

```yaml
id: R-4
title: See your plants as a filterable list
detail: coarse
outcomes: [O-3]
target_sprint: null
mvp: false
status: proposed
```

Each plant appears with a photo, its name, species and location, and the ones with open
care actions are visibly flagged so the list answers "what needs me today" at a glance.

### R-5 — Open one plant for its full picture

```yaml
id: R-5
title: Open one plant for its full picture
detail: coarse
outcomes: [O-3]
target_sprint: null
mvp: false
status: proposed
```

Master data, growth phase, open care actions, photos and recent entries gathered in one
screen, which is also where a pest check gets bound to that particular plant.

### R-6 — Record what you saw, with photographs

```yaml
id: R-6
title: Record what you saw, with photographs
detail: backlog
outcomes: [O-4]
target_sprint: null
mvp: false
status: proposed
```

Diary entries carrying photos from either camera, still readable and editable weeks later.

### R-7 — Open the app straight from an instance link

```yaml
id: R-7
title: Open the app straight from an instance link
detail: coarse
outcomes: [O-1]
target_sprint: null
mvp: false
status: proposed
```

Scanning an instance's `/connect` link opens the app with the server address already filled
in, instead of dead-ending in a browser.
