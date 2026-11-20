package dev.kiln.client.lua

import com.google.gson.JsonParser
import dev.kiln.client.lua.json.LuaJson
import dev.kiln.client.lua.sandbox.LuaRuntime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue

class LuaJsonTest {
    private val rt = LuaRuntime("json")

    private fun lua(code: String): LuaValue = rt.load(code.toByteArray(), "json")

    @Test
    fun `tables encode to objects and arrays`() {
        assertEquals(JsonParser.parseString("""{"text":"hi","n":3,"f":1.5,"ok":true,"list":[1,2,"x"],"empty":[],"obj":{"a":{"b":1}}}"""),
            JsonParser.parseString(LuaJson.encode(lua("return { text = 'hi', n = 3, f = 1.5, ok = true, list = {1, 2, 'x'}, empty = {}, obj = { a = { b = 1 } } }"))))
        assertEquals("{}", LuaJson.encode(lua("return {}")))
        assertEquals("""{"list":[1,2,3]}""", LuaJson.encode(lua("return { list = {1, 2, 3} }")))
        assertEquals("""{"v":4294967295}""", LuaJson.encode(lua("return { v = 0xFFFFFFFF }")))
    }

    @Test
    fun `invalid tables are rejected`() {
        assertThrows(LuaError::class.java) { LuaJson.encode(lua("return { 1, 2 }")) } // top level must be an object
        assertThrows(LuaError::class.java) { LuaJson.encode(lua("return { t = { 1, 2, x = 3 } }")) } // mixed
        assertThrows(LuaError::class.java) { LuaJson.encode(lua("return { t = { [1] = 1, [3] = 3 } }")) } // gap
        assertThrows(LuaError::class.java) { LuaJson.encode(lua("return { f = function() end }")) }
        assertThrows(LuaError::class.java) { LuaJson.encode(lua("return { n = 0/0 }")) }
        assertThrows(LuaError::class.java) { LuaJson.encode(lua("return { n = 1/0 }")) }
        assertThrows(LuaError::class.java) { LuaJson.encode(lua("local t = {} t.self = t return t")) }
        assertThrows(LuaError::class.java) { LuaJson.encode(LuaValue.valueOf("x")) }
    }

    @Test
    fun `limits apply`() {
        assertThrows(LuaError::class.java) {
            LuaJson.encode(lua("local t = {} local c = t for i = 1, 20 do c.x = {} c = c.x end return t"))
        }
        assertThrows(LuaError::class.java) { LuaJson.encode(lua("return { s = string.rep('x', 20000) }")) }
        assertThrows(LuaError::class.java) { LuaJson.encode(lua("local t = {} for i = 1, 5000 do t[i] = i end return { t = t }")) }
        assertThrows(LuaError::class.java) { LuaJson.decode("[".repeat(40) + "]".repeat(40)) }
        assertThrows(LuaError::class.java) { LuaJson.decode("{not json") }
        assertThrows(LuaError::class.java) { LuaJson.decode("\"x\"".padEnd(20_000)) }
    }

    @Test
    fun `json decodes to tables`() {
        val t = LuaJson.decode("""{"text":"hallo","n":2,"f":0.25,"ok":false,"list":[10,null,"z"],"nil":null}""") as LuaTable
        assertEquals("hallo", t.get("text").tojstring())
        assertEquals(2, t.get("n").toint())
        assertEquals(0.25, t.get("f").todouble())
        assertEquals(false, t.get("ok").toboolean())
        assertTrue(t.get("nil").isnil())
        val list = t.get("list")
        assertEquals(10, list.get(1).toint())
        assertTrue(list.get(2).isnil())
        assertEquals("z", list.get(3).tojstring())
    }

    @Test
    fun `round trip`() {
        val json = LuaJson.encode(lua("return { a = { 1, 2, { b = 'c' } }, d = 'e' }"))
        assertEquals(JsonParser.parseString(json), JsonParser.parseString(LuaJson.encode(LuaJson.decode(json))))
    }
}
