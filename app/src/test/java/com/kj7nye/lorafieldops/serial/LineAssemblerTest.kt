package com.kj7nye.lorafieldops.serial

import org.junit.Assert.assertEquals
import org.junit.Test

class LineAssemblerTest {

    @Test
    fun multiByteCharSplitAcrossChunks() {
        val bytes = "beacon rate 120 → 60 s, 23°C\n".toByteArray(Charsets.UTF_8)
        val arrow = "beacon rate 120 ".toByteArray(Charsets.UTF_8).size  // first byte of →
        val a = LineAssembler()
        assertEquals(emptyList<String>(), a.feed(bytes.copyOfRange(0, arrow + 1)))
        assertEquals(emptyList<String>(), a.feed(bytes.copyOfRange(arrow + 1, arrow + 2)))
        assertEquals(listOf("beacon rate 120 → 60 s, 23°C"), a.feed(bytes.copyOfRange(arrow + 2, bytes.size)))
    }

    @Test
    fun everyByteSeparately() {
        val text = "ä→😀\r\nok\n"
        val a = LineAssembler()
        val lines = text.toByteArray(Charsets.UTF_8).flatMap { a.feed(byteArrayOf(it)) }
        assertEquals(listOf("ä→😀", "ok"), lines)
    }

    @Test
    fun crLfAndBlankLines() {
        val a = LineAssembler()
        assertEquals(listOf("one", "two"), a.feed("one\r\n\r\ntwo\npart".toByteArray()))
        assertEquals(listOf("partial"), a.feed("ial\r\n".toByteArray()))
    }

    @Test
    fun decoderPassesThroughWholeChunks() {
        val d = Utf8StreamDecoder()
        assertEquals("abc", d.decode("abc".toByteArray()))
        val euro = "€".toByteArray(Charsets.UTF_8)
        assertEquals("", d.decode(euro.copyOfRange(0, 2)))
        assertEquals("€x", d.decode(euro.copyOfRange(2, 3) + "x".toByteArray()))
    }
}
