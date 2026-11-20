package dev.kiln.client.screen

import dev.kiln.client.ext.ExtensionTextures
import dev.kiln.client.lua.LuaExtension
import dev.kiln.client.lua.WidgetInstance
import dev.kiln.protocol.message.ServerboundMessage
import net.minecraft.resources.Identifier

/** What a [KilnScreen] needs from the runtime. */
interface ScreenHost {
    fun send(message: ServerboundMessage): Boolean

    /** A Lua widget instance for `Custom` type [type], or `null` (placeholder). */
    fun createWidget(type: Identifier): WidgetInstance?

    val textures: ExtensionTextures
}
