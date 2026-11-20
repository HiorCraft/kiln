# Phase 3 – Custom Entities – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Eigene Entity-Arten mit Bedrock-Bone-Modell, Animationen (Zustand idle/walk automatisch, Server-Animationen),
eigener Hitbox und vollständig eigener KI (Ziele, Zustände, zeitgesteuerte Abläufe); Demo „Golem“.

**Architecture:**
- Server: NMS-Unterklasse `KilnMob` von `Zombie` (Netzwerktyp `minecraft:zombie`, keine zusätzlichen synchronisierten
  Daten; Paper baut den Bukkit-Wrapper nach Entity-Typ, deshalb Zombie statt `PathfinderMob`). Vanilla-Ziele leer,
  Sonne/Wasser/Verstärkung aus, Maße aus der Definition. KI über Kiln-`Goal`-Adapter auf dem NMS-`GoalSelector`.
  Persistenz wie bei Möbeln: Chunk-PDC `kiln:entities`, Mobs sind nicht persistent.
- Protokoll v8: `EntityAppearance(entityId, model, width, height, headBone)` und `PlayAnimation(entityId, name, mode,
  speed)` / `StopAnimation(entityId, name?)`; `EntityTraitsRemove` entfernt alle Merkmale eines Entities.
- Client: Bedrock-Geo/Animation-Parser (rein, getestet), eigener Renderer (`submitCustomGeometry`) für markierte
  Entities per Mixin auf `EntityRenderDispatcher.getRenderer`, Hitbox per Mixin auf die Dimensionen.

**Spec:** `docs/superpowers/specs/2026-11-20-kiln-content-design.md`, Abschnitt „3. Custom Entities“.

## Global Constraints
- `PROTOCOL_VERSION = 8`. Animationsnamen `[a-z0-9_.]{1,64}`, Größe 0.05..16, Speed 0..8.
- Modelle: `assets/<ns>/kiln/entity/<name>.geo.json`, `…/<name>.animation.json`, Textur `assets/<ns>/textures/entity/<name>.png`.
- Limits: ≤ 256 Bones, ≤ 4096 Cubes, ≤ 128 Animationen je Modell.
- Bewegung v1: nur Boden (`ground`); Fliegen später (Ruling).

## Review Focus
- Entity verlässt/betritt Sichtweite → Modell und laufende Loop-Animation sind wieder da (Tracking sendet Zustand).
- Chunk-Unload/Server-Stopp → Golem kommt mit Lebenspunkten und Zustand zurück, keine Duplikate.
- Fehler in Plugin-Lambdas → geloggt, Ziel für diese Entity aus, Server läuft weiter (Test Sequenz-Engine).
- Modell fehlt/ungültig auf dem Client → Vanilla-Renderer statt Absturz.
- Bedrock-Rotation und -Spiegelung korrekt (Test mit bekanntem Punkt).

### Task 1: Protokoll v8 + reine Logik
- Messages + Codec + Tests; `GeoModel`/`GeoAnimation` Parser + Sampler (linear, catmullrom, step) mit Tests im Client;
  `BehaviorSequence` (Schritte wait/run/animate) mit Tests im Server.
### Task 2: Client-Renderer
- `KilnEntityAppearance` (Zustand je Entity-Id), Modell-Cache aus dem ResourceManager (geleert bei Pack-Wechsel),
  `KilnModelRenderer` + State, Mixins: Renderer-Auswahl (Entity und State), Kontext erfassen, Dimensionen.
### Task 3: Server
- API `CustomEntities` (DSL), `KilnMob`, `CustomEntityManager` (Spawn, KI, Animationen, Tracking, Persistenz).
### Task 4: Demo Golem + Doku + Prüfung im Spiel
