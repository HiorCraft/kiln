package dev.kiln.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.GradleBuild
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * `prepareKilnClient` writes [ClientRunProject] to `build/kiln/client`; `runClient` runs that project's `runClient`
 * (Minecraft with Fabric and the Kiln runtime, offline user [KilnExtension.clientUsername], game directory
 * `<runDirectory>/client`).
 */
internal object ClientRun {
    fun register(project: Project, kiln: KilnExtension, versions: KilnVersions) {
        val kilnClient = KilnGradlePlugin.artifactConfiguration(project, "kilnClient", { kiln.clientJar.isPresent }) {
            "dev.kiln:kiln-client:${kiln.version.get()}"
        }
        val prepare = project.tasks.register("prepareKilnClient", PrepareKilnClient::class.java) {
            group = "kiln"
            description = "Writes the Gradle project that runs the Minecraft client with the Kiln runtime."
            clientJars.from(kiln.clientJar.map { listOf(it) }.orElse(emptyList()))
            clientJars.from(kilnClient)
            username.set(kiln.clientUsername)
            quickPlay.set(kiln.quickPlay)
            gameDirectory.set(kiln.runDirectory.dir("client"))
            outputDirectory.set(project.layout.buildDirectory.dir("kiln/client"))
            this.versions = versions.copy(kiln = kiln.version.get(), minecraft = kiln.minecraftVersion.get())
        }
        project.tasks.register("runClient", GradleBuild::class.java) {
            group = "kiln"
            description = "Starts Minecraft with Fabric and the Kiln runtime (connect to runServer on localhost)."
            dependsOn(prepare)
            dir = prepare.get().outputDirectory.get().asFile
            tasks = listOf("runClient")
        }
    }
}

@DisableCachingByDefault(because = "writes two small build scripts")
abstract class PrepareKilnClient : DefaultTask() {
    /** The Kiln client runtime jar (exactly one file). */
    // the generated script refers to the jar by absolute path
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    abstract val clientJars: ConfigurableFileCollection

    @get:Input
    abstract val username: Property<String>

    @get:Input
    @get:org.gradle.api.tasks.Optional
    abstract val quickPlay: Property<String>

    @get:Internal
    abstract val gameDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Internal
    internal lateinit var versions: KilnVersions

    @get:Input
    val versionText: String get() = versions.toString() + gameDirectory.get().asFile.absolutePath

    @TaskAction
    fun write() {
        val jar = clientJars.files.singleOrNull()
            ?: error("Expected exactly one Kiln client jar, got ${clientJars.files} (set kiln.clientJar or kiln.version)")
        val out = outputDirectory.get().asFile
        out.mkdirs()
        out.resolve("settings.gradle.kts").writeText(ClientRunProject.settings())
        out.resolve("build.gradle.kts").writeText(ClientRunProject.build(versions, jar, username.get(), gameDirectory.get().asFile, quickPlay.orNull))
    }
}
