package it.frumorn.tratto.backup

import android.content.Context

/**
 * Dove va il backup automatico di [BackupWorker] nella versione completa: Google Drive.
 * La versione libera ha un oggetto con lo stesso nome che scrive in una cartella.
 */
internal object DestinazioneBackup {
    /** Si carica su Internet: il lavoro in background aspetta la rete. */
    const val USA_RETE = true

    /** C'e' un account Google collegato. */
    fun collegata(s: StatoBackup): Boolean = s.account != null

    suspend fun esegui(context: Context) {
        DriveBackup(context).sincronizza()
    }

    /** Rete assente, Drive sovraccarico...; non se Google vuole di nuovo il consenso (AccessoNecessario). */
    fun daRiprovare(e: Throwable): Boolean = e.temporaneo()
}
