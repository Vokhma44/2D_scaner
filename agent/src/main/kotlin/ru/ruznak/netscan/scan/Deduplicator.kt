package ru.ruznak.netscan.scan

import java.util.concurrent.ConcurrentHashMap

/**
 * Подавление повторов — та же функция, что «same code delay» у настоящих сканеров:
 * камера видит один код десятки раз в секунду, а ввести его нужно один раз.
 *
 * Дополнительно хранит идентификаторы уже принятых сканов: повторная доставка
 * из офлайн-очереди телефона не приводит к двойному вводу.
 */
class Deduplicator(
    private val windowMillis: () -> Long,
    private val idMemory: Int = 512,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lastSeen = ConcurrentHashMap<String, Long>()
    private val processedIds = LinkedHashSet<String>()

    /** true — скан новый и его нужно обработать. */
    fun accept(deviceId: String, scanId: String, code: String): Boolean {
        synchronized(processedIds) {
            if (!processedIds.add(scanId)) return false
            while (processedIds.size > idMemory) {
                val oldest = processedIds.iterator()
                oldest.next()
                oldest.remove()
            }
        }

        val window = windowMillis()
        if (window <= 0) return true

        val key = "$deviceId $code"
        val now = clock()
        val previous = lastSeen.put(key, now)
        if (previous != null && now - previous < window) {
            // Повтор внутри окна. Отметка времени обновлена выше, поэтому непрерывное
            // наведение камеры на один код не «прорвётся» по истечении таймаута.
            return false
        }
        pruneExpired(now, window)
        return true
    }

    private fun pruneExpired(now: Long, window: Long) {
        if (lastSeen.size < 256) return
        lastSeen.entries.removeIf { now - it.value > window * 10 }
    }

    fun clear() {
        lastSeen.clear()
        synchronized(processedIds) { processedIds.clear() }
    }
}
