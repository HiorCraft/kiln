plugins {
    id("kiln.kotlin")
    id("kiln.test")
    id("kiln.shadow")
    alias(libs.plugins.run.paper)
}

base {
    archivesName.set("kiln-example-server")
}

dependencies {
    compileOnly(libs.paper.api)
    // provided at runtime by the Kiln plugin (depend: [Kiln]), including the Kotlin stdlib
    compileOnly(project(":server-api"))
    compileOnly(libs.kotlin.stdlib)

    // pure shop logic tests (market, filters, inventory math); no running server
    testImplementation(libs.kotlin.stdlib)
    // Gson (JSON of the Lua shop channels) and Bukkit interfaces, no running server
    testImplementation(libs.paper.api)
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}


tasks.runServer {
    minecraftVersion(libs.versions.minecraft.get())
    // run-paper adds this project's shadowJar automatically; add the Kiln runtime plugin as well
    pluginJars.from(project(":server").tasks.named<Jar>("shadowJar").flatMap { it.archiveFile })
    runDirectory.set(rootProject.layout.projectDirectory.dir("run/server"))
    jvmArgs("-Dcom.mojang.eula.agree=true")
    // ./gradlew :example-server:runServer -PserverPort=25566 if 25565 is taken
    providers.gradleProperty("serverPort").orNull?.let { args("--port", it) }
}
