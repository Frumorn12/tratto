package it.frumorn.tratto.ui.settings

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
import it.frumorn.tratto.backup.BackupSettings
import it.frumorn.tratto.backup.rememberAccessoDrive
import it.frumorn.tratto.ui.Icona
import it.frumorn.tratto.ui.AppState
import it.frumorn.tratto.ui.testo
import it.frumorn.tratto.ui.testoOra
import it.frumorn.tratto.ui.library.quando
import kotlinx.coroutines.launch

/**
 * Backup su Google Drive, sotto il backup su file di [BackupSection]. Solo nella versione
 * completa: quella libera qui ha il backup automatico in una cartella. [lavoro] e' il messaggio
 * dell'operazione in corso, condiviso con il backup su file.
 */
@Composable
internal fun CloudSection(stato: AppState, lavoro: String?, impostaLavoro: (String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drive by remember { BackupSettings.stato(context) }.collectAsState()

    fun avviso(t: String) = Toast.makeText(context, t, Toast.LENGTH_LONG).show()

    val collega = rememberAccessoDrive { r ->
        r.onSuccess { avviso(testoOra(R.string.google_drive_connected)) }
            .onFailure { avviso(it.message ?: testoOra(R.string.could_not_connect)) }
    }

    Text("Google Drive", style = MaterialTheme.typography.titleSmall)
    val descrizione = when {
        drive.account == null -> testo(R.string.not_connected_tratto_only_sees_the_files_it_crea)
        drive.inCorso -> testo(R.string.backup_in_progress)
        drive.serveAccesso -> testo(R.string.access_needs_to_be_granted_again_tap_connect)
        drive.errore != null -> testo(R.string.last_attempt_failed_2, drive.errore)
        drive.ultimoBackup != null -> testo(R.string.connected_to_last_backup, drive.account, quando(drive.ultimoBackup!!))
        else -> testo(R.string.connected_to_no_backup_yet, drive.account)
    }
    Text(descrizione, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        if (drive.account == null || drive.serveAccesso) {
            Button(onClick = collega) {
                Icona(R.drawable.ic_cloud_upload, null, dimensione = 18.dp); Spacer(Modifier.width(8.dp)); Text(testo(R.string.connect_google_drive))
            }
        } else {
            Button(onClick = { BackupWorker.backupOra(context); avviso(testoOra(R.string.drive_backup_started)) }, enabled = !drive.inCorso) {
                Icona(R.drawable.ic_cloud_upload, null, dimensione = 18.dp); Spacer(Modifier.width(8.dp)); Text(testo(R.string.back_up_now))
            }
            OutlinedButton(onClick = {
                scope.launch {
                    impostaLavoro(testoOra(R.string.downloading_notes_from_drive))
                    runCatching { DriveBackup(context).ripristinaDaDrive(stato.archivio) }
                        .onSuccess { avviso(testoOra(R.string.notes_restored_from_drive)); stato.versioneAnteprime++ }
                        .onFailure { avviso(it.message ?: testoOra(R.string.restore_failed)) }
                    impostaLavoro(null)
                }
            }, enabled = !drive.inCorso && lavoro == null) { Text(testo(R.string.restore_from_drive)) }
        }
    }
    if (drive.account != null) {
        Interruttore(testo(R.string.automatic_backup_every_day), drive.automatico) { BackupSettings.impostaAutomatico(context, it) }
        Interruttore(testo(R.string.wi_fi_only), drive.soloWifi) { BackupSettings.impostaSoloWifi(context, it) }
        Interruttore(testo(R.string.only_while_charging), drive.soloInCarica) { BackupSettings.impostaSoloInCarica(context, it) }
        TextButton(onClick = { scope.launch { runCatching { DriveBackup(context).disconnetti() }; avviso(testoOra(R.string.google_drive_disconnected)) } }) {
            Text(testo(R.string.disconnect_google_drive), color = MaterialTheme.colorScheme.error)
        }
    }
}
