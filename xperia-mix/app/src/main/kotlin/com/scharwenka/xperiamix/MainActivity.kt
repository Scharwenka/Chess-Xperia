package com.scharwenka.xperiamix

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import com.scharwenka.xperiamix.ui.MixerScreen
import com.scharwenka.xperiamix.ui.theme.XperiaMixTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Bord a bord jusque dans la decoupe : sur un 21:9, chaque pixel de
        // largeur gagne est du temps affiche en plus sur la timeline.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Le montage se fait a l'ecran allume ; rien de plus penible qu'une
        // veille au milieu d'un calage.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            XperiaMixTheme {
                Surface(Modifier.fillMaxSize()) {
                    MixerScreen()
                }
            }
        }
    }
}
