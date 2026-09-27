package it.frumorn.tratto.ui.impostazioni

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
import it.frumorn.tratto.backup.BackupCartella
import it.frumorn.tratto.backup.BackupWorker
import it.frumorn.tratto.backup.ImpostazioniBackup
import it.frumorn.tratto.ui.Icona
import it.frumorn.tratto.ui.StatoApp
import it.frumorn.tratto.ui.libreria.quando
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Backup automatico in una cartella scelta con il selettore di sistema: nella versione libera
 * prende il posto di Google Drive (stessa firma di quella della versione completa, per questo
 * [stato] e [impostaLavoro] qui non servono). Si ripristina con «Ripristina da file».
 */
@Composable
internal fun SezioneCloud(stato: StatoApp, lavoro: String?, impostaLavoro: (String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backup by remember { ImpostazioniBackup.stato(context) }.collectAsState()

    fun avviso(t: String) = Toast.makeText(context, t, Toast.LENGTH_LONG).show()

    val scegli = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { BackupCartella.scegli(context, uri) } }
                .onSuccess { avviso("Cartella del backup scelta") }
                .onFailure { avviso(it.message ?: "Non riesco a usare questa cartella") }
        }
    }

    Text("Backup automatico in una cartella", style = MaterialTheme.typography.titleSmall)
    if (backup.cartella != null) {
        Text("Cartella: «${backup.nomeCartella ?: "senza nome"}»", style = MaterialTheme.typography.bodyMedium)
    }
    val descrizione = when {
        backup.cartella == null ->
            "Nessuna cartella scelta. Tratto ci salva un file .tratto, anche ogni giorno, e tiene gli ultimi ${BackupCartella.DA_TENERE}. " +
                "Va bene anche la cartella di un'app che sincronizza, come Nextcloud o Syncthing. Per ripristinare usa «Ripristina da file»."
        backup.inCorso -> "Backup in corso…"
        backup.errore != null -> "Ultimo tentativo non riuscito: ${backup.errore}"
        backup.ultimoBackup != null -> "Ultimo backup: ${quando(backup.ultimoBackup!!)}"
        else -> "Nessun backup ancora."
    }
    Text(descrizione, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        if (backup.cartella == null) {
            Button(onClick = { scegli.launch(null) }) {
                Icona(R.drawable.ic_folder_open, null, dimensione = 18.dp); Spacer(Modifier.width(8.dp)); Text("Scegli la cartella")
            }
        } else {
            Button(onClick = { BackupWorker.backupOra(context); avviso("Backup avviato") }, enabled = !backup.inCorso && lavoro == null) {
                Icona(R.drawable.ic_backup, null, dimensione = 18.dp); Spacer(Modifier.width(8.dp)); Text("Esegui backup ora")
            }
            OutlinedButton(onClick = { scegli.launch(backup.cartella?.let { BackupCartella.documentoCartella(it.toUri()) }) }) {
                Icona(R.drawable.ic_folder_open, null, dimensione = 18.dp); Spacer(Modifier.width(8.dp)); Text("Cambia cartella")
            }
        }
    }
    if (backup.cartella != null) {
        Interruttore("Backup automatico ogni giorno", backup.automatico) { ImpostazioniBackup.impostaAutomatico(context, it) }
        Interruttore("Solo con il tablet in carica", backup.soloInCarica) { ImpostazioniBackup.impostaSoloInCarica(context, it) }
        TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { BackupCartella.scollega(context) }; avviso("Cartella scollegata") } }) {
            Text("Scollega cartella", color = MaterialTheme.colorScheme.error)
        }
    }
}
