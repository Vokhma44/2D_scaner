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
)

@Serializable
data class HeartbeatResponse(
    val serverTime: String,
    val nextHeartbeatSeconds: Int = 30,
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
)

class ApiException(val status: Int, override val message: String) : RuntimeException(message)
