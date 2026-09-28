package com.example.meshmessenger.mesh

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ChatStore(context: Context) {
    private val prefs = context.getSharedPreferences("chat_history", Context.MODE_PRIVATE)
    private val lock = Any()
    data class Message(val id: String, val peerId: String, val text: String, val mine: Boolean, val time: Long)

    fun add(peerId: String, text: String, mine: Boolean) = synchronized(lock) {
        val a = load().toMutableList()
        a.add(Message(UUID.randomUUID().toString(), peerId, text, mine, System.currentTimeMillis()))
        while (a.size > 2000) a.removeAt(0)
        save(a)
    }

    fun messages(peerId: String): List<Message> = synchronized(lock) { load().filter { it.peerId == peerId } }

    private fun load(): List<Message> {
        val a = runCatching { JSONArray(prefs.getString("items", "[]")) }.getOrElse { JSONArray() }
        return buildList {
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                add(Message(o.optString("id"), o.optString("peerId"), o.optString("text"), o.optBoolean("mine"), o.optLong("time")))
            }
        }
    }
    private fun save(items: List<Message>) {
        val a = JSONArray()
        items.forEach { m -> a.put(JSONObject().apply {
            put("id", m.id); put("peerId", m.peerId); put("text", m.text); put("mine", m.mine); put("time", m.time)
        }) }
        prefs.edit().putString("items", a.toString()).apply()
    }
}
