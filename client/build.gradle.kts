plugins {
    id("kiln.publish")
    id("kiln.kotlin")
    id("kiln.test")
    alias(libs.plugins.loom)
}

base {
    archivesName.set("kiln-client")
}

dependencies {
    minecraft(libs.minecraft)
    implementation(libs.fabric.loader)
    implementation(libs.fabric.api)
    implementation(libs.fabric.language.kotlin)

    // bundled as nested jars (jar-in-jar)
    implementation(project(":protocol"))
    include(project(":protocol"))

    // Lua runtime for server-provided extensions (pure Java, bundled)
    implementation(libs.luaj.jse)
    include(libs.luaj.jse)
}

loom {
    mods {
        register("kiln") { sourceSet(sourceSets.main.get()) }
    }
    runs {
        // ./gradlew :client:runClient starts Minecraft with the runtime mod (game directory client/run)
        named("client") {
            client()
            configName = "Kiln Client"
            runDir("run")
            // fixed offline name so the dev player keeps op, keybinds and consent across launches
            programArgs("--username", "Ammo")
            ideConfigGenerated(true)
        }
        named("server") {
            ideConfigGenerated(false)
        }
    }
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("fabric.mod.json") { expand(props) }
}
