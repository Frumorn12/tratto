package it.frumorn.tratto.backup

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import it.frumorn.tratto.R
import it.frumorn.tratto.ui.testoOra
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Stato del backup automatico, per la UI: su Google Drive nella versione completa, in una cartella
 * scelta dall'utente nella versione libera (vedi DestinazioneBackup nei due flavor).
 */
data class StatoBackup(
    /** Ora (ms) dell'ultimo backup riuscito. */
    val ultimoBackup: Long? = null,
    /** Email dell'account Google collegato, null se non collegato. */
    val account: String? = null,
    /** Backup automatico giornaliero attivo. */
    val automatico: Boolean = false,
    /** Il backup automatico parte solo su rete non a consumo (Wi-Fi). */
    val soloWifi: Boolean = true,
    /** Un backup o un ripristino da Drive e' in corso adesso. */
    val inCorso: Boolean = false,
    /** Errore dell'ultimo tentativo, pronto da mostrare; null se e' andato bene. */
    val errore: String? = null,
    /** Il backup automatico parte solo col tablet in carica. */
    val soloInCarica: Boolean = false,
    /** L'accesso a Drive e' stato revocato o e' scaduto: serve di nuovo autorizza() dalla UI. */
    val serveAccesso: Boolean = false,
    /** URI (albero SAF) della cartella di auto-export / historique, null se non scelta. */
    val cartella: String? = null,
    /** Nome della [cartella] da mostrare. */
    val nomeCartella: String? = null,
    /** Auto-export actif : archive à chaque changement pertinent dans [cartella]. */
    val autoExport: Boolean = false,
)

/**
 * Impostazioni e stato del backup, salvati in SharedPreferences ("backup").
 * Il primo accesso legge le preferenze da disco: meglio non farlo nel percorso di avvio.
 */
object BackupSettings {
    private const val FILE = "backup"

    @Volatile private var flusso: MutableStateFlow<StatoBackup>? = null

    fun stato(context: Context): StateFlow<StatoBackup> = flusso(context)

    fun impostaAutomatico(context: Context, attivo: Boolean) {
        modifica(context) { it.copy(automatico = attivo) }
        BackupWorker.pianifica(context)
    }

    fun impostaSoloWifi(context: Context, attivo: Boolean) {
        modifica(context) { it.copy(soloWifi = attivo) }
        BackupWorker.pianifica(context)
    }

    fun impostaSoloInCarica(context: Context, attivo: Boolean) {
        modifica(context) { it.copy(soloInCarica = attivo) }
        BackupWorker.pianifica(context)
    }

    internal fun inizio(context: Context) = modifica(context) { it.copy(inCorso = true) }

    internal fun fine(context: Context) = modifica(context) { it.copy(inCorso = false) }

    internal fun riuscito(context: Context, ora: Long) =
        modifica(context) { it.copy(ultimoBackup = ora, errore = null, serveAccesso = false) }

    internal fun errore(context: Context, messaggio: String) = modifica(context) { it.copy(errore = messaggio) }

    internal fun serveAccesso(context: Context) =
        modifica(context) { it.copy(serveAccesso = true, errore = testoOra(R.string.google_drive_access_needed_again)) }

    internal fun collegato(context: Context, account: String?) = modifica(context) {
        it.copy(account = account ?: it.account, serveAccesso = false, errore = null)
    }

    fun impostaAutoExport(context: Context, attivo: Boolean) {
        modifica(context) { it.copy(autoExport = attivo) }
        if (!attivo) AutoExportWorker.annulla(context)
    }

    /** La cartella d'auto-export a changé (ou vient d'être choisie). */
    internal fun cartellaScelta(context: Context, uri: String, nome: String?, autoExport: Boolean = true) = modifica(context) { s ->
        s.copy(
            cartella = uri,
            nomeCartella = nome,
            autoExport = autoExport,
            errore = null,
            ultimoBackup = if (s.cartella == uri) s.ultimoBackup else null,
        )
    }

    /** Déconnecte Google Drive sans toucher à l'auto-export dossier. */
    internal fun scollegaDrive(context: Context) = modifica(context) {
        it.copy(account = null, automatico = false, serveAccesso = false, errore = null)
    }

    /** Oublie le dossier d'auto-export (Drive inchangé). */
    internal fun scollegaCartella(context: Context) = modifica(context) {
        it.copy(cartella = null, nomeCartella = null, autoExport = false, errore = null)
    }

    /** @deprecated Préférer [scollegaDrive] / [scollegaCartella]. */
    internal fun scollegato(context: Context) = modifica(context) {
        it.copy(
            account = null, cartella = null, nomeCartella = null, automatico = false,
            autoExport = false, serveAccesso = false, errore = null, ultimoBackup = null,
        )
    }

    @Synchronized
    private fun modifica(context: Context, f: (StatoBackup) -> StatoBackup) {
        val fl = flusso(context)
        val nuovo = f(fl.value)
        fl.value = nuovo
        salva(preferenze(context), nuovo)
    }

    private fun flusso(context: Context): MutableStateFlow<StatoBackup> =
        flusso ?: synchronized(this) {
            flusso ?: MutableStateFlow(leggi(preferenze(context))).also { flusso = it }
        }

    private fun preferenze(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun leggi(p: SharedPreferences) = StatoBackup(
        ultimoBackup = p.getLong("ultimo", 0L).takeIf { it > 0 },
        account = p.getString("account", null),
        automatico = p.getBoolean("automatico", false),
        soloWifi = p.getBoolean("soloWifi", true),
        inCorso = false, // non si salva: se l'app muore a meta' non deve restare "in corso"
        errore = p.getString("errore", null),
        soloInCarica = p.getBoolean("soloInCarica", false),
        serveAccesso = p.getBoolean("serveAccesso", false),
        cartella = p.getString("cartella", null),
        nomeCartella = p.getString("nomeCartella", null),
        // Ancienne libera : dossier choisi = auto-export activé par défaut.
        autoExport = p.getBoolean("autoExport", p.getString("cartella", null) != null),
    )

    private fun salva(p: SharedPreferences, s: StatoBackup) = p.edit {
        putLong("ultimo", s.ultimoBackup ?: 0L)
        putString("account", s.account)
        putBoolean("automatico", s.automatico)
        putBoolean("soloWifi", s.soloWifi)
        putString("errore", s.errore)
        putBoolean("soloInCarica", s.soloInCarica)
        putBoolean("serveAccesso", s.serveAccesso)
        putString("cartella", s.cartella)
        putString("nomeCartella", s.nomeCartella)
        putBoolean("autoExport", s.autoExport)
    }
}
