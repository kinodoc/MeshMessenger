package com.example.meshmessenger.mesh

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class PendingIpMessageStore(context: Context) {
    private val prefs = context.getSharedPreferences("pending_ip_mesh", Context.MODE_PRIVATE)
    private val lock = Any()

    data class Entry(
        val id: UUID,
        val bytes: ByteArray,
        val ip: String,
        val createdAt: Long
    )

    fun enqueue(packet: MeshPacket, ip: String) = synchronized(lock) {
        val all = load().toMutableList()
        if (all.any { it.id == packet.messageId }) return@synchronized

        all.add(
            Entry(
                id = packet.messageId,
                bytes = packet.encode(),
                ip = ip,
                createdAt = System.currentTimeMillis()
            )
        )

        while (all.size > 256) all.removeAt(0)
        save(all)
    }

    fun snapshot(): List<Entry> = synchronized(lock) { load() }

    fun remove(id: UUID) = synchronized(lock) {
        save(load().filterNot { it.id == id })
    }

    private fun load(): List<Entry> {
        val raw = prefs.getString("queue", "[]") ?: "[]"
        val array = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }

        return buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue

                val id = runCatching {
                    UUID.fromString(o.getString("id"))
                }.getOrNull() ?: continue

                val bytes = runCatching {
                    Base64.decode(o.getString("data"), Base64.NO_WRAP)
                }.getOrNull() ?: continue

                val ip = o.optString("ip", "").trim()
                if (ip.isBlank()) continue

                add(
                    Entry(
                        id = id,
                        bytes = bytes,
                        ip = ip,
                        createdAt = o.optLong("createdAt", 0L)
                    )
                )
            }
        }
    }

    private fun save(entries: List<Entry>) {
        val array = JSONArray()

        entries.forEach { e ->
            array.put(
                JSONObject().apply {
                    put("id", e.id.toString())
                    put("data", Base64.encodeToString(e.bytes, Base64.NO_WRAP))
                    put("ip", e.ip)
                    put("createdAt", e.createdAt)
                }
            )
        }

        prefs.edit()
            .putString("queue", array.toString())
            .apply()
    }
}
