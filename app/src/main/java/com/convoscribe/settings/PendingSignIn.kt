package com.convoscribe.settings

import kotlinx.serialization.Serializable

/**
 * A device sign-in the user has not finished (issue #95): the key
 * request the app started at [proxyUrl] as [clientId], the
 * [requestSecret] that alone collects its key, the [state] the browser
 * must hand back, and when it started ([startedAt], epoch millis).
 */
@Serializable
data class PendingSignIn(
    val proxyUrl: String,
    val clientId: String,
    val requestSecret: String,
    val state: String,
    val startedAt: Long,
    val intervalSeconds: Int = 5
) {
    /** Past the proxy's 10 minutes to approve and 5 to collect the key. */
    fun expired(now: Long): Boolean = now - startedAt > LIFETIME_MS

    companion object {
        const val LIFETIME_MS = 15 * 60 * 1000L
    }
}
