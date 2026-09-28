package com.example.meshmessenger.mesh

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Small durable store-and-forward queue. Packets are kept locally until the app has a chance to relay them. */
class PendingMessageStore(context: Context) {
    private val prefs = context.getSharedPreferences("pending_mesh", Context.MODE_PRIVATE)
    private val lock = Any()

    data class Entry(val id: UUID, val bytes: ByteArray, val createdAt: Long)

    fun enqueue(packet: MeshPacket) = enqueueBytes(packet.messageId, packet.encode())

    fun enqueueBytes(id: UUID, bytes: ByteArray) = synchronized(lock) {
        val all = load().toMutableList()
        if (all.any { it.id == id }) return
        all.add(Entry(id, bytes, System.currentTimeMillis()))
        while (all.size > 256) all.removeAt(0)
        save(all)
    }

    fun snapshot(): List<Entry> = synchronized(lock) { load() }

    fun remove(id: UUID) = synchronized(lock) { save(load().filterNot { it.id == id }) }

    private fun load(): List<Entry> {
        val raw = prefs.getString("queue", "[]") ?: "[]"
        val array = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        return buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val id = runCatching { UUID.fromString(o.getString("id")) }.getOrNull() ?: continue
                val bytes = runCatching { Base64.decode(o.getString("data"), Base64.NO_WRAP) }.getOrNull() ?: continue
                add(Entry(id, bytes, o.optLong("createdAt", 0L)))
            }
        }
    }

    private fun save(entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { e ->
            array.put(JSONObject().apply {
                put("id", e.id.toString())
                put("data", Base64.encodeToString(e.bytes, Base64.NO_WRAP))
                put("createdAt", e.createdAt)
            })
        }
        prefs.edit().putString("queue", array.toString()).apply()
    }
}
