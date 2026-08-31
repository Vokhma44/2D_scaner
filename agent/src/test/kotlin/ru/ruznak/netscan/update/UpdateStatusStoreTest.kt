package ru.ruznak.netscan.update

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.ruznak.netscan.AGENT_VERSION
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdateStatusStoreTest {
    @Test
    fun `status is persisted and restored`() {
        val directory = Files.createTempDirectory("netscan-update-status")
        val file = directory.resolve("update-status.json")
        try {
            UpdateStatusStore(file).update("downloading", "9.9.9")
            val restored = UpdateStatusStore(file).snapshot()
            assertEquals("downloading", restored.status)
            assertEquals("9.9.9", restored.targetVersion)
            assertNull(restored.error)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `successful restart marks installation as updated`() {
        val directory = Files.createTempDirectory("netscan-update-status")
        val file = directory.resolve("update-status.json")
        try {
            Files.writeString(file, Json.encodeToString(UpdateStatusSnapshot("installing", AGENT_VERSION)))
            val restored = UpdateStatusStore(file).snapshot()
            assertEquals("updated", restored.status)
            assertEquals(AGENT_VERSION, restored.targetVersion)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
