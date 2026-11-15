# Tratto — changements et ajouts

Ce document décrit le travail ajouté par-dessus la version **1.2.0** (Google Docs en direct, envoi plus rapide, stylet incliné). L’application, les notes déjà enregistrées et l’identifiant `it.frumorn.tratto` restent les mêmes.

---

## Français

### Langue de l’interface

L’interface suit la langue de la tablette : **français**, **italien** ou **anglais**. Si la tablette est dans une autre langue, l’application s’affiche en anglais.

Dans les paramètres, **Apparence → Langue** est une liste déroulante :

- **Système** — suit la tablette (choix par défaut) ;
- **Français** ;
- **Italiano** ;
- **English**.

Les noms des langues restent écrits dans leur propre langue, pour qu’on les retrouve même quand l’interface est dans une autre. La reconnaissance d’écriture (version complète) utilise la même langue, et reconstruit son modèle si on en change.

**Comment l’app se traduit :** les libellés sont dans des fichiers Android classiques. La langue de l’app (`Apparence → Langue` / langue système) choisit le bon dossier ; le code appelle `testo(R.string.clé)` (Compose) ou `testoOra(R.string.clé)` (toasts, workers) via `Language.kt`.

- anglais (défaut) : `app/src/main/res/values/strings.xml`
- français : `app/src/main/res/values-fr/strings.xml`
- italien : `app/src/main/res/values-it/strings.xml`
- fiche Play Store : `fastlane/metadata/android/fr-FR/`, `en-US/`, `it-IT/`

La description anglaise ne dit plus que l’interface est en italien : elle suit la langue de la tablette.

### Cahiers

Les dossiers ne sont plus de simples pastilles. Sur l’accueil, chaque cahier est une **couverture** sur une étagère, avec son nom, sa couleur et le nombre de notes.

Ouvrir un cahier **y fait entrer** : l’étagère disparaît avec une animation, et l’écran ne montre plus que les pages de ce cahier, avec son nom et un bouton Retour. Le retour (bouton ou geste système) ramène à l’étagère.

Une note créée **alors qu’un cahier est ouvert** — bouton « Nouvelle note », double-clic du stylet, ou PDF importé — est rangée automatiquement dans ce cahier.

### Déplacer une note

Un appui long sur une note la soulève, comme dans Google Drive. Le titre suit le doigt. Une barre « Déposer dans un cahier » reste en haut de l’écran, même si l’étagère a défilé. Relâcher au-dessus d’un cahier y déplace la note. Relâcher ailleurs annule le déplacement ; la note reste sélectionnée.

Si la note soulevée fait déjà partie d’une sélection, **toutes les notes sélectionnées** partent ensemble. Un appui court ouvre toujours la note.

### Stylet Galaxy Tab S10 FE

Le S Pen de la Tab S10 FE est un stylet passif (EMR) : pas de Bluetooth, pas d’Air Actions. Le bouton latéral est lu directement par l’écran.

- **Maintenir le bouton en écrivant** efface, y compris au milieu d’un trait. Au relâchement, l’outil précédent revient (sauf si la gomme était déjà l’outil choisi, ou si la pointe est la gomme du stylet).
- **Un appui long, stylet près de l’écran**, ouvre un menu : plumes, gomme, lasso, annuler, rétablir. Il apparaît dans l’éditeur et dans la note rapide.
- **Deux clics brefs** près de l’écran gardent le comportement déjà réglé : nouvelle note, basculer la gomme, ou rien. L’appui long n’est pas compté comme un clic.

Si le menu Air Command de Samsung avale l’appui en vol, il faut le désactiver pour le menu de Tratto. Effacer la pointe posée sur la feuille fonctionne quand même.

### Import Noteshelf

Dans les paramètres, **Importer → Importer un fichier Noteshelf** lit un export `.noteshelf`. **Chaque page écrite** devient une note **à la racine** (pas dans un cahier). L’encre reprend la largeur variable de chaque point du segment Noteshelf (pas le réglage `strokeWidth` fixe). La page est ramenée à la largeur Tratto (1000) pour que toute la note tienne à l’écran. Les traits droits, le texte posé sur la page et les traits de ruban (comme un surligneur) sont repris. La couverture sans écriture et le motif du ruban ne le sont pas.

Les pages **paysage** (`rotationAngle` 90° / 270°) gardent souvent un rectangle portrait dans Noteshelf alors que l’encre est déjà tournée : Tratto permute largeur et hauteur à l’import. Les notes déjà trop étroites sont réparées au démarrage.

Les pages importées (et celles déjà en largeur native 1440) sont ramenées à la **largeur Tratto (1000)** avec la même proportion : traits et objets sont multipliés par le même facteur, sans perte de qualité relative ni de placement. Les vignettes trop étroites sont régénérées.

### Portrait et paysage

L’app tourne librement (`fullUser`). La barre de l’éditeur se compacte en largeur étroite (actions de page dans **⋯**), la palette d’outils défile horizontalement, la recherche et la grille de la bibliothèque s’adaptent à la largeur.

### Tourner une page

Dans l’éditeur, le menu **⋯** propose **Tourner à droite** et **Tourner à gauche** (90°). L’encre et les objets suivent, largeur et hauteur s’échangent si besoin (portrait ↔ paysage). Annuler / rétablir fonctionnent. Une page ajoutée reprend le format de la page courante.

### Chapitres dans un cahier

Dans un cahier ouvert, les chapitres ont une étagère — **dossiers à bandeau** (look distinct des livres reliés), tap pour **entrer**, retour pour ressortir, glisser-déposer, long-appui pour **renommer et changer la couleur**. Les cahiers aussi : à la création et à la modification, on choisit nom **et** couleur. Une note créée dans un chapitre ouvert y est rangée automatiquement.

### Import d’un PDF

L’écran des fichiers s’ouvre en plein écran. Un bouton **Accueil** s’affiche par-dessus pour revenir à Tratto, une fois l’autorisation d’afficher par-dessus les autres applications accordée.

### Noms du code en anglais

Les dossiers, les fichiers et le type principal de chaque fichier sont passés de l’italien (et de quelques noms français) à l’anglais. Exemples : `libreria` → `library`, `impostazioni` → `settings`, `notarapida` → `quicknote`, `calcolo` → `math`, `scrittura` → `handwriting`, `tools/installa.sh` → `tools/install.sh`, `tools/penna.py` → `tools/pen.py`.

Ce qui ne change pas, pour ne pas perdre les notes installées ni casser les scripts :

- le paquet `it.frumorn.tratto` ;
- les variantes `completa` et `libera` ;
- les clés de sauvegarde, les champs JSON et le format des fichiers `.tratto` ;
- la plupart des noms internes de fonctions et de variables, qui restent en italien.

### Lancer l’application

`tools/launch.sh` compile la version complète de débogage, démarre l’émulateur tablette `Tratto_Tablet` s’il ne tourne pas, installe l’APK et ouvre Tratto. Il attend un JDK 21.

---

## English

### Interface language

The interface follows the tablet language: **French**, **Italian** or **English**. Any other device language falls back to English.

In settings, **Appearance → Language** is a dropdown:

- **System** — follow the tablet (the default);
- **Français**;
- **Italiano**;
- **English**.

Language names stay in their own language, so they can be found even when the interface is in another one. Handwriting recognition (full version) uses the same language and rebuilds its model when it changes.

**How the app is translated:** labels live in standard Android string files. The app language (Appearance → Language / system language) picks the right folder; code calls `testo(R.string.key)` (Compose) or `testoOra(R.string.key)` (toasts, workers) via `Language.kt`.

- English (default): `app/src/main/res/values/strings.xml`
- French: `app/src/main/res/values-fr/strings.xml`
- Italian: `app/src/main/res/values-it/strings.xml`
- Play Store listing: `fastlane/metadata/android/fr-FR/`, `en-US/`, `it-IT/`

The English description no longer says the interface is in Italian: it follows the tablet language.

### Notebooks

Folders are no longer small chips. On the home screen each notebook is a **cover** on a shelf, with its name, colour and note count.

Opening a notebook **enters it**: the shelf leaves with an animation, and the screen shows only that notebook’s pages, with its name and a Back button. Back (the button or the system gesture) returns to the shelf.

A note created **while a notebook is open** — the “New note” button, a stylus double-click, or an imported PDF — is filed in that notebook automatically.

### Moving a note

A long press lifts a note, as in Google Drive. The title follows the finger. A “Drop into a notebook” bar stays at the top of the screen, even if the shelf has scrolled away. Releasing over a notebook moves the note there. Releasing anywhere else cancels the move; the note stays selected.

If the lifted note is already part of a selection, **every selected note** moves together. A short tap still opens the note.

### Galaxy Tab S10 FE stylus

The Tab S10 FE S Pen is a passive EMR stylus: no Bluetooth and no Air Actions. The side button is read by the screen itself.

- **Holding the button while writing** erases, including in the middle of a stroke. Releasing it restores the previous tool (unless the eraser was already selected, or the tip itself is the stylus eraser).
- **A long press while the pen hovers** opens a menu: pens, eraser, lasso, undo, redo. It appears in the editor and in the quick note.
- **Two short clicks** near the screen keep the existing setting: new note, toggle the eraser, or nothing. A long press is not counted as a click.

If Samsung Air Command swallows the hover press, it has to be turned off for Tratto’s menu. Erasing with the tip on the page still works.

### Noteshelf import

In settings, **Import → Import a Noteshelf file** reads a `.noteshelf` export. **Each written page** becomes a note **at the root** (not inside a notebook). Ink keeps each point’s variable width from the Noteshelf segments (not the fixed `strokeWidth` setting). The page is scaled to Tratto’s width (1000) so the whole note fits on screen. Straight strokes, text on the page, and tape strokes (as a highlighter) are kept. A cover with no writing, and the tape pattern itself, are not.

**Landscape** pages (`rotationAngle` 90° / 270°) often keep a portrait rect in Noteshelf while ink is already rotated: Tratto swaps width and height on import. Notes that were imported too narrow are repaired on launch.

Imported pages (and ones still at native width 1440) are scaled to **Tratto’s width (1000)** with the same aspect ratio: strokes and objects share one scale factor, so relative quality and placement stay. Too-narrow thumbnails are regenerated.

### Portrait and landscape

The app rotates freely (`fullUser`). The editor bar compactifies on a narrow width (page actions in **⋯**), the tool palette scrolls horizontally, and library search / grid adapt to width.

### Rotate a page

In the editor, the **⋯** menu has **Rotate right** and **Rotate left** (90°). Ink and objects follow; width and height swap when needed (portrait ↔ landscape). Undo / redo work. A newly added page keeps the current page’s size.

### Chapters in a notebook

Inside an open notebook, chapters sit on a shelf as **banded folders** (distinct from hardcover books): tap to **enter**, back to leave, drag-and-drop, long-press to **rename and change color**. Notebooks too: create and edit pick name **and** color. A note created while a chapter is open is filed there automatically.

### Importing a PDF

The file screen opens full screen. A **Home** button is drawn on top of it so you can return to Tratto, once the permission to display over other apps is granted.

### Code names in English

Folders, files, and the main type each file is named after moved from Italian (and a few French names) to English. Examples: `libreria` → `library`, `impostazioni` → `settings`, `notarapida` → `quicknote`, `calcolo` → `math`, `scrittura` → `handwriting`, `tools/installa.sh` → `tools/install.sh`, `tools/penna.py` → `tools/pen.py`.

Left unchanged, so installed notes and scripts stay valid:

- the package `it.frumorn.tratto`;
- the `completa` and `libera` flavors;
- backup keys, JSON fields and the `.tratto` file layout;
- most internal function and variable names, which stay Italian.

### Launching the app

`tools/launch.sh` builds the full debug version, starts the `Tratto_Tablet` emulator if it is not already running, installs the APK and opens Tratto. It expects JDK 21.
