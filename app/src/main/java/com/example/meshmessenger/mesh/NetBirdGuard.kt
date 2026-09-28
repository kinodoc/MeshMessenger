package com.example.meshmessenger.mesh

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.NetworkInterface

/**
 * NetBird-only policy for the IP transport.
 * BLE mesh is independent of this policy.
 * Android does not expose a stable public API saying "this VPN is NetBird",
 * so we use the common NetBird interface names and VPN transport as a conservative signal.
 */
class NetBirdGuard(private val context: Context) {
    companion object {
        private const val PREFS = "netbird_policy"
        private const val KEY_ONLY = "netbird_only"
        private val NETBIRD_NAMES = setOf("wt0", "netbird", "netbird0", "tun0")
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var onlyNetBird: Boolean
        get() = prefs.getBoolean(KEY_ONLY, false)
        set(value) { prefs.edit().putBoolean(KEY_ONLY, value).apply() }

    fun status(): Status {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces().toList() }.getOrDefault(emptyList())
        val named = interfaces.firstOrNull { it.isUp && it.name.lowercase() in NETBIRD_NAMES }
        if (named != null) return Status.CONNECTED

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val vpn = cm.allNetworks.firstOrNull { network ->
            cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
        return if (vpn != null) Status.VPN_PRESENT else Status.OFFLINE
    }

    fun allowIpTransport(): Boolean = !onlyNetBird || status() == Status.CONNECTED

    enum class Status { CONNECTED, VPN_PRESENT, OFFLINE }
}
