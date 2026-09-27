package it.frumorn.tratto.googledocs

import android.content.Context
import it.frumorn.tratto.TrattoApp
import it.frumorn.tratto.data.Testo
import it.frumorn.tratto.backup.DriveBackup
import it.frumorn.tratto.backup.ErroreDrive
import it.frumorn.tratto.backup.temporaneo
import it.frumorn.tratto.data.scriviAtomico
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** A che punto e' l'aggiornamento del documento Google di una nota. */
enum class FaseDocs {
    /** Collegata; da quando l'app e' aperta non e' ancora cambiato niente. */
    COLLEGATA,
    /** Ci sono modifiche che partiranno tra pochi secondi. */
    IN_ATTESA,
    IN_CORSO,
    AGGIORNATA,
    /** Non e' andata per un problema passeggero (rete, Google occupato): si riprova da soli. */
    RIPROVA,
    ERRORE,
}

/** Stato del collegamento di una nota, per la UI. [messaggio] spiega RIPROVA ed ERRORE. */
data class StatoDocs(
    val documento: String,
    val fase: FaseDocs = FaseDocs.COLLEGATA,
    val messaggio: String? = null,
    /** Google vuole di nuovo il consenso: va chiesto dalla UI. */
    val serveAccesso: Boolean = false,
)

/**
 * Specchio di una nota in Google Docs (solo nella versione completa): chi apre il documento su un
 * computer lo vede aggiornarsi mentre si scrive sul tablet. Va in una sola direzione, da Tratto a
 * Google Docs: le modifiche fatte nel documento si perdono al prossimo aggiornamento.
 *
 * - [collega] crea il documento nella cartella "Tratto" di Drive (stessa autorizzazione del backup,
 *   scope drive.file) e ricorda il collegamento in files/google-docs/collegamenti.json.
 * - [modificata], chiamata dall'editor dopo ogni salvataggio, aggiorna il documento 4 secondi dopo
 *   l'ultima modifica.
 * - [finisciInBackground], alla chiusura dell'editor o quando l'app va in secondo piano, affida gli
 *   aggiornamenti mancanti a [DocsWorker], che aspetta la rete e finisce anche ad app chiusa.
 *
 * Come si aggiorna il documento: vedi [SincroniaDocs] e docs/google-docs.md.
 */
object GoogleDocs {
    /** Il collegamento a Google Docs c'e' in questa versione dell'app: la UI mostra le sue voci. */
    const val DISPONIBILE = true

    /** Attesa dopo l'ultima modifica: chi scrive fa una pausa, il documento si aggiorna. */
    private const val RITARDO_MS = 4000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Un aggiornamento alla volta, per tutte le note: i file temporanei su Drive sono tutti di chi lavora. */
    private val mutex = Mutex()
    private val lock = Any()

    // Protetti da lock (dati si legge anche senza, per sapere se e' gia' caricato).
    @Volatile private var dati: CollegamentiDocs? = null
    private val attese = HashMap<String, Job>()
    private val generazioni = HashMap<String, Int>()
    private val sporche = HashSet<String>()

    private val _stati = MutableStateFlow<Map<String, StatoDocs>>(emptyMap())

    /** Alla prima sincronizzazione (e dopo un errore) si tolgono le immagini temporanee rimaste. */
    @Volatile private var avanziDaPulire = true

    /** Indirizzo del documento da aprire nel browser o nell'app di Google Docs. */
    fun indirizzo(documento: String) = "https://docs.google.com/document/d/$documento/edit"

    /** Stato di ogni nota collegata (le altre non ci sono). */
    fun stati(context: Context): StateFlow<Map<String, StatoDocs>> {
        if (dati == null) {
            val app = context.applicationContext
            scope.launch(Dispatchers.IO) { carica(app) }
        }
        return _stati
    }

    /** La nota [notaId] e' cambiata (salvata o rinominata): se e' collegata, il documento si aggiorna tra poco. */
    fun modificata(context: Context, notaId: String) {
        val app = context.applicationContext
        scope.launch(Dispatchers.IO) {
            carica(app)
            if (!segnaModificata(notaId)) return@launch
            programma(app, notaId, RITARDO_MS)
        }
    }

    /**
     * L'editor si chiude o l'app va in secondo piano: se restano modifiche da mandare (anche le
     * ultime, [cambiata], non ancora annunciate), le manda [DocsWorker] appena c'e' rete.
     */
    fun finisciInBackground(context: Context, notaId: String, cambiata: Boolean) {
        val app = context.applicationContext
        scope.launch(Dispatchers.IO) {
            carica(app)
            if (cambiata) segnaModificata(notaId)
            val daMandare = synchronized(lock) {
                val s = notaId in sporche
                if (s) attese.remove(notaId)?.cancel()
                s
            }
            if (daMandare) DocsWorker.accoda(app, notaId)
        }
    }

    /** Aggiorna subito il documento (dopo un errore, o appena ridato il consenso). */
    fun sincronizzaOra(context: Context, notaId: String) {
        val app = context.applicationContext
        scope.launch(Dispatchers.IO) {
            carica(app)
            if (collegamento(notaId) != null) programma(app, notaId, 0)
        }
    }

    /**
     * Crea il documento Google della nota e la collega. Serve l'accesso a Drive gia' concesso (vedi
     * rememberAccessoDrive): senza lancia [DriveBackup.AccessoNecessario]. Restituisce l'id del documento.
     */
    suspend fun collega(context: Context, notaId: String): String = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        carica(app)
        collegamento(notaId)?.let { return@withContext it.documento }
        val archivio = TrattoApp.archivio(app)
        archivio.caricaIndice()
        val info = archivio.note.value.find { it.id == notaId } ?: throw IOException("La nota non esiste più.")
        val documento = mutex.withLock {
            val servizio = servizio(app)
            val id = servizio.creaDocumento(info)
            modificaDati(app) {
                it.copy(note = it.note + (notaId to CollegamentoDocs(id)), cartella = servizio.cartelle.idTratto, immagini = servizio.cartelle.idImmagini)
            }
            id
        }
        _stati.update { it + (notaId to StatoDocs(documento)) }
        segnaModificata(notaId)
        programma(app, notaId, 0)
        documento
    }

    /** Toglie il collegamento. Il documento resta su Google Drive, fermo all'ultimo aggiornamento. */
    suspend fun scollega(context: Context, notaId: String) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        carica(app)
        synchronized(lock) {
            attese.remove(notaId)?.cancel()
            sporche -= notaId
        }
        DocsWorker.annulla(app, notaId)
        modificaDati(app) { it.copy(note = it.note - notaId) }
        _stati.update { it - notaId }
    }

    /** Per [DocsWorker]: aggiorna il documento e lancia l'errore, se c'e', per decidere se riprovare. */
    internal suspend fun eseguiInBackground(context: Context, notaId: String) = esegui(context.applicationContext, notaId)

    // ---------------------------------------------------------------- aggiornamento

    private fun programma(app: Context, notaId: String, ritardo: Long) {
        synchronized(lock) {
            attese.remove(notaId)?.cancel()
            attese[notaId] = scope.launch {
                delay(ritardo)
                // Una volta partito non si interrompe: una nuova modifica aspetta la fine e riparte.
                withContext(NonCancellable) {
                    try {
                        esegui(app, notaId)
                    } catch (e: Exception) {
                        // Senza rete ci pensa il worker, che aspetta la connessione anche ad app chiusa.
                        if (e.temporaneo()) DocsWorker.accoda(app, notaId)
                    }
                }
            }
        }
    }

    private suspend fun esegui(app: Context, notaId: String) = mutex.withLock {
        carica(app)
        val link = collegamento(notaId) ?: return@withLock
        val generazione = synchronized(lock) { generazioni[notaId] ?: 0 }
        imposta(notaId) { it.copy(fase = FaseDocs.IN_CORSO) }
        try {
            val servizio = servizio(app)
            val cartelle = servizio.cartelle
            val archivio = TrattoApp.archivio(app)
            // Il testo delle caselle di ogni pagina va sotto la sua immagine, come testo vero.
            val nota = NotaArchivio(archivio, notaId) { id, pagina ->
                archivio.oggetti(id, pagina.id).filterIsInstance<Testo>()
                    .flatMap { it.contenuto.testoSemplice.split('\n') }
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            }
            val sincronia = SincroniaDocs(servizio, nota, File(app.cacheDir, "google-docs"))
            try {
                if (avanziDaPulire) {
                    servizio.pulisciAvanzi()
                    avanziDaPulire = false
                }
                val finale = sincronia.sincronizza(link) { salvaCollegamento(app, notaId, it, cartelle) }
                salvaCollegamento(app, notaId, finale, cartelle)
            } finally {
                if (sincronia.avanzi) avanziDaPulire = true
            }
            val pulita = synchronized(lock) {
                val p = (generazioni[notaId] ?: 0) == generazione
                if (p) sporche -= notaId
                p
            }
            imposta(notaId) { it.copy(fase = if (pulita) FaseDocs.AGGIORNATA else FaseDocs.IN_ATTESA, messaggio = null, serveAccesso = false) }
        } catch (e: NotaSparita) {
            // Nota cancellata: il documento resta com'era, il collegamento non serve piu'.
            modificaDati(app) { it.copy(note = it.note - notaId) }
            _stati.update { it - notaId }
        } catch (e: CancellationException) {
            imposta(notaId) { it.copy(fase = FaseDocs.IN_ATTESA) }
            throw e
        } catch (e: DriveBackup.AccessoNecessario) {
            imposta(notaId) { it.copy(fase = FaseDocs.ERRORE, messaggio = "Serve di nuovo l'accesso a Google: tocca per ricollegare.", serveAccesso = true) }
            throw e
        } catch (e: Exception) {
            avanziDaPulire = true
            val fase = if (e.temporaneo()) FaseDocs.RIPROVA else FaseDocs.ERRORE
            imposta(notaId) { it.copy(fase = fase, messaggio = messaggio(e), serveAccesso = false) }
            throw e
        }
    }

    /** Drive e Docs con il token dell'account collegato; lancia [DriveBackup.AccessoNecessario] se serve il consenso. */
    private suspend fun servizio(app: Context): ServizioGoogle {
        val rest = DriveBackup(app).rest()
        val d = synchronized(lock) { dati!! }
        return ServizioGoogle(rest, CartelleDocs(rest, d.cartella, d.immagini))
    }

    private fun messaggio(e: Exception): String = when {
        e is UnknownHostException || e is java.net.ConnectException ->
            "Nessuna connessione a Internet: il documento si aggiorna quando torna la rete."
        e is SocketTimeoutException -> "Google non risponde: si riproverà."
        e is ErroreDrive && e.codice == 404 ->
            "Il documento Google collegato non c'è più. Scollega la nota e collegala di nuovo."
        e is JSONException -> "Risposta non valida da Google."
        else -> e.message ?: "Aggiornamento di Google Docs non riuscito."
    }

    // ---------------------------------------------------------------- stato

    /** Segna una modifica da mandare; falso se la nota non e' collegata. */
    private fun segnaModificata(notaId: String): Boolean {
        if (collegamento(notaId) == null) return false
        synchronized(lock) {
            generazioni[notaId] = (generazioni[notaId] ?: 0) + 1
            sporche += notaId
        }
        // Durante un aggiornamento resta "in corso": alla fine diventa "in attesa" da sola.
        imposta(notaId) { if (it.fase == FaseDocs.IN_CORSO) it else it.copy(fase = FaseDocs.IN_ATTESA) }
        return true
    }

    private fun imposta(notaId: String, f: (StatoDocs) -> StatoDocs) {
        _stati.update { m -> m[notaId]?.let { m + (notaId to f(it)) } ?: m }
    }

    private fun collegamento(notaId: String): CollegamentoDocs? = synchronized(lock) { dati?.note?.get(notaId) }

    /** Salva l'avanzamento, ma solo se nel frattempo la nota non e' stata scollegata o ricollegata. */
    private fun salvaCollegamento(app: Context, notaId: String, c: CollegamentoDocs, cartelle: CartelleDocs) {
        modificaDati(app) { d ->
            if (d.note[notaId]?.documento != c.documento) d
            else d.copy(note = d.note + (notaId to c), cartella = cartelle.idTratto, immagini = cartelle.idImmagini)
        }
    }

    private fun file(app: Context) = File(app.filesDir, "google-docs/collegamenti.json")

    /** Legge i collegamenti da disco la prima volta. Fa I/O. */
    private fun carica(app: Context) {
        synchronized(lock) {
            if (dati != null) return
            val f = file(app)
            val d = CollegamentiDocs.leggi(if (f.isFile) f.readText() else null)
            dati = d
            _stati.value = d.note.mapValues { StatoDocs(it.value.documento) }
        }
    }

    private fun modificaDati(app: Context, f: (CollegamentiDocs) -> CollegamentiDocs) {
        synchronized(lock) {
            val prima = dati ?: CollegamentiDocs()
            val dopo = f(prima)
            if (dopo == prima) return
            scriviAtomico(file(app), dopo.json().toString().toByteArray())
            dati = dopo
        }
    }
}
