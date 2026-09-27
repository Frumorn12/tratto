package it.frumorn.tratto.data

/**
 * Oggetti della pagina: immagini e caselle di testo. Stanno sotto i tratti (l'inchiostro resta sempre
 * sopra) e usano le stesse coordinate: la pagina e' larga 1000 unita', y verso il basso.
 *
 * Sono immutabili: ogni modifica crea un oggetto nuovo con lo stesso id, cosi' la cronologia
 * (annulla/ripeti) tiene il prima e il dopo senza copie difensive.
 */
sealed class Oggetto {
    abstract val id: String
    abstract val x: Float
    abstract val y: Float

    abstract fun spostato(dx: Float, dy: Float): Oggetto
}

/** Immagine salvata nella cartella della nota: [file] e' relativo alla cartella, es. "immagini/ab12cd.webp". */
data class Immagine(
    override val id: String,
    val file: String,
    override val x: Float,
    override val y: Float,
    val larghezza: Float,
    val altezza: Float,
) : Oggetto() {
    override fun spostato(dx: Float, dy: Float) = copy(x = x + dx, y = y + dy)
}

/** Casella di testo larga [larghezza]: l'altezza e' quella del testo impaginato. */
data class Testo(
    override val id: String,
    override val x: Float,
    override val y: Float,
    val larghezza: Float,
    val contenuto: TestoRicco,
) : Oggetto() {
    override fun spostato(dx: Float, dy: Float) = copy(x = x + dx, y = y + dy)

    /**
     * Impaginazione calcolata da chi disegna (su Android uno StaticLayout). Non fa parte dei dati:
     * l'oggetto e' immutabile, quindi resta valida finche' esiste questa istanza; copy() riparte da zero.
     */
    @Volatile var impaginato: Any? = null

    companion object {
        const val LARGHEZZA_MIN = 60f
    }
}

// ---------------------------------------------------------------- testo ricco

/** Unita' di pagina per punto tipografico: la pagina larga 1000 unita' e' un A4 largo 595,28 punti. */
const val UNITA_PER_PUNTO = 1000f / 595.28f

/**
 * Stili di paragrafo, come in Google Documenti: dimensione in punti, colore (ARGB) e spazio prima e
 * dopo il paragrafo in punti. Le dimensioni sono un po' piu' grandi di quelle di Documenti (11 pt per
 * il testo normale), perche' sul tablet si legge una pagina A4 intera a meno di grandezza naturale.
 */
enum class StileParagrafo(val chiave: String, val nome: String, val punti: Float, val colore: Int, val prima: Float, val dopo: Float) {
    NORMALE("normale", "Testo normale", 14f, COLORE_TESTO, 0f, 0f),
    TITOLO("titolo", "Titolo", 32f, COLORE_TESTO, 0f, 4f),
    SOTTOTITOLO("sottotitolo", "Sottotitolo", 19f, 0xFF666666.toInt(), 0f, 12f),
    INTESTAZIONE1("intestazione1", "Intestazione 1", 25f, COLORE_TESTO, 16f, 6f),
    INTESTAZIONE2("intestazione2", "Intestazione 2", 20f, COLORE_TESTO, 14f, 6f),
    INTESTAZIONE3("intestazione3", "Intestazione 3", 17f, 0xFF434343.toInt(), 12f, 4f),
}

/** Colore predefinito del testo: lo stesso nero della penna. */
const val COLORE_TESTO = 0xFF1E1E1E.toInt()

enum class Allineamento(val chiave: String) { SINISTRA("sinistra"), CENTRO("centro"), DESTRA("destra"), GIUSTIFICATO("giustificato") }

enum class Elenco(val chiave: String) { NESSUNO("nessuno"), PUNTATO("puntato"), NUMERATO("numerato") }

enum class Carattere(val chiave: String, val nome: String) { MANROPE("manrope", "Manrope"), SERIF("serif", "Serif"), MONO("mono", "Monospace") }

/** Un pezzo di testo con lo stesso formato. I valori null prendono quelli dello stile del paragrafo. */
data class Frammento(
    val testo: String,
    val grassetto: Boolean = false,
    val corsivo: Boolean = false,
    val sottolineato: Boolean = false,
    val barrato: Boolean = false,
    /** ARGB, oppure null per il colore dello stile del paragrafo. */
    val colore: Int? = null,
    /** Colore di evidenziazione (ARGB), oppure null. */
    val evidenziato: Int? = null,
    /** Dimensione in punti, oppure null per quella dello stile del paragrafo. */
    val punti: Float? = null,
    val carattere: Carattere = Carattere.MANROPE,
) {
    /** Stesso formato (il testo non conta). */
    fun stessoFormato(o: Frammento) = copy(testo = "") == o.copy(testo = "")

    fun puntiEffettivi(p: Paragrafo) = punti ?: p.stile.punti
    fun coloreEffettivo(p: Paragrafo) = colore ?: p.stile.colore

    /** Solo il formato, senza testo e senza gli attributi che vengono dallo stile del paragrafo. */
    fun soloCarattere() = Frammento("", grassetto, corsivo, sottolineato, barrato, colore, evidenziato, punti, carattere)
}

data class Paragrafo(
    val frammenti: List<Frammento> = emptyList(),
    val stile: StileParagrafo = StileParagrafo.NORMALE,
    val allineamento: Allineamento = Allineamento.SINISTRA,
    val elenco: Elenco = Elenco.NESSUNO,
    /** Livello di rientro, da 0. */
    val rientro: Int = 0,
) {
    val testo: String get() = frammenti.joinToString("") { it.testo }
    val lunghezza: Int get() = frammenti.sumOf { it.testo.length }

    /** Gli attributi del paragrafo, senza testo. */
    fun soloAttributi() = copy(frammenti = emptyList())
}

/**
 * Testo con formato, a paragrafi. Le posizioni usate dalle operazioni sono quelle di [testoSemplice],
 * dove i paragrafi sono separati da un "\n": sono le stesse del campo di testo che lo modifica.
 */
data class TestoRicco(val paragrafi: List<Paragrafo>) {
    init {
        require(paragrafi.isNotEmpty()) { "Un testo ha almeno un paragrafo" }
    }

    val testoSemplice: String by lazy { paragrafi.joinToString("\n") { it.testo } }
    val vuoto: Boolean get() = testoSemplice.isBlank()
    val lunghezza: Int get() = testoSemplice.length

    /** Inizio di ogni paragrafo in [testoSemplice]. */
    private fun inizi(): IntArray {
        val out = IntArray(paragrafi.size)
        var o = 0
        for ((k, p) in paragrafi.withIndex()) { out[k] = o; o += p.lunghezza + 1 }
        return out
    }

    /** Applica [f] ai caratteri in [inizio, fine), spezzando i frammenti ai bordi. */
    fun formatta(inizio: Int, fine: Int, f: (Frammento, Paragrafo) -> Frammento): TestoRicco {
        if (fine <= inizio) return this
        val inizi = inizi()
        val nuovi = paragrafi.mapIndexed { k, p ->
            val a = (inizio - inizi[k]).coerceIn(0, p.lunghezza)
            val b = (fine - inizi[k]).coerceIn(0, p.lunghezza)
            if (a >= b) p else p.copy(frammenti = formattaFrammenti(p, a, b, f))
        }
        return TestoRicco(nuovi).normalizzato()
    }

    private fun formattaFrammenti(p: Paragrafo, a: Int, b: Int, f: (Frammento, Paragrafo) -> Frammento): List<Frammento> {
        val out = ArrayList<Frammento>()
        var o = 0
        for (fr in p.frammenti) {
            val s = o; val e = o + fr.testo.length
            o = e
            if (e <= a || s >= b) { out += fr; continue }
            val x = maxOf(a, s) - s; val y = minOf(b, e) - s
            if (x > 0) out += fr.copy(testo = fr.testo.substring(0, x))
            out += f(fr.copy(testo = fr.testo.substring(x, y)), p)
            if (y < fr.testo.length) out += fr.copy(testo = fr.testo.substring(y))
        }
        return out
    }

    /** Indici dei paragrafi toccati da [inizio, fine): quello che contiene l'inizio e quelli che cominciano dentro. */
    fun paragrafiToccati(inizio: Int, fine: Int): IntRange {
        val inizi = inizi()
        var primo = paragrafi.lastIndex
        for (k in paragrafi.indices) if (inizio <= inizi[k] + paragrafi[k].lunghezza) { primo = k; break }
        var ultimo = primo
        for (k in primo + 1..paragrafi.lastIndex) if (inizi[k] < fine) ultimo = k
        return primo..ultimo
    }

    /** Applica [f] ai paragrafi toccati da [inizio, fine) (con inizio == fine, a quello del cursore). */
    fun formattaParagrafi(inizio: Int, fine: Int, f: (Paragrafo) -> Paragrafo): TestoRicco {
        val toccati = paragrafiToccati(inizio, fine)
        return TestoRicco(paragrafi.mapIndexed { k, p -> if (k in toccati) f(p) else p }).normalizzato()
    }

    /** Il formato comune in [inizio, fine), per mostrare lo stato dei pulsanti. */
    fun formato(inizio: Int, fine: Int): Formato {
        val toccati = paragrafiToccati(inizio, fine)
        val paragrafiSel = toccati.map { paragrafi[it] }
        val inizi = inizi()
        val caratteri = ArrayList<Pair<Frammento, Paragrafo>>()
        if (fine > inizio) {
            for (k in toccati) {
                val p = paragrafi[k]
                var o = inizi[k]
                for (fr in p.frammenti) {
                    val s = o; val e = o + fr.testo.length
                    o = e
                    if (e > inizio && s < fine) caratteri += fr to p
                }
            }
        }
        if (caratteri.isEmpty()) {
            // Cursore: conta il carattere prima (nello stesso paragrafo), altrimenti il primo del paragrafo.
            val k = toccati.first
            val p = paragrafi[k]
            val pos = (inizio - inizi[k]).coerceIn(0, p.lunghezza)
            caratteri += (frammentoIn(p, if (pos > 0) pos - 1 else 0) ?: Frammento("")) to p
        }
        fun <T> comune(v: (Pair<Frammento, Paragrafo>) -> T): T? = caratteri.map(v).distinct().singleOrNull()
        fun <T> comuneP(v: (Paragrafo) -> T): T? = paragrafiSel.map(v).distinct().singleOrNull()
        return Formato(
            grassetto = caratteri.all { it.first.grassetto },
            corsivo = caratteri.all { it.first.corsivo },
            sottolineato = caratteri.all { it.first.sottolineato },
            barrato = caratteri.all { it.first.barrato },
            colore = comune { (f, p) -> f.coloreEffettivo(p) },
            evidenziato = comune { it.first.evidenziato },
            punti = comune { (f, p) -> f.puntiEffettivi(p) },
            carattere = comune { it.first.carattere },
            stile = comuneP { it.stile },
            allineamento = comuneP { it.allineamento },
            elenco = comuneP { it.elenco },
        )
    }

    /** Il frammento che contiene il carattere [pos] del paragrafo. */
    private fun frammentoIn(p: Paragrafo, pos: Int): Frammento? {
        var o = 0
        for (fr in p.frammenti) {
            if (pos < o + fr.testo.length) return fr
            o += fr.testo.length
        }
        return p.frammenti.lastOrNull()
    }

    /** Tutto il testo ingrandito o rimpicciolito di [fattore] (quando si ridimensiona con il lazo). */
    fun scalato(fattore: Float): TestoRicco = TestoRicco(paragrafi.map { p ->
        p.copy(frammenti = p.frammenti.map { f -> f.copy(punti = arrotondaPunti(f.puntiEffettivi(p) * fattore)) })
    }).normalizzato()

    /** Unisce i frammenti vicini con lo stesso formato e toglie quelli vuoti. */
    fun normalizzato(): TestoRicco = TestoRicco(paragrafi.map { p ->
        val out = ArrayList<Frammento>()
        for (f in p.frammenti) {
            if (f.testo.isEmpty()) continue
            val u = out.lastOrNull()
            if (u != null && u.stessoFormato(f)) out[out.lastIndex] = u.copy(testo = u.testo + f.testo) else out += f
        }
        p.copy(frammenti = out)
    })

    companion object {
        val VUOTO = TestoRicco(listOf(Paragrafo()))

        /** Testo semplice (es. incollato): un paragrafo per riga, senza formato. */
        fun semplice(testo: String): TestoRicco = TestoRicco(
            testo.replace("\r\n", "\n").replace('\r', '\n').split('\n').map { r ->
                Paragrafo(if (r.isEmpty()) emptyList() else listOf(Frammento(r)))
            },
        )

        fun arrotondaPunti(p: Float): Float = (Math.round(p * 2f) / 2f).coerceIn(PUNTI_MIN, PUNTI_MAX)

        const val PUNTI_MIN = 6f
        const val PUNTI_MAX = 144f
    }
}

/** Il formato di una selezione. I valori null vogliono dire "misto". */
data class Formato(
    val grassetto: Boolean = false,
    val corsivo: Boolean = false,
    val sottolineato: Boolean = false,
    val barrato: Boolean = false,
    val colore: Int? = COLORE_TESTO,
    val evidenziato: Int? = null,
    val punti: Float? = StileParagrafo.NORMALE.punti,
    val carattere: Carattere? = Carattere.MANROPE,
    val stile: StileParagrafo? = StileParagrafo.NORMALE,
    val allineamento: Allineamento? = Allineamento.SINISTRA,
    val elenco: Elenco? = Elenco.NESSUNO,
)
