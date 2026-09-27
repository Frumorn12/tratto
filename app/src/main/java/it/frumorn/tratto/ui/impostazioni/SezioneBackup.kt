package it.frumorn.tratto.ui.impostazioni

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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import it.frumorn.tratto.R
import it.frumorn.tratto.backup.BackupLocale
import it.frumorn.tratto.backup.BackupWorker
import it.frumorn.tratto.backup.DriveBackup
import it.frumorn.tratto.backup.ImpostazioniBackup
import it.frumorn.tratto.backup.ModoRipristino
import it.frumorn.tratto.backup.rememberAccessoDrive
import it.frumorn.tratto.ui.Icona
import it.frumorn.tratto.ui.StatoApp
import it.frumorn.tratto.ui.libreria.quando
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Backup su file e su Google Drive. */
@Composable
fun SezioneBackup(stato: StatoApp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drive by remember { ImpostazioniBackup.stato(context) }.collectAsState()
    var daRipristinare by remember { mutableStateOf<android.net.Uri?>(null) }
    var lavoro by remember { mutableStateOf<String?>(null) }

    fun avviso(t: String) = Toast.makeText(context, t, Toast.LENGTH_LONG).show()

    val esporta = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupLocale.MIME)) { uri ->
        if (uri != null) scope.launch {
            lavoro = "Salvo il backup…"
            runCatching {
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")!!.use { BackupLocale.esporta(stato.archivio, it) } }
            }.onSuccess { avviso("Backup salvato con ${stato.archivio.note.value.size} note") }
                .onFailure { avviso("Backup non riuscito: ${it.message}") }
            lavoro = null
        }
    }
    val apri = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> daRipristinare = uri }
    val collega = rememberAccessoDrive { r -> r.onSuccess { avviso("Google Drive collegato") }.onFailure { avviso(it.message ?: "Collegamento non riuscito") } }

    Text("Salva tutte le note in un unico file .tratto, da tenere dove vuoi (anche su Drive o sul PC).",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        Button(onClick = { esporta.launch(BackupLocale.nomeFile()) }, enabled = lavoro == null) {
            Icona(R.drawable.ic_backup, null, dimensione = 18.dp); Spacer(Modifier.width(8.dp)); Text("Esporta backup")
        }
        OutlinedButton(onClick = { apri.launch(arrayOf("*/*")) }, enabled = lavoro == null) {
            Icona(R.drawable.ic_history, null, dimensione = 18.dp); Spacer(Modifier.width(8.dp)); Text("Ripristina da file")
        }
    }
    lavoro?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }

    Spacer(Modifier.height(12.dp))
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
                    lavoro = "Scarico le note da Drive…"
                    runCatching { DriveBackup(context).ripristinaDaDrive(stato.archivio) }
                        .onSuccess { avviso("Note ripristinate da Drive"); stato.versioneAnteprime++ }
                        .onFailure { avviso(it.message ?: "Ripristino non riuscito") }
                    lavoro = null
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

    daRipristinare?.let { uri ->
        AlertDialog(
            onDismissRequest = { daRipristinare = null },
            title = { Text("Ripristinare il backup?") },
            text = {
                Text("«Unisci» aggiunge le note del backup a quelle che hai: se una nota esiste in entrambi, tiene la versione piu' recente e conserva l'altra come copia.\n\n«Sostituisci» cancella le note attuali e mette quelle del backup.")
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    listOf(ModoRipristino.UNISCI to "Unisci", ModoRipristino.SOSTITUISCI to "Sostituisci").forEach { (modo, nome) ->
                        TextButton(onClick = {
                            daRipristinare = null
                            scope.launch {
                                lavoro = "Ripristino…"
                                runCatching {
                                    withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { BackupLocale.ripristina(stato.archivio, it, modo) } }
                                }.onSuccess { r ->
                                    avviso("Ripristino completato: ${r.aggiunte} note aggiunte, ${r.aggiornate} aggiornate" + if (r.copie > 0) ", ${r.copie} copie" else "")
                                    stato.versioneAnteprime++
                                }.onFailure { avviso(it.message ?: "Ripristino non riuscito") }
                                lavoro = null
                            }
                        }) { Text(nome, color = if (modo == ModoRipristino.SOSTITUISCI) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
                    }
                }
            },
            dismissButton = { TextButton({ daRipristinare = null }) { Text("Annulla") } },
        )
    }
}

@Composable
private fun Interruttore(testo: String, valore: Boolean, onCambio: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(testo, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(valore, onCambio)
    }
}
