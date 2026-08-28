package ru.ruznak.netscan.fleet

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

/** Локальное хранилище секрета агента; файл не входит в конфигурацию и логи. */
class FleetCredentialsStore(private val file: Path) {
    fun load(serverUrl: String): FleetCredentials? {
        if (!Files.exists(file)) return null
        return runCatching {
            JSON.decodeFromString<FleetCredentials>(Files.readString(file))
        }.getOrNull()?.takeIf { it.serverUrl == serverUrl }
    }

    fun save(credentials: FleetCredentials) {
        Files.createDirectories(file.parent)
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.writeString(tmp, JSON.encodeToString(credentials))
        restrictToOwner(tmp)
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        restrictToOwner(file)
    }

    private fun restrictToOwner(path: Path) {
        runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
