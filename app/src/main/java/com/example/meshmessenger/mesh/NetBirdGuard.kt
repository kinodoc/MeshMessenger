package com.example.meshmessenger.mesh

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Проверка состояния NetBird/VPN.
 *
 * Android не предоставляет стороннему приложению универсальный
 * способ узнать имя VPN-приложения другого разработчика.
 * Поэтому используем несколько признаков:
 * 1. интерфейсы wt*, netbird*, tun*
 * 2. адрес NetBird из диапазона 100.64.0.0/10
 * 3. наличие системного VPN как отдельный статус
 */
class NetBirdGuard(private val context: Context) {

    companion object {
        private const val PREFS = "netbird_policy"
        private const val KEY_ONLY = "netbird_only"

        private const val NETBIRD_PACKAGE = "io.netbird.client"

        private val NETBIRD_NAMES = Regex(
            "^(wt\\d+|netbird\\d*|tun\\d+)$",
            RegexOption.IGNORE_CASE
        )
    }

    private val prefs =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var onlyNetBird: Boolean
        get() = prefs.getBoolean(KEY_ONLY, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ONLY, value).apply()
        }

    fun status(): Status {
        val interfaces = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        }.getOrDefault(emptyList())

        // 1. Явное имя интерфейса NetBird/WireGuard.
        val namedNetBird = interfaces.any { iface ->
            runCatching {
                iface.isUp &&
                    !iface.isLoopback &&
                    NETBIRD_NAMES.matches(iface.name)
            }.getOrDefault(false)
        }

        if (namedNetBird) {
            return Status.CONNECTED
        }

        // 2. NetBird обычно использует адреса CGNAT 100.64.0.0/10.
        val netBirdAddress = interfaces.any { iface ->
            runCatching {
                if (!iface.isUp || iface.isLoopback) {
                    false
                } else {
                    iface.inetAddresses.toList().any { address ->
                        val ipv4 = address as? Inet4Address ?: return@any false
                        isNetBirdAddress(ipv4.address)
                    }
                }
            }.getOrDefault(false)
        }

        if (netBirdAddress) {
            return Status.CONNECTED
        }

        // 3. VPN есть, но подтвердить именно NetBird не удалось.
        val vpnPresent = runCatching {
            val cm = context.getSystemService(
                Context.CONNECTIVITY_SERVICE
            ) as ConnectivityManager

            cm.allNetworks.any { network ->
                cm.getNetworkCapabilities(network)?.hasTransport(
                    NetworkCapabilities.TRANSPORT_VPN
                ) == true
            }
        }.getOrDefault(false)

        return if (vpnPresent) {
            Status.VPN_PRESENT
        } else {
            Status.OFFLINE
        }
    }

    /**
     * Разрешаем IP-транспорт только если пользователь включил
     * режим "только внутренняя сеть" и NetBird удалось подтвердить.
     */
    fun allowIpTransport(): Boolean {
        return !onlyNetBird || status() == Status.CONNECTED
    }

    fun isNetBirdAddress(address: java.net.InetAddress): Boolean {
        val ipv4 = address as? java.net.Inet4Address ?: return false
        return isNetBirdAddress(ipv4.address)
    }

    private fun isNetBirdAddress(bytes: ByteArray): Boolean {
        if (bytes.size != 4) return false

        val a = bytes[0].toInt() and 0xff
        val b = bytes[1].toInt() and 0xff

        // 100.64.0.0/10 = 100.64.x.x ... 100.127.x.x
        return a == 100 && b in 64..127
    }

    enum class Status {
        CONNECTED,
        VPN_PRESENT,
        OFFLINE
    }
}
