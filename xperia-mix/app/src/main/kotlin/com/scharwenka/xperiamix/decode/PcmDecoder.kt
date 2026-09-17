package com.scharwenka.xperiamix.decode

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

data class DecodedTrack(val pcm: PcmBuffer, val displayName: String, val sourceSampleRate: Int)

/**
 * Decodage MP3 / WAV / FLAC / AAC / OGG vers PCM float32 via MediaExtractor +
 * MediaCodec (decodeurs materiels du SoC quand ils existent).
 *
 * Trois points souvent negliges sont traites ici :
 *  1. la sortie est demandee en float (KEY_PCM_ENCODING) : sur un FLAC 24 bits,
 *     passer par du 16 bits couterait 48 dB de plage dynamique ;
 *  2. le delai d'encodeur (AAC : 2112 trames, MP3 : 529+) est retire, sinon les
 *     deux pistes sont decalees d'une constante differente selon leur format,
 *     ce qui fausse le calage automatique ;
 *  3. le resultat est mis en cache sur disque : recharger une piste deja
 *     decodee est instantane.
 */
class PcmDecoder(private val context: Context) {

    private val cacheRoot: File = File(context.cacheDir, "pcm").apply { mkdirs() }

    suspend fun decode(
        uri: Uri,
        targetSampleRate: Int,
        onProgress: (Float) -> Unit = {},
    ): DecodedTrack = withContext(Dispatchers.IO) {
        val displayName = queryDisplayName(uri)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: error("Aucune piste audio dans $displayName")

            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val sourceRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val cacheFile = File(cacheRoot, "${cacheKey(uri)}_${sourceRate}_$channels.f32")
            if (!cacheFile.exists() || cacheFile.length() == 0L) {
                decodeToFile(extractor, inputFormat, cacheFile, onProgress)
            } else {
                onProgress(1f)
            }

            val decoded = PcmBuffer.mapReadOnly(cacheFile, sourceRate, channels)
            val pcm = if (sourceRate == targetSampleRate) {
                decoded
            } else {
                // Les deux pistes doivent partager une base de temps unique :
                // tout est ramene au taux natif du peripherique de sortie.
                val resampledFile =
                    File(cacheRoot, "${cacheKey(uri)}_${targetSampleRate}_${channels}_rs.f32")
                if (!resampledFile.exists() || resampledFile.length() == 0L) {
                    Resampler.resample(decoded, targetSampleRate, resampledFile, onProgress)
                }
                decoded.close()
                PcmBuffer.mapReadOnly(resampledFile, targetSampleRate, channels)
            }
            DecodedTrack(pcm, displayName, sourceRate)
        } finally {
            extractor.release()
        }
    }

    private suspend fun decodeToFile(
        extractor: MediaExtractor,
        inputFormat: MediaFormat,
        target: File,
        onProgress: (Float) -> Unit,
    ) {
        val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
        val durationUs = inputFormat.optLong(MediaFormat.KEY_DURATION, 0L)
        val channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var encoderDelayFrames = inputFormat.optInt("encoder-delay", 0)
        val encoderPaddingFrames = inputFormat.optInt("encoder-padding", 0)

        // On demande explicitement du float ; si le decodeur refuse, on convertit.
        inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)

        val codec = MediaCodec.createDecoderByType(mime)
        val temp = File(target.absolutePath + ".part")
        val sink = PcmSink(temp)
        var scratch = FloatArray(16384)
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

        try {
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                coroutineContext.ensureActive()

                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        pcmEncoding = codec.outputFormat
                            .optInt(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    else -> if (outIndex >= 0) {
                        if (info.size > 0) {
                            val out = codec.getOutputBuffer(outIndex)!!
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            val needed = samplesIn(info.size, pcmEncoding)
                            if (scratch.size < needed) scratch = FloatArray(needed)
                            val produced = toFloats(out, pcmEncoding, scratch)

                            // Suppression du delai d'encodeur, echantillon par echantillon.
                            var start = 0
                            if (encoderDelayFrames > 0) {
                                val skip = minOf(encoderDelayFrames * channels, produced)
                                start = skip
                                encoderDelayFrames -= skip / channels
                            }
                            if (produced > start) {
                                System.arraycopy(scratch, start, scratch, 0, produced - start)
                                sink.write(scratch, produced - start)
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        if (durationUs > 0) {
                            onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                        }
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            sink.close()
        }

        // Le padding de fin est retire par troncature du fichier.
        val paddingBytes = encoderPaddingFrames.toLong() * channels * PcmBuffer.BYTES_PER_SAMPLE
        if (paddingBytes in 1 until temp.length()) {
            java.io.RandomAccessFile(temp, "rw").use { it.setLength(temp.length() - paddingBytes) }
        }
        check(temp.renameTo(target)) { "Impossible de finaliser le cache PCM" }
    }

    private fun queryDisplayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0)
            }
        return uri.lastPathSegment ?: "piste"
    }

    private fun cacheKey(uri: Uri): String =
        Integer.toHexString(uri.toString().hashCode()) + "_" + uri.toString().length

    companion object {
        private const val TIMEOUT_US = 10_000L

        /**
         * Purge le cache PCM des fichiers les plus anciens jusqu'a repasser
         * sous [maxBytes]. Une minute de stereo 48 kHz en float32 pese 23 Mo :
         * sans plafond, quelques sessions suffisent a saturer le stockage.
         */
        fun trimCache(context: Context, maxBytes: Long) {
            val root = File(context.cacheDir, "pcm")
            val files = root.listFiles()?.sortedBy { it.lastModified() } ?: return
            var total = files.sumOf { it.length() }
            for (file in files) {
                if (total <= maxBytes) break
                total -= file.length()
                file.delete()
            }
        }

        private fun samplesIn(bytes: Int, encoding: Int): Int = when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> bytes / 4
            AudioFormat.ENCODING_PCM_32BIT -> bytes / 4
            ENCODING_PCM_24BIT_PACKED -> bytes / 3
            else -> bytes / 2
        }

        /** Normalise n'importe quelle sortie de decodeur vers du float [-1, 1]. */
        private fun toFloats(src: ByteBuffer, encoding: Int, dest: FloatArray): Int {
            src.order(ByteOrder.LITTLE_ENDIAN)
            return when (encoding) {
                AudioFormat.ENCODING_PCM_FLOAT -> {
                    val fb = src.asFloatBuffer()
                    val n = fb.remaining()
                    fb.get(dest, 0, n)
                    n
                }

                AudioFormat.ENCODING_PCM_32BIT -> {
                    val ib = src.asIntBuffer()
                    val n = ib.remaining()
                    for (i in 0 until n) dest[i] = ib.get(i) / 2147483648f
                    n
                }

                ENCODING_PCM_24BIT_PACKED -> {
                    val n = src.remaining() / 3
                    for (i in 0 until n) {
                        val b0 = src.get(src.position() + i * 3).toInt() and 0xFF
                        val b1 = src.get(src.position() + i * 3 + 1).toInt() and 0xFF
                        val b2 = src.get(src.position() + i * 3 + 2).toInt()  // signe conserve
                        dest[i] = ((b2 shl 16) or (b1 shl 8) or b0) / 8388608f
                    }
                    n
                }

                else -> {
                    val sb = src.asShortBuffer()
                    val n = sb.remaining()
                    for (i in 0 until n) dest[i] = sb.get(i) / 32768f
                    n
                }
            }
        }

        /** AudioFormat.ENCODING_PCM_24BIT_PACKED (API 31+), reference en dur pour la lisibilite. */
        private const val ENCODING_PCM_24BIT_PACKED = 21
    }
}

private fun MediaFormat.optLong(key: String, fallback: Long): Long =
    if (containsKey(key)) getLong(key) else fallback

private fun MediaFormat.optInt(key: String, fallback: Int): Int =
    if (containsKey(key)) getInteger(key) else fallback
