package ru.ruznak.netscan.fleet

import kotlinx.serialization.Serializable

@Serializable
internal data class EnrollRequest(
    val enrollmentToken: String,
    val displayName: String,
    val hostName: String,
    val agentVersion: String,
    val osName: String,
    val osVersion: String,
)

@Serializable
internal data class EnrollResponse(
    val agentId: String,
    val agentToken: String,
    val heartbeatIntervalSeconds: Int = 30,
)

@Serializable
internal data class HeartbeatRequest(
    val agentVersion: String,
    val hostName: String,
)

@Serializable
internal data class HeartbeatResponse(
    val serverTime: String,
    val nextHeartbeatSeconds: Int = 30,
)

@Serializable
data class FleetCredentials(
    val serverUrl: String,
    val agentId: String,
    val agentToken: String,
)
