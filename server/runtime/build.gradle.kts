import net.minecrell.pluginyml.bukkit.BukkitPluginDescription

plugins {
    id("kiln.publish")
    id("kiln.paper-plugin")
    id("kiln.nms")
    id("kiln.test")
}

base {
    archivesName.set("kiln-server")
}

dependencies {
    // bundled into the plugin jar
    implementation(project(":protocol"))
    implementation(project(":server:api"))
    implementation(libs.kotlin.stdlib)
}

paper {
    name = "Kiln"
    main = "dev.kiln.server.runtime.KilnPlugin"
    bootstrapper = "dev.kiln.server.runtime.KilnBootstrapper"
    load = BukkitPluginDescription.PluginLoadOrder.STARTUP
    description = "Server runtime of kiln (server-described GUIs, keybinds and Lua extensions for Fabric clients)"
}

// versions Kiln checks at start (server internals: exact Minecraft version; Kotlin of an embedding host)
tasks.processResources {
    val versions = mapOf("minecraft" to libs.versions.minecraft.get(), "kotlin" to libs.versions.kotlin.get())
    inputs.properties(versions)
    filesMatching("dev/kiln/server/runtime/kiln-build.properties") { expand(versions) }
}

// ---- kiln-server-embedded: the same runtime for shading into another plugin (KilnEmbedded) -----------------------
// No relocation (the API must stay dev.kiln.*), no Kotlin (the host provides it), no plugin descriptor.
val embeddedJar = tasks.register<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("embeddedJar") {
    group = "build"
    description = "Kiln runtime, API and protocol without Kotlin, for shading into a host plugin"
    archiveBaseName.set("kiln-server-embedded")
    archiveClassifier.set("")
    from(sourceSets.main.map { it.output })
    configurations = listOf(project.configurations.runtimeClasspath.get())
    dependencies {
        exclude(dependency("org.jetbrains.kotlin:.*"))
        exclude(dependency("org.jetbrains:annotations"))
    }
    exclude("paper-plugin.yml", "config.yml", "META-INF/kotlin-stdlib*.kotlin_module")
    mergeServiceFiles()
}

tasks.assemble {
    dependsOn(embeddedJar)
}

publishing {
    publications {
        create<MavenPublication>("embedded") {
            artifactId = "kiln-server-embedded"
            artifact(embeddedJar)
        }
    }
}
