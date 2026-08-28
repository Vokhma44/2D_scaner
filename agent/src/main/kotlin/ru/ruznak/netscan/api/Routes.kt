package ru.ruznak.netscan.api

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import ru.ruznak.netscan.AGENT_VERSION
import ru.ruznak.netscan.AgentState
import ru.ruznak.netscan.protocol.Ack
import ru.ruznak.netscan.protocol.ApiError
import ru.ruznak.netscan.protocol.ClientMessage
import ru.ruznak.netscan.protocol.DeviceStatus
import ru.ruznak.netscan.protocol.Hello
import ru.ruznak.netscan.protocol.PROTOCOL_VERSION
import ru.ruznak.netscan.protocol.PairRequest
import ru.ruznak.netscan.protocol.PairResponse
import ru.ruznak.netscan.protocol.Ping
import ru.ruznak.netscan.protocol.Pong
import ru.ruznak.netscan.protocol.Notice
import ru.ruznak.netscan.protocol.NoticeLevel
import ru.ruznak.netscan.protocol.ProtocolJson
import ru.ruznak.netscan.protocol.ScanBatch
import ru.ruznak.netscan.protocol.ScanMessage
import ru.ruznak.netscan.protocol.Welcome
import ru.ruznak.netscan.security.DeviceRecord
import kotlin.time.Duration.Companion.seconds

private val log = LoggerFactory.getLogger("ru.ruznak.netscan.api")

@Serializable
data class HealthResponse(
    val app: String = "netscan",
    val version: String = AGENT_VERSION,
    val protocolVersion: Int = PROTOCOL_VERSION,
    val host: String,
    val devicesOnline: Int,
)

@Serializable
data class SessionResponse(
    val deviceId: String,
    val deviceName: String,
    val status: DeviceStatus,
    val serverVersion: String = AGENT_VERSION,
    val settings: ru.ruznak.netscan.protocol.ClientSettings,
)

/** Основной модуль приложения: статика мобильного клиента, REST и WebSocket. */
fun Application.netscanModule(state: AgentState) {
    install(ContentNegotiation) { json(ProtocolJson) }
    install(WebSockets) {
        pingPeriod = 20.seconds
        maxFrameSize = 256 * 1024
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            log.error("Ошибка обработки запроса {}", call.request.local.uri, cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ApiError("internal_error", cause.message ?: "внутренняя ошибка"),
            )
        }
    }

    routing {
        get("/api/health") {
            call.respond(
                HealthResponse(
                    host = state.hostName(),
                    devicesOnline = state.connections.count(),
                ),
            )
        }

        post("/api/pair") { call.handlePairing(state) }

        get("/api/session") {
            val device = call.authenticate(state)
            if (device == null) {
                call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Сессия недействительна"))
                return@get
            }
            call.respond(
                SessionResponse(
                    deviceId = device.id,
                    deviceName = device.name,
                    status = device.status,
                    settings = state.clientSettings(),
                ),
            )
        }

        webSocket("/api/ws") { handleScannerSocket(state) }

        consoleRoutes(state)

        // Мобильный клиент собирается из каталога mobile/ и попадает в ресурсы агента.
        // Заглушка регистрируется только при его отсутствии: два обработчика на
        // один и тот же путь перехватывали бы запросы друг у друга.
        if (webClientBundled()) {
            staticResources("/", "web") { default("index.html") }
        } else {
            log.error("Ресурсы мобильного клиента не собраны — телефон не сможет открыть страницу сканера")
            get("/{...}") {
                call.respondText(
                    "Мобильный клиент не собран. Выполните: ./gradlew :agent:installDist",
                    status = HttpStatusCode.ServiceUnavailable,
                )
            }
        }
    }
}

/** Собран ли PWA-клиент в ресурсы агента. */
private fun webClientBundled(): Boolean =
    object {}.javaClass.classLoader.getResource("web/index.html") != null

private suspend fun ApplicationCall.handlePairing(state: AgentState) {
    val remote = request.origin.remoteHost
    if (!state.pairingLimiter.tryAcquire(remote)) {
        log.warn("Слишком много попыток сопряжения с адреса {}", remote)
        respond(HttpStatusCode.TooManyRequests, ApiError("rate_limited", "Слишком много попыток, подождите минуту"))
        return
    }

    val request = runCatching { receive<PairRequest>() }.getOrElse {
        respond(HttpStatusCode.BadRequest, ApiError("bad_request", "Некорректный запрос сопряжения"))
        return
    }

    if (!state.pairing.verify(request.token)) {
        log.warn("Неверный код сопряжения с адреса {}", remote)
        respond(HttpStatusCode.Forbidden, ApiError("invalid_token", "Код сопряжения неверен или устарел"))
        return
    }

    // Успешное сопряжение снимает счётчик попыток: следующий телефон подключится сразу.
    state.pairingLimiter.reset(remote)

    val requireApproval = state.config.security.requireDeviceApproval
    val (device, sessionToken) = state.devices.register(request.device, requireApproval)
    log.info("Сопряжено устройство «{}» ({}), статус {}", device.name, remote, device.status)

    respond(
        PairResponse(
            deviceId = device.id,
            sessionToken = sessionToken,
            serverVersion = AGENT_VERSION,
            status = device.status,
            settings = state.clientSettings(),
        ),
    )
}

/** Токен сессии принимается и в заголовке, и в параметре запроса: WebSocket в браузере не умеет заголовки. */
internal fun ApplicationCall.sessionToken(): String? {
    val header = request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
    return header?.takeIf { it.isNotBlank() } ?: request.queryParameters["s"]?.takeIf { it.isNotBlank() }
}

internal fun ApplicationCall.authenticate(state: AgentState): DeviceRecord? {
    val token = sessionToken() ?: return null
    return state.devices.authenticate(token)?.takeIf { it.status != DeviceStatus.REVOKED }
}

private suspend fun io.ktor.server.websocket.DefaultWebSocketServerSession.handleScannerSocket(state: AgentState) {
    val device = call.authenticate(state)
    if (device == null) {
        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Сессия недействительна"))
        return
    }
    if (device.status == DeviceStatus.PENDING_APPROVAL) {
        send(
            Frame.Text(
                ProtocolJson.encodeToString(
                    ru.ruznak.netscan.protocol.ServerMessage.serializer(),
                    Notice(NoticeLevel.WARN, "Устройство ждёт подтверждения на ПК"),
                ),
            ),
        )
        close(CloseReason(CloseReason.Codes.NORMAL, "Ожидает подтверждения"))
        return
    }

    val connection = state.connections.open(
        deviceId = device.id,
        deviceName = device.name,
        remoteHost = call.request.origin.remoteHost,
    ) { text -> send(Frame.Text(text)) }

    log.info("Телефон «{}» подключился с {}", device.name, connection.remoteHost)

    try {
        connection.send(
            Welcome(
                deviceId = device.id,
                serverVersion = AGENT_VERSION,
                settings = state.clientSettings(),
            ),
        )

        for (frame in incoming) {
            if (frame !is Frame.Text) continue
            connection.lastMessageAt = System.currentTimeMillis()

            val parsed = runCatching {
                ProtocolJson.decodeFromString(ClientMessage.serializer(), frame.readText())
            }
            val message = parsed.getOrNull()
            if (message == null) {
                log.debug("Не разобрано сообщение от телефона: {}", parsed.exceptionOrNull()?.message)
                connection.send(Notice(NoticeLevel.ERROR, "Сообщение не распознано"))
                continue
            }

            when (message) {
                is Hello -> if (message.protocolVersion != PROTOCOL_VERSION) {
                    connection.send(
                        Notice(
                            NoticeLevel.WARN,
                            "Версия приложения отличается от версии агента, обновите страницу",
                        ),
                    )
                }

                is Ping -> connection.send(Pong(message.ts, System.currentTimeMillis()))

                is ScanMessage -> processScan(state, connection, device, message)

                is ScanBatch -> message.scans.forEach { processScan(state, connection, device, it) }
            }
        }
    } finally {
        state.connections.close(connection)
        state.devices.touch(device.id)
        log.info("Телефон «{}» отключился", device.name)
    }
}

private suspend fun processScan(
    state: AgentState,
    connection: ru.ruznak.netscan.Connection,
    device: DeviceRecord,
    message: ScanMessage,
) {
    val outcome = state.pipeline.submit(message, device.id, device.name)
    if (outcome.status == ru.ruznak.netscan.protocol.AckStatus.ACCEPTED) {
        connection.scans += 1
        state.devices.touch(device.id, scans = 1)
    }
    // Телефон удаляет скан из офлайн-очереди только после ack: разрыв связи
    // посреди отправки не теряет код и не вводит его дважды.
    connection.send(Ack(message.id, outcome.status, outcome.detail))
}
