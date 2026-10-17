package com.convoscribe.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Round-trip and crash-recovery tests for [WavWriter] and
 * [WavReader]. The WAV is small enough to fit in a `TemporaryFolder`
 * (under the JVM's working directory); the tests never touch the
 * microphone, so they run as plain JUnit without Robolectric.
 */
class WavRoundTripTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun writer_produces44ByteHeader_andPatchesSizesOnClose() {
        val file = tmp.newFile("rec.wav")
        val samples = ShortArray(16_000) { (it % 32_768).toShort() }

        WavWriter(file).use { writer ->
            writer.write(samples)
            // We can ask the writer how much it has buffered; this
            // value feeds the size patch in close().
            assertEquals(16_000L, writer.samplesWritten)
        }

        val total = file.length()
        assertEquals(44L + 32_000L, total)

        val header = ByteArray(44)
        RandomAccessFile(file, "r").use { it.readFully(header) }
        // RIFF chunk size = 36 + dataBytes, little-endian at offset 4.
        assertEquals(36 + 32_000, readIntLE(header, 4))
        // data chunk size = dataBytes, little-endian at offset 40.
        assertEquals(32_000, readIntLE(header, 40))
        // The fourccs and basic fmt fields stay correct.
        assertEquals("RIFF", String(header, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(header, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(header, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(header, 36, 4, Charsets.US_ASCII))
    }

    @Test
    fun roundTrip_convertsSamplesBackToFloatArray() {
        val file = tmp.newFile("roundtrip.wav")
        // Mix extremes so we exercise both halves of the signed range
        // and the positive-32 767 rounding the spec calls out.
        val samples = shortArrayOf(0, 1, -1, 16_384, -32_768, 32_767, 12_345, -12_345)
        WavWriter(file).use { it.write(samples) }

        val read = WavReader.readMono16k(file)
        assertEquals(samples.size, read.size)
        // 16 384 / 32 768 = 0.5 exactly.
        assertEquals(0.5f, read[3], 0.0001f)
        // -32 768 / 32 768 = -1.0 exactly.
        assertEquals(-1.0f, read[4], 0.0001f)
        // Every other sample matches the manual scaling.
        for (i in samples.indices) {
            assertEquals(samples[i] / 32_768f, read[i], 0.0001f)
        }
    }

    @Test
    fun roundTrip_chunkedWrites_assembleInOrder() {
        val file = tmp.newFile("chunked.wav")
        WavWriter(file).use { writer ->
            for (chunk in 0 until 10) {
                val s = ShortArray(1_600) { idx -> (chunk * 1_600 + idx).toShort() }
                writer.write(s)
            }
            assertEquals(16_000L, writer.samplesWritten)
        }

        val read = WavReader.readMono16k(file)
        assertEquals(16_000, read.size)
        for (i in 0 until 16_000) {
            assertEquals(i.toShort() / 32_768f, read[i], 0.0001f)
        }
    }

    @Test
    fun readEmpty_afterJustOpeningTheWriter_returnsEmptyArray() {
        // No samples were written — the data chunk is just the 0-byte
        // marker, but the file is still a valid WAV.
        val file = tmp.newFile("empty.wav")
        WavWriter(file).close()
        val read = WavReader.readMono16k(file)
        assertEquals(0, read.size)
        // The close path patched the sizes to zero, so a subsequent
        // read sees an empty payload.
        val header = ByteArray(44)
        RandomAccessFile(file, "r").use { it.readFully(header) }
        assertEquals(36, readIntLE(header, 4))
        assertEquals(0, readIntLE(header, 40))
    }

    @Test
    fun crashRecoveredFile_withZeroSizes_isReadAndRepairable() {
        val file = tmp.newFile("crash.wav")
        val samples = ShortArray(2_000) { (it * 7).toShort() }

        // Manually build a WAV with the sizes left at zero to
        // simulate a recording that was killed mid-stream: 44-byte
        // header (sizes = 0) + raw little-endian samples. We use a
        // second RandomAccessFile to flush to disk before the
        // WavReader sees it, exactly the way the production crash
        // would be observed.
        val raf = RandomAccessFile(file, "rw")
        raf.setLength(0)
        raf.seek(0)
        // 44-byte header with the two size fields left at zero.
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(0) // RIFF size — left at zero
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1.toShort())
        header.putShort(1.toShort())
        header.putInt(AudioSpec.SAMPLE_RATE)
        header.putInt(AudioSpec.SAMPLE_RATE * 2)
        header.putShort(2.toShort())
        header.putShort(16.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(0) // data size — left at zero
        raf.write(header.array())
        // Append the sample data.
        val body = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) {
            body.putShort(s)
        }
        raf.write(body.array())
        // Make sure the bytes are persisted before we hand the file
        // to the reader — this is the "flush via a separate
        // RandomAccessFile" the spec asks for.
        raf.fd.sync()
        raf.close()

        // WavReader must fall back to the bytes that are on disk
        // because the data-size field is zero. The size is also
        // larger than the actual data in some writers; this is the
        // crash-recovery path.
        val read = WavReader.readMono16k(file)
        assertEquals(samples.size, read.size)
        for (i in samples.indices) {
            assertEquals(samples[i] / 32_768f, read[i], 0.0001f)
        }

        // repairHeader must rewrite both size fields from the file
        // length so the file is also a valid WAV on disk now.
        WavWriter.repairHeader(file)
        val patched = ByteArray(44)
        RandomAccessFile(file, "r").use { it.readFully(patched) }
        val dataBytes = samples.size * 2
        assertEquals(36 + dataBytes, readIntLE(patched, 4))
        assertEquals(dataBytes, readIntLE(patched, 40))

        // And reading after the repair still returns the same samples.
        val read2 = WavReader.readMono16k(file)
        assertEquals(read.size, read2.size)
        for (i in read.indices) {
            assertEquals(read[i], read2[i], 0.0001f)
        }
    }

    @Test
    fun inspect_locatesThePayloadOfAFinishedFile() {
        val file = tmp.newFile("finished.wav")
        WavWriter(file).use { it.write(ShortArray(100)) }

        val info = WavReader.inspect(file)

        assertEquals(16_000, info.sampleRate)
        assertEquals(44L, info.dataOffset)
        assertEquals(200L, info.dataBytes)
    }

    @Test
    fun inspect_trustsTheFileLengthWhenTheHeaderHasZeroSizes() {
        val file = tmp.newFile("crashed.wav")
        WavWriter(file).use { it.write(ShortArray(100)) }
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(WavWriter.RIFF_SIZE_OFFSET)
            raf.write(ByteArray(4))
            raf.seek(WavWriter.DATA_SIZE_OFFSET)
            raf.write(ByteArray(4))
        }

        val info = WavReader.inspect(file)

        assertEquals(44L, info.dataOffset)
        assertEquals(200L, info.dataBytes)
    }

    @Test
    fun repairHeader_throwsIfFileIsShorterThanTheHeader() {
        val file = tmp.newFile("tiny.wav")
        RandomAccessFile(file, "rw").use { it.setLength(10) }
        val ex = assertThrows(IllegalArgumentException::class.java) {
            WavWriter.repairHeader(file)
        }
        assertNotNull(ex.message)
        assertTrue(
            "message should mention the size: ${ex.message}",
            ex.message!!.contains("10")
        )
    }

    @Test
    fun reader_throwsOnStereoWav() {
        val file = tmp.newFile("stereo.wav")
        writeWav(
            file = file,
            sampleRate = AudioSpec.SAMPLE_RATE,
            channels = 2,
            bitsPerSample = 16,
            samples = ShortArray(100) { 0 }
        )
        val ex = assertThrows(IllegalArgumentException::class.java) {
            WavReader.readMono16k(file)
        }
        assertTrue(
            "message should mention 'mono': ${ex.message}",
            ex.message!!.contains("mono", ignoreCase = true)
        )
    }

    @Test
    fun reader_resamples44_1kHzWavTo16kHz() {
        // Formerly rejected; non-16 kHz clips are now resampled.
        val file = tmp.newFile("cd.wav")
        writeWav(
            file = file,
            sampleRate = 44_100,
            channels = 1,
            bitsPerSample = 16,
            samples = ShortArray(441) { 0 }
        )
        assertEquals(160, WavReader.readMono16k(file).size)
    }

    @Test
    fun reader_throwsOn24BitWav() {
        val file = tmp.newFile("24bit.wav")
        writeWav(
            file = file,
            sampleRate = AudioSpec.SAMPLE_RATE,
            channels = 1,
            bitsPerSample = 24,
            samples = ShortArray(100) { 0 }
        )
        val ex = assertThrows(IllegalArgumentException::class.java) {
            WavReader.readMono16k(file)
        }
        assertTrue(
            "message should mention 16-bit: ${ex.message}",
            ex.message!!.contains("16", ignoreCase = true)
        )
    }

    @Test
    fun reader_throwsOnNonRiffFile() {
        val file = tmp.newFile("notriff.wav")
        RandomAccessFile(file, "rw").use { it.write(ByteArray(64) { 0 }) }
        val ex = assertThrows(IllegalArgumentException::class.java) {
            WavReader.readMono16k(file)
        }
        assertTrue(
            "message should mention RIFF: ${ex.message}",
            ex.message!!.contains("RIFF")
        )
    }

    @Test
    fun reader_throwsOnFileSmallerThanHeader() {
        val file = tmp.newFile("tooshort.wav")
        RandomAccessFile(file, "rw").use { it.setLength(20) }
        val ex = assertThrows(IllegalArgumentException::class.java) {
            WavReader.readMono16k(file)
        }
        assertTrue(
            "message should mention the size: ${ex.message}",
            ex.message!!.contains("20")
        )
    }

    // --- helpers -----------------------------------------------------

    private fun readIntLE(buf: ByteArray, offset: Int): Int {
        return (buf[offset].toInt() and 0xFF) or
            ((buf[offset + 1].toInt() and 0xFF) shl 8) or
            ((buf[offset + 2].toInt() and 0xFF) shl 16) or
            ((buf[offset + 3].toInt() and 0xFF) shl 24)
    }

    /**
     * Build a valid PCM WAV with arbitrary [sampleRate], [channels]
     * and [bitsPerSample], padded out to [samples]. The RIFF and
     * data sizes are filled in from the actual payload length.
     */
    private fun writeWav(file: File, sampleRate: Int, channels: Int, bitsPerSample: Int, samples: ShortArray) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = (channels * bitsPerSample / 8).toShort()
        val dataBytes = samples.size * channels * bitsPerSample / 8
        val raf = RandomAccessFile(file, "rw")
        raf.setLength(0)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataBytes)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1.toShort()) // PCM
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign)
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataBytes)
        raf.write(header.array())
        if (bitsPerSample == 16) {
            val body = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) {
                body.putShort(s)
            }
            raf.write(body.array())
        } else if (bitsPerSample == 24) {
            // Only 24-bit zero padding is needed for the rejection
            // test; 24-bit Little-Endian = 0x00 0x00 0x00 per sample.
            raf.write(ByteArray(samples.size * 3))
        } else {
            error("test helper only supports 16-bit and 24-bit payloads")
        }
        raf.close()
    }

    @Test
    fun appendMode_keepsExistingAudioAndPatchesSizes() {
        val file = tmp.newFile("append.wav")
        WavWriter(file).use { it.write(ShortArray(100) { i -> i.toShort() }) }

        WavWriter(file, append = true).use { writer ->
            assertEquals(100L, writer.samplesWritten)
            writer.write(ShortArray(50) { i -> (1_000 + i).toShort() })
            assertEquals(150L, writer.samplesWritten)
        }

        assertEquals(44L + 150 * 2, file.length())
        val samples = WavReader.readMono16k(file)
        assertEquals(150, samples.size)
        assertEquals(99 / 32768f, samples[99], 1e-6f)
        assertEquals(1_000 / 32768f, samples[100], 1e-6f)
    }

    @Test
    fun appendMode_onMissingFile_startsFresh() {
        val file = java.io.File(tmp.root, "fresh.wav")
        WavWriter(file, append = true).use { it.write(ShortArray(10)) }
        assertEquals(44L + 10 * 2, file.length())
        assertEquals(10, WavReader.readMono16k(file).size)
    }

    /**
     * Regression: model sample clips are not always 16 kHz (Kroko's is
     * 24 kHz). They are resampled instead of rejected.
     */
    @Test
    fun read24kHzFile_resamplesTo16kHz() {
        val file = tmp.newFile("clip24k.wav")
        // One second at 24 kHz: a slow ramp, so linear interpolation is exact.
        writeWav(
            file = file,
            sampleRate = 24_000,
            channels = 1,
            bitsPerSample = 16,
            samples = ShortArray(24_000) { i -> i.toShort() }
        )

        val samples = WavReader.readMono16k(file)

        assertEquals(16_000, samples.size)
        // Output sample i sits at input position i * 1.5.
        assertEquals(0f, samples[0], 1e-6f)
        assertEquals(1.5f / 32_768f, samples[1], 1e-6f)
        assertEquals(15_000f / 32_768f, samples[10_000], 1e-6f)
    }
}
