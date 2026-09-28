package com.example.meshmessenger.mesh

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** Local contact book. Only the public identity key is stored. */
class ContactStore(context: Context) {
    private val prefs = context.getSharedPreferences("contacts", Context.MODE_PRIVATE)
    private val lock = Any()

    data class Contact(val nodeId: String, val name: String, val publicKeyBase64: String)

    fun all(): List<Contact> = synchronized(lock) { load() }

    fun upsert(contact: Contact) = synchronized(lock) {
        val list = load().filterNot { it.nodeId == contact.nodeId }.toMutableList()
        list.add(contact)
        save(list)
    }

    fun get(nodeId: String): Contact? = all().firstOrNull { it.nodeId == nodeId }

    private fun load(): List<Contact> {
        val a = runCatching { JSONArray(prefs.getString("items", "[]")) }.getOrElse { JSONArray() }
        return buildList {
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val id = o.optString("nodeId")
                val name = o.optString("name", id.take(8))
                val key = o.optString("publicKey", "")
                if (id.isNotBlank() && key.isNotBlank()) add(Contact(id, name, key))
            }
        }
    }

    private fun save(items: List<Contact>) {
        val a = JSONArray()
        items.forEach { c -> a.put(JSONObject().apply {
            put("nodeId", c.nodeId); put("name", c.name); put("publicKey", c.publicKeyBase64)
        }) }
        prefs.edit().putString("items", a.toString()).apply()
    }
}
