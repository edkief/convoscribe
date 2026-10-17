package com.convoscribe.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.RandomAccessFile

/**
 * Encodes a recording from the app's mono 16-bit WAV to AAC-LC in an
 * M4A (MPEG-4) container with [MediaCodec] and [MediaMuxer]: about
 * 0.5 MB a minute against 1.9 MB for the WAV. The PCM is streamed from
 * disk in codec-sized chunks, so a long session never has to fit in
 * memory.
 */
object M4aEncoder {

    private const val MIME_AAC = MediaFormat.MIMETYPE_AUDIO_AAC
    private const val BIT_RATE = 64_000
    private const val TIMEOUT_US = 10_000L

    /**
     * Encode [wav] into [out]. [onProgress] gets `0f..1f` as input is
     * consumed (about once per percent). [checkCancelled] is called in
     * the loop and may throw to abort; the codec and muxer are then
     * released and [out] is left partial for the caller to delete.
     *
     * @throws IllegalArgumentException if [wav] is not a mono 16-bit WAV
     *   or holds no audio.
     */
    fun encode(wav: File, out: File, onProgress: (Float) -> Unit, checkCancelled: () -> Unit) {
        RandomAccessFile(wav, "r").use { input ->
            val info = WavReader.inspect(input, wav)
            require(info.dataBytes > 0) { "The recording is empty" }
            input.seek(info.dataOffset)

            val format = MediaFormat.createAudioFormat(MIME_AAC, info.sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            }
            val codec = MediaCodec.createEncoderByType(MIME_AAC)
            var muxer: MediaMuxer? = null
            var muxerStarted = false
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

                val bufferInfo = MediaCodec.BufferInfo()
                var track = -1
                var fed = 0L
                var inputDone = false
                var outputDone = false
                var lastPercent = -1
                while (!outputDone) {
                    checkCancelled()
                    if (!inputDone) {
                        val index = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (index >= 0) {
                            val buffer = codec.getInputBuffer(index)!!
                            val want = minOf(buffer.capacity().toLong(), info.dataBytes - fed).toInt() and 1.inv()
                            val chunk = ByteArray(want)
                            input.readFully(chunk)
                            buffer.clear()
                            buffer.put(chunk)
                            val timeUs = fed / 2 * 1_000_000L / info.sampleRate
                            fed += want
                            inputDone = fed >= info.dataBytes
                            codec.queueInputBuffer(
                                index,
                                0,
                                want,
                                timeUs,
                                if (inputDone) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                            )
                            val percent = (fed * 100 / info.dataBytes).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent / 100f)
                            }
                        }
                    }
                    when (val index = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        else -> if (index >= 0) {
                            val data = codec.getOutputBuffer(index)!!
                            val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (!isConfig && bufferInfo.size > 0 && muxerStarted) {
                                data.position(bufferInfo.offset)
                                data.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(track, data, bufferInfo)
                            }
                            codec.releaseOutputBuffer(index, false)
                            outputDone = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        }
                    }
                }
            } finally {
                try {
                    codec.stop()
                } catch (_: IllegalStateException) {
                    // never started, or already failed: nothing to stop
                }
                codec.release()
                try {
                    if (muxerStarted) muxer?.stop()
                } catch (_: IllegalStateException) {
                    // no samples were written; the caller discards the file
                }
                muxer?.release()
            }
        }
    }
}
