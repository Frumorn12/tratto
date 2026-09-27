package it.frumorn.tratto.calcolo

import it.frumorn.tratto.data.Tratto
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Un segno "=" scritto a mano: due trattini (o un solo tratto a zeta). */
internal class Uguale(val forme: List<Forma>) : Ingombro {
    private val r = unione(forme)
    override val sx: Float get() = r.sx
    override val alto: Float get() = r.alto
    override val dx: Float get() = r.dx
    override val basso: Float get() = r.basso

    /** L'asse della riga di matematica: passa tra le due barre, all'altezza del meno e delle barre di frazione. */
    val asse: Float get() = cy

    /** L'ultimo tratto dell'uguale: da li' si prendono colore, penna e spessore del risultato. */
    val ultimo: Tratto get() = forme.last().tratto
}

/**
 * Riconoscimento geometrico del segno "=", senza ML Kit: si fa a ogni tratto, quindi deve
 * costare poco. Due trattini quasi orizzontali, lunghi simili (entro due volte e mezza), uno sopra
 * l'altro e vicini; oppure un tratto solo a forma di zeta schiacciata (barra, diagonale
 * all'indietro, barra), come quando si scrive l'uguale senza staccare la penna.
 */
internal object RilevaUguale {
    /** Tempo massimo tra la fine del primo trattino e la fine del secondo. */
    const val FINESTRA_MS = 2500L

    /** Larghezza ammessa per un uguale in unita' di pagina (1 unita' e' circa 0,21 mm). */
    private const val MINIMA = 5f
    private const val MASSIMA = 160f

    /** Distanza massima tra le due barre: poco meno di un rigo del foglio a righe (38 unita'). */
    private const val DISTANZA_MASSIMA = 34f

    /**
     * [hRiga] e' l'altezza delle cifre scritte subito a sinistra, se ce ne sono: un uguale
     * piccolo dopo cifre grandi puo' avere le barre lontane piu' di quanto sono lunghe.
     */
    fun dueTratti(a: Forma, b: Forma, hRiga: Float = 0f): Boolean {
        // Ognuna e' un trattino quasi orizzontale; non serve che siano parallele: scritte di corsa
        // una puo' salire e l'altra scendere (sul tablet -36 e +22 gradi).
        if (!barra(a) || !barra(b)) return false
        val wa = a.larghezza
        val wb = b.larghezza
        if (min(wa, wb) < MINIMA || max(wa, wb) > MASSIMA) return false
        if (min(wa, wb) < 0.4f * max(wa, wb)) return false
        // Scritte di corsa le barre possono essere sfalsate (sul tablet la seconda 8 unita' piu'
        // a destra, con barre di 7 e 13): basta che stiano una sopra l'altra piu' o meno al centro.
        if (sovrapposizioneX(a, b) < 0.5f * min(wa, wb) && abs(a.cx - b.cx) > 0.6f * max(wa, wb) + 2f) return false
        val w = (wa + wb) / 2f
        val d = abs(a.cy - b.cy)
        // Le barre non si toccano (anche se un po' inclinate) e restano vicine. Scritte di corsa
        // possono stare lontane quanto sono lunghe e anche di piu' (sul tablet 18 e 22 unita' con
        // barre di 15 e 20, o 13 con barre di 8 dopo cifre alte 35): fino a una volta e mezza la
        // larghezza o a tre quarti di cifra (sul tablet 18,5 con cifre alte 27), ma mai piu' di un rigo.
        val massima = min(max(1.5f * w, 0.75f * hRiga), DISTANZA_MASSIMA)
        return d >= max(0.12f * w, 0.3f * (min(a.altezza, b.altezza) + 1f)) && d <= massima
    }

    /**
     * Una barra dell'uguale: un trattino orizzontale, o scritto di corsa un segmento dritto
     * inclinato fino a circa 30 gradi (sul tablet ne sono arrivati a 25).
     */
    fun barra(f: Forma): Boolean {
        if (f.orizzontale) return true
        val w = f.larghezza
        if (w < MINIMA || f.altezza > 0.8f * w + 2f) return false
        // Si guarda la parte centrale del percorso, senza il primo e l'ultimo 15%: una lineetta
        // piccola scritta di corsa ha spesso un uncino dove la penna si stacca (sul tablet: 12
        // unita' di larghezza con 18 di percorso). Dritta e inclinata al massimo di 35 gradi.
        val n = f.quanti
        if (n < 2) return false
        var totale = 0f
        for (i in 1 until n) totale += hypot(f.x(i) - f.x(i - 1), f.y(i) - f.y(i - 1))
        var percorso = 0f
        var a = 0
        var b = n - 1
        for (i in 1 until n) {
            percorso += hypot(f.x(i) - f.x(i - 1), f.y(i) - f.y(i - 1))
            if (a == 0 && percorso >= 0.15f * totale) a = i - 1
            if (percorso <= 0.85f * totale) b = i
        }
        if (b <= a) return false
        var centro = 0f
        for (i in a + 1..b) centro += hypot(f.x(i) - f.x(i - 1), f.y(i) - f.y(i - 1))
        val dx = abs(f.x(b) - f.x(a))
        val dy = abs(f.y(b) - f.y(a))
        return dx >= 0.5f * w && dy <= 0.7f * dx + 1f && centro <= 1.3f * hypot(dx, dy) + 2f
    }

    /**
     * Due trattini piccoli uno sopra l'altro che la sola forma non basta a dire uguale (storti,
     * con uncini, uno piu' corto): candidati da far leggere a ML Kit. [hRiga] e' l'altezza delle
     * cifre vicine.
     */
    fun forseUguale(a: Forma, b: Forma, hRiga: Float): Boolean {
        val h = maxOf(hRiga, 12f)
        fun piccolo(f: Forma) = maxOf(f.larghezza, f.altezza) in 3f..1.2f * h && f.altezza <= 1.2f * f.larghezza + 3f
        if (!piccolo(a) || !piccolo(b)) return false
        val d = abs(a.cy - b.cy)
        return abs(a.cx - b.cx) <= 0.8f * max(a.larghezza, b.larghezza) + 3f && d >= 2f && d <= 0.9f * h &&
            distanzaY(a, b) >= 0f
    }


    fun unTratto(f: Forma): Boolean {
        val w = f.larghezza
        val h = f.altezza
        if (w < MINIMA || w > MASSIMA || h < 0.15f * w || h > 0.8f * w) return false
        if (f.lunghezza > 3.4f * w + 2f * h) return false
        return zeta(f, semplifica(f, 0.12f * w)) || zeta(f, semplifica(f, 0.2f * w))
    }

    /** Barra verso destra in alto, diagonale verso sinistra in basso, barra verso destra in basso. */
    private fun zeta(f: Forma, v: List<Int>): Boolean {
        if (v.size != 4) return false
        val w = f.larghezza
        val h = f.altezza
        val x = v.map { f.x(it) }
        val y = v.map { f.y(it) }
        fun barra(a: Int, b: Int): Boolean {
            val dx = x[b] - x[a]
            return dx >= 0.5f * w && abs(y[b] - y[a]) <= 0.35f * dx
        }
        return barra(0, 1) && barra(2, 3) &&
            x[2] - x[1] <= -0.25f * w && y[2] - y[1] >= 0.5f * h &&
            max(y[0], y[1]) <= f.alto + 0.35f * h && min(y[2], y[3]) >= f.basso - 0.35f * h
    }

    /**
     * Vero se nessun altro tratto passa in mezzo all'uguale (un tratto che lo taglia ne fa un
     * altro segno, come il diverso; un tratto tra le barre vuol dire che non sono un uguale).
     */
    fun libero(u: Uguale, altri: List<Forma>): Boolean {
        val margine = 0.2f * u.larghezza
        val sx = u.sx + margine
        val dx = u.dx - margine
        for (f in altri) {
            if (u.forme.any { it.id == f.id }) continue
            if (f.dx < sx || f.sx > dx || f.basso < u.alto || f.alto > u.basso) continue
            for (i in 0 until f.quanti) {
                val px = f.x(i)
                val py = f.y(i)
                if (px in sx..dx && py in u.alto..u.basso) return false
            }
        }
        return true
    }

    /** Ramer-Douglas-Peucker: indici dei vertici della polilinea semplificata con tolleranza [eps]. */
    internal fun semplifica(f: Forma, eps: Float): List<Int> {
        val n = f.quanti
        if (n < 2) return (0 until n).toList()
        val tieni = BooleanArray(n)
        tieni[0] = true
        tieni[n - 1] = true
        val pila = ArrayDeque<Pair<Int, Int>>()
        pila.addLast(0 to n - 1)
        while (pila.isNotEmpty()) {
            val (a, b) = pila.removeLast()
            if (b - a < 2) continue
            val ax = f.x(a); val ay = f.y(a)
            val bx = f.x(b); val by = f.y(b)
            val l = hypot(bx - ax, by - ay)
            var peggiore = -1
            var distanza = eps
            for (i in a + 1 until b) {
                val d = if (l < 1e-3f) hypot(f.x(i) - ax, f.y(i) - ay)
                else abs((bx - ax) * (ay - f.y(i)) - (ax - f.x(i)) * (by - ay)) / l
                if (d > distanza) { distanza = d; peggiore = i }
            }
            if (peggiore >= 0) {
                tieni[peggiore] = true
                pila.addLast(a to peggiore)
                pila.addLast(peggiore to b)
            }
        }
        return (0 until n).filter { tieni[it] }
    }
}
