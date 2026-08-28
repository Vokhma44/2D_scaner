package ru.ruznak.netscan

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import ru.ruznak.netscan.config.AgentConfig
import ru.ruznak.netscan.config.ConfigStore
import ru.ruznak.netscan.net.LanAddresses
import ru.ruznak.netscan.net.TlsMaterial
import ru.ruznak.netscan.output.SinkManager
import ru.ruznak.netscan.protocol.ClientSettings
import ru.ruznak.netscan.scan.ScanHistory
import ru.ruznak.netscan.scan.ScanPipeline
import ru.ruznak.netscan.security.DeviceRegistry
import ru.ruznak.netscan.security.PairingService
import ru.ruznak.netscan.security.RateLimiter
import java.net.InetAddress

/** Версия агента: показывается в консоли и уходит телефону в welcome. */
const val AGENT_VERSION: String = "1.1.0"

/**
 * Собранное состояние работающего агента: одна точка, из которой маршруты
 * берут конфигурацию, устройства, конвейер сканов и параметры сопряжения.
 */
class AgentState(
    val configStore: ConfigStore,
    val devices: DeviceRegistry,
    val pairing: PairingService,
    val history: ScanHistory,
    val sinks: SinkManager,
    val connections: ConnectionRegistry = ConnectionRegistry(),
    val tls: TlsMaterial? = null,
    private val httpClient: HttpClient? = null,
    clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {

    val config: AgentConfig get() = configStore.config

    val pairingLimiter = RateLimiter(
        permitsPerWindow = config.security.pairingAttemptsPerMinute,
        clock = clock,
    )

    val pipeline = ScanPipeline(
        config = { configStore.config },
        sink = { scan, context -> sinks.emit(scan, context) },
        history = history,
        clock = clock,
    )

    /** Настройки, которые агент навязывает телефону при подключении. */
    fun clientSettings(): ClientSettings = ClientSettings(
        duplicateWindowMs = config.scan.duplicateWindowMs,
        allowedFormats = config.scan.allowedFormats,
        hostName = hostName(),
    )

    /** Адрес, который печатается в консоли и кодируется в QR. */
    fun pairingUrl(): String {
        val host = config.network.advertisedHost?.takeIf { it.isNotBlank() }
            ?: LanAddresses.primary()?.host
            ?: "localhost"
        return "https://$host:${config.network.httpsPort}/?p=${pairing.code}"
    }

    fun hostName(): String = runCatching { InetAddress.getLocalHost().hostName }.getOrElse { "ПК" }

    /** Рассылает всем подключённым телефонам новые настройки после правки в консоли. */
    suspend fun broadcastSettings() = connections.broadcast(
        ru.ruznak.netscan.protocol.SettingsPush(clientSettings()),
    )

    override fun close() {
        devices.flush()
        sinks.close()
        httpClient?.close()
    }

    companion object {
        fun defaultHttpClient(): HttpClient = HttpClient(CIO)
    }
}
