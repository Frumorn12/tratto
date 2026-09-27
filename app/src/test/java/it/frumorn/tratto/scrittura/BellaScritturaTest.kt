package it.frumorn.tratto.scrittura

import it.frumorn.tratto.data.Penna
import it.frumorn.tratto.data.Tratto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.math.abs

class BellaScritturaTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun carica() {
            // I test girano nella cartella del modulo app: l'asset si legge dal sorgente.
            BellaScrittura.prepara(File("src/completa/assets/${BellaScrittura.ASSET}").readBytes())
        }

        const val FRASE = "Ciao Andrea, città è però"
    }

    private var id = 0L

    private fun componi(testo: String, stile: Stile, altezzaX: Float = 20f, larghezzaMax: Float = 0f, x: Float = 100f, y: Float = 200f) =
        BellaScrittura.componi(testo, stile, x, y, altezzaX, larghezzaMax, 0xFF202020.toInt(), Penna.PENNA, 3f) { id++ }

    private fun Tratto.xs() = (0 until quanti).map { punti[it * Tratto.CAMPI] }
    private fun Tratto.ys() = (0 until quanti).map { punti[it * Tratto.CAMPI + 1] }

    @Test
    fun fraseInEntrambiGliStili() {
        for (stile in Stile.entries) {
            val tratti = componi(FRASE, stile)
            assertTrue("$stile senza tratti", tratti.size > 5)
            assertTrue(tratti.all { it.quanti >= 2 })
            assertEquals("id tutti diversi", tratti.size, tratti.map { it.id }.toSet().size)
            for (t in tratti) {
                assertEquals(Penna.PENNA, t.penna)
                assertEquals(3f, t.spessore)
            }
        }
    }

    @Test
    fun puntiTempiEPressione() {
        for (stile in Stile.entries) {
            for (t in componi(FRASE, stile, altezzaX = 24f)) {
                val p = t.punti
                assertEquals(0f, p[5])
                for (i in 0 until t.quanti) {
                    val o = i * Tratto.CAMPI
                    assertTrue(p[o + 2] in 0.3f..0.85f)
                    assertEquals(0.45f, p[o + 3])
                    assertEquals(-1f, p[o + 4])
                    if (i > 0) {
                        assertTrue("tempi non crescenti", p[o + 5] > p[o + 5 - Tratto.CAMPI])
                        // Un punto ogni ~altezzaX/12 = 2 unita': mai salti lunghi.
                        val d = Math.hypot((p[o] - p[o - Tratto.CAMPI]).toDouble(), (p[o + 1] - p[o + 1 - Tratto.CAMPI]).toDouble())
                        assertTrue("salto di $d", d <= 2.01)
                    }
                }
            }
        }
    }

    @Test
    fun aCapoEntroLaLarghezza() {
        for (stile in Stile.entries) {
            val larghezza = 260f
            val tratti = componi("$FRASE e poi ancora qualche parola per riempire le righe", stile, larghezzaMax = larghezza)
            val d = BellaScrittura.dimensioni("$FRASE e poi ancora qualche parola per riempire le righe", stile, 20f, larghezza)
            assertTrue("larghezza ${d[0]}", d[0] <= larghezza)
            // Qualche lettera (la coda della f in corsivo) sporge di poco dal suo margine.
            val xs = tratti.flatMap { it.xs() }
            assertTrue("$stile x da ${xs.min()} a ${xs.max()}", xs.min() >= 100f - 12f && xs.max() <= 100f + larghezza + 12f)
            val senzaLimite = BellaScrittura.dimensioni("$FRASE e poi ancora qualche parola per riempire le righe", stile, 20f, 0f)
            assertTrue("deve andare a capo", d[1] > senzaLimite[1] * 2)
            // Tutto dentro l'altezza misurata (gli accenti sulle maiuscole possono sporgere, qui non ce ne sono).
            val ys = tratti.flatMap { it.ys() }
            assertTrue("$stile y da ${ys.min()} a ${ys.max()}, altezza ${d[1]}", ys.min() >= 200f - 0.5f && ys.max() <= 200f + d[1] + 0.5f)
        }
    }

    @Test
    fun gliAccentiDelleMaiuscoleNonToccanoLaRigaSopra() {
        for (stile in Stile.entries) {
            val sopra = componi("gpq", stile)
            val fondoSopra = sopra.maxOf { it.ys().max() }
            val cimaAccenti = componi("gpq\nÈÀi", stile).drop(sopra.size).minOf { it.ys().min() }
            val cimaSenza = componi("gpq\nEAi", stile).drop(sopra.size).minOf { it.ys().min() }
            // Gli accenti non salgono oltre la cima del font (il puntino della i): la riga scende.
            assertTrue("$stile: $cimaAccenti < $cimaSenza", cimaAccenti >= cimaSenza - 0.01f)
            // Nello stampatello le righe non si toccano mai; in corsivo le code lunghe possono
            // sfiorare le maiuscole della riga sotto, come su un quaderno.
            if (stile == Stile.STAMPATELLO) assertTrue("$cimaAccenti <= $fondoSopra", cimaAccenti > fondoSopra)
            // Senza accenti le righe restano equidistanti.
            val d1 = BellaScrittura.dimensioni("a\nE", stile, 20f, 0f)[1]
            val d2 = BellaScrittura.dimensioni("a\nÈ", stile, 20f, 0f)[1]
            assertTrue(d2 > d1)
            assertEquals(BellaScrittura.dimensioni("a\nE", stile, 20f, 0f)[1], BellaScrittura.dimensioni("a\nb", stile, 20f, 0f)[1], 1e-3f)
        }
    }

    @Test
    fun parolaTroppoLungaSiSpezza() {
        val d = BellaScrittura.dimensioni("precipitevolissimevolmente", Stile.STAMPATELLO, 20f, 120f)
        assertTrue(d[0] <= 120f)
        assertTrue(d[1] > BellaScrittura.dimensioni("p", Stile.STAMPATELLO, 20f, 120f)[1] * 2)
    }

    @Test
    fun aCapoForzato() {
        val una = BellaScrittura.dimensioni("ciao", Stile.CORSIVO, 20f, 0f)
        val due = BellaScrittura.dimensioni("ciao\nciao", Stile.CORSIVO, 20f, 0f)
        assertEquals(una[0], due[0], 1e-3f)
        assertTrue(due[1] > una[1] * 1.5f)
    }

    @Test
    fun gliAccentiAggiungonoUnTratto() {
        for (stile in Stile.entries) {
            for ((senza, con) in listOf("a" to "à", "e" to "è", "e" to "é", "o" to "ò", "u" to "ù", "E" to "È", "A" to "À", "O" to "Ò", "te" to "tè")) {
                val base = componi(senza, stile)
                val accentata = componi(con, stile)
                assertEquals("$stile $con", base.size + 1, accentata.size)
                // L'accento sta sopra la lettera (in "tè" resta staccato dal taglio della t, che e' piu' in alto).
                if (senza.length == 1) {
                    val accento = accentata.last()
                    val corpo = accentata.dropLast(1)
                    assertTrue("$stile $con: accento non sopra", accento.ys().max() < corpo.minOf { it.ys().min() })
                }
            }
            // Sulla i l'accento prende il posto del puntino: stessi tratti, ma quello in alto e'
            // una lineetta obliqua invece di un puntino.
            val i = componi("i", stile)
            val ì = componi("ì", stile)
            assertEquals(i.size, ì.size)
            val puntino = i.minBy { it.ys().max() }
            val accento = ì.minBy { it.ys().max() }
            val larghezza = { t: Tratto -> t.xs().max() - t.xs().min() }
            assertTrue("$stile ì", larghezza(accento) > 1.5f * larghezza(puntino))
            assertTrue(componi("Ì", stile).size > componi("I", stile).size)
        }
    }

    @Test
    fun apostrofiVirgoletteESimboli() {
        for (stile in Stile.entries) {
            val dritto = componi("l'uomo", stile)
            val tipografico = componi("l’uomo", stile)
            assertEquals(dritto.size, tipografico.size)
            assertEquals(4, componi("«»", stile).size)
            assertTrue(componi("5 €", stile).size > componi("5 C", stile).size)
            // Caratteri che il font non ha: si saltano senza errori.
            assertEquals(componi("ab", stile).size, componi("a\u2603b", stile).size)
        }
    }

    @Test
    fun stimaDellAltezzaX() {
        // La stima ritrova l'altezza x di un testo scritto con gli stessi font.
        val testi = listOf(
            "Ciao Andrea, città è però",
            "La riunione di domani e' spostata alle nove",
            "comprare latte, pane e uova per la colazione",
        )
        for (stile in Stile.entries) for (testo in testi) for (h in listOf(12f, 20f, 35f)) {
            val stima = BellaScrittura.altezzaXStimata(componi(testo, stile, altezzaX = h, larghezzaMax = 30 * h))
            assertTrue("$stile \"$testo\" h=$h stima=$stima", abs(stima - h) / h < 0.25f)
        }
        assertEquals(BellaScrittura.ALTEZZA_X_PREDEFINITA, BellaScrittura.altezzaXStimata(emptyList()))
    }

    @Test
    fun corsivoUnisceLeLettere() {
        // In corsivo "unire" e' un tratto solo piu' il puntino della i; in stampatello ogni lettera ha i suoi.
        val corsivo = componi("unire", Stile.CORSIVO)
        val stampatello = componi("unire", Stile.STAMPATELLO)
        assertEquals(2, corsivo.size)
        assertTrue(stampatello.size >= 5)
    }

    /** Scrive un'anteprima SVG in build/reports/bella-scrittura, da guardare a occhio. */
    @Test
    fun anteprima() {
        val cartella = File("build/reports/bella-scrittura").apply { mkdirs() }
        val testo = "Ciao Andrea, città è però.\nÈ l’ora di «Tratto»: perché no? Più già, così 5 € a 30° ÀÒÙ\nQuel giorno è stato bellissimo, (dice) Giulia: \"sì!\" 1234567890"
        for (stile in Stile.entries) {
            val tratti = componi(testo, stile, altezzaX = 18f, larghezzaMax = 700f, x = 20f, y = 20f)
            val d = BellaScrittura.dimensioni(testo, stile, 18f, 700f)
            val sb = StringBuilder()
            sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"760\" height=\"${(d[1] + 60).toInt()}\">")
            sb.append("<rect width=\"100%\" height=\"100%\" fill=\"white\"/>")
            sb.append("<rect x=\"20\" y=\"20\" width=\"${d[0]}\" height=\"${d[1]}\" fill=\"none\" stroke=\"#9cf\"/>")
            for (t in tratti) {
                val p = t.punti
                for (i in 1 until t.quanti) {
                    val a = (i - 1) * Tratto.CAMPI
                    val b = i * Tratto.CAMPI
                    val w = 3f * (0.34f + 1.11f * (p[a + 2] + p[b + 2]) / 2f)
                    sb.append("<line x1=\"${p[a]}\" y1=\"${p[a + 1]}\" x2=\"${p[b]}\" y2=\"${p[b + 1]}\" stroke=\"#1a2a6c\" stroke-width=\"$w\" stroke-linecap=\"round\"/>")
                }
            }
            sb.append("</svg>")
            File(cartella, "${stile.name.lowercase()}.svg").writeText(sb.toString())
        }
    }
}
