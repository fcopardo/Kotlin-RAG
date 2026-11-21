package rag.extract

import java.io.File

/** Reads a text file as UTF-8, falling back to Latin-1 for files that are not valid UTF-8. A leading BOM is dropped. */
object DocumentCharset {
    fun read(file: File): String = decode(file.readBytes())

    fun decode(bytes: ByteArray): String {
        val text = try {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            String(bytes, Charsets.ISO_8859_1)
        }
        return text.removePrefix("﻿")
    }
}
