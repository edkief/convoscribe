package com.convoscribe.engine.qnn

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.TensorInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

/** [Uint16Cast] on the desktop onnxruntime of the app's version. */
class Uint16CastTest {

    private val env = OrtEnvironment.getEnvironment()

    private fun shorts(vararg values: Int) =
        ByteBuffer.allocateDirect(values.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply {
            values.forEach { put(it.toShort()) }
        }

    private fun bits(tensor: OnnxTensor): List<Int> {
        val out = tensor.byteBuffer.order(ByteOrder.nativeOrder()).asShortBuffer()
        return List(out.remaining()) { out.get(it).toInt() and 0xFFFF }
    }

    @Test
    fun keepsTheBits_asAUint16Tensor() {
        Uint16Cast(env).use { cast ->
            cast.withUint16(shorts(0, 1, 31605, 0x8000, 0xFFFF), longArrayOf(5)) { tensor ->
                assertEquals(TensorInfo.OnnxTensorType.ONNX_TENSOR_ELEMENT_DATA_TYPE_UINT16, tensor.info.onnxType)
                assertEquals(listOf(0, 1, 31605, 0x8000, 0xFFFF), bits(tensor))
            }
        }
    }

    @Test
    fun takesAnyShape_andCanBeReused() {
        Uint16Cast(env).use { cast ->
            val shape = longArrayOf(1, 1, 1, 3)
            cast.withUint16(shorts(7, 8, 9), shape) { tensor ->
                assertEquals(shape.toList(), tensor.info.shape.toList())
                assertEquals(listOf(7, 8, 9), bits(tensor))
            }
            val again = shorts(65535, 0, 1)
            again.position(3)
            cast.withUint16(again, longArrayOf(3)) { tensor -> assertEquals(listOf(65535, 0, 1), bits(tensor)) }
        }
    }
}
