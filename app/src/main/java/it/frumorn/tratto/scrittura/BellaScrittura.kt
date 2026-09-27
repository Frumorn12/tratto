package it.frumorn.tratto.scrittura

import android.content.Context
import android.graphics.RectF
import it.frumorn.tratto.data.Penna
import it.frumorn.tratto.data.Tratto
import java.text.Normalizer
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Stile della bella scrittura. L'ordine e' parte del formato dell'asset dei font: aggiungere solo in fondo. */
enum class Stile { CORSIVO, STAMPATELLO }

/**
 * "Bella scrittura": riscrive un testo come tratti di penna veri (non testo), con i font Hershey
 * a tratto singolo: "cursive" per il corsivo, "futural" per lo stampatello.
 *
 * I tratti escono come quelli scritti a mano: punti ogni 1/12 dell'altezza delle minuscole,
 * tempi crescenti, pressione che sale, ondeggia e cala come in tools/penna.py, cosi' la penna a
 * spessore variabile li disegna in modo naturale. In corsivo le lettere di una parola che si
 * toccano diventano un solo tratto, come quando si scrive senza staccare la penna.
 *
 * Prima dell'uso va chiamata [prepara] (legge un asset di pochi KB, si puo' fare anche sul main thread).
 * Attribuzione dei font: docs/Hershey-fonts.txt.
 */
object BellaScrittura {
    const val ASSET = "scrittura/hershey.bin"

    /** Usata da [altezzaXStimata] quando non ci sono tratti da misurare: circa 3 mm. */
    const val ALTEZZA_X_PREDEFINITA = 14f

    /** Distanza minima tra le linee di base, in altezze delle minuscole. */
    private const val INTERLINEA = 2.2f

    /**
     * Distanza minima tra le linee di base come frazione dell'ingombro verticale del font (dalla
     * cima delle aste al fondo delle code). Conta nel corsivo, dove aste e code sono lunghe (3,3
     * altezze x): con 0,9 le code di una riga sfiorano appena le aste della riga sotto, come su un quaderno.
     */
    private const val INTERLINEA_FONT = 0.9f

    /** Distanza tra due punti del tratto, in altezze delle minuscole. */
    private const val PASSO = 1f / 12f
    private const val MS_PER_PUNTO = 8f
    private const val INCLINAZIONE = 0.45f
    private const val PRESSIONE_MIN = 0.3f
    private const val PRESSIONE_MAX = 0.85f

    /** Due tratti che si toccano entro questa distanza (unita' del font) diventano uno solo. */
    private const val CONTATTO = 0.6f

    /** In corsivo si unisce anche un piccolo stacco tra una lettera e la successiva (in altezze x). */
    private const val PONTE_CORSIVO = 0.4f

    @Volatile private var font: Map<Stile, FontHershey>? = null
    private val cache = HashMap<Pair<Stile, Char>, Segno?>()

    /** Vero quando i font sono gia' caricati. */
    val pronta: Boolean get() = font != null

    /** Carica i font dagli asset. Idempotente ed economica (circa 7 KB). */
    fun prepara(context: Context) {
        if (font != null) return
        prepara(context.assets.open(ASSET).use { it.readBytes() })
    }

    /** Carica i font da un array di byte (per i test sulla JVM, dove non ci sono asset). */
    internal fun prepara(dati: ByteArray) = synchronized(this) {
        if (font == null) font = FontHershey.leggi(dati)
    }

    private fun font(stile: Stile): FontHershey =
        font?.get(stile) ?: throw IllegalStateException("Chiamare BellaScrittura.prepara(context) prima di comporre")

    /**
     * Scrive [testo] come tratti a partire da ([x], [y]), angolo in alto a sinistra, in unita' di pagina.
     *
     * [altezzaX] e' l'altezza delle minuscole (vedi [altezzaXStimata] per imitare la scrittura
     * originale). Le parole vanno a capo entro [larghezzaMax] (0 o infinito: nessun limite), una
     * parola piu' lunga della riga si spezza tra le lettere, "\n" forza un a capo. La prima linea
     * di base sta a y + ascesa (dalla cima di maiuscole e aste); le righe distano circa 2,2 altezze x
     * (di piu' in corsivo, dove aste e code sono lunghe) e una riga con una maiuscola accentata
     * scende quanto serve perche' l'accento non tocchi la riga sopra. I caratteri che il font non
     * ha si saltano.
     */
    fun componi(
        testo: String,
        stile: Stile,
        x: Float,
        y: Float,
        altezzaX: Float,
        larghezzaMax: Float,
        colore: Int,
        penna: Penna,
        spessore: Float,
        idNuovo: () -> Long,
    ): List<Tratto> {
        val f = font(stile)
        val imp = impagina(f, testo, altezzaX, larghezzaMax)
        val s = imp.scala
        val out = ArrayList<Tratto>()
        imp.righe.forEachIndexed { r, riga ->
            val base = y + imp.basi[r]
            for (posata in riga.parole) {
                for (catena in catene(posata.parola, f)) {
                    out += tratto(catena, x + posata.x * s, base, s, altezzaX, penna, colore, spessore, idNuovo(), out.size)
                }
            }
        }
        return out
    }

    /**
     * Distanza tra la y di partenza di [componi] e la prima linea di base, per un testo senza
     * maiuscole accentate: serve a far poggiare il testo composto su una linea di base data.
     */
    fun ascesa(stile: Stile, altezzaX: Float): Float {
        val f = font(stile)
        return (f.base - f.cima) * altezzaX / f.altezzaX
    }

    /**
     * Ingombro del testo composto con gli stessi parametri di [componi], relativo al punto di
     * partenza: left = top = 0. L'altezza va dalla cima della prima riga (accenti compresi) al
     * fondo dei discendenti dell'ultima; la larghezza e' quella della riga piu' lunga. Qualche
     * svolazzo del corsivo (la coda della f, della p) sporge di poco a sinistra della lettera.
     */
    fun misura(testo: String, stile: Stile, altezzaX: Float, larghezzaMax: Float): RectF {
        val d = dimensioni(testo, stile, altezzaX, larghezzaMax)
        return RectF(0f, 0f, d[0], d[1])
    }

    /** [larghezza, altezza] del testo composto: il cuore di [misura], senza classi Android. */
    internal fun dimensioni(testo: String, stile: Stile, altezzaX: Float, larghezzaMax: Float): FloatArray {
        val imp = impagina(font(stile), testo, altezzaX, larghezzaMax)
        if (imp.righe.isEmpty()) return floatArrayOf(0f, 0f)
        return floatArrayOf(imp.righe.maxOf { it.larghezza } * imp.scala, imp.altezza)
    }

    /**
     * Stima l'altezza delle minuscole della scrittura a mano in [tratti], in unita' di pagina,
     * da passare a [componi] per riscrivere il testo della stessa grandezza.
     *
     * Per ogni riga cerca la fascia orizzontale piu' stretta che contiene il 70% dell'inchiostro:
     * e' il corpo delle minuscole, dove passa la maggior parte dei tratti (aste, code, puntini e
     * maiuscole ne restano quasi fuori). Il rapporto tra quella fascia e l'altezza x (1,18) e'
     * tarato sui due font Hershey con frasi italiane: con il 70% la stima varia meno che con altre
     * quote (entro il 15% tra frasi e stili). Le righe pesano per quanto inchiostro hanno.
     * Senza tratti: [ALTEZZA_X_PREDEFINITA].
     */
    fun altezzaXStimata(tratti: List<Tratto>): Float {
        val fascia = fasciaInchiostro(tratti, FASCIA_INCHIOSTRO)
        return if (fascia > 0f) fascia * RAPPORTO_FASCIA else ALTEZZA_X_PREDEFINITA
    }

    /** Larghezza della fascia piu' stretta con [frazione] dell'inchiostro, mediana pesata sulle righe; 0 se non c'e' inchiostro. */
    internal fun fasciaInchiostro(tratti: List<Tratto>, frazione: Float): Float {
        val righe = Righe.dividi(tratti.filter { it.penna != Penna.EVIDENZIATORE })
        val stime = ArrayList<Pair<Float, Float>>() // (fascia, inchiostro)
        for (riga in righe) {
            val campioni = campioniVerticali(riga)
            val totale = campioni.pesoTotale
            if (totale <= 0f) continue
            val fascia = fasciaPiuStretta(campioni, frazione * totale)
            if (fascia > 0f) stime += fascia to totale
        }
        if (stime.isEmpty()) return 0f
        // Mediana pesata: una riga di due puntini non sposta la stima di un paragrafo intero.
        val ordinate = stime.sortedBy { it.first }
        val meta = ordinate.sumOf { it.second.toDouble() } / 2
        var somma = 0.0
        for ((stima, peso) in ordinate) {
            somma += peso
            if (somma >= meta) return stima
        }
        return ordinate.last().first
    }

    /** Frazione dell'inchiostro di una riga che deve stare nella fascia delle minuscole. */
    private const val FASCIA_INCHIOSTRO = 0.7f

    /** Altezza x diviso larghezza della fascia che contiene [FASCIA_INCHIOSTRO] dell'inchiostro. */
    internal const val RAPPORTO_FASCIA = 1.18f

    private class Campioni(val y: FloatArray, val peso: FloatArray, val pesoTotale: Float)

    /** Posizioni verticali dell'inchiostro di una riga, pesate per lunghezza di tratto. */
    private fun campioniVerticali(riga: Righe.Riga): Campioni {
        val passo = max(riga.altezza / 40f, 0.25f)
        val ys = ArrayList<Float>()
        val pesi = ArrayList<Float>()
        for (t in riga.tratti) {
            val p = t.punti
            for (i in 1 until t.quanti) {
                val a = (i - 1) * Tratto.CAMPI
                val b = i * Tratto.CAMPI
                val lunghezza = hypot(p[b] - p[a], p[b + 1] - p[a + 1])
                if (lunghezza <= 0f) continue
                val n = ceil(lunghezza / passo).toInt().coerceAtLeast(1)
                for (k in 0 until n) {
                    val u = (k + 0.5f) / n
                    ys += p[a + 1] + (p[b + 1] - p[a + 1]) * u
                    pesi += lunghezza / n
                }
            }
        }
        val ordine = ys.indices.sortedBy { ys[it] }
        val y = FloatArray(ordine.size) { ys[ordine[it]] }
        val peso = FloatArray(ordine.size) { pesi[ordine[it]] }
        return Campioni(y, peso, peso.sum())
    }

    /** Larghezza della fascia verticale piu' stretta che contiene almeno [quota] di peso. */
    private fun fasciaPiuStretta(c: Campioni, quota: Float): Float {
        var migliore = Float.MAX_VALUE
        var j = 0
        var somma = 0f
        for (i in c.y.indices) {
            while (j < c.y.size && somma < quota) somma += c.peso[j++]
            if (somma < quota) break
            migliore = min(migliore, c.y[j - 1] - c.y[i])
            somma -= c.peso[i]
        }
        return if (migliore == Float.MAX_VALUE) 0f else migliore
    }

    // --- Impaginazione -------------------------------------------------------------------------

    /** Un carattere pronto da scrivere: tratti con x dal margine sinistro e y dalla linea di base (unita' del font). */
    private class Segno(val tratti: List<FloatArray>, val larghezza: Float) {
        /** Il punto piu' alto, rispetto alla linea di base (negativo = sopra). */
        val alto: Float = tratti.minOfOrNull { t -> (1 until t.size step 2).minOfOrNull { t[it] } ?: 0f } ?: 0f
    }

    private class Parola(val segni: List<Segno>) {
        val larghezza: Float = segni.sumOf { it.larghezza.toDouble() }.toFloat()
    }

    private class Posata(val x: Float, val parola: Parola)

    private class RigaTesto(val parole: List<Posata>, val larghezza: Float)

    /** Righe impaginate con le loro linee di base (unita' di pagina, dalla cima del testo). */
    private class Impaginato(val righe: List<RigaTesto>, val basi: FloatArray, val altezza: Float, val scala: Float)

    private fun impagina(f: FontHershey, testo: String, altezzaX: Float, larghezzaMax: Float): Impaginato {
        require(altezzaX > 0f) { "altezzaX deve essere positiva" }
        val s = altezzaX / f.altezzaX
        val limite = if (larghezzaMax > 0f && larghezzaMax.isFinite()) larghezzaMax / s else Float.POSITIVE_INFINITY
        val spazio = segno(f, ' ')?.larghezza ?: f.altezzaX.toFloat()
        val righe = ArrayList<RigaTesto>()
        for (paragrafo in normalizza(testo).split('\n')) {
            val parole = paragrafo.split(' ')
                .map { p -> Parola(p.mapNotNull { segno(f, it) }) }
                .filter { it.segni.isNotEmpty() }
            var corrente = ArrayList<Posata>()
            var fine = 0f
            for (parola in parole) {
                for (pezzo in spezza(parola, limite)) {
                    var inizio = if (corrente.isEmpty()) 0f else fine + spazio
                    if (corrente.isNotEmpty() && inizio + pezzo.larghezza > limite) {
                        righe += RigaTesto(corrente, fine)
                        corrente = ArrayList()
                        inizio = 0f
                    }
                    corrente += Posata(inizio, pezzo)
                    fine = inizio + pezzo.larghezza
                }
            }
            righe += RigaTesto(corrente, fine)
        }
        val ascesa = (f.base - f.cima) * s
        val discesa = (f.fondo - f.base) * s
        val interlinea = max(INTERLINEA * altezzaX, INTERLINEA_FONT * (f.fondo - f.cima) * s)
        // Di norma le righe sono equidistanti; solo gli accenti delle maiuscole superano la cima
        // del font, e allora quella riga scende di quel tanto.
        val cima = (f.cima - f.base).toFloat()
        val basi = FloatArray(righe.size)
        var base = 0f
        righe.forEachIndexed { i, riga ->
            val alto = riga.parole.minOfOrNull { p -> p.parola.segni.minOf { it.alto } } ?: 0f
            base += (if (i == 0) ascesa else interlinea) + max(0f, cima - alto) * s
            basi[i] = base
        }
        return Impaginato(righe, basi, if (righe.isEmpty()) 0f else base + discesa, s)
    }

    /** Una parola piu' larga della riga si spezza tra le lettere (almeno una lettera per pezzo). */
    private fun spezza(p: Parola, limite: Float): List<Parola> {
        if (p.larghezza <= limite) return listOf(p)
        val pezzi = ArrayList<Parola>()
        var corrente = ArrayList<Segno>()
        var larghezza = 0f
        for (s in p.segni) {
            if (corrente.isNotEmpty() && larghezza + s.larghezza > limite) {
                pezzi += Parola(corrente)
                corrente = ArrayList()
                larghezza = 0f
            }
            corrente += s
            larghezza += s.larghezza
        }
        if (corrente.isNotEmpty()) pezzi += Parola(corrente)
        return pezzi
    }

    private fun normalizza(testo: String): String = testo
        .replace("\r\n", "\n").replace('\r', '\n')
        .replace("…", "...")
        .replace('\t', ' ').replace('\u00A0', ' ').replace('\u202F', ' ')
        .trimEnd()

    // --- Caratteri -----------------------------------------------------------------------------

    private enum class Segnaccento { GRAVE, ACUTO, CIRCONFLESSO, DIERESI, TILDE }

    private fun segno(f: FontHershey, c: Char): Segno? = synchronized(cache) {
        cache.getOrPut(f.stile to c) { creaSegno(f, c) }
    }

    private fun creaSegno(f: FontHershey, c: Char): Segno? {
        when (c) {
            '’', '‘', '‚', '\u2032', '´', '\u02BC' -> return glifo(f, '\'')
            '“', '”', '„', '\u2033' -> return glifo(f, '"')
            '–', '—', '−', '\u2010', '\u2011' -> return glifo(f, '-')
            '«' -> return virgolette(f, versoSinistra = true)
            '»' -> return virgolette(f, versoSinistra = false)
            '€' -> return euro(f)
            '°' -> return grado(f)
        }
        glifo(f, c)?.let { return it }
        // Lettere accentate: lettera base + segni diacritici (NFD separa "è" in "e" + accento grave).
        val scomposto = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
        if (scomposto.length < 2) return null
        val base = glifo(f, scomposto[0]) ?: return null
        val segni = scomposto.drop(1).mapNotNull {
            when (it) {
                '\u0300' -> Segnaccento.GRAVE
                '\u0301' -> Segnaccento.ACUTO
                '\u0302' -> Segnaccento.CIRCONFLESSO
                '\u0308' -> Segnaccento.DIERESI
                '\u0303' -> Segnaccento.TILDE
                else -> null // cediglia e simili: resta la lettera base
            }
        }
        val accento = segni.firstOrNull() ?: return base
        return accenta(f, base, scomposto[0], accento)
    }

    private fun glifo(f: FontHershey, c: Char): Segno? {
        val g = f.glifo(c) ?: return null
        val dx = -g.sinistra.toFloat()
        val dy = -f.base.toFloat()
        val tratti = g.tratti.map { t -> FloatArray(t.size) { i -> t[i] + if (i % 2 == 0) dx else dy } }
        return Segno(tratti, (g.destra - g.sinistra).toFloat())
    }

    /**
     * Aggiunge un accento sopra [base]. Sulle minuscole sta tra la cima delle minuscole e quella
     * delle maiuscole (all'altezza del puntino della i), sulle maiuscole poco sopra la cima; la i
     * e la j perdono il puntino, sostituito dall'accento.
     */
    private fun accenta(f: FontHershey, base: Segno, lettera: Char, accento: Segnaccento): Segno {
        val h = f.altezzaX.toFloat()
        var tratti = base.tratti
        var ancora = Float.NaN
        if (lettera == 'i' || lettera == 'j') {
            val puntino = tratti.firstOrNull { t -> puntino(t, h) }
            if (puntino != null) {
                ancora = (minX(puntino) + maxX(puntino)) / 2f
                tratti = tratti - puntino
            }
        }
        if (ancora.isNaN()) {
            // Centro dei punti piu' alti della lettera: la cima della pancia o delle aste.
            var cima = Float.MAX_VALUE
            for (t in tratti) for (i in 1 until t.size step 2) cima = min(cima, t[i])
            var sx = Float.MAX_VALUE
            var dx = -Float.MAX_VALUE
            for (t in tratti) for (i in 0 until t.size step 2) {
                if (t[i + 1] <= cima + 0.15f * h) { sx = min(sx, t[i]); dx = max(dx, t[i]) }
            }
            ancora = (sx + dx) / 2f
        }
        val cimaMaiuscole = (f.maiuscole - f.base).toFloat()
        // Spazio tra la cima delle minuscole e quella delle maiuscole: largo nel corsivo, stretto
        // nello stampatello, dove l'accento non deve superare le maiuscole.
        val spazio = -h - cimaMaiuscole
        val l: Float
        val centro: Float
        if (lettera.isUpperCase()) {
            // Piccolo e vicino alla lettera, come quando si scrive "È" a mano.
            l = 0.35f * h
            centro = cimaMaiuscole - 0.12f * h - l / 2f
        } else {
            l = min(0.5f * h, 0.75f * spazio)
            centro = -h - min(0.25f * h, 0.25f * spazio) - l / 2f
        }
        val ax = ancora
        val extra: List<FloatArray> = when (accento) {
            Segnaccento.GRAVE -> listOf(floatArrayOf(ax - 0.3f * l, centro - 0.5f * l, ax + 0.3f * l, centro + 0.5f * l))
            Segnaccento.ACUTO -> listOf(floatArrayOf(ax + 0.3f * l, centro - 0.5f * l, ax - 0.3f * l, centro + 0.5f * l))
            Segnaccento.CIRCONFLESSO -> listOf(
                floatArrayOf(ax - 0.4f * l, centro + 0.35f * l, ax, centro - 0.35f * l, ax + 0.4f * l, centro + 0.35f * l),
            )
            Segnaccento.TILDE -> listOf(
                floatArrayOf(ax - 0.45f * l, centro + 0.1f * l, ax - 0.2f * l, centro - 0.2f * l, ax + 0.2f * l, centro + 0.2f * l, ax + 0.45f * l, centro - 0.1f * l),
            )
            Segnaccento.DIERESI -> listOf(pallino(ax - 0.28f * h, centro, 0.07f * h), pallino(ax + 0.28f * h, centro, 0.07f * h))
        }
        return Segno(tratti + extra, base.larghezza)
    }

    /** Il puntino della i: un tratto minuscolo tutto sopra la cima delle minuscole. */
    private fun puntino(t: FloatArray, h: Float): Boolean {
        var sy = Float.MAX_VALUE
        var dy = -Float.MAX_VALUE
        for (i in 1 until t.size step 2) { sy = min(sy, t[i]); dy = max(dy, t[i]) }
        return dy < -h && dy - sy <= 0.35f * h && maxX(t) - minX(t) <= 0.35f * h
    }

    private fun minX(t: FloatArray): Float { var m = Float.MAX_VALUE; for (i in 0 until t.size step 2) m = min(m, t[i]); return m }
    private fun maxX(t: FloatArray): Float { var m = -Float.MAX_VALUE; for (i in 0 until t.size step 2) m = max(m, t[i]); return m }

    private fun pallino(cx: Float, cy: Float, r: Float) = floatArrayOf(cx, cy - r, cx + r, cy, cx, cy + r, cx - r, cy, cx, cy - r)

    /** Virgolette basse: due piccole punte a meta' altezza delle minuscole. */
    private fun virgolette(f: FontHershey, versoSinistra: Boolean): Segno {
        val h = f.altezzaX.toFloat()
        val alta = 0.6f * h
        val punta = 0.36f * h
        val passo = 0.32f * h
        val margine = 0.2f * h
        val cy = -0.5f * h
        val tratti = (0..1).map { k ->
            val x0 = margine + k * passo
            if (versoSinistra) floatArrayOf(x0 + punta, cy - alta / 2, x0, cy, x0 + punta, cy + alta / 2)
            else floatArrayOf(x0, cy - alta / 2, x0 + punta, cy, x0, cy + alta / 2)
        }
        return Segno(tratti, 2 * margine + passo + punta)
    }

    /** Euro: la C del font con due trattini orizzontali che sporgono a sinistra. */
    private fun euro(f: FontHershey): Segno? {
        val c = glifo(f, 'C') ?: return null
        val h = f.altezzaX.toFloat()
        val sposta = 0.25f * h
        val alto = (f.maiuscole - f.base).toFloat()
        val tratti = c.tratti.map { t -> FloatArray(t.size) { i -> if (i % 2 == 0) t[i] + sposta else t[i] } }
        val sx = tratti.minOf { minX(it) }
        val dx = tratti.maxOf { maxX(it) }
        val da = sx - sposta
        val a = sx + 0.55f * (dx - sx)
        val trattini = listOf(floatArrayOf(da, 0.42f * alto, a, 0.42f * alto), floatArrayOf(da, 0.6f * alto, a, 0.6f * alto))
        return Segno(tratti + trattini, c.larghezza + sposta)
    }

    /** Grado: un cerchietto in alto. */
    private fun grado(f: FontHershey): Segno {
        val h = f.altezzaX.toFloat()
        val r = 0.22f * h
        val margine = 0.2f * h
        val cx = margine + r
        val cy = (f.maiuscole - f.base) + r
        val cerchio = FloatArray(2 * 13) { i ->
            val a = 2 * PI * (i / 2) / 12
            if (i % 2 == 0) (cx + r * sin(a + PI / 2)).toFloat() else (cy - r * sin(a)).toFloat()
        }
        return Segno(listOf(cerchio), 2 * (margine + r))
    }

    // --- Tratti --------------------------------------------------------------------------------

    /**
     * I tratti di una parola in unita' del font (x dall'inizio della parola, y dalla linea di base).
     * Un tratto che parte dove ne finisce un altro lo prolunga; in corsivo basta che parta vicino,
     * ma solo nel corpo delle minuscole, dove si attaccano le lettere: accenti, puntini e taglio
     * della t restano staccati.
     */
    private fun catene(p: Parola, f: FontHershey): List<FloatArray> {
        val catene = ArrayList<ArrayList<Float>>()
        var penna = 0f
        for (segno in p.segni) {
            for (t in segno.tratti) {
                if (t.size < 2) continue
                val sx = t[0] + penna
                val sy = t[1]
                val ponte = if (f.stile == Stile.CORSIVO && sy >= -f.altezzaX) PONTE_CORSIVO * f.altezzaX else CONTATTO
                var scelta = -1
                var distanza = Float.MAX_VALUE
                for (k in catene.indices.reversed()) {
                    val c = catene[k]
                    val d = hypot(c[c.size - 2] - sx, c[c.size - 1] - sy)
                    if (d <= ponte && d < distanza - 1e-4f) { scelta = k; distanza = d }
                }
                val catena = if (scelta >= 0) catene[scelta] else ArrayList<Float>().also { catene += it }
                val salta = if (scelta >= 0 && distanza <= CONTATTO) 2 else 0
                for (i in salta until t.size) catena += if (i % 2 == 0) t[i] + penna else t[i]
            }
            penna += segno.larghezza
        }
        return catene.map { it.toFloatArray() }
    }

    /** Trasforma una polilinea del font in un [Tratto]: ricampionamento, tempi, pressione. */
    private fun tratto(
        c: FloatArray,
        ox: Float,
        oy: Float,
        s: Float,
        altezzaX: Float,
        penna: Penna,
        colore: Int,
        spessore: Float,
        id: Long,
        indice: Int,
    ): Tratto {
        val passo = altezzaX * PASSO
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        val n = c.size / 2
        xs += ox + c[0] * s
        ys += oy + c[1] * s
        for (i in 1 until n) {
            val ax = ox + c[2 * i - 2] * s
            val ay = oy + c[2 * i - 1] * s
            val bx = ox + c[2 * i] * s
            val by = oy + c[2 * i + 1] * s
            val lunghezza = hypot(bx - ax, by - ay)
            if (lunghezza <= 1e-4f) continue
            val parti = ceil(lunghezza / passo).toInt().coerceAtLeast(1)
            for (k in 1..parti) {
                val u = k.toFloat() / parti
                xs += ax + (bx - ax) * u
                ys += ay + (by - ay) * u
            }
        }
        val quanti = xs.size
        val percorso = FloatArray(quanti)
        for (k in 1 until quanti) percorso[k] = percorso[k - 1] + hypot(xs[k] - xs[k - 1], ys[k] - ys[k - 1])
        val totale = percorso[quanti - 1]
        // Salita e discesa della pressione lunghe al massimo 1,2 altezze x: una parola intera
        // resta piena nel mezzo, un accento o un puntino fanno tutto l'arco in poco spazio.
        // I segni brevi si scrivono piu' leggeri, se no diventano macchie.
        val rampa = min(1.2f * altezzaX, totale / 2f).coerceAtLeast(1e-3f)
        val ampiezza = 0.5f * (0.5f + 0.5f * min(1f, totale / (2f * altezzaX)))
        val punti = FloatArray(quanti * Tratto.CAMPI)
        for (k in 0 until quanti) {
            val su = sin(PI / 2 * min(1f, percorso[k] / rampa)).toFloat()
            val giu = sin(PI / 2 * min(1f, (totale - percorso[k]) / rampa)).toFloat()
            val ondeggio = 0.06f * sin(k / 3f + indice * 1.7f)
            val o = k * Tratto.CAMPI
            punti[o] = xs[k]
            punti[o + 1] = ys[k]
            punti[o + 2] = (PRESSIONE_MIN + ampiezza * su * giu + ondeggio).coerceIn(PRESSIONE_MIN, PRESSIONE_MAX)
            punti[o + 3] = INCLINAZIONE
            punti[o + 4] = -1f
            punti[o + 5] = k * MS_PER_PUNTO
        }
        return Tratto(id, penna, colore, spessore, punti)
    }
}
