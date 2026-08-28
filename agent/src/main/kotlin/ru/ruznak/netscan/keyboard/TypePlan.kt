package ru.ruznak.netscan.keyboard

import ru.ruznak.netscan.config.TypingMode

/** Шаг ввода: либо нажатие клавиши, либо вставка текста через буфер обмена. */
sealed interface TypeStep {
    data class Keys(val chord: KeyChord) : TypeStep
    data class Paste(val text: String) : TypeStep
}

/** Готовая программа ввода для одного скана. */
data class TypePlan(val steps: List<TypeStep>) {
    val usesClipboard: Boolean get() = steps.any { it is TypeStep.Paste }
    val isEmpty: Boolean get() = steps.isEmpty()
}

/**
 * Строит план ввода без обращения к экрану и Robot: логику можно проверять
 * тестами на любой машине, включая headless-сборку в CI.
 */
object TypePlanner {

    fun plan(text: String, mode: TypingMode): TypePlan {
        if (text.isEmpty()) return TypePlan(emptyList())
        return when (mode) {
            TypingMode.CLIPBOARD -> TypePlan(listOf(TypeStep.Paste(text)))
            TypingMode.KEYS -> TypePlan(text.map { ch -> keyStepOrPlaceholder(ch) })
            TypingMode.HYBRID -> hybrid(text)
        }
    }

    /** В режиме KEYS непечатаемый символ всё равно нужно как-то ввести — отдаём его в буфер. */
    private fun keyStepOrPlaceholder(ch: Char): TypeStep =
        KeyboardLayout.chordFor(ch)?.let { TypeStep.Keys(it) } ?: TypeStep.Paste(ch.toString())

    /**
     * Латиница и цифры набираются посимвольно, а идущие подряд «чужие» символы
     * группируются в одну вставку: одна комбинация Ctrl+V вместо десяти.
     */
    private fun hybrid(text: String): TypePlan {
        val steps = mutableListOf<TypeStep>()
        val pending = StringBuilder()

        fun flush() {
            if (pending.isNotEmpty()) {
                steps += TypeStep.Paste(pending.toString())
                pending.setLength(0)
            }
        }

        for (ch in text) {
            val chord = KeyboardLayout.chordFor(ch)
            if (chord != null) {
                flush()
                steps += TypeStep.Keys(chord)
            } else {
                pending.append(ch)
            }
        }
        flush()
        return TypePlan(steps)
    }
}
