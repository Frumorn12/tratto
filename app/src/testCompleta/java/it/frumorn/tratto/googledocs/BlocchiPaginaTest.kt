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
class BlocchiPaginaTest {
    private val pagina = Pagina("p", Sfondo.RIGHE)
    private var id = 1L

    /** Un tratto da (x0, y0) a (x1, y1). */
    private fun tratto(x0: Float, y0: Float, x1: Float, y1: Float, spessore: Float = 2f) =
        Tratto(id++, Penna.PENNA, 0, spessore, floatArrayOf(x0, y0, 0.5f, 0f, 0f, 0f, x1, y1, 0.5f, 0f, 0f, 10f))

    private fun casella(id: String, y: Float, testo: String = "ciao") =
        Testo(id, 100f, y, 500f, TestoRicco(listOf(Paragrafo(listOf(Frammento(testo))))))

    private fun dividi(tratti: List<Tratto>, oggetti: List<it.frumorn.tratto.data.Oggetto> = emptyList(), p: Pagina = pagina) =
        BlocchiPagina.dividi(p, tratti, oggetti) { 40f }

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
        val i1 = BlocchiPagina.impronta(pagina, b, listOf(a), emptyList())
        assertEquals(i1, BlocchiPagina.impronta(pagina, dividi(listOf(a))[0], listOf(a), emptyList()))
        val a2 = Tratto(a.id, a.penna, 0xFF0000, a.spessore, a.punti)
        assertNotEquals(i1, BlocchiPagina.impronta(pagina, dividi(listOf(a2))[0], listOf(a2), emptyList()))
    }

    @Test
    fun aspettiDaPosizioni() {
        val b = dividi(listOf(tratto(100f, 100f, 300f, 130f), tratto(200f, 200f, 300f, 230f)))
        val prima = ContenutoDocs.aspetti(b, primaPagina = true)
        assertEquals(0, prima[0].sopraPt)
        // Spazio sopra come la distanza dalla riga prima, rientro come la distanza dal bordo.
        assertEquals(Math.round((b[1].riquadro.alto - b[0].riquadro.basso) * 0.44).toInt(), prima[1].sopraPt)
        assertEquals(Math.round(b[1].riquadro.sinistra * 0.44).toInt(), prima[1].rientroPt)
        assertEquals(ContenutoDocs.SOPRA_PAGINA_PT, ContenutoDocs.aspetti(b, primaPagina = false)[0].sopraPt)
    }
}
