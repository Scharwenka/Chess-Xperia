# MUSIC_IDEAS

Application pour téléphone (PWA, hors connexion) pour noter ses idées musicales sur une
**double portée de piano** (clé de sol + clé de fa) et les **exporter en un toucher**.

## Installation sur le téléphone

L'application est un site statique : il suffit de servir le dossier `music-ideas/` en HTTPS.

1. Sur GitHub : **Settings → Pages → Build and deployment → Deploy from a branch**, branche `main`, dossier `/ (root)`.
2. Sur le téléphone (Chrome) : ouvrir `https://<utilisateur>.github.io/Chess-Xperia/music-ideas/`.
3. Menu ⋮ → **Ajouter à l'écran d'accueil / Installer l'application**.

Ensuite elle s'ouvre en plein écran comme une vraie application et fonctionne sans réseau.
Les idées sont enregistrées automatiquement sur le téléphone.

## Saisie

| Geste | Effet |
|---|---|
| Toucher la portée | pose une note à la hauteur touchée (portée de sol ou de fa) au curseur |
| Maintenir puis glisser | la note suit le doigt avec son nom affiché ; relâcher pour la poser |
| Toucher une note | la sélectionne : durée, point, triolet, altération, ▲▼ s'appliquent à elle |
| Clavier de piano | joue dans la portée active (bouton 𝄞/𝄢) ; plusieurs doigts = accord |
| Accord | empile les notes suivantes sur la note courante |
| ▲ ▼ | un degré (appui long : une octave) |
| ◀ ▶ | déplacer le curseur pour insérer au milieu |
| ⌫ / ↶ ↷ | effacer / annuler / rétablir |
| ▶ | écouter (son de piano synthétisé) |

Durées : ronde → triple croche, notes pointées, triolets, liaisons, silences.
Les barres de mesure sont automatiques ; une note qui déborde est coupée et liée.
Réglages : titre, tempo, mesure (2/4 … 12/8), armure (7♭ à 7♯), taille de la portée.

Raccourcis clavier (ordinateur / clavier Bluetooth) : `a`–`g` notes, `2`–`7` durées,
`.` point, `0` silence, `+ - =` altérations, flèches, `Tab` change de portée, `Espace` écoute, `Ctrl+Z`.

## Exports

Bouton orange → un toucher par format (mode *Télécharger* ou *Partager*) :

| Format | Usage |
|---|---|
| PDF | partition vectorielle A4 à imprimer |
| MusicXML | MuseScore, Sibelius, Dorico, Finale, Logic… |
| MIDI | DAW, séquenceur, clavier (2 pistes : main droite / main gauche) |
| PNG / SVG | image à envoyer / image vectorielle |
| ABC | notation texte ABC |
| LilyPond | gravure LilyPond / Frescobaldi |
| Projet (.json) | sauvegarde ré-importable dans MUSIC_IDEAS |
| Tout (.zip) | les 8 formats d'un coup |

Android ne permet pas de *partager* certains types (.musicxml, .mid, .zip) : ils sont alors téléchargés.

## Structure

```
music-ideas/
  index.html, manifest.json, sw.js   application installable + hors connexion
  css/style.css
  js/glyphs.js     symboles musicaux (police Bravura, SIL OFL 1.1)
  js/model.js      modèle de partition, mesures, liaisons, altérations, ligatures
  js/render.js     mise en page de la double portée + rendu SVG
  js/exporters.js  MusicXML, MIDI, PDF, PNG, SVG, ABC, LilyPond, ZIP
  js/audio.js      synthèse sonore (Web Audio)
  js/app.js        interface
```

Aucune dépendance ni étape de compilation. Pour tester en local : `python3 -m http.server` à la racine
du dépôt puis ouvrir `http://localhost:8000/music-ideas/`.

Les glyphes musicaux proviennent de la police [Bravura](https://github.com/steinbergmedia/bravura)
(© Steinberg Media Technologies GmbH, SIL Open Font License 1.1).
