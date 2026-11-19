package com.kj7nye.lorafieldops.platform

/** Growable byte buffer: the common-code stand-in for java.io.ByteArrayOutputStream. */
class ByteBuilder(initialCapacity: Int = 64) {
    private var data = ByteArray(initialCapacity.coerceAtLeast(1))
    private var length = 0

    fun size(): Int = length

    fun write(byte: Int) {
        if (length == data.size) data = data.copyOf(data.size * 2)
        data[length++] = byte.toByte()
    }

    fun write(bytes: ByteArray) = bytes.forEach { write(it.toInt()) }

    fun reset() { length = 0 }

    fun toByteArray(): ByteArray = data.copyOf(length)
}

/** ISO-8859-1: each byte becomes the char with the same value, so no byte is lost or altered. */
fun ByteArray.decodeLatin1(): String = buildString(size) { this@decodeLatin1.forEach { append((it.toInt() and 0xFF).toChar()) } }

/** ISO-8859-1 encoding; chars above U+00FF become '?'. */
fun String.encodeLatin1(): ByteArray = ByteArray(length) { i -> this[i].code.let { if (it <= 0xFF) it.toByte() else '?'.code.toByte() } }
