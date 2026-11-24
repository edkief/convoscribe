package com.convoscribe.asr

import android.util.Log
import com.convoscribe.TAG
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What a transcriber that works behind the recording (live Whisper,
 * issue #160) is doing: [pending] speech segments wait for or are in
 * the recognizer, and [failure] says why it gave up, after which the
 * recording carries on without a live transcript.
 */
data class LiveStatus(val pending: Int = 0, val failure: String? = null)

/**
 * Live Whisper (issue #160): a [StreamingTranscriber] that cuts the
 * recording at pauses with a [VoiceDetector] and makes each speech
 * segment one line once Whisper has decoded it.
 *
 * [accept] only feeds the voice detector, which is cheap, and queues the
 * segments it closes; [decoder] works through the queue on a thread of
 * its own, so the recorder is never held up when Whisper falls behind.
 * The queue has no bound: a segment waits as long as it takes. Lines
 * decoded since the last call come back from the next [accept], and
 * [endUtterance] (pause) and [finish] (Stop) close the segment in
 * progress and wait for the queue to empty.
 *
 * Whisper reports no word times, so each line's words are spread evenly
 * over it ([WordTimer]); diarization can still split it at a speaker
 * change. There are no partial captions.
 *
 * A segment is at most [maxSamples] long: the detector should cap its
 * segments there, and a longer one is cut by [SpeechChunker.split].
 * Times count from the first sample passed to [accept]. Each run between
 * pauses gets a fresh detector from [voiceDetector], so its clock never
 * depends on how the detector counts after a flush.
 *
 * When [decoder] fails (on the CPU, or on the NPU with no CPU model to
 * fall back to) the failure is published in [status] and later speech is
 * not transcribed; the recording itself is untouched.
 */
class WhisperLiveTranscriber(
    private val decoder: WhisperDecoder,
    private val voiceDetector: () -> VoiceDetector,
    private val maxSamples: Int = SpeechChunker.MAX_SAMPLES
) : StreamingTranscriber {

    private val _status = MutableStateFlow(LiveStatus())
    override val status: StateFlow<LiveStatus> = _status.asStateFlow()

    private val queue = LinkedBlockingQueue<SpeechSpan>()
    private val decoded = ConcurrentLinkedQueue<TranscriptEvent.Final>()

    /** Guards [pending]; waited on by [awaitIdle]. */
    private val lock = Object()
    private var pending = 0

    @Volatile
    private var failed = false

    private var vad: VoiceDetector = voiceDetector()

    /** Samples accepted before the current [vad] was opened. */
    private var vadBase = 0L
    private var totalSamples = 0L
    private var closed = false

    private val worker = Thread(::work, "whisper-live").apply {
        isDaemon = true
        start()
    }

    init {
        require(maxSamples > 0) { "maxSamples must be positive" }
    }

    override fun accept(samples: FloatArray): List<TranscriptEvent> {
        if (samples.isNotEmpty()) {
            vad.accept(samples)
            totalSamples += samples.size
            enqueue(vad.drain())
        }
        return takeDecoded()
    }

    override fun endUtterance(): List<TranscriptEvent> {
        closeRun()
        vad = voiceDetector()
        vadBase = totalSamples
        return awaitAndTake()
    }

    override fun finish(): List<TranscriptEvent> {
        closeRun()
        return awaitAndTake()
    }

    override fun close() {
        if (closed) return
        closed = true
        queue.put(STOP)
        // The decoder is native: release it only once the worker has
        // left it.
        worker.join()
        runCatching { decoder.close() }
        runCatching { vad.close() }
    }

    /** Close the detector's segment in progress and queue it. */
    private fun closeRun() {
        vad.flush()
        enqueue(vad.drain())
        runCatching { vad.close() }
    }

    private fun awaitAndTake(): List<TranscriptEvent> {
        awaitIdle()
        return takeDecoded() + TranscriptEvent.Partial("")
    }

    private fun enqueue(spans: List<SpeechSpan>) {
        if (spans.isEmpty() || failed) return
        for (span in spans) {
            for (piece in SpeechChunker.split(span, maxSamples)) {
                synchronized(lock) { pending++ }
                queue.put(SpeechSpan(vadBase + piece.startSample, piece.samples))
            }
        }
        publish()
    }

    private fun takeDecoded(): List<TranscriptEvent> {
        if (decoded.isEmpty()) return emptyList()
        val lines = mutableListOf<TranscriptEvent>()
        while (true) lines += decoded.poll() ?: break
        return lines
    }

    private fun awaitIdle() {
        synchronized(lock) {
            while (pending > 0) lock.wait()
        }
    }

    private fun publish() {
        val count = synchronized(lock) { pending }
        _status.update { it.copy(pending = count) }
    }

    private fun work() {
        while (true) {
            val span = queue.take()
            if (span === STOP) return
            try {
                if (!failed) {
                    span.lineOf(decoder.decode(span.samples))?.let { line ->
                        decoded += WordTimer.retime(listOf(line), emptyList()).single()
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "live Whisper failed; the recording continues without it", t)
                failed = true
                _status.update { it.copy(failure = t.message ?: t.toString()) }
            } finally {
                synchronized(lock) {
                    pending--
                    lock.notifyAll()
                }
                publish()
            }
        }
    }

    private companion object {
        /** Tells the worker to stop. */
        val STOP = SpeechSpan(-1, FloatArray(0))
    }
}
