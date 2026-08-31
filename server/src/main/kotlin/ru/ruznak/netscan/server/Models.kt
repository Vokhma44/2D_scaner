package ru.ruznak.netscan.server

import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

@Serializable
data class CreateEnrollmentTokenRequest(
    val label: String,
    val ttlMinutes: Int = 30,
)

@Serializable
data class EnrollmentTokenResponse(
    val token: String,
    val expiresAt: String,
)

@Serializable
data class EnrollAgentRequest(
    val enrollmentToken: String,
    val displayName: String,
    val hostName: String,
    val agentVersion: String,
    val osName: String,
    val osVersion: String,
)

@Serializable
data class EnrollAgentResponse(
    val agentId: String,
    val agentToken: String,
    val heartbeatIntervalSeconds: Int = 30,
)

@Serializable
data class HeartbeatRequest(
    val agentVersion: String,
    val hostName: String,
    val appliedConfigRevision: Long = 0,
    val rejectedConfigRevision: Long = 0,
    val configRejectionReason: String? = null,
    val appliedRevokePhonesRevision: Long = 0,
)

@Serializable
data class HeartbeatResponse(
    val serverTime: String,
    val nextHeartbeatSeconds: Int = 30,
    val configRevision: Long = 0,
    val config: RemoteAgentConfig? = null,
    val revokePhonesRevision: Long = 0,
)

@Serializable
data class RemoteAgentConfig(
    val typingMode: String = "clipboard",
    val suffix: String = "enter",
    val keyDelayMs: Long = 4,
    val typingLeadMs: Long = 0,
    val duplicateWindowMs: Long = 1500,
    val allowedFormats: List<String> = listOf("data_matrix"),
    val filterRegex: String? = null,
    val gs1SeparatorReplacement: String = "",
)

@Serializable
data class AgentView(
    val id: String,
    val displayName: String,
    val hostName: String,
    val agentVersion: String,
    val osName: String,
    val osVersion: String,
    val enrolledAt: String,
    val lastSeenAt: String,
    val status: String,
    val revokedAt: String? = null,
    val desiredConfig: RemoteAgentConfig = RemoteAgentConfig(),
    val configRevision: Long = 0,
    val appliedConfigRevision: Long = 0,
    val rejectedConfigRevision: Long = 0,
    val configRejectionReason: String? = null,
    val revokePhonesRevision: Long = 0,
    val appliedRevokePhonesRevision: Long = 0,
)

@Serializable
data class ErrorResponse(val error: String)

data class AgentRecord(
    val id: UUID,
    val displayName: String,
    val hostName: String,
    val agentVersion: String,
    val osName: String,
    val osVersion: String,
    val enrolledAt: Instant,
    val lastSeenAt: Instant,
    val revokedAt: Instant? = null,
    val desiredConfig: RemoteAgentConfig = RemoteAgentConfig(),
    val configRevision: Long = 0,
    val appliedConfigRevision: Long = 0,
    val rejectedConfigRevision: Long = 0,
    val configRejectionReason: String? = null,
    val revokePhonesRevision: Long = 0,
    val appliedRevokePhonesRevision: Long = 0,
)

data class AgentCommands(
    val desiredConfig: RemoteAgentConfig,
    val configRevision: Long,
    val revokePhonesRevision: Long,
)

class ApiException(val status: Int, override val message: String) : RuntimeException(message)
