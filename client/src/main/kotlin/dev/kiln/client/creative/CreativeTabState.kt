package dev.kiln.client.creative

import dev.kiln.protocol.CreativeTabData
import dev.kiln.protocol.ItemStackData
import dev.kiln.protocol.ProtocolLimits

/**
 * Creative content of the connected server (`CreativeTabs` messages): own tabs in the order they arrive, mapped onto
 * the placeholder slots 0..[ProtocolLimits.MAX_CREATIVE_TABS]-1 (extra ones ignored), and additions per vanilla tab.
 * Pure; [version] changes with every update so the game rebuilds its tab contents.
 */
class CreativeTabState {
    private val own = ArrayList<CreativeTabData>()
    private val vanilla = LinkedHashMap<String, MutableList<ItemStackData>>()

    var version: Long = 0
        private set

    fun apply(reset: Boolean, tabs: List<CreativeTabData>) {
        if (reset) {
            own.clear()
            vanilla.clear()
        }
        for (tab in tabs) {
            val target = tab.target
            if (target == null) {
                if (own.size < ProtocolLimits.MAX_CREATIVE_TABS) own += tab
            } else {
                vanilla.getOrPut(target) { ArrayList() } += tab.items
            }
        }
        version++
    }

    fun slot(index: Int): CreativeTabData? = own.getOrNull(index)

    fun vanillaAdditions(tab: String): List<ItemStackData> = vanilla[tab].orEmpty()

    fun clear() {
        own.clear()
        vanilla.clear()
        version++
    }
}
