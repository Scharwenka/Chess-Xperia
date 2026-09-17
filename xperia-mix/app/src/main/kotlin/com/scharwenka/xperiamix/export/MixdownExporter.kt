package com.scharwenka.xperiamix.export

import android.content.Context
import android.net.Uri
import com.scharwenka.xperiamix.audio.AudioEngine
import java.io.BufferedOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.pow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Rendu du mixage final.
 *
 * Le rendu appelle la meme fonction de mixage C++ que le thread temps reel :
 * memes offsets, memes rampes de gain, meme ordre de sommation. Le fichier
 * exporte est donc bit a bit ce qui a ete monitore, a la quantification finale
 * pres. C'est la raison principale pour laquelle le mixage vit dans le moteur
 * natif plutot que dans deux implementations paralleles.
 *
 * Deux passes :
 *  1. mesure de la crete reelle du mixage (sommer deux pistes a 0 dB depasse
 *     presque toujours 0 dBFS) ;
 *  2. application du gain de normalisation puis ecriture.
 * Le rendu etant une simple lecture memoire, la premiere passe coute quelques
 * centaines de millisecondes pour dix minutes de programme.
 */
class MixdownExporter(
    private val context: Context,
    private val engine: AudioEngine,
) {

    enum class Format(val mimeType: String, val extension: String) {
        WAV_16("audio/wav", "wav"),
        WAV_24("audio/wav", "wav"),
        WAV_FLOAT32("audio/wav", "wav"),
        FLAC("audio/flac", "flac"),
    }

    data class Options(
        val format: Format = Format.WAV_24,
        /** Crete visee apres normalisation, ou null pour ne pas toucher au niveau. */
        val normalizeToDbfs: Float? = -1f,
    )

    suspend fun export(
        destination: Uri,
        totalFrames: Long,
        options: Options,
        onProgress: (Float) -> Unit = {},
    ): Unit = withContext(Dispatchers.Default) {
        require(totalFrames > 0) { "Rien a exporter" }
        val channels = 2
        val chunkFrames = 8192
        val render = ByteBuffer
            .allocateDirect(chunkFrames * channels * 4)
            .order(ByteOrder.nativeOrder())
        val floats = render.asFloatBuffer()
        val view = FloatBufferView { index -> floats.get(index) }

        // --- Passe 1 : mesure de crete ---
        val peak = measurePeak(totalFrames, chunkFrames, channels, render, floats) {
            onProgress(it * 0.25f)
        }
        val gain = options.normalizeToDbfs?.let { target ->
            if (peak < 1e-6f) 1f else (10f.pow(target / 20f) / peak)
        } ?: 1f

        // --- Passe 2 : rendu et ecriture ---
        val scaled = FloatBufferView { index -> floats.get(index) * gain }
        when (options.format) {
            Format.FLAC -> exportFlac(destination, totalFrames, chunkFrames, channels, render, scaled) {
                onProgress(0.25f + it * 0.75f)
            }

            else -> {
                val depth = when (options.format) {
                    Format.WAV_16 -> PcmDepth.INT_16
                    Format.WAV_24 -> PcmDepth.INT_24
                    else -> PcmDepth.FLOAT_32
                }
                val stream = context.contentResolver.openOutputStream(destination)
                    ?: error("Destination illisible")
                WavWriter(BufferedOutputStream(stream, 1 shl 16), engine.sampleRate, channels, depth, totalFrames)
                    .use { writer ->
                        renderAll(
                            totalFrames, chunkFrames, channels, render,
                            onProgress = { onProgress(0.25f + it * 0.75f) },
                            write = { count -> writer.write(scaled, count) },
                        )
                    }
            }
        }
        onProgress(1f)
    }

    private suspend fun measurePeak(
        totalFrames: Long,
        chunkFrames: Int,
        channels: Int,
        render: ByteBuffer,
        floats: java.nio.FloatBuffer,
        onProgress: (Float) -> Unit,
    ): Float {
        var peak = 0f
        engine.beginOfflineRender()
        var frame = 0L
        while (frame < totalFrames) {
            coroutineContext.ensureActive()
            val count = minOf(chunkFrames.toLong(), totalFrames - frame).toInt()
            engine.renderRange(render, frame, count)
            for (i in 0 until count * channels) peak = maxOf(peak, abs(floats.get(i)))
            frame += count
            onProgress((frame.toDouble() / totalFrames).toFloat())
        }
        return peak
    }

    /** Boucle de rendu commune aux deux formats. */
    private suspend fun renderAll(
        totalFrames: Long,
        chunkFrames: Int,
        channels: Int,
        render: ByteBuffer,
        onProgress: (Float) -> Unit,
        write: (Int) -> Unit,
    ) {
        engine.beginOfflineRender()
        var frame = 0L
        while (frame < totalFrames) {
            coroutineContext.ensureActive()
            val count = minOf(chunkFrames.toLong(), totalFrames - frame).toInt()
            engine.renderRange(render, frame, count)
            write(count * channels)
            frame += count
            onProgress((frame.toDouble() / totalFrames).toFloat())
        }
    }

    /**
     * FLAC : l'encodeur annonce une duree nulle dans son STREAMINFO. On ecrit
     * donc dans un fichier temporaire, on y inscrit la duree reelle, puis on
     * copie vers la destination choisie par l'utilisateur.
     */
    private suspend fun exportFlac(
        destination: Uri,
        totalFrames: Long,
        chunkFrames: Int,
        channels: Int,
        render: ByteBuffer,
        samples: FloatBufferView,
        onProgress: (Float) -> Unit,
    ) {
        val temp = File.createTempFile("mixdown", ".flac", context.cacheDir)
        try {
            FlacWriter(
                BufferedOutputStream(temp.outputStream(), 1 shl 16),
                engine.sampleRate,
                channels,
            ).use { writer ->
                renderAll(
                    totalFrames, chunkFrames, channels, render,
                    onProgress = { onProgress(it * 0.95f) },
                    write = { count -> writer.write(samples, count) },
                )
            }

            // STREAMINFO : 4 octets de signature + 4 d'en-tete de bloc.
            RandomAccessFile(temp, "rw").use { raf ->
                val header = ByteArray(42)
                raf.readFully(header)
                FlacWriter.patchTotalSamples(header, offset = 8, totalFrames = totalFrames)
                raf.seek(0)
                raf.write(header)
            }

            context.contentResolver.openOutputStream(destination)?.use { out ->
                temp.inputStream().use { it.copyTo(out, 1 shl 16) }
            } ?: error("Destination illisible")
            onProgress(1f)
        } finally {
            temp.delete()
        }
    }
}
