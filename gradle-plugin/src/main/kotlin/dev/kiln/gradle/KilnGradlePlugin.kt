package dev.kiln.gradle

import net.minecrell.pluginyml.paper.PaperPluginDescription
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.bundling.Jar
import xyz.jpenilla.runpaper.task.RunServer

/**
 * `dev.kiln`: a Paper plugin that uses the Kiln server API.
 *
 * - `compileOnly` paper-api, `dev.kiln:kiln-server-api` and the Kotlin stdlib (Kiln provides it at runtime);
 * - repositories are the project's job (mavenCentral, PaperMC and where Kiln is published, e.g. mavenLocal), so
 *   builds that declare repositories only in settings (`RepositoriesMode.FAIL_ON_PROJECT_REPOS`) work;
 * - a `shadowJar` never contains the Kotlin stdlib (Kiln provides it);
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
        }

        project.dependencies.addProvider("compileOnly", kiln.paperVersion.map { "io.papermc.paper:paper-api:$it" })
        project.dependencies.addProvider("compileOnly", kiln.version.map { "dev.kiln:kiln-server-api:$it" })
        project.dependencies.add("compileOnly", "org.jetbrains.kotlin:kotlin-stdlib:${versions.kotlin}")

        // Kiln provides the Kotlin stdlib at runtime: a shaded copy would clash with it
        project.tasks.withType(Jar::class.java).matching { it.name == "shadowJar" }.configureEach {
            exclude("kotlin/**", "META-INF/kotlin-stdlib*.kotlin_module")
        }

        project.extensions.configure(PaperPluginDescription::class.java) {
            serverDependencies.register(KILN_PLUGIN) { loadBeforeAndJoinClasspath() }
            // bootstrappers use the Kiln API too (e.g. KilnBootstrap for custom enchantments)
            bootstrapDependencies.register(KILN_PLUGIN) { loadBeforeAndJoinClasspath() }
        }

        val kilnServer = localJarOrArtifact(project, "kilnServer", kiln.serverJar) { "dev.kiln:kiln-server:${kiln.version.get()}" }
        project.tasks.named("runServer", RunServer::class.java) {
            minecraftVersion(kiln.minecraftVersion.get())
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

        ClientRun.register(project, kiln, versions)
    }

    /** Kiln is a required server dependency, loaded before this plugin and sharing its classpath. */
    private fun PaperPluginDescription.DependencyDefinition.loadBeforeAndJoinClasspath() {
        load = PaperPluginDescription.RelativeLoadOrder.BEFORE
        required = true
        joinClasspath = true
    }

    companion object {
        private const val KILN_PLUGIN = "Kiln"

        /**
         * The [localJar] when set, otherwise the artifact [notation] resolved through the configuration [name] (which
         * stays empty while a local jar is used).
         */
        internal fun localJarOrArtifact(project: Project, name: String, localJar: RegularFileProperty, notation: () -> String): FileCollection {
            val artifact = project.configurations.create(name) {
                isCanBeConsumed = false
                isTransitive = false
                defaultDependencies { if (!localJar.isPresent) add(project.dependencies.create(notation())) }
            }
            return project.files(localJar.map { listOf(it) }.orElse(emptyList()), artifact)
        }
    }
}
