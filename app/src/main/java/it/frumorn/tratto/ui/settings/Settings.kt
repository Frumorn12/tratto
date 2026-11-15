package it.frumorn.tratto.ui.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import it.frumorn.tratto.R
import it.frumorn.tratto.data.DoppioClic
import it.frumorn.tratto.data.Sfondo
import it.frumorn.tratto.handwriting.Transcriber
import it.frumorn.tratto.ui.BottoneIcona
import it.frumorn.tratto.ui.Language
import it.frumorn.tratto.ui.Lingue
import it.frumorn.tratto.ui.Schermata
import it.frumorn.tratto.ui.AppState
import it.frumorn.tratto.ui.testo
import it.frumorn.tratto.ui.testoOra
import it.frumorn.tratto.ui.theme.Eyebrow
import it.frumorn.tratto.ui.theme.Tema

@Composable
fun Settings(stato: AppState) {
    val p = stato.preferenze
    val context = LocalContext.current
    var importaNoteshelf by remember { mutableStateOf(false) }
    val noteshelf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importaNoteshelf = true
        stato.importaNoteshelf(uri) { risultato ->
            importaNoteshelf = false
            val messaggio = risultato.fold(
                onSuccess = { note ->
                    when (note.size) {
                        1 -> testoOra(R.string.imported_at_the_root, note[0].titolo)
                        else -> testoOra(R.string.notes_imported_at_the_root_one_per_page, note.size)
                    }
                },
                onFailure = { testoOra(R.string.this_noteshelf_file_could_not_be_read) },
            )
            Toast.makeText(context, messaggio, Toast.LENGTH_LONG).show()
        }
    }
    BackHandler { stato.schermata = Schermata.Library }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BottoneIcona(R.drawable.ic_arrow_back, testo(R.string.back_to_notes)) { stato.schermata = Schermata.Library }
            Spacer(Modifier.width(8.dp))
            Column {
                Text("TRATTO", style = Eyebrow, color = MaterialTheme.colorScheme.primary)
                Text(testo(R.string.settings), style = MaterialTheme.typography.headlineLarge)
            }
        }
        Column(Modifier.widthIn(max = 640.dp).padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Sezione(testo(R.string.stylus))
            Voce(testo(R.string.pressure_sensitivity), testo(R.string.how_much_the_thickness_changes_when_you_press_ha)) {
                Scelta(listOf(0.6f to testo(R.string.light), 1f to testo(R.string.medium), 1.5f to testo(R.string.firm)), p.sensibilita) { stato.impostaSensibilita(it) }
            }
            Voce(testo(R.string.double_click_the_stylus_button), testo(R.string.two_short_clicks_stylus_near_the_screen_a_long_p)) {
                Scelta(listOf(DoppioClic.NUOVA_NOTA to testo(R.string.new_note), DoppioClic.GOMMA to testo(R.string.eraser), DoppioClic.NIENTE to testo(R.string.nothing)), p.doppioClic) { p.impostaDoppioClic(it) }
            }
            // Tutte e due servono solo con il riconoscimento della scrittura, che la versione libera non ha.
            if (Transcriber.DISPONIBILE) {
                Voce(testo(R.string.neat_handwriting), testo(R.string.the_style_used_to_rewrite_the_transcribed_text_b)) {
                    Scelta(listOf(it.frumorn.tratto.handwriting.Stile.CORSIVO to testo(R.string.cursive), it.frumorn.tratto.handwriting.Stile.STAMPATELLO to testo(R.string.print)), p.stileBellaScrittura) { p.impostaStileBellaScrittura(it) }
                }
                VoceInterruttore(testo(R.string.automatic_calculations), testo(R.string.write_an_expression_and_then_tratto_writes_the_r), p.calcoliAutomatici) { p.impostaCalcoliAutomatici(it) }
            }
            VoceInterruttore(testo(R.string.also_write_with_your_finger), testo(R.string.when_off_a_finger_only_scrolls_and_zooms), p.disegnaConDita) { p.impostaDita(it) }
            VoceInterruttore(
                testo(R.string.steady_brightness_while_writing),
                testo(R.string.with_automatic_brightness_the_shadow_of_your_han),
                p.luceStabile,
            ) { p.impostaLuceStabile(it) }

            Sezione(testo(R.string.quick_note))
            QuickNoteSection()

            Sezione(testo(R.string.pages_2))
            Voce(testo(R.string.page_for_new_notes), null) {
                Scelta(listOf(Sfondo.BIANCO to testo(R.string.blank), Sfondo.RIGHE to testo(R.string.ruled), Sfondo.QUADRETTI to testo(R.string.grid), Sfondo.PUNTINI to testo(R.string.dotted)), p.sfondoPredefinito) { p.impostaSfondo(it) }
            }

            Sezione(testo(R.string.appearance))
            Voce(testo(R.string.language), testo(R.string.french_italian_or_english_matching_the_device_an)) {
                Liste(
                    listOf(Language.SISTEMA to testo(R.string.system), Language.FR to "Français", Language.IT to "Italiano", Language.EN to "English"),
                    Lingue.scelta(context),
                ) { Lingue.applica(context, it) }
            }
            Voce(testo(R.string.theme), null) {
                Scelta(listOf(Tema.SISTEMA to testo(R.string.match_system), Tema.CHIARO to testo(R.string.light_2), Tema.SCURO to testo(R.string.dark)), p.tema) { p.impostaTema(it) }
            }
            VoceInterruttore(testo(R.string.sharp_corners), testo(R.string.a_square_look_instead_of_rounded_corners), p.spigoloVivo) { p.impostaSpigolo(it) }

            Sezione(testo(R.string.backup))
            BackupSection(stato)

            Sezione(testo(R.string.section_import))
            Voce(
                "Noteshelf",
                testo(R.string.noteshelf_import_hint),
            ) {
                OutlinedButton(onClick = { noteshelf.launch(arrayOf("*/*")) }, enabled = !importaNoteshelf) {
                    Text(
                        if (importaNoteshelf) testo(R.string.importing)
                        else testo(R.string.import_a_noteshelf_file),
                    )
                }
            }

            Sezione(testo(R.string.shortcuts))
            Text(
                testo(R.string.open_note_from_outside_hint),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
private fun Sezione(nome: String) {
    Spacer(Modifier.height(20.dp))
    Text(nome.uppercase(), style = Eyebrow, color = MaterialTheme.colorScheme.primary)
    HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun Voce(titolo: String, sottotitolo: String?, controllo: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(titolo, style = MaterialTheme.typography.titleSmall)
        if (sottotitolo != null) Text(sottotitolo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        controllo()
    }
}

@Composable
private fun VoceInterruttore(titolo: String, sottotitolo: String, valore: Boolean, onCambio: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(titolo, style = MaterialTheme.typography.titleSmall)
            Text(sottotitolo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(valore, onCambio)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Liste(opzioni: List<Pair<T, String>>, corrente: T, onScelta: (T) -> Unit) {
    var aperto by remember { mutableStateOf(false) }
    val nome = opzioni.firstOrNull { it.first == corrente }?.second.orEmpty()
    Box {
        OutlinedButton(onClick = { aperto = true }, modifier = Modifier.widthIn(min = 220.dp)) {
            Text(nome)
            Spacer(Modifier.width(12.dp))
            ExposedDropdownMenuDefaults.TrailingIcon(expanded = aperto)
        }
        DropdownMenu(expanded = aperto, onDismissRequest = { aperto = false }) {
            opzioni.forEach { (v, etichetta) ->
                DropdownMenuItem(text = { Text(etichetta) }, onClick = { onScelta(v); aperto = false })
            }
        }
    }
}

@Composable
private fun <T> Scelta(opzioni: List<Pair<T, String>>, corrente: T, onScelta: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        opzioni.forEachIndexed { i, (v, nome) ->
            SegmentedButton(selected = v == corrente, onClick = { onScelta(v) }, shape = SegmentedButtonDefaults.itemShape(i, opzioni.size)) { Text(nome) }
        }
    }
}
