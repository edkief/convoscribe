package com.convoscribe.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal WAV reader for the recordings produced by [WavWriter]
 * (and any other well-formed 16-bit mono PCM WAV). It
 * validates the `fmt ` chunk, locates the `data` chunk by walking
 * RIFF sub-chunks (so it works even if a future tool inserts an
 * extra `LIST`/`JUNK` chunk), and returns the samples scaled to
 * `[-1.0, 1.0]` for the ASR / diarization pipelines.
 *
 * If the data chunk size field is missing (zero, a common sign of a
 * crash-recovered file that was never finalized) or lies about the
 * payload length, the reader falls back to the bytes that remain on
 * disk and trusts the file length, matching [WavWriter.repairHeader].
 *
 * Files at another sample rate (model sample clips: the Kroko one
 * is 24 kHz) are resampled to 16 kHz with linear interpolation,
 * which is plenty for an engine-check clip. The app's own
 * recordings are 16 kHz and are returned as-is.
 */
object WavReader {

    /**
     * Read [file] as a mono 16-bit PCM WAV and return its samples at
     * 16 kHz as a `FloatArray` in `[-1.0, 1.0]`, resampling if the
     * file uses another rate.
     *
     * @throws IllegalArgumentException if the file is not a WAV, the
     *   `fmt ` chunk is missing, or it is not mono 16-bit PCM.
     */
    fun readMono16k(file: File): FloatArray {
        RandomAccessFile(file, "r").use { raf ->
            val info = inspect(raf, file)
            val byteCount = info.dataBytes.toInt()
            val raw = ByteArray(byteCount)
            raf.seek(info.dataOffset)
            raf.readFully(raw)
            val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            val nSamples = byteCount / 2
            val out = FloatArray(nSamples)
            for (i in 0 until nSamples) {
                out[i] = bb.short / 32_768f
            }
            return if (info.sampleRate == AudioSpec.SAMPLE_RATE) {
                out
            } else {
                resample(out, info.sampleRate, AudioSpec.SAMPLE_RATE)
            }
        }
    }

    /**
     * Where the PCM payload of a mono 16-bit WAV sits: [sampleRate] in
     * Hz, [dataOffset] the byte offset of the first sample and
     * [dataBytes] the payload length (a whole number of samples).
     */
    internal class WavInfo(val sampleRate: Int, val dataOffset: Long, val dataBytes: Long)

    /** [inspect] on a freshly opened [file]. */
    internal fun inspect(file: File): WavInfo = RandomAccessFile(file, "r").use { inspect(it, file) }

    /**
     * Validate [file]'s header (already open as [raf]) and locate its
     * `data` chunk; throws [IllegalArgumentException] as
     * [readMono16k] documents. A zero or over-long data size, as in a
     * crash-recovered file, falls back to the bytes left on disk.
     */
    internal fun inspect(raf: RandomAccessFile, file: File): WavInfo {
        val total = raf.length()
        require(total >= WavWriter.HEADER_SIZE) {
            "File is only $total bytes; smaller than a 44-byte WAV header"
        }
        val header = ByteArray(WavWriter.HEADER_SIZE.toInt())
        raf.seek(0)
        raf.readFully(header)

        requireFourCC(header, 0, "RIFF", file)
        requireFourCC(header, 8, "WAVE", file)

        // Walk the RIFF sub-chunks: each is an 8-byte header
        // (fourcc + little-endian size) followed by `size` bytes
        // of payload, then 0 or 1 pad byte to keep the next
        // chunk on an even offset. We do not assume the `data`
        // chunk is at offset 44, so a writer that inserts a
        // `LIST`/`JUNK` chunk still works.
        var offset = 12L
        var fmtFound = false
        var sampleRate = AudioSpec.SAMPLE_RATE
        var dataOffset = -1L
        var dataSize = 0L

        while (offset + 8 <= total) {
            raf.seek(offset)
            val fourcc = ByteArray(4)
            raf.readFully(fourcc)
            val chunkSize = readIntLE(raf)
            val payload = offset + 8
            if (fourccAsString(fourcc) == "fmt ") {
                require(!fmtFound) {
                    "WAV file has more than one 'fmt ' chunk: ${file.absolutePath}"
                }
                require(chunkSize >= 16) {
                    "'fmt ' chunk is $chunkSize bytes; need at least 16: ${file.absolutePath}"
                }
                val fmt = ByteArray(chunkSize)
                raf.seek(payload)
                raf.readFully(fmt)
                val bb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                val format = bb.short.toInt() and 0xFFFF
                val channels = bb.short.toInt() and 0xFFFF
                sampleRate = bb.int
                bb.int // byte rate
                bb.short // block align
                val bitsPerSample = bb.short.toInt() and 0xFFFF
                require(format == 1) {
                    "Only PCM (format 1) is supported, got $format: ${file.absolutePath}"
                }
                require(channels == 1) {
                    "Only mono WAV is supported, got $channels channel(s): ${file.absolutePath}"
                }
                require(sampleRate > 0) {
                    "Invalid sample rate $sampleRate: ${file.absolutePath}"
                }
                require(bitsPerSample == 16) {
                    "Only 16-bit WAV is supported, got $bitsPerSample bits: ${file.absolutePath}"
                }
                fmtFound = true
            } else if (fourccAsString(fourcc) == "data") {
                dataOffset = payload
                dataSize = chunkSize.toLong()
                // Don't break; we still want to validate the
                // fmt chunk. We can come back if it's missing.
            }
            // RIFF chunks are word-aligned: skip payload + 0/1
            // pad byte.
            offset = payload + chunkSize + if (chunkSize.rem(2) == 0) 0L else 1L
        }

        require(fmtFound) {
            "WAV file has no 'fmt ' chunk: ${file.absolutePath}"
        }
        require(dataOffset >= 0) {
            "WAV file has no 'data' chunk: ${file.absolutePath}"
        }

        // Crash-recovered files have a data size of 0; some
        // writers also over-report. Trust the bytes that are
        // actually on disk.
        val available = total - dataOffset
        val usable = if (dataSize <= 0L || dataSize > available) available else dataSize
        require(usable >= 0) {
            "'data' chunk starts past end of file: ${file.absolutePath}"
        }
        require(usable % 2 == 0L) {
            "'data' chunk is $usable bytes; not a whole number of 16-bit samples: " +
                file.absolutePath
        }
        return WavInfo(sampleRate, dataOffset, usable)
    }

    /** Linear-interpolation resampling of [input] from [fromRate] to [toRate]. */
    internal fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (input.isEmpty() || fromRate == toRate) return input
        val outSize = (input.size.toLong() * toRate / fromRate).toInt()
        val out = FloatArray(outSize)
        val step = fromRate.toDouble() / toRate
        for (i in 0 until outSize) {
            val pos = i * step
            val left = pos.toInt().coerceAtMost(input.size - 1)
            val right = (left + 1).coerceAtMost(input.size - 1)
            val frac = (pos - left).toFloat()
            out[i] = input[left] + (input[right] - input[left]) * frac
        }
        return out
    }

    private fun requireFourCC(buf: ByteArray, offset: Int, expected: String, file: File) {
        for (i in 0 until 4) {
            require(buf[offset + i].toInt().toChar() == expected[i]) {
                "Expected '$expected' fourcc at offset $offset, got " +
                    "'${fourccAsString(buf, offset)}': ${file.absolutePath}"
            }
        }
    }

    private fun fourccAsString(buf: ByteArray, offset: Int = 0): String {
        return String(buf, offset, 4, Charsets.US_ASCII)
    }

    private fun readIntLE(raf: RandomAccessFile): Int {
        val b0 = raf.read()
        val b1 = raf.read()
        val b2 = raf.read()
        val b3 = raf.read()
        return (b0 and 0xFF) or
            ((b1 and 0xFF) shl 8) or
            ((b2 and 0xFF) shl 16) or
            ((b3 and 0xFF) shl 24)
    }
}
