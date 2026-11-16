package it.frumorn.tratto.editor

import it.frumorn.tratto.data.LayoutPagina
import it.frumorn.tratto.data.Pagina
import it.frumorn.tratto.data.Sfondo
import it.frumorn.tratto.data.TestoRicco
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentFlowTest {

    @Test
    fun zoneRespecteLesMarges() {
        val p = Pagina("p", Sfondo.BIANCO, layout = LayoutPagina.DOCUMENT)
        val z = DocumentMargins.zone(p)
        assertEquals(DocumentMargins.MARGINE, z[0], 0.01f)
        assertEquals(DocumentMargins.MARGINE, z[1], 0.01f)
        assertEquals(p.larghezza - 2 * DocumentMargins.MARGINE, z[2], 0.01f)
        assertEquals(p.altezza - 2 * DocumentMargins.MARGINE, z[3], 0.01f)
    }

    @Test
    fun sezioneCoupeAuMilieuDuParagraphe() {
        val t = TestoRicco.semplice("Hello World")
        assertEquals("Hello ", t.sezione(0, 6).testoSemplice)
        assertEquals("World", t.sezione(6, 11).testoSemplice)
    }

    @Test
    fun sezioneSurFrontiereDeParagraphe() {
        val t = TestoRicco.semplice("Hello\nWorld")
        // "Hello\nWorld" → offset 6 = début de World
        assertEquals("Hello", t.sezione(0, 6).testoSemplice)
        assertEquals("World", t.sezione(6, 11).testoSemplice)
    }

    @Test
    fun concatenaFusionneLaSuiteDeParagraphe() {
        val a = PaginaViva(Pagina("a", Sfondo.BIANCO, layout = LayoutPagina.DOCUMENT)).apply {
            caricata = true
            oggetti += DocumentMargins.corpo(pagina, TestoRicco.semplice("Hello "))
        }
        val b = PaginaViva(Pagina("b", Sfondo.BIANCO, layout = LayoutPagina.DOCUMENT)).apply {
            caricata = true
            oggetti += DocumentMargins.corpo(pagina, TestoRicco.semplice("World"), continua = true)
        }
        val pages = listOf(a, b)
        assertEquals("Hello World", DocumentFlow.concatena(pages, 0..1).testoSemplice)
    }

    @Test
    fun concatenaGardeDeuxParagraphes() {
        val a = PaginaViva(Pagina("a", Sfondo.BIANCO, layout = LayoutPagina.DOCUMENT)).apply {
            caricata = true
            oggetti += DocumentMargins.corpo(pagina, TestoRicco.semplice("Hello"))
        }
        val b = PaginaViva(Pagina("b", Sfondo.BIANCO, layout = LayoutPagina.DOCUMENT)).apply {
            caricata = true
            oggetti += DocumentMargins.corpo(pagina, TestoRicco.semplice("World"), continua = false)
        }
        assertEquals("Hello\nWorld", DocumentFlow.concatena(listOf(a, b), 0..1).testoSemplice)
    }

    @Test
    fun catenaIsoleLesPagesDocument() {
        val pages = listOf(
            PaginaViva(Pagina("0", Sfondo.BIANCO, layout = LayoutPagina.CANVAS)),
            PaginaViva(Pagina("1", Sfondo.BIANCO, layout = LayoutPagina.DOCUMENT)),
            PaginaViva(Pagina("2", Sfondo.BIANCO, layout = LayoutPagina.DOCUMENT)),
            PaginaViva(Pagina("3", Sfondo.BIANCO, layout = LayoutPagina.CANVAS)),
        )
        assertEquals(IntRange.EMPTY, DocumentFlow.catena(pages, 0))
        assertEquals(1..2, DocumentFlow.catena(pages, 1))
        assertEquals(1..2, DocumentFlow.catena(pages, 2))
        assertEquals(IntRange.EMPTY, DocumentFlow.catena(pages, 3))
    }

    @Test
    fun paginaConserveLeLayoutParDefautEtParCopy() {
        assertEquals(LayoutPagina.CANVAS, Pagina("a", Sfondo.BIANCO).layout)
        val doc = Pagina("b", Sfondo.RIGHE, layout = LayoutPagina.DOCUMENT)
        assertEquals(LayoutPagina.DOCUMENT, doc.layout)
        assertEquals(LayoutPagina.CANVAS, doc.copy(layout = LayoutPagina.CANVAS).layout)
    }

    @Test
    fun corpoAncreAuxMarges() {
        val p = Pagina("p", Sfondo.BIANCO, layout = LayoutPagina.DOCUMENT)
        val c = DocumentMargins.corpo(p, TestoRicco.VUOTO)
        assertEquals(DocumentMargins.ID_CORPO, c.id)
        assertEquals(DocumentMargins.MARGINE, c.x, 0.01f)
        assertEquals(DocumentMargins.MARGINE, c.y, 0.01f)
        assertTrue(DocumentMargins.estCorpo(c))
        assertTrue(DocumentMargins.estCorpo(DocumentMargins.ID_CORPO_CONT))
    }
}
