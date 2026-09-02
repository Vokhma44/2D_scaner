package ru.ruznak.netscan

import ru.ruznak.netscan.config.OutputConfig
import ru.ruznak.netscan.config.SuffixKey
import ru.ruznak.netscan.config.TypingMode
import ru.ruznak.netscan.keyboard.KeyChord
import ru.ruznak.netscan.keyboard.KeyboardLayout
import ru.ruznak.netscan.keyboard.TypePlanner
import ru.ruznak.netscan.keyboard.TypeStep
import ru.ruznak.netscan.output.ClipboardAccess
import ru.ruznak.netscan.output.KeyEmitter
import ru.ruznak.netscan.output.KeyboardSink
import ru.ruznak.netscan.output.ScanContext
import ru.ruznak.netscan.scan.ScanFormatter
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.event.KeyEvent
import org.junit.jupiter.api.DisplayName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyboardLayoutTest {

    @Test
    @DisplayName("латиница цифры и знаки набираются нажатиями")
    fun latinica_cifry_i_znaki_nabirayutsya_nazhatiyami() {
        assertEquals(KeyChord(KeyEvent.VK_A), KeyboardLayout.chordFor('a'))
        assertEquals(KeyChord.shifted(KeyEvent.VK_A), KeyboardLayout.chordFor('A'))
        assertEquals(KeyChord(KeyEvent.VK_7), KeyboardLayout.chordFor('7'))
        assertEquals(KeyChord.shifted(KeyEvent.VK_7), KeyboardLayout.chordFor('&'))
        assertEquals(KeyChord(KeyEvent.VK_MINUS), KeyboardLayout.chordFor('-'))
        assertEquals(KeyChord.shifted(KeyEvent.VK_SEMICOLON), KeyboardLayout.chordFor(':'))
    }

    @Test
    @DisplayName("разделитель GS1 набирается как Ctrl+], так же как у USB-сканера")
    fun razdelitel_gs1_nabiraetsya_kak_ctrl_zakryvayushchaya_skobka() {
        assertEquals(
            KeyChord(KeyEvent.VK_CLOSE_BRACKET, setOf(KeyChord.Modifier.CTRL)),
            KeyboardLayout.chordFor(ScanFormatter.GS),
        )
    }

    @Test
    @DisplayName("символы вне раскладки US не имеют нажатия")
    fun simvoly_vne_raskladki_us_ne_imeyut_nazhatiya() {
        assertNull(KeyboardLayout.chordFor('к'))
        assertNull(KeyboardLayout.chordFor('€'))
    }
}

class TypePlannerTest {

    @Test
    @DisplayName("в режиме нажатий каждый символ превращается в клавишу")
    fun v_rezhime_nazhatiy_kazhdyy_simvol_prevraschaetsya_v_klavishu() {
        val plan = TypePlanner.plan("A1-", TypingMode.KEYS)

        assertEquals(
            listOf(
                TypeStep.Keys(KeyChord.shifted(KeyEvent.VK_A)),
                TypeStep.Keys(KeyChord(KeyEvent.VK_1)),
                TypeStep.Keys(KeyChord(KeyEvent.VK_MINUS)),
            ),
            plan.steps,
        )
        assertTrue(!plan.usesClipboard)
    }

    @Test
    @DisplayName("гибридный режим печатает латиницу и вставляет остальное одной группой")
    fun gibridnyy_rezhim_pechataet_latinicu_i_vstavlyaet_ostalnoe_odnoy_gruppoy() {
        val plan = TypePlanner.plan("AB-Тест-42", TypingMode.HYBRID)

        assertEquals(
            listOf(
                TypeStep.Keys(KeyChord.shifted(KeyEvent.VK_A)),
                TypeStep.Keys(KeyChord.shifted(KeyEvent.VK_B)),
                TypeStep.Keys(KeyChord(KeyEvent.VK_MINUS)),
                TypeStep.Paste("Тест"),
                TypeStep.Keys(KeyChord(KeyEvent.VK_MINUS)),
                TypeStep.Keys(KeyChord(KeyEvent.VK_4)),
                TypeStep.Keys(KeyChord(KeyEvent.VK_2)),
            ),
            plan.steps,
        )
        assertTrue(plan.usesClipboard)
    }

    @Test
    @DisplayName("режим буфера обмена вставляет весь код одним шагом")
    fun rezhim_bufera_obmena_vstavlyaet_ves_kod_odnim_shagom() {
        val plan = TypePlanner.plan("0104607012345678", TypingMode.CLIPBOARD)
        assertEquals(listOf(TypeStep.Paste("0104607012345678")), plan.steps)
    }

    @Test
    @DisplayName("пустой текст не порождает шагов")
    fun pustoy_tekst_ne_porozhdaet_shagov() {
        assertTrue(TypePlanner.plan("", TypingMode.HYBRID).isEmpty)
    }

    @Test
    @DisplayName("непечатаемый символ в режиме нажатий уходит через буфер обмена")
    fun nepechataemyy_simvol_v_rezhime_nazhatiy_uhodit_cherez_bufer_obmena() {
        val plan = TypePlanner.plan("Aя", TypingMode.KEYS)
        assertEquals(TypeStep.Paste("я"), plan.steps.last())
    }
}

class KeyboardSinkTest {

    private class FakeEmitter : KeyEmitter {
        val chords = mutableListOf<KeyChord>()
        var pauses = 0L
        override fun press(chord: KeyChord) {
            chords += chord
        }

        override fun pause(millis: Long) {
            pauses += millis
        }
    }

    private class FakeClipboard(initial: String? = null) : ClipboardAccess {
        var current: String? = initial
        val writes = mutableListOf<String>()
        var restored = false

        override fun write(text: String) {
            current = text
            writes += text
        }

        override fun read(): Transferable? = current?.let { StringSelection(it) }

        override fun restore(previous: Transferable) {
            restored = true
            current = previous.getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
        }
    }

    private val context = ScanContext("id", "device", "Телефон", "CODE", "qr_code", 0)

    @Test
    @DisplayName("код набирается посимвольно и завершается клавишей Enter")
    fun kod_nabiraetsya_posimvolno_i_zavershaetsya_klavishey_enter() {
        val emitter = FakeEmitter()
        val clipboard = FakeClipboard()
        val config = OutputConfig(typingMode = TypingMode.KEYS, suffix = SuffixKey.ENTER, keyDelayMs = 5)
        val sink = KeyboardSink(emitter, clipboard, { config })

        sink.emit(ScanFormatter.format("AB1", config), context)

        assertEquals(
            listOf(
                KeyChord.shifted(KeyEvent.VK_A),
                KeyChord.shifted(KeyEvent.VK_B),
                KeyChord(KeyEvent.VK_1),
                KeyChord.ENTER,
            ),
            emitter.chords,
        )
        assertEquals(15, emitter.pauses)
    }

    @Test
    @DisplayName("суффикс Tab плюс Enter нажимается в заданном порядке")
    fun suffiks_tab_plyus_enter_nazhimaetsya_v_zadannom_poryadke() {
        val emitter = FakeEmitter()
        val config = OutputConfig(typingMode = TypingMode.KEYS, suffix = SuffixKey.TAB_ENTER, keyDelayMs = 0)
        val sink = KeyboardSink(emitter, FakeClipboard(), { config })

        sink.emit(ScanFormatter.format("A", config), context)

        assertEquals(listOf(KeyChord.shifted(KeyEvent.VK_A), KeyChord.TAB, KeyChord.ENTER), emitter.chords)
    }

    @Test
    @DisplayName("кириллица вставляется через буфер обмена, а прежнее содержимое возвращается")
    fun kirillica_vstavlyaetsya_cherez_bufer_obmena_a_prezhnee_soderzhimoe_vozvraschaetsya() {
        val emitter = FakeEmitter()
        val clipboard = FakeClipboard(initial = "важный текст оператора")
        val config = OutputConfig(typingMode = TypingMode.HYBRID, suffix = SuffixKey.NONE, keyDelayMs = 0)
        val sink = KeyboardSink(emitter, clipboard, { config })

        sink.emit(ScanFormatter.format("Ящик-7", config), context)

        assertEquals(listOf("Ящик"), clipboard.writes)
        assertTrue(clipboard.restored, "буфер обмена оператора не восстановлен")
        assertEquals("важный текст оператора", clipboard.current)
        assertTrue(emitter.chords.contains(KeyboardSink.PASTE_CHORD))
        assertEquals(KeyChord(KeyEvent.VK_7), emitter.chords.last())
    }

    @Test
    @DisplayName("буфер обмена не трогается, если весь код печатается клавишами")
    fun bufer_obmena_ne_trogaetsya_esli_ves_kod_pechataetsya_klavishami() {
        val clipboard = FakeClipboard(initial = "не трогать")
        val config = OutputConfig(typingMode = TypingMode.HYBRID, suffix = SuffixKey.NONE, keyDelayMs = 0)
        val sink = KeyboardSink(FakeEmitter(), clipboard, { config })

        sink.emit(ScanFormatter.format("ABC123", config), context)

        assertTrue(clipboard.writes.isEmpty())
        assertTrue(!clipboard.restored)
    }
}
