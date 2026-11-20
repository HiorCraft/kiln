package dev.kiln.client.keybind

import com.mojang.blaze3d.platform.InputConstants
import dev.kiln.client.mixin.KeyMappingAccessor
import dev.kiln.client.mixin.KeyMappingCategoryAccessor
import dev.kiln.client.mixin.OptionsAccessor
import dev.kiln.protocol.message.KeyPressed
import dev.kiln.protocol.message.KeyReleased
import dev.kiln.protocol.message.KeybindDeclaration
import dev.kiln.protocol.message.ServerboundMessage
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

    /** Extension keybinds are sorted after the server's own ones. */
    private const val EXTENSION_ORDER_BASE = 1000

    private lateinit var store: KeybindStore

    private val categories = HashMap<String, KeyMapping.Category>()
    private val active = LinkedHashMap<String, ActiveKeybind>()
    private val edges = KeyEdgeTracker()
    private var server: String = ""

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
            val entry = create(
                storeId = declaration.id, name = SERVER_PREFIX + declaration.id, displayName = declaration.name,
                categoryName = declaration.category, defaultKeyName = declaration.defaultKey, order = index,
                extension = null, localId = declaration.id, handler = null,
            )
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
    fun registerExtension(extension: String, id: String, name: String, category: String, defaultKey: String, handler: KeybindHandler) {
        val key = "ext:$extension:$id"
        require(key !in active) { "keybind '$id' is already bound" }
        val order = EXTENSION_ORDER_BASE + active.values.count { it.extension == extension }
        val entry = create(
            storeId = key, name = "$EXTENSION_PREFIX$extension.$id", displayName = name, categoryName = category,
            defaultKeyName = defaultKey, order = order, extension = extension, localId = id, handler = handler,
        )
        active[key] = entry
        addMappings(listOf(entry.mapping))
    }

    /** Removes the keybinds of [extension] (unloaded). */
    fun removeExtension(extension: String) = remove { it.extension == extension }

    /** Removes all keybinds (disconnect); bindings are persisted first. */
    fun clear() {
        remove { true }
        unregisterCategories(categories.values.toSet())
    }

    /**
     * Creates the mapping of keybind [storeId] with its stored key, or [defaultKeyName] unless another keybind uses
     * that key already.
     */
    private fun create(
        storeId: String, name: String, displayName: String, categoryName: String, defaultKeyName: String, order: Int,
        extension: String?, localId: String, handler: KeybindHandler?,
    ): ActiveKeybind {
        val defaultKey = parseKey(defaultKeyName)
        DynamicTranslations.put(name, displayName)
        val taken = Minecraft.getInstance().options.keyMappings.map { it.saveString() }
        val mapping = KeyMapping(name, defaultKey.type, defaultKey.value, category(categoryName), order)
        val stored = store.get(server, storeId)
        if (stored != null) {
            runCatching { InputConstants.getKey(stored) }.onSuccess(mapping::setKey)
                .onFailure { logger.warn("Invalid stored key '{}' for keybind '{}'", stored, storeId) }
        } else if (KeyConflicts.startsUnbound(defaultKey.name, taken)) {
            // a server must not take over a key the game, a mod or another keybind already uses (W, attack, ...)
            logger.info("Keybind '{}' starts unbound: its default key {} is already used", storeId, defaultKey.name)
            mapping.setKey(InputConstants.UNKNOWN)
        }
        return ActiveKeybind(storeId, mapping, defaultKey.name, extension, localId, handler)
    }

    private fun addMappings(created: List<KeyMapping>) {
        if (created.isNotEmpty()) setKeyMappings(Minecraft.getInstance().options.keyMappings + created)
        KeyMapping.resetMapping()
    }

    private fun setKeyMappings(mappings: Array<KeyMapping>) {
        (Minecraft.getInstance().options as OptionsAccessor).`kiln$setKeyMappings`(mappings)
    }

    private fun remove(filter: (ActiveKeybind) -> Boolean) {
        val removedEntries = active.filterValues(filter)
        if (removedEntries.isEmpty()) return
        removedEntries.values.forEach(::persist)
        store.save()
        val removed = removedEntries.values.map { it.mapping }.toSet()
        setKeyMappings(Minecraft.getInstance().options.keyMappings.filterNot { it in removed }.toTypedArray())
        val all = KeyMappingAccessor.`kiln$getAll`()
        for ((key, entry) in removedEntries) {
            if (all[entry.mapping.name] === entry.mapping) all.remove(entry.mapping.name)
            DynamicTranslations.remove(entry.mapping.name)
            active.remove(key)
            edges.forget(key)
        }
        val used = active.values.mapTo(HashSet()) { it.mapping.category }
        unregisterCategories(categories.values.filterTo(HashSet()) { it !in used })
        KeyMapping.resetMapping()
    }

    private fun persist(entry: ActiveKeybind) {
        store.set(server, entry.storeId, entry.mapping.saveString(), entry.defaultKeyName)
    }

    /**
     * Samples press/release edges; called every client tick. Server keybinds are sent with [send] (only if
     * [sendToServer]), extension keybinds go to their handler.
     */
    fun tick(sendToServer: Boolean, send: (ServerboundMessage) -> Unit) {
        val fired = ArrayList<Pair<ActiveKeybind, Boolean>>()
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

    /** Called by `OptionsMixin` before options.txt is written. */
    @JvmStatic
    fun onOptionsSave() {
        if (active.isEmpty()) return
        active.values.forEach(::persist)
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
        DynamicTranslations.put(categoryLabelKey(category), "Server: $name")
        categories[path] = category
        return category
    }

    /** Unregisters per-server [unused] categories (vanilla keeps them in `KeyMapping.Category.SORT_ORDER`). */
    private fun unregisterCategories(unused: Set<KeyMapping.Category>) {
        if (unused.isEmpty()) return
        KeyMappingCategoryAccessor.`kiln$getSortOrder`().removeIf { it in unused }
        unused.forEach { DynamicTranslations.remove(categoryLabelKey(it)) }
        categories.values.removeAll(unused)
    }

    private fun categoryLabelKey(category: KeyMapping.Category): String = category.id().toLanguageKey("key.category")

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
