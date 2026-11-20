package dev.kiln.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

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
    @get:Optional
    abstract val quickPlay: Property<String>

    @get:Internal
    abstract val gameDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    /** Loader/API versions this plugin was built with (constant). */
    @get:Internal
    internal lateinit var baseVersions: KilnVersions

    @get:Input
    abstract val kilnVersion: Property<String>

    @get:Input
    abstract val minecraftVersion: Property<String>

    @get:Input
    val inputText: String get() = baseVersions.toString() + gameDirectory.get().asFile.absolutePath

    @TaskAction
    fun write() {
        val jar = clientJars.files.singleOrNull()
            ?: error("Expected exactly one Kiln client jar, got ${clientJars.files} (set kiln.clientJar or kiln.version)")
        val out = outputDirectory.get().asFile
        out.mkdirs()
        out.resolve("settings.gradle.kts").writeText(ClientRunProject.settings())
        val versions = baseVersions.copy(kiln = kilnVersion.get(), minecraft = minecraftVersion.get())
        out.resolve("build.gradle.kts").writeText(ClientRunProject.build(versions, jar, username.get(), gameDirectory.get().asFile, quickPlay.orNull))
    }
}
