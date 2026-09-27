package it.frumorn.tratto.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** I file degli oggetti accanto alle pagine e le immagini nella cartella della nota. */
class ArchivioOggettiTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var archivio: Archivio
    private lateinit var nota: File

    @Before
    fun prepara() {
        archivio = Archivio(File(tmp.root, "tratto"), File(tmp.root, "cache"))
        nota = archivio.cartellaNota("aaaa")
        File(nota, "pagine").mkdirs()
    }

    @Test
    fun paginaSenzaFileDegliOggettiNonHaOggetti() {
        assertTrue(archivio.oggetti("aaaa", "p1").isEmpty())
    }

    @Test
    fun fileRovinatoValeComeVuoto() {
        File(nota, "pagine/p1.oggetti.json").writeText("{rotto")
        assertTrue(archivio.oggetti("aaaa", "p1").isEmpty())
    }

    @Test
    fun salvaImmagineScriveUnFileNuovoOgniVolta() {
        val a = archivio.salvaImmagine("aaaa") { it.write(byteArrayOf(1, 2)) }
        val b = archivio.salvaImmagine("aaaa") { it.write(byteArrayOf(3)) }

        assertTrue(a, FormatoOggetti.fileValido(a))
        assertTrue(a != b)
        assertTrue(byteArrayOf(1, 2) contentEquals archivio.fileImmagine("aaaa", a).readBytes())
        assertFalse("niente .tmp avanzati", File(nota, "immagini").list()!!.any { it.endsWith(".tmp") })
    }

    @Test
    fun leImmaginiNonPiuUsateSiCancellano() {
        val usata = archivio.salvaImmagine("aaaa") { it.write(1) }
        val tolta = archivio.salvaImmagine("aaaa") { it.write(2) }
        val altrove = archivio.salvaImmagine("aaaa") { it.write(3) }
        File(nota, "pagine/p1.oggetti.json").writeText(FormatoOggetti.scrivi(listOf(Immagine("i", usata, 0f, 0f, 10f, 10f))))
        File(nota, "pagine/p2.oggetti.json").writeText(FormatoOggetti.scrivi(listOf(Immagine("j", altrove, 0f, 0f, 10f, 10f))))

        archivio.immaginiOrfane("aaaa")

        assertTrue(archivio.fileImmagine("aaaa", usata).exists())
        assertTrue(archivio.fileImmagine("aaaa", altrove).exists())
        assertFalse(archivio.fileImmagine("aaaa", tolta).exists())
    }

    @Test
    fun pagineOrfaneTolgonoAncheGliOggetti() {
        for (p in listOf("p1", "p2")) {
            File(nota, "pagine/$p.tp").writeText("tratti")
            File(nota, "pagine/$p.oggetti.json").writeText(FormatoOggetti.scrivi(emptyList()))
        }

        archivio.pagineOrfane("aaaa", setOf("p1"))

        assertEquals(listOf("p1.oggetti.json", "p1.tp"), File(nota, "pagine").list()!!.sorted())
    }
}
