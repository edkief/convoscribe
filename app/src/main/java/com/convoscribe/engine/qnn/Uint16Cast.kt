package com.convoscribe.engine.qnn

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.ByteArrayOutputStream
import java.nio.ShortBuffer

/**
 * Makes uint16 tensors, which the w8a16 Whisper NPU build takes
 * (`input_features`, `attention_mask`, issue #159) but onnxruntime's Java
 * API cannot create: it has no unsigned 16-bit type, and a session
 * refuses an int16 tensor for a uint16 input.
 *
 * A one-node model on the CPU, `Cast` int16 → uint16, turns an int16
 * tensor of the same bits into one: a cast between integers of the same
 * width keeps every bit, so 0xFFFF goes in as -1 and comes out as 65535.
 * The values are tiny next to a Whisper window (microseconds).
 *
 * Not thread-safe; [close] once done.
 */
internal class Uint16Cast(private val env: OrtEnvironment) : AutoCloseable {

    private val session: OrtSession = OrtSession.SessionOptions().use { env.createSession(MODEL, it) }

    /**
     * Run [block] with a uint16 tensor of [shape] holding the bits of
     * [data] (from its start); the tensor is valid only inside [block].
     */
    fun <T> withUint16(data: ShortBuffer, shape: LongArray, block: (OnnxTensor) -> T): T {
        data.rewind()
        return OnnxTensor.createTensor(env, data, shape).use { int16 ->
            session.run(mapOf(INPUT to int16)).use { result ->
                block(result.get(0) as OnnxTensor)
            }
        }
    }

    override fun close() = session.close()

    internal companion object {
        const val INPUT = "x"
        const val OUTPUT = "y"

        private const val INT16 = 5L
        private const val UINT16 = 4L

        /** The serialized ModelProto: `y = Cast(x, to = UINT16)`, x int16 of any shape. */
        val MODEL: ByteArray = run {
            val node = str(1, INPUT) + str(2, OUTPUT) + str(4, "Cast") +
                len(5, str(1, "to") + int(3, UINT16) + int(20, ATTRIBUTE_INT))
            val graph = len(1, node) + str(2, "uint16_cast") +
                len(11, tensorValue(INPUT, INT16)) + len(12, tensorValue(OUTPUT, UINT16))
            // ir_version 8, the graph, opset 13 of the default domain.
            int(1, 8) + len(7, graph) + len(8, int(2, 13))
        }

        private const val ATTRIBUTE_INT = 2L

        /** ValueInfoProto of a tensor of [elemType] with no fixed shape. */
        private fun tensorValue(name: String, elemType: Long) = str(1, name) + len(2, len(1, int(1, elemType)))

        private fun key(field: Int, wire: Int, out: ByteArrayOutputStream) = varint(out, (field shl 3 or wire).toLong())

        private fun varint(out: ByteArrayOutputStream, value: Long) {
            var v = value
            while (v >= 0x80) {
                out.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
            out.write(v.toInt())
        }

        private fun int(field: Int, value: Long) = ByteArrayOutputStream().apply {
            key(field, 0, this)
            varint(this, value)
        }.toByteArray()

        private fun len(field: Int, payload: ByteArray) = ByteArrayOutputStream().apply {
            key(field, 2, this)
            varint(this, payload.size.toLong())
            write(payload)
        }.toByteArray()

        private fun str(field: Int, value: String) = len(field, value.toByteArray())
    }
}
