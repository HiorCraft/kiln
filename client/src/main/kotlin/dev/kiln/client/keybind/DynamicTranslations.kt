package dev.kiln.client.keybind

import java.util.concurrent.ConcurrentHashMap

/**
 * Runtime translation overlay for names that only exist while connected (server keybind names and categories).
 * `ClientLanguageMixin` consults it before the loaded language files. Values are display strings; a value that is
 * itself a translation key of the loaded language is resolved by the mixin.
 */
object DynamicTranslations {
    private val entries = ConcurrentHashMap<String, String>()

    @JvmStatic
    fun get(key: String): String? = entries[key]

    fun put(key: String, value: String) {
        entries[key] = value
    }

    fun remove(key: String) {
        entries.remove(key)
    }
}
