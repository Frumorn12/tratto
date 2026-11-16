package it.frumorn.tratto.googledocs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Il file dei collegamenti e la firma che dice se una pagina va riletta. */
class DocsLinksTest {

    private val esempio = DocsLinks(
        note = mapOf(
            "nota1" to CollegamentoDocs(
                "doc1",
                listOf(
                    BloccoSalvato("titolo", false, null, "i0", "0,0", intervallo = "kix.nr1", paragrafi = listOf("h1")),
                    BloccoSalvato("p1/t5", true, "p1", "i1", "0,44", oggetto = "kix.1", png = "png1", larghezzaPt = 220.0, altezzaPt = 22.5),
                    BloccoSalvato("p1/xab", false, "p1", "i2", "12,44", intervallo = "kix.nr2", paragrafi = listOf("h2", "h3")),
                ),
                mapOf("p1" to "1|1790000000000:120|-|Pagina(...)|true"),
            ),
            "nota2" to CollegamentoDocs("doc2"),
        ),
        cartella = "cartellaTratto",
        immagini = "cartellaImmagini",
    )

    @Test
    fun andataERitorno() {
        assertEquals(esempio, DocsLinks.leggi(esempio.json().toString()))
    }

    @Test
    fun senzaCartelle() {
        val c = DocsLinks(mapOf("n" to CollegamentoDocs("d")))
        val letto = DocsLinks.leggi(c.json().toString())
        assertEquals(c, letto)
        assertNull(letto.cartella)
        assertNull(letto.immagini)
    }

    @Test
    fun fileMancanteORovinato() {
        assertEquals(DocsLinks(), DocsLinks.leggi(null))
        assertEquals(DocsLinks(), DocsLinks.leggi("{non e' json"))
        assertEquals(DocsLinks(), DocsLinks.leggi("[]"))
    }

    @Test
    fun vociIncompleteSaltate() {
        val t = """{"versione":2,"note":{"a":{"blocchi":[]},"b":{"documento":"d","blocchi":[{"figura":true},{"chiave":"k","figura":true},{"chiave":"k"}]}}}"""
        val l = DocsLinks.leggi(t)
        assertEquals(setOf("b"), l.note.keys)
        // Senza chiave si salta; una chiave ripetuta vale una volta.
        assertEquals(listOf("k"), l.note.getValue("b").blocchi.map { it.chiave })
    }

    @Test
    fun fileDellaPrimaVersione() {
        val t = """{"versione":1,"cartella":"c","immagini":"i","note":{"n":{"documento":"d","pagine":{"p":{"oggetto":"kix.1","dimensione":"1000.0x1414.0","impronta":"x","png":"y"}}}}}"""
        val l = DocsLinks.leggi(t)
        assertEquals(CollegamentoDocs("d", vecchio = true), l.note.getValue("n"))
        assertEquals("c", l.cartella)
    }

    @Test
    fun improntaDelTesto() {
        assertEquals(BloccoSalvato.impronta("ciao"), BloccoSalvato.impronta("ciao"))
        assertNotEquals(BloccoSalvato.impronta("ciao"), BloccoSalvato.impronta("ciao "))
        assertEquals(24, BloccoSalvato.impronta("").length)
    }

    @Test
    fun firmaDeiFileDellaPagina() {
        val dir = Files.createTempDirectory("firma").toFile()
        try {
            val tp = File(dir, "p.tp")
            val og = File(dir, "p.oggetti.json")
            assertNull(FirmaPagina.firma(tp, og, "pagina"))
            tp.writeBytes(ByteArray(10))
            val f1 = FirmaPagina.firma(tp, og, "pagina")!!
            og.writeText("{}")
            val f2 = FirmaPagina.firma(tp, og, "pagina")!!
            assertNotEquals(f1, f2)
            assertNotEquals(f2, FirmaPagina.firma(tp, og, "altra pagina"))
            assertTrue(f1.startsWith("${PageBlocks.VERSIONE}|"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
