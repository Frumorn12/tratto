package it.frumorn.tratto.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import it.frumorn.tratto.R
import it.frumorn.tratto.backup.AutoExport
import it.frumorn.tratto.backup.BackupSettings
import it.frumorn.tratto.backup.LocalBackup
import it.frumorn.tratto.backup.ModoRipristino
import it.frumorn.tratto.backup.VoceArchivio
import it.frumorn.tratto.ui.Icona
import it.frumorn.tratto.ui.AppState
import it.frumorn.tratto.ui.testo
import it.frumorn.tratto.ui.testoOra
import it.frumorn.tratto.ui.library.quando
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Backup su file, auto-export + historique dans un dossier SAF, puis [CloudSection]
 * (Drive en completa, vide en libera).
 */
@Composable
fun BackupSection(stato: AppState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var daRipristinare by remember { mutableStateOf<android.net.Uri?>(null) }
    var lavoro by remember { mutableStateOf<String?>(null) }
    val backup by remember { BackupSettings.stato(context) }.collectAsState()
    var archivi by remember { mutableStateOf<List<VoceArchivio>>(emptyList()) }
    var ripristinaArchivio by remember { mutableStateOf<VoceArchivio?>(null) }

    fun avviso(t: String) = Toast.makeText(context, t, Toast.LENGTH_LONG).show()

    fun ricaricaArchivi() {
        scope.launch {
            archivi = withContext(Dispatchers.IO) { AutoExport.elenco(context) }
        }
    }

    LaunchedEffect(backup.cartella, backup.ultimoBackup) {
        if (backup.cartella != null) ricaricaArchivi() else archivi = emptyList()
    }

    val esporta = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(LocalBackup.MIME)) { uri ->
        if (uri != null) scope.launch {
            lavoro = testoOra(R.string.saving_the_backup)
            runCatching {
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")!!.use { LocalBackup.esporta(stato.archivio, it) } }
            }.onSuccess { avviso(testoOra(R.string.backup_saved_with_notes, stato.archivio.note.value.size)) }
                .onFailure { avviso(testoOra(R.string.backup_failed, it.message)) }
            lavoro = null
        }
    }
    val apri = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> daRipristinare = uri }

    val scegliCartella = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { AutoExport.scegli(context, uri) } }
                .onSuccess {
                    avviso(testoOra(R.string.backup_folder_chosen))
                    ricaricaArchivi()
                }
                .onFailure { avviso(it.message ?: testoOra(R.string.this_folder_cannot_be_used)) }
        } else if (backup.cartella == null) {
            BackupSettings.impostaAutoExport(context, false)
        }
    }

    Text(
        testo(R.string.saves_every_note_in_one_tratto_file_kept_whereve),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        Button(onClick = { esporta.launch(LocalBackup.nomeFile()) }, enabled = lavoro == null) {
            Icona(R.drawable.ic_backup, null, dimensione = 18.dp); Spacer(Modifier.width(8.dp)); Text(testo(R.string.export_backup))
        }
        OutlinedButton(onClick = { apri.launch(arrayOf("*/*")) }, enabled = lavoro == null) {
            Icona(R.drawable.ic_history, null, dimensione = 18.dp); Spacer(Modifier.width(8.dp)); Text(testo(R.string.restore_from_a_file))
        }
    }
    lavoro?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }

    Spacer(Modifier.height(16.dp))
    Text(testo(R.string.auto_export), style = MaterialTheme.typography.titleSmall)
    Text(
        testo(R.string.auto_export_help, AutoExport.DA_TENERE),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Interruttore(testo(R.string.auto_export), backup.autoExport) { attivo ->
        if (attivo) {
            if (backup.cartella == null) scegliCartella.launch(null)
            else BackupSettings.impostaAutoExport(context, true)
        } else {
            BackupSettings.impostaAutoExport(context, false)
        }
    }
    if (backup.cartella != null) {
        Text(testo(R.string.folder_2, backup.nomeCartella ?: testo(R.string.unnamed)), style = MaterialTheme.typography.bodyMedium)
        when {
            backup.inCorso -> Text(testo(R.string.backup_in_progress), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            backup.errore != null && backup.autoExport ->
                Text(testo(R.string.last_attempt_failed, backup.errore), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            backup.ultimoBackup != null ->
                Text(testo(R.string.last_backup, quando(backup.ultimoBackup!!)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            OutlinedButton(
                onClick = { scegliCartella.launch(backup.cartella?.let { AutoExport.documentoCartella(it.toUri()) }) },
            ) {
                Icona(R.drawable.ic_folder_open, null, dimensione = 18.dp); Spacer(Modifier.width(8.dp)); Text(testo(R.string.change_folder))
            }
            TextButton(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { AutoExport.scollega(context) }
                    archivi = emptyList()
                    avviso(testoOra(R.string.folder_disconnected))
                }
            }) {
                Text(testo(R.string.disconnect_the_folder), color = MaterialTheme.colorScheme.error)
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(testo(R.string.auto_export_history), style = MaterialTheme.typography.titleSmall)
        if (archivi.isEmpty()) {
            Text(
                testo(R.string.auto_export_no_archives),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            archivi.take(15).forEach { voce ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(voce.nome, style = MaterialTheme.typography.bodyMedium)
                        if (voce.modificato > 0) {
                            Text(
                                quando(voce.modificato),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    TextButton(onClick = { ripristinaArchivio = voce }, enabled = lavoro == null) {
                        Text(testo(R.string.auto_export_restore))
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    CloudSection(stato, lavoro) { lavoro = it }

    daRipristinare?.let { uri ->
        DialogoRipristino(
            onChiudi = { daRipristinare = null },
            onModo = { modo ->
                daRipristinare = null
                scope.launch {
                    lavoro = testoOra(R.string.restoring)
                    runCatching {
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openInputStream(uri)!!.use { LocalBackup.ripristina(stato.archivio, it, modo) }
                        }
                    }.onSuccess { r ->
                        val copie = if (r.copie > 0) testoOra(R.string.restore_copies_suffix, r.copie) else ""
                        avviso(testoOra(R.string.restore_finished, r.aggiunte, r.aggiornate, copie))
                        stato.versioneAnteprime++
                    }.onFailure { avviso(it.message ?: testoOra(R.string.restore_failed)) }
                    lavoro = null
                }
            },
        )
    }

    ripristinaArchivio?.let { voce ->
        DialogoRipristino(
            onChiudi = { ripristinaArchivio = null },
            onModo = { modo ->
                ripristinaArchivio = null
                scope.launch {
                    lavoro = testoOra(R.string.restoring)
                    runCatching {
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openInputStream(voce.uri)!!.use { LocalBackup.ripristina(stato.archivio, it, modo) }
                        }
                    }.onSuccess { r ->
                        val copie = if (r.copie > 0) testoOra(R.string.restore_copies_suffix, r.copie) else ""
                        avviso(testoOra(R.string.restore_finished, r.aggiunte, r.aggiornate, copie))
                        stato.versioneAnteprime++
                    }.onFailure { avviso(it.message ?: testoOra(R.string.restore_failed)) }
                    lavoro = null
                }
            },
        )
    }
}

@Composable
private fun DialogoRipristino(onChiudi: () -> Unit, onModo: (ModoRipristino) -> Unit) {
    AlertDialog(
        onDismissRequest = onChiudi,
        title = { Text(testo(R.string.restore_the_backup)) },
        text = { Text(testo(R.string.restore_merge_replace_help)) },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                listOf(ModoRipristino.UNISCI to testo(R.string.merge), ModoRipristino.SOSTITUISCI to testo(R.string.replace)).forEach { (modo, nome) ->
                    TextButton(onClick = { onModo(modo) }) {
                        Text(nome, color = if (modo == ModoRipristino.SOSTITUISCI) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        dismissButton = { TextButton(onChiudi) { Text(testo(R.string.cancel)) } },
    )
}

/** Anche per [CloudSection] dei due flavor. */
@Composable
internal fun Interruttore(testo: String, valore: Boolean, onCambio: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(testo, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        androidx.compose.material3.Switch(valore, onCambio)
    }
}
