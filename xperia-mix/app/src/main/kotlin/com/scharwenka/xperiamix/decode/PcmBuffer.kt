package com.scharwenka.xperiamix.decode

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel

/**
 * Bloc PCM float32 entrelace, accessible a la fois depuis Kotlin (FloatBuffer)
 * et depuis le moteur natif (GetDirectBufferAddress).
 *
 * Deux modes d'allocation :
 *  - [allocateDirect] : hors tas, pour les analyses et les tampons d'export ;
 *  - [mapReadOnly]    : fichier de cache mappe en memoire, pour les pistes
 *    longues. La memoire est geree par le noyau (pages evincables), ce qui evite
 *    de bloquer des centaines de Mo de tas pour un morceau de 10 minutes.
 *
 * Limite d'un mappage unique : 2 Gio, soit ~93 min en 48 kHz stereo float32.
 * Au-dela il faudrait chainer plusieurs mappages (non implemente).
 */
class PcmBuffer private constructor(
    val sampleRate: Int,
    val channels: Int,
    val frameCount: Long,
    val bytes: ByteBuffer,
    private val backingFile: File?,
) : AutoCloseable {

    /** Vue float : `floats[frame * channels + channel]`. */
    val floats: FloatBuffer = bytes.asFloatBuffer()

    val durationMillis: Double get() = frameCount * 1000.0 / sampleRate

    fun sampleAt(frame: Long, channel: Int): Float =
        floats.get((frame * channels + channel).toInt())

    /** Somme mono normalisee : base de toutes les analyses de correlation. */
    fun monoAt(frame: Long): Float {
        val base = (frame * channels).toInt()
        var acc = 0f
        for (c in 0 until channels) acc += floats.get(base + c)
        return acc / channels
    }

    /** Copie mono d'une plage, avec remplissage par zeros hors bornes. */
    fun readMono(dest: FloatArray, startFrame: Long, count: Int) {
        for (i in 0 until count) {
            val f = startFrame + i
            dest[i] = if (f in 0 until frameCount) monoAt(f) else 0f
        }
    }

    /**
     * Le unmap effectif d'un MappedByteBuffer depend du GC : il n'y a rien a
     * liberer explicitement. Le fichier de cache, lui, est conserve pour un
     * rechargement instantane et purge par [com.scharwenka.xperiamix.decode.PcmDecoder.trimCache].
     */
    override fun close() = Unit

    /** Chemin du cache disque, ou null pour un bloc alloue en memoire. */
    val cacheFile: File? get() = backingFile

    companion object {
        const val BYTES_PER_SAMPLE = 4

        fun allocateDirect(sampleRate: Int, channels: Int, frameCount: Long): PcmBuffer {
            val size = frameCount * channels * BYTES_PER_SAMPLE
            require(size <= Int.MAX_VALUE) { "Bloc trop grand pour une allocation unique: $size o" }
            val bytes = ByteBuffer.allocateDirect(size.toInt()).order(ByteOrder.nativeOrder())
            return PcmBuffer(sampleRate, channels, frameCount, bytes, null)
        }

        /** Mappe un fichier .f32 brut produit par [PcmSink]. */
        fun mapReadOnly(file: File, sampleRate: Int, channels: Int): PcmBuffer {
            val frameCount = file.length() / (channels * BYTES_PER_SAMPLE)
            RandomAccessFile(file, "r").use { raf ->
                val mapped = raf.channel
                    .map(FileChannel.MapMode.READ_ONLY, 0, file.length())
                    .order(ByteOrder.nativeOrder())
                return PcmBuffer(sampleRate, channels, frameCount, mapped, file)
            }
        }
    }
}
