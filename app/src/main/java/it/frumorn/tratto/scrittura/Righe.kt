package it.frumorn.tratto.scrittura

import it.frumorn.tratto.data.Tratto

/**
 * Divisione dei tratti in righe di scrittura, senza dipendenze Android (si prova sulla JVM).
 *
 * I tratti "principali" (lettere, parole in corsivo) si ordinano per centro verticale e si
 * spezzano in righe dove il salto tra due centri consecutivi supera 0,6 volte l'altezza mediana
 * dei tratti. Si confrontano i centri e non gli ingombri perche' i discendenti di una riga (g, p)
 * toccano spesso le aste della riga sotto (l, d): con gli ingombri due righe finirebbero unite.
 * I tratti piccoli (puntini delle i, accenti, virgole, apostrofi) si assegnano poi alla riga
 * con il centro piu' vicino, e dentro ogni riga i tratti vanno da sinistra a destra.
 */
internal object Righe {
    /** Soglia di salto tra due righe, in multipli dell'altezza mediana dei tratti. */
    const val SALTO = 0.6f

    /** Sotto questa frazione dell'altezza mediana un tratto e' un segno piccolo (puntino, accento). */
    private const val PICCOLO = 0.4f

    class Scatola(val tratto: Tratto, val sinistra: Float, val alto: Float, val destra: Float, val basso: Float) {
        val altezza: Float get() = basso - alto
        val cx: Float get() = (sinistra + destra) / 2f
        val cy: Float get() = (alto + basso) / 2f
    }

    /** Una riga: i tratti da sinistra a destra, l'ingombro e il centro verticale tipico. */
    class Riga(val tratti: List<Tratto>, val sinistra: Float, val alto: Float, val destra: Float, val basso: Float, val centro: Float) {
        val larghezza: Float get() = destra - sinistra
        val altezza: Float get() = basso - alto
    }

    fun scatola(t: Tratto): Scatola? {
        if (t.quanti == 0) return null
        val p = t.punti
        var sx = Float.MAX_VALUE; var sy = Float.MAX_VALUE
        var dx = -Float.MAX_VALUE; var dy = -Float.MAX_VALUE
        for (i in 0 until t.quanti) {
            val o = i * Tratto.CAMPI
            val x = p[o]; val y = p[o + 1]
            if (x < sx) sx = x
            if (x > dx) dx = x
            if (y < sy) sy = y
            if (y > dy) dy = y
        }
        return Scatola(t, sx, sy, dx, dy)
    }

    fun dividi(tratti: List<Tratto>): List<Riga> {
        val scatole = tratti.mapNotNull { scatola(it) }
        if (scatole.isEmpty()) return emptyList()
        val altezzaTipica = mediana(scatole.map { it.altezza }).coerceAtLeast(1e-3f)
        // Se sono tutti tratti piatti (per esempio solo trattini) li si tratta come principali.
        val (principali, piccoli) = scatole.partition { it.altezza >= PICCOLO * altezzaTipica }
            .let { (a, b) -> if (a.isEmpty()) b to a else a to b }
        val soglia = SALTO * mediana(principali.map { it.altezza }).coerceAtLeast(1e-3f)

        val gruppi = ArrayList<MutableList<Scatola>>()
        var ultimo = Float.NaN
        for (s in principali.sortedBy { it.cy }) {
            if (gruppi.isEmpty() || s.cy - ultimo > soglia) gruppi.add(ArrayList())
            gruppi.last().add(s)
            ultimo = s.cy
        }
        val centri = gruppi.map { g -> mediana(g.map { it.cy }) }
        for (s in piccoli) {
            var migliore = 0
            for (i in centri.indices) {
                if (kotlin.math.abs(s.cy - centri[i]) < kotlin.math.abs(s.cy - centri[migliore])) migliore = i
            }
            gruppi[migliore].add(s)
        }
        return gruppi.mapIndexed { i, g ->
            // sortedBy e' stabile: a parita' di x resta l'ordine in cui i tratti sono stati scritti.
            val ordinati = g.sortedBy { it.sinistra }
            Riga(
                tratti = ordinati.map { it.tratto },
                sinistra = g.minOf { it.sinistra },
                alto = g.minOf { it.alto },
                destra = g.maxOf { it.destra },
                basso = g.maxOf { it.basso },
                centro = centri[i],
            )
        }
    }

    fun mediana(valori: List<Float>): Float {
        if (valori.isEmpty()) return 0f
        val s = valori.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2f
    }
}
