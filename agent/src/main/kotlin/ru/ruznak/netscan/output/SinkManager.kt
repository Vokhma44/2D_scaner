package ru.ruznak.netscan.output

import io.ktor.client.HttpClient
import ru.ruznak.netscan.config.OutputConfig
import ru.ruznak.netscan.config.SinkKind
import ru.ruznak.netscan.scan.FormattedScan
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicReference

/**
 * Собирает приёмники по конфигурации и пересобирает их при изменении настроек
 * из веб-консоли. Всё, что не удалось включить, попадает в [warnings] и
 * показывается оператору вместо тихого отказа.
 */
class SinkManager(
    private val outputConfig: () -> OutputConfig,
    private val httpClient: () -> HttpClient,
    private val keyboardFactory: (() -> OutputConfig) -> OutputSink? = { KeyboardSink.createOrNull(it) },
    private val clipboardFactory: () -> ClipboardAccess? = { runCatching { AwtClipboard() }.getOrNull() },
) : AutoCloseable {

    private data class Built(val signature: OutputConfig, val sink: CompositeSink, val warnings: List<String>)

    private val current = AtomicReference<Built>()

    val warnings: List<String> get() = ensure().warnings

    val activeKinds: List<SinkKind> get() = ensure().sink.kinds

    val status: String get() = ensure().sink.status

    fun emit(scan: FormattedScan, context: ScanContext) = ensure().sink.emit(scan, context)

    private fun ensure(): Built {
        val config = outputConfig()
        val existing = current.get()
        if (existing != null && existing.signature == config) return existing
        val rebuilt = build(config)
        val previous = current.getAndSet(rebuilt)
        if (previous !== rebuilt) previous?.sink?.close()
        return rebuilt
    }

    private fun build(config: OutputConfig): Built {
        val warnings = mutableListOf<String>()
        val sinks = mutableListOf<OutputSink>()

        for (kind in config.sinks.distinct()) {
            val sink = when (kind) {
                SinkKind.KEYBOARD -> keyboardFactory(outputConfig)
                    ?: null.also {
                        warnings += "Эмуляция клавиатуры недоступна (нет графической сессии). " +
                            "Коды выводятся в консоль агента."
                    }

                SinkKind.CLIPBOARD -> clipboardFactory()?.let(::ClipboardSink)
                    ?: null.also { warnings += "Буфер обмена недоступен в этой среде." }

                SinkKind.CONSOLE -> ConsoleSink()

                SinkKind.FILE -> config.filePath?.takeIf { it.isNotBlank() }?.let { FileSink(Paths.get(it)) }
                    ?: null.also { warnings += "Приёмник «файл» включён, но путь не задан." }

                SinkKind.WEBHOOK -> config.webhookUrl?.takeIf { it.isNotBlank() }
                    ?.let { WebhookSink(it, httpClient()) }
                    ?: null.also { warnings += "Приёмник «вебхук» включён, но URL не задан." }
            }
            if (sink != null) sinks += sink
        }

        // Без единого рабочего приёмника скан просто исчез бы — оставляем консоль как страховку.
        if (sinks.isEmpty()) {
            sinks += ConsoleSink()
            if (config.sinks.isNotEmpty()) warnings += "Ни один приёмник не запустился, включена консоль."
        }

        return Built(config, CompositeSink(sinks), warnings)
    }

    override fun close() {
        current.getAndSet(null)?.sink?.close()
    }
}
