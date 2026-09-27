package it.frumorn.tratto.ui.impostazioni

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import it.frumorn.tratto.R
import it.frumorn.tratto.data.DoppioClic
import it.frumorn.tratto.data.Sfondo
import it.frumorn.tratto.ui.BottoneIcona
import it.frumorn.tratto.ui.Schermata
import it.frumorn.tratto.ui.StatoApp
import it.frumorn.tratto.ui.theme.Eyebrow
import it.frumorn.tratto.ui.theme.Tema

@Composable
fun Impostazioni(stato: StatoApp) {
    val p = stato.preferenze
    BackHandler { stato.schermata = Schermata.Libreria }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BottoneIcona(R.drawable.ic_arrow_back, "Torna alle note") { stato.schermata = Schermata.Libreria }
            Spacer(Modifier.width(8.dp))
            Column {
                Text("TRATTO", style = Eyebrow, color = MaterialTheme.colorScheme.primary)
                Text("Impostazioni", style = MaterialTheme.typography.headlineLarge)
            }
        }
        Column(Modifier.widthIn(max = 640.dp).padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Sezione("Penna")
            Voce("Sensibilità alla pressione", "Quanto cambia lo spessore premendo di più o di meno") {
                Scelta(listOf(0.6f to "Leggera", 1f to "Media", 1.5f to "Forte"), p.sensibilita) { stato.impostaSensibilita(it) }
            }
            Voce("Doppio clic sul tasto della penna", "Con la penna vicina allo schermo, dentro Tratto") {
                Scelta(listOf(DoppioClic.NUOVA_NOTA to "Nuova nota", DoppioClic.GOMMA to "Gomma", DoppioClic.NIENTE to "Niente"), p.doppioClic) { p.impostaDoppioClic(it) }
            }
            Voce("Bella scrittura", "Lo stile usato per riscrivere a mano il testo trascritto") {
                Scelta(listOf(it.frumorn.tratto.scrittura.Stile.CORSIVO to "Corsivo", it.frumorn.tratto.scrittura.Stile.STAMPATELLO to "Stampatello"), p.stileBellaScrittura) { p.impostaStileBellaScrittura(it) }
            }
            VoceInterruttore("Scrivi anche con il dito", "Se spento, il dito serve solo a scorrere e zoomare", p.disegnaConDita) { p.impostaDita(it) }

            Sezione("Pagine")
            Voce("Pagina delle note nuove", null) {
                Scelta(listOf(Sfondo.BIANCO to "Bianca", Sfondo.RIGHE to "Righe", Sfondo.QUADRETTI to "Quadretti", Sfondo.PUNTINI to "Puntini"), p.sfondoPredefinito) { p.impostaSfondo(it) }
            }

            Sezione("Aspetto")
            Voce("Tema", null) {
                Scelta(listOf(Tema.SISTEMA to "Come il sistema", Tema.CHIARO to "Chiaro", Tema.SCURO to "Scuro"), p.tema) { p.impostaTema(it) }
            }
            VoceInterruttore("Angoli vivi", "Stile squadrato, invece degli angoli arrotondati", p.spigoloVivo) { p.impostaSpigolo(it) }

            Sezione("Backup")
            SezioneBackup(stato)

            Sezione("Scorciatoie")
            Text(
                "Per aprire una nota nuova da fuori Tratto:\n" +
                    "• aggiungi il riquadro «Nuova nota» nelle impostazioni rapide;\n" +
                    "• tieni premuta l'icona di Tratto e trascina «Nuova nota» sulla Home;\n" +
                    "• in Lawnchair: Impostazioni Home › Gesti › Doppio tocco › App › Tratto (Nuova nota).",
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

@Composable
private fun <T> Scelta(opzioni: List<Pair<T, String>>, corrente: T, onScelta: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        opzioni.forEachIndexed { i, (v, nome) ->
            SegmentedButton(selected = v == corrente, onClick = { onScelta(v) }, shape = SegmentedButtonDefaults.itemShape(i, opzioni.size)) { Text(nome) }
        }
    }
}
