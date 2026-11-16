package com.virexdl.app

import org.junit.Assert.*
import org.junit.Test

class LyricsTest {
    @Test fun `fractional seconds are normalized and lines sorted`() {
        val result = parseLrc("[01:02.5]Later\n[00:01.25]Earlier\n[00:02.123]Precise")
        assertEquals(listOf(1250L, 2123L, 62500L), result.map { it.millis })
        assertEquals("Earlier", result.first().text)
    }
    @Test fun `multiple timestamps repeat text and blank metadata are ignored`() {
        val result = parseLrc("[ar:Artist]\n[00:01][00:03.00]Refrain\n[00:04.00]  ")
        assertEquals(listOf(Lyric(1000L, "Refrain"), Lyric(3000L, "Refrain")), result)
    }
    @Test fun `plain text is not treated as synchronized lyrics`() {
        assertTrue(parseLrc("Some unsynchronized lyrics").isEmpty())
    }
}
