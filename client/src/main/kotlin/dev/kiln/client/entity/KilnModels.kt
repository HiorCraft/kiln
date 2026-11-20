package dev.kiln.client.entity

import dev.kiln.client.entity.model.GeoAnimations
import dev.kiln.client.entity.model.GeoException
import dev.kiln.client.entity.model.GeoModel
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory

/**
 * Bone models of custom entities from the resources (normally the server's extension pack):
 * `assets/<ns>/kiln/entity/<name>.geo.json`, optional `<name>.animation.json`, texture
 * `assets/<ns>/textures/entity/<name>.png`. Loaded once per model and resource state; a model that fails to load
 * is remembered as missing (the entity then keeps its vanilla renderer) and logged once.
 */
object KilnModels {
    class Loaded(val geo: GeoModel, val animations: GeoAnimations, val texture: Identifier)

    private val logger = LoggerFactory.getLogger("kiln")
    private val cache = HashMap<String, Loaded?>()

    fun get(model: String): Loaded? = cache.getOrPut(model) { load(model) }

    /** Resources changed (pack mounted or removed). */
    fun clear() = cache.clear()

    private fun load(model: String): Loaded? {
        val id = Identifier.tryParse(model) ?: return null
        val resources = Minecraft.getInstance().resourceManager
        fun read(path: String): String? = resources.getResource(Identifier.fromNamespaceAndPath(id.namespace, path)).orElse(null)
            ?.open()?.use { it.readNBytes(MAX_FILE).toString(Charsets.UTF_8) }
        return try {
            val geo = read("kiln/entity/${id.path}.geo.json") ?: run {
                logger.warn("Entity model {} not found (kiln/entity/{}.geo.json)", model, id.path)
                return null
            }
            val animations = read("kiln/entity/${id.path}.animation.json")?.let(GeoAnimations::parse) ?: GeoAnimations.EMPTY
            Loaded(GeoModel.parse(geo), animations, Identifier.fromNamespaceAndPath(id.namespace, "textures/entity/${id.path}.png"))
        } catch (e: GeoException) {
            logger.warn("Entity model {} is invalid: {}", model, e.message)
            null
        } catch (e: Exception) {
            logger.warn("Entity model {} could not be loaded", model, e)
            null
        }
    }

    private const val MAX_FILE = 4 * 1024 * 1024
}
