package it.frumorn.tratto.googledocs

import it.frumorn.tratto.backup.ErroreDrive
import it.frumorn.tratto.data.NotaInfo
import it.frumorn.tratto.data.Pagina
import it.frumorn.tratto.data.Penna
import it.frumorn.tratto.data.Sfondo
import it.frumorn.tratto.data.Tratto
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * L'aggiornamento completo contro un Google finto: gruppi di immagini, immagini pubbliche solo per
 * il tempo del batchUpdate, errori di Docs, pagine da non rileggere ne' ricaricare.
 */
class SincroniaDocsTest {

    /** Google Docs e Drive finti: il documento e' un [DocFinto], i file pubblicati stanno in memoria. */
    private class GoogleFinto : ServizioDocs {
        val doc = DocFinto()
        private val pubblici = HashMap<String, ByteArray>()
        private var prossimo = 1

        /** Per ogni immagine del documento, i byte che Google ha scaricato quando l'ha inserita. */
        val copie = HashMap<String, ByteArray>()
        var pubblicazioni = 0
        var aggiornamenti = 0
        val nomi = ArrayList<String>()
        val richieste = ArrayList<JSONArray>()

        /** Quante volte Google non riesce ancora a scaricare le immagini. */
        var immaginiNonScaricabili = 0

        /** Quante volte Docs rifiuta ancora le richieste. */
        var richiesteRifiutate = 0

        /** Qualcuno che scrive nel documento proprio prima del prossimo batchUpdate. */
        var intruso: ((DocFinto) -> Unit)? = null

        var ritiroNonRiesce = false

        /** Nessun file resta pubblico dopo l'aggiornamento. */
        val pubbliciRimasti get() = pubblici.size

        override suspend fun documento(id: String) = doc.json()

        override suspend fun aggiorna(id: String, richieste: JSONArray, revisione: String?) {
            intruso?.let {
                // Il documento e' cambiato dopo che Tratto l'ha letto: Docs rifiuta per la revisione.
                it(doc)
                intruso = null
                throw ErroreDrive(400, "INVALID_ARGUMENT", "rifiutato", "The required revision ID 'r' does not match the latest revision.")
            }
            check(revisione == doc.revisione.toString()) { "Manca requiredRevisionId o e' vecchio" }
            if (richiesteRifiutate > 0) {
                richiesteRifiutate--
                throw ErroreDrive(400, "INVALID_ARGUMENT", "rifiutato", "Invalid requests[0].deleteContentRange: Index 12 must be less than the end index")
            }
            // Google scarica adesso le immagini: devono essere pubbliche.
            val scaricate = HashMap<String, ByteArray>()
            for (i in 0 until richieste.length()) {
                val r = richieste.getJSONObject(i)
                val uri = (r.optJSONObject("insertInlineImage") ?: r.optJSONObject("replaceImage"))?.getString("uri") ?: continue
                if (immaginiNonScaricabili > 0) {
                    immaginiNonScaricabili--
                    throw ErroreDrive(400, "INVALID_ARGUMENT", "rifiutato",
                        "Invalid requests[$i].insertInlineImage: There was a problem retrieving the image. The provided image should be publicly accessible, within size limit, and in supported formats.")
                }
                val fileId = uri.substringAfter("/d/", "").ifEmpty { uri.substringAfter("id=") }
                scaricate[uri] = pubblici[fileId] ?: error("Immagine non pubblica: $uri")
            }
            doc.applica(RichiesteDocs.corpo(richieste, revisione))
            copie.putAll(scaricate)
            this.richieste += richieste
            aggiornamenti++
        }

        override suspend fun rinomina(id: String, nome: String) {
            doc.titolo = nome
            nomi += nome
        }

        override suspend fun pubblica(file: File, nome: String): Pubblicata {
            pubblicazioni++
            val id = "file${prossimo++}"
            pubblici[id] = file.readBytes()
            return Pubblicata(id, "anyoneWithLink")
        }

        override suspend fun ritira(immagine: Pubblicata) {
            if (ritiroNonRiesce) throw java.io.IOException("rete assente")
            pubblici.remove(immagine.id)
        }

        /** Il contenuto delle immagini del documento, in ordine. */
        fun immagini(): List<String> = doc.immagini().map { String(copie.getValue(it.uri)) }
    }

    /**
     * Nota finta: [versioni] cambia i tratti di una pagina, [aspetti] cambia il disegno. Una pagina
     * con i tratti cambiati ma lo stesso aspetto e' come un tratto aggiunto e poi annullato.
     */
    private class NotaFinta : NotaDocs {
        override val id = "nota1"
        var titolo = "Appunti"
        val pagine = ArrayList<Pagina>()
        val versioni = HashMap<String, Int>()
        val aspetti = HashMap<String, Int>()
        var letture = 0
        var disegni = 0

        fun aggiungi(vararg ids: String) = ids.forEach { pagine += Pagina(it, Sfondo.RIGHE); versioni[it] = 1; aspetti[it] = 1 }

        fun cambia(id: String, aspetto: Boolean = true) {
            versioni[id] = versioni.getValue(id) + 1
            if (aspetto) aspetti[id] = aspetti.getValue(id) + 1
        }

        fun atteso(id: String) = "pagina $id aspetto ${aspetti.getValue(id)}"

        override fun leggi() = NotaInfo(id, titolo, null, 0L, 0L, false, pagine.size, Sfondo.RIGHE, false) to pagine.toList()

        override fun firma(pagina: Pagina, testo: List<String>) = "v${versioni[pagina.id]}:$pagina:${testo.hashCode()}"

        override fun tratti(pagina: Pagina): List<Tratto> {
            letture++
            return listOf(Tratto(1, Penna.PENNA, versioni.getValue(pagina.id), 3f, floatArrayOf(1f, 2f, 0.5f, 0f, 0f, 0f)))
        }

        override suspend fun disegna(info: NotaInfo, pagina: Pagina, tratti: List<Tratto>, file: File): String {
            disegni++
            val b = atteso(pagina.id).toByteArray()
            file.parentFile?.mkdirs()
            file.writeBytes(b)
            return ImprontaPagina.sha256(b)
        }
    }

    private val cartella = Files.createTempDirectory("docs").toFile()
    private val google = GoogleFinto()
    private val nota = NotaFinta()
    private var link = CollegamentoDocs("doc1")
    private var progressi = 0

    @After
    fun pulisci() {
        cartella.deleteRecursively()
    }

    private fun sincronizza(): SincroniaDocs {
        val s = SincroniaDocs(google, nota, cartella) { }
        link = runBlocking { s.sincronizza(link) { progressi++ } }
        // Nessuna immagine resta pubblica e nessun PNG resta nella cache.
        assertEquals(0, google.pubbliciRimasti)
        assertEquals(0, cartella.listFiles()?.size ?: 0)
        controlla()
        return s
    }

    /** Il documento e' la nota, e il collegamento riconosce tutte le sue immagini. */
    private fun controlla() {
        val par = google.doc.paragrafi()
        assertEquals(nota.titolo, par.first().testo)
        assertEquals("TITLE", par.first().stile)
        assertEquals(nota.pagine.map { nota.atteso(it.id) }, google.immagini())
        assertEquals(1 + nota.pagine.size, par.size)
        assertEquals(google.doc.immagini().map { it.id }, nota.pagine.map { link.pagine.getValue(it.id).oggetto })
    }

    private fun JSONArray.tipi() = (0 until length()).map { getJSONObject(it).keys().next() }

    @Test
    fun primoAggiornamento() {
        nota.aggiungi("a", "b", "c")
        sincronizza()
        assertEquals(3, google.pubblicazioni)
        assertEquals(1, google.aggiornamenti)
        assertEquals(listOf("Appunti"), google.nomi)
    }

    @Test
    fun siRicaricaSoloLaPaginaCambiata() {
        nota.aggiungi("a", "b", "c")
        sincronizza()
        val oggetti = google.doc.immagini().map { it.id }
        nota.cambia("b")
        nota.letture = 0
        sincronizza()
        assertEquals(4, google.pubblicazioni)
        assertEquals(listOf("replaceImage"), google.richieste.last().tipi())
        // Le immagini restano le stesse (stessi id): chi guarda vede cambiare solo la pagina b.
        assertEquals(oggetti, google.doc.immagini().map { it.id })
        // I tratti delle pagine non cambiate non si rileggono (una lettura per il controllo, una per il disegno).
        assertEquals(2, nota.letture)
    }

    @Test
    fun nienteDaFare() {
        nota.aggiungi("a", "b")
        sincronizza()
        nota.letture = 0
        nota.disegni = 0
        sincronizza()
        assertEquals(1, google.aggiornamenti)
        assertEquals(2, google.pubblicazioni)
        assertEquals(0, nota.letture)
        assertEquals(0, nota.disegni)
    }

    @Test
    fun disegnoIdenticoNonSiRicarica() {
        nota.aggiungi("a", "b")
        sincronizza()
        nota.cambia("a", aspetto = false)
        sincronizza()
        assertEquals(2, google.pubblicazioni)
        assertEquals(1, google.aggiornamenti)
        // La firma nuova e' ricordata: la volta dopo non si rilegge niente.
        nota.letture = 0
        sincronizza()
        assertEquals(0, nota.letture)
    }

    @Test
    fun notaLungaAGruppi() {
        nota.aggiungi(*(1..25).map { "p$it" }.toTypedArray())
        sincronizza()
        assertEquals(25, google.pubblicazioni)
        assertEquals(3, google.aggiornamenti)
        assertTrue(google.richieste.all { r -> r.tipi().count { it == "insertInlineImage" } <= SincroniaDocs.LIMITE_IMMAGINI })
        // Il collegamento si salva dopo ogni gruppo: se il lavoro si interrompe, riprende da li'.
        assertEquals(3, progressi)
    }

    @Test
    fun pagineAggiunteTolteSpostate() {
        nota.aggiungi("a", "b", "c")
        sincronizza()
        nota.aggiungi("d")
        nota.pagine.removeAt(1)
        sincronizza()
        nota.pagine.reverse()
        nota.cambia("c")
        sincronizza()
        nota.pagine.clear()
        sincronizza()
    }

    @Test
    fun titoloCambiato() {
        nota.aggiungi("a")
        sincronizza()
        nota.titolo = "Fisica"
        sincronizza()
        assertEquals(listOf("Appunti", "Fisica"), google.nomi)
        nota.titolo = ""
        sincronizza_titoloPredefinito()
    }

    private fun sincronizza_titoloPredefinito() {
        val s = SincroniaDocs(google, nota, cartella) { }
        link = runBlocking { s.sincronizza(link) }
        assertEquals("Nota senza titolo", google.doc.paragrafi().first().testo)
        assertEquals("Nota senza titolo", google.nomi.last())
    }

    @Test
    fun immagineScaricataAlSecondoTentativo() {
        nota.aggiungi("a", "b")
        google.immaginiNonScaricabili = 1
        sincronizza()
        // Il secondo tentativo usa l'altro indirizzo.
        assertTrue(google.doc.immagini().all { it.uri.startsWith("https://drive.google.com/uc?export=download&id=") })
        nota.cambia("a")
        sincronizza()
        assertTrue(google.doc.immagini().first().uri.startsWith("https://lh3.googleusercontent.com/d/"))
    }

    @Test
    fun immagineMaiScaricata() {
        nota.aggiungi("a")
        google.immaginiNonScaricabili = 100
        try {
            runBlocking { SincroniaDocs(google, nota, cartella) { }.sincronizza(link) }
            fail("Doveva fallire")
        } catch (_: ImmagineNonScaricata) {
        }
        // Le immagini pubblicate si ritirano comunque, e il documento non e' cambiato.
        assertEquals(0, google.pubbliciRimasti)
        assertEquals(0, google.aggiornamenti)
        assertEquals(0, cartella.listFiles()?.size ?: 0)
    }

    @Test
    fun documentoCambiatoDuranteLAggiornamento() {
        nota.aggiungi("a", "b")
        sincronizza()
        nota.cambia("b")
        nota.aggiungi("c")
        google.intruso = { d -> d.inserisciTesto(1, "scritto da un altro\n") }
        sincronizza()
    }

    @Test
    fun richiesteRifiutateSiRiscriveTutto() {
        nota.aggiungi("a", "b")
        sincronizza()
        nota.aggiungi("c")
        google.richiesteRifiutate = 2
        sincronizza()
        // Due tentativi con la sola pagina nuova, poi il corpo si svuota e si ricaricano tutte.
        assertEquals(2 + 1 + 1 + 3, google.pubblicazioni)
    }

    @Test
    fun richiesteSempreRifiutate() {
        nota.aggiungi("a")
        google.richiesteRifiutate = 100
        try {
            runBlocking { SincroniaDocs(google, nota, cartella) { }.sincronizza(link) }
            fail("Doveva fallire")
        } catch (e: ErroreDrive) {
            assertEquals(400, e.codice)
        }
        assertEquals(0, google.pubbliciRimasti)
    }

    @Test
    fun ritiroNonRiuscitoLasciaAvanzi() {
        nota.aggiungi("a")
        google.ritiroNonRiesce = true
        val s = SincroniaDocs(google, nota, cartella) { }
        runBlocking { s.sincronizza(link) }
        assertTrue(s.avanzi)
        assertFalse(SincroniaDocs(google, nota, cartella) { }.avanzi)
    }

    @Test
    fun testoSottoLePagine() {
        val conTesto = object : NotaDocs by nota {
            override fun testo(pagina: Pagina) = if (pagina.id == "a") listOf("Riga uno\nRiga due") else emptyList()
        }
        nota.aggiungi("a", "b")
        val s = SincroniaDocs(google, conTesto, cartella) { }
        link = runBlocking { s.sincronizza(link) }
        assertEquals(
            listOf("Appunti", "", "Riga uno", "Riga due", ""),
            google.doc.paragrafi().map { it.testo },
        )
        assertEquals(nota.pagine.map { nota.atteso(it.id) }, google.immagini())
    }
}
