package com.example.meshmessenger.mesh

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ChatStore(context: Context) {
    private val prefs = context.getSharedPreferences("chat_history", Context.MODE_PRIVATE)
    private val lock = Any()

    enum class Delivery {
        SENT,
        WAITING,
        NOT_SENT
    }

    data class Message(
        val id: String,
        val peerId: String,
        val text: String,
        val mine: Boolean,
        val time: Long,
        val delivery: Delivery = Delivery.SENT,
        val packetId: String = ""
    )

    fun add(
        peerId: String,
        text: String,
        mine: Boolean,
        delivery: Delivery = Delivery.SENT,
        packetId: String = ""
    ) = synchronized(lock) {
        val a = load().toMutableList()

        a.add(
            Message(
                id = UUID.randomUUID().toString(),
                peerId = peerId,
                text = text,
                mine = mine,
                time = System.currentTimeMillis(),
                delivery = delivery,
                packetId = packetId
            )
        )

        while (a.size > 2000) a.removeAt(0)
        save(a)
    }

    fun messages(peerId: String): List<Message> =
        synchronized(lock) {
            load().filter { it.peerId == peerId }
        }

    fun updateDelivery(
        packetId: String,
        delivery: Delivery
    ) = synchronized(lock) {
        if (packetId.isBlank()) return@synchronized

        val updated = load().map { message ->
            if (message.packetId == packetId) {
                message.copy(delivery = delivery)
            } else {
                message
            }
        }

        save(updated)
    }

    private fun load(): List<Message> {
        val a = runCatching {
            JSONArray(prefs.getString("items", "[]"))
        }.getOrElse {
            JSONArray()
        }

        return buildList {
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue

                val delivery = runCatching {
                    Delivery.valueOf(
                        o.optString("delivery", Delivery.SENT.name)
                    )
                }.getOrDefault(Delivery.SENT)

                add(
                    Message(
                        id = o.optString("id"),
                        peerId = o.optString("peerId"),
                        text = o.optString("text"),
                        mine = o.optBoolean("mine"),
                        time = o.optLong("time"),
                        delivery = delivery,
                        packetId = o.optString("packetId", "")
                    )
                )
            }
        }
    }

    private fun save(items: List<Message>) {
        val a = JSONArray()

        items.forEach { m ->
            a.put(
                JSONObject().apply {
                    put("id", m.id)
                    put("peerId", m.peerId)
                    put("text", m.text)
                    put("mine", m.mine)
                    put("time", m.time)
                    put("delivery", m.delivery.name)
                    put("packetId", m.packetId)
                }
            )
        }

        prefs.edit()
            .putString("items", a.toString())
            .apply()
    }
}
