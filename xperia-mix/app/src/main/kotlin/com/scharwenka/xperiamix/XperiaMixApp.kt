package com.scharwenka.xperiamix

import android.app.Application
import com.scharwenka.xperiamix.decode.PcmDecoder

class XperiaMixApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Le cache PCM decode peut representer plusieurs centaines de Mo ; on le
        // ramene sous un plafond a chaque demarrage plutot que de le laisser
        // grossir jusqu'a ce que le systeme vide le repertoire de cache.
        PcmDecoder.trimCache(this, maxBytes = 2L * 1024 * 1024 * 1024)
    }
}
