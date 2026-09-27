package it.frumorn.tratto.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors

/**
 * Disegna le pagine del PDF allegato a una nota, come sfondo sotto i tratti.
 *
 * PdfRenderer non e' thread-safe e apre una pagina alla volta: tutto passa da un unico thread.
 * Le immagini sono tenute in una cache per (pagina, larghezza) arrotondata a gradini, cosi' durante
 * lo zoom si riusa l'immagine piu' vicina finche' non arriva quella nitida.
 */
class PdfSfondo(file: File, private val pronto: () -> Unit) : Closeable {
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(fd)
    private val esecutore = Executors.newSingleThreadExecutor { r -> Thread(r, "tratto-pdf").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val inCorso = HashSet<String>()
    @Volatile private var chiuso = false

    private val cache = object : LruCache<String, Bitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    val pagine: Int get() = renderer.pageCount

    /** Restituisce subito l'immagine migliore disponibile e, se serve, ne chiede una piu' nitida. */
    fun immagine(pagina: Int, larghezzaPx: Int): Bitmap? {
        val gradino = gradino(larghezzaPx)
        val chiave = "$pagina/$gradino"
        cache.get(chiave)?.let { return it }
        richiedi(pagina, gradino, chiave)
        // Nel frattempo, la migliore gia' pronta per questa pagina.
        return cache.snapshot().entries.filter { it.key.startsWith("$pagina/") }.maxByOrNull { it.value.width }?.value
    }

    private fun richiedi(pagina: Int, larghezza: Int, chiave: String) {
        synchronized(inCorso) { if (!inCorso.add(chiave)) return }
        esecutore.execute {
            try {
                if (chiuso) return@execute
                val bmp = renderer.openPage(pagina).use { p ->
                    val h = (larghezza.toFloat() * p.height / p.width).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(larghezza, h, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(Color.WHITE)
                        p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
                cache.put(chiave, bmp)
                pronto()
            } catch (_: Exception) {
            } finally {
                synchronized(inCorso) { inCorso.remove(chiave) }
            }
        }
    }

    override fun close() {
        chiuso = true
        esecutore.execute {
            runCatching { renderer.close() }
            runCatching { fd.close() }
        }
        esecutore.shutdown()
    }

    companion object {
        /** Larghezze a gradini (x1.5), limitate a 3000 px per non esaurire la memoria. */
        fun gradino(px: Int): Int {
            var g = 300
            while (g < px && g < 3000) g = (g * 1.5f).toInt()
            return g.coerceAtMost(3000)
        }

        /** Dimensioni delle pagine (larghezza, altezza in punti) per creare la nota. */
        fun dimensioni(file: File): List<Pair<Float, Float>> =
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { r ->
                    (0 until r.pageCount).map { i -> r.openPage(i).use { it.width.toFloat() to it.height.toFloat() } }
                }
            }
    }
}
