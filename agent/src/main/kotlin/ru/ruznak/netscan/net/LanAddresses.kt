package ru.ruznak.netscan.net

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** Адрес ПК в локальной сети, по которому телефон достучится до агента. */
data class LanAddress(val address: InetAddress, val interfaceName: String, val displayName: String) {
    val host: String get() = address.hostAddress
    val isIPv4: Boolean get() = address is Inet4Address
}

/**
 * Поиск адресов ПК в локальной сети. Именно этот адрес попадает в QR-код
 * сопряжения, поэтому виртуальные и выключенные интерфейсы отбрасываются:
 * телефон не должен получить адрес docker0 или туннеля VPN.
 */
object LanAddresses {

    private val VIRTUAL_PREFIXES = listOf(
        "docker", "br-", "veth", "virbr", "vmnet", "vboxnet", "utun", "tun", "tap", "zt", "wg",
    )

    fun discover(): List<LanAddress> = runCatching {
        NetworkInterface.networkInterfaces().toList()
            .filter { iface -> iface.isUp && !iface.isLoopback && !iface.isVirtual && !isVirtualByName(iface.name) }
            .flatMap { iface ->
                iface.inetAddresses.toList()
                    .filter { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
                    .map { LanAddress(it, iface.name, iface.displayName ?: iface.name) }
            }
            .sortedBy { rank(it) }
    }.getOrElse { emptyList() }

    /** Наиболее вероятный адрес для сопряжения. */
    fun primary(): LanAddress? = discover().firstOrNull()

    /** Все адреса, которые должны попасть в SAN сертификата. */
    fun certificateHosts(): List<InetAddress> =
        (discover().map { it.address } + InetAddress.getByName("127.0.0.1")).distinctBy { it.hostAddress }

    private fun isVirtualByName(name: String): Boolean {
        val lower = name.lowercase()
        return VIRTUAL_PREFIXES.any { lower.startsWith(it) }
    }

    /**
     * Домашние и офисные сети чаще всего 192.168.*, поэтому такой адрес показываем
     * первым; 10.* и 172.16-31.* идут следом, всё остальное — в конце.
     */
    private fun rank(candidate: LanAddress): Int {
        val host = candidate.host
        return when {
            host.startsWith("192.168.") -> 0
            host.startsWith("10.") -> 1
            host.startsWith("172.") -> 2
            else -> 3
        }
    }
}
