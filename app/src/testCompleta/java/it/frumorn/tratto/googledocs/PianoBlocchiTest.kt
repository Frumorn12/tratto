package it.frumorn.tratto.googledocs

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Casi del piano che il documento finto non sa fare (tabelle). */
class PianoBlocchiTest {

    /** Un testo di Tratto di un paragrafo. */
    private fun testo(chiave: String, t: String) =
        BloccoVoluto.Scritto(chiave, "p", BloccoSalvato.impronta(t), Aspetto.NESSUNO, listOf(ParagrafoScritto(t)))

    private fun par(inizio: Int, fine: Int, testo: String = "", immagine: String? = null) = ElementoDoc(
        inizio, fine, true, testo, immagine?.let { listOf(it to inizio) }.orEmpty(), stile = JSONObject().put("namedStyleType", "NORMAL_TEXT"),
    )

    @Test
    fun figuraPrimaDiUnaTabella() {
        // Titolo, una figura di Tratto, una tabella messa a mano, un paragrafo.
        val doc = DocLetto(
            "r1", "Appunti",
            listOf(par(1, 9, "Appunti"), par(9, 11, immagine = "kix.1"), ElementoDoc(11, 30, false), par(30, 31)),
            listOf(IntervalloDoc("kix.nr1", "tratto/titolo", 1, 9)),
        )
        val titolo = testo("titolo", "Appunti")
        val salvati = listOf(
            BloccoSalvato("titolo", false, null, titolo.impronta, titolo.aspetto.codice, intervallo = "kix.nr1", paragrafi = listOf(BloccoSalvato.impronta("Appunti"))),
            BloccoSalvato("p/t1", true, "p", "i", "0,0", oggetto = "kix.1", png = "x", larghezzaPt = 10.0, altezzaPt = 10.0),
        )
        val piano = PianoBlocchi(doc, salvati, listOf(titolo))
        val r = piano.richieste(emptyMap()).json
        val tipi = (0 until r.length()).map { r.getJSONObject(it).keys().next() }
        // L'a capo prima della tabella non si cancella: si toglie solo l'immagine e il paragrafo torna normale.
        val canc = r.getJSONObject(tipi.indexOf("deleteContentRange")).getJSONObject("deleteContentRange").getJSONObject("range")
        assertEquals(9, canc.getInt("startIndex"))
        assertEquals(10, canc.getInt("endIndex"))
        assertTrue(tipi.contains("updateParagraphStyle"))
    }

    @Test
    fun documentoGiaGiustoNonSiTocca() {
        val doc = DocLetto("r1", "Appunti", listOf(par(1, 9, "Appunti"), par(9, 10)), listOf(IntervalloDoc("kix.nr1", "tratto/titolo", 1, 9)))
        val titolo = testo("titolo", "Appunti")
        val salvati = listOf(BloccoSalvato("titolo", false, null, titolo.impronta, titolo.aspetto.codice, intervallo = "kix.nr1", paragrafi = listOf(BloccoSalvato.impronta("Appunti"))))
        assertTrue(PianoBlocchi(doc, salvati, listOf(titolo)).vuoto)
        // Un intervallo allargato (qualcuno ha scritto subito dopo) si rifa' esatto.
        val largo = DocLetto("r1", "Appunti", listOf(par(1, 9, "Appunti"), par(9, 20, "mio testo")), listOf(IntervalloDoc("kix.nr1", "tratto/titolo", 1, 20)))
        val p = PianoBlocchi(largo, salvati, listOf(titolo))
        assertTrue(!p.vuoto)
        val r = p.richieste(emptyMap()).json
        val crea = (0 until r.length()).map { r.getJSONObject(it) }.first { it.has("createNamedRange") }.getJSONObject("createNamedRange").getJSONObject("range")
        assertEquals(1, crea.getInt("startIndex"))
        assertEquals(9, crea.getInt("endIndex"))
        // E il testo scritto a mano non si tocca.
        assertTrue((0 until r.length()).none { r.getJSONObject(it).has("deleteContentRange") || r.getJSONObject(it).has("insertText") })
    }
}
