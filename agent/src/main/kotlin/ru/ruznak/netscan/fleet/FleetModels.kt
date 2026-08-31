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
    val appliedConfigRevision: Long = 0,
    val rejectedConfigRevision: Long = 0,
    val configRejectionReason: String? = null,
    val appliedRevokePhonesRevision: Long = 0,
    val updateStatus: String = "idle",
    val updateTargetVersion: String? = null,
    val updateError: String? = null,
    val updateStatusAt: String? = null,
)

@Serializable
internal data class HeartbeatResponse(
    val serverTime: String,
    val nextHeartbeatSeconds: Int = 30,
    val configRevision: Long = 0,
    val config: RemoteAgentConfig? = null,
    val revokePhonesRevision: Long = 0,
)

@Serializable
internal data class RemoteAgentConfig(
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
data class FleetCredentials(
    val serverUrl: String,
    val agentId: String,
    val agentToken: String,
)
