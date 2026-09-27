# Tratto — ricerca tecnica

Data: 27/09/2026. Dispositivo di riferimento: Galaxy Tab S6 Lite 2020 (SM-P610, Exynos 9611, 4 GB, 1200×2000 a 240 dpi, 60 Hz) con LineageOS 23.2, cioè Android 16 QPR2: API 36.1, `SDK_INT_FULL` = 3600001. Ci sono MindTheGapps, mentre il software Samsung è assente.

Le versioni sono state lette il 27/09/2026 dagli indici Maven (`dl.google.com/android/maven2/.../group-index.xml`, `repo1.maven.org`) e dalle release notes ufficiali. Il comportamento di sistema citato è stato controllato sui sorgenti AOSP del tag `android-16.0.0_r4` e sui device tree LineageOS del ramo `lineage-23.2`. Quando un'informazione non è verificata lo dico esplicitamente, e i dubbi da risolvere sul device sono raccolti nel §9.

---

## TL;DR: le decisioni

1. **Inchiostro**: Jetpack Ink `androidx.ink:*` **1.1.0-alpha09**, con ripiego sulla stabile 1.0.0. La superficie di disegno è una **View**: `InProgressStrokesView` gestisce l'inchiostro "bagnato" in front-buffer, un `RenderNode` per pagina gestisce quello "secco", e la View è ospitata in un'Activity il cui guscio di UI è in Compose. Per la predizione si usa `MotionEventPredictor` (`androidx.input:input-motionprediction:1.0.0`). `graphics-core` 1.0.4 non si usa direttamente: arriva già con Ink.
2. **Stilo**: si legge `BUTTON_STYLUS_PRIMARY` da `buttonState`, sia in hover sia in contatto. Il **doppio clic del tasto S Pen si rileva solo dentro l'app**, e solo con la penna nel raggio di hover.
   - A livello di sistema, da app normale **non si può**: il tasto genera anche `KEYCODE_STYLUS_BUTTON_PRIMARY`, che `PhoneWindowManager` consuma senza passarlo né alle app né ai servizi di accessibilità.
   - Il ruolo Notes (`android.app.role.NOTES`) su questa build **non è disponibile**, perché `config_enableDefaultNotes=false`.
   - Alternative: tile nelle Impostazioni rapide, voce launcher dedicata "Nuova nota" da agganciare al doppio tap di Lawnchair, Tratto come "assistente digitale" (gesto dall'angolo o pressione lunga del power), oppure un esperimento Shizuku che legge `/dev/input`.
3. **Riconoscimento della scrittura**: ML Kit `com.google.mlkit:digital-ink-recognition:19.0.0` con il modello `it`. Il download è di circa 14 MB e su disco occupa circa 26 MB. Serve per la ricerca e per "converti in testo".
4. **PDF**:
   - Visualizzazione con `android.graphics.pdf.PdfRenderer` di piattaforma (pdfium). Dall'API 35 offre anche testo e ricerca.
   - L'originale resta **intatto** dentro la nota, e le annotazioni vivono nel formato di Tratto.
   - In esportazione i tratti diventano **path vettoriali** aggiunti in un content stream separato, così il testo del PDF resta selezionabile. Lo strumento è PdfBox-Android `com.tom-roush:pdfbox-android:2.0.27.0` (Apache-2.0). Se il device lo supporta, si può usare l'API di piattaforma 36.1 (`PdfRenderer.Page.addPageObject`, `write`).
5. **Backup**: Drive REST v3 con l'`AuthorizationClient` di Google Identity Services (`play-services-auth:22.0.0`) e lo scope **`drive.file`**, pilotato da WorkManager 2.12.0.
   - Ogni nota è un file `.tratto`; PDF e immagini sono blob deduplicati per hash.
   - Il progetto Cloud va messo **"In production"**, altrimenti i consensi scadono ogni 7 giorni.
   - SAF su Drive e Auto Backup non vanno bene come backup principale.
6. **Formato**: una cartella per nota con `note.json` versionato, un log binario append-only di tratti per ogni pagina (payload `StrokeInputBatch` di ink-storage) e asset per hash. Il file `.tratto` è lo zip di quella cartella. Un indice Room con FTS4 è ricostruibile dai file.
7. **Prestazioni**:
   - Il percorso "nuova nota" non tocca Compose nel primo frame.
   - Si usano Baseline + Startup Profile, R8 e soltanto l'ABI arm64.
   - Niente DI framework né inizializzatori automatici.
   - Obiettivi: avvio a freddo dell'editor sotto i 350 ms di TTID sull'Exynos 9611, da verificare con `am start -W` e Macrobenchmark.

---

## 0. Stack e versioni (verificate il 27/09/2026)

| Componente | Versione consigliata | Note |
|---|---|---|
| Android Gradle Plugin | **9.4.1** | Richiede Gradle ≥ 9.6.0 (attuale 9.8.0) e JDK ≥ 17: il JDK 21 va bene. AGP 9 ha **Kotlin integrato**, quindi non si applica `org.jetbrains.kotlin.android`. |
| Kotlin | **2.4.20** | Serve il plugin `org.jetbrains.kotlin.plugin.compose` 2.4.20; per il JSON anche `plugin.serialization` 2.4.20. |
| KSP | **2.3.12** | Per Room. |
| compileSdk / targetSdk / minSdk | **37 / 36 / 35** | Compose UI 1.12.1 e core 1.19.1 richiedono `minCompileSdk=37` e AGP ≥ 9.1. Con minSdk 35 le API `PdfRenderer` di testo, ricerca e `write` si usano senza controlli di versione. Si può scendere a 34 se serve. |
| Compose BOM | **2026.09.00** | ui, foundation e runtime 1.12.1; material3 1.4.0. |
| activity-compose / lifecycle / core-ktx | 1.13.0 / 2.11.0 / 1.19.1 | |
| Jetpack Ink `androidx.ink:ink-*` | **1.1.0-alpha09** (stabile: 1.0.0) | Moduli authoring, brush, geometry, nativeloader, rendering, storage e strokes. Gli equivalenti `-compose` sono opzionali. |
| graphics-core | 1.0.4 | Transitiva di Ink; non serve dichiararla. |
| input-motionprediction | **1.0.0** | `MotionEventPredictor.newInstance(view)`. |
| ML Kit Digital Ink | **19.0.0** | minSdk 23. |
| Room | **2.8.5** (+ KSP) | Alternativa Room 3.0.3 (`androidx.room3`), vedi §7. |
| WorkManager | **2.12.0** | |
| play-services-auth (GIS) | **22.0.0** | `Identity.getAuthorizationClient`. |
| kotlinx-coroutines(-play-services) | 1.11.0 | `Task.await()`. |
| kotlinx-serialization-json | 1.11.0 (evitare la 1.12.0-RC) | |
| PdfBox-Android | **2.0.27.0** | Apache-2.0. Dormiente dal 2023 e porta con sé BouncyCastle 1.72 (vedi §5). |
| androidx.pdf | 1.0.0-beta01 | Solo come riferimento o opzione, non come base (vedi §5). |
| profileinstaller | **1.4.1** | |
| Macrobenchmark / plugin `androidx.baselineprofile` | **1.5.0** | |
| Shizuku API (opzionale) | 13.1.5 (app Shizuku 13.6.0) | Solo per l'esperimento sul tasto a livello di sistema (§2.6). |

Da non includere:

- Hilt/Dagger;
- `material-icons-extended`;
- Coil/Glide, perché per le miniature basta un loader `BitmapFactory` + `LruCache`;
- il client Java di Google per Drive (`google-api-services-drive` più `google-api-client-android` è pesante e usa la reflection), sostituito da chiamate REST dirette;
- Media3, perché per l'audio bastano `MediaRecorder` e `MediaPlayer`;
- `androidx.biometric`, perché con minSdk ≥ 30 basta il `BiometricPrompt` di piattaforma con `DEVICE_CREDENTIAL`. Il Tab S6 Lite non ha sensore di impronte, quindi lo sblocco è PIN o sequenza.

---

## 1. Inking a bassa latenza

### 1.1 Jetpack Ink: stato e moduli

- **Stabile 1.0.0** del 17/12/2025. L'ultima alpha è la **1.1.0-alpha09** del 23/09/2026.
- minSdk: 23 nella 1.0.0, 24 nella 1.1.
- La libreria nativa `libink.so` pesa circa 1,3–1,4 MB per arm64. Il core è in C++ e le implementazioni sono per Android e per JVM Linux x86_64 lato server.

Moduli:

- `ink-strokes`: `StrokeInputBatch`, `InProgressStroke`, `Stroke(brush, inputs)`. Il costruttore ricalcola la mesh; `Stroke.copy(brush)` è utile per ricolorare.
- `ink-brush`: `Brush.createWithColorLong(family, colorLong, size, epsilon)`, `BrushFamily`, `StockBrushes`, `BrushBehavior`, `BrushTip`, `BrushPaint`.
- `ink-authoring`: `InProgressStrokesView` (FrameLayout) per l'inchiostro "bagnato".
- `ink-authoring-compose`: `InProgressStrokes(defaultBrush, nextBrush, pointerEventToWorldTransform, strokeToWorldTransform, maskPath, textureBitmapStore, onStrokesFinished)`.
- `ink-rendering`: `CanvasStrokeRenderer.create()`, `ViewStrokeRenderer`. Dall'API 34, su canvas hardware, disegna con `android.graphics.Mesh`/`Canvas.drawMesh`; altrimenti ripiega su `drawPath`.
- `ink-geometry`: `Box`, `Vec`, `Parallelogram`, `PartitionedMesh` con `computeCoverage(…, AffineTransform)`, `computeCoverageIsGreaterThan` e `initializeSpatialIndex()`.
- `ink-storage`: codifica compatta (protobuf + delta + gzip) di `StrokeInputBatch` e `BrushFamily`.

**Perché la 1.1.0-alpha09 e non la 1.0.0.** Le alpha 1.1 correggono problemi che ci toccano da vicino:

- gestione dei campi opzionali di `MotionEvent` come tilt e pressione (alpha04);
- artefatti in virgola mobile nei tratti finiti "dopo aggiornamenti dell'OS" (alpha04);
- un memory leak di `InProgressStrokesView` (alpha06);
- una `RejectedExecutionException` (alpha05);
- il flicker di `maskPath` (alpha02).

In più rende **pubblica** l'API per costruire pennelli custom (alpha03), aggiunge l'encode/decode su `ByteArray`, incluso `encodeUncompressed` (alpha04), e introduce una gomma "parziale" sperimentale (`Stroke.subtract`/`split`, alpha05/08).

Il rischio è che l'API cambi tra una alpha e l'altra. Lo si contiene in due modi: tenere Ink dietro un sottile strato nostro, e **salvare nel file i nostri ID di pennello, non i proto di Ink** (§7). Se una alpha desse problemi, si torna alla 1.0.0.

### 1.2 Pressione e tilt nei pennelli

**StockBrushes** (1.0/1.1): `pressurePen()`, `marker()`, `highlighter(selfOverlap)`, `dashedLine()` (per il lasso) ed `emojiHighlighter()`. La 1.0.0 aveva anche un `pencilUnstable` sperimentale, che nella 1.1 non c'è più.

- Ogni famiglia ha una versione: `PressurePenVersion`, `MarkerVersion`, `HighlighterVersion`.
- **Va salvata la versione** usata, altrimenti un aggiornamento della libreria può cambiare l'aspetto dei tratti vecchi.
- La 1.1 aggiunge `Brush.Version` e `calculateMinimumRequiredVersion`.

`pressurePen` usa la pressione per la larghezza. `marker` ha larghezza quasi costante. `highlighter` va disegnato *sotto* l'inchiostro normale: basta un layer o un ordine di disegno separato per l'evidenziatore.

**Pennelli custom.** Si costruiscono in codice nella 1.1 (`BrushFamily(coats = listOf(BrushCoat(tip = BrushTip(...), paint = BrushPaint(...))))`), oppure si caricano da un proto gzip con `BrushFamily.decode(InputStream)`.

- Sorgenti di `BrushBehavior` disponibili: `NORMALIZED_PRESSURE`, `TILT_IN_RADIANS`, `TILT_X_IN_RADIANS`, `TILT_Y_IN_RADIANS`, `ORIENTATION_IN_RADIANS`, `SPEED_IN_MULTIPLES_OF_BRUSH_SIZE_PER_SECOND`, `DIRECTION_IN_RADIANS`, `DISTANCE_TRAVELED_…`, `TIME_SINCE_INPUT_…` e altre.
- Target disponibili: `WIDTH_MULTIPLIER`, `HEIGHT_MULTIPLIER`, `SIZE_MULTIPLIER`, `SLANT_OFFSET_IN_RADIANS`, `ROTATION_OFFSET_IN_RADIANS`, `PINCH_OFFSET`, `CORNER_ROUNDING_OFFSET`, `OPACITY_MULTIPLIER`, `HUE_OFFSET…`. Nella 1.1.0-alpha09 SATURATION e LUMINOSITY sono state rinominate in CHROMA e LIGHTNESS, con lo shift in Oklab.

Ricette per replicare i pennelli di Samsung Notes (da tarare a mano):

- **Penna a sfera**: `pressurePen`, oppure una punta tonda con `NORMALIZED_PRESSURE → WIDTH/SIZE` in una curva morbida (0,6–1,1×).
- **Stilografica / calligrafica**: punta ellittica o piatta (`BrushTip` con `scaleX≠scaleY`) a **rotazione fissa** di circa 30–45°. Così la larghezza dipende dalla direzione del tratto, come in un pennino vero. A questo si aggiunge `NORMALIZED_PRESSURE → SIZE_MULTIPLIER`.
- **Matita**: texture di grana in `BrushPaint`, con `NORMALIZED_PRESSURE → OPACITY_MULTIPLIER`. Con `TILT_IN_RADIANS → WIDTH_MULTIPLIER` la penna inclinata fa l'ombreggiatura.
- **Evidenziatore**: `highlighter(SelfOverlap…)` con alfa circa 0,35.
- **Pennello**: `SPEED → WIDTH` inverso e pressione forte. È per la v2.

**Assi reali del Tab S6 Lite su AOSP.** Li ho verificati in `TouchInputMapper.cpp` di Android 16.

- `ABS_PRESSURE` 0..4095 viene normalizzata a 0..1 in `AXIS_PRESSURE`: con un asse pressione la calibrazione è "physical" con scala 1/max.
- `ABS_TILT_X/Y` −63..63 senza risoluzione viene interpretato **in gradi**: la scala è `M_PI/180`.
- Da qui il sistema ricava `AXIS_TILT = acos(cos x·cos y)` (0 = penna perpendicolare, circa 1,1 rad al massimo) e `AXIS_ORIENTATION = atan2(−sin x, sin y)`, ruotato con lo schermo.
- Quindi **tilt e orientamento arrivano all'app anche senza SDK Samsung**, e Ink li usa se il pennello li richiede.
- `ABS_DISTANCE` 0..255 arriva come `AXIS_DISTANCE` ed è utile solo per l'anteprima in hover.

### 1.3 Serializzazione (ink-storage)

- 1.0.0: `stroke.inputs.encode(outputStream)` e `StrokeInputBatch.decode(inputStream)`, oppure `StrokeInputBatchSerialization.encode/decode`. Il risultato è un proto compresso gzip.
- 1.1: anche `encode(): ByteArray`, `decode(ByteArray)`, `encodeUncompressed()` e `decodeUncompressed(DecompressedBytes)`.
  - La versione non compressa serve se si comprime a livello di contenitore, o se si vuole leggere un singolo tratto senza pagare lo gzip.
- Di un tratto si salvano **gli input**, non la mesh: non esiste un'API pubblica per serializzare la `PartitionedMesh`.
  - La conseguenza pratica è che **all'apertura ogni `Stroke(brush, inputs)` ricalcola la geometria**. È CPU in proporzione al numero di punti.
  - Per questo la nota si apre mostrando **prima un raster in cache**, e i tratti vettoriali vengono ricostruiti in background (§7, §8).
- Il `Brush` si salva a parte: il nostro ID di famiglia con la sua versione, `colorLong`, `size` ed `epsilon`. La guida ufficiale lo raccomanda esplicitamente al posto di serializzare la `BrushFamily`.

### 1.4 Sistema di coordinate ed epsilon

Ink chiede di fissare per sempre l'unità "mondo" e l'epsilon; la guida suggerisce 1 dp ed epsilon 0,025 per un zoom 10×.

Per Tratto: **unità mondo = 1 punto PDF (1/72″)** per pagina, quindi A4 = 595×842.

- A zoom 100% in verticale la pagina è larga 1200 px, cioè circa 2,02 px per punto.
- Per avere arrotondamenti ≤ 1 px fino a 8× serve epsilon ≤ 1/(2,02·8) ≈ 0,06. **Scelta: epsilon 0,05**, salvato comunque nel file.
- Il vantaggio è che le pagine PDF e le pagine bianche condividono le stesse coordinate, e l'esportazione in PDF non richiede conversioni (§5).

### 1.5 Compose o View per la superficie

`InProgressStrokes` in Compose va bene per demo, ma prende tutti i puntatori da sé e offre poco controllo: niente accesso diretto a `maskPath`, `eagerInit()`, `requestHandoff()`, `flush()/sync()`, `LatencyDataCallback` o `useHighLatencyRenderHelper`, e non permette di decidere pointer per pointer. **Per Tratto si usa la View** `InProgressStrokesView`:

- `startStroke(event, pointerId, brush, motionEventToWorldTransform, strokeToWorldTransform)`;
- `addToStroke(event, pointerId, strokeId, predictedEvent)`, che consuma anche i punti storici;
- `finishStroke` e `cancelStroke(strokeId, event)`;
- `addFinishedStrokesListener { onStrokesFinished(Map<InProgressStrokeId, Stroke>) }` + `removeFinishedStrokes(ids)`;
- `maskPath`, per escludere le zone della toolbar;
- `eagerInit()`, da chiamare subito dopo l'attach per non pagare l'inizializzazione al primo tratto.

**Rendering interno di Ink.** Sull'API 33+ usa un proprio helper front-buffer (`CanvasInProgressStrokesRenderHelperV33`: `SurfaceView` + `SurfaceControl` + `HardwareBuffer` + `RenderNode`). Verifica con `HardwareBuffer.isSupported` il supporto `USAGE_FRONT_BUFFER` e ripiega da solo se manca. Sulle API 29–32 usa `CanvasFrontBufferedRenderer` di graphics-core.

Il wet layer è una `SurfaceView` messa sopra la finestra, e da questo discendono due cose:

1. Toolbar e popup sopra il canvas vanno **mascherati** con `maskPath`, altrimenti l'inchiostro bagnato ci passa sopra.
2. Il "passaggio di consegne" (handoff) va gestito bene. Quando arriva `onStrokesFinished` si aggiunge il tratto al layer secco, si invalida, e si chiama `removeFinishedStrokes` nello stesso frame. Così non c'è flicker.

### 1.6 Confronto con graphics-core diretto e motion prediction

- **`GLFrontBufferedRenderer` / `CanvasFrontBufferedRenderer`** (`androidx.graphics:graphics-core:1.0.4`, API 29+) sono i mattoni su cui è costruito Ink.
  - Usarli direttamente significa riscrivere modellazione degli input, smoothing, mesh dei pennelli e handoff: settimane di lavoro per ottenere quello che Ink già fa.
  - Hanno senso solo per un motore di rendering tutto proprio in GL/Vulkan, e non è il nostro caso.
  - Il front-buffer serve solo per aree piccole, non per ridisegnare tutto lo schermo (possibile tearing).
- **`MotionEventPredictor`** (`input-motionprediction:1.0.0`): `newInstance(view)`, `record(event)` a ogni evento, `predict()` sul MOVE da passare a `addToStroke`, e `recycle()` dell'evento predetto.
  - Dall'API 34 usa il `MotionPredictor` di sistema se `isPredictionAvailable`; altrimenti usa un **filtro di Kalman** interno. Su LineageOS è probabile che si finisca sul Kalman, e va bene così.
  - La guida Ink dice di usare comunque la versione Jetpack anche con minSdk 34.
  - I punti predetti vengono sostituiti da quelli reali; `StockBrushes.predictionFadeOutBehavior` sfuma la parte predetta.
- La latenza si misura con `InProgressStrokesView.latencyDataCallback` (`@ExperimentalInkLatencyDataApi`). `useHighLatencyRenderHelper = true` serve a confrontare con e senza front-buffer.
  - Se il Mali-G72 non supporta `USAGE_FRONT_BUFFER`, lo si vede subito da questo confronto: va controllato sul device (§9).

### 1.7 Architettura raccomandata della superficie

```
EditorActivity (ComponentActivity; windowBackground = colore carta; splash a tema)
└─ NoteCanvasLayout : FrameLayout      ← UN solo punto di input (onTouch/onHover/onGenericMotion)
   ├─ PageLayerView : View             ← "inchiostro secco" + sfondo
   │    onDraw: per ogni pagina visibile → canvas.concat(camera) → drawRenderNode(page.node)
   │    page.node (RenderNode, registrato una volta, ri-registrato solo se la pagina cambia):
   │       template procedurale (righe/quadretti/puntini) → tile PDF (Bitmap) → evidenziatori → tratti
   │       tratti disegnati con CanvasStrokeRenderer (Mesh su API 34+) + transform per-tratto
   │    nodo "delta" per gli ultimi tratti (evita di ri-registrare pagine con migliaia di tratti)
   ├─ InProgressStrokesView           ← inchiostro "bagnato", front-buffer, maskPath = area toolbar
   ├─ OverlayView : View              ← lasso (dashedLine), maniglie selezione, cursore hover (anteprima punta/gomma)
   └─ ComposeView (toolbar/pannelli)  ← aggiunta DOPO il primo frame (doOnPreDraw/postFrameCallback)
```

Flusso degli eventi:

- **DOWN della penna**:
  1. `view.requestUnbufferedDispatch(event)`;
  2. `predictor.record`;
  3. `startStroke(event, id, brush, inverse(camera))`.
- **MOVE**: `addToStroke(event, id, strokeId, predictor.predict())`.
- **UP**: `finishStroke`.
- **CANCEL, o `FLAG_CANCELED` sull'UP**: `cancelStroke`.
- **Tratto finito**: si aggiunge al modello della pagina e al nodo delta, `removeFinishedStrokes` avviene nello stesso frame, e il record si accoda al log su disco (thread IO).
- **Pan e zoom a due dita**: cambia solo la matrice della camera e i `RenderNode` vengono riprodotti così come sono. È economico, perché la GPU rigioca la display list e le mesh restano vettoriali, quindi nitide. Solo a gesto fermo si ri-renderizzano le tile PDF alla nuova risoluzione.
- **Pagine molto dense** (migliaia di tratti): se il replay del RenderNode supera il budget di frame, si passa a una cache di tile bitmap per livello di zoom (es. 512×512) con LRU di circa 64–96 MB.
  - Attenzione: `Canvas.drawMesh` funziona solo su canvas **hardware**.
  - Per miniature ed export conviene `Picture` → `Bitmap.createBitmap(picture)`, che produce una bitmap hardware, poi `copy(ARGB_8888)` prima di comprimere. Su canvas software Ink ripiega sul path renderer, più lento.

---

## 2. Input dello stilo su ROM non Samsung

### 2.1 Cosa arriva in `MotionEvent`

| Dato | API |
|---|---|
| Tipo di strumento | `getToolType(i)` = `TOOL_TYPE_STYLUS`, oppure `TOOL_TYPE_ERASER` quando il driver alza `BTN_TOOL_RUBBER`. Sulla penna del Tab S6 Lite non c'è una punta gomma: va verificato con `getevent` se `BTN_TOOL_RUBBER` viene mai emesso (§9). Sorgente: `SOURCE_STYLUS \| SOURCE_TOUCHSCREEN`. |
| Pressione | `getPressure(i)` / `AXIS_PRESSURE`, 0..1 (normalizzarla comunque se supera 1). |
| Tilt / orientamento | `getAxisValue(AXIS_TILT, i)` in radianti; `AXIS_ORIENTATION`. |
| Hover | `ACTION_HOVER_ENTER/MOVE/EXIT` su `onHoverEvent` / `dispatchHoverEvent`; `AXIS_DISTANCE`. |
| Tasto laterale | `buttonState and BUTTON_STYLUS_PRIMARY`, sia in hover sia a contatto. `ACTION_BUTTON_PRESS/RELEASE` con `actionButton` arrivano via `onGenericMotionEvent`, perché non sono "touch event". |
| Campioni coalescenti | `historySize` + `getHistoricalX/Y/Pressure/AxisValue(axis, i, pos)` e `getHistoricalEventTime`. Ink li consuma da solo in `addToStroke`. |
| Consegna immediata | `View.requestUnbufferedDispatch(event)` sul DOWN, valida per la durata del gesto. Esiste anche `requestUnbufferedDispatch(source)` dall'API 30, persistente, utile per l'hover. Evita l'accorpamento degli eventi a vsync. |

Verificato nei sorgenti di Android 16:

- `TouchInputMapper` emette `ACTION_BUTTON_PRESS` e `ACTION_BUTTON_RELEASE` anche per gli stili.
- Rimuove `BUTTON_STYLUS_PRIMARY/SECONDARY` da `buttonState` se `Settings.Secure.STYLUS_BUTTONS_ENABLED` = 0; il default è 1.
- **Consiglio**: ricavare i fronti di pressione del tasto dal `buttonState` di **ogni** evento (touch, hover, generic) invece di affidarsi solo a `ACTION_BUTTON_PRESS`. È più robusto.

**Limite fisico.** La S Pen del Tab S6 Lite è EMR passiva: niente Bluetooth, niente Air Actions. Il tasto viene letto **solo quando la penna è nel raggio di hover**, circa 1–1,5 cm dallo schermo.

### 2.2 Palm rejection

**Livello di sistema (Android 16, `InputDispatcher`/`InputState`).** Con il flag `enable_multi_device_same_window_stream` spento, una finestra accetta un solo dispositivo di input alla volta, e **lo stilo ha la precedenza**.

- Se lo stream della penna è attivo, in hover o a contatto, gli eventi touch del touchscreen vengono **scartati** per quella finestra.
- Se c'è già un dito o un palmo appoggiato e arriva la penna (`HOVER_ENTER` o `DOWN`), il gesto del dito riceve **`ACTION_CANCEL` con `FLAG_CANCELED`**.
- Nella release config di Android 16 (bp1a) il flag ha un valore solo in `trunk_staging`, quindi è probabilmente spento. Va verificato: `adb shell aflags list | grep same_window`.
- In pratica, con la penna vicina allo schermo il palmo viene già ignorato gratis.

**Livello app** (serve comunque):

1. Modalità **"disegna solo con la penna"** attiva di default, come in Samsung Notes: disegnano solo `TOOL_TYPE_STYLUS/ERASER`. Le dita servono per scorrere (un dito in modalità scroll) e per pan e zoom (due dita).
2. Si ignorano i tocchi di dita per circa 300–500 ms dopo l'ultimo evento della penna (anche dopo `HOVER_EXIT`), e i contatti con `getTouchMajor()` o `getSize()` troppo grandi (palmo).
3. `ACTION_CANCEL` e `FLAG_CANCELED` (API 33) sull'UP portano a `cancelStroke` e all'annullamento dell'ultimo tratto di quel pointer.
4. Nessun gesto di sistema sui bordi durante la scrittura: `WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` in modalità immersiva opzionale, e `systemGestureExclusionRects` per le zone laterali (massimo 200 dp per lato).

### 2.3 Doppio clic del tasto S Pen DENTRO l'app

Fattibile e affidabile, con la penna in hover o a contatto:

- Si tiene lo stato precedente di `BUTTON_STYLUS_PRIMARY` e si considera ogni transizione 0→1 come una pressione.
- Due pressioni entro `ViewConfiguration.getDoubleTapTimeout()` (300 ms, da portare a 350 se serve) e con spostamento minore di `touchSlop` equivalgono a un **doppio clic**, che crea subito una nuova nota.
- Per non far attendere il timeout alla pressione singola conviene che l'azione singola sia **momentanea**, come in Samsung Notes: *gomma, o lasso, finché il tasto è tenuto premuto*. In questo modo pressione singola e doppio clic non entrano in conflitto.
- "Nuova nota istantanea" significa:
  - creare l'UUID in memoria e mostrare la pagina bianca subito;
  - la cartella su disco nasce al primo tratto;
  - nessun I/O sul main thread.
- Il listener va messo anche sulle schermate di lista, per ottenere il doppio clic ovunque nell'app.

### 2.4 Doppio clic a livello di sistema: risposta onesta

**Da un'app normale, senza root, non è possibile.** Le prove, dai sorgenti di Android 16 e dai device tree LineageOS 23.2:

1. `EventHub` classifica come KEYBOARD i dispositivi che hanno `BTN_STYLUS`, e `Generic.kl` mappa `key 331` (BTN_STYLUS) su `STYLUS_BUTTON_PRIMARY`.
   - Il tasto genera quindi anche un **`KeyEvent KEYCODE_STYLUS_BUTTON_PRIMARY`**.
   - `PhoneWindowManager.interceptKeyBeforeQueueing` lo **toglie dal flusso utente** (`result &= ~ACTION_PASS_TO_USER`) e lo passa solo a SystemUI.
2. Gli **AccessibilityService** con `FLAG_REQUEST_FILTER_KEY_EVENTS` non lo vedono: `AccessibilityInputFilter` salta gli eventi senza `FLAG_PASS_TO_USER`.
3. **Accessibility con `setMotionEventSources(SOURCE_STYLUS)`** (API 34): l'evento arriva al servizio, ma viene **consumato**. La penna smetterebbe di funzionare in tutte le altre app, perché un servizio di accessibilità non può reiniettare eventi stilo con pressione.
   - La variante "osserva senza consumare" (`setObservedMotionEventSources`) è `@TestApi` e nascosta, quindi non usabile.
4. **Overlay** (`TYPE_APPLICATION_OVERLAY`): una finestra toccabile a tutto schermo ruberebbe l'input alle app sotto; una non toccabile non riceve hover né pulsanti. Anche questa strada è inutilizzabile.
5. **`InputMonitor` e le spy window** richiedono `MONITOR_INPUT` (permesso di sistema).
6. **Ruolo Notes** (`RoleManager.ROLE_NOTES`, `Intent.ACTION_CREATE_NOTE`, `EXTRA_USE_STYLUS_MODE`):
   - `NotesRoleBehavior` rende il ruolo disponibile solo se `config_enableDefaultNotes=true`. AOSP ha **false**, e né `android_device_samsung_gta4xl-common`, né `gta4xlwifi`, né `vendor/lineage` lo sovrascrivono.
   - Anche se fosse attivo, in AOSP si attiva solo con:
     - il tasto **coda** (`KEYCODE_STYLUS_BUTTON_TAIL` → `KEY_GESTURE_TYPE_OPEN_NOTES`), che la S Pen non ha;
     - la scorciatoia da tastiera;
     - la scorciatoia della schermata di blocco;
     - la tile QS "Note".
   - Il ruolo inoltre è `requestable="false"` in `roles.xml`, quindi va assegnato da Impostazioni → App predefinite, non con `createRequestRoleIntent`.

Conviene comunque dichiarare già ora l'activity `CREATE_NOTE`, con `showWhenLocked`, `turnScreenOn` ed `exported`: non costa nulla.

### 2.5 Le alternative migliori, in ordine di praticità

1. **Doppio clic in app** (§2.3), sempre attivo.
2. **Tile nelle Impostazioni rapide** ("Nuova nota"): un `TileService`.
   - Sull'API 34+ va usato `startActivityAndCollapse(PendingIntent)`: la variante con `Intent` lancia un'eccezione con targetSdk ≥ 34.
   - La tile funziona anche dalla schermata di blocco con un'activity `showWhenLocked` che apre **solo** una nuova nota: è il sostituto del "memo a schermo spento".
   - `isLocked()`/`isSecure()` e `unlockAndRun{}` servono a chiedere lo sblocco quando si vuole la libreria.
3. **Voce launcher dedicata**: un `activity-alias` "Nuova nota" con categoria `LAUNCHER` e icona propria, più una shortcut statica `shortcuts.xml` (pressione lunga sull'icona).
   - **Lawnchair** permette "Doppio tap sulla home → Apri app". Il suo `OpenAppGestureHandler` avvia la **main activity** di un componente, mentre il target "Shortcut" è un no-op nel codice attuale.
   - Quindi va scelto l'alias "Tratto · Nuova nota", **non** una shortcut.
4. **Tratto come "Assistente digitale"**. `AssistantRoleBehavior` qualifica qualsiasi app con un'activity esportata che gestisce `android.intent.action.ASSIST` (categoria DEFAULT). A quel punto:
   - lo **swipe dall'angolo in basso** (navigazione a gesti), fattibile anche con la penna,
   - oppure **pressione lunga del tasto power**, se impostata su "Assistente digitale",

   aprono una nuova nota da qualsiasi app. Si perde Gemini/Assistant come assistente. **È il gesto di sistema più vicino a quello che si chiedeva.** Va verificato sul device che lo swipe dall'angolo sia attivo con un assistente a sola activity.
5. **Esperimento senza root: Shizuku.** L'utente shell (uid 2000) è nel gruppo `input`, infatti `adb shell getevent` funziona anche su build non root.
   - Un `UserService` Shizuku (API `dev.rikka.shizuku:api:13.1.5`) può leggere `/dev/input/eventN` della `sec_e-pen`, senza `EVIOCGRAB` per non disturbare le altre app.
   - Da lì rileva il doppio `BTN_STYLUS` in hover **ovunque** e lancia `NewNoteActivity`.
   - Contro: dopo ogni riavvio Shizuku va riattivato (debug wireless o `adb`); la compatibilità dell'app Shizuku 13.6.0 con Android 16 QPR2 va verificata; è una dipendenza "fragile". Va tenuto come feature opzionale di v2.
   - LineageOS ha anche "Rooted debugging" (`adb root`), ma è root.
6. **Lock screen**: in AOSP le scorciatoie del lock screen sono fisse (torcia, fotocamera, QR…, "Note" solo con il ruolo). Resta la tile QS (punto 2).

### 2.6 Altri dettagli di input

- Nei campi di testo (caselle di testo della nota), Android 14+ avvia la **scrittura a mano dell'IME** (`View.setAutoHandwritingEnabled`, Gboard) quando la penna scrive su un `EditText`. Va disattivata sulle viste del canvas, oppure sfruttata di proposito per le caselle di testo.
- Una stessa finestra può ricevere eventi da due device diversi (penna e dito): va sempre filtrato per `toolType` e `deviceId`.

---

## 3. Funzionalità di Samsung Notes (era One UI 6/7/8) da replicare

Legenda: **v1** = MVP, **v2** = seconda release, **poi** = più avanti, **no** = fuori scope.

| Funzione (Samsung Notes) | Priorità | Implementazione in Tratto |
|---|---|---|
| Penne: penna (sfera), stilografica, calligrafica, matita, evidenziatore, pennello; colori, spessore, preferiti | **v1**: penna, stilografica, evidenziatore, pennarello, 3–5 preset preferiti, palette + colore custom. **v2**: matita (texture + tilt), calligrafica, contagocce. **poi**: pennello | Ink `StockBrushes` + pennelli custom (§1.2) |
| Gomma per tratto / per area, dimensione, "solo evidenziatore" | **v1**: per tratto (`computeCoverageIsGreaterThan` con un parallelogramma lungo il movimento) e dimensione. **v2**: per area e "solo evidenziatore" | La gomma per area passa dalla gomma parziale sperimentale di Ink 1.1 (`Stroke.subtract/split`) oppure dallo spezzare `StrokeInputBatch` sui segmenti colpiti |
| Tasto S Pen tenuto = gomma o selezione; doppio clic | **v1** | §2.3 |
| Selezione lasso o rettangolo: sposta, ridimensiona, ruota, ricolora, spessore, copia, taglia, incolla, avanti/dietro | **v1**: lasso, sposta, ridimensiona, elimina, ricolora (`Stroke.copy(brush)`), copia/incolla. **v2**: rettangolo, ruota, ordine Z, cambio spessore | Lasso come `dashedLine`, poi `MeshCreation.createClosedShape(inputs)` e `computeCoverage > 0,5`. Una **trasformazione affine per tratto** salvata nel file evita di ricalcolare gli input |
| Correzione forme, linea dritta tenendo ferma la penna | **v1.1**: "tieni premuto → linea dritta". **v2**: forme (cerchio/ellisse, rettangolo, triangolo, freccia) | Rilevamento: penna ferma oltre ~450 ms entro pochi px. Fitting geometrico proprio, deterministico e offline; in opzione ML Kit `zxx-Zsym-x-shapes` (RECTANGLE/TRIANGLE/ARROW/ELLIPSE) |
| Annulla / ripeti | **v1** | Stack di comandi per nota (aggiungi, rimuovi, trasforma) |
| Zoom e pan | **v1** (pinch a due dita, 50–800%) | Camera + RenderNode (§1.7) |
| Tipi di pagina: pagine (verticale/orizzontale), scroll lungo, canvas infinito | **v1**: pagine A4/A5 verticali e orizzontali, aggiunta pagine in fondo. **v2**: scroll verticale continuo. **poi**: canvas infinito (tile a griglia) | |
| Modelli di pagina: bianco, righe, quadretti, puntini, copertine | **v1**: i quattro base, procedurali (niente bitmap). **poi**: copertine e template custom da PDF | |
| Caselle di testo (formattazione) | **v2** | `EditText` sopra il canvas; testo salvato nel JSON della pagina |
| Immagini (inserisci, ritaglia) | **v2** | Photo Picker (`PickVisualMedia`) senza permessi; asset per hash |
| Import PDF e annotazione | **v1**: import, annotazione, esporta "appiattito". **v2**: annotazioni `/Ink` modificabili | §5 |
| Cartelle, preferiti, tag | **v1**: cartelle e preferiti. **v2**: tag | Room |
| Blocco nota | **v2** | `BiometricPrompt` di piattaforma con `DEVICE_CREDENTIAL`. Crittografia dei file opzionale **poi** |
| Ricerca: titoli e testo digitato / scrittura a mano / testo PDF | **v1**: titoli e testo PDF (`getTextContents`, API 35). **v2**: scrittura a mano (indice ML Kit in background) | FTS4 (§7) |
| Converti scrittura in testo | **v2** | ML Kit (§4) |
| Registrazione audio sincronizzata ai tratti | **poi** | `MediaRecorder` AAC m4a 64 kbps mono. Ogni tratto memorizza l'istante di creazione rispetto all'inizio della registrazione; in riproduzione si evidenziano i tratti |
| Esporta PDF / immagine / Word | **v1**: PDF e PNG della pagina. **poi**: DOCX (zip XML scritto a mano con immagini + testo riconosciuto) | §5 |
| Memo a schermo spento | **no** (hardware e servizi Samsung). Sostituto: tile QS sul lock screen (§2.5) | |
| Cestino ("eliminati di recente") | **v1** (costa poco, salva vite) | |
| Salvataggio automatico | **v1** | Log append-only (§7) |
| Backup/sync | **v1**: backup Drive automatico. **v2**: multi-dispositivo con conflitti | §6 |
| Galaxy AI (Note Assist, Transcript Assist, Math Solver, Drawing Assist), condivisione collaborativa, sync OneNote | **no** / poi | |
| Toolbar personalizzabile (One UI 8), orientamento orizzontale | **v2** | Su Android 16 con targetSdk 36 l'orientamento **non si può bloccare** su schermi ≥ 600 dp (§8.6) |

---

## 4. Riconoscimento della scrittura on-device (ML Kit Digital Ink)

- **Dipendenza**: `com.google.mlkit:digital-ink-recognition:19.0.0`, minSdk 23.
  - La libreria nativa `libdigitalink.so` pesa **6,9 MB** per arm64: **filtrare le ABI** (§8).
  - Si trascina `play-services-base`, `firebase-components`, `transport-*` e **okhttp 3.12.1**. Se in app si aggiunge OkHttp 5, Gradle la porta alla 5.x per tutti: il download dei modelli va testato. Per Drive consiglio `HttpURLConnection`, così il problema non si pone.
- **Italiano**: tag BCP-47 `it` (esistono anche `it-IT`, `it-CH` e varianti regionali; gesti: `it-x-gesture`).
  - Dal manifest dei modelli dentro l'AAR 19.0.0, il pacchetto `it` è un modello linguistico FST di **10,5 MB compressi / 22 MB espansi** più il modello LSTM latino condiviso di **3,4 MB / 4 MB**.
  - Totale circa **14 MB da scaricare e circa 26 MB su disco**; la documentazione dice "circa 20 MB per lingua".
  - I modelli arrivano da `dl.google.com/handwriting/models/…` e sono datati 2019–2020: vecchi ma funzionanti.
- **API**:
  1. `DigitalInkRecognitionModelIdentifier.fromLanguageTag("it")` → `DigitalInkRecognitionModel.builder(id).build()`;
  2. `RemoteModelManager.getInstance().download(model, DownloadConditions.Builder().requireWifi().build())` e `isModelDownloaded(model)`;
  3. `DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())`;
  4. `recognizer.recognize(ink, RecognitionContext.builder().setPreContext(prev).setWritingArea(WritingArea(w, h)).build())` → `RecognitionResult.candidates[i].text`.
  - Costruzione dell'input: `Ink.builder().addStroke(Ink.Stroke.builder().addPoint(Ink.Point.create(x, y, t))…)`. I timestamp migliorano l'accuratezza; li abbiamo già negli input Ink.
- **Classificatori di forme**: `zxx-Zsym-x-shapes` (circa 40 KB, restituisce RECTANGLE/TRIANGLE/ARROW/ELLIPSE), `zxx-Zsym-x-autodraw` ed emoji. Interpretano l'intero Ink come **una** forma.
- **Avvio**: ML Kit registra un `MlKitInitProvider` (ContentProvider) che gira a **ogni cold start**. Va rimosso con `tools:node="remove"` nel manifest e sostituito da `MlKit.initialize(context)` lazy, al primo uso in background.
- **Strategia per la ricerca**:
  1. debounce di 2–3 s dopo l'ultimo tratto, e worker a bassa priorità;
  2. segmentazione in **righe**: clustering per sovrapposizione verticale dei bounding box e ordinamento per x;
  3. riconoscimento di ogni riga con `WritingArea(larghezza riga, altezza riga)` e `preContext` = ultimi circa 20 caratteri della riga precedente;
  4. i **primi 3 candidati** vanno in FTS (il primo visibile, gli altri in una colonna nascosta) insieme al bbox, così i risultati si possono evidenziare sulla pagina;
  5. per ogni pagina si salva la `strokeRevision` riconosciuta, per non ripetere il lavoro.
- **"Converti in testo"**: tratti selezionati → riconoscimento → scelta tra i candidati → sostituzione con una casella di testo, conservando i tratti originali per l'annulla.
- Tutto on-device dopo il download: niente cloud. Con MindTheGapps funziona. Va verificato che il download dei modelli non richieda altro che la rete.

---

## 5. PDF: import, annotazione, esportazione

### 5.1 Rendering

- **`android.graphics.pdf.PdfRenderer`** (pdfium, in piattaforma, zero dipendenze).
  - Dall'API 35 c'è anche `Page.getTextContents()`, `searchText()`, `selectContent()`, form (`getFormWidgetInfos`, `applyEdit`) e `PdfRenderer.write(pfd, removePasswordProtection)`.
  - Serve un `ParcelFileDescriptor` **seekable**, quindi il PDF va copiato nella cartella della nota (comunque lo facciamo).
  - **Si può aprire una sola pagina alla volta** per istanza e l'oggetto **non è thread-safe**. Soluzione: un thread o dispatcher dedicato per documento e `page.render(bitmap, destClip, matrix, RENDER_MODE_FOR_DISPLAY)` per le tile.
- **Android 16 QPR2 (API 36.1, "S Extensions 18")** aggiunge l'editing:
  - `Page.addPageObject(PdfPagePathObject/ImageObject/TextObject)`, `updatePageObject`, `removePageObject`;
  - `addPageAnnotation(StampAnnotation | HighlightAnnotation | FreeTextAnnotation)`.
  - **Non esiste un tipo `InkAnnotation`**; lo `StampAnnotation` può però contenere oggetti path.
  - LineageOS 23.2 è QPR2, quindi dovrebbe esserci. Controllo a runtime: `Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1` oppure `SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 18`.
- **`androidx.pdf` 1.0.0-beta01** (minSdk 28):
  - comprende `PdfViewerFragment`, `EditablePdfViewerFragment` con annotazioni basate su Ink (penna, evidenziatore, gomma, annulla), il composable `PdfViewer` e `PdfWriteHandle` per salvare;
  - il documento viene gestito in un **servizio `isolatedProcess`** (`pdf-document-service`).
  - È pensato come *viewer* completo, non come layer dentro un canvas di note; aggiunge un processo, avvio più lento e molte API `@ExperimentalPdfApi`.
  - Giudizio: **non va usato come base**; è utile da studiare e, se serve, come visore rapido "apri con".

### 5.2 Modello di annotazione (per tenere il testo selezionabile)

- Il PDF originale resta **immutato** in `assets/<sha256>.pdf`. Una pagina di nota di tipo `pdf` punta a `(asset, pageIndex)` e ne eredita dimensioni, `CropBox` e `/Rotate`.
- I tratti vivono nel nostro log, in coordinate pagina in **punti PDF** (§1.4), e sono quindi riutilizzabili, modificabili e ricercabili.
- In visualizzazione: tile PDF sotto e tratti sopra, nello stesso `RenderNode`.

### 5.3 Esportazione in un PDF vero

1. **Default, "appiattito vettoriale".** Per ogni pagina si aggiunge un nuovo content stream (`PDPageContentStream(doc, page, AppendMode.APPEND, compress = true, resetContext = true)`) che **riempie i contorni** dei tratti.
   - I contorni vengono da Ink: `stroke.shape.getOutlineCount(g)`, `getOutlineVertexCount(g, o)`, `populateOutlinePosition(...)`, con riempimento nonzero.
   - Evidenziatori: `PDExtendedGraphicsState` con alfa circa 0,35 e blend `Multiply`.
   - Conversioni: la Y va capovolta (origine PDF in basso a sinistra), e vanno considerati `/Rotate` e CropBox.
   - **Il content stream originale non si tocca**, quindi il testo resta selezionabile e ricercabile e il file resta leggero, perché è vettoriale.
2. **Opzione "modificabili" (v2).** Si crea `PDAnnotationMarkup` con subtype `/Ink`:
   - `/InkList` = linee centrali (serve per Acrobat, Xodo e altri);
   - **`/AP` personalizzato** con i contorni pieni, perché `/Ink` ha larghezza costante (`/BS`) e perderebbe la pressione;
   - `/NM "tratto:<uuid>"`, per riconoscere le nostre annotazioni se il PDF viene reimportato.
3. **Alternativa di piattaforma (36.1).** Per ogni tratto: `PdfPagePathObject(path)` con colore di riempimento → `page.addPageObject(...)`, oppure dentro uno `StampAnnotation`. Alla fine `renderer.write(pfd, false)`.
   - Vantaggi: pdfium nativo, veloce, zero MB di libreria.
   - Svantaggi: API nuovissima e disponibile solo su 36.1+.
   - Si valuta in v2: se funziona bene sul device, si può eliminare PdfBox.

### 5.4 PdfBox-Android: note

- `com.tom-roush:pdfbox-android:2.0.27.0`: **Apache-2.0**, basata su PDFBox 2.0.27.
  - Ultima release **gennaio 2023**, ultimo commit dicembre 2023, circa 126 issue aperte: **manutenzione ferma**.
  - AAR di circa 3,3 MB; R8 lo riduce.
  - Il package è `com.tom_roush.pdfbox.*`. Ho verificato che ci sono `PDAnnotationMarkup.SUB_TYPE_INK`/`setInkList(float[][])`, `PDExtendedGraphicsState.setBlendMode`/`setNonStrokingAlphaConstant` e `MemoryUsageSetting.setupTempFileOnly()`.
- Dipende da **BouncyCastle 1.72** (`bcprov`, `bcpkix`, `bcutil`), che ha CVE note ed è pesante:
  - se non servono PDF cifrati, **escluderla** (`exclude(group = "org.bouncycastle")` + `-dontwarn org.bouncycastle.**`);
  - altrimenti forzare una versione recente.
- Va usata **solo nel worker di esportazione**, in background, con `MemoryUsageSetting.setupTempFileOnly()` per i PDF grandi (4 GB di RAM).
- Da scartare per licenza: iText e MuPDF (AGPL); OpenPDF dipende da java.awt.

---

## 6. Backup su Google Drive

### 6.1 Opzioni a confronto

| | (a) Drive REST v3 + GIS `AuthorizationClient` | (b) SAF → DocumentsProvider di Drive | (c) Auto Backup Android |
|---|---|---|---|
| Controllo | Totale: incrementale, per nota, conflitti, ripristino selettivo | Scarso: Drive **non supporta la scelta di cartelle** (`ACTION_OPEN_DOCUMENT_TREE`); si possono creare o aprire solo singoli file. L'upload è asincrono e gestito dall'app Drive (che MindTheGapps **non** include di default) | Nessuno: tutto o niente |
| Limiti | Quota Drive dell'utente | Permessi persistenti fragili, nessuna notifica di completamento | **25 MB per app**, una volta al giorno (idle, Wi-Fi, 24 h), solo l'ultimo backup. Oltre i 25 MB: `onQuotaExceeded` e nessun backup |
| Con MindTheGapps | Funziona (GMS fornisce Identity) | Serve installare l'app Drive | Dipende dal transport Google (`bmgr list transports`) |
| Verdetto | **Scelta** | No | Solo per le impostazioni (`dataExtractionRules`: includere SharedPreferences/DataStore, escludere note e DB) |

### 6.2 Setup in Google Cloud Console (una volta)

1. Creare il progetto **"Tratto"** in console.cloud.google.com.
2. In **APIs & Services → Library** abilitare **Google Drive API**.
3. In **Google Auth Platform → Branding** inserire nome app, email di supporto e contatto sviluppatore.
   - Meglio lasciare vuoto il logo: caricarlo può far scattare la verifica del brand.
4. In **Audience** scegliere tipo **External**.
   - In stato **Testing** servono i *test user* (massimo 100; aggiungere il proprio account). **Le autorizzazioni dei test user scadono dopo 7 giorni** dal consenso.
   - Per questo va fatto **"Publish app" → In production**. Con soli scope *non sensibili* (`drive.file`, `drive.appdata`) non serve verifica Google.
5. In **Data Access** aggiungere lo scope `https://www.googleapis.com/auth/drive.file` (ed eventualmente `drive.appdata`).
6. In **Clients → Create client → Android** inserire il package name (es. `it.frumorn.tratto`) e l'**SHA-1** del certificato di firma: `keytool -list -v -keystore tratto-release.jks -alias tratto`.
   - Serve **un client per ogni SHA-1**: creane un secondo per `~/.android/debug.keystore` (password `android`), oppure firma anche il debug con la chiave di release.
   - Nel codice **non** va messo nessun client ID e non serve `google-services.json`: Play services riconosce l'app da package + SHA-1.
   - Il client "Web application" serve solo per ottenere un `serverAuthCode` per un backend, cosa che non ci riguarda.

### 6.3 Autorizzazione (on-device)

- La richiesta si costruisce con `Identity.getAuthorizationClient(context).authorize(AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DRIVE_FILE))).build())`.
  - Se `hasResolution()`, si lancia `pendingIntent` con `ActivityResultContracts.StartIntentSenderForResult` e si legge il risultato con `getAuthorizationResultFromIntent`.
  - Altrimenti `accessToken` è già disponibile.
- Dopo il primo consenso, **ogni successiva `authorize()` restituisce un token senza UI** (validità circa 1 h, messo in cache da Play services). Si può fare anche dal Worker con il `Context` dell'applicazione.
  - Se nel Worker compare `hasResolution()` (consenso revocato), niente UI: si mostra una notifica "Riconnetti Google Drive" e il worker termina con `Result.failure()`.
- Su HTTP 401 si chiama `clearToken(ClearTokenRequest.builder().setToken(t).build())` e si riprova una volta.
- Per disconnettersi: `revokeAccess(...)`.

### 6.4 `drive.file` o `drive.appdata`

- **`drive.file`** (consigliato): si crea una cartella visibile "Tratto" nel Drive dell'utente.
  - L'app vede **solo i file che ha creato**, e da qualsiasi dispositivo con lo stesso progetto OAuth, quindi il ripristino su un nuovo tablet funziona.
  - L'utente può scaricare i backup a mano, anche senza l'app, e ci si possono mettere i PDF esportati.
- `drive.appdata`: cartella nascosta `appDataFolder`, accessibile solo dall'app. Viene cancellata se l'utente disconnette l'app da Drive, e occupa comunque quota.
  - Va bene se si preferisce che l'utente non veda (né rompa) i file.

### 6.5 Chiamate REST (senza client Google)

Endpoint:

- `GET https://www.googleapis.com/drive/v3/files?q='<folderId>' in parents and trashed=false&fields=nextPageToken,files(id,name,md5Checksum,size,modifiedTime,headRevisionId,appProperties)`
- **Creazione, fino a 5 MB**: `POST https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart` (metadata JSON + contenuto).
- **Creazione, oltre 5 MB**: `uploadType=resumable` → URI di sessione in `Location` → `PUT` a blocchi multipli di 256 KiB. Il resumable è consigliato anche su mobile per reti instabili.
- **Aggiornamento**: `PATCH https://www.googleapis.com/upload/drive/v3/files/{id}?uploadType=multipart|resumable`.
- **Download**: `GET …/drive/v3/files/{id}?alt=media`.
- **Sync incrementale**: `GET …/changes/startPageToken` e poi `GET …/changes?pageToken=…&fields=…`.

`appProperties` (privati per app, massimo 30 per file, 124 byte per coppia chiave+valore) contengono `noteId`, `ver` (versione locale monotona), `dev` (id dispositivo), `sha` (hash del contenuto) e `del=1` per le cancellazioni.

Come client HTTP basta `HttpURLConnection` + kotlinx-serialization; la sola alternativa ragionevole è OkHttp 5.5.0 (§4).

### 6.6 Design del backup

Layout su Drive:

```
Tratto/                       (cartella creata dall'app)
  notes/<noteId>.tratto       (zip "core": note.json + pagine/log tratti + testi; di solito < 1 MB)
  blobs/<sha256>.<ext>        (PDF, immagini, audio: immutabili, deduplicati, caricati una sola volta)
  meta/tombstones.json        (id note eliminate + data; opzionale, in alternativa appProperties.del)
```

- **Incrementale**: localmente ogni nota ha `localVersion` (che cresce a ogni salvataggio), `syncedVersion`, `remoteFileId` e `remoteMd5`/`headRevisionId`.
  - Si carica solo `localVersion > syncedVersion`.
  - Un blob si carica solo se non esiste già su Drive: si cerca per nome, cioè per hash.
  - Così un PDF da 50 MB viaggia **una sola volta**, anche se lo si annota cento volte.
- **WorkManager**:
  - `OneTimeWorkRequest` univoca "sync" (`enqueueUniqueWork(REPLACE)`) con `setInitialDelay(2–5 min)` come debounce dopo le modifiche e quando l'app va in background;
  - `PeriodicWorkRequest` ogni 12 h;
  - vincoli `NetworkType.CONNECTED` (oppure UNMETERED, a scelta dell'utente) e `setRequiresBatteryNotLow(true)`.
  - Il pulsante "Esegui ora" usa `setExpedited`.
- **Conflitti** (stesso utente su più dispositivi, v2):
  - all'inizio del sync si leggono i cambi remoti con `changes.list`;
  - se una nota è cambiata sia in remoto (`md5`/`headRevisionId` diverso dall'ultimo visto e `dev` ≠ il mio) sia in locale (`localVersion > syncedVersion`), **non si fa il merge**: si tiene la versione locale e la remota si scarica come *"Titolo (conflitto – <dispositivo> <data>)"*;
  - altrimenti vince il lato che è cambiato;
  - Drive v3 non ha un `If-Match` affidabile sui file, per cui la finestra di corsa si chiude **riverificando l'md5 subito prima del PATCH**.
- **Cancellazioni**: cestino locale per 30 giorni; poi tombstone + spostamento del file Drive nel cestino di Drive (`trashed=true`), mai cancellazione immediata.
- **Ripristino su un nuovo dispositivo**:
  1. autorizzazione;
  2. si trova la cartella "Tratto";
  3. si scaricano tutti i `notes/*.tratto` (piccoli);
  4. i blob si scaricano su Wi-Fi in background, oppure al primo accesso alla nota;
  5. si ricostruisce l'indice Room dai file.
- **Integrità**: zip verificato, perché ogni voce ha CRC32. Il `sha256` dei blob si ricontrolla dopo il download. Un file corrotto non sovrascrive mai la copia locale.
- **Crittografia lato client**: opzionale, per dopo (AES-GCM con chiave derivata da passphrase); con `drive.file` i dati sono comunque solo nel Drive dell'utente.

---

## 7. Formato dei file

### 7.1 Su disco (sorgente di verità = file, non DB)

```
files/notes/<noteId>/                      (storage interno dell'app)
  note.json            manifest versionato (kotlinx-serialization)
  pages/<pageId>.log   log binario append-only dei tratti della pagina
  pages/<pageId>.json  elementi non-tratto della pagina (caselle testo, immagini, forme) — piccolo, riscritto atomicamente
  assets/<sha256>.pdf|.jpg|.webp|.m4a      immutabili
cache/raster/<noteId>/<pageId>@<scala>.webp   raster di pagina per apertura istantanea (rigenerabile, escluso dai backup)
files/thumbs/<noteId>.webp                  miniatura lista (~300 px, WebP q80)
```

**`note.json`** (esempio di campi):

- `format: "tratto/1"`, `id`, `title`, `createdAt`, `modifiedAt`, `localVersion`;
- `folderId`, `favorite`, `tags[]`, `locked`, `pageMode: "pages"|"scroll"`, `epsilon: 0.05`, `unit: "pt"`;
- `brushes[]`: tabella dei pennelli usati, con `id` locale, `family: "tratto.fountain"`, `familyVersion`, `inkStockVersion`, `size`, `color` (ColorLong);
- `pages[]` con `{id, kind:"blank"|"pdf", template:"lines|grid|dots|blank", w, h, pdf:{asset, index}, rev}`;
- `assets[]` con `{sha256, mime, size}`;
- `recognition: {lang:"it", pageRev:{…}}`.

**`<pageId>.log`**:

- **Header**: magic `TRST`, versione del formato e versione di Ink usata in scrittura.
- **Record**: `[u8 tipo][u32 len][payload][u32 crc32c]`. Tipi:
  - `ADD`: `strokeId` a 64 bit, `brushRef` a 16 bit, flag (evidenziatore, …), `createdAt`, transform affine opzionale (6 float) e `StrokeInputBatch` codificato con ink-storage;
  - `DEL`: `strokeId`;
  - `XFORM`: `strokeId[]` + matrice;
  - `STYLE`: `strokeId[]` + `brushRef`.
- **Salvataggio incrementale**: un tratto finito vale un record accodato. È veloce e scrive pochi byte.
- **In lettura** si riproduce il log. Un record troncato o con CRC errato indica un crash durante la scrittura: lo si scarta e si tronca il file lì.
- **Compattazione**: quando i record "morti" superano circa il 30%, si riscrive la pagina completa (solo `ADD` vivi con le transform applicate) in modo atomico.

Scritture atomiche:

- **File riscritti per intero** (`note.json`, `pages/*.json`, compattazioni): scrittura su `x.tmp`, poi `FileOutputStream.fd.sync()`, poi `Files.move(tmp, x, ATOMIC_MOVE)` (atomico su f2fs/ext4 nella stessa directory), poi fsync della directory con `Os.open(dir, O_RDONLY)` + `Os.fsync`. In alternativa `androidx.core.util.AtomicFile`.
- **Log append**: `write` subito, dal thread IO, a ogni tratto; `fd.sync()` al massimo ogni ~1 s, su `onPause`/`onStop` e alla chiusura della nota. Niente fsync per ogni tratto, che significherebbe latenza e usura della memoria flash.
- **Blob**: hash durante la copia, poi rinomina in `<sha256>.ext`. Sono idempotenti.

**Apertura veloce delle note grandi**:

1. si legge solo `note.json`;
2. si mostrano i **raster in cache** delle pagine visibili (WebP, ~1× schermo);
3. in background si decodificano i log delle pagine visibili, si costruiscono gli `Stroke` e poi i `RenderNode`, e si sostituisce il raster con il vettoriale;
4. le pagine lontane non vengono caricate; se la memoria scarseggia si scaricano gli `Stroke` delle pagine lontane, tenendo il raster.

**`.tratto`** (condivisione ed export di una nota e unità di backup):

- è lo **zip** della cartella nota senza la cache;
- prima voce `mimetype` = `application/x-tratto` (STORED), come negli ODF/EPUB, per il riconoscimento;
- `note.json`, `pages/*` DEFLATE; blob già compressi (PDF, JPEG, m4a) STORED;
- per il backup, "core" e blob sono separati (§6.6).

### 7.2 Indice (Room 2.8.5, ricostruibile)

- `notes(id PK, title, folderId, createdAt, modifiedAt, favorite, locked, pageCount, thumbVersion, trashedAt, localVersion, syncedVersion, remoteFileId, remoteMd5)`
- `folders(id, name, parentId, color, sort)`
- `tags`, `note_tags`
- `pages_index(noteId, pageId, idx, rev, recognizedRev)`
- `note_text_fts`, **FTS4**: `@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61, tokenizerArgs = ["remove_diacritics=2"])`, con colonne `noteId`, `pageId`, `source` (`title|typed|ink|pdf`), `content`, `alt`, `bbox`. Le query usano il prefisso (`perch*`), così "perche" trova anche "perché".
  - Il SQLite di piattaforma di Android 16 è compilato con FTS3/FTS4 ma **non FTS5** (`external/sqlite/dist/Android.bp`).
  - Per l'FTS5 servirebbero Room 3 (`@Fts5`, `androidx.room3:room3-*:3.0.3`) **e** `androidx.sqlite:sqlite-bundled` (+1,3 MB di `.so`, più tempo di caricamento). Non ne vale la pena.
  - Non esiste uno stemmer italiano (porter è inglese).
- **Room 3**: tutto coroutine, KSP e niente SupportSQLite. È valido, ma WorkManager dipende da Room 2.x e avere entrambi significa due runtime nell'APK. **Si resta su Room 2.8.5**.
- L'indice si aggiorna dal thread IO dopo ogni salvataggio. Se lo schema cambia, `fallbackToDestructiveMigration()` + reindicizzazione dai file: nessuna migrazione dolorosa.
- La lista note legge solo l'indice (un `SELECT` su poche colonne) e le miniature WebP. Non apre mai i file delle note.

---

## 8. Prestazioni: avvio "miracoloso" e disegno fluido sull'Exynos 9611

L'Exynos 9611 ha 4× A73 a 2,3 GHz, 4× A53 a 1,7 GHz e GPU Mali-G72 MP3; la memoria eMMC/UFS è lenta rispetto ai flagship.

### 8.1 Build

- `isMinifyEnabled = true`, `isShrinkResources = true`. R8 **full mode** è il default da AGP 8, e la regola vale anche per il debug usato nelle misure (build `benchmark`).
- **Solo arm64**: `ndk { abiFilters += "arm64-v8a" }`. Risparmia circa 22 MB di `.so` tra ML Kit, Ink, graphics-core e PdfBox.
  - `packaging.jniLibs.useLegacyPackaging = false` (default): `.so` non compresse e allineate, caricate con mmap, quindi più veloci all'avvio.
- Kotlin: evitare la reflection. kotlinx-serialization va usato con serializer generati, senza `Json` "lenient" costruiti a ogni chiamata.
- Niente Hilt/Dagger: **DI manuale**, cioè un `AppGraph` con `by lazy`.
- **Obiettivo APK < 20 MB.**

### 8.2 Avvio

- **Percorso "nuova nota"**:
  - `NewNoteActivity`/`EditorActivity` con `setContentView(NoteCanvasLayout)`: **niente Compose nel primo frame**.
  - La toolbar Compose (`ComposeView`) si aggiunge in `doOnPreDraw` o al frame successivo; intanto si può già scrivere.
  - `InProgressStrokesView.eagerInit()` si chiama subito dopo l'attach.
  - Il caricamento di `libink.so` può essere anticipato su un thread in `Application.onCreate` (Ink carica la lib in modo lazy al primo uso).
- **Splash**: l'Android 12+ lo mostra sempre sui cold/warm start. Va messo a tema con `android:windowSplashScreenBackground` = colore carta e `windowSplashScreenAnimatedIcon` trasparente o minimale, così "sembra già la nota". Con minSdk ≥ 31 non serve `core-splashscreen`.
- **`Application.onCreate` quasi vuoto**. Vanno rimossi gli inizializzatori automatici:
  - **WorkManager**: inizializzazione on-demand (`Configuration.Provider`), rimuovendo `WorkManagerInitializer` dal `InitializationProvider` con `tools:node="remove"`;
  - **ML Kit** `MlKitInitProvider` (§4);
  - **ProfileInstaller** va tenuto: è economico e rimanda il suo lavoro.
- **Room**: l'apertura del DB avviene in background; il primo frame della libreria mostra uno scheletro, o una lista in cache (un file `list-snapshot` minimale, opzionale).
- `reportFullyDrawn()` va chiamato quando lista e miniature sono visibili (TTFD).
- **Baseline Profile + Startup Profile**:
  - plugin `androidx.baselineprofile` 1.5.0 e modulo `:baselineprofile` (`com.android.test`) con `benchmark-macro-junit4:1.5.0`;
  - nell'app `androidx.profileinstaller:profileinstaller:1.4.1`;
  - `BaselineProfileRule.collect(packageName, includeInStartupProfile = true) { startActivityAndWait(); /* apri nota, disegna tratti, pan/zoom */ }`, con `dexLayoutOptimization = true`;
  - **la generazione non richiede root su API 33+**, quindi funziona direttamente sul tablet.
- **Installazione in sviluppo** (sideload): il profilo nell'APK viene applicato da ProfileInstaller al primo avvio, e la compilazione avviene poi in manutenzione. Per misurare subito: `adb shell cmd package compile -f -m speed-profile <pkg>`.
  - Per un'app personale si può anche forzare `-m speed` (AOT completo). Dopo gli OTA di LineageOS, però, ART ricompila con il profilo, quindi il Baseline Profile resta la base.

### 8.3 Compose (libreria e UI)

- La lista usa `LazyVerticalGrid` con `key` e `contentType`, miniature pre-dimensionate (`inSampleSize`, `inPreferredConfig`) decodificate fuori dal main thread e `LruCache` in memoria.
- **Non mettere tratti o liste di `Stroke` nello stato Compose**, cioè niente `SnapshotStateList<Stroke>`: la superficie di disegno sta fuori da Compose (§1.7).
- Letture di stato ad alta frequenza (zoom, posizione hover) solo nelle fasi layout/draw (`Modifier.graphicsLayer { }`, `drawBehind { }`), non in composizione.
- Lo strong skipping è di default con Kotlin ≥ 2.0.20. Usare tipi stabili o immutabili; niente `material-icons-extended` (meglio vector drawable propri).
- **Navigazione**: con 3–4 schermate basta uno stato semplice, oppure Navigation 3 (`navigation3-ui:1.2.0`). Vanno tenute separate le Activity "Libreria" (Compose) ed "Editor" (View + Compose lazy).

### 8.4 Disegno

- Inchiostro bagnato in front-buffer (Ink); `requestUnbufferedDispatch`; predizione.
- Inchiostro secco in **`RenderNode` per pagina**, con un nodo "delta" per gli ultimi tratti, ri-registrato poco e in batch; pan e zoom cambiano solo la matrice.
- Tile PDF: si renderizzano alla scala esatta solo a gesto fermo; durante il pinch si scalano quelle esistenti.
- Nessuna allocazione nel percorso `onTouch` (oggetti riusati, niente lambda per evento, come raccomandato dalla guida stylus).
- Il riconoscimento ML Kit, la compattazione dei log, le miniature e il sync girano su un dispatcher a bassa priorità (`Dispatchers.IO.limitedParallelism(1)` + `Process.THREAD_PRIORITY_BACKGROUND`) e **mai mentre la penna è giù**. Si mettono in pausa se c'è input negli ultimi ~500 ms.
- Il tratto finito va nel log dal thread IO. `InProgressStrokesView` resta leggero e ne scarica i tratti subito dopo l'handoff.

### 8.5 Misure e obiettivi (stime per l'Exynos 9611 da confermare con le misure)

| Metrica | Obiettivo | Come si misura |
|---|---|---|
| Cold start editor "nuova nota" (TTID) | **≤ 350 ms** (accettabile ≤ 450) | `adb shell am force-stop <pkg>` poi `adb shell am start-activity -W -n <pkg>/.EditorActivity` → `TotalTime`; Macrobenchmark `StartupTimingMetric`, `StartupMode.COLD`, `CompilationMode.Partial()` confrontato con `None()` |
| Cold start libreria (TTID / TTFD) | ≤ 500 ms / ≤ 700 ms | Come sopra + `reportFullyDrawn()` |
| Warm / hot start | ≤ 180 ms / ≤ 80 ms | `StartupMode.WARM/HOT` |
| Apertura nota di 20 pagine (primo contenuto) | ≤ 150 ms (raster), vettoriale pronto ≤ 600 ms | Trace personalizzati (`androidx.tracing`) + Perfetto |
| Pan/zoom su pagina con circa 2.000 tratti | p90 frame < 16,6 ms, jank < 1% | `FrameTimingMetric` in Macrobenchmark |
| Latenza inchiostro | Con front-buffer circa 1 frame di pipeline in meno rispetto al fallback | `LatencyDataCallback`, confronto con `useHighLatencyRenderHelper` e video a 240 fps del cellulare |
| Ricerca FTS su 1.000 note | < 20 ms | Test di strumentazione |
| Dimensione APK | < 20 MB (arm64) | `apkanalyzer` |

L'aggancio dei clock durante le misure richiede root. Su LineageOS c'è "Rooted debugging", facoltativo, altrimenti si accettano gli avvisi di Macrobenchmark.

### 8.6 Android 16 / targetSdk 36: cose che influenzano l'architettura

- **Su schermi ≥ 600 dp** (il Tab S6 Lite è circa 800 dp di lato corto) `screenOrientation`, `resizeableActivity` e i limiti di proporzioni vengono **ignorati**. L'app deve gestire rotazione e ridimensionamento, e le note non devono "saltare" alla rotazione: la camera va salvata in `onSaveInstanceState`.
  - Esiste un opt-out temporaneo, `PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY`, che sparisce con target 37.
- **Edge-to-edge obbligatorio** (senza opt-out): gestire gli insets.
- **Predictive back** attivo: niente `onBackPressed`, si usa `OnBackPressedCallback`.

---

## 9. Verifiche da fare sul tablet (prima di scrivere codice)

1. **Dispositivo penna**:
   - `adb shell getevent -lp`: individuare `sec_e-pen`.
   - `adb shell getevent -lt /dev/input/eventN`: premere il tasto in hover e a contatto, e osservare `BTN_STYLUS`, `BTN_TOOL_PEN` e un eventuale `BTN_TOOL_RUBBER`.
   - `adb shell dumpsys input | sed -n '/sec_e-pen/,/^  [0-9]/p'`: sorgenti (TOUCHSCREEN|STYLUS), classi (KEYBOARD?), `HaveTilt: true`, calibrazione della pressione.
2. `adb shell settings get secure stylus_buttons_enabled`: deve dare 1 (o null, che vale come default 1).
3. `adb shell aflags list | grep -i -E 'same_window|multi_device'`: comportamento penna+dito (§2.2).
4. `adb shell dumpsys role | grep -i -A3 notes`: conferma che il ruolo Notes non è disponibile.
5. **Front-buffer**: nella schermata di debug si chiama `HardwareBuffer.isSupported(w, h, RGBA_8888, 1, USAGE_FRONT_BUFFER or USAGE_COMPOSER_OVERLAY or USAGE_GPU_COLOR_OUTPUT or USAGE_GPU_SAMPLED_IMAGE)`, poi `LatencyDataCallback` con e senza `useHighLatencyRenderHelper`.
6. **API PDF 36.1**: `Build.VERSION.SDK_INT_FULL` e `SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S)`.
7. **Backup Android**: `adb shell bmgr list transports`, per vedere se il transport Google di MindTheGapps è presente.
8. **Assistente**: dopo aver impostato Tratto come assistente digitale, verificare lo swipe dall'angolo e la pressione lunga del power.
9. **Lawnchair**: doppio tap → "Apri app" → alias "Nuova nota".

---

## 10. Fonti

**Jetpack Ink / grafica / input**

- Release notes Ink: https://developer.android.com/jetpack/androidx/releases/ink
- Versioni Maven Ink: https://dl.google.com/android/maven2/androidx/ink/group-index.xml
- Guida Ink (Views): https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/about-ink-api · …/ink-api-modules · …/ink-api-draw-stroke · …/ink-api-brush-apis-views · …/ink-api-coordinate-system · …/ink-api-geometry-apis · …/ink-api-persistent-storage · …/ink-api-setup
- Ink in Compose: https://developer.android.com/develop/ui/compose/touch-input/stylus-input/ink-api-draw-stroke
- Funzioni avanzate dello stilo (MotionEvent, palm rejection, front-buffer): https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/advanced-stylus-features
- App di note e ruolo Notes: https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/create-a-note-taking-app
- API delle classi Ink, graphics-core 1.0.4 e input-motionprediction 1.0.0 lette dagli AAR su https://dl.google.com/android/maven2/ (javap su `StockBrushes`, `BrushBehavior$Source/Target`, `InProgressStrokesView`, `PartitionedMesh`, `MeshCreation`, `CanvasStrokeRenderer`, ink-storage)

**AOSP Android 16 (tag android-16.0.0_r4) e LineageOS**

- PhoneWindowManager (tasti stilo intercettati, tasto coda → note): https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r4/services/core/java/com/android/server/policy/PhoneWindowManager.java
- Generic.kl (BTN_STYLUS → STYLUS_BUTTON_PRIMARY): https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r4/data/keyboards/Generic.kl
- EventHub (stilo con pulsanti = classe keyboard): https://android.googlesource.com/platform/frameworks/native/+/refs/tags/android-16.0.0_r4/services/inputflinger/reader/EventHub.cpp
- TouchInputMapper (tilt in gradi, BUTTON_PRESS, filtro dei pulsanti): https://android.googlesource.com/platform/frameworks/native/+/refs/tags/android-16.0.0_r4/services/inputflinger/reader/mapper/TouchInputMapper.cpp
- InputDispatcher / InputState (un device per finestra, precedenza allo stilo): https://android.googlesource.com/platform/frameworks/native/+/refs/tags/android-16.0.0_r4/services/inputflinger/dispatcher/InputState.cpp · …/InputDispatcher.cpp · flag: https://android.googlesource.com/platform/frameworks/native/+/refs/tags/android-16.0.0_r4/libs/input/input_flags.aconfig
- AccessibilityInputFilter: https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r4/services/accessibility/java/com/android/server/accessibility/AccessibilityInputFilter.java
- AccessibilityServiceInfo (`setObservedMotionEventSources` = @TestApi): https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r4/core/java/android/accessibilityservice/AccessibilityServiceInfo.java
- SystemUI note task: https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r4/packages/SystemUI/src/com/android/systemui/notetask/
- Ruoli (NOTES non richiedibile, ASSISTANT): https://android.googlesource.com/platform/packages/modules/Permission/+/refs/tags/android-16.0.0_r4/PermissionController/res/xml/roles.xml · NotesRoleBehavior: https://android.googlesource.com/platform/packages/modules/Permission/+/refs/tags/android-16.0.0_r4/PermissionController/role-controller/java/com/android/role/controller/behavior/v34/NotesRoleBehavior.java · AssistantRoleBehavior: …/behavior/AssistantRoleBehavior.java
- `config_enableDefaultNotes=false`: https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r4/core/res/res/values/config.xml
- SQLite di piattaforma (FTS3/4, niente FTS5): https://android.googlesource.com/platform/external/sqlite/+/refs/tags/android-16.0.0_r4/dist/Android.bp
- Device tree LineageOS: https://github.com/LineageOS/android_device_samsung_gta4xl-common/tree/lineage-23.2 · https://github.com/LineageOS/android_device_samsung_gta4xlwifi/tree/lineage-23.2 · https://github.com/LineageOS/android_vendor_lineage/tree/lineage-23.2
- LineageOS 23.2 = Android 16 QPR2: https://piunikaweb.com/2026/02/09/lineageos-23-2-based-on-android-16-qpr2-released-for-pixel-4-and-newer-devices/
- Lawnchair OpenAppGestureHandler: https://github.com/LawnchairLauncher/lawnchair/blob/16-dev/lawnchair/src/app/lawnchair/gestures/handlers/OpenAppGestureHandler.kt
- Shizuku: https://github.com/RikkaApps/Shizuku · API su Maven Central `dev.rikka.shizuku:api`

**Samsung Notes**

- Funzioni di scrittura con S Pen: https://www.samsung.com/us/support/answer/ANS10003634/
- FAQ Samsung Notes: https://www.samsung.com/us/support/answer/ANS10002888/
- One UI 8 Samsung Notes: https://www.sammobile.com/news/one-ui-8-samsung-notes-features-leak-customizable-toolbar/
- Consigli S Pen / Notes (S26 Ultra): https://www.sammobile.com/news/s-pen-tips-samsung-notes-features-galaxy-s26-ultra/
- Guida a Samsung Notes: https://www.guidingtech.com/guide-using-samsung-notes-app/
- Note Assist: https://www.samsung.com/us/support/answer/ANS10000941/
- Wikipedia: https://en.wikipedia.org/wiki/Samsung_Notes
- Evoluzione S Pen (tipi di penna): https://news.samsung.com/global/from-stylus-to-self-expression-looking-back-at-the-evolution-of-the-s-pen

**ML Kit**

- Panoramica: https://developers.google.com/ml-kit/vision/digital-ink-recognition
- Android: https://developers.google.com/ml-kit/vision/digital-ink-recognition/android
- Lingue e modelli: https://developers.google.com/ml-kit/vision/digital-ink-recognition/base-models
- Dimensioni dei modelli: `assets/manifest.json` dentro https://dl.google.com/android/maven2/com/google/mlkit/digital-ink-recognition/19.0.0/digital-ink-recognition-19.0.0.aar

**PDF**

- Release notes androidx.pdf: https://developer.android.com/jetpack/androidx/releases/pdf
- PdfRenderer: https://developer.android.com/reference/android/graphics/pdf/PdfRenderer · PdfRenderer.Page: https://developer.android.com/reference/android/graphics/pdf/PdfRenderer.Page
- StampAnnotation (36.1 / S Ext 18): https://developer.android.com/reference/android/graphics/pdf/component/StampAnnotation · PdfAnnotationType: https://developer.android.com/reference/android/graphics/pdf/component/PdfAnnotationType
- PdfBox-Android: https://github.com/TomRoush/PdfBox-Android · https://repo1.maven.org/maven2/com/tom-roush/pdfbox-android/

**Google Drive / Identity / Backup**

- Autorizzazione (AuthorizationClient): https://developer.android.com/identity/authorization
- Scope Drive: https://developers.google.com/workspace/drive/api/guides/api-specific-auth
- appDataFolder: https://developers.google.com/workspace/drive/api/guides/appdata
- Proprietà personalizzate: https://developers.google.com/workspace/drive/api/guides/properties
- Upload: https://developers.google.com/workspace/drive/api/guides/manage-uploads
- Audience e test user (scadenza a 7 giorni): https://support.google.com/cloud/answer/15549945
- Auto Backup: https://developer.android.com/identity/data/autobackup
- SAF: https://developer.android.com/guide/topics/providers/document-provider · segnalazione `ACTION_OPEN_DOCUMENT_TREE` non supportato: https://support.google.com/files/thread/313389105/action-open-document-tree-not-supported

**Build / prestazioni / piattaforma**

- AGP 9.4: https://developer.android.com/build/releases/gradle-plugin · AGP 9.0 (Kotlin integrato): https://developer.android.com/build/releases/agp-9-0-0-release-notes
- Android 16, cambi di comportamento: https://developer.android.com/about/versions/16/behavior-changes-16
- Baseline Profile: https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile · Startup Profile: https://developer.android.com/topic/performance/baselineprofiles/dex-layout-optimizations · Misura manuale: https://developer.android.com/topic/performance/baselineprofiles/manually-create-measure
- Release notes di Benchmark: https://developer.android.com/jetpack/androidx/releases/benchmark
- Room 3: https://developer.android.com/jetpack/androidx/releases/room3
- Versioni Kotlin, kotlinx, OkHttp, KSP e Drive: https://repo1.maven.org/maven2/ · Gradle: https://services.gradle.org/versions/current
