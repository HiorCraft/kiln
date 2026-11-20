package dev.kiln.client.lua.sandbox

import org.luaj.vm2.Lua
import org.luaj.vm2.LuaClosure
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaFunction
import org.luaj.vm2.LuaString
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.DebugLib

/**
 * Execution hook of one extension. LuaJ calls `Globals.debuglib` on every function entry/exit and **before every VM
 * instruction** of interpreted code (`LuaClosure.execute`) as soon as the field is non-null; this is the only
 * per-instruction hook point LuaJ offers without patching the interpreter. The hook is installed directly into the
 * field and never exposed as the `debug` library, and it does not delegate to [DebugLib] (no call stack bookkeeping,
 * no hooks a script could set).
 *
 * It enforces:
 * - the instruction budget ([remaining]); exceeding it throws [LuaLimitExceeded];
 * - the call depth ([LuaLimits.maxCallDepth]) as a catchable `LuaError("stack overflow")`, before LuaJ's Java
 *   recursion could overflow the render thread's stack;
 * - the size of `..` results: before an `OP_CONCAT` executes, the lengths of its operands are summed from the
 *   frame's register array (captured in [onCall]); oversized results never get allocated.
 *
 * Coroutines are not available in the sandbox, so there is exactly one Lua thread and one frame stack.
 *
 * **Calls across extensions** (Lua API v2 imports): LuaJ reports instructions to the hook of the *closure's*
 * environment, so code of an imported API extension would be counted on that extension's hook. While a call is in
 * progress, every hook therefore forwards to the hook of the call's principal ([LuaExecution.activeHook]): the
 * caller pays for everything that runs in its call, including code of other extensions.
 */
class SandboxHook(private val limits: LuaLimits) : DebugLib() {
    /** The runtime this hook belongs to (owner of the closures compiled for its globals, [LuaExecution.caller]). */
    var runtime: LuaRuntime? = null
        internal set

    /** Instructions left in the current call. */
    var remaining: Long = 0

    /** Instructions executed since the hook was created (used to schedule memory checks). */
    var executed: Long = 0
        private set

    private var closures = arrayOfNulls<LuaClosure>(INITIAL_FRAMES)
    private var stacks = arrayOfNulls<Array<LuaValue>>(INITIAL_FRAMES)

    /** Program counter of the instruction each frame executes (for a caller frame: the call in progress). */
    private var pcs = IntArray(INITIAL_FRAMES)
    private var depth = 0

    /** Bytes of strings created in the current call ([chargeBytes]). */
    private var allocated = 0L

    /** Prepares a new outermost call with [budget] instructions. */
    fun begin(budget: Long) {
        remaining = budget
        allocated = 0
        if (depth != 0) {
            closures.fill(null)
            stacks.fill(null)
            depth = 0
        }
    }

    /**
     * Checks that the host function running now is called **directly** by Lua code: the innermost frame executes an
     * `OP_CALL` whose function register holds it ([isTarget]), or that frame is a `pcall` whose first argument is
     * it. Metamethods run while the frame executes another instruction (`GETTABLE`, `CONCAT`, `EQ`, ...), and
     * functions called by library functions (`tostring`, `table.sort`, `string.gsub`, ...) are not in the register
     * of the `CALL` in progress, so both are [CallOrigin.Indirect]. Native functions do not get frames of their own, except
     * `pcall` (an empty frame).
     */
    fun directCall(isTarget: (LuaValue) -> Boolean): CallOrigin {
        if (depth == 0) return CallOrigin.FromHost
        var i = depth - 1
        var viaPcall = false
        if (closures[i] == null) {
            viaPcall = true
            i--
            if (i < 0 || closures[i] == null) return CallOrigin.Indirect
        }
        val closure = closures[i]!!
        val stack = stacks[i] ?: return CallOrigin.Indirect
        val code = closure.p.code
        val pc = pcs[i]
        if (pc !in code.indices) return CallOrigin.Indirect
        val instruction = code[pc]
        if (Lua.GET_OPCODE(instruction) != Lua.OP_CALL) return CallOrigin.Indirect
        val a = Lua.GETARG_A(instruction)
        val callee = if (viaPcall) a + 1 else a
        if (callee >= stack.size) return CallOrigin.Indirect
        if (viaPcall && stack[a].javaClass.name != PCALL_CLASS) return CallOrigin.Indirect
        return if (isTarget(stack[callee])) CallOrigin.Direct(closure) else CallOrigin.Indirect
    }

    /** Work done in Java on behalf of the script (pattern matching, drawing) costs instructions too. */
    fun chargeInstructions(count: Long) {
        executed += count
        remaining -= count
        if (remaining < 0) throw LuaLimitExceeded("instruction budget exceeded")
    }

    /** A string of [bytes] is about to be created; above [LuaLimits.maxAllocationPerCall] per call the call ends. */
    fun chargeBytes(bytes: Long) {
        allocated += bytes
        if (allocated > limits.maxAllocationPerCall) throw LuaLimitExceeded("memory budget of one call exceeded (${limits.maxAllocationPerCall / 1024 / 1024} MiB of strings)")
    }

    /** Instructions consumed since [begin] with [budget]. */
    fun used(budget: Long): Long = budget - remaining

    override fun onCall(f: LuaFunction) {
        val active = LuaExecution.activeHook
        if (active != null && active !== this) return active.onCall(f)
        push(null, null)
    }

    override fun onCall(c: LuaClosure, varargs: Varargs, stack: Array<LuaValue>) {
        val active = LuaExecution.activeHook
        if (active != null && active !== this) return active.onCall(c, varargs, stack)
        push(c, stack)
    }

    private fun push(closure: LuaClosure?, stack: Array<LuaValue>?) {
        // thrown before LuaJ enters its try/finally, so no onReturn follows: the stack stays balanced
        if (depth >= limits.maxCallDepth) throw LuaError("stack overflow (more than ${limits.maxCallDepth} nested calls)")
        if (depth == closures.size) {
            closures = closures.copyOf(depth * 2)
            stacks = stacks.copyOf(depth * 2)
            pcs = pcs.copyOf(depth * 2)
        }
        closures[depth] = closure
        stacks[depth] = stack
        pcs[depth] = -1
        depth++
    }

    override fun onReturn() {
        val active = LuaExecution.activeHook
        if (active != null && active !== this) return active.onReturn()
        if (depth == 0) return
        depth--
        closures[depth] = null
        stacks[depth] = null
    }

    override fun onInstruction(pc: Int, v: Varargs, top: Int) {
        val active = LuaExecution.activeHook
        if (active != null && active !== this) return active.onInstruction(pc, v, top)
        executed++
        if (--remaining < 0) throw LuaLimitExceeded("instruction budget exceeded")
        if (depth == 0) return
        pcs[depth - 1] = pc
        val closure = closures[depth - 1] ?: return
        val code = closure.p.code
        if (pc < 0 || pc >= code.size) return
        val i = code[pc]
        if (Lua.GET_OPCODE(i) == Lua.OP_CONCAT) checkConcat(stacks[depth - 1] ?: return, Lua.GETARG_B(i), Lua.GETARG_C(i))
    }

    private fun checkConcat(stack: Array<LuaValue>, from: Int, to: Int) {
        var total = 0L
        for (r in from..minOf(to, stack.size - 1)) {
            val value = stack[r]
            total += when {
                value is LuaString -> value.m_length.toLong()
                value.isnumber() -> 32L
                else -> 0L // metamethod (__concat) or error: counted when it runs
            }
        }
        checkStringLength(limits, total)
        chargeBytes(total)
    }

    /** LuaJ appends this to error messages; the sandbox keeps messages short and free of host details. */
    override fun traceback(level: Int): String = ""

    private companion object {
        /** LuaJ's `pcall` (the only library function that gets a frame). */
        const val PCALL_CLASS = "org.luaj.vm2.lib.BaseLib\$pcall"

        /** Frames the stack arrays hold before they grow. */
        const val INITIAL_FRAMES = 64
    }
}
