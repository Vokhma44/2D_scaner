package ru.ruznak.netscan.fleet

import ru.ruznak.netscan.config.ConfigStore
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FleetClientTest {
    @Test
    fun `неподдерживаемая конфигурация отмечается отклонённой только один раз`() {
        val home = createTempDirectory("netscan-fleet-test")
        val store = ConfigStore.open(home.resolve("config.json"))
        val client = FleetClient(
            configStore = store,
            hostName = "TEST-PC",
            enrollmentToken = null,
            credentialsStore = FleetCredentialsStore(home.resolve("credentials.json")),
            revokePhones = {},
        )
        try {
            val response = HeartbeatResponse(
                serverTime = "now",
                configRevision = 7,
                config = RemoteAgentConfig(typingMode = "unsupported"),
            )
            client.applyCommands(response)
            val rejected = store.config.fleet
            assertEquals(7, rejected.rejectedConfigRevision)
            assertNotNull(rejected.configRejectionReason)
            assertEquals(0, rejected.appliedConfigRevision)

            client.applyCommands(response)
            assertEquals(rejected, store.config.fleet)

            client.applyCommands(
                HeartbeatResponse(
                    serverTime = "now",
                    configRevision = 8,
                    config = RemoteAgentConfig(typingMode = "clipboard", suffix = "tab"),
                ),
            )
            assertEquals(8, store.config.fleet.appliedConfigRevision)
            assertNull(store.config.fleet.configRejectionReason)
        } finally {
            client.close()
        }
    }
}
