package com.kj7nye.lorafieldops.serial

/**
 * Decodes a byte stream that arrives in arbitrary chunks (BLE notifications can be
 * 20 bytes) as UTF-8, holding back a multi-byte character split across chunks until
 * the rest of it arrives. Malformed bytes become U+FFFD. Not thread-safe: one
 * instance per stream.
 */
class Utf8StreamDecoder {
    private var leftover: ByteArray = ByteArray(0)

    fun decode(bytes: ByteArray): String {
        val input = if (leftover.isEmpty()) bytes else leftover + bytes
        // Hold back an incomplete sequence at the very end; everything before it is complete
        // (or malformed, which decodeToString replaces).
        val cut = incompleteTailStart(input)
        leftover = input.copyOfRange(cut, input.size)
        return input.decodeToString(0, cut)
    }

    /** Index where a truncated (but so far valid) multi-byte sequence starts, or [b].size. */
    private fun incompleteTailStart(b: ByteArray): Int {
        // A sequence is at most 4 bytes, so its lead byte is within the last 3.
        for (back in 1..minOf(3, b.size)) {
            val i = b.size - back
            val v = b[i].toInt() and 0xFF
            if (v and 0xC0 == 0x80) continue          // continuation byte: keep looking for the lead
            val need = when {
                v and 0xE0 == 0xC0 -> 2
                v and 0xF0 == 0xE0 -> 3
                v and 0xF8 == 0xF0 -> 4
                else -> return b.size                 // ASCII or invalid lead: nothing to hold back
            }
            return if (back < need) i else b.size
        }
        return b.size
    }
}

/**
 * Turns a chunked byte stream into complete text lines: UTF-8 decoded across chunk
 * boundaries, split on LF, trailing CR removed, blank lines dropped. A partial last
 * line is kept until its LF arrives.
 */
class LineAssembler {
    private val decoder = Utf8StreamDecoder()
    private val partial = StringBuilder()

    fun feed(bytes: ByteArray): List<String> {
        partial.append(decoder.decode(bytes))
        val lastNl = partial.lastIndexOf("\n")
        if (lastNl < 0) return emptyList()
        val complete = partial.substring(0, lastNl)
        partial.deleteRange(0, lastNl + 1)
        return complete.split('\n').map { it.trimEnd('\r') }.filter { it.isNotEmpty() }
    }
}
