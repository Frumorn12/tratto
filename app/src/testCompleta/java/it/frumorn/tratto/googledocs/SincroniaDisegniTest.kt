package it.frumorn.tratto.googledocs

import it.frumorn.tratto.backup.ErroreDrive
import it.frumorn.tratto.data.NotaInfo
import it.frumorn.tratto.data.Pagina
import it.frumorn.tratto.data.Penna
import it.frumorn.tratto.data.Sfondo
import it.frumorn.tratto.data.Tratto
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * "Il polling di Google Doc quando scrivo su Tratto è lento" (Andrea, 04/11). Scrivendo a mano una
 * riga cresce tratto dopo tratto: a ogni aggiornamento il suo disegno nel documento si rifa'. Prima
 * erano tre chiamate di seguito (intervalli, script che mette il nuovo, batchUpdate che toglie il
 * vecchio); ora lo script mette il nuovo e toglie il vecchio insieme: una chiamata in meno, e chi
 * guarda il documento non vede mai i due disegni insieme. Con lo script vecchio (non ancora
 * aggiornato su Google) si toglie come prima.
 */
class SincroniaDisegniTest {

    /** Google finto: tre paragrafi fissi, oggetti posizionati, intervalli con nome e lo script. */
    private class GoogleFinto(private val scriptNuovo: Boolean, private val trovaDaTogliere: Boolean = true) : ApiDocs, ScriptDisegni {
        private val testi = listOf("uno\n", "due\n", "tre\n")
        class Posto(val paragrafo: Int, val sinistra: Double, val alto: Double, val larghezza: Double, val altezza: Double)

        val oggetti = LinkedHashMap<String, Posto>()
        private val intervalli = ArrayList<Pair<String, Int>>() // nome, inizio
        private var revisione = 1
        private var prossimo = 1

        /** Le richieste di ogni batchUpdate, e per ogni chiamata dello script quanti disegni messi e tolti. */
        val scritture = ArrayList<JSONArray>()
        val script = ArrayList<Pair<Int, Int>>()

        private fun inizio(k: Int) = 1 + 4 * k
        private fun paragrafoA(indice: Int) = (indice - 1) / 4

        override suspend fun completo(id: String): JSONObject {
            val contenuto = JSONArray().put(JSONObject().put("endIndex", 1).put("sectionBreak", JSONObject()))
            for ((k, t) in testi.withIndex()) {
                val ids = JSONArray()
                oggetti.filterValues { it.paragrafo == k }.keys.forEach { ids.put(it) }
                contenuto.put(
                    JSONObject().put("startIndex", inizio(k)).put("endIndex", inizio(k) + t.length).put(
                        "paragraph",
                        JSONObject().put("paragraphStyle", JSONObject().put("namedStyleType", "NORMAL_TEXT")).put("positionedObjectIds", ids)
                            .put("elements", JSONArray().put(JSONObject().put("startIndex", inizio(k)).put("textRun", JSONObject().put("content", t)))),
                    ),
                )
            }
            val po = JSONObject()
            for ((id, p) in oggetti) {
                po.put(
                    id,
                    JSONObject().put(
                        "positionedObjectProperties",
                        JSONObject()
                            .put("embeddedObject", JSONObject().put("size", JSONObject().put("width", RichiesteDocs.misura(p.larghezza)).put("height", RichiesteDocs.misura(p.altezza))))
                            .put("positioning", JSONObject().put("layout", "IN_FRONT_OF_TEXT").put("leftOffset", RichiesteDocs.misura(p.sinistra)).put("topOffset", RichiesteDocs.misura(p.alto))),
                    ),
                )
            }
            val nr = JSONObject()
            for ((k, x) in intervalli.withIndex()) {
                nr.put(
                    x.first,
                    JSONObject().put("name", x.first).put(
                        "namedRanges",
                        JSONArray().put(JSONObject().put("namedRangeId", "nr$k").put("name", x.first).put("ranges", JSONArray().put(JSONObject().put("startIndex", x.second).put("endIndex", x.second + 1))))
                    ),
                )
            }
            return JSONObject().put("revisionId", "r$revisione").put("title", "doc").put("body", JSONObject().put("content", contenuto))
                .put("positionedObjects", po).put("namedRanges", nr)
        }

        override suspend fun aggiorna(id: String, richieste: JSONArray, revisione: String?): JSONObject = applica(richieste)

        override suspend fun aggiornaSu(id: String, richieste: JSONArray, base: String): JSONObject = applica(richieste)

        private fun applica(r: JSONArray): JSONObject {
            scritture += r
            for (i in 0 until r.length()) {
                val x = r.getJSONObject(i)
                x.optJSONObject("createNamedRange")?.let { intervalli += it.getString("name") to it.getJSONObject("range").getInt("startIndex") }
                x.optJSONObject("deleteNamedRange")?.let { d -> intervalli.removeAt(d.getString("namedRangeId").removePrefix("nr").toInt()) }
                x.optJSONObject("deletePositionedObject")?.let { d ->
                    if (oggetti.remove(d.getString("objectId")) == null) throw ErroreDrive(400, null, "oggetto inesistente")
                }
            }
            revisione++
            return JSONObject()
        }

        override suspend fun aggiungiDisegni(documento: String, disegni: List<ScriptDocs.Disegno>, togli: List<ScriptDocs.Togli>): ScriptDocs.Esito {
            script += disegni.size to (if (scriptNuovo) togli.size else 0)
            val ids = disegni.map { d ->
                val i = intervalli.firstOrNull { it.first == d.ancora } ?: return@map null
                intervalli.removeAll { it.first == d.ancora }
                val id = "kix.n${prossimo++}"
                oggetti[id] = Posto(paragrafoA(i.second), d.sinistra, d.alto, d.larghezza, d.altezza)
                id
            }
            revisione++
            // Lo script vecchio non conosce il terzo parametro e restituisce solo gli id.
            if (!scriptNuovo) return ScriptDocs.Esito(ids, null)
            val tolti = HashSet<String>()
            for (t in togli) {
                t.ancora?.let { n -> intervalli.removeAll { it.first == n } }
                // Lo script vero puo' non trovare l'immagine: allora non la mette tra le tolte.
                if (trovaDaTogliere && oggetti.remove(t.oggetto) != null) tolti += t.oggetto
            }
            return ScriptDocs.Esito(ids, tolti)
        }

        /** Gli intervalli rimasti nel documento. */
        val avanzi get() = intervalli.size
    }

    /** Una nota di una pagina con tratti veri, divisa in blocchi come sul tablet. */
    private class NotaFinta : NotaDocs {
        override val id = "nota1"
        private val pagina = Pagina("p1", Sfondo.RIGHE, pdfPagina = -1)
        val tratti = ArrayList<Tratto>()
        private var prossimo = 1L

        /** Un tratto a zig zag alto 25 all'altezza [y], da [x0] a [x1]. */
        fun tratto(y: Float, x0: Float, x1: Float): Tratto {
            val p = FloatArray(10 * Tratto.CAMPI)
            for (k in 0 until 10) {
                p[k * 6] = x0 + (x1 - x0) * k / 9
                p[k * 6 + 1] = y + if (k % 2 == 0) 0f else 25f
                p[k * 6 + 2] = 0.5f
                p[k * 6 + 5] = k.toFloat()
            }
            return Tratto(prossimo++, Penna.PENNA, 0xFF000000.toInt(), 3f, p).also { tratti += it }
        }

        override fun leggi() = NotaInfo(id, "Appunti", null, 0L, 0L, false, 1, Sfondo.RIGHE, false) to listOf(pagina)

        override fun firma(pagina: Pagina) = null

        override fun leggiPagina(pagina: Pagina) = PaginaLetta(pagina, tratti.toList(), emptyList(), BlocchiPagina.dividi(pagina, tratti.toList(), emptyList()) { 20f })

        override suspend fun disegna(info: NotaInfo, letta: PaginaLetta, blocco: BloccoPagina, file: File): String {
            file.parentFile?.mkdirs()
            file.writeBytes(blocco.chiave.toByteArray())
            return "x"
        }
    }

    private val cartella = Files.createTempDirectory("disegni").toFile()
    private val nota = NotaFinta()

    @org.junit.Before
    fun scriptDaCapo() {
        // Ogni prova parte senza ricordare lo script di quella prima.
        SincroniaDisegni.scriptToglie = true
    }

    /** Tre paragrafi sulla prima pagina (cime 121, 200, 300); 1 punto = 2 unita', margine di 72 punti. */
    private fun sincronia(google: GoogleFinto) = SincroniaDisegni(
        google, google, nota,
        impagina = { PostiParagrafi(listOf(0, 0, 0), listOf(121f, 200f, 300f), 2.0, 72.0) },
        cartella = cartella,
    )

    private fun richieste(google: GoogleFinto, tipo: String) = google.scritture.sumOf { r -> (0 until r.length()).count { r.getJSONObject(it).has(tipo) } }

    @Test
    fun unDisegnoCheCresceSiRifaInUnaChiamata() = runBlocking {
        val google = GoogleFinto(scriptNuovo = true)
        val s = sincronia(google)
        nota.tratto(250f, 244f, 400f)
        var link = s.sincronizza(CollegamentoDocs("doc"))
        assertEquals(1, google.oggetti.size)
        val primo = google.oggetti.keys.single()

        // La riga cresce: un tratto accanto, nello stesso blocco.
        nota.tratto(250f, 410f, 500f)
        google.scritture.clear()
        google.script.clear()
        link = s.sincronizza(link)

        assertEquals("una sola scrittura con l'API di Docs (gli intervalli)", 1, google.scritture.size)
        assertEquals(0, richieste(google, "deletePositionedObject"))
        assertEquals(listOf(1 to 1), google.script.toList())
        assertEquals(1, google.oggetti.size)
        assertTrue(primo !in google.oggetti)
        assertEquals(google.oggetti.keys.single(), link.disegni.single().oggetto)
        assertEquals("nessun intervallo rimasto nel documento", 0, google.avanzi)
    }

    @Test
    fun conLoScriptVecchioSiToglieComePrima() = runBlocking {
        val google = GoogleFinto(scriptNuovo = false)
        val s = sincronia(google)
        nota.tratto(250f, 244f, 400f)
        var link = s.sincronizza(CollegamentoDocs("doc"))
        nota.tratto(250f, 410f, 500f)
        link = s.sincronizza(link)

        assertEquals(1, richieste(google, "deletePositionedObject"))
        assertEquals(1, google.oggetti.size)
        assertEquals(google.oggetti.keys.single(), link.disegni.single().oggetto)
    }

    @Test
    fun loScriptNonTrovaIlVecchioETrattoLoToglieDaSe() = runBlocking {
        val google = GoogleFinto(scriptNuovo = true, trovaDaTogliere = false)
        val s = sincronia(google)
        nota.tratto(250f, 244f, 400f)
        var link = s.sincronizza(CollegamentoDocs("doc"))
        nota.tratto(250f, 410f, 500f)
        link = s.sincronizza(link)

        assertEquals(1, richieste(google, "deletePositionedObject"))
        assertEquals(1, google.oggetti.size)
        assertEquals(google.oggetti.keys.single(), link.disegni.single().oggetto)
        assertTrue(SincroniaDisegni.scriptToglie)
    }

    @Test
    fun loScriptVecchioSeLoRicordaFinoAllAggiornamento() = runBlocking {
        val vecchio = GoogleFinto(scriptNuovo = false)
        val s = sincronia(vecchio)
        nota.tratto(250f, 244f, 400f)
        s.sincronizza(CollegamentoDocs("doc"))
        assertEquals(false, SincroniaDisegni.scriptToglie)
        // Lo script si aggiorna su Google: alla prima chiamata Tratto se ne accorge.
        val nuovo = GoogleFinto(scriptNuovo = true)
        val s2 = sincronia(nuovo)
        s2.sincronizza(CollegamentoDocs("doc"))
        assertTrue(SincroniaDisegni.scriptToglie)
    }

    @Test
    fun soloDaTogliereSenzaScript() = runBlocking {
        val google = GoogleFinto(scriptNuovo = true)
        val s = sincronia(google)
        nota.tratto(250f, 244f, 400f)
        var link = s.sincronizza(CollegamentoDocs("doc"))
        nota.tratti.clear()
        google.scritture.clear()
        google.script.clear()
        link = s.sincronizza(link)

        assertEquals(emptyList<Pair<Int, Int>>(), google.script.toList())
        assertEquals(1, google.scritture.size)
        assertEquals(1, richieste(google, "deletePositionedObject"))
        assertEquals(0, richieste(google, "createNamedRange"))
        assertTrue(google.oggetti.isEmpty())
        assertTrue(link.disegni.isEmpty())
    }
}
