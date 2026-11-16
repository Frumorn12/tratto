package it.frumorn.tratto.googledocs

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** I corpi JSON di batchUpdate e la lettura di documents.get. */
class DocsRequestsTest {

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
        DocsRequests.inserisciTesto(5, "Ciao\n"),
    )

    @Test
    fun cancella() = assertJson(
        """{"deleteContentRange":{"range":{"startIndex":3,"endIndex":9}}}""",
        DocsRequests.cancella(3, 9),
    )

    @Test
    fun inserisciImmagine() = assertJson(
        """{"insertInlineImage":{"location":{"index":7},"uri":"https://lh3.googleusercontent.com/d/abc",
            "objectSize":{"width":{"magnitude":440.0,"unit":"PT"},"height":{"magnitude":622.2,"unit":"PT"}}}}""",
        DocsRequests.inserisciImmagine(7, "https://lh3.googleusercontent.com/d/abc", 440.0, 622.2),
    )

    @Test
    fun intervalli() {
        assertJson("""{"createNamedRange":{"name":"tratto/a/x1","range":{"startIndex":4,"endIndex":9}}}""", DocsRequests.creaIntervallo("tratto/a/x1", 4, 9))
        assertJson("""{"deleteNamedRange":{"namedRangeId":"kix.nr1"}}""", DocsRequests.togliIntervallo("kix.nr1"))
    }

    @Test
    fun stileDiUnaFigura() = assertJson(
        """{"updateParagraphStyle":{"range":{"startIndex":1,"endIndex":3},
            "paragraphStyle":{"namedStyleType":"NORMAL_TEXT","alignment":"START","indentStart":{"magnitude":44.0,"unit":"PT"},
              "indentFirstLine":{"magnitude":44.0,"unit":"PT"},"spaceAbove":{"magnitude":12.0,"unit":"PT"},
              "spaceBelow":{"magnitude":0.0,"unit":"PT"},"lineSpacing":100},
            "fields":"namedStyleType,alignment,indentStart,indentFirstLine,spaceAbove,spaceBelow,lineSpacing"}}""",
        DocsRequests.stileParagrafo(1, 3, DocsContent.stileFigura(Aspetto(12, 44)), DocsContent.CAMPI_PARAGRAFO),
    )

    @Test
    fun formatoDelTesto() = assertJson(
        """{"updateTextStyle":{"range":{"startIndex":5,"endIndex":9},
            "textStyle":{"bold":true,"foregroundColor":{"color":{"rgbColor":{"red":1.0,"green":0.0,"blue":0.0}}},
              "fontSize":{"magnitude":12.5,"unit":"PT"},"weightedFontFamily":{"fontFamily":"Roboto Mono"}},
            "fields":"bold,italic,underline,strikethrough,foregroundColor,backgroundColor,fontSize,weightedFontFamily"}}""",
        DocsRequests.stileTesto(5, 9, DocsContent.stilePezzo(PezzoDocs(0, 4, grassetto = true, colore = 0xFFFF0000.toInt(), punti = 12.5, carattere = "Roboto Mono")), DocsContent.CAMPI_TESTO),
    )

    @Test
    fun elenco() = assertJson(
        """{"createParagraphBullets":{"range":{"startIndex":2,"endIndex":20},"bulletPreset":"NUMBERED_DECIMAL_ALPHA_ROMAN"}}""",
        DocsRequests.elenco(2, 20, "NUMBERED_DECIMAL_ALPHA_ROMAN"),
    )

    @Test
    fun paginaA4() = assertJson(
        """{"updateDocumentStyle":{"documentStyle":{"pageSize":{"width":{"magnitude":595.28,"unit":"PT"},
            "height":{"magnitude":841.89,"unit":"PT"}}},"fields":"pageSize"}}""",
        DocsRequests.paginaA4(),
    )

    @Test
    fun corpoConESenzaRevisione() {
        val r = JSONArray().put(DocsRequests.cancella(1, 2))
        assertJson("""{"requests":[{"deleteContentRange":{"range":{"startIndex":1,"endIndex":2}}}],"writeControl":{"requiredRevisionId":"ALm37"}}""",
            DocsRequests.corpo(r, "ALm37"))
        assertJson("""{"requests":[{"deleteContentRange":{"range":{"startIndex":1,"endIndex":2}}}]}""", DocsRequests.corpo(r, null))
    }

    /** Una risposta di documents.get come quelle vere, con cose che Tratto non ha messo. */
    private val risposta = JSONObject(
        """
        {"revisionId":"r1","title":"Appunti","body":{"content":[
          {"endIndex":1,"sectionBreak":{"sectionStyle":{}}},
          {"startIndex":1,"endIndex":10,"paragraph":{"paragraphStyle":{"namedStyleType":"TITLE"},"elements":[{"startIndex":1,"textRun":{"content":"Appunti\uD83D\uDE00\n"}}]}},
          {"startIndex":10,"endIndex":12,"paragraph":{"paragraphStyle":{"namedStyleType":"NORMAL_TEXT","alignment":"CENTER"},"elements":[{"startIndex":10,"inlineObjectElement":{"inlineObjectId":"kix.a"}},{"startIndex":11,"textRun":{"content":"\n"}}]}},
          {"startIndex":12,"endIndex":18,"paragraph":{"paragraphStyle":{"namedStyleType":"NORMAL_TEXT"},"elements":[{"startIndex":12,"textRun":{"content":"x"}},{"startIndex":13,"inlineObjectElement":{"inlineObjectId":"kix.b"}},{"startIndex":14,"textRun":{"content":"ciao\n"}}]}},
          {"startIndex":18,"endIndex":30,"table":{}},
          {"startIndex":30,"endIndex":32,"paragraph":{"elements":[{"startIndex":30,"pageBreak":{}}, {"startIndex":31,"textRun":{"content":"\n"}}]}},
          {"startIndex":32,"endIndex":33,"paragraph":{"elements":[{"startIndex":32,"textRun":{"content":"\n"}}]}}
        ]},
        "namedRanges":{"tratto/a/x1":{"name":"tratto/a/x1","namedRanges":[
          {"namedRangeId":"kix.nr1","name":"tratto/a/x1","ranges":[{"startIndex":1,"endIndex":5},{"startIndex":8,"endIndex":10}]}]}}}
        """.trimIndent(),
    )

    @Test
    fun letturaDelDocumento() {
        val d = DocLetto.da(risposta)
        assertEquals("r1", d.revisione)
        assertEquals(6, d.elementi.size)
        assertEquals(33, d.fine)
        val t = d.elementi[0]
        assertEquals("Appunti\uD83D\uDE00", t.testo)
        assertEquals("TITLE", t.stile!!.getString("namedStyleType"))
        assertEquals("kix.a", d.elementi[1].soloImmagine)
        // Immagine con testo accanto: si sa dov'e', ma il paragrafo non e' solo suo.
        assertEquals(listOf("kix.b" to 13), d.elementi[2].oggetti)
        assertEquals("xciao", d.elementi[2].testo)
        assertEquals(null, d.elementi[2].soloImmagine)
        assertFalse(d.elementi[3].paragrafo)
        assertTrue(d.elementi[4].altro)
        // L'intervallo va dal primo inizio all'ultima fine delle sue parti.
        assertEquals(listOf(IntervalloDoc("kix.nr1", "tratto/a/x1", 1, 10)), d.intervalli)
    }

    @Test
    fun misureDelleFigure() {
        val riga = BloccoPagina.Disegno("t1", Riquadro(100f, 100f, 600f, 150f), emptyList(), emptyList())
        assertEquals(220.0 to 22.0, DocsContent.misure(riga))
        // Molto alta: si ferma all'altezza massima e si stringe.
        val alta = BloccoPagina.Intera("pdf", Riquadro(0f, 0f, 1000f, 3000f))
        val (w, h) = DocsContent.misure(alta)
        assertEquals(DocsContent.ALTEZZA_MAX_PT, h, 0.0)
        assertEquals(230.0, w, 0.0)
    }

    @Test
    fun testoPulito() {
        assertEquals("a b c", DocsContent.pulisci("a\tb\u2028c\uE000\uFFFC"))
    }
}
