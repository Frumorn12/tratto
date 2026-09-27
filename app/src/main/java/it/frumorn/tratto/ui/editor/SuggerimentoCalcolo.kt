package it.frumorn.tratto.ui.editor

import android.content.Context
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.frumorn.tratto.R
import it.frumorn.tratto.calcolo.Calcolatore
import it.frumorn.tratto.calcolo.Suggerimento
import it.frumorn.tratto.data.Preferenze
import it.frumorn.tratto.data.Tratto
import it.frumorn.tratto.editor.EditorView
import it.frumorn.tratto.editor.PaginaViva
import it.frumorn.tratto.scrittura.Trascrittore
import it.frumorn.tratto.ui.Icona
import it.frumorn.tratto.ui.theme.TrattoMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Il risultato proposto mentre si scrive un calcolo: dopo una breve pausa nella scrittura, se la
 * riga dell'ultimo tratto e' una formula, accanto compare "= risultato". Toccandolo lo si scrive a
 * mano; appena la penna torna sul foglio o le pagine si spostano, sparisce.
 */
class SuggerimentoCalcolo(private val contesto: Context, private val preferenze: Preferenze, private val scope: CoroutineScope) {
    class Proposta(val pagina: PaginaViva, val suggerimento: Suggerimento)

    var proposta by mutableStateOf<Proposta?>(null)
        private set
    private var lavoro: Job? = null

    fun nascondi() {
        lavoro?.cancel()
        lavoro = null
        proposta = null
    }

    /** Da chiamare dopo un tratto che non ha chiuso un calcolo. */
    fun dopoTratto(p: PaginaViva, ultimo: Tratto) {
        nascondi()
        if (!Trascrittore.DISPONIBILE || !preferenze.calcoliAutomatici) return
        lavoro = scope.launch {
            delay(ATTESA_MS)
            val tutti = synchronized(p) { ArrayList(p.tratti) }
            if (tutti.none { it.id == ultimo.id }) return@launch
            val s = withContext(Dispatchers.Default) {
                runCatching { Calcolatore.suggerisci(contesto, tutti, ultimo) }.getOrNull()
            } ?: return@launch
            proposta = Proposta(p, s)
        }
    }

    /** I tratti da aggiungere alla pagina della proposta accettata, o null se non c'e' piu'. */
    fun accetta(idNuovo: () -> Long): Pair<PaginaViva, List<Tratto>>? {
        val p = proposta ?: return null
        nascondi()
        return p.pagina to Calcolatore.scriviSuggerimento(p.suggerimento, preferenze.stileBellaScrittura, idNuovo)
    }

    companion object {
        /** Pausa dopo l'ultimo tratto: scrivendo di seguito non si chiama mai il riconoscimento. */
        const val ATTESA_MS = 900L
    }
}

/**
 * L'etichetta del suggerimento, sopra la superficie di scrittura [vista] (da mettere in un Box
 * grande quanto la vista): subito a destra della formula, centrata sulla sua riga.
 */
@Composable
fun EtichettaSuggerimento(s: SuggerimentoCalcolo, vista: EditorView, onScrivi: () -> Unit) {
    val proposta = s.proposta ?: return
    val indice = vista.documento?.pagine?.indexOfFirst { it === proposta.pagina } ?: -1
    if (indice < 0) return
    val sug = proposta.suggerimento
    val foglio = vista.foglio
    val x = foglio.tx + (sug.fine + 0.35f * sug.altezza) * foglio.scala
    val y = foglio.ty + (foglio.cimaPagina(indice) + sug.asse) * foglio.scala
    Box(Modifier.fillMaxSize()) {
        key(proposta) {
            val comparsa = remember { MutableTransitionState(false).apply { targetState = true } }
            androidx.compose.animation.AnimatedVisibility(
                visibleState = comparsa,
                enter = fadeIn(tween(160)) + scaleIn(tween(TrattoMotion.ENTER_MS, easing = TrattoMotion.Enter), initialScale = 0.8f, transformOrigin = TransformOrigin(0f, 0.5f)),
                modifier = Modifier.layout { misurabile, vincoli ->
                    val p = misurabile.measure(vincoli.copy(minWidth = 0, minHeight = 0))
                    layout(p.width, p.height) { p.place(x.roundToInt(), (y - p.height / 2f).roundToInt()) }
                },
            ) {
                val testo = if (sug.conUguale) sug.valore else "= ${sug.valore}"
                Surface(
                    onClick = onScrivi,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shadowElevation = 3.dp,
                    modifier = Modifier.semantics { contentDescription = "${sug.espressione} = ${sug.valore}: tocca per scriverlo" },
                ) {
                    Row(Modifier.padding(start = 14.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(testo, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                        Spacer(Modifier.width(8.dp))
                        Icona(R.drawable.ic_draw, null, dimensione = 18.dp)
                    }
                }
            }
        }
    }
}
