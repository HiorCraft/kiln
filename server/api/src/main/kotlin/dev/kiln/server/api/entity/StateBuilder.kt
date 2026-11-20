package dev.kiln.server.api.entity

public class StateBuilder {
    public var onEnter: (EntityContext.() -> Unit)? = null
    public var onExit: (EntityContext.() -> Unit)? = null

    public fun onEnter(handler: EntityContext.() -> Unit) {
        onEnter = handler
    }

    public fun onExit(handler: EntityContext.() -> Unit) {
        onExit = handler
    }
}
