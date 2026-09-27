package it.frumorn.tratto.calcolo

import kotlin.math.abs

/** Un pezzo di formula su una riga: un simbolo, una frazione o una radice. */
internal sealed class Elemento : Ingombro {
    abstract val forme: List<Forma>
    private val r: Rettangolo by lazy { unione(forme) }
    override val sx: Float get() = r.sx
    override val alto: Float get() = r.alto
    override val dx: Float get() = r.dx
    override val basso: Float get() = r.basso
}

/**
 * Un simbolo: uno o piu' tratti vicini (il piu' in due tratti, il 4 in due tempi). I simboli
 * riconosciuti gia' dalla forma hanno [speciale], il loro testo nella formula: "/" per il
 * diviso con i due puntini, "*" per il puntino a meta' altezza, "√" per la radice senza barra.
 */
internal class Gruppo(override val forme: List<Forma>, val speciale: String? = null) : Elemento()

internal class Frazione(val barra: Forma, val numeratore: Riga, val denominatore: Riga) : Elemento() {
    override val forme: List<Forma> = listOf(barra) + numeratore.forme + denominatore.forme
}

internal class Radice(val segno: List<Forma>, val contenuto: Riga) : Elemento() {
    override val forme: List<Forma> = segno + contenuto.forme
}

/** Un elemento con il suo eventuale esponente. */
internal class Voce(val elemento: Elemento, val esponente: Riga?)

/** Una sequenza di elementi allo stesso livello, da sinistra a destra; [h] e' l'altezza tipica dei simboli. */
internal class Riga(val voci: List<Voce>, val h: Float) {
    val forme: List<Forma> get() = voci.flatMap { v -> v.elemento.forme + (v.esponente?.forme ?: emptyList()) }

    /** I simboli di grandezza normale: questa riga, numeratori, denominatori e radicandi, senza esponenti. */
    fun gruppiNormali(): List<Gruppo> = voci.flatMap { v ->
        when (val e = v.elemento) {
            is Gruppo -> listOf(e)
            is Frazione -> e.numeratore.gruppiNormali() + e.denominatore.gruppiNormali()
            is Radice -> e.contenuto.gruppiNormali()
        }
    }
}

/**
 * Analisi bidimensionale di una formula scritta a mano: dai tratti a un albero di righe,
 * frazioni, radici ed esponenti. ML Kit legge solo testo su una riga; qui si trova la struttura
 * e poi si fanno leggere a ML Kit solo i pezzi lineari.
 *
 * 1. Strutture, dalla piu' larga (cosi' in una frazione di frazioni la barra principale viene
 *    prima delle altre, e una radice che copre una frazione viene prima della frazione):
 *    - barra di frazione: un trattino orizzontale con simboli veri (non puntini, come nel
 *      diviso) sia sopra sia sotto, dentro la sua larghezza;
 *    - radice: un tratto a "√" (breve discesa, lunga salita) con la barra in cima, nello stesso
 *      tratto o in un trattino a parte che parte dalla punta; copre i simboli sotto la barra.
 *    Numeratore, denominatore e radicando si analizzano allo stesso modo, ricorsivamente.
 * 2. I tratti rimasti si raggruppano in simboli: tratti che si sovrappongono in orizzontale.
 * 3. Esponenti: dopo un simbolo alto (cifra, parentesi), i simboli piu' piccoli con il centro
 *    nella parte alta del simbolo e subito a destra.
 */
internal object Struttura {
    private const val PROFONDITA_MAX = 6

    fun analizza(forme: List<Forma>, hPadre: Float, profondita: Int = 0): Riga {
        if (forme.isEmpty()) return Riga(emptyList(), hPadre)
        val h = altezzaTipica(forme, hPadre)
        val libere = forme.toMutableList()
        val strutture = ArrayList<Elemento>()
        if (profondita < PROFONDITA_MAX) {
            while (true) {
                val c = candidataPiuLarga(libere, h) ?: break
                libere.removeAll { it in c.forme }
                strutture += c.costruisci(profondita)
            }
        }
        val elementi = (raggruppa(libere, h) + strutture).sortedBy { it.sx }
        return apici(elementi, h, profondita)
    }

    /**
     * [altezzaCifre] dei tratti non piatti e non parentesi (che nel corsivo sono alte quasi il
     * doppio delle cifre); con meno di due tratti, quella del livello sopra.
     */
    internal fun altezzaTipica(forme: List<Forma>, hPadre: Float): Float {
        val alte = forme.filter { !it.piatto(hPadre) && it.altezza >= 0.3f * it.larghezza && !it.parentesi }.map { it.altezza }
        if (alte.size < 2) return hPadre
        return altezzaCifre(alte).coerceIn(0.3f * hPadre, 1.6f * hPadre)
    }

    // --- Frazioni e radici ---------------------------------------------------------------------

    private class Candidata(val larghezza: Float, val forme: Set<Forma>, val costruisci: (Int) -> Elemento)

    private fun candidataPiuLarga(libere: List<Forma>, h: Float): Candidata? {
        var migliore: Candidata? = null
        for (f in libere) {
            val c = frazione(f, libere, h) ?: radice(f, libere, h) ?: continue
            if (migliore == null || c.larghezza > migliore.larghezza) migliore = c
        }
        return migliore
    }

    private fun frazione(b: Forma, libere: List<Forma>, h: Float): Candidata? {
        if (!b.orizzontale || b.larghezza < 0.45f * h) return null
        val margine = 0.15f * b.larghezza + 0.1f * h
        val colonna = libere.filter { it !== b && it.cx >= b.sx - margine && it.cx <= b.dx + margine }
        // Sopra: dal piu' vicino alla barra in su, finche' non c'e' un vuoto.
        val sopra = ArrayList<Forma>()
        var frontiera = b.alto
        for (c in colonna.filter { it.cy < b.cy && it.basso <= b.basso + 0.15f * h }.sortedByDescending { it.basso }) {
            if (c.basso < frontiera - 0.8f * h) break
            sopra += c
            if (c.alto < frontiera) frontiera = c.alto
        }
        val sotto = ArrayList<Forma>()
        frontiera = b.basso
        for (c in colonna.filter { it.cy > b.cy && it.alto >= b.alto - 0.15f * h }.sortedBy { it.alto }) {
            if (c.alto > frontiera + 0.8f * h) break
            sotto += c
            if (c.basso > frontiera) frontiera = c.basso
        }
        // Simboli veri sopra e sotto: i puntini del diviso non fanno una frazione.
        if (sopra.none { !it.piccolo(h) } || sotto.none { !it.piccolo(h) }) return null
        return Candidata(b.larghezza, (sopra + sotto + b).toSet()) { p ->
            Frazione(b, analizza(sopra, h, p + 1), analizza(sotto, h, p + 1))
        }
    }

    /** Punta (dove la salita incontra la barra), cima e fine della barra se e' nello stesso tratto. */
    internal class SegnoRadice(val xPunta: Float, val yCima: Float, val fineBarra: Float?)

    /**
     * Un tratto a forma di radice: parte a meta' altezza o piu' in basso, scende un poco (il
     * trattino corto a sinistra), risale fino in cima verso destra e poi, se c'e', va a destra
     * restando in alto (la barra). Il V e le cifre partono dall'alto o non risalgono alla fine.
     */
    internal fun segnoRadice(f: Forma, h: Float): SegnoRadice? {
        val n = f.quanti
        if (n < 3 || f.altezza < 0.6f * h) return null
        val alt = f.altezza
        var iBasso = 0
        for (i in 1 until n) if (f.y(i) > f.y(iBasso)) iBasso = i
        if (iBasso == 0 || f.y(0) < f.alto + 0.25f * alt || f.y(iBasso) - f.y(0) < 0.15f * alt) return null
        if (f.x(iBasso) - f.sx > 0.65f * f.larghezza) return null
        // La punta: il primo punto che arriva in cima (la barra puo' essere un po' inclinata).
        val iCima = (iBasso + 1 until n).firstOrNull { f.y(it) <= f.alto + 0.15f * alt } ?: return null
        if (f.x(iCima) <= f.x(iBasso)) return null
        // Dopo la cima: la barra (resta in alto e va a destra) oppure una coda corta.
        var coda = 0f
        var inAlto = true
        for (i in iCima + 1 until n) {
            coda += kotlin.math.hypot(f.x(i) - f.x(i - 1), f.y(i) - f.y(i - 1))
            if (f.y(i) > f.alto + 0.3f * alt) inAlto = false
        }
        val barra = inAlto && f.x(n - 1) - f.x(iCima) >= 0.4f * h
        if (!barra && coda > 0.3f * alt) return null
        return SegnoRadice(f.x(iCima), f.alto, if (barra) f.dx else null)
    }

    private fun radice(f: Forma, libere: List<Forma>, h: Float): Candidata? {
        val s = segnoRadice(f, h) ?: return null
        var barra: Forma? = null
        val fine = s.fineBarra ?: run {
            barra = libere
                .filter { it !== f && it.orizzontale && abs(it.sx - s.xPunta) <= 0.4f * h && abs(it.cy - s.yCima) <= 0.4f * h && it.dx > s.xPunta + 0.3f * h }
                .maxByOrNull { it.larghezza }
            barra?.dx ?: return null
        }
        val segno = listOfNotNull(f, barra)
        val contenuto = libere.filter {
            it !== f && it !== barra && it.cx > s.xPunta && it.cx < fine + 0.1f * h &&
                it.alto >= s.yCima - 0.25f * h && it.basso <= f.basso + 0.4f * h
        }
        if (contenuto.isEmpty()) return null
        return Candidata(fine - f.sx, (segno + contenuto).toSet()) { p -> Radice(segno, analizza(contenuto, h, p + 1)) }
    }

    // --- Simboli -------------------------------------------------------------------------------

    internal fun raggruppa(libere: List<Forma>, h: Float): List<Gruppo> {
        // Una radice senza barra resta un simbolo a se': il radicando le sta attaccato.
        val radici = libere.filter { segnoRadice(it, h) != null }
        val resto = libere.filter { it !in radici }
        val padre = IntArray(resto.size) { it }
        fun radice(i: Int): Int {
            var r = i
            while (padre[r] != r) r = padre[r]
            padre[i] = r
            return r
        }
        for (i in resto.indices) for (j in i + 1 until resto.size) {
            if (stessoSimbolo(resto[i], resto[j], h)) padre[radice(i)] = radice(j)
        }
        val gruppi = resto.indices.groupBy { radice(it) }.values.map { indici -> indici.map { resto[it] } }
        val asse = mediana(gruppi.filter { g -> g.maxOf { it.altezza } >= 0.5f * h }.map { g -> unione(g).cy })
        return gruppi.map { Gruppo(it, speciale(it, h, if (asse > 0f) asse else Float.NaN)) } +
            radici.map { Gruppo(listOf(it), "√") }
    }

    /** Due tratti dello stesso simbolo: si sovrappongono in orizzontale e sono vicini in verticale. */
    private fun stessoSimbolo(a: Forma, b: Forma, h: Float): Boolean {
        val minimo = minOf(maxOf(a.larghezza, 0.15f * h), maxOf(b.larghezza, 0.15f * h))
        val (stretto, largo) = if (a.larghezza <= b.larghezza) a to b else b to a
        // Un puntino dentro la larghezza di un trattino (il diviso) conta come sovrapposto.
        val dentro = stretto.sx >= largo.sx - 0.05f * h && stretto.dx <= largo.dx + 0.05f * h
        if (sovrapposizioneX(a, b) < 0.45f * minimo && !dentro) return false
        if (distanzaY(a, b) > 0.45f * h) return false
        // Un esponente scritto stretto non si attacca alla base.
        val (grande, piccolo) = if (a.altezza >= b.altezza) a to b else b to a
        val apice = piccolo.sx >= grande.cx && piccolo.cy <= grande.alto + 0.3f * grande.altezza &&
            piccolo.altezza <= 0.85f * grande.altezza && !piccolo.piatto(h)
        return !apice
    }

    /** Simboli che si riconoscono dalla forma meglio che con ML Kit. */
    private fun speciale(forme: List<Forma>, h: Float, asse: Float): String? {
        // Diviso: un trattino con un puntino sopra e uno sotto.
        if (forme.size == 3) {
            val barra = forme.firstOrNull { it.orizzontale && !it.piccolo(h) }
            val punti = forme.filter { it !== barra && it.piccolo(h) }
            if (barra != null && punti.size == 2 && punti.any { it.cy < barra.cy } && punti.any { it.cy > barra.cy }) return "/"
        }
        // Per: un puntino a meta' altezza (quello in basso e' la virgola dei decimali).
        if (forme.size == 1 && !asse.isNaN()) {
            val f = forme[0]
            if (maxOf(f.larghezza, f.altezza) <= 0.2f * h && abs(f.cy - asse) <= 0.2f * h) return "*"
        }
        return null
    }

    // --- Esponenti -----------------------------------------------------------------------------

    private fun apici(elementi: List<Elemento>, h: Float, profondita: Int): Riga {
        val voci = ArrayList<Voce>()
        var i = 0
        while (i < elementi.size) {
            val e = elementi[i++]
            val esponente = ArrayList<Elemento>()
            if (e is Gruppo && e.speciale == null && e.altezza >= 0.45f * h && e.altezza >= 0.3f * e.larghezza) {
                // Una parentesi sporge sopra e sotto le cifre (nel corsivo anche di meta'): si
                // misura sulla sua parte centrale, e un esponente deve essere piu' piccolo delle
                // cifre della riga, se no le cifre dopo "(" sembrano esponenti.
                val parentesi = e.forme.singleOrNull()?.parentesi == true
                val alto = if (parentesi) e.alto + 0.1f * e.altezza else e.alto
                val altezza = if (parentesi) 0.8f * e.altezza else e.altezza
                val massima = 0.85f * (if (parentesi) minOf(altezza, h) else altezza)
                var ultimo: Ingombro = e
                while (i < elementi.size) {
                    val c = elementi[i]
                    val alzato = c.cy <= alto + 0.3f * altezza && c.basso <= alto + 0.6f * altezza &&
                        c.altezza <= massima && c.sx >= e.sx + 0.4f * e.larghezza && c.sx - ultimo.dx <= 0.9f * h
                    if (!alzato) break
                    esponente += c
                    ultimo = c
                    i++
                }
            }
            val riga = if (esponente.isEmpty()) null
            else analizza(esponente.flatMap { it.forme }, 0.6f * h, minOf(profondita + 1, PROFONDITA_MAX))
            voci += Voce(e, riga)
        }
        return Riga(voci, h)
    }
}
