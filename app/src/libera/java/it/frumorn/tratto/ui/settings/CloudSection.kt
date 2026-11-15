package it.frumorn.tratto.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.net.toUri
import it.frumorn.tratto.R
import it.frumorn.tratto.backup.FolderBackup
import it.frumorn.tratto.backup.BackupWorker
import it.frumorn.tratto.backup.BackupSettings
import it.frumorn.tratto.ui.Icona
import it.frumorn.tratto.ui.AppState
import it.frumorn.tratto.ui.testo
import it.frumorn.tratto.ui.testoOra
import it.frumorn.tratto.ui.library.quando
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Backup automatico in una cartella scelta con il selettore di sistema: nella versione libera
 * prende il posto di Google Drive (stessa firma di quella della versione completa, per questo
 * [stato] e [impostaLavoro] qui non servono). Si ripristina con «Ripristina da file».
 */
@Composable
internal fun CloudSection(stato: AppState, lavoro: String?, impostaLavoro: (String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backup by remember { BackupSettings.stato(context) }.collectAsState()

    fun avviso(t: String) = Toast.makeText(context, t, Toast.LENGTH_LONG).show()

    val scegli = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { FolderBackup.scegli(context, uri) } }
                .onSuccess { avviso(testoOra(R.string.backup_folder_chosen)) }
                .onFailure { avviso(it.message ?: testoOra(R.string.this_folder_cannot_be_used)) }
        }
    }

    Text(testo(R.string.automatic_backup_to_a_folder), style = MaterialTheme.typography.titleSmall)
    if (backup.cartella != null) {
        Text(testo(R.string.folder_2, backup.nomeCartella ?: testo(R.string.unnamed)), style = MaterialTheme.typography.bodyMedium)
    }
    val descrizione = when {
        backup.cartella == null -> testo(R.string.folder_backup_no_folder, FolderBackup.DA_TENERE)
        backup.inCorso -> testo(R.string.backup_in_progress)
        backup.errore != null -> testo(R.string.last_attempt_failed, backup.errore)
        backup.ultimoBackup != null -> testo(R.string.last_backup, quando(backup.ultimoBackup!!))
        else -> testo(R.string.no_backup_yet)
    }
    Text(descrizione, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        if (backup.cartella == null) {
            Button(onClick = { scegli.launch(null) }) {
                Icona(R.drawable.ic_folder_open, null, dimensione = 18.dp); Spacer(Modifier.width(8.dp)); Text(testo(R.string.choose_the_folder))
            }
        } else {
            Button(onClick = { BackupWorker.backupOra(context); avviso(testoOra(R.string.backup_started)) }, enabled = !backup.inCorso && lavoro == null) {
                Icona(R.drawable.ic_backup, null, dimensione = 18.dp); Spacer(Modifier.width(8.dp)); Text(testo(R.string.back_up_now))
            }
            OutlinedButton(onClick = { scegli.launch(backup.cartella?.let { FolderBackup.documentoCartella(it.toUri()) }) }) {
                Icona(R.drawable.ic_folder_open, null, dimensione = 18.dp); Spacer(Modifier.width(8.dp)); Text(testo(R.string.change_folder))
            }
        }
    }
    if (backup.cartella != null) {
        Interruttore(testo(R.string.automatic_backup_every_day), backup.automatico) { BackupSettings.impostaAutomatico(context, it) }
        Interruttore(testo(R.string.only_while_charging), backup.soloInCarica) { BackupSettings.impostaSoloInCarica(context, it) }
        TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { FolderBackup.scollega(context) }; avviso(testoOra(R.string.folder_disconnected)) } }) {
            Text(testo(R.string.disconnect_the_folder), color = MaterialTheme.colorScheme.error)
        }
    }
}
