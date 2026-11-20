package dev.kiln.server

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The generated `paper-plugin.yml` (plugin-yml) must keep Kiln's identity and early load. */
class PluginYmlTest {
    private val yaml: String by lazy {
        val stream = javaClass.classLoader.getResourceAsStream("paper-plugin.yml")
        assertNotNull(stream, "paper-plugin.yml is not on the classpath")
        stream!!.readAllBytes().toString(Charsets.UTF_8)
    }

    @Test
    fun generatedYamlHasStartupLoad() {
        assertTrue(Regex("(?m)^name: '?Kiln'?$").containsMatchIn(yaml), yaml)
        assertTrue(Regex("(?m)^main: '?dev[.]kiln[.]server[.]KilnPlugin'?$").containsMatchIn(yaml), yaml)
        assertTrue(Regex("(?m)^load: '?STARTUP'?$").containsMatchIn(yaml), yaml)
        assertTrue(Regex("(?m)^api-version: [\"']?26[.]3[\"']?$").containsMatchIn(yaml), yaml)
    }

    @Test
    fun legacyPluginYmlIsGone() {
        assertNull(javaClass.classLoader.getResource("plugin.yml"))
    }
}
