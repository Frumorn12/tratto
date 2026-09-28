package it.frumorn.tratto.googledocs

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Il paragrafo modificato da Tratto: il suo testo nel documento e le richieste per cambiarlo. */
class TestoCondivisoTest {

    private fun doc(testo: String, inizio: Int, fine: Int) = JSONObject(
        """
        {"revisionId":"r3","namedRanges":{"tratto-testo-1":{"namedRanges":[{"namedRangeId":"kix.nr9","ranges":[{"startIndex":$inizio,"endIndex":$fine}]}]}},
         "body":{"content":[{"endIndex":1,"sectionBreak":{}},
           {"startIndex":1,"endIndex":${1 + testo.length},"paragraph":{"elements":[{"startIndex":1,"textRun":{"content":${JSONObject.quote(testo)}}}]}}]}}
        """.trimIndent(),
    )

    @Test
    fun testoDellIntervallo() {
        // "Primo\nCiao mondo\n": l'intervallo copre "Ciao mondo\n".
        val p = TestoCondiviso.leggi(doc("Primo\nCiao mondo\n", 7, 18), "tratto-testo-1")!!
        assertEquals("Ciao mondo", p.testo)
        assertEquals(7, p.inizio)
        assertEquals("kix.nr9", p.id)
        assertNull(TestoCondiviso.leggi(doc("x\n", 1, 3), "altro"))
    }

    private fun tipi(r: JSONArray) = (0 until r.length()).map { r.getJSONObject(it).keys().next() }

    @Test
    fun soloLaParteDiversa() {
        val r = TestoCondiviso.richieste(7, "Ciao mondo", "Ciao bel mondo")
        assertEquals(listOf("insertText"), tipi(r))
        val ins = r.getJSONObject(0).getJSONObject("insertText")
        assertEquals(12, ins.getJSONObject("location").getInt("index"))
        assertEquals("bel ", ins.getString("text"))
    }

    @Test
    fun cancellazioneESostituzione() {
        val r = TestoCondiviso.richieste(1, "Ciao mondo", "Ciao gente")
        assertEquals(listOf("deleteContentRange", "insertText"), tipi(r))
        val rg = r.getJSONObject(0).getJSONObject("deleteContentRange").getJSONObject("range")
        assertEquals(6, rg.getInt("startIndex"))
        assertEquals(11, rg.getInt("endIndex"))
        assertEquals(0, TestoCondiviso.richieste(1, "uguale", "uguale").length())
    }

    @Test
    fun aCapoEEmoji() {
        // Invio in mezzo: un paragrafo in piu' nel documento.
        val r = TestoCondiviso.richieste(1, "unodue", "uno\ndue")
        assertEquals("\n", r.getJSONObject(0).getJSONObject("insertText").getString("text"))
        // Una emoji cambiata non si spezza a meta'.
        val e = TestoCondiviso.richieste(1, "a😀b", "a😁b")
        val rg = e.getJSONObject(0).getJSONObject("deleteContentRange").getJSONObject("range")
        assertEquals(2, rg.getInt("startIndex"))
        assertEquals(4, rg.getInt("endIndex"))
        assertEquals("😁", e.getJSONObject(1).getJSONObject("insertText").getString("text"))
    }
}
