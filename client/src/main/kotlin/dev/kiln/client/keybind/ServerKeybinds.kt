package dev.kiln.client.keybind

import com.mojang.blaze3d.platform.InputConstants
import dev.kiln.client.mixin.KeyMappingAccessor
import dev.kiln.client.mixin.KeyMappingCategoryAccessor
import dev.kiln.client.mixin.OptionsAccessor
import dev.kiln.protocol.KeyPressed
import dev.kiln.protocol.KeyReleased
import dev.kiln.protocol.KeybindDeclaration
import dev.kiln.protocol.ServerboundMessage
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory
import java.util.Locale

/**
 * Keybinds that only exist while connected, as real vanilla [KeyMapping]s created at runtime. Two owners share
 * this registry:
 * - **server-declared** keybinds (`DeclareKeybinds`, play phase): press/release edges are sent to the server;
 * - **extension** keybinds (`kiln.keys.bind` of a Lua extension, registered while the extension loads):
 *   edges call the extension's Lua handlers.
 *
 * Mechanics:
 * - A [KeyMapping] per keybind is constructed (which registers it in vanilla's global `KeyMapping.ALL`/`MAP`) and
 *   appended to `Options.keyMappings` (made mutable by `OptionsAccessor`), so it shows up in Options -> Controls ->
 *   Key Binds, can be rebound there and is driven by vanilla's key handling.
 * - Categories: one `KeyMapping.Category` per server and category, id `kiln:server/<server>/<category>`
 *   (extension keybinds use the extension's display name as category); removed again from
 *   `KeyMapping.Category.SORT_ORDER` (`KeyMappingCategoryAccessor`) when their last mapping goes.
 * - Display names: the mapping names are internal (`kiln.server.<id>` / `kiln.ext.<ext>.<id>`); their
 *   display strings and the category labels ("Server: <category>") come from [DynamicTranslations] via
 *   `ClientLanguageMixin`.
 * - Persistence: [KeybindStore] (`config/kiln-keybinds.json`, per server address and keybind id; extension
 *   keybinds as `ext:<ext>:<id>`). Written when vanilla saves the options (controls screen closed) and on removal;
 *   the mappings are kept out of options.txt.
 * - Removal: the mappings are taken out of `Options.keyMappings` and `KeyMapping.ALL`, then
 *   `KeyMapping.resetMapping()` rebuilds vanilla's key -> mapping index.
 * - Press/release edges are sampled every client tick from [KeyMapping.isDown]. Vanilla only updates that state while
 *   no screen is open (chat, text fields, inventories release all keys), so keybinds never fire while typing.
 *   Inside a kiln screen without a focused text field, [onScreenKey] drives the state instead.
 */
object ServerKeybinds {
    private val logger = LoggerFactory.getLogger("kiln")
    private const val SERVER_PREFIX = "kiln.server."
    private const val EXTENSION_PREFIX = "kiln.ext."

    private lateinit var store: KeybindStore

    private val categories = HashMap<String, KeyMapping.Category>()
    private val active = LinkedHashMap<String, Active>()
    private val edges = KeyEdgeTracker()
    private var server: String = ""

    /** Owner of an extension keybind; receives its press/release edges. */
    interface Handler {
        fun onKey(id: String, pressed: Boolean)
    }

    private class Active(
        val storeId: String,
        val mapping: KeyMapping,
        val defaultKeyName: String,
        val extension: String?,
        val localId: String,
        val handler: Handler?,
    )

    fun init(store: KeybindStore) {
        this.store = store
        store.load()
    }

    /** Server address the keybinds of this connection are persisted under. */
    fun setServer(serverKey: String) {
        if (serverKey != server) clear()
        server = serverKey
    }

    /** Replaces the server-declared keybinds with [declarations] for [serverKey] (the server's address). */
    fun declare(serverKey: String, declarations: List<KeybindDeclaration>) {
        setServer(serverKey)
        remove { it.extension == null }
        val created = ArrayList<KeyMapping>()
        declarations.forEachIndexed { index, declaration ->
            if (declaration.id in active) {
                logger.warn("Ignoring duplicate server keybind '{}'", declaration.id)
                return@forEachIndexed
            }
            val entry = create(declaration.id, SERVER_PREFIX + declaration.id, declaration.name, declaration.category, declaration.defaultKey, index, null, declaration.id, null)
            active[declaration.id] = entry
            created += entry.mapping
        }
        addMappings(created)
        logger.info("Registered {} server keybinds", created.size)
    }

    /**
     * Registers keybind [id] of Lua extension [extension] (display [name], [defaultKey] as `InputConstants` name) in
     * category [category]. Edges go to [handler].
     */
    fun registerExtension(extension: String, id: String, name: String, category: String, defaultKey: String, handler: Handler) {
        val key = "ext:$extension:$id"
        require(key !in active) { "keybind '$id' is already bound" }
        val index = active.values.count { it.extension == extension }
        val entry = create(key, "$EXTENSION_PREFIX$extension.$id", name, category, defaultKey, 1000 + index, extension, id, handler)
        active[key] = entry
        addMappings(listOf(entry.mapping))
    }

    /** Removes the keybinds of [extension] (unloaded). */
    fun removeExtension(extension: String) = remove { it.extension == extension }

    /** Removes all keybinds (disconnect); bindings are persisted first. */
    fun clear() {
        remove { true }
        clearCategories()
    }

    private fun create(
        storeId: String, name: String, displayName: String, categoryName: String, defaultKeyName: String, order: Int,
        extension: String?, localId: String, handler: Handler?,
    ): Active {
        val defaultKey = parseKey(defaultKeyName)
        DynamicTranslations.put(name, displayName)
        val mapping = KeyMapping(name, defaultKey.type, defaultKey.value, category(categoryName), order)
        store.get(server, storeId)?.let { stored ->
            runCatching { InputConstants.getKey(stored) }.onSuccess(mapping::setKey)
                .onFailure { logger.warn("Invalid stored key '{}' for keybind '{}'", stored, storeId) }
        }
        return Active(storeId, mapping, defaultKey.name, extension, localId, handler)
    }

    private fun addMappings(created: List<KeyMapping>) {
        if (created.isNotEmpty()) {
            val options = Minecraft.getInstance().options
            (options as OptionsAccessor).`kiln$setKeyMappings`(options.keyMappings + created)
        }
        KeyMapping.resetMapping()
    }

    private fun remove(filter: (Active) -> Boolean) {
        val removedEntries = active.filterValues(filter)
        if (removedEntries.isEmpty()) return
        for (entry in removedEntries.values) store.set(server, entry.storeId, entry.mapping.saveString(), entry.defaultKeyName)
        store.save()
        val removed = removedEntries.values.map { it.mapping }.toSet()
        val options = Minecraft.getInstance().options
        (options as OptionsAccessor).`kiln$setKeyMappings`(options.keyMappings.filterNot { it in removed }.toTypedArray())
        val all = KeyMappingAccessor.`kiln$getAll`()
        for ((key, entry) in removedEntries) {
            if (all[entry.mapping.name] === entry.mapping) all.remove(entry.mapping.name)
            DynamicTranslations.remove(entry.mapping.name)
            active.remove(key)
            edges.forget(key)
        }
        // drop categories that have no mapping left
        val used = active.values.mapTo(HashSet()) { it.mapping.category }
        val unused = categories.filterValues { it !in used }
        if (unused.isNotEmpty()) {
            KeyMappingCategoryAccessor.`kiln$getSortOrder`().removeIf { it in unused.values }
            unused.forEach { (path, category) ->
                DynamicTranslations.remove(category.id().toLanguageKey("key.category"))
                categories.remove(path)
            }
        }
        KeyMapping.resetMapping()
    }

    /**
     * Samples press/release edges; called every client tick. Server keybinds are sent with [send] (only if
     * [sendToServer]), extension keybinds go to their handler.
     */
    fun tick(sendToServer: Boolean, send: (ServerboundMessage) -> Unit) {
        val fired = ArrayList<Pair<Active, Boolean>>()
        for ((key, entry) in active) {
            val edge = edges.update(key, entry.mapping.isDown) ?: continue
            fired += entry to edge
        }
        // dispatched after sampling: a handler may unload its extension (and remove keybinds) while we iterate
        for ((entry, pressed) in fired) {
            val handler = entry.handler
            if (handler == null) {
                if (sendToServer) send(if (pressed) KeyPressed(entry.localId) else KeyReleased(entry.localId))
            } else if (active[entry.storeId] === entry) {
                handler.onKey(entry.localId, pressed)
            }
        }
    }

    /** Key event inside a kiln screen (no text field focused): updates the matching mappings' state. */
    fun onScreenKey(event: KeyEvent, down: Boolean) {
        for (entry in active.values) if (entry.mapping.matches(event)) entry.mapping.isDown = down
    }

    fun onScreenMouse(event: MouseButtonEvent, down: Boolean) {
        for (entry in active.values) if (entry.mapping.matchesMouse(event)) entry.mapping.isDown = down
    }

    private fun persist() {
        if (!::store.isInitialized) return
        for (entry in active.values) store.set(server, entry.storeId, entry.mapping.saveString(), entry.defaultKeyName)
    }

    /** Called by `OptionsMixin` before options.txt is written. */
    @JvmStatic
    fun onOptionsSave() {
        if (active.isEmpty()) return
        persist()
        store.save()
    }

    /** [mappings] without the runtime keybinds (same array if there are none). Called by `OptionsMixin`. */
    @JvmStatic
    fun withoutServerMappings(mappings: Array<KeyMapping>): Array<KeyMapping> {
        if (active.isEmpty()) return mappings
        val ours = active.values.mapTo(HashSet()) { it.mapping }
        return mappings.filterNot { it in ours }.toTypedArray()
    }

    private fun category(name: String): KeyMapping.Category {
        val path = "server/" + sanitize(server) + "/" + sanitize(name)
        categories[path]?.let { return it }
        val id = Identifier.fromNamespaceAndPath("kiln", path)
        // a category left over from an earlier session (should not happen, removal drops them) is replaced
        KeyMappingCategoryAccessor.`kiln$getSortOrder`().removeIf { it.id() == id }
        val category = KeyMapping.Category.register(id)
        DynamicTranslations.put(category.id().toLanguageKey("key.category"), "Server: $name")
        categories[path] = category
        return category
    }

    /** Unregisters the per-server categories (vanilla keeps them in `KeyMapping.Category.SORT_ORDER`). */
    private fun clearCategories() {
        if (categories.isEmpty()) return
        val ours = categories.values.toSet()
        KeyMappingCategoryAccessor.`kiln$getSortOrder`().removeIf { it in ours }
        ours.forEach { DynamicTranslations.remove(it.id().toLanguageKey("key.category")) }
        categories.clear()
    }

    /** Identifier-safe, collision-resistant form of a category name. */
    private fun sanitize(name: String): String {
        val readable = name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9_.-]+"), "_").trim('_').take(48)
        return readable + "_" + Integer.toHexString(name.hashCode())
    }

    private fun parseKey(name: String): InputConstants.Key = try {
        InputConstants.getKey(name)
    } catch (e: RuntimeException) {
        logger.warn("Unknown default key '{}', keybind left unbound", name)
        InputConstants.UNKNOWN
    }
}
