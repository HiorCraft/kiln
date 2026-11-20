package dev.kiln.protocol.codec

import dev.kiln.protocol.ProtocolException
import kotlin.reflect.KClass

/**
 * Maps the wire type id of every subtype of [T] to its codec [C], and every subtype to its id. [what] names the type
 * family in error messages.
 */
internal class CodecRegistry<T : Any, C : Any>(private val what: String) {
    private val codecsById = HashMap<Int, C>()
    private val idsByType = HashMap<KClass<out T>, Int>()

    fun register(id: Int, type: KClass<out T>, codec: C) {
        check(codecsById.put(id, codec) == null) { "Duplicate $what type id $id" }
        check(idsByType.put(type, id) == null) { "Duplicate $what type ${type.simpleName}" }
    }

    fun idOf(value: T): Int =
        idsByType[value::class] ?: throw ProtocolException("No codec for $what ${value::class.simpleName}")

    fun codecFor(id: Int): C = codecsById[id] ?: throw ProtocolException("Unknown $what type $id")
}

/** Registers [codec] for subtype [S] under [id], e.g. `add(1, ScreenClick)` with the type's companion codec. */
@Suppress("UNCHECKED_CAST")
@JvmName("addCodec")
internal inline fun <T : Any, reified S : T> CodecRegistry<T, Codec<T>>.add(id: Int, codec: Codec<S>) {
    register(id, S::class, codec as Codec<T>)
}

/** Registers the depth-aware [codec] of subtype [S] (a container element) under [id]. */
@Suppress("UNCHECKED_CAST")
@JvmName("addDepthCodec")
internal inline fun <T : Any, reified S : T> CodecRegistry<T, DepthCodec<T>>.add(id: Int, codec: DepthCodec<S>) {
    register(id, S::class, codec as DepthCodec<T>)
}

/** Registers the [codec] of a subtype that does not recurse (a leaf element) among depth-aware codecs. */
@JvmName("addLeafCodec")
internal inline fun <T : Any, reified S : T> CodecRegistry<T, DepthCodec<T>>.add(id: Int, codec: Codec<S>) {
    add(id, DepthIgnoringCodec(codec))
}
