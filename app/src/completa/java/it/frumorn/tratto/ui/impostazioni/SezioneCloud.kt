package it.frumorn.tratto.ui.impostazioni

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import it.frumorn.tratto.R
import it.frumorn.tratto.backup.BackupWorker
import it.frumorn.tratto.backup.DriveBackup
import it.frumorn.tratto.backup.ImpostazioniBackup
import it.frumorn.tratto.backup.rememberAccessoDrive
import it.frumorn.tratto.ui.Icona
import it.frumorn.tratto.ui.StatoApp
import it.frumorn.tratto.ui.libreria.quando
import kotlinx.coroutines.launch

/**
 * Backup su Google Drive, sotto il backup su file di [SezioneBackup]. Solo nella versione
 * completa: quella libera qui ha il backup automatico in una cartella. [lavoro] e' il messaggio
 * dell'operazione in corso, condiviso con il backup su file.
 */
@Composable
internal fun SezioneCloud(stato: StatoApp, lavoro: String?, impostaLavoro: (String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drive by remember { ImpostazioniBackup.stato(context) }.collectAsState()

    fun avviso(t: String) = Toast.makeText(context, t, Toast.LENGTH_LONG).show()

    val collega = rememberAccessoDrive { r -> r.onSuccess { avviso("Google Drive collegato") }.onFailure { avviso(it.message ?: "Collegamento non riuscito") } }

    Text("Google Drive", style = MaterialTheme.typography.titleSmall)
    val descrizione = when {
        drive.account == null -> "Non collegato. Tratto vede solo i file che crea lui su Drive, nella cartella «Tratto»."
        drive.inCorso -> "Backup in corso…"
        drive.serveAccesso -> "Serve di nuovo l'accesso: tocca «Collega»."
        drive.errore != null -> "Ultimo tentativo non riuscito: ${drive.errore}"
        drive.ultimoBackup != null -> "Collegato a ${drive.account}. Ultimo backup: ${quando(drive.ultimoBackup!!)}"
        else -> "Collegato a ${drive.account}. Nessun backup ancora."
    }
    Text(descrizione, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        if (drive.account == null || drive.serveAccesso) {
            Button(onClick = collega) {
                Icona(R.drawable.ic_cloud_upload, null, dimensione = 18.dp); Spacer(Modifier.width(8.dp)); Text("Collega Google Drive")
            }
        } else {
            Button(onClick = { BackupWorker.backupOra(context); avviso("Backup su Drive avviato") }, enabled = !drive.inCorso) {
                Icona(R.drawable.ic_cloud_upload, null, dimensione = 18.dp); Spacer(Modifier.width(8.dp)); Text("Backup ora")
            }
            OutlinedButton(onClick = {
                scope.launch {
                    impostaLavoro("Scarico le note da Drive…")
                    runCatching { DriveBackup(context).ripristinaDaDrive(stato.archivio) }
                        .onSuccess { avviso("Note ripristinate da Drive"); stato.versioneAnteprime++ }
                        .onFailure { avviso(it.message ?: "Ripristino non riuscito") }
                    impostaLavoro(null)
                }
            }, enabled = !drive.inCorso && lavoro == null) { Text("Ripristina da Drive") }
        }
    }
    if (drive.account != null) {
        Interruttore("Backup automatico ogni giorno", drive.automatico) { ImpostazioniBackup.impostaAutomatico(context, it) }
        Interruttore("Solo con il Wi-Fi", drive.soloWifi) { ImpostazioniBackup.impostaSoloWifi(context, it) }
        Interruttore("Solo con il tablet in carica", drive.soloInCarica) { ImpostazioniBackup.impostaSoloInCarica(context, it) }
        TextButton(onClick = { scope.launch { runCatching { DriveBackup(context).disconnetti() }; avviso("Google Drive scollegato") } }) {
            Text("Scollega Google Drive", color = MaterialTheme.colorScheme.error)
        }
    }
}
