package ru.ruznak.netscan

import ru.ruznak.netscan.config.AgentConfig
import ru.ruznak.netscan.config.OutputConfig
import ru.ruznak.netscan.config.ScanConfig
import ru.ruznak.netscan.config.SinkKind
import ru.ruznak.netscan.config.SuffixKey
import ru.ruznak.netscan.keyboard.KeyChord
import ru.ruznak.netscan.protocol.AckStatus
import ru.ruznak.netscan.protocol.ScanMessage
import ru.ruznak.netscan.scan.ScanFormatter
import ru.ruznak.netscan.scan.ScanHistory
import ru.ruznak.netscan.scan.ScanPipeline
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.DisplayName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScanPipelineTest {

    private fun pipeline(
        config: AgentConfig = AgentConfig(),
        clock: TestClock = TestClock(),
        sink: RecordingSink = RecordingSink(),
    ): Triple<ScanPipeline, RecordingSink, ScanHistory> {
        val history = ScanHistory { config.scan.historySize }
        val pipeline = ScanPipeline(
            config = { config },
            sink = { scan, context -> sink.emit(scan, context) },
            history = history,
            clock = clock::millis,
        )
        return Triple(pipeline, sink, history)
    }

    private fun scan(code: String, id: String = code, format: String = "qr_code") =
        ScanMessage(id = id, code = code, format = format, scannedAt = 0)

    @Test
    @DisplayName("код доходит до приёмника с префиксом и клавишей Enter")
    fun kod_dohodit_do_priemnika_s_prefiksom_i_klavishey_enter() {
        val config = AgentConfig(
            output = OutputConfig(
                sinks = listOf(SinkKind.KEYBOARD),
                prefix = ">",
                suffix = SuffixKey.ENTER,
            ),
        )
        val (pipeline, sink, _) = pipeline(config)

        val outcome = pipeline.submit(scan("0104607012345678"), "device-1", "Телефон")

        assertEquals(AckStatus.ACCEPTED, outcome.status)
        assertEquals(listOf(">0104607012345678"), sink.texts)
        assertEquals(listOf(KeyChord.ENTER), sink.emitted.single().first.trailingKeys)
    }

    @Test
    @DisplayName("код маркировки проходит весь конвейер, не потеряв разделители GS1")
    fun kod_markirovki_prohodit_ves_konveyer_ne_poteryav_razdeliteli_gs1() {
        // Регрессия: разделители удалялись форматтером, Честный знак отклонял код.
        // Проверяем весь путь целиком — от сообщения телефона до текста в приёмнике.
        val gs = ScanFormatter.GS
        val marking = "0104660639879864215ah3(&ONb0de!${gs}91EE12${gs}92" +
            "ozxyxZewqZQ/JCBygX3Ne+z0O6Zt8z6zVPSxFPuNRQs="
        val (pipeline, sink, _) = pipeline(
            AgentConfig(output = OutputConfig(sinks = listOf(SinkKind.KEYBOARD), suffix = SuffixKey.NONE)),
        )

        val outcome = pipeline.submit(scan(marking, format = "data_matrix"), "device-1", "Android-телефон")

        assertEquals(AckStatus.ACCEPTED, outcome.status)
        assertEquals(listOf(marking), sink.texts)
    }

    @Test
    @DisplayName("повтор того же кода в окне подавления не вводится второй раз")
    fun povtor_togo_zhe_koda_v_okne_podavleniya_ne_vvoditsya_vtoroy_raz() {
        val clock = TestClock()
        val config = AgentConfig(scan = ScanConfig(duplicateWindowMs = 1000))
        val (pipeline, sink, _) = pipeline(config, clock)

        assertEquals(AckStatus.ACCEPTED, pipeline.submit(scan("ABC", id = "1"), "d1", "Телефон").status)
        clock.advance(200)
        assertEquals(AckStatus.DUPLICATE, pipeline.submit(scan("ABC", id = "2"), "d1", "Телефон").status)

        clock.advance(1500)
        assertEquals(AckStatus.ACCEPTED, pipeline.submit(scan("ABC", id = "3"), "d1", "Телефон").status)
        assertEquals(2, sink.texts.size)
    }

    @Test
    @DisplayName("повторная доставка того же скана из офлайн-очереди игнорируется")
    fun povtornaya_dostavka_togo_zhe_skana_iz_oflayn_ocheredi_ignoriruetsya() {
        val (pipeline, sink, _) = pipeline()

        assertEquals(AckStatus.ACCEPTED, pipeline.submit(scan("XYZ", id = "scan-1"), "d1", "Телефон").status)
        assertEquals(AckStatus.DUPLICATE, pipeline.submit(scan("XYZ", id = "scan-1"), "d1", "Телефон").status)
        assertEquals(1, sink.texts.size)
    }

    @Test
    @DisplayName("разные телефоны не мешают подавлению повторов друг друга")
    fun raznye_telefony_ne_meshayut_podavleniyu_povtorov_drug_druga() {
        val config = AgentConfig(scan = ScanConfig(duplicateWindowMs = 5000))
        val (pipeline, sink, _) = pipeline(config)

        assertEquals(AckStatus.ACCEPTED, pipeline.submit(scan("SAME", id = "a"), "d1", "Телефон 1").status)
        assertEquals(AckStatus.ACCEPTED, pipeline.submit(scan("SAME", id = "b"), "d2", "Телефон 2").status)
        assertEquals(2, sink.texts.size)
    }

    @Test
    @DisplayName("короткие и длинные коды отбрасываются")
    fun korotkie_i_dlinnye_kody_otbrasyvayutsya() {
        val config = AgentConfig(scan = ScanConfig(minLength = 4, maxLength = 8))
        val (pipeline, sink, _) = pipeline(config)

        assertEquals(AckStatus.FILTERED, pipeline.submit(scan("ab", id = "1"), "d1", "Телефон").status)
        assertEquals(AckStatus.FILTERED, pipeline.submit(scan("abcdefghij", id = "2"), "d1", "Телефон").status)
        assertEquals(AckStatus.ACCEPTED, pipeline.submit(scan("abcde", id = "3"), "d1", "Телефон").status)
        assertEquals(1, sink.texts.size)
    }

    @Test
    @DisplayName("символика вне белого списка отбрасывается")
    fun simvolika_vne_belogo_spiska_otbrasyvaetsya() {
        val config = AgentConfig(scan = ScanConfig(allowedFormats = setOf("data_matrix")))
        val (pipeline, _, _) = pipeline(config)

        assertEquals(AckStatus.FILTERED, pipeline.submit(scan("code", format = "ean_13"), "d1", "Т").status)
        assertEquals(AckStatus.ACCEPTED, pipeline.submit(scan("code", id = "2", format = "DATA_MATRIX"), "d1", "Т").status)
    }

    @Test
    @DisplayName("регулярное выражение отсекает посторонние коды")
    fun regulyarnoe_vyrazhenie_otsekaet_postoronnie_kody() {
        val config = AgentConfig(scan = ScanConfig(filterRegex = "^01\\d{14}"))
        val (pipeline, _, _) = pipeline(config)

        assertEquals(AckStatus.ACCEPTED, pipeline.submit(scan("0104607012345678"), "d1", "Т").status)
        assertEquals(AckStatus.FILTERED, pipeline.submit(scan("https://example.com"), "d1", "Т").status)
    }

    @Test
    @DisplayName("ошибка ввода на ПК возвращается телефону как failed")
    fun oshibka_vvoda_na_pk_vozvraschaetsya_telefonu_kak_failed() {
        val sink = RecordingSink(failure = { error("окно не приняло ввод") })
        val (pipeline, _, history) = pipeline(sink = sink)

        val outcome = pipeline.submit(scan("ABC"), "d1", "Телефон")

        assertEquals(AckStatus.FAILED, outcome.status)
        assertEquals("окно не приняло ввод", outcome.detail)
        assertEquals("failed", history.recent().single().outcome)
    }

    @Test
    @DisplayName("журнал хранит исходы всех сканов")
    fun zhurnal_hranit_ishody_vseh_skanov() {
        val config = AgentConfig(scan = ScanConfig(minLength = 3))
        val (pipeline, _, history) = pipeline(config)

        pipeline.submit(scan("ok-code", id = "1"), "d1", "Телефон")
        pipeline.submit(scan("x", id = "2"), "d1", "Телефон")

        val records = history.recent()
        assertEquals(2, records.size)
        assertEquals(listOf("filtered", "accepted"), records.map { it.outcome })

        val stats = pipeline.stats()
        assertEquals(1, stats.accepted)
        assertEquals(1, stats.filtered)
    }

    @Test
    @DisplayName("одновременные сканы с двух телефонов не смешиваются")
    fun odnovremennye_skany_s_dvuh_telefonov_ne_smeshivayutsya() {
        val config = AgentConfig(scan = ScanConfig(duplicateWindowMs = 0))
        val sink = SlowSink()
        val history = ScanHistory { 500 }
        val pipeline = ScanPipeline({ config }, { scan, context -> sink.emit(scan, context) }, history)

        val threads = 4
        val perThread = 25
        val ready = CountDownLatch(threads)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)

        repeat(threads) { worker ->
            pool.submit {
                ready.countDown()
                start.await()
                repeat(perThread) { index ->
                    pipeline.submit(scan("code-$worker-$index", id = "$worker-$index"), "d$worker", "Телефон $worker")
                }
            }
        }
        ready.await(5, TimeUnit.SECONDS)
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "обработка не уложилась в таймаут")

        assertEquals(threads * perThread, sink.completed)
        assertEquals(0, sink.overlaps, "вывод на ПК шёл параллельно и символы могли перемешаться")
    }

    /** Отмечает, входили ли два потока в вывод одновременно. */
    private class SlowSink {
        @Volatile
        var inside = false
        var overlaps = 0
        var completed = 0

        @Suppress("UNUSED_PARAMETER")
        fun emit(scan: ru.ruznak.netscan.scan.FormattedScan, context: ru.ruznak.netscan.output.ScanContext) {
            if (inside) overlaps++
            inside = true
            Thread.sleep(1)
            completed++
            inside = false
        }
    }
}
