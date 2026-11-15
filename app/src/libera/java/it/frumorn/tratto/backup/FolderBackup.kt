package it.frumorn.tratto.backup

import it.frumorn.tratto.R

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.core.net.toUri
import it.frumorn.tratto.TrattoApp
import it.frumorn.tratto.ui.testoOra
import it.frumorn.tratto.data.Archive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException

/** La cartella scelta non si puo' piu' usare (permesso tolto, cartella cancellata): va scelta di nuovo. */
internal class CartellaNonRaggiungibile(messaggio: String, causa: Throwable? = null) : IOException(messaggio, causa)

/**
 * Backup automatico della versione libera, al posto di Google Drive: un file .tratto (lo stesso di
 * "Esporta backup", [LocalBackup.esporta]) in una cartella scelta con il selettore di sistema
 * (Storage Access Framework, ACTION_OPEN_DOCUMENT_TREE). Puo' essere una cartella del tablet, una
 * scheda SD o la cartella di un'app che sincronizza da sola (Nextcloud, Syncthing...): Tratto non
 * usa la rete. Nella cartella restano solo gli ultimi [DA_TENERE] backup di Tratto.
 *
 * Il permesso sulla cartella si rende persistente, cosi' il worker ci scrive anche dopo un riavvio.
 * Si usa DocumentsContract direttamente: basta una query per elencare i file della cartella.
 */
internal object FolderBackup {
    const val DA_TENERE = 5

    private const val PERMESSI = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    private fun senzaPermesso() = testoOra(R.string.tratto_can_no_longer_write_in_the_backup_folder)
    private fun sparita() = testoOra(R.string.the_backup_folder_no_longer_exists_choose_it_aga)

    /**
     * I backup di Tratto: nomi di [LocalBackup.nomeFile], anche con " (1)" se il provider ha
     * trovato il nome gia' usato. Il gruppo 1 e' la data, che ordina i backup anche se i file sono
     * stati copiati (e la data di modifica e' cambiata).
     */
    private val NOME_BACKUP = Regex("""Tratto (\d{4}-\d{2}-\d{2} \d{2}\.\d{2})(?: \(\d+\))?\.${LocalBackup.ESTENSIONE}""")

    /** Un backup alla volta: il worker di "Esegui backup ora" e quello giornaliero possono sovrapporsi. */
    private val mutex = Mutex()

    /** Usa [albero] (risultato di OpenDocumentTree) per i backup automatici. Fa I/O: fuori dal main thread. */
    fun scegli(context: Context, albero: Uri) {
        val app = context.applicationContext
        val cr = app.contentResolver
        try {
            cr.takePersistableUriPermission(albero, PERMESSI)
        } catch (e: SecurityException) {
            // Alcuni provider non danno permessi che sopravvivono al riavvio: il worker non potrebbe scriverci.
            throw IOException(testoOra(R.string.this_folder_cannot_be_used_for_automatic_backup), e)
        }
        val vecchia = BackupSettings.stato(app).value.cartella
        if (vecchia != null && vecchia != albero.toString()) rilascia(cr, vecchia)
        BackupSettings.cartellaScelta(app, albero.toString(), nome(cr, albero))
        BackupWorker.pianifica(app)
    }

    /** Dimentica la cartella e spegne il backup automatico. I backup gia' fatti restano dove sono. */
    fun scollega(context: Context) {
        val app = context.applicationContext
        BackupSettings.stato(app).value.cartella?.let { rilascia(app.contentResolver, it) }
        BackupSettings.scollegato(app)
        BackupWorker.pianifica(app)
    }

    /** Scrive un backup completo nella cartella scelta e cancella quelli oltre gli ultimi [DA_TENERE]. */
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
        if (cr.persistedUriPermissions.none { it.uri == albero && it.isWritePermission }) throw CartellaNonRaggiungibile(senzaPermesso())
        // Con il tipo generico il provider tiene il nome cosi' com'e', senza aggiungere estensioni.
        val file = try {
            DocumentsContract.createDocument(cr, documentoCartella(albero), LocalBackup.MIME, LocalBackup.nomeFile())
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
            // Un backup a meta' non deve restare tra gli ultimi da tenere.
            withContext(NonCancellable) { runCatching { DocumentsContract.deleteDocument(cr, file) } }
            throw e
        }
    }

    private class Voce(val id: String, val data: String, val modificato: Long)

    /** Cancella i backup di Tratto nella cartella oltre gli ultimi [DA_TENERE]. Gli altri file non si toccano. */
    private fun pulisci(cr: ContentResolver, albero: Uri) {
        val figli = DocumentsContract.buildChildDocumentsUriUsingTree(albero, DocumentsContract.getTreeDocumentId(albero))
        val colonne = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_MIME_TYPE)
        val backup = ArrayList<Voce>()
        cr.query(figli, colonne, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(3) == Document.MIME_TYPE_DIR) continue
                val m = NOME_BACKUP.matchEntire(c.getString(1) ?: continue) ?: continue
                backup += Voce(c.getString(0), m.groupValues[1], c.getLong(2))
            }
        }
        backup.sortedWith(compareByDescending<Voce> { it.data }.thenByDescending { it.modificato })
            .drop(DA_TENERE)
            .forEach { v -> runCatching { DocumentsContract.deleteDocument(cr, DocumentsContract.buildDocumentUriUsingTree(albero, v.id)) } }
    }

    /** Documento della cartella scelta, per crearci i file e come punto di partenza del selettore. */
    fun documentoCartella(albero: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(albero, DocumentsContract.getTreeDocumentId(albero))

    private fun nome(cr: ContentResolver, albero: Uri): String? = runCatching {
        cr.query(documentoCartella(albero), arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    /** Il permesso potrebbe non esserci piu' (tolto dal sistema): non e' un errore. */
    private fun rilascia(cr: ContentResolver, uri: String) {
        runCatching { cr.releasePersistableUriPermission(uri.toUri(), PERMESSI) }
    }

    private fun messaggio(e: Exception): String = when (e) {
        is SecurityException -> senzaPermesso()
        else -> e.message ?: testoOra(R.string.unknown_error_during_backup)
    }
}
