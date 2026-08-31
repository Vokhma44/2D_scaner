package ru.ruznak.netscan

import org.slf4j.LoggerFactory
import ru.ruznak.netscan.config.CliArgs
import ru.ruznak.netscan.config.ConfigStore
import ru.ruznak.netscan.console.Banner
import ru.ruznak.netscan.fleet.FleetClient
import ru.ruznak.netscan.fleet.FleetCredentialsStore
import ru.ruznak.netscan.net.TlsProvisioner
import ru.ruznak.netscan.net.startServers
import ru.ruznak.netscan.output.SinkManager
import ru.ruznak.netscan.scan.ScanHistory
import ru.ruznak.netscan.security.DeviceRegistry
import ru.ruznak.netscan.security.PairingService
import ru.ruznak.netscan.tray.TrayController
import ru.ruznak.netscan.update.AutoUpdater
import ru.ruznak.netscan.update.UpdateStatusStore
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("ru.ruznak.netscan.Main")

fun main(argv: Array<String>) {
    val args = runCatching { CliArgs.parse(argv) }.getOrElse { error ->
        println(error.message)
        println(CliArgs.USAGE)
        exitProcess(2)
    }

    if (args.showHelp) {
        println(CliArgs.USAGE)
        return
    }

    Files.createDirectories(args.home)

    val instanceLock = SingleInstanceLock.acquire(args.home.resolve("agent.lock"))
    if (instanceLock == null) {
        log.info("Агент уже запущен для каталога {}", args.home)
        return
    }

    val configStore = ConfigStore.open(args.home.resolve("config.json"))
    // Ключи командной строки имеют приоритет над сохранённым конфигом и сразу
    // становятся новым значением по умолчанию: запуск скрипта настраивает агент.
    val config = configStore.update(args.overrides)
    runCatching { config.network.validated() }.onFailure {
        println("Некорректные настройки сети: ${it.message}")
        exitProcess(2)
    }

    val devices = DeviceRegistry(args.home.resolve("devices.json"))
    if (args.resetPairing) {
        devices.revokeAll()
        log.info("Сессии устройств отозваны")
    }

    val pairing = PairingService(
        file = args.home.resolve("pairing.txt"),
        ttlMinutes = { configStore.config.security.pairingTokenTtlMinutes },
    )
    if (args.resetPairing) pairing.rotate()

    val httpClient = AgentState.defaultHttpClient()
    val sinks = SinkManager(
        outputConfig = { configStore.config.output },
        httpClient = { httpClient },
    )

    val tls = TlsProvisioner(args.home.resolve("keystore.p12")).provision()
    if (tls.regenerated) log.info("Выпущен новый TLS-сертификат для адресов: {}", tls.subjectAlternativeNames)

    val state = AgentState(
        configStore = configStore,
        devices = devices,
        pairing = pairing,
        history = ScanHistory { configStore.config.scan.historySize },
        sinks = sinks,
        tls = tls,
        httpClient = httpClient,
    )

    val servers = runCatching { startServers(state, tls, config.network) }.getOrElse { error ->
        println("Не удалось занять порт ${config.network.httpsPort}: ${error.message}")
        println("Укажите другой порт: netscan --port 9443")
        state.close()
        exitProcess(1)
    }

    print(Banner.render(state))

    val updateStatus = UpdateStatusStore(args.home.resolve("update-status.json"))
    val fleet = FleetClient(
        configStore = configStore,
        hostName = state.hostName(),
        enrollmentToken = args.enrollmentToken,
        credentialsStore = FleetCredentialsStore(args.home.resolve("fleet-credentials.json")),
        revokePhones = {
            devices.revokeAll()
            pairing.rotate()
        },
        updateStatus = updateStatus::snapshot,
    ).also { it.start() }

    val updater = AutoUpdater(args.home, httpClient, updateStatus).also { it.start() }

    val shutdown = CountDownLatch(1)
    val tray = TrayController.install(state) {
        exitProcess(0)
    }
    Runtime.getRuntime().addShutdownHook(
        Thread {
            log.info("Остановка агента")
            tray?.close()
            fleet.close()
            updater.close()
            servers.stop()
            state.close()
            instanceLock.close()
            shutdown.countDown()
        },
    )
    shutdown.await()
}
