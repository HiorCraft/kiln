package dev.kiln.server.api.enchantment

import net.kyori.adventure.key.Key

/** Receiver of [KilnBootstrap.enchantments]. */
public class EnchantmentDefinitions {
    internal val definitions = LinkedHashMap<Key, EnchantmentDefinition>()

    public fun register(key: Key, init: EnchantmentDefinition.() -> Unit) {
        check(key !in definitions) { "enchantment ${key.asString()} defined twice" }
        definitions[key] = EnchantmentDefinition().apply(init)
    }
}
