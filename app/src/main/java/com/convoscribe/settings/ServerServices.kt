package com.convoscribe.settings

/**
 * Where a service the sync server can provide comes from (issue #94):
 * the sync server, or the address, key and model the user typed in for
 * it. [key] is what is stored.
 */
enum class ServiceSource(val key: String) {
    SERVER("server"),
    OWN("own");

    companion object {
        fun fromKey(key: String?): ServiceSource? = entries.firstOrNull { it.key == key }
    }
}

/**
 * One service the sync server provides. [model] is the model to ask
 * for; blank means the server's own default.
 */
data class OfferedService(val model: String = "")

/**
 * What the sync server said it provides, from
 * `GET /api/sync/v1/config` (issue #94), at [fetchedAt] (epoch ms):
 * its language model, transcription and decision service (issue #101).
 * For [decision], a blank model lets laya pick one by language.
 * `null` means not provided; a server too old to have the endpoint
 * provides nothing. It is kept for offline use and replaced only by a
 * successful answer.
 */
data class ServerOffer(
    val llm: OfferedService? = null,
    val transcription: OfferedService? = null,
    val decision: OfferedService? = null,
    val fetchedAt: Long = 0L
)

/** The sync server's language model, under [AppSettings.syncServerUrl]. */
const val SERVER_LLM_PATH = "/api/ai/v1/llm"

/** The sync server's transcription, under [AppSettings.syncServerUrl]. */
const val SERVER_ASR_PATH = "/api/ai/v1/asr"

/** The sync server's decision service (issue #101), under [AppSettings.syncServerUrl]. */
const val SERVER_DECISION_PATH = "/api/ai/v1/decision"
