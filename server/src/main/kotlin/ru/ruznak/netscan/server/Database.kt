package ru.ruznak.netscan.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

object DatabaseFactory {
    fun create(config: ServerConfig): HikariDataSource {
        val dataSource = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = config.databaseUrl
                username = config.databaseUser
                password = config.databasePassword
                driverClassName = "org.postgresql.Driver"
                maximumPoolSize = 10
                minimumIdle = 1
                connectionTimeout = 10_000
                validationTimeout = 3_000
                isAutoCommit = true
            },
        )
        Flyway.configure().dataSource(dataSource).load().migrate()
        return dataSource
    }
}

class FleetRepository(private val dataSource: DataSource) {

    fun ping(): Boolean = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT 1").use { statement ->
            statement.executeQuery().use { it.next() && it.getInt(1) == 1 }
        }
    }

    fun createEnrollmentToken(tokenHash: String, label: String, expiresAt: java.time.Instant) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO enrollment_tokens(token_hash, label, expires_at) VALUES (?, ?, ?)",
            ).use { statement ->
                statement.setString(1, tokenHash)
                statement.setString(2, label)
                statement.setObject(3, java.time.OffsetDateTime.ofInstant(expiresAt, java.time.ZoneOffset.UTC))
                statement.executeUpdate()
            }
        }
    }

    fun enroll(tokenHash: String, request: EnrollAgentRequest, agentTokenHash: String): AgentRecord? {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val consumed = connection.prepareStatement(
                    """
                    UPDATE enrollment_tokens
                    SET consumed_at = CURRENT_TIMESTAMP
                    WHERE token_hash = ? AND consumed_at IS NULL AND expires_at > CURRENT_TIMESTAMP
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, tokenHash)
                    statement.executeUpdate()
                }
                if (consumed != 1) {
                    connection.rollback()
                    return null
                }

                val id = UUID.randomUUID()
                val now = Instant.now()
                connection.prepareStatement(
                    """
                    INSERT INTO agents(
                        id, token_hash, display_name, host_name, agent_version,
                        os_name, os_version, enrolled_at, last_seen_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, id)
                    statement.setString(2, agentTokenHash)
                    statement.setString(3, request.displayName)
                    statement.setString(4, request.hostName)
                    statement.setString(5, request.agentVersion)
                    statement.setString(6, request.osName)
                    statement.setString(7, request.osVersion)
                    val timestamp = OffsetDateTime.ofInstant(now, ZoneOffset.UTC)
                    statement.setObject(8, timestamp)
                    statement.setObject(9, timestamp)
                    statement.executeUpdate()
                }
                connection.commit()
                return AgentRecord(
                    id = id,
                    displayName = request.displayName,
                    hostName = request.hostName,
                    agentVersion = request.agentVersion,
                    osName = request.osName,
                    osVersion = request.osVersion,
                    enrolledAt = now,
                    lastSeenAt = now,
                )
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = true
            }
        }
    }

    fun authenticateAgent(rawToken: String): UUID? = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT id FROM agents WHERE token_hash = ?").use { statement ->
            statement.setString(1, Tokens.hash(rawToken))
            statement.executeQuery().use { result -> if (result.next()) result.getObject("id", UUID::class.java) else null }
        }
    }

    fun heartbeat(agentId: UUID, request: HeartbeatRequest): Boolean = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            UPDATE agents SET last_seen_at = CURRENT_TIMESTAMP, agent_version = ?, host_name = ?
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, request.agentVersion)
            statement.setString(2, request.hostName)
            statement.setObject(3, agentId)
            statement.executeUpdate() == 1
        }
    }

    fun listAgents(): List<AgentRecord> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT id, display_name, host_name, agent_version, os_name, os_version,
                   enrolled_at, last_seen_at
            FROM agents ORDER BY last_seen_at DESC
            """.trimIndent(),
        ).use { statement ->
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(
                            AgentRecord(
                                id = result.getObject("id", UUID::class.java),
                                displayName = result.getString("display_name"),
                                hostName = result.getString("host_name"),
                                agentVersion = result.getString("agent_version"),
                                osName = result.getString("os_name"),
                                osVersion = result.getString("os_version"),
                                enrolledAt = result.getObject("enrolled_at", OffsetDateTime::class.java).toInstant(),
                                lastSeenAt = result.getObject("last_seen_at", OffsetDateTime::class.java).toInstant(),
                            ),
                        )
                    }
                }
            }
        }
    }
}
