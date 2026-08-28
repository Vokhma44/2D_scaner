package ru.ruznak.netscan.tray

import org.slf4j.LoggerFactory
import ru.ruznak.netscan.AgentState
import java.awt.Desktop
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.net.URI
import javax.imageio.ImageIO

/** Управление фоновым агентом из области уведомлений Windows. */
class TrayController private constructor(
    private val systemTray: SystemTray,
    private val trayIcon: TrayIcon,
) : AutoCloseable {

    override fun close() {
        systemTray.remove(trayIcon)
    }

    companion object {
        private val log = LoggerFactory.getLogger(TrayController::class.java)

        fun install(state: AgentState, onExit: () -> Unit): TrayController? {
            if (!SystemTray.isSupported()) {
                log.warn("Системный трей недоступен; агент продолжит работу без значка")
                return null
            }

            return runCatching {
                val image = requireNotNull(
                    TrayController::class.java.getResourceAsStream("/icons/ruznak-tray.png"),
                ) { "Не найден ресурс /icons/ruznak-tray.png" }.use(ImageIO::read)

                val consoleUrl = "https://localhost:${state.config.network.httpsPort}/console"
                val popup = PopupMenu().apply {
                    add(MenuItem("Открыть консоль").apply {
                        addActionListener { browse(consoleUrl) }
                    })
                    add(MenuItem("Подключить телефон (QR-код)").apply {
                        addActionListener { browse(state.pairingUrl()) }
                    })
                    addSeparator()
                    add(MenuItem("Выход").apply {
                        addActionListener { onExit() }
                    })
                }

                val icon = TrayIcon(image, "РУЗНАК — сканер 2D", popup).apply {
                    isImageAutoSize = true
                    addActionListener { browse(consoleUrl) }
                }
                val tray = SystemTray.getSystemTray()
                tray.add(icon)
                icon.displayMessage(
                    "РУЗНАК — сканер 2D",
                    "Агент работает в фоне. Дважды нажмите на значок, чтобы открыть консоль.",
                    TrayIcon.MessageType.INFO,
                )
                TrayController(tray, icon)
            }.onFailure { error ->
                log.error("Не удалось создать значок в системном трее", error)
            }.getOrNull()
        }

        private fun browse(url: String) {
            runCatching {
                check(Desktop.isDesktopSupported()) { "Открытие браузера не поддерживается" }
                Desktop.getDesktop().browse(URI(url))
            }.onFailure { error ->
                log.error("Не удалось открыть {}", url, error)
            }
        }
    }
}
