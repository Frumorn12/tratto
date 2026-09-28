package it.frumorn.tratto.googledocs

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** La lettura del documento per disegnarlo: stili ereditati, elenchi, immagini, oggetti posizionati. */
class ModelloDocTest {

    private val doc = JSONObject(
        """
        {"revisionId":"r7","title":"Appunti",
         "documentStyle":{"pageSize":{"width":{"magnitude":595.28,"unit":"PT"},"height":{"magnitude":841.89,"unit":"PT"}},
           "marginTop":{"magnitude":72,"unit":"PT"},"marginLeft":{"magnitude":50,"unit":"PT"}},
         "namedStyles":{"styles":[
           {"namedStyleType":"NORMAL_TEXT","textStyle":{"fontSize":{"magnitude":11,"unit":"PT"},"weightedFontFamily":{"fontFamily":"Arial","weight":400}},
            "paragraphStyle":{"lineSpacing":115,"alignment":"START"}},
           {"namedStyleType":"HEADING_1","textStyle":{"fontSize":{"magnitude":20,"unit":"PT"}},"paragraphStyle":{"spaceAbove":{"magnitude":20,"unit":"PT"}}}]},
         "lists":{"l1":{"listProperties":{"nestingLevels":[
           {"glyphType":"DECIMAL","glyphFormat":"%0.","indentFirstLine":{"magnitude":18,"unit":"PT"},"indentStart":{"magnitude":36,"unit":"PT"}},
           {"glyphSymbol":"○","indentFirstLine":{"magnitude":54,"unit":"PT"},"indentStart":{"magnitude":72,"unit":"PT"}}]}}},
         "inlineObjects":{"kix.i1":{"inlineObjectProperties":{"embeddedObject":{"size":{"width":{"magnitude":100,"unit":"PT"},"height":{"magnitude":50,"unit":"PT"}},
           "imageProperties":{"contentUri":"https://lh7.example/img"}}}}},
         "positionedObjects":{"kix.p1":{"positionedObjectProperties":{"embeddedObject":{"size":{"width":{"magnitude":80,"unit":"PT"},"height":{"magnitude":40,"unit":"PT"}},
           "imageProperties":{"contentUri":"https://lh7.example/pos"}},"positioning":{"layout":"IN_FRONT_OF_TEXT","leftOffset":{"magnitude":10,"unit":"PT"},"topOffset":{"magnitude":-5,"unit":"PT"}}}}},
         "body":{"content":[
           {"endIndex":1,"sectionBreak":{}},
           {"startIndex":1,"endIndex":8,"paragraph":{"paragraphStyle":{"namedStyleType":"HEADING_1"},"elements":[
             {"startIndex":1,"textRun":{"content":"Titolo\n","textStyle":{"bold":true}}}]}},
           {"startIndex":8,"endIndex":20,"paragraph":{"paragraphStyle":{"namedStyleType":"NORMAL_TEXT"},"positionedObjectIds":["kix.p1"],"elements":[
             {"startIndex":8,"textRun":{"content":"ciao ","textStyle":{}}},
             {"startIndex":13,"inlineObjectElement":{"inlineObjectId":"kix.i1","textStyle":{}}},
             {"startIndex":14,"textRun":{"content":"rosso\n","textStyle":{"foregroundColor":{"color":{"rgbColor":{"red":1}}},"fontSize":{"magnitude":14,"unit":"PT"}}}}]}},
           {"startIndex":20,"endIndex":25,"paragraph":{"bullet":{"listId":"l1"},"paragraphStyle":{"namedStyleType":"NORMAL_TEXT"},"elements":[{"startIndex":20,"textRun":{"content":"uno\n"}}]}},
           {"startIndex":25,"endIndex":30,"paragraph":{"bullet":{"listId":"l1","nestingLevel":1},"paragraphStyle":{"namedStyleType":"NORMAL_TEXT"},"elements":[{"startIndex":25,"textRun":{"content":"sub\n"}}]}},
           {"startIndex":30,"endIndex":35,"paragraph":{"bullet":{"listId":"l1"},"paragraphStyle":{"namedStyleType":"NORMAL_TEXT"},"elements":[{"startIndex":30,"textRun":{"content":"due\n"}}]}},
           {"startIndex":35,"endIndex":37,"paragraph":{"paragraphStyle":{"namedStyleType":"NORMAL_TEXT"},"elements":[{"startIndex":35,"pageBreak":{}},{"startIndex":36,"textRun":{"content":"\n"}}]}},
           {"startIndex":37,"endIndex":60,"table":{"tableRows":[{"tableCells":[{"content":[
             {"startIndex":39,"endIndex":45,"paragraph":{"elements":[{"startIndex":39,"textRun":{"content":"cella\n"}}]}}]}]}]}}
         ]}}
        """.trimIndent(),
    )

    @Test
    fun paginaEMargini() {
        val m = ModelloDoc.da(doc)
        assertEquals("r7", m.revisione)
        assertEquals(595.28, m.larghezza, 0.001)
        assertEquals(50.0, m.margineSinistro, 0.001)
        // Quello che manca vale come in Documenti: un pollice.
        assertEquals(72.0, m.margineDestro, 0.001)
    }

    @Test
    fun stiliEreditati() {
        val m = ModelloDoc.da(doc)
        val titolo = m.paragrafi[0]
        assertEquals("HEADING_1", titolo.stile)
        assertEquals(20.0, titolo.sopra, 0.001)
        assertEquals(115.0, titolo.interlinea, 0.001)
        val f = titolo.pezzi[0].formato
        assertTrue(f.grassetto)
        assertEquals(20.0, f.punti, 0.001)
        assertEquals("Arial", f.famiglia)
        // Il pezzo rosso: colore e dimensione sue, il resto dal testo normale.
        val rosso = m.paragrafi[1].pezzi.last().formato
        assertEquals(0xFFFF0000.toInt(), rosso.colore)
        assertEquals(14.0, rosso.punti, 0.001)
        assertFalse(rosso.grassetto)
    }

    @Test
    fun immaginiEOggettiPosizionati() {
        val m = ModelloDoc.da(doc)
        val p = m.paragrafi[1]
        assertEquals("ciao ￼rosso", p.testo)
        assertEquals("kix.i1", p.pezzi[1].immagine)
        assertEquals(ImmagineDoc(100.0, 50.0, "https://lh7.example/img"), m.immagini["kix.i1"])
        assertEquals(listOf("kix.p1"), p.posizionati)
        assertEquals(PosizionatoDoc(80.0, 40.0, "https://lh7.example/pos", 10.0, -5.0, "IN_FRONT_OF_TEXT"), m.posizionati["kix.p1"])
    }

    @Test
    fun elenchiNumerati() {
        val m = ModelloDoc.da(doc)
        assertEquals("1.", m.paragrafi[2].elenco)
        assertEquals("○", m.paragrafi[3].elenco)
        // Dopo la voce di secondo livello il primo livello continua.
        assertEquals("2.", m.paragrafi[4].elenco)
        assertEquals(18.0, m.paragrafi[2].rientroPrimaRiga, 0.001)
        assertEquals(72.0, m.paragrafi[3].rientroInizio, 0.001)
        assertNull(m.paragrafi[1].elenco)
    }

    @Test
    fun interruzioneDiPaginaETabelle() {
        val m = ModelloDoc.da(doc)
        assertTrue(m.paragrafi[5].interruzione)
        // Le celle delle tabelle, per ora, come paragrafi uno dopo l'altro.
        assertEquals("cella", m.paragrafi.last().testo)
    }
}
