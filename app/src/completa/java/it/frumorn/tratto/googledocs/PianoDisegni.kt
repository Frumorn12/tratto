package it.frumorn.tratto.googledocs

import kotlin.math.abs

/**
 * Un disegno di Tratto nel documento Google: un'immagine posizionata davanti al testo, ancorata a un
 * paragrafo. [oggetto] e' il suo id nel documento (positionedObjectId).
 */
internal data class DisegnoSalvato(
    val chiave: String,
    val pagina: String,
    val oggetto: String,
    /** Impronta di tratti e riquadro (vedi [BlocchiPagina.impronta]): se cambia, il disegno va rifatto. */
    val impronta: String,
)

/** Un disegno sul foglio: pagina (indice), riquadro in unita' di pagina e impronta. */
internal data class DisegnoVoluto(val chiave: String, val pagina: String, val indicePagina: Int, val riquadro: Riquadro, val impronta: String)

/** Dove va un disegno nel documento: paragrafo di ancoraggio (indice nel modello) e posizione rispetto a lui, in punti. */
internal data class Ancora(val paragrafo: Int, val sinistra: Double, val alto: Double, val larghezza: Double, val altezza: Double)

/** Dove sono i paragrafi del documento impaginato su Tratto (vedi [PagineDoc.posizioni]). */
internal class PostiParagrafi(
    /** Per ogni paragrafo: pagina e cima della prima riga, in unita' di pagina. */
    val pagine: List<Int>,
    val cime: List<Float>,
    /** Unita' di pagina per punto, e margine sinistro in punti. */
    val u: Double,
    val margineSinistro: Double,
)

/**
 * Cosa fare nel documento per avere i disegni del foglio davanti al testo, ognuno nel suo punto.
 *
 * Un disegno si ancora all'ultimo paragrafo che comincia sulla sua pagina sopra di lui (o, se non ce
 * n'e', al primo della pagina), con la distanza dal paragrafo e dal margine sinistro. Un disegno
 * gia' nel documento resta se non e' cambiato (stessa impronta) e se e' ancora ancorato allo stesso
 * paragrafo, nello stesso punto (entro un punto); altrimenti si rifa'. Un disegno su una pagina dove
 * il documento non ha testo per ora non si puo' mettere.
 */
internal class PianoDisegni(
    private val modello: ModelloDoc,
    private val posti: PostiParagrafi,
    salvati: List<DisegnoSalvato>,
    private val voluti: List<DisegnoVoluto>,
) {
    /** Disegni da mettere nel documento (nuovi o cambiati), con la loro ancora. */
    val daAggiungere = ArrayList<Pair<DisegnoVoluto, Ancora>>()

    /** Oggetti del documento da togliere (disegni cambiati o spariti dal foglio). */
    val daTogliere = ArrayList<String>()

    /** Disegni che restano come sono. */
    val tenuti = ArrayList<DisegnoSalvato>()

    /** Disegni che per ora non si possono mettere (pagina senza testo nel documento). */
    val senzaPosto = ArrayList<DisegnoVoluto>()

    /** Paragrafo che contiene ogni oggetto posizionato. */
    private val ancoraDi: Map<String, Int> = HashMap<String, Int>().also { m ->
        for ((k, p) in modello.paragrafi.withIndex()) for (id in p.posizionati) m[id] = k
    }

    init {
        val perChiave = salvati.associateBy { it.chiave }
        val usati = HashSet<String>()
        for (v in voluti) {
            val s = perChiave[v.chiave]
            val a = ancora(v)
            if (a == null) {
                senzaPosto += v
                continue
            }
            if (s != null && s.oggetto in modello.posizionati && s.impronta == v.impronta && alSuoPosto(s.oggetto, v)) {
                tenuti += s
                usati += s.chiave
                continue
            }
            daAggiungere += v to a
        }
        // Tolti: cambiati, spariti dal foglio o senza posto (tranne quelli rimasti come sono).
        for (s in salvati) if (s.chiave !in usati && s.oggetto in modello.posizionati) daTogliere += s.oggetto
    }

    val vuoto: Boolean get() = daAggiungere.isEmpty() && daTogliere.isEmpty()

    /** Il paragrafo (indice nel modello) a cui e' ancorato l'oggetto [oggetto] del documento, se c'e'. */
    fun paragrafoDi(oggetto: String): Int? = ancoraDi[oggetto]

    /** L'ancora di un disegno, o null se sulla sua pagina il documento non ha paragrafi. */
    fun ancora(v: DisegnoVoluto): Ancora? {
        var scelto = -1
        for (k in posti.pagine.indices) {
            if (posti.pagine[k] != v.indicePagina) continue
            if (scelto < 0 || posti.cime[k] <= v.riquadro.alto + TOLLERANZA) scelto = k
            if (posti.cime[k] > v.riquadro.alto + TOLLERANZA) break
        }
        if (scelto < 0) return null
        val u = posti.u
        return Ancora(
            scelto,
            v.riquadro.sinistra / u - posti.margineSinistro,
            (v.riquadro.alto - posti.cime[scelto]) / u,
            v.riquadro.larghezza / u,
            v.riquadro.altezza / u,
        )
    }

    /**
     * L'oggetto sta dove deve stare, sulla stessa pagina entro un punto. Conta il posto, non il
     * paragrafo: se sopra e' arrivato testo nuovo e il disegno e' rimasto ancorato al paragrafo di
     * prima, va bene lo stesso.
     */
    private fun alSuoPosto(oggetto: String, v: DisegnoVoluto): Boolean {
        val o = modello.posizionati[oggetto] ?: return false
        val k = ancoraDi[oggetto] ?: return false
        if (posti.pagine.getOrNull(k) != v.indicePagina) return false
        val u = posti.u
        val x = (posti.margineSinistro + o.sinistra) * u
        val y = posti.cime[k] + o.alto * u
        return abs(x - v.riquadro.sinistra) < u && abs(y - v.riquadro.alto) < u
    }

    private companion object {
        /** Un paragrafo che comincia appena sotto la cima del disegno (una lettera alta) conta ancora come sopra. */
        const val TOLLERANZA = 4f
    }
}
