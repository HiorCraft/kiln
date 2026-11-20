package dev.kiln.client.screen.widget

import dev.kiln.client.screen.TextFieldSync
import dev.kiln.protocol.message.ScreenInput
import net.minecraft.util.Util

/** Reports a pending value change of input element [elementId] ([force]: regardless of the throttle). */
internal fun TextFieldSync.flush(env: WidgetEnv, elementId: String, force: Boolean) {
    poll(Util.getMillis(), force)?.let { env.send(ScreenInput(env.screenId, elementId, it)) }
}

/** Sends a value change of an input element: reports [value] immediately (no throttling). */
internal fun TextFieldSync.sendNow(env: WidgetEnv, elementId: String, value: String) {
    onLocalEdit(value)
    flush(env, elementId, force = true)
}
