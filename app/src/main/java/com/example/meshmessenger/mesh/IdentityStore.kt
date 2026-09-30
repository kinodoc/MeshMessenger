package com.example.meshmessenger.mesh

import android.content.Context
import android.util.Base64
import java.security.KeyPair

class IdentityStore(context: Context) {
    private val prefs = context.getSharedPreferences("identity", Context.MODE_PRIVATE)
    val keyPair: KeyPair by lazy { loadOrCreate() }
    val nodeId: String by lazy { CryptoManager.keyId(keyPair.public) }
    val publicKeyBase64: String by lazy { CryptoManager.publicKeyBase64(keyPair.public) }
    var displayName: String
        get() = prefs.getString("display_name", "Я")?.trim().orEmpty().ifBlank { "Я" }
        set(value) { prefs.edit().putString("display_name", value.trim().ifBlank { "Я" }).apply() }

    private fun loadOrCreate(): KeyPair {
        val pub = prefs.getString("public", null)
        val priv = prefs.getString("private", null)
        if (pub != null && priv != null) {
            return java.security.KeyPair(
                CryptoManager.publicKeyFromBase64(pub),
                CryptoManager.privateKeyFromBytes(Base64.decode(priv, Base64.DEFAULT))
            )
        }
        val pair = CryptoManager.generateIdentity()
        prefs.edit()
            .putString("public", CryptoManager.publicKeyBase64(pair.public))
            .putString("private", Base64.encodeToString(pair.private.encoded, Base64.NO_WRAP))
            .apply()
        return pair
    }
}
