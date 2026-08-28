package ru.ruznak.netscan.security

import java.util.concurrent.ConcurrentHashMap

/**
 * Скользящее окно на ключ (обычно IP): защищает код сопряжения от перебора.
 * Реализация без таймеров — старые отметки отбрасываются при обращении.
 */
class RateLimiter(
    private val permitsPerWindow: Int,
    private val windowMillis: Long = 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val hits = ConcurrentHashMap<String, ArrayDeque<Long>>()

    fun tryAcquire(key: String): Boolean {
        if (permitsPerWindow <= 0) return true
        val now = clock()
        val bucket = hits.computeIfAbsent(key) { ArrayDeque() }
        synchronized(bucket) {
            while (bucket.isNotEmpty() && now - bucket.first() >= windowMillis) bucket.removeFirst()
            if (bucket.size >= permitsPerWindow) return false
            bucket.addLast(now)
            return true
        }
    }

    fun reset(key: String) {
        hits.remove(key)
    }

    /** Убирает пустые корзины, чтобы карта не росла от разовых обращений. */
    fun evictStale() {
        val now = clock()
        hits.entries.removeIf { (_, bucket) ->
            synchronized(bucket) {
                while (bucket.isNotEmpty() && now - bucket.first() >= windowMillis) bucket.removeFirst()
                bucket.isEmpty()
            }
        }
    }
}
