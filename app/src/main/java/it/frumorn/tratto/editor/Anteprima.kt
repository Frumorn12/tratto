package it.frumorn.tratto.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import it.frumorn.tratto.data.Archivio
import it.frumorn.tratto.data.Oggetto
import it.frumorn.tratto.data.Sfondo
import it.frumorn.tratto.pdf.PdfSfondo
import java.io.File

/** Crea la copertina di una nota (la prima pagina, rimpicciolita) per la libreria. */
object Anteprima {
    const val LARGHEZZA_PX = 420

    fun crea(archivio: Archivio, doc: Documento, pdf: PdfSfondo?) {
        val p = doc.pagine.firstOrNull() ?: return
        doc.caricaTratti(p)
        val s = LARGHEZZA_PX / FoglioView.LARGHEZZA
        val w = LARGHEZZA_PX
        val h = (p.pagina.altezza * s).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val sfondoPdf = if (p.pagina.pdfPagina >= 0) pdf?.immagine(p.pagina.pdfPagina, w) else null
        if (sfondoPdf != null) {
            c.drawBitmap(sfondoPdf, null, android.graphics.RectF(0f, 0f, w.toFloat(), h.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            disegnaModello(c, p.pagina.sfondo, s, p.pagina.altezza)
        }
        val m = Matrix().apply { setScale(s, s) }
        val renderer = CanvasStrokeRenderer.create()
        c.save()
        c.concat(m)
        // Immagini e caselle di testo sotto i tratti, come nell'editor.
        val oggetti = synchronized(p) { ArrayList(p.oggetti) }
        disegnaOggetti(c, oggetti, w.toFloat()) { doc.fileImmagine(it) }
        synchronized(p) {
            for (passata in 0..1) for (t in p.tratti) {
                if ((t.penna == it.frumorn.tratto.data.Penna.EVIDENZIATORE) != (passata == 0)) continue
                p.stroke(t)?.let { renderer.draw(c, it, m) }
            }
        }
        c.restore()
        val file = archivio.fileAnteprima(doc.info.id)
        val tmp = File(file.parentFile, "anteprima.tmp")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, 82, it) }
        tmp.renameTo(file)
        bmp.recycle()
    }

    /**
     * Disegna gli oggetti su un canvas qualsiasi (anche senza GPU) gia' in unita' di pagina, per una
     * pagina larga [pxPagina] pixel: le immagini si decodificano adesso alla grandezza giusta e il
     * testo si impagina da capo, perche' si e' fuori dal thread principale.
     */
    fun disegnaOggetti(c: Canvas, oggetti: List<Oggetto>, pxPagina: Float, file: (String) -> File) {
        if (oggetti.isEmpty()) return
        val decodificate = ArrayList<Bitmap>()
        try {
            DisegnoOggetti.disegna(
                c, oggetti,
                immagine = { im ->
                    ImmaginiEditor.decodifica(file(im.file), DisegnoOggetti.pixelPer(im.larghezza, pxPagina), hardware = false)
                        ?.also { decodificate += it }
                },
                impaginato = { Impaginazione.impagina(it) },
            )
        } finally {
            // Su un canvas accelerato (RenderNode) la bitmap serve fino al disegno vero: la libera il GC.
            if (!c.isHardwareAccelerated) decodificate.forEach { it.recycle() }
        }
    }

    private fun disegnaModello(c: Canvas, sfondo: Sfondo, s: Float, altezza: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(217, 214, 236); strokeWidth = 1f }
        when (sfondo) {
            Sfondo.RIGHE -> {
                var y = FoglioView.RIGHE_INIZIO
                while (y < altezza - 40f) { c.drawLine(60f * s, y * s, (FoglioView.LARGHEZZA - 60f) * s, y * s, p); y += FoglioView.PASSO_RIGHE }
            }
            Sfondo.QUADRETTI -> {
                var x = FoglioView.PASSO_QUADRETTI
                while (x < FoglioView.LARGHEZZA) { c.drawLine(x * s, 0f, x * s, altezza * s, p); x += FoglioView.PASSO_QUADRETTI }
                var y = FoglioView.PASSO_QUADRETTI
                while (y < altezza) { c.drawLine(0f, y * s, FoglioView.LARGHEZZA * s, y * s, p); y += FoglioView.PASSO_QUADRETTI }
            }
            Sfondo.PUNTINI -> {
                var y = FoglioView.PASSO_QUADRETTI
                while (y < altezza) {
                    var x = FoglioView.PASSO_QUADRETTI
                    while (x < FoglioView.LARGHEZZA) { c.drawCircle(x * s, y * s, 1f, p); x += FoglioView.PASSO_QUADRETTI }
                    y += FoglioView.PASSO_QUADRETTI
                }
            }
            Sfondo.BIANCO -> Unit
        }
    }
}
