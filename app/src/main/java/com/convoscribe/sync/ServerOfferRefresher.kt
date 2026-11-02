package com.convoscribe.sync

import com.convoscribe.settings.SettingsRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps [com.convoscribe.settings.AppSettings.serverOffer] up to date
 * while *Use this server's services* is on (issue #94). Only an answer
 * from the server replaces the offer, and only for the address it was
 * asked of: a failed or refused fetch keeps the last one, so the
 * services keep working offline.
 */
class ServerOfferRefresher(
    private val settings: SettingsRepository,
    private val client: ServerConfigClient,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val mutex = Mutex()
    private var lastAttemptAt = 0L

    /** Fetch now; `null` when there is no server or its services are off. */
    suspend fun refresh(): ServerConfigResult? = mutex.withLock {
        val current = settings.settings.value
        if (!current.serverServicesEnabled || current.syncServerUrl.isBlank()) return@withLock null
        lastAttemptAt = now()
        val url = current.syncServerUrl
        val result = client.fetch(url, settings.syncKey())
        if (result is ServerConfigResult.Offered) {
            settings.update { s -> if (s.syncServerUrl == url) s.copy(serverOffer = result.offer) else s }
        }
        result
    }

    /** [refresh] when the last attempt is more than [INTERVAL_MS] old, as on coming to the foreground. */
    suspend fun refreshIfDue(): ServerConfigResult? {
        if (now() - lastAttemptAt < INTERVAL_MS) return null
        return refresh()
    }

    companion object {
        const val INTERVAL_MS = 60 * 60 * 1000L
    }
}
