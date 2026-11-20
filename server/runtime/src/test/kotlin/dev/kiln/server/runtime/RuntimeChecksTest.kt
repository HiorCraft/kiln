package dev.kiln.server.runtime

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RuntimeChecksTest {
    private val build = BuildInfo(minecraft = "26.3", kotlin = "2.4.21")

    @Test
    fun `matching server and kotlin pass`() {
        assertEquals(emptyList<String>(), RuntimeChecks.problems(build, serverMinecraft = "26.3", runtimeKotlin = KotlinVersion(2, 4, 21)))
        // a newer patch of the same Kotlin minor is fine, a newer minor too
        assertEquals(emptyList<String>(), RuntimeChecks.problems(build, "26.3", KotlinVersion(2, 5, 0)))
    }

    @Test
    fun `another minecraft version or an older kotlin are refused with a clear reason`() {
        val mc = RuntimeChecks.problems(build, "26.4", KotlinVersion(2, 4, 21))
        assertTrue(mc.single().contains("26.3") && mc.single().contains("26.4"), mc.toString())
        val kt = RuntimeChecks.problems(build, "26.3", KotlinVersion(2, 3, 0))
        assertTrue(kt.single().contains("2.4") && kt.single().contains("2.3.0"), kt.toString())
    }

    @Test
    fun `build info is generated into the jar`() {
        val info = BuildInfo.load()
        assertTrue(info.minecraft.isNotBlank() && !info.minecraft.contains("$"), info.toString())
        assertTrue(info.kotlin.startsWith("2."), info.toString())
    }

    @Test
    fun `settings come from config with defaults and bounds`() {
        val config = YamlConfiguration.loadConfiguration(
            """
            debug: true
            rate-limit-per-second: 0
            hello-timeout-seconds: 9999
            max-handshakes-per-address: 2
            """.trimIndent().reader(),
        )
        val s = KilnSettings.from(config)
        assertTrue(s.debug)
        assertEquals(1, s.rateLimitPerSecond)
        assertEquals(120_000, s.timeouts.helloMillis)
        assertEquals(2, s.maxHandshakesPerAddress)
        assertEquals(KilnSettings().extensionMessagesPerSecond, s.extensionMessagesPerSecond)
        assertEquals(KilnSettings(), KilnSettings.from(YamlConfiguration()))
    }
}
