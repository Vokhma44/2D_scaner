package ru.ruznak.netscan

import kotlinx.serialization.Serializable
import ru.ruznak.netscan.protocol.ProtocolJson
import ru.ruznak.netscan.protocol.ServerMessage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Живое подключение телефона. */
class Connection(
    val id: Long,
    val deviceId: String,
    val deviceName: String,
    val remoteHost: String,
    val connectedAt: Long,
    private val sender: suspend (String) -> Unit,
) {
    @Volatile
    var lastMessageAt: Long = connectedAt
        internal set

    @Volatile
    var scans: Long = 0
        internal set

    suspend fun send(message: ServerMessage) =
        sender(ProtocolJson.encodeToString(ServerMessage.serializer(), message))
}

/** Снимок подключения для веб-консоли. */
@Serializable
data class ConnectionView(
    val deviceId: String,
    val deviceName: String,
    val remoteHost: String,
    val connectedAt: Long,
    val lastMessageAt: Long,
    val scans: Long,
)

/** Реестр открытых WebSocket-подключений: телефонов может быть несколько на один ПК. */
class ConnectionRegistry {

    private val connections = ConcurrentHashMap<Long, Connection>()
    private val sequence = AtomicLong()

    fun open(
        deviceId: String,
        deviceName: String,
        remoteHost: String,
        clock: () -> Long = System::currentTimeMillis,
        sender: suspend (String) -> Unit,
    ): Connection {
        val connection = Connection(sequence.incrementAndGet(), deviceId, deviceName, remoteHost, clock(), sender)
        connections[connection.id] = connection
        return connection
    }

    fun close(connection: Connection) {
        connections.remove(connection.id)
    }

    fun isOnline(deviceId: String): Boolean = connections.values.any { it.deviceId == deviceId }

    fun count(): Int = connections.size

    fun views(): List<ConnectionView> = connections.values
        .sortedBy { it.connectedAt }
        .map {
            ConnectionView(it.deviceId, it.deviceName, it.remoteHost, it.connectedAt, it.lastMessageAt, it.scans)
        }

    /** Рассылка сообщения всем телефонам; отвалившееся подключение не мешает остальным. */
    suspend fun broadcast(message: ServerMessage) {
        for (connection in connections.values) {
            runCatching { connection.send(message) }
        }
    }

    /** Закрывает подключения отозванного устройства. */
    suspend fun notifyDevice(deviceId: String, message: ServerMessage) {
        for (connection in connections.values.filter { it.deviceId == deviceId }) {
            runCatching { connection.send(message) }
        }
    }
}
