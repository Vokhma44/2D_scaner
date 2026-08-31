package ru.ruznak.netscan.fleet

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
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
import ru.ruznak.netscan.config.ConfigStore
import ru.ruznak.netscan.config.SuffixKey
import ru.ruznak.netscan.config.TypingMode
import ru.ruznak.netscan.update.UpdateStatusSnapshot
import kotlin.math.min

/** Исходящее соединение агента с центральным fleet-сервером. */
class FleetClient(
    private val configStore: ConfigStore,
    private val hostName: String,
    private val enrollmentToken: String?,
    private val credentialsStore: FleetCredentialsStore,
    private val revokePhones: () -> Unit,
    private val updateStatus: () -> UpdateStatusSnapshot = { UpdateStatusSnapshot() },
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
        if (configStore.config.fleet.serverUrl.isBlank()) return
        job = scope.launch { runLoop() }
    }

    private suspend fun runLoop() {
        val fleetConfig = configStore.config.fleet
        val storedCredentials = credentialsStore.load(fleetConfig.serverUrl)
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
        val fleetConfig = configStore.config.fleet
        val response = http.post("${fleetConfig.serverUrl}/api/v1/agents/enroll") {
            contentType(ContentType.Application.Json)
            setBody(
                EnrollRequest(
                    enrollmentToken = token,
                    displayName = fleetConfig.displayName.ifBlank { hostName },
                    hostName = hostName,
                    agentVersion = AGENT_VERSION,
                    osName = System.getProperty("os.name", "unknown"),
                    osVersion = System.getProperty("os.version", "unknown"),
                ),
            )
        }
        check(response.status.isSuccess()) { "регистрация отклонена: HTTP ${response.status.value}" }
        val body = response.body<EnrollResponse>()
        return FleetCredentials(fleetConfig.serverUrl, body.agentId, body.agentToken).also(credentialsStore::save)
    }

    private suspend fun heartbeat(credentials: FleetCredentials): Int {
        val fleet = configStore.config.fleet
        val update = updateStatus()
        val response = http.post("${credentials.serverUrl}/api/v1/agents/heartbeat") {
            bearerAuth(credentials.agentToken)
            contentType(ContentType.Application.Json)
            setBody(
                HeartbeatRequest(
                    agentVersion = AGENT_VERSION,
                    hostName = hostName,
                    appliedConfigRevision = fleet.appliedConfigRevision,
                    rejectedConfigRevision = fleet.rejectedConfigRevision,
                    configRejectionReason = fleet.configRejectionReason,
                    appliedRevokePhonesRevision = fleet.appliedRevokePhonesRevision,
                    updateStatus = update.status,
                    updateTargetVersion = update.targetVersion,
                    updateError = update.error,
                    updateStatusAt = update.updatedAt,
                ),
            )
        }
        check(response.status.isSuccess()) { "heartbeat отклонён: HTTP ${response.status.value}" }
        val body = response.body<HeartbeatResponse>()
        applyCommands(body)
        return body.nextHeartbeatSeconds
    }

    internal fun applyCommands(response: HeartbeatResponse) {
        val current = configStore.config
        val handledRevision = maxOf(current.fleet.appliedConfigRevision, current.fleet.rejectedConfigRevision)
        if (response.config != null && response.configRevision > handledRevision) {
            val remote = response.config
            val applied = runCatching {
                configStore.update { existing ->
                    existing.copy(
                        scan = existing.scan.copy(
                            duplicateWindowMs = remote.duplicateWindowMs,
                            allowedFormats = remote.allowedFormats.toSet(),
                            filterRegex = remote.filterRegex,
                        ),
                        output = existing.output.copy(
                            typingMode = enumValue<TypingMode>(remote.typingMode),
                            suffix = suffix(remote.suffix),
                            keyDelayMs = remote.keyDelayMs,
                            typingLeadMs = remote.typingLeadMs,
                            gs1SeparatorReplacement = remote.gs1SeparatorReplacement,
                        ),
                        fleet = existing.fleet.copy(
                            appliedConfigRevision = response.configRevision,
                            configRejectionReason = null,
                        ),
                    )
                }
            }
            applied.onSuccess {
                log.info("Применена удалённая конфигурация fleet, ревизия {}", response.configRevision)
            }.onFailure { error ->
                val reason = (error.message ?: error::class.simpleName ?: "неизвестная ошибка").take(500)
                configStore.update { existing ->
                    existing.copy(
                        fleet = existing.fleet.copy(
                            rejectedConfigRevision = response.configRevision,
                            configRejectionReason = reason,
                        ),
                    )
                }
                log.warn("Отклонена удалённая конфигурация fleet, ревизия {}: {}", response.configRevision, reason)
            }
        }

        val appliedRevoke = configStore.config.fleet.appliedRevokePhonesRevision
        if (response.revokePhonesRevision > appliedRevoke) {
            revokePhones()
            configStore.update { existing ->
                existing.copy(
                    fleet = existing.fleet.copy(appliedRevokePhonesRevision = response.revokePhonesRevision),
                )
            }
            log.info("По команде fleet отозваны сопряжённые телефоны, ревизия {}", response.revokePhonesRevision)
        }
    }

    private inline fun <reified T : Enum<T>> enumValue(raw: String): T =
        enumValues<T>().firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: error("неподдерживаемое значение fleet: $raw")

    private fun suffix(raw: String): SuffixKey = when (raw.lowercase()) {
        "none" -> SuffixKey.NONE
        "enter" -> SuffixKey.ENTER
        "tab" -> SuffixKey.TAB
        "both", "tab_enter" -> SuffixKey.TAB_ENTER
        else -> error("неподдерживаемый суффикс: $raw")
    }

    override fun close() {
        job?.cancel()
        scope.cancel()
        http.close()
    }
}
