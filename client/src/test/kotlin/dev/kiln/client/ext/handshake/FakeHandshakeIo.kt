package dev.kiln.client.ext.handshake

import dev.kiln.client.ext.ExtensionLoadException
import dev.kiln.client.ext.ExtensionPackage
import dev.kiln.client.ext.trust.TrustGrant
import dev.kiln.protocol.message.ExtensionInfo
import dev.kiln.protocol.message.ServerboundMessage

/** Records what an [ExtensionHandshake] asks of its [HandshakeIo]; loading and reloading are scriptable. */
internal class FakeHandshakeIo(var trusted: Boolean = false, val cache: MutableMap<String, ByteArray> = HashMap()) : HandshakeIo {
    val sent = ArrayList<ServerboundMessage>()
    val events = ArrayList<String>()
    var failLoad: String? = null

    override fun send(message: ServerboundMessage) { sent += message; events += "send:${message::class.simpleName}" }
    override fun isTrusted(grants: List<TrustGrant>) = trusted
    override fun trust(grants: List<TrustGrant>) { events += "trust:${grants.map { it.id }}" }
    override fun cached(info: ExtensionInfo) = cache[info.id]
    override fun store(info: ExtensionInfo, zip: ByteArray) { cache[info.id] = zip; events += "store:${info.id}" }
    override fun askConsent(infos: List<ExtensionInfo>) { events += "consent:${infos.map { it.id }}" }
    override fun showProgress() { events += "progress" }
    override fun beginLoad() { events += "begin" }
    override fun load(pkg: ExtensionPackage) {
        failLoad?.let { if (pkg.id == it) throw ExtensionLoadException("$it: boom") }
        events += "load:${pkg.id}"
    }
    override fun unloadAll() { events += "unload" }
    override fun disconnect(reason: String) { events += "disconnect:$reason" }
    var reloadDone: ((String?) -> Unit)? = null
    override fun reloadResources(assets: Map<String, ByteArray>, done: (error: String?) -> Unit) { events += "reload:${assets.keys.sorted()}"; reloadDone = done }
    val mods = HashSet<String>()
    override fun isModLoaded(id: String) = id in mods
    val packNamespaces = HashSet<String>()
    override fun isResourceNamespace(id: String) = id in packNamespaces
    var mounted = false
    override fun resourcesMounted() = mounted
    var covered = false
    override fun screenCovered() = covered
}
