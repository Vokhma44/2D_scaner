package ru.ruznak.netscan

import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import ru.ruznak.netscan.api.netscanModule
import ru.ruznak.netscan.config.AgentConfig
import ru.ruznak.netscan.config.OutputConfig
import ru.ruznak.netscan.config.ScanConfig
import ru.ruznak.netscan.config.SecurityConfig
import ru.ruznak.netscan.config.SinkKind
import ru.ruznak.netscan.config.SuffixKey
import ru.ruznak.netscan.protocol.Ack
import ru.ruznak.netscan.protocol.AckStatus
import ru.ruznak.netscan.protocol.DeviceInfo
import ru.ruznak.netscan.protocol.PairRequest
import ru.ruznak.netscan.protocol.PairResponse
import ru.ruznak.netscan.protocol.ProtocolJson
import ru.ruznak.netscan.protocol.ScanMessage
import ru.ruznak.netscan.protocol.ServerMessage
import ru.ruznak.netscan.protocol.Welcome
import kotlin.io.path.createTempDirectory
import org.junit.jupiter.api.DisplayName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApiTest {

    private fun ApplicationTestBuilder.jsonClient() = createClient {
        install(ContentNegotiation) { json(ProtocolJson) }
        install(WebSockets)
    }

    private fun state(config: AgentConfig = defaultConfig()): Pair<AgentState, RecordingSink> =
        testState(createTempDirectory("netscan-api"), config)

    private fun defaultConfig() = AgentConfig(
        output = OutputConfig(sinks = listOf(SinkKind.KEYBOARD), suffix = SuffixKey.ENTER),
        scan = ScanConfig(duplicateWindowMs = 0),
    )

    private suspend fun ApplicationTestBuilder.pair(state: AgentState): PairResponse {
        val response = jsonClient().post("/api/pair") {
            contentType(ContentType.Application.Json)
            setBody(ProtocolJson.encodeToString(PairRequest(state.pairing.code, DeviceInfo(name = "Тестовый телефон"))))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        return ProtocolJson.decodeFromString<PairResponse>(response.bodyAsText())
    }

    @Test
    @DisplayName("служебный эндпоинт отвечает без авторизации")
    fun sluzhebnyy_endpoint_otvechaet_bez_avtorizacii() = testApplication {
        val (agent, _) = state()
        application { netscanModule(agent) }

        val body = jsonClient().get("/api/health").bodyAsText()

        assertTrue(body.contains("netscan"))
    }

    @Test
    @DisplayName("сопряжение с неверным кодом отклоняется")
    fun sopryazhenie_s_nevernym_kodom_otklonyaetsya() = testApplication {
        val (agent, _) = state()
        application { netscanModule(agent) }

        val response = jsonClient().post("/api/pair") {
            contentType(ContentType.Application.Json)
            setBody(ProtocolJson.encodeToString(PairRequest("ZZZZ-ZZZZ-ZZZZ", DeviceInfo())))
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(agent.devices.devices().isEmpty())
    }

    @Test
    @DisplayName("перебор кода сопряжения блокируется")
    fun perebor_koda_sopryazheniya_blokiruetsya() = testApplication {
        val (agent, _) = state(defaultConfig().copy(security = SecurityConfig(pairingAttemptsPerMinute = 3)))
        application { netscanModule(agent) }
        val client = jsonClient()

        repeat(3) {
            val attempt = client.post("/api/pair") {
                contentType(ContentType.Application.Json)
                setBody(ProtocolJson.encodeToString(PairRequest("WRON-GCOD-E$it", DeviceInfo())))
            }
            assertEquals(HttpStatusCode.Forbidden, attempt.status)
        }

        val blocked = client.post("/api/pair") {
            contentType(ContentType.Application.Json)
            setBody(ProtocolJson.encodeToString(PairRequest(agent.pairing.code, DeviceInfo())))
        }
        assertEquals(HttpStatusCode.TooManyRequests, blocked.status)
    }

    @Test
    @DisplayName("сессия проверяется по токену")
    fun sessiya_proveryaetsya_po_tokenu() = testApplication {
        val (agent, _) = state()
        application { netscanModule(agent) }
        val paired = pair(agent)

        val ok = jsonClient().get("/api/session") { header("Authorization", "Bearer ${paired.sessionToken}") }
        assertEquals(HttpStatusCode.OK, ok.status)
        assertTrue(ok.bodyAsText().contains("Тестовый телефон"))

        val denied = jsonClient().get("/api/session") { header("Authorization", "Bearer поддельный") }
        assertEquals(HttpStatusCode.Unauthorized, denied.status)
    }

    @Test
    @DisplayName("скан из телефона доходит до ПК и подтверждается")
    fun skan_iz_telefona_dohodit_do_pk_i_podtverzhdaetsya() = testApplication {
        val (agent, sink) = state()
        application { netscanModule(agent) }
        val paired = pair(agent)

        jsonClient().webSocket("/api/ws?s=${paired.sessionToken}") {
            assertTrue(receiveMessage() is Welcome)

            send(Frame.Text(clientScan("scan-1", "0104607012345678")))
            val ack = receiveMessage() as Ack

            assertEquals(AckStatus.ACCEPTED, ack.status)
            assertEquals("scan-1", ack.id)
        }

        assertEquals(listOf("0104607012345678"), sink.texts)
        assertEquals(1, agent.devices.byId(paired.deviceId)?.scanCount)
    }

    @Test
    @DisplayName("повторная отправка того же скана не вводит код дважды")
    fun povtornaya_otpravka_togo_zhe_skana_ne_vvodit_kod_dvazhdy() = testApplication {
        val (agent, sink) = state()
        application { netscanModule(agent) }
        val paired = pair(agent)

        jsonClient().webSocket("/api/ws?s=${paired.sessionToken}") {
            receiveMessage()

            send(Frame.Text(clientScan("scan-7", "ABC123")))
            assertEquals(AckStatus.ACCEPTED, (receiveMessage() as Ack).status)

            send(Frame.Text(clientScan("scan-7", "ABC123")))
            assertEquals(AckStatus.DUPLICATE, (receiveMessage() as Ack).status)
        }

        assertEquals(1, sink.texts.size)
    }

    @Test
    @DisplayName("подключение с чужим токеном закрывается")
    fun podklyuchenie_s_chuzhim_tokenom_zakryvaetsya() = testApplication {
        val (agent, sink) = state()
        application { netscanModule(agent) }

        val result = runCatching {
            jsonClient().webSocket("/api/ws?s=нет-такой-сессии") {
                send(Frame.Text(clientScan("scan-1", "CODE")))
                incoming.receive()
            }
        }

        assertTrue(result.isFailure || sink.texts.isEmpty())
        assertTrue(sink.texts.isEmpty())
    }

    @Test
    @DisplayName("консоль отдаёт состояние и принимает новые настройки")
    fun konsol_otdaet_sostoyanie_i_prinimaet_novye_nastroyki() = testApplication {
        val (agent, _) = state()
        application { netscanModule(agent) }
        val client = jsonClient()

        val before = client.get("/api/console/state")
        assertEquals(HttpStatusCode.OK, before.status)
        assertTrue(before.bodyAsText().contains(agent.pairing.code))

        val updated = ProtocolJson.encodeToString(
            AgentConfig.serializer(),
            agent.config.copy(output = agent.config.output.copy(prefix = "PRE:")),
        )
        val save = client.post("/api/console/config") {
            header("Origin", "https://localhost:8443")
            contentType(ContentType.Application.Json)
            setBody(updated)
        }

        assertEquals(HttpStatusCode.OK, save.status)
        assertEquals("PRE:", agent.config.output.prefix)
    }

    @Test
    @DisplayName("консоль умеет отправить тестовый код на ввод")
    fun konsol_umeet_otpravit_testovyy_kod_na_vvod() = testApplication {
        val (agent, sink) = state()
        application { netscanModule(agent) }

        val response = jsonClient().post("/api/console/test") {
            header("Origin", "https://localhost:8443")
            contentType(ContentType.Application.Json)
            setBody("""{"code":"TEST-42"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf("TEST-42"), sink.texts)
    }

    @Test
    @DisplayName("изменение консоли без локального Origin отклоняется")
    fun izmenenie_konsoli_bez_lokalnogo_origin_otklonyaetsya() = testApplication {
        val (agent, _) = state()
        application { netscanModule(agent) }

        val noOrigin = jsonClient().post("/api/console/history/clear")
        assertEquals(HttpStatusCode.Forbidden, noOrigin.status)

        val foreignOrigin = jsonClient().post("/api/console/history/clear") {
            header("Origin", "https://evil.example")
        }
        assertEquals(HttpStatusCode.Forbidden, foreignOrigin.status)

        val localOrigin = jsonClient().post("/api/console/history/clear") {
            header("Origin", "https://localhost:8443")
        }
        assertEquals(HttpStatusCode.OK, localOrigin.status)
    }

    @Test
    @DisplayName("отозванное устройство теряет доступ")
    fun otozvannoe_ustroystvo_teryaet_dostup() = testApplication {
        val (agent, sink) = state()
        application { netscanModule(agent) }
        val paired = pair(agent)
        agent.devices.revoke(paired.deviceId)

        val result = runCatching {
            jsonClient().webSocket("/api/ws?s=${paired.sessionToken}") {
                send(Frame.Text(clientScan("scan-1", "CODE")))
                incoming.receive()
            }
        }

        assertTrue(result.isFailure || sink.texts.isEmpty())
        assertTrue(sink.texts.isEmpty())
    }

    @Test
    @DisplayName("страница мобильного клиента отдаётся по корневому адресу")
    fun stranica_mobilnogo_klienta_otdaetsya_po_kornevomu_adresu() = testApplication {
        val (agent, _) = state()
        application { netscanModule(agent) }

        val response = jsonClient().get("/")

        // Клиент собирается Vite и попадает в ресурсы агента; без него телефону
        // нечего открыть, поэтому корневой адрес обязан отдавать страницу.
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("netscan"))
    }

    private fun clientScan(id: String, code: String): String = ProtocolJson.encodeToString(
        ru.ruznak.netscan.protocol.ClientMessage.serializer(),
        ScanMessage(id = id, code = code, format = "qr_code", scannedAt = 0),
    )

    private suspend fun io.ktor.websocket.WebSocketSession.receiveMessage(): ServerMessage = withTimeout(5_000) {
        while (true) {
            val frame = incoming.receive()
            if (frame is Frame.Text) {
                return@withTimeout ProtocolJson.decodeFromString(ServerMessage.serializer(), frame.readText())
            }
        }
        error("сообщение не получено")
    }
}
