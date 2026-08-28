package ru.ruznak.netscan.net

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.origin
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import ru.ruznak.netscan.AgentState
import ru.ruznak.netscan.api.netscanModule
import ru.ruznak.netscan.config.NetworkConfig

/** Пара запущенных серверов: основной HTTPS и вспомогательный HTTP-редирект. */
class RunningServers(
    private val https: EmbeddedServer<*, *>,
    private val httpRedirect: EmbeddedServer<*, *>?,
) {
    fun stop(gracePeriodMillis: Long = 500, timeoutMillis: Long = 2000) {
        httpRedirect?.stop(gracePeriodMillis, timeoutMillis)
        https.stop(gracePeriodMillis, timeoutMillis)
    }
}

/**
 * Поднимает HTTPS-сервер с самоподписанным сертификатом и, если порт задан,
 * маленький HTTP-сервер рядом. Второй нужен ровно для одного: оператор набрал
 * адрес без «https://», и браузер должен не показать ошибку, а перекинуть на
 * защищённый порт — без HTTPS камера в браузере не запустится.
 */
fun startServers(state: AgentState, tls: TlsMaterial, network: NetworkConfig): RunningServers {
    network.validated()

    val https = embeddedServer(
        Netty,
        configure = {
            sslConnector(
                keyStore = tls.keyStore,
                keyAlias = tls.alias,
                keyStorePassword = { tls.password.toCharArray() },
                privateKeyPassword = { tls.password.toCharArray() },
            ) {
                host = network.bindAddress
                port = network.httpsPort
            }
        },
        module = { netscanModule(state) },
    ).start(wait = false)

    val redirect = if (network.httpPort > 0) {
        embeddedServer(
            Netty,
            configure = {
                connector {
                    host = network.bindAddress
                    port = network.httpPort
                }
            },
            module = { redirectModule(network.httpsPort) },
        ).start(wait = false)
    } else {
        null
    }

    return RunningServers(https, redirect)
}

private fun Application.redirectModule(httpsPort: Int) {
    routing {
        get("/{...}") {
            val host = call.request.origin.serverHost
            val target = "https://$host:$httpsPort${call.request.local.uri}"
            call.respondRedirect(target, permanent = false)
        }
        get("/health") {
            call.respondText("redirect only", status = HttpStatusCode.OK)
        }
    }
}
