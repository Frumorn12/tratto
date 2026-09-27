package it.frumorn.tratto.calcolo

import it.frumorn.tratto.data.Tratto
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Un rettangolo allineato agli assi, in unita' di pagina (y verso il basso). */
internal interface Ingombro {
    val sx: Float
    val alto: Float
    val dx: Float
    val basso: Float
}

internal val Ingombro.larghezza: Float get() = dx - sx
internal val Ingombro.altezza: Float get() = basso - alto
internal val Ingombro.cx: Float get() = (sx + dx) / 2f
internal val Ingombro.cy: Float get() = (alto + basso) / 2f

/** Quanto si sovrappongono in orizzontale (negativo: la distanza tra i due). */
internal fun sovrapposizioneX(a: Ingombro, b: Ingombro): Float = min(a.dx, b.dx) - max(a.sx, b.sx)

/** Distanza verticale tra i due ingombri, 0 se si sovrappongono. */
internal fun distanzaY(a: Ingombro, b: Ingombro): Float = max(0f, max(a.alto, b.alto) - min(a.basso, b.basso))

internal class Rettangolo(override val sx: Float, override val alto: Float, override val dx: Float, override val basso: Float) : Ingombro

internal fun unione(scatole: Collection<Ingombro>): Rettangolo = Rettangolo(
    scatole.minOf { it.sx }, scatole.minOf { it.alto }, scatole.maxOf { it.dx }, scatole.maxOf { it.basso },
)

internal fun mediana(valori: List<Float>): Float {
    if (valori.isEmpty()) return 0f
    val s = valori.sorted()
    val m = s.size / 2
    return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2f
}

/** Il valore in posizione [q] (0..1) tra i valori ordinati, senza interpolare; 0 se vuota. */
internal fun quantile(valori: List<Float>, q: Float): Float {
    if (valori.isEmpty()) return 0f
    val s = valori.sorted()
    return s[Math.round(q * (s.size - 1)).coerceIn(0, s.size - 1)]
}

/**
 * Altezza tipica delle cifre dalle altezze dei tratti non piatti. Non la mediana: i simboli
 * bassi (x, +, il primo tratto del 4) la tirerebbero giu'; il 75% cade su cifre e parentesi.
 */
internal fun altezzaCifre(altezze: List<Float>): Float = quantile(altezze, 0.75f)

/**
 * Un tratto con le misure che servono a riconoscere la struttura di una formula: ingombro,
 * lunghezza del percorso e forma (trattino orizzontale, puntino).
 */
internal class Forma(val tratto: Tratto) : Ingombro {
    override val sx: Float
    override val alto: Float
    override val dx: Float
    override val basso: Float

    /** Lunghezza del percorso della penna. */
    val lunghezza: Float

    init {
        val p = tratto.punti
        var sx = Float.MAX_VALUE; var sy = Float.MAX_VALUE
        var dx = -Float.MAX_VALUE; var dy = -Float.MAX_VALUE
        var l = 0f
        for (i in 0 until tratto.quanti) {
            val o = i * Tratto.CAMPI
            val x = p[o]; val y = p[o + 1]
            if (x < sx) sx = x
            if (x > dx) dx = x
            if (y < sy) sy = y
            if (y > dy) dy = y
            if (i > 0) l += hypot(x - p[o - Tratto.CAMPI], y - p[o + 1 - Tratto.CAMPI])
        }
        if (tratto.quanti == 0) { sx = 0f; sy = 0f; dx = 0f; dy = 0f }
        this.sx = sx; alto = sy; this.dx = dx; basso = dy
        lunghezza = l
    }

    val id: Long get() = tratto.id
    val quanti: Int get() = tratto.quanti
    fun x(i: Int): Float = tratto.punti[i * Tratto.CAMPI]
    fun y(i: Int): Float = tratto.punti[i * Tratto.CAMPI + 1]

    /**
     * Un trattino dritto e quasi orizzontale: il meno, una barra di frazione, meta' di un uguale.
     * Si tollerano una pendenza di circa 15 gradi e gli uncini alle estremita' (la penna che
     * scivola appoggiandosi o staccandosi): la parte centrale, senza il primo e l'ultimo 12% del
     * percorso, deve essere dritta e piatta.
     */
    val orizzontale: Boolean by lazy {
        if (larghezza < 3f || altezza > 0.6f * larghezza + 2f) return@lazy false
        if (altezza <= 0.3f * larghezza + 1f && lunghezza <= 1.4f * larghezza + 2f) return@lazy true
        val n = quanti
        var percorso = 0f
        var inizio = -1
        var fine = -1
        for (i in 1 until n) {
            percorso += hypot(x(i) - x(i - 1), y(i) - y(i - 1))
            if (inizio < 0 && percorso >= 0.12f * lunghezza) inizio = i
            if (fine < 0 && percorso >= 0.88f * lunghezza) fine = i
        }
        if (inizio < 0 || fine <= inizio) return@lazy false
        var sx = Float.MAX_VALUE; var dx = -Float.MAX_VALUE
        var su = Float.MAX_VALUE; var giu = -Float.MAX_VALUE
        var l = 0f
        for (i in inizio..fine) {
            sx = min(sx, x(i)); dx = max(dx, x(i))
            su = min(su, y(i)); giu = max(giu, y(i))
            if (i > inizio) l += hypot(x(i) - x(i - 1), y(i) - y(i - 1))
        }
        val w = dx - sx
        w >= 0.6f * larghezza && giu - su <= 0.3f * w + 1f && l <= 1.3f * w + 2f
    }

    /**
     * Una parentesi tonda, anche inclinata: tratto stretto con le estremita' in cima e in fondo,
     * curvo tutto da una parte della corda tra le estremita' e con la pancia a meta' altezza.
     * L'1 e' dritto, il 7 si stacca dalla corda in cima, le altre cifre sono piu' larghe.
     */
    val parentesi: Boolean by lazy {
        val n = quanti
        val alt = altezza
        if (n < 3 || alt <= 0f || larghezza > 0.6f * alt) return@lazy false
        if (minOf(y(0), y(n - 1)) > alto + 0.25f * alt || maxOf(y(0), y(n - 1)) < basso - 0.25f * alt) return@lazy false
        val ax = x(0); val ay = y(0)
        val bx = x(n - 1); val by = y(n - 1)
        val corda = hypot(bx - ax, by - ay).coerceAtLeast(1e-3f)
        // Distanza con segno dalla corda: positiva da una parte, negativa dall'altra.
        fun d(i: Int) = ((bx - ax) * (y(i) - ay) - (by - ay) * (x(i) - ax)) / corda
        var su = 1
        var giu = 1
        for (i in 1 until n - 1) {
            if (d(i) > d(su)) su = i
            if (d(i) < d(giu)) giu = i
        }
        val (pancia, altroLato) = if (d(su) >= -d(giu)) su to -d(giu) else giu to d(su)
        abs(d(pancia)) >= 0.08f * alt && altroLato.coerceAtLeast(0f) <= 0.03f * alt &&
            y(pancia) in alto + 0.25f * alt..basso - 0.25f * alt
    }

    /** Piatto rispetto all'altezza tipica dei simboli [h]: meno, barre, puntini. */
    fun piatto(h: Float): Boolean = altezza < 0.3f * h

    /** Un puntino o un segnetto rispetto all'altezza tipica [h]. */
    fun piccolo(h: Float): Boolean = max(larghezza, altezza) <= 0.35f * h
}
