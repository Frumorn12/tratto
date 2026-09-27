package it.frumorn.tratto.googledocs

import org.json.JSONArray
import kotlin.math.round

/** Un paragrafo che il documento Google deve avere. */
internal sealed interface Voluto {
    val chiave: String
    val stile: StileParagrafo

    /** Titolo o testo: [testo] e' senza a capo (vedi [pulisciTesto]). */
    data class Testo(val testo: String, override val stile: StileParagrafo = StileParagrafo.TESTO) : Voluto {
        override val chiave get() = chiaveTesto(stile.nome, testo)
    }

    /** L'immagine di una pagina di Tratto, grande [larghezzaPt] x [altezzaPt] punti nel documento. */
    data class Immagine(val pagina: String, val dimensione: String, val larghezzaPt: Double, val altezzaPt: Double) : Voluto {
        override val stile get() = StileParagrafo.IMMAGINE
        override val chiave get() = chiaveImmagine(pagina, dimensione)
    }

    companion object {
        /** Larghezza massima di un'immagine: una pagina A4 con i margini normali (451 punti utili). */
        const val LARGHEZZA_MAX_PT = 440.0

        /** Altezza massima: sta in una pagina A4 (698 punti utili), cosi' ogni pagina di Tratto e' una pagina del documento. */
        const val ALTEZZA_MAX_PT = 690.0

        /** L'immagine di una pagina larga [larghezza] e alta [altezza] (unita' di Tratto), nelle misure massime. */
        fun immagine(pagina: String, larghezza: Float, altezza: Float): Immagine {
            val r = (altezza / larghezza).toDouble().takeIf { it.isFinite() && it > 0 } ?: 1.0
            var w = LARGHEZZA_MAX_PT
            var h = w * r
            if (h > ALTEZZA_MAX_PT) {
                h = ALTEZZA_MAX_PT
                w = h / r
            }
            return Immagine(pagina, "${larghezza}x$altezza", round(w * 10) / 10, round(h * 10) / 10)
        }
    }
}

/**
 * Testo pronto per il documento: niente a capo (ogni paragrafo e' un elemento a parte) e niente
 * caratteri che Google toglierebbe da solo, cambiando la lunghezza su cui si calcolano gli indici.
 */
internal fun pulisciTesto(s: String): String =
    s.replace(Regex("[\\p{Cntrl}  ]"), " ").replace(Regex("\\p{Co}"), "").trimEnd()

/**
 * Cosa cambiare nel documento Google per passare da [attuali] (com'e' adesso) a [voluti].
 *
 * I paragrafi che ci sono gia' e vanno bene restano dove sono (sottosequenza comune piu' lunga
 * delle chiavi): chi guarda il documento non vede sparire e ricomparire le pagine. Gli altri si
 * cancellano e quelli che mancano si inseriscono. Le immagini delle pagine rimaste ma disegnate di
 * nuovo si sostituiscono per id, senza toccare il testo intorno.
 */
internal class PianoDocs(private val attuali: List<ParagrafoDoc>, private val voluti: List<Voluto>) {
    init {
        require(attuali.isNotEmpty()) { "Il corpo di un documento Google ha sempre almeno un paragrafo" }
    }

    /** Coppie (indice in attuali, indice in voluti) dei paragrafi che restano, in ordine. */
    private val coppie: List<Pair<Int, Int>> = sottosequenza(attuali.map { it.chiave }, voluti.map { it.chiave })

    /** Pagina -> id dell'immagine gia' nel documento, per le pagine la cui immagine resta al suo posto. */
    val tenute: Map<String, String> = coppie.mapNotNull { (i, j) ->
        (voluti[j] as? Voluto.Immagine)?.let { v -> attuali[i].oggetto?.let { v.pagina to it } }
    }.toMap()

    /** Pagine la cui immagine va inserita (nuove, spostate o sparite dal documento). */
    val daInserire: List<Voluto.Immagine> = voluti.filterIsInstance<Voluto.Immagine>().filter { it.pagina !in tenute }

    /** Paragrafi che restano ma con stile o allineamento diversi (cambiati a mano nel documento). */
    private val stiliDiversi: Boolean = coppie.any { (i, j) -> attuali[i].aspetto != null && attuali[i].aspetto != voluti[j].stile.aspetto }

    /** Vero se il documento e' gia' giusto: restano da fare al massimo le sostituzioni delle immagini. */
    val strutturaUguale: Boolean get() = coppie.size == attuali.size && coppie.size == voluti.size && !stiliDiversi

    /**
     * Le richieste di batchUpdate. [uri] da' l'indirizzo pubblico dell'immagine di ogni pagina da
     * inserire e di quelle, tra le [tenute], da sostituire.
     */
    fun richieste(uri: Map<String, String>): JSONArray {
        val out = JSONArray()
        // Prima le sostituzioni: lavorano sull'id dell'immagine, non sugli indici.
        for ((i, j) in coppie) {
            val v = voluti[j] as? Voluto.Immagine ?: continue
            val u = uri[v.pagina] ?: continue
            out.put(RichiesteDocs.sostituisciImmagine(attuali[i].oggetto!!, u))
        }
        val fineCorpo = attuali.last().fine
        // Un tratto da cambiare prima di ogni coppia e uno dopo l'ultima. Si lavora dal fondo verso
        // l'inizio: una modifica sposta solo gli indici che vengono dopo, gia' sistemati.
        for (k in coppie.size downTo 0) {
            val prima = coppie.getOrNull(k - 1)
            val dopo = coppie.getOrNull(k)
            val a = prima?.let { attuali[it.first].fine } ?: attuali.first().inizio
            val b = dopo?.let { attuali[it.first].inizio } ?: fineCorpo
            val nuovi = voluti.subList((prima?.second ?: -1) + 1, dopo?.second ?: voluti.size)
            var riprendiPrima = false
            if (a < b || nuovi.isNotEmpty()) when {
                b < fineCorpo -> {
                    // In mezzo: a e b sono inizi di paragrafo, si tolgono paragrafi interi.
                    if (a < b) {
                        out.put(RichiesteDocs.cancella(a, b))
                        // Il paragrafo che segue resta; se prendesse lo stile di quelli tolti, lo si riprende.
                        out.put(RichiesteDocs.stile(a, a + 1, voluti[dopo!!.second].stile))
                    }
                    for (v in nuovi.asReversed()) inserisci(out, v, a, uri)
                }
                prima != null -> {
                    // In fondo, dopo un paragrafo che resta. L'ultimo a capo del corpo non si puo'
                    // cancellare: si toglie quello del paragrafo prima e l'ultimo prende il suo posto.
                    var fondo = b
                    if (a < b) {
                        out.put(RichiesteDocs.cancella(a - 1, b - 1))
                        riprendiPrima = true
                        fondo = a
                    }
                    if (nuovi.isNotEmpty()) {
                        inFondo(out, nuovi.last(), fondo, uri)
                        for (v in nuovi.dropLast(1).asReversed()) inserisci(out, v, fondo, uri)
                    }
                }
                else -> {
                    // Non resta niente: si svuota il corpo (rimane un paragrafo vuoto) e si riscrive.
                    if (a < b - 1) out.put(RichiesteDocs.cancella(a, b - 1))
                    if (nuovi.isNotEmpty()) {
                        riempi(out, nuovi.last(), a, uri)
                        for (v in nuovi.dropLast(1).asReversed()) inserisci(out, v, a, uri)
                    }
                }
            }
            // Il paragrafo che resta prima di questo tratto: il suo inizio non si e' mosso. Si sistema
            // lo stile se finisce con un a capo non suo o se e' stato cambiato a mano.
            if (prima != null) {
                val p = attuali[prima.first]
                val stile = voluti[prima.second].stile
                if (riprendiPrima || (p.aspetto != null && p.aspetto != stile.aspetto)) out.put(RichiesteDocs.stile(p.inizio, p.inizio + 1, stile))
            }
        }
        return out
    }

    /** Nuovo paragrafo [v] prima di quello che comincia in [a]. */
    private fun inserisci(out: JSONArray, v: Voluto, a: Int, uri: Map<String, String>) {
        when (v) {
            is Voluto.Testo -> out.put(RichiesteDocs.inserisciTesto(a, v.testo + "\n"))
            is Voluto.Immagine -> {
                out.put(RichiesteDocs.inserisciTesto(a, "\n"))
                out.put(immagine(v, a, uri))
            }
        }
        out.put(RichiesteDocs.stile(a, a + 1, v.stile))
    }

    /** Nuovo paragrafo [v] dopo l'ultimo, con il corpo che finisce in [fine]: comincera' in [fine]. */
    private fun inFondo(out: JSONArray, v: Voluto, fine: Int, uri: Map<String, String>) {
        when (v) {
            is Voluto.Testo -> out.put(RichiesteDocs.inserisciTesto(fine - 1, "\n" + v.testo))
            is Voluto.Immagine -> {
                out.put(RichiesteDocs.inserisciTesto(fine - 1, "\n"))
                out.put(immagine(v, fine, uri))
            }
        }
        out.put(RichiesteDocs.stile(fine, fine + 1, v.stile))
    }

    /** [v] dentro il paragrafo vuoto che comincia in [a], unico rimasto nel corpo. */
    private fun riempi(out: JSONArray, v: Voluto, a: Int, uri: Map<String, String>) {
        when (v) {
            is Voluto.Testo -> if (v.testo.isNotEmpty()) out.put(RichiesteDocs.inserisciTesto(a, v.testo))
            is Voluto.Immagine -> out.put(immagine(v, a, uri))
        }
        out.put(RichiesteDocs.stile(a, a + 1, v.stile))
    }

    private fun immagine(v: Voluto.Immagine, indice: Int, uri: Map<String, String>) =
        RichiesteDocs.inserisciImmagine(indice, uri[v.pagina] ?: error("Manca l'immagine della pagina ${v.pagina}"), v.larghezzaPt, v.altezzaPt)

    companion object {
        /** Sottosequenza comune piu' lunga, come coppie di indici in ordine crescente. */
        fun sottosequenza(a: List<String>, b: List<String>): List<Pair<Int, Int>> {
            val n = a.size
            val m = b.size
            // l[i][j] = lunghezza della sottosequenza comune piu' lunga di a[i..] e b[j..]
            val l = Array(n + 1) { IntArray(m + 1) }
            for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
                l[i][j] = if (a[i] == b[j]) l[i + 1][j + 1] + 1 else maxOf(l[i + 1][j], l[i][j + 1])
            }
            val out = ArrayList<Pair<Int, Int>>()
            var i = 0
            var j = 0
            while (i < n && j < m) {
                when {
                    a[i] == b[j] -> { out += i to j; i++; j++ }
                    l[i + 1][j] >= l[i][j + 1] -> i++
                    else -> j++
                }
            }
            return out
        }
    }
}
