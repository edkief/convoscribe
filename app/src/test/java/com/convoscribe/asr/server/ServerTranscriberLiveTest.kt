package com.convoscribe.asr.server

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * [ServerTranscriber] against a real transcription server (issue #58).
 * Skipped unless both are set:
 *
 *  - `CONVOSCRIBE_ASR_SERVER`: the base URL, e.g. `http://parakeet-asr.asr:8000/v1`
 *  - `CONVOSCRIBE_ASR_SAMPLE_WAV`: a 16 kHz mono WAV with speech
 *
 * The client does not check the scheme (Settings does), so a plain
 * `http://` cluster address works here.
 */
class ServerTranscriberLiveTest {

    @Test
    fun transcribesASampleWithWordTimings() = runBlocking {
        val url = System.getenv("CONVOSCRIBE_ASR_SERVER").orEmpty()
        val wav = System.getenv("CONVOSCRIBE_ASR_SAMPLE_WAV")?.let(::File)
        assumeTrue(url.isNotBlank() && wav != null && wav.isFile)
        val http = OkHttpClient.Builder().readTimeout(5, TimeUnit.MINUTES).build()
        val transcriber = ServerTranscriber(http) {
            ServerAsrConfig(url, System.getenv("CONVOSCRIBE_ASR_KEY").orEmpty(), model = "")
        }

        val finals = transcriber.transcribe(wav!!, "en")

        println("server ${transcriber.describe()}: ${finals.size} lines")
        finals.forEach { println("  [${it.startMs}-${it.endMs}] ${it.text} (${it.tokens.size} words)") }
        assertTrue(finals.isNotEmpty())
        assertTrue(finals.all { it.tokens.isNotEmpty() && it.endMs > it.startMs })
        assertTrue(transcriber.describe().model != "unknown")
    }
}
