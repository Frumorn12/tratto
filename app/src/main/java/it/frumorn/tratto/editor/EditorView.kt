package it.frumorn.tratto.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.OverScroller
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.strokes.Stroke
import androidx.input.motionprediction.MotionEventPredictor
import it.frumorn.tratto.data.Penna
import it.frumorn.tratto.data.Tratto
import it.frumorn.tratto.ink.Pennelli
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * La superficie di scrittura.
 *
 *  - penna: inchiostro a bassa latenza con [InProgressStrokesView] (front buffer) e predizione;
 *  - tasto della penna tenuto premuto, o punta gomma: gomma temporanea;
 *  - dita: scorrimento, lancio, pizzico per lo zoom, doppio tocco per tornare alla larghezza pagina;
 *  - con la penna vicina allo schermo le dita vengono ignorate (appoggio del palmo).
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class EditorView(context: Context) : FrameLayout(context), InProgressStrokesFinishedListener {

    val foglio = FoglioView(context)
    private val sopra = Sovrapposizione(context)
    val inchiostro = InProgressStrokesView(context)

    var documento: Documento? = null
        set(v) { field = v; foglio.documento = v; selezione = null }

    var strumenti = StatoStrumenti()
        set(v) {
            field = v
            if (v.strumento != Strumento.LAZO && selezione != null) chiudiSelezione()
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

    private val densita = resources.displayMetrics.density
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
            sopra.invalidate()
            alCambioPagina?.invoke(foglio.paginaCorrente())
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

    override fun onInterceptTouchEvent(ev: MotionEvent) = true

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

    private enum class Gesto { NESSUNO, INCHIOSTRO, GOMMA, LAZO, SPOSTA_SELEZIONE, SCALA_SELEZIONE }
    private var gesto = Gesto.NESSUNO

    private fun tocchiPenna(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pennaGiu = true
                sopra.nascondiCursore()
                requestUnbufferedDispatch(e)
                scorritore.forceFinished(true)
                gesto = when {
                    gommaAttiva(e) -> Gesto.GOMMA.also { iniziaGomma(e) }
                    selezione?.let { dentroManiglia(e.x, e.y, it) } == true -> Gesto.SCALA_SELEZIONE.also { iniziaTrasforma(e, scala = true) }
                    selezione?.let { dentroSelezione(e.x, e.y, it) } == true -> Gesto.SPOSTA_SELEZIONE.also { iniziaTrasforma(e, scala = false) }
                    strumenti.strumento == Strumento.LAZO -> Gesto.LAZO.also { chiudiSelezione(); iniziaLazo(e) }
                    else -> { if (selezione != null) chiudiSelezione(); Gesto.INCHIOSTRO.also { iniziaTratto(e) } }
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
                Gesto.NESSUNO -> Unit
            }
            MotionEvent.ACTION_UP -> {
                when (gesto) {
                    Gesto.INCHIOSTRO -> trattoInCorso?.let { inchiostro.finishStroke(e, e.getPointerId(0), it) }
                    Gesto.GOMMA -> fineGomma()
                    Gesto.LAZO -> fineLazo()
                    Gesto.SPOSTA_SELEZIONE, Gesto.SCALA_SELEZIONE -> fineTrasforma()
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

    class Selezione(val pagina: Int, val ids: Set<Long>, val scatola: RectF) {
        val trasforma = Matrix()
    }

    var selezione: Selezione? = null
        private set(v) {
            field = v
            foglio.nascosti = v?.ids ?: emptySet()
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
        }
        selezione = if (presi.isEmpty()) null else Selezione(i, presi, box)
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
        d.esegui(Modifica(p, tolti, nuovi))
        val box = RectF(s.scatola).also { s.trasforma.mapRect(it) }
        selezione = Selezione(s.pagina, nuovi.map { it.id }.toSet(), box)
        foglio.invalidate()
        alTratto?.invoke()
    }

    /** Operazioni sulla selezione, chiamate dalla barra del lazo. */
    fun eliminaSelezione() {
        val s = selezione ?: return
        val d = documento ?: return
        val p = d.pagine[s.pagina]
        val tolti = synchronized(p) { p.tratti.withIndex().filter { it.value.id in s.ids }.map { it.index to it.value } }
        d.esegui(Modifica(p, tolti, emptyList()))
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
        selezione = Selezione(s.pagina, nuovi.map { it.id }.toSet(), RectF(s.scatola))
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

    /** Incolla dei tratti al centro della pagina visibile, spostati di un poco se sono uguali. */
    fun incolla(tratti: List<Tratto>) {
        val d = documento ?: return
        if (tratti.isEmpty()) return
        val i = foglio.paginaCorrente()
        val p = d.pagine[i]
        d.caricaTratti(p)
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        tratti.forEach { t -> for (k in 0 until t.quanti) { val x = t.punti[k * Tratto.CAMPI]; val y = t.punti[k * Tratto.CAMPI + 1]; minX = min(minX, x); minY = min(minY, y); maxX = max(maxX, x); maxY = max(maxY, y) } }
        val cy = (height / 2f - foglio.ty) / foglio.scala - foglio.cimaPagina(i)
        val dx = FoglioView.LARGHEZZA / 2 - (minX + maxX) / 2 + 20f
        val dy = cy - (minY + maxY) / 2 + 20f
        val nuovi = tratti.map { t ->
            val pts = t.punti.copyOf()
            for (k in 0 until t.quanti) { pts[k * Tratto.CAMPI] += dx; pts[k * Tratto.CAMPI + 1] += dy }
            Tratto(d.nuovoIdTratto(), t.penna, t.colore, t.spessore, pts)
        }
        d.esegui(Modifica(p, emptyList(), nuovi))
        selezione = Selezione(i, nuovi.map { it.id }.toSet(), RectF(minX + dx, minY + dy, maxX + dx, maxY + dy).apply { inset(-6f, -6f) })
        foglio.invalidate()
        alTratto?.invoke()
    }

    fun ridisegna() {
        foglio.invalidate()
        sopra.invalidate()
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
    }

    private fun tocchiDita(e: MotionEvent): Boolean {
        pizzico.onTouchEvent(e)
        if (velocita == null) velocita = android.view.VelocityTracker.obtain()
        velocita?.addMovement(e)
        val (cx, cy) = centro(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scorritore.forceFinished(true)
                inGestoDita = true
                spostato = false
                ultimoX = cx; ultimoY = cy
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                ultimoX = cx; ultimoY = cy
                spostato = true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = cx - ultimoX; val dy = cy - ultimoY
                if (abs(dx) + abs(dy) > 1f) spostato = true
                foglio.trasla(dx, dy)
                ultimoX = cx; ultimoY = cy
            }
            MotionEvent.ACTION_UP -> {
                val v = velocita
                if (v != null && spostato) {
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
            MotionEvent.ACTION_CANCEL -> annullaGestoDita()
        }
        return true
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

    /** Cursore della penna, lazo e selezione. Sta sopra le pagine e sotto l'inchiostro in corso. */
    private inner class Sovrapposizione(context: Context) : View(context) {
        private var cx = 0f
        private var cy = 0f
        private var visibile = false
        private var gomma = false
        private val pCursore = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * densita }
        private val pPieno = Paint(Paint.ANTI_ALIAS_FLAG)
        private val pLazo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.6f * densita
            pathEffect = DashPathEffect(floatArrayOf(8 * densita, 6 * densita), 0f)
            color = Color.rgb(101, 70, 243)
        }
        private val pRiquadro = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.5f * densita; color = Color.rgb(101, 70, 243)
            pathEffect = DashPathEffect(floatArrayOf(6 * densita, 5 * densita), 0f)
        }
        private val pVelo = Paint().apply { color = Color.argb(18, 101, 70, 243) }
        private val pManiglia = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(101, 70, 243) }
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
                } else {
                    pCursore.color = Color.rgb(101, 70, 243)
                    canvas.drawCircle(cx, cy, 5 * densita, pCursore)
                }
            }
        }
    }

    companion object {
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
