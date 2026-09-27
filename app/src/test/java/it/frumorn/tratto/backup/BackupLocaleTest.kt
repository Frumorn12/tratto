package it.frumorn.tratto.backup

import it.frumorn.tratto.data.Archivio
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupLocaleTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var radice: File
    private lateinit var temporanei: File

    @Before
    fun prepara() {
        radice = File(tmp.root, "files/tratto")
        temporanei = File(tmp.root, "cache").apply { mkdirs() }
    }

    // ------------------------------------------------------------ esportazione e manifest

    @Test
    fun esportaScriveManifestIndiceENote() {
        nota(radice, "aaaa", "pagine A", pdf = byteArrayOf(9, 9, 9))
        nota(radice, "bbbb", "pagine B")
        File(radice, "note/aaaa/pagine/p2.tp.tmp").writeText("a meta'")
        File(radice, "note/orfana").mkdirs()
        File(radice, "note/orfana/nota.json").writeText("{}")
        scriviIndice(radice, listOf(voce("aaaa", "A", 10), voce("bbbb", "B", 20)))

        val voci = vociZip(esportaPubblico())

        assertEquals("manifest.json", voci.keys.first())
        val manifest = JSONObject(String(voci.getValue("manifest.json")))
        assertEquals("Tratto", manifest.getString("app"))
        assertEquals(1, manifest.getInt("formato"))
        assertEquals(2, manifest.getInt("note"))
        assertTrue(manifest.getLong("creato") > 0)
        assertTrue(manifest.has("dispositivo"))
        assertTrue("tratto/indice.json" in voci)
        assertEquals("pagine A", String(voci.getValue("tratto/note/aaaa/pagine/p1.tp")))
        assertTrue("tratto/note/aaaa/allegato.pdf" in voci)
        assertFalse("i .tmp non vanno nel backup", voci.keys.any { it.endsWith(".tmp") })
        assertFalse("le cartelle non indicizzate non vanno nel backup", voci.keys.any { "orfana" in it })
    }

    @Test
    fun esportaNotaSolaConLaSuaCartella() {
        nota(radice, "aaaa", "pagine A")
        nota(radice, "bbbb", "pagine B")
        scriviIndice(
            radice,
            listOf(voce("aaaa", "A", 10, cartella = "c1"), voce("bbbb", "B", 20, cartella = "c2")),
            listOf(cartella("c1", "Scuola"), cartella("c2", "Casa")),
        )
        val archivio = Archivio(radice, temporanei)

        val out = ByteArrayOutputStream()
        BackupLocale.esportaNota(archivio, "aaaa", out)
        val voci = vociZip(out.toByteArray())

        val indice = JSONObject(String(voci.getValue("tratto/indice.json")))
        assertEquals(listOf("aaaa"), indice.elenco("note").map { it.id })
        assertEquals(listOf("c1"), indice.elenco("cartelle").map { it.id })
        assertFalse(voci.keys.any { "bbbb" in it })
        assertEquals(1, JSONObject(String(voci.getValue("manifest.json"))).getInt("note"))

        val vuoto = ByteArrayOutputStream()
        try {
            BackupLocale.esportaNota(archivio, "nessuna", vuoto)
            fail("serviva un errore")
        } catch (_: IOException) {
        }
        assertEquals("per una nota che non c'e' non si scrive nulla", 0, vuoto.size())
    }

    @Test
    fun ilBackupSiRipristinaSuUnArchivioVuoto() {
        val altro = File(tmp.root, "altro/tratto")
        nota(altro, "aaaa", "pagine A")
        scriviIndice(altro, listOf(voce("aaaa", "A", 10, cartella = "c1")), listOf(cartella("c1", "Scuola")))

        val r = ripristina(backupDi(altro), ModoRipristino.UNISCI)

        assertEquals(RiepilogoRipristino(aggiunte = 1, aggiornate = 0, copie = 0, cartelleAggiunte = 1), r)
        assertEquals("pagine A", pagina("aaaa"))
        assertEquals("c1", voceLocale("aaaa").getString("cartella"))
        assertPulito()
    }

    // ------------------------------------------------------------ validazione

    @Test
    fun manifestMancante() {
        val prima = archivioDiProva()
        val dati = zip("tratto/indice.json" to indiceCon(indiceVuoto(), emptyList(), emptyList()).toString().toByteArray())

        val e = erroreDi { ripristina(dati, ModoRipristino.SOSTITUISCI) }

        assertEquals(BackupLocale.NON_E_UN_BACKUP, e.message)
        assertEquals(prima, File(radice, "indice.json").readText())
        assertPulito()
    }

    @Test
    fun manifestDiUnAltraApp() {
        val dati = zip(
            "manifest.json" to """{"app":"Altro","formato":1}""".toByteArray(),
            "tratto/indice.json" to "{}".toByteArray(),
        )
        assertEquals(BackupLocale.NON_E_UN_BACKUP, erroreDi { ripristina(dati, ModoRipristino.UNISCI) }.message)
    }

    @Test
    fun formatoPiuRecente() {
        val prima = archivioDiProva()
        val dati = zip(
            "manifest.json" to """{"app":"Tratto","formato":2,"creato":1,"note":0}""".toByteArray(),
            "tratto/indice.json" to indiceCon(indiceVuoto(), emptyList(), emptyList()).toString().toByteArray(),
        )

        val e = erroreDi { ripristina(dati, ModoRipristino.SOSTITUISCI) }

        assertTrue(e.message!!, "più recente" in e.message!!)
        assertEquals(prima, File(radice, "indice.json").readText())
    }

    @Test
    fun indiceMancante() {
        val dati = zip("manifest.json" to BackupLocale.manifest(0).toString().toByteArray())
        assertTrue("indice" in erroreDi { ripristina(dati, ModoRipristino.UNISCI) }.message!!)
    }

    @Test
    fun fileCheNonEUnoZip() {
        val prima = archivioDiProva()
        val e = erroreDi { ripristina("non sono uno zip".toByteArray(), ModoRipristino.SOSTITUISCI) }
        assertEquals(BackupLocale.NON_E_UN_BACKUP, e.message)
        assertEquals(prima, File(radice, "indice.json").readText())
    }

    @Test
    fun zipSlipVieneRifiutato() {
        val prima = archivioDiProva()
        for (nome in listOf("tratto/../../fuori.txt", "tratto/note/aaaa/../../../../../fuori.txt", "/fuori.txt", "tratto\\..\\fuori.txt")) {
            val dati = zip(
                "manifest.json" to BackupLocale.manifest(0).toString().toByteArray(),
                "tratto/indice.json" to indiceCon(indiceVuoto(), emptyList(), emptyList()).toString().toByteArray(),
                nome to "attacco".toByteArray(),
            )

            val e = erroreDi { ripristina(dati, ModoRipristino.SOSTITUISCI) }

            assertTrue(e.message!!, "percorso non valido" in e.message!!)
            assertFalse(File(tmp.root, "fuori.txt").exists())
            assertFalse(File(tmp.root, "files/fuori.txt").exists())
            assertFalse(File("/fuori.txt").exists())
            assertEquals(prima, File(radice, "indice.json").readText())
            assertPulito()
        }
    }

    // ------------------------------------------------------------ UNISCI

    @Test
    fun unisciTieneLaPiuRecenteELAltraComeCopia() {
        // Locale: A vecchia, B nuova, C solo locale.
        nota(radice, "aaaa", "locale A")
        nota(radice, "bbbb", "locale B")
        nota(radice, "cccc", "locale C")
        scriviIndice(
            radice,
            listOf(voce("aaaa", "A", 100, cartella = "c1"), voce("bbbb", "B", 300), voce("cccc", "C", 50)),
            listOf(cartella("c1", "Scuola")),
        )
        // Backup: A nuova, B vecchia, D solo nel backup (in una cartella che manca).
        val altro = File(tmp.root, "altro/tratto")
        nota(altro, "aaaa", "backup A")
        nota(altro, "bbbb", "backup B")
        nota(altro, "dddd", "backup D")
        scriviIndice(
            altro,
            listOf(voce("aaaa", "A", 200, cartella = "c1"), voce("bbbb", "B", 200), voce("dddd", "D", 10, cartella = "c2")),
            listOf(cartella("c1", "Scuola"), cartella("c2", "Lavoro")),
        )

        val r = ripristina(backupDi(altro), ModoRipristino.UNISCI)

        assertEquals(RiepilogoRipristino(aggiunte = 1, aggiornate = 1, copie = 2, cartelleAggiunte = 1), r)
        val note = indice().elenco("note")
        assertEquals(6, note.size)
        assertEquals(6, note.map { it.id }.toSet().size)

        // A: vince il backup, la locale resta come copia con un id nuovo.
        assertEquals("backup A", pagina("aaaa"))
        assertEquals(200, voceLocale("aaaa").modificata)
        val copiaA = note.single { it.titolo == "A (copia)" }
        assertNotEquals("aaaa", copiaA.id)
        assertEquals("locale A", pagina(copiaA.id))
        assertEquals(100, copiaA.modificata)
        assertEquals("c1", copiaA.getString("cartella"))

        // B: vince la locale, quella del backup diventa la copia.
        assertEquals("locale B", pagina("bbbb"))
        val copiaB = note.single { it.titolo == "B (copia)" }
        assertEquals("backup B", pagina(copiaB.id))

        // C resta, D arriva con la sua cartella.
        assertEquals("locale C", pagina("cccc"))
        assertEquals("backup D", pagina("dddd"))
        assertEquals("c2", voceLocale("dddd").getString("cartella"))
        assertEquals(listOf("c1", "c2"), indice().elenco("cartelle").map { it.id })
        assertPulito()
    }

    @Test
    fun unisciSenzaCopiaSeLePagineSonoUguali() {
        nota(radice, "aaaa", "stesse pagine")
        scriviIndice(radice, listOf(voce("aaaa", "Vecchio titolo", 100)))
        val altro = File(tmp.root, "altro/tratto")
        nota(altro, "aaaa", "stesse pagine")
        File(altro, "note/aaaa/anteprima.webp").writeBytes(byteArrayOf(7, 7)) // l'anteprima non conta
        scriviIndice(altro, listOf(voce("aaaa", "Titolo nuovo", 200)))

        val r = ripristina(backupDi(altro), ModoRipristino.UNISCI)

        assertEquals(RiepilogoRipristino(aggiunte = 0, aggiornate = 1, copie = 0), r)
        assertEquals(1, indice().elenco("note").size)
        assertEquals("Titolo nuovo", voceLocale("aaaa").titolo)
    }

    @Test
    fun unisciDueVolteNonDuplicaLaCopia() {
        nota(radice, "aaaa", "locale nuova")
        scriviIndice(radice, listOf(voce("aaaa", "A", 300)))
        val altro = File(tmp.root, "altro/tratto")
        nota(altro, "aaaa", "backup vecchio")
        scriviIndice(altro, listOf(voce("aaaa", "A", 200)))
        val dati = backupDi(altro)

        assertEquals(1, ripristina(dati, ModoRipristino.UNISCI).copie)
        assertEquals(0, ripristina(dati, ModoRipristino.UNISCI).copie)

        assertEquals(2, indice().elenco("note").size)
        assertEquals("locale nuova", pagina("aaaa"))
    }

    @Test
    fun unisciRiusaLaCartellaOmonima() {
        nota(radice, "aaaa", "A")
        scriviIndice(radice, listOf(voce("aaaa", "A", 1)), listOf(cartella("locale", "Scuola")))
        val altro = File(tmp.root, "altro/tratto")
        nota(altro, "bbbb", "B")
        scriviIndice(altro, listOf(voce("bbbb", "B", 1, cartella = "remota")), listOf(cartella("remota", " scuola ")))

        val r = ripristina(backupDi(altro), ModoRipristino.UNISCI)

        assertEquals(0, r.cartelleAggiunte)
        assertEquals(listOf("locale"), indice().elenco("cartelle").map { it.id })
        assertEquals("locale", voceLocale("bbbb").getString("cartella"))
    }

    @Test
    fun noteSenzaPagineNelBackupSiSaltano() {
        val altro = File(tmp.root, "altro/tratto")
        nota(altro, "aaaa", "A")
        scriviIndice(altro, listOf(voce("aaaa", "A", 1), voce("bbbb", "Persa", 1), voce("../x", "Cattiva", 1)))
        val dati = zip(
            "manifest.json" to BackupLocale.manifest(3).toString().toByteArray(),
            "tratto/indice.json" to File(altro, "indice.json").readBytes(),
            "tratto/note/aaaa/nota.json" to File(altro, "note/aaaa/nota.json").readBytes(),
            "tratto/note/aaaa/pagine/p1.tp" to "A".toByteArray(),
        )

        val r = ripristina(dati, ModoRipristino.UNISCI)

        assertEquals(1, r.aggiunte)
        assertEquals(2, r.saltate)
        assertEquals(listOf("aaaa"), indice().elenco("note").map { it.id })
    }

    // ------------------------------------------------------------ SOSTITUISCI

    @Test
    fun sostituisciRimpiazzaTutto() {
        nota(radice, "aaaa", "locale A")
        nota(radice, "bbbb", "locale B")
        scriviIndice(radice, listOf(voce("aaaa", "A", 100), voce("bbbb", "B", 100)), listOf(cartella("c1", "Scuola")))
        val altro = File(tmp.root, "altro/tratto")
        nota(altro, "aaaa", "backup A")
        nota(altro, "cccc", "backup C")
        scriviIndice(altro, listOf(voce("aaaa", "A", 50), voce("cccc", "C", 50, cartella = "sparita")))

        val r = ripristina(backupDi(altro), ModoRipristino.SOSTITUISCI)

        assertEquals(2, r.aggiunte)
        assertEquals(setOf("aaaa", "cccc"), indice().elenco("note").map { it.id }.toSet())
        assertEquals("backup A", pagina("aaaa"))
        assertFalse(File(radice, "note/bbbb").exists())
        assertTrue(indice().elenco("cartelle").isEmpty())
        assertNull("riferimento a una cartella che non esiste", voceLocale("cccc").testo("cartella"))
        assertPulito()
    }

    @Test
    fun ricaricaVieneChiamataDopoLoScambio() {
        val altro = File(tmp.root, "altro/tratto")
        nota(altro, "aaaa", "A")
        scriviIndice(altro, listOf(voce("aaaa", "A", 1)))
        var visto: String? = null

        BackupLocale.ripristinaIn(radice, temporanei, ByteArrayInputStream(backupDi(altro)), ModoRipristino.SOSTITUISCI, Any()) {
            visto = pagina("aaaa")
        }

        assertEquals("A", visto)
    }

    // ------------------------------------------------------------ supporto

    private fun archivioDiProva(): String {
        nota(radice, "aaaa", "locale A")
        scriviIndice(radice, listOf(voce("aaaa", "A", 100)))
        return File(radice, "indice.json").readText()
    }

    private fun nota(radice: File, id: String, pagine: String, pdf: ByteArray? = null) {
        val dir = File(radice, "note/$id")
        File(dir, "pagine").mkdirs()
        File(dir, "nota.json").writeText("""{"versione":1,"pagine":[{"id":"p1","sfondo":"RIGHE","w":1000.0,"h":1414.0}]}""")
        File(dir, "pagine/p1.tp").writeText(pagine)
        File(dir, "anteprima.webp").writeBytes(byteArrayOf(1, 2, 3))
        if (pdf != null) File(dir, "allegato.pdf").writeBytes(pdf)
    }

    private fun voce(id: String, titolo: String, modificata: Long, cartella: String? = null): JSONObject = JSONObject()
        .put("id", id).put("titolo", titolo).put("creata", 1L).put("modificata", modificata)
        .put("preferita", false).put("pagine", 1).put("sfondo", "RIGHE").put("pdf", false)
        .apply { if (cartella != null) put("cartella", cartella) }

    private fun cartella(id: String, nome: String): JSONObject = JSONObject().put("id", id).put("nome", nome).put("colore", 0xFF336699L)

    private fun scriviIndice(radice: File, note: List<JSONObject>, cartelle: List<JSONObject> = emptyList()) {
        radice.mkdirs()
        File(radice, "indice.json").writeText(indiceCon(indiceVuoto(), cartelle, note).toString())
    }

    private fun esportaPubblico(): ByteArray =
        ByteArrayOutputStream().also { BackupLocale.esporta(Archivio(radice, temporanei), it) }.toByteArray()

    private fun backupDi(r: File): ByteArray = ByteArrayOutputStream().also { BackupLocale.esportaIn(r, it, null) }.toByteArray()

    private fun ripristina(dati: ByteArray, modo: ModoRipristino): RiepilogoRipristino =
        BackupLocale.ripristinaIn(radice, temporanei, ByteArrayInputStream(dati), modo, Any()) {}

    private fun indice(): JSONObject = JSONObject(File(radice, "indice.json").readText())

    private fun voceLocale(id: String): JSONObject = indice().elenco("note").single { it.id == id }

    private fun pagina(id: String): String = File(radice, "note/$id/pagine/p1.tp").readText()

    private fun zip(vararg voci: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((nome, dati) in voci) {
                z.putNextEntry(ZipEntry(nome))
                z.write(dati)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun vociZip(dati: ByteArray): LinkedHashMap<String, ByteArray> {
        val voci = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(dati)).use { z ->
            while (true) {
                val v = z.nextEntry ?: break
                voci[v.name] = z.readBytes()
            }
        }
        return voci
    }

    private fun erroreDi(blocco: () -> Unit): BackupNonValido {
        try {
            blocco()
        } catch (e: BackupNonValido) {
            return e
        }
        fail("serviva un BackupNonValido")
        throw AssertionError()
    }

    /** Nessun avanzo: niente cartelle di lavoro in cache, niente tratto.preparazione/nuovo/vecchia. */
    private fun assertPulito() {
        assertTrue(temporanei.listFiles().isNullOrEmpty())
        val fratelli = radice.parentFile!!.listFiles()!!.map { it.name }.filter { it != "tratto" }
        assertTrue("avanzi: $fratelli", fratelli.isEmpty())
    }
}
