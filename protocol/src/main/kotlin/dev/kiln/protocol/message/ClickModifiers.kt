package dev.kiln.protocol.message

/** Bit flags of [ScreenClick.modifiers]. */
public object ClickModifiers {
    public const val SHIFT: Int = 1
    public const val CONTROL: Int = 2
    public const val ALT: Int = 4
    public const val ALL: Int = SHIFT or CONTROL or ALT
}
