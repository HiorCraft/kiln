package dev.kiln.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AssetRulesTest {
    @Test
    fun assetRulesRejectForeignNamespace() {
        assertNotNull(AssetRules.problem("demo", "assets/minecraft/models/x.json", 10))
        assertNotNull(AssetRules.problem("demo", "assets/other/models/x.json", 10))
    }

    @Test
    fun assetRulesAllowOwnModel() {
        assertNull(AssetRules.problem("demo", "assets/demo/models/item/ruby.json", 10))
        assertNull(AssetRules.problem("demo", "assets/demo/sounds/ruby_chime.ogg", 10))
        assertNull(AssetRules.problem("demo", "assets/demo/font/runes.ttf", 10))
        assertNull(AssetRules.problem("demo", "assets/demo/textures/item/ruby.png.mcmeta", 10))
        assertNull(AssetRules.problem("demo", "assets/demo/shaders/post/wobble.fsh", 10))
    }

    @Test
    fun assetRulesRejectUnknownSuffix() {
        assertNotNull(AssetRules.problem("demo", "assets/demo/x.lua", 10))
        assertNotNull(AssetRules.problem("demo", "assets/demo/x.class", 10))
    }

    @Test
    fun assetRulesRejectLargeOgg() {
        assertNull(AssetRules.problem("demo", "assets/demo/sounds/a.ogg", ProtocolLimits.MAX_SOUND_BYTES))
        assertNotNull(AssetRules.problem("demo", "assets/demo/sounds/a.ogg", ProtocolLimits.MAX_SOUND_BYTES + 1))
        assertNotNull(AssetRules.problem("demo", "assets/demo/font/a.otf", ProtocolLimits.MAX_FONT_BYTES + 1))
    }

    @Test
    fun jsonProblemFindsKilnReference() {
        assertNotNull(AssetRules.jsonProblem("assets/demo/font/x.json", """{"providers":[{"type":"bitmap","file":"kiln:font/x.png"}]}"""))
    }

    @Test
    fun jsonProblemAllowsMinecraftParent() {
        assertNull(AssetRules.jsonProblem("assets/demo/models/item/a.json", """{"parent":"minecraft:item/generated","textures":{"layer0":"demo:item/a"}}"""))
    }

    @Test
    fun jsonProblemRejectsInvalidJson() {
        assertNotNull(AssetRules.jsonProblem("assets/demo/models/item/a.json", """{"parent": """))
    }

    @Test
    fun langKeysNeedOwnPrefix() {
        assertTrue(AssetRules.langKeyAllowed("demo", "demo.enchantment.lifesteal"))
        assertFalse(AssetRules.langKeyAllowed("demo", "gui.done"))
        assertFalse(AssetRules.langKeyAllowed("demo", "demox.a"))
    }

    @Test
    fun permissionsCanonicalOrderEndsWithResources() {
        assertEquals(ExtensionPermissions.RESOURCES, ExtensionPermissions.ALL.last())
        assertEquals("resources", ExtensionPermissions.RESOURCES)
        assertEquals(8, PROTOCOL_VERSION)
    }
}
