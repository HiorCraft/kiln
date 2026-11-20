package dev.kiln.protocol

/**
 * Version of the wire format. Client and server compare it during the handshake ([Hello] / [Welcome]) in the
 * configuration phase; on a mismatch the server disconnects the player (see `docs/lua-extensions.md`).
 *
 * v3: Lua extension packages delivered in the configuration phase ([ExtensionManifest], [ExtensionRequest],
 * [ExtensionChunk], [ExtensionsReady]); [ExtensionMessage] carries JSON text instead of raw bytes.
 * v4: [ExtensionInfo.icon] in the manifest; the client asks for consent before it sends [ExtensionRequest], so
 * nothing is downloaded without consent.
 * v5: [ExtensionInfo.dependencies] (manifest in dependency order), permissions `screens` and `inventory`, larger
 * clientbound [ExtensionMessage]s ([ProtocolLimits.MAX_CLIENTBOUND_EXTENSION_JSON_LENGTH]).
 * v6: resource-pack trees in packages (`assets/<id>/…`, [AssetRules]), permission `resources`, larger packages
 * ([ProtocolLimits.MAX_EXTENSION_PACKAGE_BYTES]), [SetPostEffect].
 * v7: [CreativeTabs], [EntityTraits], [EntityTraitsRemove] (furniture).
 * v8: [EntityAppearance], [PlayAnimation], [StopAnimation] (custom entities).
 * v9: one codec per type (`dev.kiln.protocol.codec.codecs`); the tree-wide element rules (count, unique ids) are
 * checked for the whole tree, and validation failures are reported by the codec of the offending value.
 */
public const val PROTOCOL_VERSION: Int = 9

/** The single plugin-messaging / custom-payload channel used in both directions. */
public const val CHANNEL: String = "kiln:main"

/** Namespace of [CHANNEL]. */
public const val CHANNEL_NAMESPACE: String = "kiln"

/** Path of [CHANNEL]. */
public const val CHANNEL_PATH: String = "main"
