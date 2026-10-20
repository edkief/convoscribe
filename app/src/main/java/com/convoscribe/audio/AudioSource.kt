package com.convoscribe.audio

import android.annotation.SuppressLint
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * A source of 16 kHz / mono / PCM16 audio broken into
 * [AudioSpec.CHUNK_SAMPLES]-sample frames. The recording service
 * (TASK-12) and the ASR pipeline collect from [chunks] to drive the
 * recognizer and to feed the WAV writer at the same time.
 */
interface AudioSource {
    /**
     * A cold flow of audio chunks. The recording only starts when
     * the flow is collected and stops when the collector goes away
     * or the flow throws.
     */
    fun chunks(): Flow<ShortArray>
}

/**
 * Microphone-backed [AudioSource]. Uses the `VOICE_RECOGNITION`
 * audio source, which Android tunes for speech recognizers: the
 * compatibility definition requires noise reduction (and automatic
 * gain control) to be *off* for it, so the audio is close to raw.
 * That is what the recognizers here do best on, and what the
 * optional noise cleanup (issue #37) starts from. Sampling and
 * channel configuration are fixed to the project's [AudioSpec].
 *
 * Callers are responsible for holding the `RECORD_AUDIO` runtime
 * permission before collecting [chunks] (the constructor is
 * permission-free so the class can be instantiated from places that
 * do not yet have it, e.g. the setup screen). If the platform cannot
 * allocate an [AudioRecord] in the `STATE_INITIALIZED` state — no
 * mic, busy device, denied permission — [chunks] throws an
 * [IllegalStateException] rather than blocking.
 */
@SuppressLint("MissingPermission")
class MicAudioSource : AudioSource {

    override fun chunks(): Flow<ShortArray> = flow {
        // `CHUNK_SAMPLES * 2 * 4` is four chunks worth of 16-bit
        // samples, which keeps the OS ring buffer from being
        // drained faster than the flow can emit (a 1-chunk buffer
        // drops every time the ASR thread pauses for a UI tick).
        val minBuffer = AudioRecord.getMinBufferSize(
            AudioSpec.SAMPLE_RATE,
            AndroidAudioFormat.CHANNEL_IN_MONO,
            AndroidAudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(
            if (minBuffer > 0) minBuffer else 0,
            AudioSpec.CHUNK_SAMPLES * 2 * 4
        )

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            AudioSpec.SAMPLE_RATE,
            AndroidAudioFormat.CHANNEL_IN_MONO,
            AndroidAudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) {
                "Microphone unavailable"
            }
            recorder.startRecording()
            val buf = ShortArray(AudioSpec.CHUNK_SAMPLES)
            while (true) {
                val n = recorder.read(buf, 0, AudioSpec.CHUNK_SAMPLES)
                if (n > 0) {
                    emit(buf.copyOf(n))
                } else if (n < 0) {
                    // An error code from AudioRecord#read — better
                    // to surface it once and let the caller decide
                    // whether to retry than to spin.
                    throw IllegalStateException(
                        "AudioRecord.read returned error code $n"
                    )
                }
                // n == 0 means "no data yet" but `read` is blocking,
                // so a true zero is only possible if the buffer is
                // empty and the OS asks us to come back later;
            }
        } finally {
            // Order matters: stop the stream first so the OS
            // releases the mic for other apps, then close the
            // recorder to free the native object.
            try {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop()
                }
            } finally {
                recorder.release()
            }
        }
    }.flowOn(Dispatchers.IO)
}
