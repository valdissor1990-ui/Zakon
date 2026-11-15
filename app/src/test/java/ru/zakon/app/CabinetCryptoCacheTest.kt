package ru.zakon.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class CabinetCryptoCacheTest {
    @Test
    fun overwriteCoversTheWholeFile() {
        val file = File.createTempFile("cabinet-cache-", ".tmp")
        try {
            file.writeBytes(ByteArray(20_000) { 0x5A.toByte() })
            overwriteFileBestEffort(file)
            assertTrue(file.readBytes().all { it == 0.toByte() })
        } finally {
            file.delete()
        }
    }
}
