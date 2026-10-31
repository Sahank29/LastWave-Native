package com.lastwave.app.data.music

/** Let the player's first media request validate the URL; probe formats on a rejected-URL retry. */
internal class PlaybackUrlValidationPolicy(
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private data class TrackKey(val videoId: String, val authScope: String)
    private data class ClientKey(val track: TrackKey, val client: String)

    private val lock = Any()
    private val unverified = LinkedHashMap<ClientKey, Long>()
    private val probeRetries = LinkedHashMap<TrackKey, Long>()

    fun requiresProbe(videoId: String, authScope: String): Boolean = synchronized(lock) {
        trim()
        probeRetries.containsKey(TrackKey(videoId, authScope))
    }

    fun recordUnverified(videoId: String, authScope: String, client: String) = synchronized(lock) {
        trim()
        val track = TrackKey(videoId, authScope)
        if (!probeRetries.containsKey(track)) {
            unverified[ClientKey(track, client)] = nowMs() + RETRY_WINDOW_MS
        }
        trim()
    }

    /** True grants one validated retry with the same client before its normal cooldown applies. */
    fun retryWithProbe(videoId: String, authScope: String, client: String): Boolean = synchronized(lock) {
        trim()
        val track = TrackKey(videoId, authScope)
        if (unverified.remove(ClientKey(track, client)) == null) return@synchronized false
        probeRetries[track] = nowMs() + RETRY_WINDOW_MS
        trim()
        true
    }

    private fun trim() {
        val now = nowMs()
        unverified.entries.removeAll { it.value <= now }
        probeRetries.entries.removeAll { it.value <= now }
        while (unverified.size > MAX_ENTRIES) unverified.remove(unverified.keys.first())
        while (probeRetries.size > MAX_ENTRIES) probeRetries.remove(probeRetries.keys.first())
    }

    private companion object {
        const val RETRY_WINDOW_MS = 60_000L
        const val MAX_ENTRIES = 128
    }
}
