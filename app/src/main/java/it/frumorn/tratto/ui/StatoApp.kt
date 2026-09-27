package it.frumorn.tratto.ui

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import it.frumorn.tratto.data.Archivio
import it.frumorn.tratto.data.NotaInfo
import it.frumorn.tratto.data.Preferenze
import it.frumorn.tratto.data.Sfondo
import it.frumorn.tratto.data.Tratto
import it.frumorn.tratto.editor.Anteprima
import it.frumorn.tratto.editor.Documento
import it.frumorn.tratto.ink.Pennelli
import it.frumorn.tratto.pdf.PdfSfondo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Schermata {
    data object Libreria : Schermata
    /** [origine] e' la chiave dell'elemento da cui parte l'animazione di apertura. */
    data class Editor(val id: String, val origine: String) : Schermata
    data object Impostazioni : Schermata
}

sealed interface Filtro {
    data object Tutte : Filtro
    data object Preferite : Filtro
    data class InCartella(val id: String) : Filtro
}

/** Stato di tutta l'app: schermata corrente, filtri della libreria e operazioni sulle note. */
class StatoApp(private val context: Context, val archivio: Archivio, val preferenze: Preferenze) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var schermata by mutableStateOf<Schermata>(Schermata.Libreria)
    var filtro by mutableStateOf<Filtro>(Filtro.Tutte)
    var ricerca by mutableStateOf("")
    var indiceCaricato by mutableStateOf(false)
        private set
    /** Cresce quando cambia una copertina: la libreria ricarica le immagini. */
    var versioneAnteprime by mutableIntStateOf(0)

    /** Tratti copiati con il lazo, incollabili in qualsiasi nota. */
    var appunti: List<Tratto> = emptyList()

    init {
        Pennelli.sensibilita = preferenze.sensibilita
    }

    fun caricaIndice() {
        scope.launch {
            withContext(Dispatchers.IO) { archivio.caricaIndice() }
            indiceCaricato = true
            rigeneraAnteprimeMancanti()
        }
    }

    /** Crea in sottofondo le copertine che mancano (note importate o ripristinate da un backup). */
    private fun rigeneraAnteprimeMancanti() {
        scope.launch {
            var fatte = 0
            withContext(Dispatchers.IO) {
                delay(1500) // dopo l'avvio, per non rubare tempo al primo fotogramma
                for (n in archivio.note.value) {
                    if (archivio.fileAnteprima(n.id).exists()) continue
                    runCatching {
                        val d = Documento(archivio, n).also { it.apri() }
                        val f = archivio.filePdf(n.id)
                        val pdf = if (f.exists()) PdfSfondo(f) {} else null
                        pdf.use { sfondo ->
                            // Il PDF si disegna in un thread a parte: aspettiamo la prima pagina.
                            if (sfondo != null && d.pagine.firstOrNull()?.pagina?.pdfPagina == 0) {
                                repeat(20) { if (sfondo.immagine(0, Anteprima.LARGHEZZA_PX) == null) Thread.sleep(100) }
                            }
                            Anteprima.crea(archivio, d, sfondo)
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
            val cartella = (filtro as? Filtro.InCartella)?.id
            val info = withContext(Dispatchers.IO) { archivio.caricaIndice(); archivio.nuovaNota(sfondo, cartella) }
            indiceCaricato = true
            schermata = Schermata.Editor(info.id, origine)
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
                    val pagine = PdfSfondo.dimensioni(tmp)
                    require(pagine.isNotEmpty()) { "Il PDF non ha pagine" }
                    val titolo = nomeFile(uri)?.removeSuffix(".pdf")?.removeSuffix(".PDF") ?: "PDF"
                    val cartella = (filtro as? Filtro.InCartella)?.id
                    archivio.notaDaPdf(titolo, cartella, { dest -> tmp.copyTo(dest, overwrite = true) }, pagine).also { tmp.delete() }
                }
            }
            risultato.onSuccess { schermata = Schermata.Editor(it.id, "nuova") }
                .onFailure { Toast.makeText(context, "Non riesco ad aprire il PDF: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    private fun nomeFile(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    fun inBackground(azione: () -> Unit) {
        scope.launch(Dispatchers.IO) { azione() }
    }

    fun elimina(ids: Set<String>) = inBackground { archivio.elimina(ids) }
    fun preferita(id: String, si: Boolean) = inBackground { archivio.preferita(id, si) }
    fun sposta(ids: Set<String>, cartella: String?) = inBackground { ids.forEach { archivio.sposta(it, cartella) } }
    fun rinomina(id: String, titolo: String) = inBackground { archivio.rinomina(id, titolo) }

    fun nuovaCartella(nome: String, colore: Int) = inBackground { archivio.nuovaCartella(nome, colore) }
    fun eliminaCartella(id: String) {
        if ((filtro as? Filtro.InCartella)?.id == id) filtro = Filtro.Tutte
        inBackground { archivio.eliminaCartella(id) }
    }

    fun impostaSensibilita(v: Float) {
        preferenze.impostaSensibilita(v)
        Pennelli.sensibilita = v
    }
}
