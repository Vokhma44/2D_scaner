package ru.ruznak.netscan.output

import org.slf4j.LoggerFactory
import ru.ruznak.netscan.config.OutputConfig
import ru.ruznak.netscan.config.SinkKind
import ru.ruznak.netscan.keyboard.KeyChord
import ru.ruznak.netscan.keyboard.TypePlan
import ru.ruznak.netscan.keyboard.TypePlanner
import ru.ruznak.netscan.keyboard.TypeStep
import ru.ruznak.netscan.scan.FormattedScan
import java.awt.Robot
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.event.KeyEvent

/**
 * Ввод кода в активное окно ПК через эмуляцию клавиатуры — ровно то, что делает
 * USB-сканер в режиме HID keyboard wedge: приложению не нужно ничего знать о нас.
 */
class KeyboardSink(
    private val robot: KeyEmitter,
    private val clipboard: ClipboardAccess,
    private val config: () -> OutputConfig,
) : OutputSink {

    override val kind: SinkKind = SinkKind.KEYBOARD

    override val status: String get() = "эмуляция клавиатуры активна"

    override fun emit(scan: FormattedScan, context: ScanContext) {
        val settings = config()
        if (settings.typingLeadMs > 0) robot.pause(settings.typingLeadMs)

        val plan: TypePlan = TypePlanner.plan(scan.text, settings.typingMode)
        // Буфер обмена пользователя восстанавливается после вставки: сканер не должен
        // затирать то, что оператор скопировал минуту назад.
        val saved = if (plan.usesClipboard) clipboard.read() else null
        try {
            for (step in plan.steps) {
                when (step) {
                    is TypeStep.Keys -> robot.press(step.chord)
                    is TypeStep.Paste -> paste(step.text)
                }
                if (settings.keyDelayMs > 0) robot.pause(settings.keyDelayMs)
            }
            scan.trailingKeys.forEach { robot.press(it) }
        } finally {
            if (saved != null) runCatching { clipboard.restore(saved) }
        }
    }

    private fun paste(text: String) {
        clipboard.write(text)
        // Небольшая пауза: приложение-получатель должно увидеть уже обновлённый буфер.
        robot.pause(PASTE_SETTLE_MS)
        robot.press(PASTE_CHORD)
    }

    companion object {
        private val log = LoggerFactory.getLogger(KeyboardSink::class.java)
        private const val PASTE_SETTLE_MS = 30L

        /** На macOS вставка — Cmd+V, на Windows и Linux — Ctrl+V. */
        val PASTE_CHORD: KeyChord = if (isMac()) {
            KeyChord(KeyEvent.VK_V, setOf(KeyChord.Modifier.META))
        } else {
            KeyChord(KeyEvent.VK_V, setOf(KeyChord.Modifier.CTRL))
        }

        fun isMac(): Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

        /**
         * Создаёт приёмник или возвращает null, если графическая среда недоступна
         * (сервер без X11, headless-режим JVM). Вызывающий код в этом случае
         * переключается на другой приёмник и пишет об этом в консоль.
         */
        fun createOrNull(config: () -> OutputConfig): KeyboardSink? = runCatching {
            KeyboardSink(AwtKeyEmitter(Robot()), AwtClipboard(), config)
        }.onFailure { log.warn("Эмуляция клавиатуры недоступна: {}", it.message) }.getOrNull()
    }
}

/** Абстракция над Robot: позволяет тестировать порядок нажатий без реального экрана. */
interface KeyEmitter {
    fun press(chord: KeyChord)
    fun pause(millis: Long)
}

/** Абстракция над системным буфером обмена. */
interface ClipboardAccess {
    fun write(text: String)
    fun read(): Transferable?
    fun restore(previous: Transferable)
}

class AwtKeyEmitter(private val robot: Robot) : KeyEmitter {

    override fun press(chord: KeyChord) {
        val modifiers = chord.modifiers.map(::modifierKeyCode)
        modifiers.forEach(robot::keyPress)
        try {
            robot.keyPress(chord.keyCode)
            robot.keyRelease(chord.keyCode)
        } finally {
            modifiers.asReversed().forEach(robot::keyRelease)
        }
    }

    override fun pause(millis: Long) {
        if (millis > 0) robot.delay(millis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    private fun modifierKeyCode(modifier: KeyChord.Modifier): Int = when (modifier) {
        KeyChord.Modifier.SHIFT -> KeyEvent.VK_SHIFT
        KeyChord.Modifier.CTRL -> KeyEvent.VK_CONTROL
        KeyChord.Modifier.ALT -> KeyEvent.VK_ALT
        KeyChord.Modifier.META -> KeyEvent.VK_META
    }
}

class AwtClipboard : ClipboardAccess {

    private val clipboard get() = Toolkit.getDefaultToolkit().systemClipboard

    override fun write(text: String) = clipboard.setContents(StringSelection(text), null)

    override fun read(): Transferable? = runCatching {
        val current = clipboard.getContents(null) ?: return null
        // Сохраняем только текст: копирование произвольного Transferable между
        // владельцами буфера ненадёжно и может «уронить» чужие данные.
        val text = current.getTransferData(DataFlavor.stringFlavor) as? String
        text?.let { StringSelection(it) }
    }.getOrNull()

    override fun restore(previous: Transferable) = clipboard.setContents(previous, null)
}
