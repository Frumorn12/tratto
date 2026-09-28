package it.frumorn.tratto.googledocs

import it.frumorn.tratto.backup.ErroreDrive
import it.frumorn.tratto.data.Elenco
import it.frumorn.tratto.data.Frammento
import it.frumorn.tratto.data.NotaInfo
import it.frumorn.tratto.data.Oggetto
import it.frumorn.tratto.data.Pagina
import it.frumorn.tratto.data.Paragrafo
import it.frumorn.tratto.data.Penna
import it.frumorn.tratto.data.Sfondo
import it.frumorn.tratto.data.StileParagrafo
import it.frumorn.tratto.data.Testo
import it.frumorn.tratto.data.TestoRicco
import it.frumorn.tratto.data.Tratto
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.nio.file.Files

/**
 * L'aggiornamento a blocchi contro un Google finto: righe e disegni come immagini, caselle come testo,
 * testo aggiunto a mano nel documento che resta, immagini pubbliche solo per il tempo del batchUpdate,
 * errori di Docs. Ogni prova gira con tutti i modi in cui un intervallo con nome potrebbe allargarsi
 * sui bordi e con i due modi di unire gli stili dei paragrafi.
 */
@RunWith(Parameterized::class)
class SincroniaDocsTest(private val allargaInizio: Boolean, private val allargaFine: Boolean, private val stileDalPrimo: Boolean) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "inizio={0} fine={1} stileDalPrimo={2}")
        fun casi() = listOf(
            arrayOf<Any>(false, false, false),
            arrayOf<Any>(true, false, true),
            arrayOf<Any>(false, true, false),
            arrayOf<Any>(true, true, true),
        )
    }

    /** Google Docs e Drive finti: il documento e' un [DocFinto], i file pubblicati stanno in memoria. */
    private inner class GoogleFinto : ServizioDocs {
        val doc = DocFinto(stileDalPrimo, allargaInizio, allargaFine)
        private val pubblici = HashMap<String, ByteArray>()
        private var prossimo = 1

        /** Per ogni immagine del documento (uri), i byte che Google ha scaricato quando l'ha inserita. */
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

        override suspend fun aggiorna(id: String, richieste: JSONArray, revisione: String?): JSONObject {
            intruso?.let {
                // Il documento e' cambiato dopo che Tratto l'ha letto: Docs rifiuta per la revisione.
                doc.aMano { it(this) }
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
                val uri = richieste.getJSONObject(i).optJSONObject("insertInlineImage")?.getString("uri") ?: continue
                if (immaginiNonScaricabili > 0) {
                    immaginiNonScaricabili--
                    throw ErroreDrive(400, "INVALID_ARGUMENT", "rifiutato",
                        "Invalid requests[$i].insertInlineImage: There was a problem retrieving the image. The provided image should be publicly accessible, within size limit, and in supported formats.")
                }
                val fileId = uri.substringAfter("/d/", "").ifEmpty { uri.substringAfter("id=") }
                scaricate[uri] = pubblici[fileId] ?: error("Immagine non pubblica: $uri")
            }
            val risposta = doc.applica(RichiesteDocs.corpo(richieste, revisione))
            copie.putAll(scaricate)
            this.richieste += richieste
            aggiornamenti++
            return risposta
        }

        override suspend fun rinomina(id: String, nome: String) {
            doc.titolo = nome
            nomi += nome
        }

        override suspend fun pubblica(file: File, nome: String): Pubblicata {
            val b = file.readBytes()
            synchronized(this) {
                pubblicazioni++
                val id = "file${prossimo++}"
                pubblici[id] = b
                return Pubblicata(id, "anyoneWithLink")
            }
        }

        override suspend fun ritira(immagine: Pubblicata) {
            if (ritiroNonRiesce) throw java.io.IOException("rete assente")
            synchronized(this) { pubblici.remove(immagine.id) }
        }

        /** Il contenuto di un'immagine del documento. */
        fun contenuto(i: DocFinto.Immagine): String = String(copie.getValue(i.uri))
    }

    /** Nota finta: pagine con tratti e oggetti veri, divise in blocchi come sul tablet; il "disegno" e' un testo. */
    private class NotaFinta : NotaDocs {
        override val id = "nota1"
        var titolo = "Appunti"
        val pagine = ArrayList<Pagina>()
        val tratti = HashMap<String, MutableList<Tratto>>()
        val oggetti = HashMap<String, MutableList<Oggetto>>()
        private val versioni = HashMap<String, Int>()
        private var prossimoTratto = 1L
        var letture = 0
        var disegni = 0

        fun pagina(id: String, pdf: Boolean = false) {
            pagine += Pagina(id, Sfondo.RIGHE, pdfPagina = if (pdf) 0 else -1)
            tratti[id] = ArrayList()
            oggetti[id] = ArrayList()
            versioni[id] = 1
        }

        /** Una riga scritta a mano all'altezza [y], da [x0] a [x1]: un tratto a zig zag alto 25. */
        fun riga(pid: String, y: Float, x0: Float = 100f, x1: Float = 500f, tempo: Float = 0f): Tratto {
            val p = FloatArray(10 * Tratto.CAMPI)
            for (k in 0 until 10) {
                p[k * 6] = x0 + (x1 - x0) * k / 9
                p[k * 6 + 1] = y + if (k % 2 == 0) 0f else 25f
                p[k * 6 + 2] = 0.5f
                p[k * 6 + 5] = tempo + k
            }
            val t = Tratto(prossimoTratto++, Penna.PENNA, 0xFF000000.toInt(), 3f, p)
            tratti.getValue(pid) += t
            cambia(pid)
            return t
        }

        fun casella(pid: String, id: String, y: Float, vararg paragrafi: Paragrafo, x: Float = 100f): Testo {
            val t = Testo(id, x, y, 600f, TestoRicco(paragrafi.toList()))
            oggetti.getValue(pid).removeIf { it.id == id }
            oggetti.getValue(pid) += t
            cambia(pid)
            return t
        }

        fun cambia(pid: String) {
            versioni[pid] = versioni.getValue(pid) + 1
        }

        override fun leggi() = NotaInfo(id, titolo, null, 0L, 0L, false, pagine.size, Sfondo.RIGHE, false) to pagine.toList()

        override fun firma(pagina: Pagina) = "v${versioni[pagina.id]}:$pagina"

        override fun leggiPagina(pagina: Pagina): PaginaLetta {
            letture++
            return leggiSenzaContare(pagina)
        }

        fun leggiSenzaContare(pagina: Pagina): PaginaLetta {
            val tr = tratti.getValue(pagina.id).toList()
            val og = oggetti.getValue(pagina.id).toList()
            return PaginaLetta(pagina, tr, og, BlocchiPagina.dividi(pagina, tr, og) { t -> 20f * t.contenuto.paragrafi.size })
        }

        /** Il "disegno" di una figura: dipende solo da dove passa l'inchiostro, non dai tempi dei punti. */
        fun contenuto(letta: PaginaLetta, b: BloccoPagina): String = when (b) {
            is BloccoPagina.Disegno -> "${letta.pagina.id}/${b.chiave}:" + b.tratti.joinToString(";") { t ->
                (0 until t.quanti).joinToString(",") { "${t.punti[it * 6]}:${t.punti[it * 6 + 1]}" }
            }
            else -> "${letta.pagina.id}/${b.chiave}:pagina intera ${letta.tratti.size}"
        }

        override suspend fun disegna(info: NotaInfo, letta: PaginaLetta, blocco: BloccoPagina, file: File): String {
            disegni++
            val b = contenuto(letta, blocco).toByteArray()
            file.parentFile?.mkdirs()
            file.writeBytes(b)
            return FirmaPagina.sha256(b)
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

    /** Un paragrafo del documento: il testo, o il contenuto dell'immagine se e' una figura. */
    private fun DocFinto.Paragrafo.leggibile() = if (figura) "[" + google.contenuto(immagini[0]) + "]" else testo

    /**
     * Il documento e' la nota: tolti i paragrafi scritti a mano ("UTENTE...") e il paragrafo vuoto in
     * fondo, restano i blocchi della nota in ordine. Ogni testo di Tratto ha il suo intervallo, esatto,
     * e il collegamento riconosce tutte le immagini.
     */
    private fun controlla() {
        val attesi = ArrayList<String>()
        val testi = LinkedHashMap<String, String>()
        for (p in nota.pagine) {
            val l = nota.leggiSenzaContare(p)
            for (b in l.blocchi) {
                if (b is BloccoPagina.Scritta) {
                    val par = ContenutoDocs.paragrafi(b.testo.contenuto).map { it.testo }
                    attesi += par
                    testi["${p.id}/${b.chiave}"] = par.joinToString("") { "$it\n" }
                } else {
                    attesi += "[" + nota.contenuto(l, b) + "]"
                }
            }
        }
        val par = google.doc.paragrafi().toMutableList()
        if (par.isNotEmpty() && par.last().testo.isEmpty() && par.last().immagini.isEmpty()) par.removeAt(par.lastIndex)
        assertEquals(attesi, par.filter { !it.testo.startsWith("UTENTE") }.map { it.leggibile() })
        // Il titolo della nota e' solo il nome del documento.
        assertTrue(par.none { it.stile == "TITLE" })

        for ((k, t) in testi) assertEquals("intervallo di $k", t, google.doc.testoIntervallo(ContenutoDocs.nomeIntervallo(k)))
        assertEquals(testi.size, google.doc.intervalli.size)

        val figure = link.blocchi.filter { it.figura }
        assertEquals(figure.map { it.oggetto }, google.doc.paragrafi().filter { it.figura && !it.testo.startsWith("UTENTE") }.map { it.immagini[0].id })
    }

    private fun JSONArray.tipi() = (0 until length()).map { getJSONObject(it).keys().next() }

    private fun testo(s: String, stile: StileParagrafo = StileParagrafo.NORMALE, elenco: Elenco = Elenco.NESSUNO, grassetto: Boolean = false) =
        Paragrafo(listOf(Frammento(s, grassetto = grassetto)), stile, elenco = elenco)

    /** Una pagina con due righe e una casella in mezzo, e una seconda pagina con un disegno. */
    private fun notaDiProva(): Map<String, Tratto> {
        nota.pagina("a")
        val r1 = nota.riga("a", 100f)
        nota.casella("a", "c1", 200f, testo("Ciao a tutti"))
        val r2 = nota.riga("a", 300f)
        nota.pagina("b")
        val d = nota.riga("b", 100f, 50f, 900f)
        return mapOf("r1" to r1, "r2" to r2, "d" to d)
    }

    @Test
    fun primoAggiornamento() {
        notaDiProva()
        sincronizza()
        assertEquals(3, google.pubblicazioni)
        assertEquals(1, google.aggiornamenti)
        assertEquals(listOf("Appunti"), google.nomi)
        assertEquals("[figura]", google.doc.paragrafi().first().let { if (it.figura) "[figura]" else it.testo })
        // Ogni riga e' un'immagine trasparente della sua misura: la pagina larga 1000 occupa 440 punti.
        val img = google.richieste[0].let { a -> (0 until a.length()).map { a.getJSONObject(it) }.mapNotNull { it.optJSONObject("insertInlineImage") } }
        assertEquals(3, img.size)
        assertTrue(img.all { it.getJSONObject("objectSize").getJSONObject("width").getDouble("magnitude") < 440.0 })
        // Il testo e' testo vero, nel carattere di Tratto.
        val p = google.doc.paragrafi().first { it.testo == "Ciao a tutti" }
        assertEquals("Manrope", google.doc.testoDi(p.inizio).getJSONObject("weightedFontFamily").getString("fontFamily"))
    }

    @Test
    fun siCambiaSoloLaRigaToccata() {
        val t = notaDiProva()
        sincronizza()
        val oggetti = google.doc.immagini().map { it.id }
        // Un tratto in piu' sulla prima riga (l'accento di una lettera).
        nota.riga("a", 90f, 200f, 220f)
        nota.letture = 0
        sincronizza()
        assertEquals(4, google.pubblicazioni)
        assertEquals(listOf("deleteNamedRange", "deleteContentRange", "insertInlineImage"), google.richieste.last().tipi().take(3))
        // Le altre immagini restano le stesse (stessi id); la pagina b non si rilegge.
        val dopo = google.doc.immagini().map { it.id }
        assertNotEquals(oggetti[0], dopo[0])
        assertEquals(oggetti.drop(1), dopo.drop(1))
        assertEquals(1, nota.letture)
        assertEquals(t.getValue("r1").id, link.blocchi[0].chiave.substringAfter("/t").toLong())
    }

    @Test
    fun nienteDaFare() {
        notaDiProva()
        sincronizza()
        nota.letture = 0
        nota.disegni = 0
        sincronizza()
        assertEquals(1, google.aggiornamenti)
        assertEquals(3, google.pubblicazioni)
        assertEquals(0, nota.letture)
        assertEquals(0, nota.disegni)
    }

    @Test
    fun disegnoIdenticoNonSiRicarica() {
        val t = notaDiProva()
        sincronizza()
        // Stessi punti ma tempi diversi: l'impronta cambia, il disegno no.
        val r = t.getValue("r2")
        val tr = nota.tratti.getValue("a")
        tr[tr.indexOf(r)] = Tratto(r.id, r.penna, r.colore, r.spessore, r.punti.copyOf().also { it[5] = 999f })
        nota.cambia("a")
        sincronizza()
        assertEquals(3, google.pubblicazioni)
        assertEquals(1, google.aggiornamenti)
        nota.letture = 0
        sincronizza()
        assertEquals(0, nota.letture)
    }

    @Test
    fun ilTestoScrittoAManoResta() {
        notaDiProva()
        sincronizza()
        // Andrea scrive in Google Docs: dopo la prima riga, dopo la casella e in fondo.
        google.doc.aMano {
            val p = paragrafi()
            inserisciTesto(p[0].fine, "UTENTE dopo la riga\n")
            val c = paragrafi().first { it.testo == "Ciao a tutti" }
            inserisciTesto(c.fine - 1, " ")
            cancella(c.fine - 1, c.fine) // un ripensamento: il paragrafo resta uguale
            inserisciTesto(c.fine, "UTENTE dopo la casella\n")
            inserisciTesto(fine - 1, "UTENTE in fondo")
        }
        // Sul tablet: una riga nuova tra la prima e la casella, la casella cambia, la seconda riga sparisce.
        nota.riga("a", 150f)
        nota.casella("a", "c1", 200f, testo("Ciao a tutti!"))
        nota.tratti.getValue("a").removeIf { it.punti[1] == 300f }
        nota.cambia("a")
        sincronizza()
        val par = google.doc.paragrafi().map { if (it.figura) "[figura]" else it.testo }
        assertEquals(
            listOf("[figura]", "[figura]", "UTENTE dopo la riga", "Ciao a tutti!", "UTENTE dopo la casella", "[figura]", "UTENTE in fondo"),
            par,
        )
    }

    @Test
    fun testoDiTrattoCambiatoAManoRestaFinoAlProssimoCambio() {
        notaDiProva()
        sincronizza()
        google.doc.aMano {
            val c = paragrafi().first { it.testo == "Ciao a tutti" }
            inserisciTesto(c.fine - 1, " (corretto)")
        }
        // Cambia solo la pagina b: la casella resta come l'ha corretta Andrea.
        nota.riga("b", 300f)
        sincronizza_senzaControllo()
        assertTrue(google.doc.paragrafi().any { it.testo == "Ciao a tutti (corretto)" })
        // Poi cambia la casella sul tablet: vince il tablet.
        nota.casella("a", "c1", 200f, testo("Ciao a tutti, di nuovo"))
        sincronizza()
        assertFalse(google.doc.paragrafi().any { it.testo.contains("corretto") })
    }

    private fun sincronizza_senzaControllo() {
        link = runBlocking { SincroniaDocs(google, nota, cartella) { }.sincronizza(link) }
    }

    @Test
    fun figuraCancellataAManoSiRimette() {
        notaDiProva()
        sincronizza()
        google.doc.aMano {
            val f = paragrafi().filter { it.figura }[1]
            cancella(f.inizio, f.fine)
        }
        sincronizza()
        assertEquals(4, google.pubblicazioni)
    }

    @Test
    fun scrittaNelParagrafoDiUnaFigura() {
        notaDiProva()
        sincronizza()
        google.doc.aMano {
            val f = paragrafi().filter { it.figura }[0]
            inserisciTesto(f.fine - 1, " UTENTE didascalia")
        }
        // La riga cambia: si cambia solo l'immagine, la didascalia resta nel suo paragrafo.
        nota.riga("a", 90f, 200f, 220f)
        sincronizza_senzaControllo()
        val p = google.doc.paragrafi().first { it.testo.contains("didascalia") }
        assertEquals(1, p.immagini.size)
        assertEquals(" UTENTE didascalia", p.testo)
        // Poi la riga si cancella: resta solo la didascalia.
        nota.tratti.getValue("a").removeIf { it.punti[1] < 150f }
        nota.cambia("a")
        sincronizza_senzaControllo()
        val q = google.doc.paragrafi().first { it.testo.contains("didascalia") }
        assertEquals(0, q.immagini.size)
    }

    @Test
    fun casellaConFormato() {
        nota.pagina("a")
        nota.casella(
            "a", "c1", 100f,
            testo("Capitolo", StileParagrafo.INTESTAZIONE1),
            testo("uno", elenco = Elenco.PUNTATO),
            Paragrafo(listOf(Frammento("due")), elenco = Elenco.PUNTATO, rientro = 1),
            testo("fine", grassetto = true),
            x = 200f,
        )
        sincronizza()
        val par = google.doc.paragrafi()
        val cap = par.first { it.testo == "Capitolo" }
        assertEquals("HEADING_1", cap.stile)
        assertEquals(88.0, google.doc.stileDi(cap.inizio).getJSONObject("indentStart").getDouble("magnitude"), 0.01)
        val uno = par.first { it.testo == "uno" }
        val due = par.first { it.testo == "due" }
        assertEquals("BULLET_DISC_CIRCLE_SQUARE", google.doc.elencoDi(uno.inizio))
        assertEquals("BULLET_DISC_CIRCLE_SQUARE", google.doc.elencoDi(due.inizio))
        val fine = par.first { it.testo == "fine" }
        assertTrue(google.doc.testoDi(fine.inizio).getBoolean("bold"))
        assertFalse(google.doc.stileDi(fine.inizio).has("spaceAbove"))
        // Si riscrive senza problemi (gli indici dopo gli elenchi sono giusti).
        nota.casella("a", "c1", 100f, testo("Capitolo", StileParagrafo.INTESTAZIONE1), testo("uno", elenco = Elenco.NUMERATO), x = 200f)
        sincronizza()
        assertEquals("NUMBERED_DECIMAL_ALPHA_ROMAN", google.doc.elencoDi(google.doc.paragrafi().first { it.testo == "uno" }.inizio))
    }

    @Test
    fun paginaDiPdfIntera() {
        nota.pagina("a", pdf = true)
        nota.riga("a", 100f)
        nota.casella("a", "c1", 300f, testo("nota a margine"))
        sincronizza()
        // Una pagina di PDF resta un'immagine sola, con tutto sopra.
        assertEquals(listOf("[a/pdf:pagina intera 1]"), google.doc.paragrafi().dropLast(1).map { it.leggibile() })
    }

    @Test
    fun notaLungaAGruppi() {
        nota.pagina("a")
        for (k in 0 until 45) nota.riga("a", 20f + k * 50f)
        sincronizza()
        assertEquals(45, google.pubblicazioni)
        assertEquals(3, google.aggiornamenti)
        assertTrue(google.richieste.all { r -> r.tipi().count { it == "insertInlineImage" } <= SincroniaDocs.LIMITE_IMMAGINI })
        // Il collegamento si salva dopo ogni gruppo: se il lavoro si interrompe, riprende da li'.
        assertEquals(3, progressi)
    }

    @Test
    fun pagineAggiunteTolteSpostate() {
        notaDiProva()
        sincronizza()
        nota.pagina("c")
        nota.riga("c", 500f)
        nota.pagine.removeAt(1)
        sincronizza()
        nota.pagine.reverse()
        nota.riga("a", 600f)
        sincronizza()
        nota.pagine.clear()
        sincronizza()
        assertEquals(emptyList<String>(), google.doc.paragrafi().map { it.leggibile() }.filter { it.isNotEmpty() })
    }

    @Test
    fun titoloCambiato() {
        notaDiProva()
        sincronizza()
        nota.titolo = "Fisica"
        sincronizza()
        assertEquals(listOf("Appunti", "Fisica"), google.nomi)
        nota.titolo = ""
        sincronizza()
        assertEquals("Nota senza titolo", google.nomi.last())
    }

    @Test
    fun documentoDellaPrimaVersione() {
        // Titolo e una immagine per pagina, come li faceva la prima versione.
        google.doc.aMano {
            inserisciTesto(1, "Appunti\n")
            inserisciImmagine(fine - 1, "https://x.example/vecchia.png")
        }
        link = CollegamentoDocs("doc1", vecchio = true)
        notaDiProva()
        sincronizza()
        assertTrue(google.doc.immagini().none { it.uri.contains("vecchia") })
    }

    @Test
    fun immagineScaricataAlSecondoTentativo() {
        notaDiProva()
        google.immaginiNonScaricabili = 1
        sincronizza()
        // Il secondo tentativo usa l'altro indirizzo.
        assertTrue(google.doc.immagini().all { it.uri.startsWith("https://drive.google.com/uc?export=download&id=") })
        nota.riga("a", 90f, 200f, 220f)
        sincronizza()
        assertTrue(google.doc.immagini().first().uri.startsWith("https://lh3.googleusercontent.com/d/"))
    }

    @Test
    fun immagineMaiScaricata() {
        notaDiProva()
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
        notaDiProva()
        sincronizza()
        nota.riga("a", 400f)
        google.intruso = { d -> d.inserisciTesto(1, "UTENTE scritto da un altro\n") }
        sincronizza()
        assertEquals("UTENTE scritto da un altro", google.doc.paragrafi().first().testo)
    }

    @Test
    fun richiesteRifiutateSiRiprova() {
        notaDiProva()
        sincronizza()
        nota.riga("a", 400f)
        google.richiesteRifiutate = 2
        sincronizza()
    }

    @Test
    fun richiesteSempreRifiutate() {
        notaDiProva()
        google.richiesteRifiutate = 100
        try {
            runBlocking { SincroniaDocs(google, nota, cartella) { }.sincronizza(link) }
            fail("Doveva fallire")
        } catch (e: ErroreDrive) {
            assertEquals(400, e.codice)
        }
        assertEquals(0, google.pubbliciRimasti)
        // Niente e' stato cancellato per ripartire da zero: il documento e' rimasto com'era.
        assertEquals(listOf(""), google.doc.paragrafi().map { it.testo })
    }

    @Test
    fun ritiroNonRiuscitoLasciaAvanzi() {
        notaDiProva()
        google.ritiroNonRiesce = true
        val s = SincroniaDocs(google, nota, cartella) { }
        runBlocking { s.sincronizza(link) }
        assertTrue(s.avanzi)
        assertFalse(SincroniaDocs(google, nota, cartella) { }.avanzi)
    }
}
