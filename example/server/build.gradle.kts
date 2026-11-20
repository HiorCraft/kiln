plugins {
    id("kiln.kotlin")
    id("kiln.test")
    id("kiln.shadow")
    // paper-plugin.yml with Kiln as dependency, compileOnly API, runServer / runClient
    id("dev.kiln")
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
