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

/** Le due chiamate dell'API di Google Docs usate da Tratto, con il client (token e tentativi) di Drive. */
internal class DocsRest(private val rest: DriveRest) {
    suspend fun documento(id: String): JSONObject =
        rest.richiesta("GET", "$API/documents/$id?fields=${URLEncoder.encode(LetturaDoc.CAMPI, "UTF-8")}")

    suspend fun aggiorna(id: String, richieste: JSONArray, revisione: String? = null): JSONObject =
        rest.richiesta("POST", "$API/documents/$id:batchUpdate", RichiesteDocs.corpo(richieste, revisione))

    private companion object {
        const val API = "https://docs.googleapis.com/v1"
    }
}

/**
 * Le cartelle su Drive: "Tratto", la stessa del backup (la si riconosce dalle appProperties), e
 * dentro ".immagini-docs" per le immagini temporanee. Gli id trovati o creati si ricordano in
 * [CollegamentiDocs].
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
internal class ServizioGoogle(private val rest: DriveRest, val cartelle: CartelleDocs) : ServizioDocs {
    private val docs = DocsRest(rest)

    override suspend fun documento(id: String): JSONObject = docs.documento(id)

    override suspend fun aggiorna(id: String, richieste: JSONArray, revisione: String?) {
        docs.aggiorna(id, richieste, revisione)
    }

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
            .put("name", SincroniaDocs.nomeDocumento(info))
            .put("mimeType", MIME_DOCUMENTO)
            .put("parents", JSONArray().put(cartelle.tratto()))
            // Niente "notaId": il backup riconosce le sue note da quella proprieta'.
            .put("appProperties", JSONObject().put("tratto", "docs").put("docsNota", info.id))
        val id = rest.crea(meta)
        try {
            // E' anche la prova che l'API di Docs risponde: senza, il documento resterebbe vuoto per sempre.
            docs.aggiorna(id, JSONArray().put(RichiesteDocs.paginaA4()))
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
