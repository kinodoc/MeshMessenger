package com.example.meshmessenger.mesh

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Network
import java.net.Inet4Address
import java.net.InetAddress
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

    fun localNetBirdIp(): String? {
        val interfaces = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        }.getOrDefault(emptyList())

        for (iface in interfaces) {
            val address = runCatching {
                iface.inetAddresses.toList()
                    .firstOrNull { it is Inet4Address && isNetBirdAddress(it) }
            }.getOrNull()

            if (address is Inet4Address) {
                return address.hostAddress
            }
        }

        return null
    }

    fun netBirdNetwork(): Network? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val vpnNetworks = cm.allNetworks.filter { network ->
            cm.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }

        // Prefer a VPN network whose LinkProperties explicitly exposes the
        // NetBird 100.64/10 address. Some Android VPN implementations do not
        // mirror the tunnel address into LinkProperties, so keep a fallback
        // when there is exactly one VPN and the OS network interfaces show a
        // NetBird address.
        vpnNetworks.firstOrNull { network ->
            cm.getLinkProperties(network)?.linkAddresses
                ?.any { isNetBirdAddress(it.address) } == true
        }?.let { return it }

        // Some NetBird Android builds expose the tunnel interface name (wt0)
        // but omit the 100.x address from LinkProperties. Prefer that VPN when
        // several VPN networks exist.
        vpnNetworks.firstOrNull { network ->
            val name = cm.getLinkProperties(network)?.interfaceName.orEmpty()
            NETBIRD_NAMES.matches(name)
        }?.let { return it }

        return if (vpnNetworks.size == 1 && localNetBirdIp() != null) {
            vpnNetworks.first()
        } else {
            null
        }
    }

    /**
     * Returns IPv4 destinations actually routed through the NetBird VPN.
     * NetBird commonly installs host routes (/32) for peers, so LAN broadcast
     * discovery cannot see them.
     */
    fun netBirdDiscoveryTargets(): List<java.net.InetAddress> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = netBirdNetwork() ?: return emptyList()
        val result = linkedSetOf<java.net.InetAddress>()
        val local = localNetBirdIp()

        runCatching {
            cm.getLinkProperties(network)?.routes.orEmpty().forEach { route ->
                val ipv4 = route.destination.address as? Inet4Address ?: return@forEach
                val prefix = route.destination.prefixLength
                if (!isNetBirdAddress(ipv4) || ipv4.hostAddress == local) return@forEach
                when {
                    prefix == 32 -> result.add(ipv4)
                    prefix in 24..30 -> {
                        val base = java.nio.ByteBuffer.wrap(ipv4.address).int
                        val hostBits = 32 - prefix
                        val hostCount = 1 shl hostBits
                        val mask = -1 shl hostBits
                        val networkBase = base and mask
                        for (host in 1 until hostCount - 1) {
                            val target = java.net.InetAddress.getByAddress(
                                java.nio.ByteBuffer.allocate(4).putInt(networkBase or host).array()
                            )
                            if (target.hostAddress != local) result.add(target)
                        }
                    }
                }
            }
        }

        // NetBird assigns one /16 overlay block per account. Some Android VPN
        // implementations expose only that /16 route, not individual /32 peer
        // routes. Probe the local /24 as a bounded fallback instead of silently
        // returning zero targets; known contact IPs are added by the service too.
        runCatching {
            val localAddress = local?.let { InetAddress.getByName(it) } as? Inet4Address
            if (localAddress != null) {
                val b = localAddress.address.map { it.toInt() and 0xff }
                for (host in 1..254) {
                    val target = InetAddress.getByAddress(
                        byteArrayOf(b[0].toByte(), b[1].toByte(), b[2].toByte(), host.toByte())
                    )
                    if (target.hostAddress != local) result.add(target)
                }
            }
        }
        return result.toList()
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
