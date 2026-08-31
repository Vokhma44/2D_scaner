package ru.ruznak.netscan.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.ruznak.netscan.AGENT_VERSION
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

@Serializable
data class UpdateStatusSnapshot(
    val status: String = "idle",
    val targetVersion: String? = null,
    val error: String? = null,
    val updatedAt: String = Instant.now().toString(),
)

class UpdateStatusStore(private val file: Path) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    @Volatile private var current = load()

    init {
        if (current.status == "installing" && current.targetVersion == AGENT_VERSION) {
            update("updated", AGENT_VERSION)
        }
    }

    fun snapshot(): UpdateStatusSnapshot = current

    @Synchronized
    fun update(status: String, targetVersion: String? = current.targetVersion, error: String? = null) {
        val next = UpdateStatusSnapshot(status, targetVersion, error?.take(500), Instant.now().toString())
        Files.createDirectories(file.parent)
        val temporary = file.resolveSibling("${file.fileName}.tmp")
        Files.writeString(temporary, json.encodeToString(next))
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
        }
        current = next
    }

    private fun load(): UpdateStatusSnapshot = runCatching {
        json.decodeFromString<UpdateStatusSnapshot>(Files.readString(file).trimStart('\uFEFF'))
    }.getOrDefault(UpdateStatusSnapshot())
}
