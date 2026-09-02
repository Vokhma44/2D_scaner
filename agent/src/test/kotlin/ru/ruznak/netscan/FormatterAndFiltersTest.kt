package ru.ruznak.netscan

import ru.ruznak.netscan.config.OutputConfig
import ru.ruznak.netscan.config.SuffixKey
import ru.ruznak.netscan.keyboard.KeyChord
import ru.ruznak.netscan.scan.Deduplicator
import ru.ruznak.netscan.scan.ScanFormatter
import ru.ruznak.netscan.scan.ScanHistory
import ru.ruznak.netscan.scan.ScanRecord
import ru.ruznak.netscan.security.RateLimiter
import org.junit.jupiter.api.DisplayName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScanFormatterTest {

    @Test
    @DisplayName("префикс и текстовый суффикс добавляются вокруг кода")
    fun prefiks_i_tekstovyy_suffiks_dobavlyayutsya_vokrug_koda() {
        val config = OutputConfig(prefix = "IN:", suffixText = ";", suffix = SuffixKey.TAB)

        val result = ScanFormatter.format("12345", config)

        assertEquals("IN:12345;", result.text)
        assertEquals(listOf(KeyChord.TAB), result.trailingKeys)
    }

    @Test
    @DisplayName("разделитель GS1 передаётся как есть по умолчанию и заменяется по настройке")
    fun razdelitel_gs1_peredaetsya_kak_est_po_umolchaniyu_i_zamenyaetsya_po_nastroyke() {
        val gs = ScanFormatter.GS
        val raw = "0104607012345678${gs}21ABC${gs}93XYZ"

        // Без разделителя код маркировки недействителен: группа 21 переменной длины,
        // и приёмная сторона не может определить, где кончается серийный номер.
        assertEquals(raw, ScanFormatter.format(raw, OutputConfig()).text)
        assertEquals(
            "0104607012345678|21ABC|93XYZ",
            ScanFormatter.format(raw, OutputConfig(gs1SeparatorReplacement = "|")).text,
        )
    }

    @Test
    @DisplayName("реальный код маркировки доходит до приёмника без потерь")
    fun realnyy_kod_markirovki_dohodit_do_priemnika_bez_poter() {
        val gs = ScanFormatter.GS
        val raw = "0104660639879864215ah3(&ONb0de!${gs}91EE12${gs}92" +
            "ozxyxZewqZQ/JCBygX3Ne+z0O6Zt8z6zVPSxFPuNRQs="

        val result = ScanFormatter.format(raw, OutputConfig())

        assertEquals(raw, result.text)
        assertEquals(2, result.text.count { it == gs }, "оба разделителя должны сохраниться")
    }

    @Test
    @DisplayName("разделитель на краю кода не срезается вместе с пробелами")
    fun razdelitel_na_krayu_koda_ne_srezaetsya_vmeste_s_probelami() {
        // Java относит GS к пробельным символам, поэтому обычный trim() съел бы его.
        val gs = ScanFormatter.GS

        assertEquals("01ABC$gs", ScanFormatter.format("  01ABC$gs  ", OutputConfig(trim = true)).text)
    }

    @Test
    @DisplayName("переводы строк внутри кода вырезаются, табуляция сохраняется")
    fun perevody_strok_vnutri_koda_vyrezayutsya_tabulyaciya_sohranyaetsya() {
        val result = ScanFormatter.format("AB\nCD\tEF", OutputConfig(suffix = SuffixKey.NONE))

        assertEquals("ABCD\tEF", result.text)
        assertTrue(result.trailingKeys.isEmpty())
    }

    @Test
    @DisplayName("пробелы по краям убираются только при включённой настройке")
    fun probely_po_krayam_ubirayutsya_tolko_pri_vklyuchennoy_nastroyke() {
        assertEquals("code", ScanFormatter.format("  code  ", OutputConfig(trim = true)).text)
        assertEquals("  code  ", ScanFormatter.format("  code  ", OutputConfig(trim = false)).text)
    }
}

class DeduplicatorTest {

    @Test
    @DisplayName("непрерывное наведение камеры не прорывается по истечении окна")
    fun nepreryvnoe_navedenie_kamery_ne_proryvaetsya_po_istechenii_okna() {
        val clock = TestClock()
        val dedup = Deduplicator({ 1000 }, clock = clock::millis)

        assertTrue(dedup.accept("d1", "1", "CODE"))
        // Камера присылает код каждые 100 мс: окно должно сдвигаться, а не истекать.
        repeat(20) { index ->
            clock.advance(100)
            assertFalse(dedup.accept("d1", "repeat-$index", "CODE"))
        }
        clock.advance(1200)
        assertTrue(dedup.accept("d1", "final", "CODE"))
    }

    @Test
    @DisplayName("нулевое окно отключает подавление повторов")
    fun nulevoe_okno_otklyuchaet_podavlenie_povtorov() {
        val dedup = Deduplicator({ 0 })

        assertTrue(dedup.accept("d1", "1", "CODE"))
        assertTrue(dedup.accept("d1", "2", "CODE"))
    }

    @Test
    @DisplayName("память идентификаторов ограничена и не растёт бесконечно")
    fun pamyat_identifikatorov_ogranichena_i_ne_rastet_beskonechno() {
        val dedup = Deduplicator({ 0 }, idMemory = 4)

        repeat(10) { assertTrue(dedup.accept("d1", "id-$it", "code-$it")) }
        // Самые старые идентификаторы вытеснены, поэтому такой скан снова считается новым.
        assertTrue(dedup.accept("d1", "id-0", "code-0"))
        assertFalse(dedup.accept("d1", "id-9", "code-9"))
    }
}

class ScanHistoryTest {

    @Test
    @DisplayName("журнал ограничен размером и отдаёт свежие записи первыми")
    fun zhurnal_ogranichen_razmerom_i_otdaet_svezhie_zapisi_pervymi() {
        val history = ScanHistory { 3 }

        repeat(5) { index ->
            history.add(ScanRecord("id-$index", "d1", "Телефон", "code-$index", "qr_code", index.toLong(), "accepted"))
        }

        val recent = history.recent()
        assertEquals(3, recent.size)
        assertEquals(listOf("code-4", "code-3", "code-2"), recent.map { it.code })
    }
}

class RateLimiterTest {

    @Test
    @DisplayName("перебор кода сопряжения блокируется после лимита попыток")
    fun perebor_koda_sopryazheniya_blokiruetsya_posle_limita_popytok() {
        val clock = TestClock()
        val limiter = RateLimiter(permitsPerWindow = 3, windowMillis = 60_000, clock = clock::millis)

        repeat(3) { assertTrue(limiter.tryAcquire("10.0.0.5")) }
        assertFalse(limiter.tryAcquire("10.0.0.5"))
        // Другой адрес не должен страдать из-за чужого перебора.
        assertTrue(limiter.tryAcquire("10.0.0.6"))

        clock.advance(60_001)
        assertTrue(limiter.tryAcquire("10.0.0.5"))
    }

    @Test
    @DisplayName("успешное сопряжение сбрасывает счётчик попыток")
    fun uspeshnoe_sopryazhenie_sbrasyvaet_schetchik_popytok() {
        val limiter = RateLimiter(permitsPerWindow = 2)

        assertTrue(limiter.tryAcquire("ip"))
        assertTrue(limiter.tryAcquire("ip"))
        assertFalse(limiter.tryAcquire("ip"))

        limiter.reset("ip")
        assertTrue(limiter.tryAcquire("ip"))
    }
}
