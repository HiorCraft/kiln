package dev.kiln.gradle

import org.gradle.api.Project
import org.gradle.api.tasks.GradleBuild

/**
 * `prepareKilnClient` writes [ClientRunProject] to `build/kiln/client`; `runClient` runs that project's `runClient`
 * (Minecraft with Fabric and the Kiln runtime, offline user [KilnExtension.clientUsername], game directory
 * `<runDirectory>/client`).
 */
internal object ClientRun {
    fun register(project: Project, kiln: KilnExtension, versions: KilnVersions) {
        val kilnClient = KilnGradlePlugin.localJarOrArtifact(project, "kilnClient", kiln.clientJar) {
            "dev.kiln:kiln-client:${kiln.version.get()}"
        }
        val prepare = project.tasks.register("prepareKilnClient", PrepareKilnClient::class.java) {
            group = "kiln"
            description = "Writes the Gradle project that runs the Minecraft client with the Kiln runtime."
            clientJars.from(kilnClient)
            username.set(kiln.clientUsername)
            quickPlay.set(kiln.quickPlay)
            gameDirectory.set(kiln.runDirectory.dir("client"))
            outputDirectory.set(project.layout.buildDirectory.dir("kiln/client"))
            baseVersions = versions
            kilnVersion.set(kiln.version)
            minecraftVersion.set(kiln.minecraftVersion)
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
