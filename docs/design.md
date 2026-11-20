# Kiln – Prototyp-Design

Ziel: Eine Plattform wie Fabric, deren Spiellogik auf **Paper** läuft. Spieler installieren einmal die
Client-Runtime (Fabric-Mod); Client-Seitiges eines Servers kommt seit v3 als Lua-Extension-Paket vom Server
(siehe `docs/lua-extensions.md`; früher: Client-Erweiterungen als Fabric-Mods, mit Texturen,
eigenen Widgets, Keybinds). Der Server schickt **nur Daten** über Custom Payloads, niemals Code.

Prototyp-Umfang: **GUIs** (vom Server beschrieben, nativ auf dem Client gerendert, live aktualisierbar) und
**Keybinds**. Blöcke, Entities usw. kommen später.

Ziel-Versionen: Minecraft 26.3, Paper `26.3.build.256-stable`, Fabric Loader 0.20.1, Fabric API `0.164.0+26.3`,
Java 25, Kotlin. Keine surf-Abhängigkeiten. Basis-Package `dev.kiln`.

## Module

| Modul | Art | Inhalt |
|---|---|---|
| `protocol` | pure Kotlin/JVM, keine MC-Abhängigkeit | Nachrichten, GUI-Baum, binärer Codec (DataInput/DataOutput, keine Libs) |
| `server-api` | Kotlin, `compileOnly` paper-api | API für Paper-Plugins (wie paper-api) |
| `server` | Paper-Plugin | Implementierung von `server-api`, Kanal, Handshake, Sessions |
| `client` | Fabric-Mod | Runtime: empfängt Payloads, rendert Screens, Keybinds, sendet Events |
| `example-server` | Paper-Plugin | Demo gegen `server-api`: Factory-GUI, Keybinds |

`server` bündelt `protocol` + `server-api` (+ Kotlin-Stdlib). `client` bündelt `protocol` + LuaJ per `include`. Das Example-Modul hängt nur an `server-api` (compileOnly).

## Protokoll

Ein Kanal pro Richtung reicht: `kiln:main` (Plugin-Messaging / Fabric Custom Payload, Byte-Array).
Jede Nachricht: `varint typeId` + Felder. `PROTOCOL_VERSION` als Konstante (v3); bei Mismatch wird der Spieler gekickt.
Ab v3 (Lua-Extensions) gilt `docs/lua-extensions.md`, Abschnitt „Entscheidungen“: Handshake und Paket-Auslieferung
in der Configuration-Phase.

Client → Server
- `Hello(protocolVersion, extensions: List<String>)` in der Configuration-Phase (`extensions` seit v3 leer).
- `ExtensionRequest(ids)`, `ExtensionsReady(ok, error?)` (Configuration-Phase)
- `ScreenClick(screenId, elementId, button)`
- `ScreenInput(screenId, elementId, value)` (Textfeld, nach Änderung gedrosselt bzw. bei Enter)
- `ScreenClosed(screenId)` (Escape oder Server-Close bestätigt)
- `KeyPressed(keyId)` / `KeyReleased(keyId)`
- `ExtensionMessage(channel, json)` (Lua-Extension-Kanäle)

Server → Client
- `Welcome(protocolVersion)`
- `DeclareKeybinds(list of {id, name, category, defaultKey})` (Server-definierte Keybinds)
- `OpenScreen(screenId, root)` / `UpdateScreen(screenId, root)` (ganzer Baum; Client behält Fokus, Textinhalt,
  Scrollposition anhand stabiler Element-IDs) / `CloseScreen(screenId)`
- `ExtensionManifest(list)`, `ExtensionChunk(id, index, total, bytes)` (Configuration-Phase)
- `ExtensionMessage(channel, json)`

## GUI-Baum (deklarativ)

Elemente haben eine stabile `id` (String), Position/Größe in GUI-Pixeln relativ zum Eltern-Element, Root wird
zentriert. Typen im Prototyp:
- `Panel(children, background: Color?, texture: Identifier?, border: Color?)`
- `Text(text: Component als JSON, color, align, shadow)`
- `Button(label, enabled, tooltip)` → Klick-Event
- `Image(texture: Identifier, uv/size)` (Textur aus einer Client-Erweiterung)
- `ItemDisplay(itemId, count)` mit Vanilla-Tooltip
- `ProgressBar(value 0..1, color, background)`
- `TextField(value, placeholder, maxLength)` → Input-Event
- `Custom(type: Identifier, properties: Map<String, String>)` → von einer Client-Erweiterung gerendert;
  unbekannter Typ zeichnet einen Platzhalter

## Protokoll v2 (Fullscreen-Shop-GUI)

- `Viewport(width, height)` (C→S) nach `Hello` und bei jeder Änderung der GUI-skalierten Fenstergröße; der Server
  rendert den offenen Screen neu. `Hello` bleibt unverändert, damit Versions-Mismatches immer erkennbar sind.
- `OpenScreen`/`UpdateScreen` mit `fullscreen` (Root nicht zentriert, Größe = Viewport).
- Jedes Element hat `tooltip`. Neu: `ItemSlot`, `Dropdown`, `Checkbox`, `Slider`, `ScrollPanel(contentHeight)`,
  `Modal` (nur direkt unter Root, letztes = oberstes). `Panel` mit `hoverBackground`, `selectedBackground`,
  `selected`, `clickable`. `TextField.numeric`.
- Items als `ItemStackData(itemId, count, nbt?)`: `nbt` = Paper `ItemStack.serializeAsBytes()` (gzip-NBT von
  `ItemStack.CODEC` + `DataVersion`, Menge 1); Anzahl separat (bis 10^9, >99 als Text).
- `ScreenClick.modifiers` (Shift/Ctrl/Alt), `ScreenInput` für alle Inputs, `ModalDismiss` (Escape bei offenem Modal),
  `ShowToast(title, text, icon, displayMillis)` (S→C).
- Server verwirft Klicks/Inputs auf Elemente außerhalb des obersten Modals.

## Keybinds

- **Server-definiert:** Server deklariert beim Join; Runtime legt KeyMappings an (eigene Kategorie
  „Server: …“), Belegung wird clientseitig persistiert, Druck/Loslassen geht an den Server.
- **Erweiterungs-definiert:** Lua-Extensions registrieren beim Laden `kiln.keys.bind` (Kategorie = Name der
  Extension unter dem Server); Druck/Loslassen ruft die Lua-Handler, die selbst per Kanal an den Server melden.

## Sicherheit / Regeln

- Kein Code vom Server, nur Daten. Server-Nachrichten werden validiert (Größen, Tiefe des Baums, Anzahl Elemente,
  Stringlängen); ungültige Nachrichten werden verworfen und geloggt, nie gecrasht.
- Server prüft jedes Client-Event gegen die offene Session (Screen-ID, existierende Element-ID).
- Spieler ohne Runtime, mit falscher Version oder ohne geladene Extensions werden in der Configuration-Phase
  gekickt (keine Fallbacks, siehe `docs/lua-extensions.md`).
