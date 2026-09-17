package com.scharwenka.xperiamix.export

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class WavWriterTest {

    @Test
    fun `en-tete WAV 24 bits conforme`() {
        val out = ByteArrayOutputStream()
        val frames = 100L
        WavWriter(out, sampleRate = 48_000, channels = 2, depth = PcmDepth.INT_24, totalFrames = frames)
            .use { writer ->
                writer.write({ 0f }, (frames * 2).toInt())
            }

        val bytes = out.toByteArray()
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val dataSize = frames * 2 * 3

        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals((36 + dataSize).toInt(), header.getInt(4))
        assertEquals("WAVE", String(bytes, 8, 4))
        assertEquals("fmt ", String(bytes, 12, 4))
        assertEquals(16, header.getInt(16))
        assertEquals(1.toShort(), header.getShort(20))          // PCM entier
        assertEquals(2.toShort(), header.getShort(22))          // canaux
        assertEquals(48_000, header.getInt(24))
        assertEquals(48_000 * 6, header.getInt(28))             // octets par seconde
        assertEquals(6.toShort(), header.getShort(32))          // alignement de bloc
        assertEquals(24.toShort(), header.getShort(34))
        assertEquals("data", String(bytes, 36, 4))
        assertEquals(dataSize.toInt(), header.getInt(40))
        assertEquals(44 + dataSize, bytes.size.toLong())
    }

    @Test
    fun `le format flottant est annonce par le code 3`() {
        val out = ByteArrayOutputStream()
        WavWriter(out, 48_000, 2, PcmDepth.FLOAT_32, totalFrames = 10).use {
            it.write({ 0.5f }, 20)
        }
        val header = ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(3.toShort(), header.getShort(20))
        assertEquals(0.5f, header.getFloat(44), 1e-6f)
    }
}
