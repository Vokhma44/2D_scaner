package ru.ruznak.netscan.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource
import kotlinx.serialization.json.Json

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

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

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
        connection.prepareStatement("SELECT id FROM agents WHERE token_hash = ? AND revoked_at IS NULL").use { statement ->
            statement.setString(1, Tokens.hash(rawToken))
            statement.executeQuery().use { result -> if (result.next()) result.getObject("id", UUID::class.java) else null }
        }
    }

    fun heartbeat(agentId: UUID, request: HeartbeatRequest): AgentCommands? = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            UPDATE agents SET
                last_seen_at = CURRENT_TIMESTAMP,
                agent_version = ?,
                host_name = ?,
                applied_config_revision = GREATEST(applied_config_revision, ?),
                config_rejection_reason = CASE
                    WHEN ? >= rejected_config_revision THEN ?
                    ELSE config_rejection_reason
                END,
                rejected_config_revision = GREATEST(rejected_config_revision, ?),
                applied_revoke_phones_revision = GREATEST(applied_revoke_phones_revision, ?)
            WHERE id = ? AND revoked_at IS NULL
            RETURNING desired_config::text, config_revision, revoke_phones_revision
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, request.agentVersion)
            statement.setString(2, request.hostName)
            statement.setLong(3, request.appliedConfigRevision)
            statement.setLong(4, request.rejectedConfigRevision)
            statement.setString(5, request.configRejectionReason?.take(500))
            statement.setLong(6, request.rejectedConfigRevision)
            statement.setLong(7, request.appliedRevokePhonesRevision)
            statement.setObject(8, agentId)
            statement.executeQuery().use { result ->
                if (result.next()) {
                    AgentCommands(
                        desiredConfig = json.decodeFromString(result.getString("desired_config")),
                        configRevision = result.getLong("config_revision"),
                        revokePhonesRevision = result.getLong("revoke_phones_revision"),
                    )
                } else null
            }
        }
    }

    fun listAgents(): List<AgentRecord> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT id, display_name, host_name, agent_version, os_name, os_version,
                   enrolled_at, last_seen_at, revoked_at, desired_config::text,
                   config_revision, applied_config_revision,
                   rejected_config_revision, config_rejection_reason,
                   revoke_phones_revision, applied_revoke_phones_revision
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
                                revokedAt = result.getObject("revoked_at", OffsetDateTime::class.java)?.toInstant(),
                                desiredConfig = json.decodeFromString(result.getString("desired_config")),
                                configRevision = result.getLong("config_revision"),
                                appliedConfigRevision = result.getLong("applied_config_revision"),
                                rejectedConfigRevision = result.getLong("rejected_config_revision"),
                                configRejectionReason = result.getString("config_rejection_reason"),
                                revokePhonesRevision = result.getLong("revoke_phones_revision"),
                                appliedRevokePhonesRevision = result.getLong("applied_revoke_phones_revision"),
                            ),
                        )
                    }
                }
            }
        }
    }

    fun revokeAgent(id: UUID): Boolean = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "UPDATE agents SET revoked_at = COALESCE(revoked_at, CURRENT_TIMESTAMP) WHERE id = ?",
        ).use { statement ->
            statement.setObject(1, id)
            statement.executeUpdate() == 1
        }
    }

    fun updateAgentConfig(id: UUID, config: RemoteAgentConfig): Boolean = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            UPDATE agents
            SET desired_config = ?::jsonb, config_revision = config_revision + 1
            WHERE id = ? AND revoked_at IS NULL
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, json.encodeToString(config))
            statement.setObject(2, id)
            statement.executeUpdate() == 1
        }
    }

    fun requestPhoneRevocation(id: UUID): Boolean = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            UPDATE agents SET revoke_phones_revision = revoke_phones_revision + 1
            WHERE id = ? AND revoked_at IS NULL
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, id)
            statement.executeUpdate() == 1
        }
    }
}
