package it.frumorn.tratto.ui.impostazioni

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import it.frumorn.tratto.R
import it.frumorn.tratto.ingressi.ServizioNotaRapida
import it.frumorn.tratto.notarapida.NotaRapidaActivity
import it.frumorn.tratto.ui.Icona
import it.frumorn.tratto.ui.theme.LocalTrattoColors

/** Nota rapida: cos'e', se la scorciatoia di sistema e' attiva, dove attivarla e un modo per provarla. */
@Composable
fun SezioneNotaRapida() {
    val context = LocalContext.current
    var attivo by remember { mutableStateOf(ServizioNotaRapida.attivo(context)) }
    // Si aggiorna da solo quando si torna dalle impostazioni di sistema.
    DisposableEffect(context) {
        val am = context.getSystemService(AccessibilityManager::class.java)
        val ascolto = AccessibilityManager.AccessibilityServicesStateChangeListener { attivo = ServizioNotaRapida.attivo(context) }
        am?.addAccessibilityServicesStateChangeListener(context.mainExecutor, ascolto)
        onDispose { am?.removeAccessibilityServicesStateChangeListener(ascolto) }
    }

    Text(
        "Un foglietto per scrivere al volo sopra qualsiasi app. Quello che scrivi finisce tra le note; se lo chiudi senza scrivere non resta niente.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        val colore = if (attivo) LocalTrattoColors.current.success else MaterialTheme.colorScheme.outline
        Box(Modifier.size(10.dp).clip(CircleShape).background(colore))
        Spacer(Modifier.width(10.dp))
        Text(
            if (attivo) "Attiva: la apri con il pulsante Accessibilità o con i tasti del volume" else "Da qualsiasi app: non ancora attiva",
            style = MaterialTheme.typography.titleSmall,
        )
    }
    Text(
        "Per aprirla da qualsiasi app, in Accessibilità attiva «${context.getString(R.string.servizio_nota_rapida)}» e la sua scorciatoia: " +
            "il pulsante Accessibilità (un pulsante mobile sullo schermo) o i due tasti del volume tenuti premuti. Il servizio non legge lo schermo.\n" +
            "Il doppio clic del tasto della penna funziona solo dentro Tratto: nelle altre app Android lo tiene per sé, " +
            "e ascoltare la penna da fuori la bloccherebbe mentre scrivi.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        Button(onClick = { apriAccessibilita(context) }) {
            Icona(R.drawable.ic_settings, null, dimensione = 18.dp); Spacer(Modifier.width(8.dp)); Text("Impostazioni di Accessibilità")
        }
        OutlinedButton(onClick = { NotaRapidaActivity.apri(context) }) {
            Icona(R.drawable.ic_edit_note, null, dimensione = 18.dp); Spacer(Modifier.width(8.dp)); Text("Prova la nota rapida")
        }
    }
}

/**
 * La pagina del servizio, se il sistema la apre anche alle app (Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS
 * non e' pubblica e di solito chiede un permesso di sistema); altrimenti l'elenco di Accessibilita'
 * con la voce di Tratto evidenziata.
 */
private fun apriAccessibilita(context: Context) {
    val nome = ServizioNotaRapida.componente(context).flattenToString()
    val dettaglio = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
        .putExtra(Intent.EXTRA_COMPONENT_NAME, nome)
    val elenco = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        .putExtra(":settings:fragment_args_key", nome)
        .putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", nome) })
    runCatching { context.startActivity(dettaglio) }
        .recoverCatching { context.startActivity(elenco) }
}
