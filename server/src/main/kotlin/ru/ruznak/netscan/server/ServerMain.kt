package ru.ruznak.netscan.server

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("ru.ruznak.netscan.server.Main")

fun main() {
    val config = ServerConfig.fromEnvironment()
    val dataSource = DatabaseFactory.create(config)
    val repository = FleetRepository(dataSource)

    Runtime.getRuntime().addShutdownHook(Thread { dataSource.close() })
    log.info("Запуск netscan-server на {}:{}", config.host, config.port)
    embeddedServer(Netty, host = config.host, port = config.port) {
        fleetModule(config, repository)
    }.start(wait = true)
}
