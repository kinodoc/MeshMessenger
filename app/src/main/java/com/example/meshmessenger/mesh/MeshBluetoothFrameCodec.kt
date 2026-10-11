package com.example.meshmessenger.mesh

import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Length-prefixed RFCOMM frame codec shared by the reader and writer.
 * Rejects malformed sizes before allocating the payload buffer.
 */
internal object MeshBluetoothFrameCodec {
    const val MAX_FRAME = 1024 * 1024
    private const val HEADER_SIZE = 4

    fun encode(payload: ByteArray): ByteArray {
        require(payload.isNotEmpty()) { "empty_frame" }
        require(payload.size <= MAX_FRAME) { "frame_too_large=${payload.size}" }
        return ByteBuffer.allocate(HEADER_SIZE + payload.size)
            .putInt(payload.size)
            .put(payload)
            .array()
    }

    fun read(input: InputStream): ByteArray {
        val header = ByteArray(HEADER_SIZE)
        readFully(input, header)
        val length = ByteBuffer.wrap(header).int
        require(length in 1..MAX_FRAME) { "bad_frame_length=${length}" }
        return ByteArray(length).also { readFully(input, it) }
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val count = input.read(target, offset, target.size - offset)
            if (count < 0) throw EOFException("truncated_frame")
            offset += count
        }
    }
}
