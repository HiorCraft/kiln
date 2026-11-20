package dev.kiln.client.screen

import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import net.minecraft.client.Minecraft
import net.minecraft.core.RegistryAccess
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ComponentSerialization
import org.slf4j.LoggerFactory

/** Parses the server's component JSON; malformed JSON falls back to the raw string as literal text. */
object Components {
    private val logger = LoggerFactory.getLogger("kiln")

    fun parse(json: String?): Component? {
        if (json == null) return null
        if (json.isEmpty()) return Component.empty()
        return try {
            val element = JsonParser.parseString(json)
            val registries: RegistryAccess = Minecraft.getInstance().connection?.registryAccess() ?: RegistryAccess.EMPTY
            ComponentSerialization.CODEC.parse(registries.createSerializationContext(JsonOps.INSTANCE), element)
                .resultOrPartial { logger.debug("Invalid component JSON from server: {}", it) }
                .orElseGet { Component.literal(json) }
        } catch (e: Exception) {
            logger.debug("Invalid component JSON from server", e)
            Component.literal(json)
        }
    }
}
