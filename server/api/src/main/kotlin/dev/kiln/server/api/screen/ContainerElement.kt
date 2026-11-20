package dev.kiln.server.api.screen

/** An element with children. */
public sealed interface ContainerElement : ScreenElement {
    public val children: List<ScreenElement>
    public val layout: Layout
}
