package com.convoscribe.engine.qnn

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.convoscribe.asr.WhisperDecoderStep
import com.convoscribe.asr.WhisperFeatures
import com.convoscribe.asr.WhisperGreedyDecoder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.nio.ShortBuffer

/**
 * Qualcomm's precompiled Whisper Large-v3-Turbo (issue #159) loaded into
 * onnxruntime with its QNN execution provider: `encoder.onnx` and
 * `decoder.onnx` are EPContext wrappers around the `.bin` QNN context
 * binaries next to them, so both graphs run whole on the Hexagon NPU.
 * The CPU fallback inside onnxruntime is switched off: a graph the NPU
 * cannot take fails to load instead of crawling on the CPU.
 *
 * Tensors (from the w8a16 build's `metadata.json`), quantized as
 * [WhisperQuant] describes:
 *  - encoder: `input_features` (uint16 1×128×3000) → `k_cache_cross_i`
 *    (uint8 20×1×64×1500) and `v_cache_cross_i` (uint8 20×1×1500×64),
 *    i = 0..3;
 *  - decoder: `input_ids` (int32 1×1), `position_ids` (int32 1),
 *    `attention_mask` (uint16 1×1×1×200), `k/v_cache_self_i_in` (uint8
 *    20×1×64×199, 20×1×199×64) and the cross caches → `logits` (uint16
 *    1×51866×1×1) and `k/v_cache_self_i_out`.
 *
 * onnxruntime's Java API has no uint16 tensors; [Uint16Cast] makes them.
 *
 * Not thread-safe; one window at a time.
 */
internal class WhisperQnnSession private constructor(
    private val env: OrtEnvironment,
    private val encoder: OrtSession,
    private val decoder: OrtSession
) : AutoCloseable {

    private val uint16 = Uint16Cast(env)

    /** Token ids Whisper predicts for one window of at most 30 s, after [prompt]. */
    fun transcribe(samples: FloatArray, prompt: IntArray): List<Int> {
        val features = WhisperFeatures.compute(samples)
        val featureBuffer = directShorts(features.size)
        for (v in features) {
            featureBuffer.put(WhisperQuant.quantize(v, WhisperQuant.FEATURES_SCALE, WhisperQuant.FEATURES_ZERO))
        }
        featureBuffer.rewind()
        return uint16.withUint16(featureBuffer, FEATURE_SHAPE) { input ->
            encoder.run(mapOf(INPUT_FEATURES to input)).use { encoded ->
                val cross = crossNames.associateWith { name ->
                    encoded.get(name).orElseThrow { IllegalStateException("encoder has no output $name") } as OnnxTensor
                }
                Decoder(cross).use { step -> WhisperGreedyDecoder.decode(prompt, step) }
            }
        }
    }

    /** The decoder with its self-attention caches, carried from step to step. */
    private inner class Decoder(private val cross: Map<String, OnnxTensor>) : WhisperDecoderStep, AutoCloseable {

        private val zeros: List<OnnxTensor> = (0 until LAYERS).flatMap {
            listOf(zeroTensor(K_SELF_SHAPE), zeroTensor(V_SELF_SHAPE))
        }
        private var selfCaches: Map<String, OnnxTensor> = (0 until LAYERS).flatMap { i ->
            listOf("k_cache_self_${i}_in" to zeros[2 * i], "v_cache_self_${i}_in" to zeros[2 * i + 1])
        }.toMap()

        // Owns the tensors in selfCaches after the first step.
        private var previous: OrtSession.Result? = null

        private val maskBuffer = directShorts(WhisperGreedyDecoder.MAX_TOKENS)

        override fun step(token: Int, position: Int, mask: FloatArray): FloatArray {
            maskBuffer.clear()
            for (v in mask) maskBuffer.put(WhisperQuant.quantize(v, WhisperQuant.MASK_SCALE, WhisperQuant.MASK_ZERO))
            maskBuffer.rewind()
            val ids = OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(token)), longArrayOf(1, 1))
            val pos = OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(position)), longArrayOf(1))
            val result = try {
                uint16.withUint16(maskBuffer, MASK_SHAPE) { maskTensor ->
                    decoder.run(
                        mapOf(INPUT_IDS to ids, POSITION_IDS to pos, ATTENTION_MASK to maskTensor) + selfCaches + cross
                    )
                }
            } finally {
                ids.close()
                pos.close()
            }
            previous?.close()
            previous = result
            selfCaches = (0 until LAYERS).flatMap { i ->
                listOf("k", "v").map { kv -> "${kv}_cache_self_${i}_in" to output(result, "${kv}_cache_self_${i}_out") }
            }.toMap()
            val logits = output(result, LOGITS).byteBuffer.order(ByteOrder.nativeOrder()).asShortBuffer()
            return FloatArray(logits.remaining()) {
                WhisperQuant.dequantize(logits.get(it), WhisperQuant.LOGITS_SCALE, WhisperQuant.LOGITS_ZERO)
            }
        }

        private fun output(result: OrtSession.Result, name: String): OnnxTensor =
            result.get(name).orElseThrow { IllegalStateException("decoder has no output $name") } as OnnxTensor

        override fun close() {
            previous?.close()
            zeros.forEach { it.close() }
        }
    }

    /** An empty uint8 cache: every byte the zero point. */
    private fun zeroTensor(shape: LongArray): OnnxTensor {
        val size = shape.fold(1L) { a, b -> a * b }.toInt()
        val bytes = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        repeat(size) { bytes.put(WhisperQuant.CACHE_ZERO) }
        bytes.rewind()
        return OnnxTensor.createTensor(env, bytes, shape, OnnxJavaType.UINT8)
    }

    override fun close() {
        uint16.close()
        decoder.close()
        encoder.close()
    }

    companion object {
        private const val LAYERS = 4
        private const val INPUT_FEATURES = "input_features"
        private const val INPUT_IDS = "input_ids"
        private const val POSITION_IDS = "position_ids"
        private const val ATTENTION_MASK = "attention_mask"
        private const val LOGITS = "logits"

        private val FEATURE_SHAPE = longArrayOf(1, WhisperFeatures.MEL_BINS.toLong(), WhisperFeatures.FRAMES.toLong())
        private val MASK_SHAPE = longArrayOf(1, 1, 1, WhisperGreedyDecoder.MAX_TOKENS.toLong())
        private val K_SELF_SHAPE = longArrayOf(20, 1, 64, WhisperGreedyDecoder.MAX_TOKENS - 1L)
        private val V_SELF_SHAPE = longArrayOf(20, 1, WhisperGreedyDecoder.MAX_TOKENS - 1L, 64)

        private val crossNames = (0 until LAYERS).flatMap { listOf("k_cache_cross_$it", "v_cache_cross_$it") }

        private fun directShorts(count: Int): ShortBuffer =
            ByteBuffer.allocateDirect(count * 2).order(ByteOrder.nativeOrder()).asShortBuffer()

        /**
         * Load the encoder and decoder from [modelDir] on the NPU. The HTP
         * backend is `libQnnHtp.so` from [nativeLibraryDir]; the caller has
         * already put that directory on the DSP library path
         * ([com.convoscribe.engine.sherpa.QnnRuntime.ensurePrepared]).
         */
        fun open(modelDir: File, nativeLibraryDir: String): WhisperQnnSession {
            val encoderFile = File(modelDir, "encoder.onnx")
            val decoderFile = File(modelDir, "decoder.onnx")
            val env = OrtEnvironment.getEnvironment()
            fun options() = OrtSession.SessionOptions().apply {
                addQnn(
                    mapOf(
                        "backend_path" to File(nativeLibraryDir, "libQnnHtp.so").absolutePath,
                        "htp_performance_mode" to "burst"
                    )
                )
                addConfigEntry("session.disable_cpu_ep_fallback", "1")
            }
            val encoder = options().use { env.createSession(encoderFile.absolutePath, it) }
            val decoder = try {
                options().use { env.createSession(decoderFile.absolutePath, it) }
            } catch (t: Throwable) {
                encoder.close()
                throw t
            }
            return WhisperQnnSession(env, encoder, decoder)
        }
    }
}
