package dev.kiln.client.creative

import dev.kiln.client.mixin.CreativeModeTabsAccessor
import dev.kiln.client.screen.Components
import dev.kiln.client.screen.ItemStacks
import dev.kiln.protocol.CreativeTabData
import dev.kiln.protocol.ItemStackData
import dev.kiln.protocol.ProtocolLimits
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * The server's creative content in the game: [ProtocolLimits.MAX_CREATIVE_TABS] placeholder tabs registered at
 * startup (`kiln:server_<n>`, hidden by vanilla while empty) whose title, icon and items come from [state], plus
 * additions to vanilla tabs through Fabric's `MODIFY_OUTPUT_ALL`. After every update the tab contents are rebuilt.
 */
object KilnCreativeTabs {
    val state = CreativeTabState()
    private val tabs = ArrayList<CreativeModeTab>()

    /** Called once during client initialisation (the tab registry is still open then). */
    fun register() {
        for (index in 0 until ProtocolLimits.MAX_CREATIVE_TABS) {
            val tab = FabricCreativeModeTab.builder()
                .title(Component.literal("Kiln"))
                .icon { ItemStack(Items.BARRIER) }
                .displayItems { _, output -> stacks(state.slot(index)?.items.orEmpty()).forEach(output::accept) }
                .build()
            Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, Identifier.fromNamespaceAndPath("kiln", "server_$index"), tab)
            tabs += tab
        }
        CreativeModeTabEvents.MODIFY_OUTPUT_ALL.register { tab, output ->
            val key = BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab) ?: return@register
            for (stack in stacks(state.vanillaAdditions(key.toString()))) output.accept(stack)
        }
    }

    fun apply(reset: Boolean, data: List<CreativeTabData>) {
        state.apply(reset, data)
        rebuild()
    }

    fun clear() {
        state.clear()
        rebuild()
    }

    /** The server tab [tab] stands for, or `null` if it is no Kiln tab or currently unused. */
    private fun slotOf(tab: CreativeModeTab): CreativeTabData? {
        val index = tabs.indexOf(tab)
        return if (index < 0) null else state.slot(index)
    }

    /** Title for Kiln tabs (`CreativeModeTabMixin`), else `null`. */
    @JvmStatic
    fun title(tab: CreativeModeTab): Component? {
        val index = tabs.indexOf(tab)
        if (index < 0) return null
        if (titleVersion != state.version) {
            titles.clear()
            titleVersion = state.version
        }
        return titles.getOrPut(index) { state.slot(index)?.let { Components.parse(it.title) } ?: Component.literal("Kiln") }
            .takeIf { state.slot(index) != null }
    }

    private val titles = HashMap<Int, Component>()
    private var titleVersion = -1L

    /** Icon for Kiln tabs (`CreativeModeTabMixin`), else `null`. */
    @JvmStatic
    fun icon(tab: CreativeModeTab): ItemStack? {
        val index = tabs.indexOf(tab)
        if (index < 0) return null
        // decoded once per update, not every frame
        if (iconVersion != state.version) {
            icons.clear()
            iconVersion = state.version
        }
        return icons.getOrPut(index) {
            val data = state.slot(index)
            val icon = data?.icon ?: data?.items?.firstOrNull()
            icon?.let { stacks(listOf(it)).firstOrNull() } ?: ItemStack(Items.BARRIER)
        }
    }

    private val icons = HashMap<Int, ItemStack>()
    private var iconVersion = -1L

    private fun stacks(items: List<ItemStackData>): List<ItemStack> =
        items.map { ItemStacks.decode(it) }.filterNot { it.failed }.map { it.stack.copyWithCount(it.count.coerceIn(1, it.stack.maxStackSize)) }

    /** Forgets the cached tab parameters so the creative screen rebuilds every tab the next time it opens. */
    private fun rebuild() {
        CreativeModeTabsAccessor.`kiln$setCachedParameters`(null)
    }
}
