import java.util.Properties

plugins {
    `kotlin-dsl`
    `maven-publish`
}

// same coordinates as the rest of Kiln (../gradle.properties)
val kilnProperties = Properties().apply { rootDir.resolve("../gradle.properties").reader().use(::load) }
group = kilnProperties.getProperty("group")
version = kilnProperties.getProperty("version")

dependencies {
    implementation(libs.gradle.plugin.yml)
    implementation(libs.gradle.run.paper)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

gradlePlugin {
    plugins {
        register("kiln") {
            id = "dev.kiln"
            implementationClass = "dev.kiln.gradle.KilnGradlePlugin"
            displayName = "Kiln"
            description = "Builds and runs Paper plugins that use the Kiln server API"
        }
    }
}

// versions the plugin defaults to: the ones this Kiln build uses
val versions = mapOf(
    "kiln" to version.toString(),
    "minecraft" to libs.versions.minecraft.get(),
    "paper" to libs.versions.paper.get(),
    "fabricLoader" to libs.versions.fabric.loader.get(),
    "fabricApi" to libs.versions.fabric.api.get(),
    "fabricLanguageKotlin" to libs.versions.fabric.language.kotlin.get(),
    "kotlin" to libs.versions.kotlin.get(),
    "loom" to libs.versions.loom.get(),
)

val generateVersions by tasks.registering {
    val output = layout.buildDirectory.dir("generated/kiln-versions")
    inputs.property("versions", versions)
    outputs.dir(output)
    doLast {
        val file = output.get().file("dev/kiln/gradle/kiln-versions.properties").asFile
        file.parentFile.mkdirs()
        file.writeText(versions.entries.sortedBy { it.key }.joinToString("\n", postfix = "\n") { "${it.key}=${it.value}" })
    }
}

sourceSets.main {
    resources.srcDir(generateVersions)
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    repositories {
        mavenLocal()
        // same repository as the Kiln modules (build-logic kiln.publish)
        maven("https://reposilite.slne.dev/releases") {
            name = "slneReleases"
            credentials {
                username = providers.environmentVariable("SLNE_RELEASES_REPO_USERNAME").orNull
                password = providers.environmentVariable("SLNE_RELEASES_REPO_PASSWORD").orNull
            }
        }
    }
}
