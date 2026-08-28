package ru.ruznak.netscan.output

import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.ruznak.netscan.config.SinkKind
import ru.ruznak.netscan.scan.FormattedScan
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

/** Кладёт код в буфер обмена: оператор вставляет его сам, фокус окна не важен. */
class ClipboardSink(private val clipboard: ClipboardAccess) : OutputSink {
    override val kind: SinkKind = SinkKind.CLIPBOARD
    override fun emit(scan: FormattedScan, context: ScanContext) = clipboard.write(scan.text)
}

/** Печатает код в стандартный вывод: отладка и работа агента без графической среды. */
class ConsoleSink(private val out: Appendable = System.out) : OutputSink {
    override val kind: SinkKind = SinkKind.CONSOLE
    override fun emit(scan: FormattedScan, context: ScanContext) {
        out.append("[скан] ${context.deviceName}: ${scan.asLine()}").append(System.lineSeparator())
        (out as? java.io.Flushable)?.flush()
    }
}

/** Дописывает коды в текстовый файл — простая интеграция с чем угодно, что умеет читать файл. */
class FileSink(private val path: Path) : OutputSink {

    private val writer: BufferedWriter by lazy {
        path.parent?.let { Files.createDirectories(it) }
        Files.newBufferedWriter(
            path,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND,
        )
    }

    override val kind: SinkKind = SinkKind.FILE

    override val status: String get() = path.toString()

    @Synchronized
    override fun emit(scan: FormattedScan, context: ScanContext) {
        writer.append(scan.asLine())
        writer.newLine()
        writer.flush()
    }

    @Synchronized
    override fun close() {
        runCatching { writer.close() }
    }
}

@Serializable
private data class WebhookPayload(
    val code: String,
    val raw: String,
    val format: String,
    val deviceId: String,
    val deviceName: String,
    val scanId: String,
    val receivedAt: String,
)

/** Отправляет скан во внешнюю систему HTTP-запросом: WMS, 1С, самописный сервис. */
class WebhookSink(
    private val url: String,
    private val client: HttpClient,
    private val json: Json = Json { encodeDefaults = true },
) : OutputSink {

    override val kind: SinkKind = SinkKind.WEBHOOK

    override val status: String get() = url

    override fun emit(scan: FormattedScan, context: ScanContext) {
        val payload = WebhookPayload(
            code = scan.text,
            raw = context.rawCode,
            format = context.format,
            deviceId = context.deviceId,
            deviceName = context.deviceName,
            scanId = context.scanId,
            receivedAt = Instant.ofEpochMilli(context.receivedAt).toString(),
        )
        val response: HttpResponse = runBlocking {
            client.post(url) {
                contentType(ContentType.Application.Json)
                headers { append(HttpHeaders.UserAgent, "netscan-agent") }
                setBody(json.encodeToString(payload))
            }
        }
        if (!response.status.isSuccess()) error("вебхук ответил ${response.status.value}")
    }
}
