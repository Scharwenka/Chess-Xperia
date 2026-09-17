package com.scharwenka.xperiamix.export

import android.media.MediaCodec
import android.media.MediaFormat
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Export FLAC via l'encodeur MediaCodec de la plateforme.
 *
 * MediaMuxer ne sait pas empaqueter du FLAC de maniere fiable ; on ecrit donc
 * le conteneur natif a la main, ce qui est trivial :
 *
 *     "fLaC" | en-tete de bloc (0x80 = dernier bloc + type STREAMINFO, 3 octets
 *     de longueur = 34) | STREAMINFO (34 o) | trames FLAC brutes...
 *
 * Le STREAMINFO est fourni par l'encodeur dans csd-0. Il y annonce un nombre
 * total d'echantillons nul (encodage en flux) : on le corrige a la fermeture,
 * sinon les lecteurs n'affichent pas la duree et le "seek" est approximatif.
 * Cela suppose un flux de sortie repositionnable, d'ou l'ecriture dans un
 * fichier temporaire puis la copie finale.
 */
class FlacWriter(
    private val out: OutputStream,
    private val sampleRate: Int,
    private val channels: Int,
    private val compressionLevel: Int = 5,
) : AutoCloseable {

    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC)
    private val info = MediaCodec.BufferInfo()
    private var headerWritten = false
    private var streamInfo: ByteArray? = null
    private var totalFrames = 0L
    private var finished = false

    init {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_FLAC, sampleRate, channels
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, sampleRate * channels * 16)
            setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, compressionLevel)
            setInteger(MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    /** [samples] : echantillons float entrelaces, convertis en 16 bits pour l'encodeur. */
    fun write(samples: FloatBufferView, count: Int) {
        var written = 0
        while (written < count) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) {
                drain(endOfStream = false)
                continue
            }
            val buffer = codec.getInputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN)
            buffer.clear()
            val chunk = minOf(count - written, buffer.capacity() / 2)
            for (i in 0 until chunk) {
                val v = samples[written + i].coerceIn(-1f, 1f)
                buffer.putShort((v * 32_767f).toInt().toShort())
            }
            codec.queueInputBuffer(index, 0, chunk * 2, presentationTimeUs(), 0)
            totalFrames += chunk / channels
            written += chunk
            drain(endOfStream = false)
        }
    }

    private fun presentationTimeUs(): Long = totalFrames * 1_000_000L / sampleRate

    private fun drain(endOfStream: Boolean) {
        while (true) {
            when (val index = codec.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_US else 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val csd = codec.outputFormat.getByteBuffer("csd-0")
                    if (csd != null) streamInfo = ByteArray(csd.remaining()).also { csd.get(it) }
                }

                else -> if (index >= 0) {
                    val buffer = codec.getOutputBuffer(index)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        streamInfo = ByteArray(info.size).also {
                            buffer.position(info.offset); buffer.get(it)
                        }
                    } else if (info.size > 0) {
                        ensureHeader()
                        val bytes = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.get(bytes)
                        out.write(bytes)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun ensureHeader() {
        if (headerWritten) return
        val csd = streamInfo ?: buildStreamInfo()
        // Certains encodeurs fournissent deja la signature "fLaC" dans csd-0.
        if (csd.size >= 4 && csd[0] == 0x66.toByte() && csd[1] == 0x4C.toByte() &&
            csd[2] == 0x61.toByte() && csd[3] == 0x43.toByte()
        ) {
            out.write(csd)
        } else {
            out.write(byteArrayOf(0x66, 0x4C, 0x61, 0x43))            // "fLaC"
            out.write(byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x22))   // dernier bloc, STREAMINFO, 34 o
            out.write(csd, 0, minOf(csd.size, 34))
        }
        headerWritten = true
    }

    /** Repli minimal si l'encodeur n'a pas fourni de STREAMINFO. */
    private fun buildStreamInfo(): ByteArray {
        val b = ByteBuffer.allocate(34).order(ByteOrder.BIG_ENDIAN)
        b.putShort(4096)                       // min block size
        b.putShort(4096)                       // max block size
        b.put(byteArrayOf(0, 0, 0, 0, 0, 0))   // min/max frame size inconnus
        // 20 bits de frequence, 3 bits de canaux, 5 bits de resolution, 36 bits de duree
        val packed = (sampleRate.toLong() shl 44) or
            ((channels - 1).toLong() shl 41) or
            (15L shl 36) or                    // 16 bits - 1
            0L
        for (i in 7 downTo 0) b.put(((packed shr (i * 8)) and 0xFF).toByte())
        b.put(ByteArray(16))                   // MD5 non calcule
        return b.array()
    }

    override fun close() {
        if (!finished) {
            finished = true
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) {
                codec.queueInputBuffer(index, 0, 0, presentationTimeUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            drain(endOfStream = true)
        }
        runCatching { codec.stop() }
        codec.release()
        out.flush()
        out.close()
    }

    companion object {
        private const val TIMEOUT_US = 10_000L

        /**
         * Inscrit le nombre total d'echantillons dans un STREAMINFO deja ecrit.
         * Champ de 36 bits a cheval sur les octets 13 a 17 du bloc.
         */
        fun patchTotalSamples(streamInfo: ByteArray, offset: Int, totalFrames: Long) {
            val i = offset + 13
            streamInfo[i] = ((streamInfo[i].toInt() and 0xF0) or
                ((totalFrames shr 32).toInt() and 0x0F)).toByte()
            streamInfo[i + 1] = ((totalFrames shr 24) and 0xFF).toByte()
            streamInfo[i + 2] = ((totalFrames shr 16) and 0xFF).toByte()
            streamInfo[i + 3] = ((totalFrames shr 8) and 0xFF).toByte()
            streamInfo[i + 4] = (totalFrames and 0xFF).toByte()
        }
    }
}
