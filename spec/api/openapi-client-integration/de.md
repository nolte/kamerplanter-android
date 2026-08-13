# OpenAPI-basierte Anbindung an die Backend-API

Status: draft
Portfolio-Scope: local

## Context
<!-- Why does this spec exist? What problem, user need, or constraint drives it? -->

kamerplanter-android ist ein vollwertiger Mobile-Client für das self-hosted Backend
[nolte/kamerplanter](https://github.com/nolte/kamerplanter) (FastAPI). Gemäß
[ADR 0001](../../../docs/de/adrs/0001-tech-stack.md) liegt der API-Client in
`core/network/`, wird aus dem OpenAPI-Schema des Backends via `openapi-generator`
(kotlin, `jvm-retrofit2`) **generiert** und läuft auf Retrofit + OkHttp +
kotlinx.serialization. Es gibt keine handgepflegten DTOs.

Das Backend legt die konkrete Schnittstelle fest, gegen die diese Spec integriert:

- **Pfad-basierte Major-Versionierung:** Routen liegen unter `/api/v1/…`; das
  OpenAPI-Dokument wird unter `/api/v1/openapi.json` ausgeliefert.
- **Zwei unabhängige Versions-Achsen:** die URL-Major (`/api/vN`) ist *nicht* die
  Backend-Anwendungsversion. Die Anwendungsversion ist ein SemVer-String aus
  `settings.app_version`, ausgewiesen sowohl als OpenAPI `info.version` als auch als
  `/api/health.version` — beide laufen also gleich, weil sie dieselbe Einstellung lesen.
  Der **Release-Tag läuft mit keiner von beiden gleich**: das veröffentlichte
  `v0.1.0`-Release-Asset weist `info.version` `1.0.0` aus. Tag, Anwendungsversion und
  API-Pfad sind drei getrennte Strings, und insbesondere der Tag darf nie als
  Anwendungsversion gelesen werden.
- **Health-Endpoint:** `GET /api/health` (Root-Ebene, dokumentiert „for M2M consumers")
  liefert `{ "status": "healthy", "version": <app_version>, "mode": <Deployment-Modus> }`.
  `mode` unterscheidet eine vollständige Instanz von einer `light`-Instanz, die keine
  Konten kennt und die `/api/v1/auth/…`-Routen mit `404` beantwortet.
- **Multi-Tenancy:** tenant-scoped Routen haben die Form `/api/v1/t/{tenant_slug}/…`.
- **Schema-Verteilung:** das Backend veröffentlicht `openapi.json` als **GitHub-Release-
  Asset** getaggter Releases (z. B. Release `v0.1.0`), und GitHub weist dessen `sha256`
  aus. Die Arbeitskopie `openapi.json` bleibt im Backend gitignored (ein Build-Artefakt aus
  `task openapi:export`); das Release-Asset ist die dauerhafte, adressierbare Kopie.

Weil das Backend **self-hosted** ist, driften App und Server in *beide* Richtungen: ein
Server kann neuer sein als die installierte App (liefert Felder/Endpoints, die der
generierte Client nicht kennt) oder älter (die App erwartet Dinge, die ein älterer Server
nicht anbietet). Grundlegende Abwärtskompatibilität über diesen Drift hinweg ist die
treibende Anforderung dieser Spec.

## Goals
<!-- What this spec aims to achieve. Bullet points, outcome-oriented. -->
- Reproduzierbare, deterministische Client-Generierung aus einem **gepinnten, vendored**
  OpenAPI-Dokument, bezogen aus einem getaggten Backend-Release.
- Ein klar getrenntes Zwei-Achsen-Versionsmodell: API-Major (`/api/vN`-Pfad) vs.
  Backend-Anwendungsversion (SemVer, `info.version` / `/api/health.version`).
- Tolerant-Reader-Deserialisierung, sodass ein unerwartet neuerer Server die App nie zum
  Absturz bringt.
- Laufzeit-Aushandlung der API-Major-Version: die höchste Major nutzen, die Client und
  Server gemeinsam unterstützen.
- Graceful Degradation plus klare, lokalisierte Diagnose, wenn die Serverversion
  inkompatibel oder nicht erreichbar ist — nie ein harter Crash.
- Die Isolationsregeln aus ADR 0001 unangetastet lassen: Networking bleibt in
  `core/network/`; UVC leakt nie hinein.

## Non-Goals
<!-- Explicitly out of scope. Prevents creep. -->
- Handgepflegte DTOs oder ein handgeschriebener HTTP-Client.
- Offline-Caching- / Sync-Semantik (Room) — ein separates Thema, falls es je ein Feature
  wird.
- Das vollständige Design des OIDC-Authentifizierungsflows — eigene Spec; hier nur so weit
  behandelt, wie Versionskompatibilität es berührt.
- Server-seitiges API-Design, Deprecation-Policy oder die Gestalt eines künftigen
  `/api/v2` — das gehört ins Backend-Repository.
- Die genaue Endpoint-zu-Feature-Zuordnung der App — diese Spec regelt, *wie* der Client an
  die API bindet, nicht *welche* Features sie konsumieren.

## Requirements
<!-- Use RFC 2119 keywords: MUST, SHOULD, MAY. One atomic requirement per bullet. -->

### Client-Generierung & Schema-Pinning
- **R-GEN-1 — MUSS [MUST]** den API-Client aus einem versionierten, eingecheckten
  („vendored") OpenAPI-Dokument generieren, niemals durch Abruf eines Live-Endpoints zur
  Build-Zeit.
- **R-GEN-2 — MUSS [MUST]** das vendored Dokument aus einem **getaggten
  Backend-GitHub-Release-Asset** (`openapi.json`) beziehen und die Provenance festhalten:
  den Backend-Release-Tag und den `sha256` des Releases.
- **R-GEN-3 — MUSS [MUST]** das vendored Dokument in CI gegen seinen festgehaltenen
  `sha256` verifizieren, sodass ein korruptes oder still ausgetauschtes Schema den Build
  scheitern lässt.
- **R-GEN-4 — MUSS [MUST]** die Generierung über einen reproduzierbaren Gradle-Task
  ausführen (`openapi-generator`, kotlin, `jvm-retrofit2`), dessen Ausgabe in
  `core/network/` landet; zweimaliges Ausführen auf derselben Eingabe MUSS identische
  Ausgabe erzeugen.
- **R-GEN-5 — DARF NICHT [MUST NOT]** den generierten Client oder irgendeinen
  Networking-Typ außerhalb von `core/network/` leaken lassen; Feature-Module konsumieren die
  API ausschließlich über `core/network/`-eigene Interfaces (ADR-0001-Isolation, analog zur
  UVC-Regel).
- **R-GEN-6 — SOLLTE [SHOULD]** jedes Schema-Update als einen einzigen reviewbaren Commit
  behandeln, der das vendored Dokument, seinen Provenance-Tag und seinen `sha256` gemeinsam
  anhebt, sodass der DTO-Diff im Review sichtbar ist.
- **R-GEN-7 — SOLLTE [SHOULD]** CI scheitern lassen, wenn der eingecheckte generierte
  Client nicht mehr zum gepinnten Schema passt (Regenerieren + Diff muss leer sein), um
  Schema/Code-Drift zu verhindern.

### Zwei-Achsen-Versionsmodell
- **R-VER-1 — MUSS [MUST]** die API-Major-Version (URL-Segment `/api/vN`) und die
  Backend-Anwendungsversion (SemVer aus `info.version` / `/api/health.version`) als zwei
  getrennte Achsen modellieren; der Client DARF NICHT [MUST NOT] die eine aus der anderen
  ableiten.
- **R-VER-2 — MUSS [MUST]** in client-eigener Konfiguration die geordnete Menge der vom
  Client unterstützten API-Majors und eine minimal unterstützte Backend-Anwendungsversion
  (`MIN_SUPPORTED`) deklarieren. `MIN_SUPPORTED` ist eine Untergrenze für die
  **Anwendungsversion** (`info.version` / `/api/health.version`), nie für den Release-Tag —
  beide laufen auseinander, eine aus dem Tag abgelesene Untergrenze würde also gegen die
  falsche Zahl prüfen. Die anfängliche Untergrenze ist `0.1.0` (SemVer) und wandert mit dem
  Backend mit.

### Abwärtskompatibilität (Tolerant Reader)
- **R-COMPAT-1 — MUSS [MUST]** JSON so deserialisieren, dass unbekannte Felder ignoriert
  (`ignoreUnknownKeys`) und Eingabewerte auf deklarierte Defaults gecoerct werden
  (`coerceInputValues`) — bereits in `core/network` `NetworkModule.provideJson`
  konfiguriert; diese Spec macht es verbindlich.
- **R-COMPAT-2 — MUSS [MUST]** neu hinzugefügte Response-Felder als optional/nullable mit
  Defaults modellieren, sodass ein älterer Server, der sie weglässt, ohne Fehler
  deserialisiert.
- **R-COMPAT-3 — DARF NICHT [MUST NOT]** das Vorhandensein eines Feldes oder Endpoints
  annehmen, das von einer neueren Serverversion eingeführt wurde, ohne zuvor dessen
  Verfügbarkeit festzustellen (Feature-Detection statt Annahme).
- **R-COMPAT-4 — SOLLTE [SHOULD]** einen fehlenden optionalen Endpoint auf einem älteren
  Server (`404`/`501`) als „Feature nicht verfügbar" behandeln, nicht als harten Fehler.

### Laufzeit-Aushandlung der Major-Version
- **R-NEG-1 — MUSS [MUST]** beim Verbinden mit einem Server ermitteln, welche API-Majors
  der Server anbietet, und die höchste von Client und Server gemeinsam unterstützte Major
  wählen (highest common major).
- **R-NEG-2 — MUSS [MUST]** die ausgehandelte Major als Pfad-Präfix (`/api/vN/…`,
  inklusive tenant-scoped `/api/vN/t/{tenant_slug}/…`) für jeden versionierten Request
  dieser Session verwenden; das root-level, versionsunabhängige `/api/health` ist vom
  Präfix ausgenommen.
- **R-NEG-3 — SOLLTE [SHOULD]** Server-Majors durch Probing der Kandidaten-Majors vom
  höchsten client-bekannten Major abwärts entdecken (z. B. `GET`/`HEAD`
  `/api/v{n}/openapi.json`), bis einer antwortet, da das Backend derzeit keinen dedizierten
  „supported majors"-Index anbietet (siehe Open Questions).
- **R-NEG-4 — SOLLTE [SHOULD]** das Aushandlungsergebnis pro Server-Basis-URL cachen und
  bei Verbindungsfehler oder auf eine explizite Nutzeraktion hin neu bewerten.
- **R-NEG-5 — KANN [MAY]** den Nutzer aus den Einstellungen ein Re-Discovery erzwingen
  lassen.

### Health-Gate & Graceful Degradation
- **R-HEALTH-1 — MUSS [MUST]** `GET /api/health` abfragen, bevor Features genutzt werden,
  und `status` sowie `version` auslesen.
- **R-HEALTH-2 — MUSS [MUST]** `version` mit `MIN_SUPPORTED` nach SemVer-Präzedenz
  vergleichen (nie lexikalischer String-Vergleich) und ein optionales führendes `v`
  normalisieren; bei `version` < `MIN_SUPPORTED` eine sichtbare, lokalisierte Warnung
  zeigen und in einem reduzierten Modus fortfahren (nur mit dieser Serverversion
  kompatible Features), statt hart zu scheitern.
- **R-HEALTH-3 — MUSS [MUST]** einen klaren, lokalisierten Fehler (kein Crash) anzeigen,
  wenn der Server nicht erreichbar ist oder einen nicht-gesunden `status` meldet.
- **R-HEALTH-4 — SOLLTE [SHOULD]** im reduzierten Modus genau die Features deaktivieren,
  die die fehlende Serverversion oder Major benötigen, statt die App global zu blockieren.

## Acceptance Criteria
<!-- Testable, checkable conditions. A reviewer should be able to mark each as done/not done. -->
- [ ] Ein Gradle-Task generiert den Client deterministisch aus dem vendored
      `openapi.json`; zweimaliges Ausführen liefert byte-identische Ausgabe. (R-GEN-1,
      R-GEN-4)
- [ ] Das vendored Schema trägt Provenance (Backend-Release-Tag) und einen `sha256`, den
      CI verifiziert; ein manipuliertes Schema lässt den Build scheitern. (R-GEN-2, R-GEN-3)
- [ ] Ein CI-Check scheitert, wenn der eingecheckte generierte Client nicht zum gepinnten
      Schema passt. (R-GEN-7)
- [ ] `core/network/` exponiert keine UVC/`libuvc`-Symbole, und Feature-Module referenzieren
      die API nur über `core/network/`-Interfaces. (R-GEN-5)
- [ ] Die Deserialisierung eines Response mit zusätzlichen unbekannten Feldern (neuerer
      Server) wirft nicht und liefert die bekannten Felder korrekt. (R-COMPAT-1)
- [ ] Die Deserialisierung eines Response ohne neu hinzugefügte optionale Felder (älterer
      Server) liefert Defaults/nulls ohne Fehler. (R-COMPAT-2)
- [ ] Gegen einen Server, der nur `/api/v1` anbietet, wählt der Client v1, obwohl er auch
      v2 kennt; gegen einen Server mit v1 und v2 wählt er v2. (R-NEG-1, R-NEG-3)
- [ ] Alle Session-Requests nutzen die ausgehandelte Major als Pfad-Präfix, inklusive
      tenant-scoped Routen. (R-NEG-2)
- [ ] Bei `/api/health.version` < `MIN_SUPPORTED` erscheint eine lokalisierte Warnung und
      die App bleibt im reduzierten Modus bedienbar (kein Hard-Fail, kein Crash). (R-HEALTH-2,
      R-HEALTH-4)
- [ ] Bei nicht erreichbarem Server erscheint eine lokalisierte Fehlermeldung statt eines
      Crashs. (R-HEALTH-3)
- [ ] Der Client fragt `/api/health` ab und liest `status` + `version`, bevor ein Feature
      genutzt wird. (R-HEALTH-1)
- [ ] Der Client deklariert eine geordnete Menge unterstützter API-Majors und eine
      `MIN_SUPPORTED`-Untergrenze und leitet keine der beiden Versions-Achsen aus der
      anderen ab. (R-VER-1, R-VER-2)
- [ ] Das Gate `version` vs. `MIN_SUPPORTED` nutzt SemVer-Präzedenz mit Normalisierung
      eines optionalen `v`: `0.10.0` rangiert über `0.9.0`, nicht darunter. (R-HEALTH-2)
- [ ] Ein von einem neueren Server eingeführtes Feld oder Endpoint wird erst nach
      Feststellung seiner Verfügbarkeit genutzt (Feature-Detection), durch einen Test
      abgesichert. (R-COMPAT-3)

## Open Questions
<!-- Unresolved decisions, known unknowns, things that need a stakeholder answer. -->
- **Major-Discovery-Mechanismus:** das Backend bietet heute keinen expliziten „supported
  majors"-Index; das Probing von `/api/v{n}/openapi.json` ist ein Workaround. Soll das
  Backend einen Discovery-/Capabilities-Endpoint erhalten (oder ein Feld `supported_majors`
  zu `/api/health` hinzufügen)? Nachverfolgt als
  [nolte/kamerplanter#1124](https://github.com/nolte/kamerplanter/issues/1124).
- **Provenance-Kodierung:** wie werden Release-Tag + `sha256` technisch gepinnt — eine
  Provenance-Geschwisterdatei, ein Header-Kommentar oder eine Gradle-Property, die der
  Verify-Task konsumiert? Zu beachten: der Tag allein identifiziert die Anwendungsversion
  nicht (Tag `v0.1.0` liefert `info.version` `1.0.0`), eine Provenance, die nur den Tag
  festhält, lässt die Versions-Achse also unprotokolliert.
- **Sollte die `MIN_SUPPORTED`-Untergrenze neu angesetzt werden?** Die Untergrenze ist
  `0.1.0`, während das aktuelle Release bereits `1.0.0` meldet — das Gate ist derzeit also
  um eine volle Major zu großzügig. Ob sie angehoben wird, ist eine
  Kompatibilitäts-Policy-Entscheidung und keine Faktenkorrektur und bleibt hier bewusst
  offen.
- **Reicht `/api/health` für das Gate?** Es liefert `status` + App-`version`, aber nicht
  die Menge der API-Majors; die Aushandlung stützt sich derzeit auf Probing. Zu
  entscheiden, ob die Health-Payload erweitert werden soll (koppelt an
  [nolte/kamerplanter#1124](https://github.com/nolte/kamerplanter/issues/1124)).
