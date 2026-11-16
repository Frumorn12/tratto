package it.frumorn.tratto.backup

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import it.frumorn.tratto.R
import it.frumorn.tratto.ui.testoOra
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Collegamento a Google Drive da Compose: restituisce la funzione da chiamare al clic su "Collega".
 * Chiede l'autorizzazione e, se serve, apre la schermata di consenso di Google.
 * [alTermine] riceve successo (l'account e' in [BackupSettings]) o l'errore, col messaggio da mostrare.
 */
@Composable
fun rememberAccessoDrive(conDocumenti: Boolean = false, alTermine: (Result<Unit>) -> Unit): () -> Unit {
    val context = LocalContext.current
    val drive = remember(context, conDocumenti) { DriveBackup(context, conDocumenti) }
    val scope = rememberCoroutineScope()
    val esito by rememberUpdatedState(alTermine)

    suspend fun prova(azione: suspend () -> Unit) {
        try {
            azione()
            esito(Result.success(Unit))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            esito(Result.failure(e))
        }
    }

    /**
     * Dopo il selettore account / schermata di consenso, a volte arriva RESULT_CANCELED
     * anche se Google ha registrato la scelta. Si ritenta [autorizza] senza UI:
     * se non serve più risoluzione, la connessione e' OK.
     */
    suspend fun dopoSchermataConsenso(data: Intent?) {
        if (data != null) {
            try {
                drive.completaAutorizzazione(data)
                esito(Result.success(Unit))
                return
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Si ritenta sotto.
            }
        }
        when (val r = drive.autorizza()) {
            is DriveBackup.Risultato.Autorizzato -> esito(Result.success(Unit))
            is DriveBackup.Risultato.ServeConsenso ->
                esito(Result.failure(IOException(testoOra(R.string.google_access_cancelled))))
        }
    }

    val lanciatore = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        scope.launch {
            try {
                if (r.resultCode == Activity.RESULT_OK && r.data != null) {
                    prova { drive.completaAutorizzazione(r.data!!) }
                } else {
                    dopoSchermataConsenso(r.data)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                esito(Result.failure(e))
            }
        }
    }
    return remember(drive, lanciatore) {
        {
            scope.launch {
                try {
                    when (val r = drive.autorizza()) {
                        is DriveBackup.Risultato.Autorizzato -> esito(Result.success(Unit))
                        is DriveBackup.Risultato.ServeConsenso ->
                            lanciatore.launch(IntentSenderRequest.Builder(r.pendingIntent).build())
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    esito(Result.failure(e))
                }
            }
        }
    }
}
