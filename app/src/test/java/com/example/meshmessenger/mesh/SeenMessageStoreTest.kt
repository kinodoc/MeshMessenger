package com.example.meshmessenger.mesh

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeenMessageStoreTest {
    @Test
    fun duplicateIdIsRejectedWithinRetentionWindow() {
        val store = SeenMessageStore(maxEntries = 8, maxAgeMs = 1_000)
        val id = UUID.randomUUID()

        assertTrue(store.acceptFirstTime(id, nowMs = 10_000))
        assertFalse(store.acceptFirstTime(id, nowMs = 10_500))
        assertEquals(1, store.size(nowMs = 10_500))
    }

    @Test
    fun expiredIdCanBeAcceptedAgain() {
        val store = SeenMessageStore(maxEntries = 8, maxAgeMs = 1_000)
        val id = UUID.randomUUID()

        assertTrue(store.acceptFirstTime(id, nowMs = 10_000))
        assertTrue(store.acceptFirstTime(id, nowMs = 11_001))
    }

    @Test
    fun capacityEvictsOldestEntryFirst() {
        val store = SeenMessageStore(maxEntries = 2, maxAgeMs = 10_000)
        val oldest = UUID.randomUUID()
        val middle = UUID.randomUUID()
        val newest = UUID.randomUUID()

        assertTrue(store.acceptFirstTime(oldest, nowMs = 1))
        assertTrue(store.acceptFirstTime(middle, nowMs = 2))
        assertTrue(store.acceptFirstTime(newest, nowMs = 3))

        assertEquals(2, store.size(nowMs = 3))
        assertTrue(store.acceptFirstTime(oldest, nowMs = 4))
        assertFalse(store.acceptFirstTime(newest, nowMs = 4))
    }
}
