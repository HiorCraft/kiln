package dev.kiln.client.lua

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.luaj.vm2.LuaValue

class LuaSandboxTest {
    private fun runtime(limits: LuaLimits = LuaLimits(), clock: () -> Long = System::nanoTime) = LuaRuntime("test", limits, clock = clock)

    private fun eval(rt: LuaRuntime, code: String): LuaValue = rt.load(code.toByteArray(), "test")

    @ParameterizedTest
    @ValueSource(strings = [
        "io", "os", "debug", "luajava", "load", "loadstring", "dofile", "loadfile", "require", "package", "module",
        "collectgarbage", "coroutine", "rawget", "rawset", "rawequal", "rawlen", "xpcall", "unpack",
    ])
    fun `dangerous globals are absent`(name: String) {
        assertTrue(eval(runtime(), "return $name == nil").toboolean(), name)
    }

    @Test
    fun `allowed basics are present`() {
        val rt = runtime()
        val code = "return type(pairs) == 'function' and type(ipairs) == 'function' and type(next) == 'function' and " +
            "type(select) == 'function' and type(tostring) == 'function' and type(tonumber) == 'function' and " +
            "type(pcall) == 'function' and type(error) == 'function' and type(assert) == 'function' and " +
            "type(setmetatable) == 'function' and type(getmetatable) == 'function' and type(string.format) == 'function' and " +
            "type(math.sin) == 'function' and type(table.insert) == 'function' and type(table.unpack) == 'function'"
        assertTrue(eval(rt, code).toboolean())
    }

    @Test
    fun `string dump is gone, also through the string metatable`() {
        assertTrue(eval(runtime(), "return string.dump == nil and (''). dump == nil").toboolean())
    }

    @Test
    fun `string metatable is protected`() {
        val rt = runtime()
        assertEquals("string", eval(rt, "return getmetatable('')").tojstring())
        // the classic escape: getmetatable("").__index is a string here, not the library table
        assertTrue(eval(rt, "return type(getmetatable('').__index) ~= 'table'").toboolean())
        assertFalse(eval(rt, "return (pcall(setmetatable, '', {}))").toboolean())
        assertFalse(eval(rt, "return (pcall(function() getmetatable('').__index.upper = nil end))").toboolean())
    }

    @Test
    fun `changing the string library does not leak into other extensions`() {
        val a = runtime()
        val b = runtime()
        eval(a, "string.upper = function() return 'pwned' end; string.evil = 1")
        assertEquals("pwned", eval(a, "return string.upper('x')").tojstring())
        assertEquals("X", eval(a, "return ('x'):upper()").tojstring()) // method calls use the shared read-only table
        assertEquals("X", eval(b, "return string.upper('x')").tojstring())
        assertTrue(eval(b, "return string.evil == nil and ('x').evil == nil").toboolean())
    }

    @Test
    fun `bytecode is rejected`() {
        val rt = runtime()
        val bytecode = byteArrayOf(0x1B, 'L'.code.toByte(), 'u'.code.toByte(), 'a'.code.toByte(), 0x52, 0, 1, 4)
        val e = assertThrows(LuaScriptException::class.java) { rt.load(bytecode, "evil") }
        assertTrue(e.message!!.contains("precompiled"))
    }

    @Test
    fun `no path to Java classes`() {
        val rt = runtime()
        // LuaJ exposes Java only through luajava; neither it nor class-like fields on library tables exist
        assertTrue(eval(rt, "return luajava == nil and string.class == nil and _G.luajava == nil").toboolean())
        assertTrue(eval(rt, "for k, v in pairs(_G) do if type(v) == 'userdata' then return false end end return true").toboolean())
    }

    @Test
    fun `infinite loop hits the instruction budget`() {
        val rt = runtime()
        val e = assertThrows(LuaScriptException::class.java) { eval(rt, "while true do end") }
        assertTrue(e.message!!.contains("instruction budget"), e.message)
    }

    @Test
    fun `pcall cannot swallow the budget`() {
        val rt = runtime()
        assertThrows(LuaScriptException::class.java) {
            eval(rt, "for i = 1, 10 do pcall(function() while true do end end) end")
        }
    }

    @Test
    fun `budget is per call and counts the work of a callback`() {
        val rt = runtime(LuaLimits(instructionsPerCall = 10_000, instructionsForLoad = 10_000, instructionsPerSecond = Long.MAX_VALUE))
        val fn = eval(rt, "return function(n) local s = 0 for i = 1, n do s = s + i end return s end")
        assertEquals(5050, rt.call(fn, LuaValue.valueOf(100)).arg1().toint())
        assertThrows(LuaScriptException::class.java) { rt.call(fn, LuaValue.valueOf(1_000_000)) }
    }

    @Test
    fun `per second budget is shared by all calls`() {
        var now = 0L
        val rt = runtime(LuaLimits(instructionsPerCall = 50_000, instructionsPerSecond = 100_000), clock = { now })
        val fn = eval(rt, "return function() for i = 1, 5000 do end end")
        var calls = 0
        val e = assertThrows(LuaScriptException::class.java) { while (true) { rt.call(fn); calls++ } }
        assertTrue(e.message!!.contains("budget"), e.message)
        assertTrue(calls in 1..20, "calls=$calls")
        now += 1_000_000_000L // next window
        val rt2 = runtime(LuaLimits(instructionsPerCall = 50_000, instructionsPerSecond = 100_000), clock = { now })
        rt2.call(eval(rt2, "return function() end"))
    }

    @Test
    fun `runaway recursion fails cleanly`() {
        val rt = runtime()
        val e = assertThrows(LuaScriptException::class.java) { eval(rt, "local function f(n) return f(n + 1) + 1 end return f(1)") }
        assertTrue(e.message!!.contains("stack overflow"), e.message)
        // the runtime stays usable after a caught overflow
        assertEquals(3, eval(rt, "local ok = pcall(function() local function f() return f() + 1 end return f() end) return ok and 1 or 3").toint())
    }

    @Test
    fun `huge strings are refused before allocation`() {
        val rt = runtime()
        assertThrows(LuaScriptException::class.java) { eval(rt, "return string.rep('x', 1e9)") }
        assertThrows(LuaScriptException::class.java) { eval(rt, "return ('x'):rep(1e9)") }
        assertThrows(LuaScriptException::class.java) { eval(rt, "local s = 'xxxxxxxxxxxxxxxx' for i = 1, 40 do s = s .. s end return #s") }
        assertThrows(LuaScriptException::class.java) { eval(rt, "local s = string.rep('x', 2000) return (string.gsub(s, '', s))") }
        assertThrows(LuaScriptException::class.java) {
            eval(rt, "local t = {} for i = 1, 3000 do t[i] = string.rep('x', 1000) end return table.concat(t)")
        }
        assertThrows(LuaScriptException::class.java) { eval(rt, "return table.unpack({}, 1, 1e8)") }
        assertEquals(1000, eval(rt, "return #string.rep('ab', 500)").toint())
    }

    @Test
    fun `memory limit is enforced across calls`() {
        val rt = runtime(LuaLimits(maxMemoryBytes = 2L shl 20, memoryCheckInterval = 10_000))
        val grow = eval(rt, "big = {} return function() for i = 1, 2000 do big[#big + 1] = {i} end end")
        val e = assertThrows(LuaScriptException::class.java) { repeat(1000) { rt.call(grow) } }
        assertTrue(e.message!!.contains("memory"), e.message)
    }

    @Test
    fun `syntax errors are reported`() {
        val e = assertThrows(LuaScriptException::class.java) { eval(runtime(), "this is not lua") }
        assertTrue(e.message!!.startsWith("test:"), e.message)
    }

    @Test
    fun `print goes to the sink, not stdout`() {
        val out = ArrayList<String>()
        val rt = LuaRuntime("p", print = { out += it })
        eval(rt, "print('a', 1, {})")
        assertEquals(listOf("a\t1\ttable"), out)
    }

    @Test
    fun `many large strings in one call hit the allocation budget`() {
        val rt = runtime(LuaLimits(maxAllocationPerCall = 32L shl 20))
        val e = assertThrows(LuaScriptException::class.java) {
            eval(rt, "local t = {} for i = 1, 100 do t[i] = ('x'):rep(1048576) end")
        }
        assertTrue(e.message!!.contains("memory"), e.message)
        val copies = assertThrows(LuaScriptException::class.java) {
            eval(rt, "local s = ('x'):rep(1048576) local t = {} for i = 1, 100 do t[i] = s:upper() end")
        }
        assertTrue(copies.message!!.contains("memory"), copies.message)
        // small work stays fine
        assertEquals(10, eval(rt, "local t = {} for i = 1, 10 do t[i] = ('x'):rep(1000) .. i end return #t").toint())
    }

    @Test
    fun `format refuses a result that would be too long before building it`() {
        val rt = runtime()
        val e = assertThrows(LuaScriptException::class.java) {
            eval(rt, "local s = ('x'):rep(1048576) local t = {} for i = 1, 4096 do t[i] = s end return string.format(('%s'):rep(4096), table.unpack(t))")
        }
        assertTrue(e.message!!.contains("too long"), e.message)
    }

    @Test
    fun `pathological patterns hit the instruction budget instead of hanging`() {
        val rt = runtime()
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(10)) {
            val e = assertThrows(LuaScriptException::class.java) {
                eval(rt, "return ('a'):rep(40):find(('a*'):rep(20) .. 'b')")
            }
            assertTrue(e.message!!.contains("instruction budget"), e.message)
        }
    }

    @Test
    fun `ordinary patterns still work`() {
        val rt = runtime()
        assertEquals("b2", eval(rt, "return ('a1 b2 c3'):match('(b%d)')").tojstring())
        assertEquals(3, eval(rt, "local n = 0 for w in ('x y z'):gmatch('%a') do n = n + 1 end return n").toint())
        assertEquals("X-X", eval(rt, "return (('a-a'):gsub('a', 'X'))").tojstring())
        assertEquals(4, eval(rt, "return ('hello'):find('lo')").toint())
    }
}
