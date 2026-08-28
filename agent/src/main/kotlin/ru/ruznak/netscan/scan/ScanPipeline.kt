package ru.ruznak.netscan.scan

import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import ru.ruznak.netscan.config.AgentConfig
import ru.ruznak.netscan.output.ScanContext
import ru.ruznak.netscan.protocol.AckStatus
import ru.ruznak.netscan.protocol.ScanMessage
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.withLock

/** Результат обработки одного скана: возвращается телефону как ack. */
data class ScanOutcome(val status: AckStatus, val detail: String? = null) {
    companion object {
        val ACCEPTED = ScanOutcome(AckStatus.ACCEPTED)
        val DUPLICATE = ScanOutcome(AckStatus.DUPLICATE, "повтор")
        fun filtered(reason: String) = ScanOutcome(AckStatus.FILTERED, reason)
        fun failed(reason: String) = ScanOutcome(AckStatus.FAILED, reason)
    }
}

/** Счётчики для веб-консоли. */
@Serializable
data class PipelineStats(
    val accepted: Long,
    val duplicates: Long,
    val filtered: Long,
    val failed: Long,
)

/**
 * Единственный путь скана от телефона до ПК: фильтры → подавление повторов →
 * форматирование → приёмники.
 *
 * Обработка сериализована: два телефона, сканирующие одновременно, не должны
 * вводить символы вперемешку в одно и то же окно.
 */
class ScanPipeline(
    private val config: () -> AgentConfig,
    private val sink: (FormattedScan, ScanContext) -> Unit,
    private val history: ScanHistory,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = ReentrantLock(true)
    private val deduplicator = Deduplicator({ config().scan.duplicateWindowMs }, clock = clock)

    private val accepted = AtomicLong()
    private val duplicates = AtomicLong()
    private val filtered = AtomicLong()
    private val failed = AtomicLong()

    fun stats(): PipelineStats = PipelineStats(accepted.get(), duplicates.get(), filtered.get(), failed.get())

    fun submit(message: ScanMessage, deviceId: String, deviceName: String): ScanOutcome {
        val settings = config()
        val receivedAt = clock()
        val rawCode = message.code

        val rejection = validate(rawCode, message.format, settings)
        if (rejection != null) return record(rejection, message, deviceId, deviceName, receivedAt)

        // Дедупликация и вывод — под одним замком, иначе два одинаковых кода,
        // пришедших одновременно, оба прошли бы проверку.
        return lock.withLock {
            if (!deduplicator.accept(deviceId, message.id, rawCode)) {
                return@withLock record(ScanOutcome.DUPLICATE, message, deviceId, deviceName, receivedAt)
            }

            val formatted = ScanFormatter.format(rawCode, settings.output)
            val context = ScanContext(
                scanId = message.id,
                deviceId = deviceId,
                deviceName = deviceName,
                rawCode = rawCode,
                format = message.format,
                receivedAt = receivedAt,
            )

            val outcome = runCatching { sink(formatted, context) }
                .fold(
                    onSuccess = { ScanOutcome.ACCEPTED },
                    onFailure = { error ->
                        log.warn("Не удалось вывести код на ПК: {}", error.message)
                        ScanOutcome.failed(error.message ?: "ошибка вывода")
                    },
                )
            record(outcome, message, deviceId, deviceName, receivedAt)
        }
    }

    private fun validate(code: String, format: String, settings: AgentConfig): ScanOutcome? {
        val scanRules = settings.scan
        val effective = if (settings.output.trim) code.trim() else code

        if (effective.length < scanRules.minLength) return ScanOutcome.filtered("код короче ${scanRules.minLength} символов")
        if (effective.length > scanRules.maxLength) return ScanOutcome.filtered("код длиннее ${scanRules.maxLength} символов")

        val allowed = scanRules.allowedFormats
        if (allowed.isNotEmpty() && allowed.none { it.equals(format, ignoreCase = true) }) {
            return ScanOutcome.filtered("символика $format отключена")
        }

        val regex = scanRules.filterRegex?.takeIf { it.isNotBlank() }
        if (regex != null) {
            val matches = runCatching { Regex(regex).containsMatchIn(effective) }
                .getOrElse {
                    log.warn("Некорректное регулярное выражение фильтра: {}", it.message)
                    true
                }
            if (!matches) return ScanOutcome.filtered("код не подходит под фильтр")
        }
        return null
    }

    private fun record(
        outcome: ScanOutcome,
        message: ScanMessage,
        deviceId: String,
        deviceName: String,
        receivedAt: Long,
    ): ScanOutcome {
        when (outcome.status) {
            AckStatus.ACCEPTED -> accepted
            AckStatus.DUPLICATE -> duplicates
            AckStatus.FILTERED -> filtered
            AckStatus.FAILED -> failed
        }.incrementAndGet()

        history.add(
            ScanRecord(
                id = message.id,
                deviceId = deviceId,
                deviceName = deviceName,
                code = message.code,
                format = message.format,
                receivedAt = receivedAt,
                outcome = outcome.status.name.lowercase(),
                detail = outcome.detail,
            ),
        )
        return outcome
    }

    private companion object {
        val log = LoggerFactory.getLogger(ScanPipeline::class.java)
    }
}
