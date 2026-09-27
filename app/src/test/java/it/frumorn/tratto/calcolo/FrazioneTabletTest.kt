package it.frumorn.tratto.calcolo

import it.frumorn.tratto.data.Penna
import it.frumorn.tratto.data.Tratto
import it.frumorn.tratto.scrittura.BellaScrittura
import it.frumorn.tratto.scrittura.FontHershey
import it.frumorn.tratto.scrittura.Stile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.math.ceil
import kotlin.math.hypot

/**
 * Formule scritte come sul tablet vero con tools/penna.py: coordinate in pixel dello schermo
 * (scala ~1,116 px per unita' di pagina, pagina da x = 42), glifi del corsivo Hershey non uniti
 * (come li scrive penna.py), barre come tratti dritti.
 *
 * penna.py usa normalize_rendering(altezza) del pacchetto Hershey-Fonts: la fascia dalla cima
 * delle maiuscole a una linea "zero" diventa alta [altezza] pixel, e la linea zero va alla Y data
 * a penna.py. Non e' certo quale sia la linea zero, quindi ogni prova del tablet gira con tre
 * letture: la linea di base (cifre alte [altezza], 21 unita' del font), la linea di fondo del
 * font (28 unita': cifre alte 3/4 e 7 unita' piu' in alto della Y) o il fondo delle code del
 * corsivo (33 unita'). Con le ultime due il numeratore resta piu' staccato dalla barra (che e'
 * un tratto dritto, non spostato): e' il caso che sul tablet dava "I + 2".
 */
class FrazioneTabletTest {
    companion object {
        private lateinit var corsivo: FontHershey

        @BeforeClass
        @JvmStatic
        fun carica() {
            Quaderno.preparaFont()
            corsivo = FontHershey.leggi(File("src/completa/assets/${BellaScrittura.ASSET}").readBytes())[Stile.CORSIVO]!!
        }

        const val SCALA = 1.116f
        const val SINISTRA = 42f
        const val B = 600f
        val DIVISORI = listOf(21f, 28f, 33f)
    }

    private var adesso = 50_000L

    @Before
    fun pulisci() = Calcolatore.azzera()

    private fun ux(px: Float) = (px - SINISTRA) / SCALA
    private fun uy(px: Float) = px / SCALA

    /** Un tratto dai punti in pixel, un punto ogni ~3 px come penna.py. */
    private fun trattoPx(q: Quaderno, punti: List<Pair<Float, Float>>): Tratto {
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        xs += ux(punti[0].first); ys += uy(punti[0].second)
        for (k in 1 until punti.size) {
            val (ax, ay) = punti[k - 1]
            val (bx, by) = punti[k]
            val n = ceil(hypot(bx - ax, by - ay) / 3f).toInt().coerceAtLeast(1)
            for (j in 1..n) {
                xs += ux(ax + (bx - ax) * j / n)
                ys += uy(ay + (by - ay) * j / n)
            }
        }
        val p = FloatArray(xs.size * Tratto.CAMPI)
        for (i in xs.indices) {
            val o = i * Tratto.CAMPI
            p[o] = xs[i]; p[o + 1] = ys[i]; p[o + 2] = 0.6f; p[o + 3] = 0.4f; p[o + 4] = 1f; p[o + 5] = i * 8f
        }
        return Tratto(q.nuovoId(), Penna.PENNA, Quaderno.NERO, 3f, p)
    }

    /**
     * Come "penna.py scrivi X Y ALTEZZA c": la fascia dalla cima delle maiuscole alla linea zero
     * ([divisore] unita' del font) alta [altezzaPx], con la linea zero a [yPx]. Con [comePenna]
     * falso la linea zero e' sempre la linea di base (per le prove di scrittura a mano).
     */
    private fun glifo(
        q: Quaderno,
        c: Char,
        xPx: Float,
        yPx: Float,
        altezzaPx: Float,
        divisore: Float,
        sullaPagina: Boolean = true,
        comePenna: Boolean = true,
    ): List<Tratto> {
        val g = corsivo.glifo(c)!!
        val s = altezzaPx / divisore
        val zero = if (comePenna) corsivo.maiuscole + divisore else corsivo.base.toFloat()
        val tratti = g.tratti.map { t ->
            trattoPx(q, (0 until t.size / 2).map { i -> (xPx + (t[2 * i] - g.sinistra) * s) to (yPx + (t[2 * i + 1] - zero) * s) })
        }
        if (sullaPagina) q.registra(tratti, c.toString())
        return tratti
    }

    private fun barra(q: Quaderno, x0: Float, y0: Float, x1: Float, y1: Float, testo: String = "-"): Tratto =
        trattoPx(q, listOf(x0 to y0, x1 to y1)).also { q.registra(listOf(it), testo) }

    private fun chiudi(q: Quaderno, uguale: List<Tratto>, lettore: Lettore): Risultato? = runBlocking {
        var r: Risultato? = null
        for (t in uguale) {
            q.tratti += t
            adesso += 400
            r = Calcolatore.dopoTratto(q.tratti, listOf(t), Stile.CORSIVO, { q.nuovoId() }, lettore, adesso)
        }
        r
    }

    /** Il caso del tablet: 3/4 + 2² = , con il denominatore che sfiora l'asse dell'uguale. */
    @Test
    fun treQuartiPiuDueAlQuadrato() {
        val sbagliati = ArrayList<String>()
        for (d in DIVISORI) for (uncini in listOf(false, true)) {
            Calcolatore.azzera()
            val q = Quaderno(Stile.CORSIVO)
            glifo(q, '3', 132f, B - 26f, 36f, d)
            if (uncini) {
                // La penna scivola appoggiandosi e staccandosi: un uncino a ogni estremita'.
                val t = trattoPx(q, listOf(117f to B - 22f, 121f to B - 14f, 172f to B - 15f, 175f to B - 9f))
                q.registra(listOf(t), "-")
            } else {
                barra(q, 118f, B - 14f, 172f, B - 15f)
            }
            glifo(q, '4', 132f, B + 28f, 36f, d)
            glifo(q, '+', 200f, B + 6f, 36f, d)
            glifo(q, '2', 250f, B + 6f, 42f, d)
            glifo(q, '2', 280f, B - 24f, 24f, d)
            val uguale = glifo(q, '=', 330f, B + 6f, 36f, d, sullaPagina = false)
            val lettore = LettoreFinto(q)
            val r = chiudi(q, uguale, lettore)
            if (r?.valore != "4,75" || r.espressione != "3/4+2^2") sbagliati += "divisore $d uncini $uncini: ${r?.espressione} = ${r?.valore}, letti ${lettore.letti}"
        }
        assertTrue(sbagliati.joinToString("\n"), sbagliati.isEmpty())
    }

    /**
     * Varianti di scrittura vera: barra corta o spostata a sinistra rispetto alle cifre,
     * numeratore e denominatore di una cifra piu' o meno staccati, denominatore che scende
     * sull'asse o lo supera, frazione alta o bassa rispetto all'uguale.
     */
    @Test
    fun frazioniScritteAMano() {
        val sbagliati = ArrayList<String>()
        for (d in DIVISORI) for (sinistraBarra in listOf(118f, 126f, 134f)) for (lunghezza in listOf(34f, 44f, 54f))
            for (su in listOf(6f, 12f, 20f)) for (giu in listOf(2f, 8f, 16f)) for (spostamento in listOf(-8f, 0f, 8f)) {
                Calcolatore.azzera()
                val q = Quaderno(Stile.CORSIVO)
                val h = 36f * 21f / d
                val yBarra = B - 14f + spostamento
                glifo(q, '5', 132f, yBarra - su, 36f, d, comePenna = false)
                barra(q, sinistraBarra, yBarra, sinistraBarra + lunghezza, yBarra - 1f)
                glifo(q, '8', 132f, yBarra + giu + h, 36f, d, comePenna = false)
                // Il resto della riga comincia mezza cifra dopo la frazione (il "+" ha 4 unita' di
                // margine a sinistra nel font, le cifre 3).
                val s = 36f / d
                val fine = maxOf(sinistraBarra + lunghezza, 132f + 17f * s)
                val xPiu = fine + 0.5f * h - 4f * s
                glifo(q, '+', xPiu, B + 6f, 36f, d, comePenna = false)
                glifo(q, '1', xPiu + 26f * s, B + 6f, 36f, d, comePenna = false)
                val uguale = glifo(q, '=', xPiu + 50f * s, B + 6f, 36f, d, sullaPagina = false, comePenna = false)
                val lettore = LettoreFinto(q)
                val r = chiudi(q, uguale, lettore)
                if (r?.valore != "1,625") {
                    sbagliati += "d=$d barra=$sinistraBarra+$lunghezza su=$su giu=$giu sp=$spostamento: ${r?.espressione} = ${r?.valore} ${lettore.letti}"
                }
            }
        assertEquals(sbagliati.take(30).joinToString("\n"), 0, sbagliati.size)
    }
}
