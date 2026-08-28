package ru.ruznak.netscan.config

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicReference

/**
 * Хранилище настроек: JSON-файл в рабочем каталоге агента.
 * Запись атомарная — переименованием временного файла, чтобы не потерять конфиг при сбое.
 */
class ConfigStore(private val file: Path, initial: AgentConfig) {

    private val current = AtomicReference(initial)

    val config: AgentConfig get() = current.get()

    fun update(mutator: (AgentConfig) -> AgentConfig): AgentConfig {
        val updated = current.updateAndGet(mutator)
        save(updated)
        return updated
    }

    fun replace(value: AgentConfig): AgentConfig = update { value }

    private fun save(value: AgentConfig) {
        Files.createDirectories(file.parent)
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.writeString(tmp, JSON.encodeToString(value))
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        val JSON = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        /**
         * Читает конфиг с диска. Битый или несовместимый файл не роняет агент:
         * он переименовывается в *.broken, а работа продолжается с настройками по умолчанию.
         */
        fun open(file: Path): ConfigStore {
            val loaded = when {
                !Files.exists(file) -> AgentConfig()
                else -> runCatching { JSON.decodeFromString<AgentConfig>(Files.readString(file)) }
                    .getOrElse {
                        runCatching {
                            Files.move(
                                file,
                                file.resolveSibling(file.fileName.toString() + ".broken"),
                                StandardCopyOption.REPLACE_EXISTING,
                            )
                        }
                        AgentConfig()
                    }
            }
            return ConfigStore(file, loaded)
        }
    }
}
