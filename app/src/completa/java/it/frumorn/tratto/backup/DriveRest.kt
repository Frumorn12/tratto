package it.frumorn.tratto.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import kotlin.math.min
import kotlin.random.Random

/**
 * Risposta di errore di Drive (o di Docs, che usa lo stesso client), con un messaggio in italiano
 * pronto da mostrare. [dettaglio] e' il messaggio originale di Google, per riconoscere i casi particolari.
 */
internal class ErroreDrive(val codice: Int, val motivo: String?, messaggio: String, val dettaglio: String? = null) : IOException(messaggio) {
    val temporaneo: Boolean
        get() = codice == 408 || codice == 429 || codice >= 500 ||
            (codice == 403 && motivo in setOf("rateLimitExceeded", "userRateLimitExceeded"))
}

/** La sessione di caricamento resumable e' scaduta: va riaperta da capo. */
internal class SessioneScaduta : IOException("Sessione di caricamento scaduta")

/** Un file nella cartella di Drive, con le appProperties messe da Tratto. */
internal class FileDrive(val id: String, val nome: String, val proprieta: Map<String, String>) {
    val notaId: String? get() = proprieta["notaId"]
    val modificata: Long get() = proprieta["modificata"]?.toLongOrNull() ?: 0L
}

/** Vale la pena riprovare piu' tardi? (rete assente, Drive sovraccarico...) */
internal fun Throwable.temporaneo(): Boolean = when (this) {
    is ErroreDrive -> temporaneo
    is ErroreAccesso -> temporaneo
    is BackupNonValido -> false
    is IOException -> true
    else -> false
}

/**
 * Client minimo per Drive REST v3 con HttpURLConnection, senza le librerie Google per le API.
 * Le chiamate girano su Dispatchers.IO. Su 401 chiede una volta un token nuovo a [rinnova];
 * sugli errori temporanei riprova con attese crescenti (le richieste non idempotenti solo se Drive
 * ha risposto con un errore, non se e' caduta la rete).
 * Lo usa anche il collegamento a Google Docs, per le sue chiamate a docs.googleapis.com ([richiesta]).
 */
internal class DriveRest(private var token: String, private val rinnova: suspend (scaduto: String) -> String) {

    suspend fun email(): String? =
        json("GET", "$API/about?fields=${enc("user(emailAddress)")}").optJSONObject("user")?.testo("emailAddress")

    /** Metadati di un file, o null se non esiste. */
    suspend fun file(id: String, campi: String): JSONObject? = try {
        json("GET", "$API/files/$id?fields=${enc(campi)}")
    } catch (e: ErroreDrive) {
        if (e.codice == 404) null else throw e
    }

    suspend fun cerca(q: String): List<FileDrive> {
        val out = ArrayList<FileDrive>()
        var pagina: String? = null
        do {
            val url = "$API/files?q=${enc(q)}&spaces=drive&pageSize=1000&fields=${enc("nextPageToken,files(id,name,appProperties)")}" +
                (pagina?.let { "&pageToken=${enc(it)}" } ?: "")
            val r = json("GET", url)
            for (f in r.elenco("files")) {
                val p = f.optJSONObject("appProperties")
                val proprieta = HashMap<String, String>()
                p?.keys()?.forEach { k -> proprieta[k] = p.optString(k) }
                out += FileDrive(f.getString("id"), f.optString("name"), proprieta)
            }
            pagina = r.testo("nextPageToken")
        } while (pagina != null)
        return out
    }

    /** Crea una cartella: nella radice di Drive o, con [genitore], dentro un'altra. */
    suspend fun creaCartella(nome: String, proprieta: JSONObject, genitore: String? = null): String {
        val corpo = JSONObject().put("name", nome).put("mimeType", MIME_CARTELLA).put("appProperties", proprieta)
        if (genitore != null) corpo.put("parents", JSONArray().put(genitore))
        return crea(corpo)
    }

    /** Crea un file senza contenuto (per esempio un documento Google vuoto) e ne restituisce l'id. */
    suspend fun crea(metadati: JSONObject): String = json("POST", "$API/files?fields=id", metadati).getString("id")

    /** Chiunque abbia il link puo' leggere il file. Restituisce l'id del permesso, da togliere con [togliPermesso]. */
    suspend fun condividiConChiunque(id: String): String {
        val corpo = JSONObject().put("role", "reader").put("type", "anyone")
        // Ripeterla non fa danni: il permesso "anyone" di un file e' uno solo.
        return json("POST", "$API/files/$id/permissions?fields=id", corpo, idempotente = true).getString("id")
    }

    suspend fun togliPermesso(id: String, permesso: String) {
        try {
            json("DELETE", "$API/files/$id/permissions/$permesso")
        } catch (e: ErroreDrive) {
            if (e.codice != 404) throw e
        }
    }

    /** Cancella il file per sempre, senza passare dal cestino. */
    suspend fun elimina(id: String) {
        try {
            json("DELETE", "$API/files/$id")
        } catch (e: ErroreDrive) {
            if (e.codice != 404) throw e
        }
    }

    /**
     * Richiesta JSON a un'altra API Google con lo stesso token e gli stessi tentativi (Google Docs).
     * Dopo una caduta della rete una POST si ripete solo se [idempotente].
     */
    suspend fun richiesta(metodo: String, url: String, corpo: JSONObject? = null, idempotente: Boolean = metodo != "POST"): JSONObject =
        json(metodo, url, corpo, idempotente)

    suspend fun aggiornaMetadati(id: String, metadati: JSONObject) {
        json("PATCH", "$API/files/$id?fields=id", metadati)
    }

    /** Sposta il file nel cestino di Drive (non lo cancella). */
    suspend fun cestina(id: String) {
        try {
            aggiornaMetadati(id, JSONObject().put("trashed", true))
        } catch (e: ErroreDrive) {
            if (e.codice != 404) throw e
        }
    }

    suspend fun scarica(id: String, dest: File) = chiama(idempotente = true) { t ->
        val c = apri("$API/files/$id?alt=media", "GET", t)
        try {
            controlla(c)
            c.inputStream.use { i -> FileOutputStream(dest).use { o -> i.copyTo(o, BUFFER) } }
        } finally {
            c.disconnect()
        }
    }

    /**
     * Crea (se [fileId] e' null) o aggiorna un file nella [cartella] col contenuto di [file].
     * Fino a 5 MB un caricamento multipart, oltre uno resumable a blocchi. Restituisce l'id del file.
     */
    suspend fun carica(fileId: String?, cartella: String, metadati: JSONObject, file: File, mime: String): String {
        if (fileId != null) {
            try {
                return caricaUna(fileId, cartella, metadati, file, mime)
            } catch (e: ErroreDrive) {
                if (e.codice != 404) throw e
                // Il file su Drive non c'e' piu' (cancellato a mano): se ne crea uno nuovo.
            }
        }
        return caricaUna(null, cartella, metadati, file, mime)
    }

    private suspend fun caricaUna(fileId: String?, cartella: String, metadati: JSONObject, file: File, mime: String): String {
        val meta = copiaJson(metadati)
        if (fileId == null) meta.put("parents", JSONArray().put(cartella))
        return if (file.length() <= LIMITE_MULTIPART) caricaMultipart(fileId, meta, file, mime) else caricaResumable(fileId, meta, file, mime)
    }

    private suspend fun caricaMultipart(fileId: String?, meta: JSONObject, file: File, mime: String): String =
        chiama(idempotente = fileId != null) { t ->
            val c = apri(urlCaricamento(fileId, "multipart"), if (fileId == null) "POST" else "PATCH", t)
            try {
                val confine = "tratto-" + UUID.randomUUID()
                val testa = ("--$confine\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n" +
                    "--$confine\r\nContent-Type: $mime\r\n\r\n").toByteArray()
                val coda = "\r\n--$confine--\r\n".toByteArray()
                c.doOutput = true
                c.setRequestProperty("Content-Type", "multipart/related; boundary=$confine")
                c.setFixedLengthStreamingMode(testa.size + file.length() + coda.size)
                c.outputStream.use { o ->
                    o.write(testa)
                    file.inputStream().use { it.copyTo(o, BUFFER) }
                    o.write(coda)
                }
                leggiJson(c).getString("id")
            } finally {
                c.disconnect()
            }
        }

    private suspend fun caricaResumable(fileId: String?, meta: JSONObject, file: File, mime: String): String {
        var sessioni = 0
        while (true) {
            // Aprire una sessione non crea ancora il file: si puo' ripetere senza rischi.
            val sessione = chiama(idempotente = true) { t ->
                val c = apri(urlCaricamento(fileId, "resumable"), if (fileId == null) "POST" else "PATCH", t)
                try {
                    val corpo = meta.toString().toByteArray()
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    c.setRequestProperty("X-Upload-Content-Type", mime)
                    c.setRequestProperty("X-Upload-Content-Length", file.length().toString())
                    c.setFixedLengthStreamingMode(corpo.size)
                    c.outputStream.use { it.write(corpo) }
                    controlla(c)
                    c.getHeaderField("Location") ?: throw IOException("Google Drive non ha aperto la sessione di caricamento.")
                } finally {
                    c.disconnect()
                }
            }
            try {
                return inviaBlocchi(sessione, file)
            } catch (e: SessioneScaduta) {
                if (++sessioni > 1) throw IOException("Caricamento su Google Drive interrotto: si riproverà.", e)
            }
        }
    }

    private sealed interface Esito
    private class Completato(val id: String) : Esito
    private class Parziale(val ricevuti: Long) : Esito

    /** Invia il file a blocchi da 8 MB; se la rete cade chiede a Drive quanto ha ricevuto e riparte da li'. */
    private suspend fun inviaBlocchi(sessione: String, file: File): String {
        val totale = file.length()
        var inviati = 0L
        var errori = 0
        var daVerificare = false
        RandomAccessFile(file, "r").use { raf ->
            while (true) {
                val esito = try {
                    withContext(Dispatchers.IO) {
                        if (daVerificare) statoSessione(sessione, totale) else inviaBlocco(sessione, raf, inviati, totale)
                    }
                } catch (e: IOException) {
                    if (e is SessioneScaduta || (e is ErroreDrive && !e.temporaneo) || ++errori > TENTATIVI) throw e
                    delay(attesa(errori))
                    daVerificare = true
                    continue
                }
                daVerificare = false
                when (esito) {
                    is Completato -> return esito.id
                    is Parziale -> {
                        if (esito.ricevuti > inviati) errori = 0
                        inviati = esito.ricevuti
                    }
                }
            }
        }
    }

    private fun inviaBlocco(sessione: String, raf: RandomAccessFile, da: Long, totale: Long): Esito {
        val n = min(BLOCCO, totale - da)
        val c = apriSessione(sessione)
        try {
            c.setRequestProperty("Content-Range", "bytes $da-${da + n - 1}/$totale")
            c.setFixedLengthStreamingMode(n)
            c.outputStream.use { o ->
                raf.seek(da)
                val buf = ByteArray(BUFFER)
                var resto = n
                while (resto > 0) {
                    val r = raf.read(buf, 0, min(buf.size.toLong(), resto).toInt())
                    if (r < 0) throw IOException("Il file da caricare si è accorciato")
                    o.write(buf, 0, r)
                    resto -= r
                }
            }
            return esitoSessione(c)
        } finally {
            c.disconnect()
        }
    }

    private fun statoSessione(sessione: String, totale: Long): Esito {
        val c = apriSessione(sessione)
        try {
            c.setRequestProperty("Content-Range", "bytes */$totale")
            c.setFixedLengthStreamingMode(0)
            c.outputStream.close()
            return esitoSessione(c)
        } finally {
            c.disconnect()
        }
    }

    private fun apriSessione(sessione: String): HttpURLConnection {
        val c = URL(sessione).openConnection() as HttpURLConnection
        c.connectTimeout = TIMEOUT_CONNESSIONE
        c.readTimeout = TIMEOUT_LETTURA
        c.instanceFollowRedirects = false // 308 qui vuol dire "continua", non e' un redirect
        c.requestMethod = "PUT"
        c.doOutput = true
        return c
    }

    private fun esitoSessione(c: HttpURLConnection): Esito = when (val codice = c.responseCode) {
        200, 201 -> Completato(JSONObject(testo(c.inputStream)).getString("id"))
        308 -> Parziale(c.getHeaderField("Range")?.substringAfterLast('-')?.toLongOrNull()?.plus(1) ?: 0L)
        404, 410 -> throw SessioneScaduta()
        else -> throw errore(c, codice)
    }

    // ---------------------------------------------------------------- HTTP

    private suspend fun json(metodo: String, url: String, corpo: JSONObject? = null, idempotente: Boolean = metodo != "POST"): JSONObject =
        chiama(idempotente) { t ->
            val c = apri(url, metodo, t)
            try {
                if (corpo != null) {
                    val b = corpo.toString().toByteArray()
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    c.setFixedLengthStreamingMode(b.size)
                    c.outputStream.use { it.write(b) }
                }
                leggiJson(c)
            } finally {
                c.disconnect()
            }
        }

    /** Esegue [blocco] col token attuale, rinnovandolo su 401 e riprovando sugli errori temporanei. */
    private suspend fun <T> chiama(idempotente: Boolean, blocco: (token: String) -> T): T {
        var rinnovato = false
        var errori = 0
        while (true) {
            try {
                return withContext(Dispatchers.IO) { blocco(token) }
            } catch (e: ErroreDrive) {
                if (e.codice == 401 && !rinnovato) {
                    rinnovato = true
                    token = rinnova(token)
                    continue
                }
                if (!e.temporaneo || ++errori > TENTATIVI) throw e
            } catch (e: IOException) {
                if (!idempotente || ++errori > TENTATIVI) throw e
            }
            delay(attesa(errori))
        }
    }

    private fun apri(url: String, metodo: String, token: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = TIMEOUT_CONNESSIONE
        c.readTimeout = TIMEOUT_LETTURA
        try {
            c.requestMethod = metodo
        } catch (_: ProtocolException) {
            // HttpURLConnection del JDK non conosce PATCH (quella di Android si'): le API Google accettano l'override.
            c.requestMethod = "POST"
            c.setRequestProperty("X-HTTP-Method-Override", metodo)
        }
        c.setRequestProperty("Authorization", "Bearer $token")
        return c
    }

    private fun controlla(c: HttpURLConnection) {
        val codice = c.responseCode
        if (codice >= 400) throw errore(c, codice)
    }

    private fun leggiJson(c: HttpURLConnection): JSONObject {
        controlla(c)
        val t = testo(c.inputStream)
        return if (t.isBlank()) JSONObject() else JSONObject(t)
    }

    private fun errore(c: HttpURLConnection, codice: Int): ErroreDrive {
        val corpo = try { c.errorStream?.let { testo(it) } ?: "" } catch (_: IOException) { "" }
        val err = try { JSONObject(corpo).optJSONObject("error") } catch (_: Exception) { null }
        // Le API v1 come Docs non hanno "errors", solo lo stato (INVALID_ARGUMENT, RESOURCE_EXHAUSTED...).
        val motivo = err?.elenco("errors")?.firstOrNull()?.testo("reason") ?: err?.testo("status")
        val dettaglio = err?.testo("message")
        val docs = c.url.host == HOST_DOCS
        val servizio = if (docs) "Google Docs" else "Google Drive"
        val messaggio = when {
            motivo == "storageQuotaExceeded" -> "Lo spazio su Google Drive è esaurito."
            motivo == "accessNotConfigured" || "SERVICE_DISABLED" in corpo ->
                "L'API di $servizio non è abilitata nel progetto Google Cloud di Tratto."
            codice == 401 -> "L'accesso a $servizio è scaduto."
            codice == 403 -> "$servizio ha rifiutato l'operazione" + (dettaglio?.let { ": $it" } ?: ".")
            codice == 404 -> if (docs) "Documento non trovato su Google Docs." else "File non trovato su Google Drive."
            codice >= 500 -> "$servizio non risponde (errore $codice)."
            else -> "Errore di $servizio ($codice)" + (dettaglio?.let { ": $it" } ?: ".")
        }
        return ErroreDrive(codice, motivo, messaggio, dettaglio)
    }

    private fun testo(input: InputStream): String = input.use { it.readBytes().toString(Charsets.UTF_8) }

    private fun urlCaricamento(fileId: String?, tipo: String) =
        if (fileId == null) "$UPLOAD/files?uploadType=$tipo&fields=id" else "$UPLOAD/files/$fileId?uploadType=$tipo&fields=id"

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun attesa(tentativo: Int): Long = (1000L shl min(tentativo, 5)) + Random.nextLong(1000)

    companion object {
        const val MIME_CARTELLA = "application/vnd.google-apps.folder"
        private const val HOST_DOCS = "docs.googleapis.com"
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private const val LIMITE_MULTIPART = 5L shl 20
        /** Blocchi del caricamento resumable: devono essere multipli di 256 KiB. */
        private const val BLOCCO = 8L shl 20
        private const val BUFFER = 64 * 1024
        private const val TENTATIVI = 4
        private const val TIMEOUT_CONNESSIONE = 20_000
        private const val TIMEOUT_LETTURA = 60_000
    }
}
