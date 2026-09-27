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
 * costare poco. Due trattini quasi orizzontali, lunghi uguali (entro il doppio), uno sopra
 * l'altro e vicini; oppure un tratto solo a forma di zeta schiacciata (barra, diagonale
 * all'indietro, barra), come quando si scrive l'uguale senza staccare la penna.
 */
internal object RilevaUguale {
    /** Tempo massimo tra la fine del primo trattino e la fine del secondo. */
    const val FINESTRA_MS = 2500L

    /** Larghezza ammessa per un uguale in unita' di pagina (1 unita' e' circa 0,21 mm). */
    private const val MINIMA = 5f
    private const val MASSIMA = 160f

    fun dueTratti(a: Forma, b: Forma): Boolean {
        if (!a.orizzontale || !b.orizzontale) return false
        val wa = a.larghezza
        val wb = b.larghezza
        if (min(wa, wb) < MINIMA || max(wa, wb) > MASSIMA) return false
        if (min(wa, wb) < 0.5f * max(wa, wb)) return false
        if (sovrapposizioneX(a, b) < 0.5f * min(wa, wb)) return false
        val w = (wa + wb) / 2f
        val d = abs(a.cy - b.cy)
        // Le barre non si toccano (anche se un po' inclinate) e restano vicine.
        return d >= max(0.12f * w, 0.3f * (a.altezza + b.altezza)) && d <= 0.9f * w
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
