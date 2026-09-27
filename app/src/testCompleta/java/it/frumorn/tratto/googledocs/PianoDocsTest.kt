package it.frumorn.tratto.googledocs

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Il piano delle modifiche applicato al documento finto: dopo ogni sincronizzazione il documento
 * deve essere esattamente la nota, e le immagini delle pagine rimaste devono restare le stesse.
 */
class PianoDocsTest {

    /** Una pagina della nota di prova: [versione] cambia quando cambia il disegno. */
    private data class Pag(val id: String, val versione: Int = 1, val testo: List<String> = emptyList(), val w: Float = 1000f, val h: Float = 1414f)

    /** Tratto e il suo documento, come SincroniaDocs ma senza rete: le immagini sono indirizzi finti. */
    private class Specchio(stileDalPrimo: Boolean = false) {
        val doc = DocFinto(stileDalPrimo)

        /** Pagina -> (id dell'immagine, dimensione, versione caricata), come in CollegamentoDocs. */
        val salvate = HashMap<String, Triple<String, String, Int>>()

        fun sincronizza(titolo: String, pagine: List<Pag>): JSONArray {
            val voluti = volutiDi(titolo, pagine)
            val immagini = salvate.entries.associate { (p, v) -> v.first to chiaveImmagine(p, v.second) }
            val piano = PianoDocs(LetturaDoc.paragrafi(doc.json(), immagini), voluti)
            val uri = HashMap<String, String>()
            for (p in pagine) {
                if (p.id !in piano.tenute || salvate[p.id]?.third != p.versione) uri[p.id] = indirizzo(p)
            }
            assertEquals(pagine.filter { it.id !in piano.tenute }.map { it.id }, piano.daInserire.map { it.pagina })
            val richieste = piano.richieste(uri)
            if (richieste.length() > 0) doc.applica(RichiesteDocs.corpo(richieste, doc.revisione.toString()))

            // Come dopo il batchUpdate vero: gli id delle immagini, in ordine.
            val ids = LetturaDoc.immagini(doc.json())
            val volute = voluti.filterIsInstance<Voluto.Immagine>()
            assertEquals(volute.size, ids.size)
            salvate.clear()
            for ((k, v) in volute.withIndex()) salvate[v.pagina] = Triple(ids[k], v.dimensione, pagine.first { it.id == v.pagina }.versione)
            controlla(titolo, pagine)
            return richieste
        }

        fun controlla(titolo: String, pagine: List<Pag>) {
            val attesi = ArrayList<String>()
            attesi += "TITLE START $titolo"
            for (p in pagine) {
                attesi += "NORMAL_TEXT CENTER [${indirizzo(p)}]"
                p.testo.forEach { attesi += "NORMAL_TEXT START $it" }
            }
            val reali = doc.paragrafi().map { par ->
                if (par.immagini.isNotEmpty()) "${par.stile} ${par.allineamento} ${par.testo}${par.immagini.map { it.uri }}"
                else "${par.stile} ${par.allineamento} ${par.testo}"
            }
            assertEquals(attesi, reali)
        }

        fun oggetto(pagina: String) = salvate.getValue(pagina).first

        private fun indirizzo(p: Pag) = "https://img.example/${p.id}/${p.versione}"
    }

    private companion object {
        fun volutiDi(titolo: String, pagine: List<Pag>): List<Voluto> =
            listOf(Voluto.Testo(titolo, StileParagrafo.TITOLO)) +
                pagine.flatMap { p -> listOf(Voluto.immagine(p.id, p.w, p.h)) + p.testo.map { Voluto.Testo(it) } }
    }

    private fun JSONArray.tipi(): List<String> = (0 until length()).map { getJSONObject(it).keys().next() }

    @Test
    fun documentoNuovo() {
        val s = Specchio()
        s.sincronizza("Appunti", listOf(Pag("a"), Pag("b"), Pag("c")))
    }

    @Test
    fun nienteDaFareSeNonCambiaNiente() {
        val s = Specchio()
        val pagine = listOf(Pag("a"), Pag("b"))
        s.sincronizza("Appunti", pagine)
        assertEquals(0, s.sincronizza("Appunti", pagine).length())
    }

    @Test
    fun disegnoCambiatoSoloSostituzione() {
        val s = Specchio()
        s.sincronizza("Appunti", listOf(Pag("a"), Pag("b"), Pag("c")))
        val prima = s.oggetto("b")
        val r = s.sincronizza("Appunti", listOf(Pag("a"), Pag("b", 2), Pag("c")))
        // Una sola richiesta, per id: il testo intorno non si muove e chi guarda non vede sfarfallare.
        assertEquals(listOf("replaceImage"), r.tipi())
        assertEquals(prima, r.getJSONObject(0).getJSONObject("replaceImage").getString("imageObjectId"))
        assertEquals(prima, s.oggetto("b"))
    }

    @Test
    fun paginaAggiuntaInFondoNonToccaLeAltre() {
        val s = Specchio()
        s.sincronizza("Appunti", listOf(Pag("a"), Pag("b")))
        val a = s.oggetto("a")
        val b = s.oggetto("b")
        val r = s.sincronizza("Appunti", listOf(Pag("a"), Pag("b"), Pag("c")))
        assertEquals(1, r.tipi().count { it == "insertInlineImage" })
        assertFalse("deleteContentRange" in r.tipi())
        assertEquals(a, s.oggetto("a"))
        assertEquals(b, s.oggetto("b"))
    }

    @Test
    fun paginaInseritaInMezzo() {
        val s = Specchio()
        s.sincronizza("Appunti", listOf(Pag("a"), Pag("c")))
        val r = s.sincronizza("Appunti", listOf(Pag("a"), Pag("b"), Pag("c")))
        assertEquals(1, r.tipi().count { it == "insertInlineImage" })
    }

    @Test
    fun pagineTolte() {
        for (tolta in listOf("a", "b", "c")) {
            val s = Specchio()
            s.sincronizza("Appunti", listOf(Pag("a"), Pag("b"), Pag("c")))
            val r = s.sincronizza("Appunti", listOf(Pag("a"), Pag("b"), Pag("c")).filter { it.id != tolta })
            assertFalse("insertInlineImage" in r.tipi())
        }
    }

    @Test
    fun ultimaPaginaTolta_ancheConLoStileDelPrimoParagrafo() {
        for (dalPrimo in listOf(false, true)) {
            val s = Specchio(dalPrimo)
            s.sincronizza("Appunti", listOf(Pag("a"), Pag("b", testo = listOf("sotto b"))))
            s.sincronizza("Appunti", listOf(Pag("a")))
            s.sincronizza("Appunti", emptyList())
            s.sincronizza("Appunti", listOf(Pag("a", testo = listOf("uno", "due"))))
        }
    }

    @Test
    fun titoloCambiatoSenzaToccareLeImmagini() {
        val s = Specchio()
        s.sincronizza("Appunti", listOf(Pag("a"), Pag("b")))
        val a = s.oggetto("a")
        val r = s.sincronizza("Appunti di fisica", listOf(Pag("a"), Pag("b")))
        assertFalse("insertInlineImage" in r.tipi())
        assertFalse("replaceImage" in r.tipi())
        assertEquals(a, s.oggetto("a"))
        s.sincronizza("", listOf(Pag("a"), Pag("b")))
    }

    @Test
    fun ordineCambiato() {
        val s = Specchio()
        s.sincronizza("Appunti", listOf(Pag("a"), Pag("b"), Pag("c"), Pag("d")))
        s.sincronizza("Appunti", listOf(Pag("d"), Pag("a"), Pag("c"), Pag("b")))
    }

    @Test
    fun dimensioneCambiataRifaLImmagine() {
        val s = Specchio()
        s.sincronizza("Appunti", listOf(Pag("a"), Pag("b")))
        val r = s.sincronizza("Appunti", listOf(Pag("a"), Pag("b", w = 1000f, h = 700f)))
        assertFalse("replaceImage" in r.tipi())
        assertEquals(1, r.tipi().count { it == "insertInlineImage" })
    }

    @Test
    fun testoSottoLePagine() {
        val s = Specchio()
        s.sincronizza("Appunti", listOf(Pag("a", testo = listOf("Prima riga", "Seconda")), Pag("b")))
        val a = s.oggetto("a")
        val r = s.sincronizza("Appunti", listOf(Pag("a", testo = listOf("Prima riga", "Seconda corretta", "Terza")), Pag("b", testo = listOf("Sotto b"))))
        assertFalse("insertInlineImage" in r.tipi())
        assertEquals(a, s.oggetto("a"))
        s.sincronizza("Appunti", listOf(Pag("a"), Pag("b", testo = listOf("Sotto b"))))
        s.sincronizza("Appunti", listOf(Pag("a"), Pag("b", testo = listOf("", "vuota sopra"))))
    }

    @Test
    fun leModificheFatteNelDocumentoSiTolgono() {
        val s = Specchio()
        s.sincronizza("Appunti", listOf(Pag("a"), Pag("b")))
        val d = s.doc
        // Qualcuno scrive nel documento: testo in cima, un'immagine sua, testo accanto a un'immagine di Tratto.
        d.inserisciTesto(1, "Ciao a tutti\n")
        val paragrafi = d.paragrafi()
        d.inserisciImmagine(paragrafi[2].inizio, "https://altro.example/foto.png")
        d.inserisciTesto(d.paragrafi().last().fine - 1, "\nin fondo")
        d.stile(1, 2, "HEADING_1", "START")
        s.sincronizza("Appunti", listOf(Pag("a"), Pag("b")))
        // E se l'immagine di una pagina finisce accanto a del testo, la pagina si reinserisce.
        d.inserisciTesto(d.paragrafi()[1].inizio, "scritto accanto ")
        s.sincronizza("Appunti", listOf(Pag("a"), Pag("b")))
    }

    @Test
    fun stileDiUnImmagineCambiatoAMano() {
        val s = Specchio()
        s.sincronizza("Appunti", listOf(Pag("a"), Pag("b")))
        val b = s.oggetto("b")
        s.doc.stile(s.doc.paragrafi()[2].inizio, s.doc.paragrafi()[2].inizio + 1, "HEADING_1", "END")
        // Solo lo stile torna com'era: l'immagine non si ricarica.
        assertEquals(listOf("updateParagraphStyle"), s.sincronizza("Appunti", listOf(Pag("a"), Pag("b"))).tipi())
        assertEquals(b, s.oggetto("b"))
    }

    @Test
    fun statoPersoRicostruisceTutto() {
        val s = Specchio()
        s.sincronizza("Appunti", listOf(Pag("a"), Pag("b")))
        s.salvate.clear()
        val r = s.sincronizza("Appunti", listOf(Pag("a"), Pag("b")))
        assertEquals(2, r.tipi().count { it == "insertInlineImage" })
    }

    @Test
    fun documentoConSoloUnParagrafoDiTestoGiaGiusto() {
        val s = Specchio()
        s.doc.inserisciTesto(1, "Appunti")
        s.doc.stile(1, 2, "TITLE", "START")
        val r = s.sincronizza("Appunti", listOf(Pag("a")))
        assertFalse("deleteContentRange" in r.tipi())
    }

    @Test
    fun strutturaUgualeQuandoNonCambiaNiente() {
        val s = Specchio()
        val pagine = listOf(Pag("a"), Pag("b"))
        s.sincronizza("Appunti", pagine)
        val immagini = s.salvate.entries.associate { (p, v) -> v.first to chiaveImmagine(p, v.second) }
        val piano = PianoDocs(LetturaDoc.paragrafi(s.doc.json(), immagini), volutiDi("Appunti", pagine))
        assertTrue(piano.strutturaUguale)
        assertEquals(setOf("a", "b"), piano.tenute.keys)
        val altro = PianoDocs(LetturaDoc.paragrafi(s.doc.json(), immagini), volutiDi("Appunti", pagine + Pag("c")))
        assertFalse(altro.strutturaUguale)
    }

    /** Molte sequenze a caso di stati della nota e di modifiche fatte a mano nel documento. */
    @Test
    fun sequenzeACaso() {
        val rnd = Random(20260927)
        repeat(2000) { giro ->
            val s = Specchio(stileDalPrimo = giro % 2 == 1)
            val versioni = HashMap<String, Int>()
            repeat(8) {
                val ids = (0 until 9).map { "p$it" }.shuffled(rnd).take(rnd.nextInt(0, 7))
                val pagine = ids.map { id ->
                    if (rnd.nextInt(4) == 0) versioni[id] = (versioni[id] ?: 1) + 1
                    val testo = List(if (rnd.nextInt(3) == 0) rnd.nextInt(1, 3) else 0) { k -> if (rnd.nextInt(5) == 0) "" else "testo $id $k" }
                    Pag(id, versioni[id] ?: 1, testo)
                }
                val titolo = listOf("Appunti", "Appunti 2", "", "testo p1 0")[rnd.nextInt(4)]
                if (rnd.nextInt(3) == 0) modificaAMano(s.doc, rnd)
                s.sincronizza(titolo, pagine)
            }
        }
    }

    /** Quello che potrebbe fare una persona nel documento: scrivere, cancellare, aggiungere immagini. */
    private fun modificaAMano(d: DocFinto, rnd: Random) {
        repeat(rnd.nextInt(1, 4)) {
            val par = d.paragrafi()
            val p = par[rnd.nextInt(par.size)]
            when (rnd.nextInt(5)) {
                0 -> d.inserisciTesto(p.inizio, "scritto a mano\n")
                1 -> d.inserisciTesto(p.fine - 1, " aggiunto")
                2 -> d.inserisciImmagine(p.inizio, "https://altro.example/${rnd.nextInt(100)}.png")
                3 -> if (p.fine < d.fine) d.cancella(p.inizio, p.fine) else if (p.fine - 1 > p.inizio) d.cancella(p.inizio, p.fine - 1)
                else -> d.stile(p.inizio, p.inizio + 1, "HEADING_2", "END")
            }
        }
    }
}
