package it.frumorn.tratto.googledocs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import it.frumorn.tratto.backup.DriveBackup
import it.frumorn.tratto.data.scriviAtomico
import it.frumorn.tratto.data.TestoRicco
import it.frumorn.tratto.data.UNITA_PER_PUNTO
import it.frumorn.tratto.editor.Attesa
import it.frumorn.tratto.editor.DocumentoAperto
import it.frumorn.tratto.editor.ModificaDocumento
import it.frumorn.tratto.editor.ParagrafoDocumento
import it.frumorn.tratto.editor.SpostamentoTratti
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Il documento Google di una nota aperta nell'editor, dal vivo. Finche' la nota e' aperta e l'app e'
 * in primo piano chiede a Google la revisione del documento (una richiesta leggerissima): ogni
 * secondo se il documento e' cambiato da poco (qualcuno ci sta scrivendo), ogni 3 secondi se e'
 * fermo. Quando la revisione cambia scarica il documento, lo impagina ([PagineDoc]) e lo passa a
 * [mostra], che lo disegna sotto l'inchiostro.
 *
 * L'ultima versione si tiene in files/google-docs/doc-<nota>.json: aprendo la nota si vede subito,
 * anche senza rete. Le immagini del documento si scaricano quando servono e restano in memoria.
 */
internal class DocumentoVivo(
    private val app: Context,
    private val notaId: String,
    private val scope: CoroutineScope,
    /** L'id del documento collegato adesso, o null se la nota non e' collegata. */
    private val documento: () -> String?,
    /** Oggetti posizionati da non disegnare: i disegni di Tratto (l'inchiostro c'e' gia'). */
    private val escludi: () -> Set<String>,
    /** I disegni di Tratto nel documento, per farli seguire al testo. */
    private val disegni: () -> List<DisegnoSalvato>,
    /** La nota, per sapere dove sono i tratti dei disegni. */
    private val nota: NotaDocs,
    private val mostra: (PagineDoc) -> Unit,
    /** Tratti da spostare o togliere perche' il loro disegno si e' mosso nel documento. */
    private val segui: (List<SpostamentoTratti>) -> Unit,
) : DocumentoAperto {
    private val caratteri = CaratteriDoc(app)
    private val bitmap = ConcurrentHashMap<String, Bitmap>()
    private val scaricando = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var inPausa = false

    /** Il paragrafo che si sta modificando da Tratto, se c'e'. */
    @Volatile private var inModifica: SessioneTesto? = null

    /** Per chiedere subito la revisione (dopo una modifica mandata) invece di aspettare il giro. */
    private val sveglia = Channel<Unit>(Channel.CONFLATED)
    private var lavoro: Job? = null
    private var docs: DocsRest? = null
    private var revisione: String? = null
    private var mostrato: PagineDoc? = null
    private var ultimoCambio = 0L

    /** L'ultimo documento letto, gia' impaginato (null finche' non c'e'). */
    val pagine: PagineDoc? get() = mostrato

    fun avvia() {
        lavoro = scope.launch(Dispatchers.IO) {
            var errori = 0
            var letto: String? = null
            while (isActive) {
                val id = documento()
                if (id == null) {
                    delay(ATTESA_FERMO_MS)
                    continue
                }
                if (letto != id) {
                    // Nota appena aperta o appena collegata: prima la copia salvata, se e' di questo documento.
                    letto = id
                    revisione = null
                    leggiCache(id)?.let { pubblica(it) }
                }
                if (!inPausa) {
                    try {
                        val d = docs ?: DocsRest(DriveBackup(app, conDocumenti = true).rest()).also { docs = it }
                        val rev = d.revisione(id)
                        if (rev != revisione) {
                            val json = d.completo(id)
                            salvaCache(id, json)
                            pubblica(json)
                            ultimoCambio = SystemClock.elapsedRealtime()
                        }
                        errori = 0
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        errori++
                        // Un token scaduto o l'accesso tolto: al giro dopo si riparte da capo.
                        if (e is DriveBackup.AccessoNecessario) docs = null
                        Log.w(TAG, "Documento non aggiornato ($errori)", e)
                    }
                }
                withTimeoutOrNull(attesa(errori)) { sveglia.receive() }
            }
        }
    }

    /** Ogni secondo se il documento e' cambiato da poco, altrimenti ogni 3; dopo un errore sempre piu' piano. */
    private fun attesa(errori: Int): Long = when {
        errori > 0 -> minOf(ATTESA_FERMO_MS shl minOf(errori, 4), 60_000L)
        SystemClock.elapsedRealtime() - ultimoCambio < ATTIVO_MS -> ATTESA_VIVO_MS
        else -> ATTESA_FERMO_MS
    }

    private fun pubblica(json: JSONObject) {
        val m = try {
            ModelloDoc.da(json)
        } catch (e: Exception) {
            Log.w(TAG, "Documento non leggibile", e)
            return
        }
        revisione = m.revisione
        val p = PagineDoc(m, caratteri, escludi) { bitmap[it] }
        // Il paragrafo in modifica lo mostra il campo di testo: quello del documento non si disegna.
        inModifica?.let { s -> p.nascosti = s.paragrafi(json, m) }
        mostrato = p
        mostra(p)
        scaricaImmagini(m, p)
        try {
            seguiDisegni(m, p)
        } catch (e: Exception) {
            Log.w(TAG, "Disegni non seguiti", e)
        }
    }

    /** Dove stava ogni oggetto posizionato nella revisione precedente: pagina e angolo in alto a sinistra, in unita'. */
    private var postiPrima: Map<String, Posto>? = null

    private data class Posto(val pagina: Int, val x: Float, val y: Float)

    private fun posti(m: ModelloDoc, p: PagineDoc): Map<String, Posto> {
        val out = HashMap<String, Posto>()
        for ((k, par) in m.paragrafi.withIndex()) for (id in par.posizionati) {
            val o = m.posizionati[id] ?: continue
            val pos = p.posizioni.getOrNull(k) ?: continue
            out[id] = Posto(pos.pagina, pos.x + (o.sinistra * p.u).toFloat(), pos.y + (o.alto * p.u).toFloat())
        }
        return out
    }

    /**
     * I disegni di Tratto seguono il documento: se tra la revisione di prima e questa un disegno si e'
     * mosso (testo aggiunto sopra, immagine trascinata in Docs), l'inchiostro si sposta di altrettanto;
     * se e' sparito, l'inchiostro si toglie. Si guarda solo come cambia il documento: un disegno mosso
     * sul tablet e non ancora mandato non torna indietro.
     */
    private fun seguiDisegni(m: ModelloDoc, p: PagineDoc) {
        val ora = posti(m, p)
        val prima = postiPrima
        postiPrima = ora
        if (prima == null) return
        val salvati = disegni().filter { prima.containsKey(it.oggetto) && prima[it.oggetto] != ora[it.oggetto] }
        if (salvati.isEmpty()) return
        val pagine = nota.leggi().second
        val lette = HashMap<String, PaginaLetta>()
        val mosse = ArrayList<SpostamentoTratti>()
        for (s in salvati) {
            val da = pagine.indexOfFirst { it.id == s.pagina }
            if (da < 0) continue
            val letta = lette.getOrPut(s.pagina) { nota.leggiPagina(pagine[da]) }
            val b = letta.blocchi.firstOrNull { it is BloccoPagina.Disegno && it.chiave == s.chiave } as? BloccoPagina.Disegno ?: continue
            val ids = b.tratti.mapTo(HashSet()) { it.id }
            val a = prima.getValue(s.oggetto)
            val n = ora[s.oggetto]
            if (n == null) {
                mosse += SpostamentoTratti(da, ids, -1, 0f, 0f)
                continue
            }
            // Lo scarto che c'era tra inchiostro e immagine resta (di solito zero).
            val x = n.x + (b.riquadro.sinistra - a.x)
            val y = n.y + (b.riquadro.alto - a.y)
            mosse += SpostamentoTratti(da, ids, n.pagina, x - b.riquadro.sinistra, y - b.riquadro.alto)
        }
        if (mosse.isNotEmpty()) segui(mosse)
    }

    /** Scarica le immagini che mancano; quando ce n'e' una nuova si ridisegna. */
    private fun scaricaImmagini(m: ModelloDoc, p: PagineDoc) {
        val esclusi = escludi()
        val servono = m.immagini.mapValues { it.value.uri } + m.posizionati.filterKeys { it !in esclusi }.mapValues { it.value.uri }
        for ((id, uri) in servono) {
            if (uri == null || bitmap.containsKey(id) || !scaricando.add(id)) continue
            scope.launch(Dispatchers.IO) {
                try {
                    val d = docs ?: return@launch
                    val b = d.scarica(uri)
                    BitmapFactory.decodeByteArray(b, 0, b.size)?.let { bitmap[id] = it }
                    if (mostrato === p) mostra(p)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Immagine $id non scaricata", e)
                } finally {
                    scaricando.remove(id)
                }
            }
        }
    }

    private fun fileCache() = File(app.filesDir, "google-docs/doc-$notaId.json")

    private fun leggiCache(id: String): JSONObject? = try {
        val f = fileCache()
        if (!f.isFile) null else JSONObject(f.readText()).takeIf { it.optString("documentId") == id }
    } catch (e: Exception) {
        null
    }

    private fun salvaCache(id: String, json: JSONObject) {
        runCatching { scriviAtomico(fileCache(), json.put("documentId", id).toString().toByteArray()) }
    }

    override fun modifica(p: ParagrafoDocumento): ModificaDocumento? {
        val rif = p.rif as? PagineDoc.Rif ?: return null
        val id = documento() ?: return null
        inModifica?.let { return null }
        val s = SessioneTesto(id, rif, p.contenuto)
        inModifica = s
        mostrato?.let { m ->
            m.nascosti = if (rif.indice >= 0) setOf(rif.indice) else emptySet()
            mostra(m)
        }
        scope.launch(Dispatchers.IO) { s.avvia() }
        return s
    }

    /**
     * Un paragrafo del documento modificato da Tratto. Un intervallo con nome lo segue mentre altri
     * scrivono; alla prima pausa (mezzo secondo, e comunque ogni secondo scrivendo di fila) si rilegge
     * il suo testo e si manda solo la parte diversa, con la revisione appena letta: Google unisce le
     * modifiche arrivate nel frattempo. Un invio partito arriva in fondo; ne aspetta al massimo uno.
     */
    private inner class SessioneTesto(private val id: String, private val rif: PagineDoc.Rif, iniziale: TestoRicco) : ModificaDocumento {
        private val nome = "$PREFISSO_TESTO${UUID.randomUUID()}"
        private val pronta = CompletableDeferred<Boolean>()
        private val turni = Turni(scope + Dispatchers.IO)
        @Volatile private var ultimo = iniziale

        /** Quando e' cambiato per primo il testo non ancora mandato (0 se e' tutto mandato). */
        @Volatile private var nonMandatoDa = 0L

        /** Il formato gia' nel documento: si manda solo quando cambia. */
        private var formatoInviato = TestoCondiviso.firma(iniziale)

        suspend fun avvia() {
            try {
                val d = docs ?: DocsRest(DriveBackup(app, conDocumenti = true).rest()).also { docs = it }
                val m = rif.modello
                val r = JSONArray()
                if (rif.indice < 0) {
                    // Un paragrafo nuovo in fondo: a capo prima dell'ultimo a capo del corpo.
                    val fine = m.paragrafi.lastOrNull()?.fine ?: 2
                    r.put(RichiesteDocs.inserisciTesto(fine - 1, "\n")).put(RichiesteDocs.creaIntervallo(nome, fine, fine + 1))
                } else {
                    val par = m.paragrafi[rif.indice]
                    r.put(RichiesteDocs.creaIntervallo(nome, par.inizio, par.fine))
                }
                d.aggiornaSu(id, r, m.revisione)
                pronta.complete(true)
            } catch (e: Exception) {
                Log.w(TAG, "Modifica del documento non partita", e)
                pronta.complete(false)
            }
        }

        override fun cambiato(t: TestoRicco) {
            ultimo = t
            val ora = SystemClock.elapsedRealtime()
            if (nonMandatoDa == 0L) nonMandatoDa = ora
            turni.programma(TURNO, Attesa.ritardo(nonMandatoDa, ora, ATTESA_TESTO_MS, MASSIMO_TESTO_MS)) { inviaNelTurno() }
        }

        override fun fine(t: TestoRicco) {
            ultimo = t
            turni.annulla(TURNO)
            scope.launch(Dispatchers.IO) {
                turni.esegui { inviaNelTurno() }
                chiudi()
                if (inModifica === this@SessioneTesto) inModifica = null
                // Il paragrafo torna a disegnarsi dal documento appena arriva la revisione nuova.
                revisione = null
                sveglia.trySend(Unit)
            }
        }

        /** Manda al documento la differenza tra il suo testo di adesso e [ultimo]. Solo al suo turno. */
        private suspend fun inviaNelTurno() {
            try {
                if (!pronta.await()) return
                val d = docs ?: return
                nonMandatoDa = 0L
                val json = d.testo(id)
                val pezzo = TestoCondiviso.leggi(json, nome) ?: return
                val t = ultimo
                val r = TestoCondiviso.richieste(pezzo.inizio, pezzo.testo, t.testoSemplice)
                // Il formato (grassetto, colori, stili dei paragrafi...) solo se e' cambiato sul tablet.
                val firma = TestoCondiviso.firma(t)
                if (firma != formatoInviato) {
                    val f = TestoCondiviso.formato(pezzo.inizio, t, UNITA_PER_PUNTO / (mostrato?.u ?: UNITA_PER_PUNTO).toDouble())
                    for (k in 0 until f.length()) r.put(f.getJSONObject(k))
                }
                if (r.length() == 0) return
                d.aggiornaSu(id, r, json.optString("revisionId"))
                formatoInviato = firma
                sveglia.trySend(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Testo non mandato al documento", e)
            }
        }

        private suspend fun chiudi() {
            try {
                val d = docs ?: return
                val pezzo = TestoCondiviso.leggi(d.testo(id), nome) ?: return
                d.aggiorna(id, JSONArray().put(RichiesteDocs.togliIntervallo(pezzo.id)))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Intervallo della modifica non tolto", e)
            }
        }

        /** I paragrafi coperti dall'intervallo nel documento [json] (letto in [m]). */
        fun paragrafi(json: JSONObject, m: ModelloDoc): Set<Int> {
            val r = json.optJSONObject("namedRanges")?.optJSONObject(nome)?.optJSONArray("namedRanges")?.optJSONObject(0)
                ?.optJSONArray("ranges")?.optJSONObject(0) ?: return if (rif.indice >= 0 && rif.modello === m) setOf(rif.indice) else emptySet()
            val s = r.optInt("startIndex")
            val e = r.optInt("endIndex")
            return m.paragrafi.indices.filterTo(HashSet()) { m.paragrafi[it].inizio in s until e }
        }
    }

    override fun pausa() {
        inPausa = true
    }

    override fun riprendi() {
        inPausa = false
    }

    override fun chiudi() {
        lavoro?.cancel()
        bitmap.clear()
    }

    private companion object {
        const val TAG = "DocumentoVivo"
        const val PREFISSO_TESTO = "tratto-testo-"

        /** Pausa dopo cui il testo scritto va al documento... */
        const val ATTESA_TESTO_MS = 500L

        /** ...ma scrivendo di fila al massimo dopo cosi' dal primo cambio non mandato. */
        const val MASSIMO_TESTO_MS = 1000L
        const val TURNO = "testo"
        const val ATTESA_VIVO_MS = 1000L
        const val ATTESA_FERMO_MS = 3000L

        /** Per quanto, dopo un cambio, si continua a chiedere ogni secondo. */
        const val ATTIVO_MS = 30_000L
    }
}
