package com.example.meshmessenger.mesh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.nio.ByteBuffer

class MeshBluetoothFrameCodecTest {
    @Test
    fun encodesAndReadsFrame() {
        val payload = "hello mesh".toByteArray(Charsets.UTF_8)
        val encoded = MeshBluetoothFrameCodec.encode(payload)

        assertEquals(payload.size, ByteBuffer.wrap(encoded, 0, 4).int)
        assertArrayEquals(payload, MeshBluetoothFrameCodec.read(ByteArrayInputStream(encoded)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyFrame() {
        MeshBluetoothFrameCodec.encode(ByteArray(0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedPayload() {
        MeshBluetoothFrameCodec.encode(ByteArray(MeshBluetoothFrameCodec.MAX_FRAME + 1))
    }

    @Test(expected = EOFException::class)
    fun rejectsTruncatedHeader() {
        MeshBluetoothFrameCodec.read(ByteArrayInputStream(byteArrayOf(0, 0, 0)))
    }

    @Test(expected = EOFException::class)
    fun rejectsTruncatedPayload() {
        val encoded = ByteBuffer.allocate(6).putInt(4).put(byteArrayOf(1, 2)).array()
        MeshBluetoothFrameCodec.read(ByteArrayInputStream(encoded))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeLength() {
        val header = ByteBuffer.allocate(4).putInt(-1).array()
        MeshBluetoothFrameCodec.read(ByteArrayInputStream(header))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsLengthAboveLimitBeforeAllocating() {
        val header = ByteBuffer.allocate(4).putInt(MeshBluetoothFrameCodec.MAX_FRAME + 1).array()
        MeshBluetoothFrameCodec.read(ByteArrayInputStream(header))
    }
}
