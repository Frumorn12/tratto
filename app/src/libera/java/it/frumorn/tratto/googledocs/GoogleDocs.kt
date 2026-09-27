package it.frumorn.tratto.googledocs

import android.content.Context

/**
 * Collegamento a Google Docs della versione libera: non c'e' (serve l'accesso all'account Google
 * delle librerie Play services). Stessa interfaccia usata dall'editor nella versione completa, ma
 * non fa niente, e la UI non mostra le sue voci ([DISPONIBILE] falso).
 */
object GoogleDocs {
    /** Il collegamento a Google Docs non c'e' in questa versione dell'app. */
    const val DISPONIBILE = false

    /** Nessuna nota e' collegata: non c'e' niente da aggiornare. */
    fun modificata(context: Context, notaId: String) = Unit

    fun finisciInBackground(context: Context, notaId: String, cambiata: Boolean) = Unit
}
