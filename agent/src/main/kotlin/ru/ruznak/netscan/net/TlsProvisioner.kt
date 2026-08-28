package ru.ruznak.netscan.net

import io.ktor.network.tls.certificates.buildKeyStore
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate

/** Готовое TLS-хранилище и отпечаток сертификата для проверки на телефоне. */
data class TlsMaterial(
    val keyStore: KeyStore,
    val alias: String,
    val password: String,
    val fingerprintSha256: String,
    val subjectAlternativeNames: List<String>,
    val regenerated: Boolean,
)

/**
 * Камера в браузере работает только в защищённом контексте, поэтому агент обязан
 * отдавать HTTPS. Публичный сертификат для адреса 192.168.x.x получить негде,
 * поэтому агент выпускает собственный самоподписанный — один раз, с адресами
 * всех сетевых интерфейсов ПК в SAN.
 *
 * При смене сети (другой Wi-Fi, новый адрес) сертификат перевыпускается
 * автоматически: иначе браузер ругался бы уже не на «неизвестный центр», а на
 * несовпадение имени, и телефон не смог бы подключиться вовсе.
 */
class TlsProvisioner(
    private val keyStorePath: Path,
    private val alias: String = "netscan",
    private val password: String = "netscan",
) {

    fun provision(hosts: List<InetAddress> = LanAddresses.certificateHosts()): TlsMaterial {
        val required = expectedNames(hosts)
        val existing = loadExisting()

        if (existing != null) {
            val covered = subjectAlternativeNames(existing)
            if (covered.containsAll(required)) {
                return material(existing, covered, regenerated = false)
            }
            log.info("Адреса ПК изменились, перевыпускаю TLS-сертификат: {}", required - covered.toSet())
        }

        val keyStore = generate(hosts)
        return material(keyStore, subjectAlternativeNames(keyStore), regenerated = true)
    }

    private fun expectedNames(hosts: List<InetAddress>): Set<String> =
        (hosts.map { it.hostAddress } + DEFAULT_DOMAINS).toSet()

    private fun generate(hosts: List<InetAddress>): KeyStore {
        val keyStore = buildKeyStore {
            certificate(alias) {
                password = this@TlsProvisioner.password
                daysValid = 3650
                keySizeInBits = 2048
                domains = DEFAULT_DOMAINS + hostName()
                ipAddresses = hosts
            }
        }
        Files.createDirectories(keyStorePath.parent)
        Files.newOutputStream(keyStorePath).use { keyStore.store(it, password.toCharArray()) }
        restrictPermissions(keyStorePath)
        return keyStore
    }

    private fun loadExisting(): KeyStore? {
        if (!Files.exists(keyStorePath)) return null
        return runCatching {
            KeyStore.getInstance("PKCS12").apply {
                Files.newInputStream(keyStorePath).use { stream: InputStream -> load(stream, password.toCharArray()) }
            }.takeIf { it.containsAlias(alias) }
        }.getOrElse {
            log.warn("Не удалось прочитать хранилище ключей, будет создано новое: {}", it.message)
            null
        }
    }

    private fun material(keyStore: KeyStore, names: List<String>, regenerated: Boolean) = TlsMaterial(
        keyStore = keyStore,
        alias = alias,
        password = password,
        fingerprintSha256 = fingerprint(keyStore),
        subjectAlternativeNames = names,
        regenerated = regenerated,
    )

    private fun certificate(keyStore: KeyStore): X509Certificate? =
        keyStore.getCertificate(alias) as? X509Certificate

    private fun subjectAlternativeNames(keyStore: KeyStore): List<String> {
        val certificate = certificate(keyStore) ?: return emptyList()
        return runCatching {
            certificate.subjectAlternativeNames.orEmpty().mapNotNull { it.getOrNull(1)?.toString() }
        }.getOrElse { emptyList() }
    }

    private fun fingerprint(keyStore: KeyStore): String {
        val certificate = certificate(keyStore) ?: return ""
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        return digest.joinToString(":") { "%02X".format(it) }
    }

    /** Приватный ключ не должен читаться другими пользователями машины. */
    private fun restrictPermissions(path: Path) {
        runCatching {
            val file = path.toFile()
            file.setReadable(false, false)
            file.setWritable(false, false)
            file.setReadable(true, true)
            file.setWritable(true, true)
        }
    }

    private fun hostName(): String = runCatching { InetAddress.getLocalHost().hostName }.getOrElse { "localhost" }

    private companion object {
        val log = LoggerFactory.getLogger(TlsProvisioner::class.java)
        val DEFAULT_DOMAINS = listOf("localhost")
    }
}
