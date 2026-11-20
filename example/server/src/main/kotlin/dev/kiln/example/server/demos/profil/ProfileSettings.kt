package dev.kiln.example.server.demos.profil

import com.google.gson.JsonObject
import dev.kiln.example.server.demos.DemoResult
import dev.kiln.example.server.demos.RequestGuard
import dev.kiln.example.server.shop.lua.RequestFields
import java.util.UUID

/*
 * Einstellungen/Profil (extension `profil`): per-player settings in memory. The client edits a local copy and saves
 * everything at once; the server validates every field (errors per field) and applies the nickname color.
 *
 * C->S  save {req, settings: {nickname, nickColor, status, chatFilter, volume, notifications, privateMessages, compactChat}}
 */

class ProfileSettings(private val guard: RequestGuard = RequestGuard()) {
    private val saved = HashMap<UUID, Settings>()

    fun settings(player: UUID): Settings = saved[player] ?: DEFAULTS

    fun state(player: UUID, name: String) = ProfileState(
        name, settings(player), DEFAULTS,
        COLORS.map { ColorDto(it.id, it.label, it.rgb or 0xFF000000) },
        FILTERS.map { OptionDto(it.first, it.second) },
        NICK_MIN, NICK_MAX, STATUS_MAX, VOLUME_STEP,
    )

    fun save(player: UUID, msg: JsonObject?, now: Long): SaveOutcome {
        var applied: Settings? = null
        val result = guard.run(player, msg, "save", now) { req ->
            val s = msg?.get("settings")?.takeIf { it.isJsonObject }?.asJsonObject ?: return@run DemoResult.fail(req, "save", DemoResult.INVALID)
            val nickname = RequestFields.string(s, "nickname", 64)
            val nickColor = RequestFields.string(s, "nickColor", 32)
            val status = RequestFields.string(s, "status", 256)
            val chatFilter = RequestFields.string(s, "chatFilter", 16)
            val volume = RequestFields.int(s, "volume", Int.MIN_VALUE, Int.MAX_VALUE)
            val notifications = RequestFields.boolean(s, "notifications")
            val privateMessages = RequestFields.boolean(s, "privateMessages")
            val compactChat = RequestFields.boolean(s, "compactChat")
            if (nickname == null || nickColor == null || status == null || chatFilter == null || volume == null ||
                notifications == null || privateMessages == null || compactChat == null
            ) {
                return@run DemoResult.fail(req, "save", DemoResult.INVALID)
            }
            val nick = nickname.trim()
            val statusText = status.trim()
            val fields = LinkedHashMap<String, String>()
            validateNickname(nick)?.let { fields["nickname"] = it }
            if (COLORS.none { it.id == nickColor }) fields["nickColor"] = "Unbekannte Farbe."
            validateStatus(statusText)?.let { fields["status"] = it }
            if (FILTERS.none { it.first == chatFilter }) fields["chatFilter"] = "Unbekannte Filterstufe."
            if (volume !in 0..100 || volume % VOLUME_STEP != 0) fields["volume"] = "0–100 in Schritten von $VOLUME_STEP."
            if (fields.isNotEmpty()) return@run DemoResult.fail(req, "save", "Bitte prüfe die markierten Felder.", fields)
            val settings = Settings(nick, nickColor, statusText, chatFilter, volume, notifications, privateMessages, compactChat)
            saved[player] = settings
            applied = settings
            DemoResult.ok(req, "save", "Einstellungen gespeichert.")
        }
        return SaveOutcome(result, applied)
    }

    fun forget(player: UUID) {
        saved.remove(player)
        guard.forget(player)
    }

    companion object {
        const val NICK_MIN = 3
        const val NICK_MAX = 16
        const val STATUS_MAX = 40
        const val VOLUME_STEP = 5
        private val NICK_CHARS = Regex("[A-Za-z0-9_]+")

        val DEFAULTS = Settings()

        val COLORS = listOf(
            NickColor("white", "Weiß", 0xFFFFFF),
            NickColor("gold", "Gold", 0xFFAA00),
            NickColor("yellow", "Gelb", 0xFFFF55),
            NickColor("green", "Grün", 0x55FF55),
            NickColor("aqua", "Türkis", 0x55FFFF),
            NickColor("blue", "Blau", 0x5555FF),
            NickColor("light_purple", "Rosa", 0xFF55FF),
            NickColor("red", "Rot", 0xFF5555),
            NickColor("gray", "Grau", 0xAAAAAA),
        )

        val FILTERS = listOf("OFF" to "Aus", "MILD" to "Mild (Schimpfwörter)", "STRICT" to "Streng (auch Links)")

        /** Same rules as the client form; empty = use the player name. */
        fun validateNickname(nick: String): String? = when {
            nick.isEmpty() -> null
            nick.length < NICK_MIN -> "Mindestens $NICK_MIN Zeichen (oder leer lassen)."
            nick.length > NICK_MAX -> "Höchstens $NICK_MAX Zeichen."
            !NICK_CHARS.matches(nick) -> "Nur A–Z, a–z, 0–9 und _."
            else -> null
        }

        fun validateStatus(status: String): String? = when {
            status.length > STATUS_MAX -> "Höchstens $STATUS_MAX Zeichen."
            status.any { it.isISOControl() || it == '§' } -> "Keine Steuer- oder Formatzeichen."
            else -> null
        }
    }
}
