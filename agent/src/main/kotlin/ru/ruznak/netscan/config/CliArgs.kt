package ru.ruznak.netscan.config

import java.nio.file.Path
import java.nio.file.Paths

/** Разобранные аргументы командной строки. */
data class CliArgs(
    val home: Path,
    val overrides: (AgentConfig) -> AgentConfig,
    val resetPairing: Boolean = false,
    val showHelp: Boolean = false,
) {
    companion object {
        const val USAGE = """
netscan — сетевой 2D-сканер: телефон работает вместо USB-сканера штрихкодов.

Использование: netscan [опции]

  --home <путь>            рабочий каталог агента (по умолчанию ~/.netscan)
  --port <число>           порт HTTPS (по умолчанию 8443)
  --http-port <число>      порт HTTP-редиректа, 0 — выключить (по умолчанию 8080)
  --bind <адрес>           интерфейс прослушивания (по умолчанию 0.0.0.0)
  --host <адрес>           адрес ПК для QR-кода, если автоопределение ошибается
  --sink <список>          приёмники через запятую: keyboard,clipboard,console,file,webhook
  --suffix <none|enter|tab|both>   клавиша после кода (по умолчанию enter)
  --prefix <текст>         текст перед кодом
  --typing <keys|clipboard|hybrid> способ ввода (по умолчанию clipboard)
  --key-delay <мс>         задержка между символами (по умолчанию 4)
  --dedup <мс>             окно подавления повторов (по умолчанию 1500)
  --file <путь>            файл для приёмника file
  --webhook <url>          адрес для приёмника webhook
  --approve-devices        требовать подтверждение нового устройства на ПК
  --reset-pairing          выпустить новый код сопряжения и отозвать сессии
  --help                   эта справка
"""

        fun parse(argv: Array<String>): CliArgs {
            var home = Paths.get(System.getProperty("user.home"), ".netscan")
            var reset = false
            var help = false
            val mutations = mutableListOf<(AgentConfig) -> AgentConfig>()

            fun value(index: Int, name: String): String =
                argv.getOrNull(index) ?: error("Опция $name требует значение")

            var i = 0
            while (i < argv.size) {
                val arg = argv[i]
                when (arg) {
                    "--help", "-h" -> help = true
                    "--reset-pairing" -> reset = true
                    "--approve-devices" ->
                        mutations += { it.copy(security = it.security.copy(requireDeviceApproval = true)) }
                    "--home" -> home = Paths.get(value(++i, arg))
                    "--port" -> {
                        val port = value(++i, arg).toIntOrNull() ?: error("--port ожидает число")
                        mutations += { it.copy(network = it.network.copy(httpsPort = port)) }
                    }
                    "--http-port" -> {
                        val port = value(++i, arg).toIntOrNull() ?: error("--http-port ожидает число")
                        mutations += { it.copy(network = it.network.copy(httpPort = port)) }
                    }
                    "--bind" -> {
                        val bind = value(++i, arg)
                        mutations += { it.copy(network = it.network.copy(bindAddress = bind)) }
                    }
                    "--host" -> {
                        val host = value(++i, arg)
                        mutations += { it.copy(network = it.network.copy(advertisedHost = host)) }
                    }
                    "--sink" -> {
                        val sinks = parseSinks(value(++i, arg))
                        mutations += { it.copy(output = it.output.copy(sinks = sinks)) }
                    }
                    "--suffix" -> {
                        val suffix = parseSuffix(value(++i, arg))
                        mutations += { it.copy(output = it.output.copy(suffix = suffix)) }
                    }
                    "--prefix" -> {
                        val prefix = value(++i, arg)
                        mutations += { it.copy(output = it.output.copy(prefix = prefix)) }
                    }
                    "--typing" -> {
                        val mode = parseTypingMode(value(++i, arg))
                        mutations += { it.copy(output = it.output.copy(typingMode = mode)) }
                    }
                    "--key-delay" -> {
                        val delay = value(++i, arg).toLongOrNull() ?: error("--key-delay ожидает число")
                        mutations += { it.copy(output = it.output.copy(keyDelayMs = delay)) }
                    }
                    "--dedup" -> {
                        val window = value(++i, arg).toLongOrNull() ?: error("--dedup ожидает число")
                        mutations += { it.copy(scan = it.scan.copy(duplicateWindowMs = window)) }
                    }
                    "--file" -> {
                        val path = value(++i, arg)
                        mutations += {
                            it.copy(output = it.output.copy(filePath = path, sinks = withSink(it.output.sinks, SinkKind.FILE)))
                        }
                    }
                    "--webhook" -> {
                        val url = value(++i, arg)
                        mutations += {
                            it.copy(output = it.output.copy(webhookUrl = url, sinks = withSink(it.output.sinks, SinkKind.WEBHOOK)))
                        }
                    }
                    else -> error("Неизвестная опция: $arg")
                }
                i++
            }

            return CliArgs(
                home = home,
                overrides = { base -> mutations.fold(base) { acc, mutate -> mutate(acc) } },
                resetPairing = reset,
                showHelp = help,
            )
        }

        private fun withSink(sinks: List<SinkKind>, kind: SinkKind): List<SinkKind> =
            if (kind in sinks) sinks else sinks + kind

        fun parseSinks(raw: String): List<SinkKind> = raw.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { name ->
                SinkKind.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                    ?: error("Неизвестный приёмник: $name")
            }
            .distinct()

        fun parseSuffix(raw: String): SuffixKey = when (raw.lowercase()) {
            "none", "off" -> SuffixKey.NONE
            "enter", "cr", "crlf" -> SuffixKey.ENTER
            "tab" -> SuffixKey.TAB
            "both", "tab+enter" -> SuffixKey.TAB_ENTER
            else -> error("Неизвестный суффикс: $raw")
        }

        fun parseTypingMode(raw: String): TypingMode = when (raw.lowercase()) {
            "keys", "keyboard" -> TypingMode.KEYS
            "clipboard", "paste" -> TypingMode.CLIPBOARD
            "hybrid", "auto" -> TypingMode.HYBRID
            else -> error("Неизвестный режим ввода: $raw")
        }
    }
}
