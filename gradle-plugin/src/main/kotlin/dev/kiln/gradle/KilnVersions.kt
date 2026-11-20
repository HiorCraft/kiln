package dev.kiln.gradle

import java.util.Properties

/** Versions this plugin was built with (`kiln-versions.properties`, generated from the Kiln version catalog). */
data class KilnVersions(
    val kiln: String,
    val minecraft: String,
    val paper: String,
    val fabricLoader: String,
    val fabricApi: String,
    val fabricLanguageKotlin: String,
    val kotlin: String,
    val loom: String,
) {
    companion object {
        fun load(): KilnVersions {
            val properties = Properties()
            KilnVersions::class.java.getResourceAsStream("kiln-versions.properties")
                .use { requireNotNull(it) { "kiln-versions.properties missing from the Kiln Gradle plugin" }.reader().use(properties::load) }
            fun get(key: String) = requireNotNull(properties.getProperty(key)) { "kiln-versions.properties has no '$key'" }
            return KilnVersions(
                kiln = get("kiln"),
                minecraft = get("minecraft"),
                paper = get("paper"),
                fabricLoader = get("fabricLoader"),
                fabricApi = get("fabricApi"),
                fabricLanguageKotlin = get("fabricLanguageKotlin"),
                kotlin = get("kotlin"),
                loom = get("loom"),
            )
        }
    }
}
