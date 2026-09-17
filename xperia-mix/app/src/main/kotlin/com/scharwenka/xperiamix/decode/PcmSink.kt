package com.scharwenka.xperiamix.decode

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Ecrit un flux float32 entrelace dans un fichier de cache, par blocs.
 *
 * On passe par un fichier plutot que par un tableau qui grossit : la taille
 * exacte n'est pas connue avant la fin du decodage (les durees annoncees par
 * les conteneurs sont approximatives), et un `ByteArray` qui double de taille
 * ferait culminer la memoire au double du besoin reel sur une piste de 10 min.
 */
class PcmSink(val file: File) : Closeable {

    private val stream = FileOutputStream(file)
    private val staging = ByteBuffer.allocateDirect(STAGING_BYTES).order(ByteOrder.nativeOrder())

    var sampleCount: Long = 0
        private set

    fun write(samples: FloatArray, count: Int) {
        var written = 0
        while (written < count) {
            val chunk = minOf(count - written, staging.remaining() / 4)
            if (chunk == 0) {
                flushStaging()
                continue
            }
            for (i in 0 until chunk) staging.putFloat(samples[written + i])
            written += chunk
        }
        sampleCount += count
    }

    private fun flushStaging() {
        staging.flip()
        while (staging.hasRemaining()) stream.channel.write(staging)
        staging.clear()
    }

    override fun close() {
        flushStaging()
        stream.close()
    }

    private companion object {
        const val STAGING_BYTES = 1 shl 16
    }
}
