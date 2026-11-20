import java.util.UUID

plugins {
    id("kiln.kotlin")
    id("kiln.test")
    id("kiln.shadow")
    // paper-plugin.yml with Kiln as dependency, compileOnly API, runServer / runClient
    id("dev.kiln")
    // a Velocity proxy in front of two backends (runNetwork*), each with its own set of demo extensions
    alias(libs.plugins.run.velocity)
}

base {
    archivesName.set("kiln-example-server")
}

// inside the Kiln repository the published artifacts come from the local projects
configurations.all {
    resolutionStrategy.dependencySubstitution {
        substitute(module("dev.kiln:kiln-server-api")).using(project(":server:api"))
    }
}

dependencies {
    // pure shop logic tests (market, filters, inventory math); no running server
    testImplementation(libs.kotlin.stdlib)
    // Gson (JSON of the Lua shop channels) and Bukkit interfaces, no running server
    testImplementation(libs.paper.api)
    testImplementation(project(":server:api"))
}

paper {
    name = "KilnExample"
    main = "dev.kiln.example.server.ExamplePlugin"
    // registers the custom enchantment of the content demo
    bootstrapper = "dev.kiln.example.server.ExampleBootstrap"
    description = "Demo plugin for the kiln server API (factory GUI, keybinds, Lua extensions, market)"
}

// the Kiln plugin and runtime jars below come from these projects
evaluationDependsOn(":server:runtime")
evaluationDependsOn(":client")

kiln {
    serverJar.set(project(":server:runtime").tasks.named<Jar>("shadowJar").flatMap { it.archiveFile })
    clientJar.set(project(":client").tasks.named<Jar>("jar").flatMap { it.archiveFile })
    // run/server and run/client in the repository root (run/server keeps online-mode=false for the dev client)
    runDirectory.set(rootProject.layout.projectDirectory.dir("run"))
    clientUsername.set("Ammo")
    // ./gradlew :example:server:runServer -PserverPort=25566 if 25565 is taken
    providers.gradleProperty("serverPort").orNull?.let { serverPort.set(it.toInt()) }
    // ./gradlew :example:server:runClient -PquickPlay joins the dev server right away
    if (providers.gradleProperty("quickPlay").isPresent) quickPlay.set(serverPort.map { "localhost:$it" })
}

tasks.test {
    val jar = tasks.shadowJar.flatMap { it.archiveFile }
    dependsOn(tasks.shadowJar)
    systemProperty("kiln.exampleJar", jar.get().asFile.absolutePath)
}


// ---- proxy network: Velocity (25565) -> survival (25566) and games (25567) ------------------------------------
// Start each in its own terminal: runSurvival, runGames, runVelocity; then runClient -PquickPlay (joins the proxy).
// Switch backends in game with /server survival and /server games.

/** Backends of the dev network: name -> (port, demos of the example plugin). */
val backends = linkedMapOf(
    "survival" to (25566 to listOf("shop", "content", "factory")),
    "games" to (25567 to listOf("warps", "quests", "bestenliste")),
)
val networkDir = rootProject.layout.projectDirectory.dir("run/network")

/** The secret Velocity and the backends share for modern forwarding (created once). */
fun forwardingSecret(): String {
    val file = networkDir.file("velocity/forwarding.secret").asFile
    if (!file.exists()) {
        file.parentFile.mkdirs()
        file.writeText(UUID.randomUUID().toString().replace("-", ""))
    }
    return file.readText().trim()
}

/** Sets `proxies.velocity` in paper-global.yml (Paper fills in everything else on start). */
fun enableVelocityForwarding(file: File, secret: String) {
    file.parentFile.mkdirs()
    if (!file.exists()) {
        file.writeText(listOf("proxies:", "  velocity:", "    enabled: true", "    online-mode: false", "    secret: '$secret'").joinToString("\n", postfix = "\n"))
        return
    }
    val out = ArrayList<String>()
    var inVelocity = false
    for (line in file.readLines()) {
        val trimmed = line.trim()
        if (line.startsWith("  ") && !line.startsWith("    ")) inVelocity = trimmed == "velocity:"
        out += when {
            inVelocity && trimmed.startsWith("enabled:") -> "    enabled: true"
            inVelocity && trimmed.startsWith("online-mode:") -> "    online-mode: false"
            inVelocity && trimmed.startsWith("secret:") -> "    secret: '$secret'"
            else -> line
        }
    }
    file.writeText(out.joinToString("\n", postfix = "\n"))
}

for ((name, backend) in backends) {
    val (port, demos) = backend
    tasks.register<xyz.jpenilla.runpaper.task.RunServer>("run${name.replaceFirstChar { it.uppercase() }}") {
        group = "kiln network"
        description = "Paper backend '$name' (port $port, demos $demos) behind runVelocity"
        minecraftVersion(kiln.minecraftVersion.get())
        pluginJars.from(kiln.serverJar)
        pluginJars.from(tasks.shadowJar.flatMap { it.archiveFile })
        val dir = networkDir.dir(name)
        runDirectory.set(dir)
        jvmArgs("-Dcom.mojang.eula.agree=true")
        args("--port", port.toString())
        doFirst {
            val root = dir.asFile
            root.resolve("server.properties").takeIf { !it.exists() }?.apply {
                parentFile.mkdirs()
                writeText(listOf("online-mode=false", "white-list=false", "server-port=$port", "motd=kiln $name").joinToString("\n", postfix = "\n"))
            }
            enableVelocityForwarding(root.resolve("config/paper-global.yml"), forwardingSecret())
            root.resolve("plugins/KilnExample/config.yml").takeIf { !it.exists() }?.apply {
                parentFile.mkdirs()
                writeText("demos: [${demos.joinToString()}]\n")
            }
        }
    }
}

tasks.runVelocity {
    group = "kiln network"
    // the example jar is a Paper plugin; Velocity would try (and fail) to load it
    pluginJars.setFrom()
    velocityVersion("4.3.1")
    val dir = networkDir.dir("velocity")
    runDirectory.set(dir)
    doFirst {
        forwardingSecret()
        val servers = backends.entries.map { (n, b) -> "$n = \"127.0.0.1:${b.first}\"" }
        dir.asFile.resolve("velocity.toml").takeIf { !it.exists() }?.writeText(
            (listOf(
                "config-version = \"2.7\"",
                "bind = \"0.0.0.0:25565\"",
                "motd = \"<aqua>kiln dev network\"",
                "online-mode = false",
                "player-info-forwarding-mode = \"modern\"",
                "forwarding-secret-file = \"forwarding.secret\"",
                "",
                "[servers]",
            ) + servers + listOf(
                "try = [\"${backends.keys.first()}\"]",
                "",
                "[forced-hosts]",
            )).joinToString("\n", postfix = "\n"),
        )
    }
}
