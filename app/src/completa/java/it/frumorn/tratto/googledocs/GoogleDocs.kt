package it.frumorn.tratto.googledocs

import android.content.Context
import android.os.SystemClock
import it.frumorn.tratto.BuildConfig
import it.frumorn.tratto.TrattoApp
import it.frumorn.tratto.backup.DriveBackup
import it.frumorn.tratto.backup.DriveRest
import it.frumorn.tratto.backup.ErroreDrive
import it.frumorn.tratto.backup.ImpostazioniBackup
import it.frumorn.tratto.backup.temporaneo
import it.frumorn.tratto.data.scriviAtomico
import it.frumorn.tratto.editor.DocumentoAperto
import it.frumorn.tratto.editor.LivelloDocumento
import it.frumorn.tratto.editor.SpostamentoTratti
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
 * computer lo vede aggiornarsi mentre si scrive sul tablet. Tratto scrive nel documento solo i suoi
 * blocchi (righe e disegni come immagini, caselle di testo come testo vero): quello che si aggiunge
 * direttamente in Google Docs resta.
 *
 * - [collega] crea il documento nella cartella "Tratto" di Drive (stessa autorizzazione del backup,
 *   scope drive.file) e ricorda il collegamento in files/google-docs/collegamenti.json.
 * - [modificata], chiamata dall'editor dopo ogni salvataggio, aggiorna il documento un secondo dopo
 *   l'ultima modifica.
 * - [finisciInBackground], alla chiusura dell'editor o quando l'app va in secondo piano, affida gli
 *   aggiornamenti mancanti a [DocsWorker], che aspetta la rete e finisce anche ad app chiusa.
 *
 * Come si aggiorna il documento: vedi [SincroniaDocs] e docs/google-docs.md.
 */
object GoogleDocs {
    /** Il collegamento a Google Docs c'e' in questa versione dell'app: la UI mostra le sue voci. */
    const val DISPONIBILE = true

    /**
     * La nota mostra il documento (testo di Google Docs sotto l'inchiostro) e ci mette i suoi disegni
     * davanti al testo con lo script di Google ([SincroniaDisegni]), invece di copiarci i suoi blocchi.
     */
    private const val MODO_DOCUMENTO = true

    /**
     * Attesa dopo un salvataggio prima di aggiornare il documento. Nessuna: l'editor aspetta gia' la
     * pausa (o al massimo due secondi scrivendo di fila) e un aggiornamento in coda prende il posto
     * dell'altro (vedi [Turni]).
     */
    private const val RITARDO_MS = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Un aggiornamento alla volta, per tutte le note, e per ogni nota al massimo uno in attesa. */
    private val turni = Turni(scope)
    private val lock = Any()

    // Protetti da lock (dati si legge anche senza, per sapere se e' gia' caricato).
    @Volatile private var dati: CollegamentiDocs? = null
    private val generazioni = HashMap<String, Int>()
    private val sporche = HashSet<String>()

    private val _stati = MutableStateFlow<Map<String, StatoDocs>>(emptyMap())

    /** Alla prima sincronizzazione (e dopo un errore) si tolgono le immagini temporanee rimaste. */
    @Volatile private var avanziDaPulire = true

    /**
     * La nota [notaId] e' aperta nell'editor: se e' (o diventa) collegata, il suo documento si
     * disegna sotto l'inchiostro e si aggiorna dal vivo (vedi [DocumentoVivo]). [mostra] riceve il
     * documento impaginato, da un thread qualsiasi.
     */
    fun apri(
        context: Context,
        notaId: String,
        scope: CoroutineScope,
        mostra: (LivelloDocumento) -> Unit,
        segui: (List<SpostamentoTratti>) -> Unit,
    ): DocumentoAperto {
        val app = context.applicationContext
        scope.launch(Dispatchers.IO) { carica(app) }
        return DocumentoVivo(
            app, notaId, scope,
            documento = { collegamento(notaId)?.documento },
            escludi = { collegamento(notaId)?.disegni?.mapTo(HashSet()) { it.oggetto }.orEmpty() },
            disegni = { collegamento(notaId)?.disegni.orEmpty() },
            nota = NotaArchivio(TrattoApp.archivio(app), notaId),
            mostra = mostra,
            segui = segui,
        ).also { it.avvia() }
    }

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

    /**
     * La nota [notaId] e' cambiata (salvata o rinominata): se e' collegata, il documento si aggiorna
     * dopo [ritardo] (il titolo si scrive una lettera alla volta: si aspetta l'ultima).
     */
    fun modificata(context: Context, notaId: String, ritardo: Long = RITARDO_MS) {
        val app = context.applicationContext
        scope.launch(Dispatchers.IO) {
            carica(app)
            if (!segnaModificata(notaId)) return@launch
            programma(app, notaId, ritardo)
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
                if (s) turni.annulla(notaId)
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
        val documento = turni.esegui {
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
            turni.annulla(notaId)
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
        turni.programma(notaId, ritardo) {
            try {
                eseguiNelTurno(app, notaId)
            } catch (e: Exception) {
                // Senza rete ci pensa il worker, che aspetta la connessione anche ad app chiusa.
                if (e.temporaneo()) DocsWorker.accoda(app, notaId)
            }
        }
    }

    private suspend fun esegui(app: Context, notaId: String) = turni.esegui { eseguiNelTurno(app, notaId) }

    /** Aggiorna il documento della nota; da chiamare solo al proprio turno (vedi [Turni]). */
    private suspend fun eseguiNelTurno(app: Context, notaId: String) {
        carica(app)
        val link = collegamento(notaId) ?: return
        val generazione = synchronized(lock) { generazioni[notaId] ?: 0 }
        imposta(notaId) { it.copy(fase = FaseDocs.IN_CORSO) }
        val inizio = SystemClock.elapsedRealtime()
        try {
            val servizio = servizio(app)
            val cartelle = servizio.cartelle
            val archivio = TrattoApp.archivio(app)
            val nota = NotaArchivio(archivio, notaId)
            if (MODO_DOCUMENTO) {
                if (BuildConfig.SCRIPT_DOCS.isEmpty()) throw IOException("Manca lo script di Google Docs: i disegni restano su Tratto.")
                val caratteri = caratteri(app)
                val disegni = SincroniaDisegni(
                    DocsRest(servizio.rest), ScriptDocs(servizio.rest, BuildConfig.SCRIPT_DOCS), nota,
                    impagina = { m ->
                        val p = PagineDoc(m, caratteri, { emptySet() }) { null }
                        PostiParagrafi(p.posizioni.map { it.pagina }, p.posizioni.map { it.y }, p.u.toDouble(), m.margineSinistro)
                    },
                    cartella = File(app.cacheDir, "google-docs"),
                )
                try {
                    val finale = disegni.sincronizza(link) { salvaCollegamento(app, notaId, it, cartelle) }
                    salvaCollegamento(app, notaId, finale, cartelle)
                } finally {
                    // Per capire dove va il tempo: adb logcat -s GoogleDocs
                    android.util.Log.i(
                        "GoogleDocs",
                        "Disegni di $notaId in ${SystemClock.elapsedRealtime() - inizio} ms: " +
                            disegni.tempi.entries.joinToString { "${it.key} ${it.value}" },
                    )
                }
            } else {
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
            rest = null
            imposta(notaId) { it.copy(fase = FaseDocs.ERRORE, messaggio = "Serve di nuovo l'accesso a Google: tocca per ricollegare.", serveAccesso = true) }
            throw e
        } catch (e: Exception) {
            android.util.Log.w("GoogleDocs", "Aggiornamento di $notaId non riuscito", e)
            avanziDaPulire = true
            val fase = if (e.temporaneo()) FaseDocs.RIPROVA else FaseDocs.ERRORE
            imposta(notaId) { it.copy(fase = fase, messaggio = messaggio(e), serveAccesso = false) }
            throw e
        }
    }

    /** Drive e Docs con il token dell'account collegato; lancia [DriveBackup.AccessoNecessario] se serve il consenso. */
    private suspend fun servizio(app: Context): ServizioGoogle {
        // Il client si tiene tra un aggiornamento e l'altro: chiedere il token a Play services ogni
        // volta costa tempo, e quando scade il client lo rinnova da solo.
        // Se nel frattempo e' cambiato l'account collegato, il client vecchio non serve piu'.
        val account = ImpostazioniBackup.stato(app).value.account
        if (account != restAccount) rest = null
        val r = rest ?: DriveBackup(app, conDocumenti = true).rest().also { rest = it; restAccount = account }
        val d = synchronized(lock) { dati!! }
        return ServizioGoogle(r, CartelleDocs(r, d.cartella, d.immagini))
    }

    /** Il client di Google dell'ultimo aggiornamento; si dimentica quando serve di nuovo il consenso. */
    @Volatile private var rest: DriveRest? = null
    @Volatile private var restAccount: String? = null

    /** I caratteri per impaginare il documento: si caricano una volta sola. */
    @Volatile private var caratteriDoc: CaratteriDoc? = null

    private fun caratteri(app: Context): CaratteriDoc = caratteriDoc ?: CaratteriDoc(app).also { caratteriDoc = it }

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
