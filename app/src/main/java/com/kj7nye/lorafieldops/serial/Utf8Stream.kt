package com.kj7nye.lorafieldops.serial

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * Decodes a byte stream that arrives in arbitrary chunks (BLE notifications can be
 * 20 bytes) as UTF-8, holding back a multi-byte character split across chunks until
 * the rest of it arrives. Not thread-safe: one instance per stream.
 */
class Utf8StreamDecoder {
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var leftover: ByteArray = ByteArray(0)

    fun decode(bytes: ByteArray): String {
        val input = ByteBuffer.allocate(leftover.size + bytes.size).put(leftover).put(bytes)
        input.flip()
        val out = CharBuffer.allocate(input.remaining() + 1)
        decoder.decode(input, out, false)
        leftover = ByteArray(input.remaining()).also { input.get(it) }
        out.flip()
        return out.toString()
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
        partial.delete(0, lastNl + 1)
        return complete.split('\n').map { it.trimEnd('\r') }.filter { it.isNotEmpty() }
    }
}
