package it.frumorn.tratto.googledocs

import it.frumorn.tratto.data.Pagina
import it.frumorn.tratto.data.Penna
import it.frumorn.tratto.data.Sfondo
import it.frumorn.tratto.data.Tratto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** Il file dei collegamenti e l'impronta che dice se una pagina va ridisegnata. */
class CollegamentiDocsTest {

    private val esempio = CollegamentiDocs(
        note = mapOf(
            "nota1" to CollegamentoDocs(
                "doc1",
                mapOf(
                    "p1" to PaginaDocs("kix.1", "1000.0x1414.0", "i1", "png1", "1790000000000:120:Pagina(...):1"),
                    "p2" to PaginaDocs("kix.2", "1000.0x700.0", "i2", "png2"),
                ),
            ),
            "nota2" to CollegamentoDocs("doc2"),
        ),
        cartella = "cartellaTratto",
        immagini = "cartellaImmagini",
    )

    @Test
    fun andataERitorno() {
        val testo = esempio.json().toString()
        assertEquals(esempio, CollegamentiDocs.leggi(testo))
        assertEquals(CollegamentiDocs.VERSIONE, esempio.json().getInt("versione"))
    }

    @Test
    fun senzaCartelle() {
        val c = CollegamentiDocs(note = mapOf("n" to CollegamentoDocs("d")))
        val letto = CollegamentiDocs.leggi(c.json().toString())
        assertEquals(c, letto)
        assertNull(letto.cartella)
        assertNull(letto.immagini)
    }

    @Test
    fun fileMancanteORovinato() {
        assertEquals(CollegamentiDocs(), CollegamentiDocs.leggi(null))
        assertEquals(CollegamentiDocs(), CollegamentiDocs.leggi("{non e' json"))
        assertEquals(CollegamentiDocs(), CollegamentiDocs.leggi("{}"))
    }

    @Test
    fun vociIncompleteSaltate() {
        val c = CollegamentiDocs.leggi(
            """{"versione":1,"note":{"senzaDoc":{"pagine":{}},"vuoto":{"documento":""},
               "ok":{"documento":"d","pagine":{"p":{"dimensione":"x"},"q":{"oggetto":"kix.q","dimensione":"1x2","impronta":"i","png":"h"}}}}}""",
        )
        assertEquals(setOf("ok"), c.note.keys)
        assertEquals(mapOf("q" to PaginaDocs("kix.q", "1x2", "i", "h")), c.note.getValue("ok").pagine)
    }

    @Test
    fun immaginiRiconoscibili() {
        assertEquals(
            mapOf("kix.1" to chiaveImmagine("p1", "1000.0x1414.0"), "kix.2" to chiaveImmagine("p2", "1000.0x700.0")),
            esempio.note.getValue("nota1").immagini(),
        )
    }

    private fun tratto(id: Long, x: Float = 10f) = Tratto(id, Penna.PENNA, 0xFF000000.toInt(), 3f, floatArrayOf(x, 20f, 0.5f, 0f, 0f, 0f, x + 5, 25f, 0.6f, 0f, 0f, 16f))

    @Test
    fun improntaStabile() {
        val p = Pagina("p1", Sfondo.RIGHE)
        assertEquals(
            ImprontaPagina.di(p, listOf(tratto(1), tratto(2)), listOf("testo")),
            ImprontaPagina.di(p.copy(), listOf(tratto(1), tratto(2)), listOf("testo")),
        )
    }

    @Test
    fun improntaCambiaConIDati() {
        val p = Pagina("p1", Sfondo.RIGHE)
        val base = ImprontaPagina.di(p, listOf(tratto(1), tratto(2)), emptyList())
        assertNotEquals(base, ImprontaPagina.di(p, listOf(tratto(1), tratto(2, x = 11f)), emptyList()))
        assertNotEquals(base, ImprontaPagina.di(p, listOf(tratto(2), tratto(1)), emptyList()))
        assertNotEquals(base, ImprontaPagina.di(p, listOf(tratto(1)), emptyList()))
        assertNotEquals(base, ImprontaPagina.di(p.copy(sfondo = Sfondo.QUADRETTI), listOf(tratto(1), tratto(2)), emptyList()))
        assertNotEquals(base, ImprontaPagina.di(p, listOf(tratto(1), tratto(2)), listOf("")))
        val colore = Tratto(2, Penna.PENNA, 0xFFFF0000.toInt(), 3f, tratto(2).punti)
        assertNotEquals(base, ImprontaPagina.di(p, listOf(tratto(1), colore), emptyList()))
    }

    @Test
    fun sha256() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", ImprontaPagina.sha256(ByteArray(0)))
    }

    @Test
    fun firmaDelFileDeiTratti() {
        val f = File.createTempFile("pagina", ".tp")
        try {
            val p = Pagina("p1", Sfondo.RIGHE)
            f.writeBytes(ByteArray(10))
            f.setLastModified(1_790_000_000_000L)
            val prima = ImprontaPagina.firma(f, p, emptyList())
            assertEquals(prima, ImprontaPagina.firma(f, p.copy(), emptyList()))
            // Cambia se cambiano il file (data o dimensione), la pagina o il testo.
            assertNotEquals(prima, ImprontaPagina.firma(f, p.copy(sfondo = Sfondo.PUNTINI), emptyList()))
            assertNotEquals(prima, ImprontaPagina.firma(f, p, listOf("testo")))
            f.writeBytes(ByteArray(11))
            f.setLastModified(1_790_000_000_000L)
            assertNotEquals(prima, ImprontaPagina.firma(f, p, emptyList()))
            f.setLastModified(1_790_000_001_000L)
            assertNotEquals(ImprontaPagina.firma(f, p, emptyList()), prima)
        } finally {
            f.delete()
        }
        // Senza file non c'e' firma: i tratti si leggono sempre.
        assertNull(ImprontaPagina.firma(File(f.parentFile, "non-esiste-${System.nanoTime()}.tp"), Pagina("p", Sfondo.BIANCO), emptyList()))
    }
}
