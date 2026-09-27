package it.frumorn.tratto.backup

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import it.frumorn.tratto.TrattoApp
import it.frumorn.tratto.data.Archivio
import it.frumorn.tratto.data.scriviAtomico
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Errore dell'autorizzazione Google, con messaggio in italiano. */
internal class ErroreAccesso(messaggio: String, causa: Throwable?, val temporaneo: Boolean) : IOException(messaggio, causa)

/**
 * Backup su Google Drive (scope drive.file: l'app vede solo i file che ha creato lei).
 *
 *   Tratto/                          cartella nel Drive dell'utente, appProperties {"tratto":"cartella"}
 *     <titolo>.tratto                una nota (stesso formato di BackupLocale.esportaNota),
 *                                    appProperties {"tratto":"nota","notaId":..,"modificata":..}
 *     indice.tratto-indice           indice.json dell'archivio (cartelle e dati delle note)
 *
 * Si caricano solo le note cambiate dall'ultimo backup; lo stato (nota -> file su Drive) e' in
 * files/backup/drive.json. Le note cancellate sul tablet finiscono nel cestino di Drive.
 *
 * Collegamento dalla UI in Compose (oppure [rememberAccessoDrive], che fa proprio questo):
 *
 *   val lanciatore = rememberLauncherForActivityResult(StartIntentSenderForResult()) { r ->
 *       if (r.resultCode == RESULT_OK) scope.launch { drive.completaAutorizzazione(r.data!!) }
 *   }
 *   scope.launch {
 *       when (val r = drive.autorizza()) {
 *           is Risultato.ServeConsenso -> lanciatore.launch(IntentSenderRequest.Builder(r.pendingIntent).build())
 *           is Risultato.Autorizzato -> Unit // collegato: l'account e' in ImpostazioniBackup
 *       }
 *   }
 *
 * Nel codice non c'e' nessun client ID: Google riconosce l'app da package e SHA-1 (vedi docs/backup.md).
 */
class DriveBackup(context: Context) {
    private val app = context.applicationContext
    private val client by lazy { Identity.getAuthorizationClient(app) }
    private val fileStato get() = File(app.filesDir, "backup/drive.json")

    sealed interface Risultato {
        /** Accesso concesso: l'account collegato e' in [ImpostazioniBackup]. */
        data class Autorizzato(val token: String) : Risultato

        /** Serve il consenso dell'utente: lanciare [pendingIntent] con StartIntentSenderForResult e poi [completaAutorizzazione]. */
        data class ServeConsenso(val pendingIntent: PendingIntent) : Risultato
    }

    /** Il consenso e' stato revocato o scaduto: serve [autorizza] dalla UI. */
    class AccessoNecessario : Exception("Serve di nuovo l'accesso a Google Drive.")

    data class RiepilogoBackup(val caricate: Int, val rinominate: Int, val cestinate: Int)

    // ---------------------------------------------------------------- autorizzazione

    suspend fun autorizza(): Risultato {
        val r = richiedi()
        val pi = r.pendingIntent
        if (r.hasResolution() && pi != null) return Risultato.ServeConsenso(pi)
        return collegato(r)
    }

    /** Da chiamare col risultato della schermata di consenso aperta da [Risultato.ServeConsenso]. */
    suspend fun completaAutorizzazione(data: Intent): Risultato.Autorizzato {
        val r = try {
            client.getAuthorizationResultFromIntent(data)
        } catch (e: ApiException) {
            throw erroreAccesso(e)
        }
        return collegato(r)
    }

    /** Revoca l'accesso, dimentica lo stato del backup e spegne quello automatico. I file su Drive restano. */
    suspend fun disconnetti() {
        val account = withContext(Dispatchers.IO) { ImpostazioniBackup.stato(app).value.account }
        if (account != null) {
            try {
                val richiesta = RevokeAccessRequest.builder()
                    .setAccount(Account(account, TIPO_ACCOUNT))
                    .setScopes(listOf(Scope(SCOPE_DRIVE_FILE)))
                    .build()
                client.revokeAccess(richiesta).attendi()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Si scollega comunque: al massimo l'accesso resta concesso in Google.
            }
        }
        mutex.withLock { withContext(Dispatchers.IO) { fileStato.delete() } }
        withContext(Dispatchers.IO) {
            ImpostazioniBackup.scollegato(app)
            BackupWorker.pianifica(app)
        }
    }

    private suspend fun collegato(r: AuthorizationResult): Risultato.Autorizzato {
        val token = r.accessToken ?: throw ErroreAccesso("Google non ha concesso l'accesso a Drive.", null, false)
        if (SCOPE_DRIVE_FILE !in r.grantedScopes) {
            throw ErroreAccesso("Per il backup serve il permesso di vedere e gestire i file creati da Tratto su Google Drive.", null, false)
        }
        // L'email serve solo da mostrare: se non arriva la si prende al primo backup.
        val email = try {
            DriveRest(token) { tokenSilenzioso() }.email()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
        withContext(Dispatchers.IO) {
            ImpostazioniBackup.collegato(app, email)
            BackupWorker.pianifica(app)
        }
        return Risultato.Autorizzato(token)
    }

    private suspend fun richiedi(): AuthorizationResult {
        val richiesta = withContext(Dispatchers.IO) {
            val b = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE_DRIVE_FILE)))
            ImpostazioniBackup.stato(app).value.account?.let { b.setAccount(Account(it, TIPO_ACCOUNT)) }
            b.build()
        }
        return try {
            client.authorize(richiesta).attendi()
        } catch (e: ApiException) {
            throw erroreAccesso(e)
        }
    }

    /** Token senza interfaccia (dopo il primo consenso Play services lo da' senza chiedere nulla). */
    private suspend fun tokenSilenzioso(): String {
        val r = richiedi()
        if (r.hasResolution()) throw AccessoNecessario()
        return r.accessToken ?: throw AccessoNecessario()
    }

    private suspend fun connetti(): DriveRest = DriveRest(tokenSilenzioso()) { scaduto ->
        try {
            client.clearToken(ClearTokenRequest.builder().setToken(scaduto).build()).attendi()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
        tokenSilenzioso()
    }

    private fun erroreAccesso(e: ApiException): Exception = when (e.statusCode) {
        CommonStatusCodes.SIGN_IN_REQUIRED, CommonStatusCodes.RESOLUTION_REQUIRED -> AccessoNecessario()
        CommonStatusCodes.DEVELOPER_ERROR -> ErroreAccesso(
            "Accesso a Google non configurato per questa app: in Google Cloud Console serve un client OAuth " +
                "Android con il package e lo SHA-1 giusti.", e, false,
        )
        CommonStatusCodes.NETWORK_ERROR -> ErroreAccesso("Nessuna connessione a Internet.", e, true)
        CommonStatusCodes.CANCELED -> ErroreAccesso("Accesso a Google annullato.", e, false)
        else -> ErroreAccesso("Accesso a Google non riuscito (codice ${e.statusCode}).", e, true)
    }

    // ---------------------------------------------------------------- backup

    /** Carica su Drive le note cambiate dall'ultima volta, rinomina, cestina le cancellate e aggiorna l'indice. */
    suspend fun sincronizza(archivio: Archivio = TrattoApp.archivio(app)): RiepilogoBackup = conStato {
        val rest = connetti()
        val stato = withContext(Dispatchers.IO) { StatoDrive.leggi(fileStato) }
        controllaAccount(rest, stato)
        val cartella = cartella(rest, stato, crea = true)!!
        val remoti = rest.cerca("'$cartella' in parents and trashed = false")
        val perId = remoti.associateBy { it.id }
        val perNota = HashMap<String, FileDrive>()
        for (f in remoti.sortedByDescending { it.modificata }) f.notaId?.let { perNota.putIfAbsent(it, f) }

        val radice = archivio.radice
        val datiIndice = withContext(Dispatchers.IO) { File(radice, "indice.json").takeIf { it.isFile }?.readBytes() }
        val voci = try {
            datiIndice?.let { JSONObject(String(it, Charsets.UTF_8)) }?.elenco("note") ?: emptyList()
        } catch (e: JSONException) {
            throw IOException("L'indice delle note sul tablet è illeggibile: backup non eseguito.", e)
        }
        val temp = File(app.cacheDir, "drive-backup")
        var caricate = 0
        var rinominate = 0
        var cestinate = 0
        try {
            withContext(Dispatchers.IO) { temp.mkdirs() }
            for (n in voci) {
                val id = n.id
                if (!ID_VALIDO.matches(id)) continue
                val nome = nomeSuDrive(n)
                val remoto = stato.note[id]?.fileId?.let { perId[it] } ?: perNota[id]
                var voce = stato.note[id]
                if (voce == null && remoto != null && remoto.modificata == n.modificata) {
                    // Gia' su Drive, ad esempio dopo un ripristino da Drive: basta ricordarselo.
                    voce = StatoDrive.Voce(remoto.id, remoto.modificata, remoto.nome)
                    stato.note[id] = voce
                }
                if (voce == null || remoto == null || voce.fileId != remoto.id || voce.modificata != n.modificata) {
                    val zip = File(temp, "$id.tratto")
                    val esportate = withContext(Dispatchers.IO) {
                        FileOutputStream(zip).use { BackupLocale.esportaIn(radice, it, id) }
                    }
                    if (esportate.isEmpty()) continue // cancellata nel frattempo
                    val modificata = esportate[0].modificata
                    val meta = JSONObject()
                        .put("name", nome)
                        .put("mimeType", MIME_NOTA)
                        .put("appProperties", JSONObject().put("tratto", "nota").put("notaId", id).put("modificata", modificata.toString()))
                    val fileId = rest.carica(remoto?.id, cartella, meta, zip, MIME_NOTA)
                    zip.delete()
                    stato.note[id] = StatoDrive.Voce(fileId, modificata, nome)
                    salva(stato)
                    caricate++
                } else if (voce.nome != nome) {
                    rest.aggiornaMetadati(voce.fileId, JSONObject().put("name", nome))
                    stato.note[id] = voce.copy(nome = nome)
                    salva(stato)
                    rinominate++
                }
            }

            // Note cancellate sul tablet: il file va nel cestino di Drive, che lo tiene 30 giorni.
            // Con l'indice vuoto non si cestina niente: meglio un file in piu' che svuotare Drive per sbaglio.
            val idLocali = voci.mapTo(HashSet()) { it.id }
            if (idLocali.isNotEmpty()) {
                for ((id, v) in stato.note.toList()) {
                    if (id in idLocali) continue
                    if (v.fileId in perId) rest.cestina(v.fileId)
                    stato.note.remove(id)
                    salva(stato)
                    cestinate++
                }
            }

            // Indice: cartelle, titoli e preferite (cambiano senza toccare le pagine).
            if (datiIndice != null) {
                val impronta = sha256(datiIndice)
                val remoto = stato.indiceId?.let { perId[it] } ?: remoti.firstOrNull { it.proprieta["tratto"] == "indice" }
                if (remoto == null || remoto.id != stato.indiceId || impronta != stato.indiceImpronta) {
                    val f = File(temp, NOME_INDICE)
                    withContext(Dispatchers.IO) { f.writeBytes(datiIndice) }
                    val meta = JSONObject()
                        .put("name", NOME_INDICE)
                        .put("mimeType", MIME_INDICE)
                        .put("appProperties", JSONObject().put("tratto", "indice"))
                    stato.indiceId = rest.carica(remoto?.id, cartella, meta, f, MIME_INDICE)
                    stato.indiceImpronta = impronta
                    salva(stato)
                }
            }
            salva(stato)
        } finally {
            withContext(Dispatchers.IO) { temp.deleteRecursively() }
        }
        withContext(Dispatchers.IO) { ImpostazioniBackup.riuscito(app, System.currentTimeMillis()) }
        RiepilogoBackup(caricate, rinominate, cestinate)
    }

    /**
     * Scarica tutte le note dalla cartella "Tratto" di Drive e le unisce all'archivio con le regole
     * di [ModoRipristino.UNISCI]: nessuna nota locale va persa.
     */
    suspend fun ripristinaDaDrive(archivio: Archivio = TrattoApp.archivio(app)): RiepilogoRipristino = conStato {
        val rest = connetti()
        val stato = withContext(Dispatchers.IO) { StatoDrive.leggi(fileStato) }
        controllaAccount(rest, stato)
        val cartella = cartella(rest, stato, crea = false)
            ?: throw IOException("Su Google Drive non c'è nessun backup di Tratto per questo account.")
        val remoti = rest.cerca("'$cartella' in parents and trashed = false")
        val lavoro = File(archivio.temporanei, "drive-ripristino-${System.nanoTime()}")
        try {
            val unito = File(lavoro, "unito/tratto")
            withContext(Dispatchers.IO) { File(unito, "note").mkdirs() }

            val fileIndice = remoti.firstOrNull { it.proprieta["tratto"] == "indice" }
            val indiceRemoto = fileIndice?.let { f ->
                val dest = File(lavoro, "indice.json")
                rest.scarica(f.id, dest)
                withContext(Dispatchers.IO) {
                    try { JSONObject(dest.readText()) } catch (_: JSONException) { null }
                }
            }
            val vociIndice = indiceRemoto?.elenco("note")?.associateBy { it.id } ?: emptyMap()
            val cartelle = LinkedHashMap<String, JSONObject>()
            indiceRemoto?.elenco("cartelle")?.forEach { if (it.id.isNotEmpty()) cartelle.putIfAbsent(it.id, it) }

            // Una nota per id: se su Drive ce n'e' piu' d'una si prende la piu' recente.
            val daScaricare = remoti
                .filter { f -> f.notaId?.let { ID_VALIDO.matches(it) } == true }
                .sortedByDescending { it.modificata }
                .distinctBy { it.notaId }
            val note = ArrayList<JSONObject>()
            val origine = HashMap<String, FileDrive>()
            var saltate = 0
            for (f in daScaricare) {
                val zip = File(lavoro, "nota.tratto")
                rest.scarica(f.id, zip)
                val n = withContext(Dispatchers.IO) { prendiNota(zip, lavoro, unito, cartelle) }
                if (n == null) {
                    saltate++
                    continue
                }
                // L'indice su Drive ha titolo, cartella e preferita aggiornati se la nota non e' cambiata dopo.
                note += vociIndice[n.id]?.takeIf { it.modificata == n.modificata }?.let { copiaJson(it) } ?: n
                origine[n.id] = f
            }

            val riepilogo = withContext(Dispatchers.IO) {
                val indice = indiceCon(indiceRemoto ?: indiceVuoto(), cartelle.values.toList(), note)
                scriviAtomico(File(unito, "indice.json"), indice.toString().toByteArray())
                val sorgente = BackupLocale.Sorgente(unito, indice, note, cartelle.values.toList(), saltate)
                BackupLocale.applica(archivio.radice, sorgente, ModoRipristino.UNISCI, archivio) { archivio.ricarica() }
            }

            // Le note che ora sono identiche a quelle su Drive non vanno ricaricate al prossimo backup.
            withContext(Dispatchers.IO) {
                val locali = leggiIndiceDa(File(archivio.radice, "indice.json")).elenco("note").associateBy { it.id }
                for ((id, f) in origine) {
                    if (locali[id]?.modificata == f.modificata) stato.note[id] = StatoDrive.Voce(f.id, f.modificata, f.nome)
                }
                stato.salva(fileStato)
            }
            riepilogo
        } finally {
            withContext(Dispatchers.IO) { lavoro.deleteRecursively() }
        }
    }

    /** Estrae la nota di un .tratto scaricato dentro [unito]; restituisce la sua voce d'indice o null se il file non va. */
    private fun prendiNota(zip: File, lavoro: File, unito: File, cartelle: MutableMap<String, JSONObject>): JSONObject? {
        val dir = File(lavoro, "estratto")
        try {
            FileInputStream(zip).use { BackupLocale.estrai(it, dir) }
            val s = BackupLocale.leggiSorgente(dir)
            val n = s.note.singleOrNull() ?: return null
            val dest = File(unito, "note/${n.id}")
            if (dest.exists() || !s.cartellaNota(n.id).renameTo(dest)) return null
            s.cartelle.forEach { cartelle.putIfAbsent(it.id, it) }
            return n
        } catch (_: BackupNonValido) {
            return null
        } finally {
            dir.deleteRecursively()
            zip.delete()
        }
    }

    // ---------------------------------------------------------------- supporto

    /** Un solo backup o ripristino alla volta, con lo stato per la UI aggiornato. */
    private suspend fun <T> conStato(blocco: suspend () -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) { ImpostazioniBackup.inizio(app) }
        try {
            blocco()
        } catch (e: AccessoNecessario) {
            withContext(Dispatchers.IO) { ImpostazioniBackup.serveAccesso(app) }
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            withContext(Dispatchers.IO) { ImpostazioniBackup.errore(app, messaggio(e)) }
            throw e
        } finally {
            withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { ImpostazioniBackup.fine(app) }
        }
    }

    /** Se l'account Google e' cambiato, gli id dei file su Drive salvati non valgono piu'. */
    private suspend fun controllaAccount(rest: DriveRest, stato: StatoDrive) {
        val email = rest.email() ?: return
        if (stato.account != null && stato.account != email) stato.azzera()
        stato.account = email
        withContext(Dispatchers.IO) { ImpostazioniBackup.collegato(app, email) }
    }

    /** Id della cartella "Tratto" su Drive: quella salvata, oppure la si cerca, oppure (se [crea]) la si crea. */
    private suspend fun cartella(rest: DriveRest, stato: StatoDrive, crea: Boolean): String? {
        stato.cartella?.let { id ->
            val f = rest.file(id, "id,trashed")
            if (f != null && !f.optBoolean("trashed")) return id
        }
        val q = "mimeType = '${DriveRest.MIME_CARTELLA}' and appProperties has { key='tratto' and value='cartella' } and trashed = false"
        val id = rest.cerca(q).firstOrNull()?.id
            ?: if (crea) rest.creaCartella(NOME_CARTELLA, JSONObject().put("tratto", "cartella")) else return null
        stato.cartella = id
        salva(stato)
        return id
    }

    private suspend fun salva(stato: StatoDrive) = withContext(Dispatchers.IO) { stato.salva(fileStato) }

    private fun messaggio(e: Exception): String = when (e) {
        is UnknownHostException -> "Nessuna connessione a Internet."
        is SocketTimeoutException -> "Google Drive non risponde: si riproverà più tardi."
        is JSONException -> "Risposta non valida da Google Drive."
        else -> e.message ?: "Errore sconosciuto durante il backup."
    }

    private fun nomeSuDrive(n: JSONObject): String {
        val t = n.titolo.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]+"), " ").trim().take(80)
        return t.ifEmpty { n.id } + ".tratto"
    }

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    companion object {
        const val SCOPE_DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"
        private const val TIPO_ACCOUNT = "com.google"
        private const val NOME_CARTELLA = "Tratto"
        private const val NOME_INDICE = "indice.tratto-indice"
        private const val MIME_NOTA = "application/zip"
        private const val MIME_INDICE = "application/json"

        /** Condiviso da tutte le istanze: il worker e la UI non devono lavorare sullo stesso stato insieme. */
        private val mutex = Mutex()
    }
}

/** Stato del backup su Drive: per ogni nota il file su Drive e la versione caricata. */
private class StatoDrive(
    var account: String? = null,
    var cartella: String? = null,
    var indiceId: String? = null,
    var indiceImpronta: String? = null,
    val note: MutableMap<String, Voce> = LinkedHashMap(),
) {
    data class Voce(val fileId: String, val modificata: Long, val nome: String)

    fun azzera() {
        cartella = null
        indiceId = null
        indiceImpronta = null
        note.clear()
    }

    fun salva(f: File) {
        val n = JSONObject()
        for ((id, v) in note) n.put(id, JSONObject().put("file", v.fileId).put("modificata", v.modificata).put("nome", v.nome))
        val j = JSONObject()
            .put("versione", 1)
            .put("account", account)
            .put("cartella", cartella)
            .put("indice", indiceId)
            .put("indiceImpronta", indiceImpronta)
            .put("note", n)
        scriviAtomico(f, j.toString().toByteArray())
    }

    companion object {
        fun leggi(f: File): StatoDrive {
            if (!f.isFile) return StatoDrive()
            val j = try { JSONObject(f.readText()) } catch (_: JSONException) { return StatoDrive() }
            val s = StatoDrive(j.testo("account"), j.testo("cartella"), j.testo("indice"), j.testo("indiceImpronta"))
            val n = j.optJSONObject("note") ?: return s
            n.keys().forEach { id ->
                val v = n.optJSONObject(id) ?: return@forEach
                s.note[id] = Voce(v.optString("file"), v.optLong("modificata"), v.optString("nome"))
            }
            return s
        }
    }
}

/** Attende un Task di Play services senza la libreria kotlinx-coroutines-play-services. */
private suspend fun <T> Task<T>.attendi(): T = suspendCancellableCoroutine { c ->
    addOnCompleteListener { t ->
        val e = t.exception
        when {
            e != null -> c.resumeWithException(e)
            t.isCanceled -> c.cancel()
            else -> c.resume(t.result)
        }
    }
}
