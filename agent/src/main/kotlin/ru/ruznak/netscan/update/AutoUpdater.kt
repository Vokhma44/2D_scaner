package ru.ruznak.netscan.update

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import ru.ruznak.netscan.AGENT_VERSION
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.name

class AutoUpdater(
    private val home: Path,
    private val http: HttpClient,
    private val checkIntervalMs: Long = 6 * 60 * 60 * 1_000L,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(AutoUpdater::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        if (!System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true)) return
        if (System.getenv("NETSCAN_DISABLE_AUTO_UPDATE") == "1") return
        scope.launch {
            delay(60_000)
            while (isActive) {
                runCatching { checkAndInstall() }
                    .onFailure { log.warn("Не удалось проверить обновление агента: {}", it.message) }
                delay(checkIntervalMs)
            }
        }
    }

    internal suspend fun checkAndInstall() {
        val releaseResponse = http.get(RELEASE_API) { header(HttpHeaders.UserAgent, "RUZNAK-netscan/$AGENT_VERSION") }
        check(releaseResponse.status.isSuccess()) { "GitHub Releases: HTTP ${releaseResponse.status.value}" }
        val release = releaseResponse.body<GitHubRelease>()
        val current = SemanticVersion.parse(AGENT_VERSION) ?: error("некорректная текущая версия $AGENT_VERSION")
        val available = SemanticVersion.parse(release.tagName) ?: return
        if (release.draft || release.prerelease || available <= current) return

        val version = release.tagName.removePrefix("v")
        val zipName = "netscan-windows-$version.zip"
        val hashName = "netscan-windows-$version.sha256"
        val zipAsset = release.assets.singleOrNull { it.name == zipName } ?: error("в релизе нет $zipName")
        val hashAsset = release.assets.singleOrNull { it.name == hashName } ?: error("в релизе нет $hashName")
        require(zipAsset.url.startsWith(RELEASE_DOWNLOAD_PREFIX) && hashAsset.url.startsWith(RELEASE_DOWNLOAD_PREFIX)) {
            "релиз содержит недоверенный адрес загрузки"
        }

        val updateDir = home.resolve("updates").resolve(version)
        Files.createDirectories(updateDir)
        val zip = updateDir.resolve(zipName)
        val hashFile = updateDir.resolve(hashName)
        download(zipAsset.url, zip)
        download(hashAsset.url, hashFile)
        val expected = parseSha256(Files.readString(hashFile), zipName)
        val actual = sha256(zip)
        check(actual.equals(expected, ignoreCase = true)) { "SHA-256 обновления не совпадает" }

        val installDir = Path.of(System.getProperty("jpackage.app-path", "")).parent
            ?: error("не удалось определить папку установки")
        val script = installDir.resolve("update-netscan.ps1")
        check(Files.isRegularFile(script)) { "не найден update-netscan.ps1" }
        ProcessBuilder(
            "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.toString(),
            "-Package", zip.toString(), "-InstallDir", installDir.toString(),
            "-ExpectedVersion", version, "-ParentPid", ProcessHandle.current().pid().toString(),
        ).inheritIO().start()
        log.info("Подготовлено автоматическое обновление агента {} -> {}", AGENT_VERSION, version)
        System.exit(0)
    }

    private suspend fun download(url: String, target: Path) {
        val response = http.get(url) { header(HttpHeaders.UserAgent, "RUZNAK-netscan/$AGENT_VERSION") }
        check(response.status.isSuccess()) { "загрузка ${target.name}: HTTP ${response.status.value}" }
        Files.write(target, response.body<ByteArray>())
    }

    override fun close() = scope.cancel()

    companion object {
        const val RELEASE_API = "https://api.github.com/repos/Vokhma44/2D_scaner/releases/latest"
        const val RELEASE_DOWNLOAD_PREFIX = "https://github.com/Vokhma44/2D_scaner/releases/download/"

        internal fun parseSha256(text: String, expectedFile: String): String {
            val parts = text.trim().split(Regex("\\s+"), limit = 2)
            require(parts.size == 2 && parts[0].matches(Regex("[0-9a-fA-F]{64}"))) { "некорректный SHA-256 файл" }
            require(parts[1].trimStart('*').trim() == expectedFile) { "SHA-256 относится к другому файлу" }
            return parts[0]
        }

        internal fun sha256(path: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

@Serializable
internal data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
internal data class GitHubAsset(
    val name: String,
    @SerialName("browser_download_url") val url: String,
)
