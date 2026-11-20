package dev.kiln.server.runtime.enchantment

import net.kyori.adventure.key.Key
import org.bukkit.plugin.Plugin

/** A handler [call] of [plugin] for the enchantment [key]. */
internal class EnchantmentHandler<F>(val plugin: Plugin, val key: Key, val call: F)
