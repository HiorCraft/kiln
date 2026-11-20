# Kiln

Prototype of a Fabric-like platform whose game logic runs on **Paper**: players install the client runtime
(a Fabric mod) once. Everything client-side a server needs (textures, custom widgets, HUD overlays, keybinds,
channels) comes from the server as a **Lua extension package** and runs in a sandbox inside the runtime. There are no
client extensions in Kotlin/Java and no fallbacks: players without the runtime, with a different protocol version,
who decline the extensions or whose extensions fail to load are disconnected with a reason.

Specification: [`docs/design.md`](docs/design.md) (GUIs, keybinds, protocol) and
[`docs/lua-extensions.md`](docs/lua-extensions.md) (extension packages, delivery, sandbox, Lua API v1).

Targets: Minecraft 26.3, Paper `26.3.build.256-stable`, Fabric Loader 0.20.1, Fabric API `0.164.0+26.3`,
Java 25, Kotlin 2.4.21, LuaJ 3.0.1 (Lua 5.2).

## Modules

| Module | Kind | Depends on | Artifact |
|---|---|---|---|
| `protocol` | plain Kotlin/JVM, no Minecraft | – | messages, GUI tree, binary codec, limits |
| `server-api` | Kotlin, `compileOnly` paper-api | – | API for Paper plugins (`Kiln`, screen DSL, keybinds, extensions) |
| `server` | Paper plugin `Kiln` | `protocol`, `server-api` (shaded, + Kotlin stdlib) | `kiln-server-<v>.jar` |
| `client` | Fabric mod `kiln` | `protocol` + LuaJ (both `include`d) | `kiln-client-<v>.jar` |
| `example-server` | Paper plugin `KilnExample` | `server-api` (compileOnly), `depend: [Kiln]` | demo plugin + Lua extensions `kiln_example`, `ui_kit`, `shop` |

## Build

```sh
./gradlew build                     # everything, including tests
./gradlew :client:test              # sandbox, Lua API, JSON, package/zip, trust tests
```

Outputs:
- `server/build/libs/kiln-server-<v>.jar` (shadow jar; install on Paper)
- `example-server/build/libs/kiln-example-server-<v>.jar`
- `client/build/libs/kiln-client-<v>.jar` (protocol and LuaJ as nested jars)

Players need the client jar plus Fabric API and Fabric Language Kotlin.

## Testing locally

```sh
./gradlew :example-server:runServer    # Paper 26.3 with Kiln + example plugin, run dir: run/server
                                       # (-PserverPort=25566 if 25565 is taken)
./gradlew :client:runClient            # Minecraft 26.3 with the kiln runtime, run dir: client/run
```

Connect the dev client to `localhost`. On the first join the client downloads the example extension, asks for
consent (stored in `client/run/config/kiln-trust.json`) and caches the package in
`client/run/kiln/cache/<sha256>.zip`.

## Writing extensions

- **Server plugins**: `compileOnly` the `server-api` artifact (and the Kotlin stdlib), `depend: [Kiln]` in
  `plugin.yml`, then `Kiln.get()` / `player.openScreen(screen(w, h) { ... })`.
- **Client-side behaviour**: an extension package (`extension.json`, `*.lua`, `textures/**.png`, optional
  `lang/<locale>.json`) registered by the plugin; see `docs/lua-extensions.md` and the example in
  `example-server/src/main/resources/extensions/kiln_example/`.
- **Lua screens**: the API extension `ui_kit` (buttons, dropdowns, sliders, scroll panels, modals, item slots, …;
  `docs/ui_kit.md`) and the market `shop` built on it (`/shop`, key K; the same market rendered by the server:
  `/shopdsl`). Five more ui_kit demos, each its own extension with its own key: `fabrik` (B, `/fabrik`),
  `questlog` (U, `/quests`), `warps` (V, `/warps`), `bestenliste` (N, `/bestenliste`), `profil` (M, `/profil`).
