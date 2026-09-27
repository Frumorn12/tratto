package it.frumorn.tratto.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class OggettiTest {

    private val ricco = TestoRicco(
        listOf(
            Paragrafo(listOf(Frammento("Titolo della nota")), stile = StileParagrafo.TITOLO, allineamento = Allineamento.CENTRO),
            Paragrafo(
                listOf(
                    Frammento("Normale, "),
                    Frammento("grassetto", grassetto = true),
                    Frammento(" e ", corsivo = true, sottolineato = true, barrato = true),
                    Frammento("rosso", colore = 0xFFE0354B.toInt(), evidenziato = 0x80F2B705.toInt(), punti = 18.5f, carattere = Carattere.SERIF),
                ),
            ),
            Paragrafo(),
            Paragrafo(listOf(Frammento("uno")), elenco = Elenco.NUMERATO, rientro = 1),
            Paragrafo(listOf(Frammento("due", carattere = Carattere.MONO)), elenco = Elenco.PUNTATO, allineamento = Allineamento.GIUSTIFICATO),
        ),
    )

    // ------------------------------------------------------------ formato su disco

    @Test
    fun andataERitorno() {
        val oggetti = listOf(
            Immagine("i1", "immagini/abc123.webp", 100.25f, 200f, 300.5f, 150f),
            Testo("t1", 80f, 120.126f, 400f, ricco),
        )
        val letti = FormatoOggetti.leggi(FormatoOggetti.scrivi(oggetti))

        assertEquals(oggetti[0], letti[0])
        val t = letti[1] as Testo
        assertEquals(ricco, t.contenuto)
        assertEquals(120.13f, t.y, 0.0001f)
        assertEquals("Titolo della nota\nNormale, grassetto e rosso\n\nuno\ndue", t.contenuto.testoSemplice)
    }

    @Test
    fun siScrivonoSoloIValoriDiversiDalPredefinito() {
        val j = JSONObject(FormatoOggetti.scrivi(listOf(Testo("t", 0f, 0f, 300f, TestoRicco.semplice("ciao")))))
        assertEquals(1, j.getInt("versione"))
        val p = j.getJSONArray("oggetti").getJSONObject(0).getJSONArray("paragrafi").getJSONObject(0)
        assertEquals(setOf("frammenti"), p.keys().asSequence().toSet())
        assertEquals(setOf("testo"), p.getJSONArray("frammenti").getJSONObject(0).keys().asSequence().toSet())
        // I numeri tondi restano interi.
        assertEquals("0", j.getJSONArray("oggetti").getJSONObject(0).get("x").toString())
    }

    @Test
    fun coloriInEsadecimale() {
        assertEquals("#E0354B", FormatoOggetti.colore(0xFFE0354B.toInt()))
        assertEquals("#80F2B705", FormatoOggetti.colore(0x80F2B705.toInt()))
        assertEquals(0xFFE0354B.toInt(), FormatoOggetti.leggiColore("#e0354b"))
        assertEquals(0x80F2B705.toInt(), FormatoOggetti.leggiColore("#80F2B705"))
        assertNull(FormatoOggetti.leggiColore("rosso"))
        assertNull(FormatoOggetti.leggiColore("#12345"))
    }

    @Test
    fun campiETipiSconosciutiSiIgnorano() {
        val json = """
            {"versione":1,"futuro":true,"oggetti":[
              {"tipo":"forma","id":"f1","x":1,"y":2},
              {"tipo":"immagine","id":"i1","file":"immagini/a.webp","x":1,"y":2,"w":3,"h":4,"rotazione":45},
              {"tipo":"testo","id":"t1","x":1,"y":2,"w":10,"paragrafi":[{"stile":"boh","frammenti":[{"testo":"a\nb","punti":1000}]}]}
            ]}
        """.trimIndent()
        val letti = FormatoOggetti.leggi(json)

        assertEquals(listOf("i1", "t1"), letti.map { it.id })
        val t = letti[1] as Testo
        assertEquals("la larghezza minima vale anche per i file scritti a mano", Testo.LARGHEZZA_MIN, t.larghezza)
        assertEquals(StileParagrafo.NORMALE, t.contenuto.paragrafi[0].stile)
        assertEquals("a b", t.contenuto.testoSemplice)
        assertEquals(TestoRicco.PUNTI_MAX, t.contenuto.paragrafi[0].frammenti[0].punti)
    }

    @Test
    fun immaginiConPercorsiFuoriDallaNotaSiScartano() {
        for (file in listOf("../altro/x.webp", "/sdcard/x.webp", "immagini/../../x.webp", "immagini/x.exe", "x.webp")) {
            val json = """{"versione":1,"oggetti":[{"tipo":"immagine","id":"i","file":"$file","x":0,"y":0,"w":1,"h":1}]}"""
            assertTrue(file, FormatoOggetti.leggi(json).isEmpty())
        }
    }

    @Test
    fun versionePiuRecenteOFileRovinato() {
        for (json in listOf("""{"versione":2,"oggetti":[]}""", "non json", "")) {
            try {
                FormatoOggetti.leggi(json)
                fail("serviva un errore per $json")
            } catch (_: IOException) {
            }
        }
    }

    @Test
    fun testoVuotoHaUnParagrafo() {
        val t = FormatoOggetti.leggiTesto(org.json.JSONArray())
        assertEquals(TestoRicco.VUOTO, t)
        assertTrue(t.vuoto)
    }

    // ------------------------------------------------------------ operazioni sul testo ricco

    @Test
    fun formattaSpezzaIFrammentiEAttraversaIParagrafi() {
        val t = TestoRicco.semplice("abcdef\nghi")
        val g = t.formatta(3, 8) { f, _ -> f.copy(grassetto = true) }

        assertEquals(listOf(Frammento("abc"), Frammento("def", grassetto = true)), g.paragrafi[0].frammenti)
        assertEquals(listOf(Frammento("g", grassetto = true), Frammento("hi")), g.paragrafi[1].frammenti)
        assertEquals(t.testoSemplice, g.testoSemplice)

        // Tolto di nuovo, i frammenti si riuniscono.
        val n = g.formatta(0, g.lunghezza) { f, _ -> f.copy(grassetto = false) }
        assertEquals(t, n)
    }

    @Test
    fun formatoDellaSelezioneEDelCursore() {
        val t = TestoRicco.semplice("abcdef").formatta(0, 3) { f, _ -> f.copy(grassetto = true, colore = 0xFF2F6BFF.toInt()) }

        val tutto = t.formato(0, 6)
        assertFalse("grassetto solo a meta'", tutto.grassetto)
        assertNull("colore misto", tutto.colore)
        assertEquals(14f, tutto.punti)

        assertTrue(t.formato(1, 2).grassetto)
        // Il cursore prende il formato del carattere prima...
        assertTrue(t.formato(3, 3).grassetto)
        assertFalse(t.formato(4, 4).grassetto)
        // ...e all'inizio del paragrafo quello del primo.
        assertTrue(t.formato(0, 0).grassetto)
    }

    @Test
    fun paragrafiToccatiDallaSelezione() {
        val t = TestoRicco.semplice("uno\ndue\ntre")
        assertEquals(0..0, t.paragrafiToccati(3, 3))
        assertEquals(1..1, t.paragrafiToccati(4, 4))
        assertEquals(0..1, t.paragrafiToccati(2, 5))
        // Selezionare una riga intera con il suo "a capo" non tocca la riga dopo.
        assertEquals(0..0, t.paragrafiToccati(0, 4))
        assertEquals(0..2, t.paragrafiToccati(0, t.lunghezza))

        val e = t.formattaParagrafi(5, 9) { it.copy(elenco = Elenco.PUNTATO) }
        assertEquals(listOf(Elenco.NESSUNO, Elenco.PUNTATO, Elenco.PUNTATO), e.paragrafi.map { it.elenco })
        assertEquals(Elenco.PUNTATO, e.formato(5, 9).elenco)
        assertNull(e.formato(0, 9).elenco)
    }

    @Test
    fun scalaLeDimensioni() {
        val t = TestoRicco(listOf(Paragrafo(listOf(Frammento("a"), Frammento("b", punti = 10f)), stile = StileParagrafo.TITOLO)))
        val s = t.scalato(1.5f)
        assertEquals(listOf(48f, 15f), s.paragrafi[0].frammenti.map { it.punti })
    }

    @Test
    fun testoSempliceDaRigheIncollate() {
        val t = TestoRicco.semplice("prima\r\nseconda\n\nquarta")
        assertEquals(4, t.paragrafi.size)
        assertEquals("prima\nseconda\n\nquarta", t.testoSemplice)
        assertTrue(t.paragrafi[2].frammenti.isEmpty())
    }
}
