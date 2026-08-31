package ru.ruznak.netscan.update

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AutoUpdaterTest {
    @Test
    fun `semantic versions compare numerically`() {
        assertTrue(SemanticVersion.parse("v1.10.0")!! > SemanticVersion.parse("1.9.9")!!)
        assertEquals(null, SemanticVersion.parse("main"))
    }

    @Test
    fun `sha file must name the expected archive`() {
        val hash = "a".repeat(64)
        assertEquals(hash, AutoUpdater.parseSha256("$hash  netscan-windows-1.5.0.zip", "netscan-windows-1.5.0.zip"))
        assertFailsWith<IllegalArgumentException> {
            AutoUpdater.parseSha256("$hash  other.zip", "netscan-windows-1.5.0.zip")
        }
    }

    @Test
    fun `sha256 is calculated from package bytes`() {
        val file = Files.createTempFile("netscan-update", ".zip")
        try {
            Files.writeString(file, "abc")
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", AutoUpdater.sha256(file))
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
