# Kiln Content – Design (Ressourcen, Items, Enchantments, Möbel, Custom Entities, Build)

Status: Entwurf, 2026-11-20. Blöcke sind ausdrücklich **nicht** Teil dieses Designs (später, eigener Entwurf).

## Absicht

Kiln soll Server-Plugins erlauben, Inhalte so **nativ wie möglich** auszuliefern – „alles, was ein Resourcepack
bietet“, dazu eigene Items, Enchantments, Möbel mit (zusammengesetzten) Hitboxen und Custom Entities mit
Animationen und vollständig eigener KI. Der Vorteil gegenüber ItemsAdder/Nexo/ModelEngine: **jeder Spieler hat die
Kiln-Runtime**. Kiln darf deshalb Server (NMS) **und** Client (Mixins) patchen, solange beide Seiten
übereinstimmen. Plugins sehen davon nur saubere Methoden in `server-api`; NMS bleibt im Modul `server`.

Daneben wird der Build aufgeräumt (Convention-Plugins in `build-logic`, `paper-plugin.yml` per plugin-yml) und es
entsteht ein Gradle-Plugin, mit dem externe Kiln-Projekte Server und Dev-Client starten können.

## Ziele

1. **Ressourcen:** Extension-Pakete dürfen einen `assets/`-Baum im Resourcepack-Format enthalten (Modelle,
   Item-Definitionen, Texturen, `sounds.json` + `.ogg`, Fonts inkl. TTF/OTF/Bitmap, Sprachdateien, Post-Effekte
   und Shader im eigenen Namensraum, `.mcmeta`). Der Client hängt sie als **echtes Resourcepack** ein; alles wird
   von Minecraft selbst genutzt.
2. **Custom Items:** API für Items auf Basis von Datenkomponenten (`item_model`, Name, Lore, Food, Tool,
   Equippable, Consumable, …) plus Verhaltens-Hooks (Benutzen, Treffen, Abbauen …).
3. **Custom Enchantments:** echte Registry-Einträge über Paper-Bootstrap (`RegistryEvents.ENCHANTMENT`), mit
   Kiln-Hooks für eigene Logik.
4. **Sounds & Fonts:** über Ressourcen nativ nutzbar (`Sound.sound(key)`, `Component.font(key)`); zusätzlich
   Lua-Zugriff (`kiln.sound.play`, Font-Parameter in `g:text`).
5. **Möbel:** platzierbare Objekte aus Modell + mehreren Hitboxen (anklickbar, optional **fest/begehbar**),
   persistiert in der Welt, mit Interaktions-Hooks (Sitzen ist eine Demo, kein Pflichtteil der API).
6. **Custom Entities:** eigene Entity-Arten mit Bone-Modell, Animationen (server-gesteuert + automatische
   Idle/Walk-Zustände), eigener Hitbox-Größe und vollständig eigener KI (Ziele, Zustandsmaschine, Abläufe).
7. **Build:** `build-logic` mit Convention-Plugins; `paper-plugin.yml` per `de.eldoria.plugin-yml.paper` für
   `server` und `example-server`; NMS im `server` per paperweight-userdev.
8. **Kiln-Gradle-Plugin** für Fremdprojekte: Abhängigkeiten, `paper-plugin.yml`-Abhängigkeit auf Kiln,
   `runServer` (Paper + Kiln), `runClient` (Minecraft + Fabric + Kiln-Runtime).
9. Demos in `example-server` für Item-Modell, Sound, Font, Enchantment, Möbel, Custom Entity; Tests grün in
   `./gradlew build`.

## Nicht-Ziele

- Eigene Blöcke (Notenblock-Träger mit gepatchten Formen) – später.
- Neue Einträge in fest eingebauten Registries (Block/Item/EntityType). Begründung: Paper unterstützt das nicht,
  Bukkit (`Material`, `CraftBlockData`) kennt die Typen nicht, der Client kann Einträge nicht wieder entfernen
  (Serverwechsel bräuchte Neustart) und ohne Plugin gingen Weltdaten verloren.
- Überschreiben von Vanilla-Assets (`assets/minecraft/…`) oder fremden Namensräumen (Sicherheitsregel bleibt:
  Zustimmungs-Screen fälschen, UI-Texte ändern). Ausnahmen trifft Kiln selbst, nie eine Extension.
- Core-Shader-Ersatz. Post-Effekte im eigenen Namensraum ja.
- Molang-Ausdrücke in Animationen über Konstanten hinaus.
- Veröffentlichung auf einem Remote-Maven-Repository (vorbereitet, Ziel offen; zunächst `mavenLocal`).

## Anti-Ziele (wäre ein Fehlschlag, auch wenn es „läuft“)

- Server und Client sind sich über Hitboxen/Kollision uneinig (Gummiband-Effekte, Kicks wegen „moved wrongly“
  oder Fliegen, Klicks, die der Server verwirft).
- Ein Plugin muss NMS anfassen oder Kiln-Interna kennen, um Inhalte zu definieren.
- Ressourcen umgehen die Zustimmung oder können Vanilla/kiln-Texte und -Texturen ersetzen.
- Möbel/Entities verschwinden oder werden zu „nackten“ Vanilla-Entities nach Server-Neustart oder Chunk-Reload.

## Randbedingungen

- Minecraft 26.3 (Mojang-Namen, nicht obfuskiert), Paper `26.3.build.256-stable`, Fabric Loader 0.20.1,
  Fabric API `0.164.0+26.3`, Java 25, Kotlin 2.4.21.
- Paper erlaubt beim Bootstrap nur datengetriebene Registries (Enchantment, Sound Event, Damage Type, Dialog,
  Varianten …). Blöcke/Items/EntityTypes nicht.
- Kein Code vom Server auf dem Client außer sandboxed Lua; Ressourcen sind Daten. Limits stehen im Protokoll.
- Protokoll-Bump: `PROTOCOL_VERSION = 6`.
- Commits englisch, Conventional Commits.

## Ansätze (entschieden)

| Ansatz | Kern | Bewertung |
|---|---|---|
| A: echte Registry-Einträge auf beiden Seiten | NMS-Unfreeze auf Paper, dynamische Registrierung auf dem Client | am nativsten, aber von Paper nicht gestützt, Bukkit-inkompatibel, Client kann nicht entladen, Datenverlust ohne Plugin → **verworfen** |
| B: Vanilla-Träger ohne Patches (ItemsAdder-Stil) | Display/Interaction-Entities, Notenblöcke | robust, aber keine eigenen Hitboxen/Kollisionen, keine Bone-Animation → **zu schwach** |
| **C: Vanilla-Träger, beidseitig gepatcht** | Träger-Entities/-Zustände, Kiln patcht Server (NMS) und Client (Mixin) konsistent; datengetriebenes nativ (Enchantments, Items, Ressourcen) | **gewählt** vom User (ohne Blöcke) |

Build-Teil: eine sinnvolle Lösung (Convention-Plugins in einem Included Build `build-logic`, Kiln-Gradle-Plugin
als eigener Included Build), keine echten Alternativen.

## Entscheidungen des Users

- Ansatz C, Reihenfolge: Ressourcen-Grundlage → Möbel → Custom Entities; Blöcke später.
- NMS im `server`-Modul erlaubt; `server-api` bietet nur API-Methoden.
- `paper-plugin.yml` statt `plugin.yml`, generiert mit `de.eldoria.plugin-yml` (0.9.0 im Cache).
- `build-logic` mit extrahierten Konventionen (Kotlin/Java-Toolchain, Tests, ShadowJar, …).
- Gradle-Plugin für Kiln-Projekte mit Tasks wie `runServer`, `runClient`.
- Paper-Seite: „wie, ist mir egal“; Custom Entities brauchen komplett eigene KI.

## Entscheidungen des Builders (nach Prüfung, vom User gegenzulesen)

1. **Namensräume von Inhalten:** Der Namensraum jedes registrierten Keys (Item, Möbel, Entity, Enchantment) muss
   die Id einer Extension sein, die dasselbe Plugin registriert. Item/Möbel/Entity prüfen das beim `register`
   (Extension muss also vorher registriert sein) samt Existenz der referenzierten Assets
   (`assets/<id>/items/<name>.json` bzw. `kiln/entity/<name>.geo.json`) → `IllegalArgumentException`.
   Enchantments entstehen im Bootstrap vor jeder Extension; Kiln prüft sie bei `ServerLoadEvent` und loggt
   eine Warnung, wenn keine passende Extension existiert. Sprachschlüssel folgen der Präfixregel
   (`kiln_content.enchantment.lifesteal`).
2. **„Fest“ bei Möbeln:** Kollision wie bei Booten/Shulkern – Spieler, Mobs und alle Entities, die Vanilla-
   Entity-Kollision nutzen, stoßen an und können darauf stehen. Pfadfindung um Möbel herum wird in v1 **nicht**
   gepatcht (Vanilla-Knotenbewertung ignoriert Entities); Mobs laufen ggf. dagegen. Bekannte Lücke, kommt mit
   Blöcken.
3. **Lua-Zugriff:** `kiln.sound.play` mit eigenen Sounds und `font` in `g:text` brauchen die Berechtigung
   `resources`; erlaubt sind der eigene Namensraum und die Namensräume der eigenen `dependencies`. Die alten
   Kurznamen (`click`, `pling`, …) bleiben ohne Recht nutzbar. Die Funktionen gelten als rechte-gebunden im
   Sinne von „Kein Ausleihen von Rechten“.
4. **Tests:** Unit-Tests für reine Logik (Codec, Paket-/Asset-Validierung, Geo-/Animations-Parser und
   Interpolation, KI-Sequenz-Engine, Möbel-Rotation/Offsets, Gradle-Plugin per TestKit). Keine automatisierten
   In-Game-Tests (Paper/Fabric-Gametests); Kollision, Persistenz und Pack-Reload werden mit dem Dev-Client von Hand
   geprüft und in der Plan-Checkliste festgehalten.
5. **Referenzen vs. Überschreiben:** Assets dürfen auf fremde Namensräume **verweisen** (Parent
   `minecraft:item/generated`, Vanilla-Programme in Post-Effekt-Ketten, Vanilla-Texturen), aber nur Dateien unter
   `assets/<id>/` **liefern**. Ausnahme gegen Fälschung: Verweise auf den Namensraum `kiln` (Kiln-UI-Texturen)
   sind verboten; Font-Provider und Post-Effekte dürfen keine `kiln:`-Texturen nutzen.

## Design

### 0. Build

**`build-logic`** (Included Build, `pluginManagement { includeBuild("build-logic") }`), Precompiled Script
Plugins:
- `kiln.kotlin` – Kotlin JVM, Toolchain 25, `jvmTarget 25`, `withSourcesJar`, UTF-8, `release 25`.
- `kiln.test` – JUnit-BOM, Jupiter, Launcher, `useJUnitPlatform()`.
- `kiln.library` – `kiln.kotlin` + `java-library` + `explicitApi()` (protocol, server-api).
- `kiln.shadow` – ShadowJar ohne Classifier, `.kotlin_module`-Merge, `mergeServiceFiles`, `jar` → `plain`,
  `assemble` hängt an `shadowJar`.
- `kiln.paper-plugin` – `kiln.kotlin` + `kiln.shadow` + `de.eldoria.plugin-yml.paper`, Version aus Projekt.
- `kiln.publish` – `maven-publish` mit `mavenLocal` (Remote-Repo später per Property).
Versionskatalog bleibt `gradle/libs.versions.toml`, auch in `build-logic` nutzbar. Root-`build.gradle.kts`
enthält danach keine `subprojects {}`-Blöcke mehr.

**`server`**: paperweight-userdev (`2.0.0-beta.24`, Dev-Bundle `26.3.build.256-stable`) statt reinem
`paper-api`; Mojang-Mappings zur Laufzeit (`paperweight-mappings-namespace: mojang`, kein Reobf). Generiert
`paper-plugin.yml` (`name: Kiln`, `load: STARTUP`, `bootstrapper` + `loader` falls nötig). Tests für reine Logik
bleiben ohne laufenden Server.

**`example-server`**: nutzt das Kiln-Gradle-Plugin (s. u.) und `paper-plugin.yml`. Folge: Befehle stehen nicht
mehr in der YAML, sondern werden per `LifecycleEvents.COMMANDS` (Brigadier) registriert – alle bestehenden
Demo-Befehle werden umgezogen.

**Kiln-Gradle-Plugin** (Included Build `gradle-plugin`, Plugin-Id `dev.kiln`):
- Erweiterung `kiln { version; minecraftVersion; paperVersion; serverPort; clientUsername }` mit Defaults aus der
  Kiln-Version, mit der das Plugin gebaut wurde.
- Wendet `de.eldoria.plugin-yml.paper` und `xyz.jpenilla.run-paper` an; fügt `compileOnly` paper-api und
  `dev.kiln:kiln-server-api` hinzu; trägt Kiln als Server-Abhängigkeit in `paper-plugin.yml` ein
  (`load: BEFORE`, `required: true`, `join-classpath: true`).
- `runServer`: Paper mit Kiln-Server-Jar (Konfiguration `kilnServer`) + Projekt-Jar, Run-Ordner `run/server`,
  EULA, `online-mode=false` per `server.properties`-Vorbelegung (Dev-Client spielt offline).
- `runClient`: erzeugt unter `build/kiln/client/` ein kleines Gradle-Projekt mit Loom und
  `modRuntimeOnly` Kiln-Client + Fabric API + Fabric Language Kotlin und startet dort `runClient` (Run-Ordner
  `run/client`, Benutzer aus `clientUsername`). So bleibt Loom aus dem Plugin-Projekt heraus.
- Im Kiln-Repo selbst ersetzt eine Dependency-Substitution (Composite Build) die Maven-Artefakte durch die lokalen
  Projekte (`:server-api`, `:server`, `:client`), sodass `./gradlew :example-server:runServer`/`runClient` ohne
  Veröffentlichung funktionieren. `:client:runClient` bleibt zusätzlich erhalten.
- Veröffentlichung von `protocol`, `server-api`, `server`, `client`, `gradle-plugin` über `kiln.publish`.

### 1. Ressourcen-Grundlage

**Paketformat** (v6):
- Neu erlaubt: `assets/<id>/**` mit Endungen `json png mcmeta ogg ttf otf fsh vsh glsl txt`. Der Namensraum unter
  `assets/` muss der Extension-Id entsprechen (Server beim Packen, Client beim Lesen); andere Namensräume →
  Fehler. Bestehende `textures/`- und `lang/`-Dateien an der Wurzel bleiben für Lua (unverändert).
- `main` wird optional: Pakete ohne Lua (reine Ressourcen-Pakete) sind erlaubt.
- Neue Berechtigung `resources` (Zustimmungs-Screen: „Bringt Ressourcen mit: Modelle, Texturen, Sounds,
  Schriften“). Pflicht, sobald `assets/` existiert; der Server leitet sie beim Packen automatisch ab, wenn sie
  fehlt (Autor muss sie nicht pflegen), und zeigt sie im Manifest.
- Limits (Protokoll-Konstanten): Zip ≤ 32 MiB, entpackt ≤ 64 MiB, ≤ 4096 Dateien, einzelne `.ogg` ≤ 8 MiB,
  Font-Dateien ≤ 4 MiB, Texturen ≤ 4096×4096. Chunks bleiben 512 KiB. Die Gesamtdauer-/Drossel-Logik bleibt.

**Client:** Nach dem Laden aller Pakete (Configuration-Phase, vor `ExtensionsReady`) baut Kiln aus den
`assets/`-Bäumen ein In-Memory-`PackResources` („Kiln: <Server>“) und hängt es über eine eigene
`RepositorySource` als oberstes, nicht abwählbares Pack ein; dann `reloadResourcePacks()` mit Ladebildschirm.
Erst nach erfolgreichem Reload `ExtensionsReady(true)`; Fehler beim Reload → `ExtensionsReady(false, Grund)`.
Beim Disconnect wird das Pack entfernt und erneut geladen (nur wenn eines eingehängt war). Reload entfällt, wenn
kein Paket `assets/` hat. Vor dem Einhängen prüft der Client erneut: nur `assets/<id>/…`, erlaubte Endungen,
Größen; JSON parsebar. Sprachschlüssel aus `assets/<id>/lang/*.json` unterliegen derselben Präfixregel wie heute
(`<id>.…`), damit keine Vanilla-Texte ersetzt werden.

**Server-API:**
- `Extension.resource(path)`/`Extension.key(name)` als Hilfen; Ressourcen werden ansonsten über Vanilla-Keys
  genutzt (`Key.key(ext.id, "ruby")`).
- Sounds: `Sound.sound(Key.key(ext.id, "laser"), …)` funktioniert nativ (Adventure, direkter Holder).
- Fonts: `Component.text("…").font(Key.key(ext.id, "runes"))`.
- Post-Effekte: `Kiln.setPostEffect(player, key?)` → neue Nachricht `SetPostEffect(id?)` (S→C, Play-Phase), nur
  Keys geladener Extensions oder `null`.
- Lua: `kiln.sound.play(id, {volume, pitch})` (eigener Namensraum oder bisherige Kurzname-Sounds),
  `g:text(..., {font = "ext:runes"})`.

**Custom Items** (`server-api`):
```kotlin
val ruby = kiln.items.register(plugin, Key.key("kiln_content", "ruby")) {
    model = Key.key("kiln_content", "ruby")            // item_model
    base = ItemType.PAPER                      // Träger-Item (Default PAPER)
    name = Component.text("Rubin")
    components { food(4, 2f); maxStackSize(16) }   // Paper DataComponent-API durchgereicht
    onUse { player, stack -> … }; onHit { player, target, stack -> … }
}
val stack: ItemStack = ruby.create(amount = 3)
kiln.items.of(stack) // CustomItem? – Erkennung per PDC-Schlüssel kiln:item
```
Die Hooks verteilt Kiln aus Bukkit-Events. Items werden **nicht** in eine Registry geschrieben (kein Item-Typ),
sondern sind Stacks mit Komponenten; das ist für Vanilla-Clients wie für Kiln nativ.

**Custom Enchantments:** über Kilns Bootstrap-Hilfe im Bootstrapper des Fremd-Plugins:
```kotlin
class DemoBootstrap : PluginBootstrap {
    override fun bootstrap(context: BootstrapContext) {
        KilnBootstrap.enchantments(context) {
            register(Key.key("kiln_content", "lifesteal")) {
                description = Component.translatable("kiln_content.enchantment.lifesteal")
                supportedItems = ItemTypeTagKeys.ENCHANTABLE_WEAPON
                maxLevel = 3; weight = 2; anvilCost = 4
                slots(EquipmentSlotGroup.MAINHAND)
            }
        }
    }
}
// später, onEnable:
kiln.enchantments.onHit(Key.key("kiln_content", "lifesteal")) { attacker, victim, level, event -> … }
```
`KilnBootstrap` liegt in `server-api` und delegiert an Paper (`RegistryEvents.ENCHANTMENT.compose`). Die Hooks
(`onHit`, `onBreak`, `onTick` alle 20 Ticks für getragene Ausrüstung) liefert Kiln über Events. Übersetzungen
kommen aus den Extension-Ressourcen (Sprachschlüssel im eigenen Namensraum, z. B. `kiln_content.enchantment.lifesteal` –
Präfixregel beachten).

### 2. Möbel

Ein Möbelstück ist eine **Gruppe von Entities**, von Kiln verwaltet:
- 1 `ItemDisplay` (Modell über `item_model`, Rotation beliebig, Transform aus der Definition),
- N Hitbox-Teile als `Interaction`-Entities (Breite/Höhe frei, achsenparallel). Offsets werden mit der
  Platzierungsrichtung gedreht; ist mindestens eine Hitbox definiert, wird die Gier auf 90°-Schritte gerastet
  (achsenparallele Boxen); reine Modell-Möbel dürfen beliebig drehen.
- Hitboxen mit `solid = true` sind **fest**: Spieler und Mobs kollidieren und können darauf stehen. Umsetzung:
  Server – Kiln-Unterklasse von NMS `Interaction` ohne zusätzliche synchronisierte Daten, `canBeCollidedWith →
  true` (wie Boote/Shulker), damit Bewegungsprüfung und Pfadfindung die Box kennen. Client – Kiln markiert die
  Entity-Id per Nachricht (`EntityTraits(entityId, flags)`) und ein Mixin liefert `canBeCollidedWith → true`
  für markierte Entities. Damit gilt dieselbe Kollision auf beiden Seiten.
- Persistenz: alle Teile tragen PDC (`kiln:furniture` = Typ-Key, Gruppen-UUID, Teilindex). Bei
  `EntitiesLoadEvent` ersetzt Kiln geladene Vanilla-`Interaction`-Teile mit `solid` durch die Kiln-Unterklasse
  (gleiche UUID/Daten) und hängt Verhalten wieder an. Ohne Kiln bleiben harmlose Display/Interaction-Entities.
- Markierungen an Clients: beim Tracken (`PlayerTrackEntityEvent`/NMS-Tracking) sendet Kiln `EntityTraits`.

API:
```kotlin
val chair = kiln.furniture.register(plugin, Key.key("kiln_content", "chair")) {
    model = Key.key("kiln_content", "chair"); item = chairItem          // Platzieren per Item (optional)
    hitbox(width = 1.0, height = 0.5, solid = true)              // Sitzfläche
    hitbox(width = 1.0, height = 0.6, offset = Vector(0.0, 0.5, 0.4), solid = true) // Lehne
    onInteract { player, furniture -> furniture.seat(player, Vector(0.0, 0.4, 0.0)) }
    onBreak { player, furniture -> … }                            // Default: Item droppen
}
chair.place(location, yaw): Furniture ; kiln.furniture.at(entity): Furniture?
```
`Furniture.seat(player, offset)` ist eine kleine Hilfe (unsichtbarer Sitz-Display als Passagier-Träger).
Abbauen: Linksklick auf eine Hitbox (Interaction meldet Angriffe) → `onBreak`, Survival gibt Item zurück.

### 3. Custom Entities

**Modellformat:** Bedrock-Geometrie (`*.geo.json`, Format 1.12+: Bones mit `pivot`, `rotation`, `cubes` mit
Box-UV und Per-Face-UV, `inflate`, `mirror`) und Bedrock-Animationen (`*.animation.json`: `loop`,
`animation_length`, Bone-Kanäle `rotation`/`position`/`scale` mit Keyframes, Interpolation `linear` und
`catmullrom`, Werte nur Zahlen). Beides ist das Standard-Export-Format von Blockbench. Ablage in den Ressourcen:
`assets/<id>/kiln/entity/<name>.geo.json`, `…/<name>.animation.json`, Textur
`assets/<id>/textures/entity/<name>.png`. Ein Kiln-Reload-Listener auf dem Client lädt sie über den normalen
`ResourceManager` (also mit dem Pack aus Teil 1). Limits: ≤ 256 Bones, ≤ 4096 Cubes, ≤ 128 Animationen je Modell.

**Client-Rendering:** Für Entities mit `EntityTraits.model = <key>` ersetzt Kiln den Vanilla-Renderer
(Mixin/Dispatcher-Hook) durch einen Kiln-Renderer, der das Bone-Modell zeichnet. Animationen:
- Zustandsanimationen automatisch: `idle`, `walk` (aus der Client-seitigen Bewegung), optional `death`.
- Server-Animationen: `PlayAnimation(entityId, name, mode = once|loop|hold, speed, fade)` und
  `StopAnimation(entityId, name)`; überlagern die Zustandsanimation (Bones, die die Server-Animation nicht
  setzt, bleiben bei der Zustandsanimation).
- Kopfdrehung: Bone `head` folgt optional Blickrichtung.
- Hitbox: Client-Mixin auf `getDimensions` für markierte Entities (Breite/Höhe aus `EntityTraits`), damit
  Fadenkreuz-Treffer zur Server-Box passen.

**Server – Träger:** Kiln-Unterklassen von NMS-Mobs, **ohne** zusätzliche synchronisierte Entity-Daten (sonst
stürzt der Client wegen unbekannter Data-Accessors ab):
- `ground` (Standard): Unterklasse eines Vanilla-PathfinderMob-Typs mit Boden-Navigation.
- `flying`: Unterklasse eines fliegenden Typs mit Flug-Navigation.
- (`water` optional, wenn günstig.)
Welcher Vanilla-Typ konkret dient, entscheidet der Builder; Kriterien: wenige eigene Mechaniken (kein Brennen in
der Sonne, keine Verwandlung, keine Geräusche – oder alle abschaltbar), Vanilla-Ziele/Brain werden vollständig
geleert, Dimensions per Override (`getDefaultDimensions`) aus der Definition, Lautlos, eigener Name/Drops.
Persistenz wie bei Möbeln: PDC `kiln:entity` = Typ-Key; beim Laden ersetzt Kiln die Vanilla-Instanz durch die
Unterklasse (gleiche UUID, NBT übernommen) und stellt die KI neu her. Unbekannte Typ-Keys (Plugin fehlt) → Entity
bleibt als Vanilla-Mob ohne KI, unsichtbar (Kiln entfernt es nicht automatisch; Log-Warnung).

**KI-API** (`server-api`, reine Kiln-Typen, intern auf NMS `GoalSelector`/Navigation abgebildet):
```kotlin
val golem = kiln.entities.register(plugin, Key.key("kiln_content", "golem")) {
    model = Key.key("kiln_content", "golem"); movement = Movement.GROUND
    size(width = 1.4, height = 2.7); health = 80.0; speed = 0.25
    attribute(Attribute.ATTACK_DAMAGE, 8.0)
    behavior {
        goal(priority = 1, "smash") {                     // eigene Ziele
            canStart { target != null && distanceTo(target!!) < 3 && cooldown("smash") }
            sequence {                                    // „Abläufe“: zeitgesteuerte Schritte
                stopMoving(); animate("smash"); wait(12)
                run { nearby<Player>(3.0).forEach { damage(it, 10.0) } }
                wait(8); cooldown("smash", ticks = 60)
            }
        }
        goal(2, "chase") { canStart { target != null }; tick { moveTo(target!!, speed = 1.2) } }
        goal(5, "wander") { wander(radius = 8) }          // fertige Bausteine
        goal(6, "look") { lookAtNearest<Player>(8.0) }
        targeting { nearest<Player>(range = 16.0) }       // Ziel-Auswahl
        state("enraged") { onEnter { animate("roar") } }  // einfache Zustandsmaschine: state(…), switchTo(…)
    }
    onDamage { source, amount -> if (health < 30) switchTo("enraged") }
    onDeath { animate("death"); drop(rubyItem.create()) }
}
golem.spawn(location): CustomEntity   // CustomEntity: bukkit (Mob), playAnimation, stopAnimation, state, target …
```
- Kontext der Lambdas: `EntityContext` mit `self`, `target`, `world`, `moveTo`, `lookAt`, `animate`,
  `cooldown`, `nearby`, `damage`, `switchTo`, Blackboard (`memory["x"]`).
- `sequence` läuft tickweise ohne Threads; wird ein Ziel unterbrochen (höhere Priorität), bricht die Sequenz ab
  (`onCancel` optional).
- Alles läuft auf dem Main-Thread; Fehler in Plugin-Lambdas werden geloggt und deaktivieren das Ziel für die
  Entity, nie den Server.

### Protokoll v6 – neue Nachrichten (S→C, Play-Phase)

- `EntityTraits(entityId: varint, model: Key?, width: float?, height: float?, solid: bool, headBone: bool)`
- `EntityTraitsRemove(entityId)` (optional, Untrack räumt ohnehin)
- `PlayAnimation(entityId, name, mode, speed, fadeTicks)` / `StopAnimation(entityId, name?)`
- `SetPostEffect(id?)`
Alle mit Längen-/Wertgrenzen im Codec (Animation-Name `[a-z0-9_.]{1,64}`, Größe 0.05..16 Blöcke, Speed 0..8).
Client verwirft Nachrichten zu unbekannten Modellen/Entity-Ids still (Debug-Log). Ordnung: der Server sendet
`EntityTraits` nach dem Spawn-Paket des Trackings; der Client puffert Traits für noch unbekannte Ids kurz.

### Demos (`example-server`, Extension `kiln_content`)

- Item `Rubin` mit eigenem Modell und Textur, `/kiln give ruby`.
- Sound: Rechtsklick mit Rubin spielt `kiln_content:ruby_chime`.
- Font: `/kiln font` schickt eine Nachricht in einer eigenen Bitmap-Schrift; HUD-Lua nutzt sie auch.
- Enchantment `kiln_content:lifesteal` (Bootstrapper), `/kiln give lifesteal_sword`.
- Möbel: Stuhl (2 feste Hitboxen, Sitzen), Tisch (feste Platte), `/kiln give chair|table`.
- Custom Entity: „Golem“ mit Idle/Walk/Smash/Roar-Animation, Verfolgen, Smash-Ablauf, Wut-Zustand,
  `/kiln spawn golem`.
- Post-Effekt-Demo optional (`/kiln effect`).
Assets der Demos werden mit einem kleinen Skript in `tools/` erzeugt oder von Hand gebaut (einfache Würfel-
Modelle reichen); Lizenzfreiheit ist Pflicht.

## Dem Builder überlassen

- Konkrete Vanilla-Trägertypen für Custom Entities und Sitz-Entities (nach den Kriterien oben).
- Mixin-Punkte auf dem Client (Renderer-Ersatz, `canBeCollidedWith`, `getDimensions`, Pack-Einhängen).
- Wie genau die Bukkit-Events auf Item-/Enchantment-Hooks verteilt werden.
- Ob `PlayerTrackEntityEvent` reicht oder ein NMS-Hook am `ServerEntity`/`ChunkMap` nötig ist, um
  `EntityTraits` nach dem Spawn-Paket zu senden.
- Aufteilung in Commits/PRs pro Phase (0 → 1 → 2 → 3), jede Phase mit grünem `./gradlew build`.
