package it.frumorn.tratto.backup

import android.content.Context
import java.io.IOException

/**
 * Backup périodique (si activé) et destination dossier : [AutoExport].
 */
internal object BackupDestination {
    const val USA_RETE = false

    fun collegata(s: StatoBackup): Boolean = s.cartella != null

    suspend fun esegui(context: Context) {
        AutoExport.esegui(context)
    }

    fun daRiprovare(e: Throwable): Boolean = e is IOException && e !is CartellaNonRaggiungibile && e !is BackupNonValido
}
