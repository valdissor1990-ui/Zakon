package ru.zakon.app

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentImportTest {
    @Test
    fun readsUtf8WithinConfiguredLimit() {
        val input = "Закон".toByteArray(Charsets.UTF_8)
        assertEquals(
            "Закон",
            readLimitedText(ByteArrayInputStream(input), input.size)
        )
    }

    @Test
    fun rejectsInputLargerThanConfiguredLimit() {
        val error = runCatching {
            readLimitedText(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)), 4)
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun acceptsInputExactlyAtLimit() {
        assertEquals(
            "1234",
            readLimitedText(ByteArrayInputStream("1234".toByteArray()), 4)
        )
    }

    @Test
    fun allowsOnlyHttpsContentFromThisRepository() {
        assertTrue(isAllowedContentUrl(
            "https://raw.githubusercontent.com/valdissor1990-ui/Zakon/main/content/updates/pack.json"
        ))
    }

    @Test
    fun rejectsHttpForeignHostsAndForeignRepositories() {
        assertFalse(isAllowedContentUrl(
            "http://raw.githubusercontent.com/valdissor1990-ui/Zakon/main/content/updates/pack.json"
        ))
        assertFalse(isAllowedContentUrl(
            "https://example.com/valdissor1990-ui/Zakon/main/content/updates/pack.json"
        ))
        assertFalse(isAllowedContentUrl(
            "https://raw.githubusercontent.com/attacker/Other/main/pack.json"
        ))
    }
}
