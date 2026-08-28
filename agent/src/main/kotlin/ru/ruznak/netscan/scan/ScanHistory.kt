package ru.ruznak.netscan.scan

import kotlinx.serialization.Serializable

/** Запись журнала для веб-консоли на ПК. */
@Serializable
data class ScanRecord(
    val id: String,
    val deviceId: String,
    val deviceName: String,
    val code: String,
    val format: String,
    val receivedAt: Long,
    val outcome: String,
    val detail: String? = null,
)

/** Кольцевой журнал последних сканов: нужен для разбора «код пришёл, но не вставился». */
class ScanHistory(private val capacity: () -> Int) {

    private val entries = ArrayDeque<ScanRecord>()

    fun add(record: ScanRecord) {
        synchronized(entries) {
            entries.addLast(record)
            val limit = capacity().coerceAtLeast(1)
            while (entries.size > limit) entries.removeFirst()
        }
    }

    /** Последние записи, новые сверху. */
    fun recent(limit: Int = 50): List<ScanRecord> = synchronized(entries) {
        entries.toList().takeLast(limit).asReversed()
    }

    fun clear() = synchronized(entries) { entries.clear() }

    fun size(): Int = synchronized(entries) { entries.size }
}
