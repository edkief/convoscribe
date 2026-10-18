package com.convoscribe.audio

import android.media.AudioFormat as AndroidAudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.IOException
import java.nio.ByteOrder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Decodes an audio file of any format to PCM (issue #3). The blocks
 * keep the file's channel count and rate; [PcmConverter] turns them
 * into 16 kHz mono.
 */
fun interface AudioDecoder {

    /**
     * Decode [file], handing each block to [onBlock] in order.
     * [onProgress] gets the fraction of the file decoded, `0f..1f`,
     * when its duration is known. Cancellation is checked between
     * blocks.
     *
     * @throws AudioImportException when the file cannot be decoded.
     */
    suspend fun decode(file: File, onBlock: suspend (PcmBlock) -> Unit, onProgress: (Float) -> Unit)
}

/** Why an audio file could not be imported; the UI words each one. */
class AudioImportException(val reason: Reason, cause: Throwable? = null) : Exception(reason.name, cause) {
    enum class Reason {
        /** Not a media file Android can open, or corrupt. */
        UNREADABLE,

        /** A media file without an audio track. */
        NO_AUDIO_TRACK,

        /** The audio codec has no decoder on this device. */
        UNSUPPORTED_FORMAT,

        /** Decodes to no audio at all. */
        EMPTY
    }
}

/**
 * [AudioDecoder] over the platform's [MediaExtractor] and
 * [MediaCodec]: whatever the device can play (WAV, MP3, AAC/M4A,
 * OGG/Opus, FLAC, AMR…) decodes here. The first audio track is used.
 * Decoders output 16-bit PCM unless they say otherwise in their output
 * format; float and 8-bit output are converted too.
 */
class MediaCodecAudioDecoder : AudioDecoder {

    override suspend fun decode(file: File, onBlock: suspend (PcmBlock) -> Unit, onProgress: (Float) -> Unit) {
        val extractor = MediaExtractor()
        try {
            try {
                extractor.setDataSource(file.absolutePath)
            } catch (e: IOException) {
                throw AudioImportException(AudioImportException.Reason.UNREADABLE, e)
            }
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw AudioImportException(AudioImportException.Reason.NO_AUDIO_TRACK)
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            val codec = try {
                MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).apply {
                    configure(format, null, null, 0)
                    start()
                }
            } catch (e: IOException) {
                throw AudioImportException(AudioImportException.Reason.UNSUPPORTED_FORMAT, e)
            } catch (e: IllegalArgumentException) {
                throw AudioImportException(AudioImportException.Reason.UNSUPPORTED_FORMAT, e)
            } catch (e: IllegalStateException) {
                throw AudioImportException(AudioImportException.Reason.UNSUPPORTED_FORMAT, e)
            }
            try {
                drain(extractor, codec, format, onBlock, onProgress)
            } catch (e: IllegalStateException) {
                // CodecException is an IllegalStateException: the
                // stream is corrupt past what the decoder can skip.
                throw AudioImportException(AudioImportException.Reason.UNREADABLE, e)
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private suspend fun drain(
        extractor: MediaExtractor,
        codec: MediaCodec,
        inputFormat: MediaFormat,
        onBlock: suspend (PcmBlock) -> Unit,
        onProgress: (Float) -> Unit
    ) {
        val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
            inputFormat.getLong(MediaFormat.KEY_DURATION)
        } else {
            -1L
        }
        var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var encoding = AndroidAudioFormat.ENCODING_PCM_16BIT
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var lastPercent = -1
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val out = codec.outputFormat
                    channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    sampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (out.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        encoding = out.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                }
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)!!
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val samples = toFloats(buffer.slice().order(ByteOrder.nativeOrder()), encoding)
                    codec.releaseOutputBuffer(index, false)
                    if (samples.isNotEmpty()) onBlock(PcmBlock(samples, channels, sampleRate))
                    if (durationUs > 0) {
                        val percent = (info.presentationTimeUs * 100 / durationUs).toInt().coerceIn(0, 100)
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent / 100f)
                        }
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
        onProgress(1f)
    }

    private fun toFloats(buffer: java.nio.ByteBuffer, encoding: Int): FloatArray = when (encoding) {
        AndroidAudioFormat.ENCODING_PCM_16BIT -> {
            val shorts = buffer.asShortBuffer()
            FloatArray(shorts.remaining()) { shorts.get() / 32_768f }
        }
        AndroidAudioFormat.ENCODING_PCM_FLOAT -> {
            val floats = buffer.asFloatBuffer()
            FloatArray(floats.remaining()) { floats.get().coerceIn(-1f, 1f) }
        }
        AndroidAudioFormat.ENCODING_PCM_8BIT -> FloatArray(buffer.remaining()) {
            ((buffer.get().toInt() and 0xFF) - 128) / 128f
        }
        else -> throw AudioImportException(AudioImportException.Reason.UNSUPPORTED_FORMAT)
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
    }
}
