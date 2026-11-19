package io.github.jeepnjonny.lorafieldops.platform

import io.github.jeepnjonny.lorafieldops.serial.Utf8StreamDecoder
import io.github.jeepnjonny.lorafieldops.ui.formatMhz
import io.github.jeepnjonny.lorafieldops.ui.parseWebAddress
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Common-code replacements for JVM APIs (String.format, java.net.URI, CharsetDecoder, ...). */
class PortableHelpersTest {

    @Test
    fun mhzHasThreeDecimals() {
        assertEquals("433.775", formatMhz(433_775_000))
        assertEquals("915.000", formatMhz(915_000_000))
        assertEquals("868.100", formatMhz(868_099_600))   // rounds to the nearest kHz
        assertEquals("0.007", formatMhz(7_000))
    }

    @Test
    fun webAddressParts() {
        val a = parseWebAddress("https://user@Example.org:8443/path?q=1#frag")!!
        assertEquals("https", a.scheme)
        assertEquals("user", a.userInfo)
        assertEquals("Example.org", a.host)
        assertEquals("q=1", a.query)
        assertEquals("frag", a.fragment)

        val plain = parseWebAddress("https://apps.k7swi.org/aprs-coverage")!!
        assertNull(plain.userInfo); assertNull(plain.query); assertNull(plain.fragment)

        assertEquals("", parseWebAddress("apps.k7swi.org/x")!!.scheme)
        assertEquals("", parseWebAddress("https:no-slashes")!!.host)
        assertNull(parseWebAddress("https://exa mple.org"))
        assertNull(parseWebAddress("https://example.org/\"x\""))
    }

    @Test
    fun utf8DecoderReplacesMalformedBytesAndHoldsSplitChars() {
        val d = Utf8StreamDecoder()
        assertEquals("a�b", d.decode(byteArrayOf('a'.code.toByte(), 0xFF.toByte(), 'b'.code.toByte())))
        val emoji = "📡".encodeToByteArray()   // 4 bytes
        assertEquals("", d.decode(emoji.copyOfRange(0, 3)))
        assertEquals("📡", d.decode(emoji.copyOfRange(3, 4)))
    }

    @Test
    fun latin1RoundTripsEveryByte() {
        val all = ByteArray(256) { it.toByte() }
        assertContentEquals(all, all.decodeLatin1().encodeLatin1())
    }

    @Test
    fun byteBuilderGrows() {
        val b = ByteBuilder(2)
        repeat(100) { b.write(it) }
        assertEquals(100, b.size())
        assertEquals(99, b.toByteArray().last().toInt())
        b.reset()
        assertEquals(0, b.toByteArray().size)
    }
}
