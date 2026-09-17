# XperiaMix — architecture

Application Android native de chargement, visualisation, calage et mixage de
deux pistes audio, optimisée pour le Sony Xperia 1 V.

---

## 1. Cible et contraintes

| Élément | Valeur | Conséquence sur l'architecture |
|---|---|---|
| SoC | Snapdragon 8 Gen 2, 4 cœurs performance | analyses hors-ligne parallélisables, NEON par auto-vectorisation |
| Écran | 3840 × 1644, 21:9, 120 Hz LTPO | budget de **8,3 ms par trame** ; la largeur est l'atout principal |
| Audio | HAL à 48 kHz ; jack 3,5 mm + ampli dédié jusqu'à 192 kHz | tout le pipeline est aligné sur le taux natif ; mode Hi-Res optionnel |
| API | 33+ (Android 13) | ADPF (`APerformanceHint`), photo/document picker, pas de permission de stockage |
| Mémoire | 12 Go | on peut se permettre du PCM décodé, mais pas sans plafond |

Deux chiffres commandent presque toutes les décisions qui suivent :

* **1 ms = 48 trames** à 48 kHz, et **1 trame = 20,8 µs**. Deux captations du même
  signal décalées d'une milliseconde produisent un filtre en peigne dont la
  première encoche tombe à 500 Hz — parfaitement audible. Le calage doit donc
  viser la trame, pas la milliseconde.
* **10 minutes de stéréo 48 kHz en float32 = 230 Mo.** Deux pistes tiennent en
  RAM sur cet appareil, mais pas sans précaution : d'où le cache PCM mappé en
  mémoire plutôt que des tableaux sur le tas.

---

## 2. Choix des bibliothèques

### 2.1 Rendu audio : **Oboe (backend AAudio)**

C'est la décision structurante. Elle découle d'une contrainte : **deux pistes
lues sans dérive l'une par rapport à l'autre**.

| Solution | Verdict |
|---|---|
| Deux `ExoPlayer` / `MediaPlayer` | ✗ Deux `AudioTrack`, donc deux horloges de présentation. Le décalage initial n'est pas reproductible et dérive au fil de la lecture. Rédhibitoire. |
| Un `ExoPlayer` + `MixingAudioProcessor` | ~ Possible depuis Media3 1.3, mais le décalage se règle par un graphe à reconfigurer, et la latence du chemin `DEFAULT` se compte en dizaines de millisecondes. Mal adapté à un réglage au doigt. |
| `AudioTrack` en Java | ~ Fonctionne, mais le mixage se fait alors sur un thread JVM, exposé aux pauses du GC. |
| **Oboe / AAudio, un seul flux** | ✓ **Retenu.** Les deux pistes sont sommées dans le même callback, donc sur la même horloge : **la dérive est nulle par construction**, pas par synchronisation. |

Configuration : `SharingMode::Exclusive`, `PerformanceMode::LowLatency`, format
`Float`, **fréquence = taux natif du HAL**. Ouvrir à une autre fréquence force
l'insertion d'un rééchantillonneur dans le serveur audio, ce qui fait perdre le
chemin MMAP et multiplie la latence par trois ou quatre. La taille de tampon est
fixée à deux rafales.

Oboe plutôt qu'AAudio brut : gestion du repli OpenSL ES, contournement des bogues
de HAL connus, redémarrage sur déconnexion (jack débranché), et activation d'ADPF
en une ligne.

### 2.2 Décodage : **MediaExtractor + MediaCodec** (APIs plateforme)

Couvre MP3, WAV, FLAC, AAC, OGG/Vorbis et Opus avec les décodeurs matériels du
SoC. Media3 reste référencé au catalogue de versions comme repli documenté pour
les conteneurs exotiques que `MediaExtractor` gère mal ; il n'est pas utilisé.

Trois points traités dans `PcmDecoder` et souvent oubliés ailleurs :

1. **Sortie en float demandée explicitement** (`KEY_PCM_ENCODING`). Un FLAC
   24 bits passé par du 16 bits perd 48 dB de plage dynamique. Les sorties
   16 / 24 packé / 32 bits entier sont normalisées si le décodeur refuse.
2. **Délai d'encodeur retiré** (`encoder-delay` / `encoder-padding`). AAC insère
   2112 trames de silence en tête, MP3 au moins 529. Si les deux pistes n'ont pas
   le même format, ce sont **44 ms d'erreur systématique** injectées dans le
   calage automatique.
3. **Cache disque.** Recharger une piste déjà décodée est instantané ; le cache
   est plafonné à 2 Gio au démarrage.

### 2.3 Interface : **Jetpack Compose + Material 3**

Le tracé de la forme d'onde descend au `Canvas` natif (`drawLines`) : Compose
gère la structure, le gestuel et l'état ; le dessin reste au niveau de l'API
graphique. Voir §8.

### 2.4 DSP d'analyse : **implémenté sur place**

FFT, décimation, enveloppe de flux spectral, GCC-PHAT et rééchantillonnage sinc
tiennent en ~600 lignes de Kotlin lisible et testable sur JVM. Importer JTransforms
ou une bibliothèque native n'apporterait rien ici : l'analyse est hors-ligne, et
la correction de la *convention de signe* du décalage compte plus que la vitesse.

---

## 3. Couches

```
┌──────────────────────────────────────────────────────────────────┐
│  UI — Compose                                                    │
│  MixerScreen · WaveformTimeline · OffsetControl · TrackStrip     │
│  TimelineState (zoom/scroll/curseur : lus en phase de dessin)    │
└───────────────┬──────────────────────────────────────────────────┘
                │ StateFlow<MixerUiState>          appels directs
                ▼                                  (écritures atomiques)
┌──────────────────────────────────────────────────────────────────┐
│  MixerViewModel — état de session, orchestration des coroutines   │
└───┬─────────────────┬──────────────────┬──────────────────┬──────┘
    │                 │                  │                  │
    ▼                 ▼                  ▼                  ▼
┌─────────┐   ┌──────────────┐   ┌──────────────┐   ┌──────────────┐
│PcmDecoder│  │WaveformPyramid│  │ TrackAligner │   │MixdownExporter│
│+Resampler│  │ (mip-map)     │  │ (3 étages)   │   │ WAV / FLAC    │
└────┬─────┘  └──────────────┘   └──────────────┘   └───────┬──────┘
     │                                                       │
     ▼  PcmBuffer (float32, mappé en mémoire)                 │ renderRange()
┌──────────────────────────────────────────────────────────────────┐
│  AudioEngine (Kotlin)  ──JNI──▶  MixerEngine (C++ / Oboe)        │
│                                  callback temps réel : somme des  │
│                                  deux pistes, gains lissés        │
└──────────────────────────────────────────────────────────────────┘
```

**Règle de frontière :** le thread audio ne remonte jamais vers la JVM. Aucun
callback natif → Java, aucune allocation, aucun verrou, aucune E/S dans
`onAudioReady`. Les commandes de l'UI sont de simples écritures atomiques
relâchées : appelables depuis le thread principal pendant un geste, sans risque
de blocage et sans file d'attente à gérer.

---

## 4. Structures de données

### `PcmBuffer` — le bloc audio

```kotlin
class PcmBuffer(
    val sampleRate: Int,
    val channels: Int,
    val frameCount: Long,
    val bytes: ByteBuffer,     // direct : adressable par GetDirectBufferAddress
) { val floats: FloatBuffer }  // vue : floats[frame * channels + canal]
```

Deux modes : `allocateDirect` (hors tas, pour les tampons de travail) et
`mapReadOnly` (fichier de cache mappé, pour les pistes longues). Le mappage
donne trois choses à la fois : mémoire gérée par le noyau et donc évinçable,
pointeur exploitable directement par le JNI sans copie, et rechargement
instantané. Limite d'un mappage unique : 2 Gio, soit ~93 min en 48 kHz stéréo.

Le moteur natif reçoit ce pointeur et rien d'autre. Une référence globale JNI sur
le `ByteBuffer` empêche le GC de démapper la zone pendant que le thread audio la
lit. `madvise(MADV_WILLNEED)` pré-charge les pages pour éviter une faute de page
majeure dans le callback.

### `TrackState` — l'état d'une piste

```kotlin
data class TrackState(
    val id: TrackId, val uri: Uri?, val displayName: String,
    val pcm: PcmBuffer?, val waveform: WaveformPyramid?,
    val offsetFrames: Long,        // décalage sur la timeline
    val gainDb: Float, val muted: Boolean, val soloed: Boolean,
    val polarityInverted: Boolean,
)
```

**Convention d'offsets.** Les deux pistes portent un offset positif ou nul, et la
session est renormalisée après chaque modification pour que le plus petit des deux
vaille zéro : reculer B revient à avancer A. Le moteur n'a donc jamais à gérer de
temps négatifs, et la timeline commence toujours au premier échantillon audible.
L'utilisateur, lui, ne voit qu'une valeur : `offsetB − offsetA`.

Le gain est stocké en décibels — l'unité dans laquelle on raisonne pour équilibrer
deux sources — et converti en linéaire seulement au passage vers le moteur.

### `WaveformPyramid` — la forme d'onde

```kotlin
class PeakLevel(val bucketFrames: Int, val mins, val maxs, val rms: FloatArray)
class WaveformPyramid(val sampleRate: Int, val frameCount: Long,
                      val levels: List<PeakLevel>)
```

Niveau 0 : un paquet pour 256 trames (min, max, RMS). Chaque niveau suivant
fusionne deux paquets du précédent — construction en O(n), une seule passe sur le
PCM. Le rendu choisit le niveau le plus grossier tel que `bucketFrames ≤ trames
par pixel`, donc **environ un paquet par colonne de pixels**.

Coût pour 10 minutes : 112 500 paquets × 3 floats = 1,35 Mo au niveau 0, moins de
3 Mo pour toute la pyramide. À comparer aux 230 Mo de PCM.

### `TimelineState` — la fenêtre de visualisation

`firstFrame`, `framesPerPixel`, `playheadFrame`, `widthPx`, `totalFrames`, tous en
`MutableState`. Leur particularité est expliquée au §8 : ils sont lus **uniquement
dans les lambdas de dessin**.

---

## 5. Pipeline audio

```
   Fichier (SAF, URI persistant)
        │
        ▼  MediaExtractor + MediaCodec
   PCM natif ── délai d'encodeur retiré ──▶ cache .f32 (float32 entrelacé)
        │
        ▼  Resampler (sinc Lanczos 16 lobes) si taux ≠ taux natif du HAL
   PcmBuffer mappé en mémoire ──────────────┬────────────────────┐
        │                                   │                    │
        ▼ WaveformPyramid.build             ▼ TrackAligner       ▼ setTrack (JNI)
   pyramide de crêtes                  décalage estimé      moteur natif
        │                                   │                    │
        ▼                                   ▼                    ▼
   affichage Compose ◀── offsetFrames ──▶ setOffset      callback temps réel
                                                                 │
                                                                 ├─▶ sortie AAudio
                                                                 └─▶ renderRange() ─▶ export
```

### Le callback temps réel

Pour chaque piste, on calcule l'intersection entre la fenêtre demandée
`[playhead, playhead + n)` et la zone sonore `[offset, offset + frames)`, et on ne
parcourt que cette intersection. Le gain converge vers sa cible par un filtre du
premier ordre (τ = 10 ms) : sans ce lissage, un changement par bloc produit un
*zipper noise* très audible. Une rampe de transport de 8 ms supprime le clic au
démarrage et à la pause.

**L'export réutilise exactement cette fonction de mixage** (`renderRange`), avec
un état de rampe séparé. Le fichier produit est donc ce qui a été entendu, et non
le résultat d'une seconde implémentation qui divergerait tôt ou tard.

---

## 6. Calage manuel

Trois granularités, parce qu'aucune seule ne couvre le besoin :

| Geste | Portée | Résolution |
|---|---|---|
| Glissement sur la piste B (mode calage) | toute la timeline | 1 pixel = `framesPerPixel` trames, avec accumulation des fractions |
| Curseur | ± 2 s | ~4 ms au doigt |
| Boutons pas à pas | ± 10 ms / ± 1 ms / ± 1 trame | jusqu'à 20,8 µs |

Le glissement accumule les fractions de trame : au zoom maximal un pixel vaut
0,05 trame, et un arrondi par événement immobiliserait la piste. Un retour
haptique marque le passage par le calage nul, ce qui permet de se repérer sans
quitter la forme d'onde des yeux.

Le mode calage a la priorité sur le défilement : le détecteur de la piste consomme
l'événement avant que le conteneur ne le voie.

---

## 7. Calage automatique

### 7.1 Pourquoi trois étages

Une corrélation à pleine résolution sur 10 minutes demanderait une FFT de 2²⁶
points par piste, soit plus d'un gigaoctet par signal en double précision. Une
corrélation d'enveloppes est mille fois moins chère et beaucoup plus robuste, mais
sa résolution est celle du pas d'analyse. Une GCC-PHAT donne un pic très fin mais
peut accrocher le mauvais lobe si la plage de recherche n'est pas déjà restreinte.

D'où un enchaînement **grossier → fin → exact** :

| Étage | Méthode | Domaine | Résolution | Coût (10 min) |
|---|---|---|---|---|
| 1 | corrélation d'enveloppes de transitoires | toute la durée | ~21 ms | FFT 2¹⁶ |
| 2 | GCC-PHAT | fenêtre de 20 s à 12 kHz | ~83 µs | FFT 2¹⁹ |
| 3 | corrélation directe normalisée | ± 8 trames à 48 kHz | < 1 trame | ~4 M opérations |

### 7.2 Étage 1 — enveloppes de transitoires

Les deux pistes sont ramenées en mono puis décimées d'un facteur entier vers
~12 kHz, avec filtre anti-repliement (sinc fenêtré Blackman, 65 coefficients).
Sans ce filtre, une caisse claire à 9 kHz réapparaîtrait à 3 kHz et brouillerait
la corrélation — vérifié par test : le RMS résiduel d'un 9 kHz décimé par 4 tombe
à 0,0045.

On calcule ensuite le **flux spectral** : STFT (1024 points, saut de 256, fenêtre
de Hann), puis somme des *augmentations* d'amplitude par bande entre deux trames
consécutives. C'est un détecteur d'attaques, donc une représentation de « quand ça
attaque » indépendante du timbre. C'est ce qui permet de caler un enregistreur de
salle sur une sortie console, là où une corrélation forme d'onde contre forme
d'onde échouerait.

Les deux enveloppes sont centrées-réduites, puis corrélées sur toute leur durée.

### 7.3 Étage 2 — GCC-PHAT

Sur la fenêtre de 20 s la plus énergétique de A et la fenêtre correspondante de B
(déduite du décalage grossier), on calcule :

```
R(f) = A(f) · conj(B(f)) / |A(f) · conj(B(f))|        ← transformation de phase
r(τ) = IFFT{ R(f) }
```

La normalisation par le module blanchit le spectre : le pic devient
quasi impulsionnel et l'estimation cesse de dépendre du contenu spectral, ce qui
la rend robuste à une réverbération ou à une égalisation différente entre les deux
prises. Mesuré sur signal bruité à 14 dB de rapport signal/bruit : rapport
pic/lobes de **80**, contre un pic large et ambigu en corrélation simple.

Si l'étage 1 n'a rien donné (piste sans transitoire : nappe, sinus), l'étage 2
balaie toute la plage de recherche au lieu d'affiner.

### 7.4 Étage 3 — exactitude à la trame

Corrélation directe normalisée (Pearson) sur ± 2 facteurs de décimation à la
fréquence d'origine, puis interpolation parabolique du sommet :

```
δ = ½ · (r₋₁ − r₊₁) / (r₋₁ − 2r₀ + r₊₁)
```

pour une précision inférieure à l'échantillon — utile quand la différence de
trajet entre deux micros ne tombe pas sur un multiple exact de la période
d'échantillonnage.

### 7.5 Convention de signe

C'est le point où une implémentation sur deux se trompe. Avec
`r(τ) = Σₜ a[t + τ] · b[t]` :

> un pic en `τ` signifie qu'un événement situé à l'instant `t` dans B se retrouve
> à `t + τ` dans A, autrement dit **τ = t_A − t_B**. Pour aligner B sur A, on
> **ajoute τ à l'offset de B**.

Vérification par test unitaire : impulsion à 100 dans A, à 300 dans B → `τ = −200`,
et `300 + (−200) = 100`. ✓

### 7.6 Ce que l'algorithme rapporte en plus du décalage

| Indicateur | Usage |
|---|---|
| **Confiance** (corrélation normalisée au pic, 0..1) | seuil d'application automatique |
| **Rapport pic / lobes** | distingue un vrai pic d'un plateau ; un signal périodique ou bouclé donne un rapport proche de 1 et doit être signalé comme non fiable |
| **Polarité** | un pic **négatif** signifie que les deux pistes sont en opposition de phase ; on propose l'inversion, sans quoi la somme se creuse au lieu de se renforcer |
| **Dérive d'horloge** | le décalage est ré-estimé au début et à la fin du programme ; un écart révèle deux horloges différentes (typiquement 10 à 100 ppm, soit jusqu'à 360 ms par heure). **Aucun décalage constant ne peut alors caler les deux pistes de bout en bout** : il faut le dire à l'utilisateur plutôt que de lui livrer un résultat qui se dégrade au fil du morceau. |

Le résultat n'est **jamais appliqué d'office** : l'interface affiche décalage,
confiance, rapport pic/lobes et méthode retenue, et l'utilisateur tranche.

---

## 8. Visualisation

### Le problème

À 48 kHz, 10 minutes font 28,8 millions de trames pour environ 3 800 colonnes de
pixels utiles. Il faut donc que **le coût du dessin dépende de la largeur de
l'écran, pas de la durée de la piste**. C'est le rôle de la pyramide de crêtes.

### Les trois choix qui font la fluidité

1. **Un seul appel de dessin.** Tous les segments verticaux sont accumulés dans un
   `FloatArray` puis envoyés en une fois à `Canvas.drawLines`. Un `drawLine` par
   colonne — 3 800 appels — coûterait à lui seul plus que le budget de 8,3 ms.
2. **Tableaux réutilisés**, dimensionnés une fois pour la largeur maximale : la
   phase de dessin ne doit jamais déclencher le ramasse-miettes.
3. **Lectures d'état confinées à la phase de dessin.** `firstFrame` et
   `framesPerPixel` sont lus *dans* la lambda du `Canvas`. Compose invalide alors
   la seule phase de dessin — ni recomposition, ni remesure. Les lire dans le corps
   du composable suffirait à déclencher une recomposition par trame de geste :
   c'est l'erreur classique sur ce type d'écran.

Détail d'implémentation : l'arithmétique des positions est en `Double`. À 65 000
trames par pixel sur 3 840 pixels, la mantisse de 24 bits d'un `Float` perdrait
déjà des trames entières par arrondi.

Au-delà d'un échantillon par pixel — zoom indispensable pour vérifier un calage à
la trame près — on bascule sur le PCM et on trace la polyligne réelle, avec
anticrénelage (les traits deviennent obliques).

Le curseur de lecture est suivi par `withFrameNanos` : une valeur par trame de
composition, soit 120 fois par seconde sur cette dalle, sans minuteur indépendant
qui réveillerait le CPU pour rien. La position affichée est la position **audible**
(curseur moins la latence de sortie), pas la dernière trame écrite dans le flux.

---

## 9. Export

Deux passes sur `renderRange` :

1. mesure de la crête réelle du mixage — sommer deux pistes à 0 dB dépasse presque
   toujours 0 dBFS ;
2. application du gain de normalisation, puis écriture.

Le rendu n'étant qu'une lecture mémoire, la première passe coûte quelques
centaines de millisecondes pour dix minutes.

**WAV** (16 / 24 / 32 bits flottant) : l'en-tête est écrit complet dès le départ,
le nombre de trames étant connu à l'avance — indispensable avec un `OutputStream`
du Storage Access Framework, sur lequel on ne peut pas revenir en arrière. En
16 bits, un dither TPDF d'un LSB est ajouté : sans lui, la troncature d'un mixage
float produit une distorsion corrélée au signal, très audible sur les fins de
réverbération ; le dither la transforme en un souffle constant à −93 dBFS.

**FLAC** : encodeur `MediaCodec` de la plateforme. `MediaMuxer` n'empaquette pas le
FLAC de manière fiable, on écrit donc le conteneur natif à la main —
`"fLaC"` + en-tête de bloc + `STREAMINFO` (fourni en `csd-0`) + trames brutes. Le
nombre total d'échantillons, annoncé à zéro par l'encodeur, est corrigé après coup
sur un fichier temporaire, sans quoi les lecteurs n'affichent pas la durée.

---

## 10. Adaptation au Xperia 1 V

* **21:9 en paysage** : 3840 pixels de large affichent ~40 s de programme à une
  résolution d'une milliseconde par pixel. La colonne de commandes est fixée à
  droite (360 dp) plutôt que superposée, et la timeline prend tout le reste.
  `BoxWithConstraints` bascule en colonne en portrait.
* **Bord à bord jusque dans la découpe** (`shortEdges`) : chaque pixel de largeur
  gagné est du temps affiché en plus.
* **120 Hz** : budget de 8,3 ms, d'où les règles du §8.
* **OLED** : thème sombre par défaut, qui est aussi le bon choix ergonomique pour
  un plan de travail audio. Les deux pistes sont distinguées par deux teintes
  éloignées en chrominance *et* en luminance (cyan / ambre), lisibles en
  superposition et pour les daltonismes rouge-vert.
* **Hi-Res** : `DeviceAudioProfile` relève les fréquences acceptées par la sortie
  active. Le jack 3,5 mm et son ampli dédié montent à 192 kHz, au prix du chemin
  basse latence — d'où un réglage explicite plutôt qu'un choix implicite.
* **arm64 uniquement** : pas d'ABI inutile dans l'APK.
* **ADPF** (`setPerformanceHintEnabled`) : le thread audio déclare sa charge au
  gouverneur CPU, ce qui évite les descentes de fréquence responsables d'une partie
  des ruptures de flux.
* **`FLAG_KEEP_SCREEN_ON`** : rien de plus pénible qu'une mise en veille au milieu
  d'un calage.

---

## 11. Budgets

| Opération | Ordre de grandeur (10 min, 48 kHz stéréo) |
|---|---|
| Décodage (décodeur matériel) | 2 à 5 s |
| Rééchantillonnage 44,1 → 48 kHz (sinc 16 lobes) | 3 à 6 s |
| Construction de la pyramide | < 1 s |
| Calage automatique complet | 2 à 4 s |
| Rendu de l'export | < 1 s (lecture mémoire) |
| Encodage FLAC | 5 à 15 s |
| Latence de sortie (AAudio exclusif) | ~10 ms |
| Trame de dessin | < 3 ms pour 3 840 colonnes × 2 pistes |

Mémoire : ~230 Mo de PCM mappé par piste (évinçable par le noyau), < 3 Mo de
pyramide, quelques Mo de tampons d'analyse.

---

## 12. Vérification

Ce qui est **compilé et exécuté** : la moitié pure-JVM du code — `align/`,
`decode/PcmBuffer`, `decode/PcmSink`, `decode/Resampler`, `waveform/`,
`export/WavWriter`, `model/` — compile sans erreur ni avertissement, et ses
13 tests passent. (Vérification menée dans un projet Gradle JVM jetable, faute
de SDK Android dans l'environnement de développement ; les sources sont les
mêmes.)

Ce qui est **vérifié par test unitaire JVM** (`app/src/test`) :

* FFT comparée à une DFT naïve (erreur < 1e-9) et aller-retour sans perte ;
* convention de signe du décalage, sur impulsions et sur signal bruité ;
* supériorité du rapport pic/lobes de la GCC-PHAT sur la corrélation simple ;
* détection de polarité inversée ;
* interpolation parabolique du sommet ;
* efficacité du filtre anti-repliement de la décimation ;
* détection des attaques par l'enveloppe de flux spectral ;
* conservation des crêtes à travers les niveaux de la pyramide ;
* conformité de l'en-tête WAV (PCM et flottant).

Ce qui **n'a pas été compilé** : tout ce qui dépend du SDK ou du NDK Android —
l'interface Compose, `PcmDecoder` (MediaCodec), `FlacWriter`, `MixdownExporter`,
la façade `audio/` et le moteur C++. Ces fichiers sont écrits mais n'ont pas
traversé de compilateur.

Ce qui **ne peut pas être vérifié** hors appareil, et reste à valider sur un
Xperia 1 V : latence réelle et compteur de ruptures de flux, obtention effective
du chemin MMAP exclusif, comportement des décodeurs matériels sur les formats
limites, sortie `csd-0` de l'encodeur FLAC, fluidité mesurée à 120 Hz.

---

## 13. Limites connues et suites

* Une seule paire de pistes. Le moteur est dimensionné par `kMaxTracks` ; passer à
  N pistes est mécanique côté natif, mais l'interface 21:9 devrait être repensée.
* Pas de compensation de dérive d'horloge : elle est **détectée et signalée**, pas
  corrigée. La correction demanderait un rééchantillonnage au rapport estimé.
* Le décalage fractionnaire issu de l'interpolation parabolique est affiché mais
  pas appliqué : le moteur travaille à la trame. L'appliquer demanderait un
  interpolateur à retard fractionnaire dans le chemin de lecture.
* Pas de limiteur à l'export, seulement une normalisation de crête.
* WAV limité à 4 Gio (pas de RF64) ; mappage PCM limité à 2 Gio, soit ~93 min.
* Pas de lecture en arrière-plan (ni service de premier plan, ni `MediaSession`).
