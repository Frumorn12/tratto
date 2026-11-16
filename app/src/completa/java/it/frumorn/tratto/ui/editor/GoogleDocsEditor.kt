package it.frumorn.tratto.ui.editor

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import it.frumorn.tratto.R
import it.frumorn.tratto.ui.testo
import it.frumorn.tratto.ui.testoOra
import it.frumorn.tratto.backup.rememberAccessoDrive
import it.frumorn.tratto.googledocs.FaseDocs
import it.frumorn.tratto.googledocs.GoogleDocs
import it.frumorn.tratto.googledocs.StatoDocs
import it.frumorn.tratto.ui.Icona
import it.frumorn.tratto.ui.theme.LocalTrattoColors
import it.frumorn.tratto.ui.theme.TrattoMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Il collegamento a Google Docs della nota aperta, per la barra dell'editor: lo stato (il
 * pallino accanto al titolo) e le azioni delle voci del menu. Solo nella versione completa: la
 * libera ha una classe con lo stesso nome che non mostra niente.
 */
@Stable
class ControlloDocs internal constructor(
    private val context: Context,
    private val sessione: EditorSession,
    private val scope: CoroutineScope,
    stati: State<Map<String, StatoDocs>>,
) {
    /** Null se la nota non e' collegata. */
    internal val stato: StatoDocs? by derivedStateOf { stati.value[sessione.id] }

    /** Consenso e creazione del documento in corso. */
    internal var collegando by mutableStateOf(false)
        private set

    /** Chiede l'accesso a Google (quello del backup su Drive); l'esito arriva in [esitoAccesso]. */
    internal var accesso: () -> Unit = {}
    private var dopoAccesso: (suspend () -> Unit)? = null

    internal fun collega() {
        if (collegando) return
        collegando = true
        chiediAccesso {
            try {
                // Il documento parte dalla nota com'e' adesso, anche con i tratti degli ultimi secondi.
                sessione.salvaEAttendi()
                GoogleDocs.collega(context, sessione.id)
                avviso(testoOra(R.string.note_linked_to_google_docs_the_document_updates))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                avviso(testoOra(R.string.could_not_link, e.message ?: testoOra(R.string.unknown_error)))
            }
        }
    }

    internal fun apri() {
        val s = stato ?: return
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, GoogleDocs.indirizzo(s.documento).toUri()))
        } catch (_: ActivityNotFoundException) {
            avviso(testoOra(R.string.no_app_to_open_the_document))
        }
    }

    internal fun scollega() {
        scope.launch {
            GoogleDocs.scollega(context, sessione.id)
            avviso(testoOra(R.string.note_unlinked_the_document_stays_on_google_drive))
        }
    }

    /** Tocco sul pallino: se c'e' un problema lo si spiega e si riprova, altrimenti si apre il documento. */
    internal fun tocco() {
        val s = stato ?: return
        when {
            s.serveAccesso -> chiediAccesso { GoogleDocs.sincronizzaOra(context, sessione.id) }
            s.fase == FaseDocs.ERRORE || s.fase == FaseDocs.RIPROVA -> {
                avviso(s.messaggio ?: testoOra(R.string.update_failed_trying_again))
                GoogleDocs.sincronizzaOra(context, sessione.id)
            }
            else -> apri()
        }
    }

    internal fun esitoAccesso(r: Result<Unit>) {
        val poi = dopoAccesso
        dopoAccesso = null
        r.onSuccess {
            if (poi == null) collegando = false
            else scope.launch { try { poi() } finally { collegando = false } }
        }.onFailure {
            collegando = false
            avviso(it.message ?: testoOra(R.string.could_not_access_google))
        }
    }

    private fun chiediAccesso(poi: suspend () -> Unit) {
        dopoAccesso = poi
        accesso()
    }

    private fun avviso(t: String) = Toast.makeText(context, t, Toast.LENGTH_LONG).show()
}

/** Da chiamare nella barra dell'editor, che resta sempre in pagina: tiene aperta anche la schermata del consenso. */
@Composable
fun rememberGoogleDocs(sessione: EditorSession): ControlloDocs {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val stati = remember(context) { GoogleDocs.stati(context) }.collectAsState()
    val controllo = remember(sessione) { ControlloDocs(context, sessione, scope, stati) }
    val accesso = rememberAccessoDrive(conDocumenti = true) { controllo.esitoAccesso(it) }
    SideEffect { controllo.accesso = accesso }
    return controllo
}

/**
 * "Docs" con un pallino accanto al titolo, solo per le note collegate: grigio collegata, viola che
 * pulsa mentre aggiorna, verde aggiornata, ambra se riprovera', rosso se c'e' un errore.
 * Un tocco apre il documento, oppure spiega il problema e riprova.
 */
@Composable
fun ChipGoogleDocs(docs: ControlloDocs) {
    val stato = docs.stato
    AnimatedVisibility(
        visible = stato != null || docs.collegando,
        enter = fadeIn(tween(TrattoMotion.ENTER_MS)) + scaleIn(tween(TrattoMotion.ENTER_MS, easing = TrattoMotion.Enter), initialScale = 0.8f),
        exit = fadeOut(tween(TrattoMotion.EXIT_MS)) + scaleOut(tween(TrattoMotion.EXIT_MS, easing = TrattoMotion.Exit), targetScale = 0.8f),
    ) {
        val fase = if (stato == null) FaseDocs.IN_CORSO else stato.fase
        val extra = LocalTrattoColors.current
        val schema = MaterialTheme.colorScheme
        val colore by animateColorAsState(
            when (fase) {
                FaseDocs.COLLEGATA -> schema.outline
                FaseDocs.IN_ATTESA, FaseDocs.IN_CORSO -> schema.primary
                FaseDocs.AGGIORNATA -> extra.success
                FaseDocs.RIPROVA -> extra.warning
                FaseDocs.ERRORE -> schema.error
            },
            tween(TrattoMotion.ENTER_MS), label = "pallino",
        )
        val pulsa = fase == FaseDocs.IN_CORSO
        val pulsazione = if (pulsa) {
            rememberInfiniteTransition(label = "docs").animateFloat(0.3f, 1f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "alfa")
        } else {
            null
        }
        val alfa = if (fase == FaseDocs.IN_ATTESA) 0.45f else 1f
        val descrizione = "Google Docs: " + when (fase) {
            FaseDocs.COLLEGATA -> testo(R.string.linked_tap_to_open_the_document)
            FaseDocs.IN_ATTESA -> testo(R.string.update_coming_tap_to_open_the_document)
            FaseDocs.IN_CORSO -> testo(R.string.updating_tap_to_open_the_document)
            FaseDocs.AGGIORNATA -> testo(R.string.up_to_date_tap_to_open_the_document)
            FaseDocs.RIPROVA -> testo(R.string.update_postponed_tap_to_try_again)
            FaseDocs.ERRORE -> testo(R.string.error_tap_to_learn_more)
        }
        Surface(
            onClick = { docs.tocco() },
            enabled = stato != null,
            shape = CircleShape,
            color = schema.surfaceContainer,
            border = BorderStroke(1.dp, schema.outlineVariant),
            modifier = Modifier.padding(horizontal = 6.dp).semantics { contentDescription = descrizione },
        ) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(8.dp)
                        .graphicsLayer { this.alpha = pulsazione?.value ?: alfa }
                        .clip(CircleShape)
                        .background(colore),
                )
                Spacer(Modifier.width(6.dp))
                Text("Docs", style = MaterialTheme.typography.labelMedium, color = schema.onSurfaceVariant)
            }
        }
    }
}

/** Le voci di Google Docs nel menu dell'editor. */
@Composable
fun VociGoogleDocs(docs: ControlloDocs, chiudiMenu: () -> Unit) {
    HorizontalDivider()
    Text("GOOGLE DOCS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    if (docs.stato == null) {
        DropdownMenuItem(
            text = { Text(testo(R.string.link_to_google_docs)) }, leadingIcon = { Icona(R.drawable.ic_link, null) }, enabled = !docs.collegando,
            onClick = { chiudiMenu(); docs.collega() },
        )
    } else {
        DropdownMenuItem(
            text = { Text(testo(R.string.open_in_google_docs)) }, leadingIcon = { Icona(R.drawable.ic_description, null) },
            onClick = { chiudiMenu(); docs.apri() },
        )
        DropdownMenuItem(
            text = { Text(testo(R.string.unlink_from_google_docs)) }, leadingIcon = { Icona(R.drawable.ic_link_off, null) },
            onClick = { chiudiMenu(); docs.scollega() },
        )
    }
}
