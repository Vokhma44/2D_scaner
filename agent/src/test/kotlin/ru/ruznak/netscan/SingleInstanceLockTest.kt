package ru.ruznak.netscan

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SingleInstanceLockTest {
    @Test
    fun `second agent cannot acquire the same lock`() {
        val file = createTempDirectory("netscan-lock").resolve("agent.lock")
        val first = assertNotNull(SingleInstanceLock.acquire(file))
        try {
            assertNull(SingleInstanceLock.acquire(file))
        } finally {
            first.close()
        }
        assertNotNull(SingleInstanceLock.acquire(file)).close()
    }
}
