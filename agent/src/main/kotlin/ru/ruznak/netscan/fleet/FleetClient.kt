package ru.ruznak.netscan.fleet

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.contentType
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import ru.ruznak.netscan.AGENT_VERSION
import ru.ruznak.netscan.config.FleetConfig
import kotlin.math.min

/** Исходящее соединение агента с центральным fleet-сервером. */
class FleetClient(
    private val config: FleetConfig,
    private val hostName: String,
    private val enrollmentToken: String?,
    private val credentialsStore: FleetCredentialsStore,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(FleetClient::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
        }
    }
    private var job: Job? = null

    fun start() {
        if (config.serverUrl.isBlank()) return
        job = scope.launch { runLoop() }
    }

    private suspend fun runLoop() {
        val storedCredentials = credentialsStore.load(config.serverUrl)
        val credentials = if (storedCredentials == null) {
            val token = enrollmentToken?.takeIf { it.isNotBlank() }
            if (token == null) {
                log.warn("Fleet-сервер настроен, но агент не зарегистрирован: нужен --enrollment-token")
                return
            }
            enrollWithRetry(token) ?: return
        } else storedCredentials

        var retrySeconds = 5L
        while (scope.isActive) {
            val next = runCatching { heartbeat(credentials) }
                .onSuccess {
                    retrySeconds = 5
                    log.debug("Heartbeat fleet-серверу отправлен")
                }
                .onFailure { error ->
                    log.warn("Fleet-сервер временно недоступен: {}", error.message)
                }
                .getOrNull()

            if (next != null) {
                delay(next.coerceIn(15, 300) * 1_000L)
            } else {
                delay(retrySeconds * 1_000L)
                retrySeconds = min(retrySeconds * 2, 60)
            }
        }
    }

    private suspend fun enrollWithRetry(token: String): FleetCredentials? {
        var retrySeconds = 5L
        while (scope.isActive) {
            val result = runCatching { enroll(token) }
            if (result.isSuccess) return result.getOrThrow()
            log.warn("Не удалось зарегистрировать агент на fleet-сервере: {}", result.exceptionOrNull()?.message)
            delay(retrySeconds * 1_000L)
            retrySeconds = min(retrySeconds * 2, 60)
        }
        return null
    }

    private suspend fun enroll(token: String): FleetCredentials {
        val response = http.post("${config.serverUrl}/api/v1/agents/enroll") {
            contentType(ContentType.Application.Json)
            setBody(
                EnrollRequest(
                    enrollmentToken = token,
                    displayName = config.displayName.ifBlank { hostName },
                    hostName = hostName,
                    agentVersion = AGENT_VERSION,
                    osName = System.getProperty("os.name", "unknown"),
                    osVersion = System.getProperty("os.version", "unknown"),
                ),
            )
        }
        check(response.status.isSuccess()) { "регистрация отклонена: HTTP ${response.status.value}" }
        val body = response.body<EnrollResponse>()
        return FleetCredentials(config.serverUrl, body.agentId, body.agentToken).also(credentialsStore::save)
    }

    private suspend fun heartbeat(credentials: FleetCredentials): Int {
        val response = http.post("${config.serverUrl}/api/v1/agents/heartbeat") {
            bearerAuth(credentials.agentToken)
            contentType(ContentType.Application.Json)
            setBody(HeartbeatRequest(agentVersion = AGENT_VERSION, hostName = hostName))
        }
        check(response.status.isSuccess()) { "heartbeat отклонён: HTTP ${response.status.value}" }
        return response.body<HeartbeatResponse>().nextHeartbeatSeconds
    }

    override fun close() {
        job?.cancel()
        scope.cancel()
        http.close()
    }
}
