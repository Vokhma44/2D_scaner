package ru.ruznak.netscan.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Версия протокола обмена между телефоном и агентом. */
const val PROTOCOL_VERSION: Int = 1

/** Единый Json для WebSocket и REST: дискриминатор типа сообщения — поле "type". */
val ProtocolJson: Json = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/** Описание телефона, которое приходит при сопряжении и в hello. */
@Serializable
data class DeviceInfo(
    val name: String = "Телефон",
    val platform: String = "unknown",
    val userAgent: String? = null,
    val appVersion: String? = null,
)

// ---------------------------------------------------------------- REST

@Serializable
data class PairRequest(
    val token: String,
    val device: DeviceInfo = DeviceInfo(),
)

@Serializable
data class PairResponse(
    val deviceId: String,
    val sessionToken: String,
    val serverVersion: String,
    val protocolVersion: Int = PROTOCOL_VERSION,
    val status: DeviceStatus,
    val settings: ClientSettings,
)

@Serializable
enum class DeviceStatus {
    @SerialName("active") ACTIVE,
    @SerialName("pending") PENDING_APPROVAL,
    @SerialName("revoked") REVOKED,
}

/** Настройки, которые агент навязывает клиенту: чтобы телефон вёл себя как настроенный сканер. */
@Serializable
data class ClientSettings(
    val duplicateWindowMs: Long,
    val allowedFormats: Set<String>,
    val hostName: String,
)

@Serializable
data class ApiError(val error: String, val message: String)

// ---------------------------------------------------------------- WebSocket: телефон → агент

@Serializable
sealed interface ClientMessage

@Serializable
@SerialName("hello")
data class Hello(
    val device: DeviceInfo,
    val protocolVersion: Int = PROTOCOL_VERSION,
) : ClientMessage

/**
 * Один отсканированный код. [id] генерируется телефоном и обеспечивает идемпотентность:
 * повторная отправка из офлайн-очереди не приведёт к двойному вводу на ПК.
 */
@Serializable
@SerialName("scan")
data class ScanMessage(
    val id: String,
    val code: String,
    val format: String = "unknown",
    val scannedAt: Long = 0,
    val source: ScanSource = ScanSource.CAMERA,
) : ClientMessage

/** Пачка сканов: режим накопления и выгрузка офлайн-очереди. */
@Serializable
@SerialName("scans")
data class ScanBatch(val scans: List<ScanMessage>) : ClientMessage

@Serializable
@SerialName("ping")
data class Ping(val ts: Long) : ClientMessage

@Serializable
enum class ScanSource {
    @SerialName("camera") CAMERA,
    @SerialName("manual") MANUAL,
    @SerialName("image") IMAGE,
}

// ---------------------------------------------------------------- WebSocket: агент → телефон

@Serializable
sealed interface ServerMessage

@Serializable
@SerialName("welcome")
data class Welcome(
    val deviceId: String,
    val serverVersion: String,
    val protocolVersion: Int = PROTOCOL_VERSION,
    val settings: ClientSettings,
) : ServerMessage

@Serializable
@SerialName("ack")
data class Ack(
    val id: String,
    val status: AckStatus,
    val detail: String? = null,
) : ServerMessage

@Serializable
enum class AckStatus {
    /** Код передан на ПК. */
    @SerialName("accepted") ACCEPTED,

    /** Повтор: код уже принимался в окне подавления либо этот id уже обработан. */
    @SerialName("duplicate") DUPLICATE,

    /** Код отброшен фильтрами (символика, длина, регулярное выражение). */
    @SerialName("filtered") FILTERED,

    /** Ошибка вывода на ПК; телефон покажет её пользователю. */
    @SerialName("failed") FAILED,
}

@Serializable
@SerialName("pong")
data class Pong(val ts: Long, val serverTs: Long) : ServerMessage

@Serializable
@SerialName("notice")
data class Notice(val level: NoticeLevel, val text: String) : ServerMessage

@Serializable
enum class NoticeLevel {
    @SerialName("info") INFO,
    @SerialName("warn") WARN,
    @SerialName("error") ERROR,
}

/** Агент прислал новые настройки — например, оператор поменял их в консоли на ПК. */
@Serializable
@SerialName("settings")
data class SettingsPush(val settings: ClientSettings) : ServerMessage
