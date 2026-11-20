# Phase 2 – Creative-Tabs und Möbel – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Server-definierte Creative-Tabs (eigene + Einsortieren in Vanilla-Tabs) und Möbel mit mehreren (festen) Hitboxen, platzierbar per Item, persistent im Chunk.

**Architecture:** Protokoll v7 mit `CreativeTabs`, `EntityTraits`, `EntityTraitsRemove`. Client: 8 Platzhalter-Tabs + Fabric-Event für Vanilla-Tabs; Mixin auf `Entity.canBeCollidedWith` für als fest markierte Interaction-Entities. Server: Möbel im Chunk-PDC, nicht-persistente `ItemDisplay` + NMS-Unterklasse `KilnInteraction`.

**Tech Stack:** Kotlin, Paper 26.3 + paperweight (NMS, Mojang-Namen), Fabric API `creativetab.v1`, Mixin.

**Spec:** `docs/superpowers/specs/2026-11-20-kiln-content-design.md` (Abschnitt „2. Möbel“, „Entscheidungen des Builders“ 1–2, „Ergänzung … Creative-Tabs und Umsetzung der Möbel“)

## Global Constraints

- `PROTOCOL_VERSION = 7`.
- Creative-Tabs: ≤ 8 eigene Tabs (`kiln:server_0..7`), ≤ 32 Einträge, ≤ 512 Items je Eintrag; Titel als Component-JSON.
- Möbel-Keys: Namensraum = Extension desselben Plugins, Modell = Item-Definition in dieser Extension (wie Custom Items).
- Hitboxen: achsenparallel, Breite/Höhe 0.05..16, Offsets ±16; Gier auf 90° gerastet, sobald Hitboxen existieren.
- Feste Hitboxen kollidieren für Spieler und Entities (wie Boote); keine Pfadfindungs-Patches.
- Deutsche Spielertexte, englische Commits (Conventional Commits).

## Review Focus

- Chunk wird entladen und wieder geladen → genau ein Satz Teile, keine Duplikate (Test Task 4 + manuell).
- Server-Neustart → Möbel wieder da, Kollision wieder fest (manuell).
- Client betritt Chunk mit Möbeln, nachdem `EntityTraits` vor dem Spawn kam → Kollision fest (Test Task 5).
- Disconnect/Serverwechsel → keine alten Traits/Tabs bleiben (Tests Task 3/5).
- Platzieren in belegten Block oder mit Hitbox, die in Blöcke ragt → abgelehnt (Test Task 4).

---

### Task 1: Protokoll v7
**Files:** `protocol/.../Messages.kt`, `Protocol.kt`, `codec/MessageCodec.kt`; Test `MessageCodecTest.kt`
**Interfaces:**
- `data class CreativeTabData(val id: String, val title: String, val icon: ItemStackData?, val items: List<ItemStackData>, val target: String?)`
- `data class CreativeTabs(val tabs: List<CreativeTabData>) : ClientboundMessage`
- `data class EntityTraits(val entityId: Int, val solid: Boolean) : ClientboundMessage`, `data class EntityTraitsRemove(val entityIds: List<Int>) : ClientboundMessage`
- Limits `MAX_CREATIVE_TABS = 8`, `MAX_CREATIVE_ENTRIES = 32`, `MAX_CREATIVE_ITEMS = 512`.
- [ ] Roundtrip-Tests (inkl. leere Liste, `target` null/gesetzt) + Ablehnung von 33 Einträgen → FAIL → implementieren → PASS → Commit `feat(protocol): bump to v7 with creative tabs and entity traits`.

### Task 2: Creative-Tabs Server
**Files:** `server/api/.../creative/CreativeTabs.kt`, `Kiln.kt` (`val creativeTabs`), `server/runtime/.../creative/CreativeTabRegistry.kt`, `KilnImpl.kt` (senden nach `DeclareKeybinds` und bei Änderung, gebündelt pro Tick); Test `CreativeTabRegistryTest.kt`
**Interfaces:** `interface CreativeTabs { fun register(plugin, key: Key, init: CreativeTabBuilder.() -> Unit): CreativeTab; fun addToVanillaTab(plugin, tab: Key, items: () -> List<ItemStack>): Registration }`; `CreativeTabBuilder { var title: Component; var icon: (() -> ItemStack)?; fun add(vararg items: CustomItem); fun add(stack: () -> ItemStack) }`. Reine Funktion `CreativeTabRegistry.entries(...)` erzeugt die Einträge (Items lazy), Namensraumprüfung wie bei Items, höchstens 8 eigene Tabs → `IllegalStateException`.
- [ ] Tests: `ninthOwnTabIsRejected`, `foreignNamespaceRejected`, `entriesKeepOrderAndTarget`, `pluginDisableRemovesTabs` → FAIL → implementieren → PASS → Commit `feat(api): add server-defined creative tabs`.

### Task 3: Creative-Tabs Client
**Files:** `client/.../creative/KilnCreativeTabs.kt` (Zustand + Registrierung), Mixin `CreativeModeTabsAccessor` (CACHED_PARAMETERS setzen), Mixin `CreativeModeTabMixin` (`getIconItem` für Kiln-Tabs), `KilnClientMod.kt` (8 Tabs beim Start), `KilnClientImpl.kt` (Nachricht, Reset bei Disconnect); Test `KilnCreativeTabsTest.kt`
**Interfaces:** `class CreativeTabState { fun apply(tabs: List<CreativeTabData>); fun slot(index: Int): Slot?; fun vanillaAdditions(tab: String): List<ItemStackData>; fun clear() }` – reiner Zustand (Slots 0..7 der Reihenfolge nach, Rest ignoriert).
- [ ] Tests: `ownTabsFillSlotsInOrder`, `vanillaTargetsAreSeparated`, `clearEmptiesEverything` → FAIL → implementieren → PASS → Commit `feat(client): show server creative tabs`.

### Task 4: Möbel-Kernlogik (rein)
**Files:** `server/runtime/.../furniture/FurnitureGeometry.kt`, `FurnitureStore.kt`; Tests
**Interfaces:** `FurnitureGeometry.snapYaw(yaw: Float, hasHitboxes: Boolean): Float`, `rotate(offset: Vec(x,y,z), yaw): Vec`, `boxes(origin, yaw, hitboxes): List<Box>`; `FurnitureStore.encode(list: List<StoredFurniture>): String` / `decode(String): List<StoredFurniture>` (robust gegen Müll → leere Liste + Fehlerzahl); `StoredFurniture(type: String, x: Double, y: Double, z: Double, yaw: Float, id: UUID)`.
- [ ] Tests: `yawSnapsTo90WithHitboxes`, `offsetsRotateWithYaw`, `storeRoundTrip`, `garbageIsIgnored`, `placementBlockedByBlocks` (Funktion `FurnitureGeometry.blocked(boxes, isSolidAt)`) → FAIL → implementieren → PASS → Commit `feat(server): add furniture geometry and chunk storage`.

### Task 5: Möbel Server + Client
**Files:** `server/api/.../furniture/Furniture.kt` (`FurnitureRegistry`, `FurnitureType`, `Furniture`, Builder: `model`, `item: CustomItem?`, `hitbox(width, height, offset, solid)`, `onInteract`, `onBreak`, `Furniture.seat(player, offset)`), `server/runtime/.../furniture/*` (`KilnInteraction` NMS, `FurnitureManager`: Chunk-Load/-Unload, Platzieren, Abbauen, Tracking → `EntityTraits`), client `entity/KilnEntityTraits.kt` + Mixin `EntityCollisionMixin`; Tests `KilnEntityTraitsTest`.
- [ ] Client-Tests: `traitsBeforeSpawnApply`, `removeAndClear` → FAIL → implementieren → PASS.
- [ ] Server: Build grün; manuelle Prüfung in Task 6.
- [ ] Commit `feat: add furniture with solid multi-part hitboxes`.

### Task 6: Demos, Doku, manuelle Prüfung
**Files:** `tools/gen_content_assets.py` (Stuhl/Tisch-Modelle + Item-Definitionen + Texturen), `ContentDemo.kt` (`/kiln give chair|table`, Creative-Tab „Kiln Inhalte“ mit Rubin, Schwert, Stuhl, Tisch; Rubin zusätzlich in Vanilla-Tab „Zutaten“), README, `docs/lua-extensions.md`.
- [ ] Build grün; manuell: Tab sichtbar, Items nehmbar; Stuhl platzieren, draufstellen, sitzen, abbauen; Chunk entladen/neu laden; Server-Neustart.
- [ ] Commit `feat(example): add furniture and creative tab demos`.
