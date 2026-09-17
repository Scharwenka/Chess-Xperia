package com.scharwenka.xperiamix.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Caracteristiques de la sortie audio courante.
 *
 * Le point critique est [sampleRate] : ouvrir AAudio a une frequence differente
 * de celle du HAL force l'insertion d'un reechantillonneur dans le serveur
 * audio, ce qui fait perdre le chemin rapide (MMAP/EXCLUSIVE) et multiplie la
 * latence par trois ou quatre. Tout le pipeline est donc aligne sur cette
 * valeur : les pistes sont reechantillonnees une fois pour toutes au chargement.
 *
 * Sur Xperia 1 V la sortie mixee est a 48 kHz ; le jack 3,5 mm et son ampli
 * dedie acceptent en plus 96 et 192 kHz, ce que [hiResRates] expose pour un
 * mode "Hi-Res" optionnel (meilleure resolution, latence plus elevee).
 */
data class DeviceAudioProfile(
    val sampleRate: Int,
    val framesPerBurst: Int,
    val outputName: String,
    val hiResRates: List<Int>,
) {
    val burstMillis: Double get() = framesPerBurst * 1000.0 / sampleRate
    val hiResCapable: Boolean get() = hiResRates.any { it > 48_000 }

    companion object {
        fun query(context: Context): DeviceAudioProfile {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val rate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
                ?: 48_000
            val burst = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()
                ?: 192

            val active = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            } ?: am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull()

            return DeviceAudioProfile(
                sampleRate = rate,
                framesPerBurst = burst,
                outputName = active?.productName?.toString() ?: "sortie par defaut",
                hiResRates = active?.sampleRates?.toList()?.sorted() ?: listOf(rate),
            )
        }
    }
}
