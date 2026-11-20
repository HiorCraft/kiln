package dev.kiln.client.lua.sandbox

import org.luaj.vm2.Globals
import org.luaj.vm2.Lua
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Prototype
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

/**
 * Who runs Lua right now (client thread only). The **principal** is the runtime whose [LuaRuntime.call] started the
 * current call chain: the extension the host called (render, event, channel message, keybind). Everything that runs
 * until that call returns - its own code, code of imported API extensions, host functions calling back into Lua - is
 * charged to the principal's budget, and permission-checked API functions check the principal's permissions
 * (see `docs/lua-extensions.md`, "Sicherheitsmodell beim Import").
 */
object LuaExecution {
    /** Runtime of the outermost call in progress, or `null` when no Lua runs. */
    var principal: LuaRuntime? = null
        private set

    /** Hook of [principal]; every extension's hook forwards to it (see [SandboxHook]). */
    @JvmField
    internal var activeHook: SandboxHook? = null

    val active: Boolean get() = principal != null

    /** Charges Java-side work to the running call (no-op outside a call). */
    @JvmStatic
    fun chargeInstructions(count: Long) {
        activeHook?.chargeInstructions(count)
    }

    /** Charges a string allocation to the running call (no-op outside a call). */
    @JvmStatic
    fun chargeBytes(bytes: Long) {
        activeHook?.chargeBytes(bytes)
    }

    /**
     * Holders of the exported functions running now ([ExportedFunction.holder], innermost last): an importer that
     * calls (or hands on) a function it imported adds its own permissions to the check of everything that runs inside.
     */
    private val holders = ArrayList<Any>()
    val activeHolders: List<Any> get() = holders

    internal fun pushHolder(holder: Any) { holders += holder }

    /** Holder of a view nobody imported (`kiln.readonly`): has no permissions (see [LuaExports]). */
    internal val UNKNOWN_HOLDER = Any()

    internal fun popHolder() { if (holders.isNotEmpty()) holders.removeAt(holders.size - 1) }

    internal fun enter(runtime: LuaRuntime, hook: SandboxHook) {
        principal = runtime
        activeHook = hook
        holders.clear()
    }

    internal fun exit() {
        principal = null
        activeHook = null
        holders.clear()
    }

    /** Owner (environment) of every compiled function prototype; closures can only come from these (no `load`). */
    private val owners: MutableMap<Prototype, WeakReference<Globals>> = Collections.synchronizedMap(WeakHashMap())

    /**
     * Records [globals] as owner of [prototype] and all functions nested in it, and turns tail calls into plain
     * calls: LuaJ runs a tail call *after* the calling function's frame is gone, so the callee would appear to be
     * called by the caller's caller (see [caller]). `return f(x)` compiles to `TAILCALL A B 0; RETURN A 0`, and
     * `CALL A B 0` (all results to the stack top) followed by that `RETURN` returns exactly the same values. The only
     * difference: tail calls now count towards [LuaLimits.maxCallDepth].
     */
    internal fun registerChunk(prototype: Prototype, globals: Globals) {
        val pending = ArrayDeque<Prototype>()
        pending += prototype
        while (pending.isNotEmpty()) {
            val p = pending.removeLast()
            owners[p] = WeakReference(globals)
            val code = p.code
            for (i in code.indices) {
                val instruction = code[i]
                if (Lua.GET_OPCODE(instruction) == Lua.OP_TAILCALL && Lua.GETARG_C(instruction) == 0) {
                    code[i] = (instruction and Lua.MASK_NOT_OP) or (Lua.OP_CALL shl Lua.POS_OP)
                }
            }
            p.p?.let { pending.addAll(it) }
        }
    }

    /**
     * The direct Lua caller of the running host function [isTarget] (the function itself, or a wrapper of it): the
     * owner of the Lua frame whose `CALL` (or `pcall(f, ...)`) invokes it ([SandboxHook.directCall]). Frames are
     * tracked by the principal's hook, which every extension's hook forwards to, so this works across extensions.
     */
    fun caller(isTarget: (LuaValue) -> Boolean): LuaCaller {
        val hook = activeHook ?: return LuaCaller.Host
        val closure = when (val call = hook.directCall(isTarget)) {
            CallOrigin.FromHost -> return LuaCaller.Host
            CallOrigin.Indirect -> return LuaCaller.Indirect
            is CallOrigin.Direct -> call.closure
        }
        val globals = owners[closure.p]?.get() ?: return LuaCaller.Unknown
        val runtime = (globals.debuglib as? SandboxHook)?.runtime ?: return LuaCaller.Unknown
        return LuaCaller.Script(runtime)
    }
}
