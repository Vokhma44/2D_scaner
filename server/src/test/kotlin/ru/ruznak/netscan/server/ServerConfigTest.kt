package ru.ruznak.netscan.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class ServerConfigTest {
    private val valid = mapOf(
        "NETSCAN_DB_URL" to "jdbc:postgresql://localhost:5432/netscan",
        "NETSCAN_DB_USER" to "netscan",
        "NETSCAN_DB_PASSWORD" to "secret",
        "NETSCAN_ADMIN_TOKEN" to "12345678901234567890123456789012",
    )

    @Test
    fun `конфигурация читает безопасные значения по умолчанию`() {
        val config = ServerConfig.fromEnvironment(valid)
        assertEquals("0.0.0.0", config.host)
        assertEquals(8081, config.port)
        assertEquals(90, config.onlineWindowSeconds)
    }

    @Test
    fun `короткий административный токен запрещён`() {
        assertFailsWith<IllegalArgumentException> {
            ServerConfig.fromEnvironment(valid + ("NETSCAN_ADMIN_TOKEN" to "short"))
        }
    }

    @Test
    fun `токены имеют достаточную энтропию и не повторяются`() {
        val first = Tokens.issue()
        val second = Tokens.issue()
        assertNotEquals(first, second)
        assertEquals(43, first.length)
        assertEquals(64, Tokens.hash(first).length)
    }
}
