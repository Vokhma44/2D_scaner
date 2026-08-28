package ru.ruznak.netscan

import ru.ruznak.netscan.config.AgentConfig
import ru.ruznak.netscan.config.CliArgs
import ru.ruznak.netscan.config.ConfigStore
import ru.ruznak.netscan.config.SinkKind
import ru.ruznak.netscan.config.SuffixKey
import ru.ruznak.netscan.config.TypingMode
import ru.ruznak.netscan.console.QrRenderer
import ru.ruznak.netscan.protocol.DeviceInfo
import ru.ruznak.netscan.protocol.DeviceStatus
import ru.ruznak.netscan.security.DeviceRegistry
import ru.ruznak.netscan.security.PairingService
import ru.ruznak.netscan.security.Tokens
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import org.junit.jupiter.api.DisplayName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingServiceTest {

    private fun home(): Path = createTempDirectory("netscan-pairing")

    @Test
    @DisplayName("код сравнивается без учёта регистра и дефисов")
    fun kod_sravnivaetsya_bez_ucheta_registra_i_defisov() {
        val pairing = PairingService(home().resolve("pairing.txt"), { 0 })

        assertTrue(pairing.verify(pairing.code))
        assertTrue(pairing.verify(pairing.code.lowercase()))
        assertTrue(pairing.verify(pairing.code.replace("-", "")))
        assertFalse(pairing.verify("WRONG-CODE-0000"))
    }

    @Test
    @DisplayName("код переживает перезапуск агента")
    fun kod_perezhivaet_perezapusk_agenta() {
        val dir = home()
        val first = PairingService(dir.resolve("pairing.txt"), { 0 })
        val second = PairingService(dir.resolve("pairing.txt"), { 0 })

        assertEquals(first.code, second.code)
    }

    @Test
    @DisplayName("ротация выпускает новый код и отменяет старый")
    fun rotaciya_vypuskaet_novyy_kod_i_otmenyaet_staryy() {
        val pairing = PairingService(home().resolve("pairing.txt"), { 0 })
        val old = pairing.code

        val fresh = pairing.rotate()

        assertNotEquals(old, fresh)
        assertFalse(pairing.verify(old))
        assertTrue(pairing.verify(fresh))
    }

    @Test
    @DisplayName("просроченный код перестаёт приниматься")
    fun prosrochennyy_kod_perestaet_prinimatsya() {
        val clock = TestClock()
        val pairing = PairingService(home().resolve("pairing.txt"), { 10 }, clock::millis)

        assertTrue(pairing.verify(pairing.code))
        clock.advance(11 * 60_000)
        assertFalse(pairing.verify(pairing.code))
        assertTrue(pairing.isExpired())
    }
}

class DeviceRegistryTest {

    private fun home(): Path = createTempDirectory("netscan-devices")

    @Test
    @DisplayName("устройство находится по своему секрету сессии")
    fun ustroystvo_nahoditsya_po_svoemu_sekretu_sessii() {
        val registry = DeviceRegistry(home().resolve("devices.json"))

        val (device, token) = registry.register(DeviceInfo(name = "Склад-1", platform = "Android"), requireApproval = false)

        assertEquals(DeviceStatus.ACTIVE, device.status)
        assertEquals(device.id, registry.authenticate(token)?.id)
        assertNull(registry.authenticate("подделанный-токен"))
    }

    @Test
    @DisplayName("сырой секрет сессии на диск не попадает")
    fun syroy_sekret_sessii_na_disk_ne_popadaet() {
        val dir = home()
        val registry = DeviceRegistry(dir.resolve("devices.json"))

        val (_, token) = registry.register(DeviceInfo(), requireApproval = false)

        val stored = Files.readString(dir.resolve("devices.json"))
        assertFalse(stored.contains(token), "секрет сессии сохранён в открытом виде")
        assertTrue(stored.contains(Tokens.sha256(token)))
    }

    @Test
    @DisplayName("режим подтверждения переводит устройство в ожидание")
    fun rezhim_podtverzhdeniya_perevodit_ustroystvo_v_ozhidanie() {
        val registry = DeviceRegistry(home().resolve("devices.json"))

        val (device, _) = registry.register(DeviceInfo(), requireApproval = true)
        assertEquals(DeviceStatus.PENDING_APPROVAL, device.status)

        assertEquals(DeviceStatus.ACTIVE, registry.approve(device.id)?.status)
    }

    @Test
    @DisplayName("отзыв и удаление закрывают доступ")
    fun otzyv_i_udalenie_zakryvayut_dostup() {
        val registry = DeviceRegistry(home().resolve("devices.json"))
        val (device, token) = registry.register(DeviceInfo(), requireApproval = false)

        registry.revoke(device.id)
        assertEquals(DeviceStatus.REVOKED, registry.authenticate(token)?.status)

        assertTrue(registry.forget(device.id))
        assertNull(registry.authenticate(token))
        assertFalse(registry.forget(device.id))
    }

    @Test
    @DisplayName("список устройств переживает перезапуск")
    fun spisok_ustroystv_perezhivaet_perezapusk() {
        val dir = home()
        val first = DeviceRegistry(dir.resolve("devices.json"))
        val (device, token) = first.register(DeviceInfo(name = "Приёмка"), requireApproval = false)
        first.touch(device.id, scans = 3)
        first.flush()

        val second = DeviceRegistry(dir.resolve("devices.json"))
        val restored = second.authenticate(token)

        assertNotNull(restored)
        assertEquals("Приёмка", restored.name)
        assertEquals(3, restored.scanCount)
    }
}

class ConfigStoreTest {

    @Test
    @DisplayName("настройки сохраняются и читаются обратно")
    fun nastroyki_sohranyayutsya_i_chitayutsya_obratno() {
        val file = createTempDirectory("netscan-config").resolve("config.json")
        val store = ConfigStore.open(file)

        store.update { it.copy(output = it.output.copy(prefix = "%", suffix = SuffixKey.TAB)) }

        val reopened = ConfigStore.open(file)
        assertEquals("%", reopened.config.output.prefix)
        assertEquals(SuffixKey.TAB, reopened.config.output.suffix)
    }

    @Test
    @DisplayName("битый файл конфигурации не роняет агент")
    fun bityy_fayl_konfiguracii_ne_ronyaet_agent() {
        val dir = createTempDirectory("netscan-config")
        val file = dir.resolve("config.json")
        Files.writeString(file, "{ это не json")

        val store = ConfigStore.open(file)

        assertEquals(AgentConfig(), store.config)
        assertTrue(Files.exists(dir.resolve("config.json.broken")))
    }

    @Test
    @DisplayName("неизвестные поля из новой версии игнорируются")
    fun neizvestnye_polya_iz_novoy_versii_ignoriruyutsya() {
        val file = createTempDirectory("netscan-config").resolve("config.json")
        Files.writeString(file, """{"output":{"prefix":"A","поле-из-будущего":1}}""")

        assertEquals("A", ConfigStore.open(file).config.output.prefix)
    }
}

class CliArgsTest {

    @Test
    @DisplayName("ключи командной строки перекрывают настройки")
    fun klyuchi_komandnoy_stroki_perekryvayut_nastroyki() {
        val args = CliArgs.parse(arrayOf("--port", "9443", "--suffix", "tab", "--typing", "clipboard", "--dedup", "0"))

        val config = args.overrides(AgentConfig())

        assertEquals(9443, config.network.httpsPort)
        assertEquals(SuffixKey.TAB, config.output.suffix)
        assertEquals(TypingMode.CLIPBOARD, config.output.typingMode)
        assertEquals(0, config.scan.duplicateWindowMs)
    }

    @Test
    @DisplayName("указание файла или вебхука само включает нужный приёмник")
    fun ukazanie_fayla_ili_vebhuka_samo_vklyuchaet_nuzhnyy_priemnik() {
        val args = CliArgs.parse(arrayOf("--file", "/tmp/scans.txt", "--webhook", "http://localhost/scan"))

        val config = args.overrides(AgentConfig())

        assertTrue(config.output.sinks.containsAll(listOf(SinkKind.FILE, SinkKind.WEBHOOK)))
        assertEquals("/tmp/scans.txt", config.output.filePath)
    }

    @Test
    @DisplayName("неизвестная опция сообщается пользователю")
    fun neizvestnaya_opciya_soobschaetsya_polzovatelyu() {
        val error = runCatching { CliArgs.parse(arrayOf("--чего-то-нет")) }.exceptionOrNull()
        assertTrue(error?.message?.contains("Неизвестная опция") == true)
    }

    @Test
    @DisplayName("совпадающие порты отклоняются")
    fun sovpadayuschie_porty_otklonyayutsya() {
        val config = CliArgs.parse(arrayOf("--port", "8080")).overrides(AgentConfig())
        assertTrue(runCatching { config.network.validated() }.isFailure)
    }
}

class QrRendererTest {

    @Test
    @DisplayName("QR для терминала рисуется блоками и содержит рамку тишины")
    fun qr_dlya_terminala_risuetsya_blokami_i_soderzhit_ramku_tishiny() {
        val rendered = QrRenderer.toTerminal(
            "https://192.168.1.10:8443/?p=ABCD-EFGH-IJKL",
            style = QrRenderer.TerminalStyle.BLOCKS,
        )
        val lines = rendered.trimEnd('\n').split('\n')

        assertTrue(lines.size > 12)
        assertTrue(lines.all { it.length == lines.first().length }, "строки QR разной длины")
        assertTrue(rendered.any { it == '█' || it == '▀' || it == '▄' })
        assertTrue(lines.first().isBlank(), "нет зоны тишины сверху — камера может не прочитать код")
    }

    @Test
    @DisplayName("векторный QR пригоден для вставки в консоль на ПК")
    fun vektornyy_qr_prigoden_dlya_vstavki_v_konsol_na_pk() {
        val svg = QrRenderer.toSvg("https://192.168.1.10:8443/?p=ABCD-EFGH-IJKL", sizePx = 320)

        assertTrue(svg.startsWith("<svg"))
        assertTrue(svg.contains("""width="320""""))
        assertTrue(svg.contains("<rect"))
    }
}

class QrTerminalStyleTest {

    @Test
    @DisplayName("для консоли без Unicode выбирается ASCII-вариант QR")
    fun dlya_konsoli_bez_unicode_vybiraetsya_ascii_variant() {
        assertEquals(
            QrRenderer.TerminalStyle.ASCII,
            QrRenderer.detectStyle(Charset.forName("US-ASCII")),
        )
        assertEquals(
            QrRenderer.TerminalStyle.BLOCKS,
            QrRenderer.detectStyle(Charsets.UTF_8),
        )
    }

    @Test
    @DisplayName("ASCII-вариант QR состоит только из печатных символов ASCII")
    fun ascii_variant_sostoit_tolko_iz_pechatnyh_simvolov() {
        val rendered = QrRenderer.toTerminal(
            "https://192.168.1.10:8443/?p=ABCD-EFGH-IJKL",
            style = QrRenderer.TerminalStyle.ASCII,
        )

        assertTrue(rendered.all { it == '\n' || it == ' ' || it == '#' })
        val lines = rendered.trimEnd('\n').split('\n')
        // Каждый модуль занимает два знакоместа, поэтому строка вдвое шире матрицы.
        assertTrue(lines.all { it.length == lines.first().length })
        assertTrue(lines.first().length == lines.size * 2)
    }
}
