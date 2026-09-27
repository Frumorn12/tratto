package it.frumorn.tratto.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import it.frumorn.tratto.ui.editor.SchermataEditor
import it.frumorn.tratto.ui.editor.SessioneEditor
import it.frumorn.tratto.ui.impostazioni.Impostazioni
import it.frumorn.tratto.ui.libreria.Libreria
import it.frumorn.tratto.ui.theme.TrattoMotion

/**
 * Radice dell'interfaccia. Il passaggio libreria <-> nota e' una "trasformazione del contenitore":
 * la scheda della nota (o il pulsante Nuova nota) si allarga fino a diventare l'editor e, chiudendo,
 * l'editor si restringe dentro la sua scheda.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun App(stato: StatoApp, alSessione: (SessioneEditor?) -> Unit) {
    SharedTransitionLayout {
        AnimatedContent(
            targetState = stato.schermata,
            contentKey = { if (it is Schermata.Editor) "editor-${it.id}" else it.toString() },
            transitionSpec = {
                val verso = targetState
                val da = initialState
                when {
                    verso is Schermata.Impostazioni ->
                        (slideInHorizontally(tween(TrattoMotion.ENTER_MS, easing = TrattoMotion.Enter)) { it / 4 } + fadeIn(tween(TrattoMotion.ENTER_MS))) togetherWith fadeOut(tween(TrattoMotion.EXIT_MS))
                    da is Schermata.Impostazioni ->
                        fadeIn(tween(TrattoMotion.ENTER_MS)) togetherWith (slideOutHorizontally(tween(TrattoMotion.EXIT_MS, easing = TrattoMotion.Exit)) { it / 4 } + fadeOut(tween(TrattoMotion.EXIT_MS)))
                    verso is Schermata.Editor && da is Schermata.Editor ->
                        // Nota nuova aperta dalla penna mentre se ne scrive un'altra: sale dal basso.
                        (fadeIn(tween(TrattoMotion.ENTER_MS)) + scaleIn(tween(TrattoMotion.ENTER_MS, easing = TrattoMotion.Enter), initialScale = 0.92f)) togetherWith
                            (fadeOut(tween(TrattoMotion.EXIT_MS)) + scaleOut(tween(TrattoMotion.EXIT_MS), targetScale = 1.04f))
                    else -> fadeIn(tween(TrattoMotion.ENTER_MS, delayMillis = 40)) togetherWith fadeOut(tween(TrattoMotion.EXIT_MS + 60))
                }
            },
            label = "schermata",
        ) { s ->
            when (s) {
                Schermata.Libreria -> Libreria(stato, this)
                Schermata.Impostazioni -> Impostazioni(stato)
                is Schermata.Editor -> {
                    val context = LocalContext.current
                    val sessione = remember(s.id) { SessioneEditor(stato, s.id, context).also { it.apri() } }
                    DisposableEffect(sessione) {
                        alSessione(sessione)
                        onDispose { sessione.chiudi(); alSessione(null) }
                    }
                    SchermataEditor(stato, s, sessione, this)
                }
            }
        }
    }
    LaunchedEffect(Unit) { if (!stato.indiceCaricato) stato.caricaIndice() }
}
