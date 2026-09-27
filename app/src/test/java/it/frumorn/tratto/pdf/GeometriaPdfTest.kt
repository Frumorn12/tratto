package it.frumorn.tratto.pdf

import it.frumorn.tratto.data.Penna
import it.frumorn.tratto.data.Sfondo
import it.frumorn.tratto.data.Tratto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class GeometriaPdfTest {
    private val eps = 0.001f

    private fun assertPunto(t: Trasformazione, u: Float, v: Float, x: Float, y: Float) {
        assertEquals("x di ($u, $v)", x, t.x(u, v), eps)
        assertEquals("y di ($u, $v)", y, t.y(u, v), eps)
    }

    @Test
    fun a4SenzaRotazioneCapovolgeLaY() {
        val t = Trasformazione.perPagina(0f, 0f, A4_LARGHEZZA_PT, A4_ALTEZZA_PT)
        assertEquals(0.59528f, t.scala, 1e-5f)
        // In alto a sinistra in Tratto = in alto a sinistra nel PDF, che ha la y verso l'alto.
        assertPunto(t, 0f, 0f, 0f, A4_ALTEZZA_PT)
        assertPunto(t, 1000f, 0f, A4_LARGHEZZA_PT, A4_ALTEZZA_PT)
        assertPunto(t, 0f, 1414f, 0f, A4_ALTEZZA_PT - 1414f * 0.59528f)
        assertPunto(t, 500f, 707f, 297.64f, A4_ALTEZZA_PT - 707f * 0.59528f)
    }

    @Test
    fun cropBoxSpostatoSiSommaAllOrigine() {
        val t = Trasformazione.perPagina(10f, 20f, 610f, 820f)
        assertEquals(0.6f, t.scala, eps)
        assertPunto(t, 0f, 0f, 10f, 820f)
        assertPunto(t, 1000f, 0f, 610f, 820f)
        assertPunto(t, 1000f, 800f / 0.6f, 610f, 20f)
    }

    @Test
    fun rotazione90() {
        // Pagina verticale 600x800 ruotata di 90 gradi in senso orario: il lettore la mostra 800x600.
        val t = Trasformazione.perPagina(0f, 0f, 600f, 800f, 90)
        assertEquals(0.8f, t.scala, eps)
        val altezza = 600f / 0.8f
        assertPunto(t, 0f, 0f, 0f, 0f)            // in alto a sinistra: era l'angolo in basso a sinistra
        assertPunto(t, 1000f, 0f, 0f, 800f)       // in alto a destra: era l'angolo in alto a sinistra
        assertPunto(t, 0f, altezza, 600f, 0f)     // in basso a sinistra: era l'angolo in basso a destra
        assertPunto(t, 1000f, altezza, 600f, 800f)
        // Andare a destra sullo schermo = salire nel PDF; andare giu' = andare a destra.
        assertPunto(t, 100f, 200f, 160f, 80f)
    }

    @Test
    fun rotazione90ConCropBox() {
        val t = Trasformazione.perPagina(10f, 20f, 590f, 780f, 90)
        assertEquals(0.76f, t.scala, eps)
        assertPunto(t, 100f, 100f, 86f, 96f)
    }

    @Test
    fun rotazione180() {
        val t = Trasformazione.perPagina(0f, 0f, 600f, 800f, 180)
        assertEquals(0.6f, t.scala, eps)
        assertPunto(t, 0f, 0f, 600f, 0f)
        assertPunto(t, 1000f, 800f / 0.6f, 0f, 800f)
    }

    @Test
    fun rotazione270() {
        val t = Trasformazione.perPagina(0f, 0f, 600f, 800f, 270)
        assertEquals(0.8f, t.scala, eps)
        assertPunto(t, 0f, 0f, 600f, 800f)
        assertPunto(t, 1000f, 0f, 600f, 0f)
        assertPunto(t, 0f, 750f, 0f, 800f)
    }

    @Test
    fun rotazioniStraneSiNormalizzano() {
        assertEquals(270, Trasformazione.normalizzaRotazione(-90))
        assertEquals(90, Trasformazione.normalizzaRotazione(450))
        assertEquals(0, Trasformazione.normalizzaRotazione(360))
        assertEquals(0, Trasformazione.normalizzaRotazione(45))
        // -90 si comporta come 270.
        val a = Trasformazione.perPagina(0f, 0f, 600f, 800f, -90)
        assertPunto(a, 0f, 0f, 600f, 800f)
        assertEquals(800f to 600f, Trasformazione.dimensioniVisibili(600f, 800f, 90))
        assertEquals(600f to 800f, Trasformazione.dimensioniVisibili(600f, 800f, 180))
    }

    private val nero = StilePdf(0x000000, 255, false)
    private val blu = StilePdf(0x1565C0, 255, false)
    private val giallo = StilePdf(0xFFEB3B, 0x66, true)

    @Test
    fun raggruppaSoloTrattiConsecutiviUguali() {
        assertEquals(emptyList<IntRange>(), raggruppa(emptyList()))
        assertEquals(listOf(0..0), raggruppa(listOf(nero)))
        assertEquals(listOf(0..2), raggruppa(listOf(nero, nero, nero)))
        // Lo stesso stile non consecutivo resta separato: l'ordine di disegno conta.
        assertEquals(listOf(0..1, 2..2, 3..3, 4..5), raggruppa(listOf(nero, nero, blu, nero, giallo, giallo)))
    }

    @Test
    fun soloIColoriPieniSiUniscono() {
        assertTrue(nero.unibile)
        assertFalse(giallo.unibile)
        assertFalse(StilePdf(0, 128, false).unibile)
        assertFalse(StilePdf(0, 255, true).unibile)
    }

    private fun tratto(penna: Penna, colore: Int, vararg punti: Pair<Float, Float>): Tratto {
        // [x, y, pressione, inclinazione, orientamento, tempo]
        val a = FloatArray(punti.size * Tratto.CAMPI)
        punti.forEachIndexed { i, (pressione, inclinazione) ->
            val o = i * Tratto.CAMPI
            a[o] = i * 10f; a[o + 1] = 0f; a[o + 2] = pressione; a[o + 3] = inclinazione; a[o + 4] = -1f; a[o + 5] = i.toFloat()
        }
        return Tratto(1, penna, colore, 4f, a)
    }

    @Test
    fun stileDellePenne() {
        val penna = stilePdf(tratto(Penna.PENNA, 0xFF1565C0.toInt(), 0.5f to -1f))
        assertEquals(StilePdf(0x1565C0, 255, false), penna)
        val evidenziatore = stilePdf(tratto(Penna.EVIDENZIATORE, 0xFFFFEB3B.toInt(), 0.5f to -1f))
        assertEquals(StilePdf(0xFFEB3B, 0x66, true), evidenziatore)
    }

    @Test
    fun opacitaDellaMatita() {
        assertEquals(1f, opacitaMatita(tratto(Penna.MATITA, 0, 1f to -1f)), eps)
        assertEquals(0.35f, opacitaMatita(tratto(Penna.MATITA, 0, 0f to -1f)), eps)
        assertEquals(0.55f, opacitaMatita(tratto(Penna.MATITA, 0, 1f to 1.2f)), eps)
        assertEquals((1f + 0.35f) / 2f, opacitaMatita(tratto(Penna.MATITA, 0, 1f to -1f, 0f to 0.2f)), eps)
        val matita = stilePdf(tratto(Penna.MATITA, 0xFF000000.toInt(), 0f to -1f))
        assertEquals(quantizzaAlfa(0.35f * 255f), matita.alfa)
        assertFalse(matita.moltiplica)
    }

    @Test
    fun alfaAGradini() {
        assertEquals(255, quantizzaAlfa(255f))
        assertEquals(102, quantizzaAlfa(102f))
        assertEquals(89, quantizzaAlfa(0.35f * 255f))
        assertEquals(13, quantizzaAlfa(0f))
    }

    @Test
    fun arrotondaAlCentesimo() {
        assertEquals(12.34f, arrotonda(12.3449f), 0f)
        assertEquals(12.35f, arrotonda(12.346f), 0f)
        assertEquals(-3.5f, arrotonda(-3.4999f), 0f)
    }

    @Test
    fun numeriCortiNelPdf() {
        fun n(v: Float) = StringBuilder().numeroPdf(centesimi(v)).toString()
        assertEquals("841.89", n(841.89f))
        assertEquals("595.28", n(595.28f))
        assertEquals("84", n(84f))
        assertEquals("84.1", n(84.1f))
        assertEquals("0.05", n(0.05f))
        assertEquals("-0.05", n(-0.05f))
        assertEquals("-12.3", n(-12.3f))
        assertEquals("0", n(0.004f))
        assertEquals("0", n(-0.004f))
        assertEquals("1000", n(999.996f))
    }

    @Test
    fun versoDeiContorni() {
        val antiorario = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f)
        val orario = floatArrayOf(0f, 0f, 0f, 10f, 10f, 10f, 10f, 0f)
        assertEquals(100.0, areaConSegno(antiorario), 1e-9)
        assertEquals(-100.0, areaConSegno(orario), 1e-9)
        // Nastro chiuso ad anello (una "o"): lato esterno in un verso e interno nell'altro, conta il nastro.
        val anello = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f, 0f, 0f, 2f, 2f, 2f, 8f, 8f, 8f, 8f, 2f, 2f, 2f)
        assertEquals(100.0 - 36.0, areaConSegno(anello), 1e-9)
    }

    @Test
    fun semplificaToglieIPuntiAllineati() {
        // Quadrato 100x100 con un vertice ogni 10 unita' sui lati: restano i 4 angoli.
        val punti = ArrayList<Float>()
        for (i in 0 until 10) { punti += i * 10f; punti += 0f }
        for (i in 0 until 10) { punti += 100f; punti += i * 10f }
        for (i in 0 until 10) { punti += 100f - i * 10f; punti += 100f }
        for (i in 0 until 10) { punti += 0f; punti += 100f - i * 10f }
        val s = semplifica(punti.toFloatArray(), 0.05f)
        assertArrayEquals(floatArrayOf(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f), s, 0f)
    }

    @Test
    fun semplificaRestaEntroLaTolleranza() {
        val n = 2000
        val xy = FloatArray(n * 2)
        for (i in 0 until n) {
            val a = 2 * PI * i / n
            xy[2 * i] = (300 + 100 * cos(a)).toFloat()
            xy[2 * i + 1] = (400 + 100 * sin(a)).toFloat()
        }
        val s = semplifica(xy, 0.05f)
        assertTrue("vertici: ${s.size / 2}", s.size / 2 < n / 4)
        // Ogni vertice originale sta entro la tolleranza dal contorno semplificato.
        for (i in 0 until n) assertTrue(distanzaDalContorno(xy[2 * i], xy[2 * i + 1], s) <= 0.0501f)
    }

    @Test
    fun semplificaLasciaStareIContorniPiccoli() {
        val triangolo = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f)
        assertTrue(triangolo === semplifica(triangolo, 0.05f))
        val fermo = FloatArray(20) { 5f }
        assertTrue(fermo === semplifica(fermo, 0.05f))
    }

    private fun distanzaDalContorno(px: Float, py: Float, s: FloatArray): Float {
        val m = s.size / 2
        var min = Float.MAX_VALUE
        for (i in 0 until m) {
            val ax = s[2 * i]; val ay = s[2 * i + 1]
            val bx = s[2 * ((i + 1) % m)]; val by = s[2 * ((i + 1) % m) + 1]
            val vx = bx - ax; val vy = by - ay
            val l2 = vx * vx + vy * vy
            val t = if (l2 > 0f) (((px - ax) * vx + (py - ay) * vy) / l2).coerceIn(0f, 1f) else 0f
            val dx = px - (ax + t * vx); val dy = py - (ay + t * vy)
            min = minOf(min, sqrt(dx * dx + dy * dy))
        }
        return min
    }

    @Test
    fun sfondiComeNellEditor() {
        val righe = lineeSfondo(Sfondo.RIGHE, 1414f)
        assertEquals(33, righe.size / 4)
        assertArrayEquals(floatArrayOf(60f, 120f, 940f, 120f), righe.copyOfRange(0, 4), 0f)
        assertEquals(120f + 32 * 38f, righe[righe.size - 1], 0f)

        val quadretti = lineeSfondo(Sfondo.QUADRETTI, 1414f)
        assertEquals(41 + 58, quadretti.size / 4)
        assertEquals(0, lineeSfondo(Sfondo.BIANCO, 1414f).size)
        assertEquals(0, lineeSfondo(Sfondo.PUNTINI, 1414f).size)

        val puntini = puntiniSfondo(1414f)
        assertEquals(41 * 58, puntini.size / 2)
        assertArrayEquals(floatArrayOf(24f, 24f), puntini.copyOfRange(0, 2), 0f)
    }
}
