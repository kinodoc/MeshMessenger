package com.example.meshmessenger.mesh

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Prevents a packet from endlessly circulating around a mesh. */
class SeenMessageStore(private val maxEntries: Int = 4096) {
    private val seen = ConcurrentHashMap<UUID, Long>()

    fun acceptFirstTime(id: UUID): Boolean {
        if (seen.putIfAbsent(id, System.currentTimeMillis()) != null) return false
        if (seen.size > maxEntries) {
            seen.entries.minByOrNull { it.value }?.let { seen.remove(it.key) }
        }
        return true
    }
}
