package ru.ruznak.netscan

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Удерживает файловую блокировку, чтобы один пользователь не запустил два агента на одних портах. */
class SingleInstanceLock private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {

    override fun close() {
        runCatching { lock.release() }
        runCatching { channel.close() }
    }

    companion object {
        fun acquire(file: Path): SingleInstanceLock? {
            Files.createDirectories(file.parent)
            val channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            } catch (_: Throwable) {
                channel.close()
                throw
            }
            if (lock == null) {
                channel.close()
                return null
            }
            return SingleInstanceLock(channel, lock)
        }
    }
}
