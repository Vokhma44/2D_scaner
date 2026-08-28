package ru.ruznak.netscan.security

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.ruznak.netscan.protocol.DeviceInfo
import ru.ruznak.netscan.protocol.DeviceStatus
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/** Сопряжённое устройство. Секрет сессии хранится только как хеш. */
@Serializable
data class DeviceRecord(
    val id: String,
    val name: String,
    val platform: String,
    val sessionTokenHash: String,
    val status: DeviceStatus,
    val pairedAt: Long,
    val lastSeenAt: Long = pairedAt,
    val scanCount: Long = 0,
)

@Serializable
private data class DeviceStorage(val devices: List<DeviceRecord> = emptyList())

/**
 * Реестр сопряжённых телефонов. Переживает перезапуск агента: повторное
 * сопряжение после закрытия ноутбука не требуется.
 */
class DeviceRegistry(private val file: Path?, private val clock: () -> Long = System::currentTimeMillis) {

    private val byId = ConcurrentHashMap<String, DeviceRecord>()
    private val idByTokenHash = ConcurrentHashMap<String, String>()

    init {
        load()
    }

    fun devices(): List<DeviceRecord> = byId.values.sortedByDescending { it.lastSeenAt }

    fun byId(id: String): DeviceRecord? = byId[id]

    /** Регистрирует устройство и возвращает пару «запись, секрет сессии». */
    fun register(info: DeviceInfo, requireApproval: Boolean): Pair<DeviceRecord, String> {
        val sessionToken = Tokens.sessionToken()
        val now = clock()
        val record = DeviceRecord(
            id = Tokens.deviceId(),
            name = info.name.ifBlank { "Телефон" }.take(64),
            platform = info.platform.take(64),
            sessionTokenHash = Tokens.sha256(sessionToken),
            status = if (requireApproval) DeviceStatus.PENDING_APPROVAL else DeviceStatus.ACTIVE,
            pairedAt = now,
        )
        put(record)
        persist()
        return record to sessionToken
    }

    /** Находит устройство по предъявленному секрету сессии. */
    fun authenticate(sessionToken: String): DeviceRecord? {
        val id = idByTokenHash[Tokens.sha256(sessionToken)] ?: return null
        return byId[id]
    }

    fun approve(id: String): DeviceRecord? = mutate(id) { it.copy(status = DeviceStatus.ACTIVE) }

    fun revoke(id: String): DeviceRecord? = mutate(id) { it.copy(status = DeviceStatus.REVOKED) }

    fun forget(id: String): Boolean {
        val removed = byId.remove(id) ?: return false
        idByTokenHash.remove(removed.sessionTokenHash)
        persist()
        return true
    }

    fun revokeAll() {
        byId.keys.toList().forEach { forget(it) }
    }

    fun touch(id: String, scans: Long = 0) {
        mutate(id, persist = false) {
            it.copy(lastSeenAt = clock(), scanCount = it.scanCount + scans)
        }
    }

    /** Сохраняет накопленную статистику: вызывается по таймеру и при остановке. */
    fun flush() = persist()

    private fun mutate(id: String, persist: Boolean = true, mutator: (DeviceRecord) -> DeviceRecord): DeviceRecord? {
        val updated = byId.computeIfPresent(id) { _, existing -> mutator(existing) } ?: return null
        idByTokenHash[updated.sessionTokenHash] = updated.id
        if (persist) persist()
        return updated
    }

    private fun put(record: DeviceRecord) {
        byId[record.id] = record
        idByTokenHash[record.sessionTokenHash] = record.id
    }

    private fun load() {
        val path = file ?: return
        if (!Files.exists(path)) return
        runCatching { JSON.decodeFromString<DeviceStorage>(Files.readString(path)) }
            .getOrNull()
            ?.devices
            ?.forEach(::put)
    }

    private fun persist() {
        val path = file ?: return
        runCatching {
            Files.createDirectories(path.parent)
            val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
            Files.writeString(tmp, JSON.encodeToString(DeviceStorage(byId.values.toList())))
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        val JSON = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
