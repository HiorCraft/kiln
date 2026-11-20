package dev.kiln.client.entity

import dev.kiln.client.entity.model.ActiveAnimation
import dev.kiln.protocol.message.EntityAppearance

/** The custom look of one entity and the server animations playing on it. */
class AppearanceEntry(val appearance: EntityAppearance) {
    val animations = ArrayList<ActiveAnimation>()
}
