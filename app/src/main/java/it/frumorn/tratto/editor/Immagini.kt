package it.frumorn.tratto.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import it.frumorn.tratto.data.Immagine
import it.frumorn.tratto.data.Oggetto
import it.frumorn.tratto.data.Testo
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * Le immagini delle note decodificate per l'editor. Si decodificano fuori dal thread principale e alla
 * risoluzione che serve (a gradini, per non rifarle a ogni zoom): una foto grande non entra mai intera
 * in memoria se la pagina e' piccola. Sono bitmap "hardware", che stanno nella memoria grafica e non
 * pesano sul Java heap.
 */
class ImmaginiEditor(private val alPronta: () -> Unit) {
    private val cache = object : LruCache<String, Bitmap>(MEMORIA) {
        override fun sizeOf(key: String, value: Bitmap) = value.width * value.height * 4
    }
    private val inCorso = HashSet<String>()
    /** Quando e' stata decodificata l'ultima volta ogni richiesta (o e' fallita). */
    private val fatte = HashMap<String, Long>()
    private val principale = Handler(Looper.getMainLooper())

    /**
     * La bitmap migliore gia' pronta per disegnare [file] largo [px] pixel, o null. Se quella giusta
     * non c'e' ancora la chiede: quando e' pronta si chiama [alPronta] (sul thread principale).
     */
    fun bitmap(file: File, px: Float): Bitmap? {
        val chiave = file.path
        val lato = GRADINI.firstOrNull { it >= px } ?: GRADINI.last()
        cache.get("$chiave@$lato")?.let { return it }
        val richiesta = "$chiave@$lato"
        // Una richiesta appena fatta non si ripete: se la memoria non basta per tutte le immagini in
        // vista (o il file non si legge), si andrebbe avanti a decodificare e ridisegnare in cerchio.
        val recente = fatte[richiesta]?.let { android.os.SystemClock.uptimeMillis() - it < RIPROVA_MS } == true
        if (!recente && inCorso.add(richiesta)) {
            decodificatore.execute {
                val bmp = decodifica(file, lato, hardware = true)
                principale.post {
                    inCorso.remove(richiesta)
                    fatte[richiesta] = android.os.SystemClock.uptimeMillis()
                    if (bmp != null) {
                        cache.put(richiesta, bmp)
                        alPronta()
                    }
                }
            }
        }
        // Intanto va bene un altro gradino gia' pronto, anche sfocato.
        for (g in GRADINI.reversed()) cache.get("$chiave@$g")?.let { return it }
        return null
    }

    fun svuota() = cache.evictAll()

    companion object {
        /** Larghezze a cui si decodificano le immagini: le immagini salvate non superano 2048 px di lato. */
        private val GRADINI = intArrayOf(256, 512, 1024, 2048)
        private const val MEMORIA = 96 shl 20
        private const val RIPROVA_MS = 5000L

        private val decodificatore = Executors.newFixedThreadPool(2) { r -> Thread(r, "immagini").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }

        /**
         * Decodifica [file] larga al massimo [larghezza] pixel (mantenendo le proporzioni), o null se
         * non si legge. [hardware] per disegnarla solo con la GPU; senza, si puo' disegnare ovunque.
         */
        fun decodifica(file: File, larghezza: Int, hardware: Boolean): Bitmap? = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { d, info, _ ->
                val w = info.size.width
                val h = info.size.height
                if (w > larghezza) d.setTargetSize(larghezza, max(1, (h.toLong() * larghezza / w).toInt()))
                d.allocator = if (hardware) ImageDecoder.ALLOCATOR_HARDWARE else ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (e: Exception) {
            Log.w("Immagini", "Immagine non leggibile: ${file.name}", e)
            null
        }
    }
}

/**
 * Disegna gli oggetti di una pagina su un canvas gia' in unita' di pagina. Immagini e testo si
 * prendono da chi chiama: l'editor usa le sue cache, esportazione e copertina decodificano al momento.
 */
object DisegnoOggetti {
    private val pImmagine = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val pSegnaposto = Paint().apply { color = 0x14000000 }
    private val pBordo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x22000000; strokeWidth = 1.5f }

    fun disegna(
        c: Canvas,
        oggetti: List<Oggetto>,
        nascosti: Set<String> = emptySet(),
        immagine: (Immagine) -> Bitmap?,
        impaginato: (Testo) -> Impaginato,
    ) {
        val r = RectF()
        for (o in oggetti) {
            if (o.id in nascosti) continue
            when (o) {
                is Immagine -> {
                    r.set(o.x, o.y, o.x + o.larghezza, o.y + o.altezza)
                    val bmp = immagine(o)
                    // Una bitmap hardware non si disegna su un canvas normale (copertina senza GPU).
                    if (bmp != null && (c.isHardwareAccelerated || bmp.config != Bitmap.Config.HARDWARE)) {
                        c.drawBitmap(bmp, null, r, pImmagine)
                    } else {
                        c.drawRect(r, pSegnaposto)
                        c.drawRect(r, pBordo)
                    }
                }
                is Testo -> {
                    val s = c.save()
                    c.translate(o.x, o.y)
                    impaginato(o).disegna(c)
                    c.restoreToCount(s)
                }
            }
        }
    }

    /** Altezza di un oggetto (per il testo serve l'impaginazione). */
    fun altezza(o: Oggetto): Float = when (o) {
        is Immagine -> o.altezza
        is Testo -> Impaginazione.impaginato(o).altezza
    }

    /** Riquadro di un oggetto in unita' di pagina. Da usare sul thread principale (impagina il testo). */
    fun riquadro(o: Oggetto, out: RectF = RectF()): RectF = when (o) {
        is Immagine -> out.apply { set(o.x, o.y, o.x + o.larghezza, o.y + o.altezza) }
        is Testo -> out.apply { set(o.x, o.y, o.x + o.larghezza, o.y + altezza(o)) }
    }

    /** Larghezza in pixel con cui un'immagine larga [larghezza] unita' finisce su una pagina larga [pxPagina]. */
    fun pixelPer(larghezza: Float, pxPagina: Float): Int = min(4096f, larghezza * pxPagina / FoglioView.LARGHEZZA).toInt().coerceAtLeast(1)
}
