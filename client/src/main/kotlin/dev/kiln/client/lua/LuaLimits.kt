package dev.kiln.client.lua

/**
 * Resource limits of one extension's Lua environment.
 *
 * Rechenzeit is measured in Lua VM instructions (counted by [SandboxHook]), not wall-clock time: instruction counts
 * are deterministic, cheap to check on every instruction and cannot be gamed by a slow machine. LuaJ interprets
 * roughly 30-60 M instructions per second with the hook installed, so [instructionsPerCall] = 100 000 is about
 * 2-3 ms, which matches the "2 ms per frame" target of the design; [instructionsPerSecond] bounds the sum over all
 * calls (many widgets, many channel messages).
 */
data class LuaLimits(
    /** Budget of one call into Lua (render, event, channel message, keybind). */
    val instructionsPerCall: Long = 100_000,
    /**
     * Budget of one `render` call of an open Lua screen (once per frame; not counted in [instructionsPerSecond],
     * the frame rate bounds it). About 5 ms on a slow machine; a shop of 50 tiles needs ~8 000 instructions (~3 %,
     * 0.35 ms; benchmark in
     * `LuaScreenTest`).
     */
    val instructionsPerFrame: Long = 250_000,
    /** Budget of loading the extension (running `main` and everything it requires). */
    val instructionsForLoad: Long = 2_000_000,
    /** Sum of all calls of one extension within a sliding one-second window. */
    val instructionsPerSecond: Long = 6_000_000,
    /** Maximum nesting of Lua calls (protects the Java stack; LuaJ recurses on the Java stack). */
    val maxCallDepth: Int = 200,
    /** Maximum length of a string produced by `..`, `string.rep`, `string.format`, `string.gsub`, `table.concat`. */
    val maxStringBytes: Int = 1 shl 20,
    /** Estimated Lua heap of one extension ([LuaMemory]). */
    val maxMemoryBytes: Long = 16L shl 20,
    /** Maximum number of objects (tables, closures, strings) the memory estimate visits. */
    val maxObjects: Int = 250_000,
    /** Instructions between two memory estimates (the walk is not free, so it only runs after real work). */
    val memoryCheckInterval: Long = 1_000_000,
    /** Maximum number of values `table.unpack` may return. */
    val maxUnpack: Int = 4_096,
)
