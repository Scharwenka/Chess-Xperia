# XperiaMix

Application Android native pour charger **deux pistes audio**, les **visualiser**,
les **caler temporellement** et les **mixer**, optimisée pour le Sony Xperia 1 V.

> L'architecture, les choix de bibliothèques, les algorithmes et les budgets de
> performance sont détaillés dans **[ARCHITECTURE.md](ARCHITECTURE.md)**.

## Fonctionnalités

- **Chargement** MP3, WAV, FLAC, AAC, OGG/Opus via le Storage Access Framework
  (aucune permission de stockage), décodage en PCM float32 avec cache disque.
- **Visualisation** : deux formes d'onde côte à côte ou superposées, zoom de la
  vue d'ensemble jusqu'à l'échantillon, défilement, règle temporelle, curseur de
  lecture synchronisé sur la cadence d'affichage.
- **Calage manuel** : glissement au doigt, curseur, pas à pas jusqu'à la trame
  (20,8 µs à 48 kHz), retour haptique au passage par zéro.
- **Calage automatique** : corrélation croisée en trois étages (enveloppes de
  transitoires → GCC-PHAT → corrélation à pleine fréquence), avec indice de
  confiance, détection de polarité inversée et estimation de dérive d'horloge.
- **Lecture** : les deux pistes sont sommées dans un unique callback AAudio, donc
  **sans dérive possible** ; gain par piste, mute, solo, inversion de polarité.
- **Export** WAV 16/24/32 bits flottant (dither TPDF en 16 bits) ou FLAC, rendu
  par la même fonction de mixage que le monitoring.

## Structure

```
xperia-mix/
├── ARCHITECTURE.md                    document de conception
└── app/src/
    ├── main/cpp/                      moteur temps réel
    │   ├── MixerEngine.{h,cpp}        callback Oboe, mixage, rampes de gain
    │   └── jni_bridge.cpp             frontière JNI
    ├── main/kotlin/com/scharwenka/xperiamix/
    │   ├── audio/                     façade Kotlin, profil du périphérique
    │   ├── decode/                    MediaCodec → PCM, rééchantillonneur sinc
    │   ├── waveform/                  pyramide de crêtes (mip-map)
    │   ├── align/                     FFT, DSP, calage automatique
    │   ├── export/                    WAV, FLAC, rendu du mixage
    │   ├── model/                     état de session
    │   └── ui/                        Compose : timeline, tranches, transport
    └── test/kotlin/                   tests JVM (DSP, pyramide, WAV)
```

Points d'entrée pour la lecture du code :

| Sujet | Fichier |
|---|---|
| Rendu de la forme d'onde | `ui/WaveformPainter.kt` |
| Plan de travail et gestes | `ui/WaveformTimeline.kt` |
| Réglage du décalage | `ui/OffsetControl.kt` |
| Algorithme de calage | `align/TrackAligner.kt` |
| Mixage temps réel | `cpp/MixerEngine.cpp` |

## Compilation

```bash
./gradlew :app:assembleDebug     # APK
./gradlew :app:testDebugUnitTest # tests DSP / pyramide / WAV (JVM, sans appareil)
```

Prérequis : Android SDK 35, NDK 27, CMake 3.22. Oboe est récupéré depuis Maven
sous forme d'AAR *prefab* — aucun sous-module à cloner.

## État

- **Compilé et testé** : la moitié pure-JVM du code (DSP, calage, pyramide de
  crêtes, rééchantillonneur, écriture WAV, modèle) compile sans erreur ni
  avertissement, et ses 13 tests unitaires passent.
- **Non compilé** : tout ce qui dépend du SDK ou du NDK Android — interface
  Compose, décodage MediaCodec, encodage FLAC, façade audio et moteur C++ —
  faute de SDK dans l'environnement où le projet a été écrit.
- **À valider sur appareil** : latence réelle, obtention du chemin MMAP exclusif,
  comportement des décodeurs matériels, fluidité à 120 Hz. Détail au §12 de
  `ARCHITECTURE.md`.
