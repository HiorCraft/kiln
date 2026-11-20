package dev.kiln.client.entity

import dev.kiln.client.entity.model.GeoAnimations
import dev.kiln.client.entity.model.GeoModel
import net.minecraft.resources.Identifier

/** A custom entity model ready to render: bones, animations and texture. */
class LoadedModel(val geo: GeoModel, val animations: GeoAnimations, val texture: Identifier)
