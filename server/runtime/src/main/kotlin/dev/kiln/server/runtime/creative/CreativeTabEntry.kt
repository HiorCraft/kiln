package dev.kiln.server.runtime.creative

import net.kyori.adventure.key.Key

/** An own creative tab ([target] `null`) or an addition to the vanilla tab [target]; [items] are produced lazily. */
class CreativeTabEntry<T>(
    val owner: String,
    val id: Key,
    val target: Key?,
    val title: Any?,
    val icon: (() -> T)?,
    val items: () -> List<T>,
)
