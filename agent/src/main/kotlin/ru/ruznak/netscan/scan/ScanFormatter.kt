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
        // Java относит GS к пробельным символам, поэтому обычный trim() срезал бы
        // разделитель на краю кода вместе с пробелами.
        val trimmed = if (config.trim) rawCode.trim { it.isWhitespace() && it != GS } else rawCode

        // Пустая замена означает «передать разделитель как есть»: без него код
        // маркировки не разбирается — группа 21 имеет переменную длину, и парсер
        // не может определить, где кончается серийный номер.
        val separated = config.gs1SeparatorReplacement
            .takeIf { it.isNotEmpty() }
            ?.let { replacement -> trimmed.replace(GS.toString(), replacement) }
            ?: trimmed

        val code = stripControlCharacters(separated)

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
     * кода отправил бы форму), поэтому вырезаются. Два исключения: табуляция —
     * она осмысленна для многополевых кодов, и GS — без него код маркировки
     * недействителен, а замену на печатный символ выбирает оператор.
     */
    private fun stripControlCharacters(value: String): String =
        value.filter { it == '\t' || it == GS || !it.isISOControl() }
}
