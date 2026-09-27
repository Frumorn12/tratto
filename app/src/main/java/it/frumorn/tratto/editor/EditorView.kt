package it.frumorn.tratto.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.OverScroller
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.strokes.Stroke
import androidx.input.motionprediction.MotionEventPredictor
import it.frumorn.tratto.data.Archivio
import it.frumorn.tratto.data.Immagine
import it.frumorn.tratto.data.Oggetto
import it.frumorn.tratto.data.Penna
import it.frumorn.tratto.data.Testo
import it.frumorn.tratto.data.TestoRicco
import it.frumorn.tratto.data.Tratto
import it.frumorn.tratto.ink.Pennelli
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * La superficie di scrittura.
 *
 *  - penna: inchiostro a bassa latenza con [InProgressStrokesView] (front buffer) e predizione;
 *  - tasto della penna tenuto premuto, o punta gomma: gomma temporanea;
 *  - dita: scorrimento, lancio, pizzico per lo zoom, doppio tocco per tornare alla larghezza pagina;
 *  - con la penna vicina allo schermo le dita vengono ignorate (appoggio del palmo);
 *  - oggetti (immagini e caselle di testo): un tocco del dito (o della penna con il lazo) li seleziona,
 *    poi si spostano e si ridimensionano dalle maniglie; la pressione lunga del dito su un punto vuoto
 *    apre il menu per incollare o inserire; con lo strumento Testo un tocco crea o modifica una casella.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class EditorView(context: Context) : FrameLayout(context), InProgressStrokesFinishedListener {

    val foglio = FoglioView(context)
    private val sopra = Sovrapposizione(context)
    val inchiostro = InProgressStrokesView(context)

    var documento: Documento? = null
        set(v) {
            chiudiTesto()
            field = v; foglio.documento = v; selezione = null; selezioneOggetto = null
        }

    var strumenti = StatoStrumenti()
        set(v) {
            val prima = field.strumento
            field = v
            if (v.strumento != Strumento.LAZO && selezione != null) chiudiSelezione()
            // Cambiando strumento mentre si scrive in una casella, la casella si chiude (e si salva).
            if (prima == Strumento.TESTO && v.strumento != Strumento.TESTO) chiudiTesto()
            sopra.invalidate()
        }

    /** Se attivo, anche il dito scrive (utile senza penna). */
    var disegnaConDita = false

    /** Notifiche verso la UI in Compose. */
    var alCambioSelezione: ((Boolean) -> Unit)? = null
    var alTratto: (() -> Unit)? = null
    var alCambioPagina: ((Int) -> Unit)? = null
    /** Tratti di penna appena finiti, pagina per pagina (per i calcoli automatici). */
    var alTrattiFiniti: ((PaginaViva, List<Tratto>) -> Unit)? = null
    /** Tirando oltre la fine dell'ultima pagina e rilasciando: si aggiunge una pagina. */
    var alTiraPagina: (() -> Unit)? = null
    /** La penna tocca lo schermo (per nascondere quello che sta sopra le pagine, come i suggerimenti). */
    var alPennaGiu: (() -> Unit)? = null
    /** Le pagine si spostano o cambiano scala. */
    var alMovimento: (() -> Unit)? = null
    /** L'oggetto selezionato (null se nessuno), anche quando cambia perche' spostato o ridimensionato. */
    var alCambioOggetto: ((Oggetto?) -> Unit)? = null
    /** Pressione lunga del dito su un punto della pagina senza oggetti (coordinate dello schermo). Senza, niente menu. */
    var alPressioneLunga: ((Float, Float) -> Unit)? = null
    /** Si apre (il campo) o si chiude (null) la modifica di una casella di testo. */
    var alCampoTesto: ((CampoTesto?) -> Unit)? = null

    private val densita = resources.displayMetrics.density
    private val soglia = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val predittore = MotionEventPredictor.newInstance(this)
    private val paginaDelTratto = HashMap<InProgressStrokeId, Pair<Int, Penna>>()
    private val colore = HashMap<InProgressStrokeId, ImpostazioniPenna>()
    private var trattoInCorso: InProgressStrokeId? = null
    private var pennaInHoverFino = 0L
    private var pennaGiu = false

    init {
        addView(foglio, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(sopra, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(inchiostro, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        inchiostro.addFinishedStrokesListener(this)
        foglio.alMovimento = {
            posizionaCampo()
            sopra.invalidate()
            alCambioPagina?.invoke(foglio.paginaCorrente())
            alMovimento?.invoke()
        }
        isFocusable = true
    }

    /** Parti dello schermo coperte dalla barra: l'inchiostro in corso non ci disegna sopra. */
    fun impostaMaschera(rettangoli: List<RectF>) {
        inchiostro.maskPath = if (rettangoli.isEmpty()) null else Path().apply {
            rettangoli.forEach { addRect(it, Path.Direction.CW) }
        }
    }

    fun preriscalda() {
        inchiostro.eagerInit()
    }

    // ---------------------------------------------------------------- tocchi

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS || event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER) {
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                    pennaInHoverFino = event.eventTime + 400
                    annullaPressioneLunga()
                    sopra.cursore(event.x, event.y, gommaAttiva(event))
                }
                MotionEvent.ACTION_HOVER_EXIT -> {
                    pennaInHoverFino = event.eventTime + 250
                    sopra.nascondiCursore()
                }
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    /** Il gesto in corso e' del campo di testo (e' cominciato sopra il campo): non lo si tocca. */
    private var gestoAlCampo = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) gestoAlCampo = dentroCampo(ev.x, ev.y)
        val alCampo = gestoAlCampo
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) gestoAlCampo = false
        return !alCampo
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val strumento = event.getToolType(event.actionIndex)
        val stilo = strumento == MotionEvent.TOOL_TYPE_STYLUS || strumento == MotionEvent.TOOL_TYPE_ERASER
        if (stilo || (disegnaConDita && event.pointerCount == 1 && !inGestoDita)) {
            if (inGestoDita) annullaGestoDita()
            return tocchiPenna(event)
        }
        // Appoggio del palmo: con la penna giu' o vicina allo schermo, le dita non contano.
        if (pennaGiu || event.eventTime < pennaInHoverFino) return true
        return tocchiDita(event)
    }

    private fun gommaAttiva(e: MotionEvent): Boolean =
        strumenti.strumento == Strumento.GOMMA ||
            e.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER ||
            (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0

    private enum class Gesto { NESSUNO, INCHIOSTRO, GOMMA, LAZO, SPOSTA_SELEZIONE, SCALA_SELEZIONE, OGGETTO, TESTO }
    private var gesto = Gesto.NESSUNO

    private fun tocchiPenna(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pennaGiu = true
                alPennaGiu?.invoke()
                annullaPressioneLunga()
                sopra.nascondiCursore()
                requestUnbufferedDispatch(e)
                scorritore.forceFinished(true)
                // Toccare fuori dalla casella che si sta scrivendo la chiude, e basta: niente puntino
                // d'inchiostro ne' casella nuova. Si riprende a scrivere dal tocco dopo.
                val chiusa = campo != null
                if (chiusa) chiudiTesto()
                // Con la penna (o la gomma) si scrive sempre, anche sopra l'oggetto selezionato: la penna
                // lo sposta solo con il lazo o lo strumento Testo. Il dito lo sposta con qualsiasi strumento.
                val maniglia = if (strumenti.strumento == Strumento.LAZO || strumenti.strumento == Strumento.TESTO) manigliaSotto(e.x, e.y) else NESSUNA
                gesto = when {
                    chiusa && !gommaAttiva(e) -> Gesto.NESSUNO
                    gommaAttiva(e) -> Gesto.GOMMA.also { iniziaGomma(e) }
                    selezione?.let { dentroManiglia(e.x, e.y, it) } == true -> Gesto.SCALA_SELEZIONE.also { iniziaTrasforma(e, scala = true) }
                    selezione?.let { dentroSelezione(e.x, e.y, it) } == true -> Gesto.SPOSTA_SELEZIONE.also { iniziaTrasforma(e, scala = false) }
                    maniglia != NESSUNA -> Gesto.OGGETTO.also { iniziaOggetto(e.x, e.y, maniglia) }
                    else -> {
                        deselezionaOggetto()
                        when (strumenti.strumento) {
                            Strumento.LAZO -> Gesto.LAZO.also { chiudiSelezione(); iniziaLazo(e) }
                            Strumento.TESTO -> Gesto.TESTO.also { chiudiSelezione(); iniziaRiquadroTesto(e.x, e.y) }
                            else -> { if (selezione != null) chiudiSelezione(); Gesto.INCHIOSTRO.also { iniziaTratto(e) } }
                        }
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> when (gesto) {
                Gesto.INCHIOSTRO -> trattoInCorso?.let {
                    predittore.record(e)
                    val previsione = predittore.predict()
                    try { inchiostro.addToStroke(e, e.getPointerId(0), it, previsione) } finally { previsione?.recycle() }
                }
                Gesto.GOMMA -> muoviGomma(e)
                Gesto.LAZO -> muoviLazo(e)
                Gesto.SPOSTA_SELEZIONE, Gesto.SCALA_SELEZIONE -> muoviTrasforma(e)
                Gesto.OGGETTO -> muoviOggetto(e.x, e.y)
                Gesto.TESTO -> muoviRiquadroTesto(e.x, e.y)
                Gesto.NESSUNO -> Unit
            }
            MotionEvent.ACTION_UP -> {
                when (gesto) {
                    Gesto.INCHIOSTRO -> trattoInCorso?.let { inchiostro.finishStroke(e, e.getPointerId(0), it) }
                    Gesto.GOMMA -> fineGomma()
                    Gesto.LAZO -> fineLazo()
                    Gesto.SPOSTA_SELEZIONE, Gesto.SCALA_SELEZIONE -> fineTrasforma()
                    Gesto.OGGETTO -> if (!fineOggetto()) toccoOggettoSelezionato(e.x, e.y, penna = true)
                    Gesto.TESTO -> fineRiquadroTesto(e.x, e.y)
                    Gesto.NESSUNO -> Unit
                }
                trattoInCorso = null
                gesto = Gesto.NESSUNO
                pennaGiu = false
                pennaInHoverFino = e.eventTime + 400
            }
            MotionEvent.ACTION_CANCEL -> {
                trattoInCorso?.let { inchiostro.cancelStroke(it, e) }
                if (gesto == Gesto.GOMMA) fineGomma()
                if (gesto == Gesto.LAZO) { lazo.reset(); sopra.invalidate() }
                if (gesto == Gesto.OGGETTO) annullaOggetto()
                if (gesto == Gesto.TESTO) { riquadroTesto = null; sopra.invalidate() }
                trattoInCorso = null
                gesto = Gesto.NESSUNO
                pennaGiu = false
            }
        }
        return true
    }

    // ---------------------------------------------------------------- inchiostro

    private val tmpM = Matrix()

    private fun iniziaTratto(e: MotionEvent) {
        val d = documento ?: return
        val i = foglio.paginaSotto(e.x, e.y, tolleranza = 30f)
        if (i < 0) return
        d.caricaTratti(d.pagine[i])
        val imp = strumenti.corrente
        val penna = strumenti.penna
        val brush = Pennelli.brush(penna, imp.colore, imp.spessore)
        val id = inchiostro.startStroke(e, e.getPointerId(0), brush, foglio.schermoAPagina(i, Matrix()))
        paginaDelTratto[id] = i to penna
        colore[id] = imp
        trattoInCorso = id
        predittore.record(e)
    }

    override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
        val d = documento
        if (d == null) { inchiostro.removeFinishedStrokes(strokes.keys); return }
        val nuovi = LinkedHashMap<PaginaViva, MutableList<Tratto>>()
        for ((id, stroke) in strokes) {
            val (i, penna) = paginaDelTratto.remove(id) ?: continue
            val imp = colore.remove(id) ?: continue
            val p = d.pagine.getOrNull(i) ?: continue
            val t = Tratto(d.nuovoIdTratto(), penna, imp.colore, imp.spessore, Pennelli.punti(stroke))
            p.precarica(t.id, stroke)
            d.esegui(Modifica(p, emptyList(), listOf(t)))
            nuovi.getOrPut(p) { ArrayList() } += t
        }
        foglio.invalidate()
        inchiostro.removeFinishedStrokes(strokes.keys)
        alTratto?.invoke()
        nuovi.forEach { (p, lista) -> alTrattiFiniti?.invoke(p, lista) }
    }

    // ---------------------------------------------------------------- gomma

    private var paginaGomma = -1
    private val tolti = ArrayList<Pair<Int, Tratto>>()
    private val creatiDallaGomma = HashSet<Long>()
    private var gx = 0f
    private var gy = 0f

    private fun iniziaGomma(e: MotionEvent) {
        val d = documento ?: return
        paginaGomma = foglio.paginaSotto(e.x, e.y, tolleranza = 40f)
        tolti.clear(); creatiDallaGomma.clear()
        if (paginaGomma >= 0) d.caricaTratti(d.pagine[paginaGomma])
        gx = e.x; gy = e.y
        cancellaTra(e.x, e.y, e.x, e.y)
        sopra.cursore(e.x, e.y, gomma = true)
    }

    private fun muoviGomma(e: MotionEvent) {
        for (h in 0 until e.historySize) {
            cancellaTra(gx, gy, e.getHistoricalX(h), e.getHistoricalY(h))
            gx = e.getHistoricalX(h); gy = e.getHistoricalY(h)
        }
        cancellaTra(gx, gy, e.x, e.y)
        gx = e.x; gy = e.y
        sopra.cursore(e.x, e.y, gomma = true)
    }

    /** Cancella lungo il segmento (in pixel) percorso dalla gomma. */
    private fun cancellaTra(x0: Float, y0: Float, x1: Float, y1: Float) {
        val d = documento ?: return
        val p = d.pagine.getOrNull(paginaGomma) ?: return
        val r = strumenti.raggioGomma * densita / foglio.scala
        val cima = foglio.cimaPagina(paginaGomma)
        val ax = (x0 - foglio.tx) / foglio.scala; val ay = (y0 - foglio.ty) / foglio.scala - cima
        val bx = (x1 - foglio.tx) / foglio.scala; val by = (y1 - foglio.ty) / foglio.scala - cima
        var cambiato = false
        val candidati = synchronized(p) { p.tratti.toList() }
        for (t in candidati) {
            val s = p.scatola(t)
            if (max(ax, bx) + r < s[0] || min(ax, bx) - r > s[2] || max(ay, by) + r < s[1] || min(ay, by) - r > s[3]) continue
            val raggio = r + t.spessore * 0.5f
            if (strumenti.modoGomma == ModoGomma.TRATTO) {
                if (tocca(t, ax, ay, bx, by, raggio)) {
                    val idx = synchronized(p) { p.rimuovi(t) }
                    if (t.id in creatiDallaGomma) creatiDallaGomma.remove(t.id) else tolti += idx to t
                    cambiato = true
                }
            } else {
                val pezzi = spezza(t, ax, ay, bx, by, raggio) ?: continue
                val idx = synchronized(p) { p.rimuovi(t) }
                if (t.id in creatiDallaGomma) creatiDallaGomma.remove(t.id) else tolti += idx to t
                for (pz in pezzi) {
                    val nuovo = Tratto(d.nuovoIdTratto(), t.penna, t.colore, t.spessore, pz)
                    synchronized(p) { p.aggiungi(nuovo) }
                    creatiDallaGomma += nuovo.id
                }
                cambiato = true
            }
        }
        if (cambiato) foglio.invalidate()
    }

    private fun fineGomma() {
        val d = documento ?: return
        val p = d.pagine.getOrNull(paginaGomma)
        if (p != null) {
            val aggiunti = synchronized(p) { p.tratti.filter { it.id in creatiDallaGomma } }
            d.registra(Modifica(p, tolti.toList(), aggiunti))
            if (tolti.isNotEmpty() || aggiunti.isNotEmpty()) alTratto?.invoke()
        }
        tolti.clear(); creatiDallaGomma.clear()
        paginaGomma = -1
        sopra.nascondiCursore()
    }

    // ---------------------------------------------------------------- lazo e selezione

    private val lazo = Path()
    private val puntiLazo = ArrayList<Float>()
    private var paginaLazo = -1

    /** Tratti ([ids]) e oggetti ([oggetti]) presi con il lazo, con il loro riquadro in unita' di pagina. */
    class Selezione(val pagina: Int, val ids: Set<Long>, val scatola: RectF, val oggetti: Set<String> = emptySet()) {
        val trasforma = Matrix()
    }

    var selezione: Selezione? = null
        private set(v) {
            field = v
            foglio.nascosti = v?.ids ?: emptySet()
            aggiornaNascosti()
            alCambioSelezione?.invoke(v != null)
            sopra.invalidate()
        }

    private fun iniziaLazo(e: MotionEvent) {
        paginaLazo = foglio.paginaSotto(e.x, e.y, tolleranza = 60f)
        lazo.reset(); puntiLazo.clear()
        lazo.moveTo(e.x, e.y)
        puntiLazo += e.x; puntiLazo += e.y
        sopra.invalidate()
    }

    private fun muoviLazo(e: MotionEvent) {
        for (h in 0 until e.historySize) {
            lazo.lineTo(e.getHistoricalX(h), e.getHistoricalY(h))
            puntiLazo += e.getHistoricalX(h); puntiLazo += e.getHistoricalY(h)
        }
        lazo.lineTo(e.x, e.y)
        puntiLazo += e.x; puntiLazo += e.y
        sopra.invalidate()
    }

    private fun fineLazo() {
        val d = documento
        val i = paginaLazo
        lazo.reset()
        // Un tocco (il lazo non si e' quasi mosso) seleziona l'oggetto sotto la penna.
        if (puntiLazo.size >= 2 && lazoFermo()) {
            val x = puntiLazo[0]; val y = puntiLazo[1]
            puntiLazo.clear()
            sopra.invalidate()
            oggettoSotto(x, y)?.let { (pag, o) -> selezioneOggetto = SelezioneOggetto(pag, o.id) }
            return
        }
        if (d == null || i < 0 || puntiLazo.size < 6) { puntiLazo.clear(); sopra.invalidate(); return }
        // Poligono del lazo in coordinate della pagina.
        val cima = foglio.cimaPagina(i)
        val poli = FloatArray(puntiLazo.size)
        for (k in puntiLazo.indices step 2) {
            poli[k] = (puntiLazo[k] - foglio.tx) / foglio.scala
            poli[k + 1] = (puntiLazo[k + 1] - foglio.ty) / foglio.scala - cima
        }
        puntiLazo.clear()
        val p = d.pagine[i]
        d.caricaTratti(p)
        val presi = HashSet<Long>()
        val oggettiPresi = HashSet<String>()
        val box = RectF()
        synchronized(p) {
            for (t in p.tratti) {
                var dentro = 0
                for (k in 0 until t.quanti) if (dentroPoligono(t.punti[k * Tratto.CAMPI], t.punti[k * Tratto.CAMPI + 1], poli)) dentro++
                if (dentro * 2 >= t.quanti) {
                    presi += t.id
                    val s = p.scatola(t)
                    if (box.isEmpty) box.set(s[0], s[1], s[2], s[3]) else box.union(s[0], s[1], s[2], s[3])
                }
            }
            // Un oggetto e' preso se il lazo ne circonda il centro.
            for (o in p.oggetti) {
                val r = DisegnoOggetti.riquadro(o)
                if (dentroPoligono(r.centerX(), r.centerY(), poli)) {
                    oggettiPresi += o.id
                    if (box.isEmpty) box.set(r) else box.union(r)
                }
            }
        }
        selezione = if (presi.isEmpty() && oggettiPresi.isEmpty()) null else Selezione(i, presi, box, oggettiPresi)
    }

    private fun lazoFermo(): Boolean {
        val x0 = puntiLazo[0]; val y0 = puntiLazo[1]
        for (k in puntiLazo.indices step 2) if (hypot(puntiLazo[k] - x0, puntiLazo[k + 1] - y0) > soglia) return false
        return true
    }

    fun chiudiSelezione() {
        selezione = null
    }

    /** Rettangolo della selezione sullo schermo. */
    fun rettangoloSelezione(s: Selezione, out: RectF = RectF()): RectF {
        out.set(s.scatola)
        s.trasforma.mapRect(out)
        foglio.paginaASchermo(s.pagina, tmpM).mapRect(out)
        return out
    }

    private val rTmp = RectF()
    private fun dentroSelezione(x: Float, y: Float, s: Selezione): Boolean {
        val r = rettangoloSelezione(s, rTmp)
        r.inset(-12 * densita, -12 * densita)
        return r.contains(x, y)
    }

    private fun dentroManiglia(x: Float, y: Float, s: Selezione): Boolean {
        val r = rettangoloSelezione(s, rTmp)
        return hypot(x - r.right, y - r.bottom) < 28 * densita
    }

    private var tx0 = 0f
    private var ty0 = 0f
    private val trasformaIniziale = Matrix()

    private fun iniziaTrasforma(e: MotionEvent, scala: Boolean) {
        tx0 = e.x; ty0 = e.y
        trasformaIniziale.set(selezione?.trasforma)
    }

    private fun muoviTrasforma(e: MotionEvent) {
        val s = selezione ?: return
        s.trasforma.set(trasformaIniziale)
        if (gesto == Gesto.SPOSTA_SELEZIONE) {
            s.trasforma.postTranslate((e.x - tx0) / foglio.scala, (e.y - ty0) / foglio.scala)
        } else {
            // Scala uniforme tenendo fermo l'angolo in alto a sinistra.
            val r = RectF(s.scatola).also { trasformaIniziale.mapRect(it) }
            val w0 = r.width().coerceAtLeast(1f)
            val dx = (e.x - tx0) / foglio.scala
            val f = ((w0 + dx) / w0).coerceIn(0.1f, 8f)
            s.trasforma.postScale(f, f, r.left, r.top)
        }
        sopra.invalidate()
    }

    private fun fineTrasforma() {
        val s = selezione ?: return
        val d = documento ?: return
        if (s.trasforma.isIdentity) return
        val p = d.pagine[s.pagina]
        val originali = synchronized(p) { p.tratti.filter { it.id in s.ids } }
        val valori = FloatArray(9).also { s.trasforma.getValues(it) }
        val fattore = valori[Matrix.MSCALE_X]
        val nuovi = originali.map { t ->
            val pts = t.punti.copyOf()
            val xy = FloatArray(2)
            for (k in 0 until t.quanti) {
                val o = k * Tratto.CAMPI
                xy[0] = pts[o]; xy[1] = pts[o + 1]
                s.trasforma.mapPoints(xy)
                pts[o] = xy[0]; pts[o + 1] = xy[1]
            }
            Tratto(d.nuovoIdTratto(), t.penna, t.colore, t.spessore * fattore, pts)
        }
        val tolti = synchronized(p) { originali.map { p.tratti.indexOf(it) to it } }
        // Gli oggetti seguono la stessa trasformazione, allo stesso posto nell'ordine di disegno.
        val oggettiTolti = synchronized(p) { p.oggetti.withIndex().filter { it.value.id in s.oggetti }.map { it.index to it.value } }
        val oggettiNuovi = oggettiTolti.map { (i, o) -> i to trasformato(o, s.trasforma, fattore) }
        d.esegui(Modifica(p, tolti, nuovi, oggettiTolti, oggettiNuovi))
        val box = RectF(s.scatola).also { s.trasforma.mapRect(it) }
        selezione = Selezione(s.pagina, nuovi.map { it.id }.toSet(), box, s.oggetti)
        foglio.invalidate()
        alTratto?.invoke()
    }

    /** Un oggetto spostato e scalato da [m] (solo traslazione e scala uniforme, come fa il lazo). */
    private fun trasformato(o: Oggetto, m: Matrix, fattore: Float): Oggetto {
        val xy = floatArrayOf(o.x, o.y)
        m.mapPoints(xy)
        return when (o) {
            is Immagine -> o.copy(x = xy[0], y = xy[1], larghezza = o.larghezza * fattore, altezza = o.altezza * fattore)
            is Testo -> o.copy(
                x = xy[0], y = xy[1], larghezza = (o.larghezza * fattore).coerceAtLeast(Testo.LARGHEZZA_MIN),
                contenuto = if (abs(fattore - 1f) < 0.001f) o.contenuto else o.contenuto.scalato(fattore),
            )
        }
    }

    /** Operazioni sulla selezione, chiamate dalla barra del lazo. */
    fun eliminaSelezione() {
        val s = selezione ?: return
        val d = documento ?: return
        val p = d.pagine[s.pagina]
        val tolti = synchronized(p) { p.tratti.withIndex().filter { it.value.id in s.ids }.map { it.index to it.value } }
        val oggettiTolti = synchronized(p) { p.oggetti.withIndex().filter { it.value.id in s.oggetti }.map { it.index to it.value } }
        d.esegui(Modifica(p, tolti, emptyList(), oggettiTolti))
        selezione = null
        foglio.invalidate()
        alTratto?.invoke()
    }

    fun coloraSelezione(argb: Int) {
        val s = selezione ?: return
        val d = documento ?: return
        val p = d.pagine[s.pagina]
        val originali = synchronized(p) { p.tratti.withIndex().filter { it.value.id in s.ids }.map { it.index to it.value } }
        val nuovi = originali.map { (_, t) -> Tratto(d.nuovoIdTratto(), t.penna, argb, t.spessore, t.punti) }
        d.esegui(Modifica(p, originali, nuovi))
        selezione = Selezione(s.pagina, nuovi.map { it.id }.toSet(), RectF(s.scatola), s.oggetti)
        foglio.invalidate()
        alTratto?.invoke()
    }

    /**
     * Mette [nuovi] al posto dei tratti selezionati, in un'unica modifica annullabile.
     * Gli evidenziatori restano dove sono: sottolineano il testo anche dopo averlo riscritto.
     */
    fun sostituisciSelezione(nuovi: List<Tratto>) {
        val s = selezione ?: return
        val d = documento ?: return
        val p = d.pagine[s.pagina]
        val tolti = synchronized(p) {
            p.tratti.withIndex().filter { it.value.id in s.ids && it.value.penna != Penna.EVIDENZIATORE }.map { it.index to it.value }
        }
        d.esegui(Modifica(p, tolti, nuovi))
        val box = RectF()
        synchronized(p) { nuovi.forEach { t -> val b = p.scatola(t); if (box.isEmpty) box.set(b[0], b[1], b[2], b[3]) else box.union(b[0], b[1], b[2], b[3]) } }
        selezione = Selezione(s.pagina, nuovi.map { it.id }.toSet(), box)
        foglio.invalidate()
        alTratto?.invoke()
    }

    fun nuovoId(): Long = documento?.nuovoIdTratto() ?: System.nanoTime()

    fun copiaSelezione(): List<Tratto> {
        val s = selezione ?: return emptyList()
        val p = documento?.pagine?.getOrNull(s.pagina) ?: return emptyList()
        return synchronized(p) { p.tratti.filter { it.id in s.ids } }
    }

    /** Gli oggetti presi con il lazo, nell'ordine di disegno. */
    fun copiaOggettiSelezione(): List<Oggetto> {
        val s = selezione ?: return emptyList()
        val p = documento?.pagine?.getOrNull(s.pagina) ?: return emptyList()
        return synchronized(p) { p.oggetti.filter { it.id in s.oggetti } }
    }

    /**
     * Incolla dei tratti (e degli oggetti, gia' pronti per questa nota) al centro della pagina visibile,
     * spostati di un poco se sono uguali.
     */
    fun incolla(tratti: List<Tratto>, oggetti: List<Oggetto> = emptyList()) {
        val d = documento ?: return
        if (tratti.isEmpty() && oggetti.isEmpty()) return
        val i = foglio.paginaCorrente()
        val p = d.pagine[i]
        d.caricaTratti(p)
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        tratti.forEach { t -> for (k in 0 until t.quanti) { val x = t.punti[k * Tratto.CAMPI]; val y = t.punti[k * Tratto.CAMPI + 1]; minX = min(minX, x); minY = min(minY, y); maxX = max(maxX, x); maxY = max(maxY, y) } }
        oggetti.forEach { o -> val r = DisegnoOggetti.riquadro(o); minX = min(minX, r.left); minY = min(minY, r.top); maxX = max(maxX, r.right); maxY = max(maxY, r.bottom) }
        val cy = (height / 2f - foglio.ty) / foglio.scala - foglio.cimaPagina(i)
        val dx = FoglioView.LARGHEZZA / 2 - (minX + maxX) / 2 + 20f
        val dy = cy - (minY + maxY) / 2 + 20f
        val nuovi = tratti.map { t ->
            val pts = t.punti.copyOf()
            for (k in 0 until t.quanti) { pts[k * Tratto.CAMPI] += dx; pts[k * Tratto.CAMPI + 1] += dy }
            Tratto(d.nuovoIdTratto(), t.penna, t.colore, t.spessore, pts)
        }
        val base = synchronized(p) { p.oggetti.size }
        val oggettiNuovi = oggetti.mapIndexed { k, o -> base + k to o.spostato(dx, dy) }
        d.esegui(Modifica(p, emptyList(), nuovi, emptyList(), oggettiNuovi))
        selezione = Selezione(
            i, nuovi.map { it.id }.toSet(), RectF(minX + dx, minY + dy, maxX + dx, maxY + dy).apply { inset(-6f, -6f) },
            oggettiNuovi.map { it.second.id }.toSet(),
        )
        foglio.invalidate()
        alTratto?.invoke()
    }

    fun ridisegna() {
        foglio.invalidate()
        sopra.invalidate()
    }

    // ---------------------------------------------------------------- oggetti: selezione, spostamento, maniglie

    /** Un oggetto scelto con un tocco: mostra cornice, maniglie e la sua barra. */
    class SelezioneOggetto(val pagina: Int, val id: String)

    var selezioneOggetto: SelezioneOggetto? = null
        private set(v) {
            field = v
            originale = null
            anteprima = null
            aggiornaNascosti()
            alCambioOggetto?.invoke(oggettoSelezionato())
            sopra.invalidate()
        }

    /** L'oggetto selezionato com'e' adesso nella pagina, o null. */
    fun oggettoSelezionato(): Oggetto? {
        val s = selezioneOggetto ?: return null
        val p = documento?.pagine?.getOrNull(s.pagina) ?: return null
        return synchronized(p) { p.oggetto(s.id) }
    }

    fun selezionaOggetto(pagina: Int, id: String) {
        chiudiSelezione()
        selezioneOggetto = SelezioneOggetto(pagina, id)
    }

    fun deselezionaOggetto() {
        if (selezioneOggetto != null) selezioneOggetto = null
    }

    /** L'oggetto selezionato mentre lo si trascina (disegnato sopra; quello vero resta nascosto). */
    private var anteprima: Oggetto? = null
    private var originale: Oggetto? = null
    private var maniglia = NESSUNA
    private var ox0 = 0f
    private var oy0 = 0f

    /** Il documento nasconde gli oggetti che si stanno spostando o modificando: li disegna qualcun altro. */
    private fun aggiornaNascosti() {
        val n = HashSet<String>()
        selezione?.oggetti?.let { n += it }
        if (anteprima != null) selezioneOggetto?.let { n += it.id }
        modificaTesto?.originale?.let { n += it.id }
        foglio.oggettiNascosti = n
    }

    /** Punto dello schermo in coordinate della pagina [i]. */
    private fun inPagina(i: Int, x: Float, y: Float) =
        PointF((x - foglio.tx) / foglio.scala, (y - foglio.ty) / foglio.scala - foglio.cimaPagina(i))

    /** La pagina sotto il punto dello schermo e il punto in coordinate di quella pagina, o null. */
    fun puntoPagina(x: Float, y: Float): Pair<Int, PointF>? {
        val i = foglio.paginaSotto(x, y)
        return if (i < 0) null else i to inPagina(i, x, y)
    }

    /** L'oggetto piu' in alto sotto il punto dello schermo, con la sua pagina. */
    fun oggettoSotto(x: Float, y: Float): Pair<Int, Oggetto>? {
        val d = documento ?: return null
        val i = foglio.paginaSotto(x, y)
        if (i < 0) return null
        val p = d.pagine[i]
        if (!p.caricata) return null
        val pt = inPagina(i, x, y)
        val margine = 6 * densita / foglio.scala
        val lista = synchronized(p) { ArrayList(p.oggetti) }
        val r = RectF()
        for (o in lista.asReversed()) {
            DisegnoOggetti.riquadro(o, r)
            r.inset(-margine, -margine)
            if (r.contains(pt.x, pt.y)) return i to o
        }
        return null
    }

    /** Riquadro sullo schermo di un oggetto della pagina [i]. */
    fun rettangoloOggetto(i: Int, o: Oggetto, out: RectF = RectF()): RectF {
        DisegnoOggetti.riquadro(o, out)
        foglio.paginaASchermo(i, tmpM).mapRect(out)
        return out
    }

    /**
     * Le maniglie di un oggetto sullo schermo: gli angoli per le immagini (0 in alto a sinistra, poi in
     * senso orario), i lati per il testo, che cambia solo larghezza.
     */
    private fun maniglie(o: Oggetto, r: RectF): List<Pair<Int, PointF>> = when (o) {
        is Immagine -> listOf(0 to PointF(r.left, r.top), 1 to PointF(r.right, r.top), 2 to PointF(r.right, r.bottom), 3 to PointF(r.left, r.bottom))
        is Testo -> listOf(SINISTRA to PointF(r.left, r.centerY()), DESTRA to PointF(r.right, r.centerY()))
    }

    /** Su quale parte dell'oggetto selezionato cade il punto: una maniglia, [DENTRO] o [NESSUNA]. */
    private fun manigliaSotto(x: Float, y: Float): Int {
        val s = selezioneOggetto ?: return NESSUNA
        val o = oggettoSelezionato() ?: return NESSUNA
        val r = rettangoloOggetto(s.pagina, o, rTmp)
        for ((k, pt) in maniglie(o, r)) if (hypot(x - pt.x, y - pt.y) < 24 * densita) return k
        r.inset(-8 * densita, -8 * densita)
        return if (r.contains(x, y)) DENTRO else NESSUNA
    }

    private fun iniziaOggetto(x: Float, y: Float, m: Int) {
        originale = oggettoSelezionato()
        maniglia = m
        ox0 = x; oy0 = y
        anteprima = null
    }

    private fun muoviOggetto(x: Float, y: Float) {
        val o = originale ?: return
        val s = selezioneOggetto ?: return
        // Finche' non ci si muove davvero e' un tocco, non uno spostamento.
        if (anteprima == null && hypot(x - ox0, y - oy0) < soglia) return
        val dx = (x - ox0) / foglio.scala
        val dy = (y - oy0) / foglio.scala
        val altezza = documento?.pagine?.getOrNull(s.pagina)?.pagina?.altezza ?: return
        anteprima = when {
            maniglia == DENTRO -> {
                // Almeno un pezzo resta sulla pagina.
                val r = DisegnoOggetti.riquadro(o)
                val nx = (o.x + dx).coerceIn(-r.width() + 40f, FoglioView.LARGHEZZA - 40f)
                val ny = (o.y + dy).coerceIn(-r.height() + 40f, altezza - 40f)
                o.spostato(nx - o.x, ny - o.y)
            }
            o is Immagine -> ridimensionata(o, maniglia, dx, dy)
            o is Testo -> allargato(o, maniglia, dx)
            else -> o
        }
        aggiornaNascosti()
        sopra.invalidate()
    }

    /** Immagine con l'angolo [angolo] spostato di (dx, dy), proporzioni fisse e angolo opposto fermo. */
    private fun ridimensionata(o: Immagine, angolo: Int, dx: Float, dy: Float): Immagine {
        val sinistra = angolo == 0 || angolo == 3
        val alto = angolo == 0 || angolo == 1
        val fx = if (sinistra) o.x + o.larghezza else o.x
        val fy = if (alto) o.y + o.altezza else o.y
        val vx = if (sinistra) -o.larghezza else o.larghezza
        val vy = if (alto) -o.altezza else o.altezza
        // Proiezione del punto sulla diagonale: il ridimensionamento segue il dito senza scatti.
        val px = fx + vx + dx - fx
        val py = fy + vy + dy - fy
        val minimo = 24f / min(o.larghezza, o.altezza)
        val f = ((px * vx + py * vy) / (vx * vx + vy * vy)).coerceIn(minimo, 20f)
        val w = o.larghezza * f
        val h = o.altezza * f
        return o.copy(x = if (sinistra) fx - w else fx, y = if (alto) fy - h else fy, larghezza = w, altezza = h)
    }

    /** Casella di testo allargata dal lato [lato]: il testo va a capo di nuovo. */
    private fun allargato(o: Testo, lato: Int, dx: Float): Testo = if (lato == SINISTRA) {
        val destra = o.x + o.larghezza
        val nx = min(o.x + dx, destra - Testo.LARGHEZZA_MIN)
        o.copy(x = nx, larghezza = destra - nx)
    } else {
        o.copy(larghezza = max(Testo.LARGHEZZA_MIN, o.larghezza + dx))
    }

    /** Fine del trascinamento: registra la modifica. Falso se non ci si e' mossi (era un tocco). */
    private fun fineOggetto(): Boolean {
        val a = anteprima
        val o = originale
        val s = selezioneOggetto
        originale = null
        if (a == null || o == null || s == null) {
            anteprima = null
            aggiornaNascosti()
            return false
        }
        val d = documento
        val p = d?.pagine?.getOrNull(s.pagina)
        if (d != null && p != null) {
            val i = synchronized(p) { p.indiceOggetto(o.id) }
            if (i >= 0) d.esegui(Modifica(p, emptyList(), emptyList(), listOf(i to o), listOf(i to a)))
        }
        anteprima = null
        aggiornaNascosti()
        foglio.invalidate()
        sopra.invalidate()
        alTratto?.invoke()
        alCambioOggetto?.invoke(oggettoSelezionato())
        return true
    }

    private fun annullaOggetto() {
        originale = null
        anteprima = null
        aggiornaNascosti()
        sopra.invalidate()
    }

    /** Tocco (senza trascinare) sull'oggetto gia' selezionato: una casella di testo si apre per scriverci. */
    private fun toccoOggettoSelezionato(x: Float, y: Float, penna: Boolean) {
        val s = selezioneOggetto ?: return
        val o = oggettoSelezionato() as? Testo ?: return
        if (penna && strumenti.strumento != Strumento.TESTO) return
        apriTesto(s.pagina, o, x, y)
    }

    /** Aggiunge un oggetto alla pagina [pagina] (in cima all'ordine di disegno) e lo seleziona. */
    fun aggiungiOggetto(pagina: Int, o: Oggetto) {
        val d = documento ?: return
        val p = d.pagine.getOrNull(pagina) ?: return
        d.caricaTratti(p)
        val i = synchronized(p) { p.oggetti.size }
        d.esegui(Modifica(p, emptyList(), emptyList(), oggettiAggiunti = listOf(i to o)))
        foglio.invalidate()
        alTratto?.invoke()
        selezionaOggetto(pagina, o.id)
    }

    fun eliminaOggetto() {
        val s = selezioneOggetto ?: return
        val d = documento ?: return
        val p = d.pagine.getOrNull(s.pagina) ?: return
        val o = oggettoSelezionato() ?: return
        val i = synchronized(p) { p.indiceOggetto(o.id) }
        d.esegui(Modifica(p, emptyList(), emptyList(), listOf(i to o)))
        selezioneOggetto = null
        foglio.invalidate()
        alTratto?.invoke()
    }

    /** Una copia poco piu' in basso a destra; per le immagini la copia usa lo stesso file (non cambia mai). */
    fun duplicaOggetto() {
        val s = selezioneOggetto ?: return
        val o = oggettoSelezionato() ?: return
        val copia = when (o) {
            is Immagine -> o.copy(id = Archivio.nuovoId(), x = o.x + 24f, y = o.y + 24f)
            is Testo -> o.copy(id = Archivio.nuovoId(), x = o.x + 24f, y = o.y + 24f)
        }
        aggiungiOggetto(s.pagina, copia)
    }

    /** Porta l'oggetto selezionato un posto piu' in alto ([avanti]) o piu' in basso nell'ordine di disegno. */
    fun spostaInOrdine(avanti: Boolean) {
        val s = selezioneOggetto ?: return
        val d = documento ?: return
        val p = d.pagine.getOrNull(s.pagina) ?: return
        val o = oggettoSelezionato() ?: return
        val (i, n) = synchronized(p) { p.indiceOggetto(o.id) to p.oggetti.size }
        val j = if (avanti) i + 1 else i - 1
        if (i < 0 || j !in 0 until n) return
        d.esegui(Modifica(p, emptyList(), emptyList(), listOf(i to o), listOf(j to o)))
        foglio.invalidate()
        alTratto?.invoke()
        alCambioOggetto?.invoke(o)
    }

    /** Posizione dell'oggetto selezionato nell'ordine di disegno: (indice, quanti), per abilitare i pulsanti. */
    fun ordineOggetto(): Pair<Int, Int> {
        val s = selezioneOggetto ?: return -1 to 0
        val p = documento?.pagine?.getOrNull(s.pagina) ?: return -1 to 0
        return synchronized(p) { p.indiceOggetto(s.id) to p.oggetti.size }
    }

    /** Apre la casella di testo selezionata per scriverci. */
    fun modificaTestoSelezionato() {
        val s = selezioneOggetto ?: return
        val o = oggettoSelezionato() as? Testo ?: return
        apriTesto(s.pagina, o)
    }

    /** Dove inserire qualcosa senza un punto preciso: il centro della parte visibile della pagina corrente. */
    fun puntoInserimento(): Pair<Int, PointF> {
        val i = foglio.paginaCorrente()
        val altezza = documento?.pagine?.getOrNull(i)?.pagina?.altezza ?: FoglioView.LARGHEZZA
        val alto = foglio.spazioSopra
        val pt = inPagina(i, width / 2f, (alto + height) / 2f)
        return i to PointF(pt.x.coerceIn(0f, FoglioView.LARGHEZZA), pt.y.coerceIn(0f, altezza))
    }

    // ---------------------------------------------------------------- caselle di testo

    /** La casella in modifica: pagina, oggetto di partenza (null se nuova) e posto. */
    private class ModificaTesto(val pagina: PaginaViva, val originale: Testo?, val id: String, val x: Float, val y: Float, val larghezza: Float)

    private var campo: CampoTesto? = null
    private var modificaTesto: ModificaTesto? = null

    val inModificaTesto: Boolean get() = campo != null

    /**
     * Pixel in basso coperti da tastiera e barra del testo: la riga del cursore resta sopra, e si puo'
     * scorrere oltre la fine della nota di altrettanto.
     */
    var spazioSotto = 0f
        set(v) {
            if (field == v) return
            field = v
            foglio.spazioSotto = if (campo != null) v else 0f
            if (campo != null) post { mostraCursore() }
        }

    /** Riquadro che si sta tirando con la penna e lo strumento Testo (sullo schermo). */
    private var riquadroTesto: RectF? = null

    private fun iniziaRiquadroTesto(x: Float, y: Float) {
        riquadroTesto = RectF(x, y, x, y)
    }

    private fun muoviRiquadroTesto(x: Float, y: Float) {
        riquadroTesto?.let { it.right = x; it.bottom = y; sopra.invalidate() }
    }

    /**
     * Penna con lo strumento Testo: un tocco su una casella la apre, altrove ne crea una; tirando in
     * orizzontale si sceglie anche la larghezza della casella nuova.
     */
    private fun fineRiquadroTesto(x: Float, y: Float) {
        val r = riquadroTesto ?: return
        riquadroTesto = null
        sopra.invalidate()
        val x0 = r.left; val y0 = r.top
        val tirato = abs(x - x0) / foglio.scala
        if (tirato < 40f) {
            val sotto = oggettoSotto(x0, y0)
            if (sotto != null && sotto.second is Testo) { apriTesto(sotto.first, sotto.second as Testo, x0, y0); return }
            puntoPagina(x0, y0)?.let { (i, pt) -> nuovoTesto(i, pt.x, pt.y) }
        } else {
            val (i, pt) = puntoPagina(min(x0, x), y0) ?: return
            nuovoTesto(i, pt.x, pt.y, larghezza = tirato, tenereX = true)
        }
    }

    /**
     * Crea una casella vuota nella pagina [i] con la prima riga all'altezza di [y] e la apre per scrivere.
     * Senza [larghezza] e' larga mezza pagina, e comunque resta dentro i margini.
     */
    fun nuovoTesto(i: Int, x: Float, y: Float, larghezza: Float? = null, tenereX: Boolean = false) {
        val p = documento?.pagine?.getOrNull(i) ?: return
        val w = (larghezza ?: LARGHEZZA_TESTO).coerceIn(Testo.LARGHEZZA_MIN, FoglioView.LARGHEZZA - 2 * MARGINE_TESTO)
        val x0 = if (tenereX) x.coerceIn(0f, FoglioView.LARGHEZZA - w) else x.coerceIn(MARGINE_TESTO, FoglioView.LARGHEZZA - MARGINE_TESTO - w)
        val riga = Impaginazione.ALTEZZA_MIN
        val y0 = (y - riga / 2f).coerceIn(0f, p.pagina.altezza - riga)
        apri(i, null, Archivio.nuovoId(), x0, y0, w, TestoRicco.VUOTO, null)
    }

    /**
     * Apre [t] (della pagina [i]) per scriverci. Con [tx], [ty] (schermo) il cursore va li', altrimenti in fondo.
     */
    fun apriTesto(i: Int, t: Testo, tx: Float? = null, ty: Float? = null) {
        val tocco = if (tx != null && ty != null) inPagina(i, tx, ty).let { PointF(it.x - t.x, it.y - t.y) } else null
        apri(i, t, t.id, t.x, t.y, t.larghezza, t.contenuto, tocco)
    }

    private fun apri(i: Int, originale: Testo?, id: String, x: Float, y: Float, larghezza: Float, contenuto: TestoRicco, tocco: PointF?) {
        chiudiTesto()
        chiudiSelezione()
        deselezionaOggetto()
        val d = documento ?: return
        val p = d.pagine.getOrNull(i) ?: return
        modificaTesto = ModificaTesto(p, originale, id, x, y, larghezza)
        val c = CampoTesto(context)
        c.carica(contenuto)
        c.alCambioAltezza = { sopra.invalidate() }
        addView(c, indexOfChild(inchiostro), LayoutParams(larghezza.roundToInt().coerceAtLeast(1), LayoutParams.WRAP_CONTENT))
        campo = c
        foglio.spazioSotto = spazioSotto
        posizionaCampo()
        aggiornaNascosti()
        c.requestFocus()
        c.post {
            if (tocco != null) c.setSelection(c.getOffsetForPosition(tocco.x, tocco.y).coerceIn(0, c.text.length))
            context.getSystemService(InputMethodManager::class.java)?.showSoftInput(c, 0)
            mostraCursore()
        }
        alCampoTesto?.invoke(c)
    }

    /**
     * Chiude la casella in modifica e registra il risultato come una modifica annullabile. Una casella
     * rimasta vuota sparisce.
     */
    fun chiudiTesto() {
        val c = campo ?: return
        val m = modificaTesto ?: return
        campo = null
        modificaTesto = null
        val nuovo = c.modello()
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(c.windowToken, 0)
        c.clearFocus()
        removeView(c)
        requestFocus()
        foglio.spazioSotto = 0f
        val d = documento
        val p = m.pagina
        if (d != null && d.pagine.any { it === p }) {
            val orig = m.originale
            if (orig == null) {
                if (!nuovo.vuoto) {
                    val i = synchronized(p) { p.oggetti.size }
                    d.esegui(Modifica(p, emptyList(), emptyList(), oggettiAggiunti = listOf(i to Testo(m.id, m.x, m.y, m.larghezza, nuovo))))
                }
            } else {
                val i = synchronized(p) { p.indiceOggetto(orig.id) }
                if (i >= 0 && nuovo.vuoto) d.esegui(Modifica(p, emptyList(), emptyList(), listOf(i to orig)))
                else if (i >= 0 && nuovo != orig.contenuto) d.esegui(Modifica(p, emptyList(), emptyList(), listOf(i to orig), listOf(i to orig.copy(contenuto = nuovo))))
            }
            alTratto?.invoke()
        }
        aggiornaNascosti()
        foglio.invalidate()
        sopra.invalidate()
        alCampoTesto?.invoke(null)
    }

    /** Mette il campo sopra la casella, alla scala della pagina. */
    private fun posizionaCampo() {
        val c = campo ?: return
        val m = modificaTesto ?: return
        val i = documento?.pagine?.indexOfFirst { it === m.pagina } ?: -1
        if (i < 0) return
        val s = foglio.scala
        c.pivotX = 0f
        c.pivotY = 0f
        c.scaleX = s
        c.scaleY = s
        c.translationX = foglio.tx + m.x * s
        c.translationY = foglio.ty + (foglio.cimaPagina(i) + m.y) * s
    }

    /** Il campo sullo schermo (coordinate della vista). */
    private fun rettangoloCampo(out: RectF): RectF? {
        val c = campo ?: return null
        out.set(c.translationX, c.translationY, c.translationX + c.width * c.scaleX, c.translationY + c.height * c.scaleY)
        return out
    }

    private fun dentroCampo(x: Float, y: Float): Boolean {
        val r = rettangoloCampo(rTmp) ?: return false
        r.inset(-6 * densita, -6 * densita)
        return r.contains(x, y)
    }

    /** Il campo chiede di mostrare una sua parte (il cursore): si scorre il foglio, non il campo. */
    override fun requestChildRectangleOnScreen(child: View, rectangle: Rect, immediate: Boolean): Boolean {
        if (child !== campo) return super.requestChildRectangleOnScreen(child, rectangle, immediate)
        mostra(rectangle.top.toFloat(), rectangle.bottom.toFloat())
        return true
    }

    /** Porta sopra la tastiera la riga del cursore. */
    private fun mostraCursore() {
        val c = campo ?: return
        val l = c.layout ?: return
        val riga = l.getLineForOffset(c.selectionEnd.coerceAtLeast(0))
        mostra(l.getLineTop(riga).toFloat(), l.getLineBottom(riga).toFloat())
    }

    /** Scorre il foglio perche' la fascia [alto, basso] del campo (in unita' del campo) sia visibile. */
    private fun mostra(alto: Float, basso: Float) {
        val c = campo ?: return
        val s = c.scaleY
        val a = c.translationY + alto * s
        val b = c.translationY + basso * s
        val min = foglio.spazioSopra + 12 * densita
        val max = height - spazioSotto - 12 * densita
        val dy = when {
            b > max -> max - b
            a < min -> min - a
            else -> 0f
        }
        if (dy != 0f) foglio.trasla(0f, dy)
    }

    // ---------------------------------------------------------------- dita: scorrimento e zoom

    private val scorritore = OverScroller(context)
    private var inGestoDita = false
    private var ultimoX = 0f
    private var ultimoY = 0f
    private var velocita: android.view.VelocityTracker? = null
    private var ultimoTocco = 0L
    private var ultimoToccoX = 0f
    private var ultimoToccoY = 0f
    private var spostato = false

    // Tirare oltre la fine della nota per aggiungere una pagina, come in Samsung Notes.
    private var trazione = 0f
    /** Si tira solo se il dito si e' appoggiato con la nota gia' in fondo: uno scorrimento veloce si ferma li'. */
    private var tiraggioPossibile = false
    private val tirataMassima = 200 * densita
    private var ritorno: android.animation.ValueAnimator? = null

    /** Il dito sta spostando o ridimensionando l'oggetto selezionato invece di scorrere. */
    private var ditoSuOggetto = false
    private var ditoX0 = 0f
    private var ditoY0 = 0f
    /** Il dito si e' allontanato dal punto dove si e' appoggiato: non e' piu' un tocco. */
    private var ditoLontano = false
    /** La pressione lunga e' scattata: il resto del gesto non fa altro. */
    private var lungaFatta = false
    private val pressioneLunga = Runnable { pressioneLunga() }

    private val pizzico = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean { foglio.pizzicando = true; return true }
        override fun onScale(d: ScaleGestureDetector): Boolean {
            foglio.zoom(d.scaleFactor, d.focusX, d.focusY)
            return true
        }
        override fun onScaleEnd(d: ScaleGestureDetector) { foglio.pizzicando = false }
    }).apply { isQuickScaleEnabled = false }

    private fun annullaGestoDita() {
        inGestoDita = false
        foglio.pizzicando = false
        velocita?.recycle(); velocita = null
        annullaPressioneLunga()
        if (ditoSuOggetto) { ditoSuOggetto = false; annullaOggetto() }
    }

    private fun annullaPressioneLunga() {
        removeCallbacks(pressioneLunga)
    }

    private fun pressioneLunga() {
        if (!inGestoDita || ditoLontano || ditoSuOggetto || pennaGiu) return
        // Su un oggetto lo seleziona; su un punto vuoto della pagina apre il menu (incolla, inserisci...).
        val sotto = oggettoSotto(ditoX0, ditoY0)
        val menu = alPressioneLunga
        if (sotto == null && (menu == null || foglio.paginaSotto(ditoX0, ditoY0) < 0)) return
        lungaFatta = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (campo != null) chiudiTesto()
        if (sotto != null) {
            selezionaOggetto(sotto.first, sotto.second.id)
        } else {
            deselezionaOggetto()
            menu?.invoke(ditoX0, ditoY0)
        }
    }

    private fun tocchiDita(e: MotionEvent): Boolean {
        if (ditoSuOggetto) return ditoSuOggetto(e)
        pizzico.onTouchEvent(e)
        if (velocita == null) velocita = android.view.VelocityTracker.obtain()
        velocita?.addMovement(e)
        val (cx, cy) = centro(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                ditoX0 = e.x; ditoY0 = e.y
                ditoLontano = false
                lungaFatta = false
                // Sull'oggetto selezionato il dito lo sposta (o lo ridimensiona dalle maniglie).
                val m = manigliaSotto(e.x, e.y)
                if (m != NESSUNA) {
                    scorritore.forceFinished(true)
                    inGestoDita = true
                    ditoSuOggetto = true
                    iniziaOggetto(e.x, e.y, m)
                    return true
                }
                tiraggioPossibile = scorritore.isFinished && foglio.ty <= foglio.limiteTy.start + 1f
                scorritore.forceFinished(true)
                ritorno?.cancel(); ritorno = null
                foglio.tirata = 0f
                foglio.sogliaTirata = 0.5f * tirataMassima
                inGestoDita = true
                spostato = false
                ultimoX = cx; ultimoY = cy
                postDelayed(pressioneLunga, DURATA_PRESSIONE_LUNGA)
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                ultimoX = cx; ultimoY = cy
                spostato = true
                ditoLontano = true
                annullaPressioneLunga()
                if (trazione > 0f) rilasciaTirata(false)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!ditoLontano && hypot(e.x - ditoX0, e.y - ditoY0) > soglia) { ditoLontano = true; annullaPressioneLunga() }
                // Dopo la pressione lunga il dito resta fermo: il menu e' aperto.
                if (lungaFatta) return true
                val dx = cx - ultimoX; var dy = cy - ultimoY
                if (abs(dx) + abs(dy) > 1f) spostato = true
                if (e.pointerCount == 1 && !foglio.pizzicando) dy = tira(dy)
                foglio.trasla(dx, dy)
                ultimoX = cx; ultimoY = cy
            }
            MotionEvent.ACTION_UP -> {
                annullaPressioneLunga()
                val v = velocita
                if (lungaFatta) {
                    // Il gesto e' gia' stato usato dalla pressione lunga.
                } else if (trazione > 0f) {
                    // Una sgommata veloce verso l'alto e' solo voglia di scorrere: la pagina si
                    // aggiunge quando si tira e si lascia con calma, come per aggiornare una lista.
                    v?.computeCurrentVelocity(1000)
                    val calmo = (v?.yVelocity ?: 0f) > -1200f * densita
                    rilasciaTirata(foglio.tirata >= foglio.sogliaTirata && calmo)
                } else if (!ditoLontano && toccoDito(e.x, e.y)) {
                    // Tocco usato da un oggetto o dallo strumento Testo.
                    ultimoTocco = 0
                } else if (v != null && spostato) {
                    v.computeCurrentVelocity(1000)
                    val tx = foglio.limiteTx; val ty = foglio.limiteTy
                    scorritore.fling(foglio.tx.toInt(), foglio.ty.toInt(), v.xVelocity.toInt(), v.yVelocity.toInt(),
                        tx.start.toInt(), tx.endInclusive.toInt(), ty.start.toInt(), ty.endInclusive.toInt())
                    postInvalidateOnAnimation()
                } else if (!spostato) {
                    // Doppio tocco con il dito: torna alla larghezza della pagina.
                    if (e.eventTime - ultimoTocco < 300 && hypot(e.x - ultimoToccoX, e.y - ultimoToccoY) < 60 * densita) {
                        adattaLarghezza()
                        ultimoTocco = 0
                    } else {
                        ultimoTocco = e.eventTime; ultimoToccoX = e.x; ultimoToccoY = e.y
                    }
                }
                annullaGestoDita()
            }
            MotionEvent.ACTION_CANCEL -> { if (trazione > 0f) rilasciaTirata(false); annullaGestoDita() }
        }
        return true
    }

    /** Il dito sposta o ridimensiona l'oggetto selezionato; un secondo dito torna a scorrere e zoomare. */
    private fun ditoSuOggetto(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> muoviOggetto(e.x, e.y)
            MotionEvent.ACTION_UP -> {
                ditoSuOggetto = false
                val trascinato = fineOggetto()
                annullaGestoDita()
                if (!trascinato) toccoOggettoSelezionato(e.x, e.y, penna = false)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Il secondo dito vuol dire scorrere o zoomare: l'oggetto torna dov'era.
                ditoSuOggetto = false
                annullaOggetto()
                val (cx, cy) = centro(e)
                ultimoX = cx; ultimoY = cy
                spostato = true
                ditoLontano = true
                trazione = 0f
                tiraggioPossibile = false
                pizzico.onTouchEvent(e)
                velocita?.addMovement(e)
            }
            MotionEvent.ACTION_CANCEL -> { ditoSuOggetto = false; annullaOggetto(); annullaGestoDita() }
        }
        return true
    }

    /**
     * Un tocco del dito (senza trascinare). Restituisce vero se l'ha usato: seleziona un oggetto, apre
     * o crea una casella con lo strumento Testo, toglie la selezione toccando fuori.
     */
    private fun toccoDito(x: Float, y: Float): Boolean {
        if (campo != null) { chiudiTesto(); return true }
        val sotto = oggettoSotto(x, y)
        if (strumenti.strumento == Strumento.TESTO) {
            when {
                sotto != null && sotto.second is Testo -> apriTesto(sotto.first, sotto.second as Testo, x, y)
                sotto != null -> selezionaOggetto(sotto.first, sotto.second.id)
                else -> puntoPagina(x, y)?.let { (i, pt) -> nuovoTesto(i, pt.x, pt.y) } ?: deselezionaOggetto()
            }
            return true
        }
        if (sotto != null) {
            selezionaOggetto(sotto.first, sotto.second.id)
            return true
        }
        if (selezioneOggetto != null) {
            deselezionaOggetto()
            return true
        }
        return false
    }

    /**
     * Con la nota gia' in fondo, lo spostamento verso l'alto diventa trazione (con resistenza
     * crescente) invece di scorrere; tornando giu' la trazione cala e poi si riprende a scorrere.
     * Restituisce lo spostamento che resta da applicare al foglio.
     */
    private fun tira(dy: Float): Float {
        val inFondo = foglio.ty <= foglio.limiteTy.start + 1f
        if (trazione <= 0f && !(tiraggioPossibile && inFondo && dy < 0f)) return dy
        val prima = trazione
        val eraPronta = foglio.tirata >= foglio.sogliaTirata
        trazione = (trazione - dy).coerceAtLeast(0f)
        foglio.paginaFantasma = trazione > 0f
        foglio.tirata = tirataMassima * (1f - kotlin.math.exp(-trazione / tirataMassima))
        val pronta = foglio.tirata >= foglio.sogliaTirata
        if (pronta != eraPronta) performHapticFeedback(
            if (pronta) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
            else HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE,
        )
        return if (trazione == 0f) dy - prima else 0f
    }

    private fun rilasciaTirata(aggiungi: Boolean) {
        trazione = 0f
        foglio.paginaFantasma = false
        if (aggiungi) alTiraPagina?.invoke()
        val da = foglio.tirata
        if (da <= 0f) return
        ritorno = android.animation.ValueAnimator.ofFloat(da, 0f).apply {
            duration = 240
            interpolator = android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
            addUpdateListener { foglio.tirata = it.animatedValue as Float }
            start()
        }
    }

    private fun centro(e: MotionEvent): Pair<Float, Float> {
        var x = 0f; var y = 0f; var n = 0
        val alzato = if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) e.actionIndex else -1
        for (i in 0 until e.pointerCount) { if (i == alzato) continue; x += e.getX(i); y += e.getY(i); n++ }
        return if (n == 0) e.x to e.y else x / n to y / n
    }

    /** Anima lo zoom fino alla larghezza della pagina mantenendo il punto al centro. */
    fun adattaLarghezza() {
        val da = foglio.scala
        val a = foglio.scalaAdatta()
        val cy = height / 2f
        val wy = (cy - foglio.ty) / da
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
            addUpdateListener {
                val f = it.animatedValue as Float
                val s = da + (a - da) * f
                foglio.impostaVista(s, (width - FoglioView.LARGHEZZA * s) / 2f, cy - wy * s)
            }
            start()
        }
    }

    override fun computeScroll() {
        if (scorritore.computeScrollOffset()) {
            foglio.trasla(scorritore.currX - foglio.tx, scorritore.currY - foglio.ty)
            postInvalidateOnAnimation()
        }
    }

    // ---------------------------------------------------------------- disegni sopra le pagine

    /** Cursore della penna, lazo e selezioni. Sta sopra le pagine e sotto l'inchiostro in corso. */
    private inner class Sovrapposizione(context: Context) : View(context) {
        private var cx = 0f
        private var cy = 0f
        private var visibile = false
        private var gomma = false
        private val accento = Color.rgb(101, 70, 243)
        private val pCursore = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * densita }
        private val pPieno = Paint(Paint.ANTI_ALIAS_FLAG)
        private val pLazo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.6f * densita
            pathEffect = DashPathEffect(floatArrayOf(8 * densita, 6 * densita), 0f)
            color = accento
        }
        private val pRiquadro = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.5f * densita; color = accento
            pathEffect = DashPathEffect(floatArrayOf(6 * densita, 5 * densita), 0f)
        }
        private val pCornice = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f * densita; color = accento }
        private val pVelo = Paint().apply { color = Color.argb(18, 101, 70, 243) }
        private val pManiglia = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accento }
        private val pManigliaBordo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val r = RectF()
        private val mm = Matrix()

        fun cursore(x: Float, y: Float, gomma: Boolean) {
            cx = x; cy = y; visibile = true; this.gomma = gomma
            invalidate()
        }

        fun nascondiCursore() {
            if (visibile) { visibile = false; invalidate() }
        }

        override fun onDraw(canvas: Canvas) {
            val s = selezione
            val d = documento
            if (s != null && d != null) {
                val p = d.pagine.getOrNull(s.pagina)
                if (p != null) {
                    foglio.paginaASchermo(s.pagina, mm)
                    mm.preConcat(s.trasforma)
                    if (s.oggetti.isNotEmpty()) {
                        val oggetti = synchronized(p) { p.oggetti.filter { it.id in s.oggetti } }
                        foglio.disegnaOggetti(canvas, oggetti, mm)
                    }
                    synchronized(p) {
                        for (t in p.tratti) if (t.id in s.ids) p.stroke(t)?.let { foglio.disegnaStroke(canvas, it, mm) }
                    }
                    rettangoloSelezione(s, r)
                    r.inset(-6 * densita, -6 * densita)
                    canvas.drawRect(r, pVelo)
                    canvas.drawRect(r, pRiquadro)
                    canvas.drawCircle(r.right, r.bottom, 9 * densita, pManigliaBordo)
                    canvas.drawCircle(r.right, r.bottom, 7 * densita, pManiglia)
                }
            }
            disegnaOggettoSelezionato(canvas)
            disegnaCorniceCampo(canvas)
            riquadroTesto?.let { rt ->
                val alto = Impaginazione.ALTEZZA_MIN * foglio.scala
                r.set(min(rt.left, rt.right), rt.top - alto / 2, max(rt.left, rt.right), rt.top + alto / 2)
                canvas.drawRect(r, pRiquadro)
            }
            if (!lazo.isEmpty) canvas.drawPath(lazo, pLazo)
            if (visibile) {
                if (gomma) {
                    pCursore.color = Color.argb(200, 90, 90, 100)
                    canvas.drawCircle(cx, cy, strumenti.raggioGomma * densita, pCursore)
                } else if (strumenti.strumento == Strumento.PENNA) {
                    val imp = strumenti.corrente
                    val raggio = max(2f * densita, imp.spessore * foglio.scala / 2f)
                    pPieno.color = Pennelli.coloreEffettivo(strumenti.penna, imp.colore)
                    canvas.drawCircle(cx, cy, raggio, pPieno)
                    pCursore.color = Color.argb(120, 255, 255, 255)
                    canvas.drawCircle(cx, cy, raggio + 1f * densita, pCursore)
                } else if (strumenti.strumento == Strumento.TESTO) {
                    // Cursore a "I", alto come una riga di testo normale.
                    val h = max(8 * densita, Impaginazione.ALTEZZA_MIN * foglio.scala) / 2f
                    val w = 3 * densita
                    pCursore.color = accento
                    canvas.drawLine(cx, cy - h, cx, cy + h, pCursore)
                    canvas.drawLine(cx - w, cy - h, cx + w, cy - h, pCursore)
                    canvas.drawLine(cx - w, cy + h, cx + w, cy + h, pCursore)
                } else {
                    pCursore.color = accento
                    canvas.drawCircle(cx, cy, 5 * densita, pCursore)
                }
            }
        }

        /** L'oggetto selezionato: la sua anteprima mentre si trascina, la cornice e le maniglie. */
        private fun disegnaOggettoSelezionato(canvas: Canvas) {
            val sel = selezioneOggetto ?: return
            val o = anteprima ?: oggettoSelezionato() ?: return
            if (anteprima != null) {
                foglio.paginaASchermo(sel.pagina, mm)
                foglio.disegnaOggetti(canvas, listOf(o), mm)
            }
            rettangoloOggetto(sel.pagina, o, r)
            canvas.drawRect(r, pCornice)
            for ((_, pt) in maniglie(o, r)) {
                canvas.drawCircle(pt.x, pt.y, 8 * densita, pManigliaBordo)
                canvas.drawCircle(pt.x, pt.y, 6 * densita, pManiglia)
            }
        }

        /** Cornice tratteggiata attorno alla casella in modifica. */
        private fun disegnaCorniceCampo(canvas: Canvas) {
            val rc = rettangoloCampo(r) ?: return
            val m = modificaTesto ?: return
            // Il campo e' alto quanto il testo; la cornice e' larga quanto la casella.
            rc.right = rc.left + m.larghezza * foglio.scala
            rc.bottom = max(rc.bottom, rc.top + Impaginazione.ALTEZZA_MIN * foglio.scala)
            rc.inset(-4 * densita, -4 * densita)
            canvas.drawRect(rc, pRiquadro)
        }
    }

    companion object {
        private const val NESSUNA = -1
        private const val DENTRO = 10
        private const val SINISTRA = 4
        private const val DESTRA = 5
        private const val DURATA_PRESSIONE_LUNGA = 500L

        /** Larghezza di una casella di testo nuova, in unita' di pagina. */
        const val LARGHEZZA_TESTO = 500f
        private const val MARGINE_TESTO = 40f

        /** Il segmento a-b passa entro [raggio] da uno dei segmenti del tratto? */
        fun tocca(t: Tratto, ax: Float, ay: Float, bx: Float, by: Float, raggio: Float): Boolean {
            val p = t.punti
            val n = t.quanti
            if (n == 1) return distanzaSegmento(p[0], p[1], ax, ay, bx, by) <= raggio
            for (k in 0 until n) {
                val x = p[k * Tratto.CAMPI]; val y = p[k * Tratto.CAMPI + 1]
                if (distanzaSegmento(x, y, ax, ay, bx, by) <= raggio) return true
            }
            return false
        }

        /**
         * Gomma parziale: toglie i punti vicini alla gomma e restituisce i pezzi rimasti
         * (ognuno con almeno 2 punti), oppure null se il tratto non e' stato toccato.
         */
        fun spezza(t: Tratto, ax: Float, ay: Float, bx: Float, by: Float, raggio: Float): List<FloatArray>? {
            val n = t.quanti
            val via = BooleanArray(n)
            var qualcuno = false
            for (k in 0 until n) {
                val o = k * Tratto.CAMPI
                if (distanzaSegmento(t.punti[o], t.punti[o + 1], ax, ay, bx, by) <= raggio) { via[k] = true; qualcuno = true }
            }
            if (!qualcuno) return null
            val pezzi = ArrayList<FloatArray>()
            var inizio = -1
            for (k in 0..n) {
                val tieni = k < n && !via[k]
                if (tieni && inizio < 0) inizio = k
                if (!tieni && inizio >= 0) {
                    if (k - inizio >= 2) pezzi += t.punti.copyOfRange(inizio * Tratto.CAMPI, k * Tratto.CAMPI)
                    inizio = -1
                }
            }
            return pezzi
        }

        fun distanzaSegmento(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
            val vx = bx - ax; val vy = by - ay
            val l2 = vx * vx + vy * vy
            val u = if (l2 == 0f) 0f else (((px - ax) * vx + (py - ay) * vy) / l2).coerceIn(0f, 1f)
            return hypot(px - (ax + u * vx), py - (ay + u * vy))
        }

        fun dentroPoligono(x: Float, y: Float, poli: FloatArray): Boolean {
            var dentro = false
            var j = poli.size - 2
            var i = 0
            while (i < poli.size) {
                val xi = poli[i]; val yi = poli[i + 1]; val xj = poli[j]; val yj = poli[j + 1]
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) dentro = !dentro
                j = i; i += 2
            }
            return dentro
        }
    }
}
