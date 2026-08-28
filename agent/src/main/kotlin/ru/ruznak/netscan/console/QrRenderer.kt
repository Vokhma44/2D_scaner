package ru.ruznak.netscan.console

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.nio.charset.Charset

/**
 * QR-код сопряжения. Это единственный шаг настройки для оператора: навёл камеру
 * телефона на экран ПК — и телефон уже подключён.
 */
object QrRenderer {

    /** Как рисовать QR в консоли. */
    enum class TerminalStyle {
        /** Полублоки: компактно и квадратно, требует поддержки Unicode в консоли. */
        BLOCKS,

        /** Только ASCII: вдвое шире, зато читается в любой кодировке терминала. */
        ASCII,
    }

    private fun matrix(content: String, quietZone: Int = 1): BitMatrix =
        QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            0,
            0,
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to quietZone,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            ),
        )

    /**
     * Отрисовка в терминал. По умолчанию — полублоками: один символ несёт две
     * строки модулей, поэтому код остаётся квадратным и помещается в обычное
     * окно консоли.
     *
     * Если кодировка консоли не переваривает псевдографику (старая cmd.exe,
     * окружение без UTF-8), автоматически берётся ASCII-вариант: иначе на месте
     * QR-кода оператор увидел бы поле вопросительных знаков.
     */
    fun toTerminal(content: String, quietZone: Int = 2, style: TerminalStyle = detectStyle()): String =
        when (style) {
            TerminalStyle.BLOCKS -> renderBlocks(content, quietZone)
            TerminalStyle.ASCII -> renderAscii(content, quietZone)
        }

    private fun renderBlocks(content: String, quietZone: Int): String {
        val bits = matrix(content, quietZone)
        val builder = StringBuilder()
        var y = 0
        while (y < bits.height) {
            for (x in 0 until bits.width) {
                val top = bits.get(x, y)
                val bottom = y + 1 < bits.height && bits.get(x, y + 1)
                builder.append(
                    when {
                        top && bottom -> '█'
                        top -> '▀'
                        bottom -> '▄'
                        else -> ' '
                    },
                )
            }
            builder.append('\n')
            y += 2
        }
        return builder.toString()
    }

    /** Модуль занимает два знакоместа: иначе код выйдет сплюснутым и не считается камерой. */
    private fun renderAscii(content: String, quietZone: Int): String {
        val bits = matrix(content, quietZone)
        val builder = StringBuilder()
        for (y in 0 until bits.height) {
            for (x in 0 until bits.width) builder.append(if (bits.get(x, y)) "##" else "  ")
            builder.append('\n')
        }
        return builder.toString()
    }

    fun detectStyle(charset: Charset = consoleCharset()): TerminalStyle =
        if (canEncode(charset, "█▀▄")) TerminalStyle.BLOCKS else TerminalStyle.ASCII

    /** Кодировка вывода берётся у самого потока консоли, а не у файловой системы. */
    fun consoleCharset(): Charset = runCatching {
        val name = System.getProperty("stdout.encoding")
            ?: System.getProperty("sun.stdout.encoding")
            ?: System.getProperty("file.encoding")
        name?.let(Charset::forName) ?: Charset.defaultCharset()
    }.getOrElse { Charset.defaultCharset() }

    fun canEncode(charset: Charset, text: String): Boolean =
        runCatching { charset.newEncoder().canEncode(text) }.getOrElse { false }

    /** Векторный QR для веб-консоли: масштабируется без размытия и не требует картинок. */
    fun toSvg(content: String, sizePx: Int = 256, quietZone: Int = 2): String {
        val bits = matrix(content, quietZone)
        val modules = bits.width
        val rects = StringBuilder()
        for (y in 0 until bits.height) {
            var x = 0
            while (x < bits.width) {
                if (!bits.get(x, y)) {
                    x++
                    continue
                }
                // Соседние модули склеиваются в один прямоугольник: SVG получается компактнее.
                var run = 1
                while (x + run < bits.width && bits.get(x + run, y)) run++
                rects.append("""<rect x="$x" y="$y" width="$run" height="1"/>""")
                x += run
            }
        }
        return """
            <svg xmlns="http://www.w3.org/2000/svg" width="$sizePx" height="$sizePx"
                 viewBox="0 0 $modules $modules" shape-rendering="crispEdges" role="img"
                 aria-label="QR-код для подключения телефона">
              <rect width="$modules" height="$modules" fill="#ffffff"/>
              <g fill="#000000">$rects</g>
            </svg>
        """.trimIndent()
    }
}
