package dev.kiln.gradle

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class KilnGradlePluginTest {
    @TempDir
    lateinit var dir: File

    private fun project(extra: String = "") {
        dir.resolve("settings.gradle.kts").writeText("rootProject.name = \"demo\"\n")
        dir.resolve("build.gradle.kts").writeText(
            """
            plugins { id("dev.kiln") }
            version = "1.0"
            paper { main = "x.Main" }
            $extra
            """.trimIndent(),
        )
    }

    private fun run(vararg args: String) =
        GradleRunner.create().withProjectDir(dir).withPluginClasspath().withArguments(*args, "--stacktrace").build()

    @Test
    fun generatesKilnServerDependency() {
        project()
        run("generatePaperPluginDescription")
        val yaml = dir.resolve("build").walkTopDown().first { it.name == "paper-plugin.yml" }.readText()
        val kiln = yaml.substringAfter("Kiln:")
        assertTrue(yaml.contains("Kiln:"), yaml)
        assertTrue(kiln.contains("load: BEFORE"), yaml)
        assertTrue(kiln.contains("required: true"), yaml)
        assertTrue(kiln.contains("join-classpath: true"), yaml)
    }

    @Test
    fun addsCompileOnlyApi() {
        project()
        val out = run("dependencies", "--configuration", "compileOnly").output
        assertTrue(out.contains("dev.kiln:kiln-server-api:"), out)
        assertTrue(out.contains("io.papermc.paper:paper-api:"), out)
    }

    @Test
    fun runServerUsesConfiguredServerJar() {
        project(
            """
            kiln { serverJar.set(file("kiln.jar")) }
            tasks.register("printKilnRun") {
                val run = tasks.named<xyz.jpenilla.runpaper.task.RunServer>("runServer")
                doLast {
                    println("JARS=" + run.get().pluginJars.files.map { it.name })
                    println("DIR=" + run.get().runDirectory.get().asFile.invariantSeparatorsPath)
                }
            }
            """.trimIndent(),
        )
        dir.resolve("kiln.jar").writeText("")
        val out = run("printKilnRun").output
        assertTrue(out.contains("kiln.jar"), out)
        assertTrue(Regex("DIR=.*/run/server").containsMatchIn(out), out)
    }
}
