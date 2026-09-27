package it.frumorn.tratto.backup

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Collegamento a Google Drive da Compose: restituisce la funzione da chiamare al clic su "Collega".
 * Chiede l'autorizzazione e, se serve, apre la schermata di consenso di Google.
 * [alTermine] riceve successo (l'account e' in [ImpostazioniBackup]) o l'errore, col messaggio da mostrare.
 */
@Composable
fun rememberAccessoDrive(alTermine: (Result<Unit>) -> Unit): () -> Unit {
    val context = LocalContext.current
    val drive = remember(context) { DriveBackup(context) }
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

    val lanciatore = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) {
            scope.launch { prova { drive.completaAutorizzazione(data) } }
        } else {
            esito(Result.failure(IOException("Collegamento a Google Drive annullato.")))
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
