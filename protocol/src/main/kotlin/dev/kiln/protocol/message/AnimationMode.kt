package dev.kiln.protocol.message

/** How a [PlayAnimation] plays. */
public object AnimationMode {
    /** Once, then back to the state animation. */
    public const val ONCE: Int = 0

    /** Repeats until stopped. */
    public const val LOOP: Int = 1

    /** Once, then holds the last frame until stopped. */
    public const val HOLD: Int = 2
}
