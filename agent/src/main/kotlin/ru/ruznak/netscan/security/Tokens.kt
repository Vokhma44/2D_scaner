package ru.ruznak.netscan.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Генерация и сравнение секретов сопряжения. */
object Tokens {

    private val random = SecureRandom()

    /** Алфавит без похожих символов (0/O, 1/I/l): код иногда вводят руками. */
    private const val PAIRING_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    /** Короткий код сопряжения вида XXXX-XXXX-XXXX. */
    fun pairingCode(groups: Int = 3, groupSize: Int = 4): String =
        (0 until groups).joinToString("-") {
            buildString(groupSize) {
                repeat(groupSize) { append(PAIRING_ALPHABET[random.nextInt(PAIRING_ALPHABET.length)]) }
            }
        }

    /** Длинный секрет сессии: живёт в localStorage телефона. */
    fun sessionToken(bytes: Int = 32): String {
        val buffer = ByteArray(bytes)
        random.nextBytes(buffer)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer)
    }

    fun deviceId(): String = sessionToken(9)

    fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Сравнение за постоянное время: не даёт подобрать секрет по времени ответа. */
    fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    /** Нормализация введённого руками кода: регистр и дефисы значения не имеют. */
    fun normalizePairingCode(raw: String): String =
        raw.uppercase().filter { it.isLetterOrDigit() }
}
