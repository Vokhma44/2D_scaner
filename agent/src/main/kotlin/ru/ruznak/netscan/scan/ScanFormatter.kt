package ru.ruznak.netscan.scan

import ru.ruznak.netscan.config.OutputConfig
import ru.ruznak.netscan.config.SuffixKey
import ru.ruznak.netscan.keyboard.KeyChord

/** Текст, готовый к вводу на ПК, и управляющие клавиши после него. */
data class FormattedScan(val text: String, val trailingKeys: List<KeyChord>) {
    /** Представление для приёмников, которые пишут строки (файл, вебхук, консоль). */
    fun asLine(): String = text
}

/**
 * Приводит содержимое кода к виду, который ожидает учётная система:
 * префикс, суффикс, обработка разделителя GS1 и удаление управляющих символов.
 */
object ScanFormatter {

    /** Разделитель полей GS1 (ASCII 29), приходит в DataMatrix маркировки. */
    const val GS: Char = '\u001D'

    fun format(rawCode: String, config: OutputConfig): FormattedScan {
        val code = rawCode
            .let { if (config.trim) it.trim() else it }
            .replace(GS.toString(), config.gs1SeparatorReplacement)
            .let(::stripControlCharacters)

        val text = buildString {
            append(config.prefix)
            append(code)
            append(config.suffixText)
        }

        return FormattedScan(text, trailingKeys(config.suffix))
    }

    private fun trailingKeys(suffix: SuffixKey): List<KeyChord> = when (suffix) {
        SuffixKey.NONE -> emptyList()
        SuffixKey.ENTER -> listOf(KeyChord.ENTER)
        SuffixKey.TAB -> listOf(KeyChord.TAB)
        SuffixKey.TAB_ENTER -> listOf(KeyChord.TAB, KeyChord.ENTER)
    }

    /**
     * Управляющие символы в коде ломают ввод в чужое окно (перевод строки посреди
     * кода отправил бы форму), поэтому вырезаются. Табуляция сохраняется —
     * она осмысленна для многополевых кодов.
     */
    private fun stripControlCharacters(value: String): String =
        value.filter { it == '\t' || !it.isISOControl() }
}
