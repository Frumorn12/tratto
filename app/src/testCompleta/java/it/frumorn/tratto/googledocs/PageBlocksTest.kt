package it.frumorn.tratto.googledocs

import it.frumorn.tratto.data.Frammento
import it.frumorn.tratto.data.Immagine
import it.frumorn.tratto.data.Pagina
import it.frumorn.tratto.data.Paragrafo
import it.frumorn.tratto.data.Penna
import it.frumorn.tratto.data.Sfondo
import it.frumorn.tratto.data.Testo
import it.frumorn.tratto.data.TestoRicco
import it.frumorn.tratto.data.Tratto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Come si divide una pagina in righe, disegni e caselle di testo. */
class PageBlocksTest {
    private val pagina = Pagina("p", Sfondo.RIGHE)
    private var id = 1L

    /** Un tratto da (x0, y0) a (x1, y1). */
    private fun tratto(x0: Float, y0: Float, x1: Float, y1: Float, spessore: Float = 2f) =
        Tratto(id++, Penna.PENNA, 0, spessore, floatArrayOf(x0, y0, 0.5f, 0f, 0f, 0f, x1, y1, 0.5f, 0f, 0f, 10f))

    private fun casella(id: String, y: Float, testo: String = "ciao") =
        Testo(id, 100f, y, 500f, TestoRicco(listOf(Paragrafo(listOf(Frammento(testo))))))

    private fun dividi(tratti: List<Tratto>, oggetti: List<it.frumorn.tratto.data.Oggetto> = emptyList(), p: Pagina = pagina) =
        PageBlocks.dividi(p, tratti, oggetti) { 40f }

    @Test
    fun righeSeparateDallInterlinea() {
        val riga1 = listOf(tratto(100f, 100f, 300f, 130f), tratto(320f, 100f, 500f, 130f))
        val riga2 = listOf(tratto(100f, 180f, 400f, 210f))
        val b = dividi(riga1 + riga2)
        assertEquals(2, b.size)
        assertEquals(riga1.map { it.id }, (b[0] as BloccoPagina.Disegno).tratti.map { it.id })
        assertEquals("t${riga1[0].id}", b[0].chiave)
        // Il riquadro comprende lo spessore del tratto.
        assertTrue(b[0].riquadro.alto < 100f && b[0].riquadro.basso > 130f)
    }

    @Test
    fun accentiEUgualeRestanoNellaRiga() {
        val lettere = tratto(100f, 100f, 300f, 130f)
        val accento = tratto(150f, 88f, 156f, 94f)
        val uguale = listOf(tratto(320f, 108f, 360f, 108f), tratto(320f, 118f, 360f, 118f))
        val b = dividi(listOf(accento, lettere) + uguale)
        assertEquals(1, b.size)
        // La chiave e' il tratto piu' vecchio: aggiungere tratti non cambia la riga.
        assertEquals("t${lettere.id}", b[0].chiave)
    }

    @Test
    fun disegnoAltoUnBloccoSolo() {
        val cerchio = tratto(200f, 100f, 600f, 600f)
        val dentro = listOf(tratto(300f, 300f, 400f, 320f), tratto(300f, 450f, 400f, 470f))
        assertEquals(1, dividi(listOf(cerchio) + dentro).size)
    }

    @Test
    fun disegnoAccantoAlTesto() {
        // Come nella nota di Andrea: righe a sinistra, uno scarabocchio alto tre righe a destra.
        val ciao = tratto(120f, 250f, 240f, 320f)
        val disegno = listOf(tratto(330f, 240f, 640f, 515f), tratto(590f, 330f, 730f, 515f), tratto(700f, 420f, 815f, 825f))
        val come = tratto(170f, 460f, 285f, 520f)
        val stai = tratto(175f, 690f, 330f, 810f)
        val tranquillo = tratto(385f, 690f, 515f, 750f)
        val bene = tratto(150f, 950f, 320f, 1070f)
        val b = dividi(listOf(ciao, come, stai, tranquillo, bene) + disegno)
        // Ogni scritta per conto suo, il disegno da solo; le parole della stessa riga insieme.
        assertEquals(listOf("t${ciao.id}", "t${disegno[0].id}", "t${come.id}", "t${stai.id}", "t${bene.id}"), b.map { it.chiave })
        assertEquals(listOf(stai.id, tranquillo.id), (b[3] as BloccoPagina.Disegno).tratti.map { it.id })
        assertEquals(disegno.map { it.id }, (b[1] as BloccoPagina.Disegno).tratti.map { it.id })
    }

    @Test
    fun puntinoSoloVaNellaRiga() {
        val parola = tratto(100f, 100f, 300f, 130f)
        // Il puntino di una i un po' alto, lontano piu' della distanza dei gruppi.
        val puntino = tratto(200f, 70f, 203f, 73f, spessore = 1f)
        val b = dividi(listOf(parola, puntino))
        assertEquals(1, b.size)
    }

    @Test
    fun caselleInOrdineConLeRighe() {
        val sopra = tratto(100f, 100f, 300f, 130f)
        val sotto = tratto(100f, 400f, 300f, 430f)
        val b = dividi(listOf(sopra, sotto), listOf(casella("c", 250f), casella("vuota", 300f, " ")))
        assertEquals(listOf("t${sopra.id}", "xc", "t${sotto.id}"), b.map { it.chiave })
        assertEquals(290f, b[1].riquadro.basso)
    }

    @Test
    fun immaginiComeInchiostro() {
        val foto = Immagine("f1", "immagini/f1.webp", 100f, 500f, 400f, 300f)
        val freccia = tratto(450f, 600f, 520f, 620f)
        val b = dividi(listOf(freccia), listOf(foto))
        assertEquals(1, b.size)
        val d = b[0] as BloccoPagina.Disegno
        assertEquals(listOf(foto), d.immagini)
        // Senza tratti la chiave e' l'immagine.
        assertEquals("if1", dividi(emptyList(), listOf(foto))[0].chiave)
    }

    @Test
    fun paginaDiPdf() {
        val pdf = Pagina("p", Sfondo.BIANCO, pdfPagina = 2, altezza = 800f)
        val b = dividi(listOf(tratto(1f, 1f, 2f, 2f)), p = pdf)
        assertEquals(listOf(BloccoPagina.Intera("pdf", Riquadro(0f, 0f, 1000f, 800f))), b)
    }

    @Test
    fun improntaCambiaSoloConIDatiDelBlocco() {
        val a = tratto(100f, 100f, 300f, 130f)
        val b = dividi(listOf(a))[0]
        val i1 = PageBlocks.impronta(pagina, b, listOf(a), emptyList())
        assertEquals(i1, PageBlocks.impronta(pagina, dividi(listOf(a))[0], listOf(a), emptyList()))
        val a2 = Tratto(a.id, a.penna, 0xFF0000, a.spessore, a.punti)
        assertNotEquals(i1, PageBlocks.impronta(pagina, dividi(listOf(a2))[0], listOf(a2), emptyList()))
    }

    @Test
    fun aspettiDaPosizioni() {
        val b = dividi(listOf(tratto(100f, 100f, 300f, 130f), tratto(200f, 200f, 300f, 230f)))
        val prima = DocsContent.aspetti(b, primaPagina = true)
        assertEquals(0, prima[0].sopraPt)
        // Spazio sopra come la distanza dalla riga prima, rientro come la distanza dal bordo.
        assertEquals(Math.round((b[1].riquadro.alto - b[0].riquadro.basso) * 0.44).toInt(), prima[1].sopraPt)
        assertEquals(Math.round(b[1].riquadro.sinistra * 0.44).toInt(), prima[1].rientroPt)
        assertEquals(DocsContent.SOPRA_PAGINA_PT, DocsContent.aspetti(b, primaPagina = false)[0].sopraPt)
    }
}
