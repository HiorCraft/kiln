plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
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
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

tasks.shadowJar {
    archiveClassifier.set("")
    // let Shadow's KotlinModuleMetadataTransformer see every *.kotlin_module instead of dropping duplicates
    filesMatching("META-INF/*.kotlin_module") { duplicatesStrategy = DuplicatesStrategy.INCLUDE }
}

tasks.jar {
    archiveClassifier.set("plain")
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
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
