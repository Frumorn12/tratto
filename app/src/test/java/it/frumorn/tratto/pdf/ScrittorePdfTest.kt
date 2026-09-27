package it.frumorn.tratto.pdf

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNull
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import it.frumorn.tratto.data.Pagina
import it.frumorn.tratto.data.Sfondo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Prova la composizione del PDF con PdfBox-Android vero, sulla JVM e senza BouncyCastle: se l'esportazione
 * avesse bisogno di BouncyCastle o degli asset di PdfBox (font, CMap), qui fallirebbe.
 */
class ScrittorePdfTest {
    private val nero = StilePdf(0x000000, 255, false)
    private val giallo = StilePdf(0xFFEB3B, 0x66, true)

    /** Quadrato da (x, y) a (x + lato, y + lato) in unita' di pagina. */
    private fun quadrato(x: Float, y: Float, lato: Float) =
        floatArrayOf(x, y, x + lato, y, x + lato, y + lato, x, y + lato)

    /** PDF di prova con tre pagine riconoscibili da un commento nel loro content stream. */
    private fun sorgente(cifrato: Boolean = false): ByteArray {
        PDDocument(MemoryUsageSetting.setupMainMemoryOnly()).use { doc ->
            // Pagina 0: A4, con MediaBox e risorse ereditati dal nodo radice.
            val p0 = PDPage(PDRectangle(A4_LARGHEZZA_PT, A4_ALTEZZA_PT))
            doc.addPage(p0)
            // Pagina 1: 600x800 con CropBox e ruotata di 90 gradi.
            val p1 = PDPage(PDRectangle(600f, 800f))
            p1.cropBox = PDRectangle(10f, 20f, 580f, 760f)
            p1.rotation = 90
            doc.addPage(p1)
            // Pagina 2: orizzontale.
            val p2 = PDPage(PDRectangle(842f, 595f))
            doc.addPage(p2)
            for ((i, p) in listOf(p0, p1, p2).withIndex()) {
                PDPageContentStream(doc, p, PDPageContentStream.AppendMode.OVERWRITE, false).use { cs ->
                    cs.addComment("pagina-originale-$i")
                    cs.addRect(50f, 50f, 100f, 100f)
                    cs.fill()
                }
            }
            val catalogo = doc.documentCatalog.cosObject
            val radice = catalogo.getDictionaryObject(COSName.PAGES) as COSDictionary
            radice.setItem(COSName.MEDIA_BOX, p0.cosObject.getDictionaryObject(COSName.MEDIA_BOX))
            p0.cosObject.removeItem(COSName.MEDIA_BOX)

            // Riferimenti alla pagina 0 da fuori dell'albero: destinazione con nome, segnalibro, link.
            fun destinazione(p: PDPage) = COSArray().apply { add(p.cosObject); add(COSName.getPDFName("Fit")) }
            catalogo.setItem(COSName.getPDFName("Dests"), COSDictionary().apply { setItem("capitolo", destinazione(p0)) })
            val segnalibri = COSDictionary()
            val voce = COSDictionary().apply {
                setString(COSName.TITLE, "Capitolo")
                setItem(COSName.PARENT, segnalibri)
                setItem(COSName.DEST, destinazione(p0))
            }
            segnalibri.setItem(COSName.TYPE, COSName.OUTLINES)
            segnalibri.setItem(COSName.FIRST, voce)
            segnalibri.setItem(COSName.LAST, voce)
            segnalibri.setInt(COSName.COUNT, 1)
            catalogo.setItem(COSName.OUTLINES, segnalibri)
            fun link(p: PDPage) = COSDictionary().apply {
                setItem(COSName.TYPE, COSName.ANNOT)
                setItem(COSName.SUBTYPE, COSName.getPDFName("Link"))
                setItem(COSName.RECT, PDRectangle(0f, 0f, 10f, 10f).cosArray)
                setItem(COSName.DEST, destinazione(p))
            }
            p1.cosObject.setItem(COSName.ANNOTS, COSArray().apply { add(link(p0)); add(link(p2)) })
            if (cifrato) {
                val politica = StandardProtectionPolicy("proprietario", "", AccessPermission())
                politica.encryptionKeyLength = 128
                politica.isPreferAES = true
                doc.protect(politica)
            }
            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        }
    }

    private fun esporta(sorgente: ByteArray?, pagine: List<Pagina>, tratti: Map<String, List<TrattoPdf>>): ByteArray {
        val doc = if (sorgente != null) PDDocument.load(sorgente) else PDDocument()
        doc.use {
            ScrittorePdf.componi(doc, sorgente != null, pagine, "Prova") { tratti[it.id].orEmpty() }
            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        }
    }

    private fun contenuto(p: PDPage): String =
        p.contentStreams.asSequence().joinToString("\n") { String(it.toByteArray(), Charsets.ISO_8859_1) }

    private fun ultimoStream(p: PDPage): String =
        String(p.contentStreams.asSequence().last().toByteArray(), Charsets.ISO_8859_1)

    private fun contaRiempimenti(s: String) = Regex("(?m)^f$").findAll(s).count()

    @Test
    fun bouncyCastleNonCe() {
        try {
            Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider")
            fail("BouncyCastle doveva essere escluso")
        } catch (_: ClassNotFoundException) {
        }
    }

    @Test
    fun pdfAllegatoRiordinatoConPagineTolteEAggiunte() {
        val pagine = listOf(
            Pagina("a", Sfondo.BIANCO, pdfPagina = 2, altezza = 1000f * 595f / 842f),
            Pagina("b", Sfondo.RIGHE, pdfPagina = -1, altezza = 1414f),
            Pagina("c", Sfondo.BIANCO, pdfPagina = 1, altezza = 1000f * 580f / 760f),
            Pagina("d", Sfondo.BIANCO, pdfPagina = 1, altezza = 1000f * 580f / 760f),
        )
        val tratti = mapOf(
            "b" to listOf(TrattoPdf(giallo, listOf(quadrato(100f, 100f, 50f))), TrattoPdf(giallo, listOf(quadrato(120f, 100f, 50f)))),
            "c" to listOf(TrattoPdf(nero, listOf(quadrato(100f, 100f, 50f))), TrattoPdf(nero, listOf(quadrato(300f, 300f, 50f)))),
            "d" to listOf(TrattoPdf(nero, listOf(quadrato(500f, 500f, 10f)))),
        )
        val bytes = esporta(sorgente(), pagine, tratti)
        val testo = String(bytes, Charsets.ISO_8859_1)
        // La pagina 0 del PDF l'utente l'ha tolta: non deve esserci nemmeno come oggetto orfano.
        assertFalse(testo.contains("pagina-originale-0"))
        assertTrue(testo.contains("pagina-originale-1"))
        assertTrue(testo.contains("pagina-originale-2"))

        PDDocument.load(bytes).use { doc ->
            assertEquals(4, doc.numberOfPages)
            assertEquals("Prova", doc.documentInformation.title)

            val p0 = doc.getPage(0)
            assertEquals(842f, p0.mediaBox.width, 0.01f)
            assertEquals(595f, p0.mediaBox.height, 0.01f)
            assertTrue(contenuto(p0).contains("pagina-originale-2"))
            // Senza tratti la pagina originale resta com'e': un solo content stream.
            assertEquals(1, p0.contentStreams.asSequence().count())

            // Pagina aggiunta: larga come la prima pagina del PDF usata, alta in proporzione.
            val p1 = doc.getPage(1)
            assertEquals(842f, p1.mediaBox.width, 0.01f)
            assertEquals(842f * 1.414f, p1.mediaBox.height, 0.01f)
            val righe = contenuto(p1)
            assertTrue(righe.contains(" RG"))
            // Evidenziatore: trasparente e in Multiply, un riempimento per tratto.
            assertEquals(2, contaRiempimenti(righe))
            val gs = p1.resources.extGStateNames.map { p1.resources.getExtGState(it) }
            assertEquals(1, gs.size)
            assertEquals(0.4f, gs[0].nonStrokingAlphaConstant!!, 0.001f)
            assertEquals(BlendMode.MULTIPLY, gs[0].blendMode)

            // Pagina ruotata: rotazione e CropBox conservati, tratti nel sistema del PDF.
            val p2 = doc.getPage(2)
            assertEquals(90, p2.rotation)
            assertEquals(10f, p2.cropBox.lowerLeftX, 0.01f)
            assertEquals(780f, p2.cropBox.upperRightY, 0.01f)
            val nostro = ultimoStream(p2)
            // (100, 100) in Tratto: x = 10 + 0.76 * 100, y = 20 + 0.76 * 100 (ruotata di 90 gradi, larga 760).
            // Il quadrato esce in senso orario, quindi viene scritto al contrario (antiorario).
            assertTrue(nostro, nostro.contains("124 96 m\n124 134 l\n86 134 l\n86 96 l\n"))
            // Due tratti neri consecutivi: un solo riempimento.
            assertEquals(1, contaRiempimenti(nostro))
            assertTrue(contenuto(p2).startsWith("q"))

            // La stessa pagina del PDF usata due volte: contenuti separati.
            val p3 = doc.getPage(3)
            assertEquals(90, p3.rotation)
            assertTrue(contenuto(p3).contains("pagina-originale-1"))
            assertFalse(contenuto(p3).contains("86 96 l"))
            assertNotSame(
                p2.cosObject.getDictionaryObject(COSName.CONTENTS) as COSArray,
                p3.cosObject.getDictionaryObject(COSName.CONTENTS) as COSArray,
            )
            assertEquals(3, p2.contentStreams.asSequence().count())
            assertEquals(3, p3.contentStreams.asSequence().count())

            // Il link verso la pagina tolta non porta piu' da nessuna parte, quello verso una pagina
            // rimasta punta ancora a lei.
            val link = p2.cosObject.getDictionaryObject(COSName.ANNOTS) as COSArray
            val versoTolta = (link.getObject(0) as COSDictionary).getDictionaryObject(COSName.DEST) as COSArray
            val versoRimasta = (link.getObject(1) as COSDictionary).getDictionaryObject(COSName.DEST) as COSArray
            assertEquals(COSNull.NULL, versoTolta.getObject(0) ?: COSNull.NULL)
            assertSame(p0.cosObject, versoRimasta.getObject(0))
        }
    }

    @Test
    fun attributiEreditatiCopiatiSullaPagina() {
        val pagine = listOf(Pagina("a", Sfondo.BIANCO, pdfPagina = 0, altezza = 1414f))
        val tratti = mapOf("a" to listOf(TrattoPdf(nero, listOf(quadrato(0f, 0f, 1000f)))))
        PDDocument.load(esporta(sorgente(), pagine, tratti)).use { doc ->
            assertEquals(1, doc.numberOfPages)
            val p = doc.getPage(0)
            assertTrue(p.cosObject.containsKey(COSName.MEDIA_BOX))
            assertEquals(A4_LARGHEZZA_PT, p.mediaBox.width, 0.01f)
            // Il quadrato 1000x1000 copre la larghezza: da y = 841.89 in giu' fino a 841.89 - 595.28.
            assertTrue(ultimoStream(p).contains("0 246.61 m\n595.28 246.61 l\n595.28 841.89 l\n0 841.89 l\nf\n"))
        }
    }

    @Test
    fun pdfConPasswordDelProprietarioSenzaBouncyCastle() {
        val pagine = listOf(Pagina("a", Sfondo.BIANCO, pdfPagina = 1, altezza = 1000f * 580f / 760f))
        val tratti = mapOf("a" to listOf(TrattoPdf(nero, listOf(quadrato(100f, 100f, 50f)))))
        val bytes = esporta(sorgente(cifrato = true), pagine, tratti)
        PDDocument.load(bytes).use { doc ->
            assertFalse(doc.isEncrypted)
            assertEquals(1, doc.numberOfPages)
            assertTrue(contenuto(doc.getPage(0)).contains("pagina-originale-1"))
        }
    }

    @Test
    fun notaSenzaPdfInA4() {
        val pagine = listOf(
            Pagina("a", Sfondo.QUADRETTI),
            Pagina("b", Sfondo.PUNTINI),
            Pagina("c", Sfondo.BIANCO, altezza = 2000f),
        )
        val tratti = mapOf("a" to listOf(TrattoPdf(nero, listOf(quadrato(10f, 10f, 20f)))))
        PDDocument.load(esporta(null, pagine, tratti)).use { doc ->
            assertEquals(3, doc.numberOfPages)
            for (i in 0..1) {
                assertEquals(A4_LARGHEZZA_PT, doc.getPage(i).mediaBox.width, 0.01f)
                assertEquals(A4_ALTEZZA_PT, doc.getPage(i).mediaBox.height, 0.01f)
            }
            assertEquals(A4_LARGHEZZA_PT * 2f, doc.getPage(2).mediaBox.height, 0.01f)
            val quadretti = contenuto(doc.getPage(0))
            assertEquals(1, contaRiempimenti(quadretti))
            assertEquals(99, Regex(" l\n").findAll(quadretti).count() - 3)
            val puntini = contenuto(doc.getPage(1))
            assertTrue(puntini.contains("1 J"))
            assertEquals(0, contaRiempimenti(contenuto(doc.getPage(2))))
        }
    }
}
