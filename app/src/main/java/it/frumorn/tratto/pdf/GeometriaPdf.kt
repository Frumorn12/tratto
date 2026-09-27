package it.frumorn.tratto.pdf

import it.frumorn.tratto.data.Foglio
import it.frumorn.tratto.data.Penna
import it.frumorn.tratto.data.Sfondo
import it.frumorn.tratto.data.Tratto
import it.frumorn.tratto.editor.FoglioView
import it.frumorn.tratto.ink.Pennelli
import kotlin.math.roundToInt

/**
 * La parte "pura" dell'esportazione: conti su coordinate, stili e contorni, senza Android ne' PdfBox,
 * cosi' si prova con i test JVM.
 */

/** Pagina A4 in punti PDF (1 punto = 1/72 di pollice). */
const val A4_LARGHEZZA_PT = 595.28f
const val A4_ALTEZZA_PT = 841.89f

/** Colore di righe, quadretti e puntini (lo stesso dell'editor). */
const val COLORE_SFONDO = 0xD9D6EC

/** Spessore delle righe dello sfondo nel PDF, in punti. */
const val SPESSORE_RIGHE_PT = 0.5f

/** Raggio dei puntini dello sfondo, in unita' di pagina. */
const val RAGGIO_PUNTINI = 1.5f

/** Scarto massimo, in punti, quando si tolgono i vertici superflui dei contorni (0,05 pt = 0,02 mm). */
const val TOLLERANZA_PT = 0.05f

/**
 * Trasformazione affine dalle unita' di pagina di Tratto (x a destra, y in basso, origine in alto a
 * sinistra, pagina larga 1000) ai punti PDF (y in alto, origine in basso a sinistra):
 *
 *   x' = a*x + c*y + e
 *   y' = b*x + d*y + f
 *
 * cioe' la stessa matrice [a b c d e f] dell'operatore `cm`.
 */
class Trasformazione(
    val a: Float, val b: Float,
    val c: Float, val d: Float,
    val e: Float, val f: Float,
) {
    fun x(u: Float, v: Float): Float = a * u + c * v + e
    fun y(u: Float, v: Float): Float = b * u + d * v + f

    /** Punti PDF per unita' di pagina. */
    val scala: Float get() = kotlin.math.sqrt(a * a + b * b)

    companion object {
        /** Riporta /Rotate a 0, 90, 180 o 270 (i valori non multipli di 90 non sono validi: valgono 0). */
        fun normalizzaRotazione(gradi: Int): Int {
            val r = ((gradi % 360) + 360) % 360
            return if (r % 90 == 0) r else 0
        }

        /**
         * Trasformazione per una pagina PDF con il riquadro visibile (CropBox) [llx, lly, urx, ury] e la
         * rotazione [rotazione] (/Rotate, in senso orario). La pagina di Tratto corrisponde alla pagina
         * come la mostra un lettore, cioe' gia' ritagliata e ruotata: e' larga 1000 unita', quindi la
         * scala e' larghezzaVisibile / 1000.
         */
        fun perPagina(llx: Float, lly: Float, urx: Float, ury: Float, rotazione: Int = 0): Trasformazione {
            val r = normalizzaRotazione(rotazione)
            val larghezza = if (r == 90 || r == 270) ury - lly else urx - llx
            val s = larghezza / Foglio.LARGHEZZA
            return when (r) {
                // L'angolo in alto a sinistra mostrato e' (llx, lly): destra = +y, giu' = +x.
                90 -> Trasformazione(0f, s, s, 0f, llx, lly)
                // In alto a sinistra c'e' (urx, lly): destra = -x, giu' = +y.
                180 -> Trasformazione(-s, 0f, 0f, s, urx, lly)
                // In alto a sinistra c'e' (urx, ury): destra = -y, giu' = -x.
                270 -> Trasformazione(0f, -s, -s, 0f, urx, ury)
                else -> Trasformazione(s, 0f, 0f, -s, llx, ury)
            }
        }

        /** Larghezza e altezza della pagina come la mostra un lettore (con /Rotate applicato). */
        fun dimensioniVisibili(larghezza: Float, altezza: Float, rotazione: Int): Pair<Float, Float> {
            val r = normalizzaRotazione(rotazione)
            return if (r == 90 || r == 270) altezza to larghezza else larghezza to altezza
        }
    }
}

/**
 * Come si riempie un tratto nel PDF.
 *
 * @param rgb colore senza alfa (0xRRGGBB)
 * @param alfa opacita' 0..255
 * @param moltiplica fusione "Multiply", per l'evidenziatore: il testo sotto resta nero
 */
data class StilePdf(val rgb: Int, val alfa: Int, val moltiplica: Boolean) {
    /**
     * Si possono unire piu' tratti in un solo tracciato solo se il risultato non cambia, cioe' se il
     * colore e' pieno: con la trasparenza le sovrapposizioni tra tratti diversi devono sommarsi, come
     * a schermo.
     */
    val unibile: Boolean get() = alfa >= 255 && !moltiplica
}

/** Stile di un tratto: colore effettivo della penna e, per la matita, l'opacita' media. */
fun stilePdf(t: Tratto): StilePdf {
    val argb = Pennelli.coloreEffettivo(t.penna, t.colore)
    var alfa = argb ushr 24
    if (t.penna == Penna.MATITA) alfa = quantizzaAlfa(alfa * opacitaMatita(t))
    return StilePdf(argb and 0xFFFFFF, alfa, t.penna == Penna.EVIDENZIATORE)
}

/** Alfa a gradini del 5%: meno stati grafici diversi nel PDF, differenza invisibile. */
fun quantizzaAlfa(alfa: Float): Int {
    if (alfa >= 254.5f) return 255
    val gradini = (alfa / 255f * 20f).roundToInt().coerceIn(1, 20)
    return (gradini * 255f / 20f).roundToInt()
}

/**
 * Opacita' media di un tratto di matita. Il PDF non ha l'opacita' per vertice di Ink, quindi si rifanno
 * a mano i due comportamenti del pennello "matita" di [Pennelli]: pressione 0..1 -> opacita' 0,35..1 e
 * inclinazione 0,35..1,2 rad -> opacita' 1..0,55. Se si cambiano la', vanno cambiati anche qui.
 */
fun opacitaMatita(t: Tratto): Float {
    val n = t.quanti
    if (n == 0) return 1f
    val p = t.punti
    var somma = 0.0
    for (i in 0 until n) {
        val o = i * Tratto.CAMPI
        var op = 0.35f + 0.65f * p[o + 2].coerceIn(0f, 1f)
        val inclinazione = p[o + 3]
        if (inclinazione >= 0f) {
            val k = ((inclinazione - 0.35f) / (1.2f - 0.35f)).coerceIn(0f, 1f)
            op *= 1f - 0.45f * k
        }
        somma += op
    }
    return (somma / n).toFloat()
}

/**
 * Divide i tratti in gruppi di tratti consecutivi con lo stesso stile: ogni gruppo imposta colore e
 * trasparenza una volta sola. L'ordine resta quello di disegno.
 */
fun raggruppa(stili: List<StilePdf>): List<IntRange> {
    val gruppi = ArrayList<IntRange>()
    var inizio = 0
    for (i in 1..stili.size) {
        if (i == stili.size || stili[i] != stili[inizio]) {
            if (i > inizio) gruppi += inizio until i
            inizio = i
        }
    }
    return gruppi
}

/** Arrotonda al centesimo: nel PDF bastano 0,01 punti e i numeri corti fanno file piu' piccoli. */
fun arrotonda(v: Float): Float = Math.round(v * 100f) / 100f

/** [v] in centesimi, arrotondato. */
fun centesimi(v: Float): Long = Math.round(v * 100.0)

/**
 * Scrive un numero dato in centesimi senza zeri inutili: 84189 -> "841.89", 8400 -> "84", -5 -> "-0.05".
 * PdfBox scrive i float con 5 decimali e l'errore del float si vede (841.89 diventa 841.89001).
 */
fun StringBuilder.numeroPdf(centesimi: Long): StringBuilder {
    var c = centesimi
    if (c < 0) { append('-'); c = -c }
    append(c / 100)
    val f = (c % 100).toInt()
    if (f != 0) {
        append('.').append(f / 10)
        if (f % 10 != 0) append(f % 10)
    }
    return this
}

/**
 * Toglie i vertici di un contorno chiuso ([x0, y0, x1, y1, ...]) che si scostano meno di [tolleranza]
 * dalla forma (Ramer-Douglas-Peucker). I contorni di Ink hanno un vertice per lato a ogni campione
 * della penna: sui tratti lenti molti sono quasi allineati.
 */
fun semplifica(xy: FloatArray, tolleranza: Float): FloatArray {
    val n = xy.size / 2
    if (n <= 4 || tolleranza <= 0f) return xy
    // Si spezza il contorno tra il primo vertice e quello piu' lontano da lui.
    var lontano = 0
    var massimo = 0f
    for (i in 1 until n) {
        val dx = xy[2 * i] - xy[0]
        val dy = xy[2 * i + 1] - xy[1]
        val d = dx * dx + dy * dy
        if (d > massimo) { massimo = d; lontano = i }
    }
    if (lontano == 0) return xy
    val tieni = BooleanArray(n + 1)
    tieni[0] = true; tieni[lontano] = true; tieni[n] = true
    val t2 = tolleranza * tolleranza
    // Pila esplicita: niente ricorsione, i contorni possono avere migliaia di vertici.
    val pila = ArrayList<Long>()
    pila += (0L shl 32) or lontano.toLong()
    pila += (lontano.toLong() shl 32) or n.toLong()
    while (pila.isNotEmpty()) {
        val coppia = pila.removeAt(pila.lastIndex)
        val da = (coppia ushr 32).toInt()
        val a = (coppia and 0xFFFFFFFFL).toInt()
        if (a - da < 2) continue
        val ax = xy[2 * da]; val ay = xy[2 * da + 1]
        val bx = xy[2 * (a % n)]; val by = xy[2 * (a % n) + 1]
        var peggiore = -1
        var dMax = t2
        for (i in da + 1 until a) {
            val d = distanza2(xy[2 * i], xy[2 * i + 1], ax, ay, bx, by)
            if (d > dMax) { dMax = d; peggiore = i }
        }
        if (peggiore >= 0) {
            tieni[peggiore] = true
            pila += (da.toLong() shl 32) or peggiore.toLong()
            pila += (peggiore.toLong() shl 32) or a.toLong()
        }
    }
    var quanti = 0
    for (i in 0 until n) if (tieni[i]) quanti++
    if (quanti < 3) return xy
    val out = FloatArray(quanti * 2)
    var k = 0
    for (i in 0 until n) if (tieni[i]) { out[k++] = xy[2 * i]; out[k++] = xy[2 * i + 1] }
    return out
}

/**
 * Area con segno di un contorno chiuso (formula dell'area di Gauss): il segno dice il verso di
 * percorrenza. Per un contorno di Ink, che gira attorno al nastro del tratto, e' l'area del nastro.
 */
fun areaConSegno(xy: FloatArray): Double {
    val n = xy.size / 2
    var somma = 0.0
    for (i in 0 until n) {
        val j = if (i + 1 == n) 0 else i + 1
        somma += xy[2 * i].toDouble() * xy[2 * j + 1] - xy[2 * j].toDouble() * xy[2 * i + 1]
    }
    return somma / 2
}

/** Quadrato della distanza del punto (px, py) dal segmento (ax, ay)-(bx, by). */
private fun distanza2(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
    val vx = bx - ax; val vy = by - ay
    val l2 = vx * vx + vy * vy
    var t = if (l2 > 0f) ((px - ax) * vx + (py - ay) * vy) / l2 else 0f
    t = t.coerceIn(0f, 1f)
    val dx = px - (ax + t * vx); val dy = py - (ay + t * vy)
    return dx * dx + dy * dy
}

/**
 * Le linee dello sfondo in unita' di pagina, [x0, y0, x1, y1] per ogni linea, calcolate come le
 * disegna [FoglioView]. Vuoto per BIANCO e PUNTINI.
 */
fun lineeSfondo(sfondo: Sfondo, altezza: Float): FloatArray {
    val l = ArrayList<Float>()
    val larghezza = FoglioView.LARGHEZZA
    when (sfondo) {
        Sfondo.RIGHE -> {
            var y = FoglioView.RIGHE_INIZIO
            while (y < altezza - 40f) { l += 60f; l += y; l += larghezza - 60f; l += y; y += FoglioView.PASSO_RIGHE }
        }
        Sfondo.QUADRETTI -> {
            var x = FoglioView.PASSO_QUADRETTI
            while (x < larghezza) { l += x; l += 0f; l += x; l += altezza; x += FoglioView.PASSO_QUADRETTI }
            var y = FoglioView.PASSO_QUADRETTI
            while (y < altezza) { l += 0f; l += y; l += larghezza; l += y; y += FoglioView.PASSO_QUADRETTI }
        }
        else -> Unit
    }
    return l.toFloatArray()
}

/** I centri dei puntini dello sfondo PUNTINI in unita' di pagina, [x, y] per ogni puntino. */
fun puntiniSfondo(altezza: Float): FloatArray {
    val l = ArrayList<Float>()
    var y = FoglioView.PASSO_QUADRETTI
    while (y < altezza) {
        var x = FoglioView.PASSO_QUADRETTI
        while (x < FoglioView.LARGHEZZA) { l += x; l += y; x += FoglioView.PASSO_QUADRETTI }
        y += FoglioView.PASSO_QUADRETTI
    }
    return l.toFloatArray()
}
