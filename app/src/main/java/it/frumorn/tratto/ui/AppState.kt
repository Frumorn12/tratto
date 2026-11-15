package it.frumorn.tratto.ui

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import it.frumorn.tratto.R
import it.frumorn.tratto.backup.AutoExport
import it.frumorn.tratto.data.Archive
import it.frumorn.tratto.data.NotaInfo
import it.frumorn.tratto.data.NoteshelfImport
import it.frumorn.tratto.data.Preferences
import it.frumorn.tratto.data.Sfondo
import it.frumorn.tratto.data.Tratto
import it.frumorn.tratto.editor.Preview
import it.frumorn.tratto.editor.Document
import it.frumorn.tratto.ink.Brushes
import it.frumorn.tratto.pdf.PdfBackground
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Schermata {
    data object Library : Schermata
    /** [origine] e' la chiave dell'elemento da cui parte l'animazione di apertura. */
    data class Editor(val id: String, val origine: String) : Schermata
    data object Settings : Schermata
}

sealed interface Filtro {
    data object Tutte : Filtro
    data object Preferite : Filtro
    data class InCartella(val id: String) : Filtro
    /** Chapitre ouvert dans le cahier [cartellaId]. */
    data class InCapitolo(val cartellaId: String, val capitoloId: String) : Filtro
}

/** Cahier courant (ouvert, ou celui du chapitre ouvert). */
fun Filtro.cartellaAperta(): String? = when (this) {
    is Filtro.InCartella -> id
    is Filtro.InCapitolo -> cartellaId
    else -> null
}

fun Filtro.capitoloAperto(): String? = (this as? Filtro.InCapitolo)?.capitoloId

/** Stato di tutta l'app: schermata corrente, filtri della libreria e operazioni sulle note. */
class AppState(private val context: Context, val archivio: Archive, val preferenze: Preferences) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var schermata by mutableStateOf<Schermata>(Schermata.Library)
    var filtro by mutableStateOf<Filtro>(Filtro.Tutte)
    var ricerca by mutableStateOf("")
    var indiceCaricato by mutableStateOf(false)
        private set
    /** Cresce quando cambia una copertina: la libreria ricarica le immagini. */
    var versioneAnteprime by mutableIntStateOf(0)

    /** Tratti copiati con il lazo, incollabili in qualsiasi nota. */
    var appunti: List<Tratto> = emptyList()

    /**
     * Immagini e caselle di testo copiate con il lazo insieme ai tratti. Per le immagini c'e' anche il
     * file: incollando in un'altra nota lo si ricopia li'.
     */
    var appuntiOggetti: List<Pair<it.frumorn.tratto.data.Oggetto, File?>> = emptyList()

    init {
        Brushes.sensibilita = preferenze.sensibilita
    }

    fun caricaIndice() {
        scope.launch {
            withContext(Dispatchers.IO) {
                archivio.caricaIndice()
                // Noteshelf natif / paysage : même largeur Tratto (1000) avant les vignettes.
                archivio.normalizzaPagineNoteshelf()
            }
            indiceCaricato = true
            rigeneraAnteprimeMancanti()
        }
    }

    /**
     * Crea in sottofondo le copertine che mancano, e rifa' una sola volta quelle di pagine piu'
     * larghe di 1000 (import Noteshelf vecchio) ritagliate a destra: l'anteprima troppo alta
     * (scala calcolata su 1000) lo rivela.
     */
    private fun rigeneraAnteprimeMancanti() {
        scope.launch {
            var fatte = 0
            withContext(Dispatchers.IO) {
                delay(1500) // dopo l'avvio, per non rubare tempo al primo fotogramma
                for (n in archivio.note.value) {
                    val anteprima = archivio.fileAnteprima(n.id)
                    val d = runCatching { Document(archivio, n).also { it.apri() } }.getOrNull() ?: continue
                    val pagina = d.pagine.firstOrNull()?.pagina
                    val attesa = pagina?.let {
                        (Preview.LARGHEZZA_PX * it.altezza / it.larghezza.coerceAtLeast(1f)).toInt().coerceAtLeast(1)
                    }
                    val storta = attesa != null && anteprima.exists() && runCatching {
                        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeFile(anteprima.absolutePath, o)
                        o.outHeight > attesa + 40
                    }.getOrDefault(false)
                    if (anteprima.exists() && !storta) continue
                    runCatching {
                        val f = archivio.filePdf(n.id)
                        val pdf = if (f.exists()) PdfBackground(f) {} else null
                        pdf.use { sfondo ->
                            // Il PDF si disegna in un thread a parte: aspettiamo la prima pagina.
                            if (sfondo != null && d.pagine.firstOrNull()?.pagina?.pdfPagina == 0) {
                                repeat(20) { if (sfondo.immagine(0, Preview.LARGHEZZA_PX) == null) Thread.sleep(100) }
                            }
                            Preview.crea(archivio, d, sfondo)
                        }
                        fatte++
                    }
                }
            }
            if (fatte > 0) versioneAnteprime++
        }
    }

    fun apri(info: NotaInfo, origine: String = "nota-${info.id}") {
        schermata = Schermata.Editor(info.id, origine)
    }

    /** Crea una nota vuota e la apre subito. */
    fun nuovaNota(origine: String = "nuova", sfondo: Sfondo = preferenze.sfondoPredefinito) {
        scope.launch {
            val cartella = filtro.cartellaAperta()
            val capitolo = filtro.capitoloAperto()
            val info = withContext(Dispatchers.IO) {
                archivio.caricaIndice()
                archivio.nuovaNota(sfondo, cartella, capitolo).also { AutoExport.segnalaCambio(context, subito = true) }
            }
            indiceCaricato = true
            schermata = Schermata.Editor(info.id, origine)
        }
    }

    /**
     * Un export Noteshelf devient une note par cahier, posée à la racine (pas dans le cahier ouvert).
     * La copertina est générée tout de suite, pour que la bibliothèque montre l'encre.
     */
    fun importaNoteshelf(uri: Uri, onFatto: (Result<List<NotaInfo>>) -> Unit) {
        scope.launch {
            val risultato = withContext(Dispatchers.IO) {
                runCatching {
                    archivio.caricaIndice()
                    val note = context.contentResolver.openInputStream(uri)!!.use {
                        NoteshelfImport.importa(archivio, it, context.cacheDir)
                    }
                    note.forEach { info ->
                        runCatching {
                            val documento = Document(archivio, info).also { it.apri() }
                            Preview.crea(archivio, documento, null)
                        }
                    }
                    note
                }
            }
            risultato.onFailure { Log.e("Tratto", "Import Noteshelf", it) }
            if (risultato.isSuccess) {
                versioneAnteprime++
                AutoExport.segnalaCambio(context, subito = true)
            }
            onFatto(risultato)
        }
    }

    /** Importa un PDF come nota annotabile. */
    fun importaPdf(uri: Uri) {
        scope.launch {
            val risultato = withContext(Dispatchers.IO) {
                runCatching {
                    archivio.caricaIndice()
                    val tmp = File(context.cacheDir, "import.pdf")
                    context.contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } }
                    val pagine = PdfBackground.dimensioni(tmp)
                    require(pagine.isNotEmpty()) { testoOra(R.string.pdf_has_no_pages) }
                    val titolo = nomeFile(uri)?.removeSuffix(".pdf")?.removeSuffix(".PDF") ?: "PDF"
                    val cartella = filtro.cartellaAperta()
                    val info = archivio.notaDaPdf(titolo, cartella, { dest -> tmp.copyTo(dest, overwrite = true) }, pagine).also { tmp.delete() }
                    filtro.capitoloAperto()?.let { archivio.spostaInCapitolo(info.id, cartella!!, it) }
                    info
                }
            }
            risultato.onSuccess {
                AutoExport.segnalaCambio(context, subito = true)
                schermata = Schermata.Editor(it.id, "nuova")
            }.onFailure { Toast.makeText(context, testoOra(R.string.cannot_open_pdf, it.message), Toast.LENGTH_LONG).show() }
        }
    }

    private fun nomeFile(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    fun inBackground(azione: () -> Unit) {
        scope.launch(Dispatchers.IO) {
            azione()
            AutoExport.segnalaCambio(context, subito = true)
        }
    }

    fun elimina(ids: Set<String>) = inBackground { archivio.elimina(ids) }

    /** Unisce le note selezionate in una sola (pagine concatenate) e la apre. */
    fun unisciNote(ids: Set<String>) {
        if (ids.size < 2) return
        scope.launch {
            val info = withContext(Dispatchers.IO) {
                archivio.caricaIndice()
                archivio.unisciNote(ids).also { AutoExport.segnalaCambio(context, subito = true) }
            }
            versioneAnteprime++
            schermata = Schermata.Editor(info.id, "nota-${info.id}")
        }
    }

    fun preferita(id: String, si: Boolean) = inBackground { archivio.preferita(id, si) }
    fun sposta(ids: Set<String>, cartella: String?) = inBackground { ids.forEach { archivio.sposta(it, cartella) } }
    fun rinomina(id: String, titolo: String) = inBackground { archivio.rinomina(id, titolo) }

    fun nuovaCartella(nome: String, colore: Int) = inBackground { archivio.nuovaCartella(nome, colore) }
    fun eliminaCartella(id: String) {
        if (filtro.cartellaAperta() == id) filtro = Filtro.Tutte
        inBackground { archivio.eliminaCartella(id) }
    }

    fun spostaInCapitolo(ids: Set<String>, cartella: String, capitolo: String?) =
        inBackground { ids.forEach { archivio.spostaInCapitolo(it, cartella, capitolo) } }
    fun modificaCartella(id: String, nome: String, colore: Int) = inBackground { archivio.modificaCartella(id, nome, colore) }
    fun nuovoCapitolo(cartella: String, nome: String, colore: Int?) = inBackground { archivio.nuovoCapitolo(cartella, nome, colore) }
    fun modificaCapitolo(cartella: String, id: String, nome: String, colore: Int?) =
        inBackground { archivio.modificaCapitolo(cartella, id, nome, colore) }
    fun eliminaCapitolo(cartella: String, id: String) {
        val f = filtro
        if (f is Filtro.InCapitolo && f.cartellaId == cartella && f.capitoloId == id) filtro = Filtro.InCartella(cartella)
        inBackground { archivio.eliminaCapitolo(cartella, id) }
    }

    fun impostaSensibilita(v: Float) {
        preferenze.impostaSensibilita(v)
        Brushes.sensibilita = v
    }
}
