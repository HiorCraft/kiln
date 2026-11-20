package dev.kiln.gradle

import net.minecrell.pluginyml.paper.PaperPluginDescription
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import xyz.jpenilla.runpaper.task.RunServer

/**
 * `dev.kiln`: a Paper plugin that uses the Kiln server API.
 *
 * - `compileOnly` paper-api, `dev.kiln:kiln-server-api` and the Kotlin stdlib (Kiln provides it at runtime);
 * - `paper-plugin.yml` (plugin-yml) with Kiln as required server dependency, loaded before and sharing its classpath;
 * - `runServer` (run-paper): Paper with the Kiln plugin and this plugin, offline mode for the dev client;
 * - `runClient`: Minecraft with Fabric and the Kiln runtime (see [ClientRun]).
 */
class KilnGradlePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val versions = KilnVersions.load()
        project.pluginManager.apply("java")
        project.pluginManager.apply("de.eldoria.plugin-yml.paper")
        project.pluginManager.apply("xyz.jpenilla.run-paper")

        val kiln = project.extensions.create("kiln", KilnExtension::class.java).apply {
            version.convention(versions.kiln)
            minecraftVersion.convention(versions.minecraft)
            paperVersion.convention(versions.paper)
            serverPort.convention(25565)
            clientUsername.convention("Dev")
            runDirectory.convention(project.layout.projectDirectory.dir("run"))
        }

        project.afterEvaluate {
            extensions.configure(PaperPluginDescription::class.java) {
                if (apiVersion == null) apiVersion = kiln.minecraftVersion.get()
            }
            if (repositories.isEmpty()) {
                repositories.mavenCentral()
                repositories.maven { setUrl("https://repo.papermc.io/repository/maven-public/"); name = "PaperMC" }
                repositories.mavenLocal()
            }
        }

        project.dependencies.addProvider("compileOnly", kiln.paperVersion.map { "io.papermc.paper:paper-api:$it" })
        project.dependencies.addProvider("compileOnly", kiln.version.map { "dev.kiln:kiln-server-api:$it" })
        project.dependencies.add("compileOnly", "org.jetbrains.kotlin:kotlin-stdlib:${versions.kotlin}")

        project.extensions.configure(PaperPluginDescription::class.java) {
            serverDependencies.register("Kiln") {
                load = PaperPluginDescription.RelativeLoadOrder.BEFORE
                required = true
                joinClasspath = true
            }
        }

        val kilnServer = artifactConfiguration(project, "kilnServer", { kiln.serverJar.isPresent }) {
            "dev.kiln:kiln-server:${kiln.version.get()}"
        }
        project.tasks.named("runServer", RunServer::class.java) {
            minecraftVersion(kiln.minecraftVersion.get())
            pluginJars.from(kiln.serverJar.map { listOf(it) }.orElse(emptyList()))
            pluginJars.from(kilnServer)
            runDirectory.set(kiln.runDirectory.dir("server"))
            jvmArgs("-Dcom.mojang.eula.agree=true")
            args("--port", kiln.serverPort.get().toString())
            val serverProperties = kiln.runDirectory.file("server/server.properties")
            doFirst {
                // the dev client plays offline; written only on first start so local changes stay
                val file = serverProperties.get().asFile
                if (!file.exists()) {
                    file.parentFile.mkdirs()
                    file.writeText("online-mode=false\n")
                }
            }
        }
    }

    /** Resolvable configuration with one default dependency, left empty while [overridden] (a local jar is used). */
    internal fun artifactConfiguration(project: Project, name: String, overridden: () -> Boolean, notation: () -> String): Configuration =
        project.configurations.create(name) {
            isCanBeConsumed = false
            isTransitive = false
            defaultDependencies { if (!overridden()) add(project.dependencies.create(notation())) }
        }
}
