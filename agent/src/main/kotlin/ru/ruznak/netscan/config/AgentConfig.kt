package ru.ruznak.netscan.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Настройки агента. Сериализуются в ~/.netscan/config.json и правятся
 * либо из веб-консоли на ПК, либо ключами командной строки.
 */
@Serializable
data class AgentConfig(
    val network: NetworkConfig = NetworkConfig(),
    val security: SecurityConfig = SecurityConfig(),
    val scan: ScanConfig = ScanConfig(),
    val output: OutputConfig = OutputConfig(),
    val fleet: FleetConfig = FleetConfig(),
)

@Serializable
data class FleetConfig(
    /** Публичный HTTPS-адрес центрального сервера; пусто — fleet отключён. */
    val serverUrl: String = "",
    /** Понятное оператору имя рабочего места в центральной панели. */
    val displayName: String = "",
    /** Последние подтверждённые ревизии команд центрального сервера. */
    val appliedConfigRevision: Long = 0,
    val appliedRevokePhonesRevision: Long = 0,
)

@Serializable
data class NetworkConfig(
    /** Порт HTTPS: на нём работает и мобильный клиент, и API. */
    val httpsPort: Int = 8443,
    /** Порт HTTP: отдаёт только редирект на HTTPS, чтобы адрес без схемы открывался. */
    val httpPort: Int = 8080,
    /** Интерфейс для прослушивания. 0.0.0.0 — все сетевые интерфейсы. */
    val bindAddress: String = "0.0.0.0",
    /** Адрес, который попадёт в QR-код сопряжения. Пусто — определяется автоматически. */
    val advertisedHost: String? = null,
) {
    fun validated(): NetworkConfig {
        require(httpsPort in 1..65535) { "httpsPort вне диапазона 1..65535: $httpsPort" }
        require(httpPort == 0 || httpPort in 1..65535) { "httpPort вне диапазона 0..65535: $httpPort" }
        require(httpPort != httpsPort) { "httpPort и httpsPort должны различаться" }
        return this
    }
}

@Serializable
data class SecurityConfig(
    /** Сколько живёт неиспользованный код сопряжения, минут. 0 — бессрочно. */
    val pairingTokenTtlMinutes: Int = 0,
    /** Требовать подтверждение нового устройства на ПК. */
    val requireDeviceApproval: Boolean = false,
    /** Максимум попыток сопряжения с одного IP за минуту. */
    val pairingAttemptsPerMinute: Int = 10,
    /** Срок жизни сессии устройства без активности, часов. 0 — бессрочно. */
    val sessionIdleHours: Int = 0,
    /** Разрешить открывать веб-консоль ПК не только с localhost. */
    val allowRemoteConsole: Boolean = false,
)

@Serializable
data class ScanConfig(
    /** Окно подавления повторов одного и того же кода, мс. 0 — не подавлять. */
    val duplicateWindowMs: Long = 1500,
    /** Белый список символик; пусто — принимать все. */
    val allowedFormats: Set<String> = emptySet(),
    /** Минимальная и максимальная длина кода. */
    val minLength: Int = 1,
    val maxLength: Int = 4096,
    /** Регулярное выражение-фильтр; пусто — не фильтровать. */
    val filterRegex: String? = null,
    /** Глубина журнала сканов в памяти. */
    val historySize: Int = 200,
)

@Serializable
data class OutputConfig(
    /** Активные приёмники данных. */
    val sinks: List<SinkKind> = listOf(SinkKind.KEYBOARD),
    /** Что подставлять перед кодом. */
    val prefix: String = "",
    /** Управляющая клавиша после кода — как у настоящего USB-сканера. */
    val suffix: SuffixKey = SuffixKey.ENTER,
    /** Произвольный текст после кода (до нажатия suffix). */
    val suffixText: String = "",
    /** Задержка между символами при эмуляции клавиатуры, мс. */
    val keyDelayMs: Long = 4,
    /** Пауза перед началом набора, мс: окно приложения успевает получить фокус. */
    val typingLeadMs: Long = 0,
    /** Способ набора текста. */
    val typingMode: TypingMode = TypingMode.CLIPBOARD,
    /** Чем заменить разделитель GS (0x1D) в кодах GS1. */
    val gs1SeparatorReplacement: String = "",
    /** Убирать пробелы по краям кода. */
    val trim: Boolean = true,
    /** Файл для SinkKind.FILE. */
    val filePath: String? = null,
    /** URL для SinkKind.WEBHOOK. */
    val webhookUrl: String? = null,
)

@Serializable
enum class SinkKind {
    @SerialName("keyboard") KEYBOARD,
    @SerialName("clipboard") CLIPBOARD,
    @SerialName("console") CONSOLE,
    @SerialName("file") FILE,
    @SerialName("webhook") WEBHOOK,
}

@Serializable
enum class SuffixKey {
    @SerialName("none") NONE,
    @SerialName("enter") ENTER,
    @SerialName("tab") TAB,
    @SerialName("both") TAB_ENTER,
}

@Serializable
enum class TypingMode {
    /** Только эмуляция нажатий: точная имитация USB-сканера. */
    @SerialName("keys") KEYS,

    /** Только буфер обмена + Ctrl/Cmd+V: быстро и без проблем с раскладкой. */
    @SerialName("clipboard") CLIPBOARD,

    /** Нажатия для латиницы и цифр, буфер обмена — для остальных символов. */
    @SerialName("hybrid") HYBRID,
}
