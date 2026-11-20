package dev.kiln.client.ext.handshake

import dev.kiln.client.ext.ExtensionLoadException
import dev.kiln.client.ext.ExtensionNamespaces
import dev.kiln.client.ext.ExtensionPackage
import dev.kiln.client.ext.ExtensionPackageException
import dev.kiln.client.ext.trust.TrustGrant
import dev.kiln.protocol.message.ExtensionChunk
import dev.kiln.protocol.message.ExtensionManifest
import dev.kiln.protocol.message.ExtensionRequest
import dev.kiln.protocol.message.ExtensionsReady
import dev.kiln.protocol.PROTOCOL_VERSION
import dev.kiln.protocol.ProtocolLimits

/**
 * Client side of the configuration-phase handshake after `Hello`, as a pure state machine (no Minecraft):
 *
 * `Welcome` -> `ExtensionManifest` -> **consent** (skipped if already trusted) -> `ExtensionRequest` (only ids that
 * are not cached) -> `ExtensionChunk`s -> per tick: verify one package, then start (load) one extension ->
 * `ExtensionsReady(true)`. Nothing is requested before consent; declining disconnects. Any failure sends
 * `ExtensionsReady(false, reason)` (the server disconnects with that reason).
 *
 * [items] and the byte counters feed the loading screen.
 */
class ExtensionHandshake(
    private val io: HandshakeIo,
    /** All packages together, unpacked (held in memory); a server sending more fails the handshake. */
    private val maxTotalUnpackedBytes: Long = ProtocolLimits.MAX_TOTAL_UNPACKED_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * The resource reload covers the screen with Minecraft's loading overlay: it starts only after the loading
     * screen was shown this long, so the player sees what is downloaded and checked even when it is fast.
     */
    private val minProgressMillis: Long = 0,
    /** Everything loaded: the bar stays at 100 % this long before readiness is reported (the world follows at once). */
    private val minFinishMillis: Long = 0,
) {
    private var finishedAt = 0L
    private var unpackedBytes = 0L
    private var progressShownAt = 0L

    var phase: HandshakePhase = HandshakePhase.WAIT_WELCOME
        private set

    /** The extensions' resource pack is mounted (or there is none); Lua starts only then. */
    private var resourcesReady = false

    var items: List<HandshakeItem> = emptyList()
        private set

    var error: String? = null
        private set

    private val isFinished: Boolean
        get() = phase == HandshakePhase.DONE || phase == HandshakePhase.FAILED || phase == HandshakePhase.DECLINED

    val totalBytes: Long get() = items.sumOf { it.info.size.toLong() }
    val receivedBytes: Long get() = items.sumOf { if (it.isDownloadPending) it.received.toLong() else it.info.size.toLong() }

    /** 0..1: download 50 %, verification 15 %, resources 15 %, start of the extensions 20 %. */
    val progress: Float
        get() {
            if (items.isEmpty()) return if (phase == HandshakePhase.DONE) 1f else 0f
            if (items.all { it.status == HandshakeItemStatus.DONE }) return 1f
            val bytes = if (totalBytes == 0L) 1f else receivedBytes.toFloat() / totalBytes
            val verified = fractionOf { it.status == HandshakeItemStatus.STARTING || it.status == HandshakeItemStatus.DONE }
            val resources = if (resourcesReady) 1f else 0f
            val started = fractionOf { it.status == HandshakeItemStatus.DONE }
            return (bytes * 0.5f + verified * 0.15f + resources * 0.15f + started * 0.2f).coerceIn(0f, 1f)
        }

    private fun fractionOf(predicate: (HandshakeItem) -> Boolean): Float = items.count(predicate).toFloat() / items.size

    private val grants: List<TrustGrant> get() = items.map { TrustGrant(it.info.id, it.info.permissions.toSet()) }

    fun onWelcome(version: Int) {
        if (phase != HandshakePhase.WAIT_WELCOME) return fail("unexpected Welcome")
        if (version != PROTOCOL_VERSION) return fail("protocol mismatch (server $version, client $PROTOCOL_VERSION)")
        phase = HandshakePhase.WAIT_MANIFEST
    }

    fun onManifest(manifest: ExtensionManifest) {
        if (phase != HandshakePhase.WAIT_MANIFEST) return fail("unexpected extension manifest")
        val infos = manifest.extensions
        if (infos.map { it.id }.toSet().size != infos.size) return fail("duplicate extension ids in the manifest")
        // checked before consent: the download buffers follow the announced sizes
        val announced = infos.sumOf { it.size.toLong() }
        if (announced > maxTotalUnpackedBytes) return fail("the server announces ${mib(announced)} MiB of extensions (limit ${mib(maxTotalUnpackedBytes)} MiB)")
        // before consent and download: an extension must not take over the namespace of the game, kiln or a mod
        for (info in infos) ExtensionNamespaces.problem(info.id, io::isModLoaded, io::isResourceNamespace)?.let { return fail(it) }
        items = infos.map(::HandshakeItem)
        if (io.isTrusted(grants)) {
            startDownload()
        } else {
            phase = HandshakePhase.CONSENT
            io.askConsent(infos)
        }
    }

    /** The player's answer on the consent screen. */
    fun consent(accepted: Boolean) {
        if (phase != HandshakePhase.CONSENT) return
        if (!accepted) {
            phase = HandshakePhase.DECLINED
            io.disconnect("Server-Erweiterungen abgelehnt")
            return
        }
        io.trust(grants)
        startDownload()
    }

    /** The player cancelled on the loading screen. */
    fun cancel() {
        if (isFinished) return
        phase = HandshakePhase.DECLINED
        io.unloadAll()
        io.disconnect("Laden der Server-Erweiterungen abgebrochen")
    }

    private fun startDownload() {
        val missing = ArrayList<String>()
        for (item in items) {
            val cached = io.cached(item.info)
            if (cached != null) {
                item.zip = cached
                item.status = HandshakeItemStatus.CACHED
            } else {
                item.assembler = ChunkAssembler(item.info)
                missing += item.info.id
            }
        }
        phase = HandshakePhase.DOWNLOADING
        progressShownAt = clock()
        io.showProgress()
        io.send(ExtensionRequest(missing))
        items.firstOrNull { it.status == HandshakeItemStatus.QUEUED }?.status = HandshakeItemStatus.DOWNLOADING
        if (missing.isEmpty()) phase = HandshakePhase.LOADING
    }

    fun onChunk(chunk: ExtensionChunk) {
        if (phase != HandshakePhase.DOWNLOADING) return fail("unexpected chunk for '${chunk.id}'")
        val item = items.firstOrNull { it.status == HandshakeItemStatus.DOWNLOADING } ?: return fail("unexpected chunk for '${chunk.id}'")
        val zip = try {
            item.assembler!!.add(chunk)
        } catch (e: ExtensionPackageException) {
            item.fail(e.message)
            return fail(e.message ?: "download failed")
        }
        item.received = minOf(item.info.size, item.received + chunk.data.size)
        if (zip == null) return
        io.store(item.info, zip)
        item.zip = zip
        item.assembler = null
        item.status = HandshakeItemStatus.VERIFYING
        val next = items.firstOrNull { it.status == HandshakeItemStatus.QUEUED }
        if (next != null) next.status = HandshakeItemStatus.DOWNLOADING else phase = HandshakePhase.LOADING
    }

    /**
     * Advances loading by one step (called every client tick, so the loading screen shows each state): verify the
     * next package, or mount the resources, or start the next verified extension, or report readiness.
     */
    fun tick() {
        if (phase != HandshakePhase.LOADING) return
        val toVerify = items.firstOrNull { it.pkg == null && it.zip != null }
        if (toVerify != null) return verifyStep(toVerify)
        // every package verified: mount their resources before any Lua runs (main.lua may use its sounds/fonts)
        if (!resourcesReady && !mountResources()) return
        if (items.none { it.status == HandshakeItemStatus.DONE }) io.beginLoad()
        val toStart = items.firstOrNull { it.status == HandshakeItemStatus.STARTING }
        if (toStart != null) return startStep(toStart)
        if (items.all { it.status == HandshakeItemStatus.DONE }) holdFinishedScreen()
    }

    private fun verifyStep(item: HandshakeItem) {
        item.status = HandshakeItemStatus.VERIFYING
        try {
            val pkg = verify(item)
            unpackedBytes += pkg.files.values.sumOf { it.size.toLong() }
            if (unpackedBytes > maxTotalUnpackedBytes) {
                throw ExtensionPackageException("the server's extensions are larger than ${mib(maxTotalUnpackedBytes)} MiB unpacked")
            }
            item.pkg = pkg
            item.zip = null
            item.status = HandshakeItemStatus.STARTING
        } catch (e: ExtensionPackageException) {
            item.fail(e.message)
            fail(e.message ?: "invalid package")
        }
    }

    /**
     * Mounts the verified packages' assets (or drops those of an earlier handshake) once the loading screen was shown
     * long enough; `true` if loading may go on in this tick (nothing to mount).
     */
    private fun mountResources(): Boolean {
        val assets = LinkedHashMap<String, ByteArray>()
        for (item in items) item.pkg?.assets?.let(assets::putAll)
        if (assets.isEmpty() && !io.resourcesMounted()) {
            resourcesReady = true
            return true
        }
        if (clock() - progressShownAt < minProgressMillis) return false
        phase = HandshakePhase.RELOADING
        io.reloadResources(assets, ::onResourcesReloaded)
        return false
    }

    private fun onResourcesReloaded(error: String?) {
        if (phase != HandshakePhase.RELOADING) return
        if (error == null) {
            resourcesReady = true
            phase = HandshakePhase.LOADING
        } else {
            io.unloadAll()
            fail("Ressourcen konnten nicht geladen werden: $error")
        }
    }

    private fun startStep(item: HandshakeItem) {
        try {
            io.load(item.pkg!!)
            item.status = HandshakeItemStatus.DONE
        } catch (e: ExtensionLoadException) {
            item.fail(e.message)
            io.unloadAll()
            // extensions that import the failed one cannot load either (no fallbacks)
            val dependents = dependentsOf(item.info.id)
            for (dependent in items) if (dependent.info.id in dependents) dependent.fail("needs ${item.info.id}")
            val suffix = if (dependents.isEmpty()) "" else " (needed by ${dependents.joinToString()})"
            fail((e.message ?: "extension failed to load") + suffix)
        }
    }

    /** The hold at 100 % starts once the loading screen is visible again (the reload overlay fades out first). */
    private fun holdFinishedScreen() {
        if (io.screenCovered()) {
            finishedAt = 0L
            return
        }
        if (finishedAt == 0L) finishedAt = clock()
        if (clock() - finishedAt >= minFinishMillis) ready()
    }

    private fun ready() {
        phase = HandshakePhase.DONE
        io.send(ExtensionsReady(true))
    }

    /** Ids of the manifest entries that (transitively) depend on [id]. */
    fun dependentsOf(id: String): List<String> {
        val result = LinkedHashSet<String>()
        var changed = true
        while (changed) {
            changed = false
            for (item in items) {
                val itemId = item.info.id
                if (itemId !in result && item.info.dependencies.any { it == id || it in result }) {
                    result += itemId
                    changed = true
                }
            }
        }
        return result.toList()
    }

    private fun verify(item: HandshakeItem): ExtensionPackage {
        val info = item.info
        val pkg = ExtensionPackage.read(item.zip!!, info.id)
        if (pkg.permissions != info.permissions.toSet()) {
            throw ExtensionPackageException("${info.id}: permissions in extension.json differ from the manifest")
        }
        if (pkg.version != info.version || pkg.name != info.name) {
            throw ExtensionPackageException("${info.id}: name/version in extension.json differ from the manifest")
        }
        if (pkg.dependencies != info.dependencies) {
            throw ExtensionPackageException("${info.id}: dependencies in extension.json differ from the manifest")
        }
        return pkg
    }

    private fun fail(reason: String) {
        if (isFinished) return
        phase = HandshakePhase.FAILED
        error = reason
        io.send(ExtensionsReady(false, reason.take(ProtocolLimits.MAX_EXTENSION_ERROR_LENGTH)))
    }

    private fun mib(bytes: Long): Long = bytes / 1024 / 1024
}
