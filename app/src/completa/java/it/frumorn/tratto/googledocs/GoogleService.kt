package it.frumorn.tratto.googledocs

import it.frumorn.tratto.backup.DriveRest
import it.frumorn.tratto.backup.ErroreDrive
import it.frumorn.tratto.data.NotaInfo
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/** Le chiamate a Google Docs che servono a [DrawingSync] (nei test, un Google finto). */
internal interface ApiDocs {
    /** Il documento intero. */
    suspend fun completo(id: String): JSONObject

    suspend fun aggiorna(id: String, richieste: JSONArray, revisione: String? = null): JSONObject

    /** Come [aggiorna], con gli indici della revisione [base]. */
    suspend fun aggiornaSu(id: String, richieste: JSONArray, base: String): JSONObject

    /** Cambia il nome del documento (su Drive). */
    suspend fun rinomina(id: String, nome: String)
}

/** Lo script che mette i disegni davanti al testo (nei test, un Google finto). */
internal interface ScriptDisegni {
    /**
     * Mette [disegni] nel documento e, nella stessa esecuzione, toglie [togli]. Lo script vecchio
     * (non ancora aggiornato su Google) non sa togliere: allora [ScriptDocs.Esito.tolti] e' null.
     */
    suspend fun aggiungiDisegni(documento: String, disegni: List<ScriptDocs.Disegno>, togli: List<ScriptDocs.Togli>): ScriptDocs.Esito
}

/** Le due chiamate dell'API di Google Docs usate da Tratto, con il client (token e tentativi) di Drive. */
internal class DocsRest(private val rest: DriveRest) : ApiDocs {
    suspend fun documento(id: String): JSONObject =
        rest.richiesta("GET", "$API/documents/$id?fields=${URLEncoder.encode(DocLetto.CAMPI, "UTF-8")}")

    /** Solo la revisione: costa poco, si chiede spesso per sapere se il documento e' cambiato. */
    suspend fun revisione(id: String): String =
        rest.richiesta("GET", "$API/documents/$id?fields=revisionId").optString("revisionId")

    /**
     * Un'immagine del documento (contentUri, vale una mezz'ora ed e' gia' legato all'account che ha
     * letto il documento). Se Google la rifiuta senza token, si riprova col token.
     */
    suspend fun scarica(url: String): ByteArray = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        try {
            if (c.responseCode == 200) return@withContext c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
        rest.scaricaIndirizzo(url)
    }

    /** Revisione, intervalli con nome e testo del corpo: quanto basta per modificare un paragrafo. */
    suspend fun testo(id: String): JSONObject = rest.richiesta(
        "GET",
        "$API/documents/$id?fields=" + java.net.URLEncoder.encode(
            "revisionId,namedRanges,body(content(startIndex,endIndex,paragraph(elements(startIndex,textRun(content),inlineObjectElement(inlineObjectId))),table))",
            "UTF-8",
        ),
    )

    /** Il documento intero, per disegnarlo. */
    override suspend fun completo(id: String): JSONObject = rest.richiesta("GET", "$API/documents/$id")

    override suspend fun aggiorna(id: String, richieste: JSONArray, revisione: String?): JSONObject =
        rest.richiesta("POST", "$API/documents/$id:batchUpdate", DocsRequests.corpo(richieste, revisione))

    /**
     * Come [aggiorna], ma gli indici sono quelli della revisione [base]: se nel frattempo qualcuno ha
     * scritto nel documento, Google sposta le richieste sopra le sue modifiche invece di rifiutarle.
     */
    override suspend fun aggiornaSu(id: String, richieste: JSONArray, base: String): JSONObject =
        rest.richiesta("POST", "$API/documents/$id:batchUpdate", DocsRequests.corpoUnito(richieste, base))

    override suspend fun rinomina(id: String, nome: String) {
        rest.aggiornaMetadati(id, JSONObject().put("name", nome))
    }

    private companion object {
        const val API = "https://docs.googleapis.com/v1"
    }
}

/**
 * Lo script di Google che mette i disegni davanti al testo (docs/apps-script/Codice.gs), chiamato con
 * l'API Apps Script. [id] e' quello del suo deployment "eseguibile API".
 */
internal class ScriptDocs(private val rest: DriveRest, private val id: String) : ScriptDisegni {
    /** Un disegno da mettere: nome dell'intervallo sul paragrafo, PNG, misure e posizione in punti. */
    class Disegno(val ancora: String, val png: ByteArray, val larghezza: Double, val altezza: Double, val sinistra: Double, val alto: Double)

    /** Un disegno da togliere: id dell'immagine e, se si sa, nome di un intervallo sul paragrafo a cui e' ancorata. */
    class Togli(val oggetto: String, val ancora: String?)

    /**
     * [ids]: le immagini messe, nello stesso ordine (null dove il paragrafo non c'era piu').
     * [tolti]: le immagini tolte dallo script; null se lo script e' quello vecchio, che non toglie.
     */
    class Esito(val ids: List<String?>, val tolti: Set<String>?)

    override suspend fun aggiungiDisegni(documento: String, disegni: List<Disegno>, togli: List<Togli>): Esito {
        val lista = JSONArray()
        for (d in disegni) {
            lista.put(
                // Apps Script vuole larghezza e altezza in pixel (96 per pollice), la posizione in punti.
                JSONObject().put("ancora", d.ancora).put("png", android.util.Base64.encodeToString(d.png, android.util.Base64.NO_WRAP))
                    .put("larghezza", d.larghezza * PX_PER_PT).put("altezza", d.altezza * PX_PER_PT).put("sinistra", d.sinistra).put("alto", d.alto),
            )
        }
        val via = JSONArray()
        for (t in togli) via.put(JSONObject().put("id", t.oggetto).put("ancora", t.ancora ?: JSONObject.NULL))
        val corpo = JSONObject().put("function", "aggiungiDisegni").put("parameters", JSONArray().put(documento).put(lista).put(via)).put("devMode", false)
        val r = rest.richiesta("POST", "https://script.googleapis.com/v1/scripts/$id:run", corpo, idempotente = false)
        r.optJSONObject("error")?.let { e ->
            val dettaglio = e.optJSONArray("details")?.optJSONObject(0)?.optString("errorMessage")
            throw java.io.IOException("Le script Google Docs a échoué : ${dettaglio ?: e.optString("message")}")
        }
        // Lo script vecchio restituisce solo la lista degli id; quello nuovo { ids, tolti }.
        val risposta = r.optJSONObject("response")
        val nuovo = risposta?.optJSONObject("result")
        val ids = nuovo?.optJSONArray("ids") ?: risposta?.optJSONArray("result") ?: JSONArray()
        val tolti = nuovo?.optJSONArray("tolti")?.let { a -> (0 until a.length()).mapNotNullTo(HashSet()) { a.optString(it).takeIf { s -> s.isNotEmpty() } } }
        return Esito((0 until disegni.size).map { k -> ids.optString(k).takeIf { !ids.isNull(k) && it.isNotEmpty() } }, tolti)
    }

    private companion object {
        const val PX_PER_PT = 4.0 / 3.0
    }
}

/**
 * Le cartelle su Drive: "Tratto", la stessa del backup (la si riconosce dalle appProperties), e
 * dentro ".immagini-docs" per le immagini temporanee. Gli id trovati o creati si ricordano in
 * [DocsLinks].
 */
internal class CartelleDocs(private val rest: DriveRest, tratto: String?, immagini: String?) {
    var idTratto: String? = tratto
        private set
    var idImmagini: String? = immagini
        private set

    suspend fun tratto(): String {
        idTratto?.let { if (esiste(it)) return it }
        val q = "mimeType = '${DriveRest.MIME_CARTELLA}' and appProperties has { key='tratto' and value='cartella' } and trashed = false"
        val id = rest.cerca(q).firstOrNull()?.id ?: rest.creaCartella(NOME_TRATTO, JSONObject().put("tratto", "cartella"))
        idTratto = id
        return id
    }

    /** La cartella delle immagini; con [verifica] si controlla che non sia stata cancellata o cestinata. */
    suspend fun immagini(verifica: Boolean = false): String {
        idImmagini?.let { if (!verifica || esiste(it)) return it }
        val q = "mimeType = '${DriveRest.MIME_CARTELLA}' and appProperties has { key='tratto' and value='$PROPRIETA_IMMAGINI' } and trashed = false"
        val id = rest.cerca(q).firstOrNull()?.id
            ?: rest.creaCartella(NOME_IMMAGINI, JSONObject().put("tratto", PROPRIETA_IMMAGINI), genitore = tratto())
        idImmagini = id
        return id
    }

    fun dimenticaImmagini() {
        idImmagini = null
    }

    private suspend fun esiste(id: String): Boolean = rest.file(id, "id,trashed")?.optBoolean("trashed") == false

    companion object {
        const val NOME_TRATTO = "Tratto"
        const val NOME_IMMAGINI = ".immagini-docs"
        const val PROPRIETA_IMMAGINI = "docs-immagini"
    }
}

/**
 * [ServizioDocs] vero: Docs per il documento, Drive per le immagini temporanee.
 *
 * L'API di Docs inserisce le immagini solo da un indirizzo pubblico. [pubblica] carica il PNG nella
 * cartella ".immagini-docs" e lo condivide con "chiunque abbia il link"; dopo il batchUpdate (Google
 * ha gia' scaricato l'immagine e ne tiene una copia) [ritira] toglie la condivisione e cancella il file.
 */
internal class GoogleService(val rest: DriveRest, val cartelle: CartelleDocs) : ServizioDocs {
    private val docs = DocsRest(rest)

    override suspend fun documento(id: String): JSONObject = docs.documento(id)

    override suspend fun aggiorna(id: String, richieste: JSONArray, revisione: String?): JSONObject =
        docs.aggiorna(id, richieste, revisione)

    override suspend fun rinomina(id: String, nome: String) {
        rest.aggiornaMetadati(id, JSONObject().put("name", nome))
    }

    override suspend fun pubblica(file: File, nome: String): Pubblicata {
        val meta = JSONObject().put("name", nome).put("mimeType", MIME_PNG).put("appProperties", JSONObject().put("tratto", "docs-immagine"))
        val id = try {
            rest.carica(null, cartelle.immagini(), meta, file, MIME_PNG)
        } catch (e: ErroreDrive) {
            if (e.codice != 404) throw e
            // La cartella temporanea non c'e' piu': la si ricrea.
            cartelle.dimenticaImmagini()
            rest.carica(null, cartelle.immagini(), meta, file, MIME_PNG)
        }
        val permesso = try {
            rest.condividiConChiunque(id)
        } catch (e: Exception) {
            withContext(NonCancellable) { runCatching { rest.elimina(id) } }
            throw e
        }
        return Pubblicata(id, permesso)
    }

    override suspend fun ritira(immagine: Pubblicata) {
        // Prima il permesso: se la cancellazione non riesce, il file almeno non e' piu' pubblico.
        runCatching { rest.togliPermesso(immagine.id, immagine.permesso) }
        rest.elimina(immagine.id)
    }

    /** Crea il documento Google della nota nella cartella "Tratto", con le pagine A4. Restituisce il suo id. */
    suspend fun creaDocumento(info: NotaInfo): String {
        val meta = JSONObject()
            .put("name", DocsSync.nomeDocumento(info))
            .put("mimeType", MIME_DOCUMENTO)
            .put("parents", JSONArray().put(cartelle.tratto()))
            // Niente "notaId": il backup riconosce le sue note da quella proprieta'.
            .put("appProperties", JSONObject().put("tratto", "docs").put("docsNota", info.id))
        val id = rest.crea(meta)
        try {
            // E' anche la prova che l'API di Docs risponde: senza, il documento resterebbe vuoto per sempre.
            docs.aggiorna(id, JSONArray().put(DocsRequests.paginaA4()))
        } catch (e: Exception) {
            withContext(NonCancellable) { runCatching { rest.elimina(id) } }
            throw e
        }
        return id
    }

    /** Cancella le immagini temporanee rimaste da un aggiornamento interrotto (e quindi forse ancora pubbliche). */
    suspend fun pulisciAvanzi() {
        if (cartelle.idImmagini == null) return
        val cartella = cartelle.immagini(verifica = true)
        for (f in rest.cerca("'$cartella' in parents and trashed = false")) rest.elimina(f.id)
    }

    companion object {
        const val MIME_DOCUMENTO = "application/vnd.google-apps.document"
        private const val MIME_PNG = "image/png"
    }
}
