package ru.ruznak.netscan.keyboard

import java.awt.event.KeyEvent

/** Нажатие клавиши с модификаторами. */
data class KeyChord(val keyCode: Int, val modifiers: Set<Modifier> = emptySet()) {
    enum class Modifier { SHIFT, CTRL, ALT, META }

    companion object {
        val ENTER = KeyChord(KeyEvent.VK_ENTER)
        val TAB = KeyChord(KeyEvent.VK_TAB)
        fun shifted(keyCode: Int) = KeyChord(keyCode, setOf(Modifier.SHIFT))
    }
}

/**
 * Таблица «символ → нажатие» для стандартной раскладки US ANSI.
 *
 * Эмуляция клавиатуры всегда идёт через раскладку, активную в ОС. Латиница,
 * цифры и служебные знаки набираются нажатиями (как это делает USB-сканер),
 * всё остальное — кириллица, иероглифы, эмодзи — уходит через буфер обмена.
 */
object KeyboardLayout {

    private val unshifted: Map<Char, Int> = buildMap {
        for (c in 'a'..'z') put(c, KeyEvent.VK_A + (c - 'a'))
        for (c in '0'..'9') put(c, KeyEvent.VK_0 + (c - '0'))
        put(' ', KeyEvent.VK_SPACE)
        put('-', KeyEvent.VK_MINUS)
        put('=', KeyEvent.VK_EQUALS)
        put('[', KeyEvent.VK_OPEN_BRACKET)
        put(']', KeyEvent.VK_CLOSE_BRACKET)
        put('\\', KeyEvent.VK_BACK_SLASH)
        put(';', KeyEvent.VK_SEMICOLON)
        put('\'', KeyEvent.VK_QUOTE)
        put(',', KeyEvent.VK_COMMA)
        put('.', KeyEvent.VK_PERIOD)
        put('/', KeyEvent.VK_SLASH)
        put('`', KeyEvent.VK_BACK_QUOTE)
        put('\n', KeyEvent.VK_ENTER)
        put('\t', KeyEvent.VK_TAB)
    }

    private val shifted: Map<Char, Int> = buildMap {
        for (c in 'A'..'Z') put(c, KeyEvent.VK_A + (c - 'A'))
        put('!', KeyEvent.VK_1)
        put('@', KeyEvent.VK_2)
        put('#', KeyEvent.VK_3)
        put('$', KeyEvent.VK_4)
        put('%', KeyEvent.VK_5)
        put('^', KeyEvent.VK_6)
        put('&', KeyEvent.VK_7)
        put('*', KeyEvent.VK_8)
        put('(', KeyEvent.VK_9)
        put(')', KeyEvent.VK_0)
        put('_', KeyEvent.VK_MINUS)
        put('+', KeyEvent.VK_EQUALS)
        put('{', KeyEvent.VK_OPEN_BRACKET)
        put('}', KeyEvent.VK_CLOSE_BRACKET)
        put('|', KeyEvent.VK_BACK_SLASH)
        put(':', KeyEvent.VK_SEMICOLON)
        put('"', KeyEvent.VK_QUOTE)
        put('<', KeyEvent.VK_COMMA)
        put('>', KeyEvent.VK_PERIOD)
        put('?', KeyEvent.VK_SLASH)
        put('~', KeyEvent.VK_BACK_QUOTE)
    }

    /**
     * Разделитель полей GS1 (ASCII 29, он же ScanFormatter.GS). Настоящий USB-сканер
     * в режиме HID отдаёт его нажатием Ctrl+], и учётные системы ждут именно этого.
     */
    private const val GROUP_SEPARATOR = '\u001D'

    /** Возвращает нажатие для символа или null, если раскладка его не покрывает. */
    fun chordFor(ch: Char): KeyChord? {
        if (ch == GROUP_SEPARATOR) {
            return KeyChord(KeyEvent.VK_CLOSE_BRACKET, setOf(KeyChord.Modifier.CTRL))
        }
        unshifted[ch]?.let { return KeyChord(it) }
        shifted[ch]?.let { return KeyChord.shifted(it) }
        return null
    }

    fun isTypable(ch: Char): Boolean = chordFor(ch) != null
}
