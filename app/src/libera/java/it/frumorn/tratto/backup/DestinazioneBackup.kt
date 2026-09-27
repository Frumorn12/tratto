package it.frumorn.tratto.backup

import android.content.Context
import java.io.IOException

/**
 * Dove va il backup automatico di [BackupWorker] nella versione libera: una cartella scelta
 * dall'utente ([BackupCartella]). La versione completa ha un oggetto con lo stesso nome per Drive.
 */
internal object DestinazioneBackup {
    /** La cartella e' sul tablet, o la sincronizza un'altra app: Tratto non usa la rete. */
    const val USA_RETE = false

    /** C'e' una cartella scelta. */
    fun collegata(s: StatoBackup): Boolean = s.cartella != null

    suspend fun esegui(context: Context) {
        BackupCartella.esegui(context)
    }

    /** Si riprova solo per errori di scrittura, non se la cartella non c'e' piu'. */
    fun daRiprovare(e: Throwable): Boolean = e is IOException && e !is CartellaNonRaggiungibile && e !is BackupNonValido
}
