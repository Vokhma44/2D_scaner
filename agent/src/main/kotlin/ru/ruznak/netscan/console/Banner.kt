package ru.ruznak.netscan.console

import ru.ruznak.netscan.AGENT_VERSION
import ru.ruznak.netscan.AgentState
import ru.ruznak.netscan.net.LanAddresses

/**
 * Приветствие в консоли ПК. Оператор видит QR-код и наводит на него камеру
 * телефона — других шагов настройки нет.
 */
object Banner {

    fun render(state: AgentState): String = buildString {
        val config = state.config
        val url = state.pairingUrl()

        appendLine()
        appendLine("  netscan $AGENT_VERSION — телефон вместо USB-сканера 2D")
        appendLine("  " + "-".repeat(52))
        appendLine()
        append(QrRenderer.toTerminal(url).prependIndent("  "))
        appendLine()
        appendLine("  Наведите камеру телефона на QR-код выше.")
        appendLine()
        appendLine("  Адрес:            $url")
        appendLine("  Код сопряжения:   ${state.pairing.code}")
        appendLine("  Консоль на ПК:    https://localhost:${config.network.httpsPort}/console")
        if (config.network.httpPort > 0) {
            appendLine("  HTTP-редирект:    порт ${config.network.httpPort}")
        }

        val addresses = LanAddresses.discover()
        if (addresses.isEmpty()) {
            appendLine("  Сеть:             локальных адресов не найдено, проверьте подключение к Wi-Fi")
        } else {
            appendLine("  Адреса ПК:        " + addresses.joinToString(", ") { "${it.host} (${it.interfaceName})" })
        }

        state.tls?.let { appendLine("  Отпечаток TLS:    ${it.fingerprintSha256}") }
        appendLine("  Вывод:            ${state.sinks.activeKinds.joinToString(", ") { it.name.lowercase() }}")
        appendLine(
            "  Суффикс:          ${config.output.suffix.name.lowercase()}, " +
                "подавление повторов ${config.scan.duplicateWindowMs} мс",
        )

        if (state.sinks.warnings.isNotEmpty()) {
            appendLine()
            state.sinks.warnings.forEach { appendLine("  ! $it") }
        }

        appendLine()
        appendLine("  Браузер телефона предупредит о самоподписанном сертификате — это ожидаемо,")
        appendLine("  подтвердите переход. Подробности: docs/SECURITY.md")
        appendLine()
    }
}
