<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/kiln-logo-dark.svg">
    <img src="docs/assets/kiln-logo-light.svg" alt="Kiln" width="320">
  </picture>
</p>

<p align="center">
  <b>Server-driven client content for Paper.</b><br>
  Real GUIs, keybinds and sandboxed Lua extensions, shipped by the server and rendered natively on the client.
</p>

<p align="center">
  <a href="https://github.com/SLNE-Development/kiln/actions/workflows/build.yml"><img src="https://github.com/SLNE-Development/kiln/actions/workflows/build.yml/badge.svg?branch=main" alt="Build"></a>
  <img src="https://img.shields.io/badge/Minecraft-26.3-3C8527" alt="Minecraft 26.3">
  <img src="https://img.shields.io/badge/Paper-26.3-F7CF0D" alt="Paper 26.3">
  <img src="https://img.shields.io/badge/Fabric-0.20.1-DBD0B4" alt="Fabric Loader 0.20.1">
  <img src="https://img.shields.io/badge/Java-25-E76F00" alt="Java 25">
</p>

---

> **Status: prototype.** APIs, protocol and package format still change between versions.

## What is Kiln?

Vanilla Minecraft offers servers no real way to show custom user interfaces: chest menus, books and dialogs can be
bent into shape, but always with hacks such as core shaders or font tricks. Kiln takes a different route, similar to
FiveM or Garry's Mod:

- Players install the **Kiln runtime** once, a small Fabric mod.
- Everything client-side a server needs comes **from the server**: GUI trees, keybinds, Lua scripts and a full
  resource-pack tree (models, item definitions, textures, sounds, fonts, languages, post effects), mounted as a real
  resource pack. Plugins add custom items and custom enchantments on top.
- Game logic, money, items and validation stay on the **Paper server**. The client only renders and reports input.
- Server scripts run in a **sandbox** (LuaJ without `io`, `os`, `debug`, `load` or Java access, with instruction,
  memory and rate budgets). Players see which permissions an extension wants and agree **before** anything is
  downloaded.
- There are no fallbacks: players without the runtime, with another protocol version, who decline or whose
  extensions fail to load are disconnected with a clear reason.

```mermaid
flowchart LR
    subgraph Server["Paper server"]
        P[Your plugin] --> K[Kiln plugin]
        E[(Extension packages<br/>Lua · PNG · JSON)] --> K
    end
    subgraph Client["Player (Fabric)"]
        R[Kiln runtime] --> S[Native screens · HUD · keybinds]
        R --> L[Lua sandbox]
    end
    K -- "manifest → consent → download<br/>(configuration phase)" --> R
    K <-- "GUI trees, updates, typed channels" --> R
```

## Two ways to build UIs

**1. Server-defined screens** with the Kotlin DSL. The server sends a tree of elements and live updates; the
client keeps focus, scroll position and text input across updates.

```kotlin
player.openScreen(220, 120) {
    panel(width = 220, height = 120) {
        layout = Layout.Column(gap = 6, padding = 10)
        text("Energie: ${machine.energy} %")
        progressBar(machine.energy / 100f, width = 200)
        button("toggle", if (machine.running) "Ausschalten" else "Einschalten") {
            onClick { machine.running = !machine.running; it.screen.refresh() }
        }
    }
}
```

**2. Lua extensions** for anything that needs client-side logic: custom widgets, HUD overlays, keybinds, full
screens. The API extension `ui_kit` provides buttons, tabs, dropdowns, sliders, tables, scroll panels, modals and
item slots.

```lua
local ui, keys, net = kiln.ui, kiln.keys, kiln.net
local hello = net.channel("hello")

keys.bind("hello", { name = "Hallo an Server", default = "J", press = function()
  hello:send({ text = "Hallo vom Client!" })
end })

hello:on(function(msg) ui.toast("Server", msg.text) end)
```

```kotlin
val ext = registerExtension("kiln_example")              // from the plugin's resources
ext.channel<HelloMessage>("hello") { player, msg -> send(player, HelloMessage("Hallo zurück, ${player.name}!")) }
```

## Modules

| Module | Kind | Artifact |
|---|---|---|
| `protocol` | plain Kotlin/JVM, no Minecraft | messages, GUI tree, binary codec, limits |
| `server/api` | Kotlin, `compileOnly` paper-api | API for Paper plugins: screen DSL, keybinds, extensions, typed channels |
| `server/runtime` | Paper plugin `Kiln` | `kiln-server-<v>.jar` (shades `protocol`, `server/api`, Kotlin stdlib; NMS via paperweight) |
| `client` | Fabric mod `kiln` | `kiln-client-<v>.jar` (`protocol` and LuaJ as nested jars) |
| `example/server` | Paper plugin `KilnExample` | demos and Lua extensions, see below |
| `build-logic` | included build | convention plugins (`kiln.kotlin`, `kiln.test`, `kiln.library`, `kiln.shadow`, `kiln.paper-plugin`, `kiln.nms`, `kiln.publish`) |
| `gradle-plugin` | Gradle plugin `dev.kiln` | `paper-plugin.yml` with Kiln dependency, `runServer`, `runClient` for plugins using Kiln |

## Getting started

Requirements: Java 25. Gradle downloads everything else.

```sh
./gradlew build                     # all modules, including tests
./gradlew :example:server:runServer # Paper 26.3 with Kiln and the demos (run/server)
./gradlew :example:server:runClient # Minecraft 26.3 with the Kiln runtime (run/client), offline user "Ammo"
./gradlew :client:runClient         # the same from the client module (client/run)
./gradlew publishToMavenLocal; ./gradlew -p gradle-plugin publishToMavenLocal   # dev.kiln:* artifacts + plugin
```

Connect the dev client to the server. On the first join you see the consent screen, then the loading screen, then
the world. Players need `kiln-client-<v>.jar`, Fabric API and Fabric Language Kotlin.

### Demos

| Command | Key | What it shows |
|---|---|---|
| `/pbdemo` | G | server-defined factory screen with live updates, gauge widget from Lua |
| `/shop` | K | full-screen market as a Lua screen on `ui_kit` (server-rendered twin: `/shopdsl`) |
| `/fabrik` | B | machine control: list, details, toggles, slider, dropdown |
| `/quests` | U | quest log with tabs, progress and reward items |
| `/warps` | V | warp tiles, search, favourites, teleport with cooldown |
| `/bestenliste` | N | sortable leaderboard table and bar chart |
| `/profil` | M | settings form with live preview and unsaved-changes dialog |
| `/kiln give ruby` | | custom item with its own model and texture; right click plays the custom sound `ruby_chime` |
| `/kiln give lifesteal_sword` | | custom enchantment `kiln_content:lifesteal` (bootstrap registry), heals on hit |
| `/kiln font` | | chat message in the bitmap font `kiln_content:runes` (also in a HUD label from Lua) |
| `/kiln effect [ruby_vision\|off]` | | post-processing effect from the extension's resources |
| `/kiln give chair\|table\|bench` | | furniture: model + solid hit boxes (the bench has two and three seats), right click on a block places, right click sits, left click breaks |
| creative inventory | | tab „Kiln Inhalte“ with the custom items; the ruby also appears under „Ingredients“ |

## Using Kiln in your plugin

Apply the Gradle plugin `dev.kiln` (published to `mavenLocal()` for now). It adds `paper-api`, `kiln-server-api`
and the Kotlin stdlib as `compileOnly`, generates `paper-plugin.yml` with Kiln as required dependency
(`load: BEFORE`, `join-classpath: true`) and provides `runServer` (Paper + Kiln + your plugin, `run/server`,
offline mode) and `runClient` (Minecraft + Fabric + Kiln runtime, `run/client`).

```kotlin
// settings.gradle.kts
pluginManagement { repositories { mavenLocal(); gradlePluginPortal() } }

// build.gradle.kts
plugins {
    kotlin("jvm") version "2.4.21"
    id("dev.kiln") version "0.1.0-SNAPSHOT"
}

paper { main = "com.example.MyPlugin" }
kiln { clientUsername = "Dev" }   // optional: version, serverPort, serverJar, clientJar, runDirectory
```

Commands are registered with Paper's command API (`registerCommand` / `LifecycleEvents.COMMANDS`), since
`paper-plugin.yml` has no `commands` section.

## Documentation

- [`docs/design.md`](docs/design.md): architecture, protocol, GUI elements, keybinds
- [`docs/lua-extensions.md`](docs/lua-extensions.md): extension packages, delivery, consent, sandbox, Lua API
- [`docs/ui_kit.md`](docs/ui_kit.md): the `ui_kit` component library

Targets: Minecraft 26.3, Paper `26.3.build.256-stable`, Fabric Loader 0.20.1, Fabric API `0.164.0+26.3`,
Kotlin 2.4.21, LuaJ 3.0.1 (Lua 5.2).
