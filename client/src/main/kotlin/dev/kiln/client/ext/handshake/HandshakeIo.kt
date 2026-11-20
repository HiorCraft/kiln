package dev.kiln.client.ext.handshake

import dev.kiln.client.ext.ExtensionPackage
import dev.kiln.client.ext.trust.TrustGrant
import dev.kiln.protocol.message.ExtensionInfo
import dev.kiln.protocol.message.ServerboundMessage

/** What an [ExtensionHandshake] needs from the connection, the stores and the game. */
interface HandshakeIo {
    fun send(message: ServerboundMessage)
    fun isTrusted(grants: List<TrustGrant>): Boolean
    fun trust(grants: List<TrustGrant>)
    fun cached(info: ExtensionInfo): ByteArray?
    fun store(info: ExtensionInfo, zip: ByteArray)

    /** Show the consent screen for [infos]; the answer comes back through [ExtensionHandshake.consent]. */
    fun askConsent(infos: List<ExtensionInfo>)

    /** Show the loading screen (download/verify/start progress). */
    fun showProgress()

    /** Prepares loading (fresh state) before the first [load]. */
    fun beginLoad()

    /** Loads one verified package; throws [ExtensionLoadException]. */
    fun load(pkg: ExtensionPackage)

    /** Undo everything [load] did (failure). */
    fun unloadAll()

    fun disconnect(reason: String)

    /** `true` if a Fabric mod with this id is installed (its namespace is off limits for extensions). */
    fun isModLoaded(id: String): Boolean = false

    /** `true` if a loaded resource pack other than the extensions' own provides namespace [id] (mods, packs). */
    fun isResourceNamespace(id: String): Boolean = false

    /**
     * Mounts [assets] (paths relative to `assets/` of all verified packages) as resource pack and reloads the
     * game's resources, before any extension is started; calls [done] on the client thread with `null` or a
     * readable error. Called when a package has assets, or with none to drop [resourcesMounted] ones.
     */
    fun reloadResources(assets: Map<String, ByteArray>, done: (error: String?) -> Unit) = done(null)

    /** `true` while something (Minecraft's resource reload overlay) hides the loading screen. */
    fun screenCovered(): Boolean = false

    /** `true` while resources of an earlier handshake are mounted (a backend switch behind a proxy). */
    fun resourcesMounted(): Boolean = false
}
