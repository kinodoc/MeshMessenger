package com.example.meshmessenger.mesh

import java.util.LinkedHashMap
import java.util.UUID

/**
 * Bounded duplicate suppression for packets travelling through the mesh.
 *
 * Entries expire so a long-running process does not retain old IDs forever.
 * All operations are synchronized because BLE and relay callbacks can arrive
 * on different threads.
 */
class SeenMessageStore(
    private val maxEntries: Int = 4096,
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
        require(maxAgeMs > 0) { "maxAgeMs must be positive" }
    }

    // LinkedHashMap preserves insertion order: the eldest ID is evicted first.
    private val seen = LinkedHashMap<UUID, Long>()

    @Synchronized
    fun acceptFirstTime(id: UUID, nowMs: Long = System.currentTimeMillis()): Boolean {
        pruneExpired(nowMs)
        if (seen.containsKey(id)) return false

        while (seen.size >= maxEntries) {
            val eldest = seen.entries.iterator()
            if (!eldest.hasNext()) break
            eldest.next()
            eldest.remove()
        }
        seen[id] = nowMs
        return true
    }

    @Synchronized
    fun size(nowMs: Long = System.currentTimeMillis()): Int {
        pruneExpired(nowMs)
        return seen.size
    }

    private fun pruneExpired(nowMs: Long) {
        val iterator = seen.entries.iterator()
        while (iterator.hasNext()) {
            val age = nowMs - iterator.next().value
            if (age >= 0 && age > maxAgeMs) iterator.remove()
        }
    }

    companion object {
        // Long enough to suppress ordinary mesh loops and short retries, while
        // keeping memory bounded in a long-lived foreground service.
        const val DEFAULT_MAX_AGE_MS: Long = 6 * 60 * 60 * 1000L
    }
}
