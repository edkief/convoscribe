package com.convoscribe.asr

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

/**
 * Whisper Large-v3(-Turbo)'s vocabulary (issue #159), for the NPU build,
 * which hands back token ids. Text tokens come from sherpa-onnx's
 * `turbo-tokens.txt` (one `<base64 bytes> <id>` line per byte-level BPE
 * token); the special tokens after them have fixed ids, listed here.
 */
class WhisperTokens(private val bytesById: Array<ByteArray?>) {

    /**
     * The text of [ids]: special tokens (and unknown ids) are dropped,
     * and the token bytes are decoded as UTF-8 together, so a character
     * split across tokens stays whole.
     */
    fun decode(ids: List<Int>): String {
        val out = ByteArrayOutputStream()
        for (id in ids) {
            if (id < 0 || id >= EOT || id >= bytesById.size) continue
            bytesById[id]?.let { out.write(it) }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    companion object {
        const val EOT = 50257
        const val SOT = 50258
        private const val FIRST_LANGUAGE = 50259
        const val TRANSLATE = 50359
        const val TRANSCRIBE = 50360
        const val NO_TIMESTAMPS = 50364

        /** Whisper's language codes in token order (`tokenizer.LANGUAGES`, Large-v3 adds `yue`). */
        val LANGUAGES: List<String> = listOf(
            "en", "zh", "de", "es", "ru", "ko", "fr", "ja", "pt", "tr", "pl", "ca", "nl", "ar", "sv", "it",
            "id", "hi", "fi", "vi", "he", "uk", "el", "ms", "cs", "ro", "da", "hu", "ta", "no", "th", "ur",
            "hr", "bg", "lt", "la", "mi", "ml", "cy", "sk", "te", "fa", "lv", "bn", "sr", "az", "sl", "kn",
            "et", "mk", "br", "eu", "is", "hy", "ne", "mn", "bs", "kk", "sq", "sw", "gl", "mr", "pa", "si",
            "km", "sn", "yo", "so", "af", "oc", "ka", "be", "tg", "sd", "gu", "am", "yi", "lo", "uz", "fo",
            "ht", "ps", "tk", "nn", "mt", "sa", "lb", "my", "bo", "tl", "mg", "as", "tt", "haw", "ln", "ha",
            "ba", "jw", "su", "yue"
        )

        /** The `<|xx|>` token for [language], or `null` if Whisper does not know it. */
        fun languageToken(language: String): Int? =
            LANGUAGES.indexOf(language.lowercase()).takeIf { it >= 0 }?.let { FIRST_LANGUAGE + it }

        /** The forced decoder prompt: start, language, transcribe, no timestamps. */
        fun prompt(language: String): IntArray {
            val lang = requireNotNull(languageToken(language)) { "Whisper does not support language '$language'" }
            return intArrayOf(SOT, lang, TRANSCRIBE, NO_TIMESTAMPS)
        }

        /** Parse a `turbo-tokens.txt` file. */
        fun load(file: File): WhisperTokens = parse(file.readLines())

        fun parse(lines: List<String>): WhisperTokens {
            val entries = mutableListOf<Pair<Int, ByteArray>>()
            val decoder = Base64.getDecoder()
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val space = trimmed.lastIndexOf(' ')
                if (space <= 0) continue
                val id = trimmed.substring(space + 1).toIntOrNull() ?: continue
                val bytes = runCatching { decoder.decode(trimmed.substring(0, space)) }.getOrNull() ?: continue
                entries += id to bytes
            }
            val size = (entries.maxOfOrNull { it.first } ?: -1) + 1
            val table = arrayOfNulls<ByteArray>(size)
            for ((id, bytes) in entries) table[id] = bytes
            return WhisperTokens(table)
        }
    }
}
