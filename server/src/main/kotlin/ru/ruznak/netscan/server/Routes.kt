package ru.ruznak.netscan.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant
import java.util.UUID

fun Application.fleetModule(config: ServerConfig, repository: FleetRepository) {
    val appLog = environment.log
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
    }
    install(StatusPages) {
        exception<ApiException> { call, error ->
            call.respond(HttpStatusCode.fromValue(error.status), ErrorResponse(error.message))
        }
        exception<Throwable> { call, error ->
            appLog.error("Необработанная ошибка API", error)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Внутренняя ошибка сервера"))
        }
    }

    routing {
        get("/admin") {
            val html = checkNotNull(javaClass.getResource("/admin/index.html")) {
                "Ресурс панели администратора не найден"
            }.readText(Charsets.UTF_8)
            call.respondText(html, ContentType.Text.Html)
        }

        get("/health") {
            if (repository.ping()) call.respond(mapOf("status" to "ok"))
            else call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("База данных недоступна"))
        }

        route("/api/v1/admin") {
            post("/enrollment-tokens") {
                requireAdmin(call.request.headers["Authorization"], config.adminToken)
                val request = call.receive<CreateEnrollmentTokenRequest>()
                val label = request.label.trim()
                if (label.isEmpty() || label.length > 200) throw ApiException(400, "Некорректная метка токена")
                if (request.ttlMinutes !in 1..1440) throw ApiException(400, "ttlMinutes должен быть от 1 до 1440")

                val rawToken = Tokens.issue()
                val expiresAt = Instant.now().plusSeconds(request.ttlMinutes * 60L)
                repository.createEnrollmentToken(Tokens.hash(rawToken), label, expiresAt)
                call.respond(HttpStatusCode.Created, EnrollmentTokenResponse(rawToken, expiresAt.toString()))
            }

            get("/agents") {
                requireAdmin(call.request.headers["Authorization"], config.adminToken)
                val now = Instant.now()
                val agents = repository.listAgents().map { agent ->
                    AgentView(
                        id = agent.id.toString(),
                        displayName = agent.displayName,
                        hostName = agent.hostName,
                        agentVersion = agent.agentVersion,
                        osName = agent.osName,
                        osVersion = agent.osVersion,
                        enrolledAt = agent.enrolledAt.toString(),
                        lastSeenAt = agent.lastSeenAt.toString(),
                        status = if (agent.revokedAt != null) {
                            "revoked"
                        } else if (Duration.between(agent.lastSeenAt, now).seconds <= config.onlineWindowSeconds) {
                            "online"
                        } else {
                            "offline"
                        },
                        revokedAt = agent.revokedAt?.toString(),
                    )
                }
                call.respond(agents)
            }

            post("/agents/{id}/revoke") {
                requireAdmin(call.request.headers["Authorization"], config.adminToken)
                val id = runCatching { UUID.fromString(call.parameters["id"]) }
                    .getOrElse { throw ApiException(400, "Некорректный идентификатор агента") }
                if (!repository.revokeAgent(id)) throw ApiException(404, "Агент не найден")
                call.respond(mapOf("status" to "revoked"))
            }
        }

        post("/api/v1/agents/enroll") {
            val request = call.receive<EnrollAgentRequest>().validated()
            val agentToken = Tokens.issue()
            val record = repository.enroll(
                tokenHash = Tokens.hash(request.enrollmentToken),
                request = request,
                agentTokenHash = Tokens.hash(agentToken),
            ) ?: throw ApiException(401, "Код подключения недействителен, истёк или уже использован")
            call.respond(
                HttpStatusCode.Created,
                EnrollAgentResponse(agentId = record.id.toString(), agentToken = agentToken),
            )
        }

        post("/api/v1/agents/heartbeat") {
            val agentId = authenticateAgent(call.request.headers["Authorization"], repository)
            val request = call.receive<HeartbeatRequest>().validated()
            if (!repository.heartbeat(agentId, request)) throw ApiException(401, "Агент не зарегистрирован")
            call.respond(HeartbeatResponse(serverTime = Instant.now().toString()))
        }
    }
}

private fun requireAdmin(authorization: String?, expectedToken: String) {
    val supplied = bearer(authorization) ?: throw ApiException(401, "Требуется токен администратора")
    if (!Tokens.constantTimeEquals(supplied, expectedToken)) throw ApiException(403, "Доступ запрещён")
}

private fun authenticateAgent(authorization: String?, repository: FleetRepository): java.util.UUID {
    val supplied = bearer(authorization) ?: throw ApiException(401, "Требуется токен агента")
    return repository.authenticateAgent(supplied) ?: throw ApiException(401, "Недействительный токен агента")
}

private fun bearer(value: String?): String? = value
    ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
    ?.substringAfter(' ')
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

private fun EnrollAgentRequest.validated(): EnrollAgentRequest = copy(
    enrollmentToken = enrollmentToken.trim(),
    displayName = displayName.trim(),
    hostName = hostName.trim(),
    agentVersion = agentVersion.trim(),
    osName = osName.trim(),
    osVersion = osVersion.trim(),
).also {
    if (it.enrollmentToken.length < 32) throw ApiException(400, "Некорректный код подключения")
    if (it.displayName.isEmpty() || it.displayName.length > 200) throw ApiException(400, "Некорректное имя агента")
    if (it.hostName.isEmpty() || it.hostName.length > 255) throw ApiException(400, "Некорректное имя ПК")
    if (it.agentVersion.isEmpty() || it.agentVersion.length > 50) throw ApiException(400, "Некорректная версия агента")
    if (it.osName.isEmpty() || it.osName.length > 100) throw ApiException(400, "Некорректное имя ОС")
    if (it.osVersion.length > 100) throw ApiException(400, "Некорректная версия ОС")
}

private fun HeartbeatRequest.validated(): HeartbeatRequest = copy(
    agentVersion = agentVersion.trim(),
    hostName = hostName.trim(),
).also {
    if (it.agentVersion.isEmpty() || it.agentVersion.length > 50) throw ApiException(400, "Некорректная версия агента")
    if (it.hostName.isEmpty() || it.hostName.length > 255) throw ApiException(400, "Некорректное имя ПК")
}
