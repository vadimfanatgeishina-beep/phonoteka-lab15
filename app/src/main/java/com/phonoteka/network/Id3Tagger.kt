package com.phonoteka.network

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes ID3v2.3 tags (TPE1 = artist, TIT2 = title) to an MP3 file.
 *
 * Uses UTF-16 encoding for Cyrillic support. The tag is prepended to the file
 * (ID3v2 tags always go at the beginning). If an existing ID3v2 tag is present,
 * it is replaced; an existing ID3v1 tag at the end is left untouched.
 *
 * This is a minimal, dependency-free writer — enough for the Phonoteka use case
 * (artist + title on every downloaded track).
 */
object Id3Tagger {

    private const val ID3_HEADER = "ID3"
    private const val VERSION_MAJOR: Byte = 3   // ID3v2.3
    private const val VERSION_MINOR: Byte = 0

    /**
     * Writes [artist] and [title] as ID3v2.3 tags to [file].
     * If the file already has an ID3v2 tag, it is replaced atomically.
     */
    fun tag(file: File, artist: String, title: String) {
        val newTag = buildTag(artist, title)
        val existingSize = readExistingTagSize(file)

        if (existingSize > 0) {
            // Replace: read file after existing tag, prepend new tag.
            val raf = RandomAccessFile(file, "rw")
            raf.use {
                val bodyLen = (it.length() - existingSize).toInt()
                val body = ByteArray(bodyLen)
                it.seek(existingSize.toLong())
                it.readFully(body)
                it.seek(0)
                it.write(newTag)
                it.write(body)
                // Truncate if new tag is smaller than old
                if (newTag.size < existingSize) {
                    it.setLength(newTag.size + bodyLen.toLong())
                }
            }
        } else {
            // Prepend: read entire file, write new tag + original content.
            val original = file.readBytes()
            file.writeBytes(newTag + original)
        }
    }

    /** Build the complete ID3v2.3 tag as a byte array. */
    private fun buildTag(artist: String, title: String): ByteArray {
        val tit2 = buildTextFrame("TIT2", title)
        val tpe1 = buildTextFrame("TPE1", artist)
        val frames = tit2 + tpe1
        return buildHeader(frames.size) + frames
    }

    /**
     * Build a text frame: [frameId] (4) + size (4) + flags (2) + encoding (1) + UTF-16LE BOM + text + null.
     */
    private fun buildTextFrame(frameId: String, text: String): ByteArray {
        // Content: encoding byte (01 = UTF-16) + BOM (FF FE) + UTF-16LE text + null terminator (00 00)
        val utf16 = text.toByteArray(Charsets.UTF_16LE)
        val content = ByteArray(1 + 2 + utf16.size + 2)
        content[0] = 0x01                              // encoding: UTF-16
        content[1] = 0xFF.toByte()                      // BOM: little-endian
        content[2] = 0xFE.toByte()
        System.arraycopy(utf16, 0, content, 3, utf16.size)
        // null terminator already zeroed

        val idBytes = frameId.toByteArray(Charsets.US_ASCII)
        val sizeBytes = intToBytesBE(content.size)
        val flags = byteArrayOf(0, 0)
        return idBytes + sizeBytes + flags + content
    }

    /** Build the 10-byte ID3v2 header. [framesSize] is the total size of all frames. */
    private fun buildHeader(framesSize: Int): ByteArray {
        val header = ByteArray(10)
        System.arraycopy(ID3_HEADER.toByteArray(Charsets.US_ASCII), 0, header, 0, 3)
        header[3] = VERSION_MAJOR
        header[4] = VERSION_MINOR
        header[5] = 0  // flags
        // Synchsafe integer: each byte uses 7 bits
        header[6] = ((framesSize shr 21) and 0x7F).toByte()
        header[7] = ((framesSize shr 14) and 0x7F).toByte()
        header[8] = ((framesSize shr 7) and 0x7F).toByte()
        header[9] = (framesSize and 0x7F).toByte()
        return header
    }

    /** Convert a non-negative Int to a 4-byte big-endian array. */
    private fun intToBytesBE(value: Int): ByteArray =
        byteArrayOf(
            ((value shr 24) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte()
        )

    /** Read the size of an existing ID3v2 tag at the start of [file], or 0. */
    private fun readExistingTagSize(file: File): Int {
        if (file.length() < 10) return 0
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(10)
            raf.readFully(header)
            if (header[0] != 'I'.code.toByte() ||
                header[1] != 'D'.code.toByte() ||
                header[2] != '3'.code.toByte()) return 0
            // Synchsafe integer
            val size = ((header[6].toInt() and 0x7F) shl 21) or
                       ((header[7].toInt() and 0x7F) shl 14) or
                       ((header[8].toInt() and 0x7F) shl 7) or
                       (header[9].toInt() and 0x7F)
            return 10 + size
        }
    }
}
