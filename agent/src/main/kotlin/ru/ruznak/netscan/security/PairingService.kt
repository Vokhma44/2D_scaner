package ru.ruznak.netscan.security

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicReference

/**
 * Код сопряжения. Живёт в файле рядом с конфигом, поэтому один раз наклеенный
 * на монитор QR-код продолжает работать после перезапуска агента.
 */
class PairingService(
    private val file: Path?,
    private val ttlMinutes: () -> Int,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Issued(val code: String, val issuedAt: Long)

    private val state = AtomicReference<Issued>()

    init {
        val restored = file?.takeIf { Files.exists(it) }?.let { path ->
            runCatching {
                val parts = Files.readString(path).trim().split(':', limit = 2)
                Issued(parts[0], parts.getOrNull(1)?.toLongOrNull() ?: clock())
            }.getOrNull()
        }
        state.set(restored ?: issueNew())
    }

    val code: String get() = state.get().code

    fun rotate(): String = issueNew().code

    fun isExpired(): Boolean {
        val ttl = ttlMinutes()
        if (ttl <= 0) return false
        return clock() - state.get().issuedAt > ttl * 60_000L
    }

    /** Проверка предъявленного кода: регистр и дефисы игнорируются. */
    fun verify(candidate: String): Boolean {
        if (isExpired()) return false
        return Tokens.constantTimeEquals(
            Tokens.normalizePairingCode(candidate),
            Tokens.normalizePairingCode(code),
        )
    }

    private fun issueNew(): Issued {
        val issued = Issued(Tokens.pairingCode(), clock())
        state.set(issued)
        file?.let { path ->
            runCatching {
                Files.createDirectories(path.parent)
                val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
                Files.writeString(tmp, "${issued.code}:${issued.issuedAt}")
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        return issued
    }
}
