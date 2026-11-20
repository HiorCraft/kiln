package dev.kiln.gradle

import java.io.File

/**
 * The small Gradle project `runClient` starts: Loom with Minecraft, Fabric Loader, Fabric API, Fabric Language
 * Kotlin and the Kiln client runtime jar. Kept out of the plugin project so Loom never touches a Paper build.
 */
object ClientRunProject {
    fun settings(): String = """
        pluginManagement {
            repositories {
                maven("https://maven.fabricmc.net/") { name = "Fabric" }
                gradlePluginPortal()
                mavenCentral()
            }
        }

        dependencyResolutionManagement {
            repositories {
                maven("https://maven.fabricmc.net/") { name = "Fabric" }
                mavenCentral()
            }
        }

        rootProject.name = "kiln-client-run"
    """.trimIndent() + "\n"

    fun build(versions: KilnVersions, clientJar: File, username: String, runDir: File, quickPlay: String? = null): String = """
        plugins {
            id("net.fabricmc.fabric-loom") version ${string(versions.loom)}
        }

        java {
            toolchain.languageVersion.set(JavaLanguageVersion.of(25))
        }

        dependencies {
            minecraft(${string("com.mojang:minecraft:${versions.minecraft}")})
            implementation(${string("net.fabricmc:fabric-loader:${versions.fabricLoader}")})
            implementation(${string("net.fabricmc.fabric-api:fabric-api:${versions.fabricApi}")})
            implementation(${string("net.fabricmc:fabric-language-kotlin:${versions.fabricLanguageKotlin}")})
            implementation(files(${string(path(clientJar))}))
        }

        loom {
            runs {
                named("client") {
                    client()
                    runDir(${string(path(runDir))})
                    programArgs("--username", ${string(username)})${quickPlayArgs(quickPlay)}
                }
            }
        }
    """.trimIndent() + "\n"

    /** Extra `programArgs` line that makes Minecraft join [quickPlay] (`host:port`) on start, or nothing. */
    private fun quickPlayArgs(quickPlay: String?): String =
        if (quickPlay == null) "" else "\n                    programArgs(\"--quickPlayMultiplayer\", ${string(quickPlay)})"

    private fun path(file: File): String = file.absolutePath.replace('\\', '/')

    /** Kotlin string literal. */
    private fun string(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\$", "\\\$") + "\""
}
