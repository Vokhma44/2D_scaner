package ru.ruznak.netscan.output

import ru.ruznak.netscan.config.SinkKind
import ru.ruznak.netscan.scan.FormattedScan

/** Откуда пришёл код: попадает в журнал и в вебхук. */
data class ScanContext(
    val scanId: String,
    val deviceId: String,
    val deviceName: String,
    val rawCode: String,
    val format: String,
    val receivedAt: Long,
)

/** Приёмник отсканированных данных на стороне ПК. */
interface OutputSink : AutoCloseable {
    val kind: SinkKind

    /** Человекочитаемое состояние приёмника для консоли. */
    val status: String get() = "готов"

    fun emit(scan: FormattedScan, context: ScanContext)

    override fun close() {}
}

/**
 * Раздаёт скан всем настроенным приёмникам. Ошибка одного приёмника не отменяет
 * остальные: файл продолжает писаться, даже если вебхук недоступен.
 */
class CompositeSink(private val sinks: List<OutputSink>) : OutputSink {

    override val kind: SinkKind get() = sinks.firstOrNull()?.kind ?: SinkKind.CONSOLE

    override val status: String
        get() = sinks.joinToString(", ") { "${it.kind.name.lowercase()}: ${it.status}" }

    val kinds: List<SinkKind> get() = sinks.map { it.kind }

    override fun emit(scan: FormattedScan, context: ScanContext) {
        val failures = mutableListOf<String>()
        for (sink in sinks) {
            runCatching { sink.emit(scan, context) }
                .onFailure { failures += "${sink.kind.name.lowercase()}: ${it.message ?: it::class.simpleName}" }
        }
        if (failures.isNotEmpty()) error(failures.joinToString("; "))
    }

    override fun close() = sinks.forEach { runCatching { it.close() } }.let { }
}
