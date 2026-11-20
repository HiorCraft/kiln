package dev.kiln.example.server

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.zip.ZipFile

/** The example plugin jar: a Paper plugin depending on Kiln, which provides Kotlin at runtime. */
class PluginJarTest {
    @Test
    fun jarHasNoKotlinAndHasPaperPluginYml() {
        val path = System.getProperty("kiln.exampleJar")
        assertNotNull(path, "system property kiln.exampleJar not set")
        ZipFile(path).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            assertFalse("plugin.yml" in names, "legacy plugin.yml still in the jar")
            assertTrue(names.none { it.startsWith("kotlin/") }, "Kotlin stdlib must not be shaded")
            val yaml = zip.getInputStream(zip.getEntry("paper-plugin.yml") ?: error("paper-plugin.yml missing")).readAllBytes().toString(Charsets.UTF_8)
            assertTrue(yaml.contains("Kiln:"), yaml)
            assertTrue(yaml.contains("join-classpath: true"), yaml)
            assertTrue(yaml.contains("dev.kiln.example.server.ExamplePlugin"), yaml)
            // the plugin name is also its data folder (plugins/KilnExample)
            assertTrue(Regex("(?m)^name: KilnExample$").containsMatchIn(yaml), yaml)
        }
    }
}
