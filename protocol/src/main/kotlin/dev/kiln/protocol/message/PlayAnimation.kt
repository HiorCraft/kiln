package dev.kiln.protocol.message

import dev.kiln.protocol.ProtocolException
import dev.kiln.protocol.codec.Codec
import dev.kiln.protocol.codec.FloatCodec
import dev.kiln.protocol.codec.NumberChecks
import dev.kiln.protocol.codec.ProtocolReader
import dev.kiln.protocol.codec.ProtocolWriter
import dev.kiln.protocol.codec.RangedVarIntCodec
import dev.kiln.protocol.codec.StringCodec
import dev.kiln.protocol.codec.VarIntCodec

/** Play phase (v8): plays animation [name] of the entity's model ([AnimationMode], [speed] 0..8). */
public data class PlayAnimation(
    val entityId: Int,
    val name: String,
    val mode: Int,
    val speed: Float,
) : ClientboundMessage {
    /** Wire codec: entity id, animation name, [AnimationMode], speed (0..8). */
    public companion object : Codec<PlayAnimation> {
        private val SPEED = 0f..8f

        private val mode = RangedVarIntCodec(AnimationMode.ONCE, AnimationMode.HOLD, "animation mode")
        private val speed = FloatCodec.validated { NumberChecks.checkWithin(it, SPEED, "Animation speed") }

        override fun encode(writer: ProtocolWriter, value: PlayAnimation) {
            VarIntCodec.encode(writer, value.entityId)
            animationNameCodec.encode(writer, value.name)
            mode.encode(writer, value.mode)
            speed.encode(writer, value.speed)
        }

        override fun decode(reader: ProtocolReader): PlayAnimation = PlayAnimation(
            entityId = VarIntCodec.decode(reader),
            name = animationNameCodec.decode(reader),
            mode = mode.decode(reader),
            speed = speed.decode(reader),
        )
    }
}

private const val MAX_ANIMATION_NAME_LENGTH = 64
private val ANIMATION_NAME = Regex("[a-z0-9_.]{1,$MAX_ANIMATION_NAME_LENGTH}")

/** An animation name of the entity model, `[a-z0-9_.]{1,64}`; shared with [StopAnimation]. */
internal val animationNameCodec: Codec<String> =
    StringCodec(MAX_ANIMATION_NAME_LENGTH, "animation").validated(::checkAnimationName)

private fun checkAnimationName(name: String) {
    if (!ANIMATION_NAME.matches(name)) {
        throw ProtocolException("Invalid animation name '${name.take(MAX_ANIMATION_NAME_LENGTH)}'")
    }
}
