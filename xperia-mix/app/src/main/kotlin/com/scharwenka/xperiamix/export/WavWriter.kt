package com.scharwenka.xperiamix.export

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/** Resolution demandee a l'export. */
enum class PcmDepth(val bits: Int, val isFloat: Boolean) {
    INT_16(16, false),
    INT_24(24, false),
    FLOAT_32(32, true),
}

/**
 * Ecriture WAV en flux.
 *
 * L'en-tete est ecrit complet des le depart : le nombre de trames est connu
 * avant le rendu (longueur de la timeline), ce qui evite d'avoir a revenir en
 * arriere - impossible sur un OutputStream fourni par le Storage Access
 * Framework.
 *
 * En 16 bits, un dither TPDF d'un LSB est ajoute. Sans lui, la troncature d'un
 * mixage float produit une distorsion correlee au signal, tres audible sur les
 * fins de reverberation ; le dither la transforme en un souffle constant a
 * -93 dBFS.
 */
class WavWriter(
    private val out: OutputStream,
    private val sampleRate: Int,
    private val channels: Int,
    private val depth: PcmDepth,
    totalFrames: Long,
) : AutoCloseable {

    private val bytesPerSample = depth.bits / 8
    private val random = Random(Random.nextInt())
    private val scratch = ByteBuffer.allocate(CHUNK_FRAMES * channels * 4).order(ByteOrder.LITTLE_ENDIAN)

    init {
        val dataSize = totalFrames * channels * bytesPerSample
        require(dataSize + 44 <= 0xFFFFFFFFL) {
            "Depassement des 4 Gio du format WAV : utiliser FLAC ou decouper l'export"
        }
        writeHeader(dataSize)
    }

    /** [samples] contient `count` echantillons float entrelaces. */
    fun write(samples: FloatBufferView, count: Int) {
        var written = 0
        while (written < count) {
            val chunk = minOf(count - written, scratch.capacity() / bytesPerSample)
            scratch.clear()
            for (i in 0 until chunk) {
                putSample(samples[written + i])
            }
            scratch.flip()
            out.write(scratch.array(), 0, scratch.limit())
            written += chunk
        }
    }

    private fun putSample(value: Float) {
        when (depth) {
            PcmDepth.FLOAT_32 -> scratch.putFloat(value)

            PcmDepth.INT_24 -> {
                val v = quantize(value, 8_388_607f)
                scratch.put((v and 0xFF).toByte())
                scratch.put(((v shr 8) and 0xFF).toByte())
                scratch.put(((v shr 16) and 0xFF).toByte())
            }

            PcmDepth.INT_16 -> {
                // Dither TPDF : difference de deux uniformes = un LSB crete a crete.
                val dither = (random.nextFloat() - random.nextFloat()) / 32768f
                scratch.putShort(quantize(value + dither, 32_767f).toShort())
            }
        }
    }

    private fun quantize(value: Float, scale: Float): Int =
        (value.coerceIn(-1f, 1f) * scale).toInt()

    private fun writeHeader(dataSize: Long) {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        val blockAlign = channels * bytesPerSample
        header.put("RIFF".toByteArray())
        header.putInt((36 + dataSize).toInt())
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(if (depth.isFloat) 3 else 1)     // 3 = IEEE float, 1 = PCM entier
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(sampleRate * blockAlign)
        header.putShort(blockAlign.toShort())
        header.putShort(depth.bits.toShort())
        header.put("data".toByteArray())
        header.putInt(dataSize.toInt())
        out.write(header.array())
    }

    override fun close() {
        out.flush()
        out.close()
    }

    private companion object {
        const val CHUNK_FRAMES = 4096
    }
}

/** Petite abstraction pour ecrire depuis un FloatBuffer ou un FloatArray. */
fun interface FloatBufferView {
    operator fun get(index: Int): Float
}
