package it.frumorn.tratto.ui.settings

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
import it.frumorn.tratto.shortcuts.QuickNoteService
import it.frumorn.tratto.quicknote.QuickNoteActivity
import it.frumorn.tratto.ui.Icona
import it.frumorn.tratto.ui.testo
import it.frumorn.tratto.ui.theme.LocalTrattoColors

/** Nota rapida: cos'e', se la scorciatoia di sistema e' attiva, dove attivarla e un modo per provarla. */
@Composable
fun QuickNoteSection() {
    val context = LocalContext.current
    var attivo by remember { mutableStateOf(QuickNoteService.attivo(context)) }
    // Si aggiorna da solo quando si torna dalle impostazioni di sistema.
    DisposableEffect(context) {
        val am = context.getSystemService(AccessibilityManager::class.java)
        val ascolto = AccessibilityManager.AccessibilityServicesStateChangeListener { attivo = QuickNoteService.attivo(context) }
        am?.addAccessibilityServicesStateChangeListener(context.mainExecutor, ascolto)
        onDispose { am?.removeAccessibilityServicesStateChangeListener(ascolto) }
    }

    Text(
        testo(R.string.quick_note_blurb),
        style = MaterialTheme.typography.bodyMedium,
    )
    Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        val colore = if (attivo) LocalTrattoColors.current.success else MaterialTheme.colorScheme.outline
        Box(Modifier.size(10.dp).clip(CircleShape).background(colore))
        Spacer(Modifier.width(10.dp))
        Text(
            if (attivo) testo(R.string.on_open_it_with_the_accessibility_button_or_the)
            else testo(R.string.from_any_app_not_turned_on_yet),
            style = MaterialTheme.typography.titleSmall,
        )
    }
    Text(
        testo(
            R.string.quick_note_how_to,
            context.getString(R.string.nota_rapida),
            context.getString(R.string.servizio_nota_rapida),
        ),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        Button(onClick = { apriAccessibilita(context) }) {
            Icona(R.drawable.ic_settings, null, dimensione = 18.dp); Spacer(Modifier.width(8.dp)); Text(testo(R.string.accessibility_settings))
        }
        OutlinedButton(onClick = { QuickNoteActivity.apri(context) }) {
            Icona(R.drawable.ic_edit_note, null, dimensione = 18.dp); Spacer(Modifier.width(8.dp)); Text(testo(R.string.try_the_quick_note))
        }
    }
}

/**
 * La pagina del servizio, se il sistema la apre anche alle app (Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS
 * non e' pubblica e di solito chiede un permesso di sistema); altrimenti l'elenco di Accessibilita'
 * con la voce di Tratto evidenziata.
 */
private fun apriAccessibilita(context: Context) {
    val nome = QuickNoteService.componente(context).flattenToString()
    val dettaglio = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
        .putExtra(Intent.EXTRA_COMPONENT_NAME, nome)
    val elenco = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        .putExtra(":settings:fragment_args_key", nome)
        .putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", nome) })
    runCatching { context.startActivity(dettaglio) }
        .recoverCatching { context.startActivity(elenco) }
}
