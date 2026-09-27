package it.frumorn.tratto.calcolo

import it.frumorn.tratto.data.Tratto

/**
 * Chi legge una sequenza di simboli scritti a mano su una riga: ML Kit nell'app
 * ([it.frumorn.tratto.scrittura.Trascrittore.candidati]), un lettore finto nei test.
 * Restituisce i testi candidati, dal piu' probabile.
 */
internal fun interface Lettore {
    suspend fun leggi(tratti: List<Tratto>, preContesto: String, altezzaRiga: Float): List<String>
}

/** Una formula letta: l'albero dell'espressione e la struttura geometrica da cui viene. */
internal class Formula(val nodo: Nodo, val riga: Riga)

/**
 * Dalla struttura della formula al testo, con ML Kit solo sui pezzi lineari.
 *
 * La struttura diventa uno schema di testo fisso con dei buchi: una frazione e' "((" num ")⁄("
 * den "))", una radice "(√(" radicando "))", un esponente "^(" esp ")", un simbolo riconosciuto
 * dalla forma il suo testo. Ogni buco e' una sequenza di simboli sulla stessa riga, che ML Kit
 * legge come una riga di testo. Si provano poi le combinazioni delle letture dei pezzi, dalle
 * piu' probabili, finche' una non da' un'espressione valida con almeno un'operazione.
 */
internal object Lettura {
    /** Combinazioni di letture da provare al massimo. */
    private const val TENTATIVI = 64

    sealed interface Parte
    class Fissa(val testo: String) : Parte
    class Buco(val sequenza: Int) : Parte

    /** Simboli consecutivi sulla stessa riga, da leggere insieme; [h] e' l'altezza dei simboli. */
    class Sequenza(val gruppi: List<Gruppo>, val h: Float) {
        /** I tratti da sinistra a destra, e dentro ogni simbolo nell'ordine in cui sono stati scritti. */
        val tratti: List<Tratto> get() = gruppi.flatMap { g -> g.forme.sortedBy { it.id }.map { it.tratto } }
    }

    class Schema(val parti: List<Parte>, val sequenze: List<Sequenza>)

    fun schema(riga: Riga): Schema {
        val parti = ArrayList<Parte>()
        val sequenze = ArrayList<Sequenza>()
        fun visita(r: Riga) {
            var corrente = ArrayList<Gruppo>()
            fun chiudi() {
                if (corrente.isEmpty()) return
                sequenze += Sequenza(corrente, r.h)
                parti += Buco(sequenze.lastIndex)
                corrente = ArrayList()
            }
            for (v in r.voci) {
                when (val e = v.elemento) {
                    is Gruppo -> if (e.speciale != null) { chiudi(); parti += Fissa(e.speciale) } else corrente += e
                    is Frazione -> {
                        chiudi()
                        parti += Fissa("((")
                        visita(e.numeratore)
                        parti += Fissa(")⁄(")
                        visita(e.denominatore)
                        parti += Fissa("))")
                    }
                    is Radice -> {
                        chiudi()
                        parti += Fissa("(√(")
                        visita(e.contenuto)
                        parti += Fissa("))")
                    }
                }
                v.esponente?.let {
                    chiudi()
                    parti += Fissa("^(")
                    visita(it)
                    parti += Fissa(")")
                }
            }
            chiudi()
        }
        visita(riga)
        return Schema(parti, sequenze)
    }

    /**
     * Vero se la formula non puo' contenere un calcolo, e quindi non vale la pena di chiamare
     * ML Kit: un solo simbolo, senza frazioni, radici ne' esponenti.
     */
    fun banale(riga: Riga): Boolean {
        val v = riga.voci.singleOrNull() ?: return riga.voci.isEmpty()
        val e = v.elemento
        return v.esponente == null && e is Gruppo && e.speciale == null
    }

    suspend fun leggi(riga: Riga, lettore: Lettore): Formula? {
        val schema = schema(riga)
        val varianti = ArrayList<List<String>>()
        var pre = ""
        for ((k, s) in schema.sequenze.withIndex()) {
            val candidati = lettore.leggi(s.tratti, pre, 2f * s.h)
            val iniziale = k == 0 && (schema.parti.firstOrNull() as? Buco)?.sequenza == 0
            val v = Testo.varianti(candidati, iniziale)
            if (v.isEmpty()) return null
            varianti += v
            pre = candidati.firstOrNull()?.trim().orEmpty()
        }
        for (scelta in combinazioni(varianti.map { it.size })) {
            val testo = schema.parti.joinToString("") {
                when (it) {
                    is Fissa -> it.testo
                    is Buco -> varianti[it.sequenza][scelta[it.sequenza]]
                }
            }
            val nodo = Espressione.analizza(testo) ?: continue
            if (nodo.operazione) return Formula(nodo, riga)
        }
        return null
    }

    /** Scelte di una lettura per ogni pezzo, per somma crescente delle posizioni (le piu' probabili prima). */
    internal fun combinazioni(dimensioni: List<Int>): List<IntArray> {
        val out = ArrayList<IntArray>()
        if (dimensioni.isEmpty()) return listOf(IntArray(0))
        if (dimensioni.any { it == 0 }) return out
        val corrente = IntArray(dimensioni.size)
        fun riempi(i: Int, resto: Int) {
            if (out.size >= TENTATIVI) return
            if (i == dimensioni.lastIndex) {
                if (resto < dimensioni[i]) { corrente[i] = resto; out += corrente.copyOf() }
                return
            }
            for (v in 0..minOf(resto, dimensioni[i] - 1)) {
                corrente[i] = v
                riempi(i + 1, resto - v)
            }
        }
        val massimo = dimensioni.sumOf { it - 1 }
        for (somma in 0..massimo) {
            riempi(0, somma)
            if (out.size >= TENTATIVI) break
        }
        return out
    }
}
