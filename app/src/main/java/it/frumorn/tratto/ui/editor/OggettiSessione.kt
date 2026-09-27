package it.frumorn.tratto.ui.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.text.Html
import android.widget.Toast
import androidx.compose.ui.geometry.Offset
import androidx.core.content.FileProvider
import it.frumorn.tratto.data.Archivio
import it.frumorn.tratto.data.FormatoOggetti
import it.frumorn.tratto.data.Immagine
import it.frumorn.tratto.data.Testo
import it.frumorn.tratto.data.TestoRicco
import it.frumorn.tratto.editor.EditorView
import it.frumorn.tratto.editor.FoglioView
import it.frumorn.tratto.editor.Impaginazione
import it.frumorn.tratto.editor.ImmaginiEditor
import it.frumorn.tratto.ui.StatoApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Le operazioni della nota aperta su immagini e caselle di testo: inserire un'immagine scelta o
 * incollata, incollare testo, copiare negli appunti di sistema, e i comandi della barra dell'oggetto.
 */
class OggettiSessione(private val s: SessioneEditor, private val stato: StatoApp, private val contesto: Context) {
    private val vista: EditorView get() = s.vista
    private val appunti get() = contesto.getSystemService(ClipboardManager::class.java)

    /** Negli appunti di sistema c'e' un'immagine o del testo da incollare. */
    fun appuntiDisponibili(): Boolean {
        val d = appunti?.primaryClipDescription ?: return false
        return d.hasMimeType("image/*") || d.hasMimeType("text/*")
    }

    /** Pagina e punto (in unita' di pagina) di un punto dello schermo, o il centro della pagina visibile. */
    private fun dove(schermo: Offset?) = schermo?.let { vista.puntoPagina(it.x, it.y) } ?: vista.puntoInserimento()

    /**
     * Inserisce un'immagine (dal selettore di foto o dagli appunti) centrata in [schermo], o al centro
     * della pagina visibile. Si rimpicciolisce a 2048 px di lato e si salva in WebP nella nota: senza
     * perdita se ha trasparenza (screenshot, loghi), altrimenti con una compressione leggera.
     */
    fun inserisciImmagine(uri: Uri, schermo: Offset? = null) {
        val d = s.documento ?: return
        val (i, pt) = dove(schermo)
        stato.scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(contesto.contentResolver, uri)) { dec, info, _ ->
                        val w = info.size.width; val h = info.size.height
                        val lato = max(w, h)
                        if (lato > LATO_MAX) dec.setTargetSize(max(1, w * LATO_MAX / lato), max(1, h * LATO_MAX / lato))
                        dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                    try {
                        val trasparente = bmp.hasAlpha()
                        val file = d.salvaImmagine("webp") { out ->
                            val ok = if (trasparente) bmp.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 30, out)
                            else bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, 88, out)
                            if (!ok) throw java.io.IOException("Compressione non riuscita")
                        }
                        Triple(file, bmp.width, bmp.height)
                    } finally {
                        bmp.recycle()
                    }
                }
            }
            r.onSuccess { (file, w, h) ->
                val p = d.pagine.getOrNull(i) ?: return@onSuccess
                // Grande al massimo 600 x 700 unita' (piu' di mezza pagina), mai piu' dei suoi pixel.
                var larghezza = min(LARGHEZZA_MAX, w.toFloat())
                var altezza = larghezza * h / w
                if (altezza > ALTEZZA_MAX) { altezza = ALTEZZA_MAX; larghezza = altezza * w / h }
                val x = (pt.x - larghezza / 2).coerceIn(0f, max(0f, FoglioView.LARGHEZZA - larghezza))
                val y = (pt.y - altezza / 2).coerceIn(0f, max(0f, p.pagina.altezza - altezza))
                vista.aggiungiOggetto(i, Immagine(Archivio.nuovoId(), file, x, y, larghezza, altezza))
            }.onFailure {
                Toast.makeText(contesto, "Immagine non inserita: ${it.message ?: "formato non leggibile"}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Incolla dagli appunti di sistema: un'immagine diventa un'immagine, il testo una casella di testo. */
    fun incolla(schermo: Offset? = null) {
        val clip = appunti?.primaryClip ?: return
        if (clip.itemCount == 0) return
        val item = clip.getItemAt(0)
        val uri = item.uri
        val immagine = uri != null && (clip.description.hasMimeType("image/*") || contesto.contentResolver.getType(uri)?.startsWith("image/") == true)
        if (immagine) {
            inserisciImmagine(uri, schermo)
            return
        }
        // Con l'HTML (copiato da un browser o da Documenti) resta anche il formato: grassetto, colori...
        val html = item.htmlText
        val testo = if (html != null) Impaginazione.modello(Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT))
        else TestoRicco.semplice(item.coerceToText(contesto).toString())
        val pulito = senzaRigheVuoteAiBordi(testo)
        if (pulito.vuoto) return
        val (i, pt) = dove(schermo)
        val larghezza = EditorView.LARGHEZZA_TESTO
        val x = pt.x.coerceIn(40f, FoglioView.LARGHEZZA - 40f - larghezza)
        vista.aggiungiOggetto(i, Testo(Archivio.nuovoId(), x, max(0f, pt.y - 12f), larghezza, pulito))
    }

    private fun senzaRigheVuoteAiBordi(t: TestoRicco): TestoRicco {
        val p = t.paragrafi.dropWhile { it.testo.isBlank() }.dropLastWhile { it.testo.isBlank() }
        return if (p.isEmpty()) TestoRicco.VUOTO else TestoRicco(p)
    }

    /** Una casella di testo nuova nel punto [schermo], aperta per scrivere. */
    fun testoQui(schermo: Offset) {
        vista.puntoPagina(schermo.x, schermo.y)?.let { (i, pt) -> vista.nuovoTesto(i, pt.x, pt.y) }
    }

    /**
     * Copia l'oggetto selezionato negli appunti di sistema: un'immagine come PNG (tramite il FileProvider,
     * cosi' la si incolla anche in altre app), un testo come testo con il suo formato in HTML.
     */
    fun copia() {
        when (val o = vista.oggettoSelezionato() ?: return) {
            is Immagine -> {
                val d = s.documento ?: return
                stato.scope.launch {
                    val f = withContext(Dispatchers.IO) {
                        runCatching {
                            val dir = File(contesto.cacheDir, "condivisi").apply { mkdirs() }
                            val png = File(dir, "immagine-${o.id}.png")
                            val bmp = ImmaginiEditor.decodifica(d.fileImmagine(o.file), 4096, hardware = false) ?: error("immagine non leggibile")
                            try { png.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { bmp.recycle() }
                            png
                        }.getOrNull()
                    } ?: run { Toast.makeText(contesto, "Immagine non copiata", Toast.LENGTH_SHORT).show(); return@launch }
                    val uri = FileProvider.getUriForFile(contesto, "${contesto.packageName}.file", f)
                    appunti?.setPrimaryClip(ClipData.newUri(contesto.contentResolver, "Immagine", uri))
                }
            }
            is Testo -> appunti?.setPrimaryClip(ClipData.newHtmlText("Testo", o.contenuto.testoSemplice, html(o.contenuto)))
        }
    }

    fun elimina() = vista.eliminaOggetto()
    fun duplica() = vista.duplicaOggetto()
    fun avanti() = vista.spostaInOrdine(avanti = true)
    fun indietro() = vista.spostaInOrdine(avanti = false)
    fun modifica() = vista.modificaTestoSelezionato()
    fun chiudi() = vista.deselezionaOggetto()

    companion object {
        private const val LATO_MAX = 2048
        private const val LARGHEZZA_MAX = 600f
        private const val ALTEZZA_MAX = 700f

        /** Il testo ricco in HTML semplice, quello che capiscono le altre app (e Html.fromHtml). */
        fun html(t: TestoRicco): String = buildString {
            for (p in t.paragrafi) {
                val allinea = when (p.allineamento) {
                    it.frumorn.tratto.data.Allineamento.CENTRO -> "center"
                    it.frumorn.tratto.data.Allineamento.DESTRA -> "right"
                    it.frumorn.tratto.data.Allineamento.GIUSTIFICATO -> "justify"
                    else -> null
                }
                append(if (allinea != null) "<p style=\"text-align:$allinea\">" else "<p>")
                for (f in p.frammenti) {
                    var s = Html.escapeHtml(f.testo)
                    val stile = buildList {
                        f.colore?.let { add("color:${FormatoOggetti.colore(it or (0xFF shl 24))}") }
                        f.evidenziato?.let { add("background-color:${FormatoOggetti.colore(it or (0xFF shl 24))}") }
                    }
                    if (stile.isNotEmpty()) s = "<span style=\"${stile.joinToString(";")}\">$s</span>"
                    if (f.barrato) s = "<s>$s</s>"
                    if (f.sottolineato) s = "<u>$s</u>"
                    if (f.corsivo) s = "<i>$s</i>"
                    if (f.grassetto) s = "<b>$s</b>"
                    append(s)
                }
                append("</p>")
            }
        }
    }
}
