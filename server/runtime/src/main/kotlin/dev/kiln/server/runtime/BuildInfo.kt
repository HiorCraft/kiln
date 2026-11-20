package dev.kiln.server.runtime

import java.util.Properties

/** Versions this Kiln build was made for (generated into `kiln-build.properties`). */
data class BuildInfo(val minecraft: String, val kotlin: String) {
    companion object {
        fun load(): BuildInfo {
            val props = Properties()
            BuildInfo::class.java.getResourceAsStream("/dev/kiln/server/runtime/kiln-build.properties")?.use(props::load)
            return BuildInfo(props.getProperty("minecraft", "?"), props.getProperty("kotlin", "?"))
        }
    }
}
