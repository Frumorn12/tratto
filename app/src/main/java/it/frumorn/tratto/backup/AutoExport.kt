package it.frumorn.tratto.backup

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.core.net.toUri
import it.frumorn.tratto.R
import it.frumorn.tratto.TrattoApp
import it.frumorn.tratto.data.Archive
import it.frumorn.tratto.ui.testoOra
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** La cartella scelta non si puo' piu' usare (permesso tolto, cartella cancellata): va scelta di nuovo. */
class CartellaNonRaggiungibile(messaggio: String, causa: Throwable? = null) : IOException(messaggio, causa)

/** Una voce dell'historique d'archives dans le dossier d'auto-export. */
data class VoceArchivio(
    val uri: Uri,
    val nome: String,
    /** Clé de tri (date dans le nom). */
    val data: String,
    val modificato: Long,
)

/**
 * Auto-export vers un dossier SAF (tablette, SD, Drive/Nextcloud via le sélecteur système) :
 * à chaque changement pertinent, une archive `.tratto` horodatée ; on en garde [DA_TENERE].
 */
object AutoExport {
    const val DA_TENERE = 30

    private const val PERMESSI = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    private fun senzaPermesso() = testoOra(R.string.tratto_can_no_longer_write_in_the_backup_folder)
    private fun sparita() = testoOra(R.string.the_backup_folder_no_longer_exists_choose_it_aga)

    /** Accepte l'ancien format (HH.mm) et le nouveau (HH.mm.ss). */
    private val NOME_BACKUP =
        Regex("""Tratto (\d{4}-\d{2}-\d{2} \d{2}\.\d{2}(?:\.\d{2})?)(?: \(\d+\))?\.${LocalBackup.ESTENSIONE}""")

    private val mutex = Mutex()

    fun attivo(context: Context): Boolean {
        val s = BackupSettings.stato(context).value
        return s.autoExport && s.cartella != null
    }

    /** Signale un changement : planifie un export (debounce) ou immédiat. */
    fun segnalaCambio(context: Context, subito: Boolean = false) {
        if (!attivo(context)) return
        if (subito) AutoExportWorker.subito(context) else AutoExportWorker.pianifica(context)
    }

    fun scegli(context: Context, albero: Uri) {
        val app = context.applicationContext
        val cr = app.contentResolver
        try {
            cr.takePersistableUriPermission(albero, PERMESSI)
        } catch (e: SecurityException) {
            throw IOException(testoOra(R.string.this_folder_cannot_be_used_for_automatic_backup), e)
        }
        val vecchia = BackupSettings.stato(app).value.cartella
        if (vecchia != null && vecchia != albero.toString()) rilascia(cr, vecchia)
        BackupSettings.cartellaScelta(app, albero.toString(), nome(cr, albero), autoExport = true)
    }

    fun scollega(context: Context) {
        val app = context.applicationContext
        BackupSettings.stato(app).value.cartella?.let { rilascia(app.contentResolver, it) }
        BackupSettings.scollegaCartella(app)
        AutoExportWorker.annulla(app)
    }

    fun documentoCartella(albero: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(albero, DocumentsContract.getTreeDocumentId(albero))

    fun nomeArchivio(ora: Long = System.currentTimeMillis()): String =
        "Tratto " + SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.ITALY).format(Date(ora)) + ".${LocalBackup.ESTENSIONE}"

    /** Liste les archives Tratto du dossier (plus récentes en premier). */
    fun elenco(context: Context): List<VoceArchivio> {
        val app = context.applicationContext
        val albero = BackupSettings.stato(app).value.cartella?.toUri() ?: return emptyList()
        return elenco(app.contentResolver, albero)
    }

    suspend fun esegui(context: Context, archivio: Archive = TrattoApp.archivio(context)) = mutex.withLock {
        val app = context.applicationContext
        withContext(Dispatchers.IO) {
            BackupSettings.inizio(app)
            try {
                val albero = BackupSettings.stato(app).value.cartella?.toUri()
                    ?: throw CartellaNonRaggiungibile(testoOra(R.string.no_folder_chosen_for_backup))
                scrivi(app.contentResolver, albero, archivio)
                pulisci(app.contentResolver, albero)
                BackupSettings.riuscito(app, System.currentTimeMillis())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BackupSettings.errore(app, messaggio(e))
                throw e
            } finally {
                BackupSettings.fine(app)
            }
        }
    }

    private suspend fun scrivi(cr: ContentResolver, albero: Uri, archivio: Archive) {
        if (cr.persistedUriPermissions.none { it.uri == albero && it.isWritePermission }) {
            throw CartellaNonRaggiungibile(senzaPermesso())
        }
        val file = try {
            DocumentsContract.createDocument(cr, documentoCartella(albero), LocalBackup.MIME, nomeArchivio())
                ?: throw CartellaNonRaggiungibile(sparita())
        } catch (e: FileNotFoundException) {
            throw CartellaNonRaggiungibile(sparita(), e)
        } catch (e: IllegalArgumentException) {
            throw CartellaNonRaggiungibile(sparita(), e)
        }
        try {
            val out = cr.openOutputStream(file, "wt") ?: throw IOException(testoOra(R.string.could_not_write_the_backup_file))
            out.use { LocalBackup.esporta(archivio, it) }
        } catch (e: Throwable) {
            withContext(NonCancellable) { runCatching { DocumentsContract.deleteDocument(cr, file) } }
            throw e
        }
    }

    private fun elenco(cr: ContentResolver, albero: Uri): List<VoceArchivio> {
        val figli = DocumentsContract.buildChildDocumentsUriUsingTree(albero, DocumentsContract.getTreeDocumentId(albero))
        val colonne = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_MIME_TYPE)
        val backup = ArrayList<VoceArchivio>()
        cr.query(figli, colonne, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(3) == Document.MIME_TYPE_DIR) continue
                val nome = c.getString(1) ?: continue
                val m = NOME_BACKUP.matchEntire(nome) ?: continue
                val id = c.getString(0)
                backup += VoceArchivio(
                    uri = DocumentsContract.buildDocumentUriUsingTree(albero, id),
                    nome = nome,
                    data = m.groupValues[1],
                    modificato = c.getLong(2),
                )
            }
        }
        return backup.sortedWith(compareByDescending<VoceArchivio> { it.data }.thenByDescending { it.modificato })
    }

    private fun pulisci(cr: ContentResolver, albero: Uri) {
        elenco(cr, albero).drop(DA_TENERE).forEach { v ->
            runCatching { DocumentsContract.deleteDocument(cr, v.uri) }
        }
    }

    private fun nome(cr: ContentResolver, albero: Uri): String? = runCatching {
        cr.query(documentoCartella(albero), arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun rilascia(cr: ContentResolver, uri: String) {
        runCatching { cr.releasePersistableUriPermission(uri.toUri(), PERMESSI) }
    }

    private fun messaggio(e: Exception): String = when (e) {
        is SecurityException -> senzaPermesso()
        else -> e.message ?: testoOra(R.string.unknown_error_during_backup)
    }
}
