package dev.kiln.example.server.demos

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.kiln.example.server.demos.bestenliste.Leaderboard
import dev.kiln.example.server.demos.bestenliste.StatCategory
import dev.kiln.example.server.demos.fabrik.FactoryControl
import dev.kiln.example.server.demos.fabrik.MachineStatus
import dev.kiln.example.server.demos.profil.ProfileSettings
import dev.kiln.example.server.demos.questlog.ObjectiveKind
import dev.kiln.example.server.demos.questlog.QuestBook
import dev.kiln.example.server.demos.questlog.QuestStatus
import dev.kiln.example.server.demos.warps.Warp
import dev.kiln.example.server.demos.warps.WarpBook
import dev.kiln.example.server.demos.warps.WarpLocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/** Validation and handlers of the five ui_kit demo GUIs (pure controllers, no server). */
class DemoControllersTest {
    private val p = UUID.randomUUID()
    private val other = UUID.randomUUID()
    private var now = 1_000_000L

    private fun json(text: String): JsonObject = JsonParser.parseString(text).asJsonObject

    /** Requests far apart in time, so the rate limit never interferes. */
    private fun t(): Long {
        now += 10_000
        return now
    }

    @Test
    fun `rate limit - six at once, then four per second, per player`() {
        val guard = RequestGuard()
        val results = (1..8).map { guard.run(p, json("""{"req":$it}"""), "x", 0) { req -> DemoResult.ok(req, "x") } }
        assertEquals(6, results.count { it.ok })
        assertEquals(DemoResult.TOO_MANY, results.last().error)
        assertEquals(8L, results.last().req, "the answer keeps the request id")
        assertTrue(guard.run(other, json("{}"), "x", 0) { DemoResult.ok(it, "x") }.ok, "other players are not affected")
        assertTrue(guard.run(p, json("{}"), "x", 250) { DemoResult.ok(it, "x") }.ok, "refilled after 250 ms")
    }

    // ---- fabrik ---------------------------------------------------------------------------------------------

    @Test
    fun `fabrik - power goes to the highest priority first`() {
        val f = FactoryControl(supply = 50)
        val machines = f.machines(p)
        machines.forEach { it.running = false }
        val (a, b) = machines
        a.running = true; a.priority = 2 // needs 30
        b.running = true; b.priority = 9 // needs 20
        f.tick(p)
        assertEquals(1.0, b.power)
        assertEquals(1.0, a.power, "50 = 20 + 30")
        b.recipe = b.type.recipes[1] // gold: 40
        f.tick(p)
        assertEquals(1.0, b.power)
        assertEquals(10.0 / 30.0, a.power, 1e-9)
        assertEquals(MachineStatus.LOW_POWER, a.status)
        assertEquals(MachineStatus.OFF, machines[2].status)
        val produced = b.produced
        repeat(6) { f.tick(p) }
        assertEquals(produced + 1, b.produced, "6 s per gold ingot at full power")
        assertEquals(50, f.state(p).used)
    }

    @Test
    fun `fabrik - set validates machine, fields and recipe`() {
        val f = FactoryControl()
        fun set(body: String) = f.set(p, json(body), t())
        assertEquals(DemoResult.INVALID, set("""{"req":1}""").error)
        assertEquals(DemoResult.INVALID, set("""{"req":1,"machine":1}""").error, "nothing to change")
        assertEquals(DemoResult.INVALID, set("""{"req":1,"machine":"1","running":true}""").error)
        assertEquals("Diese Maschine gibt es nicht.", set("""{"req":1,"machine":99,"running":true}""").error)
        assertEquals(DemoResult.INVALID, set("""{"req":1,"machine":1,"running":"yes"}""").error)
        assertEquals("priority", set("""{"req":1,"machine":1,"priority":11}""").fields!!.keys.single())
        assertNotNull(set("""{"req":1,"machine":1,"priority":2.5}""").fields)
        assertEquals("recipe", set("""{"req":1,"machine":1,"recipe":"planks"}""").fields!!.keys.single(), "sawmill recipe on a smelter")
        val ok = set("""{"req":7,"machine":1,"running":false,"priority":3,"recipe":"gold"}""")
        assertTrue(ok.ok, ok.error)
        assertEquals(7L, ok.req)
        val m = f.machines(p).first()
        assertFalse(m.running)
        assertEquals(3, m.priority)
        assertEquals("gold", m.recipe.id)
        assertEquals(MachineStatus.OFF, m.status)
        assertEquals(0.0, m.progress)
        // other players have their own factory
        assertTrue(f.machines(other).first().running)
    }

    // ---- questlog -------------------------------------------------------------------------------------------

    @Test
    fun `questlog - accept, progress from events, completion, abandon`() {
        val q = QuestBook()
        fun accept(id: String) = q.accept(p, json("""{"req":1,"quest":"$id"}"""), t())
        assertEquals("Diese Quest gibt es nicht.", accept("nope").error)
        assertEquals("Diese Quest gibt es nicht.", q.accept(p, json("""{"req":1,"quest":5}"""), t()).error)
        assertTrue(accept("holzfaeller").ok)
        assertEquals("Die Quest ist schon aktiv.", accept("holzfaeller").error)
        // wildcard target *_log
        repeat(15) { q.record(p, ObjectiveKind.BREAK, "minecraft:birch_log") }
        assertEquals(listOf(15), q.counts(p, "holzfaeller"))
        assertEquals(false to emptyList<Any>(), q.record(p, ObjectiveKind.KILL, "minecraft:birch_log"), "wrong kind")
        val (changed, done) = q.record(p, ObjectiveKind.BREAK, "minecraft:oak_log")
        assertTrue(changed)
        assertEquals("holzfaeller", done.single().quest.id)
        assertEquals(QuestStatus.COMPLETED, q.status(p, "holzfaeller"))
        assertEquals("Die Quest ist bereits abgeschlossen.", accept("holzfaeller").error)
        // at most three active
        assertTrue(accept("bergmann").ok)
        assertTrue(accept("zombiejagd").ok)
        assertTrue(accept("sandburg").ok)
        assertTrue(accept("ernte").error!!.contains("3 aktive"))
        // abandon resets progress
        q.record(p, ObjectiveKind.BREAK, "minecraft:stone")
        assertEquals("Die Quest ist nicht aktiv.", q.abandon(p, json("""{"req":1,"quest":"ernte"}"""), t()).error)
        assertTrue(q.abandon(p, json("""{"req":1,"quest":"bergmann"}"""), t()).ok)
        assertEquals(QuestStatus.AVAILABLE, q.status(p, "bergmann"))
        assertTrue(accept("bergmann").ok)
        assertEquals(listOf(0, 0), q.counts(p, "bergmann"))
        // state: rewards encoded, progress per objective
        val state = q.state(p) { key -> if (key == "minecraft:torch") null else "enc:$key" }
        val bergmann = state.quests.single { it.id == "bergmann" }
        assertEquals("ACTIVE", bergmann.status)
        assertEquals(listOf("enc:minecraft:iron_pickaxe", null), bergmann.rewards.map { it.item })
        assertEquals(16, state.quests.single { it.id == "holzfaeller" }.objectives.single().have)
    }

    // ---- warps ----------------------------------------------------------------------------------------------

    private val spawn = Warp("spawn", "Spawn", "minecraft:red_bed", "Start", WarpLocation("world", 0.0, 64.0, 0.0))

    @Test
    fun `warps - teleport with cooldown, favorites`() {
        val w = WarpBook(listOf(spawn), cooldownMillis = 10_000)
        val start = t()
        assertEquals("Diesen Warp gibt es nicht (mehr).", w.teleport(p, json("""{"req":1,"warp":"nope"}"""), start).result.error)
        val ok = w.teleport(p, json("""{"req":2,"warp":"spawn"}"""), start)
        assertTrue(ok.result.ok)
        assertEquals("world", ok.location!!.world)
        val again = w.teleport(p, json("""{"req":3,"warp":"spawn"}"""), start + 3_000)
        assertNull(again.location)
        assertEquals("Noch 7 s Abklingzeit.", again.result.error)
        assertEquals(7_000, w.state(p, start + 3_000).cooldownLeft)
        assertTrue(w.teleport(p, json("""{"req":4,"warp":"spawn"}"""), start + 10_000).result.ok)
        // a failed teleport (world missing) costs no cooldown
        w.cancelCooldown(p)
        assertEquals(0, w.cooldownLeft(p, start + 10_001))
        assertEquals(DemoResult.INVALID, w.favorite(p, json("""{"req":1,"warp":"spawn","on":1}"""), t()).error)
        assertTrue(w.favorite(p, json("""{"req":1,"warp":"spawn","on":true}"""), t()).ok)
        assertTrue(w.state(p, now).warps.single().favorite)
        assertFalse(w.state(other, now).warps.single().favorite)
    }

    @Test
    fun `warps - create validates name and icon, delete only own warps`() {
        val w = WarpBook(listOf(spawn))
        val here = WarpLocation("world", 5.0, 70.0, 5.0)
        fun create(name: String, icon: String = "minecraft:chest", who: UUID = p) =
            w.create(who, JsonObject().apply { addProperty("req", 1); addProperty("name", name); addProperty("icon", icon) }, here, t())
        assertEquals("Mindestens 3 Zeichen.", create("ab").fields!!["name"])
        assertEquals("Höchstens 24 Zeichen.", create("a".repeat(25)).fields!!["name"])
        assertEquals("Nur Buchstaben, Ziffern, Leerzeichen, _ und -.", create("Base!").fields!!["name"])
        assertEquals("Keine doppelten Leerzeichen.", create("Meine  Base").fields!!["name"])
        assertEquals("Diesen Namen gibt es schon.", create("SPAWN").fields!!["name"])
        assertEquals("Unbekanntes Symbol.", create("Base", "minecraft:bedrock").fields!!["icon"])
        assertEquals(DemoResult.INVALID, w.create(p, json("""{"req":1,"name":5,"icon":"minecraft:chest"}"""), here, t()).error)
        assertEquals(DemoResult.INVALID, w.create(p, json("{\"req\":1,\"name\":\"Ba\\u0007se\",\"icon\":\"minecraft:chest\"}"), here, t()).error)
        assertTrue(create("  Höhle Süd ").ok)
        val own = w.state(p, now).warps.single { it.own }
        assertEquals("Höhle Süd", own.name)
        assertEquals(70, own.y)
        assertEquals(1, w.state(p, now).warps.count { it.own })
        assertEquals(0, w.state(other, now).warps.count { it.own }, "own warps are private")
        assertEquals("Diesen Namen gibt es schon.", create("höhle süd").fields!!["name"])
        repeat(WarpBook.MAX_OWN - 1) { assertTrue(create("Warp $it").ok) }
        assertTrue(create("Noch einer").error!!.contains("${WarpBook.MAX_OWN} eigene"))
        // delete
        assertEquals("Öffentliche Warps kannst du nicht löschen.", w.delete(p, json("""{"req":1,"warp":"spawn"}"""), t()).error)
        assertEquals("Diesen Warp gibt es nicht (mehr).", w.delete(other, json("""{"req":1,"warp":"${own.id}"}"""), t()).error)
        assertTrue(w.delete(p, json("""{"req":1,"warp":"${own.id}"}"""), t()).ok)
        assertTrue(w.state(p, now).warps.none { it.id == own.id })
    }

    // ---- bestenliste ----------------------------------------------------------------------------------------

    @Test
    fun `bestenliste - ranking with ties, own row, query validation`() {
        val b = Leaderboard()
        val initial = b.state(p, "Steve")
        assertEquals("BLOCKS", initial.category)
        assertEquals("ALL", initial.period)
        assertEquals(Leaderboard.DEMO_PLAYERS.size + 1, initial.players)
        val me = initial.rows.single { it.self }
        assertEquals("Steve", me.name)
        assertEquals(initial.players, me.rank, "0 blocks: last place, still in the list")
        // ties share a rank
        b.record("Steve", StatCategory.DEATHS, 1_000_000)
        b.record("Zed", StatCategory.DEATHS, 1_000_000)
        assertTrue(b.query(p, json("""{"req":1,"category":"DEATHS","period":"DAY"}"""), t()).ok)
        val deaths = b.state(p, "Steve")
        assertEquals("DEATHS", deaths.category)
        assertEquals(listOf(1, 1), deaths.rows.take(2).map { it.rank })
        assertEquals(3, deaths.rows[2].rank)
        assertEquals(1_000_000, deaths.rows.single { it.self }.gain, "gain since the last snapshot")
        // invalid queries keep the selection
        assertEquals(DemoResult.INVALID, b.query(p, json("""{"req":1,"category":"MONEY","period":"DAY"}"""), t()).error)
        assertEquals(DemoResult.INVALID, b.query(p, json("""{"req":1,"category":"BLOCKS"}"""), t()).error)
        assertEquals("DEATHS", b.state(p, "Steve").category)
        assertTrue(b.state(p, "Steve").rows.size <= Leaderboard.TOP + 1)
        // totals from the game never lower the value
        b.setTotal("Steve", StatCategory.PLAYTIME, 50)
        b.setTotal("Steve", StatCategory.PLAYTIME, 10)
        b.query(p, json("""{"req":1,"category":"PLAYTIME","period":"ALL"}"""), t())
        assertEquals(50, b.state(p, "Steve").rows.single { it.self }.value)
    }

    // ---- profil ---------------------------------------------------------------------------------------------

    private fun settings(nickname: String = "Baumeister", color: String = "gold", status: String = "Baue eine Burg", filter: String = "STRICT", volume: Any = 45, flag: Any = true) =
        json("""{"req":3,"settings":{"nickname":${gson(nickname)},"nickColor":"$color","status":${gson(status)},"chatFilter":"$filter","volume":$volume,"notifications":$flag,"privateMessages":false,"compactChat":true}}""")

    private fun gson(s: String) = com.google.gson.JsonPrimitive(s).toString()

    @Test
    fun `profil - save validates every field and stores per player`() {
        val pr = ProfileSettings()
        assertEquals(ProfileSettings.DEFAULTS, pr.settings(p))
        fun save(msg: JsonObject) = pr.save(p, msg, t())
        assertEquals(DemoResult.INVALID, save(json("""{"req":1}""")).result.error)
        assertEquals(DemoResult.INVALID, save(settings(flag = "\"yes\"")).result.error)
        assertEquals(DemoResult.INVALID, save(settings(volume = "\"50\"")).result.error)
        assertEquals(setOf("nickname"), save(settings(nickname = "ab")).result.fields!!.keys)
        assertEquals(setOf("nickname"), save(settings(nickname = "Bäumeister")).result.fields!!.keys)
        assertEquals(setOf("nickColor"), save(settings(color = "black")).result.fields!!.keys)
        assertEquals(setOf("status"), save(settings(status = "x".repeat(41))).result.fields!!.keys)
        assertEquals(setOf("status"), save(settings(status = "§cRot")).result.fields!!.keys)
        assertEquals(setOf("chatFilter"), save(settings(filter = "MAX")).result.fields!!.keys)
        assertEquals(setOf("volume"), save(settings(volume = 101)).result.fields!!.keys)
        assertEquals(setOf("volume"), save(settings(volume = 42)).result.fields!!.keys, "steps of 5")
        assertEquals(setOf("nickname", "volume"), save(settings(nickname = "a b", volume = -5)).result.fields!!.keys)
        assertEquals(ProfileSettings.DEFAULTS, pr.settings(p), "nothing stored on errors")
        val ok = save(settings(nickname = " Baumeister ", status = " Baue eine Burg "))
        assertTrue(ok.result.ok)
        assertEquals(3L, ok.result.req)
        assertEquals("Baumeister", ok.settings!!.nickname)
        assertEquals("Baue eine Burg", pr.settings(p).status)
        assertEquals(45, pr.settings(p).volume)
        assertFalse(pr.settings(p).privateMessages)
        assertEquals(ProfileSettings.DEFAULTS, pr.settings(other))
        // empty nickname = player name
        assertTrue(save(settings(nickname = "")).result.ok)
        val state = pr.state(p, "Steve")
        assertEquals("Steve", state.name)
        assertEquals(0xFFFFFFFF, state.colors.first().color)
    }
}
