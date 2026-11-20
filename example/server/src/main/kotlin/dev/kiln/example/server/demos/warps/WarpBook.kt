package dev.kiln.example.server.demos.warps

import com.google.gson.JsonObject
import dev.kiln.example.server.demos.DemoResult
import dev.kiln.example.server.demos.RequestGuard
import dev.kiln.example.server.shop.lua.RequestFields
import java.util.UUID

/*
 * Warps (extension `warps`): public server warps plus up to MAX_OWN own warps per player, favorites, a teleport
 * cooldown enforced here. Everything in memory.
 *
 * C->S  teleport {req, warp}
 * C->S  favorite {req, warp, on}
 * C->S  create   {req, name, icon}     at the player's current position
 * C->S  delete   {req, warp}           own warps only
 */

class WarpBook(
    private val publicWarps: List<Warp>,
    val cooldownMillis: Long = 10_000,
    private val guard: RequestGuard = RequestGuard(),
) {
    private val own = HashMap<UUID, MutableList<Warp>>()
    private val favorites = HashMap<UUID, MutableSet<String>>()
    private val lastTeleport = HashMap<UUID, Long>()
    private var nextId = 1

    fun warps(player: UUID): List<Warp> = publicWarps + own[player].orEmpty()

    private fun warp(player: UUID, msg: JsonObject?): Warp? = RequestFields.string(msg, "warp", 32)?.let { id -> warps(player).find { it.id == id } }

    fun cooldownLeft(player: UUID, now: Long): Long = lastTeleport[player]?.let { (it + cooldownMillis - now).coerceAtLeast(0) } ?: 0

    fun state(player: UUID, now: Long): WarpState {
        val fav = favorites[player].orEmpty()
        return WarpState(
            cooldownMillis, cooldownLeft(player, now), MAX_OWN, NAME_MIN, NAME_MAX, ICONS.map { IconDto(it.first, it.second) },
            warps(player).map {
                val l = it.location
                WarpDto(it.id, it.name, it.icon, it.description, l.world, l.x.toInt(), l.y.toInt(), l.z.toInt(), it.owner == player, it.id in fav)
            },
        )
    }

    fun teleport(player: UUID, msg: JsonObject?, now: Long): TeleportOutcome {
        var outcome: TeleportOutcome? = null
        val result = guard.run(player, msg, "teleport", now) { req ->
            val warp = warp(player, msg) ?: return@run DemoResult.fail(req, "teleport", UNKNOWN_WARP)
            val left = cooldownLeft(player, now)
            if (left > 0) return@run DemoResult.fail(req, "teleport", "Noch ${ceilSeconds(left)} s Abklingzeit.")
            lastTeleport[player] = now
            val ok = DemoResult.ok(req, "teleport", "Teleportiert: ${warp.name}")
            outcome = TeleportOutcome(ok, warp.location, warp)
            ok
        }
        return outcome ?: TeleportOutcome(result)
    }

    /** The teleport could not be executed (world missing): no cooldown. */
    fun cancelCooldown(player: UUID) {
        lastTeleport.remove(player)
    }

    fun favorite(player: UUID, msg: JsonObject?, now: Long): DemoResult = guard.run(player, msg, "favorite", now) { req ->
        val warp = warp(player, msg) ?: return@run DemoResult.fail(req, "favorite", UNKNOWN_WARP)
        val on = RequestFields.boolean(msg, "on") ?: return@run DemoResult.fail(req, "favorite", DemoResult.INVALID)
        val set = favorites.getOrPut(player) { HashSet() }
        if (on) set += warp.id else set -= warp.id
        DemoResult.ok(req, "favorite")
    }

    fun create(player: UUID, msg: JsonObject?, location: WarpLocation, now: Long): DemoResult = guard.run(player, msg, "create", now) { req ->
        val rawName = RequestFields.string(msg, "name", 64) ?: return@run DemoResult.fail(req, "create", DemoResult.INVALID)
        val icon = RequestFields.string(msg, "icon", 64) ?: return@run DemoResult.fail(req, "create", DemoResult.INVALID)
        val fields = LinkedHashMap<String, String>()
        val name = rawName.trim()
        validateName(name, warps(player).map { it.name })?.let { fields["name"] = it }
        if (ICONS.none { it.first == icon }) fields["icon"] = "Unbekanntes Symbol."
        val list = own.getOrPut(player) { ArrayList() }
        if (list.size >= MAX_OWN) return@run DemoResult.fail(req, "create", "Du hast schon $MAX_OWN eigene Warps – lösche zuerst einen.")
        if (fields.isNotEmpty()) return@run DemoResult.fail(req, "create", fields.values.first(), fields)
        list += Warp("own${nextId++}", name, icon, "Eigener Warp", location.copy(surface = false), player)
        DemoResult.ok(req, "create", "Warp angelegt: $name")
    }

    fun delete(player: UUID, msg: JsonObject?, now: Long): DemoResult = guard.run(player, msg, "delete", now) { req ->
        val warp = warp(player, msg) ?: return@run DemoResult.fail(req, "delete", UNKNOWN_WARP)
        if (warp.owner != player) return@run DemoResult.fail(req, "delete", "Öffentliche Warps kannst du nicht löschen.")
        own[player]?.remove(warp)
        favorites[player]?.remove(warp.id)
        DemoResult.ok(req, "delete", "Warp gelöscht: ${warp.name}")
    }

    private fun ceilSeconds(millis: Long): Long = (millis + 999) / 1000

    fun forget(player: UUID) {
        own.remove(player)
        favorites.remove(player)
        lastTeleport.remove(player)
        guard.forget(player)
    }

    companion object {
        const val MAX_OWN = 8
        const val NAME_MIN = 3
        const val NAME_MAX = 24
        private const val UNKNOWN_WARP = "Diesen Warp gibt es nicht (mehr)."
        private val NAME_CHARS = Regex("[A-Za-z0-9ÄÖÜäöüß _-]+")

        val ICONS = listOf(
            "minecraft:red_bed" to "Bett",
            "minecraft:chest" to "Truhe",
            "minecraft:crafting_table" to "Werkbank",
            "minecraft:wheat" to "Weizen",
            "minecraft:iron_pickaxe" to "Spitzhacke",
            "minecraft:diamond" to "Diamant",
            "minecraft:oak_sapling" to "Setzling",
            "minecraft:water_bucket" to "Wassereimer",
            "minecraft:beacon" to "Leuchtfeuer",
            "minecraft:compass" to "Kompass",
        )

        /** Error text for a (trimmed) warp name or `null` if valid. Same rules as the client form. */
        fun validateName(name: String, existing: List<String>): String? = when {
            name.length < NAME_MIN -> "Mindestens $NAME_MIN Zeichen."
            name.length > NAME_MAX -> "Höchstens $NAME_MAX Zeichen."
            !NAME_CHARS.matches(name) -> "Nur Buchstaben, Ziffern, Leerzeichen, _ und -."
            "  " in name -> "Keine doppelten Leerzeichen."
            existing.any { it.equals(name, ignoreCase = true) } -> "Diesen Namen gibt es schon."
            else -> null
        }
    }
}
