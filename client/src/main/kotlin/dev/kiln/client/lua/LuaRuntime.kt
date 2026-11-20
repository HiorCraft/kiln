package dev.kiln.client.lua

import org.luaj.vm2.Globals
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs

/** A script failed or exceeded a limit; the extension must be unloaded. [message] is safe to show to the player. */
class LuaScriptException(message: String, cause: Throwable? = null) : Exception(message, cause)

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

    internal fun enter(runtime: LuaRuntime, hook: SandboxHook) {
        principal = runtime
        activeHook = hook
    }

    internal fun exit() {
        principal = null
        activeHook = null
    }
}

/**
 * One sandboxed Lua environment with its limits. All calls into Lua go through [call] / [load], which
 * - give the call its instruction budget (bounded by what is left of the one-second window),
 * - convert every failure into a [LuaScriptException] (Lua errors, limit violations, Java stack overflow),
 * - run a memory estimate every [LuaLimits.memoryCheckInterval] instructions.
 *
 * Calls made while another call is in progress (Lua -> host -> Lua, also into another extension's runtime) are
 * nested: they run inside the outer call's budget and error handling ([LuaExecution]).
 *
 * Not thread-safe: an environment is only used from the client (render) thread.
 */
class LuaRuntime(
    val name: String,
    val limits: LuaLimits = LuaLimits(),
    print: (String) -> Unit = {},
    private val clock: () -> Long = System::nanoTime,
) {
    private val hook = SandboxHook(limits)
    val globals: Globals = LuaSandbox.createGlobals(hook, limits, print)

    /** The object (extension) this runtime belongs to; read through [LuaExecution.principal]. */
    var owner: Any? = null

    /** Extra GC roots for the memory estimate (values held only by the host, e.g. widget state tables). */
    var extraRoots: () -> Iterable<LuaValue> = { emptyList() }

    /**
     * Runtimes whose memory is checked together with this one after its calls: the API extensions it imports, so
     * a caller cannot grow an imported extension's heap unchecked (that extension may never run calls of its own).
     */
    var memoryDependencies: () -> List<LuaRuntime> = { emptyList() }

    /** Instructions of the last outermost call (benchmarks, diagnostics). */
    var lastCallInstructions: Long = 0
        private set

    private var windowStart = clock()
    private var windowUsed = 0L
    private var nextMemoryCheck = limits.memoryCheckInterval

    /** Compiles and runs [source] as chunk [chunkName] with the load budget; returns the chunk's first result. */
    fun load(source: ByteArray, chunkName: String): LuaValue {
        val chunk = try {
            LuaSandbox.compile(globals, source, chunkName)
        } catch (e: LuaError) {
            throw LuaScriptException(cleanMessage(e))
        }
        return call(chunk, LuaValue.NONE, limits.instructionsForLoad).arg1()
    }

    /**
     * Calls [function] with [args]. Re-entrant calls (Lua -> host -> Lua, any runtime) share the outer call's budget.
     * [countInWindow] = false leaves the one-second window untouched (screen rendering: at most once per frame and
     * bounded by [LuaLimits.instructionsPerFrame]).
     */
    fun call(function: LuaValue, args: Varargs = LuaValue.NONE, budget: Long = limits.instructionsPerCall, countInWindow: Boolean = true): Varargs {
        if (LuaExecution.active) return function.invoke(args) // nested: inside the outer call's budget and error handling
        val now = clock()
        if (now - windowStart >= 1_000_000_000L) {
            windowStart = now
            windowUsed = 0
        }
        val available = if (countInWindow) minOf(budget, limits.instructionsPerSecond - windowUsed) else budget
        if (available <= 0) throw LuaScriptException("$name: instruction budget per second exceeded")
        hook.begin(available)
        LuaExecution.enter(this, hook)
        try {
            return function.invoke(args)
        } catch (e: LuaLimitExceeded) {
            throw LuaScriptException("$name: ${e.message}")
        } catch (e: LuaError) {
            throw LuaScriptException(cleanMessage(e))
        } catch (e: StackOverflowError) {
            throw LuaScriptException("$name: stack overflow")
        } catch (e: LuaScriptException) {
            throw e
        } catch (e: RuntimeException) {
            throw LuaScriptException("$name: ${e.javaClass.simpleName}: ${e.message}", e)
        } finally {
            LuaExecution.exit()
            val used = hook.used(available)
            lastCallInstructions = used
            if (countInWindow) windowUsed += used
            hook.begin(0)
            if (hook.executed >= nextMemoryCheck) {
                nextMemoryCheck = hook.executed + limits.memoryCheckInterval
                checkMemory()
            }
        }
    }

    /**
     * Estimates the reachable Lua heap of this runtime and of [memoryDependencies]; throws [LuaScriptException] above
     * [LuaLimits.maxMemoryBytes] (each runtime has its own limit).
     */
    fun checkMemory() {
        val estimate = LuaMemory.estimate(listOf<LuaValue>(globals) + extraRoots(), limits.maxMemoryBytes, limits.maxObjects)
        if (estimate.exceeded) {
            throw LuaScriptException("$name: memory limit exceeded (~${estimate.bytes / 1024} KiB, ${estimate.objects} objects)")
        }
        for (dependency in memoryDependencies()) {
            val e = LuaMemory.estimate(listOf<LuaValue>(dependency.globals) + dependency.extraRoots(), dependency.limits.maxMemoryBytes, dependency.limits.maxObjects)
            if (e.exceeded) throw LuaScriptException("$name: memory limit of imported extension ${dependency.name} exceeded (~${e.bytes / 1024} KiB)")
        }
    }

    private fun cleanMessage(e: LuaError): String {
        val raw = (e.message ?: e.toString()).trim()
        return raw.lineSequence().first().take(300)
    }
}
