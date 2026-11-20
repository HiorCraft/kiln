package dev.kiln.gradle

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property

/**
 * `kiln { … }` in a project that applies `dev.kiln`. Every version defaults to the one the plugin was built with.
 */
abstract class KilnExtension {
    /** Kiln version of `kiln-server-api`, the Kiln server plugin and the Kiln client runtime. */
    abstract val version: Property<String>
    abstract val minecraftVersion: Property<String>
    abstract val paperVersion: Property<String>

    /** Port of `runServer` (default 25565). */
    abstract val serverPort: Property<Int>

    /** Offline user name of `runClient` (default "Dev"). */
    abstract val clientUsername: Property<String>

    /** Kiln server plugin jar for `runServer`; unset = `dev.kiln:kiln-server:<version>` from Maven. */
    abstract val serverJar: RegularFileProperty

    /** Kiln client runtime jar for `runClient`; unset = `dev.kiln:kiln-client:<version>` from Maven. */
    abstract val clientJar: RegularFileProperty

    /** Parent of `server/` and `client/` run folders (default `<project>/run`). */
    abstract val runDirectory: DirectoryProperty
}
