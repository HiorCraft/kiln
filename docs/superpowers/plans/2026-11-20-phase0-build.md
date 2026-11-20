# Phase 0 – Build-Logic, paper-plugin.yml, NMS, Kiln-Gradle-Plugin – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Aufgeräumter Build mit Convention-Plugins, `paper-plugin.yml` per plugin-yml, NMS im `server` und ein Gradle-Plugin `dev.kiln`, mit dem Kiln-Projekte `runServer`/`runClient` bekommen.

**Architecture:** Zwei Included Builds: `build-logic` (Precompiled Script Plugins, nur intern) und `gradle-plugin` (öffentliches Plugin `dev.kiln`, Kotlin-Klassen-Plugin). `example-server` wird erster Nutzer von `dev.kiln`; im Repo zeigen Datei-Properties auf lokale Jars statt Maven-Artefakte.

**Tech Stack:** Gradle (Wrapper im Repo), Kotlin DSL, `de.eldoria.plugin-yml.paper` 0.9.0, `io.papermc.paperweight.userdev` 2.0.0-beta.24, `xyz.jpenilla.run-paper` 3.1.0, `net.fabricmc.fabric-loom` 1.19.4, Gradle TestKit.

**Spec:** `docs/superpowers/specs/2026-11-20-kiln-content-design.md` (Abschnitt „0. Build“)

## Global Constraints

- Java 25 Toolchain, `jvmTarget 25`, `options.release = 25`, UTF-8.
- Minecraft 26.3, Paper `26.3.build.256-stable`, Fabric Loader 0.20.1, Fabric API `0.164.0+26.3`, Kotlin 2.4.21.
- Versionen nur in `gradle/libs.versions.toml`; `build-logic` und `gradle-plugin` lesen denselben Katalog.
- `kotlin.stdlib.default.dependency=false` bleibt.
- Commits englisch, Conventional Commits, Trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- `./gradlew build` grün nach jedem Task; CI-Workflow (`.github/workflows/build.yml`) lädt weiter die drei Jars hoch.

## Review Focus

- `paper-plugin.yml` von Kiln verliert `load: STARTUP` → Extensions-Ordner/Handshake-Listener zu spät registriert. Erwartet: generierte YAML enthält `load: STARTUP` (Test in Task 2).
- Abhängige Plugins sehen Kiln-API-Klassen nicht (Paper-Plugins isolieren Classloader). Erwartet: `join-classpath: true` für Kiln (Test in Task 3).
- Kotlin-Stdlib doppelt/inkompatibel im Server: Kiln schattiert Kotlin, Example darf es nicht. Erwartet: Example-Jar enthält kein `kotlin/` (Test in Task 5).
- `runClient` in einem Fremdprojekt ohne Loom-Repos → Auflösungsfehler. Erwartet: generiertes Projekt deklariert Fabric- und Paper-Maven selbst (Test in Task 4).
- Pfade mit Leerzeichen/Backslashes (Windows) im generierten Client-Projekt. Erwartet: Pfade werden als `file("…")` mit `/` und escapten Anführungszeichen geschrieben (Test in Task 4).

---

### Task 1: `build-logic` mit Convention-Plugins

**Files:**
- Create: `build-logic/settings.gradle.kts`, `build-logic/build.gradle.kts`
- Create: `build-logic/src/main/kotlin/kiln.kotlin.gradle.kts`, `kiln.test.gradle.kts`, `kiln.library.gradle.kts`, `kiln.shadow.gradle.kts`, `kiln.publish.gradle.kts`
- Modify: `settings.gradle.kts` (`pluginManagement { includeBuild("build-logic") }`), `build.gradle.kts` (nur noch `group/version`, keine `subprojects {}`), `gradle/libs.versions.toml` (Plugin-Artefakte als Libraries für `build-logic`)
- Modify: `protocol/build.gradle.kts`, `server-api/build.gradle.kts`, `client/build.gradle.kts`

**Interfaces:**
- Produces: Plugin-Ids `kiln.kotlin`, `kiln.test`, `kiln.library`, `kiln.shadow`, `kiln.publish`. Zugriff auf den Katalog in Script-Plugins über `val libs = the<VersionCatalogsExtension>().named("libs")`.
- `kiln.library` = `kiln.kotlin` + `java-library` + `kotlin { explicitApi() }`.
- `kiln.shadow` = ShadowJar `archiveClassifier=""`, `filesMatching("META-INF/*.kotlin_module") { INCLUDE }`, `mergeServiceFiles()`, `jar.archiveClassifier="plain"`, `assemble.dependsOn(shadowJar)`.
- `kiln.publish` = `maven-publish`, Publikation `maven` aus `components["java"]` (bzw. vom Nutzer überschrieben), Repository nur `mavenLocal()`; Property `kilnPublishUrl` (optional) fügt ein Remote-Repo hinzu.

- [ ] **Step 1:** `build-logic/settings.gradle.kts` mit `dependencyResolutionManagement { repositories { gradlePluginPortal(); mavenCentral(); maven("https://maven.fabricmc.net/"); maven("https://repo.papermc.io/repository/maven-public/") }; versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } } }`. `build-logic/build.gradle.kts`: `kotlin-dsl`, Abhängigkeiten auf Kotlin-Gradle-Plugin, Shadow, plugin-yml, run-paper, paperweight-userdev (Marker-Artefakte `<id>:<id>.gradle.plugin:<version>`), Einträge neu im Katalog unter `[libraries]` (`gradle-kotlin`, `gradle-shadow`, `gradle-plugin-yml`, `gradle-run-paper`, `gradle-paperweight`).
- [ ] **Step 2:** Die fünf Script-Plugins mit den Inhalten aus „Interfaces“ anlegen; `kiln.kotlin` übernimmt den bisherigen `subprojects`-Block aus `build.gradle.kts` (Toolchain 25, `jvmTarget`, `withSourcesJar`, `JavaCompile.release=25`, UTF-8); `kiln.test` den JUnit-Block (`platform(junit-bom)`, `junit-jupiter`, `junit-platform-launcher`, `useJUnitPlatform()`).
- [ ] **Step 3:** `protocol` → `plugins { id("kiln.library"); id("kiln.test") }`; `server-api` → `kiln.library`; `client` → `kiln.kotlin`, `kiln.test`, Loom bleibt per `alias`. Root-`build.gradle.kts` reduzieren (Plugins `apply false` nur noch, wo nötig, z. B. Loom).
- [ ] **Step 4:** Run `./gradlew build` → BUILD SUCCESSFUL, gleiche Testanzahl wie vorher (`./gradlew test --rerun` vorher/nachher vergleichen, Summen in `build/test-results`).
- [ ] **Step 5:** Commit `build: extract convention plugins into build-logic`.

### Task 2: `server` mit paperweight-userdev und generierter `paper-plugin.yml`

**Files:**
- Create: `build-logic/src/main/kotlin/kiln.paper-plugin.gradle.kts`
- Modify: `server/build.gradle.kts`; Delete: `server/src/main/resources/plugin.yml`
- Test: `server/src/test/kotlin/dev/kiln/server/PluginYmlTest.kt`

**Interfaces:**
- Produces: `kiln.paper-plugin` = `kiln.kotlin` + `kiln.shadow` + `de.eldoria.plugin-yml.paper`; setzt `paper { version = project.version; apiVersion = "26.3" }`.
- `server`: `paperweight.userdev`, `dependencies { paperweight.paperDevBundle(libs.versions.paper) }` statt `compileOnly(paper-api)`; `paperweight.reobfArtifactConfiguration = MOJANG_PRODUCTION`; Manifest-Attribut `paperweight-mappings-namespace: mojang` (Default bei MOJANG_PRODUCTION).
- `paper { name = "Kiln"; main = "dev.kiln.server.KilnPlugin"; load = STARTUP; description = <bisheriger Text> }`.

- [ ] **Step 1: Failing test** `PluginYmlTest.generatedYamlHasStartupLoad`: liest `build/resources/main/paper-plugin.yml` (Pfad per System-Property `kiln.pluginYml`, gesetzt in `tasks.test { systemProperty(...) ; dependsOn("generatePaperPluginDescription") }`) und prüft die Zeilen `name: Kiln`, `main: dev.kiln.server.KilnPlugin`, `load: STARTUP`, `api-version: '26.3'` (Quoting wie plugin-yml es schreibt, per `contains("26.3")`).
- [ ] **Step 2:** `./gradlew :server:test --tests '*PluginYmlTest*'` → FAIL (Datei fehlt).
- [ ] **Step 3:** Convention + `server/build.gradle.kts` umstellen; Tests behalten `testImplementation(paper-api)`-Ersatz: der Dev-Bundle-Classpath steht im Test ohnehin zur Verfügung.
- [ ] **Step 4:** `./gradlew :server:build` → PASS; `unzip -l server/build/libs/kiln-server-*.jar | grep paper-plugin.yml` zeigt die Datei, kein `plugin.yml`.
- [ ] **Step 5:** Commit `build(server): use paperweight-userdev and generated paper-plugin.yml`.

### Task 3: Gradle-Plugin `dev.kiln` – Extension, Abhängigkeiten, `paper-plugin.yml`, `runServer`

**Files:**
- Create: `gradle-plugin/settings.gradle.kts`, `gradle-plugin/build.gradle.kts` (`java-gradle-plugin`, `kotlin-dsl`, `kiln.publish` nicht verfügbar → eigenes `maven-publish`)
- Create: `gradle-plugin/src/main/kotlin/dev/kiln/gradle/KilnGradlePlugin.kt`, `KilnExtension.kt`
- Create: `gradle-plugin/src/main/resources/dev/kiln/gradle/kiln-versions.properties` (per `processResources` aus dem Katalog + Projektversion befüllt: `kiln`, `minecraft`, `paper`, `fabricLoader`, `fabricApi`, `fabricLanguageKotlin`, `loom`)
- Modify: `settings.gradle.kts` (`pluginManagement { includeBuild("gradle-plugin") }`)
- Test: `gradle-plugin/src/test/kotlin/dev/kiln/gradle/KilnGradlePluginTest.kt` (TestKit)

**Interfaces:**
- Plugin-Id `dev.kiln`, Klasse `dev.kiln.gradle.KilnGradlePlugin : Plugin<Project>`.
- `abstract class KilnExtension { val version: Property<String>; val minecraftVersion: Property<String>; val paperVersion: Property<String>; val serverPort: Property<Int> (25565); val clientUsername: Property<String> ("Dev"); val serverJar: RegularFileProperty; val clientJar: RegularFileProperty; val runDirectory: DirectoryProperty (projectDir/run) }` – Defaults aus `kiln-versions.properties`.
- Wendet an: `de.eldoria.plugin-yml.paper`, `xyz.jpenilla.run-paper`. Fügt `compileOnly("io.papermc.paper:paper-api:<paper>")`, `compileOnly("dev.kiln:kiln-server-api:<kiln>")`, `compileOnly(kotlin-stdlib)` hinzu (Repos: `mavenCentral`, PaperMC, `mavenLocal` werden ergänzt, falls `repositories` leer ist).
- Trägt in `paper.serverDependencies` `register("Kiln") { load = BEFORE; required = true; joinClasspath = true }` ein.
- Konfigurationen `kilnServer` (Default-Dependency `dev.kiln:kiln-server:<kiln>`), `kilnClient` (Default `dev.kiln:kiln-client:<kiln>`); `serverJar`/`clientJar` überschreiben sie, wenn gesetzt.
- `runServer`: `minecraftVersion`, `pluginJars.from(serverJar oder kilnServer)`, `runDirectory = runDirectory/server`, `jvmArgs("-Dcom.mojang.eula.agree=true")`, `args("--port", serverPort)`; vor dem Start schreibt eine `doFirst`-Aktion `server.properties` mit `online-mode=false`, falls die Datei noch nicht existiert.

- [ ] **Step 1: Failing tests** (TestKit, temporäres Projekt mit `plugins { id("dev.kiln") }`, `paper { main = "x.Main" }`):
  - `generatesKilnServerDependency`: `generatePaperPluginDescription` → YAML enthält `Kiln:` mit `load: BEFORE`, `required: true`, `join-classpath: true`.
  - `addsCompileOnlyApi`: Task `dependencies --configuration compileOnly` listet `dev.kiln:kiln-server-api:` und `io.papermc.paper:paper-api:`.
  - `runServerUsesConfiguredServerJar`: mit `kiln { serverJar = file("kiln.jar") }` liefert ein Hilfstask `printKilnRun` (nur in Tests per Property `kiln.debugTasks=true` registriert) den Jar-Pfad und den Run-Ordner `run/server`.
- [ ] **Step 2:** `./gradlew -p gradle-plugin test` → FAIL.
- [ ] **Step 3:** Implementieren.
- [ ] **Step 4:** `./gradlew -p gradle-plugin test` → PASS. Root `./gradlew build` führt `gradle-plugin`-Tests mit aus (`tasks.build { dependsOn(gradle.includedBuild("gradle-plugin").task(":check")) }` im Root).
- [ ] **Step 5:** Commit `feat(gradle-plugin): add dev.kiln plugin with runServer and paper-plugin.yml defaults`.

### Task 4: `runClient` über generiertes Loom-Projekt

**Files:**
- Create: `gradle-plugin/src/main/kotlin/dev/kiln/gradle/ClientRunProject.kt`, `RunKilnClient.kt`
- Test: `gradle-plugin/src/test/kotlin/dev/kiln/gradle/ClientRunProjectTest.kt` (reiner Unit-Test der Textgenerierung) + TestKit-Fall in `KilnGradlePluginTest`

**Interfaces:**
- `object ClientRunProject { fun settings(): String; fun build(versions: KilnVersions, clientJar: File, username: String, runDir: File): String }` – erzeugt `settings.gradle.kts` (pluginManagement-Repos: Fabric, Gradle Plugin Portal) und `build.gradle.kts` (`id("net.fabricmc.fabric-loom") version <loom>`, `minecraft("com.mojang:minecraft:<mc>")`, `implementation(fabric-loader)`, `implementation(fabric-api)`, `implementation(fabric-language-kotlin)`, `implementation(files("<clientJar>"))` mit Pfaden als `/`-Strings, `loom.runs.named("client") { runDir(<runDir>); programArgs("--username", <name>) }`).
- Task `prepareKilnClient` (schreibt nach `build/kiln/client/`, kopiert Gradle-Wrapper-Properties nicht – nutzt `GradleBuild`), Task `runClient` (Typ `GradleBuild`, `dir = build/kiln/client`, `tasks = ["runClient"]`, hängt an `prepareKilnClient` und am Build des Client-Jars, wenn `clientJar` von einem Task stammt).
- `data class KilnVersions(kiln, minecraft, paper, fabricLoader, fabricApi, fabricLanguageKotlin, loom)` aus `kiln-versions.properties`.

- [ ] **Step 1: Failing tests** `ClientRunProjectTest`:
  - `buildScriptPinsVersions`: Ausgabe enthält `version "1.19.4"`, `com.mojang:minecraft:26.3`, `fabric-api:0.164.0+26.3`.
  - `windowsPathsAreForwardSlashed`: `File("C:\\a b\\kiln.jar")` erscheint als `files("C:/a b/kiln.jar")`.
  - `usernameIsEscaped`: Name `a"b` erscheint als `"a\"b"`.
  - TestKit `runClientIsWiredToGeneratedProject`: `prepareKilnClient` erzeugt `build/kiln/client/build.gradle.kts`.
- [ ] **Step 2:** Tests → FAIL. **Step 3:** Implementieren. **Step 4:** Tests → PASS.
- [ ] **Step 5:** Commit `feat(gradle-plugin): add runClient via generated Loom project`.

### Task 5: `example-server` auf `dev.kiln`, `paper-plugin.yml` und Brigadier-Befehle

**Files:**
- Modify: `example-server/build.gradle.kts` (nur `id("dev.kiln")`, `id("kiln.kotlin")`, `id("kiln.test")`, `id("kiln.shadow")`, `paper { main; description }`, `kiln { serverJar = project(":server").tasks.shadowJar…; clientJar = project(":client").tasks.jar…; runDirectory = rootDir/run }`, `serverPort` aus Property `serverPort`)
- Delete: `example-server/src/main/resources/plugin.yml`
- Create: `example-server/src/main/kotlin/dev/kiln/example/server/ExampleCommands.kt`
- Modify: `ExamplePlugin.kt` (Befehle über `lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { it.registrar().register(...) }`)
- Test: `example-server/src/test/kotlin/dev/kiln/example/server/PluginJarTest.kt`

**Interfaces:**
- Befehle wie bisher: `pbdemo [open|close|status]`, `shop [markt|verkaufen|angebote|balance]`, `shopdsl [balance]`, `fabrik`, `quests [open|test]`, `warps`, `bestenliste`, `profil` – gleiche Namen, gleiche Unterargumente als Literale, Beschreibungen aus der alten `plugin.yml`. Die bestehenden `CommandExecutor`-Rümpfe werden aus den Brigadier-Knoten aufgerufen (Logik bleibt, nur die Registrierung wandert).
- `run/server` bleibt Run-Ordner; `:example-server:runServer` und neu `:example-server:runClient` funktionieren; `:client:runClient` bleibt unverändert.

- [ ] **Step 1: Failing test** `PluginJarTest.jarHasNoKotlinAndHasPaperPluginYml`: öffnet `build/libs/kiln-example-server-<v>.jar` (Pfad per System-Property, `test.dependsOn(shadowJar)`), erwartet `paper-plugin.yml` mit `Kiln:` und `join-classpath: true`, keinen Eintrag `plugin.yml`, keinen Eintrag mit Präfix `kotlin/`.
- [ ] **Step 2:** → FAIL. **Step 3:** Umstellen. **Step 4:** `./gradlew build` → PASS.
- [ ] **Step 5: Manuell:** `./gradlew :example-server:runServer` startet, Log zeigt `Enabling Kiln` vor `Enabling KilnExample`, `/shop` ist als Befehl vorhanden (Konsole `help shop`). `./gradlew :example-server:runClient` startet den Client mit Kiln; Join auf `localhost` funktioniert.
- [ ] **Step 6:** Commit `build(example-server): use dev.kiln plugin, paper-plugin.yml and brigadier commands`.

### Task 6: Veröffentlichung, CI, Doku

**Files:**
- Modify: `protocol`, `server-api`, `server`, `client` Build-Skripte (`id("kiln.publish")`; `server` publiziert das Shadow-Jar, `client` das Loom-`jar`), `gradle-plugin/build.gradle.kts` (`maven-publish`)
- Modify: `.github/workflows/build.yml` (keine inhaltliche Änderung nötig außer, falls Pfade sich ändern), `README.md` (Abschnitt „Getting started“ + neuer Abschnitt „Using Kiln in your plugin“ mit `plugins { id("dev.kiln") }`-Beispiel), `docs/design.md` (Modultabelle um `build-logic`, `gradle-plugin`)

- [ ] **Step 1:** `./gradlew publishToMavenLocal` → `~/.m2/repository/dev/kiln/{kiln-protocol,kiln-server-api,kiln-server,kiln-client}/0.1.0-SNAPSHOT/` und `dev/kiln/dev.kiln.gradle.plugin/` existieren.
- [ ] **Step 2:** `./gradlew build` → PASS.
- [ ] **Step 3:** Commit `build: publish kiln artifacts and document the gradle plugin`.
