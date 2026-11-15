package it.frumorn.tratto.backup

import android.content.Context
import android.net.Uri
import it.frumorn.tratto.data.Archive

/**
 * Compatibilité version libre : délègue à [AutoExport] (même dossier SAF + historique).
 */
internal object FolderBackup {
    const val DA_TENERE = AutoExport.DA_TENERE

    fun scegli(context: Context, albero: Uri) = AutoExport.scegli(context, albero)

    fun scollega(context: Context) = AutoExport.scollega(context)

    suspend fun esegui(context: Context, archivio: Archive? = null) {
        if (archivio != null) AutoExport.esegui(context, archivio) else AutoExport.esegui(context)
    }

    fun documentoCartella(albero: Uri): Uri = AutoExport.documentoCartella(albero)
}
