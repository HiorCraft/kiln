package dev.kiln.server.runtime

import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin

/**
 * Runs [action] once on the next tick however often it is [request]ed until then; requests while [isNeeded] is
 * `false` or [plugin] is disabled (no task can be scheduled) are ignored.
 */
internal class TickCoalescer(private val plugin: Plugin, private val isNeeded: () -> Boolean, private val action: () -> Unit) {
    private var scheduled = false

    fun request() {
        if (scheduled || !isNeeded() || !plugin.isEnabled) return
        scheduled = true
        Bukkit.getScheduler().runTask(plugin, Runnable {
            scheduled = false
            action()
        })
    }
}
