package dev.kiln.client.ext

/** Textures of the loaded extensions (`<ext>:textures/...png` -> size). */
interface ExtensionTextures {
    fun size(id: String): IntArray?
}
