package it.frumorn.tratto.googledocs

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** I corpi JSON di batchUpdate e la lettura di documents.get. */
class RichiesteDocsTest {

    private fun assertJson(atteso: String, reale: JSONObject) =
        assertEquals(normale(JSONObject(atteso)), normale(reale))

    /** JSON come mappe e liste di Kotlin, confrontabili con equals (i numeri come Double). */
    private fun normale(x: Any?): Any? = when (x) {
        is JSONObject -> x.keys().asSequence().associateWith { normale(x.get(it)) }
        is JSONArray -> (0 until x.length()).map { normale(x.get(it)) }
        is Number -> x.toDouble()
        else -> x
    }

    @Test
    fun inserisciTesto() = assertJson(
        """{"insertText":{"location":{"index":5},"text":"Ciao\n"}}""",
        RichiesteDocs.inserisciTesto(5, "Ciao\n"),
    )

    @Test
    fun cancella() = assertJson(
        """{"deleteContentRange":{"range":{"startIndex":3,"endIndex":9}}}""",
        RichiesteDocs.cancella(3, 9),
    )

    @Test
    fun inserisciImmagine() = assertJson(
        """{"insertInlineImage":{"location":{"index":7},"uri":"https://lh3.googleusercontent.com/d/abc",
            "objectSize":{"width":{"magnitude":440.0,"unit":"PT"},"height":{"magnitude":622.2,"unit":"PT"}}}}""",
        RichiesteDocs.inserisciImmagine(7, "https://lh3.googleusercontent.com/d/abc", 440.0, 622.2),
    )

    @Test
    fun sostituisciImmagine() = assertJson(
        """{"replaceImage":{"imageObjectId":"kix.1","uri":"https://x.example/i.png","imageReplaceMethod":"CENTER_CROP"}}""",
        RichiesteDocs.sostituisciImmagine("kix.1", "https://x.example/i.png"),
    )

    @Test
    fun stile() = assertJson(
        """{"updateParagraphStyle":{"range":{"startIndex":1,"endIndex":2},
            "paragraphStyle":{"namedStyleType":"TITLE","alignment":"START"},"fields":"namedStyleType,alignment"}}""",
        RichiesteDocs.stile(1, 2, StileParagrafo.TITOLO),
    )

    @Test
    fun paginaA4() = assertJson(
        """{"updateDocumentStyle":{"documentStyle":{"pageSize":{"width":{"magnitude":595.28,"unit":"PT"},
            "height":{"magnitude":841.89,"unit":"PT"}}},"fields":"pageSize"}}""",
        RichiesteDocs.paginaA4(),
    )

    @Test
    fun corpoConESenzaRevisione() {
        val r = JSONArray().put(RichiesteDocs.cancella(1, 2))
        assertJson("""{"requests":[{"deleteContentRange":{"range":{"startIndex":1,"endIndex":2}}}],"writeControl":{"requiredRevisionId":"ALm37"}}""",
            RichiesteDocs.corpo(r, "ALm37"))
        assertJson("""{"requests":[{"deleteContentRange":{"range":{"startIndex":1,"endIndex":2}}}]}""", RichiesteDocs.corpo(r, null))
    }

    /** Una risposta di documents.get come quelle vere, con cose che Tratto non ha messo. */
    private val risposta = JSONObject(
        """
        {"revisionId":"r1","title":"Appunti","body":{"content":[
          {"endIndex":1,"sectionBreak":{"sectionStyle":{}}},
          {"startIndex":1,"endIndex":10,"paragraph":{"paragraphStyle":{"namedStyleType":"TITLE"},"elements":[{"textRun":{"content":"Appunti\uD83D\uDE00\n"}}]}},
          {"startIndex":10,"endIndex":12,"paragraph":{"paragraphStyle":{"namedStyleType":"NORMAL_TEXT","alignment":"CENTER"},"elements":[{"inlineObjectElement":{"inlineObjectId":"kix.a"}},{"textRun":{"content":"\n"}}]}},
          {"startIndex":12,"endIndex":14,"paragraph":{"paragraphStyle":{"namedStyleType":"NORMAL_TEXT"},"elements":[{"inlineObjectElement":{"inlineObjectId":"kix.sconosciuta"}},{"textRun":{"content":"\n"}}]}},
          {"startIndex":14,"endIndex":20,"paragraph":{"paragraphStyle":{"namedStyleType":"NORMAL_TEXT"},"elements":[{"inlineObjectElement":{"inlineObjectId":"kix.b"}},{"textRun":{"content":"ciao\n"}}]}},
          {"startIndex":20,"endIndex":30,"table":{}},
          {"startIndex":30,"endIndex":32,"paragraph":{"elements":[{}, {"textRun":{"content":"\n"}}]}},
          {"startIndex":32,"endIndex":33,"paragraph":{"elements":[{"textRun":{"content":"\n"}}]}}
        ]}}
        """.trimIndent(),
    )

    @Test
    fun letturaDelDocumento() {
        val p = LetturaDoc.paragrafi(risposta, mapOf("kix.a" to chiaveImmagine("a", "1000.0x1414.0"), "kix.b" to chiaveImmagine("b", "1000.0x1414.0")))
        assertEquals(7, p.size)
        assertEquals(ParagrafoDoc(1, 10, chiaveTesto("TITLE", "Appunti\uD83D\uDE00"), aspetto = "TITLE/START"), p[0])
        assertEquals(ParagrafoDoc(10, 12, chiaveImmagine("a", "1000.0x1414.0"), "kix.a", "NORMAL_TEXT/CENTER"), p[1])
        // Immagine non di Tratto, immagine con testo accanto, tabella, interruzione di pagina: da togliere.
        assertTrue(p[2].chiave.startsWith("X"))
        assertEquals("kix.sconosciuta", p[2].oggetto)
        assertTrue(p[3].chiave.startsWith("X"))
        assertTrue(p[4].chiave.startsWith("X"))
        assertTrue(p[5].chiave.startsWith("X"))
        // Senza paragraphStyle vale NORMAL_TEXT.
        assertEquals(ParagrafoDoc(32, 33, chiaveTesto("NORMAL_TEXT", ""), aspetto = "NORMAL_TEXT/START"), p[6])
        // Le chiavi sconosciute sono tutte diverse: nessuna resta per sbaglio.
        assertEquals(p.size, p.map { it.chiave }.toSet().size)
    }

    @Test
    fun immaginiInOrdine() {
        assertEquals(listOf("kix.a", "kix.sconosciuta"), LetturaDoc.immagini(risposta))
        assertEquals(emptyList<String>(), LetturaDoc.immagini(JSONObject()))
    }

    @Test
    fun misureDelleImmagini() {
        val a4 = Voluto.immagine("p", 1000f, 1414f)
        assertEquals(440.0, a4.larghezzaPt, 0.0)
        assertEquals(622.2, a4.altezzaPt, 0.0)
        assertEquals("1000.0x1414.0", a4.dimensione)
        // Molto alta: si ferma all'altezza di una pagina A4 e si stringe.
        val alta = Voluto.immagine("p", 1000f, 3000f)
        assertEquals(Voluto.ALTEZZA_MAX_PT, alta.altezzaPt, 0.0)
        assertEquals(230.0, alta.larghezzaPt, 0.0)
        // Orizzontale (pagina di un PDF).
        val larga = Voluto.immagine("p", 1000f, 707f)
        assertEquals(440.0, larga.larghezzaPt, 0.0)
        assertEquals(311.1, larga.altezzaPt, 0.0)
    }

    @Test
    fun testoPulito() {
        assertEquals("Riga uno due", pulisciTesto("Riga uno\ndue"))
        assertEquals("a b", pulisciTesto("a\tb\u0000"))
        assertEquals("privato", pulisciTesto("privato"))
        assertEquals("emoji \uD83D\uDE00", pulisciTesto("emoji \uD83D\uDE00  \n"))
    }

    @Test
    fun indirizziDelleImmagini() {
        assertEquals("https://lh3.googleusercontent.com/d/ID1", SincroniaDocs.indirizzoImmagine("ID1", 0))
        assertEquals("https://drive.google.com/uc?export=download&id=ID1", SincroniaDocs.indirizzoImmagine("ID1", 1))
        assertEquals("https://lh3.googleusercontent.com/d/ID1", SincroniaDocs.indirizzoImmagine("ID1", 2))
    }

    @Test
    fun erroriDelleImmaginiRiconosciuti() {
        assertTrue(SincroniaDocs.immagineNonScaricata(
            "Invalid requests[0].insertInlineImage: There was a problem retrieving the image. The provided image should be " +
                "publicly accessible, within size limit, and in supported formats.",
        ))
        assertTrue(SincroniaDocs.immagineNonScaricata("Invalid requests[12].replaceImage: Access to the provided image was forbidden."))
        assertFalse(SincroniaDocs.immagineNonScaricata(
            "Invalid requests[3].deleteContentRange: Index 12 must be less than the end index of the referenced segment, 10.",
        ))
        assertFalse(SincroniaDocs.immagineNonScaricata(null))
    }

    @Test
    fun pagineEscluseDalGruppo() {
        val voluti = listOf(
            Voluto.Testo("Titolo", StileParagrafo.TITOLO),
            Voluto.immagine("a", 1000f, 1414f), Voluto.Testo("sotto a"),
            Voluto.immagine("b", 1000f, 1414f), Voluto.Testo("sotto b"), Voluto.Testo("ancora b"),
            Voluto.immagine("c", 1000f, 1414f),
        )
        val r = SincroniaDocs.volutiSenza(voluti, setOf("b"))
        assertEquals(listOf("Titolo", "a", "sotto a", "c"), r.map { (it as? Voluto.Testo)?.testo ?: (it as Voluto.Immagine).pagina })
    }
}
