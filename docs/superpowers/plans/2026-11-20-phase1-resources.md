# Phase 1 – Ressourcen, Items, Enchantments, Sounds, Fonts – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extension-Pakete liefern einen `assets/<id>/`-Baum, den der Client als echtes Resourcepack einhängt; Plugins registrieren Custom Items und Enchantments; Sounds, Fonts und Post-Effekte sind nativ und aus Lua nutzbar.

**Architecture:** Protokoll v6 (Recht `resources`, größere Limits, `SetPostEffect`). Server-Packager und Client-Reader validieren `assets/` gleich (gemeinsame Regeln in `protocol`). Der Client baut nach dem Laden der Lua-Extensions ein In-Memory-Pack, lädt die Ressourcen neu und meldet erst danach `ExtensionsReady(true)`. Items/Enchantments sind reine Paper-API (Datenkomponenten, Bootstrap-Registry).

**Tech Stack:** Kotlin, Paper 26.3 (Data Components, Registry API), Fabric/Mixin, LuaJ.

**Spec:** `docs/superpowers/specs/2026-11-20-kiln-content-design.md` (Abschnitte „1. Ressourcen-Grundlage“, „Entscheidungen des Builders“ 1, 3, 5, „Protokoll v6“, „Demos“)

**Voraussetzung:** Phase 0 abgeschlossen (`docs/superpowers/plans/2026-11-20-phase0-build.md`).

## Global Constraints

- `PROTOCOL_VERSION = 6`.
- Neue Berechtigung `resources`; kanonische Reihenfolge: `widgets, keybinds, hud, channels, toasts, screens, inventory, resources`.
- Erlaubte Endungen unter `assets/<id>/`: `json png mcmeta ogg ttf otf fsh vsh glsl txt`; an der Paketwurzel weiter nur `lua png json`.
- Limits: Zip ≤ 32 MiB, entpackt ≤ 64 MiB, ≤ 4096 Dateien, `.ogg` ≤ 8 MiB je Datei, `.ttf/.otf` ≤ 4 MiB, Texturen ≤ 4096×4096; Chunk 512 KiB.
- Namensraum unter `assets/` == Extension-Id; Verweise auf andere Namensräume erlaubt, außer `kiln:` (verboten in allen JSON-Dateien unter `assets/`).
- Sprachschlüssel in `assets/<id>/lang/*.json` nur mit Präfix `<id>.`.
- Content-Keys (Item, Enchantment) haben als Namensraum die Id einer Extension desselben Plugins.
- Lua: eigene Sounds/Fonts brauchen `resources`; erlaubt: eigener Namensraum und Namensräume der `dependencies`; alte Kurznamen ohne Recht.
- Deutsche Spielertexte, englische Commits (Conventional Commits).

## Review Focus

- Paket mit `assets/minecraft/...` oder `assets/<andere_id>/...` → Server: `InvalidExtensionException` mit Pfad; Client: Ladefehler (Tests in Task 2/3).
- JSON unter `assets/` mit Wert `"kiln:textures/gui/x"` (z. B. Font-Provider) → abgelehnt (Tests in Task 2/3).
- Resource-Reload schlägt fehl oder dauert lange, Spieler bricht ab → `ExtensionsReady(false, Grund)` bzw. Disconnect, Pack wird wieder entfernt (Test in Task 4 über Handshake-Zustandsmaschine).
- Zweiter Join ohne Neustart (anderer Server, andere Assets) → altes Pack ist weg, neues aktiv (manuelle Prüfung Task 4; Unit-Test: `unloadAll` leert Pack-Quelle).
- Reine Ressourcen-Extension ohne `main.lua` → lädt, kein Lua-Kontext, keine Fehler (Tests in Task 2/3).

---

### Task 1: Protokoll v6

**Files:**
- Modify: `protocol/src/main/kotlin/dev/kiln/protocol/Protocol.kt` (Version, Limits), `Types.kt` (`ExtensionPermissions.RESOURCES`, `ALL`), `Messages.kt` (`SetPostEffect`), `codec/MessageCodec.kt`
- Create: `protocol/src/main/kotlin/dev/kiln/protocol/AssetRules.kt`
- Test: `protocol/src/test/kotlin/dev/kiln/protocol/MessageCodecTest.kt`, `AssetRulesTest.kt`

**Interfaces:**
- `ProtocolLimits.MAX_EXTENSION_PACKAGE_BYTES = 32 MiB`, neu `MAX_EXTENSION_UNPACKED_BYTES = 64 MiB`, `MAX_EXTENSION_FILES = 4096`, `MAX_SOUND_BYTES = 8 MiB`, `MAX_FONT_BYTES = 4 MiB`, `MAX_ASSET_TEXTURE_SIZE = 4096`.
- `data class SetPostEffect(val id: String?) : ClientboundMessage` – `id` ist `ResourceId`-Text oder `null` (aus).
- `object AssetRules { fun problem(extensionId: String, path: String, size: Int): String?; fun jsonProblem(path: String, json: String): String?; fun langKeyAllowed(extensionId: String, key: String): Boolean; val EXTENSIONS: Set<String> }` – `problem` prüft Präfix `assets/<extensionId>/`, Endung, Größen je Typ; `jsonProblem` sucht rekursiv String-Werte, die mit `kiln:` beginnen.

- [ ] **Step 1: Failing tests**: `setPostEffectRoundTrip` (mit `"kiln_content:wobble"` und `null`); `assetRulesRejectForeignNamespace` (`assets/minecraft/models/x.json` → nicht null); `assetRulesAllowOwnModel` (`assets/demo/models/item/ruby.json` → null); `assetRulesRejectLargeOgg` (8 MiB + 1); `jsonProblemFindsKilnReference` (`{"providers":[{"file":"kiln:font/x.png"}]}` → nicht null); `jsonProblemAllowsMinecraftParent` (`{"parent":"minecraft:item/generated"}` → null); `permissionsCanonicalOrderEndsWithResources`.
- [ ] **Step 2:** `./gradlew :protocol:test` → FAIL. **Step 3:** Implementieren (Codec: neue Typ-Id ans Ende der Clientbound-Liste). **Step 4:** → PASS.
- [ ] **Step 5:** Commit `feat(protocol): bump to v6 with resources permission, asset rules and SetPostEffect`.

### Task 2: Server-Packager für `assets/` und Ressourcen-Pakete

**Files:**
- Modify: `server/src/main/kotlin/dev/kiln/server/extension/ExtensionPackager.kt`, `ExtensionFiles.kt` (Budgets aus `ProtocolLimits`)
- Test: `server/src/test/kotlin/dev/kiln/server/extension/ExtensionPackagerTest.kt`

**Interfaces:**
- `ExtensionPackage.main: String?` (null = kein Lua). `ExtensionPackage.hasAssets: Boolean`.
- `build()` validiert Pfade unter `assets/` über `AssetRules.problem`, JSON über `AssetRules.jsonProblem`, PNG-Größe ≤ 4096 (Header), Sprachschlüssel über `AssetRules.langKeyAllowed`; fügt `resources` zu `permissions` hinzu, wenn `assets/` existiert.

- [ ] **Step 1: Failing tests**: `resourceOnlyPackageWithoutMain` (nur `extension.json` ohne `main` + `assets/x/models/item/a.json`) baut, `main == null`, `permissions` enthält `resources`; `explicitMainMustExist` bleibt; `rejectsForeignAssetNamespace`; `rejectsKilnReferenceInFont`; `rejectsForeignLangKey` (`assets/x/lang/en_us.json` mit `"gui.done"`); `allowsOggUnderAssetsOnly` (`sounds/a.ogg` an der Wurzel → Fehler, `assets/x/sounds/a.ogg` → ok); `acceptsUpTo4096Files`.
- [ ] **Step 2:** FAIL. **Step 3:** Implementieren; `main` nur noch Default `main.lua`, wenn die Datei existiert. **Step 4:** PASS.
- [ ] **Step 5:** Commit `feat(server): accept asset trees and resource-only extension packages`.

### Task 3: Client-Paketleser und Zustimmungstext

**Files:**
- Modify: `client/src/main/kotlin/dev/kiln/client/ext/ExtensionPackage.kt` (Limits aus `ProtocolLimits`, `main` optional, `assets/`-Regeln), `ConsentModel.kt` (Text für `resources`: „Ressourcen: Modelle, Texturen, Sounds, Schriften“), `lua/LuaExtension.kt` (`load()` ohne `main` = kein Lua-Kontext)
- Test: `client/src/test/kotlin/dev/kiln/client/ext/ExtensionPackageTest.kt`, `ConsentTest.kt`

- [ ] **Step 1: Failing tests**: dieselben Fälle wie Task 2 für `ExtensionPackage.read` (fremder Namensraum, `kiln:`-Verweis, fremder Sprachschlüssel, Ressourcen-Paket ohne `main`); `consentListsResourcesPermission`.
- [ ] **Step 2–4:** FAIL → implementieren → PASS.
- [ ] **Step 5:** Commit `feat(client): read asset trees and show the resources permission`.

### Task 4: Ressourcen als Pack einhängen und neu laden

**Files:**
- Create: `client/src/main/kotlin/dev/kiln/client/resource/KilnPackResources.kt`, `KilnPackSource.kt`
- Create: `client/src/main/java/dev/kiln/client/mixin/PackRepositoryMixin.java` (fügt `KilnPackSource` zu den `RepositorySource`s hinzu)
- Modify: `client/src/main/resources/kiln.mixins.json`, `ext/ExtensionHandshake.kt` (Phase `RELOADING`), `KilnClientImpl.kt` (IO-Methoden), `ext/ExtensionManager.kt` (Assets sammeln, beim `unloadAll` leeren)
- Test: `client/src/test/kotlin/dev/kiln/client/ext/ExtensionHandshakeTest.kt`, `client/src/test/kotlin/dev/kiln/client/resource/KilnPackResourcesTest.kt`

**Interfaces:**
- `class KilnPackResources(files: Map<String, ByteArray>) : PackResources` – `files` sind Pfade relativ zu `assets/` (`<ns>/models/…`); nur Typ `CLIENT_RESOURCES`; `getNamespaces` = Schlüssel der ersten Ebene; `listResources` filtert nach Namespace + Pfadpräfix; `getRootResource("pack.mcmeta")` liefert generiertes Metadata (`pack_format` aus `SharedConstants`), `pack.png` → null.
- `object KilnPackSource : RepositorySource { fun set(files: Map<String, ByteArray>); fun clear(): Boolean /* true, wenn vorher etwas gesetzt war */ }` – meldet ein Pack `kiln:server` (Position TOP, `required = true`, `fixedPosition = true`), nur wenn Dateien gesetzt sind.
- `ExtensionHandshake.Io` neu: `fun reloadResources(done: (error: String?) -> Unit)`; Phase `RELOADING` zwischen „alle Pakete geladen“ und `ExtensionsReady(true)`; übersprungen, wenn kein Paket Assets hat.
- `KilnClientImpl`: `reloadResources` ruft `minecraft.reloadResourcePacks()` und meldet über `thenRun/exceptionally` zurück (auf dem Client-Thread); bei Disconnect `if (KilnPackSource.clear()) minecraft.reloadResourcePacks()`.
- Sprachdateien unter `assets/<id>/lang/` laufen über das Pack; die bisherige Wurzel-`lang/`-Logik bleibt für alte Pakete.

- [ ] **Step 1: Failing tests**:
  - `ExtensionHandshakeTest.reloadsResourcesBeforeReady`: Fake-IO protokolliert Reihenfolge `load(a)`, `reloadResources`, erst nach `done(null)` `ExtensionsReady(true)`.
  - `reloadErrorFailsHandshake`: `done("boom")` → `ExtensionsReady(false, "Ressourcen konnten nicht geladen werden: boom")`, `unloadAll` aufgerufen.
  - `noAssetsSkipsReload`: kein `reloadResources`-Aufruf.
  - `KilnPackResourcesTest.listsOnlyRequestedNamespaceAndPrefix`, `namespacesFromFiles`, `serverDataIsEmpty`.
- [ ] **Step 2–4:** FAIL → implementieren → PASS.
- [ ] **Step 5: Manuell** (nach Task 8 mit Demo-Assets wiederholen): Join zeigt nach dem Laden kurz den Reload-Overlay; F3+T funktioniert danach weiter; Disconnect → erneuter Reload, Assets weg.
- [ ] **Step 6:** Commit `feat(client): mount extension assets as a resource pack during configuration`.

### Task 5: Custom Items

**Files:**
- Create: `server-api/src/main/kotlin/dev/kiln/api/item/CustomItems.kt` (`CustomItems`, `CustomItem`, `CustomItemBuilder`)
- Modify: `server-api/src/main/kotlin/dev/kiln/api/Kiln.kt` (`val items: CustomItems`)
- Create: `server/src/main/kotlin/dev/kiln/server/item/CustomItemRegistry.kt`, `CustomItemListener.kt`
- Modify: `server/.../extension/ExtensionRegistry.kt` (Abfrage „Extension-Id gehört Plugin X und enthält Datei Y“)
- Test: `server/src/test/kotlin/dev/kiln/server/item/CustomItemRegistryTest.kt`

**Interfaces:**
- `interface CustomItems { fun register(plugin: Plugin, key: Key, init: CustomItemBuilder.() -> Unit): CustomItem; fun of(stack: ItemStack?): CustomItem?; fun get(key: Key): CustomItem?; fun all(): Collection<CustomItem> }`
- `class CustomItemBuilder { var model: Key?; var base: ItemType = ItemType.PAPER; var name: Component?; var lore: List<Component>; fun components(block: ItemComponents.() -> Unit); fun onUse(h: (Player, ItemStack, PlayerInteractEvent) -> Unit); fun onHit(h: (Player, Entity, ItemStack, EntityDamageByEntityEvent) -> Unit); fun onBreakBlock(h: (Player, Block, ItemStack, BlockBreakEvent) -> Unit) }` – `ItemComponents` ist eine dünne Hülle, die `setData(DataComponentType.Valued<T>, T)` / `setData(NonValued)` sammelt und beim `create` anwendet.
- `interface CustomItem : Registration { val key: Key; val plugin: Plugin; fun create(amount: Int = 1): ItemStack; fun matches(stack: ItemStack?): Boolean }`
- PDC-Schlüssel `kiln:item` (String = Key). Zusätzlich `Extension.key(name: String): Key` (= `Key.key(id, name)`) in `server-api`. `model` Default = `key`. Registrierung prüft: Namensraum == Extension-Id dieses Plugins; `assets/<ns>/items/<path>.json` existiert in dieser Extension → sonst `IllegalArgumentException` mit Text „Item model <key> not found in extension <ns> (expected assets/<ns>/items/<path>.json)“. Doppelter Key → `IllegalStateException`. Plugin-Disable entfernt die Registrierungen.
- Die Prüflogik liegt in einer reinen Funktion `CustomItemRegistry.validate(key, model, plugin, extensions: (String) -> ExtensionLookup?)` – testbar ohne Server.

- [ ] **Step 1: Failing tests**: `rejectsForeignNamespace`, `rejectsMissingItemDefinition`, `acceptsExistingDefinition`, `rejectsDuplicateKey`, `modelDefaultsToKey`.
- [ ] **Step 2–4:** FAIL → implementieren (Listener: `PlayerInteractEvent` Hand-Item, `EntityDamageByEntityEvent` Hauptand, `BlockBreakEvent`; Ausnahmen aus Plugin-Lambdas loggen, nicht werfen) → PASS.
- [ ] **Step 5:** Commit `feat(api): add custom items backed by data components`.

### Task 6: Custom Enchantments

**Files:**
- Create: `server-api/src/main/kotlin/dev/kiln/api/enchantment/KilnBootstrap.kt`, `Enchantments.kt`
- Modify: `server-api/.../Kiln.kt` (`val enchantments: CustomEnchantments`)
- Create: `server/src/main/kotlin/dev/kiln/server/enchantment/EnchantmentHooks.kt`
- Test: `server/src/test/kotlin/dev/kiln/server/enchantment/EnchantmentHooksTest.kt`

**Interfaces:**
- `object KilnBootstrap { fun enchantments(context: BootstrapContext, block: EnchantmentDefinitions.() -> Unit) }` – registriert einen Handler für `RegistryEvents.ENCHANTMENT.compose()` und legt pro `register(key) { … }` einen Eintrag an. Builder-Felder: `description: Component`, `supportedItems: TagKey<ItemType>`, `primaryItems: TagKey<ItemType>?`, `maxLevel: Int = 1`, `weight: Int = 1`, `anvilCost: Int = 1`, `minCost/maxCost: EnchantmentCost` (Defaults `of(1, 10)` / `of(21, 10)`), `fun slots(vararg EquipmentSlotGroup)`. Registrierte Keys merkt sich ein statischer Speicher `KilnBootstrap.registered: Set<Key>`.
- `interface CustomEnchantments { fun onHit(key: Key, h: (attacker: LivingEntity, victim: Entity, level: Int, EntityDamageByEntityEvent) -> Unit): Registration; fun onBreak(key: Key, h: (Player, Block, level: Int, BlockBreakEvent) -> Unit): Registration; fun onTick(key: Key, h: (Player, level: Int) -> Unit): Registration /* alle 20 Ticks für Rüstung + Hände */; fun level(stack: ItemStack?, key: Key): Int }`
- `ServerLoadEvent`: für jeden Key aus `KilnBootstrap.registered` ohne Extension mit gleicher Id → Warnung „Enchantment <key>: no extension '<ns>' registered; its texts and assets will be missing“.
- Reine Logik `EnchantmentHooks.dispatchLevels(stacks, key)` (höchstes Level über Slots) testbar ohne Server.

- [ ] **Step 1: Failing tests**: `levelIsHighestAcrossSlots`, `missingExtensionProducesWarning` (Funktion `EnchantmentHooks.warnings(registered, extensionIds)`), `handlerExceptionsAreContained`.
- [ ] **Step 2–4:** FAIL → implementieren → PASS.
- [ ] **Step 5:** Commit `feat(api): add custom enchantments via bootstrap registry and hooks`.

### Task 7: Post-Effekte, Lua-Sounds und -Fonts

**Files:**
- Modify: `server-api/.../Kiln.kt` (`fun setPostEffect(player: Player, effect: Key?)`), `server/.../KilnImpl.kt` (sendet `SetPostEffect`; Key muss Namensraum einer registrierten Extension sein oder `null`)
- Modify: `client/.../KilnClientImpl.kt` (`SetPostEffect` → `GameRenderer.setPostEffect(Identifier)` bzw. `clearPostEffect()`; unbekannter Effekt → Debug-Log)
- Modify: `client/.../lua/LuaExtension.kt` (`kiln.sound.play(id, {volume, pitch})` gated `resources`; Kurznamen über bestehendes `ui.sound`), `lua/LuaGraphics.kt` + `ext/GuiDrawTarget.kt` (`font`-Option in `g:text`, gated `resources`, nur erlaubte Namensräume)
- Test: `client/src/test/kotlin/dev/kiln/client/lua/LuaResourcesTest.kt`, `server/src/test/.../PostEffectTest.kt`

**Interfaces:**
- `ExtensionHost.playSound(id: Identifier, volume: Float, pitch: Float)` neu (bestehende Kurzname-Variante bleibt).
- `DrawTarget.text(..., font: String?)` – `null` = Standardschrift.
- Erlaubte Namensräume für Lua = `{eigene Id} ∪ dependencies`; sonst Lua-Fehler „namespace '<ns>' not allowed (own or dependency only)“. `kiln.sound` höchstens 10 Aufrufe pro Sekunde.

- [ ] **Step 1: Failing tests**: `soundNeedsResourcesPermission`, `soundRejectsForeignNamespace`, `soundAllowsDependencyNamespace`, `fontOptionPassedToTarget`, `fontRejectsForeignNamespace`; Server: `postEffectRejectsUnknownNamespace` (reine Prüffunktion).
- [ ] **Step 2–4:** FAIL → implementieren → PASS.
- [ ] **Step 5:** Commit `feat: add post effects, lua sounds and fonts from extension resources`.

### Task 8: Demos und Doku

**Files:**
- Create: `example-server/src/main/resources/extensions/kiln_content/extension.json` (Ressourcen-Paket + kleines `main.lua` für die HUD-Font-Demo, Rechte `hud`, `resources`)
- Create: `…/kiln_content/assets/kiln_content/items/ruby.json`, `models/item/ruby.json`, `textures/item/ruby.png`, `sounds.json`, `sounds/ruby_chime.ogg`, `font/runes.json`, `textures/font/runes.png`, `lang/en_us.json`, `lang/de_de.json`, `post_effect/wobble.json` (+ ggf. Shader im eigenen Namensraum)
- Create: `tools/gen_content_assets.py` (erzeugt PNGs + Font-Bitmap deterministisch; `.ogg` siehe Step 1)
- Create: `example-server/src/main/kotlin/dev/kiln/example/server/demos/content/ContentDemo.kt`, `ExampleBootstrap.kt`
- Modify: `example-server/build.gradle.kts` (`paper { bootstrapper = "dev.kiln.example.server.ExampleBootstrap" }`), `ExamplePlugin.kt`, `ExampleCommands.kt` (`/kiln give ruby|lifesteal_sword`, `/kiln font`, `/kiln effect [wobble|off]`)
- Modify: `README.md` (Demo-Tabelle), `docs/lua-extensions.md` (Paketformat v6, `resources`, `kiln.sound`, `font`), `docs/design.md` (Protokoll v6)

- [ ] **Step 1:** `.ogg`-Quelle klären: Im Repo gibt es keinen Ogg-Encoder (kein ffmpeg/oggenc). Vor diesem Schritt den User fragen: eigene `.ogg` liefern oder `pip install soundfile` (libsndfile mit Vorbis) für `tools/gen_content_assets.py` erlauben.
- [ ] **Step 2:** Assets erzeugen, Demo-Code schreiben: Rubin (`kiln.items.register`, `onUse` spielt `Sound.sound(Key.key("kiln_content","ruby_chime"), MASTER, 1f, 1f)`), Lifesteal (Bootstrap, `onHit` heilt `level` Herzen × Schaden 20 %), Font-Nachricht, Post-Effekt.
- [ ] **Step 3:** `./gradlew build` → PASS (inkl. `DemoExtensionsTest`, der `kiln_content` mit den Client-Regeln liest – Fall ergänzen).
- [ ] **Step 4: Manuell** mit `:example-server:runServer` + `:example-server:runClient`: Zustimmung zeigt „Ressourcen“, Rubin hat eigenes Modell, Rechtsklick spielt Sound, `/kiln font` zeigt Runen, Lifesteal-Schwert im Verzauberungstisch-Text und heilt, `/kiln effect wobble` an/aus, Disconnect entfernt alles.
- [ ] **Step 5:** Commit `feat(example): add content demos for items, sounds, fonts, enchantments and post effects`.
