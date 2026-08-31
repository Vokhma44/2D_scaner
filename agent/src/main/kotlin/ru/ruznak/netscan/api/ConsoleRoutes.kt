package ru.ruznak.netscan.api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import ru.ruznak.netscan.AGENT_VERSION
import ru.ruznak.netscan.AgentState
import ru.ruznak.netscan.ConnectionView
import ru.ruznak.netscan.config.AgentConfig
import ru.ruznak.netscan.console.QrRenderer
import ru.ruznak.netscan.net.LanAddresses
import ru.ruznak.netscan.protocol.ApiError
import ru.ruznak.netscan.protocol.Notice
import ru.ruznak.netscan.protocol.NoticeLevel
import ru.ruznak.netscan.protocol.ScanMessage
import ru.ruznak.netscan.protocol.ScanSource
import ru.ruznak.netscan.scan.ScanRecord
import ru.ruznak.netscan.security.DeviceRecord
import java.net.URI
import java.util.UUID

@Serializable
data class ConsoleDevice(
    val record: DeviceRecord,
    val online: Boolean,
)

@Serializable
data class ConsoleState(
    val version: String,
    val hostName: String,
    val pairingUrl: String,
    val pairingCode: String,
    val addresses: List<String>,
    val tlsFingerprint: String?,
    val config: AgentConfig,
    val devices: List<ConsoleDevice>,
    val connections: List<ConnectionView>,
    val history: List<ScanRecord>,
    val stats: ru.ruznak.netscan.scan.PipelineStats,
    val activeSinks: List<String>,
    val warnings: List<String>,
)

@Serializable
data class TestScanRequest(val code: String)

@Serializable
data class ConfigUpdateResponse(val config: AgentConfig, val restartRequired: Boolean)

@Serializable
data class OkResponse(val ok: Boolean = true)

/**
 * Консоль оператора на самом ПК: QR для подключения телефона, список устройств,
 * журнал сканов и настройки. По умолчанию открывается только с localhost —
 * иначе любой в той же сети менял бы настройки ввода на чужой машине.
 */
fun Route.consoleRoutes(state: AgentState) {
    route("/api/console") {
        get("/state") {
            if (!call.consoleAllowed(state)) return@get
            call.respond(state.snapshot())
        }

        get("/qr.svg") {
            if (!call.consoleAllowed(state)) return@get
            call.respondText(QrRenderer.toSvg(state.pairingUrl()), ContentType.Image.SVG)
        }

        post("/config") {
            if (!call.consoleMutationAllowed(state)) return@post
            val incoming = runCatching { call.receive<AgentConfig>() }.getOrElse {
                call.respond(HttpStatusCode.BadRequest, ApiError("bad_request", "Некорректные настройки"))
                return@post
            }
            val validation = runCatching { incoming.network.validated() }
            if (validation.isFailure) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("bad_request", validation.exceptionOrNull()?.message ?: "Некорректные настройки"),
                )
                return@post
            }

            val previous = state.config
            val saved = state.configStore.replace(incoming)
            // Порты и адрес прослушивания меняются только при перезапуске: подменить
            // сокет у работающего сервера нельзя, и лучше честно сказать об этом.
            val restartRequired = previous.network != saved.network
            state.broadcastSettings()
            call.respond(ConfigUpdateResponse(saved, restartRequired))
        }

        post("/pairing/rotate") {
            if (!call.consoleMutationAllowed(state)) return@post
            state.pairing.rotate()
            call.respond(state.snapshot())
        }

        post("/devices/{id}/{action}") {
            if (!call.consoleMutationAllowed(state)) return@post
            val id = call.parameters["id"].orEmpty()
            val action = call.parameters["action"].orEmpty()

            val updated = when (action) {
                "approve" -> state.devices.approve(id)?.also {
                    state.connections.notifyDevice(id, Notice(NoticeLevel.INFO, "Устройство подтверждено"))
                }

                "revoke" -> state.devices.revoke(id)?.also {
                    state.connections.notifyDevice(id, Notice(NoticeLevel.ERROR, "Доступ устройства отозван"))
                }

                "forget" -> if (state.devices.forget(id)) {
                    state.connections.notifyDevice(id, Notice(NoticeLevel.ERROR, "Устройство удалено с ПК"))
                    null
                } else {
                    call.respond(HttpStatusCode.NotFound, ApiError("not_found", "Устройство не найдено"))
                    return@post
                }

                else -> {
                    call.respond(HttpStatusCode.BadRequest, ApiError("bad_request", "Неизвестное действие: $action"))
                    return@post
                }
            }

            if (action != "forget" && updated == null) {
                call.respond(HttpStatusCode.NotFound, ApiError("not_found", "Устройство не найдено"))
                return@post
            }
            call.respond(state.snapshot())
        }

        post("/test") {
            if (!call.consoleMutationAllowed(state)) return@post
            val request = runCatching { call.receive<TestScanRequest>() }.getOrElse {
                call.respond(HttpStatusCode.BadRequest, ApiError("bad_request", "Не указан код"))
                return@post
            }
            val outcome = state.pipeline.submit(
                ScanMessage(
                    id = UUID.randomUUID().toString(),
                    code = request.code,
                    format = "test",
                    scannedAt = System.currentTimeMillis(),
                    source = ScanSource.MANUAL,
                ),
                deviceId = "console",
                deviceName = "Консоль ПК",
            )
            call.respond(mapOf("status" to outcome.status.name.lowercase(), "detail" to outcome.detail))
        }

        post("/history/clear") {
            if (!call.consoleMutationAllowed(state)) return@post
            state.history.clear()
            call.respond(OkResponse())
        }
    }

    get("/console") {
        if (!call.consoleAllowed(state)) return@get
        val page = ConsoleState::class.java.classLoader.getResourceAsStream("console/index.html")
            ?.bufferedReader()
            ?.readText()
        if (page == null) {
            call.respondText("Консоль не найдена в ресурсах агента", status = HttpStatusCode.InternalServerError)
        } else {
            call.respondText(page, ContentType.Text.Html)
        }
    }
}

private fun AgentState.snapshot(): ConsoleState = ConsoleState(
    version = AGENT_VERSION,
    hostName = hostName(),
    pairingUrl = pairingUrl(),
    pairingCode = pairing.code,
    addresses = LanAddresses.discover().map { "${it.host} (${it.interfaceName})" },
    tlsFingerprint = tls?.fingerprintSha256,
    config = config,
    devices = devices.devices().map { ConsoleDevice(it, connections.isOnline(it.id)) },
    connections = connections.views(),
    history = history.recent(100),
    stats = pipeline.stats(),
    activeSinks = sinks.activeKinds.map { it.name.lowercase() },
    warnings = sinks.warnings,
)

/**
 * Консоль всегда доступна только с самого ПК. Старое поле allowRemoteConsole
 * остаётся в JSON лишь для обратной совместимости, но больше не ослабляет защиту.
 */
private suspend fun ApplicationCall.consoleAllowed(state: AgentState): Boolean {
    val remote = request.origin.remoteHost
    val local = remote == "localhost" || remote == "127.0.0.1" || remote == "::1" || remote == "0:0:0:0:0:0:0:1"
    if (!local) {
        respond(
            HttpStatusCode.Forbidden,
            ApiError("console_local_only", "Консоль открывается только на самом ПК"),
        )
        return false
    }
    return true
}

/**
 * Одной проверки IP недостаточно: вредоносная страница в браузере пользователя
 * могла отправить POST на localhost. Для изменяющих запросов принимаем только
 * Origin локальной HTTPS-консоли этого агента.
 */
private suspend fun ApplicationCall.consoleMutationAllowed(state: AgentState): Boolean {
    if (!consoleAllowed(state)) return false
    val origin = request.headers["Origin"]
    val uri = runCatching { origin?.let(::URI) }.getOrNull()
    val localHost = uri?.host in setOf("localhost", "127.0.0.1", "::1", "[::1]", "0:0:0:0:0:0:0:1")
    val expectedPort = state.config.network.httpsPort
    val actualPort = uri?.port?.takeIf { it >= 0 } ?: if (uri?.scheme == "https") 443 else -1
    if (uri?.scheme != "https" || !localHost || actualPort != expectedPort) {
        respond(HttpStatusCode.Forbidden, ApiError("csrf_rejected", "Запрос отклонён защитой локальной консоли"))
        return false
    }
    return true
}
