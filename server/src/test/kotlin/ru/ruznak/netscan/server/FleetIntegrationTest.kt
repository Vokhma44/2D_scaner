package ru.ruznak.netscan.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FleetIntegrationTest {
    private val enrollment = EnrollAgentRequest(
        enrollmentToken = "unused",
        displayName = "Тестовое рабочее место",
        hostName = "TEST-PC",
        agentVersion = "1.4.0",
        osName = "Windows",
        osVersion = "11",
    )

    @Test
    fun `код подключения одноразовый и регистрация атомарна`() = withRepository { repository, _ ->
        val firstCode = "first-enrollment-code"
        repository.createEnrollmentToken(Tokens.hash(firstCode), "first", Instant.now().plusSeconds(600))
        assertNotNull(repository.enroll(Tokens.hash(firstCode), enrollment, Tokens.hash("agent-token-one")))
        assertNull(repository.enroll(Tokens.hash(firstCode), enrollment, Tokens.hash("agent-token-two")))

        val rollbackCode = "rollback-enrollment-code"
        repository.createEnrollmentToken(Tokens.hash(rollbackCode), "rollback", Instant.now().plusSeconds(600))
        assertFails {
            repository.enroll(Tokens.hash(rollbackCode), enrollment, Tokens.hash("agent-token-one"))
        }
        // INSERT агента упал на уникальном token_hash. Токен подключения обязан
        // остаться неиспользованным благодаря rollback всей транзакции.
        assertNotNull(repository.enroll(Tokens.hash(rollbackCode), enrollment, Tokens.hash("agent-token-three")))
    }

    @Test
    fun `ревизии конфигурации и команд только возрастают`() = withRepository { repository, _ ->
        val code = "revision-enrollment-code"
        repository.createEnrollmentToken(Tokens.hash(code), "revision", Instant.now().plusSeconds(600))
        val agent = assertNotNull(repository.enroll(Tokens.hash(code), enrollment, Tokens.hash("revision-agent-token")))

        assertTrue(repository.updateAgentConfig(agent.id, RemoteAgentConfig(keyDelayMs = 11)))
        assertTrue(repository.updateAgentConfig(agent.id, RemoteAgentConfig(keyDelayMs = 12)))
        assertTrue(repository.requestPhoneRevocation(agent.id))
        assertTrue(repository.requestPhoneRevocation(agent.id))

        val commands = assertNotNull(
            repository.heartbeat(
                agent.id,
                HeartbeatRequest(
                    agentVersion = "1.4.0",
                    hostName = "TEST-PC",
                    appliedConfigRevision = 1,
                    rejectedConfigRevision = 2,
                    configRejectionReason = "unsupported value",
                    appliedRevokePhonesRevision = 1,
                ),
            ),
        )
        assertEquals(2, commands.configRevision)
        assertEquals(2, commands.revokePhonesRevision)

        val stored = repository.listAgents().single { it.id == agent.id }
        assertEquals(1, stored.appliedConfigRevision)
        assertEquals(2, stored.rejectedConfigRevision)
        assertEquals("unsupported value", stored.configRejectionReason)
        assertEquals(1, stored.appliedRevokePhonesRevision)
    }

    @Test
    fun `административный API требует правильный токен`() = withRepository { repository, config ->
        testApplication {
            application { fleetModule(config, repository) }

            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/admin/agents").status)
            assertEquals(
                HttpStatusCode.Forbidden,
                client.get("/api/v1/admin/agents") { header("Authorization", "Bearer ${"x".repeat(32)}") }.status,
            )
            assertEquals(
                HttpStatusCode.OK,
                client.get("/api/v1/admin/agents") { header("Authorization", "Bearer ${config.adminToken}") }.status,
            )
        }
    }

    private fun withRepository(block: (FleetRepository, ServerConfig) -> Unit) {
        val databaseUrl = System.getenv("NETSCAN_TEST_DB_URL") ?: return
        val config = ServerConfig(
            host = "127.0.0.1",
            port = 8081,
            databaseUrl = databaseUrl,
            databaseUser = System.getenv("NETSCAN_TEST_DB_USER") ?: "netscan",
            databasePassword = System.getenv("NETSCAN_TEST_DB_PASSWORD") ?: "netscan_test",
            adminToken = "test-admin-token-${"x".repeat(32)}",
            onlineWindowSeconds = 90,
        )
        val dataSource = DatabaseFactory.create(config)
        try {
            dataSource.connection.use { connection ->
                connection.createStatement().use { it.execute("TRUNCATE agents, enrollment_tokens CASCADE") }
            }
            block(FleetRepository(dataSource), config)
        } finally {
            dataSource.close()
        }
    }
}
