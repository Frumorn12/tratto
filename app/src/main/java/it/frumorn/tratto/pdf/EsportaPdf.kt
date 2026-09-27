package it.frumorn.tratto.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.ink.geometry.MutableVec
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import it.frumorn.tratto.data.Archivio
import it.frumorn.tratto.data.Foglio
import it.frumorn.tratto.data.Immagine
import it.frumorn.tratto.data.NotaInfo
import it.frumorn.tratto.data.Oggetto
import it.frumorn.tratto.data.Pagina
import it.frumorn.tratto.data.Penna
import it.frumorn.tratto.data.Sfondo
import it.frumorn.tratto.data.Testo
import it.frumorn.tratto.data.Tratto
import it.frumorn.tratto.editor.Anteprima
import it.frumorn.tratto.editor.Impaginazione
import it.frumorn.tratto.editor.ImmaginiEditor
import it.frumorn.tratto.ink.Pennelli
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Esportazione di una nota: in PDF vettoriale (tutta la nota) o in PNG (una pagina, da condividere).
 *
 * Tutto e' bloccante e fa I/O: va chiamato fuori dal thread principale. I tratti si leggono dal disco,
 * quindi prima di esportare l'editor deve aver salvato le pagine modificate.
 */
object EsportaPdf {
    const val TITOLO_PREDEFINITO = "Nota senza titolo"

    /** Oltre questa soglia PdfBox appoggia il documento su un file temporaneo nella cache. */
    private const val MEMORIA_MAX = 16L * 1024 * 1024

    /** Lato massimo delle immagini: oltre, alcune GPU non ce la fanno a disegnarle. */
    const val LATO_MAX_PX = 4096

    private const val TAG = "EsportaPdf"

    fun titolo(info: NotaInfo): String = info.titolo.ifBlank { TITOLO_PREDEFINITO }

    /**
     * Scrive la nota in PDF su [out] (che resta aperto: lo chiude chi lo ha aperto).
     *
     * Se la nota viene da un PDF, le sue pagine restano quelle originali (testo selezionabile) con i
     * tratti sopra, in vettoriale; altrimenti ogni pagina e' un A4 con righe, quadretti o puntini.
     *
     * @throws IOException se il PDF allegato non si apre (rovinato o protetto)
     */
    fun esporta(context: Context, archivio: Archivio, info: NotaInfo, out: OutputStream) {
        // Inizializzato solo qui, non all'avvio: PdfBox si carica solo quando si esporta davvero.
        if (!PDFBoxResourceLoader.isReady()) PDFBoxResourceLoader.init(context.applicationContext)
        val pagine = archivio.pagine(info.id)
        val file = archivio.filePdf(info.id)
        val memoria = MemoryUsageSetting.setupMixed(MEMORIA_MAX).setTempDir(context.cacheDir)
        val allegato = file.exists()
        val doc = if (allegato) apri(file, memoria) else PDDocument(memoria)
        // I PDF dei livelli di testo restano aperti fino al salvataggio: il documento li riferisce.
        val livelli = ArrayList<PDDocument>()
        try {
            doc.use {
                ScrittorePdf.componi(
                    doc, allegato, pagine, titolo(info),
                    oggetti = { p -> oggettiPdf(archivio, info, p, livelli) },
                ) { p -> trattiPdf(archivio.tratti(info.id, p.id)) }
                // PdfBox chiude lo stream che riceve: gli diamo un involucro che non chiude quello di chi chiama.
                BufferedOutputStream(NonChiudere(out), 64 * 1024).use { doc.save(it) }
            }
        } finally {
            livelli.forEach { runCatching { it.close() } }
        }
    }

    /**
     * Gli oggetti di una pagina per il PDF, nell'ordine di disegno. Le immagini restano immagini (JPEG,
     * o senza perdita se hanno trasparenza); le caselle di testo vicine nell'ordine si disegnano insieme
     * su una pagina PDF di Android, che scrive testo vero con i caratteri incorporati, e quella pagina
     * diventa un livello del PDF esportato.
     */
    private fun oggettiPdf(archivio: Archivio, info: NotaInfo, p: Pagina, aperti: MutableList<PDDocument>): List<OggettoPdf> {
        val oggetti = archivio.oggetti(info.id, p.id)
        if (oggetti.isEmpty()) return emptyList()
        val out = ArrayList<OggettoPdf>()
        val testi = ArrayList<Testo>()
        fun chiudiTesti() {
            if (testi.isEmpty()) return
            livelloTesto(testi, p.altezza)?.let { (sorgente, livello) -> aperti += sorgente; out += livello }
            testi.clear()
        }
        for (o in oggetti) {
            when (o) {
                is Testo -> testi += o
                is Immagine -> {
                    chiudiTesti()
                    val f = archivio.fileImmagine(info.id, o.file)
                    out += ImmaginePdf(o.x, o.y, o.larghezza, o.altezza) { d -> immaginePdf(d, f, o.larghezza) }
                }
            }
        }
        chiudiTesti()
        return out
    }

    /** Le caselle [testi] su una pagina PDF larga 1000 punti (uno per unita'), aperta con PdfBox. */
    private fun livelloTesto(testi: List<Testo>, altezza: Float): Pair<PDDocument, LivelloPdf>? {
        val h = ceil(altezza).toInt().coerceAtLeast(1)
        val pdf = PdfDocument()
        val dati = try {
            val pagina = pdf.startPage(PdfDocument.PageInfo.Builder(Foglio.LARGHEZZA.toInt(), h, 1).create())
            val c = pagina.canvas
            for (t in testi) {
                val s = c.save()
                c.translate(t.x, t.y)
                Impaginazione.impagina(t).disegna(c)
                c.restoreToCount(s)
            }
            pdf.finishPage(pagina)
            ByteArrayOutputStream().also { pdf.writeTo(it) }.toByteArray()
        } catch (e: Exception) {
            Log.w(TAG, "Caselle di testo non esportate", e)
            return null
        } finally {
            pdf.close()
        }
        val sorgente = PDDocument.load(dati)
        return sorgente to LivelloPdf(sorgente, 0, h.toFloat())
    }

    /**
     * L'immagine per il PDF, a circa 300 dpi sulla pagina A4 (e mai piu' grande del file). Le foto vanno
     * in JPEG; le immagini con trasparenza senza perdita, con la loro maschera.
     */
    private fun immaginePdf(doc: PDDocument, file: File, larghezza: Float): PDImageXObject? {
        val bmp = ImmaginiEditor.decodifica(file, (larghezza * 3.5f).toInt().coerceIn(1, 2048), hardware = false) ?: return null
        return try {
            if (bmp.hasAlpha()) LosslessFactory.createFromImage(doc, bmp) else JPEGFactory.createFromImage(doc, bmp, 0.9f)
        } finally {
            bmp.recycle()
        }
    }

    private fun apri(file: File, memoria: MemoryUsageSetting): PDDocument = try {
        PDDocument.load(file, "", memoria)
    } catch (e: InvalidPasswordException) {
        throw IOException("Il PDF allegato e' protetto da password", e)
    } catch (e: NoClassDefFoundError) {
        // Solo i PDF cifrati con un certificato usano BouncyCastle, che non includiamo.
        throw IOException("Il PDF allegato e' cifrato con un certificato: non si puo' esportare", e)
    }

    /** I tratti di una pagina con i contorni calcolati da Ink. */
    private fun trattiPdf(tratti: List<Tratto>): List<TrattoPdf> {
        val v = MutableVec()
        return tratti.mapNotNull { t ->
            val stroke = Pennelli.stroke(t) ?: return@mapNotNull null
            val contorni = contorni(stroke, v)
            if (contorni.isEmpty()) null else TrattoPdf(stilePdf(t), contorni)
        }
    }

    /** I contorni chiusi di un tratto, [x0, y0, x1, y1, ...] in unita' di pagina. */
    private fun contorni(stroke: Stroke, v: MutableVec = MutableVec()): List<FloatArray> {
        val mesh = stroke.shape
        val out = ArrayList<FloatArray>()
        for (g in 0 until mesh.getRenderGroupCount()) {
            for (o in 0 until mesh.getOutlineCount(g)) {
                val n = mesh.getOutlineVertexCount(g, o)
                if (n < 3) continue
                val xy = FloatArray(n * 2)
                for (i in 0 until n) {
                    mesh.populateOutlinePosition(g, o, i, v)
                    xy[2 * i] = v.x
                    xy[2 * i + 1] = v.y
                }
                out += xy
            }
        }
        return out
    }

    /**
     * Disegna una pagina della nota in un'immagine larga [larghezzaPx] pixel (al massimo [LATO_MAX_PX]
     * per lato): carta bianca, sfondo o pagina del PDF, tratti. I tratti sono disegnati da Ink come a
     * schermo, anche la matita con la sua opacita' variabile.
     *
     * @param tratti i tratti da disegnare; di solito quelli salvati, ma l'editor puo' passare i suoi
     */
    fun immaginePagina(
        archivio: Archivio,
        info: NotaInfo,
        pagina: Pagina,
        larghezzaPx: Int,
        tratti: List<Tratto> = archivio.tratti(info.id, pagina.id),
        oggetti: List<Oggetto> = archivio.oggetti(info.id, pagina.id),
    ): Bitmap {
        val scala = minOf(larghezzaPx.coerceIn(1, LATO_MAX_PX) / Foglio.LARGHEZZA, LATO_MAX_PX / pagina.altezza)
        val w = (Foglio.LARGHEZZA * scala).roundToInt().coerceAtLeast(1)
        val h = (pagina.altezza * scala).roundToInt().coerceAtLeast(1)

        val fondo = if (pagina.pdfPagina >= 0) paginaPdf(archivio.filePdf(info.id), pagina.pdfPagina, w, h) else null
        val strokes = tratti.mapNotNull { t -> Pennelli.stroke(t)?.let { t to it } }
        val disegna = { c: Canvas ->
            disegnaPagina(c, pagina, fondo, strokes, scala) { cs ->
                Anteprima.disegnaOggetti(cs, oggetti, w.toFloat()) { archivio.fileImmagine(info.id, it) }
            }
        }
        try {
            return try {
                disegnaInHardware(w, h, disegna)
            } catch (e: Exception) {
                // Senza GPU la matita (che si somma su se stessa) non la disegna Ink: ci pensa disegnaPagina.
                Log.w(TAG, "Disegno con la GPU non riuscito, uso la CPU", e)
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { disegna(Canvas(it)) }
            }
        } finally {
            fondo?.recycle()
        }
    }

    /** Come [immaginePagina], ma scrive direttamente il PNG su [out] (che resta aperto). */
    fun esportaPagina(
        archivio: Archivio,
        info: NotaInfo,
        pagina: Pagina,
        larghezzaPx: Int,
        out: OutputStream,
        tratti: List<Tratto> = archivio.tratti(info.id, pagina.id),
    ) {
        val bmp = immaginePagina(archivio, info, pagina, larghezzaPx, tratti)
        try {
            if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IOException("PNG non scritto")
        } finally {
            bmp.recycle()
        }
    }

    /** La pagina [indice] del PDF disegnata a [w] x [h] pixel su fondo bianco, o null se non c'e'. */
    private fun paginaPdf(file: File, indice: Int, w: Int, h: Int): Bitmap? {
        if (!file.exists()) return null
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                if (indice >= r.pageCount) return null
                r.openPage(indice).use { p ->
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return bmp
                }
            }
        }
    }

    /** Carta, sfondo, oggetti (con [oggetti], sul canvas in unita' di pagina) e tratti. */
    private fun disegnaPagina(c: Canvas, pagina: Pagina, fondo: Bitmap?, strokes: List<Pair<Tratto, Stroke>>, scala: Float, oggetti: (Canvas) -> Unit) {
        c.drawColor(Color.WHITE)
        if (fondo != null) {
            c.drawBitmap(fondo, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            c.save()
            c.scale(scala, scala)
            disegnaSfondo(c, pagina.sfondo, pagina.altezza, scala)
            c.restore()
        }

        val m = Matrix().apply { setScale(scala, scala) }
        val renderer = CanvasStrokeRenderer.create()
        val v = MutableVec()
        val pMatita = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        c.save()
        c.concat(m)
        oggetti(c)
        for ((t, s) in strokes) {
            if (!c.isHardwareAccelerated && t.penna == Penna.MATITA) {
                // Ripiego senza GPU: contorno pieno con l'opacita' media, come nel PDF.
                val stile = stilePdf(t)
                pMatita.color = (stile.alfa shl 24) or stile.rgb
                c.drawPath(percorso(contorni(s, v)), pMatita)
            } else {
                renderer.draw(c, s, m)
            }
        }
        c.restore()
    }

    /** Righe, quadretti o puntini in unita' di pagina (il canvas e' gia' scalato). */
    private fun disegnaSfondo(c: Canvas, sfondo: Sfondo, altezza: Float, scala: Float) {
        if (sfondo == Sfondo.BIANCO) return
        val colore = Color.rgb((COLORE_SFONDO shr 16) and 0xFF, (COLORE_SFONDO shr 8) and 0xFF, COLORE_SFONDO and 0xFF)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colore }
        if (sfondo == Sfondo.PUNTINI) {
            p.strokeCap = Paint.Cap.ROUND
            p.strokeWidth = 2f * RAGGIO_PUNTINI
            c.drawPoints(puntiniSfondo(altezza), p)
        } else {
            // Come nel PDF: 0,5 pt su una pagina A4, ma mai sotto il pixel.
            p.strokeWidth = max(SPESSORE_RIGHE_PT * Foglio.LARGHEZZA / A4_LARGHEZZA_PT, 1f / scala)
            c.drawLines(lineeSfondo(sfondo, altezza), p)
        }
    }

    private fun percorso(contorni: List<FloatArray>): Path {
        val path = Path()
        for (xy in contorni) {
            path.moveTo(xy[0], xy[1])
            for (i in 1 until xy.size / 2) path.lineTo(xy[2 * i], xy[2 * i + 1])
            path.close()
        }
        return path
    }

    /**
     * Disegna con la GPU fuori dallo schermo (RenderNode -> HardwareRenderer -> ImageReader) e copia il
     * risultato in una bitmap normale. Serve perche' Ink disegna la matita solo su un canvas accelerato.
     */
    private fun disegnaInHardware(w: Int, h: Int, disegna: (Canvas) -> Unit): Bitmap {
        val nodo = RenderNode("esporta")
        nodo.setPosition(0, 0, w, h)
        val c = nodo.beginRecording(w, h)
        try {
            disegna(c)
        } finally {
            nodo.endRecording()
        }
        val lettore = ImageReader.newInstance(
            w, h, PixelFormat.RGBA_8888, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
        )
        val renderer = HardwareRenderer()
        try {
            renderer.setSurface(lettore.surface)
            renderer.setContentRoot(nodo)
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            val immagine = lettore.acquireNextImage() ?: throw IllegalStateException("Nessuna immagine dalla GPU")
            immagine.use {
                val buffer = it.hardwareBuffer ?: throw IllegalStateException("Nessun buffer dalla GPU")
                buffer.use { hb ->
                    val hw = Bitmap.wrapHardwareBuffer(hb, ColorSpace.get(ColorSpace.Named.SRGB))
                        ?: throw IllegalStateException("Buffer della GPU non leggibile")
                    val copia = hw.copy(Bitmap.Config.ARGB_8888, false)
                    hw.recycle()
                    return copia ?: throw IllegalStateException("Copia dell'immagine non riuscita")
                }
            }
        } finally {
            renderer.destroy()
            lettore.close()
            nodo.discardDisplayList()
        }
    }

    /** Passa tutto a [out] tranne la chiusura, che resta a chi lo ha aperto. */
    private class NonChiudere(out: OutputStream) : FilterOutputStream(out) {
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun close() = out.flush()
    }
}
