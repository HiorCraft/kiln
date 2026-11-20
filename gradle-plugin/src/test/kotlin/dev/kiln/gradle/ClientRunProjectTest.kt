package dev.kiln.gradle

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class ClientRunProjectTest {
    private val versions = KilnVersions(
        kiln = "0.1.0", minecraft = "26.3", paper = "26.3.build.256-stable", fabricLoader = "0.20.1",
        fabricApi = "0.164.0+26.3", fabricLanguageKotlin = "1.14.2+kotlin.2.4.21", kotlin = "2.4.21", loom = "1.19.4",
    )

    private fun build(jar: File = File("/tmp/kiln-client.jar"), user: String = "Dev", runDir: File = File("/tmp/run/client")) =
        ClientRunProject.build(versions, jar, user, runDir)

    @Test
    fun buildScriptPinsVersions() {
        val script = build()
        assertTrue(script.contains("id(\"net.fabricmc.fabric-loom\") version \"1.19.4\""), script)
        assertTrue(script.contains("com.mojang:minecraft:26.3"), script)
        assertTrue(script.contains("net.fabricmc:fabric-loader:0.20.1"), script)
        assertTrue(script.contains("fabric-api:0.164.0+26.3"), script)
        assertTrue(script.contains("fabric-language-kotlin:1.14.2+kotlin.2.4.21"), script)
    }

    @Test
    fun windowsPathsAreForwardSlashed() {
        val script = build(jar = File("C:\\a b\\kiln.jar"))
        assertTrue(script.contains("C:/a b/kiln.jar\")"), script)
        assertTrue(!script.contains("\\\\"), script)
    }

    @Test
    fun usernameIsEscaped() {
        val script = build(user = "a\"b\$c")
        assertTrue(script.contains("\"a\\\"b\\\$c\""), script)
    }

    @Test
    fun settingsDeclaresFabricRepository() {
        val settings = ClientRunProject.settings()
        assertTrue(settings.contains("https://maven.fabricmc.net/"), settings)
        assertTrue(settings.contains("gradlePluginPortal()"), settings)
    }
}
